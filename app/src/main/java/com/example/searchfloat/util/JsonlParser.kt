package com.example.searchfloat.util

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 商业扫描 App 常见 TXT 题库格式解析器：一行一个 JSON。
 * 例：{"q":"题干","ans":"D","a":["选项A","选项B","选项C","选项D"]}
 *
 * 本 App 内部也把 Excel 题库转换成同等结构再导入，便于后续用题干 + 选项一起匹配。
 */
object JsonlParser {

    fun parse(context: Context, uri: Uri): List<ParsedRow> {
        val rows = mutableListOf<ParsedRow>()
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
            lines.forEach { line ->
                val row = parseLine(line)
                if (row != null) rows.add(row)
            }
        }
        return rows
    }

    fun parseText(text: String): List<ParsedRow> {
        return text.lineSequence().mapNotNull { parseLine(it) }.toList()
    }

    /** 把 app 解析出的结构转成商业 App 类似的 JSON Lines 文本。 */
    fun toJsonLines(rows: List<QuestionRecord>): String {
        return rows.joinToString("\n") { record ->
            JSONObject().apply {
                put("q", record.question)
                put("ans", record.answer)
                put("a", JSONArray(record.options))
            }.toString()
        }
    }

    private fun parseLine(line: String): ParsedRow? {
        val raw = line.trim()
        if (raw.isBlank() || !raw.startsWith("{")) return null

        val obj = try { JSONObject(raw) } catch (_: Exception) { return null }
        val q = obj.optString("q", "").trim()
        val ans = obj.optString("ans", "").trim().uppercase()
        val arr = obj.optJSONArray("a") ?: return null
        val options = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i, "").trim()
            if (v.isNotBlank()) options.add(v)
        }

        val record = QuestionRecord(
            question = q,
            answer = ans,
            options = options
        )
        if (!record.isUsable()) return null

        return record.toParsedRow()
    }
}

/** Excel/TXT 统一后的中间结构。 */
data class QuestionRecord(
    val question: String,
    val answer: String,
    val options: List<String>,
    val type: String? = null
) {
    fun isUsable(): Boolean {
        if (question.isBlank()) return false
        if (answer.isBlank()) return false
        if (options.isEmpty()) return false

        // 过滤 Excel 干扰 Sheet 转出来的垃圾：q 是纯数字，选项也大多是纯数字。
        if (question.matches(Regex("^\\d+$"))) {
            val numericOptions = options.count { it.matches(Regex("^\\d+$")) }
            if (numericOptions >= 5) return false
        }
        return normalizeType() in setOf("单选题", "多选题", "判断题")
    }

    fun normalizeType(): String {
        val t = type.orEmpty().replace(" ", "").replace("　", "")
        // Excel 里如果明确写了题型，但不是单选/多选/判断，说明是简答等不支持题型，直接跳过。
        if (t.isNotBlank()) {
            return when {
                t.contains("多选") || t.contains("多项") -> "多选题"
                t.contains("单选") || t.contains("单项") -> "单选题"
                t.contains("判断") || t.contains("正误") || t.contains("对错") -> "判断题"
                else -> ""
            }
        }

        // 商业 TXT/JSONL 通常没有题型，按选项和答案推断。
        return when {
            isJudge() -> "判断题"
            answer.length > 1 -> "多选题"
            else -> "单选题"
        }
    }

    fun toParsedRow(): ParsedRow {
        val opts = options.mapIndexed { index, value ->
            val label = ('A'.code + index).toChar()
            "$label-$value"
        }.joinToString("\n")

        val content = buildString {
            append("选项：\n").append(opts)
            append("\n\n答案：")
            val text = answerText()
            if (text != null) append(text) else append(answer)
        }

        return ParsedRow(
            title = question.trim(),
            category = normalizeType(),
            content = content.trim()
        )
    }

    private fun isJudge(): Boolean {
        if (options.size != 2) return false
        val s = options.map { it.trim() }.toSet()
        return s == setOf("正确", "错误") || s == setOf("对", "错")
    }

    private fun answerText(): String? {
        val letters = answer.filter { it in 'A'..'Z' }
        if (letters.isBlank()) return null
        return letters.mapNotNull { ch ->
            val idx = ch - 'A'
            options.getOrNull(idx)?.let { "$ch-$it" }
        }.joinToString("；").ifBlank { null }
    }
}
