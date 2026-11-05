package com.dc.andagent.tools

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Pure-JVM tests for the KV store's validation/TTL/JSON helpers (no Android runtime). */
class KvStoreTest {
    private val now = 1_700_000_000_000L

    @Test fun nameValidation() {
        assertTrue(KvStore.isValidName("a"))
        assertTrue(KvStore.isValidName("default"))
        assertTrue(KvStore.isValidName("User.data-1_2"))
        assertTrue(KvStore.isValidName("a".repeat(64)))
        assertFalse(KvStore.isValidName(""))
        assertFalse(KvStore.isValidName("a".repeat(65)))
        assertFalse(KvStore.isValidName("a:b"))
        assertFalse(KvStore.isValidName("a b"))
        assertFalse(KvStore.isValidName("中文"))
        assertFalse(KvStore.isValidName("a/b"))
        assertFalse(KvStore.isValidName(null))
    }

    @Test fun namespaceDefaults() {
        assertEquals("default", KvStore.namespaceOrDefault(null))
        assertEquals("default", KvStore.namespaceOrDefault("  "))
        assertEquals("notes", KvStore.namespaceOrDefault("notes"))
    }

    @Test fun keyCodec() {
        assertEquals("notes:title", KvStore.encode("notes", "title"))
        assertEquals("notes" to "title", KvStore.decode("notes:title"))
        assertEquals("notes" to "a:b", KvStore.decode("notes:a:b"))
        assertNull(KvStore.decode("nocolon"))
        assertNull(KvStore.decode(":key"))
        assertNull(KvStore.decode("ns:"))
    }

    @Test fun ttlExpiry() {
        assertFalse(KvStore.isExpired(null, now))
        assertFalse(KvStore.isExpired(now + 1, now))
        assertTrue(KvStore.isExpired(now, now))
        assertTrue(KvStore.isExpired(now - 1, now))

        assertEquals(now + 500, KvStore.expiryFrom(500, now))
        assertNull(KvStore.expiryFrom(null, now))

        assertNull(KvStore.ttlRemainingSeconds(null, now))
        assertEquals(1L, KvStore.ttlRemainingSeconds(now + 1, now))
        assertEquals(2L, KvStore.ttlRemainingSeconds(now + 1001, now))
        assertEquals(0L, KvStore.ttlRemainingSeconds(now - 500, now))
    }

    @Test fun ttlValidation() {
        assertNull(KvStore.ttlError(null))
        assertNull(KvStore.ttlError(1))
        assertNotNull(KvStore.ttlError(0))
        assertNotNull(KvStore.ttlError(-5))
    }

