package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.AllowancePlan
import com.jeiel85.doran.logic.Fixed
import com.jeiel85.doran.model.Defaults
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Recurring
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** 고정 규칙의 이름이 담긴 내역의 종류와 맞는지, 알림이 같은 종류의 고정 내역만 채우는지. */
class FixedTest {
    private fun ms(d: Int, h: Int = 12) = Fmt.millis(LocalDateTime.of(2026, 11, d, h, 0))
    private val s = LedgerState(categories = Defaults.categories.associateBy { it.id })

    private fun rule(id: String, kind: Kind) = Recurring(id, id, kind, 10_000, null, null, null, startAt = ms(1))

    @Test fun labelFollowsTheKindsShown() {
        assertEquals("고정지출", Fixed.label(Kind.EXPENSE))
        assertEquals("고정수입", Fixed.label(Kind.INCOME))
        assertEquals("고정지출", Fixed.label(listOf(Kind.EXPENSE, Kind.EXPENSE)))
        // 홈 "다가오는 …"에 용돈·월급 같은 수입만 있으면 '고정지출'이라 부르지 않습니다.
        assertEquals("고정수입", Fixed.label(listOf(Kind.INCOME)))
        assertEquals("고정 수입·지출", Fixed.label(listOf(Kind.EXPENSE, Kind.INCOME)))
        assertEquals("고정지출", Fixed.label(emptyList()))
    }

    @Test fun listTitleKeepsSubscriptionsWordingOnlyWithoutIncome() {
        assertEquals("고정지출·구독", Fixed.title(emptyList()))
        assertEquals("고정지출·구독", Fixed.title(listOf(rule("a", Kind.EXPENSE))))
        assertEquals("고정 수입·지출", Fixed.title(listOf(rule("a", Kind.EXPENSE), rule("b", Kind.INCOME))))
        assertEquals("고정수입", Fixed.title(listOf(rule("b", Kind.INCOME))))
    }

    @Test fun sourceNameOfARecurringIncomeIsFixedIncome() {
        val pay = Txn("t", Kind.INCOME, 3_000_000, ms(25), merchant = "월급", source = Source.RECURRING)
        assertEquals("고정수입", Fixed.source(pay))
        assertEquals("고정지출", Fixed.source(pay.copy(kind = Kind.EXPENSE)))
        assertEquals("문자", Fixed.source(pay.copy(source = Source.SMS)))
    }

    @Test fun switchingARuleToIncomeDropsTheExpenseCategory() {
        // 새 규칙의 기본 카테고리 '구독'이 수입 규칙에 남지 않게.
        assertEquals("c.salary", Fixed.categoryFor(s, "월급", Kind.INCOME, "c.subscription"))
        assertEquals(Defaults.INCOME_FALLBACK, Fixed.categoryFor(s, "씻돌 용돈", Kind.INCOME, "c.subscription"))
        assertEquals("c.side", Fixed.categoryFor(s, "씻돌 용돈", Kind.INCOME, "c.side"))
        // 다시 지출로 돌리면 이름으로 고르고(넷플릭스 → 구독), 모르는 이름은 '기타'.
        assertEquals("c.subscription", Fixed.categoryFor(s, "넷플릭스", Kind.EXPENSE, Defaults.INCOME_FALLBACK))
        assertEquals(Defaults.EXPENSE_FALLBACK, Fixed.categoryFor(s, "", Kind.EXPENSE, null))
        assertEquals("c.housing", Fixed.categoryFor(s, "관리비", Kind.EXPENSE, "c.housing"))
    }

    private val plannedIncome = Txn("rc.r1.20261125", Kind.INCOME, 350_000, ms(25, 9), merchant = "씻돌 용돈",
        source = Source.RECURRING, recurringId = "r1")
    private val plannedExpense = Txn("rc.r2.20261125", Kind.EXPENSE, 350_000, ms(25, 9), merchant = "남편 용돈",
        source = Source.RECURRING, recurringId = "r2", accountId = "acc.bank")

