package io.nous.andagent.tools

import org.junit.Assert.*
import org.junit.Test

class WebSearchToolsTest {
    @Test fun parsesRealBingFixture() {
        val html = javaClass.classLoader!!.getResourceAsStream("bing_fixture.html")!!.bufferedReader().use { it.readText() }
        val results = WebHtml.bing(html, 8)
        assertTrue("parsed ${results.size} results", results.size >= 3)
        results.forEach {
            assertTrue(it.url.startsWith("http"))
            assertTrue(it.title.isNotBlank())
            assertEquals("bing", it.engine)
        }
    }

    @Test fun extractsReadableBody() {
        val html = "<html><head><style>x</style><script>bad()</script></head><body><nav>menu</nav><article><h1>A &amp; B</h1><p>Hello <b>world</b>.</p></article></body></html>"
        val text = WebHtml.readable(html, 100)
        assertTrue(text.contains("A & B"))
        assertTrue(text.contains("Hello world"))
        assertFalse(text.contains("bad()"))
        assertFalse(text.contains("menu"))
    }
}
