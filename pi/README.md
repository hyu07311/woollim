# 감지 장치 (Raspberry Pi 4)

마이크 소리를 모델로 판정해서 **BLE로 앱에 경고를 보내는** 프로그램입니다.

| 파일 | 내용 |
|---|---|
| `main.py` | 실행 파일. 마이크 → 모델(`ml/inference_reference.py`) → BLE 전송 |
| `woollim_ble.py` | BLE GATT 서버 (UUID는 앱 `BleEventSource.kt`와 같아야 함) |

## BLE 규격
| 항목 | UUID | 내용 |
|---|---|---|
| 서비스 | `8f1e0001-6b2c-4d3a-9e5f-77a1c0d2e301` | |
| event (Notify) | `8f1e0002-…` | `HazardEvent` JSON 한 줄 |
| level (Notify) | `8f1e0003-…` | 마이크 레벨 1 byte (0–255), 10 Hz |

## 설치 (처음 한 번)
```bash
cd ~/woollim
source ~/.venv/bin/activate
pip install -r pi/requirements.txt
```

## 실행
```bash
cd ~/woollim/pi
python main.py --fake      # 1) 먼저 가짜 시나리오로 연결 확인 (10초마다 경적 → 사이렌 주의 → 긴급 → 기록)
python main.py             # 2) 마이크 + 모델 v1 (시작 3초는 주변 소음 측정 → 조용히)
python main.py --probs     #    창별 확률·무음 판정도 함께 출력
python main.py --config ../ml/model_config.json   # 이전 모델 v0으로 실행
```
- 기본 모델은 **`ml/v1`** (팀원 최종 모델, 2초 창). 자세한 내용은 [`ml/v1/README.md`](../ml/v1/README.md)
- 무음 필터: 시작 3초 동안 주변 소음을 재서 그보다 3 dB 이상 큰 소리만 판정합니다. 끄려면 `--no-gate`
- 앱을 켜고 **설정 → 시연 모드 끄기**를 하면 자동으로 찾아서 연결합니다.
- 마이크 장치는 기본으로 ALSA `mic`(~/.asoundrc)를 씁니다. 다른 장치는 `--device 번호`.

## 문제 해결
- `org.bluez.Error...` / 권한 오류 → `sudo usermod -aG bluetooth $USER` 후 재로그인, 또는 `sudo ~/.venv/bin/python main.py --fake`
- 블루투스가 꺼져 있음 → `bluetoothctl power on`
- 앱에서 장치가 안 보임 → 휴대폰 nRF Connect 앱으로 스캔해서 `Woollim`(또는 `woollim`)과 서비스 UUID가 보이는지 확인
