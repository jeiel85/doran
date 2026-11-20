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
        if (cancelNotice || txn.kind == Kind.TRANSFER || txn.cancelOf != null) return null
        return txns.firstOrNull {
            it.source == Source.RECURRING && it.kind == txn.kind && it.amount == txn.amount && it.cancelOf == null &&
                abs(it.at - txn.at) <= NEAR && (it.accountId == null || it.accountId == accountId)
        }
    }
}
