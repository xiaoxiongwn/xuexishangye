package com.example.searchfloat.util

import android.content.Context
import android.net.Uri
import jxl.Workbook

/** 真正的老 Excel .xls / BIFF 解析器。 */
object XlsParser {

    fun parse(context: Context, uri: Uri): List<ParsedRow> =
        parseRecords(context, uri).map { it.toParsedRow() }

    fun parseRecords(context: Context, uri: Uri): List<QuestionRecord> {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val workbook = Workbook.getWorkbook(input)
            try {
                var best: List<QuestionRecord> = emptyList()
                for (sheet in workbook.sheets) {
                    val rows = parseSheet(sheet)
                    if (rows.size > best.size) best = rows
                }
                return best
            } finally {
                workbook.close()
            }
        }
        return emptyList()
    }

    private fun parseSheet(sheet: jxl.Sheet): List<QuestionRecord> {
        if (sheet.rows <= 1) return emptyList()
        val headerRow = findHeaderRow(sheet)
        if (headerRow < 0) return emptyList()

        val colMap = mutableMapOf<String, Int>()
        for (c in 0 until sheet.columns) {
            val key = normalize(sheet.getCell(c, headerRow).contents)
            if (key.isNotBlank()) colMap[key] = c
        }

        val out = mutableListOf<QuestionRecord>()
        for (r in headerRow + 1 until sheet.rows) {
            val record = extractRecord(sheet, r, colMap)
            if (record.isUsable()) out.add(record)
        }
        return out
    }

    private fun findHeaderRow(sheet: jxl.Sheet): Int {
        val maxRows = minOf(sheet.rows, 10)
        var bestRow = -1
        var bestHits = 0
        for (r in 0 until maxRows) {
            val keys = (0 until sheet.columns).map { normalize(sheet.getCell(it, r).contents) }.toSet()
            var hits = 0
            if (keys.any { it in setOf("题干", "考题", "题目", "标题", "问题", "试题", "题目内容", "试题内容", "question", "title") }) hits++
            if (keys.any { it in setOf("答案", "正确答案", "参考答案", "answer", "ans") }) hits++
            if (keys.any { it in setOf("选项", "选择项", "choices", "options") }) hits++
            if (keys.any { it in setOf("题型", "类型", "type") }) hits++
            if (hits > bestHits) {
                bestHits = hits
                bestRow = r
            }
        }
        return if (bestHits >= 2) bestRow else -1
    }

    private fun extractRecord(sheet: jxl.Sheet, row: Int, colMap: Map<String, Int>): QuestionRecord {
        val type = get(sheet, row, colMap, listOf("题型", "类型", "type"))
        val title = get(sheet, row, colMap, listOf("题干", "考题", "题目", "标题", "问题", "试题", "题目内容", "试题内容", "question", "title"))
        val optionsRaw = get(sheet, row, colMap, listOf("选项", "选择项", "choices", "options"))
        val answer = get(sheet, row, colMap, listOf("答案", "正确答案", "参考答案", "answer", "ans")).uppercase()

        return QuestionRecord(
            question = title.trim(),
            answer = answer,
            options = splitOptionsToList(optionsRaw),
            type = type
        )
    }

    private fun get(sheet: jxl.Sheet, row: Int, colMap: Map<String, Int>, keys: List<String>): String {
        for (k in keys) {
            val c = colMap[normalize(k)] ?: continue
            if (c < sheet.columns) {
                val v = clean(sheet.getCell(c, row).contents)
                if (v.isNotBlank()) return v
            }
        }
        return ""
    }

    private fun normalize(s: String): String =
        s.trim().replace(" ", "").replace("　", "").replace("\t", "").lowercase()

    private fun clean(s: String): String {
        val v = s.trim()
        return if (v == "\\") "" else v
    }

    private fun splitOptionsToList(raw: String): List<String> {
        val text = clean(raw).replace('｜', '|').replace('；', ';')
        if (text.isBlank()) return emptyList()

        val parts = if (text.contains('|') || text.contains('\n')) {
            text.split('|', '\n')
        } else {
            Regex("(?=(?<![A-Za-z])[A-Z][\\.．、)）\\-])").split(text)
        }

        return parts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.replace(Regex("^[A-Z][\\.．、)）\\-]\\s*"), "").trim() }
            .filter { it.isNotBlank() }
    }
}
