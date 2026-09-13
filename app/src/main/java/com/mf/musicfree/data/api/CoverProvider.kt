package com.mf.musicfree.data.api

import android.util.Log
import android.util.LruCache

class CoverProvider(
    private val iTunesApi: ITunesApi,
    private val deezerApi: DeezerApi
) {
    private val memoryCache = LruCache<String, String>(250)

    suspend fun searchCover(artist: String, title: String): String? {
        val cacheKey = "${artist.trim().lowercase()}|${title.trim().lowercase()}"
        memoryCache.get(cacheKey)?.let {
            Log.d("CoverProvider", "⚡ Cache HIT: $artist - $title -> $it")
            return it
        }

        Log.d("CoverProvider", "🔍 Searching cover for: $artist - $title")

        // 1. iTunes
        try {
            val iTunesCover = iTunesApi.searchCover(artist, title)
            if (iTunesCover != null) {
                Log.d("CoverProvider", "✅ iTunes HIT: $artist - $title")
                memoryCache.put(cacheKey, iTunesCover)
                return iTunesCover
            }
            Log.d("CoverProvider", "⚠️ iTunes MISS: $artist - $title")
        } catch (e: Exception) {
            Log.w("CoverProvider", "❌ iTunes ERROR: ${e.message}")
        }

        // 2. Deezer
        try {
            val deezerCover = deezerApi.searchCover(artist, title)
            if (deezerCover != null) {
                Log.d("CoverProvider", "✅ Deezer HIT: $artist - $title → $deezerCover")
                memoryCache.put(cacheKey, deezerCover)
                return deezerCover
            }
            Log.d("CoverProvider", "⚠️ Deezer MISS: $artist - $title")
        } catch (e: Exception) {
            Log.w("CoverProvider", "❌ Deezer ERROR: ${e.message}")
        }

        Log.d("CoverProvider", "💀 TOTAL MISS: $artist - $title")
        return null
    }
}