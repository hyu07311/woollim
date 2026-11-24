# 울림 (Woollim)

청각장애 운전자를 위한 실시간 위험음(사이렌·경적) 감지·알림 시스템

- `android/` — 알림 앱 (Kotlin, Jetpack Compose)
- `ml/` — 사이렌·경적 감지 모델 학습(Colab)과 Pi용 참조 추론 코드 → [`ml/MODEL_CARD.md`](ml/MODEL_CARD.md)
- `docs/` — 발표 자료 (AIoT 경진대회, 2026.12.02)
- 감지 장치(Raspberry Pi) 통합 코드는 추후 추가

## 앱 빌드

JDK 21 필요 (Android Studio 번들 JDK 25는 Gradle 8.9와 호환되지 않음)

```bash
cd android
./gradlew assembleDebug
```
