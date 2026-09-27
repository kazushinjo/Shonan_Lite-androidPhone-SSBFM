package com.shinjo.shonanandroid.tx

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.shinjo.shonanandroid.R

/** [ColorBarSource]が送出するテストパターン画像を表示するプレビュー --
 *  カメラプレビューの代わりにTxScreenへ表示する。 */
@Composable
fun ColorBarPreview(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.test_pattern),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}
