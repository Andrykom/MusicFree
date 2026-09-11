# MusicFree

Android-плеер для поиска и стриминга музыки из веб-источников. Находит удалённые релизы, редкие версии и оригиналы без цензуры.

> Для личного использования.

## Возможности

- **Поиск по 3 источникам** — плагинная система `SourcePlugin`: `DriveMusic` / `TheMP3` / `Zvuch` (чипсы на экране поиска, дедупликация по `id`)
- **Стриминг + фон** — `Media3/ExoPlayer` + `MediaSessionService`, нотификация с обложкой, `AudioFocus`/`BecomingNoisy` (пауза при звонке/вытаскивании наушников)
- **Скачивание** — `WorkManager` с прогрессом (`0-100%`/`indeterminate`), `LinearProgress` в библиотеке, ретрай, отмена, чистка файла при удалении (`filesDir/music`)
- **Библиотека и плейлисты** — `Room` (`tracks`, `playlist_tracks`, `cached_lyrics`), удаление чистит файл и очередь
- **Очередь** — `BottomSheet` в фулл-плеере (`QueueMusic`): свайп `SwipeToDismissBox` → удаление, тап → играть, `animateItem` + автоскролл к текущему; лонг-пресс на треке в списках → меню *«Играть следующим / Добавить в очередь»* (`insertAfterCurrent` / `addToEnd`)
- **Шафл** — свой (не `ExoPlayer`): шафлится только хвост после текущего, `originalQueue` для восстановления, позиция трека сохраняется (`seekToPosition`)
- **Волна** — `DeezerRadioApi` → `PluginManager`: буфер `1` трек вперёд, `insertAt(end)` (FIFO, без вечного хвоста), автопродление сидов, фильтр блэклиста
- **Блэклист артистов** — `DataStore` `blocked_artists` (нормализация `lowercase`, сплит `,; & feat.`), кнопка `Block` в фулле (красный когда в блэке), управление в настройках, чистка очереди при блоке
- **Тексты** — цепочка `Netease → LrcLib(with/without duration) → Kugou → Genius` (чистка `()` в названии, `[]` и `*Lyrics` в Genius, `30s` таймаут), 3-уровневый кэш `memory → Room(cached_lyrics) → сеть` (переживает офлайн, инвалидация грязного кэша), `LyricsUiState` (`Idle/Loading/Success/Empty/Error` + ретрай), свайп `→` выход из текста, отдельный вид для `isSynced` (караоке) и `plain`
- **Обложки** — `CoverProvider` `iTunes → Deezer`, либо нативные с сайта (`Zvuch`), `upscaleCover` `100→600`, `Coil` + `TrackCover`
- **UI/Навигация** — `Compose Material3`, `HorizontalPager(beyondViewportPageCount=2)` для вкладок, `AnimatedVisibility` bottomBar, `FullPlayer`/`Lyrics` как оверлеи `slideIn/Out(350ms)` с `Animatable` свайпом вниз (за пальцем) и фоном, кликабельный артист в фулле (`primary`, `→ поиск`), упрощённые `TrackItem` (только `Скачать/Удалить`, подсветка вместо иконки паузы)

## Стек

Kotlin 2.0 · Compose BOM 2024.09 + Material3 · Navigation Compose · Media3 1.4.1 (ExoPlayer, Session) · Room 2.6.1 (KSP) · Koin 3.5 · Ktor 2.3 (Android) + ContentNegotiation · Jsoup 1.17 · WorkManager 2.9 · Coil 2.7 · DataStore 1.1 · Coroutines/Flow

## Сборка

1. [Android Studio Ladybug+](https://developer.android.com/studio) (в комплекте JDK 17+)
2. `File → Open` → папка проекта → дождаться `Gradle Sync` (`local.properties` создастся автоматом)
3. `Run ▶` (minSdk 26, targetSdk 35)

```bash
./gradlew assembleDebug            # Windows: gradlew.bat assembleDebug
```

Если `Could not resolve com.android.tools.build:gradle … requires JVM 11`:
```powershell
$env:JAVA_HOME = "C:\Users\<user>\.jdks\corretto-22.0.2"  # или studio\jbr
.\gradlew.bat assembleDebug
```

Схемы Room лежат в `app/schemas/` — коммить их, без них `ksp` не сгенерит миграции.

## Архитектура

```
app/src/main/java/com/mf/musicfree/
├── plugin/                 # SourcePlugin(name, search) + PluginManager
│   ├── DriveMusicPlugin / TheMP3Plugin / ZvuchPlugin
│   └── exception/PluginException
├── data/
│   ├── api/                # Deezer/DeezerRadio, ITunes, Netease, LrcLib, Kugou, Genius, CoverProvider
│   ├── db/                 # AppDatabase v4 (tracks, playlist_tracks, cached_lyrics) + Migrations 2→3,3→4
│   │   └── dao/TrackDao, PlaylistDao, LyricsDao
│   ├── model/Track, Playlist, CachedLyrics, Lyrics
│   ├── preferences/BlockedArtistsRepository (DataStore)
│   ├── download/TrackDownloadWorker (progress)
│   └── repository/Track/Playlist/Lyrics/RadioRepository
├── player/                 # PlaybackService (MediaSession, AudioFocus, BecomingNoisy)
│   ├── PlayerController    # trackQueue, ExoPlayer, insertAt/addToEnd/move/removeById
│   └── PlayerViewModel     # queue/currentIndex/currentTrack, radio (buffer=1), queue ops, shuffle(originalQueue), blockedArtists, lyricsState
└── ui/
    ├── MainScreen          # HorizontalPager(3) + NavHost(playlist_detail) + overlay FullPlayer/Lyrics
    ├── search/SearchScreen # чипсы плагинов, distinctBy(id), combinedClickable → queue menu
    ├── library/LibraryScreen, PlaylistDetailScreen
    ├── player/FullPlayerScreen (offset+swipe), LyricsScreen (plain vs synced), MiniPlayer (swipe up), QueueBottomSheet
    └── settings/SettingsScreen (блэклист)
```

### Добавить новый источник

```kotlin
class MyPlugin : SourcePlugin {
    override val name = "MySource"
    override suspend fun search(query: String): List<SearchTrack> {
        // Ktor + Jsoup, вернуть SearchTrack(id,title,artist,duration,coverUrl,audioUrl)
    }
}
// AppModule.kt
single { PluginManager().apply { register(MyPlugin()) } }
```

## Разработка

- Логи скрейперов: `adb logcat -s DriveMusicPlugin:D TheMP3Plugin:D ZvuchPlugin:D LyricsRepository:D GeniusApi:D`
- Проверка сайта: `curl -A "Mozilla/5.0" "https://wwv.zvuch.com/search?q=..." | grep -o 'data-artist="[^"]*"'`
- Миграции Room — писать в `AppDatabase.MIGRATION_X_Y`, не использовать `fallbackToDestructiveMigration` для новых версий

## Обновление на GitHub (GitHub Desktop)

1. Открой GitHub Desktop → `File → Add local repository` → выбери `MusicFree2` (если уже добавлен — просто открой)
2. В `Changes` увидишь изменённые файлы + `app/schemas/.../4.json`
3. Внизу `Summary` → `Update: queue, wave, lyrics, pager` → `Commit to main`
4. `Push origin` (вверху `Push origin` / `Publish branch` если первый раз)

На втором ПК: `File → Clone repository` → выбери `MusicFree` → `Open in Android Studio`.

## Лицензия

Только для личного использования.
