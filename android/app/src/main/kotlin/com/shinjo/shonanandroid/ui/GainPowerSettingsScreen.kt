package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
private val TitleColor = Color(0xFFCCCCCC)
private val CaptionColor = Color(0xFF9AA0A6)
private val AccentCyan = Color(0xFF0C9BC0)
private val LevelGreen = Color(0xFF36B47A)
private val LevelTrack = Color(0xFF30383C)

/**
 * DATVの受信感度(RXゲイン/AGC)と送信出力(出力減衰量)を1画面にまとめたタブ(「設定２」)。
 * 横向きの電話画面に収めるため、他の設定画面のような上部バーは置かず、左右2カードに並べる。
 * SSB/FMタブの利得・減衰はそのタブ内で別に持つ(ここはDATV送受信用)。
 */
@Composable
fun GainPowerSettingsScreen(viewModel: AppViewModel) {
    val settings = viewModel.settings
    val locked = viewModel.rxController.isLocked

    Row(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingCard(settings.t("受信感度(RXゲイン)", "RX Gain"), Modifier.weight(1f), trailing = {
            Text(settings.t("自動(AGC)", "Auto (AGC)"), color = CaptionColor, fontSize = 12.sp)
            Switch(
                checked = settings.rxAgcEnabled,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(rxAgcEnabled = enabled) } },
                colors = SwitchDefaults.colors(checkedTrackColor = AccentCyan),
                modifier = Modifier.padding(start = 4.dp).height(28.dp),
            )
        }) {
            Text(
                if (settings.rxAgcEnabled) settings.t("AGC", "AGC") else "${settings.rxGainDb} dB",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Slider(
                value = settings.rxGainDb.toFloat(),
                onValueChange = { value -> viewModel.updateSettings { it.copy(rxGainDb = value.toInt()) } },
                valueRange = 0f..73f,
                steps = 72,
                enabled = !settings.rxAgcEnabled,
                colors = SliderDefaults.colors(
                    thumbColor = AccentCyan,
                    activeTrackColor = AccentCyan,
                    disabledThumbColor = Color(0xFF5F6B70),
                    disabledActiveTrackColor = Color(0xFF3E474B),
                    disabledInactiveTrackColor = Color(0xFF2A3134),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (settings.rxAgcEnabled) {
                    settings.t("AGCが受信ゲインを自動調整します。", "AGC adjusts the receive gain automatically.")
                } else {
                    settings.t("Plutoの受信ゲインにこの値(0〜73 dB)を適用します。", "This value (0–73 dB) is applied to Pluto's receive gain.")
                },
                color = CaptionColor,
                fontSize = 12.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(settings.t("信号レベル", "Signal"), color = TitleColor, fontSize = 12.sp)
                LinearProgressIndicator(
                    progress = { if (locked) 1f else 0f },
                    color = LevelGreen,
                    trackColor = LevelTrack,
                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(10.dp),
                )
                Text(
                    if (locked) settings.t("ロック中", "Locked") else settings.t("未ロック", "No lock"),
                    color = if (locked) LevelGreen else CaptionColor,
                    fontSize = 12.sp,
                )
            }
        }

        SettingCard(settings.t("送信出力(出力減衰量)", "TX Power (Attenuation)"), Modifier.weight(1f)) {
            Text("${settings.txPowerDb} dB", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Slider(
                value = settings.txPowerDb.toFloat(),
                onValueChange = { value -> viewModel.updateSettings { it.copy(txPowerDb = value.toInt()) } },
                valueRange = -70f..0f,
                steps = 69,
                colors = SliderDefaults.colors(thumbColor = AccentCyan, activeTrackColor = AccentCyan),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("-70", "-50", "-30", "-10", "0").forEach { Text(it, color = CaptionColor, fontSize = 10.sp) }
            }
            Text(
                settings.t(
                    "0 dBが最大出力です。Plutoの送信出力減衰値として適用します。",
                    "0 dB is maximum output. Applied as Pluto's TX attenuation.",
                ),
                color = CaptionColor,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SettingCard(
    title: String,
    modifier: Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxHeight()
            .background(CardBackground, RoundedCornerShape(10.dp))
            .padding(12.dp, 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TitleColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        content()
    }
}
