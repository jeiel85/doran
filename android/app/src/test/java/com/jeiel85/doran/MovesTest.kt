package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.AllowanceAlert
import com.jeiel85.doran.logic.Moves
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 범위 밖 통장의 은행 출금: 쓴 돈인가, 옮긴 돈인가(DECISIONS.md D-26).
 * 2026-11-20 사용자 폰: 남편 KB국민은행(공유 범위 밖)에 급여가 들어온 뒤 통째로 다른 통장으로 보냈더니 "용돈에서 404만원 썼어요"가 됨.
 * 금액·은행은 바꿨습니다.
 */
class MovesTest {
    private fun ms(h: Int, m: Int, d: Int = 20) = Fmt.millis(LocalDateTime.of(2026, 11, d, h, m))
    private val p = Stats.period(YearMonth.of(2026, 11), 1)
    private val install = "install-h"

    private val husband = Member("h", "남편", allowance = 350_000)
    private val wife = Member("w", "아내")

    /** 남편 폰이 읽은 범위 밖 은행 출금(판정 전 모양). */
    private fun out(id: String, amount: Long, at: Long, merchant: String = "급여", issuer: String = "KB국민은행") =
        Txn(id, Kind.EXPENSE, amount, at, merchant = merchant, memberId = "h", source = Source.PUSH, issuer = issuer, private = true,
            categoryId = "c.etc", createdBy = install)

    /** 아내 폰이 가계부에 올린 은행 입금(공유). */
    private fun wifeIn(id: String, amount: Long, at: Long, issuer: String = "신한은행", source: Source = Source.PUSH, needsReview: Boolean = false) =
        Txn(id, Kind.INCOME, amount, at, accountId = "acc.w", merchant = "홍길동", memberId = "w", source = source, issuer = issuer,
            needsReview = needsReview, createdBy = "install-w")

    /** 남편 폰의 다른 범위 밖 통장 입금(나만 보는 용돈 수입). */
    private fun myIn(id: String, amount: Long, at: Long, issuer: String) =
        Txn(id, Kind.INCOME, amount, at, merchant = "홍길동", memberId = "h", source = Source.PUSH, issuer = issuer, private = true, createdBy = install)

    private fun ledger(vararg txns: Txn, members: List<Member> = listOf(husband, wife)) =
        LedgerState(members = members.associateBy { it.id }, txns = txns.associateBy { it.id })

    private fun judge(s: LedgerState, w: Txn, rule: Boolean? = null) = Moves.judge(s, w, s.member(w.memberId)?.allowance ?: 0, rule)

    @Test fun salaryToWifeAccountIsMovedNotSpent() {
        val salaryOut = out("o1", 3_200_615, ms(12, 59))
        val deposit = wifeIn("i1", 3_200_615, ms(13, 0))
        val s = ledger(deposit, salaryOut)
        val j = judge(s, salaryOut)
        assertEquals(Kind.TRANSFER, j.kind)
        assertEquals("i1", Moves.pairOf(j))
        assertFalse(j.needsReview)
        assertNull(j.categoryId)
        assertEquals("아내 신한은행 입금(${Fmt.time(deposit.at)})과 같은 돈이라 옮긴 돈으로 봤어요", Moves.reason(s, j, "h"))
        // 남은 용돈·가족에게 가는 용돈 합계에서 빠지고, "용돈에서 썼어요" 알림도 없음
        val after = ledger(deposit, j)
        assertEquals(0L, Stats.allowanceSpent(after, "h", p))
        assertFalse(AllowanceAlert.applies(j, "h", install, ms(13, 1)))
        assertFalse(AllowanceAlert.appliesMoved(j, "h", install, ms(13, 1)))
    }

    @Test fun largeWithdrawalWithoutCounterpartIsMovedForReview() {
        // 공동 통장·적금처럼 받는 쪽 알림이 없는 곳으로 간 큰돈
        val salaryOut = out("o1", 3_200_615, ms(12, 59))
        val j = judge(ledger(salaryOut), salaryOut)
        assertEquals(Kind.TRANSFER, j.kind)
        assertEquals(Moves.LARGE, j.move)
        assertTrue(j.needsReview)
        assertEquals("월 용돈(350,000원)보다 큰 출금이라 옮긴 돈으로 봤어요", Moves.reason(ledger(j), j, "h"))
        assertEquals(0L, Stats.allowanceSpent(ledger(j), "h", p))
        // 바로 고칠 수 있게 따로 알림
        assertTrue(AllowanceAlert.appliesMoved(j, "h", install, ms(13, 0)))
        val text = AllowanceAlert.moved(j)
        assertEquals("KB국민은행에서 3,200,615원이 나갔어요", text.title)
        assertEquals("급여 · 월 용돈보다 커서 옮긴 돈으로 봤어요. 용돈에서 쓴 돈이면 눌러서 바꿔 주세요", text.body)
        // 월 용돈과 같은 금액까지는 쓴 돈(경계)
        assertEquals(Kind.EXPENSE, judge(ledger(), out("o2", 350_000, ms(12, 0))).kind)
        assertEquals(Kind.TRANSFER, judge(ledger(), out("o3", 350_001, ms(12, 0))).kind)
    }

