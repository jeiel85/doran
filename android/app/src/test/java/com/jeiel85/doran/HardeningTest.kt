package com.jeiel85.doran

import com.jeiel85.doran.capture.Categorizer
import com.jeiel85.doran.capture.Issuers
import com.jeiel85.doran.capture.Parsed
import com.jeiel85.doran.capture.Parser
import com.jeiel85.doran.capture.SamePayment
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.AllowanceAlert
import com.jeiel85.doran.logic.Twins
import com.jeiel85.doran.model.Defaults
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.MerchantRule
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 하드닝 이슈 #13·#15·#19와 버그 #18을 정리한 0.1.14의 규칙들. */
class HardeningTest {
    private val at = LocalDateTime.of(2026, 11, 19, 11, 41)
    private val ms = Fmt.millis(at)
    private val toss = Parser.parse("3,610원 결제\n토스뱅크 체크카드 | 씨유(CU)가산하이엔드6차점 잔액 540원", at, hint = "토스")!!

    // --- #19 다른 결제를 묶지 않기 -------------------------------------------------------------------

    @Test fun moneyOrPointPaymentIsNotJoinedToACardPayment() {
        val card = Parser.payNotice("네이버페이\nCU에서 3,610원 결제되었습니다. (결제 수단: 카드 간편결제)", at, "네이버페이")!!
        assertEquals("결제 수단: 카드 간편결제", card.payMethod)
        assertFalse(card.paidFromBalance)
        assertTrue(SamePayment.matches(card, ms, toss, ms))
        // 네이버페이 머니·포인트로 냈으면 카드·계좌에서 돈이 나가지 않음 → 같은 때 같은 금액의 카드 결제와 묶지 않음
        for (method in listOf("네이버페이 머니", "포인트", "Npay 머니")) {
            val money = Parser.payNotice("네이버페이\nCU에서 3,610원 결제되었습니다. (결제 수단: $method)", at, "네이버페이")!!
            assertTrue(method, money.paidFromBalance)
            assertFalse(method, SamePayment.matches(money, ms, toss, ms))
            assertFalse(method, SamePayment.matches(money, ms, recorded("t1", "토스뱅크")))
        }
        // 계좌 간편결제는 은행 출금과 짝이 될 수 있음
        val account = Parser.payNotice("네이버페이\nCU에서 3,610원 결제되었습니다. (결제 수단: 계좌 간편결제)", at, "네이버페이")!!
        assertFalse(account.paidFromBalance)
        // 결제 수단이 적히지 않은 알림은 지금처럼 금액·시각으로
        val plain = Parser.payNotice("Npay\nGS25 가산점에서 3,610원 결제했어요", at, null)!!
        assertNull(plain.payMethod)
        assertTrue(SamePayment.matches(plain, ms, toss, ms))
    }

    @Test fun joinPartnerMustBeAKnownCardOrBank() {
        val notice = Parser.payNotice("네이버페이\nCU에서 3,610원 결제되었습니다. (결제 수단: 카드 간편결제)", at, "네이버페이")!!
        assertTrue(Issuers.listed("토스뱅크"))
        assertFalse(Issuers.listed("네이버페이"))
        assertFalse(Issuers.listed(null))
        // 규칙으로 기록된 다른 간편결제 앱 거래와는 묶지 않음
        assertFalse(SamePayment.matches(notice, ms, recorded("k", "카카오페이")))
        assertFalse(SamePayment.matches(notice, ms, toss.copy(issuer = "카카오페이"), ms))
    }

    @Test fun overlappingMerchantBreaksTies() {
        assertTrue(SamePayment.related("씨유(CU)가산하이엔드6차점", "CU"))
        assertTrue(SamePayment.related("CU", "CU 가산점"))
        assertFalse(SamePayment.related("스타벅스", "CU"))
        assertFalse(SamePayment.related("", "CU"))
    }

    // --- #18 규칙으로 기록된 간편결제 앱 거래와 카드 거래 ---------------------------------------------------

