package com.jeiel85.doran.logic

import com.jeiel85.doran.capture.Categorizer
import com.jeiel85.doran.capture.Parsed
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn

/**
 * 들어온 돈을 나간 돈으로 읽어 기록한 예전 내역(#106). 0.3.16 이하는 "입출금통장(0932)에 20원이 입금되었어요"를 통장 이름의 '출금' 때문에
 * 출금으로 읽어 쓴 돈·옮긴 돈으로 남겼습니다. 이 폰에 남은 알림 원문(30일)을 지금 판정으로 다시 읽어 입금이면 사용자에게 한 번 묻습니다.
 * 예전 기록은 앱이 몰래 바꾸지 않습니다(D-28과 같음).
 */
object Misread {
    /** 원문이 남는 기간. 그보다 오래된 기록은 다시 읽을 수 없어 보지 않습니다. */
    const val DAYS = 31L

    /** 다시 읽어 볼 만한 기록: 이 폰이 알림으로 기록한 내 쓴 돈·옮긴 돈(취소·고정지출 자리·카드값 이체는 아님). */
    fun candidate(t: Txn, me: String, installId: String, since: Long): Boolean =
        t.memberId == me && t.createdBy == installId && t.at >= since && t.cancelOf == null && t.recurringId == null &&
            (t.source == Source.PUSH || t.source == Source.SMS) &&
            (t.kind == Kind.EXPENSE || Moves.isMoved(t))

    /** 그 기록의 알림을 지금 다시 읽은 결과가 모두 은행 입금인지. 하나라도 못 읽거나 다르게 읽히면 묻지 않습니다. */
    fun allDeposits(reparsed: List<Parsed?>): Boolean =
        reparsed.isNotEmpty() && reparsed.all { it != null && it.type == Parsed.Type.DEPOSIT && !it.isCard }

    /** 들어온 돈으로 바로잡은 모양. 가족 돈 표시와 그 돈으로 본 출금의 다시 판정은 저장 뒤 훅([Hooks])이 맡습니다. */
    fun asDeposit(s: LedgerState, t: Txn): Txn =
        t.copy(kind = Kind.INCOME, move = null, needsReview = false, categoryId = Categorizer.categorize(s, t.merchant, Kind.INCOME))
}
