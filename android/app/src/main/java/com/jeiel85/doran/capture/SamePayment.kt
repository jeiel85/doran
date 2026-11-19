package com.jeiel85.doran.capture

import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.Txn
import kotlin.math.abs

/**
 * 간편결제 앱 알림과 카드·은행 알림이 같은 결제를 따로 알린 경우 한 건으로 묶기 (DECISIONS.md D-5).
 *
 * 예(2026-11-19 사용자 폰, 같은 분): 네이버페이 "CU에서 3,610원 결제되었습니다. (결제 수단: 카드 간편결제)"
 * + 토스 "3,610원 결제 / 토스뱅크 체크카드 | 씨유(CU)가산하이엔드6차점 잔액 540원".
 * 돈은 카드·계좌에서 한 번 나갔으므로 카드·은행 알림 쪽을 기록으로 두고, 간편결제 알림의 가맹점과 앱 이름을 거기에 더합니다.
 * 두 알림은 어느 쪽이 먼저 올지 모르므로 CaptureProcessor가 양쪽 순서를 모두 다룹니다.
 */
object SamePayment {
    /** 같은 결제로 볼 시각 차이: 앱 푸시와 문자 중복(D-5)과 같은 3분. */
    const val WINDOW = 3 * 60_000L

    /** 간편결제 알림을 올린 앱 이름을 모를 때(문자 등). */
    const val UNKNOWN_APP = "간편결제"

    /**
     * 간편결제 알림 [notice](받은 시각 [noticeAt])와 카드·은행 알림 [card](거래 시각 [cardAt])가 같은 결제를 알렸는지.
     * 머니·포인트로 낸 결제는 카드·계좌에서 돈이 나가지 않으므로, 같은 때 같은 금액의 다른 카드 결제와 묶지 않습니다(#19).
     * 짝은 해석기가 아는 카드사·은행이어야 합니다(규칙으로 읽은 다른 간편결제 앱 알림끼리는 묶지 않음).
     */
    fun matches(notice: Parsed, noticeAt: Long, card: Parsed, cardAt: Long): Boolean =
        !notice.paidFromBalance && Issuers.listed(card.issuer) && card.type != Parsed.Type.DEPOSIT && card.cardPaymentTo == null &&
            notice.amount == card.amount && (notice.type == Parsed.Type.CANCEL) == (card.type == Parsed.Type.CANCEL) &&
            abs(noticeAt - cardAt) <= WINDOW

    /** 간편결제 알림이 카드·은행 알림으로 이미 기록된 [t]와 같은 결제인지. */
    fun matches(notice: Parsed, noticeAt: Long, t: Txn): Boolean =
        !notice.paidFromBalance && Issuers.listed(t.issuer) && (t.source == Source.PUSH || t.source == Source.SMS) &&
            t.kind == Kind.EXPENSE && notice.amount == t.amount && (notice.type == Parsed.Type.CANCEL) == (t.cancelOf != null) &&
            abs(noticeAt - t.at) <= WINDOW

    /**
     * "앞으로도 자동으로" 규칙으로 따로 기록된 간편결제 앱 거래 [pay](카드사·은행 목록 밖)가 카드·은행 기록 [card]와 같은 결제인지(#18).
     * 돈은 카드·계좌에서 한 번 나갔으므로 카드 쪽을 남기고 [pay]는 지웁니다.
     */
    fun sameAsCard(pay: Txn, card: Txn): Boolean =
        pay.id != card.id && !Issuers.listed(pay.issuer) && Issuers.listed(card.issuer) &&
            (pay.source == Source.PUSH || pay.source == Source.SMS) && pay.kind == Kind.EXPENSE && card.kind == Kind.EXPENSE &&
            pay.amount == card.amount && pay.isCancel == card.isCancel && abs(pay.at - card.at) <= WINDOW

    /** 규칙으로 기록됐던 간편결제 앱 거래를 묶을 알림 내용으로(가맹점·결제/취소). */
    fun noticeOf(pay: Txn): Parsed = Parsed(type = if (pay.isCancel) Parsed.Type.CANCEL else Parsed.Type.APPROVAL,
        issuer = pay.issuer.orEmpty(), isCard = true, amount = pay.amount, merchant = pay.merchant.ifBlank { null })

    /** 두 가맹점 이름이 겹치는지(한쪽이 다른 쪽을 포함). 같은 금액·시각의 짝이 여럿일 때 고르는 데만 씁니다(#19). */
    fun related(a: String?, b: String?): Boolean {
        val x = squash(a.orEmpty())
        val y = squash(b.orEmpty())
        return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
    }

    /**
     * 카드 알림의 가맹점에 간편결제 알림의 가맹점을 더합니다. 이미 들어 있으면 그대로("씨유(CU)가산하이엔드6차점"),
     * 없으면 앞에 붙입니다(카드 알림이 "네이버페이"뿐일 때 "CU 네이버페이").
     */
    fun merchant(card: String?, pay: String?): String {
        val c = card?.trim().orEmpty()
        val p = pay?.trim().orEmpty()
        if (p.isEmpty()) return c
        if (c.isEmpty()) return p
        val sp = squash(p)
        return if (sp.isEmpty() || squash(c).contains(sp)) c else "$p $c".take(40)
    }

    /** "토스뱅크" + "네이버페이" → "토스뱅크 · 네이버페이". */
    fun label(card: String, via: String?): String = when {
        via.isNullOrBlank() || card.contains(via) -> card
        card.isBlank() -> via
        else -> "$card · $via"
    }

    /** 기록 전: 카드·은행 알림을 먼저 온 간편결제 알림으로 보탭니다. */
    fun enrich(card: Parsed, notice: Parsed, app: String): Parsed =
        card.copy(merchant = merchant(card.merchant, shop(notice, app)).ifEmpty { null }, via = app)

    /** 기록 뒤: 이미 기록된 [t]를 간편결제 알림으로 보탭니다. 자동으로 정한 카테고리만 새 가맹점으로 다시 고릅니다(직접 고친 것은 그대로). */
    fun enrich(s: LedgerState, t: Txn, notice: Parsed, app: String): Txn {
        val merchant = merchant(t.merchant, shop(notice, app))
        // 사람이 카테고리를 고르면 그 가맹점 규칙이 생깁니다(Hooks.learnCategories). 규칙이 있으면 사람의 선택으로 보고 그대로 둡니다.
        val learned = t.merchant.isNotBlank() && Categorizer.rule(s, t.merchant) != null
        val auto = t.cancelOf == null && !learned && t.categoryId == Categorizer.categorize(s, t.merchant, t.kind)
        return t.copy(
            merchant = merchant,
            cardLabel = label(t.cardLabel ?: t.issuer.orEmpty(), app),
            categoryId = if (auto && merchant != t.merchant) Categorizer.categorize(s, merchant, t.kind) else t.categoryId,
        )
    }

    /** 간편결제 알림에서 읽은 가맹점. 앱 이름 자체("네이버페이")는 가맹점이 아닙니다. */
    private fun shop(notice: Parsed, app: String): String? =
        notice.merchant?.takeUnless { m -> squash(m).let { it.isEmpty() || it == squash(app) || it == squash(UNKNOWN_APP) } }

    private fun squash(s: String) = s.uppercase().replace(Regex("""[^0-9A-Z가-힣]"""), "")
}
