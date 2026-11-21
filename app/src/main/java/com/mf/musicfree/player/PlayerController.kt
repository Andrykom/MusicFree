package com.mf.musicfree.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.mf.musicfree.data.model.Track
import kotlinx.coroutines.*
import kotlin.math.cos
import kotlin.math.sin

class PlayerController(private val context: Context) {
    private var activePlayer: ExoPlayer? = null
    private var standbyPlayer: ExoPlayer? = null
    private var outgoingPlayer: ExoPlayer? = null
    private var activePlayerListener: Player.Listener? = null
    private var standbyPlayerListener: Player.Listener? = null

    private var onTrackChange: ((Int) -> Unit)? = null
    private var onIsPlayingChange: ((Boolean) -> Unit)? = null
    private var onPlayerError: ((PlaybackException) -> Unit)? = null
    private var onQueueChange: ((List<Track>) -> Unit)? = null
    private var onActivePlayerChanged: ((ExoPlayer) -> Unit)? = null

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
    private var isCrossfading: Boolean = false
    private var preloadedTrackId: String? = null
    private var fadeJob: Job? = null
    private val controllerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val musicAudioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    companion object {
        private const val STALE_PLAYER_THRESHOLD_MS = 3 * 60_000L
    }

    fun setServiceRunning(running: Boolean) {
        isServiceRunning = running
        if (!running && activePlayer != null) {
            val p = activePlayer!!
            if (trackQueue.isNotEmpty()) {
                pendingStartIndex = p.currentMediaItemIndex.coerceIn(0, trackQueue.lastIndex)
                pendingStartPositionMs = p.currentPosition.coerceAtLeast(0L)
            }
            lastPauseTimestampMs = System.currentTimeMillis()
        }
    }

    fun init(player: ExoPlayer) {
        if (activePlayer != null && activePlayer !== player) {
            detachActiveListener()
            try { activePlayer?.release() } catch (_: Exception) {}
        }
        activePlayer = player
        attachActiveListener(player)
        applySavedModesToPlayer(player)

        if (onTrackChange != null && trackQueue.isNotEmpty()) {
            onTrackChange?.invoke(player.currentMediaItemIndex)
        }
        if (onIsPlayingChange != null) {
            onIsPlayingChange?.invoke(player.isPlaying)
        }
        onQueueChange?.invoke(trackQueue)
    }

    private fun detachActiveListener() {
        val listener = activePlayerListener ?: return
        activePlayer?.removeListener(listener)
        activePlayerListener = null
    }

