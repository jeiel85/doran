package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.ui.Fit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil

/** 화면 배치 계산: 금액이 칸보다 길 때 글자 크기 줄이기, 달력 칸 금액, 달력 한 주 합계. */
class LayoutTest {
    /** 글자 크기(sp)마다 [perSp]px씩 늘어나는 글. [extra]는 반올림·여백으로 조금 더 넓게 재지는 몫. */
    private fun text(perSp: Float, extra: Int = 0): (Float) -> Int = { sz -> ceil(sz * perSp).toInt() + extra }

    @Test
    fun keepsSizeWhenItFits() {
        assertEquals(34f, Fit.size(34f, 22f, 400, text(10f)), 0f)
        assertEquals(34f, Fit.size(34f, 22f, 340, text(10f)), 0f)
    }

    @Test
    fun shrinksToTheWidthInOneStep() {
        // 340px 글을 170px 칸에: 반으로
        assertEquals(17f, Fit.size(34f, 11f, 170, text(10f)), 0f)
    }

    @Test
    fun neverGoesBelowTheMinimum() {
        assertEquals(22f, Fit.size(34f, 22f, 100, text(10f)), 0f)
    }

    @Test
    fun stepsDownWhenRoundingStillOverflows() {
        // 비례로 고른 크기가 여백 몫 때문에 조금 넘치면 0.5sp씩 더 줄여 칸 안에 넣습니다.
        val width = text(10f, extra = 6)
        val size = Fit.size(34f, 11f, 170, width)
        assertTrue("size=$size", width(size) <= 170)
        assertTrue("size=$size", size < 17f && size >= 16f)
    }

    @Test
    fun noWidthMeansNoChange() {
        assertEquals(14f, Fit.size(14f, 11f, 0, text(10f)), 0f)
        assertEquals(11f, Fit.size(11f, 11f, 10, text(10f)), 0f)
    }

    @Test
    fun calendarCellKeepsWonUnderAMillion() {
        assertEquals("4,500", Fmt.cell(4_500))
        assertEquals("12,345", Fmt.cell(12_345))
        assertEquals("999,999", Fmt.cell(999_999))
        assertEquals("100만", Fmt.cell(1_000_000))
        assertEquals("512만", Fmt.cell(5_129_900))
        assertEquals("1,234만", Fmt.cell(12_345_678))
        assertEquals("1.2억", Fmt.cell(123_456_789))
        assertEquals("1억", Fmt.cell(100_000_000))
        assertEquals("-12,345", Fmt.cell(-12_345))
    }

    @Test
    fun weekTotalsFollowTheCalendarRow() {
        val byDay = mapOf(
            LocalDate.of(2026, 11, 15) to (1_000L to 0L), // 일요일
            LocalDate.of(2026, 11, 18) to (2_500L to 300_000L),
            LocalDate.of(2026, 11, 21) to (700L to 0L), // 토요일
            LocalDate.of(2026, 11, 22) to (9_999L to 0L), // 다음 주 일요일
        )
        val (range, sum) = Stats.week(byDay, LocalDate.of(2026, 11, 18))
        assertEquals(LocalDate.of(2026, 11, 15), range.start)
        assertEquals(LocalDate.of(2026, 11, 21), range.endInclusive)
        assertEquals(4_200L to 300_000L, sum)

        // 토요일을 골라도 그 줄의 일요일부터
        assertEquals(LocalDate.of(2026, 11, 15), Stats.week(byDay, LocalDate.of(2026, 11, 21)).first.start)

        // 달이 바뀌는 줄(2026-11-29 일 ~ 12-05 토)은 두 달 날짜를 다 셉니다(달력이 앞뒤 달 금액도 그리므로).
        val edge = mapOf(LocalDate.of(2026, 11, 30) to (5_000L to 0L), LocalDate.of(2026, 12, 2) to (3_000L to 10_000L))
        val (row, rowSum) = Stats.week(edge, LocalDate.of(2026, 12, 2))
        assertEquals(LocalDate.of(2026, 11, 29), row.start)
        assertEquals(LocalDate.of(2026, 12, 5), row.endInclusive)
        assertEquals(8_000L to 10_000L, rowSum)
    }

    @Test
    fun calendarCoversWholeWeeks() {
        // 2026-11: 1일 일요일 ~ 30일 월요일 → 11/1 ~ 12/5 (5주), 끝은 다음 날
        assertEquals(LocalDate.of(2026, 11, 1) to LocalDate.of(2026, 12, 6), Stats.calendarRange(YearMonth.of(2026, 11)))
        // 2026-12: 1일 화요일 ~ 31일 목요일 → 11/29 ~ 2027-01-02 (5주)
        assertEquals(LocalDate.of(2026, 11, 29) to LocalDate.of(2027, 1, 3), Stats.calendarRange(YearMonth.of(2026, 12)))
        // 2027-01: 1일 금요일 ~ 31일 일요일 → 2026-12-27 ~ 2027-02-06 (6주)
        assertEquals(LocalDate.of(2026, 12, 27) to LocalDate.of(2027, 2, 7), Stats.calendarRange(YearMonth.of(2027, 1)))
        // 2026-02: 1일 일요일 ~ 28일 토요일 → 딱 4주
        assertEquals(LocalDate.of(2026, 2, 1) to LocalDate.of(2026, 3, 1), Stats.calendarRange(YearMonth.of(2026, 2)))
    }
}
