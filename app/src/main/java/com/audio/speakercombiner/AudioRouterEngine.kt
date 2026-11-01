package com.audio.speakercombiner

import android.content.Context
import android.media.*
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Multi-device Audio Routing Engine:
 * - Detects all active Android audio output devices (Phone/TV Speakers, HDMI, Bluetooth, AUX, USB DAC).
 * - Creates an individual AudioTrack for each enabled device.
 * - Routes via audioTrack.setPreferredDevice(deviceInfo).
 * - Handles per-device Delay Compensation via DelayLine circular buffers.
 * - Streams audio in parallel to all active speakers without requiring Virtual Cable!
 */
class AudioRouterEngine(private val context: Context) {

    private val TAG = "AudioRouterEngine"
    private val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val dspProcessor = DspProcessor(sampleRate)

    var masterParams = MasterParams()
    val deviceConfigs = ConcurrentHashMap<Int, OutputDeviceConfig>()

    private val activeTracks = ConcurrentHashMap<Int, AudioTrack>()
    private val delayLines = ConcurrentHashMap<Int, DelayLine>()

    private val isRunning = AtomicBoolean(false)

    fun scanDevices(): List<OutputDeviceConfig> {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val list = mutableListOf<OutputDeviceConfig>()

        for (dev in outputs) {
            val typeName = getDeviceTypeName(dev.type)
            val name = if (dev.productName.isNullOrBlank()) typeName else "${dev.productName} ($typeName)"

            val existing = deviceConfigs[dev.id]
            val config = existing ?: OutputDeviceConfig(
                id = dev.id,
                name = name,
                type = dev.type,
                deviceInfo = dev,
                isEnabled = false,
                role = SpeakerRole.STEREO,
                delayMs = 0,
                volumePercent = 100
            )
            deviceConfigs[dev.id] = config
            list.add(config)
        }
        return list
    }

    fun start() {
        if (isRunning.getAndSet(true)) return

        dspProcessor.resetSoftStart()
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioEncoding)

        // Open an AudioTrack for each enabled output device
        for ((id, config) in deviceConfigs) {
            if (config.isEnabled && config.deviceInfo != null) {
                try {
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .setEncoding(audioEncoding)
                                .build()
                        )
                        .setBufferSizeInBytes(minBufferSize * 4)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    // Bind AudioTrack to the specific physical hardware device!
                    val success = track.setPreferredDevice(config.deviceInfo)
                    Log.d(TAG, "Binding AudioTrack to device: ${config.name}, success=$success")

                    track.play()
                    activeTracks[id] = track
                    delayLines[id] = DelayLine(sampleRate)
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening AudioTrack for ${config.name}", e)
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        for ((_, track) in activeTracks) {
            try {
                track.stop()
                track.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing track", e)
            }
        }
        activeTracks.clear()
        delayLines.clear()
    }

    /**
     * Feeds captured raw internal audio frames into the DSP engine and dispatches to all devices.
     */
    fun processAndDispatch(pcmShorts: ShortArray, numShorts: Int) {
        if (!isRunning.get() || activeTracks.isEmpty()) return

        val numFrames = numShorts / 2
        val floatL = FloatArray(numFrames)
        val floatR = FloatArray(numFrames)

        // 1. Process master audio (EQ, widening, limiter, safe mode)
        dspProcessor.processMaster(pcmShorts, numShorts, floatL, floatR, masterParams)

        // Check if any active device has SUBWOOFER role
        val hasSubwooferActive = deviceConfigs.values.any { it.isEnabled && it.role == SpeakerRole.SUBWOOFER }

        // 2. Feed each active device's delay line and output track
        val tempShorts = ShortArray(numFrames * 2)

        for ((id, track) in activeTracks) {
            val config = deviceConfigs[id] ?: continue
            val delayLine = delayLines[id] ?: continue
            val volScale = (config.volumePercent / 100.0f).coerceIn(0.0f, 1.0f)

            for (i in 0 until numFrames) {
                val (rL, rR) = dspProcessor.routeRole(
                    floatL[i],
                    floatR[i],
                    config.role,
                    masterParams.cutBassOnOthers,
                    hasSubwooferActive
                )

                delayLine.write(rL, rR)
                val (dL, dR) = delayLine.read(config.delayMs, sampleRate)

                // Scale by device volume and convert back to 16-bit PCM
                val finalL = (dL * volScale).coerceIn(-1.0f, 1.0f)
                val finalR = (dR * volScale).coerceIn(-1.0f, 1.0f)

                tempShorts[i * 2] = (finalL * 32767.0f).toInt().toShort()
                tempShorts[i * 2 + 1] = (finalR * 32767.0f).toInt().toShort()
            }

            // Write to hardware track
            track.write(tempShorts, 0, tempShorts.size, AudioTrack.WRITE_NON_BLOCKING)
        }
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
            AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "AUX / Line Out"
            else -> "Audio Output"
        }
    }
}
