# MusicFree

Android-приложение для поиска и прослушивания музыки из веб-источников. Позволяет находить треки, недоступные в стриминговых сервисах: удалённые релизы, редкие версии и т.д.

> Проект для личного использования.

## Возможности

- **Поиск по нескольким источникам** — плагинная система: DriveMusic, TheMP3, Zvuch (переключаются на экране поиска)
- **Стриминг без скачивания** + фоновое воспроизведение с медиа-нотификацией
- **Скачивание треков** в локальную библиотеку (WorkManager)
- **Плейлисты** (Room)
- **«Волна»** — бесконечное радио на основе похожих треков Deezer: держит буфер из 2 треков впереди текущего, автоматически продлевает сиды
- **Тексты песен** с синхронизацией по строкам, цепочка источников: Netease → LRCLIB → Kugou → Genius; мусор в скобках названия (`(Single 2019)`, `(Prod. by ...)`) автоматически вырезается при поиске текста
- **Обложки** — iTunes → Deezer, либо нативные обложки источника

## Стек

Kotlin · Jetpack Compose (Material3) · Media3/ExoPlayer + MediaSession · Room · Koin · Ktor Client · Jsoup · WorkManager · Coil · DataStore

## Сборка

1. Установить [Android Studio](https://developer.android.com/studio) (JDK идёт в комплекте)
2. `File → Open` → выбрать папку проекта
3. Дождаться синхронизации Gradle (SDK путь пропишется в `local.properties` автоматически)
4. `Run ▶` на эмуляторе или устройстве (minSdk 26)

### Из терминала

```bash
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

Если Gradle не видит JDK — задать `JAVA_HOME` на JDK 17+:

```powershell
$env:JAVA_HOME = "C:\Users\<user>\.jdks\corretto-22.0.2"  # пример
.\gradlew.bat assembleDebug
```

## Архитектура

```
app/src/main/java/com/mf/musicfree/
├── plugin/            # Источники музыки
│   ├── SourcePlugin   # Интерфейс: name + search(query)
│   ├── PluginManager  # Реестр плагинов
│   ├── DriveMusicPlugin / TheMP3Plugin / ZvuchPlugin
│   └── exception/     # Типизированные ошибки (NoConnection, ServerUnavailable, ParseError)
├── data/
│   ├── api/           # Обложки (iTunes/Deezer), радио (Deezer), тексты (Netease/LRCLIB/Kugou/Genius)
│   ├── db/            # Room: треки, плейлисты
│   ├── download/      # TrackDownloadWorker
│   └── repository/
├── player/            # PlaybackService (MediaSession), PlayerController, PlayerViewModel
└── ui/                # Compose-экраны: поиск, библиотека, плеер, тексты, настройки
```

### Как добавить новый источник

Реализовать `SourcePlugin` и зарегистрировать в `AppModule.kt`:

```kotlin
class MyPlugin : SourcePlugin {
    override val name = "MySource"
    override suspend fun search(query: String): List<SearchTrack> {
        // HTTP-запрос (Ktor) + парсинг (Jsoup)
    }
}
```

```kotlin
single {
    PluginManager().apply {
        register(DriveMusicPlugin(get()))
        register(TheMP3Plugin(get()))
        register(ZvuchPlugin())
        register(MyPlugin())
    }
}
```

UI подхватит новый источник автоматически (список строится из `pluginManager.plugins`).

## Замечания по разработке

- Скрейперы зависят от структуры сайтов-доноров: при поломке смотреть логи тегов `*Plugin` в Logcat
- Результаты поиска дедуплицируются по id (`SearchViewModel`) — LazyColumn использует id как ключ
- Тестов пока нет; проверка парсеров делается вручную через curl + Logcat

## Лицензия

Только для личного использования.
