package com.dc.andagent.tools

import org.junit.Assert.*
import org.junit.Test

class SkillFormatTest {
    @Test fun parsesPromptAndAllowlist() {
        val skill = SkillFormat.parse("""---
name: weather-pro
description: 天气
 tools: ignored
tools: [web_read, web_search]
prompt: |
  你是天气助手
  回答简短
---
## 用法
请搜索
""".trimIndent())
        assertEquals("weather-pro", skill.name)
        assertEquals(setOf("web_read", "web_search"), skill.allow)
        assertTrue(skill.prompt.contains("回答简短"))
        assertTrue(skill.body.contains("请搜索"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTraversalName() {
        SkillFormat.parse("---\nname: ../bad\n---\n")
    }
}
