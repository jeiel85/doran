package com.jeiel85.doran.logic

import com.jeiel85.doran.capture.Issuers
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Txn

/**
 * 은행을 연결하지 않고 알림만으로 통장 관리(DECISIONS.md D-33). 비주얼 가계부처럼 은행 입출금 알림과 문자만으로 통장마다 입출금과
 * 잔액을 보여 주려고, 같은 은행의 통장을 계좌번호 끝자리로 나누고, 체크카드를 돈이 나가는 통장에 잇습니다.
 * 순수 함수라 단위 시험(BanksTest)으로 경우를 고정합니다.
 */
object Banks {
    /**
     * 적어 둔 끝자리 [registered]가 알림에 보인 끝자리 [seen]과 같은 통장인지: 한쪽이 다른 쪽의 끝이면 같다고 봅니다. 은행마다 가리는
     * 자리가 달라 알림엔 "789"만, 사람은 "56789"를 적을 수 있어서입니다. 두 자리보다 짧으면 맞춰 보지 않습니다.
     */
    fun tailMatch(registered: String, seen: String): Boolean {
        val a = registered.filter(Char::isDigit)
        val b = seen.filter(Char::isDigit)
        return minOf(a.length, b.length) >= 2 && (a.endsWith(b) || b.endsWith(a))
    }

    private fun banksOf(accounts: Collection<Account>, issuer: String, me: String?) = accounts
        .filter { it.type == AccountType.BANK && it.institution?.let { i -> Issuers.same(i, issuer) } == true && (it.ownerId == me || it.ownerId == null) }
        .sortedBy { it.order }

    /**
     * 은행 알림이 갈 이 사람(또는 공동)의 통장. 끝자리를 적어 둔 통장은 알림의 끝자리가 맞을 때만 받습니다(같은 은행 통장 나누기).
     * 맞는 통장이 없으면 끝자리를 적지 않은 통장(그 은행의 기본 통장)으로 갑니다. 기본 통장이 없을 때, 알림에 끝자리가 없으면 그 은행의
     * 첫 통장으로, 끝자리가 있으면 null(적어 둔 어느 통장과도 다른 통장이라 새로 만듦, [newTail]).
     *
     * 알림으로 처음 만든 통장에는 끝자리를 적지 않습니다: 같은 통장도 앱 푸시와 문자가 가리는 자리가 달라 저절로 나누면 통장이 둘로
     * 갈릴 수 있어서, 나누기는 사람이 끝자리를 적었을 때만 합니다.
     */
    fun pick(accounts: Collection<Account>, issuer: String, acct: String?, me: String?): Account? {
        val mine = banksOf(accounts, issuer, me)
        if (acct != null) mine.filter { it.last4 != null && tailMatch(it.last4, acct) }
            .maxByOrNull { minOf(it.last4!!.length, acct.length) }?.let { return it }
        mine.firstOrNull { it.last4 == null }?.let { return it }
        return if (acct == null) mine.firstOrNull() else null
    }

    /** [pick]이 null이라 새로 만드는 통장에 적을 끝자리: 그 은행에 끝자리를 적어 둔 통장이 있을 때만(사람이 나누기로 한 은행). */
    fun newTail(accounts: Collection<Account>, issuer: String, acct: String?, me: String?): String? =
        acct?.takeIf { banksOf(accounts, issuer, me).any { it.last4 != null } }

    /**
     * 체크카드 [card]가 돈이 나가는 통장을 아직 모르면, 같은 금융그룹 은행(KB국민카드 → KB국민은행)의 같은 사람(또는 공동) 통장이 하나뿐일 때 그
     * 통장으로 잇습니다. 둘 이상이면 어느 통장인지 몰라 잇지 않고 사람이 계좌 화면에서 고릅니다. 바꿀 것이 없으면 [card] 그대로.
     */
    fun autoPayFrom(accounts: Collection<Account>, card: Account): Account {
        // 잔액을 따로 세는 예전 체크카드(D-29)는 그 카드가 잔액을 맡으니 잇지 않습니다(이으면 같은 잔액이 두 자산에 들어감, #77 리뷰).
        if (card.type != AccountType.CHECK || card.payFromId != null || Stats.keepsBalance(card)) return card
        val bank = Gaps.bankOf(card.institution) ?: return card
        val one = accounts.filter {
            it.type == AccountType.BANK && !it.hidden && Gaps.bankOf(it.institution) == bank && (it.ownerId == card.ownerId || it.ownerId == null)
        }.singleOrNull() ?: return card
        return card.copy(payFromId = one.id)
    }

