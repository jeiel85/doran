package com.jeiel85.doran.logic

import com.jeiel85.doran.capture.Categorizer
import com.jeiel85.doran.capture.Issuers
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.AccountType
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
    /** 옮긴 돈: 가족 돈(아래 [fundOf]) 입금과 같은 금액이라 그 돈에서 나간 것(D-28). 그 입금 id가 뒤에 붙습니다. */
    private const val FROM = "from:"

    fun pairOf(t: Txn): String? = t.move?.takeIf { it.startsWith(PAIR) }?.removePrefix(PAIR)
    fun fromOf(t: Txn): String? = t.move?.takeIf { it.startsWith(FROM) }?.removePrefix(FROM)

    /** 이 판정으로 옮긴 돈이 된 출금인지. */
    fun isMoved(t: Txn): Boolean = t.kind == Kind.TRANSFER && t.move != null

    /** 앱이 스스로 정한 판정이라 받는 쪽 기록이 바뀌면 다시 판정해도 되는지(사용자 선택·규칙은 건드리지 않음). */
    fun automatic(move: String?): Boolean =
        move == AUTO || move == LARGE || move?.startsWith(PAIR) == true || move?.startsWith(FROM) == true

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
            .filter { d -> d.id != w.id && d.id !in taken && isDeposit(d) && linkOf(d) == null && d.amount == w.amount && abs(d.at - w.at) <= WINDOW }
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
            ?: fundFor(s, w)?.let { moved(w, FROM + it.id) }
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
        // 자동 판정 출금 자체의 금액·시각을 손으로 고치면 짝·금액 기준이 달라지므로 다시 봅니다(#49 리뷰). 판정만 바꾼 저장
        // (종류·이유·카테고리)은 여기 걸리지 않아 다시 판정이 되풀이되지 않고, 새로 기록된 것은 기록할 때 이미 판정했습니다.
        val edited = changed.mapNotNullTo(HashSet()) { (b, a) -> a?.id?.takeIf { b != null && (b.amount != a.amount || b.at != a.at) } }
        // 짝이던 출금이 지워지거나 다른 판정이 되면 그 입금이 풀립니다. 같은 입금을 두고 밀렸던 출금이 다시 짝을 찾게 풀린 입금도 봅니다(#49 리뷰).
        fun ref(t: Txn?) = t?.let { pairOf(it) ?: fromOf(it) }
        val released = changed.mapNotNull { (b, a) -> ref(b)?.takeIf { it != ref(a) } }.mapNotNull { s.txns[it] }
        val touched = changed.mapNotNull { it.second } + released
        val deposits = touched.filter(::isDeposit)
        // 가족 돈으로 표시된(또는 다시 풀린) 입금: 그 뒤 [FUND_DAYS]일 안의 같은 통장·같은 금액 출금을 다시 봅니다(D-28).
        val funds = touched.filter { fundOf(it) != null }
        return s.txns.values.filter { w ->
            if (!mine(w, me, installId) || !automatic(w.move)) return@filter false
            if (w.id in ids) return@filter w.id in edited
            val r = ref(w)
            if (r != null) r in ids
            else deposits.any { d -> d.amount == w.amount && abs(d.at - w.at) <= WINDOW } || funds.any { d -> fundFits(d, w) }
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
            fromOf(t) != null -> s.txns[fromOf(t)]?.let { d ->
                val src = fundOf(d)?.let { s.txns[it] }
                val who = src?.let { if (it.memberId == me) "내" else s.member(it.memberId)?.name } ?: "가족"
                "$who 통장에서 보낸 가족 돈(${Fmt.date(d.at).let { "${it.monthValue}/${it.dayOfMonth}" }} ${Fmt.won(d.amount)})으로 나간 돈이라 용돈에서 뺐어요"
            } ?: "가족 돈과 같은 금액이라 용돈에서 뺐어요"
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

    // --- 가족 돈: 가족이 가계부 통장에서 내 범위 밖 통장으로 보낸 돈 (D-28 C) ---------------------------

    /** 나만 보는 입금: 가족이 가계부 통장에서 보낸 돈. 보낸 출금 id가 뒤에 붙습니다. */
    private const val FUND = "fund:"
    /** 가족 돈으로 나간 출금을 찾는 기간: 입금 뒤 한 달 남짓(관리비·대출은 받고 며칠~몇 주 뒤에 빠져나감). */
    const val FUND_DAYS = 35L
    /** 나만 보는 입금이든 공유 내역이든: 사용자가 "가족 돈 아니에요"·"따로 세기"를 눌러 다시 짝짓지 않음. */
    const val APART = "apart"

    fun fundOf(t: Txn): String? = t.move?.takeIf { it.startsWith(FUND) }?.removePrefix(FUND)

    /** 입금 [d]를 가족 [w]가 보낸 가족 돈으로 표시한 것. */
    fun asFund(d: Txn, w: Txn): Txn = d.copy(move = FUND + w.id)

    private fun captured(t: Txn) = t.source == Source.PUSH || t.source == Source.SMS
    private fun bankIssuer(t: Txn) = t.issuer != null && Issuers.cards.none { it.name == t.issuer }

    /**
     * 내 나만 보는 은행 입금 [d]를 보낸 가족 쪽 기록: 가계부 통장의 은행 출금(알림·문자), 같은 금액, 앞뒤 [WINDOW], 다른 입금이 이미
     * 가져가지 않은 것 중 가장 가까운 것. 월 용돈과 같은 금액이거나 용돈 고정지출에 이어진 이체는 용돈이라 가족 돈이 아닙니다.
     */
    fun fundSource(s: LedgerState, d: Txn): Txn? {
        if (!d.private || d.kind != Kind.INCOME || d.cancelOf != null || d.needsReview || !captured(d) || !bankIssuer(d)) return null
        val allowance = s.member(d.memberId)?.allowance ?: 0
        if (allowance > 0 && d.amount == allowance) return null
        val taken = s.txns.values.mapNotNullTo(HashSet()) { o -> if (o.id == d.id) null else fundOf(o) }
        return s.txns.values.asSequence()
            .filter { w ->
                !w.private && !w.personal && w.kind == Kind.EXPENSE && w.cancelOf == null && captured(w) && w.amount == d.amount &&
                    abs(w.at - d.at) <= WINDOW && w.id !in taken && linkOf(w) == null &&
                    w.recurringId?.let(AllowancePlan::isRule) != true && s.account(w.accountId)?.type == AccountType.BANK &&
                    s.member(w.memberId)?.isSender != true
            }
            .minWithOrNull(compareBy<Txn>({ abs(it.at - d.at) }, { it.id }))
    }

    /** 가족 돈 [d]가 출금 [w]의 출처가 될 수 있는지: 같은 사람·같은 은행, 같은 금액, 입금 직전 [WINDOW]부터 [FUND_DAYS]일 뒤까지. */
    fun fundFits(d: Txn, w: Txn): Boolean {
        if (fundOf(d) == null || !d.private || d.memberId != w.memberId || d.amount != w.amount) return false
        val a = d.issuer ?: return false
        val b = w.issuer ?: return false
        return Issuers.canonical(a) == Issuers.canonical(b) && w.at - d.at in -WINDOW..FUND_DAYS * 86_400_000
    }

    /** 출금 [w]를 낸 가족 돈: 맞는 것 중 아직 다른 출금이 쓰지 않은, 가장 먼저 받은 것(가족 돈 하나는 출금 하나와만). */
    fun fundFor(s: LedgerState, w: Txn): Txn? {
        val used = s.txns.values.mapNotNullTo(HashSet()) { o -> if (o.id == w.id) null else fromOf(o) }
        return s.txns.values.asSequence().filter { d -> d.id !in used && fundFits(d, w) }.minWithOrNull(compareBy<Txn>({ it.at }, { it.id }))
    }

    /**
     * 장부가 바뀐 뒤 가족 돈 표시를 고칠 내 입금들(이 폰이 기록한 나만 보는 은행 입금). 가족 폰의 출금은 몇 분~몇 시간 늦게 동기화될 수
     * 있어, 바뀐 내역과 같은 금액·앞뒤 [WINDOW]인 입금과, 출처였던 출금이 지워지거나 바뀐 입금을 다시 봅니다. "가족 돈 아니에요"는 그대로 둡니다.
     */
    fun fundUpdates(s: LedgerState, changed: List<Pair<Txn?, Txn?>>, me: String, installId: String): List<Txn> {
        if (changed.isEmpty()) return emptyList()
        val ids = changed.mapNotNullTo(HashSet()) { (b, a) -> (a ?: b)?.id }
        val touched = changed.flatMap { listOfNotNull(it.first, it.second) }
        return s.txns.values.filter { d ->
            d.private && d.memberId == me && d.createdBy == installId && d.kind == Kind.INCOME && (d.move == null || fundOf(d) != null) &&
                (d.id in ids || fundOf(d) in ids || touched.any { o -> !o.private && o.amount == d.amount && abs(o.at - d.at) <= WINDOW })
        }.mapNotNull { d ->
            val next = fundSource(s, d)?.let { asFund(d, it) } ?: d.copy(move = null)
            next.takeIf { it != d }
        }
    }

    // --- 가계부 통장끼리 옮긴 돈 (D-28 B) --------------------------------------------------------------

    /** 공유 내역: 짝지은 다른 쪽 id가 뒤에 붙습니다. 둘 다 가계부 합계(쓴 돈·수입)에서 빠지고 통장 잔액은 그대로입니다. */
    private const val LINK = "link:"

    fun linkOf(t: Txn): String? = t.move?.takeIf { it.startsWith(LINK) }?.removePrefix(LINK)

    /** 가계부 합계에서 뺄, 가계부 통장끼리 옮긴 돈의 한쪽인지. */
    fun linked(t: Txn): Boolean = !t.private && linkOf(t) != null

    /** 짝이 될 수 있는 공유 내역: 알림·문자로 기록된 가계부 은행 통장의 출금(지출)·입금(수입). 고정 내역·부모님 폰이 보낸 것은 아님. */
    fun linkable(s: LedgerState, t: Txn): Boolean =
        !t.private && !t.personal && t.cancelOf == null && !t.needsReview && t.amount > 0 && t.recurringId == null &&
            (t.kind == Kind.EXPENSE || t.kind == Kind.INCOME) && captured(t) && bankIssuer(t) &&
            s.account(t.accountId)?.type == AccountType.BANK && s.member(t.memberId)?.isSender != true

    /**
     * [t]와 짝지을 다른 쪽: 서로 다른 가계부 통장의 반대 종류(출금<->입금), 같은 금액, 앞뒤 [WINDOW], 아직 짝 없는 것 중 가장 가까운 것.
     * 두 폰이 각자 찾아도 같은 짝이 되게 시각 차이, 그다음 id로 고릅니다. [again]이면 "따로 세기"한 것도 봅니다(다시 묶기).
     */
    fun linkPartner(s: LedgerState, t: Txn, again: Boolean = false): Txn? {
        fun free(o: Txn) = o.move == null || again && o.move == APART
        if (!free(t) || !linkable(s, t)) return null
        val want = if (t.kind == Kind.EXPENSE) Kind.INCOME else Kind.EXPENSE
        return s.txns.values.asSequence()
            .filter { o -> o.id != t.id && o.kind == want && free(o) && o.amount == t.amount && o.accountId != t.accountId &&
                abs(o.at - t.at) <= WINDOW && linkable(s, o) }
            .minWithOrNull(compareBy<Txn>({ abs(it.at - t.at) }, { it.id }))
    }

    fun link(a: Txn, b: Txn): List<Txn> = listOf(a.copy(move = LINK + b.id), b.copy(move = LINK + a.id))

    /** 내역 화면 "따로 세기": 두 쪽을 풀고 다시 짝짓지 않게 합니다. 다른 쪽이 없으면 이쪽만. */
    fun apart(s: LedgerState, t: Txn): List<Txn> =
        listOfNotNull(t, linkOf(t)?.let { s.txns[it] }?.takeIf { linkOf(it) == t.id }).map { it.copy(move = APART) }

    /** 내역 화면 "옮긴 돈으로 묶기": 따로 세던 것을 다시 짝짓습니다. 짝이 없으면 빈 목록. */
    fun relink(s: LedgerState, t: Txn): List<Txn> = linkPartner(s, t, again = true)?.let { link(t, it) } ?: emptyList()

    /**
     * 장부가 바뀐 뒤 고칠 공유 내역들: 짝이 끊긴 것(다른 쪽이 지워졌거나 다른 쪽이 나를 가리키지 않음)은 비워 다시 찾게 하고,
     * 바뀐 내역 근처(같은 금액·앞뒤 [WINDOW]의 두 배)의 짝 없는 출금·입금을 묶습니다. 후보 짝을 시각 차이 → 출금 id → 입금 id 순으로
     * 정렬해 앞에서부터 묶으므로, 어느 내역부터 보든 어느 폰이 하든 같은 짝이 됩니다.
     */
    fun linkUpdates(s: LedgerState, changedIds: Set<String>): List<Txn> {
        val out = LinkedHashMap<String, Txn>()
        for (t in s.txns.values) {
            val other = linkOf(t) ?: continue
            if (t.private || s.txns[other]?.let(::linkOf) != t.id) out[t.id] = t.copy(move = null)
        }
        val st = if (out.isEmpty()) s else s.copy(txns = s.txns + out)
        val seeds = (changedIds + out.keys).mapNotNull { st.txns[it] }.filter { it.move == null && linkable(st, it) }
        if (seeds.isNotEmpty()) {
            val pool = st.txns.values.filter { o ->
                o.move == null && seeds.any { it.amount == o.amount && abs(it.at - o.at) <= 2 * WINDOW } && linkable(st, o)
            }
            val ins = pool.filter { it.kind == Kind.INCOME }
            val pairs = pool.filter { it.kind == Kind.EXPENSE }
                .flatMap { e -> ins.filter { i -> i.amount == e.amount && i.accountId != e.accountId && abs(i.at - e.at) <= WINDOW }.map { e to it } }
                .sortedWith(compareBy<Pair<Txn, Txn>>({ abs(it.first.at - it.second.at) }, { it.first.id }, { it.second.id }))
            val used = HashSet<String>()
            for ((e, i) in pairs) {
                if (e.id in used || i.id in used) continue
                used += e.id
                used += i.id
                for (x in link(e, i)) out[x.id] = x
            }
        }
        return out.values.filter { it != s.txns[it.id] }
    }
}
