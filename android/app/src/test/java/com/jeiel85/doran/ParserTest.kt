package com.jeiel85.doran

import com.jeiel85.doran.capture.Parsed
import com.jeiel85.doran.capture.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 알림 해석 시험. 아래 문장은 카드사·은행이 널리 쓰는 형식을 본뜬 **합성 예시**입니다(실명·실번호 없음).
 * 실제 기기에서 받은 문구가 다르면 그 문구(개인정보를 가린 것)를 여기 추가해 형식을 고정합니다.
 */
class ParserTest {
    private val now = LocalDateTime.of(2026, 11, 17, 21, 0)
    private fun p(text: String) = Parser.parse(text, now)

    @Test fun hyundaiSmsApprovalWithCumulative() {
        val r = p("[Web발신]\n현대카드 승인\n홍*동\n12,300원 일시불\n11/17 12:30\n스타벅스코리아\n누적1,234,567원")!!
        assertEquals(Parsed.Type.APPROVAL, r.type)
        assertEquals("현대카드", r.issuer)
        assertEquals(12_300L, r.amount)
        assertEquals(0, r.installment)
        assertEquals(1_234_567L, r.cumulative)
        assertEquals("스타벅스코리아", r.merchant)
        assertEquals(LocalDateTime.of(2026, 11, 17, 12, 30), r.at)
    }

    @Test fun oneLineAppPushWithCardNumber() {
        val r = p("현대카드 M(1234) 홍*동님 58,000원 3개월 승인 11/16 19:05 이마트 성수점 누적 523,000원")!!
        assertEquals(Parsed.Type.APPROVAL, r.type)
        assertEquals(58_000L, r.amount)
        assertEquals(3, r.installment)
        assertEquals("1234", r.last4)
        assertEquals(523_000L, r.cumulative)
        assertTrue(r.merchant!!, r.merchant!!.contains("이마트"))
    }

    @Test fun cancellation() {
        val r = p("[Web발신]\n현대카드 승인취소\n홍*동\n12,300원\n11/17 13:02\n스타벅스코리아")!!
        assertEquals(Parsed.Type.CANCEL, r.type)
        assertEquals(12_300L, r.amount)
        assertEquals("스타벅스코리아", r.merchant)
    }

    @Test fun otherIssuersAndInstallmentWording() {
        val shinhan = p("신한카드(5678)승인 김*수 45,000원(할부 6개월)11/15 10:20 가구나라")!!
        assertEquals("신한카드", shinhan.issuer)
        assertEquals(6, shinhan.installment)
        assertEquals("5678", shinhan.last4)
        assertEquals(45_000L, shinhan.amount)
        val kb = p("KB국민카드 1*2*승인\n이*희님\n8,900원 일시불\n11/14 08:12\nGS25 서울숲점\n누적 98,400원")!!
        assertEquals("KB국민카드", kb.issuer)
        assertEquals(98_400L, kb.cumulative)
        assertTrue(kb.merchant!!.contains("GS25"))
    }

    @Test fun foreignApproval() {
        val r = p("현대카드 해외승인 홍*동 USD 12.34 11/10 03:15 NETFLIX.COM")!!
        assertEquals(Parsed.Type.APPROVAL, r.type)
        assertEquals("USD 12.34", r.foreign)
        assertEquals(0L, r.amount)
        assertTrue(r.merchant!!.contains("NETFLIX"))
    }

    @Test fun bankWithdrawalWithBalance() {
        val r = p("[KB]11/17 09:01\n123456**789\n홍길동\n전자금융출금\n50,000\n잔액1,234,000")!!
        assertEquals(Parsed.Type.WITHDRAWAL, r.type)
        assertEquals("KB국민은행", r.issuer)
        assertEquals(50_000L, r.amount)
        assertEquals(1_234_000L, r.balance)
    }

    @Test fun bankDeposit() {
        val r = p("[Web발신]\n[신한]11/25 10:00\n110-***-123456\n입금 3,200,000\n잔액 5,600,000\n(주)도란컴퍼니")!!
        assertEquals(Parsed.Type.DEPOSIT, r.type)
        assertEquals(3_200_000L, r.amount)
        assertEquals(5_600_000L, r.balance)
        assertTrue(r.merchant!!.contains("도란컴퍼니"))
    }

    @Test fun cardBillPaymentIsTransfer() {
        val r = p("[KB]12/14 06:30\n123456**789\n현대카드\n출금 523,000\n잔액711,000")!!
        assertEquals(Parsed.Type.WITHDRAWAL, r.type)
        assertEquals("KB국민은행", r.issuer)
        assertEquals("현대카드", r.cardPaymentTo)
    }

