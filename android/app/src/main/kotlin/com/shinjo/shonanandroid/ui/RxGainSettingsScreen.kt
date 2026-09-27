package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel

private val CardBackground = Color(0xFF191D1F)
private val CaptionColor = Color(0xFF9AA0A6)
private val AccentCyan = Color(0xFF0C9BC0)
private val LevelGreen = Color(0xFF36B47A)
private val LevelTrack = Color(0xFF30383C)

/**
 * RXゲイン設定画面 -- Shonan_Lite-pi5(`pi5/gui/screens/rxgain.py`)と同じ
 * 「左にRXゲイン調整カード(AGCトグル+目盛り+スライダー)、右に信号レベルカード」の
 * レイアウトに変更([[shonan-lite-ipad-tx-rx-pi5-design]]と同様の移植)。信号レベルは
 * pi5では静的なモック値のため、Android版では実際のRXロック状態から算出する。
 */
@Composable
fun RxGainSettingsScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit) {
    val settings = viewModel.settings
    val locked = viewModel.rxController.isLocked
    val levelPercent = if (locked) 100 else 0

    SettingsSubScreen(
        title = settings.t("受信感度", "RX Gain"),
        onBack = { onNavigate("tx") },
        homeLabel = settings.t("ホームへ戻る", "Home"),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .padding(16.dp, 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        settings.t("RXゲイン調整", "RX Gain"),
                        color = CaptionColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(settings.t("自動調整", "Auto"), color = CaptionColor, fontSize = 12.sp)
                    Switch(
                        checked = settings.rxAgcEnabled,
                        onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(rxAgcEnabled = enabled) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = AccentCyan),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("0", "20", "40", "60", "80", "100").forEach { tick ->
                        Text(tick, color = CaptionColor, fontSize = 10.sp)
                    }
                }
                Slider(
                    value = settings.rxGainDb.toFloat(),
                    onValueChange = { value -> viewModel.updateSettings { it.copy(rxGainDb = value.toInt()) } },
                    valueRange = 0f..73f,
                    steps = 72,
                    enabled = !settings.rxAgcEnabled,
                    colors = SliderDefaults.colors(thumbColor = AccentCyan, activeTrackColor = AccentCyan),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${settings.rxGainDb} dB",
                    color = Color.White,
                    fontSize = 20.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )

                if (settings.rxAgcEnabled) {
                    OperationalMemoNote(settings.t("AGCが受信ゲインを自動調整します。", "AGC automatically adjusts receive gain."))
                } else {
                    OperationalMemoNote(settings.t("AGCをOFFにすると、Plutoの受信ゲインにこの値を適用します。", "When AGC is off, this value is applied to Pluto receive gain."))
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .padding(16.dp, 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    settings.t("信号レベル", "Signal Level"),
                    color = CaptionColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("$levelPercent %", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { levelPercent / 100f },
                    color = LevelGreen,
                    trackColor = LevelTrack,
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                    modifier = Modifier.fillMaxWidth().height(12.dp),
                )
                Text(
                    if (locked) settings.t("同期ロック中", "Sync locked") else settings.t("未ロック(信号なし)", "Not locked (no signal)"),
                    color = CaptionColor,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
