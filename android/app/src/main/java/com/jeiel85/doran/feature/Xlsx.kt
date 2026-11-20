package com.jeiel85.doran.feature

import android.content.Context
import android.net.Uri
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.logic.Period
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 엑셀(.xlsx) 내보내기. Apache POI(수 MB) 대신 SpreadsheetML 몇 개 파일을 zip으로 직접 씁니다 (DECISIONS.md D-13).
 * 사람이 보는 용도라 평문이며, 복구용 백업과는 다릅니다.
 */
object Xlsx {
    class Sheet(val name: String, val header: List<String>, val rows: List<List<Any?>>, val widths: List<Int>)

    suspend fun exportLedger(context: Context, uri: Uri, periods: List<Period>) = withContext(Dispatchers.IO) {
        val s = Hub.ledger.value
        val me = Hub.identity.value.myMemberId
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val all = periods.flatMap { Stats.entries(s, it, me) }.sortedBy { it.txn.at }
        val txRows = all.map { e ->
            val t = e.txn
            listOf(
                Fmt.dateTime(t.at).format(fmt),
                when (t.kind) { Kind.EXPENSE -> "지출"; Kind.INCOME -> "수입"; Kind.TRANSFER -> "이체" },
                s.category(t.categoryId)?.name ?: "",
                t.merchant,
                if (t.kind == Kind.TRANSFER) e.net else if (t.kind == Kind.EXPENSE) -e.net else e.net,
                if (e.canceled) "취소됨" else if (e.net != t.amount) "부분취소" else "",
                s.account(t.accountId)?.name ?: t.cardLabel ?: "",
                s.account(t.toAccountId)?.name ?: "",
                s.member(t.memberId)?.name ?: "",
                if (t.installment > 1) "${t.installment}개월" else "",
                t.memo,
                com.jeiel85.doran.logic.Fixed.source(t),
                if (e.counted) "" else "제외",
            )
        }
        val catRows = periods.flatMap { p ->
            val e = Stats.entries(s, p, me)
            Stats.byCategory(e, Kind.EXPENSE).map { (c, v) -> listOf(Fmt.month(p.label), "지출", s.category(c)?.name ?: "기타", v) } +
                Stats.byCategory(e, Kind.INCOME).map { (c, v) -> listOf(Fmt.month(p.label), "수입", s.category(c)?.name ?: "기타", v) }
        }
        val accRows = s.accounts.values.filter { !it.hidden }.sortedBy { it.order }.map { a ->
            listOf(a.name, a.type.label, s.member(a.ownerId)?.name ?: "", Stats.balance(s, a))
        }
        val sheets = listOf(
            Sheet("내역", listOf("일시", "구분", "카테고리", "내용", "금액", "취소", "계좌·카드", "받는 계좌", "사람", "할부", "메모", "기록 방법", "합계"),
                txRows, listOf(17, 6, 12, 24, 13, 8, 16, 14, 8, 7, 24, 10, 6)),
            Sheet("카테고리별", listOf("월", "구분", "카테고리", "금액"), catRows, listOf(10, 6, 14, 14)),
            Sheet("자산", listOf("이름", "종류", "주인", "잔액"), accRows, listOf(20, 12, 8, 16)),
        )
        context.contentResolver.openOutputStream(uri, "wt")?.use { write(it, sheets) } ?: error("파일을 열 수 없어요")
    }

    fun write(out: OutputStream, sheets: List<Sheet>) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
                sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } +
                "</Types>")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                sheets.mapIndexed { i, sh -> """<sheet name="${esc(sh.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") +
                "</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
                """<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            // 스타일 0 기본, 1 머리글(굵게·배경), 2 숫자 천 단위 쉼표
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0"/></numFmts><fonts count="2"><font><sz val="11"/><name val="맑은 고딕"/></font><font><b/><sz val="11"/><name val="맑은 고딕"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE3F1EA"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="3"><xf/><xf fontId="1" fillId="2" applyFont="1" applyFill="1"/><xf numFmtId="164" applyNumberFormat="1"/></cellXfs></styleSheet>""")
            sheets.forEachIndexed { i, sh -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(sh)) }
        }
    }

    private fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }
        return sb.toString()
    }

    private fun sheetXml(sh: Sheet): String {
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols>""")
        sh.widths.forEachIndexed { i, w -> sb.append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""") }
        sb.append("</cols><sheetData>")
        fun row(r: Int, cells: List<Any?>, header: Boolean) {
            sb.append("<row r=\"$r\">")
            cells.forEachIndexed { c, v ->
                val ref = col(c) + r
                when {
                    v == null || v == "" -> Unit
                    v is Number && !header -> sb.append("<c r=\"$ref\" s=\"2\"><v>$v</v></c>")
                    else -> sb.append("<c r=\"$ref\" t=\"inlineStr\"${if (header) " s=\"1\"" else ""}><is><t xml:space=\"preserve\">${esc(v.toString())}</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        row(1, sh.header, true)
        sh.rows.forEachIndexed { i, r -> row(i + 2, r, false) }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }
}
