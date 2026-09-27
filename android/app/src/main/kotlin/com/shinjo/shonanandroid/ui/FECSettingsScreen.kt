package com.shinjo.shonanandroid.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.core.FECRate

private val CardBackground = Color(0xFF191D1F)
private val CaptionColor = Color(0xFF9AA0A6)
private val ChipBackground = Color(0xFF303538)
private val AccentBlue = Color(0xFF1677FF)
private val AccentOrange = Color(0xFFD08A20)

private fun overheadPercent(rate: FECRate): Double {
    val (num, den) = rate.label.split("/").map { it.toInt() }
    return (1 - num.toDouble() / den) * 100
}

private fun describe(rate: FECRate, settings: com.shinjo.shonanandroid.core.AppSettings): String {
    val overhead = overheadPercent(rate)
    return when {
        overhead >= 40 -> settings.t(
            "誤り訂正能力を重視した設定です。C/Nが低い環境でも安定して復調しやすくなります。",
            "Prioritizes error correction strength. Easier to demodulate stably even in low C/N conditions.",
        )
        overhead >= 15 -> settings.t(
            "誤り訂正能力とスループットのバランスが取れた設定です。",
            "A balanced setting between error correction strength and throughput.",
        )
        else -> settings.t(
            "スループットを重視した設定です。良好なC/N環境で高い伝送効率を得られます。",
            "Prioritizes throughput. Achieves high transmission efficiency in good C/N conditions.",
        )
    }
}

/**
 * FEC符号化率設定画面 -- Shonan_Lite-pi5(`pi5/gui/screens/fec.py`)の見た目にできる限り
 * 忠実に合わせた版。画面は「左FECモード選択(幅220dp)/中央説明+オーバーヘッドバー/
 * 右フレーム構成(幅140dp)」の3カードのみで縦幅いっぱいに構成し、pi5に存在しない
 * FEC候補表示・TX/RX一致の注意文・運用メモ注記はこの画面からは削除した
 * (Mod-Cod組み合わせの注記はpi5同様に中央カード内に配置)。
 */
@Composable
fun FECSettingsScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit) {
    val settings = viewModel.settings
    val current = settings.fecRate
    val overhead = overheadPercent(current)

    SettingsSubScreen(
        title = settings.t("誤り訂正", "FEC"),
        onBack = { onNavigate("tx") },
        homeLabel = settings.t("ホームへ戻る", "Home"),
        scrollEnabled = false,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- 左カラム: FECモード選択(2列グリッド) ---
            Column(
                modifier = Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .padding(12.dp, 10.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    settings.t("FECモード選択", "FEC Mode"),
                    color = Color(0xFFCCCCCC),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                val rates = FECRate.entries
                val rowsPerCol = (rates.size + 1) / 2
                (0 until rowsPerCol).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (col in 0..1) {
                            val index = col * rowsPerCol + row
                            if (index < rates.size) {
                                val rate = rates[index]
                                val selected = rate == current
                                Button(
                                    onClick = { viewModel.updateSettings { s -> s.copy(fecRate = rate) } },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (selected) AccentBlue else ChipBackground,
                                        contentColor = Color.White,
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                                    modifier = Modifier.weight(1f).height(28.dp),
                                ) {
                                    Text(rate.label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // --- 中央カラム: 説明 ---
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .padding(16.dp, 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    settings.t("現在の設定: ${current.label}", "Current: ${current.label}"),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(describe(current, settings), color = Color(0xFFCCCCCC), fontSize = 13.sp)
                Text(
                    settings.t("オーバーヘッド: %.0f%%".format(overhead), "Overhead: %.0f%%".format(overhead)),
                    color = CaptionColor,
                    fontSize = 12.sp,
                )

                val dataWeight = maxOf(1f, (100 - overhead).toFloat())
                val fecWeight = maxOf(1f, overhead.toFloat())
                Row(modifier = Modifier.fillMaxWidth().height(14.dp)) {
                    Spacer(modifier = Modifier.weight(dataWeight).height(14.dp).background(AccentBlue, RoundedCornerShape(4.dp)))
                    Spacer(modifier = Modifier.width(2.dp))
                    Spacer(modifier = Modifier.weight(fecWeight).height(14.dp).background(AccentOrange, RoundedCornerShape(4.dp)))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendDot(AccentBlue, "DATA")
                    Spacer(modifier = Modifier.width(16.dp))
                    LegendDot(AccentOrange, settings.t("FEC(パリティ)", "FEC (parity)"))
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    settings.t(
                        "変調方式(Modulation画面)と組み合わせてMod-Codを構成します。対応組み合わせ以外を選ぶと送受信開始時にエラーになります。",
                        "Combined with the modulation scheme (Modulation screen) to form the Mod-Cod. Choosing an unsupported combination causes an error when starting TX/RX.",
                    ),
                    color = Color(0xFF999999),
                    fontSize = 14.sp,
                )
            }

            // --- 右カラム: フレーム構成(DATA/FEC/PARITY) ---
            Column(
                modifier = Modifier
                    .width(140.dp)
                    .fillMaxHeight()
                    .background(CardBackground, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    settings.t("フレーム構成", "Frame"),
                    color = Color(0xFFCCCCCC),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                FrameBadge("DATA", active = false)
                FrameBadge("FEC", active = true)
                FrameBadge("PARITY", active = false)
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text, color = Color(0xFFCCCCCC), fontSize = 12.sp)
    }
}

@Composable
private fun FrameBadge(text: String, active: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(if (active) AccentBlue else ChipBackground, RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
