package com.jeiel85.doran

import com.jeiel85.doran.capture.Parser
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Banks
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Household
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 은행을 연결하지 않고 알림만으로 통장 관리(DECISIONS.md D-33): 계좌번호 끝자리로 같은 은행 통장 나누기, 체크카드를 돈이 나가는 통장에
 * 잇기, 나만 보는 통장의 잔액, 폰마다 다른 한 달 시작일. 알림 문구는 합성 예시입니다(실명·실번호 없음).
 */
class BanksTest {
    private val now = LocalDateTime.of(2026, 11, 23, 12, 0)
    private val t0 = 1_795_392_000_000L // 2026-11-23 09:00 KST
    private val min = 60_000L
    private fun at(m: Int) = t0 + m * min
    private val me = "m1"
    private val kb = "KB국민은행"

    private fun bank(id: String, last4: String? = null, owner: String? = me, order: Int = 0, institution: String = kb, anchor: Long? = null, anchorAt: Long? = null) =
        Account(id = id, name = id, type = AccountType.BANK, ownerId = owner, institution = institution, last4 = last4, order = order,
            anchor = anchor, anchorAt = anchorAt)

    private fun check(id: String, payFrom: String? = null, institution: String = "KB국민카드", owner: String? = me) =
        Account(id = id, name = id, type = AccountType.CHECK, ownerId = owner, institution = institution, payFromId = payFrom)

    private fun txn(id: String, account: String?, amount: Long, m: Int, kind: Kind = Kind.EXPENSE, private: Boolean = false, cancelOf: String? = null,
                    to: String? = null, issuer: String? = null) =
        Txn(id = id, kind = kind, amount = amount, at = at(m), accountId = account, toAccountId = to, memberId = me, source = Source.SMS,
            private = private, cancelOf = cancelOf, issuer = issuer)

    private fun state(accounts: List<Account>, txns: List<Txn> = emptyList(), startDay: Int = 1) =
        LedgerState(household = Household(monthStartDay = startDay), accounts = accounts.associateBy { it.id }, txns = txns.associateBy { it.id })

    // --- 알림의 계좌번호 끝자리 ---------------------------------------------------------------------------

    @Test fun bankNoticesCarryTheVisibleAccountTail() {
        assertEquals("789", Parser.parse("[KB]11/17 09:01\n123456**789\n홍길동\n전자금융출금\n50,000\n잔액1,234,000", now)!!.acct)
        assertEquals("123456", Parser.parse("[Web발신]\n[신한]11/25 10:00\n110-***-123456\n입금 3,200,000\n잔액 5,600,000", now)!!.acct)
        assertEquals("567890", Parser.parse("[농협]12/14 06:30\n123-****-5678-90\n출금 21,000원\n잔액90,000원", now)!!.acct)
        // 앱 푸시처럼 계좌번호를 가린 꼴이 다른 것도 마지막 '*' 뒤 숫자
        assertEquals("789", Parser.parse("출금 32,000원\n홍*동님 11/20 12:59 123456-**-***789 ○○회비 스마트폰출금 32,000 잔액0", now, hint = "KB스타뱅킹")!!.acct)
    }

    @Test fun cardNoticesHaveNoAccountTail() {
        assertNull(Parser.parse("[현대카드] 승인 홍*동 12,000원 일시불 11/23 10:00 ○○마트 누적 120,000원", now)!!.acct)
        assertNull(Parser.parse("KB국민체크(1234) 홍*동님 11/23 10:00 12,000원 ○○마트 사용", now)!!.acct)
    }

    @Test fun tailMatchAcceptsEitherSideAsTheEnd() {
        assertTrue(Banks.tailMatch("789", "789"))
        assertTrue(Banks.tailMatch("56789", "789")) // 사람이 더 길게 적음
        assertTrue(Banks.tailMatch("3456", "123456"))
        assertFalse(Banks.tailMatch("1234", "123456")) // 앞자리는 끝자리가 아님
        assertFalse(Banks.tailMatch("321", "789"))
        assertFalse(Banks.tailMatch("9", "789")) // 한 자리로는 맞춰 보지 않음
    }

