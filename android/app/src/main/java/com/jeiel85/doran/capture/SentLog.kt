package com.jeiel85.doran.capture

import com.jeiel85.doran.model.Kind
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * 보내는 가족 폰이 링크로 보낸 결제 기록(링크마다 최근 300건, 이 폰 kv에만 — DECISIONS.md D-6).
 * 같은 결제를 두 번 보내지 않고, 취소 알림이 가리키는 원래 거래를 찾는 데 씁니다.
 *
 * 항목: id(보낸 거래) · issuer · amount · at · cancel · merchant · kind · balance(머니·포인트 결제) · of(취소의 원래 거래)
 * · alias(같은 결제를 다른 카드사·앱 이름으로 가리키는 별칭 — 그 이름으로 오는 취소가 원래 거래를 찾게, #18)
 * · seen(이 결제를 알린 알림들의 경로·표시 [{src, pkg, nid}] — 같은 앱이 따로 올린 같은 금액 알림을 다른 거래로 보내게, [Notices].
 *   보낸 알림과, 이미 보낸 것으로 본 다른 경로의 알림까지 모두. 0.3.8 전 항목엔 없음).
 */
object SentLog {
    private const val WINDOW = 3 * 60_000L
    private const val DAY = 86_400_000L
    private const val CANCEL_WINDOW = 60L * DAY

    fun entry(id: String, p: Parsed, at: Long, kind: Kind, of: String?, route: Notices.Seen? = null): JSONObject = JSONObject()
        .put("id", id).put("issuer", p.issuer).put("amount", p.amount).put("at", at).put("cancel", p.type == Parsed.Type.CANCEL)
        .put("merchant", p.merchant ?: "").put("kind", kind.code).put("balance", p.paidFromBalance).apply {
            if (of != null) put("of", of)
            if (route?.notice != null) put("seen", JSONArray().put(routeJson(route)))
        }

    private fun routeJson(r: Notices.Seen) = JSONObject().put("src", r.source).put("pkg", r.pkg ?: "").put("nid", r.notice)

    private fun routesOf(e: JSONObject): List<Notices.Seen> {
        val a = e.optJSONArray("seen") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Notices.Seen(it.optString("src"), it.optString("pkg").ifEmpty { null }, it.optString("nid").ifEmpty { null }) }
    }

    /**
     * 이미 보낸 결제 [hit]를 지금 알림 [route]도 알렸다고 남깁니다(그 결제의 항목 모두 — 별칭 포함). 문자로 먼저 보낸 결제에 같은 결제의
     * 앱 푸시가 오면 그 푸시도 기억해야, 같은 앱이 따로 올린 다른 결제의 푸시를 이 결제와 다른 것으로 봅니다(#72 리뷰).
     * 남길 것이 없으면 null.
     */
    fun withRoute(items: List<JSONObject>, hit: JSONObject, route: Notices.Seen?): List<JSONObject>? {
        if (route?.notice == null) return null
        val id = hit.optString("id")
        if (items.none { it.optString("id") == id && route !in routesOf(it) }) return null
        return items.map { e ->
            if (e.optString("id") != id || route in routesOf(e)) e
            else JSONObject(e.toString()).put("seen", JSONArray((e.optJSONArray("seen") ?: JSONArray()).toString()).put(routeJson(route)))
        }
    }

    /**
     * 이미 보낸 같은 결제: 같은 카드사의 중복 알림(앱 푸시와 문자 등), 또는 이름이 다른 같은 결제 —
     * 규칙으로 보낸 간편결제 앱(카드사·은행 목록 밖) 알림과 그 결제의 카드·은행 알림(#18). 이름이 다를 때는 지출끼리만,
     * 머니·포인트로 낸 결제는 카드·계좌에서 돈이 나가지 않았으므로 빼고 봅니다(#21 리뷰).
     * [route]: 지금 알림의 경로·표시. 그 결제를 같은 앱·번호의 다른 알림으로 이미 보냈으면 같은 결제가 아닙니다([Notices]).
     */
    fun sameSent(items: List<JSONObject>, p: Parsed, at: Long, route: Notices.Seen? = null): JSONObject? {
        val isCancel = p.type == Parsed.Type.CANCEL
        val crossable = p.type != Parsed.Type.DEPOSIT && p.cardPaymentTo == null && !p.paidFromBalance
        return items.firstOrNull {
            val sent = it.optString("issuer")
            it.optLong("amount") == p.amount && it.optBoolean("cancel") == isCancel && abs(it.optLong("at") - at) <= WINDOW &&
                (sent == p.issuer || crossable && it.optString("kind") == Kind.EXPENSE.code && !it.optBoolean("balance") &&
                    Issuers.listed(sent) != Issuers.listed(p.issuer)) &&
                !Notices.another(routesOf(it), route?.source, route?.pkg, route?.notice)
        }
    }

    /**
     * 이름이 다른 쪽(간편결제 앱 ↔ 카드·은행)으로 이미 보낸 같은 취소: 같은 금액·하루 안, 원래 거래가 이 이름으로도 이어져 있고
     * 아직 이 이름과 짝지어지지 않은 것(#21 리뷰). 3분이 넘게 떨어져 온 부분 취소를 두 번 보내지 않게 합니다.
     * 짝지으면 [withAlias]로 이 이름의 별칭을 남겨, 같은 금액의 다음 부분 취소는 따로 셉니다.
     */
    fun sameCancelSent(items: List<JSONObject>, p: Parsed, at: Long): JSONObject? {
        if (p.type != Parsed.Type.CANCEL || p.paidFromBalance) return null
        return items.filter { e ->
            val of = e.optString("of")
            val sent = e.optString("issuer")
            e.optBoolean("cancel") && !e.optBoolean("alias") && !e.optBoolean("balance") && of.isNotEmpty() &&
                e.optLong("amount") == p.amount && abs(e.optLong("at") - at) <= DAY && sent != p.issuer &&
                Issuers.listed(sent) != Issuers.listed(p.issuer) &&
                items.any { o -> o.optString("id") == of && !o.optBoolean("cancel") && o.optString("issuer") == p.issuer } &&
                items.none { a -> a.optBoolean("alias") && a.optBoolean("cancel") && a.optString("id") == e.optString("id") && a.optString("issuer") == p.issuer }
        }.minByOrNull { abs(it.optLong("at") - at) }
    }

    /**
     * [hit]을 [issuer] 이름으로도 찾게 별칭을 더한 목록. 이미 같은 이름이면 null(바꿀 것 없음).
     * 예: 간편결제 앱 이름으로 보낸 결제에 카드 알림이 겹쳐 보내지 않았으면, 나중에 카드사만 알리는 취소가 그 거래를 찾습니다.
     */
    fun withAlias(items: List<JSONObject>, hit: JSONObject, issuer: String): List<JSONObject>? {
        val id = hit.optString("id")
        val cancel = hit.optBoolean("cancel")
        if (items.any { it.optString("id") == id && it.optString("issuer") == issuer && it.optBoolean("cancel") == cancel }) return null
        return items + JSONObject(hit.toString()).put("issuer", issuer).put("alias", true)
    }

    /**
     * 취소 알림 [p]의 원래 승인: 같은 이름(별칭 포함)으로 보낸 60일 안의 승인 중 아직 취소되지 않은 금액이 취소액 이상인 것,
     * 가맹점이 같은 것 → 최근 것. 이미 다른 이름의 알림으로 취소를 보냈으면(같은 취소) 남은 금액이 없어 찾지 않습니다.
     */
    fun original(items: List<JSONObject>, p: Parsed, at: Long): JSONObject? {
        val canceled = items.filter { it.optBoolean("cancel") && !it.optBoolean("alias") && it.optString("of").isNotEmpty() }
            .groupBy { it.optString("of") }.mapValues { (_, l) -> l.sumOf { it.optLong("amount") } }
        return items.filter {
            it.optString("issuer") == p.issuer && !it.optBoolean("cancel") && at - it.optLong("at") in 0..CANCEL_WINDOW &&
                it.optLong("amount") - (canceled[it.optString("id")] ?: 0L) >= p.amount
        }.maxByOrNull { (if (it.optString("merchant") == p.merchant) 1_000_000_000_000L else 0L) + it.optLong("at") }
    }
}
