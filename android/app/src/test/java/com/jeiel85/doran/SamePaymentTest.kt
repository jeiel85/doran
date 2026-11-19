package com.jeiel85.doran

import com.jeiel85.doran.capture.Categorizer
import com.jeiel85.doran.capture.Parser
import com.jeiel85.doran.capture.SamePayment
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Twins
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 간편결제 앱 알림 + 카드·은행 알림 = 같은 결제 (DECISIONS.md D-5).
 * 실제 사례(2026-11-19 사용자 폰, 오전 11:41): 토스뱅크 체크카드를 등록한 네이버페이로 CU 가산점에서 3,610원 결제.
 */
class SamePaymentTest {
    private val at = LocalDateTime.of(2026, 11, 19, 11, 41)
    private val ms = Fmt.millis(at)
    private val naverText = "네이버페이\nCU에서 3,610원 결제되었습니다. (결제 수단: 카드 간편결제)"
    private val tossText = "3,610원 결제\n토스뱅크 체크카드 | 씨유(CU)가산하이엔드6차점 잔액 540원"
    private val naver = Parser.payNotice(naverText, at, "네이버페이")!!
    private val toss = Parser.parse(tossText, at, hint = "토스")!!

    @Test fun payNoticeAndCardNoticeAreTheSamePayment() {
        assertTrue(SamePayment.matches(naver, ms + 20_000, toss, ms))
        assertTrue(SamePayment.matches(naver, ms - 20_000, toss, ms))
        // 3분이 넘게 떨어졌거나 금액이 다르면 다른 결제
        assertFalse(SamePayment.matches(naver, ms + 4 * 60_000, toss, ms))
        assertFalse(SamePayment.matches(naver.copy(amount = 3_600), ms, toss, ms))
        // 결제와 취소는 짝이 아님, 입금도 아님
        assertFalse(SamePayment.matches(naver.copy(type = com.jeiel85.doran.capture.Parsed.Type.CANCEL), ms, toss, ms))
        val deposit = Parser.parse("3,610원 입금\n김도란 → 내 토스뱅크 통장", at, hint = "토스")!!
        assertFalse(SamePayment.matches(naver, ms, deposit, ms))
    }

    @Test fun payNoticeFirstEnrichesTheCardRecord() {
        // 네이버페이 알림이 먼저 와서 후보로 남은 뒤 토스 알림이 오면, 토스 알림에 앱 이름을 보태 기록
        val joined = SamePayment.enrich(toss, naver, "네이버페이")
        assertEquals("씨유(CU)가산하이엔드6차점", joined.merchant) // 카드 쪽 가맹점에 CU가 이미 있으면 그대로
        assertEquals("토스뱅크 · 네이버페이", joined.cardLabel)
        assertEquals(3_610L, joined.amount)
        assertEquals("토스뱅크", joined.issuer) // 돈이 나간 곳은 그대로 토스뱅크
        // 간편결제 알림에서 앱 이름만 읽혔으면 가맹점에 붙이지 않음
        assertEquals("씨유(CU)가산하이엔드6차점", SamePayment.enrich(toss, naver.copy(merchant = "네이버페이"), "네이버페이").merchant)
    }

    @Test fun payNoticeLaterJoinsTheRecordedTxn() {
        // 이 기능 전에 기록된 그대로의 거래(가맹점에서 CU가 빠지고 '기타')에 나중 온 네이버페이 알림을 묶음
        val s = LedgerState()
        val old = Txn("t1", Kind.EXPENSE, 3_610, ms, merchant = "가산하이엔드6차점", memberId = "h", source = Source.PUSH,
            issuer = "토스뱅크", cardLabel = "토스뱅크", private = true, categoryId = "c.etc", createdBy = "me")
        assertTrue(SamePayment.matches(naver, ms + 30_000, old))
        val joined = SamePayment.enrich(s, old, naver, "네이버페이")
        assertEquals("CU 가산하이엔드6차점", joined.merchant)
        assertEquals("c.food", joined.categoryId) // 자동으로 정한 '기타'는 새 가맹점으로 다시 고름
        assertEquals("토스뱅크 · 네이버페이", joined.cardLabel)
        assertTrue(joined.private) // 나만 보는 용돈은 그대로
        // 사람이 고친 카테고리는 그대로
        assertEquals("c.cafe", SamePayment.enrich(s, old.copy(categoryId = "c.cafe"), naver, "네이버페이").categoryId)
        // 두 번 묶어도 같음
        assertEquals(joined, SamePayment.enrich(s, joined, naver, "네이버페이"))
        // 가족 폰이 보내 준 거래, 다른 금액, 이체는 묶지 않음
        assertFalse(SamePayment.matches(naver, ms, old.copy(source = Source.FAMILY)))
        assertFalse(SamePayment.matches(naver, ms, old.copy(amount = 3_600)))
        assertFalse(SamePayment.matches(naver, ms, old.copy(kind = Kind.TRANSFER)))
    }

    @Test fun cardNoticeWithOnlyPayAppNameGetsTheShopName() {
        // 카드 알림의 가맹점이 결제대행 이름뿐이면 간편결제 알림의 가맹점을 앞에 붙여 분류
        val merchant = SamePayment.merchant("네이버페이", "CU")
        assertEquals("CU 네이버페이", merchant)
        assertEquals("c.food", Categorizer.categorize(LedgerState(), merchant, Kind.EXPENSE))
        assertEquals("CU", SamePayment.merchant("", "CU"))
        assertEquals("스타벅스 성수점", SamePayment.merchant("스타벅스 성수점", null))
        // 같은 결제를 가족 폰이 "네이버페이"로 기록했어도 두 폰의 같은 결제로 봄(D-5)
        val mine = Txn("a", Kind.EXPENSE, 3_610, ms, merchant = merchant, source = Source.PUSH, issuer = "현대카드", cardLabel = "현대카드 · 네이버페이")
        val wife = Txn("b", Kind.EXPENSE, 3_610, ms + 30_000, merchant = "네이버페이", source = Source.SMS, issuer = "현대카드", cardLabel = "현대카드")
        assertTrue(Twins.same(mine, wife))
        assertFalse(Twins.same(mine, wife.copy(merchant = "스타벅스")))
    }
}
