package com.shinjo.shonanandroid.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 設定サブ画面で共通利用する骨格(上部にタイトルだけのTopAppBar)。電話版は独立したホーム画面が無く
 * 上部のタブで画面を切り替えるため、「ホームへ戻る」ボタンは置かない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSubScreen(
    title: String,
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
