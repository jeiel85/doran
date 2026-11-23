package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Moves
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 용돈 외 돈(DECISIONS.md D-28). 2026-11-20 사용자 엑셀: 아내가 신한은행에서 남편 통장으로 용돈·관리비·대출 돈을 따로 보냄.
 * B: 남편 케이뱅크(가계부 통장)로 온 관리비는 아내 출금 지출 + 남편 입금 수입으로 두 번 들어감 → 가계부 통장끼리 옮긴 돈으로 짝지음.
 * C: 남편 KB(범위 밖)로 온 대출 돈이 대출로 나가면 '용돈에서 쓴 돈'이 됨 → 가족 돈으로 표시하고 같은 금액 출금은 용돈에서 뺌.
 * 금액은 바꿨습니다.
 */
class FamilyMoneyTest {
    private fun ms(h: Int, m: Int, d: Int = 20, mo: Int = 11) = Fmt.millis(LocalDateTime.of(2026, mo, d, h, m))
    private val p = Stats.period(YearMonth.of(2026, 11), 1)
    private val install = "install-h"

    private val husband = Member("h", "남편", allowance = 350_000)
    private val wife = Member("w", "아내")
    private val parent = Member("m", "어머니", role = "sender")
    private val wifeBank = Account("acc.w", "신한은행", AccountType.BANK, ownerId = "w", institution = "신한은행")
    private val myShared = Account("acc.k", "케이뱅크", AccountType.BANK, ownerId = "h", institution = "케이뱅크")
    private val wifeCard = Account("acc.wc", "신한카드", AccountType.CARD, ownerId = "w", institution = "신한카드")

    /** 아내 폰이 문자로 기록한 신한은행 출금(공유). */
    private fun wifeOut(id: String, amount: Long, at: Long, merchant: String = "남편", account: String = wifeBank.id, issuer: String = "신한은행",
                        member: String = "w", source: Source = Source.SMS) =
        Txn(id, Kind.EXPENSE, amount, at, accountId = account, merchant = merchant, memberId = member, source = source, issuer = issuer,
            categoryId = "c.etc", createdBy = "install-w")

    /** 남편 폰이 앱 알림으로 기록한 케이뱅크 입금(가계부 통장, 공유). */
    private fun sharedIn(id: String, amount: Long, at: Long, account: String = myShared.id) =
        Txn(id, Kind.INCOME, amount, at, accountId = account, merchant = "아내_관리비", memberId = "h", source = Source.PUSH, issuer = "케이뱅크",
            categoryId = "c.income_etc", createdBy = install)

    /** 남편 폰의 범위 밖 통장 입금(나만 보는 용돈). */
    private fun myIn(id: String, amount: Long, at: Long, issuer: String = "KB국민은행") =
        Txn(id, Kind.INCOME, amount, at, merchant = "아내_대출", memberId = "h", source = Source.PUSH, issuer = issuer, private = true, createdBy = install)

    /** 남편 폰의 범위 밖 통장 출금(판정 전 모양). */
    private fun myOut(id: String, amount: Long, at: Long, issuer: String = "KB국민은행", merchant: String = "대출이자") =
        Txn(id, Kind.EXPENSE, amount, at, merchant = merchant, memberId = "h", source = Source.PUSH, issuer = issuer, private = true,
            categoryId = "c.etc", createdBy = install)

    private fun ledger(vararg txns: Txn) = LedgerState(
        members = listOf(husband, wife, parent).associateBy { it.id },
        accounts = listOf(wifeBank, myShared, wifeCard).associateBy { it.id },
        txns = txns.associateBy { it.id },
    )

    private fun LedgerState.with(updates: List<Txn>) = copy(txns = txns + updates.associateBy { it.id })

    // --- B: 가계부 통장끼리 옮긴 돈 -------------------------------------------------------------

