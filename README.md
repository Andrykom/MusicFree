# MusicFree

Android-плеер для поиска и стриминга музыки из веб-источников. Находит удалённые релизы, редкие версии и оригиналы без цензуры.

> Для личного использования.

## Возможности

- **Поиск по 3 веб-источникам** — плагинная система `SourcePlugin`: `DriveMusic` / `TheMP3` / `Zvuch` (чипсы на экране поиска, дедупликация по `id`, выбор источника по умолчанию).
- **Импорт из Яндекс Музыки** — быстрый перенос треков из открытых плейлистов Яндекс Музыки по ссылке прямо в «Мою музыку»:
  - Извлечение данных через официальные API-эндпоинты и веб-парсинг без ограничений на размер (поддержка больших плейлистов на 1000+ треков).
  - Умный каскадный поиск (`Zvuch` → `TheMP3` → `DriveMusic`) с авто-фолбэком на основного артиста при фитах.
  - Сохранение исходного хронологического порядка добавления треков.
- **Музыка с устройства (Локальные треки)** — закрепленный системный плейлист во вкладке «Плейлисты»:
  - Чтение медиатеки через Android `MediaStore` (поддержка Android 13+ `READ_MEDIA_AUDIO` и старых версий).
  - Автоматическая подгрузка онлайн-обложек высокого разрешения (`CoverProvider`) и синхронизированных караоке-текстов для файлов устройства.
  - Возможность добавления локальных файлов в пользовательские плейлисты.
- **Акустический кроссфейд** — настоящее плавное наложение треков на стыке:
  - Реализован через дополнительный независимый `crossfadePlayer`: затухающий трек переносится во вспомогательный плеер, пока основной плеер параллельно начинает следующий трек с нарастанием громкости.
  - Настройка длительности (Выкл, 2, 4, 8 сек) в Настройках.
- **Динамический фон обложки (Aura Background)** — адаптивный блюр-эффект и градиенты на основе цветовой гаммы обложки с контролем контраста и яркости для корректного отображения элемента управления.
- **Стриминг + фон** — `Media3/ExoPlayer` + `MediaSessionService`, нотификация с обложкой, `AudioFocus`/`BecomingNoisy` (пауза при звонке/отключении наушников).
- **Скачивание** — фоновая загрузка через `WorkManager` с отображением прогресса (`0-100%`/`indeterminate`), `LinearProgress` в библиотеке, автоповтор, отмена, удаление файлов (`filesDir/music`).
- **Библиотека и плейлисты** — локальная БД `Room` (`tracks`, `playlist_tracks`, `cached_lyrics`), удаление трека очищает файл и очередь.
- **Очередь воспроизведения** — `BottomSheet` в полноэкранном плеере (`QueueMusic`): свайп для удаления, переключение по тапу, анимации списка и защита от случайного перемещения играющего трека; контекстное меню треков *«Играть следующим / Добавить в очередь»*.
- **Умный шафл** — перемешивание только хвоста очереди после текущей позиции, сохранение `originalQueue` для возврата к исходному порядку.
- **Моя волна (Радио)** — персональное радио по артисту через `DeezerRadioApi` с каскадным фолбэком на плагины, буфер в 1 трек вперёд (FIFO), автопродление и фильтрация блэклиста.
- **Блэклист артистов** — исключение нежелательных исполнителей из волны через `DataStore` (нормализация, парсинг фитов), мгновенное удаление из очереди, управление в Настройках.
- **Синхронизированные тексты песен** — многоуровневый парсинг `Netease → LrcLib → Kugou → Genius` с очисткой названий, 3-уровневый кэш `RAM → Room → Сеть` с поддержкой офлайна, караоке-режим (`isSynced`), мгновенное переключение при смене трека и сброс паузы ручной прокрутки.
- **Обложки высокого разрешения** — провайдер `iTunes → Deezer` с апскейлом до 600px или нативные обложки из поисковых источников.
- **Поддержать автора** — окно крипто-пожертвований в Настройках с поддержкой BTC, ETH, Solana, BNB Chain, USDT TRC-20 и копированием в один клик.
- **UI / Навигация** — `Jetpack Compose Material 3`, жестовое управление (свайп вниз для закрытия плеера/текста за пальцем, свайп вправо на экране текста для возврата), адаптивная навигация, кликабельные имена артистов с переходом в поиск.

