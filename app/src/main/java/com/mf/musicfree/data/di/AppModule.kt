package com.mf.musicfree.di

import androidx.room.Room
import androidx.work.WorkManager
import com.mf.musicfree.data.api.CoverProvider
import com.mf.musicfree.data.api.DeezerApi
import com.mf.musicfree.data.api.DeezerRadioApi
import com.mf.musicfree.data.api.GeniusApi
import com.mf.musicfree.data.api.ITunesApi
import com.mf.musicfree.data.api.KugouApi
import com.mf.musicfree.data.api.LastFmApi
import com.mf.musicfree.data.api.ListenBrainzApi
import com.mf.musicfree.data.api.LrcLibApi
import com.mf.musicfree.data.api.NeteaseApi
import com.mf.musicfree.data.db.AppDatabase
import com.mf.musicfree.data.importer.MusicImportService
import com.mf.musicfree.data.importer.PlaylistWebExtractor
import com.mf.musicfree.data.preferences.BlockedArtistsRepository
import com.mf.musicfree.data.preferences.SettingsRepository
import com.mf.musicfree.data.preferences.TasteProfileRepository
import com.mf.musicfree.data.repository.DeviceTracksRepository
import com.mf.musicfree.data.repository.LyricsRepository
import com.mf.musicfree.data.repository.PlaylistRepository
import com.mf.musicfree.data.repository.RadioRepository
import com.mf.musicfree.data.repository.TrackRepository
import com.mf.musicfree.player.PlayerController
import com.mf.musicfree.player.PlayerViewModel
import com.mf.musicfree.plugin.DriveMusicPlugin
import com.mf.musicfree.plugin.PluginManager
import com.mf.musicfree.plugin.TheMP3Plugin
import com.mf.musicfree.plugin.ZvuchPlugin
import com.mf.musicfree.ui.library.LibraryViewModel
import com.mf.musicfree.ui.library.PlaylistDetailViewModel
import com.mf.musicfree.ui.search.SearchViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import com.mf.musicfree.data.repository.CatalogRepository
import com.mf.musicfree.ui.catalog.CatalogViewModel

val appModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            "mf_database"
        )
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            // Fallback оставлен для установок с v1 — их схему не восстановить.
            // Для всех новых изменений схемы писать миграции!
            .fallbackToDestructiveMigration()
            .build()
    }
    single { get<AppDatabase>().trackDao() }
    single { get<AppDatabase>().playlistDao() }
    single { get<AppDatabase>().lyricsDao() }
    single { TrackRepository(get()) }
    single { PlaylistRepository(get()) }
    single { PlayerController(androidContext()) }
    single { WorkManager.getInstance(androidContext()) }
    single { BlockedArtistsRepository(androidContext()) }
    single { SettingsRepository(androidContext()) }

    // Единый Ktor HTTP client для всех API, текстов, обложек и плагинов поиска
    single {
        HttpClient(Android) {
            followRedirects = true
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 2)
                exponentialDelay()
            }
            engine {
                connectTimeout = 10_000
                socketTimeout = 15_000
            }
        }
    }

    // API для обложек
    single { ITunesApi(get()) }
    single { DeezerApi(get()) }
    single { CoverProvider(get(), get()) }

    // API для текстов песен (LRC и Plain)
    single { NeteaseApi(get()) }
    single { LrcLibApi(get()) }
    single { KugouApi(get()) }
    single { GeniusApi(get()) }
    single { DeezerRadioApi(get()) }

    // Репозиторий текстов (цепочка: Netease -> LrcLib -> Kugou -> Genius)
    single { LyricsRepository(get(), get(), get(), get(), get()) }

    single {
        PluginManager().apply {
            register(DriveMusicPlugin(get(), get()))
            register(TheMP3Plugin(get(), get()))
            register(ZvuchPlugin(get()))
        }
    }


    single { TasteProfileRepository(androidContext()) }
    single { LastFmApi(get()) } // позже можно передать apiKey = "ТВОЙ_КЛЮЧ"
    single { ListenBrainzApi(get()) }
    single { DeezerRadioApi(get()) }

    single { RadioRepository(get(), get(), get(), get(), get(), get()) }

    single { DeviceTracksRepository(androidContext()) }
    single { PlaylistWebExtractor() }
    single { MusicImportService(get(), get(), get()) }
    single { com.mf.musicfree.data.download.DownloadManager(get(), get()) }
    single { CatalogRepository(get(), get(), get(), get()) }

    viewModel { PlayerViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { SearchViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { LibraryViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { CatalogViewModel(get(), get(), get()) }
    viewModel { (playlistId: String) ->
        PlaylistDetailViewModel(playlistId, get(), get())
    }
}