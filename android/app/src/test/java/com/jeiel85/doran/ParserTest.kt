package com.jeiel85.doran

import com.jeiel85.doran.capture.Issuers
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

    @Test fun failedPaymentsAreNotTransactions() {
        // 잔액 부족·한도 초과·미납처럼 돈이 실제로 나가지 않았다는 알림: 금액이 있어도 기록하지 않고 골라 보낼 후보로도 남기지 않음
        val failures = listOf(
            "[KB]11/19 09:00\n123456**789\n자동이체출금 50,000\n출금불능(잔액부족)",
            "현대카드 결제 실패 12,300원 잔액이 부족합니다 11/19 08:10 스타벅스",
            "[신한] 출금이 되지 않았습니다 30,000원 11/19 09:00",
            "현대카드 한도초과로 승인되지 않았어요 58,000원",
            "[현대카드] 결제대금 미납 안내 523,000원",
            "Transaction DECLINED 현대카드 USD 12.34",
            "[카카오뱅크] 체크카드 결제를 완료하지 못했어요 9,500원 김밥천국",
            "[우리] 이체 오류 100,000원 11/19 10:00",
            "현대카드 승인거절 잔액부족 12,300원",
        )
        for (text in failures) {
            assertNull(text, p(text))
            assertTrue(text, Parser.isFailure(text))
            assertTrue(text, Parser.isFailedPayment(text))
            assertFalse(text, Parser.looksFinancial(text))
        }
        // 실패 낱말이 있어도 금액이 없는 알림은 "실패 알림"으로 남길 것이 아님
        assertFalse(Parser.isFailedPayment("현대카드 앱 로그인에 실패했어요"))
    }

    @Test fun tossPushFailureAndNormalNotices() {
        // 실제 토스 앱 푸시 형식(2026-11-19 사용자 폰 "최근 받은 알림"). 제목 + 본문을 CaptureProcessor처럼 줄바꿈으로 이음.
        // 실패 알림이 공유 범위 밖이라 "나만 보는 용돈"으로 기록됐던 것 — 이제 기록하지 않음
        val failed = "9,900원 결제 실패\n토스뱅크 체크카드 | 구글플레이_TOSS 잔액이 부족해요."
        assertNull(Parser.parse(failed, now, hint = "토스"))
        assertTrue(Parser.isFailedPayment(failed))
        assertFalse(Parser.looksFinancial(failed))

        // 같은 앱의 정상 알림: 본문에 "잔액 2,064원"처럼 '잔액'이 흔히 들어가도 실패로 보지 않음
        val sub = Parser.parse("990원 결제\n토스뱅크 체크카드 | 카카오구독료_카카오페이 잔액 2,064원", now, hint = "토스")!!
        assertEquals(Parsed.Type.APPROVAL, sub.type)
        assertEquals("토스뱅크", sub.issuer)
        assertEquals(990L, sub.amount)
        assertEquals(2_064L, sub.balance)
        assertTrue(sub.merchant!!, sub.merchant!!.contains("카카오구독료"))
        val cafe = Parser.parse("3,400원 결제\n토스뱅크 체크카드 | 메가MGC(인증)(2) 네이버페이_CJ 잔액 3,650원", now, hint = "토스")!!
        assertEquals(Parsed.Type.APPROVAL, cafe.type)
        assertEquals(3_400L, cafe.amount)
        assertEquals(3_650L, cafe.balance)
        val deposit = Parser.parse("6,705원 입금\n김도란 → 내 토스뱅크 통장", now, hint = "토스")!!
        assertEquals(Parsed.Type.DEPOSIT, deposit.type)
        assertEquals("토스뱅크", deposit.issuer)
        assertEquals(6_705L, deposit.amount)
    }

    @Test fun merchantKeepsBrandInParentheses() {
        // 실제 토스 앱 푸시(2026-11-19 사용자 폰): 괄호로 나눠 "가산하이엔드6차점"만 남아 CU를 잃고 '기타'로 분류되던 것
        val cu = Parser.parse("3,610원 결제\n토스뱅크 체크카드 | 씨유(CU)가산하이엔드6차점 잔액 540원", now, hint = "토스")!!
        assertEquals("씨유(CU)가산하이엔드6차점", cu.merchant)
        assertEquals(540L, cu.balance)
        assertEquals("c.food", com.jeiel85.doran.capture.Categorizer.categorize(
            com.jeiel85.doran.data.LedgerState(), cu.merchant, com.jeiel85.doran.model.Kind.EXPENSE))
        val mega = Parser.parse("3,400원 결제\n토스뱅크 체크카드 | 메가MGC(인증)(2) 네이버페이_CJ 잔액 3,650원", now, hint = "토스")!!
        assertEquals("메가MGC(인증)(2) 네이버페이_CJ", mega.merchant)
        assertEquals("c.cafe", com.jeiel85.doran.capture.Categorizer.categorize(
            com.jeiel85.doran.data.LedgerState(), mega.merchant, com.jeiel85.doran.model.Kind.EXPENSE))
        // 낱말을 지우고 남은 빈 괄호·짝 없는 괄호는 가맹점이 아님
        assertEquals("가구나라", p("신한카드(5678)승인 김*수 45,000원(할부 6개월)11/15 10:20 가구나라")!!.merchant)
        assertEquals("동네빵집", p("현대카드 승인 7,000원 (일시불) 동네빵집")!!.merchant)
        assertEquals("(주)도란컴퍼니", p("[Web발신]\n[신한]11/25 10:00\n110-***-123456\n입금 3,200,000\n잔액 5,600,000\n(주)도란컴퍼니")!!.merchant)
    }

    @Test fun payAppNoticeIsReadButNotRecordedAlone() {
        // 실제 네이버페이 앱 푸시(2026-11-19 사용자 폰, 토스뱅크 체크카드를 등록해 CU에서 결제). 제목 + 본문.
        val naver = "네이버페이\nCU에서 3,610원 결제되었습니다. (결제 수단: 카드 간편결제)"
        // 카드사·은행이 아니라 혼자서는 기록하지 않음(돈은 카드 알림 쪽으로 기록됨) — 골라 보낼 후보이자 묶을 짝
        assertNull(Parser.parse(naver, now, hint = "네이버페이"))
        assertTrue(Parser.looksFinancial(naver))
        val n = Parser.payNotice(naver, now, "네이버페이")!!
        assertEquals(Parsed.Type.APPROVAL, n.type)
        assertEquals(3_610L, n.amount)
        assertEquals("CU", n.merchant)
        val cancel = Parser.payNotice("네이버페이\nCU에서 3,610원 결제가 취소되었습니다.", now, "네이버페이")!!
        assertEquals(Parsed.Type.CANCEL, cancel.type)
        assertEquals("CU", cancel.merchant)
        // 앱 이름을 못 읽어 제목("네이버페이")이 지워지지 않아도 '…에서 N원' 앞을 가맹점으로(에뮬레이터 시험에서 발견)
        assertEquals("CU", Parser.payNotice(naver, now, "Shell")!!.merchant)
        assertEquals("GS25 가산점", Parser.payNotice("Npay\nGS25 가산점에서 4,200원 결제했어요", now, null)!!.merchant)
        // 결제 실패·광고는 묶을 짝이 아님
        assertNull(Parser.payNotice("네이버페이\nCU에서 3,610원 결제에 실패했어요. 잔액이 부족해요", now, "네이버페이"))
        assertNull(Parser.payNotice("(광고) 네이버페이로 결제하면 1,000원 할인", now, "네이버페이"))
        // 골라 보내기 화면의 초기값도 같은 문장에서 가맹점을 읽음
        assertEquals("CU", Parser.parse(naver, now, custom = com.jeiel85.doran.capture.Issuer("네이버페이", true, listOf("네이버페이")))!!.merchant)
    }

    @Test fun normalPaymentsAreNotMistakenForFailures() {
        val r = p("현대카드 승인 홍*동 15,000원 일시불 11/19 12:00 이마트 미아점")!!
        assertEquals(15_000L, r.amount)
        assertTrue(r.merchant!!, r.merchant!!.contains("이마트 미아점"))
        assertFalse(Parser.isFailure("[KB]11/17 09:01\n123456**789\n홍길동\n전자금융출금\n50,000\n잔액1,234,000"))
        // 간편결제가 잔액 부족분을 자동충전하고 결제한 것은 정상 결제
        val pay = "동네마트 12,300원 결제 완료 (잔액 부족분 10,000원 자동충전)"
        assertFalse(Parser.isFailure(pay))
        val charged = Parser.parse(pay, now, custom = com.jeiel85.doran.capture.Issuer("동네페이", true, listOf("동네페이")))!!
        assertEquals(Parsed.Type.APPROVAL, charged.type)
        assertEquals(12_300L, charged.amount)
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

    // --- 결제·출금 예정 안내 (D-4 "돈이 나가지 않은 알림" 2) ---------------------------------------------------

    @Test fun tossScheduledWithdrawalIsNotRecorded() {
        // 실제 토스 앱 푸시(2026-11-20 사용자 폰 "최근 받은 알림", 가맹점 이름·계좌 끝자리는 바꿈). 출금으로 읽혀 '나만 보는 용돈'으로 기록됐던 것
        val notice = "납부 예정\n오늘 동네헬스 외 1곳에서 30,000원 출금할 예정이에요. 토스뱅크 통장 (1234)에서 출금돼요."
        assertNull(Parser.parse(notice, now, hint = "토스"))
        assertTrue(Parser.isScheduled(notice))
        assertEquals(Parser.Unspent.SCHEDULED, Parser.unspent(notice))
        assertFalse(Parser.isFailedPayment(notice))
        // 골라 보낼 후보로도 권하지 않음
        assertFalse(Parser.looksFinancial(notice))
        // 오탐이면 "그래도 보내기"로 보낼 수 있게 초기값은 읽힘
        val anyway = Parser.parse(notice, now, hint = "토스", ignoreFailure = true)!!
        assertEquals(Parsed.Type.WITHDRAWAL, anyway.type)
        assertEquals("토스뱅크", anyway.issuer)
        assertEquals(30_000L, anyway.amount)
    }

    @Test fun scheduledAndBillNoticesAreNotTransactions() {
        val notices = listOf(
            "[현대카드] 12월 결제예정금액 523,000원 안내",
            "[KB]11/25 자동이체 출금예정 50,000원 보험료",
            "[신한] 내일 관리비 120,000원이 출금될 예정입니다",
            "토스뱅크 통장에서 내일 30,000원이 결제될 예정이에요",
            "[통신사] 이번 달 납부하실 금액 65,000원 (25일 자동이체 계좌에서 출금)",
            "[우리] 예약이체 예정 안내 200,000원 11/21 09:00",
            "[삼성카드] 11월 청구금액 812,000원 결제일 12/14",
        )
        for (text in notices) {
            assertNull(text, p(text))
            assertEquals(text, Parser.Unspent.SCHEDULED, Parser.unspent(text))
            assertFalse(text, Parser.looksFinancial(text))
        }
        // 예정이었다가 실패한 것은 실패 알림
        assertEquals(Parser.Unspent.FAILED, Parser.unspent("출금 예정이던 30,000원이 잔액 부족으로 출금되지 않았어요"))
    }

    @Test fun realTransactionsWithFutureWordsAreStillRecorded() {
        // 이미 일어난 거래의 덧말에 쓰인 '예정'은 거래를 막지 않음
        val cancel = p("현대카드 승인취소 홍*동 12,300원 11/17 13:00 스타벅스 환불은 3일 안에 입금 예정")!!
        assertEquals(Parsed.Type.CANCEL, cancel.type)
        assertEquals(12_300L, cancel.amount)
        val cashback = p("현대카드 승인 홍*동 20,000원 일시불 11/17 13:00 동네마트 캐시백 500원 지급 예정")!!
        assertEquals(Parsed.Type.APPROVAL, cashback.type)
        assertEquals(20_000L, cashback.amount)
        val points = p("현대카드 승인 홍*동 9,000원 일시불 11/17 13:00 동네빵집 M포인트 적립 예정")!!
        assertEquals(9_000L, points.amount)
        // 실제 출금은 그대로 출금
        val paid = Parser.parse("30,000원 출금\n토스뱅크 통장 → 동네헬스", now, hint = "토스")!!
        assertEquals(Parsed.Type.WITHDRAWAL, paid.type)
        assertEquals(30_000L, paid.amount)
        assertNull(Parser.unspent("30,000원 출금\n토스뱅크 통장 → 동네헬스"))
    }

    @Test fun versionNumbersAreNotForeignMoney() {
        // 사내 메신저의 보안 공지(2026-11-20 사용자 폰, 사람 이름·회사 문구는 뺌): "PHP 8.2.0"을 필리핀 페소로 읽어 금액 알림 후보로 남겼음
        val chat = "팀 대화방\n아래 내용 빠르게 처리해야 할 것 같아요. PHP 원격 코드 실행 등 보안 취약점 18건 안내드립니다. " +
            "영향 범위 • PHP 8.2.0 ~ 8.2.35 → 8.2.36 이상 • PHP 8.3.0 ~ 8.3.36 → 8.3.37 이상. 사용 중인 서버는 업데이트해 주세요"
        assertFalse(Parser.looksFinancial(chat))
        assertNull(Parser.unspent(chat))
        // 실제 외화 결제는 그대로
        assertEquals("PHP 1,250.00", p("현대카드 해외승인 홍*동 PHP 1,250.00 11/10 03:15 MANILA MART")!!.foreign)
        assertEquals("USD 12.34", p("현대카드 해외승인 홍*동 USD 12.34. 11/10 03:15 NETFLIX.COM")!!.foreign)
    }

    @Test fun completedTransactionsWithANextScheduleAreRecorded() {
        // 이미 일어난 거래에 다음 일정이 덧붙은 알림은 예정 안내가 아님(#40)
        val cancel = p("현대카드 승인취소 홍*동 12,300원 11/17 13:00 스타벅스 취소금액은 다음 달 결제예정금액에서 차감됩니다")!!
        assertEquals(Parsed.Type.CANCEL, cancel.type)
        assertEquals(12_300L, cancel.amount)
        assertNull(Parser.unspent("현대카드 승인취소 홍*동 12,300원 11/17 13:00 스타벅스 취소금액은 다음 달 결제예정금액에서 차감됩니다"))
        val out = p("[신한] 관리비 120,000원이 출금되었습니다. 다음 출금예정일 12/25")!!
        assertEquals(Parsed.Type.WITHDRAWAL, out.type)
        assertEquals(120_000L, out.amount)
        // 청구 뒤 실제로 들어온 돈
        val claim = p("[KB] 보험금 청구 건 300,000원 입금 11/17 13:00")!!
        assertEquals(Parsed.Type.DEPOSIT, claim.type)
        assertEquals(300_000L, claim.amount)
        // 그래도 청구서·납부 안내, 등록 안내에 붙은 예정 금액은 예정
        for (text in listOf(
            "[삼성카드] 11월 청구금액 812,000원 결제일 12/14",
            "[삼성카드] 11월 청구금액 812,000원, 결제계좌로 입금해 주세요",
            "[삼성카드] 11월 청구금액 812,000원, 결제계좌로 입금 바랍니다",
            "[현대카드] 청구금액 812,000원 입금 부탁드립니다",
            "[KB] 보험료 청구 50,000원 입금(예정)",
            "[신한] 자동이체 신청이 처리되었습니다. 다음 출금 예정일 12/25 50,000원",
            "[KB] 보험료 청구 예정 안내 50,000원 입금일 11/30",
        )) assertEquals(text, Parser.Unspent.SCHEDULED, Parser.unspent(text))
    }

    @Test fun currencyCodesInOrdinaryMessagesAreNotMoney() {
        // 통화 코드 + 정수 + '사용'만 있는 일반 알림(#40): 카드사·은행 이름이나 결제 낱말이 없으면 금액 알림 후보가 아님
        assertFalse(Parser.looksFinancial("서버 공지: PHP 8 업그레이드 후 사용 중인 플러그인을 확인해 주세요"))
        assertFalse(Parser.looksFinancial("JPY 100 엔화 환율 알림 · 사용자 설정"))
        // 결제 낱말이나 카드사가 있으면 그대로
        assertTrue(Parser.looksFinancial("Google Play USD 9.99 결제 완료"))
        assertEquals("USD 9.99", p("현대카드 해외승인 홍*동 USD 9.99 11/10 03:15 GOOGLE")!!.foreign)
    }

    // --- 같은 금융그룹 카드사의 카드값 (#63) ----------------------------------------------------------------------

    @Test fun cardBillToTheSameGroupsCardIssuerIsATransfer() {
        val kb = p("[KB]12/14 06:30\n123456**789\nKB국민카드\n출금 523,000\n잔액711,000")!!
        assertEquals("KB국민은행", kb.issuer)
        assertEquals(Parsed.Type.WITHDRAWAL, kb.type)
        assertEquals(523_000L, kb.amount)
        assertEquals("KB국민카드", kb.cardPaymentTo)
        val nh = p("[농협]12/14 06:30\n123-****-5678-90\nNH농협카드\n출금 210,000원\n잔액90,000원")!!
        assertEquals("NH농협", nh.issuer)
        assertEquals("NH농협카드", nh.cardPaymentTo)
        // 카드사 이름이 아닌 곳에 든 은행 이름은 여전히 지워 가맹점에 남지 않음
        val shop = p("[KB]12/14 06:30\n123456**789\nKB국민 동네마트\n출금 12,000\n잔액700,000")!!
        assertNull(shop.cardPaymentTo)
        assertEquals("동네마트", shop.merchant)
    }

    // --- 은행 받는 분·메모 칸(적요) (2026-11-20 사용자 폰 KB스타뱅킹 앱 푸시, 이름·계좌·금액·회사 이름은 바꿈) -----------------------

    @Test fun kbPushMemoIsTheMerchant() {
        val now = LocalDateTime.of(2026, 11, 20, 13, 0)
        val out = Parser.parse("출금 3,200,615원\n홍*동님 11/20 12:59 123456-**-***789 급여 스마트폰출금 3,200,615 잔액0", now, hint = "KB스타뱅킹")!!
        assertEquals(Parsed.Type.WITHDRAWAL, out.type)
        assertEquals("KB국민은행", out.issuer)
        assertFalse(out.isCard)
        assertEquals(3_200_615L, out.amount)
        assertEquals(0L, out.balance)
        assertNull(out.cardPaymentTo)
        // 거래 방식("스마트폰출금")과 본문에 되풀이된 금액을 떼면 메모 칸만 남음. 전에는 "급여 스마트폰 4"처럼 읽혔음
        assertEquals("급여", out.merchant)
        assertEquals(LocalDateTime.of(2026, 11, 20, 12, 59), out.at)

        val inn = Parser.parse("입금 3,200,000원\n홍*동님 11/20 12:33 123456-**-***789 주식회사가 전자금융입금 3,200,000 잔액3,200,615", now, hint = "KB스타뱅킹")!!
        assertEquals(Parsed.Type.DEPOSIT, inn.type)
        assertEquals(3_200_000L, inn.amount)
        assertEquals(3_200_615L, inn.balance)
        assertEquals("주식회사가", inn.merchant)

        // 다른 거래 방식 낱말도 같은 방식으로
        assertEquals("월세", Parser.parse("[KB]11/20 09:00 123456**789 월세 인터넷출금 500,000 잔액100,000", now)!!.merchant)
        assertEquals("홍길동", Parser.parse("[신한]11/20 09:00 110-***-123456 홍길동 모바일출금 30,000 잔액70,000", now)!!.merchant)
    }

    @Test fun channelWordsAndAmountEchoesLeaveShopNamesAlone() {
        // 입출금 낱말이 바로 붙지 않은 '스마트폰'은 가게 이름
        assertEquals("스마트폰케이스몰", p("현대카드 승인 홍*동 25,000원 일시불 11/17 13:00 스마트폰케이스몰")!!.merchant)
        // 금액과 같은 숫자로 시작해도 '원'에 글자가 붙은 가게 이름은 지우지 않음
        val shop = p("신한카드 승인 홍*동 1,000원 일시불 11/17 13:00 1000원샵 성수점")!!
        assertEquals(1_000L, shop.amount)
        assertTrue(shop.merchant!!, shop.merchant!!.contains("1000원샵"))
    }

    // 2026-11-21 주유: 아내 폰이 가맹점 없는 현대카드 문자에서 카드 상품 이름 "현대 ZERO"를 가맹점으로 읽어, 남편 폰의 같은 결제
    // ("동네주유소")와 다른 가게로 보여 두 번 기록됐어요. 원문은 아내 폰에만 있어, 상품 이름만 남는 형식을 본뜬 합성 예시입니다.
    @Test fun cardProductNameIsNotTheMerchant() {
        val r = p("[Web발신]\n[현대카드]\n현대 ZERO 승인\n홍*동\n70,000원 일시불\n11/21 09:37\n누적1,234,567원")!!
        assertEquals("현대카드", r.issuer)
        assertEquals(70_000L, r.amount)
        assertNull(r.merchant)
        // 가맹점이 함께 오면 그쪽
        assertEquals("동네주유소", p("현대카드 ZERO 승인 홍*동 70,000원 일시불 11/21 09:37 동네주유소")!!.merchant)
        assertEquals("동네주유소", p("[현대카드]\n현대 ZERO 승인\n홍*동\n70,000원 일시불\n11/21 09:37\n동네주유소")!!.merchant)
    }

    @Test fun cardNameOnlyKeepsRealShopsThatShareTheBrand() {
        assertTrue(Issuers.cardNameOnly("현대 ZERO", "현대카드"))
        assertTrue(Issuers.cardNameOnly("현대카드", "현대카드"))
        assertTrue(Issuers.cardNameOnly("현대카드 the Green 카드", "현대카드"))
        assertTrue(Issuers.cardNameOnly("신한 Deep Dream", "신한카드"))
        // 카드사 이름 뒤에 한글이 이어지면 실제 가게
        assertFalse(Issuers.cardNameOnly("현대백화점 목동점", "현대카드"))
        assertFalse(Issuers.cardNameOnly("현대오일뱅크 성수주유소", "현대카드"))
        assertFalse(Issuers.cardNameOnly("롯데마트", "롯데카드"))
        // 카드사 이름으로 시작하지 않거나, 영문 이름 가운데에 든 짧은 이름은 아님
        assertFalse(Issuers.cardNameOnly("동네주유소", "현대카드"))
        assertFalse(Issuers.cardNameOnly("ABC MART", "BC카드"))
        assertFalse(Issuers.cardNameOnly("BCMART", "BC카드"))
        assertFalse(Issuers.cardNameOnly("BC 바로카드", "BC카드"))
        // 은행 알림에는 쓰지 않음: 같은 이름의 카드사로 나가는 카드값 출금의 받는 곳("신한카드")이 이체를 가리는 단서(#62 리뷰)
        assertFalse(Issuers.cardNameOnly("신한카드", "신한은행"))
        val bill = p("[신한]11/25 10:00\n출금 523,000원\n잔액711,000원\n신한카드")!!
        assertEquals("신한은행", bill.issuer)
        assertEquals("신한카드", bill.cardPaymentTo)
        // 다른 카드사·모르는 카드사의 이름은 보지 않음
        assertFalse(Issuers.cardNameOnly("현대 ZERO", "신한카드"))
        assertFalse(Issuers.cardNameOnly("현대 ZERO", null))
        assertFalse(Issuers.cardNameOnly("", "현대카드"))
    }
}
