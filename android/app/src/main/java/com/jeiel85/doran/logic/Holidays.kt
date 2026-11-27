package com.jeiel85.doran.logic

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 관공서 공휴일(DECISIONS.md D-38): 달력의 빨간 날과 월급날 앞당기기에 씁니다. 설·추석·대체공휴일·선거일은 해마다 달라 표로 둡니다.
 * 출처: 우주항공청 월력요항(2026년판·2027년판, 2027년판은 2026-06-29 발표)을 대조한 목록. 표에 없는 해는 공휴일이 없는 것으로 보고
 * 주말만 셉니다 — 해마다 새 월력요항이 나오면(보통 6월) 다음 해를 더합니다(BACKLOG). 임시공휴일은 지정되면 더합니다.
 */
object Holidays {
    private val table: Map<LocalDate, String> = buildMap {
        fun add(y: Int, m: Int, d: Int, name: String) = put(LocalDate.of(y, m, d), name)
        // 2026
        add(2026, 1, 1, "새해 첫날"); add(2026, 2, 16, "설날"); add(2026, 2, 17, "설날"); add(2026, 2, 18, "설날")
        add(2026, 3, 1, "삼일절"); add(2026, 3, 2, "대체공휴일(삼일절)"); add(2026, 5, 1, "노동절"); add(2026, 5, 5, "어린이날")
        add(2026, 5, 24, "부처님 오신 날"); add(2026, 5, 25, "대체공휴일(부처님 오신 날)"); add(2026, 6, 3, "지방선거")
        add(2026, 6, 6, "현충일"); add(2026, 7, 17, "제헌절"); add(2026, 8, 15, "광복절"); add(2026, 8, 17, "대체공휴일(광복절)")
        add(2026, 9, 24, "추석"); add(2026, 9, 25, "추석"); add(2026, 9, 26, "추석"); add(2026, 10, 3, "개천절")
        add(2026, 10, 5, "대체공휴일(개천절)"); add(2026, 10, 9, "한글날"); add(2026, 12, 25, "크리스마스")
        // 2027
        add(2027, 1, 1, "새해 첫날"); add(2027, 2, 6, "설날"); add(2027, 2, 7, "설날"); add(2027, 2, 8, "설날")
        add(2027, 2, 9, "대체공휴일(설날)"); add(2027, 3, 1, "삼일절"); add(2027, 5, 1, "노동절"); add(2027, 5, 3, "대체공휴일(노동절)")
        add(2027, 5, 5, "어린이날"); add(2027, 5, 13, "부처님 오신 날"); add(2027, 6, 6, "현충일"); add(2027, 7, 17, "제헌절")
        add(2027, 7, 19, "대체공휴일(제헌절)"); add(2027, 8, 15, "광복절"); add(2027, 8, 16, "대체공휴일(광복절)")
        add(2027, 9, 14, "추석"); add(2027, 9, 15, "추석"); add(2027, 9, 16, "추석"); add(2027, 10, 3, "개천절")
        add(2027, 10, 4, "대체공휴일(개천절)"); add(2027, 10, 9, "한글날"); add(2027, 10, 11, "대체공휴일(한글날)")
        add(2027, 12, 25, "크리스마스"); add(2027, 12, 27, "대체공휴일(크리스마스)")
    }

    /** 표가 있는 해(이 해까지는 공휴일을 압니다). */
    val lastYear: Int = table.keys.maxOf { it.year }

    fun name(d: LocalDate): String? = table[d]
    fun isHoliday(d: LocalDate): Boolean = d in table

    /** 은행이 쉬는 날: 토·일 또는 공휴일. */
    fun isOff(d: LocalDate): Boolean = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || isHoliday(d)

    /** [d]가 쉬는 날이면 그 앞 영업일(월급이 먼저 들어오는 날). 설·추석 연휴도 건너뜁니다. */
    fun beforeOff(d: LocalDate): LocalDate {
        var x = d
        repeat(14) { if (!isOff(x)) return x; x = x.minusDays(1) }
        return x
    }
}
