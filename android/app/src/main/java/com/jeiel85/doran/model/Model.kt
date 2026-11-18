package com.jeiel85.doran.model

import org.json.JSONArray
import org.json.JSONObject

/*
 * 장부 엔티티. 로컬 DB와 동기화 op가 같은 JSON을 씁니다 (DECISIONS.md D-2).
 * 새 필드는 기본값을 두어 옛 op도 읽히게 하고, 저장할 때는 옛 JSON 위에 덮어써서
 * 새 버전 앱이 넣은 모르는 필드를 지우지 않습니다 (Ledger.put).
 */

object T {
    const val HOUSEHOLD = "household"
    const val MEMBER = "member"
    const val ACCOUNT = "account"
    const val CATEGORY = "category"
    const val TXN = "txn"
    const val BUDGET = "budget"
    const val RECURRING = "recurring"
    const val GOAL = "goal"
    const val RULE = "rule"
    const val ALLOWANCE = "allowance"
    const val LINK = "link"
    val ALL = listOf(HOUSEHOLD, MEMBER, ACCOUNT, CATEGORY, TXN, BUDGET, RECURRING, GOAL, RULE, ALLOWANCE, LINK)
}

enum class Kind(val code: String) {
    EXPENSE("e"), INCOME("i"), TRANSFER("t");
    companion object { fun of(c: String?) = entries.firstOrNull { it.code == c } ?: EXPENSE }
}

enum class AccountType(val code: String, val label: String, val asset: Boolean) {
    CASH("cash", "현금", true),
    BANK("bank", "입출금 계좌", true),
    SAVINGS("save", "예금·적금", true),
    INVEST("invest", "투자", true),
    POINT("point", "포인트·페이", true),
    CARD("card", "신용카드", false),
    CHECK("check", "체크카드", true),
    LOAN("loan", "대출", false);
    companion object { fun of(c: String?) = entries.firstOrNull { it.code == c } ?: BANK }
}

enum class Source(val code: String, val label: String) {
    MANUAL("manual", "직접 입력"), PUSH("push", "앱 알림"), SMS("sms", "문자"),
    RECURRING("recurring", "고정지출"), FAMILY("family", "가족이 보냄"), IMPORT("import", "가져옴");
    val captured get() = this == PUSH || this == SMS || this == FAMILY
    companion object { fun of(c: String?) = entries.firstOrNull { it.code == c } ?: MANUAL }
}

