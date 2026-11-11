package com.dpcall.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dpcall.app.domain.Identity
import com.dpcall.app.domain.IdentityGenerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.identityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "identity_prefs"
)

/**
 * Хранит и предоставляет локальный identity пользователя.
 * При первом обращении генерирует новый, если его ещё нет.
 */
class IdentityRepository(private val context: Context) {

    private val keyIdentity = stringPreferencesKey("user_identity")

    val identityFlow: Flow<Identity?> = context.identityDataStore.data.map { prefs ->
        prefs[keyIdentity]?.let { Identity(it) }
    }

    /**
     * Возвращает существующий identity или создаёт новый.
     */
    suspend fun getOrCreate(): Identity {
        val existing = identityFlow.first()
        if (existing != null) return existing

        val generated = IdentityGenerator.generate()
        context.identityDataStore.edit { prefs ->
            prefs[keyIdentity] = generated.value
        }
        return generated
    }
}
