package com.dpsmpx.dpcall.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dpsmpx.dpcall.domain.Contact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.contactsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "contacts_prefs"
)

/** Контакт с таким кодом уже существует. */
class DuplicateContactException : IllegalStateException("Duplicate contact")

/**
 * Локальное хранилище контактов.
 * Список сериализуется в JSON и сохраняется в DataStore.
 */
class ContactsRepository(private val context: Context) {

    private val keyContacts = stringPreferencesKey("contacts_list")
    private val json = Json { ignoreUnknownKeys = true }

    val contactsFlow: Flow<List<Contact>> = context.contactsDataStore.data.map { prefs ->
        try {
            decode(prefs[keyContacts])
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** @throws Exception если сохранённый список не читается */
    private fun decode(raw: String?): List<Contact> =
        raw?.let { json.decodeFromString<List<Contact>>(it) } ?: emptyList()

    /**
     * Меняет список. Если сохранённые данные не читаются, правка отменяется:
     * иначе одна запись затёрла бы все контакты, которые ещё можно спасти.
     */
    private suspend fun update(transform: (List<Contact>) -> List<Contact>): Result<Unit> = try {
        context.contactsDataStore.edit { prefs ->
            prefs[keyContacts] = json.encodeToString(transform(decode(prefs[keyContacts])))
        }
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getAll(): List<Contact> = contactsFlow.first()

    suspend fun add(contact: Contact): Result<Unit> = update { current ->
        if (current.any { it.code.equals(contact.code, ignoreCase = true) }) throw DuplicateContactException()
        current + contact
    }

    /** Добавляет контакты из резервной копии; уже существующие коды не трогает. */
    suspend fun merge(contacts: List<Contact>): Result<Unit> = update { current ->
        val known = current.mapTo(HashSet()) { it.code.uppercase() }
        current + contacts.filter { known.add(it.code.uppercase()) }
    }

    suspend fun remove(code: String): Result<Unit> = update { current ->
        current.filterNot { it.code.equals(code, ignoreCase = true) }
    }

    suspend fun updateName(code: String, newName: String): Result<Unit> = update { current ->
        current.map { if (it.code.equals(code, ignoreCase = true)) it.copy(name = newName) else it }
    }
}
