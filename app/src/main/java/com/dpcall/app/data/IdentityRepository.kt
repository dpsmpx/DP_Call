package com.dpcall.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dpcall.app.domain.Identity
import com.dpcall.app.domain.IdentityGenerator
import com.dpcall.app.domain.IdentityKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.identityDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "identity_prefs"
)

/**
 * Хранит ключи пользователя.
 *
 * Секретный ключ лежит в DataStore зашифрованным ключом из Android Keystore,
 * то есть прочитать его можно только на этом устройстве и только этому приложению.
 * При первом запуске (или после обновления со старого формата кода) создаётся
 * новая пара ключей.
 */
class IdentityRepository(context: Context) {

    private val context = context.applicationContext
    private val keyIdentity = stringPreferencesKey("user_identity")
    private val keySecret = stringPreferencesKey("identity_secret")

    /** Код пользователя; null, пока ключи не созданы. */
    val identityFlow: Flow<Identity?> = this.context.identityDataStore.data.map { prefs ->
        val code = prefs[keyIdentity]
        if (prefs[keySecret] != null && code != null && code.length == IdentityGenerator.CODE_LENGTH) {
            Identity(code)
        } else {
            null
        }
    }

    suspend fun getOrCreate(): Identity = loadKeys().identity

    /** Возвращает ключи, создавая их при необходимости. */
    suspend fun loadKeys(): IdentityKeys = mutex.withLock {
        cached?.let { return it }

        val stored = context.identityDataStore.data.first()[keySecret]
        val existing = stored?.let { blob ->
            try {
                IdentityGenerator.fromSecret(unwrap(Base64.decode(blob, Base64.NO_WRAP)))
            } catch (e: Exception) {
                Log.w(TAG, "Stored identity is unreadable, creating a new one", e)
                null
            }
        }
        val keys = existing ?: IdentityGenerator.generate()
        if (existing == null || context.identityDataStore.data.first()[keyIdentity] != keys.code) {
            val wrapped = Base64.encodeToString(wrap(keys.secret), Base64.NO_WRAP)
            context.identityDataStore.edit { prefs ->
                prefs[keySecret] = wrapped
                prefs[keyIdentity] = keys.code
            }
        }
        cached = keys
        keys
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun wrap(secret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return cipher.iv + cipher.doFinal(secret)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, blob, 0, 12))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    private companion object {
        const val TAG = "IdentityRepository"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "dpcall_identity_wrap"
        val mutex = Mutex()

        @Volatile
        var cached: IdentityKeys? = null
    }
}
