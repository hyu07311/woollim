"""
울림 감지 장치 실행 프로그램 (Raspberry Pi 4)

  python main.py                 # 마이크 + 모델 → BLE로 앱에 경고 전송
  python main.py --fake          # 모델·마이크 없이 정해진 시나리오를 BLE로 전송 (연결 확인·시연 대체용)

모델 추론과 후처리는 ml/inference_reference.py 를 그대로 쓴다 (Config, SoundModel, HazardDetector).
기본 모델은 ml/v1 (팀원 최종 모델). v0으로 돌리려면 --config ../ml/model_config.json
"""
from __future__ import annotations

import argparse
import asyncio
import csv
import logging
import random
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ML = HERE.parent / "ml"
LOG_DIR = HERE / "logs"   # 실행 기록 CSV (git에는 올리지 않음)
sys.path.insert(0, str(ML))

from woollim_ble import WoollimBle  # noqa: E402

PROTOCOL_VERSION = 1  # android/.../model/HazardEvent.kt 와 동일
LEVEL_HZ = 10

log = logging.getLogger("woollim")


# ─────────────────────────── --fake ───────────────────────────

# (클래스, 단계, 추세, 신뢰도, 직전 이벤트 후 대기 초)
FAKE_SCENARIO = [
    ("horn", "caution", "steady", 0.81, 0),
    ("siren", "caution", "approach", 0.79, 1),
    ("siren", "urgent", "approach", 0.93, 2),   # 같은 사이렌이 가까워지며 긴급으로 상향
    ("siren", "log", "recede", 0.84, 1),        # 멀어지면 기록만
]


async def run_fake(ble: WoollimBle, interval: float) -> None:
    loud_until = 0.0

    async def level_task():
        base = 0.15
        while True:
            base = min(max(base + random.uniform(-0.03, 0.03), 0.08), 0.3)
            loud = time.monotonic() < loud_until
            ble.send_level(random.uniform(0.7, 1.0) if loud else base)
            await asyncio.sleep(1 / LEVEL_HZ)

    lt = asyncio.create_task(level_task())
    next_id = 1
    log.info("가짜 시나리오 시작: %d초마다 반복 (Ctrl+C 종료)", interval)
    try:
        while True:
            await asyncio.sleep(interval)
            for cls, lvl, trend, conf, wait in FAKE_SCENARIO:
                await asyncio.sleep(wait)
                loud_until = time.monotonic() + 2.5
                ble.send_event({
                    "v": PROTOCOL_VERSION, "id": next_id, "cls": cls, "lvl": lvl,
                    "conf": conf, "trend": trend, "dur_ms": 2000,
                    "t_pi": int(time.time() * 1000),
                })
                next_id += 1
    finally:
        lt.cancel()


# ─────────────────────────── 마이크 ───────────────────────────

