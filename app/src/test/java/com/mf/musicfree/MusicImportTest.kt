package com.mf.musicfree

import com.mf.musicfree.data.importer.ExtractedTrack
import com.mf.musicfree.data.importer.PlaylistPlatform
import com.mf.musicfree.data.importer.PlaylistWebExtractor
import org.json.JSONObject
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class MusicImportTest {

    private val extractor = PlaylistWebExtractor()

    @Test
    fun testDetectPlatform() {
        assertEquals(
            PlaylistPlatform.YANDEX,
            extractor.detectPlatform("https://music.yandex.ru/playlists/lk.b57b0ed0-c9cf-421b-9c12-7c777d1e97b0?utm_medium=copy_link")
        )
        assertEquals(
            PlaylistPlatform.YANDEX,
            extractor.detectPlatform("https://music.yandex.com/users/test/playlists/1000")
        )
        assertEquals(
            PlaylistPlatform.UNKNOWN,
            extractor.detectPlatform("https://vk.ru/audio_playlist766666031_1")
        )
        assertEquals(
            PlaylistPlatform.UNKNOWN,
            extractor.detectPlatform("https://spotify.com/playlist/123")
        )
    }

    @Test
    fun testParseYandexApiFormat() {
        val json = """
            {
                "result": {
                    "playlist": {
                        "title": "Мне нравится",
                        "tracks": [
                            {
                                "id": 101,
                                "track": {
                                    "title": "Bohemian Rhapsody",
                                    "artists": [{"id": 1, "name": "Queen"}]
                                }
                            },
                            {
                                "id": 102,
                                "track": {
                                    "title": "Billie Jean",
                                    "artists": [{"id": 2, "name": "Michael Jackson"}]
                                }
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val root = JSONObject(json)
        val result = root.optJSONObject("result") ?: root
        val playlistObj = result.optJSONObject("playlist") ?: result
        val tracksArray = playlistObj.optJSONArray("tracks")!!

        val extracted = mutableListOf<ExtractedTrack>()
        for (i in 0 until tracksArray.length()) {
            val item = tracksArray.optJSONObject(i) ?: continue
            val trackObj = item.optJSONObject("track") ?: item
            val title = trackObj.optString("title").ifEmpty { trackObj.optString("name") }.trim()
            val artistsArray = trackObj.optJSONArray("artists")
            val artist = if (artistsArray != null && artistsArray.length() > 0) {
                artistsArray.optJSONObject(0)?.optString("name")?.trim() ?: ""
            } else ""
            extracted.add(ExtractedTrack(artist, title))
        }

        assertEquals(2, extracted.size)
        assertEquals("Queen", extracted[0].artist)
        assertEquals("Bohemian Rhapsody", extracted[0].title)
        assertEquals("Michael Jackson", extracted[1].artist)
        assertEquals("Billie Jean", extracted[1].title)
    }

    @Test
    fun testParseYandexSchemaOrgJsonLd() {
        val jsonLd = """
            {
                "@context": "https://schema.org",
                "@type": "MusicPlaylist",
                "name": "Плейлист",
                "track": [
                    {
                        "@type": "MusicRecording",
                        "name": "In the End",
                        "byArtist": {
                            "@type": "MusicGroup",
                            "name": "Linkin Park"
                        }
                    },
                    {
                        "@type": "MusicRecording",
                        "name": "Lose Yourself",
                        "byArtist": {
                            "@type": "MusicGroup",
                            "name": "Eminem"
                        }
                    }
                ]
            }
        """.trimIndent()

        val root = JSONObject(jsonLd)
        val trackArray = root.optJSONArray("track")!!
        val extracted = mutableListOf<ExtractedTrack>()

        for (i in 0 until trackArray.length()) {
            val item = trackArray.optJSONObject(i) ?: continue
            val title = item.optString("name").trim()
            val byArtist = item.optJSONObject("byArtist")
            val artist = byArtist?.optString("name")?.trim() ?: ""
            extracted.add(ExtractedTrack(artist, title))
        }

        assertEquals(2, extracted.size)
        assertEquals("Linkin Park", extracted[0].artist)
        assertEquals("In the End", extracted[0].title)
        assertEquals("Eminem", extracted[1].artist)
        assertEquals("Lose Yourself", extracted[1].title)
    }

    @Test
    fun testTrackOrderingPreservesOriginalOrder() {
        // Исходный плейлист (трек 1 на самом верху)
        val originalPlaylist = listOf(
            ExtractedTrack("Artist 1", "Track 1"),
            ExtractedTrack("Artist 2", "Track 2"),
            ExtractedTrack("Artist 3", "Track 3")
        )

        // Добавляем треки снизу списка
        val reversed = originalPlaylist.reversed()
        val baseTimestamp = 1000000L
        val savedTimestamps = mutableMapOf<String, Long>()

        for ((index, track) in reversed.withIndex()) {
            val timestamp = baseTimestamp + (index * 1000L)
            savedTimestamps[track.title] = timestamp
        }

        // При сортировке DESC (как в Room getAllTracks)
        val sortedDesc = savedTimestamps.entries.sortedByDescending { it.value }.map { it.key }

        assertEquals("Track 1", sortedDesc[0])
        assertEquals("Track 2", sortedDesc[1])
        assertEquals("Track 3", sortedDesc[2])
    }

    @Test
    fun testParseYandexApiRealWorldStructure() {
        val sampleJson = """
            {
                "result": {
                    "owner": {
                        "uid": 766666031,
                        "login": "andryko",
                        "name": "Комовкин Андрей",
                        "sex": "male"
                    },
                    "title": "Мне нравится",
                    "tracks": [
                        {
                            "id": 152705758,
                            "track": {
                                "id": "152705758",
                                "title": "lose control",
                                "available": true,
                                "artists": [
                                    {"id": 16952877, "name": "joey cash"}
                                ],
                                "albums": [
                                    {"id": 36142721, "title": "lose control"}
                                ],
                                "labels": [
                                    {"id": 3418579, "name": "Midnight Boys"}
                                ]
                            }
                        },
                        {
                            "id": 152705759,
                            "track": {
                                "id": "152705759",
                                "title": "Спасает паранойя",
                                "available": true,
                                "artists": [
                                    {"id": 16952878, "name": "Kunteynir"}
                                ],
                                "albums": [
                                    {"id": 36142722, "title": "Альбом"}
                                ]
                            }
                        },
                        {
                            "id": 152705760,
                            "track": {
                                "id": "152705760",
                                "title": "goodnight horses!",
                                "available": true,
                                "artists": [
                                    {"id": 16952879, "name": "SALUKI"},
                                    {"id": 16952880, "name": "postoyanno"}
                                ]
                            }
                        }
                    ]
                }
            }
        """.trimIndent()

        val output = mutableListOf<ExtractedTrack>()
        extractor.parseTracksFromJson(sampleJson, output)

        assertEquals(3, output.size)
        assertEquals("joey cash", output[0].artist)
        assertEquals("lose control", output[0].title)

        assertEquals("Kunteynir", output[1].artist)
        assertEquals("Спасает паранойя", output[1].title)

        assertEquals("SALUKI, postoyanno", output[2].artist)
        assertEquals("goodnight horses!", output[2].title)

        // Убеждаемся, что имя владельца и лейбл не попали ни в исполнители, ни в название
        assertTrue(output.none { it.artist.contains("Комовкин") || it.title.contains("Комовкин") })
        assertTrue(output.none { it.artist.contains("Midnight Boys") || it.title.contains("Midnight Boys") })
    }

    @Test
    fun testFallbackChunkParserWhenTruncatedOrMalformed() {
        // Симулируем обрезанный/невалидный JSON (например, оборванный посередине 3-го трека),
        // на котором JSONObject падает с синтаксической ошибкой
        val truncatedJson = """
            {"result": {"owner": {"name": "Комовкин Андрей"}, "tracks": [
            {"track": {"title": "lose control", "artists": [{"name": "joey cash"}], "labels": [{"name": "Midnight Boys"}]}},
            {"track": {"title": "Спасает паранойя", "artists": [{"name": "Kunteynir"}]}},
            {"track": {"title": "broken track", "art
        """.trimIndent()

        val output = mutableListOf<ExtractedTrack>()
        extractor.parseTracksFromJson(truncatedJson, output)

        assertEquals(2, output.size)
        assertEquals("joey cash", output[0].artist)
        assertEquals("lose control", output[0].title)
        assertEquals("Kunteynir", output[1].artist)
        assertEquals("Спасает паранойя", output[1].title)
    }
}
