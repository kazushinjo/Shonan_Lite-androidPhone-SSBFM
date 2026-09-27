package com.shinjo.shonanandroid.rx

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** RXの復号済みライブ映像を表示する[TextureView]をホストする --
 *  iOS版`DisplayLayerView.swift`(AVSampleBufferDisplayLayer)のAndroid対応。
 *  [H264DisplaySurface]自体は所有せず、Surfaceをコールバック経由で渡すだけの
 *  薄いビューホストに留める(CameraPreviewViewと対称的なパターン)。
 *  ★SurfaceViewではなくTextureViewを使う: SurfaceViewはウィンドウに対して透過の
 *  穴を開けて描画するため、ホーム画面のタブ切替でこの画面が単独のフルスクリーン
 *  destinationではなくタブ内コンテンツ(上部にTabRow等の兄弟Composableがある)に
 *  なった際、透過範囲の計算がComposeのレイアウト境界と食い違い、TabRowまで含めて
 *  黒く抜けてしまう不具合が起きた。TextureViewは通常のView合成に参加するため
 *  この問題が起きない([[shonan-lite-androidphone-target]]でのタブ化対応)。 */
@Composable
fun RxVideoView(
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextureView(context).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                        onSurfaceAvailable(Surface(surfaceTexture))
                    }

                    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}

                    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                        onSurfaceDestroyed()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
                }
            }
        },
        // ★ComposeのModifier.alpha(0f)で隠すとHWUIのハードウェアレイヤーが
        // 描画を再開しない不具合があったため使わなかったが、Compose側の不透明な
        // 黒Boxで覆うだけではOPPO/ColorOS実機でTextureViewのハードウェアレイヤーが
        // わずかに透けて見える(細い線状のノイズ)ことがあるため、AndroidView本来の
        // View.INVISIBLEでも隠す(こちらはView自体の描画がスキップされ再表示時も
        // 正常に再開する標準的な挙動のため、alphaのときの不具合とは無関係)。
        update = { view -> view.visibility = if (visible) View.VISIBLE else View.INVISIBLE },
    )
}
