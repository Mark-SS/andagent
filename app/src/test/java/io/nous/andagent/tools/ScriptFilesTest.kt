package io.nous.andagent.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.nous.andagent.script.QuickJsEngine
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ScriptFilesTest {
    @Test fun nameIsConfinedToRoot() {
        val root = Files.createTempDirectory("scripts-test").toFile()
        assertEquals("task.js", ScriptFiles.resolve(root, "task").name)
        for (name in listOf("../secret", "..", "a/b", "a\\b", "x".repeat(65))) {
            assertThrows(IllegalArgumentException::class.java) { ScriptFiles.resolve(root, name) }
        }
    }

    @Test fun savedSourceCanBeLoadedWithoutEscaping() {
        val root = Files.createTempDirectory("scripts-roundtrip").toFile()
        val file = ScriptFiles.resolve(root, "quoted")
        val code = "const s = `hello ${'$'}{args.name}`; s + \"it's fine\""
        file.writeText(code, Charsets.UTF_8)
        assertEquals(code, file.readText(Charsets.UTF_8))
        val source = QuickJsEngine.sourceWithArgs(file.readText(Charsets.UTF_8), buildJsonObject { put("name", "world") })
        assertTrue(source.endsWith(code))
    }

    @Test fun argsPrefixPreservesTemplateLiteralAndNestedQuotes() {
        val script = "const s = `hello ${'$'}{args.name}`; s + '\\n' + \"quoted 'text'\""
        val args = buildJsonObject { put("name", "O'Reilly \"book\"") }
        val source = QuickJsEngine.sourceWithArgs(script, args)
        assertTrue(source.startsWith("var args = {"))
        assertTrue(source.endsWith(script))
        assertTrue(source.contains("O'Reilly"))
    }
}
