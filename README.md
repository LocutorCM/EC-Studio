# EC Studio

Native macOS digital audio workstation built with C++ and JUCE.

## First milestone

- Native desktop application shell
- Audio device setup and input monitoring
- Transport controls
- Initial audio recording/playback engine
- Timeline and waveform overview
- WAV export

## Build (macOS)

Requirements: Xcode Command Line Tools, CMake 3.22+, and an internet connection for the first configure (JUCE is fetched by CMake).

```bash
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --config Release
open build/ECStudio_artefacts/Release/EC Studio.app
```

For development, use `-DCMAKE_BUILD_TYPE=Debug`.

## Status

Early development scaffold. This is not yet a production-ready DAW. MIDI, plugin hosting (AU/VST3), multitrack editing, automation, and project save/load are planned milestones.

## License

All rights reserved unless a license is added by the project owner.
