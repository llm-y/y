package com.example.ipwebcam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.ipwebcam.databinding.ActivityMainBinding
import com.example.ipwebcam.service.WebcamService
import com.example.ipwebcam.utils.NetworkIpInfo
import com.example.ipwebcam.utils.NetworkType
import com.example.ipwebcam.utils.NetworkUtils
import com.example.ipwebcam.utils.PermissionUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Kamera, server & audio dimiliki WebcamService agar tetap hidup saat layar mati / Activity berhenti
    private var webcamService: WebcamService? = null
    private var isBound = false

    private var isAudioEnabled = true

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as WebcamService.LocalBinder).service
            webcamService = service
            onServiceReady(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            webcamService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false

        if (cameraGranted) {
            bindWebcamService()
        } else {
            Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Jaga layar tetap menyala selama aplikasi dibuka agar stream tidak terhenti
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Tampilkan aplikasi di atas lockscreen saat layar dinyalakan lagi.
        // Sengaja tanpa turnScreenOn agar layar tetap bisa dimatikan dengan tombol power.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        binding.tvPort.text = getString(R.string.port_label, WebcamService.PORT)
        updateServerUi()

        setupListeners()
        setupBlackScreenMode()
        checkPermissionsAndStart()
        checkBatteryOptimization()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        if (hasCameraPermission()) {
            bindWebcamService()
        }
    }

    override fun onStop() {
        // Layar dimatikan / pindah aplikasi: lepas preview, stream tetap jalan di service
        unbindWebcamService()
        super.onStop()
    }

    private fun bindWebcamService() {
        if (isBound) return
        isBound = bindService(
            Intent(this, WebcamService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun unbindWebcamService() {
        webcamService?.let { service ->
            service.cameraManager.detachPreview()
            service.cameraManager.onFpsUpdated = null
            service.onStreamingStateChanged = null
            service.onClientCountChanged = null
            service.onControlAction = null
        }
        webcamService = null
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun onServiceReady(service: WebcamService) {
        service.cameraManager.attachPreview(binding.previewView)
        binding.tvStandby.visibility = View.GONE

        service.cameraManager.onFpsUpdated = { fps ->
            runOnUiThread {
                binding.tvFps.text = getString(R.string.fps_value, fps)
            }
        }
        service.onClientCountChanged = { count -> showClientCount(count) }
        service.onControlAction = { action, value ->
            when (action) {
                "camera", "torch" -> updateTorchButtonState()
                "quality" -> {
                    value.toIntOrNull()?.let { q ->
                        binding.sliderQuality.value = q.coerceIn(30, 100) / 5 * 5f
                        binding.tvQualityValue.text = "$q%"
                    }
                }
            }
        }
        service.onStreamingStateChanged = {
            updateServerUi()
            val message = if (service.isStreaming) {
                getString(R.string.server_started_at, service.streamUrl)
            } else {
                getString(R.string.server_stopped_toast)
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        // Sinkronkan UI dengan status service (stream mungkin sudah berjalan sejak sebelum layar mati)
        val quality = service.cameraManager.jpegQuality
        binding.sliderQuality.value = quality.coerceIn(30, 100) / 5 * 5f
        binding.tvQualityValue.text = "$quality%"
        showClientCount(service.cameraManager.getActiveListenerCount())
        updateTorchButtonState()
        updateServerUi()
    }

    /**
     * Meminta pengecualian Optimasi Baterai agar socket jaringan & CPU tidak di-freeze oleh sistem Android saat layar mati.
     * Izin opsional lain (overlay kamera) ada di halaman Pengaturan.
     */
    private fun checkBatteryOptimization() {
        if (!PermissionUtils.isIgnoringBatteryOptimizations(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.battery_dialog_title)
                .setMessage(R.string.battery_dialog_message)
                .setPositiveButton(R.string.action_allow) { _, _ -> PermissionUtils.openBatteryOptimizationSettings(this) }
                .setNegativeButton(R.string.action_later, null)
                .setNeutralButton(R.string.action_guide) { _, _ ->
                    startActivity(Intent(this, GuideActivity::class.java))
                }
                .show()
        }
    }

    private fun setupListeners() {
        // Toggle Server Button
        binding.btnToggleServer.setOnClickListener {
            val service = webcamService
            when {
                service?.isStreaming == true -> service.stopStreaming()
                !hasCameraPermission() -> checkPermissionsAndStart()
                else -> WebcamService.startStreaming(this)
            }
        }

        // Pengaturan (streaming di background & overlay kamera)
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Switch Camera (Front/Back)
        binding.btnFlipCamera.setOnClickListener {
            webcamService?.cameraManager?.switchCamera()
            updateTorchButtonState()
        }

        // Flash / Torch Toggle
        binding.btnToggleTorch.setOnClickListener {
            val cameraManager = webcamService?.cameraManager ?: return@setOnClickListener
            val newState = !cameraManager.isTorchOn
            cameraManager.setTorch(newState) { success ->
                if (success) {
                    updateTorchButtonState()
                } else {
                    Toast.makeText(this, R.string.torch_unavailable, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Audio Stream Toggle
        binding.btnToggleAudio.setOnClickListener {
            isAudioEnabled = !isAudioEnabled
            if (isAudioEnabled) {
                binding.btnToggleAudio.setImageResource(R.drawable.ic_mic)
                binding.btnToggleAudio.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
                Toast.makeText(this, R.string.audio_on, Toast.LENGTH_SHORT).show()
            } else {
                webcamService?.audioStreamer?.stop()
                binding.btnToggleAudio.setImageResource(R.drawable.ic_mic_off)
                binding.btnToggleAudio.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_secondary))
                Toast.makeText(this, R.string.audio_off, Toast.LENGTH_SHORT).show()
            }
        }

        // Quality Slider
        binding.sliderQuality.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val q = value.toInt()
                webcamService?.cameraManager?.jpegQuality = q
                binding.tvQualityValue.text = "$q%"
            }
        }

        // Copy URL Button
        binding.btnCopyUrl.setOnClickListener {
            val url = binding.tvStreamUrl.text.toString()
            copyToClipboard(url)
        }

        // Tap URL untuk melihat daftar IP jaringan yang tersedia
        binding.urlContainer.setOnClickListener {
            showIpListDialog()
        }
    }

    /**
     * Mode Layar Hitam (Screen Saver):
     * Menggelapkan layar hingga kecerahan minimum (0.01) untuk menghemat baterai & mencegah panas,
     * tetapi Android tetap menjalankan kamera & server 100% tanpa di-kill oleh OS.
     */
    private fun setupBlackScreenMode() {
        binding.btnBlackScreen.setOnClickListener {
            binding.blackScreenOverlay.visibility = View.VISIBLE
            val lp = window.attributes
            lp.screenBrightness = 0.01f
            window.attributes = lp
            Toast.makeText(this, R.string.black_screen_toast, Toast.LENGTH_SHORT).show()
        }

        binding.blackScreenOverlay.setOnClickListener {
            binding.blackScreenOverlay.visibility = View.GONE
            val lp = window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        }
    }

    private fun showIpListDialog() {
        val ips = NetworkUtils.getAllIpAddresses()
        if (ips.isEmpty()) {
            Toast.makeText(this, R.string.no_network, Toast.LENGTH_SHORT).show()
            return
        }

        val port = WebcamService.PORT
        val items = ips.map { "${networkLabel(it)}: http://${it.ip}:$port" }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ip_dialog_title)
            .setItems(items) { _, which ->
                val selected = "http://${ips[which].ip}:$port"
                binding.tvStreamUrl.text = selected
                binding.tvOpencvHint.text = getString(R.string.opencv_hint, selected)
                copyToClipboard(selected)
            }
            .setPositiveButton(R.string.action_close, null)
            .setNeutralButton(R.string.connection_tips) { _, _ ->
                showTroubleshootDialog()
            }
            .show()
    }

    private fun showTroubleshootDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.troubleshoot_title)
            .setMessage(R.string.troubleshoot_message)
            .setPositiveButton(R.string.action_got_it, null)
            .show()
    }

    private fun networkLabel(info: NetworkIpInfo): String = when (info.type) {
        NetworkType.WIFI -> getString(R.string.network_wifi)
        NetworkType.HOTSPOT -> getString(R.string.network_hotspot)
        NetworkType.USB -> getString(R.string.network_usb)
        NetworkType.ETHERNET -> getString(R.string.network_ethernet)
        NetworkType.OTHER -> getString(R.string.network_other, info.interfaceName)
    }

    private fun showClientCount(count: Int) {
        binding.tvClients.text = resources.getQuantityString(R.plurals.clients_count, count, count)
    }

    private fun copyToClipboard(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Stream URL", url))
        Toast.makeText(this, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    private fun updateServerUi() {
        val service = webcamService
        if (service?.isStreaming == true) {
            val baseUrl = service.streamUrl ?: ""
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.success))
            binding.tvStatus.text = getString(R.string.server_running)
            binding.tvStreamUrl.text = baseUrl
            binding.tvStreamUrl.setTextColor(ContextCompat.getColor(this, R.color.primary))
            binding.tvOpencvHint.text = getString(R.string.opencv_hint, baseUrl)

            binding.btnToggleServer.text = getString(R.string.stop_server)
            binding.btnToggleServer.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger))
            binding.btnToggleServer.setIconResource(R.drawable.ic_videocam_off)
        } else {
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.danger))
            binding.tvStatus.text = getString(R.string.server_stopped)
            showClientCount(0)

            // Tampilkan alamat yang akan dipakai (redup) selama server belum berjalan
            val baseUrl = "http://${NetworkUtils.getLocalIpAddress(this)}:${WebcamService.PORT}"
            binding.tvStreamUrl.text = baseUrl
            binding.tvStreamUrl.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
            binding.tvOpencvHint.text = getString(R.string.opencv_hint, baseUrl)

            binding.btnToggleServer.text = getString(R.string.start_server)
            binding.btnToggleServer.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary))
            binding.btnToggleServer.setIconResource(R.drawable.ic_videocam)
        }
    }

    private fun updateTorchButtonState() {
        val isTorch = webcamService?.cameraManager?.isTorchOn ?: false
        binding.btnToggleTorch.setImageResource(if (isTorch) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        binding.btnToggleTorch.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (isTorch) R.color.warning else R.color.text_primary)
        )
    }
}
