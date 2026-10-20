package dev.pranav.applock.features.faceauth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores enrolled face embeddings encrypted with an AES-256-GCM key that lives in the
 * Android Keystore (the key never leaves secure hardware where available).
 *
 * File layout: [ivLen:int][iv][ciphertext]. Plaintext: [version:int][count:int][dim:int][floats...]
 * Kept in noBackupFilesDir so templates aren't copied by Auto Backup.
 */
class FaceTemplateStore(context: Context) {

    private val file = File(context.noBackupFilesDir, FILE_NAME)

    fun hasTemplates(): Boolean = file.exists() && file.length() > 0

    fun save(templates: List<FloatArray>) {
        require(templates.isNotEmpty()) { "Nothing to save" }
        val dim = templates[0].size
        require(templates.all { it.size == dim }) { "Mixed embedding sizes" }

        val plain = ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).use { out ->
                out.writeInt(FORMAT_VERSION)
                out.writeInt(templates.size)
                out.writeInt(dim)
                templates.forEach { t -> t.forEach(out::writeFloat) }
            }
        }.toByteArray()

        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain)
        plain.fill(0)

        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        DataOutputStream(tmp.outputStream()).use { out ->
            out.writeInt(iv.size)
            out.write(iv)
            out.write(sealed)
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Couldn't write face templates")
        }
    }

    /** Returns the enrolled templates, or an empty list if none exist or they can't be decrypted. */
    fun load(): List<FloatArray> {
        if (!hasTemplates()) return emptyList()
        return try {
            val bytes = file.readBytes()
            val input = DataInputStream(ByteArrayInputStream(bytes))
            val iv = ByteArray(input.readInt()).also(input::readFully)
            val sealed = input.readBytes()

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = cipher.doFinal(sealed)

            DataInputStream(ByteArrayInputStream(plain)).use { d ->
                check(d.readInt() == FORMAT_VERSION) { "Unknown template format" }
                val count = d.readInt()
                val dim = d.readInt()
                List(count) { FloatArray(dim) { d.readFloat() } }
            }.also { plain.fill(0) }
        } catch (e: Exception) {
            // Corrupt file or invalidated key: treat as not enrolled so the user re-enrolls.
            clear()
            emptyList()
        }
    }

    fun clear() {
        file.delete()
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val FILE_NAME = "face_templates.bin"
        const val KEY_ALIAS = "applock_face_templates_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val FORMAT_VERSION = 1
    }
}
