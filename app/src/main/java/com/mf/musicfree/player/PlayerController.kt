package com.mf.musicfree.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mf.musicfree.data.model.Track
import kotlinx.coroutines.*

class PlayerController(private val context: Context) {
    private var exoPlayer: ExoPlayer? = null
    private var onTrackChange: ((Int) -> Unit)? = null
    private var onIsPlayingChange: ((Boolean) -> Unit)? = null
    private var onPlayerError: ((PlaybackException) -> Unit)? = null
    private var onQueueChange: ((List<Track>) -> Unit)? = null
    private var trackQueue: List<Track> = emptyList()

    private var originalQueue: List<Track>? = null
    private var savedRepeatMode: RepeatMode = RepeatMode.NONE
    private var savedIsShuffled: Boolean = false
    private var savedIsRadioMode: Boolean = false

    private var isServiceRunning: Boolean = false
    private var lastPauseTimestampMs: Long = 0L
    private var pendingStartIndex: Int = 0
    private var pendingStartPositionMs: Long = 0L

    private var crossfadeSeconds: Int = 0
    private var fadeJob: Job? = null
    private val controllerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        // Если плеер стоял на паузе дольше 3 минут, при нажатии Play пересоздаём ExoPlayer,
        // чтобы избежать зависших декодеров/сокетов после Doze-режима Android
        private const val STALE_PLAYER_THRESHOLD_MS = 3 * 60_000L
    }

    fun setServiceRunning(running: Boolean) {
        isServiceRunning = running
        if (!running && exoPlayer != null) {
            // Записываем актуальную позицию перед тем, как сервис умрёт
            val p = exoPlayer!!
            if (trackQueue.isNotEmpty()) {
                pendingStartIndex = p.currentMediaItemIndex.coerceIn(0, trackQueue.lastIndex)
                pendingStartPositionMs = p.currentPosition.coerceAtLeast(0L)
            }
            lastPauseTimestampMs = System.currentTimeMillis()
        }
    }

    fun init(player: ExoPlayer) {
        if (exoPlayer != null && exoPlayer !== player) {
            try { exoPlayer?.release() } catch (_: Exception) {}
        }
        exoPlayer = player
        attachPlayerListeners(player)
        applySavedModesToPlayer(player)

        if (onTrackChange != null && trackQueue.isNotEmpty()) {
            onTrackChange?.invoke(player.currentMediaItemIndex)
        }
        if (onIsPlayingChange != null) {
            onIsPlayingChange?.invoke(player.isPlaying)
        }
        onQueueChange?.invoke(trackQueue)
    }

    private fun attachPlayerListeners(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!isCrossfading) {
                    player.volume = 1.0f
                }
                val idx = player.currentMediaItemIndex
                if (idx in trackQueue.indices) {
                    pendingStartIndex = idx
                    pendingStartPositionMs = 0L
                }
                onTrackChange?.invoke(idx)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    lastPauseTimestampMs = System.currentTimeMillis()
                    if (trackQueue.isNotEmpty()) {
                        pendingStartIndex = player.currentMediaItemIndex.coerceIn(0, trackQueue.lastIndex)
                        pendingStartPositionMs = player.currentPosition.coerceAtLeast(0L)
                    }
                } else {
                    lastPauseTimestampMs = 0L
                }
                onIsPlayingChange?.invoke(isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (trackQueue.isNotEmpty()) {
                    pendingStartIndex = player.currentMediaItemIndex.coerceIn(0, trackQueue.lastIndex)
                    pendingStartPositionMs = player.currentPosition.coerceAtLeast(0L)
                }
                onPlayerError?.invoke(error)
            }
        })
    }

    private fun applySavedModesToPlayer(player: ExoPlayer) {
        player.repeatMode = when (savedRepeatMode) {
            RepeatMode.NONE -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        player.shuffleModeEnabled = false
    }

    fun isInitialized(): Boolean = exoPlayer != null

    fun getExoPlayer(): ExoPlayer {
        ensureInitialized()
        return exoPlayer!!
    }

    fun getQueue(): List<Track> = trackQueue

    fun getCurrentIndex(): Int {
        val p = exoPlayer
        return if (p != null && p.mediaItemCount > 0) {
            p.currentMediaItemIndex
        } else if (trackQueue.isNotEmpty()) {
            pendingStartIndex.coerceIn(0, trackQueue.lastIndex)
        } else {
            -1
        }
    }

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

    private fun buildNewExoPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    private fun ensureInitialized() {
        if (exoPlayer == null) {
            val player = buildNewExoPlayer()
            exoPlayer = player
            attachPlayerListeners(player)
            applySavedModesToPlayer(player)
        }
    }

    private fun recreatePlayer() {
        fadeJob?.cancel()
        isCrossfading = false
        try { crossfadePlayer?.stop() } catch (_: Exception) {}
        try { exoPlayer?.release() } catch (_: Exception) {}
        exoPlayer = null
        ensureInitialized()
    }

    private fun String.upscaleCover(): String {
        return this.replace("100x100bb.jpg", "600x600bb.jpg")
            .replace("100x100bb", "600x600bb")
            .replace("100x100", "600x600")
    }

    /**
     * Восстанавливает очередь и позицию при холодном старте приложения,
     * не запуская foreground-сервис и не начиная воспроизведение до нажатия Play.
     */
    fun restorePausedState(tracks: List<Track>, startIndex: Int, positionMs: Long) {
        if (tracks.isEmpty()) return
        trackQueue = tracks
        pendingStartIndex = startIndex.coerceIn(0, tracks.lastIndex)
        pendingStartPositionMs = positionMs.coerceAtLeast(0L)
        lastPauseTimestampMs = System.currentTimeMillis()
        onQueueChange?.invoke(trackQueue)
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        trackQueue = tracks
        val safeIdx = if (tracks.isNotEmpty()) startIndex.coerceIn(0, tracks.lastIndex) else 0
        pendingStartIndex = safeIdx
        pendingStartPositionMs = startPositionMs.coerceAtLeast(0L)

        val wasIdleTooLong = lastPauseTimestampMs > 0L &&
                (System.currentTimeMillis() - lastPauseTimestampMs) > STALE_PLAYER_THRESHOLD_MS
        if (wasIdleTooLong || exoPlayer?.playerError != null) {
            recreatePlayer()
        } else {
            ensureInitialized()
        }

        val player = exoPlayer!!
        val mediaItems = tracks.map { it.toMediaItem() }
        player.setMediaItems(mediaItems, safeIdx, pendingStartPositionMs)
        player.prepare()
        startServiceIfNeeded()
        onQueueChange?.invoke(trackQueue)
    }

    fun play() {
        if (trackQueue.isEmpty()) return

        val p = exoPlayer
        val wasIdleTooLong = lastPauseTimestampMs > 0L &&
                (System.currentTimeMillis() - lastPauseTimestampMs) > STALE_PLAYER_THRESHOLD_MS

        val needsRebuild = p == null ||
                p.mediaItemCount == 0 ||
                p.playbackState == Player.STATE_IDLE ||
                p.playbackState == Player.STATE_ENDED ||
                p.playerError != null ||
                wasIdleTooLong ||
                !isServiceRunning

        if (needsRebuild) {
            val idx = if (p != null && p.mediaItemCount > 0) {
                p.currentMediaItemIndex.coerceIn(0, trackQueue.lastIndex)
            } else {
                pendingStartIndex.coerceIn(0, trackQueue.lastIndex)
            }
            val pos = if (p != null && p.mediaItemCount > 0 && p.playbackState != Player.STATE_ENDED) {
                p.currentPosition.coerceAtLeast(pendingStartPositionMs)
            } else if (p?.playbackState == Player.STATE_ENDED) {
                0L
            } else {
                pendingStartPositionMs
            }

            recreatePlayer()
            val freshPlayer = exoPlayer!!
            val mediaItems = trackQueue.map { it.toMediaItem() }
            freshPlayer.setMediaItems(mediaItems, idx, pos)
            freshPlayer.prepare()
            startServiceIfNeeded()
            freshPlayer.play()
        } else {
            startServiceIfNeeded()
            p.play()
        }
    }

    fun pause() {
        fadeJob?.cancel()
        if (isCrossfading) {
            isCrossfading = false
            crossfadePlayer?.stop()
            crossfadePlayer?.clearMediaItems()
            exoPlayer?.volume = 1.0f
        }
        exoPlayer?.pause()
    }

    /**
     * Заменяет трек в очереди (например, после обновления протухшей ссылки)
     * и при необходимости сразу запускает его с указанной позиции.
     */
    fun replaceTrackAt(index: Int, updatedTrack: Track, positionMs: Long, autoPlay: Boolean) {
        if (index !in trackQueue.indices) return
        trackQueue = trackQueue.toMutableList().apply { set(index, updatedTrack) }
        pendingStartIndex = index
        pendingStartPositionMs = positionMs.coerceAtLeast(0L)

        ensureInitialized()
        val player = exoPlayer!!
        if (index < player.mediaItemCount) {
            player.replaceMediaItem(index, updatedTrack.toMediaItem())
            player.seekTo(index, pendingStartPositionMs)
            player.prepare()
            if (autoPlay) {
                startServiceIfNeeded()
                player.play()
            }
        } else {
            setQueue(trackQueue, index, pendingStartPositionMs)
            if (autoPlay) play()
        }
        onQueueChange?.invoke(trackQueue)
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
        val player = exoPlayer ?: return
        if (!player.isPlaying || isCrossfading) return
        val currentMediaItem = player.currentMediaItem ?: return
        val curIndex = player.currentMediaItemIndex
        if (curIndex !in trackQueue.indices) return
        val nextIndex = curIndex + 1
        if (nextIndex >= trackQueue.size && player.repeatMode == Player.REPEAT_MODE_OFF) return

        val currentPos = player.currentPosition
        val xPlayer = ensureCrossfadePlayer()

        fadeJob?.cancel()
        isCrossfading = true

        xPlayer.setMediaItem(currentMediaItem, currentPos)
        xPlayer.volume = 1.0f
        xPlayer.prepare()
        xPlayer.play()

        player.volume = 0.0f
        if (nextIndex < trackQueue.size) {
            player.seekToDefaultPosition(nextIndex)
        } else {
            player.seekToDefaultPosition(0)
        }
        player.play()

        val fadeDuration = durationMs.coerceIn(500L, 8000L)
        fadeJob = controllerScope.launch {
            val steps = 20
            val stepTime = (fadeDuration / steps).coerceAtLeast(16L)
            for (i in 1..steps) {
                delay(stepTime)
                val progress = i.toFloat() / steps
                xPlayer.volume = (1.0f - progress).coerceIn(0f, 1f)
                exoPlayer?.volume = progress.coerceIn(0f, 1f)
            }
            xPlayer.stop()
            xPlayer.clearMediaItems()
            exoPlayer?.volume = 1.0f
            isCrossfading = false
        }
    }

    fun next() {
        val player = exoPlayer ?: return
        val curIndex = player.currentMediaItemIndex
        val hasNext = curIndex + 1 < trackQueue.size || player.repeatMode != Player.REPEAT_MODE_OFF
        if (crossfadeSeconds > 0 && player.isPlaying && hasNext) {
            performCrossfadeToNext(minOf(crossfadeSeconds * 1000L, 2500L))
        } else {
            fadeJob?.cancel()
            isCrossfading = false
            crossfadePlayer?.stop()
            player.volume = 1.0f
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.seekToNext()
        }
    }

    fun replaceQueueKeepingCurrent(newQueue: List<Track>, newCurrentIndex: Int) {
        trackQueue = newQueue
        pendingStartIndex = newCurrentIndex.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
        val player = exoPlayer
        if (player == null || player.mediaItemCount == 0) {
            setQueue(newQueue, newCurrentIndex)
            return
        }

        val currentExoIdx = player.currentMediaItemIndex
        if (currentExoIdx + 1 < player.mediaItemCount) {
            player.removeMediaItems(currentExoIdx + 1, player.mediaItemCount)
        }
        if (currentExoIdx > 0) {
            player.removeMediaItems(0, currentExoIdx)
        }
        if (newCurrentIndex > 0) {
            val beforeItems = newQueue.subList(0, newCurrentIndex).map { it.toMediaItem() }
            player.addMediaItems(0, beforeItems)
        }
        if (newCurrentIndex + 1 < newQueue.size) {
            val afterItems = newQueue.subList(newCurrentIndex + 1, newQueue.size).map { it.toMediaItem() }
            player.addMediaItems(newCurrentIndex + 1, afterItems)
        }

        onQueueChange?.invoke(trackQueue)
    }

    fun previous() {
        val player = exoPlayer ?: return
        fadeJob?.cancel()
        isCrossfading = false
        crossfadePlayer?.stop()
        player.volume = 1.0f
        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        player.seekToPrevious()
    }

    fun seekTo(progress: Float) {
        val player = exoPlayer ?: return
        val duration = player.duration
        if (duration > 0) {
            val pos = (progress * duration).toLong()
            pendingStartPositionMs = pos
            player.seekTo(pos)
        }
    }

    fun seekToPosition(positionMs: Long) {
        pendingStartPositionMs = positionMs.coerceAtLeast(0L)
        exoPlayer?.seekTo(pendingStartPositionMs)
    }

    fun getCurrentProgress(): Float {
        val player = exoPlayer
        if (player != null && player.duration > 0) {
            return player.currentPosition.toFloat() / player.duration
        }
        val trackDur = trackQueue.getOrNull(pendingStartIndex)?.duration ?: 0L
        return if (trackDur > 0 && pendingStartPositionMs > 0) {
            (pendingStartPositionMs.toFloat() / trackDur).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    fun getDuration(): Long {
        val player = exoPlayer
        if (player != null && player.duration > 0) {
            return player.duration
        }
        return trackQueue.getOrNull(pendingStartIndex)?.duration?.coerceAtLeast(0) ?: 0L
    }

    fun getCurrentPosition(): Long {
        val player = exoPlayer
        if (player != null && player.mediaItemCount > 0) {
            return player.currentPosition.coerceAtLeast(0)
        }
        return pendingStartPositionMs
    }

    fun isPlaying(): Boolean = exoPlayer?.isPlaying == true

    fun setRepeatMode(repeatMode: Int) {
        exoPlayer?.repeatMode = repeatMode
    }

    fun setShuffleEnabled(enabled: Boolean) {
        exoPlayer?.shuffleModeEnabled = enabled
    }

    fun release() {
        fadeJob?.cancel()
        controllerScope.cancel()
        crossfadePlayer?.release()
        crossfadePlayer = null
        exoPlayer?.release()
        exoPlayer = null
    }

    fun setOnTrackChangeListener(listener: (Int) -> Unit) { onTrackChange = listener }
    fun setOnIsPlayingChangeListener(listener: (Boolean) -> Unit) { onIsPlayingChange = listener }
    fun setOnPlayerErrorListener(listener: (PlaybackException) -> Unit) { onPlayerError = listener }
    fun setOnQueueChangeListener(listener: (List<Track>) -> Unit) { onQueueChange = listener }

    fun insertAfterCurrent(tracks: List<Track>) {
        ensureInitialized()
        val player = exoPlayer!!
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        trackQueue = trackQueue.toMutableList().apply { addAll(currentIndex + 1, tracks) }
        val mediaItems = tracks.map { it.toMediaItem() }
        player.addMediaItems(currentIndex + 1, mediaItems)
        onQueueChange?.invoke(trackQueue)
    }

    fun insertAt(position: Int, tracks: List<Track>) {
        ensureInitialized()
        val player = exoPlayer!!
        val pos = position.coerceIn(0, trackQueue.size)
        trackQueue = trackQueue.toMutableList().apply { addAll(pos, tracks) }
        val mediaItems = tracks.map { it.toMediaItem() }
        if (pos >= player.mediaItemCount) {
            player.addMediaItems(mediaItems)
        } else {
            player.addMediaItems(pos, mediaItems)
        }
        onQueueChange?.invoke(trackQueue)
    }

    fun addToEnd(tracks: List<Track>) {
        ensureInitialized()
        val player = exoPlayer!!
        trackQueue = trackQueue + tracks
        val mediaItems = tracks.map { it.toMediaItem() }
        player.addMediaItems(mediaItems)
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
        exoPlayer?.moveMediaItem(fromIndex, toIndex)
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
        val player = exoPlayer
        if (player != null && index < player.mediaItemCount) {
            player.removeMediaItem(index)
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