    // --- 같은 은행 통장 나누기 -------------------------------------------------------------------------------

    @Test fun oneUnnumberedAccountTakesEveryNoticeOfItsBank() {
        val a = bank("a")
        assertSame(a, Banks.pick(listOf(a), kb, "789", me))
        assertSame(a, Banks.pick(listOf(a), kb, null, me))
        assertNull(Banks.newTail(listOf(a), kb, "789", me)) // 사람이 끝자리를 적지 않은 은행은 저절로 나누지 않음
    }

    @Test fun registeredTailsSplitTheSameBank() {
        val living = bank("living", last4 = "789", order = 0)
        val saving = bank("saving", last4 = "321", order = 1)
        val all = listOf(living, saving)
        assertSame(living, Banks.pick(all, kb, "789", me))
        assertSame(saving, Banks.pick(all, kb, "54321", me))
        // 끝자리가 없는 알림(앱 푸시 등)은 그 은행의 첫 통장으로
        assertSame(living, Banks.pick(all, kb, null, me))
        // 적어 둔 어느 통장과도 다른 끝자리: 그 끝자리로 새 통장
        assertNull(Banks.pick(all, kb, "555", me))
        assertEquals("555", Banks.newTail(all, kb, "555", me))
    }

    @Test fun anUnnumberedAccountCatchesTheRest() {
        val main = bank("main", order = 0)
        val saving = bank("saving", last4 = "321", order = 1)
        val all = listOf(saving, main)
        assertSame(saving, Banks.pick(all, kb, "321", me))
        assertSame(main, Banks.pick(all, kb, "999", me))
        assertSame(main, Banks.pick(all, kb, null, me))
    }

    @Test fun pickMatchesBankAliasesAndSkipsOtherPeople() {
        val typed = bank("typed", institution = "국민은행") // 사람이 계좌 화면에 적은 이름
        val hers = bank("hers", owner = "m2")
        assertSame(typed, Banks.pick(listOf(hers, typed), kb, null, me))
        assertNull(Banks.pick(listOf(hers), kb, null, me))
        assertNull(Banks.pick(listOf(bank("sh", institution = "신한은행")), kb, null, me))
    }

    // --- 체크카드 → 돈이 나가는 통장 -----------------------------------------------------------------------

    @Test fun aCheckCardLinksToItsOnlyBankOfTheSameGroup() {
        val a = bank("a")
        val card = check("c")
        assertEquals("a", Banks.autoPayFrom(listOf(a, card), card).payFromId)
        // 같은 금융그룹 통장이 둘이면 어느 통장인지 몰라 잇지 않음
        assertNull(Banks.autoPayFrom(listOf(a, bank("b", last4 = "321"), card), card).payFromId)
        // 다른 사람 통장, 다른 은행 통장으로는 잇지 않음
        assertNull(Banks.autoPayFrom(listOf(bank("x", owner = "m2"), bank("y", institution = "신한은행"), card), card).payFromId)
        // 이미 이은 카드와 신용카드는 그대로
        val linked = check("c2", payFrom = "z")
        assertSame(linked, Banks.autoPayFrom(listOf(a, linked), linked))
        val credit = card.copy(type = AccountType.CARD)
        assertSame(credit, Banks.autoPayFrom(listOf(a, credit), credit))
    }

