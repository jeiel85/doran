package com.jeiel85.doran.logic

import com.jeiel85.doran.capture.Categorizer
import com.jeiel85.doran.capture.Issuers
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import org.json.JSONArray
import org.json.JSONObject
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
 * 내 폰이 읽은 결제가 '나만 보는 용돈'으로 기록됐을 때 내 폰에 띄우는 알림 (DECISIONS.md D-20).
 * "용돈에서 9,900원 썼어요 / 구글플레이 · 토스뱅크 · 남은 용돈 240,100원"
 */
object AllowanceAlert {
    private const val RECENT = 86_400_000L

    /** 알릴 거래: 이 폰([installId])이 알림·문자에서 읽어 [me]의 용돈으로 새로 기록한 결제와 그 취소. 입금은 은행 알림으로 충분해 빼요. */
    fun applies(t: Txn, me: String, installId: String, now: Long): Boolean =
        t.private && t.memberId == me && t.createdBy == installId && (t.source == Source.PUSH || t.source == Source.SMS) &&
            t.kind == Kind.EXPENSE && now - t.at <= RECENT

    /**
     * 월 용돈보다 커서 옮긴 돈으로 본 은행 출금([Moves.LARGE])을 알립니다: 용돈에서 빼지 않았으니, 실제로 쓴 돈이면 눌러서 바꾸라고(D-26).
     * "KB국민은행에서 4,044,525원이 나갔어요 / 급여 · 월 용돈보다 커서 옮긴 돈으로 봤어요. 용돈에서 쓴 돈이면 눌러서 바꿔 주세요"
     */
    fun appliesMoved(t: Txn, me: String, installId: String, now: Long): Boolean =
        t.private && t.memberId == me && t.createdBy == installId && (t.source == Source.PUSH || t.source == Source.SMS) &&
            t.kind == Kind.TRANSFER && t.move == Moves.LARGE && now - t.at <= RECENT

    fun moved(t: Txn): FamilyAlert.Text = FamilyAlert.Text(
        "${t.issuer ?: "통장"}에서 ${Josa.iGa(Fmt.won(t.amount))} 나갔어요",
        listOfNotNull(t.merchant.takeIf { it.isNotBlank() }, "월 용돈보다 커서 옮긴 돈으로 봤어요. 용돈에서 쓴 돈이면 눌러서 바꿔 주세요")
            .joinToString(" · "),
    )

    fun of(s: LedgerState, t: Txn): FamilyAlert.Text {
        val won = Fmt.won(t.amount)
        val title = when {
            t.isCancel -> "용돈 결제 ${Josa.iGa(won)} 취소됐어요"
            t.amount == 0L && t.foreign != null -> "용돈에서 해외 ${t.foreign} 결제했어요"
            else -> "용돈에서 $won 썼어요"
        }
        val body = listOfNotNull(t.merchant.takeIf { it.isNotBlank() }, t.issuer, status(s, t)).joinToString(" · ")
        return FamilyAlert.Text(title, body)
    }

    /**
     * 몇 분 안에 잇달아 기록된 여러 건(구독료 여러 개 등)은 알림 하나로(#15): "용돈에서 3건 · 24,300원 썼어요 / 넷플릭스 · 유튜브 · 멜론 · 남은 용돈 …".
     * [txns]는 시각 순. 한 건이면 [of]와 같습니다.
     */
    fun ofMany(s: LedgerState, txns: List<Txn>): FamilyAlert.Text {
        if (txns.size == 1) return of(s, txns.single())
        val net = txns.sumOf { if (it.isCancel) -it.amount else it.amount }
        val title = when {
            txns.all { it.isCancel } -> "용돈 결제 ${txns.size}건이 취소됐어요"
            net > 0 -> "용돈에서 ${txns.size}건 · ${Fmt.won(net)} 썼어요"
            else -> "용돈 결제 ${txns.size}건"
        }
        val names = txns.map { it.merchant.ifBlank { it.issuer ?: "결제" } }.distinct()
        val shown = names.take(3).joinToString(" · ") + if (names.size > 3) " 외 ${names.size - 3}곳" else ""
        return FamilyAlert.Text(title, "$shown · ${status(s, txns.last())}")
    }

