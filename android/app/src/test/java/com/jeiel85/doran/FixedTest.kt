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

    @Test fun allowanceRuleIdsAreRecognised() {
        assertTrue(AllowancePlan.isRule(AllowancePlan.idFor("h")))
        assertFalse(AllowancePlan.isRule("rec.netflix"))
    }
}
