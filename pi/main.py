"""
울림 감지 장치 실행 프로그램 (Raspberry Pi 4)

  python main.py                 # 마이크 + 모델 → BLE로 앱에 경고 전송
  python main.py --fake          # 모델·마이크 없이 정해진 시나리오를 BLE로 전송 (연결 확인·시연 대체용)

모델 추론과 후처리는 ml/inference_reference.py 를 그대로 쓴다 (Config, SoundModel, HazardDetector).
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import random
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ML = HERE.parent / "ml"
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
    from inference_reference import Config, HazardDetector, SoundModel, input_level, rms_db

    cfg = Config.load(args.config)
    model = SoundModel(args.model, args.threads)
    det = HazardDetector(cfg)
    loop = asyncio.get_running_loop()
    q: asyncio.Queue = asyncio.Queue()

    block = cfg.hop // 5  # 0.1 s 블록 → 레벨 10 Hz, 5블록(= hop 0.5 s)마다 추론

    def cb(indata, frames, t, status):
        if status:
            print(f"# {status}", file=sys.stderr)
        loop.call_soon_threadsafe(q.put_nowait, indata[:, 0].copy())

    buf = np.zeros(cfg.window, dtype=np.float32)
    since_infer = 0
    log.info("마이크 입력 시작 (장치: %s). 사이렌을 들려주세요. Ctrl+C 종료", args.device)
    with sd.InputStream(samplerate=cfg.sample_rate, channels=1, dtype="float32",
                        blocksize=block, device=args.device, callback=cb):
        while True:
            b = await q.get()
            buf = np.concatenate([buf[len(b):], b])
            ble.send_level(input_level(rms_db(b)))
            since_infer += len(b)
            if since_infer < cfg.hop:
                continue
            since_infer -= cfg.hop
            window = buf.copy()
            probs = await loop.run_in_executor(None, model, window)
            if args.probs:
                print(f"# siren {probs[0]:.2f}  horn {probs[1]:.2f}", file=sys.stderr)
            for ev in det.push(probs, rms_db(window), t_ms=int(time.time() * 1000)):
                ble.send_event(ev)


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
    ap.add_argument("--model", type=Path, default=ML / "woollim_sound.tflite")
    ap.add_argument("--config", type=Path, default=ML / "model_config.json")
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
