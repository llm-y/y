package com.example.ipwebcam.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.ipwebcam.MainActivity
import com.example.ipwebcam.R
import com.example.ipwebcam.audio.AudioStreamer
import com.example.ipwebcam.camera.CameraStreamManager
import com.example.ipwebcam.server.MjpegHttpServer
import com.example.ipwebcam.utils.NetworkUtils

/**
 * Service ini memiliki kamera, server HTTP, dan audio streamer.
 *
 * - Saat Activity terlihat, Activity melakukan bind ke service ini untuk menampilkan preview.
 * - Saat server dijalankan, service menjadi Foreground Service (tipe camera|microphone),
 *   sehingga kamera & stream tetap berjalan walaupun layar dimatikan dengan tombol power,
 *   Activity berhenti, atau bahkan Activity dihancurkan oleh sistem.
 */
class WebcamService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "ip_webcam_stream_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val PORT = 8080

        const val PREFS_NAME = "webcam_settings"
        const val KEY_SHOW_CAMERA_OVERLAY = "show_camera_overlay"
        const val KEY_OVERLAY_POSITION = "overlay_position"

        fun startStreaming(context: Context) {
            val intent = Intent(context, WebcamService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun isCameraOverlayEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_CAMERA_OVERLAY, true)

        private fun overlayGravity(context: Context): Int {
            val position = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_OVERLAY_POSITION, "top_center")
            return when (position) {
                "top_start" -> Gravity.TOP or Gravity.START
                "top_end" -> Gravity.TOP or Gravity.END
                "bottom_center" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
        }
    }

    inner class LocalBinder : Binder() {
        val service: WebcamService get() = this@WebcamService
    }

    private val binder = LocalBinder()

    lateinit var cameraManager: CameraStreamManager
        private set
    val audioStreamer = AudioStreamer()
    private var httpServer: MjpegHttpServer? = null

    var streamUrl: String? = null
        private set
    val isStreaming: Boolean get() = httpServer != null

    // Callback UI untuk Activity yang sedang ter-bind (dipanggil di main thread)
    var onStreamingStateChanged: (() -> Unit)? = null
    var onClientCountChanged: ((Int) -> Unit)? = null
    var onControlAction: ((action: String, value: String) -> Unit)? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var highPerfWifiLock: WifiManager.WifiLock? = null
    private var lowLatencyWifiLock: WifiManager.WifiLock? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    // true selama ada Activity aplikasi yang terlihat; overlay kamera hanya tampil saat false
    private var isUiVisible = false

    private val appVisibilityObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            isUiVisible = true
            updateOverlayWindow()
        }

        override fun onStop(owner: LifecycleOwner) {
            isUiVisible = false
            updateOverlayWindow()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        cameraManager = CameraStreamManager(this)
        cameraManager.startCamera()
        ProcessLifecycleOwner.get().lifecycle.addObserver(appVisibilityObserver)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startStreaming()
            ACTION_STOP -> stopStreaming()
            // Restart otomatis oleh sistem tanpa intent: kamera tidak boleh dibuka dari background
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startStreaming() {
        val url = "http://${NetworkUtils.getLocalIpAddress(this)}:$PORT"

        // Wajib dipanggil segera setelah startForegroundService()
        startForegroundWithType(buildNotification(url))

        if (httpServer != null) return

        try {
            httpServer = MjpegHttpServer(
                port = PORT,
                cameraManager = cameraManager,
                audioStreamer = audioStreamer,
                onControlAction = { action, value -> onControlAction?.invoke(action, value) }
            ).apply {
                onClientCountChanged = { count -> this@WebcamService.onClientCountChanged?.invoke(count) }
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            httpServer = null
            Toast.makeText(this, "Gagal memulai server: ${e.message}", Toast.LENGTH_LONG).show()
            stopStreaming()
            return
        }

        streamUrl = url
        acquireLocks()
        updateOverlayWindow()
        onStreamingStateChanged?.invoke()
    }

    private fun updateOverlayWindow() {
        if (isStreaming && !isUiVisible && isCameraOverlayEnabled(this)) {
            createOverlayWindow()
        } else {
            removeOverlayWindow()
        }
    }

    fun stopStreaming() {
        httpServer?.stop()
        httpServer = null
        audioStreamer.stop()
        streamUrl = null
        removeOverlayWindow()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Jika Activity masih ter-bind, service tetap hidup untuk preview kamera
        stopSelf()
        onStreamingStateChanged?.invoke()
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            // Android 14+ melempar SecurityException jika tipe microphone dipakai tanpa izin RECORD_AUDIO
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            // WakeLock: Menjaga CPU tetap aktif saat layar dimatikan
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "IPWebcam:StreamWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            // WifiLock: Menjaga chip Wi-Fi agar tidak sleep / drop packets.
            // HIGH_PERF tetap berlaku saat layar mati, sedangkan LOW_LATENCY (Android 10+)
            // hanya aktif saat layar menyala & aplikasi di foreground, jadi keduanya dipasang.
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            highPerfWifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "IPWebcam:StreamWifiLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lowLatencyWifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "IPWebcam:StreamWifiLockLowLatency"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Overlay kamera yang terlihat (mirip 'Show camera overlay' di IP Webcam) di atas tengah layar
     * saat stream berjalan di background, memakai izin SYSTEM_ALERT_WINDOW.
     * Menandakan kamera sedang aktif dan menaikkan prioritas proses di beberapa ROM (MIUI, ColorOS, dll.).
     * Ketuk overlay untuk membuka aplikasi.
     */
    private fun createOverlayWindow() {
        if (overlayView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }

            val density = resources.displayMetrics.density
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = overlayGravity(this@WebcamService)
                val margin = (8 * density).toInt()
                val isCentered = (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL
                x = if (isCentered) 0 else margin
                y = margin
            }

            val label = SpannableString("\u25CF  IP Webcam: kamera streaming").apply {
                setSpan(ForegroundColorSpan(ContextCompat.getColor(this@WebcamService, R.color.danger)), 0, 1, 0)
            }
            val view = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@WebcamService, R.color.text_primary))
                setBackgroundResource(R.drawable.bg_pill)
                val padH = (14 * density).toInt()
                val padV = (6 * density).toInt()
                setPadding(padH, padV, padH, padV)
                setOnClickListener {
                    startActivity(
                        Intent(this@WebcamService, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            windowManager?.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeOverlayWindow() {
        try {
            overlayView?.let {
                windowManager?.removeView(it)
                overlayView = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null

            highPerfWifiLock?.let {
                if (it.isHeld) it.release()
            }
            highPerfWifiLock = null

            lowLatencyWifiLock?.let {
                if (it.isHeld) it.release()
            }
            lowLatencyWifiLock = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appVisibilityObserver)
        onStreamingStateChanged = null
        onClientCountChanged = null
        onControlAction = null
        httpServer?.stop()
        httpServer = null
        audioStreamer.stop()
        removeOverlayWindow()
        releaseLocks()
        cameraManager.stop()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IP Webcam Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi status streaming kamera aktif"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(streamUrl: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, WebcamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IP Webcam Pro Aktif (Background Stream)")
            .setContentText("Streaming aktif di $streamUrl")
            .setSmallIcon(R.drawable.ic_videocam)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_videocam_off, "Stop", stopIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
