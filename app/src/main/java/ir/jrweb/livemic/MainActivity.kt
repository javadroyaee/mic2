package ir.jrweb.livemic

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var gainLabel: TextView
    private lateinit var gainSeekBar: SeekBar

    private val permissionRequestCode = 101

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LiveMicService.ACTION_STATE_CHANGED) {
                val running = intent.getBooleanExtra(LiveMicService.EXTRA_IS_RUNNING, false)
                val message = intent.getStringExtra(LiveMicService.EXTRA_STATUS_MESSAGE)
                    ?: if (running) "وضعیت: فعال" else "وضعیت: غیرفعال"
                updateUi(running, message)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)
        gainLabel = findViewById(R.id.gainLabel)
        gainSeekBar = findViewById(R.id.gainSeekBar)

        // مقدار اولیه اسلایدر تقویت صدا (1.0x تا 6.0x)
        val initialProgress = ((LiveMicService.gainMultiplier - 1.0f) * 10).toInt().coerceIn(0, 50)
        gainSeekBar.progress = initialProgress
        gainLabel.text = String.format(Locale.US, "تقویت صدا: %.1fx", LiveMicService.gainMultiplier)

        gainSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val gain = 1.0f + (progress / 10.0f)
                LiveMicService.gainMultiplier = gain
                gainLabel.text = String.format(Locale.US, "تقویت صدا: %.1fx", gain)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        toggleButton.setOnClickListener {
            if (LiveMicService.isRunning) {
                stopService(Intent(this, LiveMicService::class.java))
                updateUi(false, "وضعیت: غیرفعال")
            } else {
                checkPermissionsAndStart()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(LiveMicService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }

        if (LiveMicService.isRunning) {
            updateUi(true, "وضعیت: فعال\nدر حال پخش روی ${LiveMicService.currentDeviceName}")
        } else {
            updateUi(false, "وضعیت: غیرفعال")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
    }

    private fun checkPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), permissionRequestCode)
        } else {
            startLiveMicService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            val micGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (micGranted) {
                startLiveMicService()
            } else {
                Toast.makeText(this, "دسترسی به میکروفن نیاز است", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startLiveMicService() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val hasBluetooth = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }

        if (!hasBluetooth) {
            Toast.makeText(this, "لطفاً ابتدا ایرپاد را به بلوتوث متصل کنید", Toast.LENGTH_LONG).show()
            return
        }

        val serviceIntent = Intent(this, LiveMicService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun updateUi(isRunning: Boolean, message: String) {
        statusText.text = message
        if (isRunning) {
            toggleButton.text = "توقف پخش"
            toggleButton.setBackgroundResource(R.drawable.bg_button_active)
            toggleButton.setTextColor(Color.parseColor("#4A148C"))
        } else {
            toggleButton.text = "فعال کردن میکروفن"
            toggleButton.setBackgroundResource(R.drawable.bg_button_orange)
            toggleButton.setTextColor(Color.WHITE)
        }
    }
}