    @Test fun ruleRecordedPayAppTxnIsTheSamePaymentAsTheCardTxn() {
        val pay = recorded("n1", "네이버페이", merchant = "CU", at = ms - 20_000)
        val card = recorded("c1", "토스뱅크", merchant = "씨유(CU)가산하이엔드6차점")
        assertTrue(SamePayment.sameAsCard(pay, card))
        // 카드 쪽이 남고 간편결제 쪽이 지워짐: 거꾸로는 아님, 같은 거래도 아님
        assertFalse(SamePayment.sameAsCard(card, pay))
        assertFalse(SamePayment.sameAsCard(pay, pay))
        // 금액·시각·취소 여부가 다르면 다른 결제
        assertFalse(SamePayment.sameAsCard(pay.copy(amount = 3_600), card))
        assertFalse(SamePayment.sameAsCard(pay.copy(at = ms + 4 * 60_000), card))
        assertFalse(SamePayment.sameAsCard(pay.copy(cancelOf = "x"), card))
        // 직접 쓴 내역·가족이 보낸 내역은 건드리지 않음
        assertFalse(SamePayment.sameAsCard(pay.copy(source = Source.MANUAL), card))
        assertFalse(SamePayment.sameAsCard(pay.copy(source = Source.FAMILY), card))
        // 카드 쪽에 간편결제 가맹점·앱 이름을 보탬
        val joined = SamePayment.enrich(LedgerState(), card.copy(merchant = "네이버페이"), SamePayment.noticeOf(pay), pay.issuer!!)
        assertEquals("CU 네이버페이", joined.merchant)
        assertEquals("토스뱅크 · 네이버페이", joined.cardLabel)
    }

    // --- #13 실패로 뺀 알림을 그래도 보내기 ----------------------------------------------------------------

    @Test fun failedNoticeCanStillBeReadForSendingAnyway() {
        val failed = "9,900원 결제 실패\n토스뱅크 체크카드 | 구글플레이_TOSS 잔액이 부족해요."
        assertNull(Parser.parse(failed, at, hint = "토스"))
        val anyway = Parser.parse(failed, at, hint = "토스", ignoreFailure = true)
        assertNotNull(anyway)
        assertEquals(9_900L, anyway!!.amount)
        assertEquals("토스뱅크", anyway.issuer)
        // 가맹점 초기값에서 실패 문구는 뗌(에뮬레이터에서 "올리브영 이 부족해요"로 보였던 것)
        assertEquals("올리브영", Parser.withoutFailureWords("올리브영 이 부족해요"))
        assertEquals("구글플레이_TOSS", Parser.withoutFailureWords("구글플레이_TOSS 이 부족해요."))
        assertEquals("스타벅스 성수점", Parser.withoutFailureWords("스타벅스 성수점"))
        assertNull(Parser.withoutFailureWords("부족해요"))
    }

    // --- #19 옛 해석기가 남긴 이름의 카테고리 규칙 ----------------------------------------------------------

    @Test fun ruleLearnedWithTheOldMerchantNameStillApplies() {
        assertEquals("가산하이엔드6차점", Categorizer.legacyName("씨유(CU)가산하이엔드6차점"))
        assertEquals("네이버페이_CJ", Categorizer.legacyName("메가MGC(인증)(2) 네이버페이_CJ"))
        assertNull(Categorizer.legacyName("스타벅스 성수점"))
        val old = MerchantRule(Categorizer.ruleId("가산하이엔드6차점"), "가산하이엔드6차점", "c.household")
        val s = LedgerState(categories = Defaults.categories.associateBy { it.id }, rules = mapOf(old.id to old))
        assertEquals("c.household", Categorizer.categorize(s, "씨유(CU)가산하이엔드6차점", Kind.EXPENSE))
        assertEquals(old, Categorizer.rule(s, "씨유(CU)가산하이엔드6차점"))
        // 지금 이름으로 배운 규칙이 있으면 그쪽이 먼저
        val now = MerchantRule(Categorizer.ruleId("씨유(CU)가산하이엔드6차점"), "씨유(CU)가산하이엔드6차점", "c.food")
        val both = s.copy(rules = s.rules + (now.id to now))
        assertEquals("c.food", Categorizer.categorize(both, "씨유(CU)가산하이엔드6차점", Kind.EXPENSE))
    }

