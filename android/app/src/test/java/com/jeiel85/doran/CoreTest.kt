package com.jeiel85.doran

import com.jeiel85.doran.capture.Categorizer
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hlc
import com.jeiel85.doran.crypto.Crypto
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.feature.Xlsx
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.AllowanceSummary
import com.jeiel85.doran.model.Defaults
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.Recurring
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.zip.ZipInputStream

class CoreTest {
    private fun ms(y: Int, m: Int, d: Int, h: Int = 12) = Fmt.millis(LocalDateTime.of(y, m, d, h, 0))
    private fun state(vararg t: Txn, accounts: List<Account> = emptyList(), members: List<Member> = emptyList()) = LedgerState(
        categories = Defaults.categories.associateBy { it.id },
        txns = t.associateBy { it.id },
        accounts = accounts.associateBy { it.id },
        members = members.associateBy { it.id },
    )

    @Test fun hlcIsMonotonicAndSortable() {
        var now = 1_000L
        val h = Hlc("dev") { now }
        val a = h.next(); val b = h.next()
        now = 900 // 시계가 뒤로 가도
        val c = h.next()
        assertTrue(a < b && b < c)
        h.observe(Hlc.format(5_000, 3, "other"))
        assertTrue(h.next() > Hlc.format(5_000, 3, "other"))
        // 묘비용 가장 낮은 값: 같은 시각 기기보다 낮고, 더 이전 시각보다 높음
        val x = Hlc.format(7_000, 2, "Abc-def")
        val low = Hlc.lowestAt(x)
        assertTrue(low < x)
        assertTrue(low > Hlc.format(7_000, 1, "zzz"))
        assertTrue(low < Hlc.format(7_000, 2, "-a"))
    }

    @Test fun sealOpenRoundTripAndWrongKey() {
        val k = Crypto.newKey()
        val aad = Crypto.opAad("space", "ek")
        val env = Crypto.seal(k, "안녕".toByteArray(), aad)
        assertEquals(Crypto.kid(k), Crypto.kidOf(env))
        assertArrayEquals("안녕".toByteArray(), Crypto.open(k, env, aad))
        assertNull(Crypto.open(Crypto.newKey(), env, aad))
        assertNull(Crypto.open(k, env, Crypto.opAad("other", "ek")))
        assertEquals(43, Crypto.entityKey(k, "txn", "id1").length)
    }

    @Test fun monthPeriodWithStartDay() {
        val p = Stats.periodOf(LocalDate.of(2026, 11, 17), 25)
        assertEquals(YearMonth.of(2026, 10), p.label)
        assertEquals(LocalDate.of(2026, 10, 25), p.start)
        assertEquals(LocalDate.of(2026, 11, 25), p.end)
        assertEquals(LocalDate.of(2026, 11, 1), Stats.periodOf(LocalDate.of(2026, 11, 1), 1).start)
    }

    @Test fun cancelNetsOutAndAllowanceSummaryCounts() {
        val buy = Txn("t1", Kind.EXPENSE, 30_000, ms(2026, 11, 3), categoryId = "c.food")
        val partial = Txn("c1", Kind.EXPENSE, 10_000, ms(2026, 11, 4), cancelOf = "t1")
        val inc = Txn("t2", Kind.INCOME, 100_000, ms(2026, 11, 5))
        val s = state(buy, partial, inc).copy(
            allowances = mapOf("a.w.202611" to AllowanceSummary("a.w.202611", "w", "202611", 45_000, 3)),
        )
        val p = Stats.period(YearMonth.of(2026, 11), 1)
        val e = Stats.entries(s, p, myMemberId = "h")
        assertEquals(20_000L + 45_000L, Stats.expense(e))
        assertEquals(100_000L, Stats.income(e))
        // 내 용돈 요약은 내 폰에서 세부 내역으로 보이므로 합계에 두 번 넣지 않음
        assertEquals(20_000L, Stats.expense(Stats.entries(s, p, myMemberId = "w")))
    }

    @Test fun cardDueAmountSplitsInstallments() {
        val card = Account("card", "현대카드", AccountType.CARD, billingDay = 14, cycleStartDay = 1)
        val s = state(
            Txn("a", Kind.EXPENSE, 90_000, ms(2026, 10, 10), accountId = "card", installment = 3),
            Txn("b", Kind.EXPENSE, 12_000, ms(2026, 10, 20), accountId = "card"),
            Txn("c", Kind.EXPENSE, 50_000, ms(2026, 11, 2), accountId = "card"),
            accounts = listOf(card),
        )
        // 11월 5일: 10월 이용분이 11월 14일에 청구 → 할부 첫 달 30,000 + 12,000
        val nov5 = Stats.card(s, card, LocalDate.of(2026, 11, 5))
        assertEquals(LocalDate.of(2026, 11, 14), nov5.dueDate)
        assertEquals(42_000L, nov5.dueAmount)
        // 11월 20일: 11월 이용분이 12월 14일 → 할부 둘째 달 30,000 + 50,000
        val nov20 = Stats.card(s, card, LocalDate.of(2026, 11, 20))
        assertEquals(LocalDate.of(2026, 12, 14), nov20.dueDate)
        assertEquals(80_000L, nov20.dueAmount)
        assertEquals(3_334L, Stats.installmentPart(10_000, 3, 0))
    }

