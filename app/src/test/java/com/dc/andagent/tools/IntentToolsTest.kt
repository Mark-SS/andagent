package com.dc.andagent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class IntentToolsTest {

    private fun obj(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    /* ---------------- extras 类型映射（白名单） ---------------- */

    @Test fun mapsPrimitiveExtrasByInferredType() {
        val extras = mapExtras(obj("""{"s":"hello","i":42,"b":true,"d":3.14}"""))
        assertEquals(4, extras.size)
        assertEquals(IntentExtra.Str("s", "hello"), extras[0])
        assertEquals(IntentExtra.Int("i", 42), extras[1])
        assertEquals(IntentExtra.Bool("b", true), extras[2])
        assertEquals(IntentExtra.Dbl("d", 3.14), extras[3])
    }

    @Test fun mapsNegativeAndIntegerValuedDouble() {
        val extras = mapExtras(obj("""{"neg":-7,"f":2.0}"""))
        assertEquals(IntentExtra.Int("neg", -7), extras[0])
        assertEquals(IntentExtra.Dbl("f", 2.0), extras[1])
    }

    @Test fun rejectsNestedObjectExtra() {
        try {
            mapExtras(obj("""{"bad":{"nested":1}}"""))
            fail("嵌套对象应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("类型不支持"))
        }
    }

    @Test fun rejectsNullExtra() {
        try {
            mapExtras(obj("""{"bad":null}"""))
            fail("null 应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("类型不支持"))
        }
    }

    @Test fun stringArrayExtraMapped() {
        val arr = Json.parseToJsonElement("""["a","b"]""") as JsonArray
        val extra = mapExtrasArray("list", arr)
        assertEquals(IntentExtra.StrArray("list", listOf("a", "b")), extra)
    }

    @Test fun quotedNumberStaysString() {
        val extras = mapExtras(obj("""{"id":"123"}"""))
        assertEquals(IntentExtra.Str("id", "123"), extras[0])
    }

    @Test fun nullJsonYieldsEmpty() {
        assertTrue(mapExtras(null).isEmpty())
    }

    /* ---------------- 黑名单拦截 ---------------- */

    @Test fun sensitiveBroadcastActionsAreBlacklisted() {
        listOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.SHUTDOWN",
            "android.intent.action.PACKAGE_REMOVED",
            "android.intent.action.TIME_SET",
        ).forEach {
            assertTrue("应拦截 $it", INTENT_ACTION_BLACKLIST.contains(it))
        }
    }

    @Test fun normalActivityActionsAreNotBlacklisted() {
        listOf(
            "android.intent.action.SEND",
            "android.intent.action.VIEW",
            "android.media.action.IMAGE_CAPTURE",
            "com.example.DO_THING",
        ).forEach {
            assertFalse("不应拦截 $it", INTENT_ACTION_BLACKLIST.contains(it))
        }
    }

    /* ---------------- component 解析 ---------------- */

    @Test fun parsesShorthandComponent() {
        assertEquals("com.example" to "com.example.MainActivity", parseComponent("com.example/.MainActivity"))
    }

    @Test fun parsesFullyQualifiedComponent() {
        assertEquals("com.example" to "com.other.MainActivity", parseComponent("com.example/com.other.MainActivity"))
    }

    @Test fun rejectsMalformedComponents() {
        assertNull(parseComponent("no-slash"))
        assertNull(parseComponent("/MainActivity"))
        assertNull(parseComponent("com.example/"))
        assertNull(parseComponent("com.example/Main")) // 非点开头且不含包段 → 非法
    }

    /* ---------------- 广播 action 形态 ---------------- */

    @Test fun broadcastRejectsAndroidPrefixByContract() {
        // 广播侧规则：android. 前缀一概拦截（IntentTool.broadcast 内实现），
        // 这里验证黑名单与常用系统 action 的覆盖关系成立。
        assertTrue(INTENT_ACTION_BLACKLIST.contains("android.intent.action.BOOT_COMPLETED"))
    }
}