    @Test fun smallTransferToFriendStaysAllowanceSpending() {
        val dutch = out("o1", 30_000, ms(20, 10), merchant = "김친구", issuer = "토스뱅크")
        val j = judge(ledger(dutch), dutch)
        assertEquals(Kind.EXPENSE, j.kind)
        assertEquals(Moves.AUTO, j.move)
        assertEquals("c.etc", j.categoryId)
        assertEquals(30_000L, Stats.allowanceSpent(ledger(j), "h", p))
        assertTrue(AllowanceAlert.applies(j, "h", install, ms(20, 11)))
    }

    @Test fun withoutMonthlyAllowanceThereIsNoSizeRule() {
        val big = out("o1", 3_200_615, ms(12, 59))
        val s = ledger(big, members = listOf(husband.copy(allowance = 0), wife))
        assertEquals(Moves.AUTO, judge(s, big).move)
    }

    @Test fun counterpartMustBeTheSameMoneyNearby() {
        val w = out("o1", 50_000, ms(12, 0))
        fun pairWith(vararg d: Txn) = Moves.pairOf(judge(ledger(w, *d), w))
        // 앞뒤 10분 안, 같은 금액
        assertEquals("a", pairWith(wifeIn("a", 50_000, ms(12, 10))))
        assertEquals("b", pairWith(wifeIn("b", 50_000, ms(11, 50))))
        assertNull(pairWith(wifeIn("c", 50_000, ms(12, 11))))
        assertNull(pairWith(wifeIn("d", 50_001, ms(12, 0))))
        // 여럿이면 가장 가까운 것
        assertEquals("f", pairWith(wifeIn("e", 50_000, ms(12, 8)), wifeIn("f", 50_000, ms(12, 1))))
        // 받는 쪽이 될 수 없는 입금: 원래 승인을 못 찾은 카드 취소(확인 필요 수입), 카드사 수입, 부모님 폰이 보낸 것, 직접 쓴 수입
        assertNull(pairWith(wifeIn("g", 50_000, ms(12, 0), needsReview = true)))
        assertNull(pairWith(wifeIn("h", 50_000, ms(12, 0), issuer = "현대카드")))
        assertNull(pairWith(wifeIn("i", 50_000, ms(12, 0), source = Source.FAMILY)))
        assertNull(pairWith(wifeIn("j", 50_000, ms(12, 0), source = Source.MANUAL)))
    }

    @Test fun myOtherAccountCountsButNotTheSameBank() {
        // 내 KB → 내 토스뱅크(둘 다 범위 밖): 토스뱅크 입금이 받는 쪽
        val w = out("o1", 120_000, ms(9, 0))
        assertNotNull(Moves.pairOf(judge(ledger(w, myIn("t", 120_000, ms(9, 1), "토스뱅크")), w)))
        // 같은 은행의 내 입금은 같은 통장일 수 있음: 급여가 들어오고 몇 분 뒤 그대로 나가면 그 급여 입금은 받는 쪽이 아님
        val passThrough = out("o2", 3_000_000, ms(12, 40))
        val salaryIn = myIn("s", 3_000_000, ms(12, 33), "KB국민은행")
        val j = judge(ledger(salaryIn, passThrough), passThrough)
        assertNull(Moves.pairOf(j))
        assertEquals(Moves.LARGE, j.move) // 대신 금액 기준으로 옮긴 돈
    }

    @Test fun oneDepositPairsWithOneWithdrawal() {
        val d = wifeIn("d", 100_000, ms(12, 0))
        val first = judge(ledger(d, out("o1", 100_000, ms(12, 0))), out("o1", 100_000, ms(12, 0)))
        assertEquals("d", Moves.pairOf(first))
        val second = out("o2", 100_000, ms(12, 2))
        val j = judge(ledger(d, first, second), second)
        assertNull(Moves.pairOf(j))
        assertEquals(Moves.AUTO, j.move)
        // 이미 짝지은 출금을 다시 판정해도 자기 짝은 그대로
        assertEquals("d", Moves.pairOf(judge(ledger(d, first, second), first)))
    }

    @Test fun rememberedRuleWinsBothWays() {
        // 매달 10만원 적금(용돈보다 작음): 한 번 "옮긴 돈 · 앞으로도"
        val saving = out("o1", 100_000, ms(10, 0), merchant = "청약저축")
        assertEquals(Moves.RULE, judge(ledger(saving), saving, rule = true).move)
        assertEquals(Kind.TRANSFER, judge(ledger(saving), saving, rule = true).kind)
        // 큰돈이지만 "용돈에서 쓴 돈 · 앞으로도"
        val big = out("o2", 500_000, ms(10, 0), merchant = "가전마트")
        val kept = judge(ledger(big), big, rule = false)
        assertEquals(Kind.EXPENSE, kept.kind)
        assertEquals(Moves.RULE_KEPT, kept.move)
        assertFalse(kept.needsReview)
        // 규칙 열쇠: 은행(별칭은 정식 이름으로) + 메모
        assertEquals("KB국민은행|급여", Moves.ruleKey("KB국민은행", "급여"))
        assertEquals(Moves.ruleKey("KB국민은행", "급여"), Moves.ruleKey("KB스타", " 급여 "))
        assertNull(Moves.ruleKey("KB국민은행", ""))
        assertNull(Moves.ruleKey(null, "급여"))
    }

