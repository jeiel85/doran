package com.jeiel85.doran.logic

import com.jeiel85.doran.core.Fmt

/**
 * 금액 자판의 더하기·빼기 계산(비주얼 가계부 계산기). "12000 + 3500 − 500"처럼 이어 쓰면 [total]이 바로 맞춰지고,
 * 저장할 때는 [total]을 씁니다. 곱하기·나누기는 가계부 금액에 거의 쓰지 않아 두지 않았습니다.
 *
 * [base]·[op]은 앞에서 끝난 계산과 기다리는 연산자, [cur]는 지금 치고 있는 숫자(숫자만)입니다.
 */
data class AmountCalc(val base: Long? = null, val op: Char? = null, val cur: String = "") {
    val total: Long
        get() {
            val c = cur.toLongOrNull() ?: 0L
            return when (op) {
                PLUS -> (base ?: 0L) + c
                MINUS -> (base ?: 0L) - c
                else -> c
            }
        }

    /** 계산 중일 때 위에 작게 보일 식("12,000 + 3,500"). 계산 중이 아니면 null. */
    val expression: String?
        get() = op?.let { o -> "${Fmt.num(base ?: 0L)} $o${cur.toLongOrNull()?.let { " " + Fmt.num(it) } ?: ""}" }

    fun digit(d: String): AmountCalc = if (cur.length + d.length > MAX_DIGITS) this else copy(cur = (cur + d).trimStart('0'))

    fun back(): AmountCalc = when {
        cur.isNotEmpty() -> copy(cur = cur.dropLast(1))
        // 연산자만 눌렀으면 그것을 지워 앞 숫자로 돌아갑니다.
        op != null -> AmountCalc(cur = (base ?: 0L).takeIf { it > 0 }?.toString() ?: "")
        else -> this
    }

    /** 연산자를 누름: 앞 계산을 끝내고 다음 숫자를 기다립니다. 숫자 없이 연산자만 바꾸면 바꾸기만 합니다. */
    fun operator(o: Char): AmountCalc = if (op != null && cur.isEmpty()) copy(op = o) else AmountCalc(base = total, op = o)

    /** '=' 또는 자판 닫기·저장: 결과 하나만 남깁니다. 0 아래로 내려가면 0. */
    fun equals(): AmountCalc = AmountCalc(cur = total.coerceIn(0, MAX).takeIf { it > 0 }?.toString() ?: "")

    /** +1만 같은 빠른 더하기: 지금 치는 숫자에 더합니다. */
    fun add(n: Long): AmountCalc = copy(cur = ((cur.toLongOrNull() ?: 0L) + n).coerceAtMost(MAX).toString())

    companion object {
        const val PLUS = '+'
        const val MINUS = '−'
        const val MAX_DIGITS = 11
        const val MAX = 99_999_999_999L
    }
}
