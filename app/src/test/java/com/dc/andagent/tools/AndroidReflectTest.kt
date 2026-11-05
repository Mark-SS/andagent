package com.dc.andagent.tools

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AndroidReflectTest {
    @Test fun acceptsExactClass() = assertTrue(ReflectPolicy.classAllowed("android.media.AudioManager"))
    @Test fun rejectsOtherClass() = assertFalse(ReflectPolicy.classAllowed("java.lang.Runtime"))
    @Test fun rejectsPrefixSibling() = assertFalse(ReflectPolicy.classAllowed("android.media.AudioManagerEvil"))
    @Test fun classRejectionListsAllowlist() {
        val e = assertThrows(IllegalArgumentException::class.java) { ReflectPolicy.check("java.lang.Runtime", "exec") }
        assertTrue(e.message!!.contains("类不在反射白名单"))
        assertTrue(e.message!!.contains("android.media.AudioManager"))
    }
    @Test fun rejectsIdentifierMethod() {
        assertTrue(assertThrows(IllegalArgumentException::class.java) { ReflectPolicy.check("android.telephony.TelephonyManager", "getImei") }.message!!.contains("黑名单"))
    }
    @Test fun rejectsSettingsWrite() {
        assertTrue(assertThrows(IllegalArgumentException::class.java) { ReflectPolicy.check("android.provider.Settings\$System", "putString") }.message!!.contains("黑名单"))
    }
    @Test fun rejectsOtherMethodOnAllowedClass() {
        assertThrows(IllegalArgumentException::class.java) { ReflectPolicy.check("android.media.AudioManager", "adjustVolume") }
    }
    @Test fun convertsPrimitiveTypes() {
        assertEquals("abc", ReflectAdapter.convert(JsonPrimitive("abc"), String::class.java))
        assertEquals(3, ReflectAdapter.convert(JsonPrimitive(3), Int::class.javaPrimitiveType!!))
        assertEquals(3L, ReflectAdapter.convert(JsonPrimitive(3L), Long::class.javaPrimitiveType!!))
        assertEquals(true, ReflectAdapter.convert(JsonPrimitive(true), Boolean::class.javaPrimitiveType!!))
        assertEquals(1.5, ReflectAdapter.convert(JsonPrimitive(1.5), Double::class.javaPrimitiveType!!))
    }
    @Test fun rejectsObjectArguments() {
        assertThrows(IllegalArgumentException::class.java) { ReflectAdapter.convert(buildJsonObject { put("x", 1) }, String::class.java) }
    }
    @Test fun nullIsNotPrimitive() {
        assertNull(ReflectAdapter.convert(JsonNull, String::class.java))
        assertThrows(IllegalArgumentException::class.java) { ReflectAdapter.convert(JsonNull, Int::class.javaPrimitiveType!!) }
    }
    @Test fun resultTruncatesObjects() = assertEquals(2048, (ReflectAdapter.result("x".repeat(3000)) as JsonPrimitive).content.length)
    @Test fun serializesPrimitiveArraysElementwise() = assertEquals("[\"1\",\"2\"]", ReflectAdapter.result(intArrayOf(1, 2)).toString())
    @Test fun injectsContentResolverFromContextPlaceholder() {
        val executor = RestrictedReflectExecutor(RuntimeEnvironment.getApplication())
        val result = executor.invoke("android.provider.Settings\$System", "getString", JsonArray(listOf(JsonPrimitive("\$context"), JsonPrimitive("screen_brightness"))), true)
        assertTrue(result == "null" || result.startsWith("\""))
    }
    @Test fun injectsContentResolverWhenOmitted() {
        val executor = RestrictedReflectExecutor(RuntimeEnvironment.getApplication())
        val result = executor.invoke("android.provider.Settings\$System", "getString", JsonArray(listOf(JsonPrimitive("screen_brightness"))), true)
        assertTrue(result == "null" || result.startsWith("\""))
    }
    @Test fun rejectsNonWhitelistedSettingKey() {
        val executor = RestrictedReflectExecutor(RuntimeEnvironment.getApplication())
        assertThrows(IllegalArgumentException::class.java) {
            executor.invoke("android.provider.Settings\$Secure", "getString", JsonArray(listOf(JsonPrimitive("some_random_key"))), true)
        }
    }
    @Test fun acceptsWhitelistedSettingKey() {
        val executor = RestrictedReflectExecutor(RuntimeEnvironment.getApplication())
        val result = executor.invoke("android.provider.Settings\$Secure", "getString", JsonArray(listOf(JsonPrimitive("screen_brightness"))), true)
        assertTrue(result == "null" || result.startsWith("\""))
    }
    private fun spec(name: String = "api_test", method: String = "getStreamVolume") = buildJsonObject {
        put("name", name); put("domain", "audio"); put("description", "test")
        put("class", "android.media.AudioManager"); put("method", method); put("service", "audio")
        put("params", buildJsonArray { add(buildJsonObject { put("name", "stream"); put("type", "int") }) })
        put("permission", JsonNull); put("destructive", false)
    }
    @Test fun validatesFactorySpec() = assertEquals("api_test", ApiToolFactory.validate(spec()).name)
    @Test fun rejectsInvalidFactoryName() {
        assertThrows(IllegalArgumentException::class.java) { ApiToolFactory.validate(spec("bad")) }
    }
    @Test fun rejectsForbiddenFactoryMethod() {
        assertThrows(IllegalArgumentException::class.java) { ApiToolFactory.validate(spec(method = "adjustVolume")) }
    }
    @Test fun rejectsInvalidFactoryBoolean() {
        val bad = JsonObject(spec().toMutableMap().also { it["destructive"] = JsonPrimitive("false") })
        assertThrows(IllegalArgumentException::class.java) { ApiToolFactory.validate(bad) }
    }
}