private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { has(k) }
private fun JSONObject.long(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
private fun JSONObject.int(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null
private fun JSONObject.strs(k: String): List<String> =
    optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
private fun JSONObject.putN(k: String, v: Any?): JSONObject = put(k, v ?: JSONObject.NULL)
private fun List<String>.json() = JSONArray(this)

/** 가구 전체 설정. id는 항상 "home". */
data class Household(
    val name: String = "우리집",
    /** 한 달의 시작일(월급날 기준 등). 1~28. */
    val monthStartDay: Int = 1,
    /** 월 전체 예산. 0이면 없음. */
    val budget: Long = 0,
) {
    fun json(): JSONObject = JSONObject().put("name", name).put("monthStartDay", monthStartDay).put("budget", budget)
    companion object {
        const val ID = "home"
        fun from(o: JSONObject) = Household(
            name = o.str("name") ?: "우리집",
            monthStartDay = (o.int("monthStartDay") ?: 1).coerceIn(1, 28),
            budget = o.long("budget") ?: 0,
        )
    }
}

data class Member(
    val id: String,
    val name: String,
    /** member: 장부를 함께 씀 / sender: 다른 가족 — 보내기만 함 (DECISIONS.md D-6). */
    val role: String = "member",
    val color: Int = 0,
    val deviceIds: List<String> = emptyList(),
    /** 월 용돈. 0이면 용돈 영역을 쓰지 않음 (DECISIONS.md D-7). */
    val allowance: Long = 0,
    val order: Int = 0,
    val removed: Boolean = false,
    /**
     * 공유 범위 (DECISIONS.md D-19). [SHARE_ALL]: 이 사람 폰이 읽은 내역을 모두 가계부에 올림 /
     * [SHARE_PICKED]: [shareIssuers]의 카드사·은행만 올리고 나머지는 본인만 보는 용돈으로 기록(가족에게는 월 합계만).
     * 가구 키로 동기화되어 본인과 다른 가족 모두 바꿀 수 있고, [shareBy]는 마지막으로 바꾼 구성원입니다.
     */
    val share: String = SHARE_ALL,
    val shareIssuers: List<String> = emptyList(),
    val shareBy: String? = null,
) {
    val isSender get() = role == "sender"
    val sharesPicked get() = share == SHARE_PICKED
    /** 이 카드사·은행의 알림을 가계부에 올리는지. */
    fun shares(issuer: String): Boolean = !sharesPicked || shareIssuers.any { it.trim().equals(issuer.trim(), ignoreCase = true) }
    fun json(): JSONObject = JSONObject().put("name", name).put("role", role).put("color", color)
        .put("deviceIds", deviceIds.json()).put("allowance", allowance).put("order", order).put("removed", removed)
        .put("share", share).put("shareIssuers", shareIssuers.json()).putN("shareBy", shareBy)
    companion object {
        const val SHARE_ALL = "all"
        const val SHARE_PICKED = "picked"
        fun from(id: String, o: JSONObject) = Member(
            id = id, name = o.str("name") ?: "가족", role = o.str("role") ?: "member", color = o.int("color") ?: 0,
            deviceIds = o.strs("deviceIds"), allowance = o.long("allowance") ?: 0, order = o.int("order") ?: 0,
            removed = o.optBoolean("removed"),
            share = o.str("share")?.takeIf { it == SHARE_PICKED } ?: SHARE_ALL, shareIssuers = o.strs("shareIssuers"),
            shareBy = o.str("shareBy"),
        )
    }
}

data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val ownerId: String? = null,
    /** 카드사·은행 이름. 알림에서 읽은 내역을 이 계좌로 연결하는 데 씁니다. */
    val institution: String? = null,
    val last4: String? = null,
    val opening: Long = 0,
    val openingAt: Long = 0,
    /** 은행 알림의 "잔액"으로 맞춘 기준점. 이 시각 이후 내역만 더해 현재 잔액을 계산합니다. */
    val anchor: Long? = null,
    val anchorAt: Long? = null,
    /** 카드 결제일(1~31)과 이용 기간 시작일(전월 N일 ~ 당월 N-1일). */
    val billingDay: Int? = null,
    val cycleStartDay: Int? = null,
    val payFromId: String? = null,
    /** 용돈 카드: 이 계좌로 들어온 내역은 개인 용돈으로 처리합니다. */
    val allowanceCard: Boolean = false,
    val color: Int = 0,
    val order: Int = 0,
    val hidden: Boolean = false,
) {
    fun json(): JSONObject = JSONObject().put("name", name).put("type", type.code).putN("ownerId", ownerId)
        .putN("institution", institution).putN("last4", last4).put("opening", opening).put("openingAt", openingAt)
        .putN("anchor", anchor).putN("anchorAt", anchorAt).putN("billingDay", billingDay)
        .putN("cycleStartDay", cycleStartDay).putN("payFromId", payFromId).put("allowanceCard", allowanceCard)
        .put("color", color).put("order", order).put("hidden", hidden)
    companion object {
        fun from(id: String, o: JSONObject) = Account(
            id = id, name = o.str("name") ?: "계좌", type = AccountType.of(o.str("type")), ownerId = o.str("ownerId"),
            institution = o.str("institution"), last4 = o.str("last4"), opening = o.long("opening") ?: 0,
            openingAt = o.long("openingAt") ?: 0, anchor = o.long("anchor"), anchorAt = o.long("anchorAt"),
            billingDay = o.int("billingDay"), cycleStartDay = o.int("cycleStartDay"), payFromId = o.str("payFromId"),
            allowanceCard = o.optBoolean("allowanceCard"), color = o.int("color") ?: 0, order = o.int("order") ?: 0,
            hidden = o.optBoolean("hidden"),
        )
    }
}

data class Category(
    val id: String,
    val name: String,
    val kind: Kind,
    /** design/art에서 만든 그림 이름(art_cat_*). */
    val icon: String,
    val color: Int = 0,
    val order: Int = 0,
    val hidden: Boolean = false,
) {
    fun json(): JSONObject = JSONObject().put("name", name).put("kind", kind.code).put("icon", icon)
        .put("color", color).put("order", order).put("hidden", hidden)
    companion object {
        fun from(id: String, o: JSONObject) = Category(
            id = id, name = o.str("name") ?: "기타", kind = Kind.of(o.str("kind")), icon = o.str("icon") ?: "etc",
            color = o.int("color") ?: 0, order = o.int("order") ?: 0, hidden = o.optBoolean("hidden"),
        )
    }
}

data class Txn(
    val id: String,
    val kind: Kind,
    /** 원 단위, 항상 0 이상. 방향은 kind와 cancelOf가 정합니다. */
    val amount: Long,
    val at: Long,
    val accountId: String? = null,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val merchant: String = "",
    val memo: String = "",
    val memberId: String? = null,
    val source: Source = Source.MANUAL,
    /** 할부 개월 수. 0·1 = 일시불. */
    val installment: Int = 0,
    /** 이 거래가 취소하는 원래 거래 (DECISIONS.md D-5). */
    val cancelOf: String? = null,
    /** 카드 알림에 적힌 카드사 누적 이용액. */
    val cumulative: Long? = null,
    val issuer: String? = null,
    val cardLabel: String? = null,
    /** 해외 결제 원래 금액, 예: "USD 12.34". 원화 확정 전이면 needsReview. */
    val foreign: String? = null,
    val needsReview: Boolean = false,
    val receiptIds: List<String> = emptyList(),
    /** 개인 용돈 — 개인 키로 암호화돼 다른 구성원에게는 가지 않습니다 (DECISIONS.md D-7). */
    val private: Boolean = false,
    val recurringId: String? = null,
    val createdBy: String? = null,
    val createdAt: Long = 0,
) {
    val isCancel get() = cancelOf != null
    fun json(): JSONObject = JSONObject().put("kind", kind.code).put("amount", amount).put("at", at)
        .putN("accountId", accountId).putN("toAccountId", toAccountId).putN("categoryId", categoryId)
        .put("merchant", merchant).put("memo", memo).putN("memberId", memberId).put("source", source.code)
        .put("installment", installment).putN("cancelOf", cancelOf).putN("cumulative", cumulative)
        .putN("issuer", issuer).putN("cardLabel", cardLabel).putN("foreign", foreign).put("needsReview", needsReview)
        .put("receiptIds", receiptIds.json()).put("private", private).putN("recurringId", recurringId)
        .putN("createdBy", createdBy).put("createdAt", createdAt)
    companion object {
        fun from(id: String, o: JSONObject) = Txn(
            id = id, kind = Kind.of(o.str("kind")), amount = o.long("amount") ?: 0, at = o.long("at") ?: 0,
            accountId = o.str("accountId"), toAccountId = o.str("toAccountId"), categoryId = o.str("categoryId"),
            merchant = o.str("merchant") ?: "", memo = o.str("memo") ?: "", memberId = o.str("memberId"),
            source = Source.of(o.str("source")), installment = o.int("installment") ?: 0, cancelOf = o.str("cancelOf"),
            cumulative = o.long("cumulative"), issuer = o.str("issuer"), cardLabel = o.str("cardLabel"),
            foreign = o.str("foreign"), needsReview = o.optBoolean("needsReview"), receiptIds = o.strs("receiptIds"),
            private = o.optBoolean("private"), recurringId = o.str("recurringId"), createdBy = o.str("createdBy"),
            createdAt = o.long("createdAt") ?: 0,
        )
    }
}

/** 카테고리별 월 예산 (매달 같은 금액). id = "b.<categoryId>". 전체 예산은 Household.budget. */
data class Budget(val id: String, val categoryId: String, val amount: Long) {
    fun json(): JSONObject = JSONObject().put("categoryId", categoryId).put("amount", amount)
    companion object {
        fun idFor(categoryId: String) = "b.$categoryId"
        fun from(id: String, o: JSONObject) = Budget(id, o.str("categoryId") ?: "", o.long("amount") ?: 0)
    }
}

/** 고정지출·구독·고정수입. 반복 주기는 매월 n일 / 매주 요일 / 매년 m월 n일. */
data class Recurring(
    val id: String,
    val name: String,
    val kind: Kind,
    val amount: Long,
    val categoryId: String?,
    val accountId: String?,
    val memberId: String?,
    /** "m" 매월 day, "w" 매주 day(1=월…7=일), "y" 매년 month/day. */
    val period: String = "m",
    val day: Int = 1,
    val month: Int = 1,
    val startAt: Long,
    val endAt: Long? = null,
    val paused: Boolean = false,
) {
    fun json(): JSONObject = JSONObject().put("name", name).put("kind", kind.code).put("amount", amount)
        .putN("categoryId", categoryId).putN("accountId", accountId).putN("memberId", memberId).put("period", period)
        .put("day", day).put("month", month).put("startAt", startAt).putN("endAt", endAt).put("paused", paused)
    companion object {
        fun from(id: String, o: JSONObject) = Recurring(
            id = id, name = o.str("name") ?: "", kind = Kind.of(o.str("kind")), amount = o.long("amount") ?: 0,
            categoryId = o.str("categoryId"), accountId = o.str("accountId"), memberId = o.str("memberId"),
            period = o.str("period") ?: "m", day = o.int("day") ?: 1, month = o.int("month") ?: 1,
            startAt = o.long("startAt") ?: 0, endAt = o.long("endAt"), paused = o.optBoolean("paused"),
        )
    }
}

data class GoalEntry(val at: Long, val amount: Long, val memo: String = "")

/** 저축 목표. 계좌를 연결하면 그 잔액이 모은 돈이고, 아니면 직접 기록한 금액의 합입니다. */
data class Goal(
    val id: String,
    val name: String,
    val target: Long,
    val icon: String = "savings",
    val color: Int = 0,
    val accountId: String? = null,
    val entries: List<GoalEntry> = emptyList(),
    val deadline: Long? = null,
    val done: Boolean = false,
    val order: Int = 0,
) {
    fun json(): JSONObject = JSONObject().put("name", name).put("target", target).put("icon", icon).put("color", color)
        .putN("accountId", accountId).putN("deadline", deadline).put("done", done).put("order", order)
        .put("entries", JSONArray(entries.map { JSONObject().put("at", it.at).put("amount", it.amount).put("memo", it.memo) }))
    companion object {
        fun from(id: String, o: JSONObject) = Goal(
            id = id, name = o.str("name") ?: "목표", target = o.long("target") ?: 0, icon = o.str("icon") ?: "savings",
            color = o.int("color") ?: 0, accountId = o.str("accountId"), deadline = o.long("deadline"),
            done = o.optBoolean("done"), order = o.int("order") ?: 0,
            entries = o.optJSONArray("entries")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }
                    .map { GoalEntry(it.optLong("at"), it.optLong("amount"), it.optString("memo")) }
            } ?: emptyList(),
        )
    }
}