    @Test fun linkedCheckCardPaymentsLeaveTheBankBalance() {
        val a = bank("a", anchor = 500_000, anchorAt = at(0))
        val card = check("c", payFrom = "a")
        val s = state(listOf(a, card), listOf(
            txn("before", "c", 9_000, -10), // 잔액 알림 전 결제는 이미 잔액에 들어 있음
            txn("pay", "c", 12_000, 10),
            txn("cancel", "c", 2_000, 20, cancelOf = "pay"), // 부분 취소는 원래 결제에서 상쇄
            txn("mine", "c", 7_000, 30, private = true), // 나만 보는 용돈은 이 폰에만 있어 통장 잔액에 넣지 않음
            txn("out", "a", 30_000, 40),
            txn("in", "a", 100_000, 50, kind = Kind.INCOME),
        ))
        assertEquals(500_000L - 10_000 - 30_000 + 100_000, Stats.balance(s, a, LocalDate.of(2026, 11, 23)))
        // 통장 화면 목록: 통장 거래와 이은 카드 결제(취소 엔티티·나만 보는 것 빼고)
        assertEquals(listOf("in", "out", "pay", "before"), Banks.flow(s, a).map { it.id })
        assertEquals(1, Banks.sign(a, s.txns.getValue("in")))
        assertEquals(-1, Banks.sign(a, s.txns.getValue("pay")))
    }

    @Test fun aRefundAfterTheBalanceNoticeComesBackToTheBank() {
        // 결제(−10분) → 은행 잔액 알림(0분, 결제가 이미 빠진 잔액) → 취소(+10분, 잔액 없는 알림): 돌아온 돈만 더함(#77 리뷰)
        val a = bank("a", anchor = 100_000, anchorAt = at(0))
        val card = check("c", payFrom = "a")
        val s = state(listOf(a, card), listOf(
            txn("pay", "c", 30_000, -10),
            txn("refund", "c", 10_000, 10, cancelOf = "pay"),
        ))
        assertEquals(110_000L, Stats.balance(s, a))
    }

    @Test fun aRefundOfADirectBankPaymentAfterTheNoticeComesBack() {
        // 통장에 바로 기록된 결제(토스뱅크 체크카드 등)도 이은 카드와 같게: 결제(−10분) → 잔액 알림(0분) → 취소(+10분)면 돌아온 돈만 더함(#78)
        val a = bank("a", anchor = 100_000, anchorAt = at(0))
        val s = state(listOf(a), listOf(
            txn("pay", "a", 30_000, -10),
            txn("refund", "a", 10_000, 10, cancelOf = "pay"),
        ))
        assertEquals(110_000L, Stats.balance(s, a))
        // 결제와 취소가 둘 다 알림 뒤면 결제 − 취소
        val both = state(listOf(a), listOf(txn("pay", "a", 30_000, 5), txn("refund", "a", 10_000, 10, cancelOf = "pay")))
        assertEquals(80_000L, Stats.balance(both, a))
    }

    @Test fun aCheckCardThatKeepsItsOwnBalanceIsNotSubtractedTwice() {
        val a = bank("a", anchor = 500_000, anchorAt = at(0))
        val old = check("c", payFrom = "a").copy(anchor = 80_000, anchorAt = at(0)) // 예전에 통장처럼 잔액을 적은 체크카드(D-29)
        val s = state(listOf(a, old), listOf(txn("pay", "c", 12_000, 10)))
        assertTrue(Banks.linkedCards(s, a).isEmpty())
        assertEquals(500_000L, Stats.balance(s, a))
        // 저절로 잇지도 않음: 그 카드 알림의 잔액이 통장에도 적혀 같은 돈이 두 자산에 들어가지 않게(#77 리뷰)
        val unlinked = old.copy(payFromId = null)
        assertSame(unlinked, Banks.autoPayFrom(listOf(a, unlinked), unlinked))
    }

    @Test fun transfersShowOnBothAccounts() {
        val a = bank("a")
        val b = bank("b", last4 = "321")
        val s = state(listOf(a, b), listOf(txn("mv", "a", 50_000, 0, kind = Kind.TRANSFER, to = "b")))
        assertEquals(-1, Banks.sign(a, s.txns.getValue("mv")))
        assertEquals(1, Banks.sign(b, s.txns.getValue("mv")))
        assertEquals(listOf("mv"), Banks.flow(s, b).map { it.id })
    }