    @Test fun balanceUsesAnchorAndTransfers() {
        val bank = Account("bank", "통장", AccountType.BANK, opening = 1_000_000, openingAt = ms(2026, 1, 1), anchor = 500_000, anchorAt = ms(2026, 11, 10))
        val card = Account("card", "카드", AccountType.CARD, openingAt = ms(2026, 1, 1))
        val s = state(
            Txn("before", Kind.EXPENSE, 99_999, ms(2026, 11, 9), accountId = "bank"), // 기준점 이전 → 무시
            Txn("in", Kind.INCOME, 200_000, ms(2026, 11, 12), accountId = "bank"),
            Txn("buy", Kind.EXPENSE, 80_000, ms(2026, 11, 13), accountId = "card"),
            Txn("pay", Kind.TRANSFER, 80_000, ms(2026, 11, 14), accountId = "bank", toAccountId = "card"),
            accounts = listOf(bank, card),
        )
        val today = LocalDate.of(2026, 11, 20)
        assertEquals(620_000L, Stats.balance(s, bank, today))
        // 11월 이용분은 12월 14일 결제 전이라 아직 갚을 돈 (납부 이체는 카드 쪽 계산에 쓰지 않음)
        assertEquals(-80_000L, Stats.balance(s, card, today))
        assertEquals(540_000L, Stats.netWorth(s, today).net)
        // 결제일이 지나면 갚을 돈에서 빠짐
        assertEquals(0L, Stats.balance(s, card, LocalDate.of(2026, 12, 15)))
    }

    @Test fun cardOutstandingKeepsRemainingInstallments() {
        val card = Account("card", "현대카드", AccountType.CARD, billingDay = 14, cycleStartDay = 1)
        val s = state(Txn("a", Kind.EXPENSE, 90_000, ms(2026, 10, 10), accountId = "card", installment = 3), accounts = listOf(card))
        // 11/14에 첫 회차를 냈으니 11/20에는 두 회차(60,000)가 남음
        assertEquals(60_000L, Stats.cardOutstanding(s, card, LocalDate.of(2026, 11, 20)))
    }

    @Test fun recurringDueDatesClampToMonthEnd() {
        val r = Recurring("r", "관리비", Kind.EXPENSE, 200_000, null, null, null, period = "m", day = 31, startAt = ms(2026, 1, 1))
        val d = Stats.dueDates(r, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
        assertEquals(listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31)), d)
        val w = Recurring("w", "학원", Kind.EXPENSE, 50_000, null, null, null, period = "w", day = 1, startAt = ms(2026, 11, 1))
        assertEquals(LocalDate.of(2026, 11, 2), Stats.dueDates(w, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 8)).first())
    }

    @Test fun categorizerUsesRulesThenKeywords() {
        val s = state()
        assertEquals("c.cafe", Categorizer.categorize(s, "스타벅스 성수점", Kind.EXPENSE))
        assertEquals("c.subscription", Categorizer.categorize(s, "NETFLIX.COM", Kind.EXPENSE))
        assertEquals("c.salary", Categorizer.categorize(s, "(주)도란 급여", Kind.INCOME))
        assertEquals(Categorizer.normalize("(주)스타벅스코리아 성수점"), Categorizer.normalize("스타벅스코리아"))
        val learned = s.copy(rules = mapOf(Categorizer.ruleId("동네빵집") to com.jeiel85.doran.model.MerchantRule(Categorizer.ruleId("동네빵집"), "동네빵집", "c.food")))
        assertEquals("c.food", Categorizer.categorize(learned, "동네빵집", Kind.EXPENSE))
    }

    @Test fun xlsxIsAValidZipWithSheets() {
        val out = ByteArrayOutputStream()
        Xlsx.write(out, listOf(Xlsx.Sheet("내역", listOf("일시", "금액"), listOf(listOf("2026-11-17", -12300L), listOf("<&>", 5L)), listOf(10, 10))))
        val names = mutableListOf<String>()
        ZipInputStream(out.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; names += e.name } }
        assertTrue(names.containsAll(listOf("[Content_Types].xml", "xl/workbook.xml", "xl/worksheets/sheet1.xml", "xl/styles.xml")))
    }
}
