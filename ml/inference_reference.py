"""
울림 위험음 감지 — Pi/PC 공용 참조 구현

모델(woollim_sound.tflite) 추론 + 후처리(스무딩·접근/멀어짐·위험 단계)를 거쳐
앱 프로토콜 JSON 한 줄을 만든다. 팀원은 `emit()` 자리를 BLE Notify 전송으로 바꾸면 된다.

앱 프로토콜 (android/.../model/HazardEvent.kt 와 동일):
  {"v":1,"id":42,"cls":"siren","lvl":"urgent","conf":0.94,
   "trend":"approach","dur_ms":2300,"t_pi":1732412345678}

사용 예:
  python inference_reference.py test_audio/siren_01.wav          # 파일 → 이벤트 JSON
  python inference_reference.py test_audio/siren_01.wav --probs  # 창별 확률도 출력
  python inference_reference.py --check test_audio               # 기대 출력과 비교 (Pi 이식 확인)
  python inference_reference.py --mic                            # 실시간 마이크
  python inference_reference.py --bench                          # 추론 속도 측정

필요 패키지: numpy, soundfile(파일 입력), sounddevice(마이크 입력),
            TFLite 런타임 중 하나: ai-edge-litert | tflite-runtime | tensorflow
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
PROTOCOL_VERSION = 1


# ─────────────────────────── 설정 ───────────────────────────

@dataclass
class Config:
    sample_rate: int = 16000
    window: int = 15600          # 0.975 s (YAMNet 1프레임)
    hop: int = 8000              # 0.5 s
    labels: tuple = ("siren", "horn")
    thresholds: dict = field(default_factory=lambda: {"siren": 0.5, "horn": 0.5})

    # 스무딩: 최근 N창 중 K창 이상 임계 초과 → 이벤트 시작, N창 모두 미만 → 종료
    smooth_n: int = 3
    smooth_k: int = 2

    # 접근/멀어짐: 감지 구간 RMS(dB) 기울기 (dB/s)
    trend_slope_db_s: float = 1.5
    trend_min_windows: int = 3
    trend_max_windows: int = 8

    # 위험 단계 규칙
    urgent_min_conf: float = 0.8

    @classmethod
    def load(cls, path: Path) -> "Config":
        c = cls()
        if path.exists():
            d = json.loads(path.read_text(encoding="utf-8"))
            c.sample_rate = d.get("sample_rate", c.sample_rate)
            c.window = d.get("window", c.window)
            c.hop = d.get("hop", c.hop)
            c.labels = tuple(d.get("labels", c.labels))
            c.thresholds = {**c.thresholds, **d.get("thresholds", {})}
        return c


# ─────────────────────────── 모델 ───────────────────────────

def _load_interpreter_class():
    try:
        from ai_edge_litert.interpreter import Interpreter  # 최신 (pip install ai-edge-litert)
        return Interpreter
    except ImportError:
        pass
    try:
        from tflite_runtime.interpreter import Interpreter  # 구버전 Pi (pip install tflite-runtime)
        return Interpreter
    except ImportError:
        pass
    try:
        import tensorflow as tf
        return tf.lite.Interpreter
    except ImportError:
        sys.exit("TFLite 런타임이 없습니다: pip install ai-edge-litert (또는 tflite-runtime)")


class SoundModel:
    """입력: float32 [window] (16 kHz 모노, -1..1) → 출력: float32 [len(labels)] (sigmoid 확률)"""

    def __init__(self, model_path: Path, num_threads: int = 2):
        Interpreter = _load_interpreter_class()
        self.it = Interpreter(model_path=str(model_path), num_threads=num_threads)
        self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]["index"]
        self.out = self.it.get_output_details()[0]["index"]

    def __call__(self, window: np.ndarray) -> np.ndarray:
        self.it.set_tensor(self.inp, window.astype(np.float32))
        self.it.invoke()
        return np.asarray(self.it.get_tensor(self.out), dtype=np.float32).reshape(-1)


# ─────────────────────────── 후처리 ───────────────────────────

LEVEL_RANK = {"log": 0, "caution": 1, "urgent": 2}


def rms_db(x: np.ndarray) -> float:
    return float(20 * np.log10(np.sqrt(np.mean(np.square(x, dtype=np.float64))) + 1e-10))


@dataclass
class _Track:
    active: bool = False
    hits: deque = field(default_factory=deque)
    windows: int = 0                 # 이벤트 시작 후 창 수
    probs: list = field(default_factory=list)
    levels_db: list = field(default_factory=list)
    emitted_level: str | None = None


class HazardDetector:
    """창 단위 확률 → 앱 이벤트. 클래스(siren/horn)별로 독립 추적한다."""

    def __init__(self, cfg: Config, start_id: int = 1):
        self.cfg = cfg
        self.next_id = start_id
        self.speed_kmh: int | None = None   # 앱 → Pi 속도 메시지로 갱신 (없으면 규칙에 안 씀)
        self.tracks = {lab: _Track(hits=deque(maxlen=cfg.smooth_n)) for lab in cfg.labels}
        self.recent = deque(maxlen=cfg.smooth_n)   # 최근 창들의 (확률, dB) — 시작 판정 구간 복원용

    # 앱이 보낸 {"v":1,"speed_kmh":..,"t_app":..} 처리
    def on_app_message(self, raw: str) -> None:
        try:
            d = json.loads(raw)
            if d.get("v") == PROTOCOL_VERSION and "speed_kmh" in d:
                self.speed_kmh = int(d["speed_kmh"])
        except (ValueError, TypeError):
            pass

    def push(self, probs: np.ndarray, level_db: float, t_ms: int) -> list[dict]:
        """창 하나 처리. 새로 보낼 이벤트(0개 이상)를 반환."""
        out = []
        self.recent.append((np.asarray(probs, dtype=np.float32), level_db))
        for i, lab in enumerate(self.cfg.labels):
            p = float(probs[i])
            tr = self.tracks[lab]
            tr.hits.append(p >= self.cfg.thresholds[lab])
            n_hit = sum(tr.hits)

            if not tr.active:
                if n_hit >= self.cfg.smooth_k:
                    tr.active = True
                    # 시작 판정에 쓰인 창들(첫 초과 창부터)도 구간에 포함 → 접근 여부를 더 빨리 판단
                    first = list(tr.hits).index(True)
                    seed = list(self.recent)[first:]
                    tr.windows = len(seed)
                    tr.probs = [float(pp[i]) for pp, _ in seed]
                    tr.levels_db = [db for _, db in seed]
                    tr.emitted_level = None
                    out += self._maybe_emit(lab, tr, t_ms)
            else:
                if n_hit == 0 and len(tr.hits) == self.cfg.smooth_n:
                    tr.active = False          # 이벤트 종료
                    continue
                tr.windows += 1
                tr.probs.append(p)
                tr.levels_db.append(level_db)
                out += self._maybe_emit(lab, tr, t_ms)
        return out

    def _trend(self, tr: _Track) -> str:
        ys = tr.levels_db[-self.cfg.trend_max_windows:]
        if len(ys) < self.cfg.trend_min_windows:
            return "unknown"
        hop_s = self.cfg.hop / self.cfg.sample_rate
        xs = np.arange(len(ys)) * hop_s
        slope = float(np.polyfit(xs, ys, 1)[0])   # dB/s
        if slope >= self.cfg.trend_slope_db_s:
            return "approach"
        if slope <= -self.cfg.trend_slope_db_s:
            return "recede"
        return "steady"

    def _level(self, cls: str, conf: float, trend: str) -> str:
        if cls == "siren":
            if trend == "recede":
                lvl = "log"
            elif trend == "approach" and conf >= self.cfg.urgent_min_conf:
                lvl = "urgent"
            else:
                lvl = "caution"
        else:  # horn: 대개 가까운 차가 나를 향해 울리는 소리
            lvl = "log" if trend == "recede" else "caution"
        # 정차 중이면 긴급까지 올리지 않는다 (속도 정보가 있을 때만)
        if self.speed_kmh == 0 and lvl == "urgent":
            lvl = "caution"
        return lvl

    def _maybe_emit(self, cls: str, tr: _Track, t_ms: int) -> list[dict]:
        """이벤트 시작 시 1번, 이후 위험 단계가 올라갈 때만 다시 보낸다."""
        conf = float(np.mean(tr.probs[-3:]))
        trend = self._trend(tr)
        lvl = self._level(cls, conf, trend)
        if tr.emitted_level is not None and LEVEL_RANK[lvl] <= LEVEL_RANK[tr.emitted_level]:
            return []
        tr.emitted_level = lvl
        dur_ms = int(round((self.cfg.window + (tr.windows - 1) * self.cfg.hop) * 1000 / self.cfg.sample_rate))
        ev = {
            "v": PROTOCOL_VERSION,
            "id": self.next_id,
            "cls": cls,
            "lvl": lvl,
            "conf": round(conf, 3),
            "trend": trend,
            "dur_ms": dur_ms,
            "t_pi": t_ms,
        }
        self.next_id += 1
        return [ev]


def input_level(level_db: float) -> float:
    """앱 홈 화면 '마이크 입력' 막대용 0..1 (-60 dBFS → 0, 0 dBFS → 1)"""
    return float(np.clip((level_db + 60.0) / 60.0, 0.0, 1.0))


# ─────────────────────────── 입출력 ───────────────────────────

def emit(event: dict) -> None:
    """★ 팀원 통합 지점: 이 JSON 문자열을 BLE Notify(이벤트 characteristic)로 보내면 된다."""
    print(json.dumps(event, ensure_ascii=False, separators=(",", ":")), flush=True)


def load_wav(path: Path, sr: int) -> np.ndarray:
    import soundfile as sf
    y, file_sr = sf.read(str(path), dtype="float32", always_2d=True)
    y = y.mean(axis=1)
    if file_sr != sr:
        from math import gcd
        from scipy.signal import resample_poly
        g = gcd(sr, file_sr)
        y = resample_poly(y, sr // g, file_sr // g).astype(np.float32)
    return y


def iter_windows(y: np.ndarray, win: int, hop: int):
    if len(y) < win:
        y = np.pad(y, (0, win - len(y)))
    n = 1 + int(np.ceil((len(y) - win) / hop))
    y = np.pad(y, (0, (n - 1) * hop + win - len(y)))
    for i in range(n):
        yield i, y[i * hop: i * hop + win]


def window_probs(model: SoundModel, y: np.ndarray, cfg: Config) -> np.ndarray:
    return np.stack([model(w) for _, w in iter_windows(y, cfg.window, cfg.hop)])


def run_file(model: SoundModel, path: Path, cfg: Config, show_probs: bool) -> None:
    y = load_wav(path, cfg.sample_rate)
    det = HazardDetector(cfg)
    hop_ms = cfg.hop * 1000 // cfg.sample_rate
    for i, w in iter_windows(y, cfg.window, cfg.hop):
        p = model(w)
        db = rms_db(w)
        if show_probs:
            pretty = " ".join(f"{lab}={p[j]:.2f}" for j, lab in enumerate(cfg.labels))
            print(f"# t={i * hop_ms / 1000:5.1f}s  {pretty}  rms={db:6.1f}dB", file=sys.stderr)
        for ev in det.push(p, db, t_ms=i * hop_ms):   # 파일 모드: t_pi = 파일 내 시각(ms)
            emit(ev)


def run_check(model: SoundModel, folder: Path, cfg: Config) -> int:
    """노트북이 만든 expected.json 과 창별 확률 비교 → Pi 이식(전처리·런타임)이 맞는지 확인"""
    expected = json.loads((folder / "expected.json").read_text(encoding="utf-8"))
    worst = 0.0
    for name, exp in expected.items():
        got = window_probs(model, load_wav(folder / name, cfg.sample_rate), cfg)
        exp = np.asarray(exp, dtype=np.float32)
        if got.shape != exp.shape:
            print(f"FAIL {name}: 창 개수 다름 {got.shape} vs {exp.shape}")
            return 1
        d = float(np.max(np.abs(got - exp)))
        worst = max(worst, d)
        print(f"{'OK  ' if d < 1e-3 else 'FAIL'} {name}: 최대 차이 {d:.5f}")
    print(f"최대 차이 {worst:.5f} → {'통과' if worst < 1e-3 else '불일치: 샘플레이트·정규화(-1..1)·창 크기 확인'}")
    return 0 if worst < 1e-3 else 1


def run_mic(model: SoundModel, cfg: Config, device) -> None:
    import queue
    import sounddevice as sd

    q: "queue.Queue[np.ndarray]" = queue.Queue()

    def cb(indata, frames, t, status):
        if status:
            print(f"# {status}", file=sys.stderr)
        q.put(indata[:, 0].copy())

    buf = np.zeros(cfg.window, dtype=np.float32)
    det = HazardDetector(cfg)
    print("# 마이크 입력 중… (Ctrl+C 종료)", file=sys.stderr)
    with sd.InputStream(samplerate=cfg.sample_rate, channels=1, dtype="float32",
                        blocksize=cfg.hop, device=device, callback=cb):
        while True:
            block = q.get()
            buf = np.concatenate([buf[len(block):], block])
            db = rms_db(buf)
            # 팀원: input_level(db) 를 레벨 characteristic 으로 보내면 앱 홈 화면 막대가 움직인다
            for ev in det.push(model(buf), db, t_ms=int(time.time() * 1000)):
                emit(ev)


def run_bench(model: SoundModel, cfg: Config, n: int = 50) -> None:
    w = (np.random.default_rng(0).standard_normal(cfg.window) * 0.1).astype(np.float32)
    model(w)  # warm-up
    t0 = time.perf_counter()
    for _ in range(n):
        model(w)
    ms = (time.perf_counter() - t0) * 1000 / n
    budget = cfg.hop * 1000 / cfg.sample_rate
    print(f"창당 평균 {ms:.1f} ms (실시간 여유: hop {budget:.0f} ms 대비 {budget / ms:.1f}배)")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("wav", nargs="?", type=Path, help="입력 wav 파일")
    ap.add_argument("--model", type=Path, default=HERE / "woollim_sound.tflite")
    ap.add_argument("--config", type=Path, default=HERE / "model_config.json")
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--probs", action="store_true", help="창별 확률을 stderr로 출력")
    ap.add_argument("--check", type=Path, metavar="DIR", help="DIR/expected.json 과 비교")
    ap.add_argument("--mic", action="store_true")
    ap.add_argument("--device", default=None, help="sounddevice 입력 장치 번호/이름")
    ap.add_argument("--bench", action="store_true")
    a = ap.parse_args()

    cfg = Config.load(a.config)
    model = SoundModel(a.model, a.threads)
    if a.bench:
        run_bench(model, cfg)
    elif a.check:
        sys.exit(run_check(model, a.check, cfg))
    elif a.mic:
        run_mic(model, cfg, a.device)
    elif a.wav:
        run_file(model, a.wav, cfg, a.probs)
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
