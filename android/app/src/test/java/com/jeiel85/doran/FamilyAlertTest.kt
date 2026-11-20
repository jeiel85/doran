package com.jeiel85.doran

import com.jeiel85.doran.capture.SmsReceiver
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.FamilyAlert
import com.jeiel85.doran.logic.Hooks
import com.jeiel85.doran.logic.Josa
import com.jeiel85.doran.logic.Twins
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 가족 알림 문장, 같은 결제 두 번 기록 막기, 전달받은 문자 거르기. */
class FamilyAlertTest {
    private val husband = Member("h", "남편")
    private val wife = Member("w", "아내", color = 1)
    private val mom = Member("m", "어머니", role = "sender")
    private val card = Account("card", "현대카드", AccountType.CARD, ownerId = "h", institution = "현대카드")
    private val bank = Account("bank", "KB국민은행", AccountType.BANK, ownerId = "w", institution = "KB국민은행")
    private val s = LedgerState(
        members = listOf(husband, wife, mom).associateBy { it.id },
        accounts = listOf(card, bank).associateBy { it.id },
    )

    private fun cardTxn(id: String = "t1", member: String = "h", at: Long = 1_000_000L, merchant: String = "스타벅스 성수점",
                        label: String = "현대카드(1234)", source: Source = Source.PUSH, createdAt: Long = 1) =
        Txn(id, Kind.EXPENSE, 12_300, at, accountId = "card", merchant = merchant, memberId = member, source = source,
            cumulative = 523_000, issuer = "현대카드", cardLabel = label, createdAt = createdAt)

    @Test fun josaFollowsBatchim() {
        assertEquals("남편이", Josa.iGa("남편"))
        assertEquals("아내가", Josa.iGa("아내"))
        assertEquals("현대카드로", Josa.ro("현대카드"))
        assertEquals("통장으로", Josa.ro("통장"))
        assertEquals("서울로", Josa.ro("서울"))
        assertEquals("Jun이", Josa.iGa("Jun"))
    }

    @Test fun budgetTitlesHaveNoGapOrBracketParticles() {
        assertEquals("이번 달 예산의 80%를 썼어요", Hooks.budgetTitle("이번 달 예산", 80))
        assertEquals("이번 달 예산을 다 썼어요", Hooks.budgetTitle("이번 달 예산", 100))
        assertEquals("식비의 80%를 썼어요", Hooks.budgetTitle("식비", 80))
        assertEquals("식비를 다 썼어요", Hooks.budgetTitle("식비", 100))
        assertEquals("카페·간식을 다 썼어요", Hooks.budgetTitle("카페·간식", 100))
    }

    @Test fun shareLabelsTakeRoParticle() {
        assertEquals("함께 관리로", Josa.ro("함께 관리"))
        assertEquals("현대카드만 보내기로", Josa.ro("현대카드만 보내기"))
        assertEquals("보내는 카드 없음으로", Josa.ro("보내는 카드 없음"))
    }

    @Test fun cardSpendReadsLikeASentence() {
        val t = FamilyAlert.of(s, cardTxn())
        assertEquals("남편이 현대카드로 12,300원 썼어요", t.title)
        assertEquals("스타벅스 성수점 · 카드사 누적 523,000원", t.body)
        val momText = FamilyAlert.of(s, cardTxn(member = "m").copy(accountId = null, cumulative = 230_000, merchant = "온누리약국"))
        assertEquals("어머니가 현대카드로 12,300원 썼어요", momText.title)
    }

    @Test fun cancelIncomeAndTransferTexts() {
        assertEquals("남편의 현대카드 12,300원 결제가 취소됐어요", FamilyAlert.of(s, cardTxn().copy(cancelOf = "x")).title)
        val income = Txn("i", Kind.INCOME, 3_200_000, 1, accountId = "bank", merchant = "급여", memberId = "w", source = Source.SMS, issuer = "KB국민은행")
        assertEquals("아내 KB국민은행에 3,200,000원이 들어왔어요", FamilyAlert.of(s, income).title)
        val pay = Txn("p", Kind.TRANSFER, 523_000, 1, accountId = "bank", toAccountId = "card", merchant = "현대카드 카드값", memberId = "w",
            source = Source.SMS, issuer = "KB국민은행")
        assertEquals("아내 KB국민은행에서 현대카드로 523,000원이 나갔어요", FamilyAlert.of(s, pay).title)
        val installment = FamilyAlert.of(s, cardTxn().copy(installment = 3))
        assertTrue(installment.body.contains("3개월 할부"))
    }

    @Test fun twinsMatchSamePaymentFromTwoPhones() {
        val a = cardTxn("a", "h", at = 1_000_000, createdAt = 10)
        val b = cardTxn("b", "w", at = 1_060_000, source = Source.SMS, createdAt = 20).copy(merchant = "스타벅스")
        assertTrue(Twins.same(a, b))
        assertEquals("a", Twins.keeper(a, b).id)
        // 다른 카드 번호, 다른 가게, 3분 넘게 차이, 수동 입력이면 다른 결제
        assertFalse(Twins.same(a, b.copy(cardLabel = "현대카드(9999)")))
        assertFalse(Twins.same(a, b.copy(merchant = "이디야커피")))
        assertFalse(Twins.same(a, b.copy(at = 1_000_000 + 4 * 60_000)))
        assertFalse(Twins.same(a, b.copy(source = Source.MANUAL)))
        assertFalse(Twins.same(a, b.copy(cancelOf = "a")))
    }

    @Test fun inviteLinksRoundTrip() {
        val secret = ByteArray(32) { it.toByte() }
        val link = com.jeiel85.doran.sync.Pairing.link(secret, "sender", "남편")
        assertTrue(link.startsWith("https://jeiel85.github.io/doran/join/#s."))
        for (form in listOf(link, "doran://join/" + link.substringAfter('#'),
                "https://jeiel85.github.io/doran/join/?install=1#" + link.substringAfter('#'), "초대: $link 눌러 주세요")) {
            val p = com.jeiel85.doran.sync.Pairing.parse(form.substringAfter("초대: ").substringBefore(" 눌러"))!!
            assertTrue(form, p.secret.contentEquals(secret))
            assertEquals("sender", p.role)
            assertEquals("남편", p.inviter)
        }
        val legacy = com.jeiel85.doran.sync.Pairing.parse("doran:1:" + com.jeiel85.doran.crypto.Crypto.b64(secret))!!
        assertTrue(legacy.secret.contentEquals(secret))
        assertEquals(null, legacy.role)
        assertEquals(null, com.jeiel85.doran.sync.Pairing.parse("https://jeiel85.github.io/doran/join/#x.abc"))
        assertEquals(null, com.jeiel85.doran.sync.Pairing.parse("아무 글"))
    }

    @Test fun forwardedTextsFromPersonalNumbersAreIgnored() {
        assertTrue(SmsReceiver.isPersonalNumber("010-1234-5678"))
        assertTrue(SmsReceiver.isPersonalNumber("+821012345678"))
        assertFalse(SmsReceiver.isPersonalNumber("15776000"))
        assertFalse(SmsReceiver.isPersonalNumber("1588-1688"))
        assertFalse(SmsReceiver.isPersonalNumber("0221234567"))
        assertFalse(SmsReceiver.isPersonalNumber(null))
    }
}
