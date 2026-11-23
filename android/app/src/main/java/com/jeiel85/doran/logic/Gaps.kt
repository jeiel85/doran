package com.jeiel85.doran.logic

import com.jeiel85.doran.capture.Issuers
import com.jeiel85.doran.core.newId
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import kotlin.math.abs

/**
 * 알림 없이 나간 돈 찾기(DECISIONS.md D-31). 토스뱅크는 내가 직접 한 이체·송금에 출금 알림을 보내지 않아, 송금한 돈이 기록되지 않고
 * 남은 용돈이 실제보다 많게 보일 수 있습니다(2026-11-23). 은행이 알려 준 **거래 뒤 잔액**으로 그 사이의 빈틈을 찾습니다:
 * 잔액을 알려 준 두 알림 사이에 기록된 돈의 드나듦으로 설명되지 않게 잔액이 더 줄었으면, 알림 없이 나간 돈이 있습니다.
 *
 * 공유 범위 밖 통장(나만 보는 용돈, D-19)만 봅니다. 그 통장의 기록은 이 폰에만 있어 드나듦을 빠짐없이 모을 수 있지만, 가계부 통장은
 * 체크카드 결제가 다른 카드사 이름의 카드 계좌로 기록돼(출금 통장 연결이 없으면) 통장 흐름에서 빠지므로 틀린 빈틈이 생깁니다.
 * 순수 함수라 단위 시험(GapsTest)으로 경우를 고정합니다.
 */
object Gaps {
    /**
     * 이보다 작은 빈틈은 넘깁니다. 수수료, 잔돈 모으기 같은 자동 저금처럼 알림 없이 조금씩 빠지는 돈이 있어서, 작은 빈틈까지 물으면
     * 결제할 때마다 묻게 됩니다. 알림 없이 한 송금은 대개 이보다 큽니다.
     */
    const val MIN = 5_000L
    /** 빈틈을 알려 준 알림이 이만큼 지나야 묻습니다: 늦게 온 출금 문자가 그 사이에 들어와 빈틈을 메울 수 있어서. */
    const val SETTLE = 10 * 60_000L

    private val CARD_BANK = mapOf(
        "KB국민카드" to "KB국민은행", "신한카드" to "신한은행", "하나카드" to "하나은행", "우리카드" to "우리은행", "NH농협카드" to "NH농협",
    )

    /**
     * 이 카드사·은행의 돈이 드나드는 통장 이름. 은행은 그대로, 같은 금융그룹의 카드사는 그 은행으로 봅니다(체크카드 결제는 통장에서 바로
     * 나가서). 다른 카드사(현대·삼성 등)는 통장이 없어 null. 카드사 거래를 실제로 흐름에 넣을지는 [inPot]이 정합니다.
     */
    fun bankOf(issuer: String?): String? {
        val c = issuer?.takeIf { it.isNotBlank() }?.let(Issuers::canonical) ?: return null
        if (Issuers.banks.any { it.name == c }) return c
        return CARD_BANK[c]
    }

    /**
     * 거래 [t]를 그 통장 흐름에 넣을지: 은행 이름의 거래(통장 입출금, 토스뱅크 체크카드처럼 은행이 낸 카드)는 넣고, 카드사 이름의 거래는
     * 체크카드 알림으로 기록된 것과 그 결제의 취소만 넣습니다. 신용카드 결제·취소는 통장이 아니라 카드값을 바꿔서, 넣으면 신용카드 취소가
     * 통장에 들어온 돈으로 보여 없는 빈틈을 만들고 "쓴 돈"으로 답하면 없던 지출이 생깁니다(#72 리뷰). [checkCards]: 체크카드 알림으로
     * 기록된 거래 id(알림 원문 30일).
     */
    fun inPot(t: Txn, checkCards: Set<String>): Boolean {
        val c = t.issuer?.takeIf { it.isNotBlank() }?.let(Issuers::canonical) ?: return false
        if (Issuers.banks.any { it.name == c }) return true
        if (CARD_BANK[c] == null) return false
        return t.id in checkCards || t.cancelOf?.let { it in checkCards } == true
    }

    /**
     * 통장 흐름의 한 칸: 거래 하나, 또는 기록하지 않은 카드값 출금 알림 하나. [order]: 같은 시각끼리의 순서(기록한 때).
     * [delta]: 통장에 들어오면 +, 나가면 −, 원화 확정 전 해외 결제처럼 금액을 모르면 null(그 구간은 보지 않음).
     * [balance]: 그 알림이 알려 준 거래 뒤 잔액.
     */
    data class Step(val key: String, val at: Long, val order: Long, val delta: Long?, val balance: Long? = null)

    /** 빈틈: [bank] 통장에서 [fromAt] 뒤 [toAt]까지 알림 없이 [amount]원이 나감. [toKey]: 빈틈을 알려 준 칸. */
    data class Gap(val bank: String, val amount: Long, val fromAt: Long, val toAt: Long, val toKey: String) {
        /** 답(옮긴 돈·쓴 돈·아니에요)을 기억하는 열쇠. */
        val key: String get() = "$bank|$toKey"
    }

