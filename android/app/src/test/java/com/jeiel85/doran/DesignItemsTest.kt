package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.ShareScope
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** D-40: 공유 범위가 좁혀진 뒤 다시 보기, 쉬는 날이면 앞 영업일부터 한 달. */
class DesignItemsTest {
    private val now = Fmt.millis(LocalDateTime.of(2026, 11, 27, 12, 0))
    private fun t(id: String, issuer: String, createdAt: Long, private: Boolean = false, by: String = "me", cancelOf: String? = null) =
        Txn(id, Kind.EXPENSE, 10_000, createdAt, accountId = "acc", memberId = "h", source = Source.PUSH, issuer = issuer,
            private = private, createdBy = by, createdAt = createdAt, cancelOf = cancelOf)

    @Test fun narrowingTheScopeMakesRecentOutOfScopeRecordsPrivate() {
        val before = Member("h", "남편") // 함께 관리(모두 공유)
        val after = before.copy(share = Member.SHARE_PICKED, shareIssuers = listOf("현대카드"))
        val s = LedgerState(txns = listOf(
            t("kb", "KB국민은행", now - 60_000),          // 이제 범위 밖 → 나만 보기로
            t("kbc", "KB국민은행", now - 30_000, cancelOf = "kb"), // 그 취소도
            t("hd", "현대카드", now - 60_000),            // 여전히 범위 안
            t("old", "KB국민은행", now - ShareScope.RECHECK_MS - 1), // 오래된 것은 그대로
            t("other", "KB국민은행", now - 60_000, by = "otherPhone"), // 다른 폰 기록은 그대로
        ).associateBy { it.id })
        val got = ShareScope.privatize(s, before, after, "me", now)
        assertEquals(setOf("kb", "kbc"), got.map { it.id }.toSet())
        assertTrue(got.all { it.private && it.accountId == null })
        // 범위를 넓힌 경우는 아무것도 바꾸지 않음
        assertTrue(ShareScope.privatize(s, after, before, "me", now).isEmpty())
    }

    @Test fun monthStartsOnTheBusinessDayBeforeAWeekendPayday() {
        val b = Stats.BUSINESS + 25
        // 2026-10-25는 일요일 → 10월 한 달은 10/23(금)부터, 11/25(수)는 그대로
        val oct = Stats.period(YearMonth.of(2026, 10), b)
        assertEquals(LocalDate.of(2026, 10, 23), oct.start)
        assertEquals(LocalDate.of(2026, 11, 25), oct.end)
        assertEquals(YearMonth.of(2026, 10), Stats.periodOf(LocalDate.of(2026, 10, 24), b).label) // 토요일은 이미 10월 몫
        assertEquals(YearMonth.of(2026, 9), Stats.periodOf(LocalDate.of(2026, 10, 22), b).label)
        // 추석 연휴(9/24~26)에 걸린 9/25 → 9/23(수)부터
        assertEquals(LocalDate.of(2026, 9, 23), Stats.period(YearMonth.of(2026, 9), b).start)
        // 끄면 예전과 같음
        assertEquals(LocalDate.of(2026, 10, 25), Stats.period(YearMonth.of(2026, 10), 25).start)
        assertEquals(b, Stats.viewStart(LedgerState(), b))
    }
}
