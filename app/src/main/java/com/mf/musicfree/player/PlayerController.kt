package com.mf.musicfree.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mf.musicfree.data.model.Track

class PlayerController(private val context: Context) {
    private lateinit var exoPlayer: ExoPlayer
    private var onTrackChange: ((Int) -> Unit)? = null
    private var onIsPlayingChange: ((Boolean) -> Unit)? = null
    private var trackQueue: List<Track> = emptyList()

    fun init(player: ExoPlayer) {
        exoPlayer = player
        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                onTrackChange?.invoke(exoPlayer.currentMediaItemIndex)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onIsPlayingChange?.invoke(isPlaying)
            }
        })
        // Если ViewModel уже подписалась до создания плеера (пересоздание Activity),
        // сразу прокидываем текущее состояние
        if (onTrackChange != null && trackQueue.isNotEmpty()) {
            onTrackChange?.invoke(exoPlayer.currentMediaItemIndex)
        }
        if (onIsPlayingChange != null) {
            onIsPlayingChange?.invoke(exoPlayer.isPlaying)
        }
    }

    fun isInitialized(): Boolean = ::exoPlayer.isInitialized

    fun getQueue(): List<Track> = trackQueue

    fun getCurrentIndex(): Int =
        if (::exoPlayer.isInitialized) exoPlayer.currentMediaItemIndex else -1

    private fun ensureInitialized() {
        if (!::exoPlayer.isInitialized) {
            exoPlayer = ExoPlayer.Builder(context)
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
            init(exoPlayer)
        }
    }

    private fun String.upscaleCover(): String {
        return this.replace("100x100bb.jpg", "600x600bb.jpg")
            .replace("100x100bb", "600x600bb")
            .replace("100x100", "600x600")
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        trackQueue = tracks
        ensureInitialized()
        val mediaItems = tracks.map { track ->
            val url = track.filePath ?: track.sourceUrl ?: ""
            val coverUrl = track.coverUrl?.upscaleCover()
            val artworkUri = coverUrl?.let { Uri.parse(it) }

            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setArtworkUri(artworkUri)
                        .build()
                )
                .build()
        }
        exoPlayer.setMediaItems(mediaItems, startIndex, 0)
        exoPlayer.prepare()
    }

    fun play() {
        ensureInitialized()
        exoPlayer.play()
    }
    fun pause() {
        if (::exoPlayer.isInitialized) exoPlayer.pause()
    }
    fun next() {
        if (::exoPlayer.isInitialized) exoPlayer.seekToNext()
    }
    fun previous() {
        if (::exoPlayer.isInitialized) exoPlayer.seekToPrevious()
    }
    fun seekTo(progress: Float) {
        if (!::exoPlayer.isInitialized) return
        val duration = exoPlayer.duration
        if (duration > 0) exoPlayer.seekTo((progress * duration).toLong())
    }
    fun getCurrentProgress(): Float {
        if (!::exoPlayer.isInitialized) return 0f
        val duration = exoPlayer.duration
        return if (duration > 0) exoPlayer.currentPosition.toFloat() / duration else 0f
    }
    fun getDuration(): Long = if (::exoPlayer.isInitialized) exoPlayer.duration.coerceAtLeast(0) else 0
    fun getCurrentPosition(): Long = if (::exoPlayer.isInitialized) exoPlayer.currentPosition.coerceAtLeast(0) else 0
    fun isPlaying(): Boolean = ::exoPlayer.isInitialized && exoPlayer.isPlaying
    fun setRepeatMode(repeatMode: Int) {
        if (::exoPlayer.isInitialized) exoPlayer.repeatMode = repeatMode
    }
    fun setShuffleEnabled(enabled: Boolean) {
        if (::exoPlayer.isInitialized) exoPlayer.shuffleModeEnabled = enabled
    }
    fun release() {
        if (::exoPlayer.isInitialized) exoPlayer.release()
    }
    fun setOnTrackChangeListener(listener: (Int) -> Unit) { onTrackChange = listener }
    fun setOnIsPlayingChangeListener(listener: (Boolean) -> Unit) { onIsPlayingChange = listener }

    fun insertAfterCurrent(tracks: List<Track>) {
        ensureInitialized()
        val currentIndex = exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
        // Синхронизируем внутренний список треков с ExoPlayer
        trackQueue = trackQueue.toMutableList().apply { addAll(currentIndex + 1, tracks) }
        val mediaItems = tracks.map { track ->
            val url = track.filePath ?: track.sourceUrl ?: ""
            val coverUrl = track.coverUrl?.upscaleCover()
            val artworkUri = coverUrl?.let { Uri.parse(it) }

            MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(url)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setArtworkUri(artworkUri)
                        .build()
                )
                .build()
        }
        exoPlayer.addMediaItems(currentIndex + 1, mediaItems)
    }

    fun removeAt(index: Int) {
        if (index !in trackQueue.indices) return
        trackQueue = trackQueue.toMutableList().apply { removeAt(index) }
        if (::exoPlayer.isInitialized && index < exoPlayer.mediaItemCount) {
            exoPlayer.removeMediaItem(index)
        }
    }
}