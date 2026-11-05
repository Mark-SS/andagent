package com.dc.andagent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