    /**
     * 2026-11-23: 아내가 신한 통장에서 자기 다른 신한 통장으로 10만 원을 옮김. 신한 문자에는 계좌번호가 없어 두 통장이 '신한은행'
     * 하나로 기록돼, 출금 "홍길순"는 지출·입금 "홍길순_축의"은 수입으로 둘 다 가계부 합계에 들어갔습니다(D-34).
     */
    @Test fun ownTransferBetweenTwoAccountsOfTheSameBankIsLinked() {
        val out = wifeOut("w1", 100_000, ms(14, 18), merchant = "홍길순")
        val inn = Txn("w2", Kind.INCOME, 100_000, ms(14, 18), accountId = wifeBank.id, merchant = "홍길순_축의", memberId = "w",
            source = Source.SMS, issuer = "신한은행", categoryId = "c.income_etc", createdBy = "install-w")
        val gift = wifeOut("w0", 100_000, ms(14, 13), merchant = "카카오페이")
        val s = ledger(gift, out, inn)
        val linked = s.with(Moves.linkUpdates(s, setOf("w1", "w2")))
        assertEquals("w2", Moves.linkOf(linked.txns.getValue("w1")))
        assertEquals("w1", Moves.linkOf(linked.txns.getValue("w2")))
        assertNull(Moves.linkOf(linked.txns.getValue("w0")))
        // 내역 목록·엑셀 '받는 계좌'에 옮긴 곳이 보임
        assertEquals("아내 신한은행", Moves.destination(linked, linked.txns.getValue("w1")))
        assertNull(Moves.destination(linked, linked.txns.getValue("w2")))
        val e = Stats.entries(linked, p, myMemberId = "w")
        // 축의금(카카오페이)만 쓴 돈, 통장끼리 옮긴 돈은 수입도 지출도 아님
        assertEquals(100_000L, Stats.expense(e))
        assertEquals(0L, Stats.income(e))
    }

    /** 나만 보는 통장(카카오뱅크)에서 가계부 통장으로 보낸 돈은 짝지은 입금의 사람·통장이 받은 곳으로 보입니다. */
    @Test fun movedPrivateWithdrawalShowsWhereItWent() {
        val inn = sharedIn("k1", 100_000, ms(14, 17))
        val w = myOut("x", 100_000, ms(14, 17), issuer = "카카오뱅크", merchant = "")
        val s = ledger(inn, w)
        val judged = Moves.judge(s, w, husband.allowance, null)
        assertEquals("k1", Moves.pairOf(judged))
        assertEquals("남편 케이뱅크", Moves.destination(s.with(listOf(judged)), judged))
        // 짝이 없으면 모름
        assertNull(Moves.destination(s, w))
    }

    @Test fun sameAccountPairNeedsTheSamePersonOnBothEnds() {
        fun inn(merchant: String, at: Long = ms(14, 18), member: String = "w") = Txn("i", Kind.INCOME, 100_000, at, accountId = wifeBank.id,
            merchant = merchant, memberId = member, source = Source.SMS, issuer = "신한은행", categoryId = "c.income_etc", createdBy = "install-w")
        fun linkedWith(o: Txn, i: Txn) = Moves.linkOf(ledger(o, i).with(Moves.linkUpdates(ledger(o, i), setOf(o.id, i.id))).txns.getValue(o.id))
        val out = wifeOut("o", 100_000, ms(14, 18), merchant = "홍길순")
        assertEquals("i", linkedWith(out, inn("홍길순")))
        // 다른 사람에게 보내고 다른 사람에게 받은 같은 금액은 따로
        assertNull(linkedWith(out, inn("박하영")))
        // 이름이 더 긴 다른 사람("홍길순이")은 같은 사람이 아님
        assertNull(linkedWith(out, inn("홍길순이")))
        // 같은 통장에서 몇 분 넘게 떨어진 것은 따로
        assertNull(linkedWith(out, inn("홍길순", at = ms(14, 25))))
        // 받는 분 이름이 없는 출금은 같은 사람인지 알 수 없어 묶지 않음
        assertNull(linkedWith(wifeOut("o", 100_000, ms(14, 18), merchant = ""), inn("홍길순")))
        // 다른 사람 폰의 입금과는 같은 통장이어도 묶지 않음
        assertNull(linkedWith(out, inn("홍길순", member = "h")))
    }

