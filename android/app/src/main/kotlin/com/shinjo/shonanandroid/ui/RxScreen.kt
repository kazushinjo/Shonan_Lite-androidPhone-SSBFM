package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.rx.RxVideoView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CardBackground = Color(0xFF191D1F)
private val DividerColor = Color(0xFF303538)
private val CaptionColor = Color(0xFF9AA0A6)
private val SecondaryButtonColor = Color(0xFF303538)
private val StartColor = Color(0xFF1677FF)
private val StopColor = Color(0xFFD02020)
private val LockedGreen = Color(0xFF20C020)
private val LockBadgeGreen = Color(0xFF20A040)
private val IdleGray = Color(0xFF5A5A5A)

/**
 * 受信画面 -- Shonan_Lite-pi5(`pi5/gui/screens/rx.py`)と同じカードレイアウトに統一。
 * 左に固定幅ダークステータスカード(周波数/シンボルレート/変調方式/FECの2x2グリッド、
 * 区切り線、統計2x2グリッド)、右に受信映像、下段に音量・ボタン行を並べる
 * ([[shonan-lite-ipad-tx-rx-pi5-design]]と同様の移植)。ロックすると自動で全画面表示に
 * なり、映像をタップすると5秒間だけ状態カード・ボタンを表示してから(ロックが継続して
 * いれば)自動で全画面表示に戻る -- タップごとにこの5秒をリスタートする。
 */
