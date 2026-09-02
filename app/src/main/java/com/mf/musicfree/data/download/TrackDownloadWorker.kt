package com.mf.musicfree.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mf.musicfree.data.repository.TrackRepository
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

class TrackDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params), KoinComponent {

    private val trackRepository: TrackRepository by inject()

    companion object {
        const val KEY_TRACK_ID = "track_id"
        const val KEY_AUDIO_URL = "audio_url"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_PROGRESS = "progress"
    }

    override suspend fun doWork(): Result {
        val trackId = inputData.getString(KEY_TRACK_ID) ?: return Result.failure()
        val audioUrl = inputData.getString(KEY_AUDIO_URL) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: "$trackId.mp3"

        return try {
            val musicDir = File(applicationContext.filesDir, "music").apply { mkdirs() }
            val outputFile = File(musicDir, fileName)

            val client = HttpClient(Android)
            val response: HttpResponse = client.get(audioUrl)

            if (!response.status.isSuccess()) {
                return Result.failure(workDataOf("error" to "HTTP ${response.status}"))
            }

            val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
            var downloaded = 0L
            var lastProgress = -1

            val channel: ByteReadChannel = response.bodyAsChannel()
            outputFile.outputStream().use { fileStream ->
                while (!channel.isClosedForRead) {
                    val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
                    while (!packet.isEmpty) {
                        val bytes = packet.readBytes()
                        fileStream.write(bytes)
                        downloaded += bytes.size
                        if (total > 0) {
                            val progress = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (progress != lastProgress && progress % 2 == 0) {
                                lastProgress = progress
                                setProgress(workDataOf(KEY_PROGRESS to progress))
                            }
                        } else {
                            // Неизвестный размер — шлём -1 как indeterminate
                            if (downloaded % (64 * 1024) == 0L) {
                                setProgress(workDataOf(KEY_PROGRESS to -1))
                            }
                        }
                    }
                }
            }

            client.close()

            setProgress(workDataOf(KEY_PROGRESS to 100))

            // Обновляем БД — теперь файл скачан
            trackRepository.updateFilePath(trackId, outputFile.absolutePath)

            Result.success(
                workDataOf(
                    KEY_TRACK_ID to trackId,
                    "file_path" to outputFile.absolutePath
                )
            )
        } catch (e: Exception) {
            Result.failure(workDataOf("error" to e.message))
        }
    }
}