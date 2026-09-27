package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel

private val CardBackground = Color(0xFF191D1F)
private val CaptionColor = Color(0xFF9AA0A6)
private val AccentCyan = Color(0xFF0C9BC0)

/**
 * TX出力設定画面 -- Shonan_Lite-pi5(`pi5/gui/screens/txpower.py`)と同じ
 * 「ダークカード内にタイル状の目盛り+シアンのスライダー+中央大きな数値表示」の
 * レイアウトに変更([[shonan-lite-ipad-tx-rx-pi5-design]]と同様の移植)。
 */
@Composable
fun TxPowerSettingsScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit) {
    val settings = viewModel.settings

    SettingsSubScreen(
        title = settings.t("送信出力", "TX Power"),
        onBack = { onNavigate("tx") },
        homeLabel = settings.t("ホームへ戻る", "Home"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardBackground, RoundedCornerShape(12.dp))
                .padding(16.dp, 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                settings.t("出力減衰量設定", "TX Attenuation"),
                color = CaptionColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("-70", "-50", "-30", "-10", "0").forEach { tick ->
                    Text(tick, color = CaptionColor, fontSize = 10.sp)
                }
            }
            Slider(
                value = settings.txPowerDb.toFloat(),
                onValueChange = { value -> viewModel.updateSettings { it.copy(txPowerDb = value.toInt()) } },
                valueRange = -70f..0f,
                steps = 69,
                colors = SliderDefaults.colors(thumbColor = AccentCyan, activeTrackColor = AccentCyan),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${settings.txPowerDb} dB",
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }

        OperationalMemoNote(
            settings.t(
                "0 dBが最大出力です。通常送信ではPlutoの送信出力減衰値として適用します。",
                "0 dB is maximum output. During normal transmission, this value is applied as Pluto TX power attenuation.",
            ),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
