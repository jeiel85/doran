package com.jeiel85.doran.logic

import com.jeiel85.doran.capture.Categorizer
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.Defaults
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Recurring
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import kotlin.math.abs

/**
 * 고정 규칙(Recurring)의 이름과, 알림 내역을 미리 넣어 둔 고정 내역에 맞추는 기준.
 * 규칙은 지출(월세·구독)로도 수입(월급)으로도 만들 수 있어서, 화면·알림 이름을 담긴 내역의 종류에 맞춥니다.
 */
object Fixed {
    /** 한 건: "고정지출" / "고정수입". */
    fun label(kind: Kind): String = if (kind == Kind.INCOME) "고정수입" else "고정지출"

    /** 여러 건을 한 이름으로: 모두 지출(또는 없음)이면 "고정지출", 모두 수입이면 "고정수입", 섞이면 "고정 수입·지출". */
    fun label(kinds: Iterable<Kind>): String {
        val set = kinds.toSet()
        return when {
            Kind.INCOME !in set -> "고정지출"
            Kind.EXPENSE !in set -> "고정수입"
            else -> "고정 수입·지출"
        }
    }

    /** 규칙 목록 화면·설정 줄 이름. 수입 규칙이 없으면 예전 그대로 "고정지출·구독". */
    fun title(rules: Collection<Recurring>): String =
        label(rules.map { it.kind }).let { if (it == "고정지출") "고정지출·구독" else it }

    /** 내역의 '기록' 출처 이름. 고정 규칙이 넣은 수입은 "고정수입". */
    fun source(t: Txn): String = if (t.source == Source.RECURRING) label(t.kind) else t.source.label

    /**
     * 규칙의 종류에 맞는 카테고리. 지금 고른 것이 같은 종류면 그대로, 아니면(지출 → 수입으로 바꾼 경우 등)
     * 이름으로 다시 고르고, 그것도 종류가 다르면 그 종류의 '기타'.
     */
    fun categoryFor(s: LedgerState, name: String, kind: Kind, current: String?): String {
        current?.takeIf { s.category(it)?.kind == kind }?.let { return it }
        val guess = Categorizer.categorize(s, name, kind)?.takeIf { s.category(it)?.kind == kind }
        return guess ?: if (kind == Kind.INCOME) Defaults.INCOME_FALLBACK else Defaults.EXPENSE_FALLBACK
    }

    private const val NEAR = 3L * 86_400_000

    /**
     * 알림으로 읽은 [txn]이 채울, 고정 규칙이 미리 넣어 둔 내역. 지출은 지출에, 수입은 수입에만 맞춥니다
     * (예전에는 종류를 보지 않아, 고정수입과 금액이 같은 카드 결제가 그 수입 내역을 지출로 덮어썼어요).
     * 같은 금액, 3일 안, 미리 넣은 내역에 계좌가 정해져 있으면 같은 계좌. 이체·취소는 맞추지 않습니다.
     * [cancelNotice]: 승인 취소 알림. 원래 승인을 못 찾은 취소는 `cancelOf` 없는 '확인 필요' 수입으로 기록되므로,
     * 그것이 고정수입 자리를 채우지 않게 알림 종류로도 막습니다(#34 리뷰).
     */
    fun planned(txns: Collection<Txn>, txn: Txn, accountId: String?, cancelNotice: Boolean = false): Txn? {
        // 나만 보는 용돈·용돈으로 옮긴 결제는 가계부 고정 내역을 채우지 않습니다: 채우면 가족 가계부의 그 자리가 개인 내역이 됩니다(#36).
        if (cancelNotice || txn.kind == Kind.TRANSFER || txn.cancelOf != null || txn.private || txn.personal) return null
        return nearest(txns.filter {
            it.source == Source.RECURRING && it.kind == txn.kind && amountFits(it, txn) && it.cancelOf == null && !it.private &&
                abs(it.at - txn.at) <= NEAR && (it.accountId == null || it.accountId == accountId)
        }, txn.at)
    }

