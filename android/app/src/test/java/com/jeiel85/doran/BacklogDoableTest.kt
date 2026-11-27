package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.feature.Receipts
import com.jeiel85.doran.logic.Accounts
import com.jeiel85.doran.logic.Banks
import com.jeiel85.doran.logic.Moves
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.logic.Suggest
import com.jeiel85.doran.logic.Twins
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.AllowanceSummary
import com.jeiel85.doran.model.Household
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.Recurring
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** 바로 할 수 있던 백로그 묶음(#93 등). 이름·금액은 지어낸 것입니다. */
class BacklogDoableTest {
    private fun ms(m: Int, d: Int, h: Int = 12, min: Int = 0) = Fmt.millis(LocalDateTime.of(2026, m, d, h, min))

    @Test fun orphanReceiptsOlderThanADay() {
        val now = ms(11, 27)
        val files = mapOf("keep" to now - 3 * 86_400_000L, "old" to now - 2 * 86_400_000L, "new" to now - 60_000L)
        assertEquals(listOf("old"), Receipts.orphans(files, setOf("keep"), now))
    }

    @Test fun carryUsesTheFamilyMonth() {
        // 가족 한 달은 1일 시작, 이 폰은 25일 시작: 두 폰 모두 가족 10월(10/1~10/31) 기준으로 같은 이월
        val s = LedgerState(household = Household(monthStartDay = 1, budget = 1_000_000, carryOver = true),
            txns = listOf(Txn("a", Kind.EXPENSE, 600_000, ms(10, 10)), Txn("b", Kind.EXPENSE, 300_000, ms(10, 28))).associateBy { it.id })
        val family = Stats.carryIn(s, Stats.periodOf(LocalDate.of(2026, 11, 15), 1), null)
        val phone = Stats.carryIn(s, Stats.periodOf(LocalDate.of(2026, 11, 26), 25), null)
        assertEquals(100_000L, family)
        assertEquals(family, phone)
    }

    @Test fun twoMonthSuggestionsNeedTheExactAmount() {
        fun t(id: String, amount: Long, m: Int, d: Int) = Txn(id, Kind.EXPENSE, amount, ms(m, d), merchant = "주유소")
        val today = LocalDate.of(2026, 11, 27)
        val drift = LedgerState(txns = listOf(t("a", 60_000, 10, 5), t("b", 63_000, 11, 6)).associateBy { it.id })
        assertTrue(Suggest.find(drift, today, emptySet()).isEmpty())
        val three = LedgerState(txns = listOf(t("a", 60_000, 9, 5), t("b", 63_000, 10, 6), t("c", 61_000, 11, 5)).associateBy { it.id })
        assertEquals(3, Suggest.find(three, today, emptySet()).single().months)
    }

    @Test fun bankSmsFirstIsTheTwinOfTheCardPayment() {
        val bank = Account("b", "신한", AccountType.BANK, institution = "신한은행")
        val card = Account("c", "신한 체크", AccountType.CHECK, institution = "신한카드", payFromId = "b")
        val out = Txn("o", Kind.EXPENSE, 12_000, ms(11, 20, 12, 0), accountId = "b", source = Source.SMS, createdBy = "me")
        val s = LedgerState(accounts = listOf(bank, card).associateBy { it.id }, txns = mapOf(out.id to out))
        val w = 3 * 60_000L
        assertEquals("o", Banks.bankTwinOfCardPayment(s, card, 12_000, ms(11, 20, 12, 1), w, "me")?.id)
        assertNull(Banks.bankTwinOfCardPayment(s, card, 12_000, ms(11, 20, 12, 1), w, "other")) // 다른 폰의 기록은 건드리지 않음
        val judged = s.copy(txns = mapOf(out.id to out.copy(move = Moves.USER)))
        assertNull(Banks.bankTwinOfCardPayment(judged, card, 12_000, ms(11, 20, 12, 1), w, "me")) // 판정이 붙은 출금은 그대로
    }

    @Test fun existingTwinsKeepTheFirstRecord() {
        val a = Txn("a", Kind.EXPENSE, 9_000, ms(11, 20, 9, 0), merchant = "동네주유소", memberId = "h", source = Source.PUSH, issuer = "현대카드",
            createdBy = "ph", createdAt = 1)
        val b = a.copy(id = "b", memberId = "w", createdBy = "pw", createdAt = 2, at = ms(11, 20, 9, 1))
        val pairs = Twins.existingPairs(LedgerState(txns = listOf(a, b).associateBy { it.id }), ms(11, 27))
        assertEquals(listOf("a" to "b"), pairs.map { it.first.id to it.second.id })
        // 같은 폰의 두 기록은 여기서 다루지 않음(D-5 중복 알림)
        assertTrue(Twins.existingPairs(LedgerState(txns = listOf(a, b.copy(memberId = "h", createdBy = "ph")).associateBy { it.id }), ms(11, 27)).isEmpty())
    }

    @Test fun mergeMovesEverythingToTheOtherAccount() {
        val from = Account("x", "현대카드 1234", AccountType.CARD, institution = "현대카드", last4 = "1234")
        val into = Account("y", "가족 카드", AccountType.CARD)
        val r = Recurring("r", "넷플릭스", Kind.EXPENSE, 13_500, null, "x", null, startAt = 0)
        val s = LedgerState(accounts = listOf(from, into).associateBy { it.id }, recurrings = mapOf(r.id to r),
            txns = listOf(Txn("t1", Kind.EXPENSE, 1, 0, accountId = "x"), Txn("t2", Kind.TRANSFER, 1, 0, accountId = "z", toAccountId = "x")).associateBy { it.id })
        val m = Accounts.merge(s, from, into)
        assertEquals(setOf("y"), m.txns.flatMap { listOfNotNull(it.accountId.takeIf { a -> a != "z" }, it.toAccountId) }.toSet())
        assertEquals("y", m.recurrings.single().accountId)
        assertEquals("1234", m.accounts.single { it.id == "y" }.last4) // 다음 알림도 합친 계좌로 오게
    }

    @Test fun personNamesAreRecognisedForTheRememberWarning() {
        assertTrue(Moves.looksLikePerson("홍길순"))
        assertTrue(Moves.looksLikePerson("홍*순"))
        assertTrue(Moves.looksLikePerson("홍길순_축의"))
        // 세 글자 한글 낱말("관리비")도 사람 이름 꼴이라 안내가 뜰 수 있음 — 막지 않고 안내만 하므로 감수
        assertFalse(Moves.looksLikePerson("GS25 가산점"))
        assertFalse(Moves.looksLikePerson("**"))
    }

    @Test fun allowanceTotalShowsWhatIsLeft() {
        val s = LedgerState(household = Household(monthStartDay = 1), members = listOf(Member("h", "남편", allowance = 350_000), Member("w", "아내")).associateBy { it.id },
            allowances = mapOf("a.h.202611" to AllowanceSummary("a.h.202611", "h", "202611", spent = 150_000, count = 4)))
        val line = Stats.entries(s, Stats.period(YearMonth.of(2026, 11), 1), "w").single { it.allowanceSummary }
        assertTrue(line.txn.merchant.contains("남은 200,000원"))
    }
}
