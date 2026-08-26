package com.mf.musicfree.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.LruCache
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.mf.musicfree.MainActivity
import com.mf.musicfree.R
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.data.repository.TrackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

@UnstableApi
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val trackRepository: TrackRepository by inject()
    private val bitmapCache = LruCache<String, Bitmap>(5)
    private var currentCoverJob: Job? = null

    companion object {
        const val CHANNEL_ID = "mf_playback_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_ADD_TO_LIBRARY = "com.mf.musicfree.ADD_TO_LIBRARY"
        const val ACTION_PREV = "com.mf.musicfree.PREV"
        const val ACTION_PLAY_PAUSE = "com.mf.musicfree.PLAY_PAUSE"
        const val ACTION_NEXT = "com.mf.musicfree.NEXT"
    }

    override fun onCreate() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildEmptyNotification())

        super.onCreate()

        val playerController: PlayerController by inject()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        playerController.init(player)

        mediaSession = MediaSession.Builder(this, player).build()

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                updateNotification(player)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification(player)
            }
        })

        updateNotification(player)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = mediaSession?.player
        when (intent?.action) {
            ACTION_PREV -> player?.seekToPrevious()
            ACTION_PLAY_PAUSE -> player?.let { it.playWhenReady = !it.playWhenReady }
            ACTION_NEXT -> player?.seekToNext()
            ACTION_ADD_TO_LIBRARY -> serviceScope.launch { addCurrentTrackToLibrary() }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private suspend fun addCurrentTrackToLibrary() {
        val mediaItem = mediaSession?.player?.currentMediaItem ?: return
        val track = Track(
            id = mediaItem.mediaId,
            title = mediaItem.mediaMetadata.title?.toString() ?: "Unknown",
            artist = mediaItem.mediaMetadata.artist?.toString() ?: "Unknown",
            duration = mediaSession?.player?.duration?.coerceAtLeast(0) ?: 0,
            coverUrl = mediaItem.mediaMetadata.artworkUri?.toString(),
            sourceUrl = mediaItem.localConfiguration?.uri?.toString(),
            isDownloaded = false
        )
        try {
            trackRepository.addTrack(track)
        } catch (_: Exception) {}
    }

    private fun updateNotification(player: Player) {
        serviceScope.launch(Dispatchers.Main) {
            try {
                val notification = buildNotification(player)
                NotificationManagerCompat.from(this@PlaybackService).notify(NOTIFICATION_ID, notification)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS permission not granted on Android 13+
            }
        }
    }

    private fun buildNotification(player: Player): Notification {
        val metadata = player.currentMediaItem?.mediaMetadata
        val title = metadata?.title?.toString() ?: "Music Free"
        val artist = metadata?.artist?.toString() ?: ""

        val style = mediaSession?.let { MediaStyleNotificationHelper.MediaStyle(it) }
            ?.setShowActionsInCompactView(0, 1, 2)

        val prev = PendingIntent.getService(this, 0,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_IMMUTABLE)
        val playPause = PendingIntent.getService(this, 1,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_IMMUTABLE)
        val next = PendingIntent.getService(this, 2,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_IMMUTABLE)
        val add = PendingIntent.getService(this, 3,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_ADD_TO_LIBRARY },
            PendingIntent.FLAG_IMMUTABLE)

        val isPlaying = player.isPlaying
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        val artworkUri = metadata?.artworkUri?.toString()
        var largeIcon: Bitmap? = null
        if (artworkUri != null) {
            largeIcon = bitmapCache.get(artworkUri)
            if (largeIcon == null) {
                currentCoverJob?.cancel()
                currentCoverJob = serviceScope.launch(Dispatchers.IO) {
                    try {
                        val loader = ImageLoader(this@PlaybackService)
                        val request = ImageRequest.Builder(this@PlaybackService)
                            .data(artworkUri)
                            .allowHardware(false)
                            .size(Size(512, 512))
                            .build()
                        val result = loader.execute(request)
                        if (result is SuccessResult) {
                            val bmp = (result.drawable as? BitmapDrawable)?.bitmap
                            if (bmp != null) {
                                bitmapCache.put(artworkUri, bmp)
                                launch(Dispatchers.Main) { updateNotification(player) }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setStyle(style)
            .setSmallIcon(R.drawable.mf_icon_white)
            .setLargeIcon(largeIcon)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                }, PendingIntent.FLAG_IMMUTABLE))
            .addAction(android.R.drawable.ic_media_previous, "Назад", prev)
            .addAction(playPauseIcon, if (isPlaying) "Пауза" else "Играть", playPause)
            .addAction(android.R.drawable.ic_media_next, "Вперёд", next)
            .addAction(android.R.drawable.ic_input_add, "В библиотеку", add)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .build()
    }

    private fun buildEmptyNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Music Free")
            .setContentText("Готов к воспроизведению")
            .setSmallIcon(R.drawable.mf_icon_white)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Управление воспроизведением"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        currentCoverJob?.cancel()
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}