package com.mf.musicfree.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mf.musicfree.data.model.Track
import kotlinx.coroutines.*
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

class PlayerController(private val context: Context) {
    private lateinit var exoPlayer: ExoPlayer
    private var onTrackChange: ((Int) -> Unit)? = null
    private var onIsPlayingChange: ((Boolean) -> Unit)? = null
    private var onPlayerError: ((PlaybackException) -> Unit)? = null
    private var onQueueChange: ((List<Track>) -> Unit)? = null
    private var trackQueue: List<Track> = emptyList()

    private var originalQueue: List<Track>? = null
    private var savedRepeatMode: RepeatMode = RepeatMode.NONE
    private var savedIsShuffled: Boolean = false
    private var savedIsRadioMode: Boolean = false

    private var crossfadeSeconds: Int = 0
    private var fadeJob: Job? = null
    private val controllerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun init(player: ExoPlayer) {
        if (::exoPlayer.isInitialized && exoPlayer != player) {
            exoPlayer.release()
        }
        exoPlayer = player
        exoPlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!isCrossfading && ::exoPlayer.isInitialized) {
                    exoPlayer.volume = 1.0f
                }
                onTrackChange?.invoke(exoPlayer.currentMediaItemIndex)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onIsPlayingChange?.invoke(isPlaying)
            }
            override fun onPlayerError(error: PlaybackException) {
                onPlayerError?.invoke(error)
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
        onQueueChange?.invoke(trackQueue)
    }

    fun isInitialized(): Boolean = ::exoPlayer.isInitialized

    fun getExoPlayer(): ExoPlayer {
        ensureInitialized()
        return exoPlayer
    }

    fun getQueue(): List<Track> = trackQueue

    fun getCurrentIndex(): Int =
        if (::exoPlayer.isInitialized) exoPlayer.currentMediaItemIndex else -1

    @OptIn(UnstableApi::class)
    fun startServiceIfNeeded() {
        val serviceIntent = Intent(context, PlaybackService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }

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
        startServiceIfNeeded()
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
        onQueueChange?.invoke(trackQueue)
    }

    fun play() {
        ensureInitialized()
        startServiceIfNeeded()
        exoPlayer.play()
    }
    fun pause() {
        fadeJob?.cancel()
        if (isCrossfading) {
            isCrossfading = false
            crossfadePlayer?.stop()
            crossfadePlayer?.clearMediaItems()
            if (::exoPlayer.isInitialized) {
                exoPlayer.volume = 1.0f
            }
        }
        if (::exoPlayer.isInitialized) exoPlayer.pause()
    }
    private var crossfadePlayer: ExoPlayer? = null
    private var isCrossfading = false

    fun getCrossfadeSeconds(): Int = crossfadeSeconds
    fun isCrossfading(): Boolean = isCrossfading

    private fun ensureCrossfadePlayer(): ExoPlayer {
        if (crossfadePlayer == null) {
            crossfadePlayer = ExoPlayer.Builder(context)
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false
                )
                .build()
        }
        return crossfadePlayer!!
    }

    fun setCrossfadeSeconds(seconds: Int) {
        crossfadeSeconds = seconds
    }

    fun performCrossfadeToNext(durationMs: Long) {
        if (!::exoPlayer.isInitialized || !exoPlayer.isPlaying || isCrossfading) return
        val currentMediaItem = exoPlayer.currentMediaItem ?: return
        val curIndex = exoPlayer.currentMediaItemIndex
        if (curIndex !in trackQueue.indices) return
        val nextIndex = curIndex + 1
        if (nextIndex >= trackQueue.size && exoPlayer.repeatMode == Player.REPEAT_MODE_OFF) return

        val currentPos = exoPlayer.currentPosition
        val xPlayer = ensureCrossfadePlayer()

        fadeJob?.cancel()
        isCrossfading = true

        // 1. Вспомогательный плеер продолжает доигрывать текущий трек с текущей секунды
        xPlayer.setMediaItem(currentMediaItem, currentPos)
        xPlayer.volume = 1.0f
        xPlayer.prepare()
        xPlayer.play()

        // 2. Основной exoPlayer переходит на следующий трек и стартует с громкостью 0
        exoPlayer.volume = 0.0f
        if (nextIndex < trackQueue.size) {
            exoPlayer.seekToDefaultPosition(nextIndex)
        } else {
            exoPlayer.seekToDefaultPosition(0)
        }
        exoPlayer.play()

        // 3. Плавное одновременное наложение двух треков
        val fadeDuration = durationMs.coerceIn(500L, 8000L)
        fadeJob = controllerScope.launch {
            val steps = 20
            val stepTime = (fadeDuration / steps).coerceAtLeast(16L)
            for (i in 1..steps) {
                delay(stepTime)
                val progress = i.toFloat() / steps
                xPlayer.volume = (1.0f - progress).coerceIn(0f, 1f)
                if (::exoPlayer.isInitialized) {
                    exoPlayer.volume = progress.coerceIn(0f, 1f)
                }
            }
            xPlayer.stop()
            xPlayer.clearMediaItems()
            if (::exoPlayer.isInitialized) {
                exoPlayer.volume = 1.0f
            }
            isCrossfading = false
        }
    }

    fun next() {
        if (!::exoPlayer.isInitialized) return
        val curIndex = exoPlayer.currentMediaItemIndex
        val hasNext = curIndex + 1 < trackQueue.size || exoPlayer.repeatMode != Player.REPEAT_MODE_OFF
        if (crossfadeSeconds > 0 && exoPlayer.isPlaying && hasNext) {
            performCrossfadeToNext(minOf(crossfadeSeconds * 1000L, 2500L))
        } else {
            fadeJob?.cancel()
            isCrossfading = false
            crossfadePlayer?.stop()
            exoPlayer.volume = 1.0f
            exoPlayer.seekToNext()
        }
    }

    fun replaceQueueKeepingCurrent(newQueue: List<Track>, newCurrentIndex: Int) {
        trackQueue = newQueue
        if (!::exoPlayer.isInitialized || exoPlayer.mediaItemCount == 0) {
            setQueue(newQueue, newCurrentIndex)
            return
        }

        val currentExoIdx = exoPlayer.currentMediaItemIndex
        // 1. Удаляем все элементы ПОСЛЕ текущего
        if (currentExoIdx + 1 < exoPlayer.mediaItemCount) {
            exoPlayer.removeMediaItems(currentExoIdx + 1, exoPlayer.mediaItemCount)
        }
        // 2. Удаляем все элементы ДО текущего (после этого текущий трек окажется на индексе 0)
        if (currentExoIdx > 0) {
            exoPlayer.removeMediaItems(0, currentExoIdx)
        }

        // 3. Вставляем треки, которые должны стоять ДО текущего (если выключаем shuffle)
        if (newCurrentIndex > 0) {
            val beforeItems = newQueue.subList(0, newCurrentIndex).map { it.toMediaItem() }
            exoPlayer.addMediaItems(0, beforeItems)
        }
        // 4. Вставляем треки, которые должны стоять ПОСЛЕ текущего
        if (newCurrentIndex + 1 < newQueue.size) {
            val afterItems = newQueue.subList(newCurrentIndex + 1, newQueue.size).map { it.toMediaItem() }
            exoPlayer.addMediaItems(newCurrentIndex + 1, afterItems)
        }

        onQueueChange?.invoke(trackQueue)
    }

    fun previous() {
        if (!::exoPlayer.isInitialized) return
        fadeJob?.cancel()
        isCrossfading = false
        crossfadePlayer?.stop()
        exoPlayer.volume = 1.0f
        exoPlayer.seekToPrevious()
    }
    fun seekTo(progress: Float) {
        if (!::exoPlayer.isInitialized) return
        val duration = exoPlayer.duration
        if (duration > 0) exoPlayer.seekTo((progress * duration).toLong())
    }
    fun seekToPosition(positionMs: Long) {
        if (!::exoPlayer.isInitialized) return
        exoPlayer.seekTo(positionMs)
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
        fadeJob?.cancel()
        controllerScope.cancel()
        crossfadePlayer?.release()
        crossfadePlayer = null
        if (::exoPlayer.isInitialized) exoPlayer.release()
    }
    fun setOnTrackChangeListener(listener: (Int) -> Unit) { onTrackChange = listener }
    fun setOnIsPlayingChangeListener(listener: (Boolean) -> Unit) { onIsPlayingChange = listener }
    fun setOnPlayerErrorListener(listener: (PlaybackException) -> Unit) { onPlayerError = listener }
    fun setOnQueueChangeListener(listener: (List<Track>) -> Unit) { onQueueChange = listener }

    fun insertAfterCurrent(tracks: List<Track>) {
        ensureInitialized()
        val currentIndex = exoPlayer.currentMediaItemIndex.coerceAtLeast(0)
        // Синхронизируем внутренний список треков с ExoPlayer
        trackQueue = trackQueue.toMutableList().apply { addAll(currentIndex + 1, tracks) }
        val mediaItems = tracks.map { it.toMediaItem() }
        exoPlayer.addMediaItems(currentIndex + 1, mediaItems)
        onQueueChange?.invoke(trackQueue)
    }

    fun insertAt(position: Int, tracks: List<Track>) {
        ensureInitialized()
        val pos = position.coerceIn(0, trackQueue.size)
        trackQueue = trackQueue.toMutableList().apply { addAll(pos, tracks) }
        val mediaItems = tracks.map { it.toMediaItem() }
        if (pos >= exoPlayer.mediaItemCount) {
            exoPlayer.addMediaItems(mediaItems)
        } else {
            exoPlayer.addMediaItems(pos, mediaItems)
        }
        onQueueChange?.invoke(trackQueue)
    }

    fun addToEnd(tracks: List<Track>) {
        ensureInitialized()
        trackQueue = trackQueue + tracks
        val mediaItems = tracks.map { it.toMediaItem() }
        exoPlayer.addMediaItems(mediaItems)
        onQueueChange?.invoke(trackQueue)
    }

    fun addToEnd(track: Track) = addToEnd(listOf(track))

    fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in trackQueue.indices || toIndex !in trackQueue.indices) return
        if (fromIndex == toIndex) return
        val mutable = trackQueue.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        trackQueue = mutable
        if (::exoPlayer.isInitialized) {
            exoPlayer.moveMediaItem(fromIndex, toIndex)
        }
        onQueueChange?.invoke(trackQueue)
    }

    private fun Track.toMediaItem(): MediaItem {
        val url = filePath ?: sourceUrl ?: ""
        val coverUrl = coverUrl?.upscaleCover()
        val artworkUri = coverUrl?.let { Uri.parse(it) }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setArtworkUri(artworkUri)
                    .build()
            )
            .build()
    }

    fun removeAt(index: Int) {
        if (index !in trackQueue.indices) return
        trackQueue = trackQueue.toMutableList().apply { removeAt(index) }
        if (::exoPlayer.isInitialized && index < exoPlayer.mediaItemCount) {
            exoPlayer.removeMediaItem(index)
        }
        onQueueChange?.invoke(trackQueue)
    }

    fun removeById(trackId: String) {
        val idx = trackQueue.indexOfFirst { it.id == trackId }
        if (idx != -1) removeAt(idx)
    }

    fun getSavedRepeatMode(): RepeatMode = savedRepeatMode
    fun setSavedRepeatMode(mode: RepeatMode) {
        savedRepeatMode = mode
    }

    fun getSavedIsShuffled(): Boolean = savedIsShuffled
    fun setSavedIsShuffled(shuffled: Boolean) {
        savedIsShuffled = shuffled
    }

    fun getOriginalQueue(): List<Track>? = originalQueue
    fun setOriginalQueue(queue: List<Track>?) {
        originalQueue = queue
    }

    fun getSavedIsRadioMode(): Boolean = savedIsRadioMode
    fun setSavedIsRadioMode(enabled: Boolean) {
        savedIsRadioMode = enabled
    }
}