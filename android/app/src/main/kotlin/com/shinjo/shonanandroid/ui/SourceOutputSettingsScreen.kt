package com.shinjo.shonanandroid.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.core.NetworkDefaults
import com.shinjo.shonanandroid.net.PlutoDiscoveryClient
import com.shinjo.shonanandroid.tx.CameraPosition
import kotlinx.coroutines.launch

private val CardBackground = Color(0xFF191D1F)
private val TitleCyan = Color(0xFF54BCE0)
private val CaptionColor = Color(0xFF9AA0A6)
private val ChipBackground = Color(0xFF303538)
private val AccentBlue = Color(0xFF1677FF)

private enum class VideoSourceOption { CAMERA_BACK, CAMERA_FRONT, PHOTO, COLOR_BAR }

private fun videoSourceOption(useFrontCamera: Boolean, useColorBarSource: Boolean, usePhotoSource: Boolean): VideoSourceOption = when {
    usePhotoSource -> VideoSourceOption.PHOTO
    useColorBarSource -> VideoSourceOption.COLOR_BAR
    useFrontCamera -> VideoSourceOption.CAMERA_FRONT
    else -> VideoSourceOption.CAMERA_BACK
}

private fun applyVideoSourceOption(viewModel: AppViewModel, option: VideoSourceOption) {
    viewModel.updateSettings { s ->
        s.copy(
            useFrontCamera = option == VideoSourceOption.CAMERA_FRONT,
            usePhotoSource = option == VideoSourceOption.PHOTO,
            useColorBarSource = option == VideoSourceOption.COLOR_BAR,
        )
    }
}

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

/**
 * 「設定４」タブ: 映像ソース(左)と配信先(右)を1画面にまとめたもの。
 * 横向きの電話画面に収めるため上部バーは置かず、各カードの中を縦スクロールにする。
 */
@Composable
fun SourceOutputSettingsScreen(viewModel: AppViewModel) {
    val settings = viewModel.settings

    // 映像ソース画面と同じく、このタブを表示している間だけ(送信中でなければ)カメラプレビューを起動する。
    // カメラ選択時のみ起動し、他ソース選択時やタブ離脱時は停止する(送信中はTxController側が管理)。
    DisposableEffect(settings.useFrontCamera, settings.useColorBarSource, settings.usePhotoSource) {
        val isCameraSource = !settings.useColorBarSource && !settings.usePhotoSource
        if (isCameraSource) {
            viewModel.txController.startCameraPreview(if (settings.useFrontCamera) CameraPosition.FRONT else CameraPosition.BACK)
        } else {
            viewModel.txController.stopCameraPreviewIfIdle()
        }
        onDispose { viewModel.txController.stopCameraPreviewIfIdle() }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VideoSourceCard(viewModel, Modifier.weight(1f))
        StreamOutputCard(viewModel, Modifier.weight(1f))
    }
}

