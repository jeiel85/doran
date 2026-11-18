package com.jeiel85.doran.capture

import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.newId
import com.jeiel85.doran.data.Db
import com.jeiel85.doran.data.Identity
import com.jeiel85.doran.data.Ledger
import com.jeiel85.doran.data.LedgerState
import com.jeiel85.doran.data.LocalSettings
import com.jeiel85.doran.data.OutLink
import com.jeiel85.doran.logic.Twins
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AccountType
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.T
import com.jeiel85.doran.model.Txn
import com.jeiel85.doran.sync.SyncEngine
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * 읽은 알림 → 장부 거래 (DECISIONS.md D-4·D-5·D-6).
 * 내 가구에는 바로 등록하고, 보내기 링크가 있으면 그 가구로도 링크 키로 봉해 보냅니다.
 */
class CaptureProcessor(
    private val db: Db,
    private val ledger: Ledger,
    private val identity: Identity,
    private val settings: LocalSettings,
    private val sync: SyncEngine,
    private val requestSync: () -> Unit,
) {
    data class Outcome(val result: String, val txnId: String?)

    private val sameMinutes = 3 * 60_000L

    @Synchronized
    fun process(source: Source, pkg: String?, title: String?, text: String, postedAt: Long, appLabel: String? = null): Outcome? {
        val body = listOfNotNull(title, text).joinToString("\n")
        val rule = settings.ruleFor(source.code, pkg)
        val custom = rule?.let { Issuer(it.issuer, it.card, listOf(it.issuer)) }
        val parsed = Parser.parse(body, Fmt.dateTime(postedAt), appLabel, custom)
        if (parsed == null) {
            // 금액이 적힌데 형식을 몰라 못 읽은 알림은 "최근 받은 알림"에서 골라 보낼 수 있게 남깁니다(이 폰에만, 30일).
            if (Parser.looksFinancial(body) && !db.hasCandidate(source.code, pkg, text)) {
                db.logCapture(postedAt, source.code, pkg, title, text, "기록 안 함 · 골라서 보낼 수 있어요", null, status = "candidate",
                    label = appLabel ?: pkg)
                db.trimCandidates(source.code, pkg, keep = 30)
            }
            return null
        }
        return deliver(parsed, source, pkg, title, text, postedAt, appLabel, logId = null)
    }

    /**
     * 사용자가 고른 알림을 보냅니다. [remember]면 같은 발신처의 금액 알림을 앞으로 [parsed]의 카드사로 읽어 자동으로 보냅니다.
     */
    @Synchronized
    fun sendPicked(row: Db.CaptureRow, parsed: Parsed, remember: Boolean): Outcome {
        check(identity.value.inHousehold || identity.value.outLinks.isNotEmpty()) { "연결된 가계부가 없어요" }
        val from = row.pkg
        if (remember && from != null) {
            settings.saveRule(com.jeiel85.doran.data.ForwardRule(
                com.jeiel85.doran.data.ForwardRule.key(row.source, from), row.label ?: from, parsed.issuer, parsed.isCard))
            // "앞으로도 자동으로 보내기"인데 공유 범위 밖 카드사면, 다음부터 용돈으로 빠지지 않게 공유 범위에 넣습니다(D-19).
            ledger.value.member(identity.value.myMemberId)?.takeIf { !it.shares(parsed.issuer) }?.let { m ->
                ledger.saveMember(m.copy(shareIssuers = m.shareIssuers + parsed.issuer, shareBy = m.id))
            }
        }
        // 직접 골라 보낸 것은 공유 범위와 관계없이 가계부에 올립니다.
        return deliver(parsed, Source.of(row.source), from, row.title, row.text, row.at, row.label, logId = row.id, forceShare = true)
    }

    /** 고른 알림을 지금 해석해 보여 줄 초기값. 카드사를 모르면 앱 이름(없으면 "카드")으로 둡니다. */
    fun previewPicked(row: Db.CaptureRow): Parsed? {
        val body = listOfNotNull(row.title, row.text).joinToString("\n")
        val name = settings.ruleFor(row.source, row.pkg)?.issuer ?: row.label?.takeIf { it.isNotBlank() && it != row.pkg } ?: "카드"
        return Parser.parse(body, Fmt.dateTime(row.at), row.label, Issuer(name, true, listOf(name)))
    }

    private fun deliver(parsed: Parsed, source: Source, pkg: String?, title: String?, text: String, postedAt: Long,
                        appLabel: String?, logId: Long?, forceShare: Boolean = false): Outcome {
        val at = parsed.at?.let { Fmt.millis(it) }?.takeIf { abs(it - postedAt) < 3L * 86_400_000 } ?: postedAt
        val results = ArrayList<String>()
        var txnId: String? = null
        if (identity.value.inHousehold) {
            val me = ledger.value.member(identity.value.myMemberId)
            val r = if (!forceShare && me != null && !me.shares(parsed.issuer)) recordPrivate(parsed, source, at, me.id)
                else recordLocal(parsed, source, at)
            results += r.result
            txnId = r.txnId
        }
        for (link in identity.value.outLinks) {
            if (link.issuers.isNotEmpty() && parsed.issuer !in link.issuers) continue
            results += forward(parsed, at, link)
        }
        if (results.isEmpty()) results += "가계부가 없어 기록하지 않음"
        if (logId != null) db.updateCapture(logId, "done", results.joinToString(" · "), txnId)
        else db.logCapture(postedAt, source.code, pkg, title, text, results.joinToString(" · "), txnId, label = appLabel)
        requestSync()
        return Outcome(results.joinToString(" · "), txnId)
    }

    // --- 내 가구에 기록 -------------------------------------------------------------------------

    private fun recordLocal(p: Parsed, source: Source, at: Long): Outcome {
        val me = identity.value.myMemberId
        val s = ledger.value
        val account = accountFor(s, p, me)
        val label = p.issuer + (p.last4?.let { "($it)" } ?: "")

        p.balance?.let { bal ->
            ledger.saveAccount(account.copy(anchor = bal, anchorAt = at))
        }

        // 같은 승인이 앱 알림과 문자로 두 번 온 경우 (DECISIONS.md D-5).
        val kind = when (p.type) {
            Parsed.Type.APPROVAL, Parsed.Type.CANCEL -> Kind.EXPENSE
            Parsed.Type.DEPOSIT -> Kind.INCOME
            Parsed.Type.WITHDRAWAL -> if (p.cardPaymentTo != null) Kind.TRANSFER else Kind.EXPENSE
        }
        val isCancel = p.type == Parsed.Type.CANCEL
        val dup = ledger.value.txns.values.firstOrNull {
            it.accountId == account.id && it.amount == p.amount && it.kind == kind && (it.cancelOf != null) == isCancel &&
                abs(it.at - at) <= sameMinutes && it.source.captured
        }
        if (dup != null) {
            if (dup.cumulative == null && p.cumulative != null || dup.merchant.isBlank() && !p.merchant.isNullOrBlank()) {
                ledger.saveTxn(dup.copy(cumulative = dup.cumulative ?: p.cumulative, merchant = dup.merchant.ifBlank { p.merchant.orEmpty() }))
            }
            return Outcome("이미 기록됨(중복 알림)", dup.id)
        }
        // 다른 가족 폰이 같은 결제를 이미 올려 둔 경우(가족카드 알림이 양쪽에 오는 등)
        val probe = Txn(id = "probe", kind = kind, amount = p.amount, at = at, merchant = p.merchant.orEmpty(), source = source,
            issuer = p.issuer, cardLabel = label, cancelOf = if (isCancel) "?" else null)
        ledger.value.txns.values.firstOrNull { it.memberId != me && Twins.same(it, probe) }?.let {
            return Outcome("가족 폰이 이미 기록함", it.id)
        }

        val txn: Txn = when (p.type) {
            Parsed.Type.CANCEL -> {
                val original = findOriginal(ledger.value, account.id, p, at)
                if (original != null) {
                    base(p, source, at, me, account, label).copy(kind = Kind.EXPENSE, cancelOf = original.id,
                        categoryId = original.categoryId, merchant = p.merchant ?: original.merchant)
                } else {
                    base(p, source, at, me, account, label).copy(kind = Kind.INCOME, categoryId = "c.income_etc",
                        memo = "원래 승인을 찾지 못한 취소 — 확인해 주세요", needsReview = true)
                }
            }
            Parsed.Type.WITHDRAWAL -> {
                val cardIssuer = p.cardPaymentTo
                if (cardIssuer != null) {
                    val card = cardAccount(ledger.value, cardIssuer, null, me)
                    base(p, source, at, me, account, label).copy(kind = Kind.TRANSFER, toAccountId = card.id, categoryId = null,
                        merchant = "$cardIssuer 카드값")
                } else base(p, source, at, me, account, label).copy(kind = Kind.EXPENSE)
            }
            Parsed.Type.DEPOSIT -> base(p, source, at, me, account, label).copy(kind = Kind.INCOME)
            Parsed.Type.APPROVAL -> base(p, source, at, me, account, label).copy(kind = Kind.EXPENSE)
        }.let { t ->
            if (t.categoryId == null && t.kind != Kind.TRANSFER && t.cancelOf == null) {
                t.copy(categoryId = Categorizer.categorize(ledger.value, t.merchant, t.kind))
            } else t
        }

        // 고정지출로 미리 잡아 둔 같은 금액이 있으면 새로 만들지 않고 그 자리를 채웁니다.
        val planned = if (txn.kind == Kind.EXPENSE && txn.cancelOf == null) ledger.value.txns.values.firstOrNull {
            it.source == Source.RECURRING && it.amount == txn.amount && abs(it.at - at) <= 3L * 86_400_000 &&
                (it.accountId == null || it.accountId == account.id)
        } else null
        val saved = if (planned != null) txn.copy(id = planned.id, recurringId = planned.recurringId,
            categoryId = planned.categoryId ?: txn.categoryId, memo = planned.memo.ifBlank { txn.memo }) else txn
        ledger.saveTxn(saved)
        settings.markUnseen(listOf(saved.id))
        return Outcome(if (planned != null) "고정지출과 맞춤" else "기록함", saved.id)
    }

    // --- 공유 범위 밖: 나만 보는 용돈으로 (DECISIONS.md D-19) ------------------------------------------

    /**
     * 공유 범위 밖 카드사·은행의 알림. 가족이 그 카드·계좌의 존재나 잔액을 알 수 없게 계좌를 만들거나 잔액을 고치지 않고,
     * 개인 키로 봉하는 용돈 거래(private)로만 남깁니다. 가족에게는 용돈 월 합계만 보입니다(D-7).
     */
    private fun recordPrivate(p: Parsed, source: Source, at: Long, me: String): Outcome {
        // 내 계좌에서 내 카드값이 나가는 것은 지출이 아니라 이체라, 계좌 없이 남기면 같은 돈을 두 번 센 셈이 됩니다.
        if (p.cardPaymentTo != null) return Outcome("공유 범위 밖 카드값 출금 · 기록 안 함", null)
        val isCancel = p.type == Parsed.Type.CANCEL
        val kind = if (p.type == Parsed.Type.DEPOSIT) Kind.INCOME else Kind.EXPENSE
        val mine = ledger.value.txns.values.filter { it.private && it.memberId == me && it.issuer == p.issuer }
        mine.firstOrNull {
            it.amount == p.amount && it.kind == kind && (it.cancelOf != null) == isCancel && abs(it.at - at) <= sameMinutes && it.source.captured
        }?.let { return Outcome("이미 기록됨(중복 알림)", it.id) }

        val first = Txn(
            id = newId(), kind = kind, amount = p.amount, at = at, accountId = null, merchant = p.merchant.orEmpty(),
            memberId = me, source = source, installment = p.installment, cumulative = p.cumulative, issuer = p.issuer,
            cardLabel = p.issuer + (p.last4?.let { "($it)" } ?: ""), foreign = p.foreign, needsReview = p.amount == 0L,
            private = true, createdBy = identity.installId, createdAt = System.currentTimeMillis(),
        )
        val txn = if (isCancel) {
            val original = pickOriginal(ledger.value, mine.asSequence(), p, at)
            if (original != null) first.copy(cancelOf = original.id, categoryId = original.categoryId, merchant = p.merchant ?: original.merchant)
            else first.copy(kind = Kind.INCOME, categoryId = "c.income_etc", memo = "원래 승인을 찾지 못한 취소 — 확인해 주세요", needsReview = true)
        } else first.copy(categoryId = Categorizer.categorize(ledger.value, first.merchant, kind))
        ledger.saveTxn(txn)
        settings.markUnseen(listOf(txn.id))
        return Outcome("나만 보는 용돈으로 기록(공유 범위 밖)", txn.id)
    }

    private fun base(p: Parsed, source: Source, at: Long, me: String?, account: Account, label: String) = Txn(
        id = newId(), kind = Kind.EXPENSE, amount = p.amount, at = at, accountId = account.id, merchant = p.merchant.orEmpty(),
        memberId = me, source = source, installment = p.installment, cumulative = p.cumulative, issuer = p.issuer,
        cardLabel = label, foreign = p.foreign, needsReview = p.amount == 0L, private = account.allowanceCard,
        createdBy = identity.installId, createdAt = System.currentTimeMillis(),
    )

    private fun findOriginal(s: LedgerState, accountId: String, p: Parsed, at: Long): Txn? =
        pickOriginal(s, s.txns.values.asSequence().filter { it.accountId == accountId }, p, at)

    /** 취소 알림이 가리키는 원래 승인: 60일 안, 남은 금액이 취소액 이상, 가맹점·금액이 같은 것 우선. */
    private fun pickOriginal(s: LedgerState, candidates: Sequence<Txn>, p: Parsed, at: Long): Txn? {
        val canceled = s.canceled
        return candidates
            .filter { it.kind == Kind.EXPENSE && it.cancelOf == null }
            .filter { at - it.at in 0..60L * 86_400_000 && it.amount - (canceled[it.id] ?: 0) >= p.amount }
            .sortedWith(compareByDescending<Txn> { p.merchant != null && it.merchant == p.merchant }
                .thenByDescending { it.amount == p.amount }.thenByDescending { it.at })
            .firstOrNull()
    }

    /** 알림의 카드사·은행으로 계좌를 찾고, 없으면 만들어 둡니다(자산 화면에 바로 보이게). */
    private fun accountFor(s: LedgerState, p: Parsed, me: String?): Account =
        if (p.isCard) cardAccount(s, p.issuer, p.last4, me)
        else s.accounts.values.firstOrNull { it.institution == p.issuer && it.type == AccountType.BANK && (it.ownerId == me || it.ownerId == null) }
            // 알림으로 처음 만든 계좌는 기준 시각을 0으로 둡니다: 알림의 거래 시각이 계좌를 만든 순간보다 조금 이를 수 있어서.
            ?: Account(id = newId(), name = p.issuer, type = AccountType.BANK, ownerId = me, institution = p.issuer,
                openingAt = 0, order = s.accounts.size).also { ledger.saveAccount(it) }

    private fun cardAccount(s: LedgerState, issuer: String, last4: String?, me: String?): Account {
        val mine = s.accounts.values.filter {
            it.institution == issuer && (it.type == AccountType.CARD || it.type == AccountType.CHECK) && (it.ownerId == me || it.ownerId == null)
        }
        mine.firstOrNull { last4 != null && it.last4 == last4 }?.let { return it }
        mine.firstOrNull { it.last4 == null || last4 == null }?.let { a ->
            return if (a.last4 == null && last4 != null) a.copy(last4 = last4).also { ledger.saveAccount(it) } else a
        }
        // 정한 결제만 보내는 사람의 보내는 카드는 가족이 함께 쓰는 카드라 처음부터 가족 카드로(D-20).
        val family = s.member(me)?.let { it.sharesPicked && it.shares(issuer) } == true
        return Account(id = newId(), name = issuer + (last4?.let { " $it" } ?: ""), type = AccountType.CARD, ownerId = me,
            institution = issuer, last4 = last4, cycleStartDay = 1, openingAt = 0, order = s.accounts.size, family = family)
            .also { ledger.saveAccount(it) }
    }

    // --- 다른 가구로 보내기 (부모님 폰 → 우리 가구) ----------------------------------------------

    private fun forward(p: Parsed, at: Long, link: OutLink): String {
        val recentKey = "fwd.recent.${link.spaceId}"
        val recent = db.kv(recentKey)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        val items = (0 until recent.length()).map { recent.getJSONObject(it) }
        val isCancel = p.type == Parsed.Type.CANCEL
        if (items.any { it.optString("issuer") == p.issuer && it.optLong("amount") == p.amount &&
                it.optBoolean("cancel") == isCancel && abs(it.optLong("at") - at) <= sameMinutes }) {
            return "${link.label}: 이미 보냄"
        }
        val accountId = "acc.${link.memberId}.${Categorizer.normalize(p.issuer)}"
        val kind = when (p.type) {
            Parsed.Type.DEPOSIT -> Kind.INCOME
            else -> Kind.EXPENSE
        }
        val original = if (isCancel) items.filter {
            it.optString("issuer") == p.issuer && !it.optBoolean("cancel") && it.optLong("amount") >= p.amount && at - it.optLong("at") in 0..60L * 86_400_000
        }.maxByOrNull { (if (it.optString("merchant") == p.merchant) 1_000_000_000_000L else 0L) + it.optLong("at") } else null
        if (isCancel && original == null) return "${link.label}: 원래 승인을 찾지 못해 보내지 않음"

        val txn = Txn(
            id = newId(), kind = kind, amount = p.amount, at = at, accountId = accountId, merchant = p.merchant.orEmpty(),
            memberId = link.memberId, source = Source.FAMILY, installment = p.installment, cumulative = p.cumulative,
            issuer = p.issuer, cardLabel = p.issuer + (p.last4?.let { "($it)" } ?: ""), foreign = p.foreign,
            needsReview = p.amount == 0L, cancelOf = original?.optString("id"),
            categoryId = if (isCancel) null else Categorizer.categorize(LedgerState(categories = ledger.value.categories), p.merchant, kind),
            createdBy = identity.installId, createdAt = System.currentTimeMillis(),
        )
        val account = Account(id = accountId, name = p.issuer, type = if (p.isCard) AccountType.CARD else AccountType.BANK,
            ownerId = link.memberId, institution = p.issuer, cycleStartDay = 1)
        for ((type, id, json) in listOf(Triple(T.ACCOUNT, accountId, account.json()), Triple(T.TXN, txn.id, txn.json()))) {
            val h = ledger.hlc.next()
            val op = sync.seal(link.spaceId, link.key, type, id, h, false, json)
            db.addForward(link.spaceId, op.ek, op.h, op.env)
        }
        val entry = JSONObject().put("id", txn.id).put("issuer", p.issuer).put("amount", p.amount).put("at", at)
            .put("cancel", isCancel).put("merchant", p.merchant ?: "")
        val keep = (items + entry).takeLast(300)
        db.setKv(recentKey, JSONArray(keep).toString())
        return "${link.label}에 보냄"
    }

    companion object {
        /** 시험용: 지금 시각을 넣어 해석만 해 봅니다(설정 화면 "문구 시험"). */
        fun preview(text: String): Parsed? = Parser.parse(text, LocalDateTime.now())
    }
}
