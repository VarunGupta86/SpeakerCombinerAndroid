package com.audio.speakercombiner

import kotlin.math.*

/**
 * High-performance audio DSP engine matching SpeakerCombiner.exe algorithms:
 * 1. Subsonic filter (40Hz high-pass for driver protection in Safe Mode)
 * 2. Bass shelving / peaking EQ biquad filter
 * 3. Treble shelving EQ biquad filter
 * 4. Subwoofer low-pass crossover biquad filter
 * 5. Satellite high-pass crossover biquad filter ("Cut bass on other speakers")
 * 6. Stereo widening / Surround synthesis (Mid/Side processing)
 * 7. Brickwall peak limiter and soft-start ramp in Safe Mode
 */
class DspProcessor(private val sampleRate: Int = 48000) {

    // Biquad filter state
    class Biquad {
        var b0 = 1.0f; var b1 = 0.0f; var b2 = 0.0f
        var a1 = 0.0f; var a2 = 0.0f
        var x1 = 0.0f; var x2 = 0.0f
        var y1 = 0.0f; var y2 = 0.0f

        fun reset() {
            x1 = 0.0f; x2 = 0.0f; y1 = 0.0f; y2 = 0.0f
        }

        inline fun process(inSample: Float): Float {
            val out = b0 * inSample + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = inSample
            y2 = y1; y1 = out
            return out
        }

        fun setLowPass(cutoffHz: Float, sampleRate: Float, q: Float = 0.7071f) {
            val clampedCutoff = cutoffHz.coerceIn(20f, sampleRate * 0.45f)
            val w0 = (2.0 * Math.PI * clampedCutoff / sampleRate).toFloat()
            val cosw0 = cos(w0)
            val sinw0 = sin(w0)
            val alpha = sinw0 / (2.0f * q)

            val a0 = 1.0f + alpha
            b0 = ((1.0f - cosw0) / 2.0f) / a0
            b1 = (1.0f - cosw0) / a0
            b2 = ((1.0f - cosw0) / 2.0f) / a0
            a1 = (-2.0f * cosw0) / a0
            a2 = (1.0f - alpha) / a0
        }

        fun setHighPass(cutoffHz: Float, sampleRate: Float, q: Float = 0.7071f) {
            val clampedCutoff = cutoffHz.coerceIn(20f, sampleRate * 0.45f)
            val w0 = (2.0 * Math.PI * clampedCutoff / sampleRate).toFloat()
            val cosw0 = cos(w0)
            val sinw0 = sin(w0)
            val alpha = sinw0 / (2.0f * q)

            val a0 = 1.0f + alpha
            b0 = ((1.0f + cosw0) / 2.0f) / a0
            b1 = (-(1.0f + cosw0)) / a0
            b2 = ((1.0f + cosw0) / 2.0f) / a0
            a1 = (-2.0f * cosw0) / a0
            a2 = (1.0f - alpha) / a0
        }

        fun setLowShelf(cutoffHz: Float, sampleRate: Float, gainDb: Float) {
            val a = 10.0.pow(gainDb / 40.0).toFloat()
            val w0 = (2.0 * Math.PI * cutoffHz.coerceIn(20f, 1000f) / sampleRate).toFloat()
            val cosw0 = cos(w0)
            val sinw0 = sin(w0)
            val alpha = sinw0 / 2.0f * sqrt(2.0f)

            val a0 = (a + 1.0f) + (a - 1.0f) * cosw0 + 2.0f * sqrt(a) * alpha
            b0 = (a * ((a + 1.0f) - (a - 1.0f) * cosw0 + 2.0f * sqrt(a) * alpha)) / a0
            b1 = (2.0f * a * ((a - 1.0f) - (a + 1.0f) * cosw0)) / a0
            b2 = (a * ((a + 1.0f) - (a - 1.0f) * cosw0 - 2.0f * sqrt(a) * alpha)) / a0
            a1 = (-2.0f * ((a - 1.0f) + (a + 1.0f) * cosw0)) / a0
            a2 = ((a + 1.0f) + (a - 1.0f) * cosw0 - 2.0f * sqrt(a) * alpha) / a0
        }

        fun setHighShelf(cutoffHz: Float, sampleRate: Float, gainDb: Float) {
            val a = 10.0.pow(gainDb / 40.0).toFloat()
            val w0 = (2.0 * Math.PI * cutoffHz.coerceIn(2000f, 18000f) / sampleRate).toFloat()
            val cosw0 = cos(w0)
            val sinw0 = sin(w0)
            val alpha = sinw0 / 2.0f * sqrt(2.0f)

            val a0 = (a + 1.0f) - (a - 1.0f) * cosw0 + 2.0f * sqrt(a) * alpha
            b0 = (a * ((a + 1.0f) + (a - 1.0f) * cosw0 + 2.0f * sqrt(a) * alpha)) / a0
            b1 = (-2.0f * a * ((a - 1.0f) + (a + 1.0f) * cosw0)) / a0
            b2 = (a * ((a + 1.0f) + (a - 1.0f) * cosw0 - 2.0f * sqrt(a) * alpha)) / a0
            a1 = (2.0f * ((a - 1.0f) - (a + 1.0f) * cosw0)) / a0
            a2 = ((a + 1.0f) - (a - 1.0f) * cosw0 - 2.0f * sqrt(a) * alpha) / a0
        }
    }

    // Global EQ filters (stereo)
    private val bassShelfL = Biquad()
    private val bassShelfR = Biquad()
    private val trebShelfL = Biquad()
    private val trebShelfR = Biquad()