    @Test fun aPurchaseMovedToAllowanceShowsOnceOnTheFamilyCard() {
        // 가족 카드 결제를 용돈으로 옮기면 원본은 나만 보는 것(private)이 되고, 공유되는 금액 자리(personal)가 대신 들어감(D-20)
        val family = check("fc").copy(family = true)
        val s = state(listOf(family), listOf(
            txn("orig", "fc", 15_000, 0, private = true),
            txn("seat", "fc", 15_000, 0).copy(personal = true),
            txn("other", "fc", 4_000, 5),
        ))
        assertEquals(listOf("other", "seat"), Banks.flow(s, family).map { it.id }) // #77 리뷰: 원본과 자리가 함께 보이면 두 번
    }

    // --- 나만 보는 통장 ---------------------------------------------------------------------------------------

    private val husband = Member(me, "남편", share = Member.SHARE_PICKED, shareIssuers = listOf("현대카드", "KB국민카드"))

    @Test fun privateBankKeepsItsFlowAfterNoticeTextExpires() {
        // 범위 밖 KB국민은행: 잔액 알림(0분) 뒤 체크카드 결제(나만 보는 것), 기록하지 않은 카드값 출금, 공유한 KB국민카드 체크카드 결제
        val sharedCheck = check("kbc") // 카드만 공유 → 공유 거래로 기록된 체크카드 계좌
        val s = LedgerState(members = mapOf(me to husband), accounts = mapOf("kbc" to sharedCheck), txns = listOf(
            txn("p1", null, 12_000, 10, private = true, issuer = "KB국민카드").copy(createdBy = "i1"),
            txn("p2", "kbc", 8_000, 20, issuer = "KB국민카드").copy(createdBy = "i1"),
            txn("credit", null, 50_000, 30, private = true, issuer = "현대카드").copy(createdBy = "i1"), // 통장 밖
        ).associateBy { it.id })
        val bill = Banks.Bill(kb, at(40), 100_000, "현대카드")
        // 알림 원문(30일)이 지워진 뒤: 이 폰에 남긴 체크카드 결제 id와 카드값 출금만으로
        val kept = Banks.Kept(listOf(Banks.Check("p1", kb, at(10))), listOf(bill))
        val one = Banks.privateBanks(s, me, "i1", mapOf(kb to (500_000L to at(0))), kept.checks.mapTo(HashSet()) { it.id }, kept.bills).single()
        assertEquals(kb, one.bank)
        assertEquals(500_000L - 12_000 - 8_000 - 100_000, one.balance)
        assertEquals(listOf("bill:$kb:${at(40)}:100000", "p2", "p1"), one.txns.map { it.id })
        // 알림 원문에서 읽은 같은 카드값 출금이 함께 와도 한 번만
        val twice = Banks.privateBanks(s, me, "i1", mapOf(kb to (500_000L to at(0))), setOf("p1"), listOf(bill, bill.copy())).single()
        assertEquals(one.balance, twice.balance)
        // 체크카드였는지 모르면(남기지 않았고 원문도 없음) 그 결제는 통장 흐름 밖
        val unknown = Banks.privateBanks(s, me, "i1", mapOf(kb to (500_000L to at(0))), emptySet(), emptyList()).single()
        assertEquals(500_000L - 8_000, unknown.balance)
    }

    @Test fun keptFlowsSurviveASaveAndStayBounded() {
        val k = Banks.Kept(listOf(Banks.Check("p1", kb, at(1)), Banks.Check("p1", kb, at(1))), listOf(Banks.Bill(kb, at(2), 3_000, "현대카드")))
        assertEquals(Banks.Kept(listOf(Banks.Check("p1", kb, at(1))), k.bills), Banks.Kept.from(k.pruned().json()))
        val many = Banks.Kept((1..Banks.MAX_KEPT + 20).map { Banks.Check("c$it", kb, at(it)) }).pruned()
        assertEquals(Banks.MAX_KEPT, many.checks.size)
        assertEquals("c21", many.checks.first().id) // 가장 오래된 것부터 버림
        assertEquals(Banks.Kept(), Banks.Kept.from("{깨진"))
    }

