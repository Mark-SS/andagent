package com.dc.andagent.tools

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Pluggable at-rest cipher for the `secure` KV namespace.
 *
 * [encrypt] returns an opaque, self-describing payload; [decrypt] returns null
 * for malformed or undecryptable payloads and must never throw. That contract
 * lets [KvStore] treat a broken Keystore as "key not found" on the read path.
 */
interface KvCipher {
    /** Encrypts [plaintext] and returns the transport-safe payload. */
    fun encrypt(plaintext: String): String

    /** Decrypts a payload produced by [encrypt]; null when it cannot be read. */
    fun decrypt(payload: String): String?
}

/**
 * Android Keystore-backed AES-256-GCM cipher (platform API, no dependencies).
 *
 * The key lives in the android keystore under [ALIAS] and is generated on
 * first use. Every [encrypt] call uses a fresh random 12-byte IV (GCM default),
 * so repeated writes never reuse an IV. The payload format is
 * `<IV base64>:<ciphertext base64>`; the `enc1:` storage prefix lives in
 * [KvStore.SECURE_PREFIX] so a future key rotation can introduce `enc2:`.
 */
class KeystoreCipher : KvCipher {
    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return encoder.encodeToString(iv) + ":" + encoder.encodeToString(ciphertext)
    }

    override fun decrypt(payload: String): String? {
        return try {
            val separator = payload.indexOf(':')
            if (separator <= 0 || separator == payload.lastIndex) return null
            val iv = decoder.decode(payload.substring(0, separator))
            val ciphertext = decoder.decode(payload.substring(separator + 1))
            if (iv.isEmpty() || ciphertext.isEmpty()) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Returns the key for [ALIAS], creating it on first use. Creation is
     * serialized process-wide via a companion lock (KvTools instantiates one
     * cipher per tool, so per-instance synchronization is not enough), and the
     * existence check is repeated inside the critical section: two first-time
     * writers must never both generate and end up with ciphertext bound to a
     * stale alias.
     */
    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ALIAS = "andagent_kv_secure"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        /** Process-wide gate for first-time key creation (see [key]). */
        private val KEY_LOCK = Any()

        // java.util.Base64 (API 26+) keeps the cipher usable without android.util stubs.
        private val encoder = Base64.getEncoder()
        private val decoder = Base64.getDecoder()
    }
}
