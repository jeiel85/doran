package com.jeiel85.doran.logic

import com.jeiel85.doran.capture.Categorizer
import com.jeiel85.doran.capture.Issuers
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import kotlin.math.abs

/**
 * 공유 범위 밖 통장의 은행 출금(나만 보는 용돈)이 용돈에서 '쓴 돈'인지, 다른 통장으로 '옮긴 돈'인지 가립니다(DECISIONS.md D-26).
 * 알림 문장에는 돈이 어디로 갔는지가 없어서("급여 스마트폰출금 4,044,525") 문장 밖의 세 근거를 이 순서로 봅니다.
 *  1. 사용자가 기억시킨 규칙(은행 + 받는 분·메모 칸)
 *  2. 받는 쪽 기록: 같은 금액이 앞뒤 [WINDOW] 안에 다른 통장의 은행 입금으로 기록돼 있으면 옮긴 돈
 *  3. 금액 크기: 월 용돈이 정해진 사람의 출금이 월 용돈보다 크면 옮긴 돈(확인 필요)
 * 옮긴 돈은 계좌 없는 '이체'로 남겨 남은 용돈·가족에게 가는 용돈 합계에서 빠집니다(`Stats.allowanceTxns`는 지출만 셈).
 * 순수 함수라 단위 시험(MovesTest)으로 경우를 고정합니다.
 */
object Moves {
    const val WINDOW = 10 * 60_000L

    /** 쓴 돈: 자동 판정. 받는 쪽 입금이 뒤늦게 동기화되면 옮긴 돈으로 바뀔 수 있습니다. */
    const val AUTO = "auto"
    /** 쓴 돈: 사용자가 "용돈으로 쓴 돈이에요"를 누름. 다시 판정하지 않습니다. */
    const val KEPT = "kept"
    /** 쓴 돈: 기억한 규칙. */
    const val RULE_KEPT = "rule-kept"
    /** 옮긴 돈: 월 용돈보다 큼(확인 필요). */
    const val LARGE = "large"
    /** 옮긴 돈: 기억한 규칙. */
    const val RULE = "rule"
    /** 옮긴 돈: 사용자가 "옮긴 돈이에요"를 누름. */
    const val USER = "user"
    /** 옮긴 돈: 짝이 된 입금 id가 뒤에 붙습니다. */
    private const val PAIR = "pair:"

    fun pairOf(t: Txn): String? = t.move?.takeIf { it.startsWith(PAIR) }?.removePrefix(PAIR)

    /** 이 판정으로 옮긴 돈이 된 출금인지. */
    fun isMoved(t: Txn): Boolean = t.kind == Kind.TRANSFER && t.move != null

    /** 앱이 스스로 정한 판정이라 받는 쪽 기록이 바뀌면 다시 판정해도 되는지(사용자 선택·규칙은 건드리지 않음). */
    fun automatic(move: String?): Boolean = move == AUTO || move == LARGE || move?.startsWith(PAIR) == true

    /** 받는 쪽 기록이 될 수 있는 입금: 알림·문자로 기록된 은행 입금. 원래 승인을 못 찾은 카드 취소(확인 필요 수입)는 아닙니다. */
    fun isDeposit(t: Txn): Boolean =
        t.kind == Kind.INCOME && t.cancelOf == null && !t.needsReview && !t.personal && t.amount > 0 &&
            (t.source == Source.PUSH || t.source == Source.SMS) && t.issuer != null && Issuers.cards.none { it.name == t.issuer }

    /**
     * 출금 [w]와 같은 돈이 들어온 입금: 같은 금액, 앞뒤 [WINDOW] 안, 아직 다른 출금과 짝지어지지 않은 것 중 시각이 가장 가까운 것.
     * 같은 은행의 내 용돈 입금은 같은 통장일 수 있어 보지 않습니다: 급여가 들어온 뒤 몇 분 안에 통째로 나간 경우 그 급여 입금이
     * '받는 쪽'으로 잘못 보이기 때문입니다.
     */
    fun counterpart(s: LedgerState, w: Txn): Txn? {
        val taken = s.txns.values.mapNotNullTo(HashSet()) { o -> if (o.id == w.id) null else pairOf(o) }
        return s.txns.values.asSequence()
            .filter { d -> d.id != w.id && d.id !in taken && isDeposit(d) && d.amount == w.amount && abs(d.at - w.at) <= WINDOW }
            .filterNot { d -> d.private && d.issuer == w.issuer }
            .minByOrNull { abs(it.at - w.at) }
    }

