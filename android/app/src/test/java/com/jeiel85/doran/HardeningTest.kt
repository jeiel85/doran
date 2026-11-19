package com.jeiel85.doran

import com.jeiel85.doran.capture.Categorizer
import com.jeiel85.doran.capture.Issuers
import com.jeiel85.doran.capture.Parsed
import com.jeiel85.doran.capture.Parser
import com.jeiel85.doran.capture.SamePayment
import com.jeiel85.doran.capture.SentLog
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

    // --- #21 리뷰: 보내는 가족 폰의 보낸 기록 ---------------------------------------------------------------

    @Test fun forwardingDoesNotMergeMoneyPaymentsWithCardPayments() {
        val money = Parser.payNotice("네이버페이\n이마트24에서 6,000원 결제되었습니다. (결제 수단: 네이버페이 머니)", at, "네이버페이")!!
        val card = Parser.parse("6,000원 결제\n토스뱅크 체크카드 | 올리브영 잔액 3,000원", at, hint = "토스")!!
        // 머니로 낸 간편결제를 보낸 뒤 같은 금액의 카드 결제가 와도 다른 결제 → 보냄
        val sent = listOf(SentLog.entry("x", money, ms, Kind.EXPENSE, null))
        assertNull(SentLog.sameSent(sent, card, ms + 60_000))
        // 거꾸로도(카드를 보낸 뒤 머니 결제 알림)
        assertNull(SentLog.sameSent(listOf(SentLog.entry("y", card, ms, Kind.EXPENSE, null)), money, ms + 60_000))
        // 카드 간편결제였으면 같은 결제
        val viaCard = Parser.payNotice("네이버페이\n올리브영에서 6,000원 결제되었습니다. (결제 수단: 카드 간편결제)", at, "네이버페이")!!
        assertNotNull(SentLog.sameSent(listOf(SentLog.entry("z", viaCard, ms, Kind.EXPENSE, null)), card, ms + 60_000))
        // 같은 카드사의 중복 알림은 그대로 같은 결제, 입금은 이름이 다르면 같은 결제가 아님
        assertNotNull(SentLog.sameSent(listOf(SentLog.entry("c", card, ms, Kind.EXPENSE, null)), card, ms + 60_000))
        val deposit = Parser.parse("6,000원 입금\n김도란 → 내 토스뱅크 통장", at, hint = "토스")!!
        assertNull(SentLog.sameSent(listOf(SentLog.entry("z", viaCard, ms, Kind.EXPENSE, null)), deposit, ms + 60_000))
    }

    @Test fun cardCancelFindsThePaymentSentUnderThePayAppName() {
        val pay = Parsed(Parsed.Type.APPROVAL, "네이버페이", true, 3_610, merchant = "CU")
        val card = Parsed(Parsed.Type.APPROVAL, "토스뱅크", false, 3_610, merchant = "씨유(CU)가산하이엔드6차점")
        var items = listOf(SentLog.entry("X", pay, ms, Kind.EXPENSE, null))
        // 카드 알림은 같은 결제라 보내지 않되, 카드사 이름으로 별칭을 남김
        val hit = SentLog.sameSent(items, card, ms + 30_000)!!
        items = SentLog.withAlias(items, hit, card.issuer)!!
        assertNull(SentLog.withAlias(items, hit, card.issuer)) // 두 번 남기지 않음
        // 카드사만 알리는 취소가 와도 원래 거래(X)를 찾아 취소를 보냄
        val cardCancel = card.copy(type = Parsed.Type.CANCEL)
        val original = SentLog.original(items, cardCancel, ms + 3_600_000)!!
        assertEquals("X", original.optString("id"))
        items = items + SentLog.entry("C1", cardCancel, ms + 3_600_000, Kind.EXPENSE, "X")
        // 간편결제 앱의 취소가 3분 넘어 늦게 와도 이미 다 취소됐으니 원래 거래가 없음 → 두 번 돌려받지 않음
        assertNull(SentLog.original(items, pay.copy(type = Parsed.Type.CANCEL), ms + 3_600_000 + 10 * 60_000))
        // 부분 취소는 남은 금액만큼만
        val partial = SentLog.entry("C0", pay.copy(type = Parsed.Type.CANCEL, amount = 1_000), ms + 1_000, Kind.EXPENSE, "X")
        val two = listOf(SentLog.entry("X", pay, ms, Kind.EXPENSE, null), partial)
        assertNotNull(SentLog.original(two, pay.copy(type = Parsed.Type.CANCEL, amount = 2_610), ms + 2_000))
        assertNull(SentLog.original(two, pay.copy(type = Parsed.Type.CANCEL, amount = 2_611), ms + 2_000))
    }

    // --- #21 리뷰: 이 폰 가계부에서 합쳐진 결제의 취소 -----------------------------------------------------------

    @Test fun payAppCancelGoesToTheCardRecordItWasMergedInto() {
        val card = recorded("T", "토스뱅크", merchant = "씨유(CU)가산하이엔드6차점").copy(cardLabel = "토스뱅크 · 네이버페이")
        val other = recorded("U", "토스뱅크", merchant = "스타벅스").copy(cardLabel = "토스뱅크")
        val s = LedgerState(txns = listOf(card, other).associateBy { it.id })
        val cancel = Parsed(Parsed.Type.CANCEL, "네이버페이", true, 3_610, merchant = "CU")
        assertEquals("T", SamePayment.absorbedOriginal(s, "me", cancel, ms + 3_600_000, "네이버페이")?.id)
        assertNull(SamePayment.absorbedOriginal(s, "me", cancel, ms + 3_600_000, "카카오페이"))
        assertNull(SamePayment.absorbedOriginal(s, "other-phone", cancel, ms + 3_600_000, "네이버페이"))
        // 앱의 취소를 카드 기록에 걸면 남은 금액이 없어 다시 걸지 않음
        val appCancel = Txn("A", Kind.EXPENSE, 3_610, ms + 3_600_000, merchant = card.merchant, source = Source.PUSH, cancelOf = "T",
            issuer = "토스뱅크", cardLabel = card.cardLabel, createdBy = "me")
        val after = s.copy(txns = s.txns + ("A" to appCancel))
        assertNull(SamePayment.absorbedOriginal(after, "me", cancel, ms + 3_700_000, "네이버페이"))
        // 그 뒤 카드사의 취소 알림이 늦게 와도 같은 취소로 봄(간편결제 앱이 붙은 취소)
        assertEquals("A", SamePayment.payAppCancel(after.txns.values, 3_610, ms + 3_600_000 + 10 * 60_000)?.id)
        assertNull(SamePayment.payAppCancel(after.txns.values, 3_600, ms + 3_600_000))
        // 거꾸로 카드사의 취소가 먼저 기록돼 있으면 앱의 취소 알림은 같은 취소
        val cardCancel = Txn("K", Kind.EXPENSE, 3_610, ms + 3_600_000, source = Source.PUSH, cancelOf = "T", issuer = "토스뱅크",
            cardLabel = "토스뱅크", createdBy = "me")
        val cardFirst = s.copy(txns = s.txns + ("K" to cardCancel))
        assertEquals("K", SamePayment.cancelOfAbsorbed(cardFirst, "me", 3_610, ms + 3_600_000 + 10 * 60_000, "네이버페이")?.id)
        assertNull(SamePayment.cancelOfAbsorbed(cardFirst, "me", 3_610, ms + 3_600_000, "카카오페이"))
        assertEquals("네이버페이", SamePayment.via(card))
        assertNull(SamePayment.via(other))
    }

    private fun recorded(id: String, issuer: String, merchant: String = "CU", at: Long = ms) =
        Txn(id, Kind.EXPENSE, 3_610, at, merchant = merchant, memberId = "h", source = Source.PUSH, issuer = issuer,
            cardLabel = issuer, createdBy = "me")

    private fun pocket(id: String, amount: Long, at: Long, merchant: String, cancelOf: String? = null) =
        Txn(id, Kind.EXPENSE, amount, at, merchant = merchant, memberId = "h", source = Source.PUSH, issuer = "토스뱅크",
            private = true, cancelOf = cancelOf, createdBy = "install-h")
}