    @Test fun maintenanceMoneyToMySharedAccountIsLinkedAndLeavesTotals() {
        val out = wifeOut("w1", 300_000, ms(13, 6))
        val inn = sharedIn("k1", 300_000, ms(13, 6))
        val church = wifeOut("w2", 100_000, ms(13, 2), merchant = "교회")
        val s = ledger(out, inn, church)
        val linked = s.with(Moves.linkUpdates(s, setOf("k1")))
        assertEquals("k1", Moves.linkOf(linked.txns.getValue("w1")))
        assertEquals("w1", Moves.linkOf(linked.txns.getValue("k1")))
        val e = Stats.entries(linked, p, myMemberId = "h")
        // 쓴 돈에는 교회만, 수입은 0. 관리비가 실제로 나가면 그때 한 번 들어감
        assertEquals(100_000L, Stats.expense(e))
        assertEquals(0L, Stats.income(e))
        // 통장 잔액은 그대로 움직임
        assertEquals(-400_000L, Stats.balance(linked, wifeBank))
        assertEquals(300_000L, Stats.balance(linked, myShared))
        // 다시 돌려도 바뀌는 것 없음(훅이 되풀이되지 않게)
        assertTrue(Moves.linkUpdates(linked, setOf("w1", "k1")).isEmpty())
    }

    @Test fun eitherPhoneFindsTheSamePair() {
        val out = wifeOut("w1", 300_000, ms(13, 6))
        val inA = sharedIn("k2", 300_000, ms(13, 8))
        val inB = sharedIn("k1", 300_000, ms(13, 4))
        val s = ledger(out, inA, inB)
        // 2분 차이 둘: id가 앞선 k1. 어느 쪽에서 시작해도 같음
        val fromWife = s.with(Moves.linkUpdates(s, setOf("w1")))
        val fromMine = s.with(Moves.linkUpdates(s, setOf("k2", "k1")))
        assertEquals("k1", Moves.linkOf(fromWife.txns.getValue("w1")))
        assertEquals(fromWife.txns, fromMine.txns)
        assertNull(fromWife.txns.getValue("k2").move)
    }

    @Test fun notLinkedWhenItIsNotAMoveBetweenLedgerBankAccounts() {
        val at = ms(13, 6)
        fun linksWith(other: Txn, base: Txn = sharedIn("k1", 300_000, at)) =
            ledger(base, other).let { Moves.linkUpdates(it, setOf(base.id, other.id)) }
        assertTrue(linksWith(wifeOut("w1", 300_000, ms(13, 17))).isEmpty()) // 11분 뒤
        assertTrue(linksWith(wifeOut("w1", 300_000, at, account = myShared.id)).isEmpty()) // 같은 통장
        assertTrue(linksWith(wifeOut("w1", 300_000, at, account = wifeCard.id, issuer = "신한카드")).isEmpty()) // 카드 결제
        assertTrue(linksWith(wifeOut("w1", 300_000, at, source = Source.MANUAL)).isEmpty()) // 직접 쓴 것
        assertTrue(linksWith(wifeOut("w1", 300_000, at, member = "m", source = Source.FAMILY)).isEmpty()) // 부모님 폰
        assertTrue(linksWith(wifeOut("w1", 300_000, at).copy(recurringId = "rec.rent")).isEmpty()) // 고정 내역
        assertTrue(linksWith(wifeOut("w1", 299_000, at)).isEmpty()) // 금액이 다름
        // 범위 밖 통장(나만 보는 입금)은 B가 아니라 C
        assertTrue(linksWith(wifeOut("w1", 300_000, at), base = myIn("i1", 300_000, at)).isEmpty())
    }

    @Test fun brokenLinkIsUndoneAndRepaired() {
        val out = wifeOut("w1", 300_000, ms(13, 6))
        val inn = sharedIn("k1", 300_000, ms(13, 6))
        val linked = ledger(out, inn).let { it.with(Moves.linkUpdates(it, setOf("k1"))) }
        // 다른 쪽이 지워짐(두 폰 중복 정리 등) → 남은 쪽은 풀려 다시 합계에
        val gone = linked.copy(txns = linked.txns - "k1")
        val fixed = gone.with(Moves.linkUpdates(gone, setOf("k1")))
        assertNull(fixed.txns.getValue("w1").move)
        assertTrue(Stats.counts(fixed, fixed.txns.getValue("w1")))
        // 같은 금액의 다른 입금이 오면 그것과 다시 짝
        val again = fixed.with(listOf(sharedIn("k3", 300_000, ms(13, 7))))
        assertEquals("k3", Moves.linkOf(again.with(Moves.linkUpdates(again, setOf("k3"))).txns.getValue("w1")))
    }

