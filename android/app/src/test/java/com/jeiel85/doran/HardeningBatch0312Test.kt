package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Fixed
import com.jeiel85.doran.logic.Moves
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.AllowanceSummary
import com.jeiel85.doran.model.Household
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 열린 하드닝 이슈 #74 #78 #81 #84 #87 묶음(0.3.13). 이름은 지어낸 것입니다. */
class HardeningBatch0312Test {
    private fun ms(h: Int, m: Int, d: Int = 20) = Fmt.millis(LocalDateTime.of(2026, 11, d, h, m))
    private val install = "install-h"
    private val husband = Member("h", "남편", allowance = 350_000)
    private val wife = Member("w", "아내")
    private val wifeBank = Account("acc.w", "신한은행", AccountType.BANK, ownerId = "w", institution = "신한은행")

    private fun wifeOut(id: String, amount: Long, at: Long) =
        Txn(id, Kind.EXPENSE, amount, at, accountId = wifeBank.id, merchant = "남편", memberId = "w", source = Source.SMS, issuer = "신한은행",
            createdBy = "install-w")
    private fun myIn(id: String, amount: Long, at: Long) =
        Txn(id, Kind.INCOME, amount, at, merchant = "아내_대출", memberId = "h", source = Source.PUSH, issuer = "KB국민은행", private = true, createdBy = install)
    private fun myOut(id: String, amount: Long, at: Long) =
        Txn(id, Kind.EXPENSE, amount, at, merchant = "대출", memberId = "h", source = Source.PUSH, issuer = "KB국민은행", private = true, createdBy = install)
    private fun ledger(vararg txns: Txn) = LedgerState(
        members = listOf(husband, wife).associateBy { it.id }, accounts = mapOf(wifeBank.id to wifeBank), txns = txns.associateBy { it.id })

    // #74: 손으로 이은 가족 돈이 사라지면 이음을 풂
    @Test fun pickedWithdrawalIsReleasedWhenItsFundGoesAway() {
        val sent = wifeOut("w1", 200_000, ms(13, 0))
        val fund = Moves.asFund(myIn("i1", 200_000, ms(13, 1)), sent)
        val out = Moves.pick(myOut("o1", 180_000, ms(9, 0, d = 21)), fund)
        assertTrue(Moves.orphanedPicks(ledger(sent, fund, out), "h", install).isEmpty())
        // 입금이 지워짐
        val gone = Moves.orphanedPicks(ledger(sent, out), "h", install).single()
        assertEquals(Moves.USER, gone.move)
        assertEquals(Kind.TRANSFER, gone.kind)
        assertNull(Moves.fromOf(gone))
        // 입금이 더는 가족 돈이 아님
        assertEquals(1, Moves.orphanedPicks(ledger(sent, fund.copy(move = null), out), "h", install).size)
        // 다른 폰이 기록한 것은 건드리지 않음
        assertTrue(Moves.orphanedPicks(ledger(sent, out.copy(createdBy = "other")), "h", install).isEmpty())
    }

    // #81: 가린 이름도 같은 사람으로
    @Test fun maskedNamesMatch() {
        assertTrue(Moves.sameName("홍길순", "홍길순"))
        assertTrue(Moves.sameName("홍길순", "홍길순_축의"))
        assertTrue(Moves.sameName("홍*순", "홍길순_축의"))
        assertTrue(Moves.sameName("홍길순", "홍*순"))
        assertFalse(Moves.sameName("홍길순", "홍길동"))
        assertFalse(Moves.sameName("홍길순", "홍길순이")) // 뒤에 한글이 이어지면 다른 이름
        assertFalse(Moves.sameName("홍**", "홍길순")) // 아는 글자가 하나뿐
        assertFalse(Moves.sameName("홍", "홍길순"))
    }

