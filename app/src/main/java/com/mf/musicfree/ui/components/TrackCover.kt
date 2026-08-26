package com.mf.musicfree.ui.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

private fun String?.upscaleCover(): String? {
    return this?.replace("100x100bb.jpg", "600x600bb.jpg")
        ?.replace("100x100bb", "600x600bb")
        ?.replace("100x100", "600x600")
}

@Composable
fun TrackCover(
    coverUrl: String?,
    title: String,
    modifier: Modifier = Modifier
) {
    val highResUrl = coverUrl.upscaleCover()
    if (!highResUrl.isNullOrBlank()) {
        Log.d("TrackCover", "Loading: $highResUrl")
        AsyncImage(
            model = highResUrl,
            contentDescription = title,
            modifier = modifier.clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop,
            onError = { Log.e("TrackCover", "Failed to load: $highResUrl") },
            onSuccess = { Log.d("TrackCover", "Loaded: $highResUrl") }
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}