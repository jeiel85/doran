package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Holidays
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.logic.Suggest
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Recurring
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** 정기결제 자동 제안과 공휴일(D-38). */
class SuggestHolidayTest {
    private fun ms(m: Int, d: Int) = Fmt.millis(LocalDateTime.of(2026, m, d, 9, 0))
    private fun t(id: String, merchant: String, amount: Long, m: Int, d: Int) = Txn(id, Kind.EXPENSE, amount, ms(m, d), merchant = merchant)
    private val today = LocalDate.of(2026, 11, 27)

    @Test fun twoMonthsOfTheSamePaymentIsSuggested() {
        val s = LedgerState(txns = listOf(
            t("a", "넷플릭스", 13_500, 10, 5), t("b", "넷플릭스", 13_500, 11, 6),
            t("c", "편의점", 4_000, 10, 3), t("d", "편의점", 9_000, 11, 20), // 금액·날짜가 달라 아님
            t("e", "통신비", 55_000, 11, 15), // 한 달뿐
        ).associateBy { it.id })
        val got = Suggest.find(s, today, emptySet())
        assertEquals(listOf("넷플릭스"), got.map { it.merchant })
        assertEquals("b", got.single().latest.id)
        assertEquals(2, got.single().months)
    }

    @Test fun existingRuleOrDismissedIsNotSuggested() {
        val s = LedgerState(txns = listOf(t("a", "넷플릭스", 13_500, 10, 5), t("b", "넷플릭스", 13_500, 11, 6)).associateBy { it.id })
        assertTrue(Suggest.find(s, today, setOf(Suggest.key("넷플릭스"))).isEmpty())
        val withRule = s.copy(recurrings = mapOf("r" to Recurring("r", "넷플릭스", Kind.EXPENSE, 13_500, null, null, null, day = 5, startAt = ms(10, 1))))
        assertTrue(Suggest.find(withRule, today, emptySet()).isEmpty())
    }

    @Test fun monthEndPaymentsMatchAcrossMonthLengths() {
        val s = LedgerState(txns = listOf(t("a", "관리비", 200_000, 9, 30), t("b", "관리비", 205_000, 10, 31), t("c", "관리비", 210_000, 11, 1)).associateBy { it.id })
        // 9/30·10/31은 말일끼리, 11/1은 10/31과 하루 차이
        assertEquals(3, Suggest.find(s, today, emptySet()).single().months)
    }

    @Test fun holidaysAndPaydayBeforeThem() {
        assertTrue(Holidays.isHoliday(LocalDate.of(2026, 9, 25)))
        assertEquals("지방선거", Holidays.name(LocalDate.of(2026, 6, 3)))
        assertFalse(Holidays.isHoliday(LocalDate.of(2026, 9, 28))) // 대체공휴일 아님
        // 추석 연휴(9/24~26)에 걸린 25일 월급날은 앞 영업일 9/23(수)
        assertEquals(LocalDate.of(2026, 9, 23), Stats.allowanceDue(YearMonth.of(2026, 9), 25, 1))
        // 공휴일 아닌 평일은 그대로, 주말은 예전처럼 앞 금요일
        assertEquals(LocalDate.of(2026, 11, 25), Stats.allowanceDue(YearMonth.of(2026, 11), 25, 1))
        assertEquals(LocalDate.of(2026, 10, 23), Stats.allowanceDue(YearMonth.of(2026, 10), 25, 1)) // 10/25 일요일
        // 설 연휴 + 대체공휴일(2027-02-06~09)에 걸린 8일 → 2/5(금)
        assertEquals(LocalDate.of(2027, 2, 5), Holidays.beforeOff(LocalDate.of(2027, 2, 8)))
    }
}
