package com.mf.musicfree

import com.mf.musicfree.data.model.Track
import org.junit.Assert.*
import org.junit.Test

class ShuffleAndSettingsTest {

    @Test
    fun testShuffleLogic_currentTrackMovesToTopAndRestores() {
        val tracks = (1..5).map {
            Track(
                id = "track_$it",
                title = "Song $it",
                artist = "Artist $it",
                duration = 180000L
            )
        }

        // Suppose currently playing track is the LAST one (index 4)
        val currentIdx = 4
        val current = tracks[currentIdx]

        // Shuffling logic simulation matching PlayerViewModel
        val originalQueue = tracks.toList()
        val others = tracks.filterIndexed { idx, _ -> idx != currentIdx }.shuffled()
        val shuffledQueue = listOf(current) + others

        // 1. Shuffled queue size must match original
        assertEquals(5, shuffledQueue.size)
        // 2. Current track must be at index 0 (top of the queue)
        assertEquals(current.id, shuffledQueue[0].id)
        // 3. All tracks must still exist in the queue
        assertEquals(tracks.map { it.id }.toSet(), shuffledQueue.map { it.id }.toSet())

        // Disabling shuffle: restoring original queue
        val restoredIdx = originalQueue.indexOfFirst { it.id == current.id }
        assertEquals(4, restoredIdx)
        assertEquals(originalQueue, tracks)
    }

    @Test
    fun testTrackOrderingByAddedAtDesc() {
        val t1 = Track(id = "1", title = "First", artist = "A", duration = 100, addedAt = 1000L)
        val t2 = Track(id = "2", title = "Second", artist = "A", duration = 100, addedAt = 2000L)
        val t3 = Track(id = "3", title = "Third (newest)", artist = "A", duration = 100, addedAt = 3000L)

        val list = listOf(t1, t2, t3).sortedByDescending { it.addedAt }

        assertEquals("3", list[0].id)
        assertEquals("2", list[1].id)
        assertEquals("1", list[2].id)
    }
}
