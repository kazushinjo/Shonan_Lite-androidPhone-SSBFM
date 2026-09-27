package com.shinjo.shonanandroid.tx

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PhotoPreview(uri: String?, callsign: String, note: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri, callsign, note) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(uri, callsign, note) {
        bitmap?.recycle()
        bitmap = withContext(Dispatchers.IO) {
            PhotoSource.loadOverlayBitmap(context, uri, callsign, note)
        }
    }
    DisposableEffect(Unit) {
        onDispose { bitmap?.recycle() }
    }

    Box(modifier = modifier.background(Color.Black)) {
        bitmap?.let {
            Image(bitmap = it.asImageBitmap(), contentDescription = "選択した写真", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}
