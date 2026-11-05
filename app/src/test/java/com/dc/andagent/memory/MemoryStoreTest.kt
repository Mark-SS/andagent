package com.dc.andagent.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryStoreTest {
    private var raw: String? = null
    private var time = 1_700_000_000_000L
    private fun store() = MemoryStore({ raw }, { raw = it }, { ++time })

    @Test fun instructionDefaultsToCore() { assertTrue(store().add("重要规则", "instruction").core) }
    @Test fun preferenceCanBeCore() { assertTrue(store().add("喜欢蓝色", "preference", true).core) }
    @Test fun factDefaultsToArchival() { assertFalse(store().add("住在上海").core) }
    @Test fun coreFullReportsError() {
        val s = store(); repeat(10) { s.add("core $it", core = true) }
        assertThrows(IllegalStateException::class.java) { s.add("eleventh", core = true) }
        assertEquals(10, s.stats().first)
    }
    @Test fun coreNeverEvictedByArchival() {
        val s = store(); s.add("permanent", core = true)
        repeat(201) { s.add("archive $it") }
        assertEquals(1, s.stats().first); assertEquals(200, s.stats().second)
    }
    @Test fun fullArchivalEvictsOldestAccessedNotNewcomer() {
        val s = store()
        repeat(200) { s.add(if (it == 0) "unique oldest archive" else "archive $it") }
        s.search("unique oldest") // warm up only the oldest entry
        val kept = s.add("brand new memory")
        assertEquals(200, s.stats().second)
        assertTrue(s.getAll().any { it.id == kept.id })
        assertTrue(s.getAll().none { it.content == "archive 1" }) // coldest entry evicted instead
    }
    @Test fun corruptEntryLeavesStateUntouched() {
        raw = """{"entries":[{"id":"a","content":"good"},{"id":"b"}],"history":[]}""" // second entry lacks fields
        val s = store()
        assertEquals(0, s.getAll().size) // half-loaded data must not leak into state
        val added = s.add("first save after corruption")
        assertFalse(raw!!.contains(""""id":"a"""")) // persist() rewrote from clean state, dropped corrupt payload
        assertTrue(s.getAll().map { it.content }.contains("first save after corruption"))
        assertEquals(added.id, s.getAll().first().id)
    }
    @Test fun duplicateNormalizesCaseAndSpace() {
        val s = store(); val a = s.add("  HELLO   world "); val b = s.add("hello world")
        assertEquals(a.id, b.id); assertEquals(1, s.stats().second)
    }
    @Test fun duplicateCanPromoteToCore() {
        val s = store(); val old = s.add("important", "preference")
        val updated = s.add("IMPORTANT", "preference", true)
        assertEquals(old.id, updated.id); assertTrue(updated.core); assertEquals(0, s.stats().second)
    }
    @Test fun clearingAllAlsoClearsHistory() {
        val s = store(); s.add("private"); s.deleteAll()
        assertTrue(s.history().isEmpty()); assertEquals(0, s.getAll().size)
    }
    @Test fun searchPrefersMoreMatchedWords() {
        val s = store(); s.add("tea"); val best = s.add("tea coffee")
        assertEquals(best.id, s.search("tea coffee").first().id)
    }
    @Test fun searchPrefersHitsOnTie() {
        val s = store(); val first = s.add("tea first"); s.add("tea second")
        repeat(2) { s.search("first") }
        assertEquals(first.id, s.search("tea").first().id)
    }
    @Test fun updateSelfEditingCore() {
        val s = store(); val old = s.add("old rule", core = true)
        assertEquals("new rule", s.update(old.id, "new rule").content)
        assertTrue(s.prompt("anything").contains("new rule"))
    }
    @Test fun deleteThenReuseCoreSlot() {
        val s = store(); val first = s.add("first", core = true)
        repeat(9) { s.add("rule $it", core = true) }
        assertTrue(s.delete(first.id)); assertTrue(s.add("replacement", core = true).core)
    }
    @Test fun persistsAcrossInstances() { val s = store(); s.add("saved"); assertEquals("saved", store().getAll().first().content) }
    @Test fun deleteAllFiltersCategory() {
        val s = store(); s.add("red", "preference"); s.add("blue", "fact")
        assertEquals(1, s.deleteAll("preference")); assertEquals(1, s.getAll().size)
    }
    @Test fun validatesLength() {
        val s = store(); assertThrows(IllegalArgumentException::class.java) { s.add("x".repeat(501), core = true) }
        assertThrows(IllegalArgumentException::class.java) { s.add("中".repeat(683)) }
    }
    @Test fun historyRecordsSelfEdit() {
        val s = store(); val e = s.add("old", core = true); s.update(e.id, "new")
        assertEquals("update", s.history(e.id).first()["action"]?.toString()?.trim('"'))
        assertEquals("old", s.history(e.id).first()["content"]?.toString()?.trim('"'))
    }
    @Test fun coreAggregateLimitRejectsOverflow() {
        val s = store(); repeat(5) { s.add("x".repeat(490) + it, core = true) }
        assertThrows(IllegalArgumentException::class.java) { s.add("y".repeat(500), core = true) }
    }
    @Test fun promptRespectsLimit() {
        val s = store(); repeat(5) { s.add("core $it " + "x".repeat(490), core = true) }
        repeat(10) { s.add("archive $it " + "y".repeat(100)) }
        assertTrue(s.prompt("archive").length <= 3000)
    }
}
