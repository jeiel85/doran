package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.CardStatus
import com.jeiel85.doran.logic.Settle
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Household
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** D-39: 카드 예산·실적, 예산 이월, 반반 정산. */
class DecisionItemsTest {
    private fun ms(m: Int, d: Int) = Fmt.millis(LocalDateTime.of(2026, m, d, 12, 0))

    @Test fun evenSplitSettles() {
        assertEquals(listOf(Settle.Transfer("w", "h", 100_000)), Settle.even(mapOf("h" to 300_000L, "w" to 100_000L)))
        assertTrue(Settle.even(mapOf("h" to 100_000L)).isEmpty())
        assertTrue(Settle.even(mapOf("h" to 50_000L, "w" to 50_000L)).isEmpty())
        // 세 사람: 90·30·0 → 각 40, 0원 낸 사람이 40, 30원 낸 사람이 10을 90원 낸 사람에게
        val three = Settle.even(mapOf("a" to 90L, "b" to 30L, "c" to 0L))
        assertEquals(setOf(Settle.Transfer("c", "a", 40), Settle.Transfer("b", "a", 10)), three.toSet())
        // 나머지 1원: 101원을 둘이 → 51/50, 받을 사람은 51원 낸 쪽이 아니라 정확히 맞음
        assertEquals(listOf(Settle.Transfer("w", "h", 50)), Settle.even(mapOf("h" to 101L, "w" to 0L)))
    }

    @Test fun carryOverAddsLastMonthsLeftover() {
        val base = LedgerState(household = Household(monthStartDay = 1, budget = 1_000_000, carryOver = true),
            txns = mapOf("a" to Txn("a", Kind.EXPENSE, 700_000, ms(10, 10))))
        val nov = Stats.periodOf(LocalDate.of(2026, 11, 15), 1)
        assertEquals(300_000L, Stats.carryIn(base, nov, null))
        assertEquals(1_300_000L, Stats.budgets(base, emptyList(), Stats.carryIn(base, nov, null)).first!!.budget)
        // 넘은 달은 0, 끄면 0, 앞 달 기록이 없으면 0
        val over = base.copy(txns = mapOf("a" to Txn("a", Kind.EXPENSE, 1_200_000, ms(10, 10))))
        assertEquals(0L, Stats.carryIn(over, nov, null))
        assertEquals(0L, Stats.carryIn(base.copy(household = base.household.copy(carryOver = false)), nov, null))
        assertEquals(0L, Stats.carryIn(base.copy(txns = emptyMap()), nov, null))
        // 저장했다 읽어도 남음
        assertEquals(true, Household.from(base.household.json()).carryOver)
    }

    @Test fun cardBudgetAndPerformanceAlerts() {
        val a = Account("c", "가계부 카드", AccountType.CARD, monthBudget = 500_000, perfGoal = 300_000)
        fun st(month: Long, cycle: Long) = CardStatus(a, month, LocalDate.of(2026, 11, 1), cycle, null, 0, null, null)
        assertTrue(Stats.cardAlerts(st(100_000, 100_000), "202611").isEmpty())
        val eighty = Stats.cardAlerts(st(420_000, 200_000), "202611").single()
        assertTrue(eighty.second.contains("80%"))
        val both = Stats.cardAlerts(st(520_000, 320_000), "202611")
        assertEquals(listOf("실적을 채웠어요", "예산을 다 썼어요"), both.map { it.second.substringAfter("가계부 카드 ") })
        assertEquals(500_000L, Account.from("c", a.json()).monthBudget)
        assertEquals(300_000L, Account.from("c", a.json()).perfGoal)
    }
}
