package com.pedropetrov.screenlens.camera

import android.content.Context
import android.os.SystemClock
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pedropetrov.screenlens.rotate
import com.pedropetrov.screenlens.scaleToMaxEdge
import com.pedropetrov.screenlens.toJpeg
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the camera on the camera phone: live preview frames for the controller,
 * full-resolution captures for Claude, plus torch / zoom / focus.
 * Methods other than [bind] and [release] are safe to call from any thread.
 */
class CameraEngine(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val latestFrame = AtomicReference<ByteArray?>(null)
    private var lastFrameAt = 0L

    @Volatile private var camera: Camera? = null
    @Volatile private var imageCapture: ImageCapture? = null
    @Volatile private var analysis: ImageAnalysis? = null

    // Keeps photos upright however the phone is held, without recreating the activity.
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val rotation = when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            imageCapture?.targetRotation = rotation
            analysis?.targetRotation = rotation
        }
    }

    fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            val frames = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(960, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            frames.setAnalyzer(executor) { image -> image.use { onFrame(it) } }

            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, frames)
            imageCapture = capture
            analysis = frames
            orientationListener.enable()
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        orientationListener.disable()
        executor.shutdown()
    }

    private fun onFrame(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAt < PREVIEW_INTERVAL_MS) return
        lastFrameAt = now
        latestFrame.set(image.toBitmap().rotate(image.imageInfo.rotationDegrees).toJpeg(60))
    }

    /** Latest low-resolution JPEG frame, or null before the camera starts. */
    fun latestPreview(): ByteArray? = latestFrame.get()

    /** Takes a full-quality photo and returns it as a JPEG sized for Claude's vision input. */
    suspend fun capture(): ByteArray = suspendCancellableCoroutine { cont ->
        val ic = imageCapture
        if (ic == null) {
            cont.resumeWithException(IllegalStateException("Camera is not ready yet"))
            return@suspendCancellableCoroutine
        }
        ic.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val result = runCatching {
                    image.use { it.toBitmap().rotate(it.imageInfo.rotationDegrees) }
                        .scaleToMaxEdge(CAPTURE_MAX_EDGE)
                        .toJpeg(90)
                }
                result.onSuccess { cont.resume(it) }.onFailure { cont.resumeWithException(it) }
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
    }

    fun setTorch(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }

    fun setZoom(ratio: Float) {
        val (min, max) = zoomRange()
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(min, max))
    }

    fun zoomRange(): Pair<Float, Float> =
        camera?.cameraInfo?.zoomState?.value?.let { it.minZoomRatio to it.maxZoomRatio } ?: (1f to 1f)

    /** Re-focuses on the centre of the frame. */
    fun focusCenter() {
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    companion object {
        private const val PREVIEW_INTERVAL_MS = 150L
        // Plenty for reading text, and keeps the photo near 2,000 input tokens.
        private const val CAPTURE_MAX_EDGE = 1568
    }
}
