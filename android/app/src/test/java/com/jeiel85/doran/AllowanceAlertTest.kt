package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.AllowanceAlert
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 내 용돈 알림: 남편 폰이 읽은 토스뱅크 체크카드 결제는 '나만 보는 용돈'으로 기록되고, 남편 폰에 남은 용돈과 함께 알립니다.
 * 같은 통장으로 들어온 입금(내 다른 통장에서 옮긴 돈)은 쓴 돈이 아닙니다(2026-11-19 사용자 폰 기록에서 본 형식).
 */
class AllowanceAlertTest {
    private fun ms(d: Int, h: Int = 9, m: Int = 0) = Fmt.millis(LocalDateTime.of(2026, 11, d, h, m))
    private val p = Stats.period(YearMonth.of(2026, 11), 1)
    private val install = "install-h"

    private val husband = Member("h", "남편", allowance = 350_000)
    private val wife = Member("w", "아내")

    private fun pocket(id: String, amount: Long, at: Long, merchant: String, kind: Kind = Kind.EXPENSE, cancelOf: String? = null,
                       source: Source = Source.PUSH, member: String = "h", by: String = install, private: Boolean = true) =
        Txn(id, kind, amount, at, merchant = merchant, memberId = member, source = source, issuer = "토스뱅크", private = private,
            cancelOf = cancelOf, createdBy = by)

    private val coffee = pocket("t1", 3_400, ms(19, 8, 18), "메가MGC")
    private val subscription = pocket("t2", 990, ms(19, 9, 0), "카카오구독료_카카오페이")
    /** 내 다른 통장에서 토스뱅크로 옮긴 돈: 용돈을 쓴 것이 아님. */
    private val moveIn = pocket("t3", 6_705, ms(19, 7, 51), "홍길동 → 내 토스뱅크 통장", kind = Kind.INCOME)

    private fun ledger(vararg txns: Txn, members: List<Member> = listOf(husband, wife)) =
        LedgerState(members = members.associateBy { it.id }, txns = txns.associateBy { it.id })

    @Test fun depositIntoAllowanceAccountIsNotSpending() {
        val s = ledger(coffee, subscription, moveIn)
        assertEquals(4_390L, Stats.allowanceSpent(s, "h", p))
        assertEquals(listOf("t1", "t2"), Stats.allowanceTxns(s, "h", p).map { it.id }.sorted())
    }

    @Test fun spendingShowsRemainingAllowance() {
        val s = ledger(coffee, subscription, moveIn)
        val a = AllowanceAlert.of(s, subscription)
        assertEquals("용돈에서 990원 썼어요", a.title)
        assertEquals("카카오구독료_카카오페이 · 토스뱅크 · 남은 용돈 345,610원", a.body)
    }

    @Test fun cancelGivesBackAndOverspendIsSaid() {
        val big = pocket("t4", 400_000, ms(10, 20), "가전마트")
        val s = ledger(coffee, big)
        assertEquals("가전마트 · 토스뱅크 · 용돈을 53,400원 넘었어요", AllowanceAlert.of(s, big).body)

        val cancel = pocket("t5", 400_000, ms(11, 10), "가전마트", cancelOf = big.id)
        val after = ledger(coffee, big, cancel)
        val a = AllowanceAlert.of(after, cancel)
        assertEquals("용돈 결제 400,000원이 취소됐어요", a.title)
        assertEquals("가전마트 · 토스뱅크 · 남은 용돈 346,600원", a.body)
    }

    @Test fun withoutMonthlyAllowanceShowsMonthTotal() {
        val s = ledger(coffee, subscription, members = listOf(husband.copy(allowance = 0), wife))
        assertEquals("카카오구독료_카카오페이 · 토스뱅크 · 이번 달 용돈 4,390원 썼어요", AllowanceAlert.of(s, subscription).body)
    }

    @Test fun onlyMyNewCapturedAllowancePaymentsNotify() {
        val now = ms(19, 9, 1)
        assertTrue(AllowanceAlert.applies(subscription, "h", install, now))
        // 가계부로 올라간 결제(가족 카드 등)는 가족 폰이 알리므로 내 폰에는 띄우지 않음
        assertFalse(AllowanceAlert.applies(subscription.copy(private = false), "h", install, now))
        // 입금, 직접 쓴 내역, 다른 사람·다른 폰이 만든 것, 하루 지난 것은 알리지 않음
        assertFalse(AllowanceAlert.applies(moveIn, "h", install, now))
        assertFalse(AllowanceAlert.applies(subscription.copy(source = Source.MANUAL), "h", install, now))
        assertFalse(AllowanceAlert.applies(subscription.copy(memberId = "w"), "h", install, now))
        assertFalse(AllowanceAlert.applies(subscription.copy(createdBy = "install-w"), "h", install, now))
        assertFalse(AllowanceAlert.applies(subscription, "h", install, now + 2 * 86_400_000L))
    }
}
