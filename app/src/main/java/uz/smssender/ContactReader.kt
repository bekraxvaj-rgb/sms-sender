package uz.smssender

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.TreeMap
import java.util.zip.ZipInputStream

/** .xlsx yoki .csv fayldan (1-ustun: telefon, 2-ustun: ism) o'qiydi. */
object ContactReader {

    fun read(ctx: Context, uri: Uri): List<Pair<String, String>> {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw Exception("fayl ochilmadi")
        if (bytes.size > 4 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte()) {
            throw Exception("eski .xls format qo'llanmaydi, .xlsx qilib saqlang")
        }
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        return if (isZip) readXlsx(bytes) else readCsv(bytes)
    }

    private fun readCsv(bytes: ByteArray): List<Pair<String, String>> {
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        val out = ArrayList<Pair<String, String>>()
        for (line in text.lines()) {
            if (line.isBlank()) continue
            val parts = line.split(Regex("[;,\t]"))
            val phone = parts.getOrElse(0) { "" }.trim().trim('"')
            val name = parts.getOrElse(1) { "" }.trim().trim('"')
            if (phone.any { it.isDigit() }) out.add(phone to name)
        }
        return out
    }

    private fun readXlsx(bytes: ByteArray): List<Pair<String, String>> {
        var sharedBytes: ByteArray? = null
        val sheets = TreeMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val n = e.name
                if (n == "xl/sharedStrings.xml") {
                    sharedBytes = zis.readBytes()
                } else if (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")) {
                    sheets[n] = zis.readBytes()
                }
                e = zis.nextEntry
            }
        }
        if (sheets.isEmpty()) throw Exception("Excel ichida varaq topilmadi")
        val shared = parseShared(sharedBytes)
        return parseSheet(sheets.firstEntry()!!.value, shared)
    }

    private fun parseShared(data: ByteArray?): List<String> {
        val out = ArrayList<String>()
        if (data == null) return out
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(data), "UTF-8")
        var sb = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                if (p.name == "si") sb = StringBuilder()
                else if (p.name == "t") sb.append(p.nextText())
            } else if (ev == XmlPullParser.END_TAG && p.name == "si") {
                out.add(sb.toString())
            }
            ev = p.next()
        }
        return out
    }

    private fun parseSheet(data: ByteArray, shared: List<String>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(data), "UTF-8")
        var row = HashMap<Int, String>()
        var col = 0
        var nextCol = 0
        var type: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "row" -> {
                        row = HashMap()
                        nextCol = 0
                    }
                    "c" -> {
                        val ref = p.getAttributeValue(null, "r")
                        col = if (ref != null) colIndex(ref) else nextCol
                        nextCol = col + 1
                        type = p.getAttributeValue(null, "t")
                    }
                    "v" -> {
                        val txt = p.nextText()
                        row[col] = if (type == "s") shared.getOrElse(txt.trim().toIntOrNull() ?: -1) { "" } else txt
                    }
                    "t" -> if (type == "inlineStr") {
                        row[col] = (row[col] ?: "") + p.nextText()
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "row") {
                val phone = row[0]?.trim().orEmpty()
                val name = row[1]?.trim().orEmpty()
                if (phone.any { it.isDigit() }) out.add(phone to name)
            }
            ev = p.next()
        }
        return out
    }

    private fun colIndex(ref: String): Int {
        var idx = 0
        for (ch in ref) {
            if (ch.isLetter()) idx = idx * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return idx - 1
    }
}
