package com.audio.speakercombiner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var routerEngine: AudioRouterEngine
    private lateinit var deviceAdapter: AudioDeviceAdapter
    private val deviceList = mutableListOf<OutputDeviceConfig>()

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnRefresh: Button
    private lateinit var tvStatus: TextView

    // Master DSP controls
    private lateinit var sliderMaster: Slider
    private lateinit var tvMasterVal: TextView
    private lateinit var sliderBass: Slider
    private lateinit var tvBassVal: TextView
    private lateinit var sliderTreble: Slider
    private lateinit var tvTrebleVal: TextView
    private lateinit var sliderSurround: Slider
    private lateinit var tvSurroundVal: TextView

    // Crossover & Safe Mode
    private lateinit var etCrossover: EditText
    private lateinit var swCutBass: SwitchMaterial
    private lateinit var swSafeMode: SwitchMaterial

    // Presets
    private lateinit var btnPresetFlat: Button
    private lateinit var btnPresetMusic: Button
    private lateinit var btnPresetMovie: Button
    private lateinit var btnPresetNight: Button

    private var isEngineRunning = false

    // MediaProjection permission launcher
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            isEngineRunning = true
            updateStatusText()
        } else {
            Toast.makeText(this, "Permission required to capture internal audio", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        routerEngine = AudioRouterEngine(this)
        AudioCaptureService.routerEngine = routerEngine

        initViews()
        requestPermissionsIfNeeded()
        refreshDevices()
        applyPreset(SoundPreset.FLAT)
    }

    private fun initViews() {
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnRefresh = findViewById(R.id.btnRefresh)
        tvStatus = findViewById(R.id.tvStatus)

        sliderMaster = findViewById(R.id.sliderMaster)
        tvMasterVal = findViewById(R.id.tvMasterValue)
        sliderBass = findViewById(R.id.sliderBass)
        tvBassVal = findViewById(R.id.tvBassValue)
        sliderTreble = findViewById(R.id.sliderTreble)
        tvTrebleVal = findViewById(R.id.tvTrebleValue)
        sliderSurround = findViewById(R.id.sliderSurround)
        tvSurroundVal = findViewById(R.id.tvSurroundValue)

        etCrossover = findViewById(R.id.etCrossover)
        swCutBass = findViewById(R.id.swCutBass)
        swSafeMode = findViewById(R.id.swSafeMode)

        btnPresetFlat = findViewById(R.id.btnPresetFlat)
        btnPresetMusic = findViewById(R.id.btnPresetMusic)
        btnPresetMovie = findViewById(R.id.btnPresetMovie)
        btnPresetNight = findViewById(R.id.btnPresetNight)

        val rvDevices: RecyclerView = findViewById(R.id.rvDevices)
        rvDevices.layoutManager = LinearLayoutManager(this)
        deviceAdapter = AudioDeviceAdapter(deviceList) { updatedConfig ->
            routerEngine.deviceConfigs[updatedConfig.id] = updatedConfig
            updateStatusText()
        }
        rvDevices.adapter = deviceAdapter

        btnStart.setOnClickListener {
            val enabledCount = deviceList.count { it.isEnabled }
            if (enabledCount == 0) {
                Toast.makeText(this, "Please enable at least one output device below", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startAudioCapture()
        }

        btnStop.setOnClickListener {
            stopAudioCapture()
        }

        btnRefresh.setOnClickListener {
            refreshDevices()
        }

        // Sliders
        sliderMaster.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            routerEngine.masterParams.masterVolumePercent = v
            tvMasterVal.text = "$v%"
        }

        sliderBass.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            routerEngine.masterParams.bassDb = v
            tvBassVal.text = "${if (v > 0) "+$v" else "$v"} dB"
            routerEngine.dspProcessor.updateEq(v, routerEngine.masterParams.trebleDb)
        }

        sliderTreble.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            routerEngine.masterParams.trebleDb = v
            tvTrebleVal.text = "${if (v > 0) "+$v" else "$v"} dB"
            routerEngine.dspProcessor.updateEq(routerEngine.masterParams.bassDb, v)
        }

        sliderSurround.addOnChangeListener { _, value, _ ->
            val v = value.toInt()
            routerEngine.masterParams.surroundPercent = v
            tvSurroundVal.text = "$v%"
        }

        // Crossover and safe mode
        etCrossover.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val hz = etCrossover.text.toString().toIntOrNull() ?: 120
                val clamped = hz.coerceIn(30, 300)
                etCrossover.setText(clamped.toString())
                routerEngine.masterParams.crossoverHz = clamped
                routerEngine.dspProcessor.updateCrossover(clamped.toFloat())
            }
        }

        swCutBass.setOnCheckedChangeListener { _, isChecked ->
            routerEngine.masterParams.cutBassOnOthers = isChecked
        }

        swSafeMode.setOnCheckedChangeListener { _, isChecked ->
            routerEngine.masterParams.safeMode = isChecked
            Toast.makeText(
                this,
                if (isChecked) "Safe mode ON (Limiter, 40Hz subsonic filter, volume cap)"
                else "Safe mode OFF (Uncapped - keep volumes moderate)",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Preset buttons
        btnPresetFlat.setOnClickListener { applyPreset(SoundPreset.FLAT) }
        btnPresetMusic.setOnClickListener { applyPreset(SoundPreset.MUSIC) }
        btnPresetMovie.setOnClickListener { applyPreset(SoundPreset.MOVIE) }
        btnPresetNight.setOnClickListener { applyPreset(SoundPreset.NIGHT) }
    }

    private fun applyPreset(preset: SoundPreset) {
        sliderMaster.value = preset.masterVol.toFloat()
        tvMasterVal.text = "${preset.masterVol}%"
        routerEngine.masterParams.masterVolumePercent = preset.masterVol

        sliderBass.value = preset.bassDb.toFloat()
        tvBassVal.text = "${if (preset.bassDb > 0) "+${preset.bassDb}" else "${preset.bassDb}"} dB"
        routerEngine.masterParams.bassDb = preset.bassDb

        sliderTreble.value = preset.trebleDb.toFloat()
        tvTrebleVal.text = "${if (preset.trebleDb > 0) "+${preset.trebleDb}" else "${preset.trebleDb}"} dB"
        routerEngine.masterParams.trebleDb = preset.trebleDb

        sliderSurround.value = preset.surround.toFloat()
        tvSurroundVal.text = "${preset.surround}%"
        routerEngine.masterParams.surroundPercent = preset.surround

        routerEngine.dspProcessor.updateEq(preset.bassDb, preset.trebleDb)
    }

    private fun refreshDevices() {
        val detected = routerEngine.scanDevices()
        deviceAdapter.updateList(detected)
        updateStatusText()
    }

    private fun updateStatusText() {
        val activeCount = deviceList.count { it.isEnabled }
        if (isEngineRunning) {
            tvStatus.text = "Status: Running on $activeCount output speaker(s) without virtual cable"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.green_active))
        } else {
            tvStatus.text = "Status: Stopped. (${deviceList.size} audio outputs detected)"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun startAudioCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            Toast.makeText(this, "Android 10+ is required for internal audio capture", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAudioCapture() {
        val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        startService(serviceIntent)
        isEngineRunning = false
        updateStatusText()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 101)
        }
    }
}
