package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.net.PlutoDiscoveryClient
import com.shinjo.shonanandroid.net.PlutoRebootController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 起動時に開くタブ(一番左のSSB/FM)。 */
private const val START_ROUTE = "ssbfm"

private data class HomeTab(val titleJA: String, val titleEN: String, val route: String) {
    /** 見た目の区切りのためだけに1タブ分の空白を置く、押せないダミータブ。 */
    val isSpacer: Boolean get() = route.isEmpty()
}

/** タブ形式のホーム画面に表示する機能一覧(機器試験はAndroid電話版では非搭載)。 */
private val homeTabs = listOf(
    HomeTab("SSB/FM", "SSB/FM", "ssbfm"),
    HomeTab("", "", ""), // SSB/FMとDATV側のタブの間を1タブ分あける
    HomeTab("送信", "Transmit", "tx"),
    HomeTab("受信", "Receive", "rx"),
    HomeTab("RSSI測定", "RSSI", "rssi"),
    HomeTab("周波数", "Frequency", "frequency"),
    HomeTab("設定１", "Config 1", "settings"),
    HomeTab("設定２", "Config 2", "gainpower"),
    HomeTab("設定３", "Config 3", "modcod"),
    HomeTab("設定４", "Config 4", "sourceoutput"),
    HomeTab("ヘルプ", "Help", "help"),
)

/**
 * PlutoへSSH経由でreboot要求を送り、Web UIとiiod両方の復旧を確認するまで待つ
 * -- iPad版`ContentView.runPlutoStartupReboot()`と同じ処理。起動時の初回実行と、
 * ホーム画面の「アプリ再起動」ボタンの両方から呼ぶ共通処理とする。
 */
private suspend fun runPlutoStartupReboot(host: String) {
    val trimmed = host.trim()
    if (trimmed.isEmpty()) return
    val started = PlutoRebootController.rebootAtStartup(trimmed, timeoutMillis = 20_000L)
    if (started) {
        PlutoRebootController.waitUntilOnline(trimmed) { }
    }
}

