package com.dpcall.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.callLogDataStore: DataStore<Preferences> by preferencesDataStore(name = "call_log")

enum class CallOutcome { ANSWERED, MISSED, DECLINED, NOT_CONNECTED }

@Serializable
data class CallLogEntry(
    val code: String,
    /** Имя на момент звонка — на случай, если контакт потом удалят. */
    val name: String,
    val incoming: Boolean,
    val outcome: CallOutcome,
    val startedAt: Long,
    val durationSec: Long = 0
)

/** Журнал звонков. Хранится только на устройстве, последние [MAX_ENTRIES] записей. */
class CallLogRepository(context: Context) {
    private val context = context.applicationContext
    private val key = stringPreferencesKey("entries")
    private val json = Json { ignoreUnknownKeys = true }

    val entries: Flow<List<CallLogEntry>> = this.context.callLogDataStore.data.map { decode(it[key]) }

    private fun decode(raw: String?): List<CallLogEntry> = try {
        raw?.let { json.decodeFromString<List<CallLogEntry>>(it) } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun add(entry: CallLogEntry) {
        context.callLogDataStore.edit { prefs ->
            val updated = (listOf(entry) + decode(prefs[key])).take(MAX_ENTRIES)
            prefs[key] = json.encodeToString(updated)
        }
    }

    suspend fun clear() {
        context.callLogDataStore.edit { it.remove(key) }
    }

    private companion object {
        const val MAX_ENTRIES = 100
    }
}
