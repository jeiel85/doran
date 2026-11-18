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
}
