package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Entry
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Household
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** 알림창 한 줄과 달력 '이체' 보기. */
class StatusLineTest {
    private fun ms(d: Int, h: Int = 12) = Fmt.millis(LocalDateTime.of(2026, 11, d, h, 0))
    private fun t(id: String, amount: Long, d: Int, kind: Kind = Kind.EXPENSE, move: String? = null) = Txn(id, kind, amount, ms(d), move = move)
    private val today = LocalDate.of(2026, 11, 27)

    @Test fun lineSaysTodayAndBudgetLeft() {
        val s = LedgerState(household = Household(monthStartDay = 1, budget = 1_000_000),
            txns = listOf(t("a", 15_000, 27), t("b", 5_000, 27), t("c", 600_000, 3)).associateBy { it.id })
        assertEquals("오늘 20,000원 썼어요" to "남은 예산 380,000원 · 이번 달 620,000원", Stats.statusLine(s, today, 1, null, true))
    }

    @Test fun lineWithoutBudgetOrSpendingToday() {
        val s = LedgerState(household = Household(monthStartDay = 1), txns = listOf(t("c", 600_000, 3)).associateBy { it.id })
        assertEquals("오늘은 아직 쓴 돈이 없어요" to "이번 달 600,000원", Stats.statusLine(s, today, 1, null, true))
        val over = s.copy(household = Household(monthStartDay = 1, budget = 500_000))
        assertEquals("예산보다 100,000원 더 썼어요 · 이번 달 600,000원", Stats.statusLine(over, today, 1, null, true).second)
    }

    @Test fun movedViewShowsTransfersAndLinkedOutflows() {
        fun e(x: Txn) = Entry(x, x.amount, false)
        val got = Stats.byDayMoved(listOf(
            e(t("tr", 100_000, 5, Kind.TRANSFER)),
            e(t("out", 50_000, 5, move = "link:in")), // 가계부 통장끼리 옮긴 출금
            e(t("in", 50_000, 5, Kind.INCOME, move = "link:out")), // 그 입금 쪽은 두 번 세지 않음
            e(t("spend", 9_000, 5)), // 쓴 돈은 아님
        ))
        assertEquals(mapOf(LocalDate.of(2026, 11, 5) to (150_000L to 0L)), got)
    }
}
