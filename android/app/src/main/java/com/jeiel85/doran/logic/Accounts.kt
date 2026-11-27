package com.jeiel85.doran.logic

import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.Goal
import com.jeiel85.doran.model.Recurring
import com.jeiel85.doran.model.Txn

/** 계좌 합치기(#60 2): 알림이 같은 카드·통장을 계좌 둘로 갈라 만든 경우, 한쪽으로 모읍니다. */
object Accounts {
    data class Merge(val txns: List<Txn>, val recurrings: List<Recurring>, val goals: List<Goal>, val accounts: List<Account>)

    /** 합칠 수 있는 짝: 같은 종류의 다른 계좌(숨긴 것 포함). */
    fun mergeTargets(s: LedgerState, from: Account): List<Account> =
        s.accounts.values.filter { it.id != from.id && it.type == from.type }.sortedBy { it.order }

    /**
     * [from]을 [into]로: [from]을 가리키던 내역(쓴 곳·받는 곳)·고정 규칙·저축 목표·체크카드의 출금 통장을 [into]로 바꾼 것.
     * [into]에 끝자리·카드사가 비어 있으면 [from]의 것을 채웁니다(다음 알림도 [into]로 오게). [from] 자체를 지우는 것은 부르는 쪽이 합니다.
     */
    fun merge(s: LedgerState, from: Account, into: Account): Merge = Merge(
        txns = s.txns.values.filter { it.accountId == from.id || it.toAccountId == from.id }.map {
            it.copy(accountId = if (it.accountId == from.id) into.id else it.accountId, toAccountId = if (it.toAccountId == from.id) into.id else it.toAccountId)
        },
        recurrings = s.recurrings.values.filter { it.accountId == from.id }.map { it.copy(accountId = into.id) },
        goals = s.goals.values.filter { it.accountId == from.id }.map { it.copy(accountId = into.id) },
        accounts = s.accounts.values.filter { it.payFromId == from.id && it.id != into.id }.map { it.copy(payFromId = into.id) } +
            listOfNotNull(into.copy(last4 = into.last4 ?: from.last4, institution = into.institution ?: from.institution).takeIf { it != into }),
    )
}