@Composable
private fun SettingCard(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxHeight()
            .background(CardBackground, RoundedCornerShape(10.dp))
            .padding(12.dp, 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = TitleCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = CaptionColor,
    focusedLabelColor = TitleCyan,
    unfocusedLabelColor = CaptionColor,
    disabledLabelColor = CaptionColor,
)

@Composable
private fun VideoSourceCard(viewModel: AppViewModel, modifier: Modifier) {
    val settings = viewModel.settings
    val context = LocalContext.current
    val currentOption = videoSourceOption(settings.useFrontCamera, settings.useColorBarSource, settings.usePhotoSource)
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.updateSettings {
            it.copy(usePhotoSource = true, useColorBarSource = false, useFrontCamera = false, selectedPhotoUri = uri.toString())
        }
    }

    SettingCard(settings.t("映像ソース", "Video Source"), modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceChip(settings.t("背面カメラ", "Rear Camera"), currentOption == VideoSourceOption.CAMERA_BACK, Modifier.weight(1f)) {
                applyVideoSourceOption(viewModel, VideoSourceOption.CAMERA_BACK)
            }
            SourceChip(settings.t("前面カメラ", "Front Camera"), currentOption == VideoSourceOption.CAMERA_FRONT, Modifier.weight(1f)) {
                applyVideoSourceOption(viewModel, VideoSourceOption.CAMERA_FRONT)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SourceChip(settings.t("写真", "Photo"), currentOption == VideoSourceOption.PHOTO, Modifier.weight(1f)) {
                photoPicker.launch(arrayOf("image/*"))
            }
            SourceChip(settings.t("テストパターン", "Test Pattern"), currentOption == VideoSourceOption.COLOR_BAR, Modifier.weight(1f)) {
                applyVideoSourceOption(viewModel, VideoSourceOption.COLOR_BAR)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                settings.t("マイク音声を送信", "Transmit Mic Audio"),
                color = Color(0xFFEEEEEE),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = settings.transmitAudio,
                onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(transmitAudio = enabled) } },
            )
        }

        if (settings.usePhotoSource) {
            Text(
                if (settings.selectedPhotoUri.isNullOrBlank()) settings.t("写真が未選択です", "No photo selected")
                else settings.t(
                    "選択済み: ${settings.selectedPhotoUri!!.substringAfterLast('/')}",
                    "Selected: ${settings.selectedPhotoUri!!.substringAfterLast('/')}",
                ),
                color = Color(0xFFCCCCCC),
                fontSize = 12.sp,
            )
            OutlinedTextField(
                value = settings.photoCallsign,
                onValueChange = { viewModel.updateSettings { s -> s.copy(photoCallsign = it) } },
                label = { Text(settings.t("コールサイン", "Callsign")) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = darkFieldColors(),
            )
            OutlinedTextField(
                value = settings.photoNote,
                onValueChange = { viewModel.updateSettings { s -> s.copy(photoNote = it) } },
                label = { Text(settings.t("備考", "Note")) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                colors = darkFieldColors(),
            )
        }
    }
}

@Composable
private fun StreamOutputCard(viewModel: AppViewModel, modifier: Modifier) {
    val settings = viewModel.settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var discovering by remember { mutableStateOf(false) }
    var discoveryMessage by remember { mutableStateOf<String?>(null) }

    SettingCard(settings.t("配信先", "Stream Output"), modifier) {
        OutlinedTextField(
            value = settings.txDestinationIP,
            onValueChange = { viewModel.updateSettings { s -> s.copy(txDestinationIP = normalizeIpInput(it)) } },
            label = { Text(settings.t("送信先(Pluto Tx)のIPアドレス", "TX Destination (Pluto Tx) IP")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            colors = darkFieldColors(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White),
            ) {
                Text(settings.t("自動検出", "Auto-Detect"), fontSize = 13.sp)
            }
            if (discovering) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = TitleCyan)
                Text(settings.t("検索中(最大数十秒)…", "Scanning (up to ~1 min)…"), color = CaptionColor, fontSize = 12.sp)
            }
        }
        discoveryMessage?.let { Text(it, color = Color(0xFFCCCCCC), fontSize = 12.sp) }
        Text(
            settings.t(
                "UDP-TSポート: ${NetworkDefaults.PLUTO_UDP_TS_PORT}(Pluto側固定)",
                "UDP-TS port: ${NetworkDefaults.PLUTO_UDP_TS_PORT} (fixed on Pluto)",
            ),
            color = CaptionColor,
            fontSize = 12.sp,
        )

        Text(settings.t("受信設定", "Receive Settings"), color = TitleCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = settings.rxListenPort.toString(),
                onValueChange = { value -> value.toIntOrNull()?.let { viewModel.updateSettings { s -> s.copy(rxListenPort = it) } } },
                label = { Text(settings.t("TSポート", "TS Port")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                colors = darkFieldColors(),
            )
            OutlinedTextField(
                value = settings.rxStatusPort.toString(),
                onValueChange = { value -> value.toIntOrNull()?.let { viewModel.updateSettings { s -> s.copy(rxStatusPort = it) } } },
                label = { Text(settings.t("ステータスポート", "Status Port")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                colors = darkFieldColors(),
            )
        }
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) AccentBlue else ChipBackground,
            contentColor = Color.White,
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
        modifier = modifier.height(32.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
