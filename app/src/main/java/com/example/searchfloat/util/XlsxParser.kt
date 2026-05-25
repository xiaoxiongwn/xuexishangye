package com.example.searchfloat.util

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.ZipInputStream

data class ParsedRow(val title: String, val category: String, val content: String)

/**
 * 题库导入解析器。
 *
 * 规则：
 * - 不看扩展名、不看 MIME。先按文件头判断是否 ZIP/xlsx，解决“.xls 其实是 xlsx”的 WPS 坑。
 * - 从 Excel 中只抽取：题型 / 题干 / 选项 / 答案。
 * - 先转换成商业扫描 App 类似的 JSONL 中间结构：{"q":"题干","ans":"D","a":[...]}
 * - 再导入 Room。这样后续可以用题干 + 选项共同提升匹配准确率。
 */
object XlsxParser {

    class NotXlsxException(msg: String) : Exception(msg)

    fun parse(context: Context, uri: Uri): List<ParsedRow> =
        parseRecords(context, uri).map { it.toParsedRow() }

    /** 把 Excel 直接转换成商业 App 类似的 TXT/JSON Lines 文本。 */
    fun convertToJsonLines(context: Context, uri: Uri): String =
        JsonlParser.toJsonLines(parseRecords(context, uri))

    /** Excel -> 统一中间结构。 */
    fun parseRecords(context: Context, uri: Uri): List<QuestionRecord> {
        val (sharedStrings, sheetXmls) = readZipParts(context, uri)
            ?: throw NotXlsxException("not_xlsx")
        val ss = sharedStrings ?: emptyList()

        // 多 sheet：选有效题数最多的一张，避开干扰 Sheet。
        var best: List<QuestionRecord> = emptyList()
        for (xml in sheetXmls) {
            val rows = parseSheetXml(xml, ss)
            if (rows.size > best.size) best = rows
        }
        return best
    }