    @Test fun cardPaymentNeverOverwritesAPlannedIncome() {
        // 예전에는 금액만 봐서, 35만원 카드 결제가 같은 금액의 고정수입 내역을 지출로 덮어썼어요.
        val card = Txn("n", Kind.EXPENSE, 350_000, ms(25, 15), accountId = "acc.card", source = Source.PUSH)
        assertNull(Fixed.planned(listOf(plannedIncome), card, "acc.card"))
    }

    @Test fun depositFillsThePlannedIncomeInsteadOfCountingItTwice() {
        val deposit = Txn("n", Kind.INCOME, 350_000, ms(26, 10), accountId = "acc.bank", source = Source.PUSH)
        assertEquals(plannedIncome.id, Fixed.planned(listOf(plannedExpense, plannedIncome), deposit, "acc.bank")?.id)
    }

    @Test fun withdrawalStillFillsThePlannedExpense() {
        // DECISIONS.md D-20: 아내가 계좌이체로 용돈을 보내면 그 출금 알림이 '남편 용돈' 자리를 채웁니다.
        val out = Txn("n", Kind.EXPENSE, 350_000, ms(25, 11), accountId = "acc.bank", source = Source.SMS)
        assertEquals(plannedExpense.id, Fixed.planned(listOf(plannedIncome, plannedExpense), out, "acc.bank")?.id)
        // 미리 정한 계좌와 다르면, 3일을 넘으면, 금액이 다르면 맞추지 않아요.
        assertNull(Fixed.planned(listOf(plannedExpense), out.copy(accountId = "acc.other"), "acc.other"))
        assertNull(Fixed.planned(listOf(plannedExpense), out.copy(at = ms(29, 11)), "acc.bank"))
        assertNull(Fixed.planned(listOf(plannedExpense), out.copy(amount = 349_000), "acc.bank"))
    }

    @Test fun cancelsAndTransfersAreNotMatched() {
        val cancel = Txn("c", Kind.EXPENSE, 350_000, ms(25, 11), cancelOf = "x", source = Source.PUSH)
        val transfer = Txn("t", Kind.TRANSFER, 350_000, ms(25, 11), source = Source.PUSH)
        assertNull(Fixed.planned(listOf(plannedExpense), cancel, null))
        assertNull(Fixed.planned(listOf(plannedExpense), transfer, null))
        // 원래 승인을 못 찾은 취소는 cancelOf 없는 '확인 필요' 수입으로 기록돼요. 고정수입 자리를 채우면 안 됩니다(#34 리뷰).
        val orphanRefund = Txn("o", Kind.INCOME, 350_000, ms(25, 11), categoryId = Defaults.INCOME_FALLBACK, needsReview = true, source = Source.PUSH)
        assertNull(Fixed.planned(listOf(plannedIncome), orphanRefund, null, cancelNotice = true))
        assertEquals(plannedIncome.id, Fixed.planned(listOf(plannedIncome), orphanRefund, null)?.id) // 입금 알림이었다면 채움
        // 이미 알림으로 채운 자리(출처가 고정이 아님)는 다시 채우지 않아요.
        val filled = plannedExpense.copy(source = Source.SMS)
        assertNull(Fixed.planned(listOf(filled), Txn("n", Kind.EXPENSE, 350_000, ms(25, 12), source = Source.PUSH), null))
    }

    @Test fun theClosestPlannedEntryIsFilled() {
        // 같은 금액의 구독 둘이 며칠 사이로 있으면 날짜가 가까운 자리를 채움(#36). 예전에는 목록 순서로 먼저 나온 것
        val a = Txn("rc.a.20261123", Kind.EXPENSE, 9_900, ms(23, 9), merchant = "구독 A", source = Source.RECURRING, recurringId = "a")
        val b = Txn("rc.b.20261125", Kind.EXPENSE, 9_900, ms(25, 9), merchant = "구독 B", source = Source.RECURRING, recurringId = "b")
        val card = Txn("n", Kind.EXPENSE, 9_900, ms(25, 15), accountId = "acc.card", source = Source.PUSH)
        assertEquals(b.id, Fixed.planned(listOf(a, b), card, "acc.card")?.id)
        assertEquals(b.id, Fixed.planned(listOf(b, a), card, "acc.card")?.id)
        assertEquals(a.id, Fixed.planned(listOf(b, a), card.copy(at = ms(23, 20)), "acc.card")?.id)
    }

