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
        val rawFileName = inputData.getString(KEY_FILE_NAME) ?: "$trackId.mp3"
        val safeFileName = rawFileName.replace(Regex("""[\\/:*?"<>|]"""), "_")

        val musicDir = File(applicationContext.filesDir, "music").apply { mkdirs() }
        val outputFile = File(musicDir, safeFileName)

        return try {
            HttpClient(Android).use { client ->
                val response: HttpResponse = client.get(audioUrl) {
                    header(
                        HttpHeaders.UserAgent,
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                    )
                    header(HttpHeaders.Accept, "*/*")
                    val referer = when {
                        audioUrl.contains("zvuch", ignoreCase = true) -> "https://wwv.zvuch.com/"
                        audioUrl.contains("themp3", ignoreCase = true) ||
                                audioUrl.contains("sunproxy", ignoreCase = true) -> "https://themp3.info/"
                        audioUrl.contains("drivemusic", ignoreCase = true) -> "https://ru.drivemusic.me/"
                        else -> null
                    }
                    if (referer != null) {
                        header(HttpHeaders.Referrer, referer)
                    }
                }

                if (!response.status.isSuccess()) {
                    return Result.failure(workDataOf("error" to "HTTP ${response.status.value}"))
                }

                val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L
                var downloaded = 0L
                var lastProgress = -1
                var lastIndeterminateBytes = 0L

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
                            } else if (downloaded - lastIndeterminateBytes >= 64 * 1024) {
                                lastIndeterminateBytes = downloaded
                                setProgress(workDataOf(KEY_PROGRESS to -1))
                            }
                        }
                    }
                }
            }

            setProgress(workDataOf(KEY_PROGRESS to 100))
            trackRepository.updateFilePath(trackId, outputFile.absolutePath)

            Result.success(
                workDataOf(
                    KEY_TRACK_ID to trackId,
                    "file_path" to outputFile.absolutePath
                )
            )
        } catch (e: Exception) {
            try { outputFile.delete() } catch (_: Exception) {}
            Result.failure(workDataOf("error" to (e.message ?: "Ошибка загрузки")))
        }
    }
}