@file:OptIn(androidx.camera.core.ExperimentalGetImage::class)
package de.rj.versandhilfe

import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Camera frames are decoded on device. Only the selected captured image enters normal intake. */
@Composable fun LiveScanner(onDismiss: () -> Unit, onCaptured: (Uri, List<String>, File) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var error by remember { mutableStateOf("") }
    var capturing by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    val captured by rememberUpdatedState(onCaptured)
    DisposableEffect(lifecycle) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val scanner = BarcodeScanning.getClient()
        val disposed = AtomicBoolean(false)
        val found = AtomicBoolean(false)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        analysis.setAnalyzer(executor) { frame ->
            val image = frame.image
            if (image == null || disposed.get() || found.get()) frame.close()
            else scanner.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                .addOnSuccessListener(main) { values ->
                    val codes = values.mapNotNull { it.rawValue }.distinct().filter { it.length <= 12000 }.take(20)
                    if (codes.isNotEmpty() && !disposed.get() && found.compareAndSet(false, true)) {
                        capturing = true
                        val file = File(context.cacheDir, "shared/scan-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
                        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), main, object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                if (disposed.get()) file.delete() else captured(Uri.fromFile(file), codes, file)
                            }
                            override fun onError(exception: ImageCaptureException) {
                                file.delete()
                                if (!disposed.get()) { capturing = false; found.set(false); error = "Foto nicht gespeichert. Bitte noch einmal versuchen." }
                            }
                        })
                    }
                }.addOnCompleteListener(main) { frame.close() }
        }
        future.addListener({
            if (!disposed.get()) runCatching {
                provider = future.get()
                camera = provider!!.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
            }.onFailure { error = "Kamera nicht verfügbar. Du kannst stattdessen ein Foto auswählen." }
        }, main)
        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            provider?.unbind(preview, capture, analysis)
            scanner.close()
            executor.shutdown()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Scanner schließen") }
                Text("QR-Code scannen", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconToggleButton(checked = torch, onCheckedChange = { torch = it; camera?.cameraControl?.enableTorch(it) }, enabled = camera?.cameraInfo?.hasFlashUnit() == true) { Icon(Icons.Outlined.FlashlightOn, "Licht") }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                Box(Modifier.size(260.dp).border(3.dp, Color.White, RoundedCornerShape(28.dp)))
                if (capturing) CircularProgressIndicator(color = Color.White)
            }
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (capturing) "Code erkannt. Foto wird gespeichert …" else "Halte den Code ruhig in die Kamera.", style = MaterialTheme.typography.titleMedium)
                Text("Die App liest den Code zuerst auf deinem Handy. Im KI-Modus werden danach das Foto und die gelesenen Daten ausgewertet.")
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onDismiss) { Text("Zurück") }
            }
        }
    }
}