    @Test fun editedPairNoLongerMatchingIsUnlinked() {
        // #54 리뷰: 짝지은 뒤 금액·날짜·통장을 고치면(종류는 그대로라 move가 남음) 조건을 다시 보고 둘 다 풀어 합계에 다시 넣음
        val linked = ledger(wifeOut("w1", 300_000, ms(13, 6)), sharedIn("k1", 300_000, ms(13, 6)))
            .let { it.with(Moves.linkUpdates(it, setOf("k1"))) }
        fun afterEdit(edit: (Txn) -> Txn): LedgerState {
            val s = linked.with(listOf(edit(linked.txns.getValue("k1"))))
            return s.with(Moves.linkUpdates(s, setOf("k1")))
        }
        for (s in listOf(afterEdit { it.copy(amount = 310_000) }, afterEdit { it.copy(at = ms(15, 0)) }, afterEdit { it.copy(accountId = wifeBank.id) })) {
            assertNull(s.txns.getValue("w1").move)
            assertNull(s.txns.getValue("k1").move)
            assertTrue(Stats.counts(s, s.txns.getValue("w1")))
        }
        // 메모·카테고리만 고친 것은 그대로 짝
        val memo = afterEdit { it.copy(memo = "10월 관리비", categoryId = "c.income_etc") }
        assertEquals("w1", Moves.linkOf(memo.txns.getValue("k1")))
    }

    @Test fun apartStaysApartUntilRelinked() {
        val out = wifeOut("w1", 300_000, ms(13, 6))
        val inn = sharedIn("k1", 300_000, ms(13, 6))
        val linked = ledger(out, inn).let { it.with(Moves.linkUpdates(it, setOf("k1"))) }
        val apart = linked.with(Moves.apart(linked, linked.txns.getValue("w1")))
        assertEquals(Moves.APART, apart.txns.getValue("w1").move)
        assertEquals(Moves.APART, apart.txns.getValue("k1").move)
        assertTrue(Moves.linkUpdates(apart, setOf("w1", "k1")).isEmpty())
        // 따로 세면 다시 지출·수입으로
        val e = Stats.entries(apart, p, "h")
        assertEquals(300_000L, Stats.expense(e))
        assertEquals(300_000L, Stats.income(e))
        val relinked = apart.with(Moves.relink(apart, apart.txns.getValue("k1")))
        assertEquals("w1", Moves.linkOf(relinked.txns.getValue("k1")))
        assertEquals("k1", Moves.linkOf(relinked.txns.getValue("w1")))
    }

    // --- C: 가족 돈 -----------------------------------------------------------------------------

    @Test fun loanMoneyFromWifeIsFamilyMoneyAndItsPaymentLeavesAllowance() {
        val sent = wifeOut("w1", 200_000, ms(13, 13))
        val got = myIn("i1", 200_000, ms(13, 13))
        val s0 = ledger(sent, got)
        assertEquals("w1", Moves.fundSource(s0, got)?.id)
        val marked = s0.with(Moves.fundUpdates(s0, listOf(null to got), "h", install))
        assertEquals("w1", Moves.fundOf(marked.txns.getValue("i1")))
        // 닷새 뒤 KB에서 대출 20만원이 나감 → 가족 돈에서 나간 것, 용돈에서 안 뺌
        val loan = myOut("o1", 200_000, ms(9, 0, d = 25))
        val j = Moves.judge(marked, loan, 350_000, rule = null)
        assertEquals(Kind.TRANSFER, j.kind)
        assertEquals("i1", Moves.fromOf(j))
        val after = marked.with(listOf(j))
        assertEquals(0L, Stats.allowanceSpent(after, "h", p))
        assertEquals("아내 통장에서 보낸 가족 돈(11/20 200,000원)으로 나간 돈이라 용돈에서 뺐어요", Moves.reason(after, j, "h"))
        // 아내 가계부는 그대로: 남편 통장으로 보낸 20만원이 지출 한 번
        assertEquals(200_000L, Stats.expense(Stats.entries(after, p, "w")))
    }

    @Test fun allowanceTransferIsNotFamilyMoney() {
        val sent = wifeOut("w1", 350_000, ms(13, 3))
        val got = myIn("i1", 350_000, ms(13, 3), issuer = "토스뱅크")
        assertNull(Moves.fundSource(ledger(sent, got), got))
        // 용돈 고정지출에 이어진 이체도 아님(금액이 달라도)
        val linkedToRule = wifeOut("w2", 300_000, ms(13, 3)).copy(recurringId = "rec.allowance.h")
        val got2 = myIn("i2", 300_000, ms(13, 3), issuer = "토스뱅크")
        assertNull(Moves.fundSource(ledger(linkedToRule, got2), got2))
    }

