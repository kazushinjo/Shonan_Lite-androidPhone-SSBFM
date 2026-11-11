package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.shinjo.shonanandroid.core.AppLanguage
import com.shinjo.shonanandroid.core.AppSettings
import com.shinjo.shonanandroid.net.PlutoInfoReader
import kotlinx.coroutines.launch

private enum class PlutoInfoKind { VERSION, SETTINGS, AD9361 }

/**
 * 設定画面の「Pluto 情報」(iPad/Mac版 SettingsView と同じ内容)。どれも読み取りのみで、
 * Plutoには何も書き込まない。PlutoのIPは送信先(Pluto Tx)のtxDestinationIPを使う。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlutoInfoSection(settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf<PlutoInfoKind?>(null) }
    var shown by remember { mutableStateOf<PlutoInfoKind?>(null) }
    var rows by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val english = settings.language != AppLanguage.JAPANESE

    fun load(kind: PlutoInfoKind) {
        val host = settings.txDestinationIP
        loading = kind
        shown = kind
        rows = emptyList()
        error = null
        scope.launch {
            try {
                rows = when (kind) {
                    PlutoInfoKind.VERSION -> PlutoInfoReader.readVersion(host, english)
                    PlutoInfoKind.SETTINGS -> PlutoInfoReader.readSettingsTxt(host)
                    PlutoInfoKind.AD9361 -> PlutoInfoReader.readAd9361(host, english)
                }
            } catch (e: PlutoInfoReader.PlutoInfoException) {
                error = e.message
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                loading = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(settings.t("Pluto 情報", "Pluto Info"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(settings.txDestinationIP, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        // ★電話の狭い画面でもボタンがはみ出さないよう、入りきらなければ折り返す。
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((kind, label) in listOf(
                PlutoInfoKind.VERSION to settings.t("バージョン", "Version"),
                PlutoInfoKind.SETTINGS to settings.t("セッティング", "Settings"),
                PlutoInfoKind.AD9361 to "AD9361",
            )) {
                if (shown == kind) {
                    Button(onClick = { load(kind) }, enabled = loading == null,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0C9BC0))) { Text(label) }
                } else {
                    OutlinedButton(onClick = { load(kind) }, enabled = loading == null) { Text(label) }
                }
            }
            if (loading != null) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        error?.let {
            Text(settings.t("取得できませんでした: ", "Failed: ") + it, color = Color(0xFFD32F2F), style = MaterialTheme.typography.bodySmall)
        }
        if (rows.isNotEmpty()) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((label, value) in rows) {
                        Row {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.Gray, modifier = Modifier.width(150.dp))
                            Text(value.ifEmpty { "-" }, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
        Text(
            settings.t(
                "送信先(Pluto Tx)のIPアドレスのPlutoから読み取ります(書き込みはしません)。バージョンとAD9361はiiod経由、セッティングはWeb UIのsettings.txt(送信時にアプリが書き込む値)です。IPアドレス等のネットワーク設定は表示されません。",
                "Reads from the Pluto at the TX destination IP (read only). Version and AD9361 come from iiod; Settings is the Web UI settings.txt written by this app on TX. Network settings such as IP addresses are not shown.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
