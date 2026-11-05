package com.dc.andagent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for R54 KV namespace export/import (no Android runtime). */
class KvBackupTest {
    private val now = 1_700_000_000_000L

    private fun record(ns: String, key: String, value: kotlinx.serialization.json.JsonElement, exp: Long? = null) =
        KvStore.BackupRecord(ns, key, value, exp)

    private fun envelope(vararg records: KvStore.BackupRecord, scope: String = "all"): String =
        KvStore.backupEnvelope(scope, records.toList(), now)

    private fun assertBackupError(expectedFragment: String, json: String) {
        val error = assertThrows(IllegalArgumentException::class.java) { KvStore.parseBackup(json) }
        assertTrue("实际错误：${error.message}", error.message?.contains(expectedFragment) == true)
    }

    @Test fun exportImportRoundTripPreservesValuesAndTtl() {
        val records = listOf(
            record("notes", "a", JsonPrimitive("hello"), now + 60_000),
            record("notes", "b", buildJsonObject { put("n", 1); put("ok", true) }, null),
            record("sessions", "s_1", JsonPrimitive(true), now - 1),
        )
        val json = KvStore.backupEnvelope("all", records, now)

        val root = Json.parseToJsonElement(json) as JsonObject
        assertEquals(true, (root["ok"] as JsonPrimitive).content.toBoolean())
        assertEquals("all", (root["scope"] as JsonPrimitive).content)
        assertEquals(3, (root["count"] as JsonPrimitive).content.toInt())
        assertEquals(now, (root["exportedAt"] as JsonPrimitive).content.toLong())
        val namespaces = root["namespaces"] as JsonArray
        assertEquals(2, namespaces.size)
        assertEquals("notes", ((namespaces[0] as JsonObject)["namespace"] as JsonPrimitive).content)

        val parsed = KvStore.parseBackup(json)
        assertEquals(3, parsed.size)
        val byId = parsed.associateBy { "${it.namespace}:${it.key}" }
        assertEquals(JsonPrimitive("hello"), byId.getValue("notes:a").value)
        assertEquals(now + 60_000, byId.getValue("notes:a").expiresAt)
        assertEquals(buildJsonObject { put("n", 1); put("ok", true) }, byId.getValue("notes:b").value)
        assertNull(byId.getValue("notes:b").expiresAt)

        val plan = KvStore.planImport(json, KvStore.IMPORT_MODE_MERGE, emptyMap(), now)
        assertEquals(1, plan.namespaces)
        assertEquals(2, plan.imported)
        assertEquals(1, plan.skipped)
        assertEquals(now + 60_000, plan.writes.getValue("notes").getValue("a").expiresAt)
    }

    @Test fun invalidPayloadsAreRejected() {
        assertBackupError("不是合法 JSON", "not json")
        assertBackupError("JSON 对象", "[]")
        assertBackupError("namespaces", """{"ok":true}""")
        assertBackupError("namespaces 必须是数组", """{"namespaces":{}}""")
        assertBackupError("namespace 非法", """{"namespaces":[{"namespace":"a:b","items":{}}]}""")
        assertBackupError("缺少 items", """{"namespaces":[{"namespace":"notes"}]}""")
        assertBackupError("items 必须是对象", """{"namespaces":[{"namespace":"notes","items":[]}]}""")
        assertBackupError("键名非法", """{"namespaces":[{"namespace":"notes","items":{"a b":{"v":1}}}]}""")
        assertBackupError("包含 v", """{"namespaces":[{"namespace":"notes","items":{"k":1}}]}""")
        assertBackupError("缺少 v", """{"namespaces":[{"namespace":"notes","items":{"k":{"exp":1}}}]}""")
        assertBackupError("exp 必须", """{"namespaces":[{"namespace":"notes","items":{"k":{"v":1,"exp":"soon"}}}]}""")

        val oversized = JsonPrimitive("x".repeat(KvStore.MAX_VALUE_BYTES))
        assertBackupError("64KB", envelope(record("notes", "big", oversized)))
    }

    @Test fun mergeOverwritesWhileReplaceClearsTargetNamespace() {
        val payload = envelope(
            record("notes", "b", JsonPrimitive(2)),
            record("notes", "c", JsonPrimitive(3)),
            record("other", "z", JsonPrimitive(9)),
        )
        val existing = mapOf("notes" to setOf("a", "b"))

        val merge = KvStore.planImport(payload, KvStore.IMPORT_MODE_MERGE, existing, now)
        assertEquals(3, merge.imported)
        assertEquals(2, merge.namespaces)
        assertTrue(merge.clearedNamespaces.isEmpty())
        assertEquals(setOf("a", "b", "c"), existing.getValue("notes") + merge.writes.getValue("notes").keys)

        val replace = KvStore.planImport(payload, KvStore.IMPORT_MODE_REPLACE, existing, now)
        assertEquals(3, replace.imported)
        assertEquals(setOf("notes", "other"), replace.clearedNamespaces)
        assertEquals(setOf("b", "c"), replace.writes.getValue("notes").keys)
    }

