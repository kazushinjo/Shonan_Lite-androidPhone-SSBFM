package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.core.AppSettings
import com.shinjo.shonanandroid.core.FECRate
import com.shinjo.shonanandroid.core.ModulationScheme
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** アマチュアDATVで一般的なシンボルレートの候補値(Msym/s)。 */
private val symbolRatePresets = listOf(0.25, 0.333, 0.5, 0.666, 1.0, 2.0)

private val CardBackground = Color(0xFF191D1F)
private val ChipBackground = Color(0xFF303538)
private val TitleColor = Color(0xFFCCCCCC)
private val CaptionColor = Color(0xFF9AA0A6)
private val AccentBlue = Color(0xFF1677FF)
private val AccentOrange = Color(0xFFFF9800)
private val AccentCyan = Color(0xFF0C9BC0)
private val ConstellationBackground = Color(0xFF050607)
private val ConstellationBorder = Color(0xFF46545B)
private val AxisColor = Color(0xFF8397A0)
private val PointColor = Color(0xFF38B8E3)

private fun ksps(msps: Double): Int = Math.round(msps * 1000).toInt()

private fun codeRateOf(rate: FECRate): Double {
    val (num, den) = rate.label.split("/").map { it.toInt() }
    return num.toDouble() / den
}

private val ModulationScheme.bitsPerSymbol: Int
    get() = when (this) {
        ModulationScheme.QPSK -> 2
        ModulationScheme.PSK8 -> 3
    }

/**
 * DVB-S2の変調パラメータ(シンボルレート・誤り訂正・変調方式)を1画面にまとめたタブ。
 * 3つとも送信側と受信側で一致させる必要があり、組み合わせ(Mod-Cod)として見比べられるよう
 * 横並びの3カードに置き、下段に帯域幅・ビットレートの目安をまとめて表示する。
 * 横向きの電話画面に収めるため、他の設定画面のような上部バーは置かない(タブで行き来する)。
 */
@Composable
fun ModCodSettingsScreen(viewModel: AppViewModel) {
    val settings = viewModel.settings
    var showCustomEntry by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SymbolRateCard(settings, Modifier.weight(1.15f), onSelect = { msps ->
                viewModel.updateSettings { it.copy(symbolRateMsps = msps) }
            }, onCustom = { showCustomEntry = true })
            FecCard(settings, Modifier.weight(1f)) { rate -> viewModel.updateSettings { it.copy(fecRate = rate) } }
            ModulationCard(settings, Modifier.weight(1f)) { scheme ->
                viewModel.updateSettings { it.copy(modulationScheme = scheme) }
            }
        }
        SummaryBar(settings)
    }

    if (showCustomEntry) {
        CustomSymbolRateDialog(
            settings = settings,
            onDismiss = { showCustomEntry = false },
            onConfirm = { ksps ->
                viewModel.updateSettings { it.copy(symbolRateMsps = ksps / 1000.0) }
                showCustomEntry = false
            },
        )
    }
}

@Composable
private fun Card(
    title: String,
    modifier: Modifier,
    value: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxHeight()
            .background(CardBackground, RoundedCornerShape(10.dp))
            .padding(10.dp, 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TitleColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (value != null) Text(value, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        content()
    }
}

@Composable
private fun ChoiceButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) AccentBlue else ChipBackground,
            contentColor = Color.White,
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = modifier.height(30.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun SymbolRateCard(settings: AppSettings, modifier: Modifier, onSelect: (Double) -> Unit, onCustom: () -> Unit) {
    val current = settings.symbolRateMsps
    val isPreset = symbolRatePresets.any { abs(it - current) < 0.0005 }
    Card(settings.t("シンボルレート", "Symbol Rate"), modifier, value = "${ksps(current)} kS/s") {
        symbolRatePresets.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { value ->
                    ChoiceButton("${ksps(value)}k", abs(value - current) < 0.0005, Modifier.weight(1f)) { onSelect(value) }
                }
            }
        }
        ChoiceButton(
            settings.t("直接入力…", "Custom…"),
            selected = !isPreset,
            modifier = Modifier.fillMaxWidth(),
            onClick = onCustom,
        )
    }
}

