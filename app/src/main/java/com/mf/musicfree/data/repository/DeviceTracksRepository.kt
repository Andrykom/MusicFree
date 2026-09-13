package com.mf.musicfree.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.mf.musicfree.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceTracksRepository(private val context: Context) {

    suspend fun getDeviceTracks(): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATE_ADDED
        )

        // Фильтруем слишком короткие звуки (звуки уведомлений, рингтоны < 10 сек)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )

            cursor?.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val albumIdCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dateAddedCol = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)

                while (it.moveToNext()) {
                    val id = it.getLong(idCol)
                    val rawTitle = it.getString(titleCol)
                    val rawArtist = it.getString(artistCol)
                    val album = it.getString(albumCol)
                    val duration = it.getLong(durCol)
                    val filePath = it.getString(dataCol)
                    val albumId = it.getLong(albumIdCol)
                    val dateAdded = it.getLong(dateAddedCol)

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()

                    val albumArtUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    ).toString()

                    val title = if (!rawTitle.isNullOrBlank()) {
                        rawTitle
                    } else {
                        filePath?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Без названия"
                    }

                    val artist = if (!rawArtist.isNullOrBlank() && rawArtist != "<unknown>") {
                        rawArtist
                    } else {
                        "Неизвестный исполнитель"
                    }

                    tracks.add(
                        Track(
                            id = "device_$id",
                            title = title,
                            artist = artist,
                            album = album,
                            duration = duration,
                            filePath = contentUri,
                            coverUrl = albumArtUri,
                            sourceUrl = contentUri,
                            isDownloaded = true,
                            addedAt = dateAdded * 1000L
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        tracks
    }

    suspend fun getDeviceTracksCount(): Int = withContext(Dispatchers.IO) {
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
        try {
            val cursor = context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                null
            )
            cursor?.use { it.count } ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
