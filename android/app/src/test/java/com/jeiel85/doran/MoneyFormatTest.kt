package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test

/** 금액 입력 칸의 세 자리 쉼표와 커서 위치(ui.MoneyTransformation이 쓰는 규칙). */
class MoneyFormatTest {
    @Test fun groupsEveryThreeDigits() {
        assertEquals("", Fmt.groupDigits(""))
        assertEquals("0", Fmt.groupDigits("0"))
        assertEquals("999", Fmt.groupDigits("999"))
        assertEquals("1,000", Fmt.groupDigits("1000"))
        assertEquals("15,000", Fmt.groupDigits("15000"))
        assertEquals("350,000", Fmt.groupDigits("350000"))
        assertEquals("1,500,000", Fmt.groupDigits("1500000"))
        assertEquals("99,999,999,999", Fmt.groupDigits("99999999999"))
    }

    @Test fun leavesNonDigitsAlone() {
        assertEquals("12a4", Fmt.groupDigits("12a4"))
    }

    @Test fun cursorSkipsCommas() {
        val raw = "1500000" // 1,500,000
        assertEquals(0, Fmt.groupedOffset(raw, 0))
        assertEquals(1, Fmt.groupedOffset(raw, 1)) // 1|,500,000 — 쉼표 앞
        assertEquals(3, Fmt.groupedOffset(raw, 2)) // 1,5|00,000
        assertEquals(9, Fmt.groupedOffset(raw, 7)) // 끝
        assertEquals(0, Fmt.digitOffset(raw, 0))
        assertEquals(1, Fmt.digitOffset(raw, 2)) // 쉼표 뒤를 눌러도 숫자 1개 뒤
        assertEquals(7, Fmt.digitOffset(raw, 9))
    }

    @Test fun offsetsRoundTrip() {
        for (raw in listOf("", "5", "1000", "123456", "1234567", "99999999999")) {
            for (o in 0..raw.length) assertEquals(o, Fmt.digitOffset(raw, Fmt.groupedOffset(raw, o)))
            val shown = Fmt.groupDigits(raw).length
            for (t in 0..shown) {
                val o = Fmt.digitOffset(raw, t)
                assert(o in 0..raw.length)
                assert(Fmt.groupedOffset(raw, o) in 0..shown)
            }
        }
    }
}