    @Test fun lateDepositFromWifesPhoneTurnsAutoSpendingIntoMoved() {
        // 남편 폰이 먼저 출금을 기록(짝 없음, 용돈보다 작음) → 몇 분 뒤 아내 폰의 입금이 동기화로 들어옴
        val w = judge(ledger(), out("o1", 200_000, ms(12, 59)))
        assertEquals(Moves.AUTO, w.move)
        val d = wifeIn("i1", 200_000, ms(13, 0))
        val s = ledger(w, d)
        assertEquals(listOf("o1"), Moves.affected(s, listOf(null to d), "h", install).map { it.id })
        assertEquals("i1", Moves.pairOf(judge(s, w)))
        // 관계없는 입금이면 다시 보지 않음
        assertTrue(Moves.affected(ledger(w, wifeIn("x", 9_000, ms(13, 0))), listOf(null to wifeIn("x", 9_000, ms(13, 0))), "h", install).isEmpty())
    }

    @Test fun deletedOrChangedCounterpartIsRejudged() {
        val d = wifeIn("i1", 3_200_615, ms(13, 0))
        val paired = judge(ledger(d), out("o1", 3_200_615, ms(12, 59)))
        assertEquals("i1", Moves.pairOf(paired))
        // 입금이 지워지면(같은 결제 두 폰 중복 정리 등) 다시 판정: 짝이 없으니 금액 기준
        val s = ledger(paired)
        assertEquals(listOf("o1"), Moves.affected(s, listOf(d to null), "h", install).map { it.id })
        assertEquals(Moves.LARGE, judge(s, paired).move)
        // 같은 입금의 다른 사본(두 폰 중 남은 쪽)이 있으면 그것과 다시 짝
        val twin = wifeIn("i2", 3_200_615, ms(13, 0))
        assertEquals("i2", Moves.pairOf(judge(ledger(paired, twin), paired)))
    }

    @Test fun userChoicesAreNeverRejudged() {
        val d = wifeIn("i1", 200_000, ms(13, 0))
        val kept = Moves.spent(ledger(), out("o1", 200_000, ms(12, 59)), Moves.KEPT)
        val user = Moves.moved(out("o2", 200_000, ms(12, 58)), Moves.USER)
        val rule = Moves.moved(out("o3", 200_000, ms(12, 57)), Moves.RULE)
        val s = ledger(kept, user, rule, d)
        assertTrue(Moves.affected(s, listOf(null to d), "h", install).isEmpty())
        // 다른 사람·다른 폰이 만든 것, 판정 전 기록(move 없음)도 건드리지 않음
        val legacy = out("o4", 200_000, ms(12, 59))
        val other = judge(ledger(), out("o5", 200_000, ms(12, 59))).copy(createdBy = "install-old")
        assertTrue(Moves.affected(ledger(legacy, other, d), listOf(null to d), "h", install).isEmpty())
    }

    @Test fun editingAmountOrTimeOfAutomaticWithdrawalRejudgesIt() {
        // 짝지어진 출금의 날짜를 손으로 고치면(종류는 그대로라 판정 이유가 남음) 입금이 10분 밖이 되니 다시 판정(#49 리뷰)
        val d = wifeIn("i1", 200_000, ms(13, 0))
        val paired = judge(ledger(d), out("o1", 200_000, ms(12, 59)))
        assertEquals("i1", Moves.pairOf(paired))
        val moved = paired.copy(at = ms(15, 0))
        val s = ledger(d, moved)
        assertEquals(listOf("o1"), Moves.affected(s, listOf(paired to moved), "h", install).map { it.id })
        assertEquals(Moves.AUTO, judge(s, moved).move)
        // 금액을 용돈 아래로 고친 큰 출금도 다시 판정
        val large = judge(ledger(), out("o2", 400_000, ms(10, 0)))
        assertEquals(Moves.LARGE, large.move)
        val smaller = large.copy(amount = 40_000)
        assertEquals(listOf("o2"), Moves.affected(ledger(smaller), listOf(large to smaller), "h", install).map { it.id })
        assertEquals(Moves.AUTO, judge(ledger(smaller), smaller).move)
        // 판정만 바뀐 저장(종류·이유·확인 표시)은 다시 보지 않음: 되풀이 없음
        val rejudged = judge(s, moved)
        assertTrue(Moves.affected(ledger(d, rejudged), listOf(moved to rejudged), "h", install).isEmpty())
        // 사용자가 정한 것은 금액을 고쳐도 그대로
        val user = Moves.moved(out("o3", 200_000, ms(9, 0)), Moves.USER)
        val userEdited = user.copy(amount = 1_000)
        assertTrue(Moves.affected(ledger(userEdited), listOf(user to userEdited), "h", install).isEmpty())
    }
}
