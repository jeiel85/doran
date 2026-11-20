package com.jeiel85.doran.logic

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.Ledger
import com.jeiel85.doran.data.LedgerState
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
    private const val PREFIX = "rec.allowance."
    fun idFor(memberId: String) = PREFIX + memberId
    /** 이 규칙이 월 용돈 규칙인지(고정지출 화면에서 지출·수입을 바꾸지 못하게). */
    fun isRule(recurringId: String) = recurringId.startsWith(PREFIX)

    /**
     * 예전 버전에서 고정지출 화면으로 수입으로 바뀐 월 용돈 규칙을 지출로 되돌린 것(D-20, 0.2.2). 하루 한 번 저장합니다(`Hooks.daily`).
     * 수입으로 두면 홈에 "+35만"으로 보이고, 정한 날 아내의 용돈 이체 대신 내 '나만 보는 용돈' 입금에 이어집니다.
     * 용돈을 다시 저장해야만 고쳐지던 것을, 이름·금액·멈춤은 건드리지 않고 종류와 카테고리만 바로잡습니다.
     */
    fun repaired(s: LedgerState): List<Recurring> =
        s.recurrings.values.filter { isRule(it.id) && it.kind != Kind.EXPENSE }
            .map { it.copy(kind = Kind.EXPENSE, categoryId = Defaults.ALLOWANCE) }

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
        // 예전 버전에서 고정지출 화면으로 수입으로 바뀐 규칙도 지출로 되돌립니다(가계부에서 나가는 돈).
        )).copy(name = "${m.name} 용돈", kind = Kind.EXPENSE, amount = m.allowance, memberId = m.id, paused = false)
        if (next == cur) return false
        ledger.saveRecurring(next)
        if (cur == null) {
            // 이번 달 용돈을 이미 적어 두었으면(날짜가 달라도) 그 내역을 이 규칙에 이어 한 건만 남깁니다.
            val p = Stats.current(s, today)
            s.txns.values.firstOrNull {
                it.kind == Kind.EXPENSE && it.categoryId == Defaults.ALLOWANCE && it.amount == m.allowance && it.recurringId == null &&
                    !it.private && !it.personal && it.cancelOf == null && it.at in p
            }?.let { ledger.saveTxn(it.copy(recurringId = id, memberId = m.id)) }
        }
        return true
    }
}
