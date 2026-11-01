package com.audio.speakercombiner

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service running native AudioPlaybackCapture:
 * In Android 10+ (API 29+), AudioPlaybackCaptureConfiguration captures system and app audio
 * directly via MediaProjection WITHOUT ROOT and WITHOUT VIRTUAL CABLE!
 */
class AudioCaptureService : Service() {

    private val TAG = "AudioCaptureService"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "speaker_combiner_playback_channel"

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var captureJob: Job? = null

    companion object {
        const val ACTION_START = "com.audio.speakercombiner.ACTION_START"
        const val ACTION_STOP = "com.audio.speakercombiner.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        var routerEngine: AudioRouterEngine? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startForegroundWithNotification()
                    startCapture(resultCode, resultData)
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val stopIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openActivityIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openActivityIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speaker Combiner is Running")
            .setContentText("Combining internal audio to multiple speakers without virtual cable")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        if (mediaProjection == null) {
            Log.e(TAG, "Failed to get MediaProjection instance")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Native AudioPlaybackCaptureConfiguration: Captures media and game audio without virtual cable!
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val sampleRate = 48000
            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 4)
                .build()

            audioRecord?.startRecording()
            isRecording.set(true)

            routerEngine?.start()

            captureJob = CoroutineScope(Dispatchers.IO).launch {
                val buffer = ShortArray(minBufferSize)
                while (isRecording.get() && isActive) {
                    val readShorts = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readShorts > 0) {
                        routerEngine?.processAndDispatch(buffer, readShorts)
                    }
                }
            }
        }
    }

    private fun stopCapture() {
        isRecording.set(false)
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaProjection", e)
        }
        mediaProjection = null

        routerEngine?.stop()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Speaker Combiner Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status while audio is combining to multiple devices"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
