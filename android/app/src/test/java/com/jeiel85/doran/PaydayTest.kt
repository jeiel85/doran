package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.AllowanceAlert
import com.jeiel85.doran.logic.AllowancePlan
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Defaults
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.Recurring
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 월 용돈 고정일이 주말이면 앞 금요일 (DECISIONS.md D-27).
 * 2026-11-21(토)이 용돈 날이라 급여·용돈이 11/20(금)에 들어온 사용자 사례가 기준입니다.
 */
class PaydayTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    private val husband = Member("h", "남편", allowance = 350_000)
    private val allowanceRule = Recurring(AllowancePlan.idFor("h"), "남편 용돈", Kind.EXPENSE, 350_000, Defaults.ALLOWANCE, null, "h",
        period = "m", day = 21, startAt = Fmt.millis(d(2026, 10, 1)))

    @Test fun beforeWeekendMovesOnlySaturdayAndSunday() {
        assertEquals(d(2026, 11, 20), Stats.beforeWeekend(d(2026, 11, 21))) // 토 → 금
        assertEquals(d(2027, 2, 19), Stats.beforeWeekend(d(2027, 2, 21))) // 일 → 금
        assertEquals(d(2026, 12, 21), Stats.beforeWeekend(d(2026, 12, 21))) // 월은 그대로
        assertEquals(d(2026, 11, 20), Stats.beforeWeekend(d(2026, 11, 20))) // 금은 그대로
    }

    @Test fun allowanceOnSaturdayIsDueFriday() {
        assertEquals(listOf(d(2026, 11, 20)), Stats.dueDates(allowanceRule, d(2026, 11, 15), d(2026, 11, 25)))
        // 몇 달을 이어 봐도 달마다 한 번씩: 10/21(수) · 11/20(금, 21일 토) · 12/21(월)
        assertEquals(listOf(d(2026, 10, 21), d(2026, 11, 20), d(2026, 12, 21)),
            Stats.dueDates(allowanceRule, d(2026, 10, 1), d(2026, 12, 31)))
    }

    @Test fun allowanceOnSundayIsDueFriday() {
        assertEquals(listOf(d(2027, 2, 19)), Stats.dueDates(allowanceRule, d(2027, 2, 1), d(2027, 2, 28)))
    }

    @Test fun otherFixedRulesKeepTheirDay() {
        // 관리비·구독 자동이체는 은행이 대개 다음 영업일에 빼 가서 앞당기지 않음
        val rent = allowanceRule.copy(id = "rent", name = "관리비")
        assertEquals(listOf(d(2026, 11, 21)), Stats.dueDates(rent, d(2026, 11, 15), d(2026, 11, 25)))
    }

    @Test fun firstOfMonthOnWeekendMovesIntoPreviousMonth() {
        // 2026-11-01은 일요일 → 10/30(금). 11월 범위에는 다시 나오지 않음(12/1은 화요일)
        val first = allowanceRule.copy(day = 1)
        assertEquals(listOf(d(2026, 10, 30)), Stats.dueDates(first, d(2026, 10, 25), d(2026, 10, 31)))
        assertTrue(Stats.dueDates(first, d(2026, 10, 31), d(2026, 11, 30)).isEmpty())
    }

    @Test fun upcomingShowsFridayAndDropsSaturday() {
        val s = LedgerState(members = mapOf("h" to husband), recurrings = mapOf(allowanceRule.id to allowanceRule))
        assertEquals(listOf(d(2026, 11, 20)), Stats.upcoming(s, 7, d(2026, 11, 19)).map { it.second })
        // 금요일 당일에는 "내일 11/21"이 더 이상 다가오는 목록에 없음(오늘 기록됨)
        assertTrue(Stats.upcoming(s, 7, d(2026, 11, 20)).isEmpty())
    }

    // --- 남은 용돈은 용돈이 들어온 날부터 ------------------------------------------------------------

    private fun at(m: Int, day: Int, h: Int = 12) = Fmt.millis(LocalDateTime.of(2026, m, day, h, 0))
    /** 체크카드로 쓴 나만 보는 용돈. */
    private val cu = Txn("cu", Kind.EXPENSE, 3_610, at(11, 19, 11), memberId = "h", merchant = "CU", private = true, issuer = "토스뱅크", source = Source.PUSH)
    private val coffee = Txn("mgc", Kind.EXPENSE, 3_400, at(11, 20, 8), memberId = "h", merchant = "메가MGC", private = true, issuer = "토스뱅크", source = Source.PUSH)
    private val ms365 = Txn("ms", Kind.EXPENSE, 3_575, at(11, 20, 15), memberId = "h", merchant = "MS 365 월 구독", private = true, issuer = "토스뱅크", source = Source.PUSH)
    private fun ledger(vararg t: Txn) = LedgerState(members = mapOf("h" to husband), recurrings = mapOf(allowanceRule.id to allowanceRule),
        txns = t.associateBy { it.id })

    @Test fun allowancePeriodStartsOnTheShiftedAllowanceDay() {
        val s = ledger()
        assertEquals(d(2026, 10, 21) to d(2026, 11, 20), Stats.allowancePeriod(s, "h", d(2026, 11, 19)).let { it.start to it.end })
        assertEquals(d(2026, 11, 20) to d(2026, 12, 21), Stats.allowancePeriod(s, "h", d(2026, 11, 20)).let { it.start to it.end })
        assertEquals(d(2026, 11, 20) to d(2026, 12, 21), Stats.allowancePeriod(s, "h", d(2026, 12, 20)).let { it.start to it.end })
        assertEquals(d(2026, 12, 21), Stats.allowancePeriod(s, "h", d(2026, 12, 21)).start)
        // 1일 규칙이 앞 달 30일(금)로 당겨진 경우도 그날부터
        val first = s.copy(recurrings = mapOf(allowanceRule.id to allowanceRule.copy(day = 1)))
        assertEquals(d(2026, 10, 30) to d(2026, 12, 1), Stats.allowancePeriod(first, "h", d(2026, 10, 31)).let { it.start to it.end })
    }

    @Test fun withoutAllowanceRuleFallsBackToLedgerMonth() {
        val none = LedgerState(members = mapOf("h" to husband))
        assertEquals(Stats.period(YearMonth.of(2026, 11), 1), Stats.allowancePeriod(none, "h", d(2026, 11, 20)))
        val paused = ledger().copy(recurrings = mapOf(allowanceRule.id to allowanceRule.copy(paused = true)))
        assertEquals(Stats.period(YearMonth.of(2026, 11), 1), Stats.allowancePeriod(paused, "h", d(2026, 11, 20)))
    }

    @Test fun remainingAllowanceRefillsOnFriday() {
        val s = ledger(cu, coffee, ms365)
        // 목요일 CU는 지난 용돈 기간, 금요일부터 새 35만원에서 셈
        assertEquals(3_400L + 3_575L, Stats.allowanceSpent(s, "h", Stats.allowancePeriod(s, "h", d(2026, 11, 20))))
        assertEquals("MS 365 월 구독 · 토스뱅크 · 남은 용돈 343,025원", AllowanceAlert.of(s, ms365).body)
        // 목요일 결제 알림은 지난 기간 기준
        assertEquals("CU · 토스뱅크 · 남은 용돈 346,390원", AllowanceAlert.of(s, cu).body)
    }

    @Test fun allowanceRuleTurnedIntoIncomeIsRepaired() {
        val income = allowanceRule.copy(kind = Kind.INCOME, categoryId = "c.income_etc")
        val other = Recurring("salary", "월급", Kind.INCOME, 4_000_000, null, null, "h", day = 21, startAt = 0)
        val s = LedgerState(recurrings = mapOf(income.id to income, other.id to other))
        assertEquals(listOf(income.copy(kind = Kind.EXPENSE, categoryId = Defaults.ALLOWANCE)), AllowancePlan.repaired(s))
        assertTrue(AllowancePlan.repaired(LedgerState(recurrings = mapOf(allowanceRule.id to allowanceRule))).isEmpty())
    }
}