    // Subsonic 40Hz highpass filters (Safe Mode)
    private val subsonicL = Biquad()
    private val subsonicR = Biquad()

    // Crossover filters (for roles)
    val subLowpass = Biquad()
    val satelliteHighpassL = Biquad()
    val satelliteHighpassR = Biquad()

    // Soft-start state
    private var softStartGain = 0.0f
    private val softStartRampRate = 1.0f / (sampleRate * 0.35f) // 350ms fade-in ramp

    init {
        subsonicL.setHighPass(40.0f, sampleRate.toFloat())
        subsonicR.setHighPass(40.0f, sampleRate.toFloat())
        updateEq(0, 0)
        updateCrossover(120f)
    }

    fun updateEq(bassDb: Int, trebDb: Int) {
        val fs = sampleRate.toFloat()
        bassShelfL.setLowShelf(100.0f, fs, bassDb.toFloat())
        bassShelfR.setLowShelf(100.0f, fs, bassDb.toFloat())
        trebShelfL.setHighShelf(6000.0f, fs, trebDb.toFloat())
        trebShelfR.setHighShelf(6000.0f, fs, trebDb.toFloat())
    }

    fun updateCrossover(crossoverHz: Float) {
        val fs = sampleRate.toFloat()
        subLowpass.setLowPass(crossoverHz, fs)
        satelliteHighpassL.setHighPass(crossoverHz, fs)
        satelliteHighpassR.setHighPass(crossoverHz, fs)
    }

    fun resetSoftStart() {
        softStartGain = 0.0f
    }

    /**
     * Processes interleaved 16-bit stereo PCM into processed floating-point buffer.
     * Applies:
     * - Master volume & Safe mode clamping
     * - Soft-start ramp
     * - Subsonic filter (Safe Mode)
     * - Bass and Treble shelving EQ
     * - Mid/Side stereo widening (Surround)
     * - Peak Limiter
     */
    fun processMaster(
        pcmShorts: ShortArray,
        numShorts: Int,
        outFloatsL: FloatArray,
        outFloatsR: FloatArray,
        params: MasterParams
    ): Int {
        val numFrames = numShorts / 2

        var effectiveMaster = params.masterVolumePercent / 100.0f
        if (params.safeMode) {
            // Cap master at 85% to protect speakers
            effectiveMaster = effectiveMaster.coerceAtMost(0.85f)
        }

        val surroundFactor = params.surroundPercent / 100.0f // 0.0 to 1.0

        for (i in 0 until numFrames) {
            var left = pcmShorts[i * 2] / 32768.0f
            var right = pcmShorts[i * 2 + 1] / 32768.0f

            // Soft-start ramp
            if (params.safeMode && softStartGain < 1.0f) {
                softStartGain = (softStartGain + softStartRampRate).coerceAtMost(1.0f)
                left *= softStartGain
                right *= softStartGain
            }

            // Subsonic filter in Safe Mode (40Hz cutoff)
            if (params.safeMode) {
                left = subsonicL.process(left)
                right = subsonicR.process(right)
            }

            // Bass & Treble EQ
            left = bassShelfL.process(left)
            left = trebShelfL.process(left)
            right = bassShelfR.process(right)
            right = trebShelfR.process(right)

            // Stereo Widening / Surround (Mid/Side processing)
            if (surroundFactor > 0.01f) {
                val mid = 0.5f * (left + right)
                var side = 0.5f * (left - right)
                // Boost side channel proportional to surround setting
                side *= (1.0f + surroundFactor * 1.5f)
                left = mid + side
                right = mid - side
            }

            // Apply Master Volume
            left *= effectiveMaster
            right *= effectiveMaster

            // Brickwall Peak Limiter (Safe Mode: threshold 0.95; Normal: threshold 0.99)
            val threshold = if (params.safeMode) 0.92f else 0.99f
            if (left > threshold) left = threshold else if (left < -threshold) left = -threshold
            if (right > threshold) right = threshold else if (right < -threshold) right = -threshold

            outFloatsL[i] = left
            outFloatsR[i] = right
        }

        return numFrames
    }

    /**
     * Extracts and routes audio for a specific device role.
     */
    fun routeRole(
        inLeft: Float,
        inRight: Float,
        role: SpeakerRole,
        cutBass: Boolean,
        hasSubwooferActive: Boolean
    ): Pair<Float, Float> {
        return when (role) {
            SpeakerRole.STEREO -> {
                if (cutBass && hasSubwooferActive) {
                    Pair(satelliteHighpassL.process(inLeft), satelliteHighpassR.process(inRight))
                } else {
                    Pair(inLeft, inRight)
                }
            }
            SpeakerRole.LEFT -> {
                val l = if (cutBass && hasSubwooferActive) satelliteHighpassL.process(inLeft) else inLeft
                Pair(l, 0.0f)
            }
            SpeakerRole.RIGHT -> {
                val r = if (cutBass && hasSubwooferActive) satelliteHighpassR.process(inRight) else inRight
                Pair(0.0f, r)
            }
            SpeakerRole.CENTER_MONO -> {
                val mono = (inLeft + inRight) * 0.5f
                val filtered = if (cutBass && hasSubwooferActive) satelliteHighpassL.process(mono) else mono
                Pair(filtered, filtered)
            }
            SpeakerRole.SUBWOOFER -> {
                val mono = (inLeft + inRight) * 0.5f
                val sub = subLowpass.process(mono)
                Pair(sub, sub)
            }
            SpeakerRole.SURROUND -> {
                // Surround channel: Phase-inverted difference (L - R)
                val diff = (inLeft - inRight) * 0.707f
                Pair(diff, -diff)
            }
        }
    }
}
