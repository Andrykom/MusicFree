package com.mf.musicfree.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

private val artistSplitRegex = Regex(
    """\s*(?:,|;|\bfeat\.?\b|\bft\.?\b|\band\b|&|\bх\b|\bx\b)\s*""",
    RegexOption.IGNORE_CASE
)

fun splitArtists(rawArtist: String): List<String> {
    val parts = rawArtist
        .split(artistSplitRegex)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
    return parts.ifEmpty { listOf(rawArtist.trim()).filter { it.isNotBlank() } }
}

/**
 * Отображает одного или нескольких артистов через запятую.
 * Нажатие на каждого артиста вызывает [onArtistClick] с его именем.
 */
@Suppress("DEPRECATION")
@Composable
fun ClickableArtistsText(
    rawArtist: String,
    onArtistClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.primary,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = 2
) {
    val artists = remember(rawArtist) { splitArtists(rawArtist) }
    if (artists.isEmpty()) return

    val annotatedText = remember(artists, color) {
        buildAnnotatedString {
            artists.forEachIndexed { index, name ->
                if (index > 0) {
                    withStyle(SpanStyle(color = color.copy(alpha = 0.7f))) {
                        append(", ")
                    }
                }
                pushStringAnnotation(tag = "ARTIST", annotation = name)
                withStyle(SpanStyle(color = color)) {
                    append(name)
                }
                pop()
            }
        }
    }

    ClickableText(
        text = annotatedText,
        style = style.copy(textAlign = textAlign),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onClick = { offset ->
            val clickedArtist = annotatedText
                .getStringAnnotations(tag = "ARTIST", start = offset, end = offset)
                .firstOrNull()
                ?.item
                ?: artists.firstOrNull()
            if (!clickedArtist.isNullOrBlank()) {
                onArtistClick(clickedArtist)
            }
        }
    )
}

/**
 * Полноэкранный просмотр фото артиста или обложки альбома с поддержкой зума (щипок и двойной тап).
 */
@Composable
fun FullScreenImageDialog(
    imageUrl: String?,
    title: String,
    onDismiss: () -> Unit
) {
    if (imageUrl.isNullOrBlank()) return

    val highResUrl = remember(imageUrl) {
        imageUrl
            .replace("100x100bb.jpg", "1000x1000bb.jpg")
            .replace("100x100bb", "1000x1000bb")
            .replace("100x100", "1000x1000")
            .replace("600x600bb", "1000x1000bb")
            .replace("600x600", "1000x1000")
            .replace("250x250", "1000x1000")
            .replace("500x500", "1000x1000")
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        BackHandler(onBack = onDismiss)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (scale <= 1.05f) onDismiss()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = highResUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            },
                            onTap = {
                                if (scale <= 1.05f) onDismiss()
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 4f)
                            scale = newScale
                            offset = if (newScale > 1f) {
                                offset + pan
                            } else {
                                Offset.Zero
                            }
                        }
                    }
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Закрыть",
                    tint = Color.White
                )
            }
        }
    }
}