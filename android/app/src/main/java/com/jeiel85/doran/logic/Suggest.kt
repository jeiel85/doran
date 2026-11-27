package com.jeiel85.doran.logic

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Txn
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/**
 * 정기결제 자동 제안(BACKLOG, D-35 다음): 한두 달 써 보면 구독·자동이체가 보이므로, 같은 가게에서 비슷한 금액이 비슷한 날에
 * 두 달 넘게 이어 나간 것을 "정기결제로 등록할까요?"로 묻습니다. 등록은 사람이 고릅니다(내역 → 고정 규칙, `Route.RecurringEdit(fromTxn)`).
 */
object Suggest {
    data class Recurring(val merchant: String, val amount: Long, val day: Int, val latest: Txn, val months: Int)

    /** 금액이 이만큼 안에서만 같은 정기결제로 봅니다(구독료가 조금 오른 달 포함). */
    const val DRIFT = 0.1
    /** 날짜가 이만큼 안에서 어긋나도(주말·영업일) 같은 날로 봅니다. */
    const val DAY_SLACK = 3

    fun key(merchant: String) = merchant.filterNot { it.isWhitespace() }.lowercase()

    /**
     * 최근 [months]달(오늘이 든 달 포함)에서 제안할 것. 이미 고정 규칙이 있는 가게([Fixed.nameFits])와 [dismissed](열쇠 [key]),
     * 나만 보는 용돈·이체·취소·규칙에 이어진 내역은 뺍니다. 가장 최근 것부터.
     */
    fun find(s: LedgerState, today: LocalDate, dismissed: Set<String>, months: Int = 3): List<Recurring> {
        val from = YearMonth.from(today).minusMonths((months - 1).toLong()).atDay(1)
        val candidates = s.txns.values.filter { t ->
            t.kind == Kind.EXPENSE && t.cancelOf == null && !t.private && !t.personal && t.recurringId == null &&
                t.amount > 0 && t.merchant.isNotBlank() && Fmt.date(t.at) >= from && Fmt.date(t.at) <= today
        }
        val out = ArrayList<Recurring>()
        for ((k, list) in candidates.groupBy { key(it.merchant) }) {
            if (k.length < 2 || k in dismissed) continue
            if (s.recurrings.values.any { Fixed.nameFits(it.name, list.first().merchant) }) continue
            // 달마다 하나(그달 가장 늦은 것)만 보고, 이어지는 달끼리 금액·날짜가 맞는지 봅니다.
            val byMonth = list.groupBy { YearMonth.from(Fmt.date(it.at)) }.mapValues { (_, v) -> v.maxBy { it.at } }.toSortedMap()
            if (byMonth.size < 2) continue
            val runs = byMonth.entries.toList()
            var streak = 1
            var exact = true
            for (i in 1 until runs.size) {
                val (ym0, a) = runs[i - 1]; val (ym1, b) = runs[i]
                val next = ym0.plusMonths(1) == ym1
                val close = abs(a.amount - b.amount) <= maxOf(a.amount, b.amount) * DRIFT
                val dayA = Fmt.date(a.at).dayOfMonth; val dayB = Fmt.date(b.at).dayOfMonth
                val sameDay = abs(dayA - dayB) <= DAY_SLACK || abs(dayA - dayB) >= 28 - DAY_SLACK // 말일 근처는 달 길이로 어긋남
                if (next && close && sameDay) { streak++; exact = exact && a.amount == b.amount }
                else { streak = 1; exact = true }
            }
            // 두 달뿐이면 금액이 원 단위까지 같아야(구독·자동이체), 금액이 조금씩 다르면 세 달 넘게 이어져야 제안합니다(#93 — 매달 비슷한
            // 날 비슷한 금액이 나가는 주유·장보기까지 묻지 않게).
            if (streak < 2 || streak == 2 && !exact) continue
            val latest = runs.last().value
            out += Recurring(latest.merchant, latest.amount, Fmt.date(latest.at).dayOfMonth, latest, streak)
        }
        return out.sortedByDescending { it.latest.at }
    }
}