    /** 빈틈을 볼 통장: [me]가 공유 범위 밖에 둔 은행(나만 보는 용돈 통장). 함께 관리하는 사람이나 공유하는 은행은 보지 않습니다. */
    fun eligible(s: LedgerState, me: String, bank: String): Boolean = s.member(me)?.shares(bank) == false

    /**
     * 통장 흐름에 모을 거래: [me]의 거래 중 나만 보는 것과, 이 폰([installId])이 기록한 공유 거래. 은행은 범위 밖이어도 같은 금융그룹의
     * 체크카드는 공유할 수 있어서(KB국민카드만 공유) 그 결제는 공유 거래로 기록되지만 통장에서는 나간 돈입니다(#72 리뷰).
     * 가족 폰이 기록한 거래는 그 사람의 통장일 수 있어 넣지 않습니다. 가족 카드의 용돈 금액 자리(personal)는 뺍니다.
     */
    fun collects(t: Txn, me: String, installId: String, checkCards: Set<String>): Boolean =
        t.memberId == me && (t.private || t.createdBy == installId) && !t.personal && inPot(t, checkCards)

    /** 거래 [t]가 그 통장에 주는 흐름: 결제·출금·옮긴 돈은 나감, 입금과 결제 취소(돌아온 돈)는 들어옴. */
    fun delta(t: Txn): Long? = when {
        t.amount == 0L && t.foreign != null -> null
        t.cancelOf != null -> t.amount
        t.kind == Kind.INCOME -> t.amount
        else -> -t.amount
    }

    /** [bank] 통장의 흐름 [steps]에서 잔액을 알려 준 두 칸 사이의 빈틈(알림 없이 나간 돈)을 찾습니다. */
    fun find(bank: String, steps: List<Step>): List<Gap> {
        val out = ArrayList<Gap>()
        var last: Step? = null
        var sum = 0L
        var unknown = false
        for (s in steps.sortedWith(compareBy<Step>({ it.at }, { it.order }, { it.key }))) {
            if (s.delta == null) unknown = true else sum += s.delta
            val b = s.balance ?: continue
            val prev = last
            if (prev?.balance != null && !unknown) {
                val gap = b - (prev.balance + sum)
                if (gap <= -MIN) out += Gap(bank, -gap, prev.at, s.at, s.key)
            }
            last = s
            sum = 0
            unknown = false
        }
        return out
    }

    /**
     * 빈틈과 같은 금액이 같은 무렵 **내 다른 통장**에 들어온 입금(알림·문자로 기록된 것): 있으면 옮긴 돈일 가능성이 큽니다.
     * 이미 다른 출금과 짝지어진 입금은 뺍니다.
     */
    fun counterpart(s: LedgerState, g: Gap, me: String): Txn? {
        val taken = s.txns.values.mapNotNullTo(HashSet()) { Moves.pairOf(it) }
        return s.txns.values.asSequence()
            .filter { d ->
                d.memberId == me && Moves.isDeposit(d) && d.id !in taken && Moves.linkOf(d) == null && d.amount == g.amount &&
                    bankOf(d.issuer) != g.bank && d.at in (g.fromAt - Moves.WINDOW)..(g.toAt + Moves.WINDOW)
            }
            .minByOrNull { abs(it.at - g.toAt) }
    }

    /**
     * 빈틈의 답으로 남길 거래(나만 보는 용돈): [spent]면 용돈에서 쓴 돈(지출), 아니면 옮긴 돈(계좌 없는 이체, D-26 `Moves.USER`).
     * 날짜는 짝 입금이 그 사이에 있으면 그 시각, 아니면 빈틈을 알려 준 알림 바로 앞(그 구간 안이라 다시 계산해도 빈틈이 메워짐).
     */
    fun answer(s: LedgerState, g: Gap, spent: Boolean, me: String, installId: String, deposit: Txn?, now: Long): Txn {
        val inside = (g.fromAt + 1)..(g.toAt - 1)
        val at = deposit?.at?.takeIf { it in inside } ?: (g.toAt - 1).coerceAtLeast(g.fromAt + 1)
        val base = Txn(
            id = newId(), kind = Kind.EXPENSE, amount = g.amount, at = at, memberId = me, source = Source.MANUAL, issuer = g.bank,
            cardLabel = g.bank, private = true, createdBy = installId, createdAt = now,
            memo = "잔액으로 찾은 알림 없는 출금",
        )
        return if (spent) base.copy(merchant = "알림 없이 나간 돈", categoryId = com.jeiel85.doran.capture.Categorizer.categorize(s, "", Kind.EXPENSE))
        else base.copy(kind = Kind.TRANSFER, move = Moves.USER, merchant = deposit?.issuer?.let { "${g.bank} → $it" } ?: "알림 없이 옮긴 돈")
    }
}