@Composable
fun RxScreen(
    viewModel: AppViewModel,
    onNavigate: (String) -> Unit,
    onFullscreenChanged: (Boolean) -> Unit = {},
) {
    val settings = viewModel.settings
    val rx = viewModel.rxController
    val running = viewModel.isReceiving
    val preparing = viewModel.isPreparingRx
    var fullscreen by remember { mutableStateOf(false) }
    var revealJob by remember { mutableStateOf<Job?>(null) }
    var lockLossJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    // ★全画面表示中はHomeScreen側の上部バー・TabRowも含めて隠したいという要望のため、
    // このfullscreen状態をHomeScreenへ伝える(タブが消えても操作はタップで復帰可能な
    // ため問題ない、との利用者の判断による)。
    LaunchedEffect(fullscreen) { onFullscreenChanged(fullscreen) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onFullscreenChanged(false) } }

    // ロックは一瞬だけ外れて戻ることがあるため、解除直後に即座に全画面を
    // 解除せず一定時間ロック復帰を待つ(ヒステリシス)。再ロックした場合は
    // その待機をキャンセルして即時全画面に戻す。これがないと信号マージンが
    // 少ないときに全画面表示が短時間で何度も切り替わってしまう。
    LaunchedEffect(rx.isLocked) {
        if (rx.isLocked) {
            lockLossJob?.cancel()
            fullscreen = true
        } else {
            lockLossJob?.cancel()
            lockLossJob = scope.launch {
                delay(1_500)
                if (!rx.isLocked) fullscreen = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!fullscreen) {
                Column(
                    modifier = Modifier
                        .width(270.dp)
                        .fillMaxHeight()
                        .background(CardBackground, RoundedCornerShape(12.dp))
                        .padding(10.dp, 6.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(if (running) LockedGreen else IdleGray, CircleShape),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (running) settings.t("受信中", "Receiving") else settings.t("受信停止中", "Receive stopped"),
                            color = Color.White,
                            fontSize = 13.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (rx.isLocked) {
                            Box(
                                modifier = Modifier
                                    .background(LockBadgeGreen, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            ) {
                                Text("LOCK", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // ★受信できているかどうか(状態/パケット数)を最優先で確認できるよう、
                    // タブ化で縦幅が狭い中でもスクロールせず見える先頭に置く。
                    CompactFieldRow(
                        settings.t("状態", "State") to if (rx.isLocked) settings.t("接続中", "Connected") else settings.t("切断中", "Disconnected"),
                        settings.t("パケット数", "Packets") to "${rx.continuityTracker.totalPackets}",
                    )
                    CompactFieldRow(
                        settings.t("周波数", "Freq") to "${settings.effectiveLoHz / 1_000} kHz",
                        settings.t("SR", "SR") to "%.1f Msym/s".format(settings.symbolRateMsps),
                    )

                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(DividerColor))

                    CompactFieldRow(
                        settings.t("変調", "Mod") to settings.modulationScheme.label,
                        "FEC" to settings.fecRate.label,
                    )
                    CompactFieldRow(
                        settings.t("エラー", "Errors") to "${rx.continuityTracker.estimatedLostPackets}",
                        settings.t("TX pps", "TX pps") to
                            if (settings.useOnDeviceGRDVBS2Rx) "${viewModel.txController.stats.packetsPerSecond}" else "-",
                    )

                    viewModel.rxError?.let {
                        Text(
                            runtimeText(it, settings.language),
                            color = Color(0xFFF44336),
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Color.Black, RoundedCornerShape(10.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (rx.isLocked) {
                            fullscreen = false
                            revealJob?.cancel()
                            revealJob = scope.launch {
                                delay(5_000)
                                if (rx.isLocked) fullscreen = true
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // ★TxController.VIDEO_WIDTH/HEIGHT(1280x720=16:9)で送信元をエンコードしているため、
                // 表示側もfillMaxSize()で単純に引き伸ばさず、同じ16:9でアスペクト比を保って中央に
                // レターボックス表示する(そうしないと映像エリアの実際の縦横比によって円形の
                // テストパターンが楕円に歪む)。
                Box(modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)) {
                    RxVideoView(
                        modifier = Modifier.fillMaxSize(),
                        visible = fullscreen,
                        onSurfaceAvailable = { surface -> rx.displayDecoder.configure(surface) },
                        onSurfaceDestroyed = { rx.displayDecoder.stop() },
                    )
                }

                // ★タップして状態カード・ボタンを表示している間(fullscreen=false)は受信映像を
                // 隠す。TextureView自身のalpha/可視性は一切変更しない(HWUIのハードウェア
                // レイヤーがalpha=0後に描画を再開しない不具合があったため)。デコーダは裏で
                // そのまま動作を継続させ、単に不透明な黒Boxを上に重ねて見た目だけ隠す。
                if (!fullscreen) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                }

                // ★全画面表示中は状態カードが非表示になりロック状態が分からなくなるため、
                // 映像エリア自体にも常時ロック状態を示す小さなバッジを重ねて表示する。
                // 通常表示時(状態カードが見えている間)は状態カード側に同じLOCK表示が
                // あり二重表示になってしまうため、全画面時のみ表示する。
                if (fullscreen) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .background(
                                if (rx.isLocked) LockBadgeGreen else Color(0xB3000000),
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            if (rx.isLocked) "LOCK" else settings.t("未ロック", "No Lock"),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        if (!fullscreen) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(settings.t("音声レベル", "Audio Level"), color = Color.White, fontSize = 12.sp)
                }
                LinearProgressIndicator(
                    progress = { rx.audioLevel },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(settings.t("音量", "Volume"), color = Color.White, fontSize = 12.sp, modifier = Modifier.width(60.dp))
                    Slider(
                        value = settings.rxVolume,
                        onValueChange = { value -> viewModel.setRxVolume(value) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFFDDDDDD), activeTrackColor = StartColor),
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Button(
                        onClick = { if (running) viewModel.stopRX() else viewModel.startRX() },
                        enabled = !preparing,
                        colors = ButtonDefaults.buttonColors(containerColor = if (running) StopColor else StartColor),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            when {
                                preparing -> settings.t("準備中…", "Starting…")
                                running -> settings.t("受信停止", "Stop")
                                else -> settings.t("受信開始", "Start")
                            },
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (settings.useOnDeviceGRDVBS2Rx) {
                        SecondaryButton(settings.t("送信画面へ", "Transmit Screen")) {
                            onNavigate("tx")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    SecondaryButton(settings.t("設定", "Settings")) {
                        onNavigate("settings")
                    }
                }
            }
        }
    }
}

/** ラベルと値を1行にまとめた省スペース表示 -- タブ化で縦幅が狭いRX画面の状態カード専用。 */
@Composable
private fun CompactFieldRow(vararg fields: Pair<String, String>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        fields.forEach { (caption, value) ->
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(caption, color = CaptionColor, fontSize = 11.sp, lineHeight = 12.sp, maxLines = 1)
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    value,
                    color = Color.White,
                    fontSize = 12.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
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