    @Test fun familyMoneyOnlyCoversSameBankSameAmountWithinFiveWeeks() {
        val sent = wifeOut("w1", 200_000, ms(13, 13))
        val got = Moves.asFund(myIn("i1", 200_000, ms(13, 13)), sent)
        val s = ledger(sent, got)
        assertNull(Moves.fundFor(s, myOut("o1", 200_000, ms(9, 0, d = 25), issuer = "토스뱅크"))) // 다른 은행
        assertNull(Moves.fundFor(s, myOut("o2", 150_000, ms(9, 0, d = 25)))) // 다른 금액
        assertNull(Moves.fundFor(s, myOut("o3", 200_000, ms(14, 0, d = 26, mo = 12)))) // 36일 뒤
        assertNull(Moves.fundFor(s, myOut("o4", 200_000, ms(9, 0, d = 19)))) // 받기 전날
        assertEquals("i1", Moves.fundFor(s, myOut("o5", 200_000, ms(9, 0, d = 24, mo = 12)))?.id) // 34일 뒤
    }

    @Test fun oneFamilyMoneyCoversOneWithdrawal() {
        val sent = wifeOut("w1", 200_000, ms(13, 13))
        val got = Moves.asFund(myIn("i1", 200_000, ms(13, 13)), sent)
        val first = Moves.judge(ledger(sent, got), myOut("o1", 200_000, ms(9, 0, d = 25)), 350_000, null)
        assertEquals("i1", Moves.fromOf(first))
        val second = Moves.judge(ledger(sent, got, first), myOut("o2", 200_000, ms(10, 0, d = 26)), 350_000, null)
        assertEquals(Kind.EXPENSE, second.kind)
        assertEquals(Moves.AUTO, second.move)
    }

    @Test fun lateSyncedWifeWithdrawalMarksFundAndRejudgesPayment() {
        // 대출이 먼저 '쓴 돈'으로 기록되고, 아내 출금이 늦게 동기화된 경우
        val got = myIn("i1", 200_000, ms(13, 13))
        val loan = Moves.judge(ledger(got), myOut("o1", 200_000, ms(9, 0, d = 21)), 350_000, null)
        assertEquals(Moves.AUTO, loan.move)
        val sent = wifeOut("w1", 200_000, ms(13, 13))
        val s1 = ledger(got, loan, sent)
        val marks = Moves.fundUpdates(s1, listOf(null to sent), "h", install)
        assertEquals(listOf("i1"), marks.map { it.id })
        val s2 = s1.with(marks)
        // 가족 돈 표시가 바뀐 입금 → 그 돈으로 나갈 수 있는 출금을 다시 판정
        val again = Moves.affected(s2, listOf(got to marks.single()), "h", install)
        assertEquals(listOf("o1"), again.map { it.id })
        assertEquals("i1", Moves.fromOf(Moves.judge(s2, again.single(), 350_000, null)))
    }

    @Test fun removedSourceOrNotFundUndoesIt() {
        val sent = wifeOut("w1", 200_000, ms(13, 13))
        val got = Moves.asFund(myIn("i1", 200_000, ms(13, 13)), sent)
        val loan = Moves.judge(ledger(sent, got), myOut("o1", 200_000, ms(9, 0, d = 25)), 350_000, null)
        val s = ledger(sent, got, loan)
        // 아내 출금이 지워지면 가족 돈 표시도 빠지고, 그 돈으로 본 출금은 다시 판정 대상
        val gone = s.copy(txns = s.txns - "w1")
        val cleared = Moves.fundUpdates(gone, listOf(sent to null), "h", install)
        assertNull(cleared.single().move)
        val s2 = gone.with(cleared)
        assertEquals(listOf("o1"), Moves.affected(s2, listOf(got to cleared.single()), "h", install).map { it.id })
        assertEquals(Moves.AUTO, Moves.judge(s2, loan, 350_000, null).move)
        // "가족 돈 아니에요"는 다시 표시하지 않음
        val apart = s.with(listOf(got.copy(move = Moves.APART)))
        assertTrue(Moves.fundUpdates(apart, listOf(null to sent), "h", install).isEmpty())
    }

