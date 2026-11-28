# SoundDetection_Final — 경적·사이렌 실시간 감지 (YAMNet 최종 모델)

마이크로 들어오는 소리를 0.5초마다 분석해 **경적(horn) / 사이렌(siren) / 배경(background)** 확률을 보여주고,
경적·사이렌이 감지되면 경고를 출력한다. 데이터셋·학습 코드 없이 이 폴더만으로 실행된다 (인터넷 불필요).

## 빠른 시작 (Windows)
1. **Python 3.11** 설치 (https://www.python.org/downloads/ , 설치 시 "Add python.exe to PATH" 체크)
2. `setup.cmd` 더블클릭 → 가상환경(.venv) 생성, 라이브러리 설치, 자체 점검(`selfcheck.py`)까지 자동 실행 (처음 1회, 수 분)
   - 폴더는 **바탕화면처럼 경로가 짧은 곳**에 두세요. TensorFlow 설치 파일 경로가 길어 Windows 260자 제한에 걸릴 수 있음
     (깊은 임시 폴더에서 실제로 `OSError: No such file or directory ... Long Path` 오류 발생 확인). 필요하면 Windows 긴 경로 지원을 켜세요.
3. `run_realtime.cmd` 더블클릭 → 처음 **3초는 주변 소음 측정(조용히)** → 실시간 감지 시작. 종료는 `Ctrl+C`

| 파일 | 용도 |
|---|---|
| `run_realtime.cmd` | 최종 설정 마이크 실시간 감지 (TFLite) |
| `run_realtime_keras.cmd` | 같은 설정을 Keras 모델로 (결과 동일, 더 느림) |
| `run_wav.cmd` | WAV 파일 감지 (WAV 를 이 파일 위에 끌어다 놓기, 없으면 샘플 사이렌) |
| `list_devices.cmd` | 마이크 장치 목록 |
| `run_selfcheck.cmd` | 자체 점검 + 마이크 10초 실행 |
| `setup.cmd` | 설치 (처음 1회) |

명령행: `python realtime_detection.py [--device N] [--file x.wav] [--duration 60] [--save-audio] [--debug]`
(인자 없이 실행하면 최종 모델·최종 설정. 어느 폴더에서 실행해도 이 폴더 기준으로 경로를 찾는다)

## 최종 설정 (변경 금지 — 실제 마이크 검증으로 확정)
| 항목 | 값 | 파일 |
|---|---|---|
| 모델 | YAMNet(AudioSet 사전학습, 고정) + 직접 학습한 **max pooling** 분류기 → TFLite | `models/` |
| 경적 임계값 | **0.9802732467651367** | `configs/realtime_final.yaml` |
| 사이렌 임계값 | **0.9996480941772461** | 〃 (validation 에서 정한 값과 동일) |
| 판정 조건 | 최근 3개 window 중 2개 이상 임계값 초과 → 경고. 경적은 window 1개가 임계값 이상이면 즉시(단일 window 규칙, 기준 max(0.9, 임계값)) | 〃 |
| 경고 종료 / 중복 방지 | hysteresis: 최근 3개 모두 임계값×0.7 미만이면 종료 / 같은 클래스 재경고 간격 3초 | 〃 |
| 무음 필터 | 켬. 시작 3초 주변 소음 측정 → 기준 = 주변소음 중앙값 + 3 dB (최소 -85 dBFS). 활동도 = 50 ms 프레임 에너지 상위 10% | 〃 |
| AGC | 끔 | 〃 |
| 마이크 | **WASAPI 배타 모드** (Windows 소음 억제·에코 제거 우회). 장치 미지정 시 WASAPI 기본 입력 자동 선택. 배타 모드를 못 열면 공유 모드로 전환하고 경고 출력 | 〃 |
| 오디오 | 16 kHz mono float32, window 2.0 s (32000 샘플), hop 0.5 s. 마이크 샘플레이트(예: 48 kHz)는 16 kHz 로 리샘플 | `configs/default.yaml` |
| 클래스 | 출력 순서 [background, horn, siren] | `configs/class_map.json` |

## 폴더 구성
```
SoundDetection_Final/
├── realtime_detection.py, selfcheck.py, *.cmd, requirements.txt, README.md
├── src/                      실행에 필요한 모듈만 (audio_io, predictor, detector, silence, yamnet_model, utils)
├── configs/                  default.yaml(오디오·window 기본값) · final.yaml(상대 경로) · realtime_final.yaml(최종 판정 설정) · class_map.json
├── models/
│   ├── yamnet_pretrained/                      YAMNet 사전학습 모델 (TF Hub google/yamnet/1 사본, SOURCE.txt)
│   ├── yamnet_multiclass_base_max/             최종 TFLite (yamnet_head.tflite) + SavedModel
│   ├── checkpoints/yamnet_multiclass_base_max/ Keras 분류기 best.keras(최적) · last.keras · 학습 로그
│   └── results/yamnet_multiclass_base_max/     validation 임계값 원본(summary.json)
├── samples/                  자체 점검용 test 샘플 WAV 3개 (출처·라이선스: samples.csv)
├── logs/                     실행 로그 (window 별 확률·RMS·활동도·추론시간·경고), 마이크 보정값
└── experiments/              학습·실험 결과 백업 — 요약은 experiments/RESULTS.md
```

## 화면 보는 법
- `[무음] 활동 … < 기준 …` : 무음 → 판정·경고 안 함
- `배경 #### 0.37 | 경적 … 0.63(≥0.9803) | 사이렌 … | RMS … 활동 … | … ms` : 클래스별 확률(괄호는 임계값)
- `*** 경고 [ 12.5s] 사이렌 감지! …` : 경고 (알림음 포함)

## 사용 시 주의 (실측 결과 기반)
- 개발 PC(인텔 스마트 사운드 마이크 배열)에서 휴대폰 스피커 **30 cm** 재생은 감지, **1~2 m 중간 볼륨**(마이크 레벨이 무음보다 3~5 dB 큰 수준)은 미감지였다.
- 공유 모드 마이크는 Windows 소음 억제가 사이렌·경적을 1~2초 뒤 지워 감지가 안 되었다 → 배타 모드 사용. 배타 모드 중에는 다른 앱이 마이크를 쓸 수 없다.
- 노트북 자체 스피커로 재생한 소리는 에코 제거로 지워져 테스트에 쓸 수 없다 (다른 기기로 재생할 것).
- 다른 PC·다른 마이크에서는 성능을 측정하지 않았다. 마이크 입력 레벨이 매우 작으면 `--save-audio` 로 녹음해 확인할 것.
- WAV 입력(`--file`)도 실시간과 같은 2초 버퍼를 쓰므로 **2초보다 짧은 파일은 판정하지 않는다** (마이크 입력에는 해당 없음).

## 라이선스
학습 데이터 출처·라이선스는 `experiments/01_dataset/data_README.md`, `restricted_licenses.csv` 참고 (일부 CC BY-NC / CC BY-SA 포함 → 상업적 이용 시 확인 필요).
