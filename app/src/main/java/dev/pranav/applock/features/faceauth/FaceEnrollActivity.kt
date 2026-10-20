package dev.pranav.applock.features.faceauth

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.ui.theme.AppLockTheme

/** Captures the user's face and saves the encrypted templates. Turns face unlock on when done. */
class FaceEnrollActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dev.pranav.applock.ui.theme.lockBackgroundColor(this)))
        setContent {
            AppLockTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    FaceEnrollScreen(onDone = ::finish)
                }
            }
        }
    }
}

@Composable
private fun FaceEnrollScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val modelOk = remember { FaceEmbedder.isModelBundled(context) }
    var hasPermission by remember { mutableStateOf(FaceUnlock.hasCameraPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Set up face unlock", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Uses the front camera. Less secure than fingerprint: a video of you could fool it.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        when {
            !modelOk -> Text(
                "Face model missing. Add mobilefacenet.tflite to app/src/main/assets and rebuild.",
                color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
            )
            !hasPermission -> {
                Text("Camera permission is needed.", textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera access")
                }
            }
            else -> EnrollCamera(onDone)
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onDone) { Text("Cancel") }
    }
}

@Composable
private fun EnrollCamera(onDone: () -> Unit) {
    val context = LocalContext.current
    val enroller = remember { FaceEnroller(targetSamples = 9, poses = Pose.GUIDED) }
    var captured by remember { mutableIntStateOf(0) }
    var target by remember { mutableIntStateOf(1) }
    var message by remember { mutableStateOf("Look at the camera") }
    var saved by remember { mutableStateOf(false) }

    FaceCameraView(
        modifier = Modifier.size(260.dp).clip(CircleShape),
        maxYawDeg = 35f, // allow side views while enrolling
        onError = { message = "Camera or face model unavailable. Close and try again." },
        onResult = { result, ts ->
            if (saved) return@FaceCameraView
            when (val s = enroller.onFrame(result, ts)) {
                is FaceEnroller.Status.Progress -> {
                    captured = s.captured
                    target = s.target
                    message = s.hint.toMessage(
                        if (s.captured == 0) "Look at the camera" else "Good, move your head a little"
                    )
                }
                is FaceEnroller.Status.Done -> {
                    saved = true
                    captured = s.templates.size
                    target = s.templates.size
                    try {
                        FaceTemplateStore(context).save(s.templates)
                        context.appLockRepository().apply {
                            setFaceUnlockEnabled(true)
                            setBiometricAuthEnabled(false) // only one biometric mode at a time
                        }
                        Toast.makeText(context, "Face unlock is on", Toast.LENGTH_SHORT).show()
                        onDone()
                    } catch (e: Exception) {
                        saved = false
                        enroller.reset()
                        message = "Couldn't save. Try again."
                    }
                }
            }
        },
    )

    Spacer(Modifier.height(24.dp))
    LinearProgressIndicator(
        progress = { captured.toFloat() / target },
        modifier = Modifier.fillMaxWidth(0.7f),
    )
    Spacer(Modifier.height(8.dp))
    Text("$captured / $target", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(16.dp))
    Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
}
