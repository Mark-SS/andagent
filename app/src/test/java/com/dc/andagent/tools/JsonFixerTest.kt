package com.dc.andagent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class JsonFixerTest {
    @Test fun repairsEachCommonForm() {
        val cases = listOf(
            "{\"url\":\"https://a/b\" // comment\n}" to "https://a/b",
            "{\"x\":1,}" to "1",
            "{'x':'value'}" to "value",
            "{x:1}" to "1",
            "{“x”:“value”}" to "value",
            "{\"x\":[1,2" to "[1,2]",
            "{\"x\":True}" to "true",
            "{\"x\":False}" to "false",
            "{\"x\":None}" to "null",
            "{/* comment */\"x\":1}" to "1",
        )
        for ((input, expected) in cases) {
            val result = JsonFixer.fix(input)
            assertTrue("$input: ${result.error}", result.valid)
            assertEquals(expected, Json.parseToJsonElement(result.json).jsonObject["x"]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.content else it.toString() } ?: Json.parseToJsonElement(result.json).jsonObject["url"]?.jsonPrimitive?.content)
        }
    }

    @Test fun validInputIsPreservedAndUnrepairableInputReportsError() {
        val valid = "{\"x\":\"a\\\"b\",\"y\":2}"
        assertEquals(valid, JsonFixer.fix(valid).json)
        val bad = JsonFixer.fix("{x: @}")
        assertFalse(bad.valid)
        assertNotNull(bad.error)
    }
}
