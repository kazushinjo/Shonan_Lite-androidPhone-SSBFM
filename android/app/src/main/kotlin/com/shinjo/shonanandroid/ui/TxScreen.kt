package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.tx.CameraPosition
import com.shinjo.shonanandroid.tx.CameraPreviewView
import com.shinjo.shonanandroid.tx.ColorBarPreview
import com.shinjo.shonanandroid.tx.PhotoPreview

private val CardBackground = Color(0xFF191D1F)
private val DividerColor = Color(0xFF303538)
private val CaptionColor = Color(0xFF9AA0A6)
private val SecondaryButtonColor = Color(0xFF303538)
private val StartColor = Color(0xFF1677FF)
private val StopColor = Color(0xFFD02020)
private val LockedGreen = Color(0xFF20C020)
private val IdleGray = Color(0xFF5A5A5A)

/**
 * 送信画面 -- Shonan_Lite-pi5(`pi5/gui/screens/tx.py`)と同じカードレイアウトに統一。
 * 左に映像プレビュー、右に固定幅ダークステータスカード(周波数/シンボルレート/変調方式/FECの
 * 2x2グリッド、区切り線、統計2x2グリッド)、下段にボタン行を並べる([[shonan-lite-ipad-tx-rx-pi5-design]]と同様の移植)。
 */
@Composable
fun TxScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit) {
    val settings = viewModel.settings
    val stats = viewModel.activeTxStats
    val running = viewModel.isTransmitting
    val preparing = viewModel.isPreparingTx

    // iPad版`TxView`のonAppear/onDisappearと同じく、送信中でない間もカメラ映像を
    // 確認できるよう、画面表示中だけカメラプレビューを起動する。
    DisposableEffect(settings.useFrontCamera, settings.useColorBarSource, settings.usePhotoSource, running) {
        val isCameraSource = !settings.useColorBarSource && !settings.usePhotoSource
        if (isCameraSource && !running) {
            viewModel.txController.startCameraPreview(if (settings.useFrontCamera) CameraPosition.FRONT else CameraPosition.BACK)
        } else {
            viewModel.txController.stopCameraPreviewIfIdle()
        }
        onDispose { viewModel.txController.stopCameraPreviewIfIdle() }
    }

    val statusScrollState = rememberScrollState()
    // ★エラーはステータスカードの一番下に表示されるため、カード内容が縦に
    // 収まらずスクロールが必要な状態だとエラーが画面外に隠れて気付けない。
    // エラー発生時は自動で一番下までスクロールして必ず見えるようにする。
    LaunchedEffect(viewModel.txError) {
        if (viewModel.txError != null) {
            statusScrollState.animateScrollTo(statusScrollState.maxValue)
        }
    }

    // ★電話の横画面は高さが小さく、以前の「上段: プレビュー+状態カード / 下段: 全幅のボタン行」では
    // 状態カードの高さが足りず、下の行(変調方式・FEC以降)がスクロールしないと見えなかった(実機で確認)。
    // ボタン行はプレビューの下だけに置き、状態カードは右側で上端から下端まで使う。
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // ★RxScreenの受信映像と同様、送信元も1280x720(16:9)でエンコードされるため
                // fillMaxSize()で単純に引き伸ばさず、同じ16:9でアスペクト比を保って中央に
                // レターボックス表示する([[shonan-lite-androidphone-target]]でのRX側修正と対称)。
                Box(modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)) {
                    if (settings.usePhotoSource) {
                        PhotoPreview(
                            uri = settings.selectedPhotoUri,
                            callsign = settings.photoCallsign,
                            note = settings.photoNote,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (settings.useColorBarSource) {
                        ColorBarPreview(modifier = Modifier.fillMaxSize())
                    } else {
                        CameraPreviewView(preview = viewModel.activeTxPreview, modifier = Modifier.fillMaxSize())
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Button(
                    onClick = { if (running) viewModel.stopTX() else viewModel.startTX() },
                    enabled = !preparing,
                    colors = ButtonDefaults.buttonColors(containerColor = if (running) StopColor else StartColor),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        when {
                            preparing -> settings.t("準備中…", "Starting…")
                            running -> settings.t("送信停止", "Stop")
                            else -> settings.t("送信開始", "Start")
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (settings.useOnDeviceGRDVBS2Rx) {
                    SecondaryButton(settings.t("受信画面へ", "Receive Screen")) {
                        onNavigate("rx")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                SecondaryButton(settings.t("設定", "Settings")) {
                    onNavigate("settings")
                }
            }
        }

            Column(
                modifier = Modifier
                    .width(360.dp)
                    .fillMaxHeight()
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .padding(14.dp, 10.dp)
                    // ★内容(4行のStatusFieldRow+区切り線+接続状態+音声レベル)が
                    // カードの高さを超えると、はみ出した行のテキストが完全に描画されず
                    // 消えてしまう(実機で確認済み: 2番目のStatusFieldRowが常に消えていた)。
                    // RxScreenの状態カードと同様にverticalScrollを付けて回避する。
                    .verticalScroll(statusScrollState),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(if (running) LockedGreen else IdleGray, CircleShape),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (running) settings.t("送信中", "Transmitting") else settings.t("送信停止中", "Transmit stopped"),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (running) {
                        Box(
                            modifier = Modifier
                                .background(StopColor, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("ON AIR", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 項目は3列×3行に並べ、縦に収める(値は折り返さず1行で表示)。
                StatusFieldRow(
                    settings.t("周波数", "Frequency") to "${settings.effectiveLoHz / 1_000} kHz",
                    settings.t("シンボルレート", "Symbol rate") to "%.1f Msym/s".format(settings.symbolRateMsps),
                    settings.t("変調方式", "Modulation") to settings.modulationScheme.label,
                )
                StatusFieldRow(
                    "FEC" to settings.fecRate.label,
                    settings.t("出力減衰量", "Attenuation") to "${settings.txPowerDb} dB",
                    settings.t("ビットレート", "Bitrate") to "%.2f Mbps".format(stats.bitsPerSecond / 1_000_000),
                )
                StatusFieldRow(
                    settings.t("合計パケット数", "Total packets") to "${stats.totalPackets}",
                    settings.t("パケット/秒", "Packets/sec") to "${stats.packetsPerSecond}",
                    settings.t("接続", "Link") to
                        (if (stats.isConnected) settings.t("接続中", "Connected") else settings.t("切断中", "Disconnected")),
                )

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(DividerColor))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(settings.t("音声レベル", "Audio Level"), color = CaptionColor, fontSize = 11.sp, lineHeight = 13.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    LinearProgressIndicator(
                        progress = { stats.audioLevel },
                        modifier = Modifier.weight(1f).height(8.dp),
                    )
                }

                viewModel.txError?.let {
                    Text(
                        settings.t("エラー: ${runtimeText(it, settings.language)}", "Error: ${runtimeText(it, settings.language)}"),
                        color = Color(0xFFF44336),
                        fontSize = 12.sp,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
    }
}

@Composable
private fun StatusFieldRow(vararg fields: Pair<String, String>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fields.forEach { (caption, value) ->
            Column(modifier = Modifier.weight(1f)) {
                // ★行の高さ(lineHeight)を指定しないとテーマ既定の24spになり、文字を小さくしても
                // 1行が高いままでカードに収まらない(実機で確認)。文字サイズに見合う値にする。
                Text(caption, color = CaptionColor, fontSize = 11.sp, lineHeight = 13.sp,
                    maxLines = 1, softWrap = false)
                // ★値が折り返すとカードの高さに収まらなくなるため1行に固定する。
                Text(value, color = Color.White, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = SecondaryButtonColor),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
