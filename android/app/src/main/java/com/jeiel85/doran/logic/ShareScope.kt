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

    /** 바꾼 시각을 모를 때 다시 볼 시간(#74). */
    const val RECHECK_MS = 6 * 3_600_000L
    /** 바꾼 시각을 알아도 이보다 오래된 기록은 다시 보지 않습니다(그 폰이 오래 꺼져 있던 경우에도 한 달까지). */
    const val RECHECK_MAX_MS = 30 * 86_400_000L
    /** 두 폰의 시계 차이를 감안해 바꾼 시각보다 조금 앞부터 봅니다. */
    private const val CLOCK_SLACK_MS = 5 * 60_000L

    /**
     * 다시 볼 기록의 시작 시각(#99): 멤버 기록의 HLC 시각([changedAt])과 최근 [RECHECK_MS] 중 **더 이른 쪽**(최대 30일, 시계 차이 5분 감안).
     * HLC 시각은 그 멤버 기록을 마지막으로 고친 때라, 범위를 바꾼 뒤 이름·용돈을 또 고쳤으면 범위를 바꾼 때보다 늦습니다(#101 리뷰) —
     * 그래서 6시간보다 줄이지는 않고, 더 오래 동기화되지 않은 경우에만 넓힙니다.
     */
    fun recheckSince(changedAt: Long?, now: Long): Long {
        val fallback = now - RECHECK_MS
        val byHlc = changedAt?.let { maxOf(it - CLOCK_SLACK_MS, now - RECHECK_MAX_MS) } ?: fallback
        return minOf(byHlc, fallback)
    }

    /**
     * 다른 폰에서 내 공유 범위를 좁혔을 때(#74, DECISIONS.md D-40): 그 동기화가 오기 전에 이 폰이 공유로 기록한 알림 내역 가운데
     * 이제 범위 밖인 것을 나만 보는 용돈으로 돌립니다(취소 기록 포함). 개인정보를 지키는 쪽만 고칩니다 — 범위를 넓힌 경우 예전
     * 나만 보는 내역을 저절로 가족에게 내놓지는 않습니다. 계좌는 비웁니다(범위 밖 은행·카드는 가계부 계좌에 두지 않음, D-19).
     * 공유하던 것을 나만 보기로 바꾸면 동기화가 가족 폰의 사본을 지웁니다(`wasShared`).
     */
    fun privatize(s: com.jeiel85.doran.data.LedgerState, before: com.jeiel85.doran.model.Member, after: com.jeiel85.doran.model.Member,
                  installId: String, now: Long, judgeWithdrawal: (com.jeiel85.doran.model.Txn) -> com.jeiel85.doran.model.Txn = { it },
                  changedAt: Long? = null): List<com.jeiel85.doran.model.Txn> {
        if (after.id != before.id) return emptyList()
        val since = recheckSince(changedAt, now)
        // 원래 결제만 고르고, 그 취소는 원래 결제와 함께 옮깁니다(#97 리뷰): 취소만 나만 보기로 가면 가족 폰엔 취소가 사라져
        // 옛 결제가 취소 안 된 지출로 되살아납니다. 6시간보다 오래된 결제의 최근 취소는 그 결제를 따라 공유로 둡니다.
        val out = s.txns.values.filter { t ->
            t.cancelOf == null && t.memberId == after.id && t.createdBy == installId && !t.private && !t.personal && t.source.captured &&
                t.createdAt >= since && t.issuer?.let { before.shares(it) && !after.shares(it) } == true
        }
        val ids = out.mapTo(HashSet()) { it.id }
        val cancels = s.txns.values.filter { it.cancelOf in ids && !it.private }
        // 은행 출금은 범위 밖 기록과 같이 '쓴 돈 / 옮긴 돈' 판정을 거칩니다(D-26, #97 리뷰 — 급여 이체가 용돈을 다 쓴 것처럼 보이지 않게).
        fun bankOut(t: com.jeiel85.doran.model.Txn) =
            t.kind == com.jeiel85.doran.model.Kind.EXPENSE && com.jeiel85.doran.capture.Issuers.cards.none { it.name == t.issuer }
        return out.map { t -> t.copy(private = true, accountId = null).let { if (bankOut(it)) judgeWithdrawal(it) else it } } +
            cancels.map { it.copy(private = true, accountId = null) }
    }
}
