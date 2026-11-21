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
    /** [handled]: 이 폰 가계부에 기록했거나(이미 기록된 것 포함) 다른 가계부로 보냈는지. */
    data class Outcome(val result: String, val txnId: String?, val handled: Boolean = txnId != null)

    private val sameMinutes = 3 * 60_000L

    @Synchronized
    fun process(source: Source, pkg: String?, title: String?, text: String, postedAt: Long, appLabel: String? = null): Outcome? {
        val body = listOfNotNull(title, text).joinToString("\n")
        val rule = settings.ruleFor(source.code, pkg)
        val custom = rule?.let { Issuer(it.issuer, it.card, listOf(it.issuer)) }
        val parsed = Parser.parse(body, Fmt.dateTime(postedAt), appLabel, custom)
        if (parsed == null) {
            val unspent = Parser.unspent(body)
            when {
                // 결제·출금 실패, 결제·출금 예정 안내는 돈이 나가지 않았으니 기록하지도, 골라 보낼 후보로 권하지도 않고 이유만 남깁니다(D-4).
                // 같은 알림이 앱 푸시·문자·알림 갱신으로 여러 번 와도 한 번만 남기고(#13), 오탐이면 "그래도 보내기"로 보낼 수 있습니다.
                unspent != null -> if (!db.hasCapture(Db.FAILED, source.code, pkg, text)) {
                    db.logCapture(postedAt, source.code, pkg, title, text, resultOf(unspent), null, status = Db.FAILED, label = appLabel ?: pkg)
                }
                Parser.looksFinancial(body) && !db.hasCapture(Db.CANDIDATE, source.code, pkg, text) -> {
                    // 간편결제 앱 알림이 이미 기록·보낸 카드·은행 결제와 같은 결제면 후보로 남기지 않고 그 기록에 묶습니다(D-5).
                    val app = payApp(source.code, appLabel, title, pkg)
                    val joined = Parser.payNotice(body, Fmt.dateTime(postedAt), app)?.let { joinRecorded(it, postedAt, app) }
                    if (joined != null) {
                        db.logCapture(postedAt, source.code, pkg, title, text, joined.result, joined.txnId, label = appLabel ?: pkg)
                    } else {
                        // 금액이 적힌데 형식을 몰라 못 읽은 알림은 "최근 받은 알림"에서 골라 보낼 수 있게 남깁니다(이 폰에만, 30일).
                        db.logCapture(postedAt, source.code, pkg, title, text, "기록 안 함 · 골라서 보낼 수 있어요", null, status = Db.CANDIDATE,
                            label = appLabel ?: pkg)
                        db.trimCandidates(source.code, pkg, keep = 30)
                    }
                }
            }
            return null
        }
        // "앞으로도 자동으로" 규칙으로 읽은 간편결제 앱 알림(카드사·은행 목록 밖)도, 같은 결제의 카드·은행 기록이 이미 있으면
        // 따로 기록하지 않고 그 기록에 묶습니다(#18, D-5).
        // 앱 이름은 규칙의 이름으로 씁니다: 합친 카드 기록의 표시("토스뱅크 · 네이버페이")로 나중에 그 앱의 취소를 찾기 때문입니다.
        // 보내기 링크는 링크마다 따로 보냅니다: 이미 보낸 같은 결제면 forward가 링크별로 거릅니다(#21 리뷰).
        if (rule != null && parsed.issuer == rule.issuer && !Issuers.listed(parsed.issuer) &&
            (parsed.type == Parsed.Type.APPROVAL || parsed.type == Parsed.Type.CANCEL)) {
            val at = txnAt(parsed, postedAt)
            val joined = joinRecorded(parsed, at, parsed.issuer, links = false)
                ?: if (parsed.type == Parsed.Type.CANCEL) cancelAbsorbed(parsed, at, source) else null
            if (joined != null) {
                val sent = identity.value.outLinks.filter { sendsTo(it, parsed.issuer) }.map { forward(parsed, at, it) }
                val result = (listOf(joined.result) + sent.map { it.text }).joinToString(" · ")
                db.logCapture(postedAt, source.code, pkg, title, text, result, joined.txnId, label = appLabel ?: pkg)
                requestSync()
                return joined.copy(result = result, handled = true)
            }
        }
        // 같은 결제를 알린 간편결제 앱 알림이 먼저 와서 후보로 남아 있으면 그 가맹점·앱 이름을 보태 함께 묶습니다(D-5).
        val notice = pendingNotice(parsed, txnAt(parsed, postedAt))
        val out = deliver(notice?.let { SamePayment.enrich(parsed, it.parsed, it.app) } ?: parsed, source, pkg, title, text, postedAt,
            appLabel, logId = null, note = notice?.let { "${it.app} 알림과 같은 결제" })
        // 실제로 기록하거나 보냈을 때만 묶습니다. 보내지 않는 카드사면 간편결제 알림은 골라 보낼 수 있게 후보로 둡니다.
        if (notice != null && out.handled) {
            out.txnId?.let { joinStored(it, notice.parsed, notice.app) }
            db.updateCapture(notice.row.id, "done", "같은 결제 · ${parsed.issuer} 알림과 묶음", out.txnId)
        }
        out.txnId?.let { absorbPayRecord(it) }
        return out
    }

    /**
     * 카드·은행 기록 [cardId]와 같은 결제를 규칙으로 먼저 따로 기록한 간편결제 앱 거래가 있으면, 그 가맹점·앱 이름을 카드 쪽에 보태고
     * 지웁니다(#18). 같은 돈을 두 번 세지 않게 카드·은행 쪽(돈이 실제로 나간 곳)만 남깁니다. 이 폰이 만든 것만 다룹니다.
     */
    private fun absorbPayRecord(cardId: String) {
        val card = ledger.value.txns[cardId]?.takeIf { it.createdBy == identity.installId && Issuers.listed(it.issuer) } ?: return
        val pay = ledger.value.txns.values
            .filter { it.createdBy == identity.installId && SamePayment.sameAsCard(it, card) }
            // 머니·포인트로 낸 결제였으면 카드·계좌에서 돈이 나가지 않았으니 같은 때 같은 금액이어도 다른 결제입니다(#19).
            .filterNot { p -> db.capturesOf(p.id).any { Parser.paidFromBalance(Parser.payMethodIn(it.text)) } }
            .minByOrNull { abs(it.at - card.at) } ?: return
        joinStored(card.id, SamePayment.noticeOf(pay), pay.issuer.orEmpty())
        // 간편결제 기록에 이미 걸린 취소(앱이 먼저 알린 취소)는 지우지 않고 카드 기록의 취소로 옮깁니다(#21 리뷰).
        val joined = ledger.value.txns[card.id] ?: card
        for (c in ledger.value.txns.values.filter { it.cancelOf == pay.id }) {
            ledger.saveTxn(c.copy(cancelOf = joined.id, accountId = joined.accountId, issuer = joined.issuer, cardLabel = joined.cardLabel,
                memberId = joined.memberId, private = joined.private, categoryId = joined.categoryId), auto = true)
        }
        ledger.deleteTxn(pay.id)
        db.relinkCaptures(pay.id, card.id, "같은 결제 · ${card.issuer} 알림 기록에 묶음")
    }

    /** 수집 기록이 카드·은행 알림인지(간편결제 앱 알림이 아니라): 본문이나 앱 이름에서 아는 카드사·은행을 찾으면. */
    private fun isCardRow(row: Db.CaptureRow): Boolean =
        (Issuers.find(listOfNotNull(row.title, row.text).joinToString("\n")) ?: row.label?.let { Issuers.find(it) }) != null

    /** 취소 [c]가 이미 간편결제 앱 알림과 짝지어졌는지 / 카드·은행 알림과 짝지어졌는지(그 알림의 수집 기록이 이어져 있는지). */
    private fun appPaired(c: Txn) = db.capturesOf(c.id).any { !isCardRow(it) }
    private fun cardPaired(c: Txn) = db.capturesOf(c.id).any { isCardRow(it) }

    /**
     * 규칙으로 읽은 간편결제 앱의 취소 알림인데, 그 앱의 결제가 카드·은행 기록으로 합쳐져(#18) 앱 쪽 원래 기록이 없을 때(#21 리뷰):
     * 카드 기록의 취소로 겁니다. 카드 알림의 취소가 이미 기록돼 있으면 같은 취소로 보고 새로 만들지 않습니다.
     * 이 폰 가계부의 기록만 다룹니다(보내는 가족 폰은 [SentLog]의 별칭으로 같은 일을 함).
     */
    private fun cancelAbsorbed(p: Parsed, at: Long, source: Source): Outcome? {
        if (!identity.value.inHousehold || p.paidFromBalance) return null
        val me = identity.installId
        val s = ledger.value
        // 카드사가 먼저 알린 같은 취소가 있으면(아직 앱 알림과 짝지어지지 않은 것) 그 취소와 같은 취소입니다. 원래 결제보다 먼저 봐야
        // 부분 취소가 3분 넘게 떨어져 와도 두 번 세지 않습니다(#21 리뷰).
        SamePayment.unpairedCardCancel(s, me, p.amount, at, p.issuer, ::appPaired)?.let { c ->
            joinStored(c.id, p, p.issuer)
            return Outcome("같은 취소 · ${c.issuer} 알림 기록에 묶음", c.id)
        }
        SamePayment.absorbedOriginal(s, me, p, at, p.issuer)?.let { t ->
            val c = Txn(
                id = newId(), kind = Kind.EXPENSE, amount = p.amount, at = at, accountId = t.accountId, merchant = t.merchant,
                memberId = t.memberId, source = source, cancelOf = t.id, issuer = t.issuer, cardLabel = t.cardLabel,
                categoryId = t.categoryId, private = t.private, createdBy = me, createdAt = System.currentTimeMillis(),
            )
            ledger.saveTxn(c)
            settings.markUnseen(listOf(c.id))
            return Outcome("${t.issuer} 결제의 취소로 기록(${p.issuer} 알림)", c.id)
        }
        return null
    }

    /**
     * 후보로 남은 간편결제 알림 중 이미 기록·보낸 결제와 같은 것을 묶습니다(하루 한 번·앱을 열 때).
     * 카드 알림이 먼저 와 있어도 이 기능이 생기기 전에 받은 알림은 후보로 남아 있어서입니다.
     */
    @Synchronized
    fun rejoinCandidates() {
        val rows = db.candidatesBetween(0, Long.MAX_VALUE)
        if (rows.isEmpty()) return
        // 후보마다 장부 전체를 훑지 않게, 후보들이 받은 때 ± 3분 안의 이 폰 기록만 먼저 추립니다(#19).
        val from = rows.minOf { it.at } - SamePayment.WINDOW
        val to = rows.maxOf { it.at } + SamePayment.WINDOW
        val pool = ledger.value.txns.values.filter { it.at in from..to && it.createdBy == identity.installId }
        for (row in rows) {
            val app = payApp(row.source, row.label, row.title, row.pkg)
            val notice = Parser.payNotice(listOfNotNull(row.title, row.text).joinToString("\n"), Fmt.dateTime(row.at), app) ?: continue
            val joined = joinRecorded(notice, row.at, app, pool) ?: continue
            db.updateCapture(row.id, "done", joined.result, joined.txnId)
        }
    }

    private class Notice(val row: Db.CaptureRow, val parsed: Parsed, val app: String)

    /** 카드·은행 알림 [card]와 같은 결제를 알린, 후보로 남은 간편결제 알림. 여럿이면 가맹점이 겹치는 것, 그다음 시각이 가장 가까운 것. */
    private fun pendingNotice(card: Parsed, at: Long): Notice? =
        db.candidatesBetween(at - SamePayment.WINDOW, at + SamePayment.WINDOW).mapNotNull { row ->
            val app = payApp(row.source, row.label, row.title, row.pkg)
            Parser.payNotice(listOfNotNull(row.title, row.text).joinToString("\n"), Fmt.dateTime(row.at), app)
                ?.takeIf { SamePayment.matches(it, row.at, card, at) }
                ?.let { Notice(row, it, app) }
        }.minWithOrNull(compareByDescending<Notice> { SamePayment.related(card.merchant, it.parsed.merchant) }.thenBy { abs(it.row.at - at) })

    /**
     * 이 폰이 이미 기록하거나 보낸 카드·은행 결제 중 간편결제 알림 [notice]와 같은 것을 찾아 묶습니다. [pool]: 찾아볼 거래(기본 장부 전체).
     * [links]: 보낸 기록도 볼지. 후보로 남길지 정할 때만 봅니다(규칙으로 보내는 알림은 forward가 링크마다 거름).
     */
    private fun joinRecorded(notice: Parsed, at: Long, app: String, pool: Collection<Txn> = ledger.value.txns.values,
                             links: Boolean = true): Outcome? {
        val t = pool
            .filter { it.createdBy == identity.installId && SamePayment.matches(notice, at, it) }
            .minWithOrNull(compareByDescending<Txn> { SamePayment.related(it.merchant, notice.merchant) }.thenBy { abs(it.at - at) })
        if (t != null) {
            joinStored(t.id, notice, app)
            return Outcome("같은 결제 · ${t.issuer ?: "카드"} 알림 기록에 묶음", t.id)
        }
        // 보내기만 하는 폰(부모님 폰): 이미 보낸 결제와 같으면 다시 보내라고 권하지 않습니다. 머니·포인트 결제는 같은 결제가 아닙니다.
        // 이 앱 이름으로 올 취소가 원래 거래를 찾게 별칭을 남깁니다(#21 리뷰).
        if (!links) return null
        for (link in identity.value.outLinks) {
            val items = sentTo(link)
            val sent = SentLog.sameSent(items, notice, at) ?: continue
            SentLog.withAlias(items, sent, notice.issuer)?.let { saveSent(link, it) }
            return Outcome("같은 결제 · ${link.label}에 이미 보낸 ${sent.optString("issuer")} 알림", null)
        }
        return null
    }

    /** 이 폰이 만든 거래만 고칩니다(가족이 기록한 것은 건드리지 않음). */
    private fun joinStored(txnId: String, notice: Parsed, app: String) {
        val t = ledger.value.txns[txnId]?.takeIf { it.createdBy == identity.installId } ?: return
        val joined = SamePayment.enrich(ledger.value, t, notice, app)
        // 자동으로 고친 것이라 카테고리를 바꿔도 가맹점 규칙으로 배우지 않습니다(#19, Hooks.learnCategories).
        if (joined != t) ledger.saveTxn(joined, auto = true)
    }

    /** 간편결제 앱 이름: 앱 알림이면 앱 이름(못 읽었으면 알림 제목), 문자면 알 수 없음. */
    private fun payApp(source: String, label: String?, title: String?, pkg: String?): String =
        (if (source == Source.PUSH.code) label?.takeIf { it.isNotBlank() && it != pkg } ?: title?.takeIf { it.isNotBlank() } else null)
            ?.trim()?.take(20) ?: SamePayment.UNKNOWN_APP

    /** 알림 본문의 거래 시각을 쓰되, 사흘 넘게 어긋나면(연도 착오 등) 받은 시각으로. */
    private fun txnAt(parsed: Parsed, postedAt: Long): Long =
        parsed.at?.let { Fmt.millis(it) }?.takeIf { abs(it - postedAt) < 3L * 86_400_000 } ?: postedAt

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
        return deliver(parsed, Source.of(row.source), from, row.title, row.text, row.at, row.label, logId = row.id, forceShare = true,
            note = PICKED_NOTE)
    }

    /** 고른 알림을 지금 해석해 보여 줄 초기값. 카드사를 모르면 앱 이름(없으면 "카드")으로 둡니다. 실패로 뺀 알림도 읽어 채웁니다(#13). */
    fun previewPicked(row: Db.CaptureRow): Parsed? {
        val body = listOfNotNull(row.title, row.text).joinToString("\n")
        val name = settings.ruleFor(row.source, row.pkg)?.issuer ?: row.label?.takeIf { it.isNotBlank() && it != row.pkg } ?: "카드"
        val parsed = Parser.parse(body, Fmt.dateTime(row.at), row.label, Issuer(name, true, listOf(name)), ignoreFailure = row.isFailed)
        return if (row.isFailed) parsed?.copy(merchant = Parser.withoutFailureWords(parsed.merchant)) else parsed
    }

    // --- 예전 버전이 기록한, 돈이 나가지 않은 알림: 0.1.11 전의 결제·출금 실패(#13), 0.2.3 전의 출금 예정 안내 ---------------

    /** 지금 기준으로는 돈이 나가지 않은 알림(실패·예정)인데 이 폰이 내역으로 기록해 장부에 남아 있는 것(최근 30일 수집 기록 안). */
    fun recordedFailures(): List<Pair<Db.CaptureRow, Txn>> {
        val rows = db.recordedCaptures()
        val failed = rows.filter { unspentOf(it) != null }
        if (failed.isEmpty()) return emptyList()
        // 같은 거래를 정상 알림도 가리키면(같은 결제의 다른 알림) 실제로 돈이 나간 것이라 그대로 둡니다.
        val alsoReal = (rows - failed.toSet()).mapNotNull { it.txnId }.toSet()
        return failed
            .filter { r -> (r.result.startsWith("기록함") || r.result.startsWith(PRIVATE_RESULT)) && PICKED_NOTE !in r.result && KEPT_NOTES.none { it in r.result } }
            .mapNotNull { r -> ledger.value.txns[r.txnId]?.takeIf { it.createdBy == identity.installId && it.id !in alsoReal }?.let { r to it } }
            .distinctBy { it.second.id }
    }

    /** [recordedFailures]의 거래를 지우고, 수집 기록은 실패·예정 알림으로 되돌립니다. */
    @Synchronized
    fun deleteRecordedFailures(items: List<Pair<Db.CaptureRow, Txn>>) {
        for ((row, t) in items) {
            ledger.deleteTxn(t.id)
            db.updateCapture(row.id, Db.FAILED, "${resultOf(unspentOf(row) ?: Parser.Unspent.FAILED)} · 기록했던 내역 지움", null)
        }
        requestSync()
    }

    /** 그대로 두기로 한 것: 다시 묻지 않게 표시합니다. */
    fun keepRecordedFailures(items: List<Pair<Db.CaptureRow, Txn>>) {
        for ((row, t) in items) {
            val note = if (unspentOf(row) == Parser.Unspent.SCHEDULED) KEPT_SCHEDULED_NOTE else KEPT_NOTE
            db.updateCapture(row.id, row.status, "${row.result} · $note", t.id)
        }
    }

    private fun unspentOf(row: Db.CaptureRow) = Parser.unspent(listOfNotNull(row.title, row.text).joinToString("\n"))

    private fun resultOf(unspent: Parser.Unspent) = if (unspent == Parser.Unspent.SCHEDULED) SCHEDULED_RESULT else FAILED_RESULT

    private fun deliver(parsed: Parsed, source: Source, pkg: String?, title: String?, text: String, postedAt: Long,
                        appLabel: String?, logId: Long?, forceShare: Boolean = false, note: String? = null): Outcome {
        val at = txnAt(parsed, postedAt)
        val results = ArrayList<String>()
        var txnId: String? = null
        var handled = false
        if (identity.value.inHousehold) {
            val me = ledger.value.member(identity.value.myMemberId)
            val r = if (!forceShare && me != null && !me.shares(parsed.issuer)) recordPrivate(parsed, source, at, me.id)
                else recordLocal(parsed, source, at)
            results += r.result
            txnId = r.txnId
            if (r.txnId != null) handled = true
        }
        for (link in identity.value.outLinks) {
            if (!sendsTo(link, parsed.issuer)) continue
            val sent = forward(parsed, at, link)
            results += sent.text
            if (sent.ok) handled = true
        }
        if (results.isEmpty()) results += "가계부가 없어 기록하지 않음"
        note?.takeIf { handled }?.let { results += it }
        if (logId != null) db.updateCapture(logId, "done", results.joinToString(" · "), txnId)
        else db.logCapture(postedAt, source.code, pkg, title, text, results.joinToString(" · "), txnId, label = appLabel)
        requestSync()
        return Outcome(results.joinToString(" · "), txnId, handled)
    }

    // --- 내 가구에 기록 -------------------------------------------------------------------------

    private fun recordLocal(p: Parsed, source: Source, at: Long): Outcome {
        val me = identity.value.myMemberId
        val s = ledger.value
        val label = p.cardLabel
        val kind = when (p.type) {
            Parsed.Type.APPROVAL, Parsed.Type.CANCEL -> Kind.EXPENSE
            Parsed.Type.DEPOSIT -> Kind.INCOME
            Parsed.Type.WITHDRAWAL -> if (p.cardPaymentTo != null) Kind.TRANSFER else Kind.EXPENSE
        }
        val isCancel = p.type == Parsed.Type.CANCEL
        // 다른 가족 폰이 같은 결제를 이미 올려 둔 경우(가족카드 알림이 양쪽에 오는 등)
        val probe = Txn(id = "probe", kind = kind, amount = p.amount, at = at, merchant = p.merchant.orEmpty(), source = source,
            issuer = p.issuer, cardLabel = label, cancelOf = if (isCancel) "?" else null)
        fun twin() = ledger.value.txns.values.firstOrNull { it.memberId != me && Twins.same(it, probe) }
        // 이 폰에 그 카드·통장이 아직 없으면, 계좌를 만들기 전에 봅니다: 남편 카드의 같은 결제가 아내 폰에도 오면
        // 기록은 안 해도 아내 앞으로 빈 카드가 생겼습니다(2026-11-21 주유).
        val existing = existingAccount(s, p, me)
        if (existing == null) twin()?.let { return Outcome("가족 폰이 이미 기록함", it.id) }
        val account = existing ?: newAccount(s, p, me)

        withBalance(account, p.balance, at).takeIf { it != account }?.let { ledger.saveAccount(it) }

        // 같은 승인이 앱 알림과 문자로 두 번 온 경우 (DECISIONS.md D-5).
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
        // 간편결제 앱 알림이 먼저 이 카드 기록에 같은 취소를 걸어 두었으면(아직 카드 알림과 짝지어지지 않은 것) 다시 세지 않습니다.
        // 원래 결제의 남은 금액보다 먼저 봐야 부분 취소가 3분 넘게 떨어져 와도 한 번만 셉니다(#18, #21 리뷰).
        if (isCancel) {
            SamePayment.unpairedAppCancel(ledger.value, ledger.value.txns.values.filter { it.accountId == account.id }, p.amount, at, p.merchant, ::cardPaired)
                ?.let { return Outcome("이미 기록됨(간편결제 알림의 같은 취소)", it.id) }
        }
        twin()?.let { return Outcome("가족 폰이 이미 기록함", it.id) }

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
                    // 카드값을 내는 것은 신용카드입니다(체크카드는 통장에서 바로 나가 청구가 없음).
                    val card = cardAccount(ledger.value, cardIssuer, null, me, check = false)
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

        // 고정지출·고정수입으로 미리 잡아 둔 같은 종류·금액이 있으면 새로 만들지 않고 그 자리를 채웁니다.
        val planned = com.jeiel85.doran.logic.Fixed.planned(ledger.value.txns.values, txn, account.id, cancelNotice = isCancel)
        val saved = if (planned != null) txn.copy(id = planned.id, recurringId = planned.recurringId,
            categoryId = planned.categoryId ?: txn.categoryId, memo = planned.memo.ifBlank { txn.memo }) else txn
        ledger.saveTxn(saved)
        settings.markUnseen(listOf(saved.id))
        return Outcome(if (planned != null) "${com.jeiel85.doran.logic.Fixed.label(planned.kind)}과 맞춤" else "기록함", saved.id)
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
        // 은행 출금은 쓴 돈인지 옮긴 돈인지 따로 가립니다(D-26). 카드 승인·체크카드 승인은 쓴 돈입니다.
        val bankOut = p.type == Parsed.Type.WITHDRAWAL && !p.isCard
        val mine = ledger.value.txns.values.filter { it.private && it.memberId == me && it.issuer == p.issuer }
        mine.firstOrNull {
            // 먼저 온 같은 출금 알림이 옮긴 돈(이체)으로 기록됐어도 같은 출금입니다(앱 푸시와 문자가 함께 오는 경우).
            val sameKind = it.kind == kind || bankOut && com.jeiel85.doran.logic.Moves.isMoved(it)
            it.amount == p.amount && sameKind && (it.cancelOf != null) == isCancel && abs(it.at - at) <= sameMinutes && it.source.captured
        }?.let { return Outcome("이미 기록됨(중복 알림)", it.id) }
        if (isCancel) {
            SamePayment.unpairedAppCancel(ledger.value, mine, p.amount, at, p.merchant, ::cardPaired)?.let { return Outcome("이미 기록됨(간편결제 알림의 같은 취소)", it.id) }
        }

        val first = Txn(
            id = newId(), kind = kind, amount = p.amount, at = at, accountId = null, merchant = p.merchant.orEmpty(),
            memberId = me, source = source, installment = p.installment, cumulative = p.cumulative, issuer = p.issuer,
            cardLabel = p.cardLabel, foreign = p.foreign, needsReview = p.amount == 0L,
            private = true, createdBy = identity.installId, createdAt = System.currentTimeMillis(),
        )
        val txn = if (isCancel) {
            val original = pickOriginal(ledger.value, mine.asSequence(), p, at)
            if (original != null) first.copy(cancelOf = original.id, categoryId = original.categoryId, merchant = p.merchant ?: original.merchant)
            else first.copy(kind = Kind.INCOME, categoryId = "c.income_etc", memo = "원래 승인을 찾지 못한 취소 — 확인해 주세요", needsReview = true)
        } else first.copy(categoryId = Categorizer.categorize(ledger.value, first.merchant, kind))
        // 은행 입금은 가족이 가계부 통장에서 보낸 돈(가족 돈)인지 봅니다(D-28). 그 돈으로 나가는 출금은 용돈에서 뺍니다.
        val bankIn = p.type == Parsed.Type.DEPOSIT && !p.isCard && !isCancel
        val saved = when {
            bankOut -> judgeMove(txn)
            bankIn -> com.jeiel85.doran.logic.Moves.fundSource(ledger.value, txn)?.let { com.jeiel85.doran.logic.Moves.asFund(txn, it) } ?: txn
            else -> txn
        }
        ledger.saveTxn(saved)
        settings.markUnseen(listOf(saved.id))
        return Outcome("$PRIVATE_RESULT(공유 범위 밖)" + if (com.jeiel85.doran.logic.Moves.isMoved(saved)) " · $MOVED_NOTE" else "", saved.id)
    }

    /** 나만 보는 용돈의 은행 출금 [w]를 쓴 돈·옮긴 돈으로 가립니다(D-26, [com.jeiel85.doran.logic.Moves]). */
    private fun judgeMove(w: Txn): Txn {
        val s = ledger.value
        val rule = settings.movedBy(com.jeiel85.doran.logic.Moves.ruleKey(w.issuer, w.merchant))
        return com.jeiel85.doran.logic.Moves.judge(s, w, s.member(w.memberId)?.allowance ?: 0, rule)
    }

    /**
     * 장부가 바뀐 뒤(동기화로 받은 아내 폰의 입금 포함) 내 은행 출금의 자동 판정을 다시 봅니다.
     * 사용자가 고른 것과 규칙으로 정한 것은 건드리지 않습니다. 개인 키로 봉한 용돈 내역이라 이 폰에서만 고칠 수 있습니다.
     */
    @Synchronized
    fun rejudgeMoves(changed: List<Pair<Txn?, Txn?>>) {
        val me = identity.value.myMemberId ?: return
        for (w in com.jeiel85.doran.logic.Moves.affected(ledger.value, changed, me, identity.installId)) {
            val cur = ledger.value.txns[w.id] ?: continue
            val next = com.jeiel85.doran.logic.Moves.judge(ledger.value, cur, ledger.value.member(cur.memberId)?.allowance ?: 0, rule = null)
            if (next != cur) {
                ledger.saveTxn(next, auto = true)
                noteMove(next)
            }
        }
    }

    /** 장부가 바뀐 뒤(가족 폰의 출금이 늦게 동기화된 경우 포함) 내 나만 보는 입금의 가족 돈 표시를 고칩니다(D-28). */
    @Synchronized
    fun markFunds(changed: List<Pair<Txn?, Txn?>>) {
        val me = identity.value.myMemberId ?: return
        com.jeiel85.doran.logic.Moves.fundUpdates(ledger.value, changed, me, identity.installId).forEach { ledger.saveTxn(it, auto = true) }
    }

    /** 사용자가 내역 화면에서 "가족 돈 아니에요"를 누름: 다시 표시하지 않고, 이 돈으로 본 출금은 다음 훅에서 다시 판정됩니다. */
    @Synchronized
    fun setNotFund(t: Txn) {
        val cur = ledger.value.txns[t.id]?.takeIf { it.private && it.memberId == identity.value.myMemberId && it.kind == Kind.INCOME } ?: return
        ledger.saveTxn(cur.copy(move = com.jeiel85.doran.logic.Moves.APART))
    }

    /** 예전 기록 정리(D-28): 판정 전에 기록된 내 은행 출금 중 지금 기준이면 옮긴 돈인 것. 알림 원문(30일)을 읽으니 IO 스레드에서. */
    fun staleMoves(): List<Pair<Txn, Txn>> {
        val me = identity.value.myMemberId ?: return emptyList()
        return com.jeiel85.doran.logic.Moves.staleCandidates(ledger.value, me, ::isBankWithdrawal) { t ->
            settings.movedBy(com.jeiel85.doran.logic.Moves.ruleKey(t.issuer, t.merchant))
        }
    }

    /**
     * "모두 정리": 보여 준 것들을 지금 장부로 다시 판정해(그사이 다른 출금이 같은 입금·가족 돈을 가져갔을 수 있어서, #54 리뷰)
     * 아직 옮긴 돈인 것만 바꿉니다. 그사이 사용자가 고친 것은 건드리지 않습니다.
     */
    @Synchronized
    fun applyStaleMoves(items: List<Pair<Txn, Txn>>) {
        val shown = items.map { it.first }.filter { ledger.value.txns[it.id] == it }.mapTo(HashSet()) { it.id }
        if (shown.isEmpty()) return
        val me = identity.value.myMemberId ?: return
        val fresh = com.jeiel85.doran.logic.Moves.staleCandidates(ledger.value, me, { it.id in shown }) { t ->
            settings.movedBy(com.jeiel85.doran.logic.Moves.ruleKey(t.issuer, t.merchant))
        }
        for ((_, next) in fresh) {
            ledger.saveTxn(next)
            noteMove(next)
        }
    }

    /** "그대로 두기": 쓴 돈으로 정했다고 남겨 다시 묻지 않습니다. */
    @Synchronized
    fun keepStaleMoves(items: List<Pair<Txn, Txn>>) {
        for ((before, _) in items) {
            if (ledger.value.txns[before.id] != before) continue
            ledger.saveTxn(com.jeiel85.doran.logic.Moves.spent(ledger.value, before, com.jeiel85.doran.logic.Moves.KEPT))
        }
    }

    /** "최근 받은 알림"의 기록 결과에 옮긴 돈 표시를 붙이거나 뗍니다. */
    private fun noteMove(t: Txn) {
        val moved = com.jeiel85.doran.logic.Moves.isMoved(t)
        for (row in db.capturesOf(t.id)) {
            val base = row.result.removeSuffix(" · $MOVED_NOTE")
            val result = if (moved) "$base · $MOVED_NOTE" else base
            if (result != row.result) db.updateCapture(row.id, row.status, result, row.txnId)
        }
    }

    /**
     * 사용자가 내역 화면에서 은행 출금을 옮긴 돈([moved]) 또는 용돈에서 쓴 돈으로 정합니다. [remember]면 같은 은행·메모의 다음 출금도
     * 그렇게 봅니다(이 폰에만). 판정 전에 기록된 출금(move 없음)도 같은 방식으로 고칩니다.
     */
    @Synchronized
    fun setMoved(t: Txn, moved: Boolean, remember: Boolean) {
        val cur = ledger.value.txns[t.id]?.takeIf { it.private && it.memberId == identity.value.myMemberId } ?: return
        val next = if (moved) com.jeiel85.doran.logic.Moves.moved(cur, com.jeiel85.doran.logic.Moves.USER)
            else com.jeiel85.doran.logic.Moves.spent(ledger.value, cur, com.jeiel85.doran.logic.Moves.KEPT)
        ledger.saveTxn(next)
        noteMove(next)
        if (remember) com.jeiel85.doran.logic.Moves.ruleKey(cur.issuer, cur.merchant)?.let { key ->
            settings.saveMoveRule(com.jeiel85.doran.data.MoveRule(key, listOfNotNull(cur.issuer, cur.merchant.ifBlank { null }).joinToString(" · "), moved))
        }
    }

    /**
     * 내역 화면에서 "옮긴 돈 ↔ 쓴 돈"을 바꿀 수 있는 내 은행 출금인지. 판정이 생기기 전(move 없음)에 기록된 것은 그 알림 원문(30일)을
     * 다시 읽어 은행 출금인지 봅니다.
     */
    fun isBankWithdrawal(t: Txn): Boolean {
        if (!t.private || t.memberId != identity.value.myMemberId || t.cancelOf != null || !t.source.captured) return false
        if (t.kind == Kind.INCOME) return false
        if (com.jeiel85.doran.logic.Moves.withdrawalMarked(t)) return true
        if (t.move != null || t.kind != Kind.EXPENSE) return false
        return db.capturesOf(t.id).any { row ->
            Parser.parse(listOfNotNull(row.title, row.text).joinToString("\n"), Fmt.dateTime(row.at), row.label)
                ?.let { it.type == Parsed.Type.WITHDRAWAL && !it.isCard && it.cardPaymentTo == null } == true
        }
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
    /** 이 알림의 카드·통장 중 이 폰 사람(또는 주인 없는) 것. 카드면 알림이 알려 준 끝자리·체크카드 여부를 채워 저장합니다. 없으면 null. */
    private fun existingAccount(s: LedgerState, p: Parsed, me: String?): Account? =
        if (p.isCard) pickCard(s.accounts.values, p.issuer, p.last4, me, p.checkCard)?.let { a ->
            refreshCard(a, p.last4, p.checkCard).also { if (it != a) ledger.saveAccount(it) }
        }
        else s.accounts.values.firstOrNull { it.institution == p.issuer && it.type == AccountType.BANK && (it.ownerId == me || it.ownerId == null) }

    /** 알림으로 처음 보는 카드·통장을 만듭니다. 기준 시각은 0: 알림의 거래 시각이 계좌를 만든 순간보다 조금 이를 수 있어서. */
    private fun newAccount(s: LedgerState, p: Parsed, me: String?): Account =
        if (p.isCard) newCard(s, p.issuer, p.last4, me, p.checkCard)
        else Account(id = newId(), name = p.issuer, type = AccountType.BANK, ownerId = me, institution = p.issuer,
            openingAt = 0, order = s.accounts.size).also { ledger.saveAccount(it) }

    private fun cardAccount(s: LedgerState, issuer: String, last4: String?, me: String?, check: Boolean): Account {
        pickCard(s.accounts.values, issuer, last4, me, check)?.let { a ->
            val fixed = refreshCard(a, last4, check)
            if (fixed != a) ledger.saveAccount(fixed)
            return fixed
        }
        return newCard(s, issuer, last4, me, check)
    }

    private fun newCard(s: LedgerState, issuer: String, last4: String?, me: String?, check: Boolean): Account {
        // 정한 결제만 보내는 사람의 보내는 카드는 가족이 함께 쓰는 카드라 처음부터 가족 카드로(D-20).
        val family = s.member(me)?.let { it.sharesPicked && it.shares(issuer) } == true
        return Account(id = newId(), name = issuer + (last4?.let { " $it" } ?: ""), type = if (check) AccountType.CHECK else AccountType.CARD,
            ownerId = me, institution = issuer, last4 = last4, cycleStartDay = 1, openingAt = 0, order = s.accounts.size, family = family)
            .also { ledger.saveAccount(it) }
    }

    // --- 다른 가구로 보내기 (부모님 폰 → 우리 가구) ----------------------------------------------

    /** 이 링크로 최근 보낸 결제(최근 300건, [SentLog]): 중복 보내기를 막고 취소의 원래 승인을 찾는 데 씁니다. */
    private fun sentTo(link: OutLink): List<JSONObject> {
        val recent = db.kv("fwd.recent.${link.spaceId}")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
        return (0 until recent.length()).map { recent.getJSONObject(it) }
    }

    private fun saveSent(link: OutLink, items: List<JSONObject>) =
        db.setKv("fwd.recent.${link.spaceId}", JSONArray(items.takeLast(300)).toString())

    private class Sent(val text: String, val ok: Boolean)

    /** [Sent.ok]: 보냈거나 이미 보낸 결제인지. */
    private fun forward(p: Parsed, at: Long, link: OutLink): Sent {
        val items = sentTo(link)
        val isCancel = p.type == Parsed.Type.CANCEL
        val kind = when (p.type) {
            Parsed.Type.DEPOSIT -> Kind.INCOME
            else -> Kind.EXPENSE
        }
        // 같은 카드사의 중복 알림, 또는 규칙으로 보낸 간편결제 앱 알림과 같은 결제의 카드·은행 알림(#18)이면 다시 보내지 않고,
        // 이 이름으로 올 취소가 원래 거래를 찾게 별칭만 남깁니다(#21 리뷰).
        (SentLog.sameSent(items, p, at) ?: SentLog.sameCancelSent(items, p, at))?.let { hit ->
            SentLog.withAlias(items, hit, p.issuer)?.let { saveSent(link, it) }
            return Sent("${link.label}: 이미 보냄", true)
        }
        val accountId = "acc.${link.memberId}.${Categorizer.normalize(p.issuer)}"
        val original = if (isCancel) SentLog.original(items, p, at) else null
        if (isCancel && original == null) return Sent("${link.label}: 원래 승인을 찾지 못해 보내지 않음", false)

        val txn = Txn(
            id = newId(), kind = kind, amount = p.amount, at = at, accountId = accountId, merchant = p.merchant.orEmpty(),
            memberId = link.memberId, source = Source.FAMILY, installment = p.installment, cumulative = p.cumulative,
            issuer = p.issuer, cardLabel = p.cardLabel, foreign = p.foreign,
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
        saveSent(link, items + SentLog.entry(txn.id, p, at, kind, original?.optString("id")))
        return Sent("${link.label}에 보냄", true)
    }

    companion object {
        const val FAILED_RESULT = "결제·출금 실패 알림 · 기록 안 함"
        const val SCHEDULED_RESULT = "결제·출금 예정 안내 · 기록 안 함"
        private const val PRIVATE_RESULT = "나만 보는 용돈으로 기록"
        /** 옮긴 돈으로 본 은행 출금의 수집 기록 표시(D-26). */
        const val MOVED_NOTE = "옮긴 돈 · 용돈에서 안 뺌"
        /** 직접 골라 보낸 기록의 표시. 실패·예정 알림을 "그래도 보내기"로 보낸 것을 다시 지우자고 묻지 않는 데 씁니다. */
        const val PICKED_NOTE = "직접 골라 보냄"
        /** "그대로 두기"의 표시. 이 낱말로 다시 묻지 않으니 바꾸면 예전에 그대로 둔 것을 또 묻게 됩니다. */
        private const val KEPT_NOTE = "실패 알림이지만 그대로 둠"
        private const val KEPT_SCHEDULED_NOTE = "예정 안내지만 그대로 둠"
        private val KEPT_NOTES = listOf(KEPT_NOTE, KEPT_SCHEDULED_NOTE)

        /** 이 링크로 보낼 카드사·은행인지. 비어 있으면 전부, 표기가 달라도 같은 곳이면 보냅니다(#10). */
        fun sendsTo(link: OutLink, issuer: String): Boolean = link.issuers.isEmpty() || link.issuers.any { Issuers.same(it, issuer) }

        /**
         * 카드사 알림이 갈 이 사람(또는 공동)의 카드 계좌. 끝자리가 같은 카드가 먼저이고, 어느 한쪽이 끝자리를 모르면
         * 같은 종류(체크·신용)의 카드입니다. 없으면 null(새로 만듦).
         * 다른 종류로는 넘기지 않습니다 — 신용카드 결제가 체크카드 계좌로 가면 갚을 돈에서 빠지기 때문입니다(#59 리뷰).
         * 하나만 예외: 0.3.4 전에는 체크카드 알림도 신용카드 계좌를 만들었으므로, 끝자리 없는 체크카드 알림은 그 신용카드 계좌에 이어 기록합니다.
         */
        fun pickCard(accounts: Collection<Account>, issuer: String, last4: String?, me: String?, check: Boolean): Account? {
            val mine = accounts.filter {
                it.institution == issuer && (it.type == AccountType.CARD || it.type == AccountType.CHECK) && (it.ownerId == me || it.ownerId == null)
            }
            mine.firstOrNull { last4 != null && it.last4 == last4 }?.let { return it }
            val loose = mine.filter { it.last4 == null || last4 == null }
            loose.firstOrNull { (it.type == AccountType.CHECK) == check }?.let { return it }
            return if (check && last4 == null) loose.firstOrNull { it.type == AccountType.CARD } else null
        }

        /**
         * 고른 카드에 알림이 알려 준 것을 채웁니다: 모르던 끝자리, 그리고 체크카드 알림이 끝자리까지 같은 '신용카드'면 체크카드로.
         * 결제일을 정한 카드는 사람이 신용카드로 맞춘 것이라 두고, 끝자리를 모르는 알림으로는 바꾸지 않습니다
         * (같은 카드사의 신용카드를 체크카드로 바꿀 수 있어서 — DECISIONS.md D-29).
         */
        fun refreshCard(a: Account, last4: String?, check: Boolean): Account {
            val sameCard = last4 != null && a.last4 == last4
            val toCheck = check && sameCard && a.type == AccountType.CARD && a.billingDay == null
            return a.copy(
                last4 = a.last4 ?: last4,
                type = if (toCheck) AccountType.CHECK else a.type,
                // 신용카드에 남아 있던 기준 잔액은 예전 알림의 '잔액'(연결 통장 잔액)이라, 체크카드가 되면서 지웁니다.
                // 남기면 체크카드가 잔액을 세는 계좌로 보여 통장 잔액이 순자산에 두 번 들어갑니다(#59 리뷰).
                anchor = if (toCheck) null else a.anchor,
                anchorAt = if (toCheck) null else a.anchorAt,
            )
        }

        /**
         * 알림이 알려 준 거래 후 잔액을 계좌의 기준점으로. 잔액을 세는 계좌에만 적습니다: 체크카드 알림의 '잔액'은 연결 통장의 잔액이라
         * 체크카드에 적으면 잔액을 세는 계좌로 바뀌어 통장 잔액이 두 번 들어가고, 신용카드는 잔액 대신 갚을 돈을 셉니다(#59 리뷰, D-29).
         */
        fun withBalance(a: Account, balance: Long?, at: Long): Account =
            if (balance != null && com.jeiel85.doran.logic.Stats.keepsBalance(a)) a.copy(anchor = balance, anchorAt = at) else a

        /** 시험용: 지금 시각을 넣어 해석만 해 봅니다(설정 화면 "문구 시험"). */
        fun preview(text: String): Parsed? = Parser.parse(text, LocalDateTime.now())
    }
}
