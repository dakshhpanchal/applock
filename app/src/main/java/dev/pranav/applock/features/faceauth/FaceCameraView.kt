package dev.pranav.applock.features.faceauth

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/**
 * Front-camera preview that streams [FrameResult]s to [onResult] on the main thread.
 * Detection and embedding run on a private background thread.
 * Requires the CAMERA permission to already be granted.
 */
@Composable
fun FaceCameraView(
    modifier: Modifier = Modifier,
    showPreview: Boolean = true,
    onResult: (FrameResult, Long) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnResult by rememberUpdatedState(onResult)

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE // works inside clipped shapes
        }
    }

    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val embedder = FaceEmbedder(context)
        val processor = FaceFrameProcessor(embedder)
        var provider: ProcessCameraProvider? = null
        var disposed = false

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = future.get().also { provider = it }
                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(analysisExecutor, FaceCameraAnalyzer(processor) { result, ts ->
                            mainExecutor.execute { if (!disposed) latestOnResult(result, ts) }
                        })
                    }
                cameraProvider.unbindAll()
                if (showPreview) {
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
                    )
                } else {
                    // Analysis only: the camera runs but nothing is shown on screen.
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis
                    )
                }
            } catch (e: Exception) {
                Log.e("FaceCameraView", "Camera bind failed", e)
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            provider?.unbindAll()
            analysisExecutor.execute {
                processor.close()
                embedder.close()
            }
            analysisExecutor.shutdown()
        }
    }

    if (showPreview) AndroidView(factory = { previewView }, modifier = modifier)
}

/** User-facing text for a hint. */
fun Hint?.toMessage(default: String): String = when (this) {
    Hint.NO_FACE -> "Look at the camera"
    Hint.MULTIPLE_FACES -> "Only one face, please"
    Hint.MOVE_CLOSER -> "Move closer"
    Hint.LOOK_STRAIGHT -> "Look straight at the phone"
    Hint.HOLD_STILL -> "Hold still"
    Hint.BLINK -> "Now blink"
    Hint.KEEP_EYES_OPEN -> "Keep your eyes open"
    Hint.TURN_SLIGHTLY -> "Turn your head slightly"
    null -> default
}
