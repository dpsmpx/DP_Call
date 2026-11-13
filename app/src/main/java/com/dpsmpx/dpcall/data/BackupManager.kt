package com.dpsmpx.dpcall.data

import android.content.Context
import android.net.Uri
import com.dpsmpx.dpcall.call.CallManager
import com.dpsmpx.dpcall.call.RelaySignaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Сохранение и восстановление резервной копии: файлы, хранилища, перезапуск связи. */
object BackupManager {

    /** Шифрует ключ и контакты паролем и записывает в выбранный пользователем файл. */
    suspend fun export(context: Context, target: Uri, password: CharArray) {
        val app = context.applicationContext
        val secret = IdentityRepository(app).exportSecret()
        val contacts = ContactsRepository(app).getAll()
        withContext(Dispatchers.Default) {
            val bytes = try {
                BackupCodec.encrypt(secret, contacts, password)
            } finally {
                secret.fill(0)
            }
            withContext(Dispatchers.IO) {
                // "wt": если файл уже был и длиннее, хвост старого содержимого не останется.
                (app.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Cannot open the file"))
                    .use { it.write(bytes) }
            }
        }
    }

    /** @throws BackupFormatException если файл слишком велик, чтобы быть копией */
    suspend fun read(context: Context, source: Uri): ByteArray = withContext(Dispatchers.IO) {
        val input = context.applicationContext.contentResolver.openInputStream(source)
            ?: throw IOException("Cannot open the file")
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > BackupCodec.MAX_FILE_BYTES) throw BackupFormatException("File is too large")
            }
            out.toByteArray()
        }
    }

    suspend fun decrypt(file: ByteArray, password: CharArray): BackupContent =
        withContext(Dispatchers.Default) { BackupCodec.decrypt(file, password) }

    /**
     * Заменяет код пользователя кодом из копии и добавляет её контакты к существующим.
     * Связь с релеями перезапускается: «почтовый ящик» зависит от ключа.
     */
    suspend fun restore(context: Context, content: BackupContent) {
        val app = context.applicationContext
        IdentityRepository(app).replace(content.keys)
        ContactsRepository(app).merge(content.contacts).getOrThrow()
        CallManager.onIdentityChanged()
        RelaySignaling.restart(app)
    }
}
