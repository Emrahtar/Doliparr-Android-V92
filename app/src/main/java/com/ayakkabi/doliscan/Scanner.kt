package com.ayakkabi.doliscan

import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

private class Last(var code: String = "", var time: Long = 0L)

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun BarcodeScanner(modifier: Modifier = Modifier, enabled: Boolean = true, onCode: (String) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val last = remember { Last() }
    val currentOnCode = rememberUpdatedState(onCode)
    val enabledState = rememberUpdatedState(enabled)
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_QR_CODE
            ).build()
        )
    }
    val providerHolder = remember { arrayOfNulls<ProcessCameraProvider>(1) }

    AndroidView(modifier = modifier, factory = { c ->
        val view = PreviewView(c)
        val future = ProcessCameraProvider.getInstance(c)
        future.addListener({
            val provider = future.get()
            providerHolder[0] = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val img = proxy.image
                if (img == null || !enabledState.value) {
                    proxy.close()
                } else {
                    val input = InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees)
                    scanner.process(input)
                        .addOnSuccessListener { list ->
                            val v = list.firstOrNull()?.rawValue
                            if (v != null) {
                                val now = System.currentTimeMillis()
                                // Aynı barkod görüntüde kaldıkça tekrar okunmaz; kamera önünden
                                // 1.5 sn çekilip tekrar getirilirse yeniden okunur.
                                val same = v == last.code && now - last.time < 1500
                                last.code = v; last.time = now
                                if (!same) currentOnCode.value(v)
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(c))
        view
    })

    DisposableEffect(Unit) {
        onDispose {
            providerHolder[0]?.unbindAll()
            executor.shutdown()
            scanner.close()
        }
    }
}
