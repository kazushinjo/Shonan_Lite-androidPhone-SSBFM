package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel

/** アマチュアDATVで一般的なシンボルレートの候補値（Msym/s）。 */
private val symbolRateCandidates = listOf(0.25, 0.333, 0.5, 0.666, 1.0, 2.0)

private fun ksps(msps: Double): Int = Math.round(msps * 1000).toInt()

private val CardBackground = Color(0xFF191D1F)
private val CaptionColor = Color(0xFF9AA0A6)
private val KeyBackground = Color(0xFF252A2D)
private val PresetBorder = Color(0xFF394146)
private val KeyBorder = Color(0xFF69747A)
private val AccentCyan = Color(0xFF0C9BC0)

/**
 * シンボルレート設定画面 -- Shonan_Lite-pi5(`pi5/gui/screens/symbolrate.py`)の見た目に
 * できる限り忠実に合わせた版。画面は「左にプリセット選択カード(幅220dp相当)、右に
 * カスタム設定カード(大きな数値表示+テンキー)」の2カードのみで構成し、pi5同様に
 * 余計な補足テキストやボタンは置かない(実制御なしの運用メモである点は変更なし、
 * FEC候補表示やバンド目安値への復元ボタンはpi5に存在しないため本画面からは削除した)。
 */
@Composable
fun SymbolRateSettingsScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit) {
    val settings = viewModel.settings
    var pendingText by remember(settings.symbolRateMsps) { mutableStateOf(ksps(settings.symbolRateMsps).toString()) }

    fun commit(msps: Double) {
        viewModel.updateSettings { s -> s.copy(symbolRateMsps = msps) }
        pendingText = ksps(msps).toString()
    }

    fun step(direction: Int) {
        val current = settings.symbolRateMsps
        val nearest = symbolRateCandidates.indices.minByOrNull { kotlin.math.abs(symbolRateCandidates[it] - current) } ?: 0
        val index = (nearest + direction).coerceIn(0, symbolRateCandidates.size - 1)
        commit(symbolRateCandidates[index])
    }

    fun onKey(key: String) {
        when (key) {
            "C" -> pendingText = ""
            "DEL" -> pendingText = pendingText.dropLast(1)
            "OK" -> {
                val value = pendingText.toIntOrNull() ?: return
                if (value !in 100..5000) return
                commit(value / 1000.0)
                return
            }
            else -> if (key.all { it.isDigit() } && pendingText.length < 4) pendingText += key
        }
    }

    SettingsSubScreen(
        title = settings.t("シンボルレート設定", "Symbol Rate Settings"),
        onBack = { onNavigate("tx") },
        homeLabel = settings.t("ホームへ戻る", "Home"),
        scrollEnabled = false,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- 左カラム: プリセット選択 ---
            Column(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .padding(14.dp, 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    settings.t("プリセット選択", "Presets"),
                    color = Color(0xFFCCCCCC),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                symbolRateCandidates.forEach { value ->
                    val selected = kotlin.math.abs(value - settings.symbolRateMsps) < 0.0005
                    Button(
                        onClick = { commit(value) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) AccentCyan else KeyBackground,
                            contentColor = Color.White,
                        ),
                        border = BorderStroke(1.dp, if (selected) AccentCyan else PresetBorder),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.fillMaxWidth().height(30.dp),
                    ) {
                        Text("${ksps(value)} kS/s", fontSize = 13.sp, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // --- 右カラム: カスタム設定 ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .padding(16.dp, 12.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    settings.t("カスタム設定", "Custom"),
                    color = Color(0xFFCCCCCC),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    pendingText.ifEmpty { "0" },
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(settings.t("シンボルレート (kS/s)", "Symbol rate (kS/s)"), color = CaptionColor, fontSize = 12.sp)

                val keypadRows = listOf(
                    listOf("7", "8", "9", "DEL"),
                    listOf("4", "5", "6", "C"),
                    listOf("1", "2", "3", "−"),
                    listOf("0", "＋", "", "OK"),
                )
                keypadRows.forEach { rowKeys ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowKeys.forEach { key ->
                            if (key.isEmpty()) {
                                Spacer(modifier = Modifier.size(66.dp, 38.dp))
                            } else {
                                Button(
                                    onClick = {
                                        when (key) {
                                            "−" -> step(-1)
                                            "＋" -> step(1)
                                            else -> onKey(key)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = KeyBackground, contentColor = Color.White),
                                    border = BorderStroke(1.dp, KeyBorder),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.size(66.dp, 38.dp),
                                ) {
                                    Text(key, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Text(
                    settings.t(
                        "現在の設定: ${ksps(settings.symbolRateMsps)} kS/s",
                        "Current: ${ksps(settings.symbolRateMsps)} kS/s",
                    ),
                    color = AccentCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    settings.t(
                        "帯域幅の目安: %.1f MHz".format(settings.symbolRateMsps * 1.2),
                        "Est. bandwidth: %.1f MHz".format(settings.symbolRateMsps * 1.2),
                    ),
                    color = CaptionColor,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
