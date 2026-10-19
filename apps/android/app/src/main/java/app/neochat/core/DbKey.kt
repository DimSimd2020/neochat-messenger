package app.neochat.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The 32-byte database key is random and stored only wrapped by a non-exportable
 * AES-GCM key in Android Keystore (hardware-backed where available).
 */
object DbKey {
    private const val ALIAS = "neochat-db-wrap"
    private const val PREFS = "neochat-keys"

    fun loadOrCreate(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sealed = prefs.getString("db_key", null)
        if (sealed != null) {
            val raw = Base64.decode(sealed, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, raw, 0, 12))
            val key = cipher.doFinal(raw, 12, raw.size - 12)
            return hex(key).also { key.fill(0) }
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        val out = cipher.iv + cipher.doFinal(key)
        // commit(): the key must be on disk before the database is created with it.
        prefs.edit().putString("db_key", Base64.encodeToString(out, Base64.NO_WRAP)).commit()
        return hex(key).also { key.fill(0) }
    }

    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