    /**
     * 은행 출금 [w]의 판정. [allowance]: 그 사람의 월 용돈(0이면 금액 기준을 쓰지 않음). [rule]: 기억한 규칙(옮긴 돈 true, 쓴 돈 false).
     * 돌려주는 거래는 종류·카테고리·확인 표시만 바꾼 [w]입니다.
     */
    fun judge(s: LedgerState, w: Txn, allowance: Long, rule: Boolean?): Txn = when {
        rule == true -> moved(w, RULE)
        rule == false -> spent(s, w, RULE_KEPT)
        else -> counterpart(s, w)?.let { moved(w, PAIR + it.id) }
            ?: if (allowance in 1 until w.amount) moved(w, LARGE, review = true) else spent(s, w, AUTO)
    }

    fun moved(w: Txn, move: String, review: Boolean = false): Txn =
        w.copy(kind = Kind.TRANSFER, move = move, categoryId = null, needsReview = review)

    fun spent(s: LedgerState, w: Txn, move: String): Txn =
        w.copy(kind = Kind.EXPENSE, move = move, needsReview = false,
            categoryId = w.categoryId.takeIf { w.kind == Kind.EXPENSE } ?: Categorizer.categorize(s, w.merchant, Kind.EXPENSE))

    /** 이 폰이 판정한 내 은행 출금(나만 보는 용돈)인지. */
    fun mine(t: Txn, me: String, installId: String): Boolean =
        t.private && t.memberId == me && t.createdBy == installId && t.move != null && t.cancelOf == null

    /**
     * 장부가 바뀐 뒤 다시 판정할 내 출금들. [changed]: 바뀐 거래의 (전, 후). 지운 것은 후가 null.
     * - 새로 생기거나 바뀐 입금과 짝이 될 수 있는, 아직 짝 없는 자동 판정 출금
     * - 짝이던 입금이 지워지거나 바뀐 출금(다른 입금과 다시 짝짓거나 금액 기준으로 돌아감)
     */
    fun affected(s: LedgerState, changed: List<Pair<Txn?, Txn?>>, me: String, installId: String): List<Txn> {
        if (changed.isEmpty()) return emptyList()
        val ids = changed.mapNotNullTo(HashSet()) { (b, a) -> (a ?: b)?.id }
        val deposits = changed.mapNotNull { it.second }.filter(::isDeposit)
        return s.txns.values.filter { w ->
            if (!mine(w, me, installId) || !automatic(w.move) || w.id in ids) return@filter false
            val pair = pairOf(w)
            if (pair != null) pair in ids
            else deposits.any { d -> d.amount == w.amount && abs(d.at - w.at) <= WINDOW }
        }
    }

    /** 내역 화면에 보일 판정 이유. [me]: 이 폰의 구성원. */
    fun reason(s: LedgerState, t: Txn, me: String?): String? {
        val pair = pairOf(t)
        return when {
            pair != null -> s.txns[pair]?.let { d ->
                val who = if (d.memberId == me) "내" else s.member(d.memberId)?.name ?: "가족"
                "$who ${d.issuer ?: "통장"} 입금(${Fmt.time(d.at)})과 같은 돈이라 옮긴 돈으로 봤어요"
            } ?: "같은 금액의 입금과 짝지어 옮긴 돈으로 봤어요"
            t.move == LARGE -> "월 용돈${s.member(t.memberId)?.allowance?.takeIf { it > 0 }?.let { "(${Fmt.won(it)})" } ?: ""}보다 큰 출금이라 옮긴 돈으로 봤어요"
            t.move == RULE -> "기억해 둔 규칙으로 옮긴 돈으로 봤어요"
            t.move == USER -> "옮긴 돈으로 바꿨어요"
            t.move == AUTO -> "용돈에서 쓴 돈으로 셌어요"
            t.move == KEPT -> "용돈에서 쓴 돈으로 바꿨어요"
            t.move == RULE_KEPT -> "기억해 둔 규칙으로 용돈에서 쓴 돈으로 셌어요"
            else -> null
        }
    }

    /** 기억할 규칙의 열쇠: 은행 + 받는 분·메모 칸. 메모가 비면 기억하지 않습니다. */
    fun ruleKey(issuer: String?, merchant: String): String? {
        val memo = Categorizer.normalize(merchant)
        if (issuer.isNullOrBlank() || memo.isEmpty()) return null
        return Issuers.canonical(issuer) + "|" + memo
    }
}
