package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.core.AppLanguage

/** Android版の動作モード・バンドプロファイル・詳細設定画面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit) {
    val settings = viewModel.settings
    val scrollState = rememberScrollState()
    var showAttenuatorWarning by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(settings.t("設定", "Settings")) },
                navigationIcon = {
                    HomeBackAction({ onNavigate("tx") }, settings.t("ホームへ戻る", "Home"))
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(settings.t("表示言語", "Display Language"))
            Row(modifier = Modifier.fillMaxWidth()) {
                AppLanguage.entries.forEach { language ->
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .selectable(
                                selected = (settings.language == language),
                                onClick = { viewModel.updateSettings { s -> s.copy(language = language) } },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = (settings.language == language),
                            onClick = { viewModel.updateSettings { s -> s.copy(language = language) } },
                        )
                        Text(settings.t(language.displayName, language.displayNameEnglish), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Text(
                settings.t(
                    "ホーム画面(常時日英併記)を除く、アプリ全画面の表示言語を切り替えます。",
                    "Switches the display language for all screens except the Home screen, which always shows both Japanese and English.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )

            Text(settings.t("受信復調", "Receive Demodulation"), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = settings.useOnDeviceGRDVBS2Rx,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            // ★ONにすると送受信の同時動作(RFループバック)が可能になり、外部
                            // アッテネータなしではTX出力がPlutoのRX入力へ直接回り込み、Pluto本体を
                            // 破損しうる。誤ってONにするのを防ぐため必ず警告を挟む。
                            showAttenuatorWarning = true
                        } else {
                            viewModel.updateSettings { it.copy(useOnDeviceGRDVBS2Rx = false) }
                        }
                    },
                )
                Text(settings.t("オンデバイス復調(GNU Radio)", "On-device demodulation (GNU Radio)"), modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                settings.t(
                    "有効にすると、外部復調機器からのUDP-TS待受ではなく、PlutoのRF IQをAndroid自身で復調します。",
                    "When enabled, Android demodulates Pluto RF IQ locally instead of waiting for UDP-TS from an external demodulator.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (settings.useOnDeviceGRDVBS2Rx) {
                Text(
                    settings.t(
                        "ロールオフ: %.2f(固定)".format(settings.dvbs2Rolloff),
                        "Roll-off: %.2f (fixed)".format(settings.dvbs2Rolloff),
                    ),
                )
                Text(
                    settings.t(
                        "Plutoのオンボード変調はロールオフ0.35固定で変更できないため、この値は編集できません。オンデバイス復調もPlutoに合わせて0.35固定で復調します。",
                        "Pluto's onboard modulator has a fixed roll-off of 0.35 and cannot be changed, so this value is not editable. On-device demodulation also fixes it at 0.35 to match Pluto.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (showAttenuatorWarning) {
        AlertDialog(
            onDismissRequest = { showAttenuatorWarning = false },
            title = { Text(settings.t("オンデバイス復調を有効にしますか？", "Enable on-device demodulation?")) },
            text = {
                Column {
                    Text(
                        settings.t(
                            "オンデバイス復調をONにすると、pluto1台でRFのループバック試験を行えます。画像を送信しながら同時にその画像を受信します。",
                            "Enabling on-device demodulation lets you run an RF loopback test with a single Pluto: you transmit an image while simultaneously receiving that same image.",
                        ),
                    )
                    Text(
                        settings.t(
                            "アッテネータは入っていますか？入れずにこのまま送受信を行うとPlutoが壊れます。",
                            "Is an attenuator connected? Transmitting and receiving like this without one will damage the Pluto.",
                        ),
                        color = androidx.compose.ui.graphics.Color.Red,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateSettings { it.copy(useOnDeviceGRDVBS2Rx = true) }
                        showAttenuatorWarning = false
                    },
                ) {
                    Text(settings.t("アッテネータ接続済み・続行する", "Attenuator connected — proceed"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAttenuatorWarning = false }) {
                    Text(settings.t("キャンセル", "Cancel"))
                }
            },
        )
    }
}
