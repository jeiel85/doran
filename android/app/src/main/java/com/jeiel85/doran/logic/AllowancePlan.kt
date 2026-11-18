package com.jeiel85.doran.logic

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.Ledger
import com.jeiel85.doran.model.Defaults
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.Recurring
import com.jeiel85.doran.model.T
import java.time.LocalDate

/**
 * 월 용돈을 가계부 지출에 한 번만 넣습니다 (DECISIONS.md D-20).
 * 용돈이 정해진 사람은 매달 "○○ 용돈" 고정지출이 생기고, 그 사람이 실제로 쓴 용돈 내역·월 합계는 가계부 합계에 더하지 않습니다(Stats.entries).
 * 규칙 id가 사람마다 고정이라 두 폰이 각각 저장해도 한 규칙입니다.
 */
object AllowancePlan {
    fun idFor(memberId: String) = "rec.allowance.$memberId"

    /** 용돈 규칙이 바뀌었으면 true(그때 고정지출을 바로 기록해 이번 달 몫이 보이게). */
    fun sync(ledger: Ledger, m: Member, today: LocalDate = LocalDate.now(Fmt.zone)): Boolean {
        val s = ledger.value
        val id = idFor(m.id)
        val cur = s.recurrings[id]
        if (m.allowance <= 0 || m.isSender || m.removed) {
            if (cur != null) ledger.delete(T.RECURRING, id)
            return cur != null
        }
        val start = Stats.current(s, today).start
        val next = (cur ?: Recurring(
            id = id, name = "", kind = Kind.EXPENSE, amount = 0, categoryId = Defaults.ALLOWANCE, accountId = null,
            memberId = m.id, period = "m", day = s.household.monthStartDay.coerceIn(1, 28), startAt = Fmt.millis(start),
        )).copy(name = "${m.name} 용돈", amount = m.allowance, memberId = m.id, paused = false)
        if (next == cur) return false
        ledger.saveRecurring(next)
        return true
    }
}