    /** 통장 [bank]에 이은 체크카드들(돈이 그 통장에서 나감). */
    fun linkedCards(s: LedgerState, bank: Account): List<Account> =
        // 잔액을 따로 세는 예전 체크카드(통장처럼 쓰던 것, D-29)는 그 카드에서 이미 빠지므로 통장에서 또 빼지 않습니다.
        s.accounts.values.filter { it.payFromId == bank.id && it.type == AccountType.CHECK && !Stats.keepsBalance(it) }.sortedBy { it.order }

    /**
     * 이은 체크카드의 결제 [t]를 통장 잔액에 넣는지. 나만 보는 용돈(private)은 이 폰에만 있어 넣으면 가족 폰과 잔액이 달라지므로 빼고,
     * 은행이 다음에 알려 주는 잔액으로 맞춥니다.
     */
    fun countsOnBank(t: Txn): Boolean = !t.private

    /**
     * 통장·카드 화면의 입출금 목록: 이 계좌의 거래와 이 계좌로 들어온 이체, 통장이면 이은 체크카드의 결제까지. 취소 엔티티는 원거래에 묶여 빠짐.
     * 가족 카드에서 용돈으로 옮긴 결제는 금액 자리(personal)가 대신 들어가므로 본인 폰의 용돈 원본은 빼서 두 번 보이지 않게 합니다
     * (잔액·카드값과 같은 규칙 [Stats.onAccount], #77 리뷰).
     */
    fun flow(s: LedgerState, a: Account): List<Txn> {
        val cards = if (a.type == AccountType.BANK) linkedCards(s, a).mapTo(HashSet()) { it.id } else emptySet()
        return s.txns.values.filter { t ->
            t.cancelOf == null && (
                (t.accountId == a.id || t.toAccountId == a.id) && Stats.onAccount(a, t) ||
                    t.accountId in cards && countsOnBank(t)
                )
        }.sortedByDescending { it.at }
    }

    /** [a] 쪽에서 본 거래 [t]의 방향: 들어오면 +, 나가면 −. 이체는 받는 계좌면 +. */
    fun sign(a: Account, t: Txn): Int = when {
        t.kind == Kind.TRANSFER -> if (t.toAccountId == a.id) 1 else -1
        t.kind == Kind.INCOME -> 1
        else -> -1
    }

    /**
     * 나만 보는 통장(공유 범위 밖 은행, D-19)의 이 폰 계산: 가족에게 그 통장이 있다는 것도 알리지 않으려고 계좌를 만들지 않으니,
     * 은행이 마지막으로 알려 준 잔액([anchor]·[anchorAt])에 그 뒤의 드나듦([flows], D-31 [Gaps.collects]로 모은 것)을 더합니다.
     * 잔액을 알려 준 알림이 없으면 null. 원화 확정 전 해외 결제처럼 금액을 모르는 거래는 넘깁니다.
     */
    fun privateBalance(anchor: Long?, anchorAt: Long?, flows: List<Txn>): Long? {
        if (anchor == null || anchorAt == null) return null
        return anchor + flows.filter { it.at > anchorAt }.sumOf { Gaps.delta(it) ?: 0L }
    }

    /** 나만 보는 통장 하나: [bank] 이름, 마지막 잔액 알림, 계산한 지금 잔액, 그 통장의 드나듦(최근 것 먼저). */
    data class Private(val bank: String, val balance: Long?, val balanceAt: Long?, val txns: List<Txn>)

    /** 체크카드 알림으로 기록된 나만 보는 거래(그 은행 통장에서 나간 돈). */
    data class Check(val id: String, val bank: String, val at: Long)

    /** 기록하지 않은 범위 밖 카드값 출금(D-19 — 지출이 아니지만 통장에서는 나간 돈). [to]: 카드사. */
    data class Bill(val bank: String, val at: Long, val amount: Long, val to: String) {
        /** 알림 원문 줄과 이 폰에 남긴 것이 같은 출금이면 같은 열쇠(받은 시각·금액). */
        val key: String get() = "bill:$bank:$at:$amount"
        fun txn(me: String) = Txn(id = key, kind = Kind.TRANSFER, amount = amount, at = at, memberId = me, merchant = "$to 카드값",
            issuer = bank, private = true)
    }