    @Test fun privateAllowancePaymentsDoNotFillHouseholdEntries() {
        // 나만 보는 용돈 결제·용돈으로 옮긴 결제는 가족 가계부의 고정 내역 자리를 채우지 않음(#36)
        val pay = Txn("n", Kind.EXPENSE, 350_000, ms(25, 11), source = Source.PUSH)
        assertNull(Fixed.planned(listOf(plannedExpense), pay.copy(private = true), null))
        assertNull(Fixed.planned(listOf(plannedExpense), pay.copy(personal = true), null))
        assertNull(Fixed.planned(listOf(plannedExpense.copy(private = true)), pay, null))
    }

    @Test fun theRulesPersonStaysWhenANoticeFillsTheEntry() {
        // 아내가 보낸 남편 용돈 이체가 '남편 용돈' 자리를 채워도 남편 몫(D-20, #36)
        val planned = plannedExpense.copy(memberId = "husband")
        val transfer = Txn("n", Kind.EXPENSE, 350_000, ms(25, 11), memberId = "wife", source = Source.SMS)
        assertEquals("husband", Fixed.memberFor(planned, transfer))
        assertEquals("wife", Fixed.memberFor(planned.copy(memberId = null), transfer))
    }

    @Test fun wrongKindCategoriesLeftByOldVersionsAreRepaired() {
        val income = Recurring("rec.salary", "월급", Kind.INCOME, 3_000_000, "c.subscription", null, null, startAt = ms(1))
        val ok = Recurring("rec.netflix", "넷플릭스", Kind.EXPENSE, 17_000, "c.subscription", null, null, startAt = ms(1))
        val allowance = Recurring(AllowancePlan.idFor("h"), "남편 용돈", Kind.INCOME, 350_000, Defaults.ALLOWANCE, null, "h", startAt = ms(1))
        val salaryEntry = Txn("rc.rec.salary.20261125", Kind.INCOME, 3_000_000, ms(25, 9), categoryId = "c.subscription",
            merchant = "월급", source = Source.RECURRING, recurringId = income.id)
        val flipped = Txn("rc.${allowance.id}.20261101", Kind.INCOME, 350_000, ms(1, 9), categoryId = Defaults.INCOME_FALLBACK,
            merchant = "남편 용돈", memberId = "h", source = Source.RECURRING, recurringId = allowance.id)
        // 사람이 고쳤거나 알림이 채운 내역은 그대로
        val edited = salaryEntry.copy(id = "x", source = Source.MANUAL)
        val st = s.copy(recurrings = listOf(income, ok, allowance).associateBy { it.id },
            txns = listOf(salaryEntry, flipped, edited).associateBy { it.id })
        val (rules, txns) = Fixed.repairs(st)
        assertEquals(listOf("rec.salary"), rules.map { it.id })
        assertEquals("c.salary", rules.single().categoryId)
        val byId = txns.associateBy { it.id }
        assertEquals(setOf(salaryEntry.id, flipped.id), byId.keys)
        assertEquals("c.salary", byId.getValue(salaryEntry.id).categoryId)
        assertEquals(Kind.EXPENSE, byId.getValue(flipped.id).kind)
        assertEquals(Defaults.ALLOWANCE, byId.getValue(flipped.id).categoryId)
        assertEquals(350_000L, byId.getValue(flipped.id).amount)
        // 고친 뒤 다시 돌리면 할 일이 없음
        val again = st.copy(recurrings = st.recurrings + rules.associateBy { it.id }, txns = st.txns + byId)
        assertTrue(Fixed.repairs(again).first.isEmpty())
        assertTrue(Fixed.repairs(again).second.isEmpty())
    }

    @Test fun allowanceRuleIdsAreRecognised() {
        assertTrue(AllowancePlan.isRule(AllowancePlan.idFor("h")))
        assertFalse(AllowancePlan.isRule("rec.netflix"))
    }
}
