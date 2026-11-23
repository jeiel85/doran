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
        if (card.type != AccountType.CHECK || card.payFromId != null) return card
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

    /** 통장·카드 화면의 입출금 목록: 이 계좌의 거래와 이 계좌로 들어온 이체, 통장이면 이은 체크카드의 결제까지. 취소 엔티티는 원거래에 묶여 빠짐. */
    fun flow(s: LedgerState, a: Account): List<Txn> {
        val cards = if (a.type == AccountType.BANK) linkedCards(s, a).mapTo(HashSet()) { it.id } else emptySet()
        return s.txns.values.filter { t ->
            t.cancelOf == null && (t.accountId == a.id || t.toAccountId == a.id || t.accountId in cards && countsOnBank(t))
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
}
