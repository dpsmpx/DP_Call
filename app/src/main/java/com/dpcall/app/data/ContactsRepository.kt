package com.dpcall.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dpcall.app.domain.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.contactsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "contacts_prefs"
)

/**
 * Локальное хранилище контактов.
 * Список сериализуется в JSON и сохраняется в DataStore.
 */
class ContactsRepository(private val context: Context) {

    private val keyContacts = stringPreferencesKey("contacts_list")
    private val json = Json { ignoreUnknownKeys = true }

    val contactsFlow: Flow<List<Contact>> = context.contactsDataStore.data.map { prefs ->
        val raw = prefs[keyContacts] ?: return@map emptyList()
        try {
            json.decodeFromString<List<Contact>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun add(contact: Contact): Result<Unit> {
        return try {
            context.contactsDataStore.edit { prefs ->
                val current = prefs[keyContacts]?.let {
                    try {
                        json.decodeFromString<List<Contact>>(it)
                    } catch (_: Exception) {
                        emptyList()
                    }
                } ?: emptyList()

                if (current.any { it.code.equals(contact.code, ignoreCase = true) }) {
                    throw IllegalStateException("Контакт с таким кодом уже есть")
                }

                val updated = current + contact
                prefs[keyContacts] = json.encodeToString(updated)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun remove(code: String): Result<Unit> {
        return try {
            context.contactsDataStore.edit { prefs ->
                val current = prefs[keyContacts]?.let {
                    try {
                        json.decodeFromString<List<Contact>>(it)
                    } catch (_: Exception) {
                        emptyList()
                    }
                } ?: emptyList()

                val updated = current.filterNot { it.code.equals(code, ignoreCase = true) }
                prefs[keyContacts] = json.encodeToString(updated)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateName(code: String, newName: String): Result<Unit> {
        return try {
            context.contactsDataStore.edit { prefs ->
                val current = prefs[keyContacts]?.let {
                    try {
                        json.decodeFromString<List<Contact>>(it)
                    } catch (_: Exception) {
                        emptyList()
                    }
                } ?: emptyList()

                val updated = current.map {
                    if (it.code.equals(code, ignoreCase = true)) it.copy(name = newName)
                    else it
                }
                prefs[keyContacts] = json.encodeToString(updated)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
