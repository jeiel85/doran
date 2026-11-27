package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Recurring
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** 예산 게이지의 '앞으로 나갈 고정지출' 몫과 예산 화면의 미리 잡힌 고정지출. */
class UpcomingFixedTest {
    private fun ms(m: Int, d: Int, h: Int = 9) = Fmt.millis(LocalDateTime.of(2026, m, d, h, 0))
    private fun rule(id: String, day: Int, amount: Long, kind: Kind = Kind.EXPENSE) =
        Recurring(id, id, kind, amount, null, null, null, day = day, startAt = ms(10, 1))

    private val ott = rule("ott", 5, 17_000)
    private val fee = rule("fee", 25, 300_000)
    private val pay = rule("pay", 20, 3_000_000, Kind.INCOME)
    private val s = LedgerState(recurrings = listOf(ott, fee, pay).associateBy { it.id })

    @Test fun onlyExpenseRulesStillToComeThisMonth() {
        val rest = Stats.ahead(s, LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 30))
        assertEquals(listOf("fee" to LocalDate.of(2026, 11, 25)), rest.map { it.first.id to it.second })
    }

    @Test fun nextMonthShowsEveryExpenseRule() {
        val next = Stats.ahead(s, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31))
        assertEquals(listOf("ott", "fee"), next.map { it.first.id })
        assertEquals(317_000, next.sumOf { it.first.amount })
    }

    @Test fun alreadyRecordedPaymentIsNotCountedAgain() {
        // 관리비가 카드 알림으로 이틀 먼저 들어와 규칙에 이어졌으면 남은 몫에서 빠집니다.
        val early = Txn("t1", Kind.EXPENSE, 300_000, ms(11, 23), recurringId = "fee")
        val withTxn = s.copy(txns = mapOf(early.id to early))
        assertEquals(emptyList<Pair<Recurring, LocalDate>>(), Stats.ahead(withTxn, LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 30)))
        // 다음 달 몫은 그대로입니다.
        assertEquals(2, Stats.ahead(withTxn, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)).size)
    }

    @Test fun pausedAndEndedRulesAreLeftOut() {
        val st = s.copy(recurrings = mapOf(ott.id to ott.copy(paused = true), fee.id to fee.copy(endAt = ms(11, 30))))
        assertEquals(emptyList<Pair<Recurring, LocalDate>>(), Stats.ahead(st, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31)))
    }

    @Test fun splitSubtractsFixedBeforeDailyAmount() {
        val b = Stats.BudgetSplit(budget = 2_500_000, spent = 1_620_000, reserved = 300_000)
        assertEquals(880_000, b.left)
        assertEquals(580_000, b.free)
        assertEquals(38_666, b.perDay(15))
        val tight = Stats.BudgetSplit(budget = 2_500_000, spent = 2_300_000, reserved = 300_000)
        assertEquals(-100_000, tight.free)
        assertEquals(0, tight.perDay(5))
    }

    @Test fun sameTimeLastMonth() {
        val p = Stats.periodOf(LocalDate.of(2026, 11, 15), 1)
        val lp = Stats.sameTimeLastPeriod(p, LocalDate.of(2026, 11, 15), 1)
        assertEquals(LocalDate.of(2026, 10, 1), lp.start)
        assertEquals(LocalDate.of(2026, 10, 16), lp.end) // 10/1~10/15 (끝은 빼고 셈)
        // 월급날(25일) 시작: 12/24는 11/25에서 29일째 → 지난 기간(10/25~11/24)의 같은 때는 11/23까지.
        val q = Stats.periodOf(LocalDate.of(2026, 12, 24), 25)
        val lq = Stats.sameTimeLastPeriod(q, LocalDate.of(2026, 12, 24), 25)
        assertEquals(LocalDate.of(2026, 10, 25), lq.start)
        assertEquals(LocalDate.of(2026, 11, 24), lq.end)
        // 지난 기간이 더 짧으면 그 끝에서 멈춤: 3/31 기준 지난달(2월)은 2/28까지.
        val m = Stats.periodOf(LocalDate.of(2027, 3, 31), 1)
        assertEquals(LocalDate.of(2027, 3, 1), Stats.sameTimeLastPeriod(m, LocalDate.of(2027, 3, 31), 1).end)
    }
}
