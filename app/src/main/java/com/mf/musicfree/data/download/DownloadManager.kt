package com.mf.musicfree.data.download

import androidx.lifecycle.Observer
import androidx.work.*
import com.mf.musicfree.data.model.Track
import com.mf.musicfree.data.repository.TrackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
typealias ProgressMap = Map<String, Int>

class DownloadManager(
    private val workManager: WorkManager,
    private val trackRepository: TrackRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val reportedFailedIds = mutableSetOf<UUID>()
    private val workerClassName = TrackDownloadWorker::class.java.name

    private val _downloadProgress = MutableStateFlow<ProgressMap>(emptyMap())
    val downloadProgress: StateFlow<ProgressMap> = _downloadProgress.asStateFlow()

    private val _downloadFailed = MutableStateFlow<Set<String>>(emptySet())
    val downloadFailed: StateFlow<Set<String>> = _downloadFailed.asStateFlow()

    private val _downloadErrorEvents = MutableSharedFlow<String>(extraBufferCapacity = 5)
    val downloadErrorEvents: SharedFlow<String> = _downloadErrorEvents.asSharedFlow()

    private val workLiveData = workManager.getWorkInfosByTagLiveData("track_download")
    private val workObserver = Observer<List<WorkInfo>> { infos ->
        val prog = mutableMapOf<String, Int>()
        val failed = mutableSetOf<String>()
        infos.forEach { info ->
            val trackId = info.tags.firstOrNull {
                it != "track_download" && it != workerClassName
            } ?: return@forEach

            when (info.state) {
                WorkInfo.State.RUNNING -> {
                    prog[trackId] = info.progress.getInt(TrackDownloadWorker.KEY_PROGRESS, 0)
                }
                WorkInfo.State.ENQUEUED -> {
                    prog[trackId] = 0
                }
                WorkInfo.State.FAILED -> {
                    failed.add(trackId)
                    if (reportedFailedIds.add(info.id)) {
                        val errMsg = info.outputData.getString("error") ?: "Ошибка сети"
                        _downloadErrorEvents.tryEmit("Не удалось скачать: $errMsg")
                    }
                }
                WorkInfo.State.CANCELLED -> {
                    failed.add(trackId)
                }
                else -> {}
            }
        }
        _downloadProgress.value = prog
        _downloadFailed.value = failed
    }

    init {
        workLiveData.observeForever(workObserver)
    }

    fun downloadTrack(track: Track) {
        val url = track.sourceUrl
        if (url.isNullOrBlank()) {
            _downloadErrorEvents.tryEmit("Нет ссылки для скачивания «${track.title}»")
            return
        }
        scope.launch {
            try {
                trackRepository.ensureTrackExists(track, bumpToTop = false)
                val safeName = "${track.artist} - ${track.title}"
                    .replace(Regex("""[\\/:*?"<>|]"""), "_")
                    .take(120)
                val inputData = Data.Builder()
                    .putString(TrackDownloadWorker.KEY_TRACK_ID, track.id)
                    .putString(TrackDownloadWorker.KEY_AUDIO_URL, url)
                    .putString(TrackDownloadWorker.KEY_FILE_NAME, "$safeName.mp3")
                    .build()

                val work = OneTimeWorkRequestBuilder<TrackDownloadWorker>()
                    .setInputData(inputData)
                    .addTag("track_download")
                    .addTag(track.id)
                    .build()

                workManager.enqueueUniqueWork(track.id, ExistingWorkPolicy.REPLACE, work)
            } catch (e: Exception) {
                _downloadErrorEvents.tryEmit("Ошибка запуска загрузки: ${e.message}")
            }
        }
    }

    fun cancelOrDeleteDownload(trackId: String, filePath: String?) {
        scope.launch {
            try { workManager.cancelUniqueWork(trackId) } catch (_: Exception) {}
            filePath?.let { path ->
                try { File(path).delete() } catch (_: Exception) {}
            }
        }
    }
}