    // --- 예전 기록 정리 -------------------------------------------------------------------------

    @Test fun staleWithdrawalsAreOfferedOnlyWhenTheCountChanges() {
        // 판정 전(0.3.0)에 기록된 범위 밖 은행 출금들(move 없음)
        val salary = myOut("o1", 3_000_000, ms(12, 59), merchant = "급여")
        val small = myOut("o2", 20_000, ms(14, 0), merchant = "홍길동")
        val card = myOut("o3", 9_000, ms(15, 0), issuer = "토스뱅크", merchant = "편의점")
        val judged = myOut("o4", 500_000, ms(16, 0)).copy(move = Moves.AUTO)
        val sent = wifeOut("w1", 200_000, ms(13, 13))
        val fund = Moves.asFund(myIn("i1", 200_000, ms(13, 13)), sent)
        val loan = myOut("o5", 200_000, ms(9, 0, d = 21))
        val kept = myOut("o6", 400_000, ms(17, 0), merchant = "적금")
        val s = ledger(salary, small, card, judged, sent, fund, loan, kept)
        val bankOuts = setOf("o1", "o2", "o4", "o5", "o6")
        val got = Moves.staleCandidates(s, "h", { it.id in bankOuts }) { t -> if (t.merchant == "적금") false else null }
        // 급여(월 용돈보다 큼)와 가족 돈으로 나간 대출만. 작은 송금·카드 결제·이미 판정한 것·쓴 돈으로 기억한 것은 아님
        assertEquals(listOf("o5", "o1"), got.map { it.first.id })
        val (_, nextSalary) = got.single { it.first.id == "o1" }
        assertEquals(Kind.TRANSFER, nextSalary.kind)
        assertEquals(Moves.LARGE, nextSalary.move)
        assertEquals(false, nextSalary.needsReview)
        assertEquals("i1", Moves.fromOf(got.single { it.first.id == "o5" }.second))
        // 정리하면 급여·대출만 남은 용돈에서 빠지고, 작은 송금·카드 결제·이미 판정한 것·쓴 돈으로 기억한 것은 그대로
        val after = s.with(got.map { it.second })
        assertEquals(20_000L + 9_000L + 500_000L + 400_000L, Stats.allowanceSpent(after, "h", p))
    }

    @Test fun staleWithdrawalsDoNotShareOneDepositOrFund() {
        // #54 리뷰: 같은 가족 돈에 맞는 예전 출금 두 건 → 먼저 나간 것만 가족 돈에서 나감, 뒤의 것은 쓴 돈(용돈보다 작아 목록에 없음)
        val sent = wifeOut("w1", 200_000, ms(13, 13))
        val fund = Moves.asFund(myIn("i1", 200_000, ms(13, 13)), sent)
        val a = myOut("o1", 200_000, ms(9, 0, d = 21))
        val b = myOut("o2", 200_000, ms(9, 0, d = 22))
        val s1 = ledger(sent, fund, a, b)
        assertEquals(listOf("o1"), Moves.staleCandidates(s1, "h", { true }) { null }.map { it.first.id })
        // 같은 입금(아내 통장)에 맞는 큰 출금 두 건 → 하나만 그 입금과 짝, 다른 하나는 금액 기준(용돈보다 큼)
        val deposit = Txn("d1", Kind.INCOME, 1_000_000, ms(13, 0), accountId = wifeBank.id, merchant = "남편", memberId = "w",
            source = Source.SMS, issuer = "신한은행", createdBy = "install-w")
        val c = myOut("o3", 1_000_000, ms(12, 58), merchant = "급여")
        val d = myOut("o4", 1_000_000, ms(13, 2), merchant = "급여")
        val got = Moves.staleCandidates(ledger(deposit, c, d), "h", { true }) { null }.associate { it.first.id to it.second }
        assertEquals(setOf("o3", "o4"), got.keys)
        assertEquals(listOf("d1"), got.values.mapNotNull { Moves.pairOf(it) })
        assertEquals(1, got.values.count { it.move == Moves.LARGE })
    }

