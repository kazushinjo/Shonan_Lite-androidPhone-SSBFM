package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.ssbfm.SsbFmBand
import com.shinjo.shonanandroid.ssbfm.SsbFmController
import com.shinjo.shonanandroid.ssbfm.SsbFmMode
import com.shinjo.shonanandroid.ssbfm.SsbFmNative
import com.shinjo.shonanandroid.ssbfm.SsbFmSettings
import kotlin.math.pow
import kotlin.math.roundToLong

private val SsbAccent = Color(0xFF0C9BC0)
private val SsbPanel = Color(0xFF101416)
private val SsbBorder = Color(0xFF3B5159)
private val SsbTxRed = Color(0xFFE53935)
private val SsbFreqColor = Color(0xFF87CEEB)

/** Sメーターとスケルチの表示範囲(相対dB)。 */
private const val METER_MIN_DB = -150f
private const val METER_MAX_DB = -50f

/**
 * SSB/FM送受信タブ。Langstone-V2(G4EML)のSSB・FM送受信機能を1画面にまとめたもの。
 * 横向きの電話画面に収まるよう、左に周波数・スペクトル・ウォーターフォール、右に操作部を置く。
 */
@Composable
fun SsbFmScreen(viewModel: AppViewModel) {
    val c = viewModel.ssbFm
    val appSettings = viewModel.settings
    val t: (String, String) -> String = { ja, en -> appSettings.t(ja, en) }
    val s = c.settings
    var showFrequencyEntry by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1.55f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FrequencyDisplay(
                    frequencyHz = s.frequencyHz,
                    stepHz = s.stepHz,
                    onSelectStep = c::setStep,
                    onLongPress = { showFrequencyEntry = true },
                    modifier = Modifier.weight(1f),
                )
                SsbFmBand.entries.forEach { band ->
                    BandChip(band.label, selected = c.currentBand == band, enabled = !c.isTransmitting) {
                        c.selectBand(band)
                    }
                }
                Text(
                    text = when {
                        c.isTransmitting -> "TX"
                        c.isRunning -> "RX"
                        else -> "--"
                    },
                    color = if (c.isTransmitting) SsbTxRed else if (c.isRunning) Color(0xFF4CAF50) else Color.Gray,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            SignalMeter(c, t, Modifier.fillMaxWidth().height(18.dp))
            SpectrumView(c, Modifier.fillMaxWidth().weight(0.42f))
            WaterfallView(c, Modifier.fillMaxWidth().weight(0.58f))
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(SsbPanel, RoundedCornerShape(8.dp))
                .padding(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                val startLabel = when {
                    c.isStarting -> t("準備中", "Wait")
                    c.isRunning -> t("停止", "Stop")
                    else -> t("開始", "Start")
                }
                CompactButton(
                    label = startLabel,
                    selected = c.isRunning,
                    enabled = !c.isStarting,
                    modifier = Modifier.weight(1.2f),
                ) { if (c.isRunning) c.stop() else viewModel.startSsbFm() }
                SsbFmMode.entries.forEach { mode ->
                    CompactButton(
                        label = mode.label,
                        selected = s.mode == mode,
                        enabled = !c.isTransmitting,
                        modifier = Modifier.weight(1f),
                    ) { c.setMode(mode) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactButton("−", false, true, Modifier.weight(1f)) { c.setFrequency(s.frequencyHz - s.stepHz) }
                Text(
                    formatStep(s.stepHz),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1.2f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                CompactButton("+", false, true, Modifier.weight(1f)) { c.setFrequency(s.frequencyHz + s.stepHz) }
            }

            val message = c.status ?: c.error
            if (message != null) {
                Text(
                    message,
                    color = if (c.status != null) Color(0xFFFFC107) else Color(0xFFFF6E6E),
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .combinedClickableCompat { c.error = null },
                )
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                LabeledSlider(t("音量", "AF"), s.afGain, 0f..1f, "${(s.afGain * 100).toInt()}") { c.setAfGain(it) }
                LabeledSlider(
                    t("スケルチ", "SQL"),
                    if (s.squelchDb <= SsbFmSettings.SQUELCH_OFF) METER_MIN_DB else s.squelchDb,
                    METER_MIN_DB..METER_MAX_DB,
                    if (s.squelchDb <= METER_MIN_DB) "OFF" else "${s.squelchDb.toInt()}",
                ) { v -> c.setSquelch(if (v <= METER_MIN_DB + 0.5f) SsbFmSettings.SQUELCH_OFF else v) }
                LabeledSlider(t("マイク", "MIC"), s.micGain, 0f..4f, "%.1f".format(s.micGain)) { c.setMicGain(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        LabeledSlider(
                            t("RF利得", "RF"),
                            if (s.rxAgc) c.rxGainDb else s.rxGainDb,
                            0f..73f,
                            if (s.rxAgc) "A${c.rxGainDb.toInt()}" else "${s.rxGainDb.toInt()}",
                            enabled = !s.rxAgc,
                        ) { c.setRxGain(false, it) }
                    }
                    Checkbox(
                        checked = s.rxAgc,
                        onCheckedChange = { c.setRxGain(it, s.rxGainDb) },
                        colors = CheckboxDefaults.colors(checkedColor = SsbAccent),
                        modifier = Modifier.size(28.dp),
                    )
                    Text("AGC", color = Color.White, fontSize = 10.sp)
                }
                LabeledSlider(
                    t("送信減衰", "TX Att"),
                    s.txAttenuationDb,
                    0f..89f,
                    "-${s.txAttenuationDb.toInt()}dB",
                ) { c.setTxAttenuation(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = s.pttLatch,
                        onCheckedChange = c::setPttLatch,
                        colors = CheckboxDefaults.colors(checkedColor = SsbAccent),
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        t("PTTを押すたびに送受信を切り替える", "Latching PTT"),
                        color = Color.White,
                        fontSize = 10.sp,
                    )
                }
            }

            PttButton(c, t, Modifier.fillMaxWidth().height(54.dp))
        }
    }

    if (showFrequencyEntry) {
        FrequencyEntryDialog(
            initialHz = s.frequencyHz,
            t = t,
            onDismiss = { showFrequencyEntry = false },
            onConfirm = { hz ->
                c.setFrequency(hz)
                showFrequencyEntry = false
            },
        )
    }
}

/**
 * 周波数表示(MHz単位の8桁、例: 1295.100.0。最下位は100Hz)。各桁をタップするとその桁を
 * 同調ステップにし、長押しで直接入力する。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FrequencyDisplay(
    frequencyHz: Long,
    stepHz: Long,
    onSelectStep: (Long) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 10^9〜10^2 Hzの8桁
    val digits = (frequencyHz / 100).toString().padStart(8, '0')
    val firstSignificant = digits.indexOfFirst { it != '0' }.let { if (it < 0) 7 else it }
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        digits.forEachIndexed { index, ch ->
            val power = 9 - index
            // 先頭の0は省くが、MHzの1の位は常に表示する。
            if (index >= firstSignificant.coerceAtMost(3)) {
                val place = 10.0.pow(power).toLong()
                val selected = place == stepHz
                Text(
                    text = ch.toString(),
                    color = SsbFreqColor,
                    fontSize = 30.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .combinedClickable(onClick = { onSelectStep(place) }, onLongClick = onLongPress)
                        .then(
                            if (selected) Modifier.background(SsbAccent.copy(alpha = 0.35f), RoundedCornerShape(3.dp))
                            else Modifier,
                        ),
                )
                if (power == 6 || power == 3) {
                    Text(".", color = SsbFreqColor, fontSize = 30.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun SignalMeter(c: SsbFmController, t: (String, String) -> String, modifier: Modifier) {
    val s = c.settings
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val transmitting = c.isTransmitting
        Text(
            if (transmitting) "MIC" else "S",
            color = Color.White,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            modifier = Modifier.width(26.dp),
        )
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            drawRect(Color(0xFF1C2226))
            val fraction = if (transmitting) {
                c.micLevel.coerceIn(0f, 1f)
            } else {
                ((c.signalDb - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB)).coerceIn(0f, 1f)
            }
            val barColor = when {
                transmitting && c.micLevel >= 0.99f -> SsbTxRed
                transmitting -> Color(0xFFFFC107)
                c.squelchOpen -> Color(0xFF4CAF50)
                else -> Color(0xFF2E5E30)
            }
            drawRect(barColor, size = Size(size.width * fraction, size.height))
            if (!transmitting && s.squelchDb > SsbFmSettings.SQUELCH_OFF) {
                val x = size.width * ((s.squelchDb - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB)).coerceIn(0f, 1f)
                drawLine(Color(0xFFFFC107), Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
            }
        }
        Text(
            if (transmitting) "" else if (c.isRunning) "${c.signalDb.toInt()} dB" else "",
            color = Color.White,
            fontSize = 10.sp,
            modifier = Modifier.width(52.dp).padding(start = 4.dp),
        )
    }
}

/** スペクトル上で水平ドラッグすると同調し、タップするとその周波数へ移動する。 */
private fun Modifier.tuningGestures(c: SsbFmController): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures { pos ->
            val s = c.settings
            val offset = (pos.x / size.width - 0.5f) * SsbFmNative.SPECTRUM_SPAN_HZ
            val grid = if (s.mode == SsbFmMode.FM) 5_000L else 100L
            c.setFrequency(((s.frequencyHz + offset) / grid).roundToLong() * grid)
        }
    }
    .pointerInput(Unit) {
        var pending = 0f
        detectHorizontalDragGestures(onDragStart = { pending = 0f }) { change, dragAmount ->
            change.consume()
            val s = c.settings
            val grid = if (s.mode == SsbFmMode.FM) 1_000L else 100L
            pending -= dragAmount * SsbFmNative.SPECTRUM_SPAN_HZ / size.width
            val steps = (pending / grid).toLong()
            if (steps != 0L) {
                pending -= steps * grid
                c.setFrequency((s.frequencyHz / grid + steps) * grid)
            }
        }
    }

@Composable
private fun SpectrumView(c: SsbFmController, modifier: Modifier) {
    val s = c.settings
    Canvas(
        modifier
            .background(Color(0xFF05080A))
            .tuningGestures(c),
    ) {
        c.frameCounter // 更新通知を購読する
        val w = size.width
        val h = size.height
        val hzToX = { hz: Float -> (hz / SsbFmNative.SPECTRUM_SPAN_HZ + 0.5f) * w }
        val (lo, hi) = when (s.mode) {
            SsbFmMode.USB -> 300f to 3000f
            SsbFmMode.FM -> -7500f to 7500f
        }
        drawRect(SsbAccent.copy(alpha = 0.18f), Offset(hzToX(lo), 0f), Size(hzToX(hi) - hzToX(lo), h))
        for (k in -20..20 step 5) {
            val x = hzToX(k * 1000f)
            drawLine(Color(0xFF1E2A30), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        drawLine(Color(0xFFFF5252), Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 1.5f)
        if (!c.isRunning || c.frameCounter == 0L) return@Canvas
        val floor = c.displayFloorDb - 5f
        val range = 60f
        val path = Path()
        val n = c.spectrum.size
        for (i in 0 until n) {
            val x = w * i / (n - 1)
            val y = h - h * ((c.spectrum[i] - floor) / range).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color.White, style = Stroke(width = 1.5f))
    }
}

@Composable
private fun WaterfallView(c: SsbFmController, modifier: Modifier) {
    val image = remember(c) { c.waterfall.asImageBitmap() }
    Canvas(
        modifier
            .background(Color.Black)
            .tuningGestures(c),
    ) {
        c.frameCounter // 更新通知を購読する
        drawImage(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
        drawLine(Color(0x80FF5252), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 1f)
    }
}

@Composable
private fun PttButton(c: SsbFmController, t: (String, String) -> String, modifier: Modifier) {
    val latch = c.settings.pttLatch
    val enabled = c.isRunning
    val controller by rememberUpdatedState(c)
    Box(
        modifier
            .padding(top = 4.dp)
            .background(
                when {
                    c.isTransmitting -> SsbTxRed
                    enabled -> Color(0xFF3A1F1F)
                    else -> Color(0xFF262626)
                },
                RoundedCornerShape(10.dp),
            )
            .border(1.dp, if (enabled) SsbTxRed else SsbBorder, RoundedCornerShape(10.dp))
            .pointerInput(latch, enabled) {
                if (!enabled) return@pointerInput
                if (latch) {
                    detectTapGestures(onTap = { controller.setPtt(!controller.isTransmitting) })
                } else {
                    detectTapGestures(onPress = {
                        controller.setPtt(true)
                        tryAwaitRelease()
                        controller.setPtt(false)
                    })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (c.isTransmitting) t("送信中", "TX") else "PTT",
            color = if (enabled) Color.White else Color.Gray,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 周波数表示の右に並べるバンド選択ボタン。 */
@Composable
private fun BandChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.padding(start = 4.dp).width(50.dp).height(30.dp),
        shape = RoundedCornerShape(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) SsbAccent else Color(0xFF263238),
            contentColor = Color.White,
        ),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun CompactButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(34.dp),
        shape = RoundedCornerShape(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) SsbAccent else Color(0xFF263238),
            contentColor = Color.White,
        ),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1, modifier = Modifier.width(58.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = SsbAccent, activeTrackColor = SsbAccent),
        )
        Text(valueText, color = Color.White, fontSize = 10.sp, modifier = Modifier.width(44.dp).padding(start = 4.dp))
    }
}

@Composable
private fun FrequencyEntryDialog(
    initialHz: Long,
    t: (String, String) -> String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var text by remember { mutableStateOf("%.4f".format(initialHz / 1e6)) }
    val parsed = text.trim().toDoubleOrNull()?.let { (it * 1e6).roundToLong() }
    val valid = parsed != null && parsed in SsbFmSettings.MIN_FREQUENCY_HZ..SsbFmSettings.MAX_FREQUENCY_HZ
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("周波数(MHz)", "Frequency (MHz)")) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = { Text(t("70〜6000 MHz", "70–6000 MHz")) },
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = valid) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("キャンセル", "Cancel")) }
        },
    )
}

private fun formatStep(stepHz: Long): String = when {
    stepHz >= 1_000_000 -> "${stepHz / 1_000_000} MHz"
    stepHz >= 1_000 -> "${stepHz / 1_000} kHz"
    else -> "$stepHz Hz"
}

private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