    private fun attachActiveListener(player: ExoPlayer) {
        detachActiveListener()
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (!isCrossfading) {
                    player.volume = 1.0f
                }
                val idx = player.currentMediaItemIndex
                if (idx in trackQueue.indices) {
                    pendingStartIndex = idx
                    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                        pendingStartPositionMs = 0L
                    }
                }
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    onTrackChange?.invoke(idx)
                }
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
        }
        activePlayerListener = listener
        player.addListener(listener)
    }

    private fun applySavedModesToPlayer(player: ExoPlayer) {
        player.repeatMode = when (savedRepeatMode) {
            RepeatMode.NONE -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
        player.shuffleModeEnabled = false
    }

    fun isInitialized(): Boolean = activePlayer != null

    fun getExoPlayer(): ExoPlayer {
        ensureInitialized()
        return activePlayer!!
    }

    fun getQueue(): List<Track> = trackQueue

    fun getCurrentIndex(): Int {
        val p = activePlayer
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

    private fun buildNewExoPlayer(handleAudioFocus: Boolean): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setAudioAttributes(musicAudioAttributes, handleAudioFocus)
            .setHandleAudioBecomingNoisy(handleAudioFocus)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    private fun ensureInitialized() {
        if (activePlayer == null) {
            val player = buildNewExoPlayer(handleAudioFocus = true)
            activePlayer = player
            attachActiveListener(player)
            applySavedModesToPlayer(player)
        }
    }

    private fun ensureStandbyPlayer(): ExoPlayer {
        val existing = standbyPlayer
        if (existing != null) return existing
        val created = buildNewExoPlayer(handleAudioFocus = false)
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Если предзагружаемый трек упал, сбрасываем его — основной трек продолжит играть без сбоев
                preloadedTrackId = null
                try {
                    created.stop()
                    created.clearMediaItems()
                } catch (_: Exception) {}
            }
        }
        standbyPlayerListener = listener
        created.addListener(listener)
        standbyPlayer = created
        return created
    }

    private fun clearStandbyPreload() {
        preloadedTrackId = null
        try {
            standbyPlayer?.pause()
            standbyPlayer?.stop()
            standbyPlayer?.clearMediaItems()
            standbyPlayer?.volume = 0f
        } catch (_: Exception) {}
    }

    private fun cancelCrossfade() {
        fadeJob?.cancel()
        fadeJob = null
        outgoingPlayer?.let { out ->
            try {
                out.pause()
                out.stop()
                out.clearMediaItems()
                out.volume = 1.0f
                out.setAudioAttributes(musicAudioAttributes, false)
            } catch (_: Exception) {}
        }
        outgoingPlayer = null
        activePlayer?.let { act ->
            act.volume = 1.0f
            act.setAudioAttributes(musicAudioAttributes, true)
        }
        isCrossfading = false
    }

    private fun recreatePlayer() {
        cancelCrossfade()
        clearStandbyPreload()
        detachActiveListener()
        try { activePlayer?.release() } catch (_: Exception) {}
        activePlayer = null
        ensureInitialized()
        activePlayer?.let { onActivePlayerChanged?.invoke(it) }
    }

    private fun String.upscaleCover(): String {
        return this.replace("100x100bb.jpg", "600x600bb.jpg")
            .replace("100x100bb", "600x600bb")
            .replace("100x100", "600x600")
    }

    fun restorePausedState(tracks: List<Track>, startIndex: Int, positionMs: Long) {
        if (tracks.isEmpty()) return
        trackQueue = tracks
        pendingStartIndex = startIndex.coerceIn(0, tracks.lastIndex)
        pendingStartPositionMs = positionMs.coerceAtLeast(0L)
        lastPauseTimestampMs = System.currentTimeMillis()
        onQueueChange?.invoke(trackQueue)
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        cancelCrossfade()
        clearStandbyPreload()
        trackQueue = tracks
        val safeIdx = if (tracks.isNotEmpty()) startIndex.coerceIn(0, tracks.lastIndex) else 0
        pendingStartIndex = safeIdx
        pendingStartPositionMs = startPositionMs.coerceAtLeast(0L)

        val wasIdleTooLong = lastPauseTimestampMs > 0L &&
                (System.currentTimeMillis() - lastPauseTimestampMs) > STALE_PLAYER_THRESHOLD_MS
        if (wasIdleTooLong || activePlayer?.playerError != null) {
            recreatePlayer()
        } else {
            ensureInitialized()
        }

        val player = activePlayer!!
        val mediaItems = tracks.map { it.toMediaItem() }
        player.setMediaItems(mediaItems, safeIdx, pendingStartPositionMs)
        player.prepare()
        startServiceIfNeeded()
        onQueueChange?.invoke(trackQueue)
    }

    fun play() {
        if (trackQueue.isEmpty()) return

        val p = activePlayer
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
            val freshPlayer = activePlayer!!
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
        cancelCrossfade()
        activePlayer?.pause()
    }

    fun replaceTrackAt(index: Int, updatedTrack: Track, positionMs: Long, autoPlay: Boolean) {
        if (index !in trackQueue.indices) return
        cancelCrossfade()
        if (preloadedTrackId == updatedTrack.id) {
            clearStandbyPreload()
        }
        trackQueue = trackQueue.toMutableList().apply { set(index, updatedTrack) }
        pendingStartIndex = index
        pendingStartPositionMs = positionMs.coerceAtLeast(0L)

        ensureInitialized()
        val player = activePlayer!!
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

    fun getCrossfadeSeconds(): Int = crossfadeSeconds
    fun isCrossfading(): Boolean = isCrossfading

    fun setCrossfadeSeconds(seconds: Int) {
        crossfadeSeconds = seconds
        if (seconds <= 0) {
            clearStandbyPreload()
        }
    }

    /**
     * Заранее открывает и буферизует следующий трек на свободной деке (с громкостью 0),
     * чтобы в момент кроссфейда он стартовал мгновенно без единой миллисекунды тишины.
     */
    fun preloadNextTrack(nextTrack: Track) {
        if (crossfadeSeconds <= 0 || isCrossfading) return
        if (preloadedTrackId == nextTrack.id && standbyPlayer?.playbackState != Player.STATE_IDLE) return

        val deck = ensureStandbyPlayer()
        preloadedTrackId = nextTrack.id
        deck.playWhenReady = false
        deck.volume = 0f
        deck.setMediaItem(nextTrack.toMediaItem(), 0L)
        deck.prepare()
    }

    private fun resolveNextIndex(curIndex: Int): Int {
        if (savedRepeatMode == RepeatMode.ONE) return -1
        return when {
            curIndex + 1 < trackQueue.size -> curIndex + 1
            savedRepeatMode == RepeatMode.ALL && trackQueue.isNotEmpty() -> 0
            else -> -1
        }
    }

    fun performCrossfadeToNext(durationMs: Long) {
        val currentDeck = activePlayer ?: return
        if (!currentDeck.isPlaying || isCrossfading) return

        val curIndex = currentDeck.currentMediaItemIndex
        if (curIndex !in trackQueue.indices) return
        val nextIndex = resolveNextIndex(curIndex)
        if (nextIndex !in trackQueue.indices) return

        val nextTrack = trackQueue[nextIndex]
        fadeJob?.cancel()
        isCrossfading = true

        fadeJob = controllerScope.launch {
            val nextDeck = ensureStandbyPlayer()
            if (preloadedTrackId != nextTrack.id || nextDeck.playbackState == Player.STATE_IDLE) {
                preloadedTrackId = nextTrack.id
                nextDeck.playWhenReady = false
                nextDeck.volume = 0f
                nextDeck.setMediaItem(nextTrack.toMediaItem(), 0L)
                nextDeck.prepare()
            }

            // Ждём готовности декодера второй деки (до 2.5 сек), пока текущий трек играет без перерыва
            var waitedMs = 0L
            while (nextDeck.playbackState == Player.STATE_BUFFERING && waitedMs < 2500L) {
                delay(25L)
                waitedMs += 25L
            }

            if (nextDeck.playbackState != Player.STATE_READY) {
                isCrossfading = false
                clearStandbyPreload()
                return@launch
            }

            // 1. Запускаем новую деку с громкостью 0, не прерывая старую
            outgoingPlayer = currentDeck
            detachActiveListener()

            nextDeck.volume = 0f
            nextDeck.play()

            // 2. Достраиваем полную очередь вокруг nextIndex на новой деке без остановки её воспроизведения
            if (nextIndex > 0) {
                val beforeItems = trackQueue.subList(0, nextIndex).map { it.toMediaItem() }
                nextDeck.addMediaItems(0, beforeItems)
            }
            if (nextIndex + 1 < trackQueue.size) {
                val afterItems = trackQueue.subList(nextIndex + 1, trackQueue.size).map { it.toMediaItem() }
                nextDeck.addMediaItems(nextIndex + 1, afterItems)
            }

            // 3. Делаем новую деку активной, а старую отправляем в standby
            activePlayer = nextDeck
            standbyPlayer = currentDeck
            preloadedTrackId = null

            attachActiveListener(nextDeck)
            applySavedModesToPlayer(nextDeck)
            pendingStartIndex = nextIndex
            pendingStartPositionMs = 0L

            onActivePlayerChanged?.invoke(nextDeck)
            onTrackChange?.invoke(nextIndex)

            // 4. Плавное equal-power наложение (cos / sin) с частотой 50 Гц
            val fadeDuration = durationMs.coerceIn(400L, 12000L)
            val stepTime = 20L
            val steps = (fadeDuration / stepTime).toInt().coerceAtLeast(10)
            for (i in 1..steps) {
                delay(stepTime)
                val progress = (i.toFloat() / steps).coerceIn(0f, 1f)
                val angle = progress * (Math.PI / 2.0)
                outgoingPlayer?.volume = cos(angle).toFloat().coerceIn(0f, 1f)
                nextDeck.volume = sin(angle).toFloat().coerceIn(0f, 1f)
            }

            // 5. Завершаем переход: гасим старую деку и передаём AudioFocus новой
            outgoingPlayer?.let { out ->
                try {
                    out.pause()
                    out.stop()
                    out.clearMediaItems()
                    out.volume = 1.0f
                    out.setAudioAttributes(musicAudioAttributes, false)
                } catch (_: Exception) {}
            }
            outgoingPlayer = null
            nextDeck.volume = 1.0f
            nextDeck.setAudioAttributes(musicAudioAttributes, true)
            isCrossfading = false
        }
    }

    fun next() {
        val player = activePlayer ?: return
        val curIndex = player.currentMediaItemIndex
        val nextIndex = resolveNextIndex(curIndex)
        if (crossfadeSeconds > 0 && player.isPlaying && nextIndex in trackQueue.indices && !isCrossfading) {
            performCrossfadeToNext(minOf(crossfadeSeconds * 1000L, 2500L))
        } else {
            cancelCrossfade()
            player.volume = 1.0f
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.seekToNext()
        }
    }

    fun replaceQueueKeepingCurrent(newQueue: List<Track>, newCurrentIndex: Int) {
        clearStandbyPreload()
        trackQueue = newQueue
        pendingStartIndex = newCurrentIndex.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
        val player = activePlayer
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
        val player = activePlayer ?: return
        cancelCrossfade()
        player.volume = 1.0f
        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
        player.seekToPrevious()
    }

    fun seekTo(progress: Float) {
        val player = activePlayer ?: return
        val duration = player.duration
        if (duration > 0) {
            val pos = (progress * duration).toLong()
            pendingStartPositionMs = pos
            player.seekTo(pos)
        }
    }

    fun seekToPosition(positionMs: Long) {
        pendingStartPositionMs = positionMs.coerceAtLeast(0L)
        activePlayer?.seekTo(pendingStartPositionMs)
    }

    fun getCurrentProgress(): Float {
        val player = activePlayer
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
        val player = activePlayer
        if (player != null && player.duration > 0) {
            return player.duration
        }
        return trackQueue.getOrNull(pendingStartIndex)?.duration?.coerceAtLeast(0) ?: 0L
    }

    fun getCurrentPosition(): Long {
        val player = activePlayer
        if (player != null && player.mediaItemCount > 0) {
            return player.currentPosition.coerceAtLeast(0)
        }
        return pendingStartPositionMs
    }

    fun isPlaying(): Boolean = activePlayer?.isPlaying == true

    fun setRepeatMode(repeatMode: Int) {
        activePlayer?.repeatMode = repeatMode
        if (repeatMode == Player.REPEAT_MODE_ONE) {
            clearStandbyPreload()
        }
    }

    fun setShuffleEnabled(enabled: Boolean) {
        activePlayer?.shuffleModeEnabled = enabled
    }

    fun release() {
        cancelCrossfade()
        controllerScope.cancel()
        standbyPlayerListener?.let { standbyPlayer?.removeListener(it) }
        standbyPlayer?.release()
        standbyPlayer = null
        detachActiveListener()
        activePlayer?.release()
        activePlayer = null
    }

    fun setOnTrackChangeListener(listener: (Int) -> Unit) { onTrackChange = listener }
    fun setOnIsPlayingChangeListener(listener: (Boolean) -> Unit) { onIsPlayingChange = listener }
    fun setOnPlayerErrorListener(listener: (PlaybackException) -> Unit) { onPlayerError = listener }
    fun setOnQueueChangeListener(listener: (List<Track>) -> Unit) { onQueueChange = listener }
    fun setOnActivePlayerChangedListener(listener: (ExoPlayer) -> Unit) { onActivePlayerChanged = listener }

    fun insertAfterCurrent(tracks: List<Track>) {
        ensureInitialized()
        clearStandbyPreload()
        val player = activePlayer!!
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        trackQueue = trackQueue.toMutableList().apply { addAll(currentIndex + 1, tracks) }
        val mediaItems = tracks.map { it.toMediaItem() }
        player.addMediaItems(currentIndex + 1, mediaItems)
        onQueueChange?.invoke(trackQueue)
    }

    fun insertAt(position: Int, tracks: List<Track>) {
        ensureInitialized()
        clearStandbyPreload()
        val player = activePlayer!!
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
        val player = activePlayer!!
        trackQueue = trackQueue + tracks
        val mediaItems = tracks.map { it.toMediaItem() }
        player.addMediaItems(mediaItems)
        onQueueChange?.invoke(trackQueue)
    }

    fun addToEnd(track: Track) = addToEnd(listOf(track))

    fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in trackQueue.indices || toIndex !in trackQueue.indices) return
        if (fromIndex == toIndex) return
        clearStandbyPreload()
        val mutable = trackQueue.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        trackQueue = mutable
        activePlayer?.moveMediaItem(fromIndex, toIndex)
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
        clearStandbyPreload()
        trackQueue = trackQueue.toMutableList().apply { removeAt(index) }
        val player = activePlayer
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