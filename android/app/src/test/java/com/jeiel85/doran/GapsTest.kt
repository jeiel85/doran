package com.jeiel85.doran

import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Gaps
import com.jeiel85.doran.logic.Gaps.Step
import com.jeiel85.doran.logic.Moves
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 알림 없이 나간 돈 찾기(DECISIONS.md D-31): 토스뱅크는 내가 한 이체·송금에 출금 알림이 없어(2026-11-23),
 * 체크카드 결제 알림의 '잔액'으로 그 사이 빈틈을 찾습니다. 입금은 '내 통장끼리 옮긴 돈'으로 고칠 수 있습니다.
 */
class GapsTest {
    private val min = 60_000L
    private val t0 = 1_795_392_000_000L // 2026-11-23 09:00 KST
    private fun at(m: Int) = t0 + m * min
    private val toss = "토스뱅크"

    @Test fun bankOfGroupsCheckCardsWithTheirBank() {
        assertEquals("토스뱅크", Gaps.bankOf("토스뱅크"))
        assertEquals("KB국민은행", Gaps.bankOf("KB국민카드"))
        assertEquals("KB국민은행", Gaps.bankOf("국민은행"))
        assertEquals("NH농협", Gaps.bankOf("NH농협카드"))
        assertNull(Gaps.bankOf("현대카드")) // 통장이 없는 카드사
        assertNull(Gaps.bankOf("네이버페이"))
        assertNull(Gaps.bankOf(null))
    }

    @Test fun aTransferWithoutNoticeShowsAsAGap() {
        // 체크카드 결제(잔액 200,000) → 알림 없는 이체 70,000 → 체크카드 결제 3,000(잔액 127,000)
        val steps = listOf(
            Step("c1", at(0), 0, -6_000, balance = 200_000),
            Step("c2", at(300), 0, -3_000, balance = 127_000),
        )
        val g = Gaps.find(toss, steps).single()
        assertEquals(70_000L, g.amount)
        assertEquals(at(0), g.fromAt)
        assertEquals(at(300), g.toAt)
        assertEquals("토스뱅크|c2", g.key)
    }

    @Test fun recordedMoneyInAndOutExplainsTheBalance() {
        val steps = listOf(
            Step("c1", at(0), 0, -6_000, balance = 200_000),
            Step("w1", at(10), 0, -15_000), // 자동이체(잔액 없는 출금 알림)
            Step("w2", at(10), 1, -15_000),
            Step("d1", at(20), 0, +30_000), // 입금(잔액 없는 입금 알림)
            Step("x1", at(30), 0, +6_000), // 결제 취소
            Step("c2", at(40), 0, -3_000, balance = 203_000),
        )
        assertTrue(Gaps.find(toss, steps).isEmpty())
    }

    @Test fun smallOrPositiveDifferencesAreNotAsked() {
        // 잔돈 모으기·수수료처럼 조금씩 빠지는 돈(5,000원 아래), 이자처럼 더 많은 쪽은 묻지 않음
        val small = listOf(Step("c1", at(0), 0, -6_000, balance = 200_000), Step("c2", at(5), 0, -3_000, balance = 196_010))
        val more = listOf(Step("c1", at(0), 0, -6_000, balance = 200_000), Step("c2", at(5), 0, -3_000, balance = 260_000))
        assertTrue(Gaps.find(toss, small).isEmpty())
        assertTrue(Gaps.find(toss, more).isEmpty())
        // 딱 5,000원이면 물음
        val edge = listOf(Step("c1", at(0), 0, -6_000, balance = 200_000), Step("c2", at(5), 0, -3_000, balance = 192_000))
        assertEquals(5_000L, Gaps.find(toss, edge).single().amount)
    }

    @Test fun unknownAmountSkipsThatStretchOnly() {
        // 원화 확정 전 해외 결제(금액 모름)가 낀 구간은 보지 않지만, 다음 구간은 다시 봄
        val steps = listOf(
            Step("c1", at(0), 0, -6_000, balance = 200_000),
            Step("f1", at(1), 0, null),
            Step("c2", at(2), 0, -3_000, balance = 100_000),
            Step("c3", at(3), 0, -1_000, balance = 59_000),
        )
        val g = Gaps.find(toss, steps).single()
        assertEquals("토스뱅크|c3", g.key)
        assertEquals(40_000L, g.amount)
    }

    @Test fun sameMinuteStepsFollowRecordingOrder() {
        // 같은 분의 두 결제: 기록한 순서대로(잔액도 그 순서)
        val steps = listOf(
            Step("b", at(0), 2, -2_000, balance = 95_000),
            Step("a", at(0), 1, -3_000, balance = 97_000),
            Step("z", at(-5), 0, 0, balance = 100_000),
        )
        assertTrue(Gaps.find(toss, steps).isEmpty())
    }

    @Test fun deltaFollowsTheMoney() {
        fun t(kind: Kind, amount: Long = 10_000, cancelOf: String? = null, foreign: String? = null) =
            Txn("t", kind, amount, t0, cancelOf = cancelOf, foreign = foreign)
        assertEquals(-10_000L, Gaps.delta(t(Kind.EXPENSE)))
        assertEquals(10_000L, Gaps.delta(t(Kind.EXPENSE, cancelOf = "o"))) // 결제 취소는 돌아온 돈
        assertEquals(10_000L, Gaps.delta(t(Kind.INCOME)))
        assertEquals(-10_000L, Gaps.delta(t(Kind.TRANSFER))) // 옮긴 돈으로 본 출금도 통장에서는 나감
        assertNull(Gaps.delta(t(Kind.EXPENSE, amount = 0, foreign = "USD 12.34")))
    }

