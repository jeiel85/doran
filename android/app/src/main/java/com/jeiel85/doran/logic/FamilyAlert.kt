package com.jeiel85.doran.logic

import com.jeiel85.doran.capture.Categorizer
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Txn
import kotlin.math.abs

/** 받침에 맞춘 조사 (남편이/아내가, 현대카드로/통장으로). */
object Josa {
    private fun last(word: String): Char? = word.trim().lastOrNull { it.isLetterOrDigit() }

    /** 받침 종성 번호(0 = 없음). 한글이 아니면 어림합니다. */
    private fun jong(word: String): Int {
        val c = last(word) ?: return 0
        return when {
            c in '가'..'힣' -> (c - '가') % 28
            // 영·일·삼·육·칠·팔은 받침이 있고(일·칠·팔은 ㄹ), 이·사·오·구는 없음
            c.isDigit() -> when (c) { '1', '7', '8' -> 8; '0', '3', '6' -> 4; else -> 0 }
            c.lowercaseChar() in "aeiouy" -> 0
            c.lowercaseChar() == 'l' -> 8
            else -> 4
        }
    }

    fun iGa(w: String) = w + if (jong(w) != 0) "이" else "가"
    fun eulReul(w: String) = w + if (jong(w) != 0) "을" else "를"
    fun ui(w: String) = "${w}의"
    /** ㄹ 받침과 받침 없음은 "로", 나머지는 "으로". */
    fun ro(w: String) = w + when (jong(w)) { 0, 8 -> "로"; else -> "으로" }
}

/**
 * 다른 가족의 카드·계좌 내역이 들어왔을 때 이 폰에 띄우는 알림 문장 (DECISIONS.md D-9).
 * 문자 원문을 그대로 넘기는 대신 "남편이 현대카드로 12,300원 썼어요 / 스타벅스 · 카드사 누적 523,000원"처럼 만듭니다.
 */
object FamilyAlert {
    data class Text(val title: String, val body: String)

    fun of(s: LedgerState, t: Txn): Text {
        val who = s.member(t.memberId)?.name ?: "가족"
        val account = s.account(t.accountId)
        val card = t.issuer ?: account?.institution ?: account?.name ?: "카드"
        val isCard = account?.type == AccountType.CARD || account?.type == AccountType.CHECK || (account == null && t.issuer?.endsWith("카드") == true)
        val won = Fmt.won(t.amount)
        val details = buildList {
            if (t.merchant.isNotBlank()) add(t.merchant)
            if (t.installment > 1) add("${t.installment}개월 할부")
            when {
                t.cumulative != null -> add("카드사 누적 ${Fmt.won(t.cumulative)}")
                isCard && account != null && !t.isCancel -> add("이번 달 이 카드 ${Fmt.won(Stats.card(s, account).monthSpent)}")
            }
        }
        val title = when {
            t.isCancel -> "${Josa.ui(who)} $card $won 결제가 취소됐어요"
            t.amount == 0L && t.foreign != null -> "${Josa.iGa(who)} ${Josa.ro(card)} 해외에서 ${t.foreign} 결제했어요"
            t.kind == Kind.TRANSFER -> "$who ${card}에서 ${Josa.ro(s.account(t.toAccountId)?.name ?: "다른 계좌")} ${won}이 나갔어요"
            t.kind == Kind.INCOME -> "$who ${card}에 ${won}이 들어왔어요"
            isCard -> "${Josa.iGa(who)} ${Josa.ro(card)} ${won} 썼어요"
            else -> "$who ${card}에서 ${won}이 나갔어요"
        }
        return Text(title, details.joinToString(" · ").ifEmpty { if (t.kind == Kind.INCOME) "입금" else "결제" })
    }
}

/**
 * 같은 결제를 두 사람 폰이 각각 기록한 경우 찾기 (DECISIONS.md D-5):
 * 가족카드 알림이 본인·주 카드 소지자 양쪽에 가거나, 옛 문자 전달 앱이 남아 있는 경우.
 */
object Twins {
    private const val WINDOW = 3 * 60_000L
    private val digits = Regex("""\((\d{3,4})\)""")

    fun same(a: Txn, b: Txn): Boolean {
        if (a.id == b.id || !a.source.captured || !b.source.captured) return false
        if (a.issuer == null || a.issuer != b.issuer) return false
        if (a.amount != b.amount || a.kind != b.kind || (a.cancelOf == null) != (b.cancelOf == null)) return false
        if (abs(a.at - b.at) > WINDOW) return false
        val la = a.cardLabel?.let { digits.find(it)?.groupValues?.get(1) }
        val lb = b.cardLabel?.let { digits.find(it)?.groupValues?.get(1) }
        if (la != null && lb != null && la != lb) return false
        if (a.merchant.isNotBlank() && b.merchant.isNotBlank()) {
            val ma = Categorizer.normalize(a.merchant)
            val mb = Categorizer.normalize(b.merchant)
            if (!ma.startsWith(mb) && !mb.startsWith(ma)) return false
        }
        return true
    }

    /** 둘 중 남길 것: 먼저 기록된 쪽(카드 주인 폰이 대개 먼저 받음), 같으면 id가 작은 쪽. */
    fun keeper(a: Txn, b: Txn): Txn =
        when {
            a.createdAt != b.createdAt && a.createdAt > 0 && b.createdAt > 0 -> if (a.createdAt < b.createdAt) a else b
            else -> if (a.id < b.id) a else b
        }
}
