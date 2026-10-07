package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.RX_GAIN_MAX_DB
import com.shinjo.shonanandroid.RX_GAIN_MIN_DB
import com.shinjo.shonanandroid.RssiMeasurement
import com.shinjo.shonanandroid.core.AppSettings
import com.shinjo.shonanandroid.formatRssiValue
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

private val OuterCardBorder = Color(0xFF34434B)
private val InnerCardBackground = Color(0xFF191D1F)
private val TitleCyan = Color(0xFF54BCE0)
private val TextColor = Color(0xFFEEEEEE)
private val ActionButton = Color(0xFF0C91B5)
/** 測定中の「測定停止」ボタンの色(SSB/FMの送信中と同じ赤)。 */
private val MeasuringRed = Color(0xFFE53935)
private val ChipBackground = Color(0xFF303538)
private val SelectedText = Color(0xFF101416)
private val DisabledText = Color(0xFF6B7880)
private val ChartBackground = Color(0xFF050607)
private val ChartFrame = Color(0xFF46545B)
private val ChartGrid = Color(0xFF2A3236)
private val ChartLabel = Color(0xFF9AA0A6)
private val ChartLine = Color(0xFFE03030)
private val ChartPoint = Color(0xFFFF3B30)
private val ChartBest = Color(0xFFFFCC33)

/** 画面表示時に中心周波数から自動で取り込むレンジ(±MHz)。Win版の既定と同じ。 */
private const val DEFAULT_RANGE_MHZ = 10

/**
 * RSSI測定タブ(タブレット版Shonan_Lite-androidのRSSI測定画面を電話版のタブへ移したもの) --
 * Shonan_Lite-win(`app/gui/screens/rssi.py`)と同じ構成:
 * 左に検索条件(中心周波数・±5/10/20MHzプリセット・ステップ・測定開始/停止)、
 * 右に検索結果(RSSIグラフ・最も強い周波数・検索方法(連続/1回)・RXゲイン)、下に状態表示。
 * Win版のテンキーは、Android版では端末の数字キーボードで代用する。
 */
