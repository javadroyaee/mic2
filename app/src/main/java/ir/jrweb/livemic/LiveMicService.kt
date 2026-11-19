package ir.jrweb.livemic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

class LiveMicService : Service() {

    companion object {
        const val ACTION_STATE_CHANGED = "ir.jrweb.livemic.STATE_CHANGED"
        const val EXTRA_IS_RUNNING = "is_running"
        const val EXTRA_STATUS_MESSAGE = "status_message"

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var currentDeviceName: String = ""

        @Volatile
        var gainMultiplier: Float = 3.0f

        private const val CHANNEL_ID = "jrweb_livemic_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private var audioThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var activeBluetoothId: Int = -1
    private val sampleRate = 44100

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (removedDevices == null || !isRunning) return
            val disconnected = removedDevices.any { device ->
                device.id == activeBluetoothId ||
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
            if (disconnected) {
                stopAndNotify("وضعیت: غیرفعال (ایرپاد قطع شد)")
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && isRunning) {
                stopAndNotify("وضعیت: غیرفعال (ایرپاد قطع شد)")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isRunning) return START_STICKY

        createNotificationChannel()
        val notification = buildNotification("در حال پخش صدا روی ایرپاد...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (!startAudioStream()) {
            stopAndNotify("وضعیت: غیرفعال (ایرپاد متصل نیست)")
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun startAudioStream(): Boolean {
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = manager

        val bluetoothOutput = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            } ?: return false

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val builtInMic = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

        val minRecordBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val minTrackBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minRecordBufferSize, minTrackBufferSize)

        val audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (builtInMic != null) {
            audioRecord.preferredDevice = builtInMic
        }

        val trackBuilder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }

        val audioTrack = trackBuilder.build()
        audioTrack.preferredDevice = bluetoothOutput

        activeBluetoothId = bluetoothOutput.id
        currentDeviceName = bluetoothOutput.productName?.toString() ?: "AirPods"
        isRunning = true

        // نگه داشتن CPU بیدار هنگام قفل بودن صفحه
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "JRwebLiveMic::AudioWakeLock"
        ).apply {
            acquire()
        }

        // رصد قطع شدن ایرپاد
        manager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        broadcastState(true, "وضعیت: فعال\nدر حال پخش روی $currentDeviceName")

        audioThread = Thread {
            val buffer = ShortArray(bufferSize / 2)
            try {
                audioRecord.startRecording()
                audioTrack.play()

                while (isRunning) {
                    val readCount = audioRecord.read(buffer, 0, buffer.size)
                    if (readCount > 0) {
                        val gain = gainMultiplier
                        if (gain > 1.0f) {
                            for (i in 0 until readCount) {
                                val amplified = (buffer[i] * gain).toInt()
                                buffer[i] = amplified.coerceIn(
                                    Short.MIN_VALUE.toInt(),
                                    Short.MAX_VALUE.toInt()
                                ).toShort()
                            }
                        }
                        audioTrack.write(buffer, 0, readCount)
                    }
                }
            } finally {
                try {
                    audioRecord.stop()
                    audioRecord.release()
                } catch (_: Exception) {
                }
                try {
                    audioTrack.stop()
                    audioTrack.release()
                } catch (_: Exception) {
                }
            }
        }
        audioThread?.start()
        return true
    }

    private fun stopAndNotify(message: String) {
        isRunning = false
        broadcastState(false, message)
        stopSelf()
    }

    private fun broadcastState(running: Boolean, message: String) {
        val intent = Intent(ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_IS_RUNNING, running)
            putExtra(EXTRA_STATUS_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        isRunning = false
        try {
            audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {
        }
        audioThread?.join(500)
        audioThread = null
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null
        broadcastState(false, "وضعیت: غیرفعال")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JRweb Live Mic",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JRweb Live Mic")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}