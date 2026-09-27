package com.shinjo.shonanandroid.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shinjo.shonanandroid.AppViewModel
import com.shinjo.shonanandroid.tx.CameraPosition

private val OuterCardBorder = Color(0xFF34434B)
private val OuterCardBackground = Color(0xFF101416)
private val InnerCardBackground = Color(0xFF191D1F)
private val TitleCyan = Color(0xFF54BCE0)
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
 * 映像ソース設定画面 -- Shonan_Lite-pi5(`pi5/gui/screens/videosource.py`)の見た目に
 * できる限り忠実に合わせた版。外枠カードを画面いっぱいに広げ、pi5に存在しない運用メモ
 * 注記は削除した。pi5にある解像度/フレームレートのコンボボックスはAndroid版では固定
 * (Full HD/30fps)のため、代わりにマイク音声送信のトグルを下段に配置する。
 * 写真ソース選択時のコールサイン/備考入力欄はpi5に対応機能がないAndroid独自の実機能
 * のため、外枠カード内に残す。
 */
@Composable
fun VideoSourceSettingsScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit) {
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

    // iPad版`VideoSourceSettingsView`のonAppear/onChange/onDisappearと同じく、この画面を
    // 表示している間だけ(送信中でなければ)カメラプレビューを起動する。カメラ選択時のみ
    // 起動し、他ソース選択時や画面離脱時は停止する(TxScreenと同じカメラを共有するため、
    // 実送信中はTxController.start側が管理しており何もしない)。
    DisposableEffect(settings.useFrontCamera, settings.useColorBarSource, settings.usePhotoSource) {
        val isCameraSource = !settings.useColorBarSource && !settings.usePhotoSource
        if (isCameraSource) {
            viewModel.txController.startCameraPreview(if (settings.useFrontCamera) CameraPosition.FRONT else CameraPosition.BACK)
        } else {
            viewModel.txController.stopCameraPreviewIfIdle()
        }
        onDispose { viewModel.txController.stopCameraPreviewIfIdle() }
    }

    SettingsSubScreen(
        title = settings.t("映像ソース", "Video Source"),
        onBack = { onNavigate("tx") },
        homeLabel = settings.t("ホームへ戻る", "Home"),
        scrollEnabled = false,
        contentPadding = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(OuterCardBackground, RoundedCornerShape(14.dp))
                .border(1.dp, OuterCardBorder, RoundedCornerShape(14.dp))
                .padding(12.dp, 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(InnerCardBackground, RoundedCornerShape(10.dp))
                    .border(1.dp, OuterCardBorder, RoundedCornerShape(10.dp))
                    .padding(10.dp, 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    settings.t("映像ソース選択", "Video Source"),
                    color = TitleCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
                SourceChip(
                    settings.t("背面カメラ(既定)", "Rear Camera"),
                    selected = currentOption == VideoSourceOption.CAMERA_BACK,
                ) { applyVideoSourceOption(viewModel, VideoSourceOption.CAMERA_BACK) }
                SourceChip(
                    settings.t("前面カメラ", "Front Camera"),
                    selected = currentOption == VideoSourceOption.CAMERA_FRONT,
                ) { applyVideoSourceOption(viewModel, VideoSourceOption.CAMERA_FRONT) }
                SourceChip(
                    settings.t("写真(写真フォルダー)", "Photo (Folder)"),
                    selected = currentOption == VideoSourceOption.PHOTO,
                ) { photoPicker.launch(arrayOf("image/*")) }
                SourceChip(
                    settings.t("テストパターン", "Test Pattern"),
                    selected = currentOption == VideoSourceOption.COLOR_BAR,
                ) { applyVideoSourceOption(viewModel, VideoSourceOption.COLOR_BAR) }

                if (settings.usePhotoSource) {
                    Text(
                        if (settings.selectedPhotoUri.isNullOrBlank()) settings.t("写真が未選択です", "No photo selected")
                        else settings.t("選択済み: ${settings.selectedPhotoUri!!.substringAfterLast('/')}", "Selected: ${settings.selectedPhotoUri!!.substringAfterLast('/')}"),
                        color = Color(0xFFCCCCCC),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = settings.photoCallsign,
                        onValueChange = { viewModel.updateSettings { s -> s.copy(photoCallsign = it) } },
                        label = { Text(settings.t("コールサイン", "Callsign")) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                        ),
                    )
                    OutlinedTextField(
                        value = settings.photoNote,
                        onValueChange = { viewModel.updateSettings { s -> s.copy(photoNote = it) } },
                        label = { Text(settings.t("備考", "Note")) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        minLines = 2,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                        ),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(settings.t("マイク音声を送信", "Transmit Mic Audio"), color = Color(0xFFEEEEEE), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.transmitAudio,
                    onCheckedChange = { enabled -> viewModel.updateSettings { it.copy(transmitAudio = enabled) } },
                )
            }
        }
    }
}

@Composable
private fun SourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) AccentBlue else ChipBackground,
            contentColor = Color.White,
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.fillMaxWidth().height(30.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
    }
}