## Стек технологий

Kotlin 2.0 · Compose BOM 2024.09 + Material3 · Navigation Compose · Media3 1.4.1 (ExoPlayer, Session) · Room 2.6.1 (KSP) · Koin 3.5 · Ktor 2.3 (Android) + ContentNegotiation · Jsoup 1.17 · WorkManager 2.9 · Coil 2.7 · DataStore 1.1 · Coroutines / Flow

## Сборка и запуск

1. [Android Studio Ladybug+](https://developer.android.com/studio) (JDK 17-21)
2. `File → Open` → папка проекта → дождаться синхронизации `Gradle Sync`
3. `Run ▶` (minSdk 26, targetSdk 35)

### Сборка APK из терминала:
```powershell
# Windows
$env:JAVA_HOME = "C:\Users\<user>\.jdks\ms-21.0.12.1"  # путь к вашей Java 17-21
.\gradlew.bat assembleDebug
```

```bash
# Linux / macOS
./gradlew assembleDebug
```

### Запуск модульных тестов:
```powershell
.\gradlew.bat testDebugUnitTest
```

Схемы Room хранятся в папке `app/schemas/` — они необходимы для автоматической генерации миграций через KSP.

## Архитектура проекта

```
app/src/main/java/com/mf/musicfree/
├── plugin/                 # Модули источников музыки (SourcePlugin + PluginManager)
│   ├── DriveMusicPlugin / TheMP3Plugin / ZvuchPlugin
│   └── exception/PluginException
├── data/
│   ├── api/                # Клиенты Deezer, ITunes, Netease, LrcLib, Kugou, Genius, CoverProvider
│   ├── db/                 # Room БД (tracks, playlist_tracks, cached_lyrics) + миграции
│   │   └── dao/TrackDao, PlaylistDao, LyricsDao
│   ├── model/              # Модели данных Track, Playlist, CachedLyrics, Lyrics
│   ├── preferences/        # SettingsRepository, BlockedArtistsRepository (DataStore)
│   ├── download/           # Фоновая загрузка TrackDownloadWorker (WorkManager)
│   ├── importer/           # Импорт плейлистов: PlaylistWebExtractor, MusicImportService
│   └── repository/         # Track, Playlist, Lyrics, Radio, DeviceTracksRepository
├── player/                 # Воспроизведение аудио и системный сервис
│   ├── PlaybackService     # MediaSessionService, AudioFocus, BecomingNoisy
│   ├── PlayerController    # Основной ExoPlayer + crossfadePlayer, управление очередью
│   └── PlayerViewModel     # Состояние плеера, кроссфейд, очередь, радио, тексты, шафл
└── ui/
    ├── MainScreen          # Корневой экран с Pager, оверлеями FullPlayer и Lyrics
    ├── search/             # Поиск треков, чипсы плагинов, контекстные меню
    ├── library/            # Моя музыка, Плейлисты, Треки с устройства (Device), Детали плейлиста
    ├── player/             # FullPlayerScreen, LyricsScreen, MiniPlayer, QueueBottomSheet, CoverAuraBackground
    └── settings/           # Настройки: Импорт Яндекс Музыки, Кроссфейд, Блэклист, Донаты
```

## Разработка и мониторинг

- **Логи импорта Яндекс Музыки:**
  ```shell
  adb logcat -s MusicImport
  ```
- **Логи плагинов и скрейперов:**
  ```shell
  adb logcat -s DriveMusicPlugin:D TheMP3Plugin:D ZvuchPlugin:D LyricsRepository:D
  ```
- **Логи плеера и кроссфейда:**
  ```shell
  adb logcat -s PlayerController:D PlayerViewModel:D
  ```

## Обновление на GitHub

1. Откройте **GitHub Desktop** (или используйте терминал).
2. Проверьте изменения во вкладке `Changes` (включая схемы `app/schemas/` и обновлённый код).
3. Укажите название коммита (например, `Refactor: fix track dynamic contrast and sync lyrics state lifecycle`).
4. Нажмите `Commit to main` и затем `Push origin`.

## Лицензия

Проект предназначен исключительно для личного и ознакомительного использования.