    // #81: 같은 금액·3분 안 입금이라도 보낸 분이 다르면 다른 돈
    @Test fun depositsFromDifferentPayersAreNotMerged() {
        assertTrue(Moves.samePayer(Kind.INCOME, "홍길순", "홍길순"))
        assertTrue(Moves.samePayer(Kind.INCOME, "홍*순", "홍길순"))
        assertTrue(Moves.samePayer(Kind.INCOME, "", "홍길순")) // 한쪽이 이름을 안 적으면 예전처럼 같은 알림
        assertFalse(Moves.samePayer(Kind.INCOME, "김철수", "홍길순_축의"))
        // 카드 결제는 앱 알림·문자의 가게 이름이 달라도 같은 결제
        assertTrue(Moves.samePayer(Kind.EXPENSE, "스타벅스 가산점", "STARBUCKS"))
    }

    // #81: 입금에 보낸 곳
    @Test fun depositShowsWhereTheMoneyCameFrom() {
        val shared = Account("acc.k", "케이뱅크", AccountType.BANK, ownerId = "h", institution = "케이뱅크")
        val out = wifeOut("w1", 100_000, ms(10, 0))
        val into = Txn("i1", Kind.INCOME, 100_000, ms(10, 1), accountId = shared.id, merchant = "아내", memberId = "h", source = Source.PUSH, issuer = "케이뱅크")
        val (a, b) = Moves.link(out, into)
        val s = LedgerState(members = listOf(husband, wife).associateBy { it.id }, accounts = listOf(wifeBank, shared).associateBy { it.id },
            txns = listOf(a, b).associateBy { it.id })
        assertEquals("아내 신한은행", Moves.origin(s, b))
        assertEquals("남편 케이뱅크", Moves.destination(s, a))
        assertNull(Moves.origin(s, a)) // 출금에는 없음
        // 가족 돈 입금
        val fund = Moves.asFund(myIn("i2", 200_000, ms(13, 1)), out)
        assertEquals("아내 신한은행", Moves.origin(s.copy(txns = s.txns + (fund.id to fund)), fund))
    }

    // #87: 구독료가 오른 달에도 규칙 자리를 채움
    @Test fun recurringSlotFitsAChangedAmountWithTheSameName() {
        val planned = Txn("rc.ott.20261105", Kind.EXPENSE, 13_500, ms(9, 0, d = 5), merchant = "넷플릭스", source = Source.RECURRING, recurringId = "ott")
        val same = Txn("t1", Kind.EXPENSE, 13_500, ms(9, 10, d = 5), merchant = "아무 가게")
        val raised = Txn("t2", Kind.EXPENSE, 17_000, ms(9, 10, d = 5), merchant = "NETFLIX 넷플릭스")
        val other = Txn("t3", Kind.EXPENSE, 17_000, ms(9, 10, d = 5), merchant = "편의점")
        val doubled = Txn("t4", Kind.EXPENSE, 30_000, ms(9, 10, d = 5), merchant = "넷플릭스")
        assertEquals(planned, Fixed.planned(listOf(planned), same, null))
        assertEquals(planned, Fixed.planned(listOf(planned), raised, null))
        assertNull(Fixed.planned(listOf(planned), other, null)) // 이름이 다르면 금액이 같아야
        assertNull(Fixed.planned(listOf(planned), doubled, null)) // 30%를 넘게 다름
        assertTrue(Fixed.nameFits("넷플릭스", "넷플릭스코리아"))
        assertFalse(Fixed.nameFits("넷", "넷플릭스"))
    }

    // #78: 이 폰의 한 달이 가족과 다르면 다른 가족 용돈 합계 줄에 그렇다고 적음
    @Test fun otherMembersAllowanceTotalNamesTheFamilyMonth() {
        val s = LedgerState(household = Household(monthStartDay = 1), members = listOf(husband, wife).associateBy { it.id },
            allowances = mapOf("a.w.202611" to AllowanceSummary("a.w.202611", "w", "202611", spent = 50_000, count = 3)))
        val family = Stats.entries(s, Stats.period(java.time.YearMonth.of(2026, 11), 1), "h").single { it.allowanceSummary }
        assertFalse(family.txn.merchant.contains("기준"))
        val mine = Stats.entries(s, Stats.period(java.time.YearMonth.of(2026, 11), 25), "h").single { it.allowanceSummary }
        assertTrue(mine.txn.merchant.contains("가족 한 달(1일 시작) 기준"))
    }
}
