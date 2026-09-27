package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.core.NetworkDefaults
import com.shinjo.shonanandroid.net.PlutoDiscoveryClient
import kotlinx.coroutines.launch

/**
 * IMEが全角数字/全角ピリオドで確定すること(日本語Gboard等でIPアドレス欄に入力した際に発生)
 * があり、UnknownHostExceptionでPlutoへ接続できなくなるため、半角へ正規化しIPアドレスに
 * 使わない文字を除去する。
 */
private fun normalizeIpInput(raw: String): String =
    raw.map { c ->
        when (c) {
            in '０'..'９' -> '0' + (c - '０')
            '．' -> '.'
            else -> c
        }
    }.filter { it.isDigit() || it == '.' }.joinToString("")

/** 配信先（送信先IP/ポート、受信ポート）の設定画面。 */
@Composable
fun StreamOutputSettingsScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit) {
    val settings = viewModel.settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var discovering by remember { mutableStateOf(false) }
    var discoveryMessage by remember { mutableStateOf<String?>(null) }

    SettingsSubScreen(
        title = settings.t("配信先", "Stream Output"),
        onBack = { onNavigate("tx") },
        homeLabel = settings.t("ホームへ戻る", "Home"),
    ) {
        Text(settings.t("送信先(Pluto Tx)", "TX Destination (Pluto Tx)"), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = settings.txDestinationIP,
            onValueChange = { viewModel.updateSettings { s -> s.copy(txDestinationIP = normalizeIpInput(it)) } },
            label = { Text(settings.t("IPアドレス", "IP Address")) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                enabled = !discovering,
                onClick = {
                    discovering = true
                    discoveryMessage = null
                    scope.launch {
                        val found = PlutoDiscoveryClient.discoverPlutoIp(context)
                        if (found != null) {
                            viewModel.updateSettings { s -> s.copy(txDestinationIP = found) }
                            discoveryMessage = settings.t("Plutoを検出しました: $found", "Found Pluto: $found")
                        } else {
                            discoveryMessage = settings.t(
                                "Plutoが見つかりませんでした(ESP32ブリッジ未接続、またはPluto未接続の可能性)",
                                "Pluto not found (check ESP32 bridge / Pluto connection)",
                            )
                        }
                        discovering = false
                    }
                },
            ) {
                Text(settings.t("自動検出", "Auto-Detect"))
            }
            if (discovering) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text(settings.t("Plutoを検索中(最大数十秒)...", "Scanning for Pluto (up to ~1 min)..."))
            }
        }
        discoveryMessage?.let {
            Text(it, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        OutlinedTextField(
            value = NetworkDefaults.PLUTO_UDP_TS_PORT.toString(),
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(settings.t("ポート(確認用、常に固定)", "Port (fixed, for confirmation only)")) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Text(settings.t("受信設定", "Receive Settings"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
        OutlinedTextField(
            value = settings.rxListenPort.toString(),
            onValueChange = { value -> value.toIntOrNull()?.let { viewModel.updateSettings { s -> s.copy(rxListenPort = it) } } },
            label = { Text(settings.t("TSポート", "TS Port")) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        OutlinedTextField(
            value = settings.rxStatusPort.toString(),
            onValueChange = { value -> value.toIntOrNull()?.let { viewModel.updateSettings { s -> s.copy(rxStatusPort = it) } } },
            label = { Text(settings.t("ステータスポート", "Status Port")) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}