async def run_mic(ble: WoollimBle, args) -> None:
    import numpy as np
    import sounddevice as sd
    from inference_reference import (Config, HazardDetector, SoundModel, activity_db, input_level,
                                     rms_db)

    cfg = Config.load(args.config)
    model_path = args.model or cfg.model or ML / "woollim_sound.tflite"
    model = SoundModel(model_path, args.threads, cfg)
    det = HazardDetector(cfg)
    loop = asyncio.get_running_loop()
    q: asyncio.Queue = asyncio.Queue()
    sr = cfg.sample_rate

    block = cfg.hop // 5  # 0.1 s 블록 → 레벨 10 Hz, 5블록(= hop 0.5 s)마다 추론

    def cb(indata, frames, t, status):
        if status:
            print(f"# {status}", file=sys.stderr)
        loop.call_soon_threadsafe(q.put_nowait, indata[:, 0].copy())

    use_gate = cfg.gate_enabled and not args.no_gate
    calib_left = int(cfg.calibrate_sec * sr) if use_gate else 0
    calib: list = []
    gate_db = cfg.gate_floor_db

    # 창별 기록 CSV (오디오는 저장하지 않고 숫자만 남긴다)
    log_f = writer = None
    if not args.no_log:
        LOG_DIR.mkdir(exist_ok=True)
        log_path = LOG_DIR / time.strftime("%Y%m%d_%H%M%S.csv")
        log_f = open(log_path, "w", newline="", encoding="utf-8-sig")  # 엑셀에서 한글이 깨지지 않게 BOM
        writer = csv.writer(log_f)
        writer.writerow(["time", "t_sec", *cfg.labels, "activity_db", "gate_db", "silent",
                         "rms_db", "infer_ms", "events"])
        log.info("기록 파일: %s", log_path)
    t0 = time.time()

    buf = np.zeros(cfg.window, dtype=np.float32)
    since_infer = 0
    log.info("모델: %s (창 %.2f s)", Path(model_path).name, cfg.window / sr)
    log.info("마이크 입력 시작 (장치: %s). Ctrl+C 종료", args.device)
    if calib_left:
        log.info("주변 소음 측정 중 (%.0f초) - 조용히 기다려 주세요", cfg.calibrate_sec)
    try:
        with sd.InputStream(samplerate=sr, channels=1, dtype="float32",
                            blocksize=block, device=args.device, callback=cb):
            while True:
                b = await q.get()
                buf = np.concatenate([buf[len(b):], b])
                ble.send_level(input_level(rms_db(b)))

                # 무음 필터 보정: 시작 몇 초 동안 주변 소음만 모은다 (판정 안 함)
                if calib_left > 0:
                    calib.append(b)
                    calib_left -= len(b)
                    if calib_left <= 0:
                        amb = np.concatenate(calib)
                        win, hop = cfg.window, cfg.hop
                        acts = [activity_db(amb[i:i + win], sr) for i in range(0, max(len(amb) - win, 0) + 1, hop)]
                        gate_db = max(cfg.gate_floor_db, float(np.median(acts)) + cfg.gate_margin_db)
                        log.info("무음 기준 %.1f dBFS (주변소음 중앙값 + %.0f dB). 감지 시작", gate_db, cfg.gate_margin_db)
                    continue

                since_infer += len(b)
                if since_infer < cfg.hop:
                    continue
                since_infer -= cfg.hop
                window = buf.copy()
                act = activity_db(window, sr)
                silent = use_gate and act < gate_db
                infer_ms = 0.0
                if silent:
                    probs = np.zeros(len(cfg.labels), dtype=np.float32)   # 무음 창은 모델 확률을 쓰지 않음
                    if args.probs:
                        print(f"# [무음] 활동 {act:6.1f} < 기준 {gate_db:6.1f} dBFS", file=sys.stderr)
                else:
                    t_inf = time.perf_counter()
                    probs = await loop.run_in_executor(None, model, window)
                    infer_ms = (time.perf_counter() - t_inf) * 1000
                    if args.probs:
                        txt = "  ".join(f"{lab} {p:.3f}" for lab, p in zip(cfg.labels, probs))
                        print(f"# {txt}  (활동 {act:6.1f} dBFS, {infer_ms:.0f} ms)", file=sys.stderr)
                level_db = rms_db(window)
                events = det.push(probs, level_db, t_ms=int(time.time() * 1000))
                for ev in events:
                    ble.send_event(ev)
                if writer:
                    now = time.time()
                    writer.writerow([
                        time.strftime("%H:%M:%S", time.localtime(now)), f"{now - t0:.1f}",
                        *(f"{p:.4f}" for p in probs), f"{act:.1f}", f"{gate_db:.1f}", int(silent),
                        f"{level_db:.1f}", f"{infer_ms:.0f}",
                        ";".join(f"{e['cls']}:{e['lvl']}:{e['trend']}" for e in events),
                    ])
                    log_f.flush()
    finally:
        if log_f:
            log_f.close()


# ─────────────────────────── 실행 ───────────────────────────

async def amain(args) -> None:
    ble = WoollimBle()
    await ble.start()
    try:
        if args.fake:
            await run_fake(ble, args.interval)
        else:
            await run_mic(ble, args)
    finally:
        await ble.stop()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--fake", action="store_true", help="모델 없이 가짜 시나리오 전송")
    ap.add_argument("--interval", type=float, default=10.0, help="--fake 시나리오 반복 간격(초)")
    ap.add_argument("--device", default="mic", help="sounddevice 입력 장치 이름/번호 (기본: ALSA 'mic')")
    ap.add_argument("--config", type=Path, default=ML / "v1" / "model_config.json",
                    help="모델 설정 (기본: v1 최종 모델, v0은 ../ml/model_config.json)")
    ap.add_argument("--model", type=Path, default=None, help="기본: 설정 파일의 model")
    ap.add_argument("--no-gate", action="store_true", help="무음 필터 끄기")
    ap.add_argument("--no-log", action="store_true", help="pi/logs/ 에 창별 기록 CSV를 남기지 않음")
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--probs", action="store_true", help="창별 확률을 stderr로 출력")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()
    if isinstance(args.device, str) and args.device.isdigit():
        args.device = int(args.device)

    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(message)s", datefmt="%H:%M:%S")
    try:
        asyncio.run(amain(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
