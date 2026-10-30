/* HexDroidIRC — Copyright (C) 2026 boxlabs. GPL-3.0-or-later. */
package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.R

@Composable
fun AvatarThumbnail(url: String, nick: String, modifier: Modifier = Modifier, refreshToken: Int = 0, onOpen: () -> Unit) {
    var image by remember(url, refreshToken) { mutableStateOf(RemoteImage.cached(url)) }
    LaunchedEffect(url, refreshToken) { if (image == null) image = RemoteImage.fetch(url) }
    Box(modifier.clip(CircleShape).clickable(onClick = onOpen).testTag("avatar-$nick"), contentAlignment = Alignment.Center) {
        image?.let {
            Image(it, stringResource(R.string.dreams_expand_avatar, nick), contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High, modifier = Modifier.fillMaxSize())
        } ?: Icon(Icons.Default.Person, stringResource(R.string.dreams_expand_avatar, nick), Modifier.fillMaxSize())
    }
}

@Composable
fun AvatarViewer(url: String, nick: String, onDismiss: () -> Unit,
                 load: suspend (String, Boolean) -> ImageBitmap? = { source, retry -> RemoteImage.fetchFull(source, retry) }) {
    var attempt by remember(url) { mutableIntStateOf(0) }
    var loading by remember(url) { mutableStateOf(true) }
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, attempt) {
        loading = true
        image = load(url, attempt > 0)
        loading = false
    }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("avatar-viewer"),
        title = { Text(nick) },
        text = {
            Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator(Modifier.testTag("avatar-loading"))
                    image != null -> Image(image!!, stringResource(R.string.dreams_full_avatar),
                        contentScale = ContentScale.Fit, filterQuality = FilterQuality.High,
                        modifier = Modifier.fillMaxSize().testTag("avatar-full-image"))
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.dreams_avatar_load_error))
                        TextButton(onClick = { attempt++ }, modifier = Modifier.testTag("avatar-retry")) { Text(stringResource(R.string.dreams_retry)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}