    private val me = Member("h", "남편", allowance = 370_000)
    private fun state(vararg txns: Txn) = LedgerState(members = mapOf(me.id to me), txns = txns.associateBy { it.id })
    private fun kbDeposit(id: String, amount: Long, at: Long, private: Boolean = true) =
        Txn(id, Kind.INCOME, amount, at, merchant = "토스뱅크 홍*동", memberId = "h", source = Source.PUSH, issuer = "KB국민은행",
            private = private, createdBy = "me", createdAt = at)

    @Test fun answersFillTheGapAndPairWithTheDeposit() {
        val g = Gaps.Gap(toss, 70_000, at(0), at(300), "c2")
        val dep = kbDeposit("d", 70_000, at(100))
        val s = state(dep)
        assertEquals(dep, Gaps.counterpart(s, g, "h"))
        // 옮긴 돈: 계좌 없는 이체(용돈에서 안 뺌), 짝 입금 시각으로
        val moved = Gaps.answer(s, g, spent = false, me = "h", installId = "me", deposit = dep, now = at(400))
        assertEquals(Kind.TRANSFER, moved.kind)
        assertTrue(Moves.isMoved(moved))
        assertEquals(at(100), moved.at)
        assertEquals("토스뱅크 → KB국민은행", moved.merchant)
        assertTrue(moved.private && moved.accountId == null && moved.toAccountId == null)
        // 쓴 돈: 용돈에서 쓴 지출, 빈틈 안의 시각으로
        val spent = Gaps.answer(s, g, spent = true, me = "h", installId = "me", deposit = null, now = at(400))
        assertEquals(Kind.EXPENSE, spent.kind)
        assertEquals(at(300) - 1, spent.at)
        assertEquals(70_000L, Stats.allowanceSpent(state(spent), "h", com.jeiel85.doran.logic.Period(java.time.YearMonth.of(2026, 11),
            java.time.LocalDate.of(2026, 11, 21), java.time.LocalDate.of(2026, 12, 19))))
        // 답을 흐름에 넣으면 빈틈이 메워짐
        val steps = listOf(Step("c1", at(0), 0, -6_000, balance = 200_000), Step("c2", at(300), 0, -3_000, balance = 127_000),
            Step(spent.id, spent.at, spent.createdAt, Gaps.delta(spent)))
        assertTrue(Gaps.find(toss, steps).isEmpty())
    }

    @Test fun counterpartSkipsTheSameBankAndOtherAmounts() {
        val g = Gaps.Gap(toss, 70_000, at(0), at(300), "c2")
        assertNull(Gaps.counterpart(state(kbDeposit("d", 40_000, at(100))), g, "h"))
        assertNull(Gaps.counterpart(state(kbDeposit("d", 70_000, at(100)).copy(issuer = "토스뱅크")), g, "h"))
        assertNull(Gaps.counterpart(state(kbDeposit("d", 70_000, at(-60))), g, "h")) // 빈틈보다 한참 전
    }

    @Test fun depositMarkedAsSelfTransferLeavesIncome() {
        // 아내 폰 같은 '함께 관리' 입금: 내 통장끼리 옮긴 돈으로 고르면 수입·가계부 합계에서 빠짐
        val wife = Member("w", "아내")
        val dep = kbDeposit("d", 70_000, at(0), private = false).copy(memberId = "w", issuer = "신한은행")
        val s = LedgerState(members = mapOf(wife.id to wife), txns = mapOf(dep.id to dep))
        assertTrue(Moves.canMarkSelf(dep))
        assertTrue(Stats.counts(s, dep))
        val self = Moves.markSelf(dep, on = true)
        assertTrue(Moves.selfIn(self))
        assertFalse(Stats.counts(s, self))
        assertFalse(Moves.canMarkSelf(self))
        assertEquals("내 다른 통장에서 옮겨 온 돈으로 바꿨어요", Moves.reason(s, self, "w"))
        // "수입이 맞아요": 처음처럼
        assertEquals(dep, Moves.markSelf(self, on = false))
        // 카드 취소·손으로 쓴 수입·가족 돈 표시가 있는 입금은 고르지 않음
        assertFalse(Moves.canMarkSelf(dep.copy(source = Source.MANUAL)))
        assertFalse(Moves.canMarkSelf(dep.copy(cancelOf = "o")))
        assertFalse(Moves.canMarkSelf(dep.copy(move = "fund:x")))
    }

    @Test fun aSelfDepositCanStillPairWithALateWithdrawal() {
        // '내 통장끼리'로 고른 입금도, 나중에 온 보낸 쪽 출금(범위 밖 통장)의 짝이 되어 그 출금을 옮긴 돈으로
        val dep = Moves.markSelf(kbDeposit("d", 70_000, at(0)), on = true)
        val w = Txn("w", Kind.EXPENSE, 70_000, at(2), merchant = "내 KB", memberId = "h", source = Source.PUSH, issuer = "토스뱅크",
            private = true, createdBy = "me", createdAt = at(2))
        val judged = Moves.judge(state(dep, w), w, 370_000, rule = null)
        assertTrue(Moves.isMoved(judged))
        assertEquals("d", Moves.pairOf(judged))
    }
}
