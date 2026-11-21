package com.jeiel85.doran

import com.jeiel85.doran.capture.CaptureProcessor
import com.jeiel85.doran.capture.Parser
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 체크카드를 신용카드로 만들던 문제(2026-11-20 가족 리뷰, DECISIONS.md D-29): 아내 폰이 문자로 만든 신한카드가 체크카드인데
 * 신용카드로 생겨 자산 화면에서 '갚을 돈'으로 잡히고 홈 카드가 결제일을 물었습니다.
 * 카드사 문구는 실제 원문을 아직 못 받아 합성 예시입니다(BACKLOG '확인이 필요한 형식').
 */
class CheckCardTest {
    private val now = LocalDateTime.of(2026, 11, 20, 13, 0)
    private val today: LocalDate = now.toLocalDate()
    private fun p(text: String) = Parser.parse(text, now)
    private fun ms(d: Int, h: Int = 12) = Fmt.millis(LocalDateTime.of(2026, 11, d, h, 0))

    private fun card(id: String, last4: String?, type: AccountType = AccountType.CARD, billingDay: Int? = null, owner: String? = "w") =
        Account(id, "신한카드" + (last4?.let { " $it" } ?: ""), type, ownerId = owner, institution = "신한카드", last4 = last4, billingDay = billingDay)

    @Test fun cardNoticeSaysCheckCard() {
        assertTrue(p("신한카드(4321) 체크승인 장*희 11,810원 11/20 12:34 OO의원")!!.checkCard)
        assertTrue(p("[KB국민카드] 체크카드 승인 8,900원 11/20 08:12 GS25")!!.checkCard)
        assertTrue(p("현대카드 체크 취소 12,000원 11/20 09:00 올리브영")!!.checkCard)
        // 은행이 낸 체크카드 승인은 예전처럼 체크카드
        assertTrue(p("[카카오뱅크] 체크카드 승인 9,500원 11/17 12:10 김밥천국")!!.checkCard)
    }

    @Test fun creditNoticeAndMerchantWordsAreNotCheckCard() {
        assertFalse(p("신한카드(5678)승인 김*수 45,000원(할부 6개월)11/15 10:20 가구나라")!!.checkCard)
        // 가맹점 이름의 '체크'로 신용카드를 체크카드로 바꾸면 안 됨
        assertFalse(p("현대카드 승인 김*수 120,000원 일시불 11/20 15:00 체크인호텔")!!.checkCard)
        assertFalse(p("삼성카드 승인 32,000원 11/20 09:00 헬스체크의원")!!.checkCard)
    }

    @Test fun autoMadeCreditCardBecomesCheckOnlyForTheSameCard() {
        val made = card("a", "4321")
        val picked = CaptureProcessor.pickCard(listOf(made), "신한카드", "4321", "w", check = true)!!
        assertEquals(AccountType.CHECK, CaptureProcessor.refreshCard(picked, "4321", check = true).type)
        // 결제일을 정한 카드는 사람이 신용카드로 맞춘 것이라 그대로
        assertEquals(AccountType.CARD, CaptureProcessor.refreshCard(made.copy(billingDay = 14), "4321", check = true).type)
        // 끝자리를 모르는 알림으로는 바꾸지 않음(같은 카드사의 신용카드일 수 있어서)
        assertEquals(AccountType.CARD, CaptureProcessor.refreshCard(made, null, check = true).type)
        // 신용카드 알림은 바꾸지 않음
        assertEquals(AccountType.CARD, CaptureProcessor.refreshCard(made, "4321", check = false).type)
        // 끝자리를 모르던 카드는 끝자리만 얻고 종류는 그대로(어느 알림으로 생긴 카드인지 몰라서)
        val loose = CaptureProcessor.refreshCard(card("x", null), "4321", check = true)
        assertEquals("4321", loose.last4)
        assertEquals(AccountType.CARD, loose.type)
    }

    @Test fun pickPrefersSameDigitsThenSameKind() {
        val both = listOf(card("c", null), card("k", null, AccountType.CHECK))
        assertEquals("k", CaptureProcessor.pickCard(both, "신한카드", null, "w", check = true)!!.id)
        assertEquals("c", CaptureProcessor.pickCard(both, "신한카드", null, "w", check = false)!!.id)
        // 끝자리가 같으면 종류보다 먼저
        val numbered = listOf(card("c", "1234"), card("k", "4321", AccountType.CHECK))
        assertEquals("c", CaptureProcessor.pickCard(numbered, "신한카드", "1234", "w", check = true)!!.id)
        // 끝자리가 다른 카드만 있거나 다른 사람 카드면 새로 만듦
        assertNull(CaptureProcessor.pickCard(listOf(card("c", "1234")), "신한카드", "4321", "w", check = true))
        assertNull(CaptureProcessor.pickCard(listOf(card("h", "4321", owner = "h")), "신한카드", "4321", "w", check = true))
    }

