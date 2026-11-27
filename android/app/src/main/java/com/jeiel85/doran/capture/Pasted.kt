package com.jeiel85.doran.capture

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import java.time.LocalDateTime

/**
 * 붙여 넣은 카드·은행 문구로 새 내역 칸을 채웁니다(편한가계부·위플 '문자 붙여넣기'). 알림 읽기와 달리 장부에 바로 넣지 않고
 * 쓰기 화면에 채워 사람이 확인한 뒤 저장합니다. 그래서 출처는 손으로 쓴 내역(MANUAL)이고 알림 원문은 남기지 않습니다.
 */
object Pasted {
    /** 읽지 못하면 null. 결제 실패·예정 안내는 돈이 나가지 않아 null입니다([Parser.unspent]). */
    fun parse(text: String, now: LocalDateTime = LocalDateTime.now(Fmt.zone)): Parsed? =
        text.takeIf { it.isNotBlank() }?.let { Parser.parse(it.trim(), now) }

    /** [p]로 채운 새 내역(저장 전). 결제 수단은 같은 카드사·은행의 계좌 중 끝자리가 맞는 것, 없으면 그 회사 계좌 하나뿐일 때만. */
    fun draft(s: LedgerState, p: Parsed, me: String?, now: LocalDateTime = LocalDateTime.now(Fmt.zone)): Txn {
        val kind = when (p.type) {
            Parsed.Type.APPROVAL -> Kind.EXPENSE
            Parsed.Type.CANCEL, Parsed.Type.DEPOSIT -> Kind.INCOME
            Parsed.Type.WITHDRAWAL -> if (p.cardPaymentTo != null) Kind.TRANSFER else Kind.EXPENSE
        }
        val same = s.accounts.values.filter { a -> !a.hidden && (a.institution == p.issuer || a.name.contains(p.issuer)) }
        // 카드는 카드 끝자리(last4), 은행 알림은 계좌 끝자리(acct)로 같은 회사의 여러 계좌를 가립니다(#91 리뷰, D-33과 같은 맞춤).
        val tail = if (p.isCard) p.last4 else p.acct
        val account = tail?.let { t -> same.firstOrNull { a -> a.last4?.let { com.jeiel85.doran.logic.Banks.tailMatch(it, t) } == true } }
            ?: same.singleOrNull()
        val merchant = when {
            p.type == Parsed.Type.CANCEL -> listOfNotNull(p.merchant, "결제 취소").joinToString(" ")
            p.cardPaymentTo != null -> "${p.cardPaymentTo} 카드값"
            else -> p.merchant.orEmpty()
        }
        return Txn(id = "paste", kind = kind, amount = p.amount, at = Fmt.millis(p.at ?: now), accountId = account?.id,
            merchant = merchant, memberId = me, installment = if (kind == Kind.EXPENSE) p.installment else 0, source = Source.MANUAL)
    }
}
