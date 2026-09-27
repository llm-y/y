package com.example.ipwebcam.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class CameraStreamManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner? = null
) {
    private val TAG = "CameraStreamManager"

    // Persistent LifecycleOwner agar CameraX TIDAK dihentikan saat layar dimatikan atau activity berhenti
    private val persistentLifecycleOwner = object : LifecycleOwner {
        private val registry = androidx.lifecycle.LifecycleRegistry(this).apply {
            currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        }
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry

        fun stop() {
            registry.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
        }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var isStopped = false

    // PreviewView hanya ada selama Activity terlihat; saat layar mati hanya ImageAnalysis yang berjalan
    private var previewView: PreviewView? = null
    private var preview: Preview? = null

    // Konfigurasi kamera
    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set
    var isTorchOn: Boolean = false
        private set
    var jpegQuality: Int = 80
    var zoomRatio: Float = 1.0f
        private set

    // Resolusi
    var targetResolution: Size = Size(1280, 720)

    // Frame terbaru untuk /shot.jpg dan MJPEG
    private val latestJpeg = AtomicReference<ByteArray?>(null)

    // Listener frame untuk MJPEG streaming
    private val frameListeners = ConcurrentHashMap<String, (ByteArray) -> Unit>()

    // Thread pool untuk pemrosesan kamera
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Kalkulasi FPS
    private val frameCount = AtomicInteger(0)
    private val lastFpsTimestamp = AtomicLong(System.currentTimeMillis())
    var currentFps: Int = 0
        private set

    var onFpsUpdated: ((Int) -> Unit)? = null

    fun startCamera(onReady: (() -> Unit)? = null) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            if (isStopped) return@addListener
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCamera()
                onReady?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Gagal menginisialisasi kamera", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Pasang PreviewView saat Activity terlihat. Rebind penuh aman dilakukan di sini
     * karena aplikasi sedang berada di foreground.
     */
    fun attachPreview(view: PreviewView) {
        previewView = view
        bindCamera()
    }

    /**
     * Lepas PreviewView saat Activity tidak terlihat (tombol power / pindah aplikasi).
     * Surface PreviewView dihancurkan sistem saat layar mati; jika Preview masih ter-bind,
     * capture session CameraX ikut macet dan ImageAnalysis (stream) berhenti mengirim frame.
     * Hanya Preview yang di-unbind supaya kamera tetap terbuka dan stream tidak terputus.
     */
    fun detachPreview() {
        previewView = null
        preview?.let { cameraProvider?.unbind(it) }
        preview = null
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        // Preview Use Case (hanya jika ada PreviewView yang terlihat)
        val preview = previewView?.let { view ->
            Preview.Builder()
                .setTargetResolution(targetResolution)
                .build()
                .also { it.setSurfaceProvider(view.surfaceProvider) }
        }
        this.preview = preview

        // Image Analysis Use Case (untuk streaming)
        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(targetResolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            processImageProxy(imageProxy)
        }

        try {
            val owner = persistentLifecycleOwner
            val camera = if (preview != null) {
                provider.bindToLifecycle(owner, cameraSelector, preview, imageAnalysis)
            } else {
                provider.bindToLifecycle(owner, cameraSelector, imageAnalysis)
            }
            cameraControl = camera.cameraControl
            cameraInfo = camera.cameraInfo

            // Terapkan torch dan zoom sebelumnya jika ada
            if (lensFacing == CameraSelector.LENS_FACING_BACK && isTorchOn) {
                cameraControl?.enableTorch(true)
            }
            cameraControl?.setZoomRatio(zoomRatio)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal bind use cases kamera", e)
        }
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        try {
            val bitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            // Rotasikan bitmap sesuai orientasi sensor kamera
            val finalBitmap = if (rotationDegrees != 0 || lensFacing == CameraSelector.LENS_FACING_FRONT) {
                val matrix = Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                    // Mirror jika kamera depan agar natural
                    matrix.postScale(-1f, 1f)
                }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            // Kompresi ke JPEG
            val out = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            val jpegBytes = out.toByteArray()

            // Simpan frame terbaru
            latestJpeg.set(jpegBytes)

            // Kirim ke semua client streaming yang aktif
            for (listener in frameListeners.values) {
                try {
                    listener(jpegBytes)
                } catch (e: Exception) {
                    // Abaikan kesalahan client terputus
                }
            }

            // Update FPS
            val count = frameCount.incrementAndGet()
            val now = System.currentTimeMillis()
            val elapsed = now - lastFpsTimestamp.get()
            if (elapsed >= 1000) {
                currentFps = (count * 1000L / elapsed).toInt()
                frameCount.set(0)
                lastFpsTimestamp.set(now)
                onFpsUpdated?.invoke(currentFps)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error saat kompresi frame kamera", e)
        } finally {
            imageProxy.close()
        }
    }

    fun getLatestFrame(): ByteArray? {
        return latestJpeg.get()
    }

    fun registerFrameListener(id: String, listener: (ByteArray) -> Unit) {
        frameListeners[id] = listener
    }

    fun unregisterFrameListener(id: String) {
        frameListeners.remove(id)
    }

    fun getActiveListenerCount(): Int = frameListeners.size

    // Kontrol Kamera
    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        isTorchOn = false // Matikan torch saat switch kamera
        bindCamera()
    }

    fun setTorch(enable: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        if (lensFacing != CameraSelector.LENS_FACING_BACK) {
            onResult?.invoke(false)
            return
        }
        isTorchOn = enable
        val future = cameraControl?.enableTorch(enable)
        future?.addListener({
            onResult?.invoke(enable)
        }, ContextCompat.getMainExecutor(context))
    }

    fun setZoom(ratio: Float) {
        zoomRatio = ratio.coerceIn(1.0f, 5.0f)
        cameraControl?.setZoomRatio(zoomRatio)
    }

    fun setResolution(width: Int, height: Int) {
        targetResolution = Size(width, height)
        bindCamera()
    }

    fun stop() {
        isStopped = true
        previewView = null
        preview = null
        persistentLifecycleOwner.stop()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        frameListeners.clear()
    }
}
