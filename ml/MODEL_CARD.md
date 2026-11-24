# 울림 위험음 감지 모델 (woollim_sound)

차량 실내 마이크 소리에서 **사이렌**과 **경적**을 감지하는 모델입니다.
Raspberry Pi 4에서 TFLite로 실시간 동작하도록 만들었습니다.

> 상태: 학습 전. `train_yamnet_head.ipynb`를 Colab에서 실행하면 아래 파일과 지표가 채워집니다.

## 파일

| 파일 | 만드는 곳 | 설명 |
|---|---|---|
| `train_yamnet_head.ipynb` | 저장소 | Colab 학습 노트북 (모두 실행 → `woollim_model.zip` 다운로드) |
| `inference_reference.py` | 저장소 | 추론 + 후처리 + 앱 프로토콜 JSON 출력 참조 구현 |
| `requirements-pi.txt` | 저장소 | Pi 설치 패키지 |
| `woollim_sound.tflite` | 노트북 | 모델 (YAMNet + 분류 헤드, 약 15 MB) |
| `labels.txt`, `model_config.json` | 노트북 | 출력 순서, 창 크기, **권장 임계값** |
| `metrics.json` | 노트북 | 테스트 성능 |
| `test_audio/` | 노트북 | 이식 확인용 wav + `expected.json` (창별 기대 확률) |

노트북에서 받은 zip은 이 `ml/` 폴더에 그대로 풀면 됩니다.

## 통합 계약 (팀원용)

### 모델 입출력
- **입력**: `float32[15600]`. 16 kHz 모노, 값 범위 −1..1, 0.975초 분량.
  - int16 PCM이면 `/ 32768.0`으로 바꿔서 넣어야 합니다.
  - 마이크가 44.1/48 kHz면 16 kHz로 리샘플해야 합니다.
- **출력**: `float32[2]` = `[siren, horn]`. 각각 독립적인 sigmoid 확률이고, 둘 다 낮으면 배경 소리입니다.
- **추론 주기**: 0.5초마다(hop 8000샘플), 최근 0.975초 창으로 추론합니다.
- **임계값**: `model_config.json`의 `thresholds` 값을 씁니다. 0.5로 고정하지 않습니다.

### 후처리 (모델 밖, `inference_reference.py`의 `HazardDetector`)

| 앱 필드 | 규칙 |
|---|---|
| 이벤트 시작/종료 | 최근 3창 중 2창 이상 임계 초과면 시작, 3창 모두 미만이면 종료 |
| `cls`, `conf` | 초과한 클래스, 최근 3창의 평균 확률 |
| `trend` | 감지 구간 RMS(dB)의 기울기: ≥ +1.5 dB/s면 `approach`, ≤ −1.5 dB/s면 `recede`, 그 사이면 `steady`, 3창 미만이면 `unknown` |
| `lvl` | 사이렌은 접근 중이고 conf ≥ 0.8이면 `urgent`, 멀어지면 `log`, 그 외는 `caution`. 경적은 멀어지면 `log`, 그 외는 `caution`. 앱이 보낸 속도가 0 km/h면 `urgent`를 `caution`으로 낮춤 |
| 전송 시점 | 이벤트 시작 때 1번, 이후에는 `lvl`이 올라갈 때만 새 `id`로 다시 보냄 (예: 주의 → 긴급) |
| `dur_ms` | 감지 구간 길이 |
| `t_pi` | Pi 시각 (epoch ms) |

앱이 받는 JSON 형식은 `android/app/src/main/java/com/vintagesheep/woollim/model/HazardEvent.kt`에 있습니다.
**팀원이 할 일**은 `inference_reference.py`의 `emit()`을 BLE Notify 전송으로 바꾸고, 앱이 보낸 속도 메시지를 `HazardDetector.on_app_message()`에 넘기는 것입니다.
홈 화면의 "마이크 입력" 막대용 값은 `input_level(db)` (0..1)입니다.

### Pi에서 확인 순서
```bash
pip install -r requirements-pi.txt
python inference_reference.py --check test_audio     # 창별 확률이 노트북과 같으면 통과 (전처리·런타임 확인)
python inference_reference.py --bench                # 창당 추론 시간 (목표 < 100 ms)
python inference_reference.py test_audio/siren_01.wav --probs
python inference_reference.py --mic                  # 사이렌 영상을 스피커로 틀어 실시간 확인
```

## 모델 구조
- [YAMNet](https://github.com/tensorflow/models/tree/master/research/audioset/yamnet) (AudioSet 사전학습, 고정)이 1024차원 임베딩을 만들고, 그 뒤에 Dense(256, relu), Dropout(0.3), Dense(2, sigmoid) 헤드를 붙였습니다.
- 처음부터 학습하지 않고 전이학습을 고른 이유: 공개 데이터 양이 적고, YAMNet이 이미 사이렌·경적 개념을 학습했기 때문입니다.

## 학습 데이터
- **ESC-50** (fold 5는 test, 4는 val), **UrbanSound8K** (fold 10은 test, 9는 val). 둘 다 CC BY-NC 라이선스라 비상업 용도로만 씁니다.
- 같은 Freesound 원본 녹음이 두 데이터셋에 겹치면 UrbanSound8K 쪽을 뺐습니다 (평가 누수 방지).
- **차 안 증강**: 저역통과 필터(2–4 kHz), 엔진·도로·바람 소음을 SNR −5~20 dB로 믹스, 게인 ±10 dB, 시간 이동.
- 테스트는 원본(`test_clean`)과 차 안 시뮬레이션(`test_car`) 두 가지로 따로 평가합니다.

## 성능
노트북 실행 후 `metrics.json` 값을 여기에 옮겨 적습니다.

| 조건 | 클래스 | PR-AUC | 창 재현율 | 창 정밀도 | 클립 재현율 | 시간당 오경보 |
|---|---|---|---|---|---|---|
| test_clean | siren | | | | | |
| test_clean | horn | | | | | |
| test_car | siren | | | | | |
| test_car | horn | | | | | |

목표: 사이렌 클립 재현율 ≥ 0.9, Pi 4에서 창당 < 100 ms.

## 한계와 다음 단계
- **실제 차 안 녹음이 없습니다.** 차 안 소리는 증강으로 흉내만 냈으므로 실차 테스트에서 오경보·미탐 사례를 모아 재학습해야 합니다.
- **해외 사이렌 위주입니다.** 한국 구급차·소방차·경찰 사이렌으로 별도 테스트셋을 만들어야 합니다.
- **음악·라디오 속 사이렌 소리**는 구분하지 못할 수 있습니다. 하드 네거티브로 FSD50K(음악, 말소리, 벨소리)를 추가할 예정입니다.
- `trend`는 음량 변화만 보는 규칙이라 창문 개폐나 음량이 갑자기 바뀌면 틀릴 수 있습니다.
- 울림은 **보조 장치**입니다. 모든 위험음을 감지한다고 보장하지 않습니다.