/**
 * 電話版ホーム画面 -- タブレット/pi5と同じ背景画像+座標タップ方式ではなく、画面上部の
 * スクロール可能なTabRowで機能を切り替える単一画面構成にする(電話の狭い画面幅では
 * 固定座標のカードレイアウトが崩れるため)。タブの内容は各機能の既存Composableをそのまま
 * 埋め込み、画面間の遷移は[onNavigate]でタブの選択を切り替えるだけにする。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: AppViewModel) {
    val settings = viewModel.settings
    val scope = rememberCoroutineScope()
    var showQuitConfirmation by remember { mutableStateOf(false) }
    var selectedRoute by remember { mutableStateOf(START_ROUTE) }
    // ★受信タブが全画面表示になっている間は、この上部バー・TabRowも含めて隠す
    // (利用者の要望: 「タブの表示が消えても問題ない」)。他タブへ切り替えたら解除する。
    var rxFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(selectedRoute) {
        if (selectedRoute != "rx") rxFullscreen = false
    }
    // iPad版の`startupState`に相当。起動時の初回実行と「アプリ再起動」ボタンの
    // 両方がこの同じフルスクリーン表示をトリガーする。
    var plutoRebootInProgress by remember { mutableStateOf(!viewModel.didRunStartupPlutoReboot) }

    LaunchedEffect(Unit) {
        if (!viewModel.didRunStartupPlutoReboot) {
            runPlutoStartupReboot(settings.txDestinationIP)
            viewModel.didRunStartupPlutoReboot = true
            plutoRebootInProgress = false
        }
    }

    // 起動時に一度だけPluto自動検出を試みる: まず端末自身の接続中サブネットを
    // スキャンし(一般的なWiFiルーター運用でも動作)、見つからなければESP32ブリッジ
    // (hardware/ESP32_WiFi_Ethernet_Bridge)のAPIにフォールバックする。どちらも
    // 見つからない場合は数秒で失敗するだけなので、UIをブロックせずエラー表示もしない。
    val discoveryContext = LocalContext.current
    LaunchedEffect(Unit) {
        if (!viewModel.didRunStartupPlutoDiscovery) {
            viewModel.didRunStartupPlutoDiscovery = true
            val found = PlutoDiscoveryClient.discoverPlutoIp(discoveryContext)
            if (found != null && found != settings.txDestinationIP) {
                viewModel.updateSettings { s -> s.copy(txDestinationIP = found) }
            }
        }
    }

    val onNavigate: (String) -> Unit = { route -> selectedRoute = route }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            if (!rxFullscreen) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (selectedRoute == "ssbfm") {
                            "SSB/FM ${viewModel.ssbFm.settings.frequencyHz / 1_000} kHz"
                        } else {
                            "${settings.effectiveLoHz / 1_000} kHz"
                        },
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        if (viewModel.isTransmitting) viewModel.stopTX()
                        if (viewModel.isReceiving) viewModel.stopRX()
                        viewModel.ssbFm.stop()
                        viewModel.ssbFm.onPlutoDatvRestarted()
                        plutoRebootInProgress = true
                        scope.launch(Dispatchers.IO) {
                            runPlutoStartupReboot(settings.txDestinationIP)
                            withContext(Dispatchers.Main) {
                                plutoRebootInProgress = false
                            }
                        }
                    }) {
                        Text("⟲ " + settings.t("アプリ再起動", "App Restart"), color = Color.White, fontSize = 12.sp)
                    }
                    TextButton(onClick = { showQuitConfirmation = true }) {
                        PowerIcon(color = Color(0xFFF05A45), modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(settings.t("終了", "Quit"), color = Color(0xFFF05A45), fontSize = 12.sp)
                    }
                }

                val selectedIndex = homeTabs.indexOfFirst { it.route == selectedRoute }.let { if (it < 0) 0 else it }
                ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    modifier = Modifier.height(40.dp),
                    containerColor = Color(0xFF101416),
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        with(TabRowDefaults) {
                            SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                                color = Color(0xFF87CEEB),
                            )
                        }
                    },
                ) {
                    homeTabs.forEachIndexed { index, tab ->
                        val isSelected = index == selectedIndex
                        if (tab.isSpacer) {
                            Tab(selected = false, onClick = {}, enabled = false, text = {})
                            return@forEachIndexed
                        }
                        Tab(
                            selected = isSelected,
                            onClick = { selectedRoute = tab.route },
                            text = {
                                Text(
                                    settings.t(tab.titleJA, tab.titleEN),
                                    color = if (isSelected) Color(0xFF87CEEB) else Color.White,
                                    fontSize = if (isSelected) 13.sp else 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                        )
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (selectedRoute) {
                    "tx" -> TxScreen(viewModel, onNavigate)
                    "rx" -> RxScreen(viewModel, onNavigate, onFullscreenChanged = { rxFullscreen = it })
                    "ssbfm" -> SsbFmScreen(viewModel)
                    "frequency" -> FrequencySettingsScreen(viewModel, onNavigate)
                    "gainpower" -> GainPowerSettingsScreen(viewModel)
                    "modcod" -> ModCodSettingsScreen(viewModel)
                    "sourceoutput" -> SourceOutputSettingsScreen(viewModel)
                    "rssi" -> RssiScreen(viewModel)
                    "settings" -> SettingsScreen(viewModel, onNavigate)
                    "help" -> HelpScreen(viewModel, onNavigate)
                }
            }
        }

        if (plutoRebootInProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = settings.t("Pluto再起動中…", "Rebooting Pluto…"),
                        color = Color.White,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        text = settings.t("20秒以内に開始できない場合は起動を続行します", "The app will continue after 20 seconds if reboot does not start."),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }

    if (showQuitConfirmation) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showQuitConfirmation = false }) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF101416), RoundedCornerShape(12.dp))
                    .padding(30.dp, 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PowerIcon(color = Color(0xFFF05A45), modifier = Modifier.size(56.dp))
                Text(
                    settings.t("プログラムを終了します。\nよろしいですか？", "The program will quit.\nAre you sure?"),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    androidx.compose.material3.Button(
                        onClick = { android.os.Process.killProcess(android.os.Process.myPid()) },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFFF05A45)),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(settings.t("終了する", "Quit"), fontWeight = FontWeight.Bold)
                    }
                    androidx.compose.material3.OutlinedButton(
                        onClick = { showQuitConfirmation = false },
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B5159)),
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(settings.t("キャンセル", "Cancel"), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * 一般的な電源記号(丸に上部の切れ目+縦線)をCanvasで直接描画する -- Unicode文字
 * ⏻(U+23FB)は実機フォントに含まれずグリフ欠落(豆腐)表示になったため、フォント
 * 依存を避けてベクター描画にした。material-icons-extendedのPowerSettingsNewは
 * 見た目は同じだが依存追加が大きいため、単純な形状は自前で描く。
 */
@Composable
private fun PowerIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.12f
        val inset = strokeWidth / 2f
        val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = arcSize,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.5f),
            strokeWidth = strokeWidth,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}