@Composable
fun RssiScreen(viewModel: AppViewModel) {
    val settings = viewModel.settings
    var selectedRangeMhz by remember { mutableIntStateOf(DEFAULT_RANGE_MHZ) }
    var startKhz by remember { mutableLongStateOf(0L) }
    var endKhz by remember { mutableLongStateOf(0L) }
    var stepKhzText by remember { mutableStateOf("200") }
    var inputError by remember { mutableStateOf<String?>(null) }
    val restrictionMessage = viewModel.rssiRestrictionMessage

    fun applyRange(rangeMhz: Int) {
        val centerHz = settings.effectiveLoHz
        startKhz = ((centerHz - rangeMhz * 1_000_000L) / 1000.0).roundToLong().coerceAtLeast(0L)
        endKhz = ((centerHz + rangeMhz * 1_000_000L) / 1000.0).roundToLong()
        selectedRangeMhz = rangeMhz
    }

    // 画面を開くたびに、周波数画面で設定した運用周波数を中心とした±10MHzを取り込む(Win版と同じ)。
    LaunchedEffect(Unit) { applyRange(DEFAULT_RANGE_MHZ) }
    // 検索は「測定停止」まで繰り返すため、他の画面へ移ったら止める(Win版のon_hideと同じ)。
    DisposableEffect(Unit) { onDispose { viewModel.stopRssi() } }

    // 電話の横向き画面に収めるため、上部バーは置かずタブの中身として表示する。
    run {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(8.dp, 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // --- 左カラム: 検索条件 ---
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(InnerCardBackground, RoundedCornerShape(10.dp))
                        .border(1.dp, OuterCardBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp, 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 電話の画面の高さに収めるため、中心周波数は見出しの行、ステップは入力欄と同じ行に置く。
                    val centerKhz = (settings.effectiveLoHz / 1000.0).roundToLong()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            settings.t("測定条件", "Measurement Conditions"),
                            color = TitleCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            settings.t("中心 $centerKhz kHz", "Center $centerKhz kHz"),
                            color = TitleCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 20).forEach { mhz ->
                            PresetButton(
                                "±${mhz}MHz",
                                selected = selectedRangeMhz == mhz,
                                modifier = Modifier.weight(1f),
                            ) { applyRange(mhz) }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(settings.t("ステップ", "Step"), color = TextColor, fontSize = 13.sp)
                        OutlinedTextField(
                            value = stepKhzText,
                            onValueChange = { text -> stepKhzText = text.filter { it.isDigit() }.take(8) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                            ),
                            // 入力欄の右端の×で入力を全部消す(キーボードにはアプリからキーを足せないため)
                            trailingIcon = {
                                if (stepKhzText.isNotEmpty()) {
                                    IconButton(onClick = { stepKhzText = "" }) {
                                        Icon(
                                            Icons.Filled.Clear,
                                            contentDescription = settings.t("クリア", "Clear"),
                                            tint = TextColor,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.width(144.dp),
                        )
                        Text("kHz", color = TextColor, fontSize = 13.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            if (viewModel.rssiIsScanning) {
                                viewModel.stopRssi()
                            } else {
                                val stepKhz = stepKhzText.toLongOrNull()
                                inputError = when {
                                    stepKhz == null -> settings.t("周波数範囲・ステップを数値で入力してください", "Enter the frequency range and step as numbers")
                                    endKhz <= startKhz -> settings.t("終了周波数は開始周波数より大きくしてください", "The end frequency must be greater than the start frequency")
                                    stepKhz <= 0 -> settings.t("ステップは1kHz以上で指定してください", "The step must be 1 kHz or more")
                                    else -> null
                                }
                                if (inputError == null && stepKhz != null) {
                                    viewModel.startRssi(startKhz * 1000, endKhz * 1000, stepKhz * 1000)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (viewModel.rssiIsScanning) MeasuringRed else ActionButton,
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                    ) {
                        Text(
                            if (viewModel.rssiIsScanning) settings.t("測定停止", "Stop Measuring") else settings.t("測定開始", "Start Measuring"),
                            fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // --- 右カラム: 検索結果 ---
                Column(
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxHeight()
                        .background(InnerCardBackground, RoundedCornerShape(10.dp))
                        .border(1.dp, OuterCardBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp, 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(settings.t("測定結果", "Measurement Result"), color = TitleCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    RssiGraph(
                        settings = settings,
                        startHz = viewModel.rssiSweepStartHz,
                        endHz = viewModel.rssiSweepEndHz,
                        centerHz = settings.effectiveLoHz,
                        points = viewModel.rssiMeasurements,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    val bestHz = viewModel.rssiBestFrequencyHz
                    val bestRssi = viewModel.rssiBestRssiDb
                    Text(
                        if (bestHz == null || bestRssi == null) settings.t("最も強い周波数: -", "Strongest frequency: -")
                        else settings.t(
                            "最も強い周波数: ${bestHz / 1000} kHz (RSSI ${formatRssiValue(bestRssi)})",
                            "Strongest frequency: ${bestHz / 1000} kHz (RSSI ${formatRssiValue(bestRssi)})",
                        ),
                        color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )

                    // 検索方法(連続=「測定停止」まで繰り返す / 1回=範囲の終わりで自動停止)とRXゲインを1行に置く。
                    // 検索方法を検索中に切り替えた場合は、実行中の周回が終わった時点から反映される。
                    // RXゲインは設定２タブと同じ設定値を共有し、検索中でも変更できる。
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PresetButton(settings.t("連続", "Repeat"), selected = settings.rssiRepeatScan, modifier = Modifier.widthIn(min = 60.dp)) {
                            viewModel.setRssiRepeatScan(true)
                        }
                        PresetButton(settings.t("1回", "Once"), selected = !settings.rssiRepeatScan, modifier = Modifier.widthIn(min = 60.dp)) {
                            viewModel.setRssiRepeatScan(false)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(settings.t("RXゲイン", "RX Gain"), color = TextColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        PresetButton("AGC", selected = settings.rxAgcEnabled, modifier = Modifier.widthIn(min = 56.dp)) {
                            viewModel.setRssiRxGain(!settings.rxAgcEnabled, settings.rxGainDb)
                        }
                        val manual = !settings.rxAgcEnabled
                        RepeatButton("−", enabled = manual && settings.rxGainDb > RX_GAIN_MIN_DB) {
                            viewModel.setRssiRxGain(false, viewModel.settings.rxGainDb - 1)
                        }
                        Text(
                            "${settings.rxGainDb} dB",
                            color = TextColor, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, modifier = Modifier.width(52.dp),
                        )
                        RepeatButton("+", enabled = manual && settings.rxGainDb < RX_GAIN_MAX_DB) {
                            viewModel.setRssiRxGain(false, viewModel.settings.rxGainDb + 1)
                        }
                    }
                }
            }

            Text(
                viewModel.rssiStatus.ifEmpty { settings.t("測定待機中", "Measurement idle") },
                color = TitleCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
        }
    }

    val dialogMessage = restrictionMessage ?: inputError
    if (dialogMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearRssiRestrictionMessage(); inputError = null },
            confirmButton = { TextButton(onClick = { viewModel.clearRssiRestrictionMessage(); inputError = null }) { Text("OK") } },
            title = {
                Text(
                    if (restrictionMessage != null) settings.t("RSSI測定を開始できません", "Cannot Start RSSI Measurement")
                    else settings.t("入力エラー", "Input Error"),
                )
            },
            text = { Text(dialogMessage) },
        )
    }
}

@Composable
private fun PresetButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) TitleCyan else ChipBackground,
            contentColor = if (selected) SelectedText else Color.White,
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        modifier = modifier.height(32.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** 押している間は400ms後から80ms間隔で繰り返す(Win版のsetAutoRepeatと同じ)。 */
@Composable
private fun RepeatButton(label: String, enabled: Boolean, onStep: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val currentOnStep by rememberUpdatedState(onStep)
    val currentEnabled by rememberUpdatedState(enabled)
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        repeated = false
        delay(400)
        while (currentEnabled) {
            repeated = true
            currentOnStep()
            delay(80)
        }
    }
    Button(
        onClick = { if (!repeated) currentOnStep(); repeated = false },
        enabled = enabled,
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(
            containerColor = ActionButton, contentColor = Color.White,
            disabledContainerColor = ChipBackground, disabledContentColor = DisabledText,
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.width(44.dp).height(32.dp),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 検索中の周波数(横軸)とRSSI(縦軸)の折れ線 -- Win版`RssiGraphWidget`と同じ描画。
 * RSSIは値が小さいほど信号が強い指標なので、値が小さいほど上に来るよう反転し、
 * 「山」がそのまま信号が強い箇所を表すようにする。白い破線は中心周波数、黄色の点は最も強い点。
 */
@Composable
private fun RssiGraph(
    settings: AppSettings,
    startHz: Long,
    endHz: Long,
    centerHz: Long,
    points: List<RssiMeasurement>,
    modifier: Modifier = Modifier,
) {
    val emptyText = settings.t("測定開始でRSSIを表示します", "Press Start Measuring to show RSSI")
    Canvas(
        modifier = modifier
            .background(ChartBackground, RoundedCornerShape(8.dp))
            .border(1.dp, OuterCardBorder, RoundedCornerShape(8.dp)),
    ) {
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = ChartLabel.toArgb()
            textSize = 12.sp.toPx()
        }
        val left = 44.dp.toPx()
        val top = 10.dp.toPx()
        val right = size.width - 10.dp.toPx()
        val bottom = size.height - 24.dp.toPx()
        drawRect(ChartFrame, topLeft = Offset(left, top), size = androidx.compose.ui.geometry.Size(right - left, bottom - top), style = Stroke(1.dp.toPx()))

        if (endHz <= startHz || points.isEmpty()) {
            labelPaint.color = DisabledText.toArgb()
            labelPaint.textSize = 15.sp.toPx()
            labelPaint.textAlign = android.graphics.Paint.Align.CENTER
            drawContext.canvas.nativeCanvas.drawText(emptyText, size.width / 2, size.height / 2, labelPaint)
            return@Canvas
        }

        val span = (endHz - startHz).toFloat().coerceAtLeast(1f)
        var yMin = points.minOf { it.rssiDb }
        var yMax = points.maxOf { it.rssiDb }
        if (yMin == yMax) {
            yMin -= 1; yMax += 1
        } else {
            val pad = (yMax - yMin) * 0.1
            yMin -= pad; yMax += pad
        }
        fun x(hz: Long) = left + (hz - startHz) / span * (right - left)
        // 値が小さいほど信号が強いので、上に行くほど強くなるよう反転する。
        fun y(rssi: Double) = top + ((rssi - yMin) / (yMax - yMin)).toFloat() * (bottom - top)

        for (i in 1..3) {
            val gy = top + (bottom - top) * i / 4f
            drawLine(ChartGrid, Offset(left, gy), Offset(right, gy), strokeWidth = 1.dp.toPx())
        }

        val canvas = drawContext.canvas.nativeCanvas
        labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
        canvas.drawText("%.0f".format(yMin), left - 6.dp.toPx(), top + labelPaint.textSize / 3, labelPaint)
        canvas.drawText("%.0f".format(yMax), left - 6.dp.toPx(), bottom + labelPaint.textSize / 3, labelPaint)
        val labelBaseline = bottom + 6.dp.toPx() + labelPaint.textSize
        labelPaint.textAlign = android.graphics.Paint.Align.LEFT
        canvas.drawText("${startHz / 1000}", left - 20.dp.toPx(), labelBaseline, labelPaint)
        labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
        canvas.drawText("${endHz / 1000} kHz", right, labelBaseline, labelPaint)

        if (centerHz in startHz..endHz) {
            val cx = x(centerHz)
            drawLine(
                Color.White, Offset(cx, top), Offset(cx, bottom), strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }

        if (points.size >= 2) {
            val path = Path().apply {
                moveTo(x(points[0].frequencyHz), y(points[0].rssiDb))
                points.drop(1).forEach { lineTo(x(it.frequencyHz), y(it.rssiDb)) }
            }
            drawPath(path, ChartLine, style = Stroke(2.dp.toPx()))
        }
        val dotRadius = 2.dp.toPx()
        points.forEach { drawCircle(ChartPoint, dotRadius, Offset(x(it.frequencyHz), y(it.rssiDb))) }
        val best = points.minBy { it.rssiDb }
        drawCircle(ChartBest, dotRadius, Offset(x(best.frequencyHz), y(best.rssiDb)))
    }
}
