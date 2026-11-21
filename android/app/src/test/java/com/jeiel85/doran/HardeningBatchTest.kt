package com.jeiel85.doran

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Period
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.logic.Twins
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.AllowanceSummary
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** 열린 하드닝 이슈 묶음 정리(2026-11-21, #64·#67): 두 폰 같은 결제 뒤 남은 빈 계좌, 달력 한 번 읽기. */
class HardeningBatchTest {
    private fun ms(d: Int, h: Int = 12) = Fmt.millis(LocalDateTime.of(2026, 11, d, h, 0))

    // --- 같은 결제라 지운 내 기록이 남긴 빈 계좌 (#64) --------------------------------------------------------

    private val autoCard = Account("acc.new", "현대카드", AccountType.CARD, ownerId = "w", institution = "현대카드", cycleStartDay = 1)
    private val mine = Txn("t.w", Kind.EXPENSE, 70_000, ms(21, 9), accountId = autoCard.id, merchant = "", memberId = "w",
        source = Source.SMS, issuer = "현대카드", cardLabel = "현대카드", createdBy = "phone.w")

    @Test fun anAccountMadeOnlyForTheRemovedTwinIsLeftOver() {
        // 내 기록을 지운 뒤의 장부: 계좌만 남음
        val s = LedgerState(accounts = mapOf(autoCard.id to autoCard))
        assertEquals(autoCard.id, Twins.leftoverAccount(s, mine)?.id)
        // 끝자리가 붙은 자동 이름도
        val withLast4 = autoCard.copy(name = "현대카드 1234", last4 = "1234")
        assertEquals(withLast4.id, Twins.leftoverAccount(LedgerState(accounts = mapOf(withLast4.id to withLast4)), mine)?.id)
    }

    @Test fun accountsInUseOrTouchedByPeopleStay() {
        val other = Txn("t.other", Kind.EXPENSE, 5_000, ms(20), accountId = autoCard.id, source = Source.PUSH)
        assertNull(Twins.leftoverAccount(LedgerState(accounts = mapOf(autoCard.id to autoCard), txns = mapOf(other.id to other)), mine))
        // 카드값 이체가 가리키는 카드도 쓰는 중
        val bill = Txn("t.bill", Kind.TRANSFER, 5_000, ms(20), accountId = "acc.bank", toAccountId = autoCard.id, source = Source.SMS)
        assertNull(Twins.leftoverAccount(LedgerState(accounts = mapOf(autoCard.id to autoCard), txns = mapOf(bill.id to bill)), mine))
        // 사람이 이름·결제일·잔액을 손댄 계좌, 직접 만든 계좌(기준 시각이 있음)
        listOf(
            autoCard.copy(name = "가계부 카드"),
            autoCard.copy(billingDay = 14),
            autoCard.copy(openingAt = ms(1), opening = 0),
            autoCard.copy(anchor = 100_000, anchorAt = ms(20)),
            autoCard.copy(type = AccountType.CASH),
        ).forEach { a -> assertNull(a.toString(), Twins.leftoverAccount(LedgerState(accounts = mapOf(a.id to a)), mine)) }
        // 계좌 없는 기록
        assertNull(Twins.leftoverAccount(LedgerState(accounts = mapOf(autoCard.id to autoCard)), mine.copy(accountId = null)))
    }

    // --- 내역 탭: 앞뒤 달까지 한 번 읽고 그 달 합계는 거르기 (#67) --------------------------------------------

    @Test fun calendarReadsOnceAndKeepsFamilyAllowanceOnTheFirst() {
        // 2026년 12월 1일은 화요일이라 달력이 11/29(일)부터
        val ym = YearMonth.of(2026, 12)
        val range = Period(ym, ym.atDay(1), ym.plusMonths(1).atDay(1))
        val grid = Stats.calendarRange(ym).let { (a, b) -> Period(ym, a, b) }
        assertEquals(LocalDate.of(2026, 11, 29), grid.start)
        val before = Txn("a", Kind.EXPENSE, 1_000, ms(30))
        val inside = Txn("b", Kind.EXPENSE, 2_000, Fmt.millis(LocalDate.of(2026, 12, 15).atTime(12, 0)))
        val s = LedgerState(
            members = mapOf("h" to Member("h", "남편"), "w" to Member("w", "아내")),
            txns = listOf(before, inside).associateBy { it.id },
            allowances = mapOf("al" to AllowanceSummary("al", "h", range.key, 30_000, 3)),
        )
        val wide = Stats.entries(s, grid, "w", summaryAt = range.startMs)
        // 앞 달 11/30 내역도 칸에 보임
        assertEquals(setOf("a", "b", "al"), wide.map { it.txn.id }.toSet())
        // 그 달 합계는 넓게 읽은 것에서 거른 것과 따로 읽은 것이 같음 — 남편 용돈 합계 줄은 12/1에(전에는 달력 첫 칸 11/29에 찍혔음)
        val month = wide.filter { it.txn.at in range }
        assertEquals(Stats.entries(s, range, "w").map { it.txn.id to it.txn.at }.toSet(), month.map { it.txn.id to it.txn.at }.toSet())
        assertEquals(range.startMs, wide.single { it.txn.id == "al" }.txn.at)
    }
}
