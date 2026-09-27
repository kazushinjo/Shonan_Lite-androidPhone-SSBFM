package com.shinjo.shonanandroid.tx

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView

/** CameraXの[PreviewView]をホストする -- iOS版`CameraPreviewView.swift`
 *  (AVCaptureVideoPreviewLayer)のAndroid対応。[preview]はこのcomposableより
 *  長生き(AppViewModelがProcessLifecycleOwnerに束縛して保持)するため、
 *  画面遷移後もゴースト表示が残らないようdispose時に明示的にsurface providerを解除する。 */
@Composable
fun CameraPreviewView(preview: Preview, modifier: Modifier = Modifier) {
    AndroidView(
        // ★カメラの映像(4:3)を16:9の枠いっぱいに収める既定のFILL_CENTERでは、映像を拡大して
        // 上下(または左右)を切り落とす。その切り落とし部分が枠でクリップされずに外へ描画され、
        // 送信タブで上部のタブ文字や下部の「送信開始」ボタンに重なっていた(電話実機で確認)。
        // 枠の範囲だけに描画するようクリップする(TextureViewモードなのでComposeのクリップが効く)。
        modifier = modifier.clipToBounds(),
        factory = { context ->
            PreviewView(context).also { previewView ->
                // SurfaceViewはComposeのテキストより前面に残ることがあるため、
                // 診断画面でもレイアウト順を守るTextureViewモードを使う。
                previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                preview.setSurfaceProvider(previewView.surfaceProvider)
            }
        },
        update = { previewView ->
            preview.setSurfaceProvider(previewView.surfaceProvider)
        },
    )
    DisposableEffect(preview) {
        onDispose {
            preview.setSurfaceProvider(null)
        }
    }
}
