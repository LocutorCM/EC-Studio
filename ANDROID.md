# EC Studio Mobile

Android companion DAW project for EC Studio, built with Kotlin and Jetpack Compose.

## Build
Requires JDK 17, Android SDK 35 and Gradle 8.9+.

```bash
gradle :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

A GitHub Actions workflow builds the debug APK on pushes affecting the Android project and on manual dispatch. Download the APK from the workflow run's Artifacts section.

## Current implementation
- Dark, Logic-inspired mobile studio interface
- Track lanes and track selection
- Tempo control
- Microphone recording to 44.1 kHz / 16-bit WAV
- Playback of recorded WAV takes
- Initial mixer view
- GitHub Actions APK build

This is the first Android milestone. Advanced multi-track synchronized playback, waveform analysis, editing, MIDI instruments, plugin hosting and full export/mixdown remain on the roadmap.