    // --- #19 두 폰 같은 결제: 짧은 가맹점 이름 -------------------------------------------------------------

    @Test fun shortMerchantNameOnlyMatchesAtTheStart() {
        val a = recorded("a", "현대카드", merchant = "CU")
        assertTrue(Twins.same(a, recorded("b", "현대카드", merchant = "CU 가산점")))
        // 두 글자 이름이 다른 가맹점 이름 가운데에 든 것은 다른 결제
        assertFalse(Twins.same(a, recorded("b", "현대카드", merchant = "ABCU마트")))
        // 세 글자 넘으면 지금처럼 포함으로("CU 네이버페이" ↔ "네이버페이")
        assertTrue(Twins.same(recorded("a", "현대카드", merchant = "CU 네이버페이"), recorded("b", "현대카드", merchant = "네이버페이")))
    }

    // --- #15 용돈 알림 묶기 ----------------------------------------------------------------------------

    @Test fun allowancePaymentsInAFewMinutesShareOneNotification() {
        val husband = Member("h", "남편", allowance = 350_000)
        val subs = listOf(
            pocket("s1", 9_900, ms, "구글플레이"),
            pocket("s2", 13_500, ms + 30_000, "넷플릭스"),
            pocket("s3", 990, ms + 60_000, "카카오구독료"),
            pocket("s4", 4_500, ms + 90_000, "스타벅스"),
        )
        val s = LedgerState(members = mapOf("h" to husband), txns = subs.associateBy { it.id })
        val many = AllowanceAlert.ofMany(s, subs)
        assertEquals("용돈에서 4건 · 28,890원 썼어요", many.title)
        assertEquals("구글플레이 · 넷플릭스 · 카카오구독료 외 1곳 · 남은 용돈 321,110원", many.body)
        // 한 건이면 지금과 같음
        assertEquals(AllowanceAlert.of(s, subs[0]), AllowanceAlert.ofMany(s, subs.take(1)))
        // 취소가 섞이면 순액
        val cancel = pocket("c", 9_900, ms + 100_000, "구글플레이", cancelOf = "s1")
        val withCancel = AllowanceAlert.ofMany(s.copy(txns = s.txns + ("c" to cancel)), listOf(subs[0], subs[1], cancel))
        assertEquals("용돈에서 3건 · 13,500원 썼어요", withCancel.title)

        // 첫 건부터 5분 안에 온 것은 같은 알림(key)으로, 넘으면 새 알림
        val g1 = AllowanceAlert.group(null, listOf("s1"), ms)
        assertEquals("s1", g1.key)
        val g2 = AllowanceAlert.group(g1, listOf("s2"), ms + 4 * 60_000)
        assertEquals("s1", g2.key)
        assertEquals(listOf("s1", "s2"), g2.ids)
        val g3 = AllowanceAlert.group(g2, listOf("s3"), ms + 6 * 60_000)
        assertEquals("s3", g3.key)
        assertEquals(listOf("s3"), g3.ids)
        // 이 폰에 저장했다 다시 읽어도 같음
        assertEquals(g2, AllowanceAlert.Group.from(JSONObject(g2.json().toString())))
    }

    private fun recorded(id: String, issuer: String, merchant: String = "CU", at: Long = ms) =
        Txn(id, Kind.EXPENSE, 3_610, at, merchant = merchant, memberId = "h", source = Source.PUSH, issuer = issuer,
            cardLabel = issuer, createdBy = "me")

    private fun pocket(id: String, amount: Long, at: Long, merchant: String, cancelOf: String? = null) =
        Txn(id, Kind.EXPENSE, amount, at, merchant = merchant, memberId = "h", source = Source.PUSH, issuer = "토스뱅크",
            private = true, cancelOf = cancelOf, createdBy = "install-h")
}
