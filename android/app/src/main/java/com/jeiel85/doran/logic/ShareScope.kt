package com.jeiel85.doran.logic

import com.jeiel85.doran.model.Member

/** 구성원의 공유 범위(DECISIONS.md D-19)를 화면·알림 문장으로. */
object ShareScope {
    /** "함께 관리" / "현대카드만 보내기" */
    fun label(m: Member): String = when {
        !m.sharesPicked -> "함께 관리"
        m.shareIssuers.isEmpty() -> "보내는 카드 없음"
        else -> m.shareIssuers.joinToString("·") + "만 보내기"
    }

    fun detail(m: Member): String = when {
        !m.sharesPicked -> "이 사람 폰이 읽은 카드·은행 내역을 모두 가계부에 올려요"
        m.shareIssuers.isEmpty() -> "자동 기록은 모두 본인만 보는 용돈으로 남겨요(가족에게는 월 합계만)"
        else -> "${m.shareIssuers.joinToString("·")} 내역만 가계부에 올리고, 나머지는 본인만 보는 용돈으로 남겨요(가족에게는 월 합계만)"
    }

    /** 다른 폰에서 공유 범위를 바꿨을 때의 알림 제목. [me]는 이 폰의 구성원, [by]는 바꾼 사람 이름. */
    fun changedTitle(m: Member, me: String?, by: String?): String {
        val who = by ?: "가족"
        return when {
            m.id == me -> "${Josa.iGa(who)} 내 공유 범위를 바꿨어요"
            m.shareBy == m.id -> "${Josa.iGa(m.name)} 공유 범위를 바꿨어요"
            else -> "${Josa.iGa(who)} ${Josa.ui(m.name)} 공유 범위를 바꿨어요"
        }
    }

    /** 공유 범위가 실제로 달라졌는지(이름·용돈만 바뀐 경우는 알리지 않음). */
    fun changed(before: Member, after: Member): Boolean =
        before.share != after.share || before.shareIssuers.toSet() != after.shareIssuers.toSet()

    /** 범위가 바뀐 뒤 다시 볼 시간(#74): 다른 폰에서 바꾼 범위가 동기화로 오기 전에 이 폰이 기록했을 만한 때. */
    const val RECHECK_MS = 6 * 3_600_000L

    /**
     * 다른 폰에서 내 공유 범위를 좁혔을 때(#74, DECISIONS.md D-40): 그 동기화가 오기 전에 이 폰이 공유로 기록한 알림 내역 가운데
     * 이제 범위 밖인 것을 나만 보는 용돈으로 돌립니다(취소 기록 포함). 개인정보를 지키는 쪽만 고칩니다 — 범위를 넓힌 경우 예전
     * 나만 보는 내역을 저절로 가족에게 내놓지는 않습니다. 계좌는 비웁니다(범위 밖 은행·카드는 가계부 계좌에 두지 않음, D-19).
     * 공유하던 것을 나만 보기로 바꾸면 동기화가 가족 폰의 사본을 지웁니다(`wasShared`).
     */
    fun privatize(s: com.jeiel85.doran.data.LedgerState, before: com.jeiel85.doran.model.Member, after: com.jeiel85.doran.model.Member,
                  installId: String, now: Long): List<com.jeiel85.doran.model.Txn> {
        if (after.id != before.id) return emptyList()
        val since = now - RECHECK_MS
        val out = s.txns.values.filter { t ->
            t.memberId == after.id && t.createdBy == installId && !t.private && !t.personal && t.source.captured &&
                t.createdAt >= since && t.issuer?.let { before.shares(it) && !after.shares(it) } == true
        }
        val ids = out.mapTo(HashSet()) { it.id }
        val cancels = s.txns.values.filter { it.cancelOf in ids && !it.private }
        return (out + cancels).map { it.copy(private = true, accountId = null) }
    }
}