    @Test fun aBankWithdrawalForALinkedCheckCardPaymentIsTheSameMoney() {
        val a = bank("a")
        val card = check("c", payFrom = "a")
        val other = check("o") // 다른 통장(또는 이지 않은) 카드
        val s = state(listOf(a, card, other), listOf(txn("pay", "c", 12_000, 0), txn("x", "o", 12_000, 1)))
        val win = 3 * 60_000L
        assertEquals("pay", Banks.linkedCardPayment(s, "a", 12_000, at(2), win)?.id)
        assertNull(Banks.linkedCardPayment(s, "a", 12_000, at(5), win)) // 3분 넘음
        assertNull(Banks.linkedCardPayment(s, "a", 11_000, at(1), win)) // 금액 다름
        assertNull(Banks.linkedCardPayment(s, "b", 12_000, at(1), win)) // 이은 카드 없음
    }

    @Test fun keptFlowsAfterTheLastBalanceNoticeAreNeverDropped() {
        // 잔액 알림(0분) 뒤 결제가 500건을 넘어도 잔액에 들어갈 것은 모두 남기고, 알림 전 것은 최근 500건만(#78)
        val after = (1..Banks.MAX_KEPT + 20).map { Banks.Check("a$it", kb, at(it)) }
        val before = (1..30).map { Banks.Check("b$it", kb, at(-100 + it)) }
        val k = Banks.Kept(before + after).pruned(mapOf(kb to (100_000L to at(0))))
        assertEquals(after.size, k.checks.count { it.id.startsWith("a") })
        // 알림 전 것은 (이미 넉넉해) 그대로 30건
        assertEquals(30, k.checks.count { it.id.startsWith("b") })
        // 알림이 없는 은행은 예전처럼 최근 500건
        assertEquals(Banks.MAX_KEPT, Banks.Kept(after).pruned(emptyMap()).checks.size)
    }


    @Test fun privateBalanceAddsTheFlowAfterTheLastBalanceNotice() {
        val flows = listOf(
            txn("old", null, 9_000, -5, private = true, issuer = "토스뱅크"),
            txn("anchor", null, 3_000, 0, private = true, issuer = "토스뱅크"), // 잔액을 알려 준 결제(이미 잔액에 들어 있음)
            txn("pay", null, 12_000, 10, private = true, issuer = "토스뱅크"),
            txn("cancel", null, 2_000, 20, private = true, cancelOf = "pay", issuer = "토스뱅크"),
            txn("in", null, 40_000, 30, kind = Kind.INCOME, private = true, issuer = "토스뱅크"),
        )
        assertEquals(200_000L - 12_000 + 2_000 + 40_000, Banks.privateBalance(200_000, at(0), flows))
        assertNull(Banks.privateBalance(null, null, flows))
    }

    // --- 폰마다 다른 한 달 시작일 ----------------------------------------------------------------------------

    @Test fun eachPhoneCanStartItsMonthOnItsOwnDay() {
        val s = state(emptyList(), startDay = 1)
        assertEquals(1, Stats.viewStart(s, 0)) // 0 = 가족 공통
        assertEquals(21, Stats.viewStart(s, 21))
        assertEquals(1, Stats.viewStart(s, 31)) // 범위 밖은 가족 공통
        val today = LocalDate.of(2026, 11, 23)
        val wife = Stats.current(s, today, Stats.viewStart(s, 0))
        val husband = Stats.current(s, today, Stats.viewStart(s, 21))
        assertEquals(LocalDate.of(2026, 11, 1) to LocalDate.of(2026, 12, 1), wife.start to wife.end)
        assertEquals(LocalDate.of(2026, 11, 21) to LocalDate.of(2026, 12, 21), husband.start to husband.end)
        // 고정지출·용돈 합계는 늘 가족 공통 날짜
        assertEquals(wife, Stats.current(s, today))
    }
}
