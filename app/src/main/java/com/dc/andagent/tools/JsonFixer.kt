package com.dc.andagent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Conservative repair of common model-produced JSON. Every result is parsed before use. */
object JsonFixer {
    data class Result(val json: String, val changes: List<String>, val error: String? = null) {
        val valid get() = error == null
    }

    fun fix(text: String): Result {
        if (validate(text) == null) return Result(text, emptyList())
        val out = StringBuilder()
        val changes = linkedSetOf<String>()
        val closers = ArrayDeque<Char>()
        var i = 0
        var quote: Char? = null
        var escaped = false
        var expectKey = false
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (escaped) {
                    if ((quote == '\'' || quote == '\u2018') && c == '\'') { out.deleteCharAt(out.length - 1); out.append(c) }
                    else out.append(c)
                    escaped = false; i++; continue
                }
                if (c == '\\') { out.append(c); escaped = true; i++; continue }
                if (c == quote || (quote == '\u201c' && c == '\u201d') || (quote == '\u2018' && c == '\u2019')) {
                    out.append('"'); quote = null
                } else {
                    if (c == '"' && quote != '"') out.append('\\')
                    out.append(c)
                }
                i++; continue
            }
            if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                changes.add("removed comments")
                i += 2
                while (i < text.length && text[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                changes.add("removed comments")
                val end = text.indexOf("*/", i + 2)
                i = if (end < 0) text.length else end + 2
                continue
            }
            if (c == '\'' || c == '"' || c == '\u201c' || c == '\u2018') {
                if (c != '"') changes.add(if (c == '\'') "converted single quotes" else "normalized smart quotes")
                quote = c; out.append('"'); i++; continue
            }
            if (c == '\u201d' || c == '\u2019') { changes.add("normalized smart quotes"); out.append('"'); i++; continue }
            if (expectKey && (c.isLetter() || c == '_' || c == '$')) {
                val start = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++
                val word = text.substring(start, i)
                if (text.drop(i).firstOrNull { !it.isWhitespace() } == ':') {
                    out.append('"').append(word).append('"'); changes.add("quoted keys"); expectKey = false
                } else out.append(word)
                continue
            }
            if (c.isLetter()) {
                val start = i
                while (i < text.length && text[i].isLetter()) i++
                val word = text.substring(start, i)
                val replacement = when (word) { "True" -> "true"; "False" -> "false"; "None" -> "null"; else -> word }
                if (word != replacement) changes.add("normalized Python values")
                out.append(replacement); continue
            }
            if (c == ',') {
                var j = i + 1
                while (j < text.length) {
                    if (text[j].isWhitespace()) { j++; continue }
                    if (j + 1 < text.length && text[j] == '/' && text[j + 1] == '/') {
                        j += 2; while (j < text.length && text[j] != '\n') j++
                        continue
                    }
                    if (j + 1 < text.length && text[j] == '/' && text[j + 1] == '*') {
                        val end = text.indexOf("*/", j + 2)
                        j = if (end < 0) text.length else end + 2
                        continue
                    }
                    break
                }
                if (j < text.length && (text[j] == '}' || text[j] == ']')) { changes.add("removed trailing commas"); i++; continue }
                expectKey = closers.lastOrNull() == '}'
            }
            if (c == '{' || c == '[') { closers.addLast(if (c == '{') '}' else ']'); expectKey = c == '{' }
            if (c == '}' || c == ']') { if (closers.lastOrNull() == c) closers.removeLast(); expectKey = false }
            if (c == ':') expectKey = false
            out.append(c); i++
        }
        if (closers.isNotEmpty()) {
            while (out.isNotEmpty() && out.last().isWhitespace()) out.deleteCharAt(out.length - 1)
            if (out.endsWith(",")) { out.deleteCharAt(out.length - 1); changes.add("removed trailing commas") }
            changes.add("completed containers")
            while (closers.isNotEmpty()) out.append(closers.removeLast())
        }
        val fixed = out.toString()
        val error = validate(fixed)
        return Result(fixed, changes.toList(), error)
    }

    private fun validate(text: String): String? {
        val element = try { Json.parseToJsonElement(text) }
            catch (e: Exception) { return e.message ?: "invalid JSON" }
        fun check(value: JsonElement): String? = when (value) {
            is JsonObject -> value.values.firstNotNullOfOrNull { check(it) }
            is JsonArray -> value.firstNotNullOfOrNull { check(it) }
            is JsonPrimitive -> {
                val token = value.content
                if (value.isString || token in setOf("true", "false", "null") || token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))) null
                else "invalid JSON token '$token' at position ${text.indexOf(token).coerceAtLeast(0)}"
            }
            else -> null
        }
        return check(element)
    }

    fun parse(text: String): JsonElement {
        val result = fix(text)
        if (!result.valid) throw IllegalArgumentException(result.error)
        return Json.parseToJsonElement(result.json)
    }

    fun parseObject(text: String): JsonObject = parse(text).jsonObject
}
