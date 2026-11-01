package com.audio.speakercombiner

/**
 * Ring buffer delay line for latency compensation per output device.
 * Supports up to 1000 ms of delay buffer at 48 kHz.
 */
class DelayLine(sampleRate: Int = 48000, maxDelayMs: Int = 1000) {

    private val maxFrames = (sampleRate * maxDelayMs) / 1000
    private val bufferL = FloatArray(maxFrames)
    private val bufferR = FloatArray(maxFrames)
    private var writeIndex = 0

    fun write(left: Float, right: Float) {
        bufferL[writeIndex] = left
        bufferR[writeIndex] = right
        writeIndex = (writeIndex + 1) % maxFrames
    }

    fun read(delayMs: Int, sampleRate: Int = 48000): Pair<Float, Float> {
        if (delayMs <= 0) {
            // Instantaneous (last written)
            val idx = (writeIndex - 1 + maxFrames) % maxFrames
            return Pair(bufferL[idx], bufferR[idx])
        }

        val delayFrames = ((delayMs * sampleRate) / 1000).coerceIn(0, maxFrames - 1)
        var readIndex = writeIndex - 1 - delayFrames
        while (readIndex < 0) readIndex += maxFrames
        readIndex %= maxFrames

        return Pair(bufferL[readIndex], bufferR[readIndex])
    }

    fun reset() {
        bufferL.fill(0.0f)
        bufferR.fill(0.0f)
        writeIndex = 0
    }
}
