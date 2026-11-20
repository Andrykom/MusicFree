package com.mf.musicfree.data.repository

import android.util.Log
import com.mf.musicfree.data.api.CoverProvider
import com.mf.musicfree.data.api.DeezerApi
import com.mf.musicfree.data.api.ITunesApi
import com.mf.musicfree.data.model.AlbumInfo
import com.mf.musicfree.data.model.AlbumTrackItem
import com.mf.musicfree.data.model.ArtistInfo
import com.mf.musicfree.data.model.ArtistPageData
import com.mf.musicfree.data.model.CatalogSearchResult
import com.mf.musicfree.plugin.PluginManager
import com.mf.musicfree.plugin.SearchTrack
import com.mf.musicfree.plugin.SourcePlugin
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class CatalogRepository(
    private val deezerApi: DeezerApi,
    private val iTunesApi: ITunesApi,
    private val coverProvider: CoverProvider,
    private val pluginManager: PluginManager
) {
    private val artistCache = ConcurrentHashMap<String, ArtistInfo>()
    private val albumCache = ConcurrentHashMap<String, AlbumInfo>()
    private val zvuchArtistPoolCache = ConcurrentHashMap<String, List<SearchTrack>>()

    private val trailingParensRegex = Regex("""^(.*?)\s*\(([^()]{2,})\)\s*$""")

    private val nonAlbumMarkers = listOf(
        "feat", "ft.", "prod", "remix", "speed up", "sped up", "slowed",
        "reverb", "nightcore", "live", "acoustic", "cover", "ковёр", "ковер",
        "radio edit", "clean", "explicit", "instrumental", "минус", "bass",
        "intro", "outro", "skit", "demo", "bonus", "version", "mix", "edit",
        "ost", "радио", "концерт", "remaster", "deluxe", "single"
    )

    private val remixMarkers = listOf(
        "remix", "speed up", "sped up", "slowed", "nightcore", "cover", "ковёр", "ковер", "bass"
    )

    private val orderedPlugins: List<SourcePlugin>
        get() = pluginManager.plugins.sortedBy { plugin ->
            when {
                plugin.name.equals("Zvuch", ignoreCase = true) -> 0
                plugin.name.equals("DriveMusic", ignoreCase = true) -> 1
                else -> 2
            }
        }

    fun getCachedArtist(id: String): ArtistInfo? = artistCache[id]
    fun getCachedAlbum(id: String): AlbumInfo? = albumCache[id]

    fun registerArtist(artist: ArtistInfo): String {
        artistCache[artist.id] = artist
        return artist.id
    }

    fun registerArtistByName(name: String): String {
        val cleanName = name.trim()
        val existing = artistCache.values.firstOrNull {
            it.name.equals(cleanName, ignoreCase = true)
        }
        if (existing != null) return existing.id
        val id = "name_${cleanName.lowercase().hashCode().toUInt()}"
        val info = ArtistInfo(id = id, name = cleanName)
        artistCache[id] = info
        return id
    }

    fun registerAlbum(album: AlbumInfo): String {
        albumCache[album.id] = album
        return album.id
    }

    /**
     * Поиск исполнителей и альбомов для верхнего блока SearchScreen.
     * Выводит артиста, если запрос совпадает с именем исполнителя, и релевантные альбомы.
     */
    suspend fun searchCatalog(query: String): CatalogSearchResult = coroutineScope {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@coroutineScope CatalogSearchResult()

        val dzArtistsDeferred = async { withTimeoutOrNull(4_500) { deezerApi.searchArtists(trimmed, 4) }.orEmpty() }
        val dzAlbumsDeferred = async { withTimeoutOrNull(4_500) { deezerApi.searchAlbums(trimmed, 8) }.orEmpty() }
        val itArtistsDeferred = async { withTimeoutOrNull(4_500) { iTunesApi.searchArtists(trimmed, 3) }.orEmpty() }
        val itAlbumsDeferred = async { withTimeoutOrNull(4_500) { iTunesApi.searchAlbums(trimmed, 6) }.orEmpty() }

        val dzArtists = dzArtistsDeferred.await()
        val itArtists = itArtistsDeferred.await()
        val dzAlbums = dzAlbumsDeferred.await()
        val itAlbums = itAlbumsDeferred.await()

        val mergedArtists = mergeArtists(dzArtists, itArtists)
            .filter { isArtistRelevantToQuery(it.name, trimmed) }
            .take(3)
            .onEach { artistCache[it.id] = it }

        val mergedAlbums = mergeAlbums(dzAlbums, itAlbums)
            .filter { isAlbumRelevantToQuery(it, trimmed) }
            .take(8)
            .onEach { albumCache[it.id] = it }

        CatalogSearchResult(artists = mergedArtists, albums = mergedAlbums)
    }

    /**
     * Загружает страницу исполнителя:
     * 1) Официальные альбомы + обложки из Deezer / iTunes
     * 2) Поиск треков исполнителя на Zvuch -> группировка по скобкам (название альбома)
     * 3) Объединение альбомов + вынос треков без скобок в "Синглы и треки"
     */
    suspend fun loadArtistPage(artistKey: String): ArtistPageData? = coroutineScope {
        val initial = artistCache[artistKey] ?: return@coroutineScope null
        val artistName = initial.name

        // Дообогащаем ID из Deezer и iTunes, если перешли по клику на имя артиста из плеера
        val dzMatchDeferred = async {
            if (initial.deezerId != null) null
            else withTimeoutOrNull(4_500) {
                deezerApi.searchArtists(artistName, 3)
                    .firstOrNull { isNameMatch(it.name, artistName) }
            }
        }
        val itMatchDeferred = async {
            if (initial.itunesId != null) null
            else withTimeoutOrNull(4_500) {
                iTunesApi.searchArtists(artistName, 3)
                    .firstOrNull { isNameMatch(it.name, artistName) }
            }
        }
        val zvuchDeferred = async { fetchZvuchArtistPool(artistName) }

        val dzMatch = dzMatchDeferred.await()
        val itMatch = itMatchDeferred.await()

        val resolvedArtist = initial.copy(
            name = dzMatch?.name ?: initial.name,
            pictureUrl = initial.pictureUrl ?: dzMatch?.pictureUrl,
            deezerId = initial.deezerId ?: dzMatch?.deezerId,
            itunesId = initial.itunesId ?: itMatch?.itunesId,
            fansCount = initial.fansCount ?: dzMatch?.fansCount
        )
        artistCache[artistKey] = resolvedArtist
        artistCache[resolvedArtist.id] = resolvedArtist

        val dzAlbumsDeferred = async {
            val id = resolvedArtist.deezerId ?: return@async emptyList()
            withTimeoutOrNull(5_000) { deezerApi.getArtistAlbums(id, resolvedArtist.name) }.orEmpty()
        }
        val itAlbumsDeferred = async {
            val id = resolvedArtist.itunesId ?: return@async emptyList()
            withTimeoutOrNull(5_000) { iTunesApi.getArtistAlbums(id, resolvedArtist.name) }.orEmpty()
        }

        val officialAlbums = mergeAlbums(dzAlbumsDeferred.await(), itAlbumsDeferred.await())
            .filter { !it.recordType.equals("single", ignoreCase = true) || it.trackCount > 2 }

        val rawZvuchTracks = zvuchDeferred.await()
        // Оставляем треки, где действительно фигурирует этот исполнитель
        val artistZvuchTracks = rawZvuchTracks.filter {
            isArtistContained(it.artist, resolvedArtist.name)
        }.ifEmpty { rawZvuchTracks }

        // Разбираем треки со Zvuch: что в скобках -> кандидат в альбом, что без скобок -> сингл
        val bracketGroups = LinkedHashMap<String, MutableList<SearchTrack>>()
        val bracketDisplayNames = HashMap<String, String>()
        val noBracketTracks = mutableListOf<SearchTrack>()

        for (track in artistZvuchTracks) {
            val (_, albumTag) = splitTitleAndAlbumBracket(track.title)
            if (albumTag != null) {
                val normTag = normalize(albumTag)
                val existingKey = bracketGroups.keys.firstOrNull { key ->
                    key == normTag ||
                            (key.length >= 8 && normTag.startsWith(key)) ||
                            (normTag.length >= 8 && key.startsWith(normTag))
                }
                val groupKey = existingKey ?: normTag
                bracketGroups.getOrPut(groupKey) { mutableListOf() }.add(track)
                // Сохраняем самую длинную (необрезанную) версию названия из скобок
                val currentDisplay = bracketDisplayNames[groupKey]
                if (currentDisplay == null || albumTag.length > currentDisplay.length) {
                    bracketDisplayNames[groupKey] = albumTag.removeSuffix("...").trim()
                }
            } else {
                noBracketTracks.add(track)
            }
        }

        // Сопоставляем группы из скобок с официальными альбомами Deezer/iTunes
        val usedGroupKeys = mutableSetOf<String>()
        val enrichedOfficialAlbums = officialAlbums.map { album ->
            val normAlb = normalize(album.title)
            val matchedKey = bracketGroups.keys.firstOrNull { gk ->
                gk == normAlb ||
                        (gk.length >= 6 && normAlb.startsWith(gk)) ||
                        (normAlb.length >= 6 && gk.startsWith(normAlb)) ||
                        (gk.length >= 6 && normAlb.contains(gk))
            }
            val matchedTracks = if (matchedKey != null) {
                usedGroupKeys.add(matchedKey)
                bracketGroups[matchedKey].orEmpty()
            } else {
                emptyList()
            }
            album.copy(prematchedTracks = matchedTracks)
        }

        // Альбомы, найденные в скобках на Zvuch, которых не оказалось в Deezer/iTunes (минимум 2 трека)
        val zvuchOnlyAlbums = mutableListOf<AlbumInfo>()
        for ((key, tracks) in bracketGroups) {
            if (key in usedGroupKeys) continue
            if (tracks.size >= 2) {
                val albTitle = bracketDisplayNames[key] ?: key
                val cover = tracks.firstNotNullOfOrNull { it.coverUrl }
                    ?: withTimeoutOrNull(2_500) { coverProvider.searchCover(resolvedArtist.name, albTitle) }
                zvuchOnlyAlbums.add(
                    AlbumInfo(
                        id = "zv_alb_${resolvedArtist.name.lowercase().hashCode().toUInt()}_${key.hashCode().toUInt()}",
                        title = albTitle,
                        artistName = resolvedArtist.name,
                        coverUrl = cover,
                        recordType = "album",
                        trackCount = tracks.size,
                        prematchedTracks = tracks
                    )
                )
            } else {
                // Одиночный трек со скобками оставляем в синглах
                noBracketTracks.addAll(tracks)
            }
        }

        val allAlbums = (enrichedOfficialAlbums + zvuchOnlyAlbums)
            .distinctBy { normalize(it.title) }
            .onEach { albumCache[it.id] = it }

        // Если у артиста нет фото в Deezer — берём обложку первого альбома или трека
        val finalArtist = if (resolvedArtist.pictureUrl.isNullOrBlank()) {
            val fallbackPic = allAlbums.firstNotNullOfOrNull { it.coverUrl }
                ?: artistZvuchTracks.firstNotNullOfOrNull { it.coverUrl }
            resolvedArtist.copy(pictureUrl = fallbackPic)
        } else {
            resolvedArtist
        }
        artistCache[artistKey] = finalArtist

        ArtistPageData(
            artist = finalArtist,
            albums = allAlbums,
            singles = noBracketTracks.distinctBy { it.id },
            allZvuchTracks = artistZvuchTracks
        )
    }

    /**
     * Загружает треклист альбома в официальном порядке (Deezer -> iTunes)
     * и автоматически привязывает аудиопотоки со Zvuch (и по скобкам, и просто по совпадению названия).
     */
    suspend fun loadAlbumTracks(albumKey: String): Pair<AlbumInfo, List<AlbumTrackItem>>? = coroutineScope {
        val initial = albumCache[albumKey] ?: return@coroutineScope null

        // 1. Получаем официальный порядок треков из Deezer или iTunes
        val officialTracksDeferred = async {
            var tracks = initial.deezerId?.let { id ->
                withTimeoutOrNull(5_000) {
                    deezerApi.getAlbumTracks(id, initial.artistName, initial.coverUrl)
                }.orEmpty()
            }.orEmpty()

            if (tracks.isEmpty() && initial.itunesId != null) {
                tracks = withTimeoutOrNull(5_000) {
                    iTunesApi.getAlbumTracks(initial.itunesId, initial.artistName, initial.coverUrl)
                }.orEmpty()
            }

            // Если альбом пришёл только со Zvuch без deezerId/itunesId — пробуем найти его в Deezer/iTunes ради порядка треков
            if (tracks.isEmpty() && initial.deezerId == null && initial.itunesId == null) {
                val query = "${initial.artistName} ${initial.title}".trim()
                val dzAlb = withTimeoutOrNull(4_000) {
                    deezerApi.searchAlbums(query, 3).firstOrNull { isTitleSimilar(it.title, initial.title) }
                }
                if (dzAlb?.deezerId != null) {
                    tracks = withTimeoutOrNull(4_500) {
                        deezerApi.getAlbumTracks(dzAlb.deezerId, initial.artistName, dzAlb.coverUrl ?: initial.coverUrl)
                    }.orEmpty()
                }
                if (tracks.isEmpty()) {
                    val itAlb = withTimeoutOrNull(4_000) {
                        iTunesApi.searchAlbums(query, 3).firstOrNull { isTitleSimilar(it.title, initial.title) }
                    }
                    if (itAlb?.itunesId != null) {
                        tracks = withTimeoutOrNull(4_500) {
                            iTunesApi.getAlbumTracks(itAlb.itunesId, initial.artistName, itAlb.coverUrl ?: initial.coverUrl)
                        }.orEmpty()
                    }
                }
            }
            tracks
        }

        // 2. Собираем пул треков со Zvuch: уже закэшированный пул артиста + поиск "Артист Альбом"
        val zvuchPoolDeferred = async {
            val cachedArtistPool = zvuchArtistPoolCache[normalize(initial.artistName)].orEmpty()
            val albumSearch = searchZvuchSafe("${initial.artistName} ${initial.title}".trim())
            val artistSearch = if (cachedArtistPool.isEmpty() && initial.artistName.isNotBlank()) {
                fetchZvuchArtistPool(initial.artistName)
            } else {
                cachedArtistPool
            }
            (initial.prematchedTracks + albumSearch + artistSearch).distinctBy { it.id }
        }

        val officialTracks = officialTracksDeferred.await()
        val zvuchPool = zvuchPoolDeferred.await()

        val resolvedCover = initial.coverUrl
            ?: zvuchPool.firstNotNullOfOrNull { it.coverUrl }
            ?: withTimeoutOrNull(2_500) { coverProvider.searchCover(initial.artistName, initial.title) }
        val finalAlbum = initial.copy(
            coverUrl = resolvedCover,
            trackCount = if (officialTracks.isNotEmpty()) officialTracks.size else initial.prematchedTracks.size
        )
        albumCache[albumKey] = finalAlbum

        // Если официальный треклист не нашёлся — показываем собранные со Zvuch треки этого альбома
        if (officialTracks.isEmpty()) {
            val fallbackItems = initial.prematchedTracks.mapIndexed { idx, st ->
                val (cleanTitle, _) = splitTitleAndAlbumBracket(st.title)
                AlbumTrackItem(
                    trackNumber = idx + 1,
                    title = cleanTitle.ifBlank { st.title },
                    artist = st.artist,
                    durationMs = st.duration,
                    coverUrl = finalAlbum.coverUrl ?: st.coverUrl,
                    resolvedTrack = st.copy(
                        title = cleanTitle.ifBlank { st.title },
                        coverUrl = finalAlbum.coverUrl ?: st.coverUrl
                    )
                )
            }
            return@coroutineScope finalAlbum to fallbackItems
        }

        // Сопоставляем каждый официальный трек с треком из пула Zvuch по названию
        val normAlbumTitle = normalize(finalAlbum.title)
        val matchedItems = officialTracks.map { item ->
            val best = findBestZvuchMatch(
                expectedTitle = item.title,
                expectedArtist = item.artist.ifBlank { finalAlbum.artistName },
                normAlbumTitle = normAlbumTitle,
                pool = zvuchPool
            )
            item.copy(
                coverUrl = finalAlbum.coverUrl ?: item.coverUrl ?: best?.coverUrl,
                resolvedTrack = best?.copy(
                    title = item.title,
                    artist = item.artist.ifBlank { best.artist },
                    coverUrl = finalAlbum.coverUrl ?: best.coverUrl
                )
            )
        }

        finalAlbum to matchedItems
    }

    /**
     * Лениво доискивает аудиопоток для трека альбома, которого не было в общем пуле поиска по артисту.
     */
    suspend fun resolveTrackStream(
        artist: String,
        title: String,
        coverUrl: String?
    ): SearchTrack? {
        val query = "$artist $title".trim()
        for (plugin in orderedPlugins) {
            try {
                val results = withTimeoutOrNull(6_000) { plugin.search(query) } ?: continue
                val best = findBestZvuchMatch(
                    expectedTitle = title,
                    expectedArtist = artist,
                    normAlbumTitle = "",
                    pool = results
                )
                if (best != null && !best.audioUrl.isNullOrBlank()) {
                    return best.copy(
                        title = title,
                        artist = artist.ifBlank { best.artist },
                        coverUrl = coverUrl ?: best.coverUrl
                    )
                }
            } catch (e: Exception) {
                Log.w("CatalogRepository", "resolveTrackStream(${plugin.name}) failed: ${e.message}")
            }
        }
        return null
    }

    private suspend fun fetchZvuchArtistPool(artistName: String): List<SearchTrack> {
        val key = normalize(artistName)
        zvuchArtistPoolCache[key]?.let { return it }
        val results = searchZvuchSafe(artistName)
        if (results.isNotEmpty()) {
            zvuchArtistPoolCache[key] = results
        }
        return results
    }

    private suspend fun searchZvuchSafe(query: String): List<SearchTrack> {
        val zvuch = pluginManager.getPlugin("Zvuch") ?: orderedPlugins.firstOrNull() ?: return emptyList()
        return try {
            withTimeoutOrNull(7_000) { zvuch.search(query) }.orEmpty()
        } catch (e: Exception) {
            Log.w("CatalogRepository", "Zvuch search failed for '$query': ${e.message}")
            emptyList()
        }
    }

    private fun findBestZvuchMatch(
        expectedTitle: String,
        expectedArtist: String,
        normAlbumTitle: String,
        pool: List<SearchTrack>
    ): SearchTrack? {
        val expTitle = normalize(expectedTitle)
        if (expTitle.isBlank()) return null
        val expArtist = normalize(expectedArtist)
        val expHasRemix = remixMarkers.any { expTitle.contains(it) }

        var bestTrack: SearchTrack? = null
        var bestScore = 0

        for (candidate in pool) {
            if (candidate.audioUrl.isNullOrBlank()) continue
            val (rawCleanTitle, bracketAlbum) = splitTitleAndAlbumBracket(candidate.title)
            val candTitle = normalize(rawCleanTitle)
            val candFull = normalize(candidate.title)
            if (candTitle.isBlank()) continue

            val candHasRemix = remixMarkers.any { candFull.contains(it) }
            if (candHasRemix && !expHasRemix) continue

            var score = 0
            when {
                candTitle == expTitle -> score += 100
                candFull == expTitle -> score += 95
                expTitle.length >= 4 && candTitle.contains(expTitle) -> score += 70
                candTitle.length >= 4 && expTitle.contains(candTitle) -> score += 60
                else -> continue
            }

            if (expArtist.isNotBlank() && isArtistContained(candidate.artist, expectedArtist)) {
                score += 30
            }
            if (normAlbumTitle.isNotBlank() && bracketAlbum != null) {
                val normBracket = normalize(bracketAlbum)
                if (normBracket == normAlbumTitle ||
                    (normBracket.length >= 5 && normAlbumTitle.startsWith(normBracket)) ||
                    (normAlbumTitle.length >= 5 && normBracket.startsWith(normAlbumTitle))
                ) {
                    score += 25
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestTrack = candidate
            }
        }
        return bestTrack
    }

    private fun splitTitleAndAlbumBracket(rawTitle: String): Pair<String, String?> {
        val match = trailingParensRegex.matchEntire(rawTitle.trim()) ?: return rawTitle.trim() to null
        val baseTitle = match.groupValues[1].trim()
        val inside = match.groupValues[2].trim()
        if (baseTitle.isBlank() || inside.length < 2) return rawTitle.trim() to null

        val lowerInside = inside.lowercase()
        val isNonAlbum = nonAlbumMarkers.any { lowerInside.contains(it) }
        return if (isNonAlbum) {
            rawTitle.trim() to null
        } else {
            baseTitle to inside
        }
    }

    private fun mergeArtists(primary: List<ArtistInfo>, secondary: List<ArtistInfo>): List<ArtistInfo> {
        val result = mutableListOf<ArtistInfo>()
        for (a in primary) {
            val sec = secondary.firstOrNull { isNameMatch(it.name, a.name) }
            result.add(a.copy(itunesId = a.itunesId ?: sec?.itunesId))
        }
        for (b in secondary) {
            if (result.none { isNameMatch(it.name, b.name) }) {
                result.add(b)
            }
        }
        return result
    }

    private fun mergeAlbums(primary: List<AlbumInfo>, secondary: List<AlbumInfo>): List<AlbumInfo> {
        val result = mutableListOf<AlbumInfo>()
        for (a in primary) {
            val sec = secondary.firstOrNull {
                isTitleSimilar(it.title, a.title) &&
                        (a.artistName.isBlank() || it.artistName.isBlank() || isNameMatch(it.artistName, a.artistName))
            }
            result.add(
                a.copy(
                    itunesId = a.itunesId ?: sec?.itunesId,
                    coverUrl = a.coverUrl ?: sec?.coverUrl,
                    releaseYear = a.releaseYear ?: sec?.releaseYear,
                    trackCount = if (a.trackCount > 0) a.trackCount else (sec?.trackCount ?: 0)
                )
            )
        }
        for (b in secondary) {
            val exists = result.any {
                isTitleSimilar(it.title, b.title) &&
                        (b.artistName.isBlank() || it.artistName.isBlank() || isNameMatch(it.artistName, b.artistName))
            }
            if (!exists) {
                result.add(b)
            }
        }
        return result
    }

    private fun isArtistRelevantToQuery(artistName: String, query: String): Boolean {
        val a = normalize(artistName)
        val q = normalize(query)
        if (a.isBlank() || q.isBlank()) return false
        return a == q || a.startsWith(q) || q.startsWith(a) || a.contains(q)
    }

    private fun isAlbumRelevantToQuery(album: AlbumInfo, query: String): Boolean {
        val t = normalize(album.title)
        val a = normalize(album.artistName)
        val q = normalize(query)
        if (q.isBlank()) return false
        val qWords = q.split(" ").filter { it.length >= 2 }
        if (qWords.isEmpty()) return t.contains(q) || a.contains(q)
        return qWords.all { w -> t.contains(w) || a.contains(w) } || t.contains(q) || a == q
    }

    private fun isArtistContained(trackArtist: String, targetArtist: String): Boolean {
        val ta = normalize(trackArtist)
        val target = normalize(targetArtist)
        if (ta.isBlank() || target.isBlank()) return false
        return ta == target || ta.contains(target) || target.contains(ta)
    }

    private fun isNameMatch(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        return na.isNotBlank() && na == nb
    }

    private fun isTitleSimilar(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isBlank() || nb.isBlank()) return false
        return na == nb || (na.length >= 5 && nb.startsWith(na)) || (nb.length >= 5 && na.startsWith(nb))
    }

    private fun normalize(s: String): String =
        s.lowercase()
            .replace('ё', 'е')
            .replace(Regex("""\([^)]*\)"""), " ")
            .replace(Regex("""\[[^\]]*]"""), " ")
            .replace(Regex("""[^a-zа-я0-9\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
}