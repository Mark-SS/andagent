package com.dc.andagent.tools

import java.io.File

object ScriptFiles {
    fun resolve(root: File, raw: String): File {
        require(raw.length in 1..64 && raw.matches(Regex("[a-zA-Z0-9_.-]+")) && raw != "." && raw != "..") { "脚本名仅可含字母、数字、下划线、点、横线，最长 64 字符" }
        val filename = if (raw.endsWith(".js")) raw else "$raw.js"
        val base = root.canonicalFile
        val target = File(base, filename).canonicalFile
        require(target.parentFile == base) { "脚本路径越界" }
        require(!target.exists() || target.isFile) { "脚本路径不是文件" }
        return target
    }
}