    /**
     * #59 리뷰: 체크카드 알림의 '잔액'(연결 통장 잔액)이 카드 계좌의 기준 잔액으로 적히면 잔액을 세는 계좌로 바뀌어
     * 통장 잔액이 순자산에 두 번 들어감 → 잔액을 세는 계좌에만 적고, 신용카드에서 체크카드로 바꿀 때 남은 값을 지움.
     */
    @Test fun noticeBalanceNeverTurnsCardIntoBalanceAccount() {
        val check = card("k", "4321", AccountType.CHECK)
        assertEquals(check, CaptureProcessor.withBalance(check, 512_000, ms(20)))
        val credit = card("c", "1234")
        assertEquals(credit, CaptureProcessor.withBalance(credit, 512_000, ms(20)))
        val bank = Account("b", "신한은행", AccountType.BANK, ownerId = "w", institution = "신한은행")
        assertEquals(512_000L, CaptureProcessor.withBalance(bank, 512_000, ms(20)).anchor)
        // 예전부터 잔액을 세던 체크카드는 알림 잔액으로 계속 맞춤
        val tracked = check.copy(anchor = 100_000, anchorAt = ms(1))
        assertEquals(512_000L, CaptureProcessor.withBalance(tracked, 512_000, ms(20)).anchor)

        // 0.3.3까지 신용카드 계좌에 적혀 있던 알림 잔액은 체크카드로 바뀔 때 지워짐
        val oldCredit = card("a", "4321").copy(anchor = 512_000, anchorAt = ms(19))
        val converted = CaptureProcessor.refreshCard(oldCredit, "4321", check = true)
        assertEquals(AccountType.CHECK, converted.type)
        assertNull(converted.anchor)
        assertFalse(Stats.keepsBalance(converted))
        // 바뀌지 않는 카드의 값은 그대로
        assertEquals(512_000L, CaptureProcessor.refreshCard(oldCredit, "4321", check = false).anchor)
    }

    /** #59 리뷰: 신용카드 결제가 끝자리 모르는 체크카드 계좌로 가면 갚을 돈에서 빠짐 → 다른 종류로 넘기지 않음. */
    @Test fun creditNeverFallsBackToCheckCard() {
        val unnumberedCheck = listOf(card("k", null, AccountType.CHECK))
        assertNull(CaptureProcessor.pickCard(unnumberedCheck, "신한카드", "9876", "w", check = false))
        assertNull(CaptureProcessor.pickCard(unnumberedCheck, "신한카드", null, "w", check = false))
        // 끝자리 있는 체크카드 알림도 끝자리 모르는 신용카드를 차지하지 않음(그 카드의 신용 결제가 섞이지 않게)
        assertNull(CaptureProcessor.pickCard(listOf(card("c", null)), "신한카드", "4321", "w", check = true))
        // 예외: 0.3.4 전에 체크카드 알림이 만든 신용카드 계좌에는 끝자리 없는 체크카드 알림을 이어 기록
        assertEquals("c", CaptureProcessor.pickCard(listOf(card("c", null)), "신한카드", null, "w", check = true)!!.id)
    }

    @Test fun checkCardWithoutBalanceIsNeitherDebtNorAsset() {
        val check = card("k", "4321", AccountType.CHECK).copy(name = "가계부 카드")
        val pay = Txn("t", Kind.EXPENSE, 11_810, ms(20), accountId = "k", memberId = "w", source = Source.SMS)
        val s = LedgerState(txns = mapOf("t" to pay), accounts = mapOf("k" to check))
        assertFalse(Stats.keepsBalance(check))
        assertEquals(0L, Stats.balance(s, check, today))
        assertEquals(0L, Stats.netWorth(s, today).net)
        assertEquals(11_810L, Stats.card(s, check, today).monthSpent)

        // 같은 결제가 신용카드에 있으면 결제일 전까지 갚을 돈(지금까지 체크카드가 이렇게 보였음)
        val asCredit = check.copy(type = AccountType.CARD)
        assertEquals(-11_810L, Stats.netWorth(s.copy(accounts = mapOf("k" to asCredit)), today).net)

        // 예전에 잔액을 적어 통장처럼 쓰던 체크카드는 그대로 잔액을 셈
        val tracked = check.copy(anchor = 100_000, anchorAt = ms(1))
        assertTrue(Stats.keepsBalance(tracked))
        assertEquals(88_190L, Stats.balance(s.copy(accounts = mapOf("k" to tracked)), tracked, today))
    }
}