/** 가맹점 → 카테고리 학습 규칙. id = "r.<정규화한 가맹점>". */
data class MerchantRule(val id: String, val merchant: String, val categoryId: String) {
    fun json(): JSONObject = JSONObject().put("merchant", merchant).put("categoryId", categoryId)
    companion object {
        fun from(id: String, o: JSONObject) = MerchantRule(id, o.str("merchant") ?: "", o.str("categoryId") ?: "")
    }
}

/** 개인 용돈 월 요약 — 배우자 폰에는 이것만 갑니다. id = "a.<memberId>.<yyyyMM>". */
data class AllowanceSummary(val id: String, val memberId: String, val month: String, val spent: Long, val count: Int) {
    fun json(): JSONObject = JSONObject().put("memberId", memberId).put("month", month).put("spent", spent).put("count", count)
    companion object {
        fun idFor(memberId: String, month: String) = "a.$memberId.$month"
        fun from(id: String, o: JSONObject) =
            AllowanceSummary(id, o.str("memberId") ?: "", o.str("month") ?: "", o.long("spent") ?: 0, o.int("count") ?: 0)
    }
}

/** 보내기 링크(다른 가족 → 우리 가구). 키는 가구 키로 암호화된 장부 안에만 있습니다. */
data class Link(val id: String, val memberId: String, val key: String, val createdAt: Long, val revoked: Boolean = false) {
    fun json(): JSONObject = JSONObject().put("memberId", memberId).put("key", key).put("createdAt", createdAt)
        .put("revoked", revoked)
    companion object {
        fun from(id: String, o: JSONObject) =
            Link(id, o.str("memberId") ?: "", o.str("key") ?: "", o.long("createdAt") ?: 0, o.optBoolean("revoked"))
    }
}
