package com.jeff.gbxsync.sync

import kotlinx.serialization.Serializable

/**
 * Per-game sync record. Cartridges carry no timestamps, so direction is
 * decided by comparing both sides against the hash recorded at last sync.
 */
@Serializable
data class SyncRecord(
    val gameKey: String, // cart header SHA-256
    val lastSyncHash: String,
    val lastSyncTimeMs: Long,
)

sealed class SyncDecision {
    /** Both sides identical — nothing to do (state is refreshed). */
    object InSync : SyncDecision()

    /** No .sav file exists yet — cart is the only copy. */
    object NoFileYet : SyncDecision()

    /** File unchanged since last sync, cart changed — cart wins. */
    object CartChanged : SyncDecision()

    /** Cart unchanged since last sync, file changed — file wins. */
    object FileChanged : SyncDecision()

    /** Never synced before and the two sides differ — user must pick. */
    object NeverSynced : SyncDecision()

    /** Both sides changed since last sync — user must pick. */
    object BothChanged : SyncDecision()
}

object SyncEngine {
    fun decide(cartHash: String, fileHash: String?, record: SyncRecord?): SyncDecision = when {
        fileHash == null -> SyncDecision.NoFileYet
        cartHash == fileHash -> SyncDecision.InSync
        record == null -> SyncDecision.NeverSynced
        cartHash == record.lastSyncHash && fileHash != record.lastSyncHash -> SyncDecision.FileChanged
        fileHash == record.lastSyncHash && cartHash != record.lastSyncHash -> SyncDecision.CartChanged
        else -> SyncDecision.BothChanged
    }
}