    @Test fun wrapUnwrapRoundTrip() {
        val cases = listOf<kotlinx.serialization.json.JsonElement>(
            JsonPrimitive("hello"),
            JsonPrimitive(42),
            JsonPrimitive(3.5),
            JsonPrimitive(true),
            JsonNull,
            JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("two"))),
            buildJsonObject { put("a", 1); put("b", "text") },
        )
        for (value in cases) {
            val raw = KvStore.wrap(value, null)
            assertEquals(value, KvStore.unwrap(raw))
            assertNull(KvStore.expiryOf(raw))
        }
        val expiring = KvStore.wrap(JsonPrimitive("x"), now + 60_000)
        assertEquals("x", (KvStore.unwrap(expiring) as JsonPrimitive).content)
        assertEquals(now + 60_000, KvStore.expiryOf(expiring))
    }

    @Test fun unwrapRejectsCorruptData() {
        assertNull(KvStore.unwrap("not json"))
        assertNull(KvStore.unwrap("""{"exp":1}"""))
        assertNull(KvStore.expiryOf("not json"))
        assertNull(KvStore.expiryOf("""{"v":1}"""))
    }

    @Test fun valueSizeLimit() {
        assertFalse(KvStore.exceedsValueLimit("x".repeat(KvStore.MAX_VALUE_BYTES)))
        assertTrue(KvStore.exceedsValueLimit("x".repeat(KvStore.MAX_VALUE_BYTES + 1)))
        assertEquals(KvStore.MAX_VALUE_BYTES, KvStore.jsonByteSize("中".repeat(21845) + "a"))
        assertFalse(KvStore.exceedsValueLimit("中".repeat(21845)))
        assertFalse(KvStore.exceedsValueLimit("中".repeat(21845) + "a"))
        assertTrue(KvStore.exceedsValueLimit("中".repeat(21846)))
        assertEquals(KvStore.MAX_VALUE_BYTES + 2, KvStore.jsonByteSize("中".repeat(21846)))
    }

    @Test fun capacityLimits() {
        assertNull(KvStore.capacityError(namespaceCount = 10, keyCount = 10, isNewNamespace = false, newKeys = 2))
        assertNull(KvStore.capacityError(namespaceCount = 63, keyCount = 0, isNewNamespace = true, newKeys = 1))
        assertNotNull(KvStore.capacityError(namespaceCount = 64, keyCount = 0, isNewNamespace = true, newKeys = 1))
        assertNull(KvStore.capacityError(namespaceCount = 10, keyCount = 511, isNewNamespace = false, newKeys = 1))
        assertNotNull(KvStore.capacityError(namespaceCount = 10, keyCount = 512, isNewNamespace = false, newKeys = 1))
        assertNull(KvStore.capacityError(namespaceCount = 10, keyCount = 512, isNewNamespace = false, newKeys = 0))
    }

    @Test fun clearRequiresConfirm() {
        assertEquals("ERROR: kv_clear 需要 confirm=true", KvStore.clearConfirmError(null))
        assertEquals("ERROR: kv_clear 需要 confirm=true", KvStore.clearConfirmError(false))
        assertNull(KvStore.clearConfirmError(true))
    }

    /* ----------------------- R55 secure namespace tests ----------------------- */

    private fun store(prefs: FakePrefs, cipher: KvCipher) = KvStore(prefs, cipher)

    @Test fun secureWritesAreEncryptedAtRest() {
        val prefs = FakePrefs()
        val store = store(prefs, FakeCipher)
        store.set(KvStore.SECURE_NAMESPACE, "token", JsonPrimitive("s3cret"), now = now)

        val raw = prefs.getString("secure:token", null)
        assertNotNull(raw)
        assertTrue(raw!!.startsWith(KvStore.SECURE_PREFIX))
        assertFalse(raw.contains("s3cret"))
        // The payload really is the encrypted envelope, not a wrapped plaintext.
        val envelope = FakeCipher.decrypt(raw.removePrefix(KvStore.SECURE_PREFIX))
        assertEquals(JsonPrimitive("s3cret"), KvStore.unwrap(envelope!!))

        // Other namespaces stay plaintext.
        store.set("notes", "title", JsonPrimitive("hello"), now = now)
        val plain = prefs.getString("notes:title", null)!!
        assertFalse(plain.startsWith(KvStore.SECURE_PREFIX))
        assertEquals(JsonPrimitive("hello"), KvStore.unwrap(plain))
    }

    @Test fun secureReadRoundTripHonoursTtl() {
        val prefs = FakePrefs()
        val store = store(prefs, FakeCipher)
        store.set(KvStore.SECURE_NAMESPACE, "token", buildJsonObject { put("k", "v") }, ttlMs = 60_000, now = now)

        val live = store.read(KvStore.SECURE_NAMESPACE, "token", now + 1_000)
        assertNotNull(live)
        assertEquals(buildJsonObject { put("k", "v") }, live!!.value)
        assertEquals(now + 60_000, live.expiresAt)

        assertNull(store.read(KvStore.SECURE_NAMESPACE, "token", now + 60_000))
    }

    @Test fun exportAllNeverContainsSecureButExplicitSecureExportWorks() {
        val prefs = FakePrefs()
        val store = store(prefs, FakeCipher)
        store.set("notes", "a", JsonPrimitive(1), now = now)
        store.set(KvStore.SECURE_NAMESPACE, "token", JsonPrimitive("s3cret"), now = now)

        val all = Json.parseToJsonElement(store.export(now = now)) as JsonObject
        assertEquals(1, (all["count"] as JsonPrimitive).content.toInt())
        assertFalse(all.toString().contains("secure"))
        assertFalse(all.toString().contains("s3cret"))

        val implicitSecure = Json.parseToJsonElement(store.export(KvStore.SECURE_NAMESPACE, now = now)) as JsonObject
        assertEquals(0, (implicitSecure["count"] as JsonPrimitive).content.toInt())
        assertEquals(KvStore.SECURE_EXPORT_HINT, (implicitSecure["message"] as JsonPrimitive).content)

        val explicitSecure = Json.parseToJsonElement(
            store.export(KvStore.SECURE_NAMESPACE, includeSecure = true, now = now),
        ) as JsonObject
        assertEquals(1, (explicitSecure["count"] as JsonPrimitive).content.toInt())
        // Even an explicitly exported secure payload can never be imported back.
        assertNull(runCatching { KvStore.parseBackup(explicitSecure.toString()) }.getOrNull())
    }

    @Test fun importRejectsSecureRecordsWithoutPollutingOtherNamespaces() {
        val prefs = FakePrefs()
        val store = store(prefs, FakeCipher)
        store.set("notes", "keep", JsonPrimitive("old"), now = now)

        val payload = KvStore.backupEnvelope(
            "all",
            listOf(
                KvStore.BackupRecord("notes", "a", JsonPrimitive("new"), null),
                KvStore.BackupRecord(KvStore.SECURE_NAMESPACE, "token", JsonPrimitive("evil"), null),
            ),
            now,
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            store.importJson(payload, now = now)
        }
        assertTrue(error.message?.contains("secure 命名空间不允许导入备份") == true)

        // Nothing was written: the pre-existing key survives untouched.
        assertEquals(JsonPrimitive("old"), store.get("notes", "keep", now))
        assertNull(store.get("notes", "a", now))
        assertNull(store.get(KvStore.SECURE_NAMESPACE, "token", now))
    }

    @Test fun brokenCipherMakesSecureKeysReadAsMissing() {
        val prefs = FakePrefs()
        store(prefs, FakeCipher).set(KvStore.SECURE_NAMESPACE, "token", JsonPrimitive("s3cret"), now = now)

        val broken = store(prefs, BrokenCipher)
        assertNull(broken.read(KvStore.SECURE_NAMESPACE, "token", now))
        assertTrue(broken.keys(KvStore.SECURE_NAMESPACE, now = now).isEmpty())

        val error = assertThrows(IllegalArgumentException::class.java) {
            broken.set(KvStore.SECURE_NAMESPACE, "other", JsonPrimitive(1), now = now)
        }
        assertEquals("secure 命名空间加密初始化失败", error.message)
    }

    @Test fun legacyPlaintextSecureEntryMigratesInPlaceOnRead() {
        val prefs = FakePrefs()
        val store = store(prefs, FakeCipher)
        // Simulate a pre-R55 record: plaintext envelope under the secure namespace.
        prefs.edit().putString("secure:legacy", KvStore.wrap(JsonPrimitive("old-s3cret"), null)).commit()

        // Read path serves the value and re-encrypts the record on disk.
        val entry = store.read(KvStore.SECURE_NAMESPACE, "legacy", now)
        assertEquals(JsonPrimitive("old-s3cret"), entry?.value)

        val migrated = prefs.getString("secure:legacy", null)
        assertNotNull(migrated)
        assertTrue(migrated!!.startsWith(KvStore.SECURE_PREFIX))
        assertFalse(migrated.contains("old-s3cret"))

        // A second read decrypts from the migrated ciphertext: no plaintext path left.
        assertEquals(JsonPrimitive("old-s3cret"), store.read(KvStore.SECURE_NAMESPACE, "legacy", now)?.value)

        // Migration never breaks enumeration (keys/namespaces still see the key).
        assertEquals(listOf("legacy"), store.keys(KvStore.SECURE_NAMESPACE, now = now).map { it.key })
    }

    @Test fun brokenCipherLeavesLegacyPlaintextReadable() {
        val prefs = FakePrefs()
        prefs.edit().putString("secure:legacy", KvStore.wrap(JsonPrimitive("old-s3cret"), null)).commit()

        val broken = store(prefs, BrokenCipher)
        // Keystore is broken: plaintext is still served (no bricking) and stays put on disk.
        assertEquals(JsonPrimitive("old-s3cret"), broken.read(KvStore.SECURE_NAMESPACE, "legacy", now)?.value)
        assertFalse(prefs.getString("secure:legacy", null)!!.startsWith(KvStore.SECURE_PREFIX))
    }

    /** In-memory SharedPreferences: enough surface for KvStore, no Android runtime. */
    private class FakePrefs : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            values[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(values)
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }

    private class FakeEditor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private val removals = linkedSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            apply { pending[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { pending[key!!] = values }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
        override fun remove(key: String?): SharedPreferences.Editor = apply { removals += key!! }
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearAll) values.clear()
            removals.forEach { values.remove(it) }
            values.putAll(pending)
        }
    }

    /** Deterministic reversible cipher; the `enc1:` prefix is added by KvStore. */
    private object FakeCipher : KvCipher {
        override fun encrypt(plaintext: String): String =
            "iv:" + Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))

        override fun decrypt(payload: String): String? =
            runCatching { String(Base64.getDecoder().decode(payload.removePrefix("iv:")), Charsets.UTF_8) }.getOrNull()
    }

    /** Simulates a Keystore that can neither decrypt nor encrypt. */
    private object BrokenCipher : KvCipher {
        override fun encrypt(plaintext: String): String = throw IllegalStateException("keystore unavailable")
        override fun decrypt(payload: String): String? = null
    }
}