    @Test fun checkCardApprovalFromBank() {
        val r = p("[카카오뱅크] 체크카드 승인 9,500원 11/17 12:10 김밥천국")!!
        assertEquals(Parsed.Type.APPROVAL, r.type)
        assertEquals("카카오뱅크", r.issuer)
        assertTrue(r.checkCard)
        assertEquals("김밥천국", r.merchant)
    }

    @Test fun merchantNamesWithWonSurvive() {
        val r = p("현대카드 승인 홍*동 23,000원 일시불 11/17 18:40 원할머니보쌈 누적 600,000원")!!
        assertEquals("원할머니보쌈", r.merchant)
    }

    @Test fun ignoresDeclinedAndNonFinancial() {
        assertNull(p("현대카드 승인거절 잔액부족 12,300원"))
        assertNull(p("엄마: 오늘 저녁 뭐 먹을래?"))
        assertNull(p("[현대카드] 12월 결제예정금액 523,000원 안내"))
    }

    @Test fun futureDateMeansLastYear() {
        val jan = LocalDateTime.of(2027, 1, 1, 9, 0)
        val r = Parser.parse("현대카드 승인 홍*동 10,000원 12/31 23:50 편의점", jan)!!
        assertEquals(LocalDateTime.of(2026, 12, 31, 23, 50), r.at)
    }

    @Test fun appNameIdentifiesIssuerWhenTextOmitsIt() {
        // 카드사 앱 푸시는 본문에 카드사 이름이 없을 수 있음 → 알림을 올린 앱 이름("현대카드")으로 정함
        val text = "[승인] 홍*동님 12,300원 일시불\n스타벅스 성수점\n누적 523,000원"
        assertNull(p(text))
        val r = Parser.parse(text, now, hint = "현대카드")!!
        assertEquals("현대카드", r.issuer)
        assertEquals(12_300L, r.amount)
        assertEquals("스타벅스 성수점", r.merchant)
        assertEquals("KB국민카드", Parser.parse("9,900원 결제 승인 11/17 08:00 GS25", now, hint = "KB Pay")!!.issuer)
        // 본문에 카드사가 있으면 앱 이름보다 본문이 우선
        assertEquals("신한카드", Parser.parse("신한카드 승인 5,000원 편의점", now, hint = "현대카드")!!.issuer)
    }

    @Test fun adsAndPromotionsAreNotTransactions() {
        assertNull(p("(광고)[현대카드] 이번 주말 스타벅스 50% 할인! 최대 10,000원 결제 시"))
        assertNull(Parser.parse("M포인트 이벤트: 30,000원 이상 결제하면 5,000원 캐시백", now, hint = "현대카드"))
        // 승인 문자에 적립 안내가 붙어 있어도 거래로 읽음
        assertNotNull(p("현대카드 승인 홍*동 30,000원 일시불 11/17 12:00 이마트 M포인트 300 적립"))
    }

    @Test fun unknownIssuerBecomesPickCandidateThenRuleReadsIt() {
        // 목록에 없는 곳(예: 지역 신협 앱·간편결제)의 결제 알림: 자동으로는 못 읽지만 골라 보낼 후보로 남김
        val text = "[결제완료] 동네마트 8,400원 결제되었습니다 11/17 19:20"
        assertNull(p(text))
        assertTrue(Parser.looksFinancial(text))
        // "앞으로도 자동으로 보내기"로 정한 이름으로 읽음
        val r = Parser.parse(text, now, hint = null, custom = com.jeiel85.doran.capture.Issuer("동네페이", true, listOf("동네페이")))!!
        assertEquals("동네페이", r.issuer)
        assertEquals(8_400L, r.amount)
        assertTrue(r.merchant!!.contains("동네마트"))
        // 금액·거래 낱말이 없는 알림, 광고는 후보가 아님
        assertFalse(Parser.looksFinancial("오늘 저녁 7시에 만나요"))
        assertFalse(Parser.looksFinancial("(광고) 지금 결제하면 5,000원 할인"))
        assertTrue(com.jeiel85.doran.data.ForwardRule.key("sms", "1577-6000") == "sms:15776000")
    }

    @Test fun missingDateLeavesNull() {
        val r = p("현대카드 승인 7,000원 일시불 동네빵집")!!
        assertNull(r.at)
        assertNotNull(r.merchant)
    }
}
