package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.AllowanceSummary
import com.jeiel85.doran.model.Defaults
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 가족 카드와 월 용돈 (DECISIONS.md D-20): 남편 명의 현대카드는 가족 공용으로 묶고,
 * 용돈 35만원은 고정지출로 한 번만 세며 남편이 그 안에서 쓴 것은 가계부 합계에 더하지 않습니다.
 */
class FamilyCardAllowanceTest {
    private fun ms(d: Int, h: Int = 12) = Fmt.millis(LocalDateTime.of(2026, 11, d, h, 0))
    private val p = Stats.period(YearMonth.of(2026, 11), 1)

    private val husband = Member("h", "남편", allowance = 350_000)
    private val wife = Member("w", "아내")
    private val hyundai = Account("acc.hd", "현대카드", AccountType.CARD, ownerId = "h", institution = "현대카드", family = true)
    private val shinhanWife = Account("acc.sh", "신한카드", AccountType.CARD, ownerId = "w", institution = "신한카드")

    private val netflix = Txn("t1", Kind.EXPENSE, 17_000, ms(3), accountId = hyundai.id, memberId = "h", merchant = "넷플릭스", source = Source.SMS)
    private val pocket = Txn("t2", Kind.EXPENSE, 350_000, ms(1, 9), categoryId = Defaults.ALLOWANCE, memberId = "h", merchant = "남편 용돈",
        source = Source.RECURRING, recurringId = "rec.allowance.h")
    private val grocery = Txn("t3", Kind.EXPENSE, 50_000, ms(5), accountId = shinhanWife.id, memberId = "w", merchant = "이마트")
    /** 남편 폰에만 있는 용돈 내역(개인 키). */
    private val gs25 = Txn("t4", Kind.EXPENSE, 8_900, ms(6), memberId = "h", merchant = "GS25", private = true, issuer = "신한카드")
    private val summary = AllowanceSummary("a.h.202611", "h", "202611", 58_900, 2)

    private fun ledger(vararg txns: Txn, members: List<Member> = listOf(husband, wife)) = LedgerState(
        members = members.associateBy { it.id }, accounts = listOf(hyundai, shinhanWife).associateBy { it.id },
        txns = txns.associateBy { it.id }, allowances = mapOf(summary.id to summary),
    )

    @Test fun wifeCountsAllowanceOnceAndGroupsFamilyCard() {
        val s = ledger(netflix, pocket, grocery)
        val e = Stats.entries(s, p, myMemberId = "w")
        // 용돈 35만원 + 가족 카드 1.7만 + 아내 5만. 남편 용돈 사용 58,900원은 참고로만
        assertEquals(417_000L, Stats.expense(e))
        val ref = e.single { it.allowanceSummary }
        assertFalse(ref.counted)
        assertTrue(ref.txn.merchant.startsWith("남편 용돈 사용"))
        assertEquals(listOf(Stats.FAMILY to 17_000L, "h" to 350_000L, "w" to 50_000L).toSet(), Stats.byMember(e, s = s).toSet())
        // 용돈 카테고리에는 35만원만(사용 합계 58,900원이 더해지지 않음)
        assertEquals(350_000L, Stats.byCategory(e, Kind.EXPENSE).toMap()[Defaults.ALLOWANCE])
    }

    @Test fun husbandPhoneDoesNotAddHisPrivateSpendingAgain() {
        val s = ledger(netflix, pocket, grocery, gs25)
        val e = Stats.entries(s, p, myMemberId = "h")
        assertEquals(417_000L, Stats.expense(e))
        val mine = e.single { it.txn.id == gs25.id }
        assertFalse(mine.counted)
        // 남편 폰은 자기 용돈 합계 줄을 따로 만들지 않음
        assertTrue(e.none { it.allowanceSummary })
        assertEquals(0L, Stats.byDay(e)[Fmt.date(gs25.at)]?.first ?: 0L)
    }

    @Test fun withoutFixedAllowanceSummaryStillCountsLikeBefore() {
        val noAllowance = husband.copy(allowance = 0)
        val s = ledger(netflix, grocery, members = listOf(noAllowance, wife))
        val e = Stats.entries(s, p, myMemberId = "w")
        assertEquals(17_000L + 50_000 + 58_900, Stats.expense(e))
        assertTrue(e.single { it.allowanceSummary }.counted)
        // 가족 카드가 아니면 지금처럼 쓴 사람 칸으로
        val plain = s.copy(accounts = s.accounts + (hyundai.id to hyundai.copy(family = false)))
        assertEquals(17_000L + 58_900, Stats.byMember(Stats.entries(plain, p, "w"), s = plain).toMap()["h"])
    }

    @Test fun movedPersonalPurchaseKeepsCardBillButNotLedgerTotal() {
        // 남편이 가족 카드 결제(올리브영 23,000)를 용돈으로 옮김: 원본은 남편 폰에만(private), 가족 카드엔 금액 자리(personal)
        val moved = Txn("t5", Kind.EXPENSE, 23_000, ms(7), accountId = hyundai.id, memberId = "h", merchant = "올리브영", private = true)
        val stub = Txn("stub.t5", Kind.EXPENSE, 23_000, ms(7), accountId = hyundai.id, memberId = "h", merchant = "남편 개인 결제(용돈)",
            categoryId = Defaults.ALLOWANCE, personal = true)
        val today = java.time.LocalDate.of(2026, 11, 18)
        // 아내 폰: 자리만 보임 → 카드 이번 달 17,000 + 23,000, 가계부 합계에는 안 더함
        val wifeView = ledger(netflix, pocket, grocery, stub)
        assertEquals(40_000L, Stats.card(wifeView, hyundai, today).monthSpent)
        assertEquals(417_000L, Stats.expense(Stats.entries(wifeView, p, "w")))
        // 남편 폰: 원본과 자리가 둘 다 있어도 카드 금액은 한 번만
        val husbandView = ledger(netflix, pocket, grocery, stub, moved)
        assertEquals(40_000L, Stats.card(husbandView, hyundai, today).monthSpent)
        assertEquals(40_000L, Stats.cardOutstanding(husbandView, hyundai, today))
        assertEquals(417_000L, Stats.expense(Stats.entries(husbandView, p, "h")))
        // personal은 json으로 오가도 그대로
        assertTrue(Txn.from(stub.id, JSONObject(stub.json().toString())).personal)
    }

    @Test fun familyFlagRoundTripsAndDefaultsOff() {
        assertEquals(hyundai, Account.from(hyundai.id, JSONObject(hyundai.json().toString())))
        assertFalse(Account.from("x", JSONObject().put("name", "옛 카드").put("type", "card")).family)
    }
}
