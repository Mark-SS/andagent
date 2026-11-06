package com.dc.andagent.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class TtsFileToolTest {
    private val tool by lazy { TtsFileTool(RuntimeEnvironment.getApplication()) }

    @Test fun schemaRequiresTextAndExposesRate() {
        assertEquals("tts_to_file", tool.name)
        val schema = tool.parameters.jsonObject
        assertEquals("text", schema.getValue("required").jsonArray.single().jsonPrimitive.content)
        assertTrue(schema.getValue("properties").jsonObject.containsKey("rate"))
    }

    @Test fun rejectsOversizeTextBeforeStartingEngine() = runBlocking {
        val result = tool.execute(JsonObject(mapOf("text" to JsonPrimitive("字".repeat(4001)))))
        assertTrue(result.startsWith("ERROR:"))
        assertTrue(result.contains("4000"))
        assertTrue(result.contains("分段"))
    }
}
