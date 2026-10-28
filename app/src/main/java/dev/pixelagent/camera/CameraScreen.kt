package dev.pixelagent.camera

import android.graphics.BitmapFactory
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun CameraScreen(modifier: Modifier = Modifier, onClose: () -> Unit, onSend: (String, Snapshot) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val preview = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var front by remember { mutableStateOf(false) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("Opening camera…") }
    var question by remember { mutableStateOf("What do you see in this image?") }
    BackHandler { onClose() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onClose() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(front, snapshot != null) {
        var active = true
        var provider: ProcessCameraProvider? = null
        var boundPreview: Preview? = null
        var boundCapture: ImageCapture? = null
        var cameraInfo: CameraInfo? = null
        var cameraObserver: Observer<CameraState>? = null
        capture = null
        if (snapshot == null) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (active) runCatching {
                    val cameraProvider = future.get().also { provider = it }
                    val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    check(cameraProvider.hasCamera(selector)) { "Selected camera is unavailable. Try the other camera." }
                    val view = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider); boundPreview = it }
                    val still = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(preview.display?.rotation ?: Surface.ROTATION_0).build().also { boundCapture = it }
                    val camera = cameraProvider.bindToLifecycle(lifecycle, selector, view, still)
                    cameraInfo = camera.cameraInfo
                    val observer = Observer<CameraState> { state ->
                        if (active) {
                            capture = still.takeIf { state.type == CameraState.Type.OPEN && state.error == null }
                            notice = when {
                                state.error != null -> "Camera unavailable. Cancel and check camera access, then retry."
                                state.type == CameraState.Type.OPEN -> "Camera active · Preview stays on this phone."
                                else -> "Camera ${state.type.name.lowercase()} · Nothing uploaded."
                            }
                        }
                    }
                    cameraObserver = observer
                    camera.cameraInfo.cameraState.observe(lifecycle, observer)
                }.onFailure { notice = it.message ?: "Camera unavailable. Check camera permission." }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose {
            active = false
            capture = null
            cameraObserver?.let { cameraInfo?.cameraState?.removeObserver(it) }
            val uses = listOfNotNull(boundPreview, boundCapture)
            if (uses.isNotEmpty()) provider?.unbind(*uses.toTypedArray())
        }
    }
    Column(modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (snapshot == null) "Camera" else "Review snapshot · Camera off", style = MaterialTheme.typography.headlineSmall)
        Text(notice)
        val reviewed = snapshot
        if (reviewed == null) AndroidView(factory = { preview }, modifier = Modifier.weight(1f).fillMaxWidth())
        else {
            val bitmap = remember(reviewed) { BitmapFactory.decodeByteArray(reviewed.jpeg, 0, reviewed.jpeg.size) }
            DisposableEffect(bitmap) { onDispose { bitmap.recycle() } }
            Image(bitmap.asImageBitmap(), "Snapshot to send", Modifier.weight(1f).fillMaxWidth())
            Text("${reviewed.width} × ${reviewed.height} · ${reviewed.jpeg.size / 1024} KB. Send uploads this image, question and conversation context to your selected ChatGPT model. Image bytes are discarded after this turn; text history remains on this phone.")
            OutlinedTextField(question, { question = it.take(4000) }, label = { Text("Ask about this image") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Button(onClick = { onSend(question, reviewed) }, enabled = question.isNotBlank()) { Text("Send snapshot to model") }
            TextButton(onClick = { snapshot = null; notice = "Opening camera…" }) { Text("Retake") }
        }
        if (reviewed == null) {
            OutlinedButton(onClick = { front = !front }, enabled = !capturing) { Text(if (front) "Use rear camera" else "Use front camera") }
            Button(enabled = capture != null && !capturing, onClick = {
                val still = capture ?: return@Button
                capturing = true
                notice = "Capturing locally…"
                val directory = File(context.cacheDir, "snapshots").apply { mkdirs() }
                val file = File.createTempFile("capture-", ".jpg", directory)
                still.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        // Scope is cancelled on dismissal; file cleanup still runs.
                        scope.launch {
                            try {
                                val encoded = withContext(Dispatchers.IO) { SnapshotEncoder.fromFile(file) }
                                snapshot = encoded
                                notice = "Snapshot ready · Camera off · Nothing uploaded yet."
                            } catch (_: Exception) { notice = "Capture could not be prepared. Try again." }
                            finally { file.delete(); capturing = false }
                        }.invokeOnCompletion { file.delete() }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        file.delete(); capturing = false; notice = "Capture failed. Check camera access and retry."
                    }
                })
            }) { Text(if (capturing) "Capturing…" else "Take snapshot") }
        }
        OutlinedButton(onClick = onClose) { Text("Cancel and discard") }
    }
}
