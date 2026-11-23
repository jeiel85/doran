package com.jeiel85.doran

import com.jeiel85.doran.capture.Notices
import com.jeiel85.doran.capture.Notices.Seen
import com.jeiel85.doran.capture.Parsed
import com.jeiel85.doran.capture.Parser
import com.jeiel85.doran.capture.SentLog
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * 같은 곳이 따로 올린 두 알림은 두 거래(DECISIONS.md D-5, 2026-11-23): 토스뱅크에서 같은 금액의 자동이체 두 건이 함께 빠지자
 * 알림이 두 개 왔는데 둘째를 중복으로 보고 한 건만 남긴 일. 앱 푸시와 문자가 같은 결제를 함께 알리는 경우는 지금처럼 한 건입니다.
 */
class NoticesTest {
    private val push = Source.PUSH.code
    private val sms = Source.SMS.code
    private val toss = "viva.republica.toss"
    private val tossKey1 = Notices.push("0|$toss|0|FCM-Notification:111|10123")
    private val tossKey2 = Notices.push("0|$toss|0|FCM-Notification:222|10123")
    private val kbSms1 = Notices.sms(1_763_860_000_000, "[KB]11/23 09:00 출금 12,000 ○○회비")
    private val kbSms2 = Notices.sms(1_763_860_003_000, "[KB]11/23 09:00 출금 12,000 ○○회비")

    @Test fun sameAppTwoNotificationsAreTwoTransactions() {
        // 같은 금액 자동이체 두 건: 같은 앱, 알림 키가 다른 두 알림
        assertTrue(Notices.another(listOf(Seen(push, toss, tossKey1)), push, toss, tossKey2))
        // 같은 번호의 문자 두 통(받은 시각이 다름)
        assertTrue(Notices.another(listOf(Seen(sms, "15881688", kbSms1)), sms, "15881688", kbSms2))
    }

    @Test fun theSameNotificationAgainIsTheSameTransaction() {
        // 앱이 같은 알림을 고쳐 다시 올리거나 시스템이 묶으며 다시 알려 줘도 키가 같음
        assertFalse(Notices.another(listOf(Seen(push, toss, tossKey1)), push, toss, tossKey1))
        // 문자 앱이 같은 문자를 다시 올림
        assertFalse(Notices.another(listOf(Seen(sms, "15881688", kbSms1)), sms, "15881688", kbSms1))
    }

    @Test fun pushAndSmsForOnePaymentStayOne() {
        // 앱 푸시로 기록된 결제에 문자가 와도, 문자로 기록된 결제에 앱 푸시가 와도 같은 결제(D-5)
        assertFalse(Notices.another(listOf(Seen(push, "com.hyundaicard.appcard", tossKey1)), sms, "15776200", kbSms1))
        assertFalse(Notices.another(listOf(Seen(sms, "15776200", kbSms1)), push, "com.hyundaicard.appcard", tossKey1))
    }

    @Test fun aPaymentAlreadyToldByThisAppAndBySmsIsNotTheSecondOnesTwin() {
        // 첫 결제가 앱 푸시 + 문자로 이미 한 건이 된 뒤, 둘째 결제의 앱 푸시가 오면 다른 거래
        val first = listOf(Seen(push, toss, tossKey1), Seen(sms, "15997000", kbSms1))
        assertTrue(Notices.another(first, push, toss, tossKey2))
        // 둘째 결제의 문자도 첫 결제의 문자와는 다른 거래
        assertTrue(Notices.another(first, sms, "15997000", kbSms2))
    }

    @Test fun oldRecordsWithoutMarksKeepTheOldRule() {
        // 0.3.8 전에 기록된 거래(표시 없음)와 표시를 모르는 지금 알림은 지금처럼 같은 거래
        assertFalse(Notices.another(listOf(Seen(push, toss, null)), push, toss, tossKey2))
        assertFalse(Notices.another(listOf(Seen(push, toss, tossKey1)), push, toss, null))
        assertFalse(Notices.another(emptyList(), push, toss, tossKey2))
    }

    @Test fun smsMarkIsPerMessage() {
        // 같은 본문이라도 받은 시각(초)이 다르면 다른 문자, 같은 초 안이면 같은 문자
        assertEquals(Notices.sms(1_000_100, "x"), Notices.sms(1_000_900, "x"))
        assertFalse(Notices.sms(1_000_000, "x") == Notices.sms(1_002_000, "x"))
    }

    @Test fun tossAutoDebitNoticeHasNoBalanceToTellThemApart() {
        // 토스뱅크 출금 알림에는 잔액이 없어, 두 출금은 글이 똑같습니다 — 알림 키로만 가릴 수 있습니다.
        val now = LocalDateTime.of(2026, 11, 23, 9, 0)
        val a = Parser.parse("12,000원 출금\n토스뱅크 통장 → ○○회비", now, hint = "토스")!!
        val b = Parser.parse("12,000원 출금\n토스뱅크 통장 → ○○회비", now, hint = "토스")!!
        assertEquals(Parsed.Type.WITHDRAWAL, a.type)
        assertEquals(12_000L, a.amount)
        assertEquals(a, b)
        assertNull(a.balance)
    }

    @Test fun senderPhoneSendsTheSecondNoticeOfTheSameApp() {
        // 보내는 가족 폰(부모님 폰)도 같은 앱이 따로 올린 알림은 따로 보냅니다.
        val now = LocalDateTime.of(2026, 11, 23, 9, 0)
        val p = Parser.parse("12,000원 출금\n토스뱅크 통장 → ○○회비", now, hint = "토스")!!
        val ms = 1_763_856_000_000L
        val sent = listOf(SentLog.entry("x", p, ms, Kind.EXPENSE, null, Seen(push, toss, tossKey1)))
        assertNull(SentLog.sameSent(sent, p, ms + 5_000, Seen(push, toss, tossKey2)))
        // 같은 알림이 다시 오거나, 같은 결제가 문자로 오면 이미 보낸 것
        assertNotNull(SentLog.sameSent(sent, p, ms + 5_000, Seen(push, toss, tossKey1)))
        assertNotNull(SentLog.sameSent(sent, p, ms + 5_000, Seen(sms, "16617654", kbSms1)))
        // 0.3.8 전에 보낸 항목(표시 없음)은 지금처럼 같은 결제
        assertNotNull(SentLog.sameSent(listOf(SentLog.entry("y", p, ms, Kind.EXPENSE, null)), p, ms + 5_000, Seen(push, toss, tossKey2)))
    }
}
