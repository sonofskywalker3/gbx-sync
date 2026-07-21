package com.jeff.gbxsync.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jeff.gbxsync.library.RomEntry
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "gbxsync")

/** App settings + per-game sync records + ROM index cache, all in DataStore. */
class SyncStateStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getRomFolderUri(): String? =
        context.dataStore.data.first()[KEY_ROM_FOLDER]

    suspend fun setRomFolderUri(uri: String) {
        context.dataStore.edit { it[KEY_ROM_FOLDER] = uri }
    }

    suspend fun getSyncRecords(): Map<String, SyncRecord> {
        val raw = context.dataStore.data.first()[KEY_SYNC_RECORDS] ?: return emptyMap()
        return runCatching {
            json.decodeFromString(ListSerializer(SyncRecord.serializer()), raw)
                .associateBy { it.gameKey }
        }.getOrDefault(emptyMap())
    }

    suspend fun putSyncRecord(record: SyncRecord) {
        val all = getSyncRecords().toMutableMap()
        all[record.gameKey] = record
        context.dataStore.edit {
            it[KEY_SYNC_RECORDS] =
                json.encodeToString(ListSerializer(SyncRecord.serializer()), all.values.toList())
        }
    }

    suspend fun getRomIndex(): List<RomEntry> {
        val raw = context.dataStore.data.first()[KEY_ROM_INDEX] ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(RomEntry.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    suspend fun putRomIndex(entries: List<RomEntry>) {
        context.dataStore.edit {
            it[KEY_ROM_INDEX] = json.encodeToString(ListSerializer(RomEntry.serializer()), entries)
        }
    }

    companion object {
        private val KEY_ROM_FOLDER = stringPreferencesKey("rom_folder_uri")
        private val KEY_SYNC_RECORDS = stringPreferencesKey("sync_records")
        private val KEY_ROM_INDEX = stringPreferencesKey("rom_index")
    }
}
