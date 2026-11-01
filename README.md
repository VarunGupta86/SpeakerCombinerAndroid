# Speaker Combiner for Android & Android TV

A full-featured native Android and Android TV application that replicates all capabilities of the Windows `SpeakerCombiner.exe` utility, **without requiring VB-Audio Virtual Cable or VoiceMeeter**.

---

### How Virtual Cable Was Eliminated on Android
On Windows, `SpeakerCombiner.exe` required "VB-Audio Virtual Cable" to capture all system audio via loopback recording from the default virtual device.

On Android (Android 10+ / API 29+), this app uses **`AudioPlaybackCaptureConfiguration`** with **`MediaProjection`**:
1. When you click **"Start Combining"**, Android asks: *"Start recording or casting with Speaker Combiner?"*.
2. Once allowed, Android's internal audio HAL automatically routes playback from apps (YouTube, Netflix, Spotify, media players, games) directly into the app's 48kHz / 16-bit PCM capture stream.
3. **No root, no virtual cables, and no Magisk/Xposed modules are needed.**

---

### Key Features & Exact Equivalents to SpeakerCombiner.exe

| Windows SpeakerCombiner Feature | Android App Equivalent | Implementation Details |
| :--- | :--- | :--- |
| **Source Audio Capture** | `AudioPlaybackCaptureConfig` | Captures internal system/media audio at 48kHz stereo PCM |
| **Multi-Speaker Output** | Multi-`AudioTrack` Routing | Opens simultaneous `AudioTrack`s bound to hardware devices via `setPreferredDevice()` |
| **Output Devices Supported** | Auto-detected Hardware | TV speakers, HDMI (ARC/eARC), Bluetooth A2DP, Wired AUX/Headphones, USB DAC |
| **Per-Speaker Roles** | 6 Discrete Roles | Stereo (L+R), Left, Right, Center/Mono, Subwoofer (LFE), Surround |
| **Latency Compensation** | Delay Sliders (0–500 ms) | Independent `DelayLine` circular ring buffers to sync laggy Bluetooth speakers with instant HDMI/wired speakers |
| **Subwoofer Crossover** | Biquad 2nd-order Filter | 30 Hz to 300 Hz cutoff (default 120 Hz) with low-pass routing for Sub |
| **Bass Cut on Satellites** | "Cut bass on other speakers" | High-pass filter satellite speakers when Sub is active to avoid low-end distortion |
| **Master Controls** | 4 Sliders | Master Vol (%), Bass EQ (±10 dB), Treble EQ (±10 dB), Surround Widening (%) |
| **Sound Presets** | 4 Quick Presets | **Flat** (70%, 0dB, 0dB, 0%), **Music** (70%, +3dB, +1dB, 20%), **Movie** (70%, +5dB, +2dB, 60%), **Night** (40%, -2dB, 0dB, 0%) |
| **Safe Mode** | Comprehensive Protection | 40 Hz subsonic highpass filter, brickwall peak limiter, soft-start fade ramp, and 85% volume clamp |
| **Android TV Support** | Leanback UI & D-pad | D-pad navigable cards, focus highlights, TV banner (`android.intent.category.LEANBACK_LAUNCHER`) |
| **Background Playback** | Foreground Service | Runs in background with notification controls while watching video or playing games |

---

### Project Structure
```
SpeakerCombinerAndroid/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/audio/speakercombiner/
│       │   ├── MainActivity.kt           // Main UI, D-pad TV navigation, permissions
│       │   ├── AudioCaptureService.kt    // Foreground service running MediaProjection capture
│       │   ├── AudioRouterEngine.kt      // Multi-AudioTrack engine with setPreferredDevice
│       │   ├── DspProcessor.kt           // Biquads, EQ, Crossover, Limiter, Widening
│       │   ├── DelayLine.kt              // Circular ring buffer for latency sync
│       │   ├── AudioModels.kt            // Enums and configuration models
│       │   └── AudioDeviceAdapter.kt     // Output device RecyclerView adapter
│       └── res/
│           ├── layout/                   // Responsive TV & mobile XML layouts
│           ├── values/                   // Dark theme, colors, strings
│           └── drawable/                 // App icon, TV banner, styles
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

### How to Build & Install

#### Method 1: Android Studio (Recommended)
1. Extract `SpeakerCombiner-Android.zip`.
2. Open Android Studio (Ladybug, Hedgehog, or newer) and select **Open**, then choose the extracted folder.
3. Connect your Android phone or Android TV via USB (or wireless ADB).
4. Click **Run** (`Shift + F10`) or select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

#### Method 2: Command Line (Gradle)
```bash
./gradlew assembleDebug
```
The compiled APK will be in:
`app/build/outputs/apk/debug/app-debug.apk`

Install directly onto your Android TV or phone via ADB:
```bash
adb install app-debug.apk
```
