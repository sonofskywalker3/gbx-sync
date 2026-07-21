package com.jeff.gbxsync

import com.jeff.gbxsync.sync.SyncDecision
import com.jeff.gbxsync.sync.SyncEngine
import com.jeff.gbxsync.sync.SyncRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncEngineTest {

    private val record = SyncRecord(gameKey = "k", lastSyncHash = "old", lastSyncTimeMs = 0)

    @Test
    fun noFileMeansCartIsOnlyCopy() {
        assertEquals(SyncDecision.NoFileYet, SyncEngine.decide("a", null, null))
    }

    @Test
    fun identicalSidesAreInSync() {
        assertEquals(SyncDecision.InSync, SyncEngine.decide("a", "a", null))
        assertEquals(SyncDecision.InSync, SyncEngine.decide("a", "a", record))
    }

    @Test
    fun differingSidesWithoutHistoryIsConflict() {
        assertEquals(SyncDecision.NeverSynced, SyncEngine.decide("a", "b", null))
    }

    @Test
    fun onlyCartChangedWinsForCart() {
        assertEquals(SyncDecision.CartChanged, SyncEngine.decide("new", "old", record))
    }

    @Test
    fun onlyFileChangedWinsForFile() {
        assertEquals(SyncDecision.FileChanged, SyncEngine.decide("old", "new", record))
    }

    @Test
    fun bothChangedIsConflict() {
        assertEquals(SyncDecision.BothChanged, SyncEngine.decide("new1", "new2", record))
    }
}