    private fun status(s: LedgerState, t: Txn): String {
        val allowance = s.member(t.memberId)?.allowance ?: 0
        // 월 용돈이 있으면 용돈이 들어온 날부터 센 용돈 기간(D-27), 없으면 가계부 기간.
        val p = if (allowance > 0) Stats.allowancePeriod(s, t.memberId, Fmt.date(t.at)) else Stats.periodOf(Fmt.date(t.at), s.household.monthStartDay)
        val spent = Stats.allowanceSpent(s, t.memberId, p)
        return when {
            allowance <= 0 -> "이번 달 용돈 ${Fmt.won(spent)} 썼어요"
            spent <= allowance -> "남은 용돈 ${Fmt.won(allowance - spent)}"
            else -> "용돈을 ${Fmt.won(spent - allowance)} 넘었어요"
        }
    }

    /** 첫 건부터 [WINDOW] 안에 들어온 용돈 결제를 한 알림([key])으로 묶습니다. 이 폰에만 둡니다. */
    data class Group(val key: String, val startedAt: Long, val ids: List<String>) {
        fun json(): JSONObject = JSONObject().put("key", key).put("at", startedAt).put("ids", JSONArray(ids))

        companion object {
            fun from(o: JSONObject) = Group(o.getString("key"), o.getLong("at"),
                o.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } })
        }
    }

    const val WINDOW = 5 * 60_000L

    fun group(prev: Group?, ids: List<String>, now: Long): Group =
        if (prev != null && now - prev.startedAt in 0..WINDOW) prev.copy(ids = (prev.ids + ids).distinct().takeLast(30))
        else Group(ids.first(), now, ids)
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
        val ma = knownMerchant(a)
        val mb = knownMerchant(b)
        if (ma.isNotBlank() && mb.isNotBlank()) {
            // 한쪽이 간편결제 알림의 가맹점을 앞에 붙였을 수 있어("CU 네이버페이" ↔ "네이버페이") 앞부분 일치가 아니라 포함으로 봅니다.
            // 단 두 글자 이하의 짧은 이름("CU")은 다른 가맹점 이름 안에도 흔히 들어 있어 앞부분 일치만 봅니다(#19).
            if (!merchantsAgree(Categorizer.normalize(ma), Categorizer.normalize(mb))) return false
        }
        return true
    }

    /**
     * 비교할 가맹점. 카드 상품 이름뿐인 것("현대 ZERO")은 가맹점을 모르는 것으로 봅니다: 0.3.4까지는 가맹점이 없는 카드 문자에서
     * 상품 이름을 가맹점으로 읽어, 남편 폰의 "동네주유소"와 아내 폰의 "현대 ZERO"가 다른 결제로 남았습니다(2026-11-21).
     */
    private fun knownMerchant(t: Txn): String = t.merchant.takeUnless { Issuers.cardNameOnly(it, t.issuer) }.orEmpty()

    private fun merchantsAgree(x: String, y: String): Boolean {
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return if (short.length <= 2) long.startsWith(short) else long.contains(short)
    }

    /**
     * 같은 결제라 지운 내 기록 [removed]가 남긴 빈 계좌(#64): 동기화보다 문자가 먼저 오면 이 폰이 그 결제 때문에 카드·통장을 새로 만들고
     * 기록한 뒤, 가족 기록이 들어오면 내 기록만 지워 계좌가 빈 채로 남았어요(아내 앞 빈 "현대카드", 2026-11-21).
     * 알림이 자동으로 만든 모양 그대로인 계좌(이름·잔액·결제일을 손대지 않음)이고 다른 내역이 하나도 없을 때만 돌려줍니다.
     * [s]는 [removed]를 지운 뒤의 장부입니다.
     */
    fun leftoverAccount(s: LedgerState, removed: Txn): com.jeiel85.doran.model.Account? {
        val a = s.account(removed.accountId) ?: return null
        if (a.type != AccountType.CARD && a.type != AccountType.CHECK && a.type != AccountType.BANK) return null
        val autoName = a.institution?.let { it + (a.last4?.let { l -> " $l" } ?: "") }
        val untouched = a.name == autoName && a.opening == 0L && a.openingAt == 0L && a.anchor == null && a.billingDay == null &&
            a.payFromId == null && !a.allowanceCard && !a.hidden
        if (!untouched) return null
        return a.takeIf { s.txns.values.none { t -> t.accountId == a.id || t.toAccountId == a.id } }
    }

    /** 둘 중 남길 것: 먼저 기록된 쪽(카드 주인 폰이 대개 먼저 받음), 같으면 id가 작은 쪽. */
    fun keeper(a: Txn, b: Txn): Txn =
        when {
            a.createdAt != b.createdAt && a.createdAt > 0 && b.createdAt > 0 -> if (a.createdAt < b.createdAt) a else b
            else -> if (a.id < b.id) a else b
        }
}
