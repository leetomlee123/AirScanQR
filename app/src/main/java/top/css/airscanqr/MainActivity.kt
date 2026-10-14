package top.css.airscanqr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AirScanQRApp() }
    }
}

@Composable
private fun AirScanQRApp() {
    val session = remember { TransferSession() }
    var status by remember { mutableStateOf("等待扫描二维码...") }
    var progress by remember { mutableStateOf(0f) }
    var receivedCount by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }
    var filename by remember { mutableStateOf("unnamed_file.txt") }
    var isComplete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isScanning by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        isScanning = true
    }

    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "AirScan QR",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        if (error != null) {
            Text(
                text = error!!,
                color = Color.Red,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        } else if (session.taskId == null) {
            Text(
                text = "请用网页端发送文件",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "文件: $filename",
                fontSize = 18.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Text(
                text = "进度: ${session.receivedCount}/$totalCount 帧",
                fontSize = 16.sp,
                color = if (isComplete) Color.Green else MaterialTheme.colorScheme.primary
            )

            if (totalCount > 0) {
                LinearProgressIndicator(
                    progress = { session.receivedCount.toFloat() / totalCount },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = { session.reset(); error = null; isScanning = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("重置")
            }
        }

        // Camera preview area
        CameraPreview(
            onFrame = { raw ->
                val result = session.ingest(raw)
                when (result) {
                    TransferSession.IngestResult.Accepted -> {
                        receivedCount = session.receivedCount
                        totalCount = session.totalFrames
                        isComplete = session.isComplete
                        if (isComplete) {
                            isScanning = false
                            status = "扫描完成，准备保存..."
                        }
                    }
                    TransferSession.IngestResult.Duplicate -> {
                        receivedCount = session.receivedCount
                        totalCount = session.totalFrames
                    }
                    TransferSession.IngestResult.ForeignTask -> {
                        error = "检测到其他二维码流，正在重置..."
                        session.reset()
                    }
                    TransferSession.IngestResult.Ignored -> {}
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
        )

        Button(
            onClick = {
                if (isComplete) {
                    saveFile(context, session)
                }
            },
            enabled = isComplete,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isComplete) "保存文件" else "正在扫描...")
        }
    }
}

@Composable
private fun CameraPreview(
    onFrame: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build()
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                scope.launch {
                    analyzeBarcode(imageProxy, onFrame)
                }
                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                context as LifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(
        modifier = modifier
    ) {
        // Empty box for preview space
    }
}

private suspend fun analyzeBarcode(
    imageProxy: ImageProxy,
    onFrame: (String) -> Unit
) {
    val mediaImage = imageProxy.image ?: return
    val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

    val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    val scanner = BarcodeScanning.getClient(options)
    val result = scanner.process(inputImage)

    result.addOnSuccessListener { barcodes ->
        for (barcode in barcodes) {
            val rawValue = barcode.rawValue ?: continue
            if (rawValue.contains("|")) {
                onFrame(rawValue)
            }
        }
    }.addOnFailureListener {
        // ignore scan failures
    }.addOnCompleteListener {
        imageProxy.close()
    }
}

private fun saveFile(context: Context, session: TransferSession) {
    val fileBytes = session.assemble()
    val filename = session.fileName.ifBlank { "downloaded_file.bin" }

    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        type = "*/*"
        putExtra(Intent.EXTRA_TITLE, filename)
    }

    val chooserIntent = Intent.createChooser(intent, "保存文件")
    val startForResult = (context as? ComponentActivity)?.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri: Uri? = result.data?.data
            uri?.let { saveToUri(context, it, fileBytes) }
        }
    }

    startForResult?.launch(chooserIntent)
}

private fun saveToUri(context: Context, uri: Uri, bytes: ByteArray) {
    val contentResolver = context.contentResolver
    contentResolver.openOutputStream(uri)?.use { output ->
        output.write(bytes)
    }
}
