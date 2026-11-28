# 울림 위험음 감지 모델 v1 (최종)

팀원이 만든 `SoundDetection_Final`에서 **실행에 필요한 파일만** 옮겨 온 폴더입니다.
학습 과정·실험 원본(SavedModel, Keras 체크포인트, 로그)은 원본 zip에 있습니다.

| 파일 | 내용 |
|---|---|
| `woollim_sound_v1.tflite` | 원본 `models/yamnet_multiclass_base_max/yamnet_head.tflite` (YAMNet + max pooling 분류기, 13.9 MB) |
| `model_config.json` | Pi 실행용 설정 (`ml/inference_reference.py`, `pi/main.py`가 읽음) |
| `realtime_final.yaml`, `class_map.json` | 원본 최종 설정 (참고용, 값은 `model_config.json`에 그대로 옮김) |
| `samples/` | 확인용 WAV 3개 (출처·라이선스: `samples.csv`) |
| `RESULTS.md`, `figures/` | 원본 실험 결과 요약·그림 |
| `README_original.md` | 원본 README (Windows 실행 방법 포함) |

## v0 → v1 차이 (Pi 코드가 자동으로 처리)
| | v0 (`ml/woollim_sound.tflite`) | v1 |
|---|---|---|
| 입력 | float32 [15600] (0.975 s) | **float32 [32000] (2.0 s)**, 16 kHz 모노 −1..1 |
| 출력 | sigmoid [siren, horn] | **softmax [background, horn, siren]** |
| 임계값 | 사이렌 0.773 · 경적 0.505 | **사이렌 0.9996 · 경적 0.9803** |
| 판정 | 최근 3창 중 2창 | 3창 중 2창 + **경적 단일 창 즉시 판정**(≥ max(0.9, 임계값)) + **종료 기준 임계값×0.7** + **같은 클래스 3초 쿨다운** |
| 무음 필터 | 없음 | 시작 3초 주변 소음 측정 → 활동도(50 ms 프레임 에너지 p90)가 중앙값+3 dB 미만인 창은 판정 안 함 |

## 성능 (원본 RESULTS.md, test 762클립)
| 클래스 | Precision | Recall | F1 |
|---|---|---|---|
| 배경 | 0.839 | 0.960 | 0.896 |
| 경적 | 0.792 | 0.717 | 0.753 |
| 사이렌 | 0.989 | 0.928 | 0.957 |

Macro F1 0.869 · TFLite 추론 28 ms (개발 PC 기준, Pi 4는 미측정)

## Pi에서 확인
```bash
cd ~/woollim/ml
python inference_reference.py --config v1/model_config.json v1/samples/sample_siren.wav --probs
python inference_reference.py --config v1/model_config.json --bench
cd ../pi && python main.py          # 기본 모델이 v1
```
