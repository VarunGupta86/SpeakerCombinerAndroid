package com.audio.speakercombiner

import android.media.AudioDeviceInfo

enum class SpeakerRole(val displayName: String, val bitMask: Int) {
    STEREO("Stereo (L+R)", 0),
    LEFT("Left", 1),
    RIGHT("Right", 2),
    CENTER_MONO("Center / Mono", 3),
    SUBWOOFER("Subwoofer", 4),
    SURROUND("Surround", 5);

    companion object {
        fun fromOrdinal(ordinal: Int): SpeakerRole = values().getOrElse(ordinal) { STEREO }
    }
}

data class OutputDeviceConfig(
    val id: Int,
    val name: String,
    val type: Int,
    val deviceInfo: AudioDeviceInfo?,
    var isEnabled: Boolean = false,
    var role: SpeakerRole = SpeakerRole.STEREO,
    var delayMs: Int = 0,         // Latency compensation (0 - 500 ms)
    var volumePercent: Int = 100  // Individual device volume (0 - 100%)
)

data class MasterParams(
    var masterVolumePercent: Int = 70,
    var bassDb: Int = 0,          // -10 to +10 dB
    var trebleDb: Int = 0,        // -10 to +10 dB
    var surroundPercent: Int = 0, // 0 to 100% stereo widening
    var crossoverHz: Int = 120,   // Sub crossover: 30 to 300 Hz
    var cutBassOnOthers: Boolean = true, // High-pass satellites when Sub is present
    var safeMode: Boolean = true  // Limiter, soft start, 40Hz subsonic filter, 85% volume cap
)

enum class SoundPreset(
    val title: String,
    val masterVol: Int,
    val bassDb: Int,
    val trebleDb: Int,
    val surround: Int
) {
    FLAT("Flat", 70, 0, 0, 0),
    MUSIC("Music", 70, 3, 1, 20),
    MOVIE("Movie", 70, 5, 2, 60),
    NIGHT("Night", 40, -2, 0, 0)
}