@Composable
private fun FecCard(settings: AppSettings, modifier: Modifier, onSelect: (FECRate) -> Unit) {
    val current = settings.fecRate
    val overhead = (1 - codeRateOf(current)) * 100
    Card(settings.t("誤り訂正(FEC)", "FEC"), modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FECRate.entries.forEach { rate ->
                ChoiceButton(rate.label, rate == current, Modifier.weight(1f)) { onSelect(rate) }
            }
        }
        Text(
            settings.t("オーバーヘッド %.0f%%".format(overhead), "Overhead %.0f%%".format(overhead)),
            color = CaptionColor,
            fontSize = 12.sp,
        )
        Row(Modifier.fillMaxWidth().height(10.dp)) {
            Spacer(Modifier.weight(maxOf(1f, (100 - overhead).toFloat())).height(10.dp).background(AccentBlue, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(2.dp))
            Spacer(Modifier.weight(maxOf(1f, overhead.toFloat())).height(10.dp).background(AccentOrange, RoundedCornerShape(3.dp)))
        }
        Text(
            when {
                overhead >= 40 -> settings.t(
                    "誤り訂正を重視。C/Nが低くても復調しやすい。",
                    "Robust: easier to decode at low C/N.",
                )
                overhead >= 15 -> settings.t(
                    "誤り訂正と伝送効率のバランス型。",
                    "Balanced robustness and throughput.",
                )
                else -> settings.t(
                    "伝送効率を重視。良好なC/Nが必要。",
                    "Efficient: needs good C/N.",
                )
            },
            color = TitleColor,
            fontSize = 12.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun ModulationCard(settings: AppSettings, modifier: Modifier, onSelect: (ModulationScheme) -> Unit) {
    val current = settings.modulationScheme
    Card(
        settings.t("変調方式", "Modulation"),
        modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModulationScheme.entries.forEach { scheme ->
                ChoiceButton(scheme.label, scheme == current, Modifier.weight(1f)) { onSelect(scheme) }
            }
        }
        Text(
            settings.t("${current.bitsPerSymbol} ビット/シンボル", "${current.bitsPerSymbol} bits/symbol"),
            color = CaptionColor,
            fontSize = 12.sp,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(ConstellationBackground, RoundedCornerShape(6.dp))
                .border(1.dp, ConstellationBorder, RoundedCornerShape(6.dp)),
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = minOf(size.width, size.height) / 2f - 8.dp.toPx()
            drawLine(AxisColor, Offset(cx - r - 4.dp.toPx(), cy), Offset(cx + r + 4.dp.toPx(), cy), strokeWidth = 1f)
            drawLine(AxisColor, Offset(cx, cy - r - 4.dp.toPx()), Offset(cx, cy + r + 4.dp.toPx()), strokeWidth = 1f)
            val count = if (current == ModulationScheme.QPSK) 4 else 8
            val startAngle = if (current == ModulationScheme.QPSK) Math.PI / 4 else 0.0
            for (i in 0 until count) {
                val a = startAngle + 2 * Math.PI * i / count
                drawCircle(
                    PointColor,
                    radius = 4.dp.toPx(),
                    center = Offset(cx + (cos(a) * r).toFloat(), cy - (sin(a) * r).toFloat()),
                )
            }
        }
    }
}

/** 下段: 3設定の組み合わせ(Mod-Cod)と、帯域幅・ビットレートの目安。 */
@Composable
private fun SummaryBar(settings: AppSettings) {
    val sr = settings.symbolRateMsps
    val bandwidthMhz = sr * 1.2
    // DVB-S2のBBHEADER・パイロット等を無視した目安値
    val bitrateMbps = sr * settings.modulationScheme.bitsPerSymbol * codeRateOf(settings.fecRate)
    Row(
        Modifier
            .fillMaxWidth()
            .background(CardBackground, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${ksps(sr)} kS/s ・ ${settings.modulationScheme.label} ${settings.fecRate.label}",
            color = AccentCyan,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Text(
            settings.t(
                "帯域 約%.2f MHz ・ 最大 約%.2f Mbps(目安)".format(bandwidthMhz, bitrateMbps),
                "BW ~%.2f MHz ・ up to ~%.2f Mbps (est.)".format(bandwidthMhz, bitrateMbps),
            ),
            color = TitleColor,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun CustomSymbolRateDialog(settings: AppSettings, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(ksps(settings.symbolRateMsps).toString()) }
    val value = text.trim().toIntOrNull()
    val valid = value != null && value in 100..5000
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(settings.t("シンボルレート(kS/s)", "Symbol rate (kS/s)")) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(4) },
                singleLine = true,
                isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text(settings.t("100〜5000 kS/s", "100–5000 kS/s")) },
            )
        },
        confirmButton = { TextButton(onClick = { value?.let(onConfirm) }, enabled = valid) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(settings.t("キャンセル", "Cancel")) } },
    )
}