    @Test fun familyMoneyDepositIsNotAJudgedWithdrawal() {
        // #54 리뷰: 가족 돈 입금·"가족 돈 아니에요" 입금에 출금 정정 카드가 뜨지 않게
        val sent = wifeOut("w1", 200_000, ms(13, 13))
        val fund = Moves.asFund(myIn("i1", 200_000, ms(13, 13)), sent)
        assertEquals(false, Moves.withdrawalMarked(fund))
        assertEquals(false, Moves.withdrawalMarked(fund.copy(move = Moves.APART)))
        assertTrue(Moves.withdrawalMarked(myOut("o1", 20_000, ms(14, 0)).copy(move = Moves.AUTO)))
        assertTrue(Moves.withdrawalMarked(Moves.moved(myOut("o2", 900_000, ms(14, 0)), Moves.LARGE)))
        assertEquals(false, Moves.withdrawalMarked(myOut("o3", 20_000, ms(14, 0))))
    }
    // --- D-32: 금액이 다른 가족 돈을 손으로 잇기 -------------------------------------------------------

    /** 아내가 대출 돈을 보냈고 사흘 뒤 KB에서 그보다 적은 대출이 나감: 금액이 달라 앱이 잇지 못하니 사용자가 고름. */
    @Test fun differentAmountPaymentCanBePickedAndStaysPicked() {
        val sent = wifeOut("w1", 150_000, ms(13, 13))
        val got = Moves.asFund(myIn("i1", 150_000, ms(13, 13)), sent)
        val loan = Moves.judge(ledger(sent, got), myOut("o1", 143_520, ms(10, 11, d = 23)), 350_000, null)
        assertEquals(Moves.AUTO, loan.move)
        val s = ledger(sent, got, loan)
        assertEquals("나만 보는 통장 내역이라 가계부 합계(쓴 돈)에는 원래 들어가지 않아요.", Moves.householdNote(s, loan, "h"))
        assertEquals(listOf("i1"), Moves.fundChoices(s, loan).map { it.id })
        val picked = Moves.pick(loan, got)
        assertEquals(Kind.TRANSFER, picked.kind)
        assertEquals("i1", Moves.fromOf(picked))
        assertTrue(Moves.picked(picked))
        val after = s.with(listOf(picked))
        assertEquals(0L, Stats.allowanceSpent(after, "h", p))
        assertEquals("아내 통장에서 보낸 가족 돈(11/20 150,000원)으로 냈다고 이어서 용돈에서 뺐어요", Moves.reason(after, picked, "h"))
        assertEquals("가계부에는 이 돈이 아내 신한은행 출금(11/20 150,000원)으로 이미 쓴 돈에 들어가 있어요. 여기서 또 쓴 돈으로 세면 두 번 들어가요.",
            Moves.householdNote(after, picked, "h"))
        // 가계부는 아내 출금 150,000원 한 번만
        assertEquals(150_000L, Stats.expense(Stats.entries(after, p, "w")))
        // 사용자가 이은 것은 장부가 바뀌어도 다시 판정하지 않고, 그 가족 돈은 다른 같은 금액 출금이 가져가지 않음
        assertTrue(Moves.affected(after, listOf(null to got), "h", install).isEmpty())
        assertNull(Moves.fundFor(after, myOut("o2", 150_000, ms(9, 0, d = 25))))
        assertTrue(Moves.fundChoices(after, myOut("o3", 5_000, ms(9, 0, d = 26))).isEmpty())
    }

    @Test fun fundChoicesNeedSameBankAndFiveWeeks() {
        val sent = wifeOut("w1", 150_000, ms(13, 13))
        val got = Moves.asFund(myIn("i1", 150_000, ms(13, 13)), sent)
        val plain = myIn("i2", 50_000, ms(8, 20, d = 23)) // 가족 돈 표시가 없는 입금
        val s = ledger(sent, got, plain)
        assertTrue(Moves.fundChoices(s, myOut("o1", 143_520, ms(10, 0, d = 23), issuer = "토스뱅크")).isEmpty())
        assertTrue(Moves.fundChoices(s, myOut("o2", 143_520, ms(10, 0, d = 26, mo = 12))).isEmpty())
        assertTrue(Moves.fundChoices(s, myOut("o3", 143_520, ms(10, 0, d = 19))).isEmpty())
        assertEquals(listOf("i1"), Moves.fundChoices(s, myOut("o4", 143_520, ms(10, 0, d = 23))).map { it.id })
    }
}