    /**
     * 고정 내역 자리 [planned](이름 = 규칙 이름)를 알림 [txn]이 채울 만큼 금액이 맞는지: 같은 금액, 또는 가게 이름이 규칙 이름과 맞고
     * 금액이 [AMOUNT_DRIFT] 안(구독료 인상·환율로 달라진 달, #87). 이름이 안 맞으면 금액이 조금만 달라도 다른 결제로 봅니다.
     */
    fun amountFits(planned: Txn, txn: Txn): Boolean =
        planned.amount == txn.amount || nameFits(planned.merchant, txn.merchant) && drift(planned.amount, txn.amount)

    /** 규칙 이름과 가게 이름이 같은 곳인지: 띄어쓰기·대소문자를 빼고 한쪽이 다른 쪽을 품으면(두 글자 이상). */
    fun nameFits(rule: String, merchant: String): Boolean {
        fun norm(x: String) = x.filterNot { it.isWhitespace() }.lowercase()
        val a = norm(rule); val b = norm(merchant)
        if (a.length < 2 || b.length < 2) return false
        return a.contains(b) || b.contains(a)
    }

    const val AMOUNT_DRIFT = 0.3

    fun drift(a: Long, b: Long): Boolean = a > 0 && b > 0 && abs(a - b) <= maxOf(a, b) * AMOUNT_DRIFT

    /**
     * 3일 안 후보 중 날짜가 가장 가까운 것(같으면 id 순 — 두 폰이 같은 것을 고르게). 같은 금액의 구독 둘이 며칠 사이로 있으면
     * 예전에는 맵 순서로 먼저 나온 자리를 채웠어요(#36).
     */
    fun nearest(candidates: Collection<Txn>, at: Long): Txn? =
        candidates.minWithOrNull(compareBy<Txn>({ abs(it.at - at) }, { it.id }))

    /**
     * 알림이 고정 내역 자리를 채울 때의 '누구': 규칙에 정한 사람이 있으면 그 사람입니다. 아내가 보낸 남편 용돈 이체가 '남편 용돈' 자리를
     * 채우면 아내의 지출이 아니라 남편 몫으로 남아야 사람별 지출이 옮겨가지 않습니다(D-20, #36).
     */
    fun memberFor(planned: Txn, captured: Txn): String? = planned.memberId ?: captured.memberId

    /**
     * 예전 버전이 남긴, 종류와 맞지 않는 카테고리를 바로잡은 고정 규칙과 그 규칙이 넣은 내역(#36). 하루 한 번 저장합니다(`Hooks.daily`).
     * 0.2.2 전에는 지출 규칙을 수입으로 바꿔도 카테고리('구독')가 남아, 수입 내역이 지출 카테고리 통계에 섞였습니다.
     * 사람이 고친 내역(출처가 고정이 아닌 것)과 이름·금액은 건드리지 않습니다. 월 용돈 규칙은 [AllowancePlan.repaired]가 맡습니다.
     */
    fun repairs(s: LedgerState): Pair<List<Recurring>, List<Txn>> {
        fun wrong(categoryId: String?, kind: Kind) = categoryId != null && s.category(categoryId)?.kind?.let { it != kind } == true
        val rules = s.recurrings.values.filter { !AllowancePlan.isRule(it.id) && wrong(it.categoryId, it.kind) }
            .map { it.copy(categoryId = categoryFor(s, it.name, it.kind, it.categoryId)) }
        val fixedRules = rules.associateBy { it.id }
        val txns = s.txns.values.filter { it.source == Source.RECURRING && it.kind != Kind.TRANSFER && it.cancelOf == null }
            .mapNotNull { t ->
                when {
                    // 수입으로 뒤집혔던 월 용돈 규칙이 넣은 내역: 용돈은 늘 가계부에서 나가는 돈입니다(D-20).
                    t.recurringId?.let(AllowancePlan::isRule) == true && t.kind != Kind.EXPENSE ->
                        t.copy(kind = Kind.EXPENSE, categoryId = Defaults.ALLOWANCE)
                    wrong(t.categoryId, t.kind) ->
                        t.copy(categoryId = fixedRules[t.recurringId]?.categoryId?.takeIf { !wrong(it, t.kind) }
                            ?: categoryFor(s, t.merchant, t.kind, t.categoryId))
                    else -> null
                }
            }
        return rules to txns
    }
}