    fun parseCsv(context: Context, uri: Uri): List<ParsedRow> {
        val out = mutableListOf<ParsedRow>()
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val lines = reader.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return out
            val header = parseCsvLine(lines[0])
            val colMap = buildColumnMap(header)
            for (line in lines.drop(1)) {
                val cols = parseCsvLine(line)
                if (cols.isEmpty()) continue
                val record = extractRecord(cols, colMap)
                if (record.isUsable()) out.add(record.toParsedRow())
            }
        }
        return out
    }

    // ==== ZIP/xlsx 拆包：按内容判定，不看扩展名 =======================

    private fun readZipParts(context: Context, uri: Uri): Pair<List<String>?, List<ByteArray>>? {
        val raw = context.contentResolver.openInputStream(uri) ?: return null
        val pb = PushbackInputStream(raw, 4)
        val sig = ByteArray(4)
        val n = pb.read(sig)
        if (n < 4) {
            pb.close()
            return null
        }
        pb.unread(sig, 0, n)

        val isZip = sig[0] == 'P'.code.toByte() &&
            sig[1] == 'K'.code.toByte() &&
            sig[2] == 0x03.toByte() &&
            sig[3] == 0x04.toByte()
        if (!isZip) {
            pb.close()
            return null
        }

        var sharedStrings: List<String>? = null
        val sheetXmls = mutableListOf<ByteArray>()

        ZipInputStream(pb).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == "xl/sharedStrings.xml" -> sharedStrings = parseSharedStrings(zis.readAllBytesCompat())
                    name.startsWith("xl/worksheets/") && name.endsWith(".xml") -> sheetXmls.add(zis.readAllBytesCompat())
                }
                entry = zis.nextEntry
            }
        }
        if (sheetXmls.isEmpty()) return null
        return sharedStrings to sheetXmls
    }

    private fun InputStream.readAllBytesCompat(): ByteArray {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        while (true) {
            val n = read(tmp)
            if (n <= 0) break
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }

    // ==== sharedStrings.xml ===========================================

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val list = mutableListOf<String>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")

        val cur = StringBuilder()
        var inT = false
        var inSi = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> { cur.setLength(0); inSi = true }
                    "t" -> if (inSi) inT = true
                }
                XmlPullParser.TEXT -> if (inT) cur.append(parser.text ?: "")
                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inT = false
                    "si" -> { list.add(cur.toString()); inSi = false }
                }
            }
            parser.next()
        }
        return list
    }

    // ==== 单个 sheet ==================================================

    private fun parseSheetXml(bytes: ByteArray, sharedStrings: List<String>): List<QuestionRecord> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(bytes.inputStream(), "UTF-8")

        val allRows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val cellValue = StringBuilder()
        var inV = false
        var inInlineString = false
        var inInlineText = false
        var cellType = ""
        var currentColIndex = 0

        fun colIndexFromRef(ref: String): Int {
            var idx = 0
            for (c in ref) {
                if (!c.isLetter()) break
                idx = idx * 26 + (c.uppercaseChar() - 'A' + 1)
            }
            return (idx - 1).coerceAtLeast(0)
        }

        fun pushCell(value: String) {
            while (currentRow.size < currentColIndex) currentRow.add("")
            if (currentRow.size == currentColIndex) currentRow.add(value)
            else currentRow[currentColIndex] = value
        }

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t") ?: ""
                        currentColIndex = colIndexFromRef(parser.getAttributeValue(null, "r") ?: "")
                    }
                    "v" -> { inV = true; cellValue.setLength(0) }
                    "is" -> { inInlineString = true; cellValue.setLength(0) }
                    "t" -> if (inInlineString) inInlineText = true
                }
                XmlPullParser.TEXT -> {
                    if (inV || inInlineText) cellValue.append(parser.text ?: "")
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> {
                        inV = false
                        val raw = cellValue.toString()
                        val real = if (cellType == "s") {
                            val i = raw.toIntOrNull() ?: -1
                            if (i in sharedStrings.indices) sharedStrings[i] else raw
                        } else raw
                        pushCell(real)
                    }
                    "t" -> if (inInlineString) inInlineText = false
                    "is" -> { inInlineString = false; pushCell(cellValue.toString()) }
                    "row" -> {
                        if (currentRow.any { clean(it).isNotBlank() }) allRows.add(currentRow.toList())
                        currentRow = mutableListOf()
                    }
                }
            }
            parser.next()
        }
        if (allRows.size < 2) return emptyList()

        val headerIdx = findHeaderIndex(allRows)
        if (headerIdx < 0) return emptyList()

        val colMap = buildColumnMap(allRows[headerIdx])
        return allRows.drop(headerIdx + 1).mapNotNull { cols ->
            val record = extractRecord(cols, colMap)
            if (record.isUsable()) record else null
        }
    }

    private fun findHeaderIndex(rows: List<List<String>>): Int {
        var bestIdx = -1
        var bestHits = 0
        for (i in 0 until rows.size.coerceAtMost(10)) {
            val h = rows[i].map { normalize(it) }.toSet()
            var hits = 0
            if (h.any { it in setOf("题干", "考题", "题目", "标题", "问题", "试题", "题目内容", "试题内容", "question", "title") }) hits++
            if (h.any { it in setOf("答案", "正确答案", "参考答案", "answer", "ans") }) hits++
            if (h.any { it in setOf("选项", "选择项", "choices", "options") }) hits++
            if (h.any { it in setOf("题型", "类型", "type") }) hits++
            if (hits > bestHits) {
                bestHits = hits
                bestIdx = i
            }
        }
        return if (bestHits >= 2) bestIdx else -1
    }

    // ==== 行转换：Excel -> QuestionRecord =============================

    private fun normalize(s: String): String =
        s.trim().replace(" ", "").replace("　", "").replace("\t", "").lowercase()

    private fun clean(s: String): String {
        val v = s.trim()
        return if (v == "\\") "" else v
    }

    private fun buildColumnMap(header: List<String>): Map<String, Int> =
        header.mapIndexed { i, name -> normalize(name) to i }.toMap()

    private fun get(cols: List<String>, colMap: Map<String, Int>, keys: List<String>): String {
        for (k in keys) {
            val i = colMap[normalize(k)] ?: continue
            if (i < cols.size) {
                val v = clean(cols[i])
                if (v.isNotBlank()) return v
            }
        }
        return ""
    }

    private fun extractRecord(cols: List<String>, colMap: Map<String, Int>): QuestionRecord {
        val type = get(cols, colMap, listOf("题型", "类型", "type"))
        val title = get(cols, colMap, listOf("题干", "考题", "题目", "标题", "问题", "试题", "题目内容", "试题内容", "question", "title"))
        val optionsRaw = get(cols, colMap, listOf("选项", "选择项", "choices", "options"))
        val answer = get(cols, colMap, listOf("答案", "正确答案", "参考答案", "answer", "ans")).uppercase()
        val options = splitOptionsToList(optionsRaw)

        return QuestionRecord(
            question = title.trim(),
            answer = answer,
            options = options,
            type = type
        )
    }

    private fun splitOptionsToList(raw: String): List<String> {
        val text = clean(raw).replace('｜', '|').replace('；', ';')
        if (text.isBlank()) return emptyList()

        val parts = if (text.contains('|') || text.contains('\n')) {
            text.split('|', '\n')
        } else {
            Regex("(?=(?<![A-Za-z])[A-Z][\\.．、)）\\-])")
                .split(text)
        }

        return parts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.replace(Regex("^[A-Z][\\.．、)）\\-]\\s*"), "").trim() }
            .filter { it.isNotBlank() }
    }

    // ==== CSV =========================================================

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
