package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.core.ModulationScheme
import kotlin.math.cos
import kotlin.math.sin

private val OuterCardBorder = Color(0xFF34434B)
private val OuterCardBackground = Color(0xFF101416)
private val InnerCardBackground = Color(0xFF191D1F)
private val TitleCyan = Color(0xFF54BCE0)
private val ChipBackground = Color(0xFF303538)
private val ChipDisabledBackground = Color(0xFF252A2D)
private val ChipDisabledText = Color(0xFF777777)
private val AccentBlue = Color(0xFF1677FF)
private val ConstellationBackground = Color(0xFF050607)
private val ConstellationBorder = Color(0xFF46545B)
private val AxisColor = Color(0xFF8397A0)
private val PointColor = Color(0xFF38B8E3)

/** pi5モックに合わせた変調方式一覧(実際に対応しているのは[ModulationScheme]の2種のみ)。 */
private data class MockScheme(val label: String, val bitsPerSymbol: Int, val scheme: ModulationScheme?)

private val mockSchemes = listOf(
    MockScheme("QPSK", 2, ModulationScheme.QPSK),
    MockScheme("8PSK", 3, ModulationScheme.PSK8),
)

private fun constellationPoints(label: String): List<Offset> = when (label) {
    "QPSK" -> listOf(Offset(-.72f, -.72f), Offset(.72f, -.72f), Offset(-.72f, .72f), Offset(.72f, .72f))
    else -> {
        val count = when (label) {
            "8PSK" -> 8
            else -> 4
        }
        (0 until count).map { i ->
            val angle = 2 * Math.PI * i / count
            Offset(cos(angle).toFloat(), sin(angle).toFloat())
        }
    }
}

/**
 * 変調方式設定画面 -- Shonan_Lite-pi5(`pi5/gui/screens/modulation.py`)の見た目に
 * できる限り忠実に合わせた版。画面いっぱいの外枠カード内に「左変調方式選択(比率1)/
 * 右コンステレーション表示(比率2)」を配置し、pi5に存在しない運用メモ注記はこの画面
 * からは削除した。
 */
@Composable
fun ModulationSettingsScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit) {
    val settings = viewModel.settings
    val currentLabel = settings.modulationScheme.label
    val currentMock = mockSchemes.firstOrNull { it.label == currentLabel } ?: mockSchemes[1]

    SettingsSubScreen(
        title = settings.t("変調方式", "Modulation"),
        onBack = { onNavigate("tx") },
        homeLabel = settings.t("ホームへ戻る", "Home"),
        scrollEnabled = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(OuterCardBackground, RoundedCornerShape(14.dp))
                .border(1.dp, OuterCardBorder, RoundedCornerShape(14.dp))
                .padding(12.dp, 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(InnerCardBackground, RoundedCornerShape(10.dp))
                        .border(1.dp, OuterCardBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp, 8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        settings.t("変調方式選択", "Modulation Scheme"),
                        color = TitleCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    mockSchemes.forEach { mock ->
                        val selected = mock.scheme != null && mock.scheme == settings.modulationScheme
                        val enabled = mock.scheme != null
                        Button(
                            onClick = {
                                mock.scheme?.let { scheme -> viewModel.updateSettings { s -> s.copy(modulationScheme = scheme) } }
                            },
                            enabled = enabled,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) AccentBlue else ChipBackground,
                                contentColor = Color.White,
                                disabledContainerColor = ChipDisabledBackground,
                                disabledContentColor = ChipDisabledText,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.fillMaxWidth().height(28.dp),
                        ) {
                            Text(
                                mock.label,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxHeight()
                        .background(InnerCardBackground, RoundedCornerShape(10.dp))
                        .border(1.dp, OuterCardBorder, RoundedCornerShape(10.dp))
                        .padding(6.dp),
                ) {
                    // ★「コンステレーション」ラベル用に専用の行を確保すると、その分だけ
                    // Canvas(円)の高さが削られて円が小さくなってしまう。ラベルは図の上に
                    // 重ねて表示し、Canvas自体はカード全体の高さをそのまま使えるようにする。
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ConstellationBackground, RoundedCornerShape(8.dp))
                            .border(1.dp, ConstellationBorder, RoundedCornerShape(8.dp)),
                    ) {
                        val marginX = 4.dp.toPx()
                        val marginY = 4.dp.toPx()
                        val left = marginX
                        val top = marginY
                        val right = size.width - marginX
                        val bottom = size.height - marginY
                        val centerX = (left + right) / 2f
                        val centerY = (top + bottom) / 2f
                        val points = constellationPoints(currentMock.label)
                        // ★点の大きさをカードの実際のサイズ(=円の最大半径)に比例させる。
                        // 円が大きくなっても点数が多いほど隣接する点が重ならないよう、
                        // 最大半径に対する比率を小さめにして点は控えめな大きさにする。
                        val maxRadius = minOf(right - left, bottom - top) / 2f
                        val pointRadius = (maxRadius * (0.35f / points.size)).coerceIn(2.dp.toPx(), 6.dp.toPx())
                        val radius = maxRadius - pointRadius

                        drawLine(AxisColor, Offset(left, centerY), Offset(right, centerY), strokeWidth = 1.dp.toPx())
                        drawLine(AxisColor, Offset(centerX, top), Offset(centerX, bottom), strokeWidth = 1.dp.toPx())
                        points.forEach { point ->
                            drawCircle(
                                color = PointColor,
                                radius = pointRadius,
                                center = Offset(centerX + point.x * radius, centerY - point.y * radius),
                            )
                        }
                    }
                    Text(
                        settings.t("コンステレーション", "Constellation"),
                        color = TitleCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .background(ConstellationBackground.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(settings.t("ビット/シンボル：", "Bits/symbol: "), color = Color(0xFFEEEEEE), fontSize = 13.sp)
                Text("${currentMock.bitsPerSymbol}", color = Color(0xFFEEEEEE), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.weight(1f))
                Text(currentMock.label, color = TitleCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