    @Test fun replaceMayRewriteAFullNamespaceButMergeCannotGrowIt() {
        val existing = mapOf("notes" to (0 until KvStore.MAX_KEYS_PER_NAMESPACE).map { "old$it" }.toSet())
        val fresh = (0 until KvStore.MAX_KEYS_PER_NAMESPACE).map { record("notes", "new$it", JsonPrimitive(it)) }
        val payload = KvStore.backupEnvelope("notes", fresh, now)

        val mergeError = assertThrows(IllegalArgumentException::class.java) {
            KvStore.planImport(payload, KvStore.IMPORT_MODE_MERGE, existing, now)
        }
        assertTrue(mergeError.message?.contains("512") == true)

        val replace = KvStore.planImport(payload, KvStore.IMPORT_MODE_REPLACE, existing, now)
        assertEquals(KvStore.MAX_KEYS_PER_NAMESPACE, replace.imported)
        assertEquals(setOf("notes"), replace.clearedNamespaces)
    }

    @Test fun quotaOverflowsAreErrors() {
        val tooManyKeys = KvStore.backupEnvelope(
            "notes",
            (0..KvStore.MAX_KEYS_PER_NAMESPACE).map { record("notes", "k$it", JsonPrimitive(it)) },
            now,
        )
        val keyError = assertThrows(IllegalArgumentException::class.java) {
            KvStore.planImport(tooManyKeys, KvStore.IMPORT_MODE_MERGE, emptyMap(), now)
        }
        assertTrue(keyError.message?.contains("512") == true)

        val tooManyNamespaces = KvStore.backupEnvelope(
            "all",
            (0..KvStore.MAX_NAMESPACES).map { record("ns$it", "k", JsonPrimitive(it)) },
            now,
        )
        val nsError = assertThrows(IllegalArgumentException::class.java) {
            KvStore.planImport(tooManyNamespaces, KvStore.IMPORT_MODE_MERGE, emptyMap(), now)
        }
        assertTrue(nsError.message?.contains("64") == true)
    }

    @Test fun expiredEntriesAreSkippedAndCounted() {
        val payload = envelope(
            record("notes", "live", JsonPrimitive(1), now + 1),
            record("notes", "dead1", JsonPrimitive(2), now - 1),
            record("notes", "dead2", JsonPrimitive(3), now),
        )
        val plan = KvStore.planImport(payload, KvStore.IMPORT_MODE_MERGE, emptyMap(), now)
        assertEquals(1, plan.imported)
        assertEquals(2, plan.skipped)
        assertEquals(setOf("live"), plan.writes.getValue("notes").keys)

        val allExpired = KvStore.planImport(
            envelope(record("notes", "dead", JsonPrimitive(1), now - 5)),
            KvStore.IMPORT_MODE_MERGE,
            emptyMap(),
            now,
        )
        assertEquals(0, allExpired.namespaces)
        assertEquals(0, allExpired.imported)
        assertEquals(1, allExpired.skipped)
        assertTrue(allExpired.writes.isEmpty())
    }

    @Test fun modeAndConfirmRules() {
        assertEquals(KvStore.IMPORT_MODE_MERGE, KvStore.normalizeImportMode(null))
        assertEquals(KvStore.IMPORT_MODE_MERGE, KvStore.normalizeImportMode("  "))
        assertEquals(KvStore.IMPORT_MODE_REPLACE, KvStore.normalizeImportMode("REPLACE"))

        assertNull(KvStore.importModeError(KvStore.IMPORT_MODE_MERGE))
        assertNull(KvStore.importModeError(KvStore.IMPORT_MODE_REPLACE))
        assertNotNull(KvStore.importModeError("overwrite"))

        assertNull(KvStore.importConfirmError(KvStore.IMPORT_MODE_MERGE, null))
        assertNotNull(KvStore.importConfirmError(KvStore.IMPORT_MODE_REPLACE, null))
        assertNotNull(KvStore.importConfirmError(KvStore.IMPORT_MODE_REPLACE, false))
        assertNull(KvStore.importConfirmError(KvStore.IMPORT_MODE_REPLACE, true))

        val badMode = assertThrows(IllegalArgumentException::class.java) {
            KvStore.planImport(envelope(record("notes", "k", JsonPrimitive(1))), "overwrite", emptyMap(), now)
        }
        assertTrue(badMode.message?.contains("merge") == true)
    }

    @Test fun exportSizeCeilingOnlyAppliesToAllScope() {
        assertNull(KvStore.exportSizeError("all", KvStore.MAX_EXPORT_BYTES))
        assertNotNull(KvStore.exportSizeError("all", KvStore.MAX_EXPORT_BYTES + 1))
        assertNull(KvStore.exportSizeError("notes", KvStore.MAX_EXPORT_BYTES + 1))
    }
}
