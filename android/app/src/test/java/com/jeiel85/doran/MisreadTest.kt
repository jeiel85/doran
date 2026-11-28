package com.jeiel85.doran

import com.jeiel85.doran.capture.Parser
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.logic.Misread
import com.jeiel85.doran.logic.Moves
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** #106: 들어온 돈을 나간 돈으로 읽어 남긴 예전 기록을 알림 원문으로 다시 읽어 바로잡기. */
class MisreadTest {
    private val now = LocalDateTime.of(2026, 11, 28, 12, 0)
    private val install = "i1"
    private val at = 1_000_000L

    private fun spent(id: String = "t1", move: String? = Moves.AUTO, kind: Kind = Kind.EXPENSE) =
        Txn(id, kind, 20, at, merchant = "", memberId = "h", source = Source.PUSH, issuer = "카카오뱅크", private = true,
            createdBy = install, move = move, categoryId = "c.etc")

    private val interest = Parser.parse("오늘은 정기이자 받는 날!\n입출금통장(0932)에 20원이 입금되었어요.", now, hint = "카카오뱅크")
    private val withdrawal = Parser.parse("[카카오뱅크] 입출금통장(1234) 출금 20원 홍길순", now)

    @Test fun onlyMyCapturedSpendingOnThisPhoneIsLookedAt() {
        assertTrue(Misread.candidate(spent(), "h", install, since = 0))
        assertTrue(Misread.candidate(Moves.moved(spent(), Moves.AUTO), "h", install, since = 0))
        assertFalse(Misread.candidate(spent().copy(memberId = "w"), "h", install, since = 0))
        assertFalse(Misread.candidate(spent().copy(createdBy = "other"), "h", install, since = 0))
        assertFalse(Misread.candidate(spent().copy(source = Source.MANUAL), "h", install, since = 0))
        assertFalse(Misread.candidate(spent().copy(cancelOf = "x"), "h", install, since = 0))
        assertFalse(Misread.candidate(spent().copy(recurringId = "r"), "h", install, since = 0))
        assertFalse(Misread.candidate(spent(kind = Kind.INCOME, move = null), "h", install, since = 0))
        // 원문이 지워졌을 만큼 오래된 것은 보지 않습니다
        assertFalse(Misread.candidate(spent(), "h", install, since = at + 1))
    }

    @Test fun asksOnlyWhenEveryNoticeNowReadsAsBankDeposit() {
        assertTrue(Misread.allDeposits(listOf(interest)))
        assertFalse(Misread.allDeposits(emptyList()))
        assertFalse(Misread.allDeposits(listOf(interest, null)))
        assertFalse(Misread.allDeposits(listOf(interest, withdrawal)))
        assertFalse(Misread.allDeposits(listOf(withdrawal)))
    }

    @Test fun fixedRecordIsIncomeWithoutMoveMark() {
        val fixed = Misread.asDeposit(LedgerState(), Moves.moved(spent(), Moves.LARGE, review = true))
        assertEquals(Kind.INCOME, fixed.kind)
        assertNull(fixed.move)
        assertFalse(fixed.needsReview)
        assertEquals(20L, fixed.amount)
    }
}