    /**
     * 나만 보는 통장 계산에 쓰려고 이 폰에 남겨 두는 것(kv): 알림 원문은 30일 뒤 지워지는데, 그 뒤에 마지막 잔액 알림보다 늦은
     * 체크카드 결제·카드값 출금이 흐름에서 빠지면 잔액이 예전 값으로 돌아가고 통장 화면 목록에서도 사라져서입니다(#77 리뷰).
     * 최근 [MAX_KEPT]건씩만 둡니다(그만큼 쌓이는 동안 잔액 알림이 한 번도 없을 일은 드묾).
     */
    data class Kept(val checks: List<Check> = emptyList(), val bills: List<Bill> = emptyList()) {
        /**
         * 은행마다 마지막 잔액 알림([anchors], 은행 → 잔액·시각)보다 늦은 것은 잔액에 들어가므로 몇 건이든 모두 남기고, 그보다 이른 것은
         * 통장 화면 목록용으로 최근 [MAX_KEPT]건만 둡니다(#78 — 전에는 500건을 넘으면 잔액에 들어갈 결제까지 빠질 수 있었어요).
         */
        fun pruned(anchors: Map<String, Pair<Long, Long>> = emptyMap()): Kept {
            fun after(bank: String, at: Long) = anchors[bank]?.let { at > it.second } ?: false
            fun <T> keep(list: List<T>, bank: (T) -> String, at: (T) -> Long): List<T> {
                val (live, old) = list.partition { after(bank(it), at(it)) }
                val kept = (live + old.takeLast(MAX_KEPT)).toHashSet()
                return list.filter { it in kept }
            }
            return Kept(keep(checks.distinctBy { it.id }, { it.bank }, { it.at }), keep(bills.distinctBy { it.key }, { it.bank }, { it.at }))
        }

        fun json(): String = org.json.JSONObject()
            .put("checks", org.json.JSONArray(checks.map { org.json.JSONObject().put("id", it.id).put("bank", it.bank).put("at", it.at) }))
            .put("bills", org.json.JSONArray(bills.map { org.json.JSONObject().put("bank", it.bank).put("at", it.at).put("amount", it.amount).put("to", it.to) }))
            .toString()

        companion object {
            fun from(json: String?): Kept {
                val o = json?.let { runCatching { org.json.JSONObject(it) }.getOrNull() } ?: return Kept()
                fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                return Kept(
                    list("checks").mapNotNull { c -> c.optString("id").takeIf { it.isNotEmpty() }?.let { Check(it, c.optString("bank"), c.optLong("at")) } },
                    list("bills").map { b -> Bill(b.optString("bank"), b.optLong("at"), b.optLong("amount"), b.optString("to")) },
                )
            }
        }
    }

    /**
     * 통장 [bankId]의 출금 알림이 그 통장에 이은 체크카드로 이미 기록한 결제와 같은 돈인지(#78): 같은 금액, 앞뒤 [window] 안의
     * 알림·문자로 기록된 체크카드 결제(취소 아님). 은행이 체크카드 결제를 출금 문자로도 알려 주면 통장에서 두 번 빠지기 때문입니다.
     */
    fun linkedCardPayment(s: LedgerState, bankId: String, amount: Long, at: Long, window: Long): Txn? {
        val cards = s.accounts.values.filter { it.type == AccountType.CHECK && it.payFromId == bankId }.mapTo(HashSet()) { it.id }
        if (cards.isEmpty()) return null
        return s.txns.values.filter {
            it.accountId in cards && it.kind == Kind.EXPENSE && it.cancelOf == null && it.amount == amount &&
                kotlin.math.abs(it.at - at) <= window && it.source.captured
        }.minByOrNull { kotlin.math.abs(it.at - at) }
    }

    /** [Kept]에 남기는 최대 건수(은행 잔액 알림이 오래 없을 때의 상한). */
    const val MAX_KEPT = 500

    /**
     * 나만 보는 통장 목록(D-33): 공유 범위 밖에 둔 은행마다 마지막 잔액 알림([anchors], 은행 → 잔액·시각) + 그 뒤의 드나듦.
     * 드나듦은 알림 없이 나간 돈 찾기(D-31)의 흐름([Gaps.collects])에 기록하지 않은 카드값 출금([bills])을 더한 것입니다.
     * 같은 금융그룹 카드사의 결제는 체크카드인 것만: 알림 원문이나 이 폰에 남긴 것([checkCards])으로, 또는 체크카드 계좌의 결제로 압니다.
     */
    fun privateBanks(s: LedgerState, me: String, installId: String, anchors: Map<String, Pair<Long, Long>>, checkCards: Set<String>,
                     bills: List<Bill>): List<Private> {
        val checks = checkCards + s.txns.values.filter { s.account(it.accountId)?.type == AccountType.CHECK }.map { it.id }
        val flows = (s.txns.values.filter { Gaps.collects(it, me, installId, checks) }.mapNotNull { t -> Gaps.bankOf(t.issuer)?.let { it to t } } +
            bills.distinctBy { it.key }.map { it.bank to it.txn(me) })
            .groupBy({ it.first }, { it.second })
        return (anchors.keys + flows.keys).distinct().filter { Gaps.eligible(s, me, it) }.sorted().map { bank ->
            val list = flows[bank].orEmpty().sortedByDescending { it.at }
            val a = anchors[bank]
            Private(bank, privateBalance(a?.first, a?.second, list), a?.second, list)
        }
    }
}
