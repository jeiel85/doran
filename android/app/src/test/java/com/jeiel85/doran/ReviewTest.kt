package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Review
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Household
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Recurring
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** 연간 리포트와 월말 마감. */
class ReviewTest {
    private fun ms(m: Int, d: Int) = Fmt.millis(LocalDateTime.of(2026, m, d, 12, 0))
    private fun t(id: String, amount: Long, m: Int, d: Int, kind: Kind = Kind.EXPENSE, merchant: String = "가게") =
        Txn(id, kind, amount, ms(m, d), merchant = merchant)

    @Test fun yearSumsMonthsAndAveragesOnlyPastMonths() {
        val s = LedgerState(household = Household(monthStartDay = 1), txns = listOf(
            t("a", 100_000, 10, 5), t("b", 300_000, 11, 3, merchant = "마트"), t("c", 2_000_000, 11, 25, Kind.INCOME),
        ).associateBy { it.id })
        val y = Review.year(s, 2026, 1, null, true, LocalDate.of(2026, 11, 27))
        assertEquals(400_000L, y.expense)
        assertEquals(2_000_000L, y.income)
        assertEquals(200_000L, y.monthlyAvg) // 기록 있는 지난 두 달(10·11월)
        assertEquals(11, y.maxMonth!!.period.label.monthValue)
        assertEquals(true, y.months[11].future) // 12월은 아직
        assertEquals("마트", y.topMerchants.first().first)
    }

    @Test fun closingShowsAroundTheMonthEnd() {
        assertEquals(11, Review.closingPeriod(LocalDate.of(2026, 11, 28), 1)!!.label.monthValue) // 끝 3일
        assertEquals(11, Review.closingPeriod(LocalDate.of(2026, 12, 2), 1)!!.label.monthValue) // 다음 달 첫 3일은 지난달
        assertNull(Review.closingPeriod(LocalDate.of(2026, 11, 15), 1))
    }

    @Test fun closingListsMissedFixedAndIncome() {
        val r = Recurring("fee", "관리비", Kind.EXPENSE, 300_000, null, null, null, day = 25, startAt = ms(10, 1))
        val s = LedgerState(household = Household(monthStartDay = 1), recurrings = mapOf(r.id to r), txns = listOf(
            t("pay", 3_000_000, 11, 25, Kind.INCOME), t("x", 500_000, 11, 10), t("rev", 0, 11, 12).copy(needsReview = true, foreign = "USD 9.99"),
        ).associateBy { it.id })
        val p = Stats.periodOf(LocalDate.of(2026, 11, 28), 1)
        val c = Review.closing(s, p, LocalDate.of(2026, 11, 28), null, true)
        assertEquals(1, c.incomeCount)
        assertEquals(1, c.needsReview)
        assertEquals(listOf("fee"), c.missedFixed.map { it.first.id })
        assertEquals(2_500_000L, c.left)
    }
}
