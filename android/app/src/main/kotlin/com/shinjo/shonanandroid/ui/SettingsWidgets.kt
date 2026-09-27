package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 設定サブ画面で共通利用する骨格。TopAppBarの左側は矢印アイコン単体ではなく、
 * pi5版の「ホームへ戻る」ボタンに相当する矢印+ラベルのボタンにする(戻り先は常に
 * ホーム画面。呼び出し側の[onBack]がその遷移処理を担う)。本文側に別途ホームボタンを
 * 重複して置かないこと。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSubScreen(
    title: String,
    onBack: () -> Unit,
    homeLabel: String = "Home",
    scrollState: ScrollState = rememberScrollState(),
    scrollEnabled: Boolean = true,
    // ★ヘルプ画面のように本文がテキスト中心でスクロール領域自体を広く取りたい画面向けに、
    // 既定の余白(top16dp/bottom32dp)を呼び出し側で狭められるようにする。
    contentPadding: Modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 32.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { HomeBackAction(onBack, homeLabel) },
            )
        },
    ) { padding ->
        // ★Modifier.verticalScrollはenabled=falseでもドラッグ操作を無効化するだけで、
        // 子を無限の高さ制約で計測する挙動自体は残る。scrollEnabled=falseの画面
        // (pi5同様に画面いっぱいの固定カードにしたい画面)ではfillMaxHeight()/weight()が
        // 無限制約下では機能しない(0になったり無視されたりする)ため、verticalScroll自体を
        // 付与しないことで有限の高さ制約を子へ伝える。
        val baseModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .navigationBarsPadding()
            .then(contentPadding)
        Column(
            modifier = if (scrollEnabled) baseModifier.verticalScroll(scrollState) else baseModifier,
            content = content,
        )
    }
}

/**
 * 矢印+「ホームへ戻る」ラベルの戻るボタン。SettingsSubScreen以外の独自Scaffold
 * (SettingsScreen/ManualScreen等)のnavigationIconからも共用する。本文側に同機能の
 * ボタンを別途置くと二重実装になるため置かないこと。
 */
@Composable
fun HomeBackAction(onClick: () -> Unit, label: String) {
    TextButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.width(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label)
    }
}

/** 選択肢を表示するラジオボタンの1行。 */
@Composable
fun <T> RadioOptionRow(label: String, value: T, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = (value == selected), onClick = { onSelect(value) })
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = (value == selected), onClick = { onSelect(value) })
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** 設定画面の補足説明を表示する注記文。 */
@Composable
fun OperationalMemoNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
