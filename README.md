# 울림 (Woollim)

청각장애 운전자를 위한 실시간 위험음(사이렌·경적) 감지·알림 시스템

- `android/` — 알림 앱 (Kotlin, Jetpack Compose)
- 감지 장치(Raspberry Pi) 코드는 추후 추가

## 앱 빌드

JDK 21 필요 (Android Studio 번들 JDK 25는 Gradle 8.9와 호환되지 않음)

```bash
cd android
./gradlew assembleDebug
```
