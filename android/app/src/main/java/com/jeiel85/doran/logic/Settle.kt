package com.jeiel85.doran.logic

/**
 * 공동 지출 분담·정산(D-39, 머니차곡·우리알지): 사람별로 쓴 돈을 똑같이 나누면 누가 누구에게 얼마를 주면 되는지.
 * 가족 카드("가족 공용")·누구인지 모르는 공동 내역은 이미 함께 낸 돈이라 나누지 않습니다. 원 단위 나머지는 앞사람이 냅니다.
 * 참고용 계산이라 장부에는 아무것도 쓰지 않습니다.
 */
object Settle {
    data class Transfer(val from: String, val to: String, val amount: Long)

    /** [paid]: 사람 id → 그 사람이 낸 돈. 두 사람 이상일 때만 의미가 있습니다. */
    fun even(paid: Map<String, Long>): List<Transfer> {
        if (paid.size < 2) return emptyList()
        val total = paid.values.sum()
        val n = paid.size
        val share = total / n
        var extra = total - share * n // 나머지 몇 원
        // 각자 내야 할 몫(나머지는 많이 낸 사람부터 1원씩 더 냄 — 줄 돈이 생기지 않게)
        val ordered = paid.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        val due = ordered.associate { e -> e.key to share + if (extra > 0) { extra--; 1L } else 0L }
        val balance = paid.mapValues { (k, v) -> v - due.getValue(k) } // +면 받을 돈, -면 줄 돈
        val creditors = balance.filter { it.value > 0 }.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }.toMutableList()
        val debtors = balance.filter { it.value < 0 }.entries.sortedWith(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .map { it.key to -it.value }.toMutableList()
        val out = ArrayList<Transfer>()
        var i = 0; var j = 0
        while (i < debtors.size && j < creditors.size) {
            val (d, owe) = debtors[i]; val (c, get) = creditors[j]
            val x = minOf(owe, get)
            if (x > 0) out += Transfer(d, c, x)
            debtors[i] = d to owe - x; creditors[j] = c to get - x
            if (debtors[i].second == 0L) i++
            if (creditors[j].second == 0L) j++
        }
        return out
    }
}
