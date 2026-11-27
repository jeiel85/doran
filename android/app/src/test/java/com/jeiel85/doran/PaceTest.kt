package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.logic.Entry
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Recurring
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** 이번 달 페이스(D-35): 월말 예상·평소 하루 평균·예상 초과일. 숫자는 구성안의 예시와 같습니다. */
class PaceTest {
    private fun ms(d: Int) = Fmt.millis(LocalDateTime.of(2026, 11, d, 12, 0))
    private fun e(id: String, amount: Long, d: Int, rule: String? = null) =
        Entry(Txn(id, Kind.EXPENSE, amount, ms(d), recurringId = rule), amount, false)

    private val p = Stats.periodOf(LocalDate.of(2026, 11, 15), 1) // 11/1 ~ 11/30
    private val fee = Recurring("fee", "관리비", Kind.EXPENSE, 300_000, null, null, null, day = 25, startAt = ms(1))
    private val entries = listOf(e("rent", 900_000, 1, rule = "rent"), e("a", 400_000, 5), e("b", 320_000, 12))
    private val ahead = listOf(fee to LocalDate.of(2026, 11, 25))

    @Test fun projectsWithFixedApart() {
        val pace = Stats.pace(entries, ahead, p, LocalDate.of(2026, 11, 15), 2_500_000)!!
        assertEquals(48_000, pace.dailyVariable) // 고정지출을 뺀 720,000 ÷ 15일
        assertEquals(2_640_000, pace.projected) // 1,620,000 + 300,000 + 48,000 × 15
        assertEquals(LocalDate.of(2026, 11, 28), pace.overDay)
    }

    @Test fun noOverDayWhenItFits() {
        val pace = Stats.pace(entries, ahead, p, LocalDate.of(2026, 11, 15), 3_000_000)!!
        assertNull(pace.overDay)
    }

    @Test fun noOverDayWithoutBudgetOrWhenAlreadyOver() {
        assertNull(Stats.pace(entries, ahead, p, LocalDate.of(2026, 11, 15), 0)!!.overDay)
        assertNull(Stats.pace(entries, ahead, p, LocalDate.of(2026, 11, 15), 1_000_000)!!.overDay)
    }

    @Test fun fixedDueTodayCanTipItOverToday() {
        val today = LocalDate.of(2026, 11, 25)
        val pace = Stats.pace(entries, ahead, p, today, 1_900_000)!!
        assertEquals(today, pace.overDay)
    }

    @Test fun firstThreeDaysAreTooEarly() {
        assertNull(Stats.pace(entries, ahead, p, LocalDate.of(2026, 11, 2), 2_500_000))
        assertEquals(3, Stats.pace(entries, ahead, p, LocalDate.of(2026, 11, 3), 2_500_000)!!.elapsedDays)
    }

    @Test fun lastDayHasNothingLeftToProject() {
        val pace = Stats.pace(entries, emptyList(), p, LocalDate.of(2026, 11, 30), 0)!!
        assertEquals(1_620_000, pace.projected)
    }

    @Test fun futureDatedExpenseIsNotPartOfTheDailyAverage() {
        // 11/30로 미리 적은 300,000원: 이미 쓴 돈에는 들어가지만 하루 평균을 부풀리지 않고, 그날 나가는 돈으로 셉니다(#86 리뷰).
        val only = listOf(e("later", 300_000, 30))
        val pace = Stats.pace(only, emptyList(), p, LocalDate.of(2026, 11, 15), 250_000)!!
        assertEquals(0, pace.dailyVariable)
        assertEquals(300_000, pace.projected)
        assertEquals(LocalDate.of(2026, 11, 30), pace.overDay)
    }
}
