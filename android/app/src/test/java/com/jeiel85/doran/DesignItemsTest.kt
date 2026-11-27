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
        // 6시간보다 오래된 결제의 최근 취소는 결제를 따라 공유로 둠(#97 리뷰)
        val lateCancel = s.copy(txns = s.txns + ("oc" to t("oc", "KB국민은행", now - 60_000, cancelOf = "old")))
        assertTrue(ShareScope.privatize(lateCancel, before, after, "me", now).none { it.id == "oc" })
        // 은행 출금은 판정을 거침(카드 결제는 아님)
        val judged = ShareScope.privatize(s, before, after, "me", now, judgeWithdrawal = { it.copy(kind = Kind.TRANSFER, move = "large") })
        assertEquals(Kind.TRANSFER, judged.single { it.id == "kb" }.kind)
        assertEquals(Kind.EXPENSE, judged.single { it.id == "kbc" }.kind) // 취소는 그대로
        // 범위를 넓힌 경우는 아무것도 바꾸지 않음
        assertTrue(ShareScope.privatize(s, after, before, "me", now).isEmpty())
    }

    @Test fun recheckStartsAtTheChangeTime() {
        val day = 86_400_000L
        assertEquals(now - ShareScope.RECHECK_MS, ShareScope.recheckSince(null, now))
        assertEquals(now - 2 * day - 5 * 60_000L, ShareScope.recheckSince(now - 2 * day, now)) // 이틀 전에 바꿨으면 그때부터(5분 여유)
        // 범위를 바꾼 뒤 이름을 또 고쳐 HLC가 방금이면 6시간보다 줄이지 않음(#101 리뷰)
        assertEquals(now - ShareScope.RECHECK_MS, ShareScope.recheckSince(now - 60_000L, now))
        assertEquals(now - ShareScope.RECHECK_MAX_MS, ShareScope.recheckSince(now - 90 * day, now)) // 한 달까지만
        // 바꾼 시각을 알면 6시간보다 오래된 기록도 다시 봄
        val before = Member("h", "남편")
        val after = before.copy(share = Member.SHARE_PICKED, shareIssuers = listOf("현대카드"))
        val s = LedgerState(txns = mapOf("kb" to t("kb", "KB국민은행", now - day)))
        assertTrue(ShareScope.privatize(s, before, after, "me", now).isEmpty())
        assertEquals(listOf("kb"), ShareScope.privatize(s, before, after, "me", now, changedAt = now - 2 * day).map { it.id })
    }

    @Test fun businessMonthsCoverEveryDayOnce() {
        // 쉬는 날 한 달 시작(101~128)에서 하루하루가 빠짐없이 정확히 한 달에 들어가고, 달 이름이 겹치지 않음(#99 — 예산 알림 열쇠가 달 이름)
        for (sd in listOf(Stats.BUSINESS + 1, Stats.BUSINESS + 25, Stats.BUSINESS + 28)) {
            var d = LocalDate.of(2026, 1, 1)
            var prev: com.jeiel85.doran.logic.Period? = null
            while (d.isBefore(LocalDate.of(2028, 1, 1))) {
                val p = Stats.periodOf(d, sd)
                assertTrue("$sd $d", !d.isBefore(p.start) && d.isBefore(p.end))
                if (prev != null && prev.label != p.label) {
                    assertEquals(prev.end, p.start)
                    assertEquals(prev.label.plusMonths(1), p.label)
                }
                prev = p
                d = d.plusDays(1)
            }
        }
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
