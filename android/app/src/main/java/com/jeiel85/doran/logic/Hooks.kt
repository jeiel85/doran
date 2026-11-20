package com.jeiel85.doran.logic

import android.content.Context
import android.util.Log
import com.jeiel85.doran.capture.Categorizer
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.data.Change
import com.jeiel85.doran.model.AllowanceSummary
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.MerchantRule
import com.jeiel85.doran.model.Source
import com.jeiel85.doran.model.T
import com.jeiel85.doran.model.Txn
import com.jeiel85.doran.ui.Notifier
import com.jeiel85.doran.widget.SummaryWidget
import org.json.JSONObject
import java.time.LocalDate

/** 장부가 바뀐 뒤 따라오는 일: 동기화 요청, 용돈 요약, 가족 알림, 예산 알림, 위젯 갱신. */
object Hooks {

    fun onChanges(context: Context, changes: List<Change>) {
        if (changes.any { it.local }) Hub.requestSync()
        rejudgeMoves(changes)
        refreshAllowanceAlert(context, changes)
        updateAllowance(changes)
        notifyAllowance(context, changes)
        learnCategories(changes)
        budgetCheck(context)
        SummaryWidget.refresh(context)
    }

    /** 동기화로 받은 것 중 다른 사람이 자동으로 기록한 새 거래는 알리고 "새 내역"으로 표시합니다. */
    fun onRemote(context: Context, changes: List<Change>) {
        notifyShareChanges(context, changes)
        val s = Hub.ledger.value
        val mine = Hub.identity.installId
        val fresh = changes.filter { it.type == T.TXN && it.before == null && it.after != null }
            .map { Txn.from(it.id, it.after!!) }
            .filter { it.createdBy != mine && it.source.captured && !it.private }
        if (fresh.isEmpty()) return
        // 같은 결제를 내 폰도 기록해 둔 경우(가족카드 알림이 양쪽에 오는 등): 내가 늦게 만든 쪽을 지우고 알리지 않습니다.
        val twins = fresh.filter { f ->
            val own = s.txns.values.firstOrNull { it.createdBy == mine && Twins.same(it, f) } ?: return@filter false
            if (Twins.keeper(own, f).id == f.id) Hub.ledger.deleteTxn(own.id)
            true
        }.map { it.id }.toSet()
        val news = fresh.filter { it.id !in twins }
        if (news.isEmpty()) return
        Hub.local.markUnseen(news.map { it.id })
        if (!Hub.settings.value.notifyFamily) return
        // 처음 합류해 옛 내역이 한꺼번에 들어올 때 알림이 쏟아지지 않게 최근 하루치만.
        val recent = news.filter { System.currentTimeMillis() - it.at < 86_400_000 }.sortedBy { it.at }.takeLast(10)
        val now = Hub.ledger.value
        for (t in recent) {
            val text = FamilyAlert.of(now, t)
            Notifier.familyTxn(context, t.id, text.title, text.body)
        }
    }

    /** 다른 폰에서 누군가의 공유 범위를 바꾸면 알립니다(DECISIONS.md D-19). 내가 바꾼 것은 알리지 않습니다. */
    private fun notifyShareChanges(context: Context, changes: List<Change>) {
        val me = Hub.identity.value.myMemberId
        val s = Hub.ledger.value
        for (c in changes) {
            if (c.type != T.MEMBER || c.before == null || c.after == null) continue
            val before = Member.from(c.id, c.before)
            val after = Member.from(c.id, c.after)
            if (!ShareScope.changed(before, after) || after.shareBy == me) continue
            Notifier.system(context, 5000 + (c.id.hashCode() and 0xfff),
                ShareScope.changedTitle(after, me, s.member(after.shareBy)?.name), ShareScope.label(after) + " · " + ShareScope.detail(after))
        }
    }

    /** 내 용돈 거래가 바뀌면 그 달의 합계만 가구 키로 올립니다 (DECISIONS.md D-7). */
    private fun updateAllowance(changes: List<Change>) {
        val me = Hub.identity.value.myMemberId ?: return
        val s = Hub.ledger.value
        val months = changes.filter { it.local && it.type == T.TXN }
            .flatMap { listOfNotNull(it.before, it.after) }
            .map { Txn.from("", it) }
            .filter { it.private && it.memberId == me }
            .map { Stats.periodOf(Fmt.date(it.at), s.household.monthStartDay) }
            .distinctBy { it.key }
        for (p in months) {
            val list = Stats.allowanceTxns(s, me, p)
            val spent = Stats.allowanceSpent(s, me, p)
            val id = AllowanceSummary.idFor(me, p.key)
            val cur = s.allowances[id]
            if (cur?.spent != spent || cur.count != list.size) {
                Hub.ledger.saveAllowance(AllowanceSummary(id, me, p.key, spent, list.size))
            }
        }
    }

    /** 동기화로 받은 아내 폰의 입금 등으로 내 은행 출금의 '옮긴 돈' 판정이 바뀌면 고칩니다(D-26). 고친 것은 다시 이 훅을 거칩니다. */
    private fun rejudgeMoves(changes: List<Change>) {
        val txns = changes.filter { it.type == T.TXN }
        if (txns.isEmpty()) return
        Hub.capture.rejudgeMoves(txns.map { c -> c.before?.let { Txn.from(c.id, it) } to c.after?.let { Txn.from(c.id, it) } })
    }

    /**
     * 이미 띄운 "용돈에서 썼어요" 알림 묶음 안의 결제가 바뀌거나 지워지면(옮긴 돈으로 바뀜, 직접 고침·지움) 알림을 고쳐 쓰거나 거둡니다.
     * 사용자가 이미 지운 알림은 다시 띄우지 않습니다.
     */
    private fun refreshAllowanceAlert(context: Context, changes: List<Change>) {
        val ids = changes.filter { it.type == T.TXN && it.before != null }.mapTo(HashSet()) { it.id }
        if (ids.isEmpty()) return
        val group = Hub.db.kv(ALLOWANCE_GROUP)?.let { runCatching { AllowanceAlert.Group.from(JSONObject(it)) }.getOrNull() } ?: return
        if (group.ids.none { it in ids } || !Notifier.allowanceShown(context, group.key)) return
        val me = Hub.identity.value.myMemberId ?: return
        val s = Hub.ledger.value
        val txns = group.ids.mapNotNull { s.txns[it] }.filter { AllowanceAlert.applies(it, me, Hub.identity.installId, System.currentTimeMillis()) }
            .sortedBy { it.at }
        if (txns.isEmpty()) { Notifier.cancelAllowance(context, group.key); return }
        val text = AllowanceAlert.ofMany(s, txns)
        Notifier.allowanceTxn(context, group.key, txns.last().id, text.title, text.body)
    }

    /**
     * 이 폰이 알림에서 읽어 내 용돈으로 기록한 새 결제를 내 폰에 알립니다(남은 용돈과 함께).
     * 가계부로 올라간 결제(가족 카드 등)는 가족 폰에 "가족이 쓴 내역"으로 가므로 내 폰에는 띄우지 않습니다.
     */
    private fun notifyAllowance(context: Context, changes: List<Change>) {
        if (!Hub.settings.value.notifyAllowance) return
        val me = Hub.identity.value.myMemberId ?: return
        val install = Hub.identity.installId
        val now = System.currentTimeMillis()
        val inserted = changes.filter { it.local && it.type == T.TXN && it.before == null && it.after != null }
            .map { Txn.from(it.id, it.after!!) }
        // 월 용돈보다 커서 옮긴 돈으로 본 은행 출금: 용돈에서 빼지 않았다고 따로 알려 바로 고칠 수 있게(D-26).
        for (t in inserted.filter { AllowanceAlert.appliesMoved(it, me, install, now) }) {
            val text = AllowanceAlert.moved(t)
            Notifier.allowanceTxn(context, "move.${t.id}", t.id, text.title, text.body)
        }
        val fresh = inserted.filter { AllowanceAlert.applies(it, me, install, now) }
        if (fresh.isEmpty()) return
        // 몇 분 안에 잇달아 온 것은 한 알림을 고쳐 가며 묶어 보여 줍니다(#15). 지워진 것은 빼고요.
        val prev = Hub.db.kv(ALLOWANCE_GROUP)?.let { runCatching { AllowanceAlert.Group.from(JSONObject(it)) }.getOrNull() }
        val group = AllowanceAlert.group(prev, fresh.sortedBy { it.at }.map { it.id }, now)
        Hub.db.setKv(ALLOWANCE_GROUP, group.json().toString())
        val s = Hub.ledger.value
        val txns = group.ids.mapNotNull { s.txns[it] }.filter { AllowanceAlert.applies(it, me, install, now) }.sortedBy { it.at }
        if (txns.isEmpty()) return
        val text = AllowanceAlert.ofMany(s, txns)
        Notifier.allowanceTxn(context, group.key, txns.last().id, text.title, text.body)
    }

    private const val ALLOWANCE_GROUP = "notify.allowance.group"

    /** 사람이 카테고리를 고치면 그 가맹점 규칙을 기억해 다음 자동 분류에 씁니다. 앱이 스스로 고친 것(같은 결제 묶기)은 빼요(#19). */
    private fun learnCategories(changes: List<Change>) {
        for (c in changes) {
            if (!c.local || c.auto || c.type != T.TXN || c.before == null || c.after == null) continue
            val before = Txn.from(c.id, c.before)
            val after = Txn.from(c.id, c.after)
            if (after.merchant.isBlank() || after.categoryId == null || before.categoryId == after.categoryId) continue
            if (after.kind == Kind.TRANSFER || after.cancelOf != null) continue
            val rule = MerchantRule(Categorizer.ruleId(after.merchant), after.merchant, after.categoryId)
            if (Hub.ledger.value.rules[rule.id]?.categoryId != rule.categoryId) Hub.ledger.saveRule(rule)
        }
    }

    /** 예산 알림 제목: "식비를 다 썼어요", "이번 달 예산의 80%를 썼어요" — 이름 받침에 맞춘 조사, 빈칸 없이. */
    fun budgetTitle(name: String, level: Int): String =
        if (level == 100) "${Josa.eulReul(name)} 다 썼어요" else "${Josa.ui(name)} $level%를 썼어요"

    private fun budgetCheck(context: Context) {
        if (!Hub.settings.value.notifyBudget) return
        val s = Hub.ledger.value
        val p = Stats.current(s)
        val e = Stats.entries(s, p, Hub.identity.value.myMemberId)
        val (total, lines) = Stats.budgets(s, e)
        for (line in listOfNotNull(total) + lines) {
            val name = line.categoryId?.let { s.category(it)?.name } ?: "이번 달 예산"
            for ((level, ratio) in listOf(100 to 1.0f, 80 to 0.8f)) {
                if (line.ratio < ratio) continue
                if (!Hub.local.once("budget.${line.categoryId ?: "total"}.${p.key}.$level")) break
                Notifier.budget(context, "budget.${line.categoryId}.$level", budgetTitle(name, level),
                    "${Fmt.won(line.spent)} / ${Fmt.won(line.budget)} · ${Fmt.month(p.label)}")
                break
            }
        }
    }

    /**
     * 하루 한 번(앱 시작·작업 관리자): 고정지출 기록, 백업 알림, 간편결제 알림 다시 묶기.
     * 일마다 따로 감싸, 하나가 예외를 내도 앱 범위 코루틴이 죽거나 나머지 일이 빠지지 않게 합니다(#19).
     */
    fun daily(context: Context) {
        step("월 용돈 규칙") { AllowancePlan.repaired(Hub.ledger.value).forEach { Hub.ledger.saveRecurring(it) } }
        step("고정지출") {
            val made = Recurrer.run(Hub.ledger, Hub.db, Hub.identity.installId)
            if (made.isNotEmpty() && Hub.settings.value.notifyRecurring) {
                val names = made.joinToString(", ") { it.merchant.ifBlank { Fixed.label(it.kind) } }
                Notifier.recurring(context, "${Fixed.label(made.map { it.kind })} ${made.size}건을 기록했어요", names)
            }
        }
        step("백업 알림") { backupReminder(context) }
        // 후보로 남은 간편결제 알림 중 이미 기록된 결제와 같은 것을 묶습니다(DECISIONS.md D-5).
        step("간편결제 다시 묶기") { Hub.capture.rejoinCandidates() }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w("doran", "하루 일 '$name' 실패", e)
        }
    }

    private fun backupReminder(context: Context) {
        val last = Hub.settings.value.lastBackupAt
        val hasData = Hub.ledger.value.txns.size >= 20
        if (hasData && System.currentTimeMillis() - last > 30L * 86_400_000 &&
            Hub.local.once("backup.remind.${LocalDate.now(Fmt.zone).let { "${it.year}${it.monthValue}" }}")) {
            Notifier.system(context, 5001, "백업할 때가 됐어요", "두 폰을 모두 잃어도 되살릴 수 있게 설정 → 백업에서 파일로 저장해 두세요")
        }
        // 한 번도 백업하지 않았는데 내역이 100건을 넘으면 한 번 더 권합니다.
        if (last == 0L && Hub.ledger.value.txns.size >= 100 && Hub.local.once("backup.first100")) {
            Notifier.system(context, 5002, "내역이 100건을 넘었어요", "폰을 바꾸거나 잃어도 되살릴 수 있게 설정 → 백업에서 파일로 저장해 두세요")
        }
    }
}

/** 고정지출을 정해진 날 장부에 넣습니다. id가 (규칙, 날짜)로 고정이라 두 폰이 같이 만들어도 한 건입니다. */
object Recurrer {
    fun idFor(ruleId: String, d: LocalDate) = "rc.$ruleId.${d.toString().replace("-", "")}"

    fun run(ledger: com.jeiel85.doran.data.Ledger, db: com.jeiel85.doran.data.Db, installId: String,
            today: LocalDate = LocalDate.now(Fmt.zone)): List<Txn> {
        val s = ledger.value
        val made = ArrayList<Txn>()
        for (r in s.recurrings.values) {
            // 처음 만든 규칙이 몇 년 전부터 쏟아지지 않게 최대 60일 전까지만.
            for ((nominal, d) in Stats.occurrences(r, today.minusDays(60), today, s.household.monthStartDay)) {
                val id = idFor(r.id, d)
                if (db.entity(T.TXN, id) != null) continue // 지운 것(묘비)도 다시 만들지 않음
                // 주말이라 앞당긴 용돈(D-27)은 옛 버전이 정한 날 id로 만들었다가 지운 것도 봅니다. 묘비는 장부 상태에 없어
                // 아래 '가까운 내역' 검사로는 못 찾아, 금요일 id로 되살아나기 때문입니다(#53 리뷰).
                if (nominal != d && db.entity(T.TXN, idFor(r.id, nominal)) != null) continue
                val at = Fmt.millis(d.atTime(9, 0))
                val near = 3L * 86_400_000
                // 매달 규칙은 같은 가계부 기간에 이미 이어진 내역이 있으면(날짜가 며칠 달라도) 다시 만들지 않습니다.
                val period = if (r.period == "m") Stats.periodOf(d, s.household.monthStartDay) else null
                if (ledger.value.txns.values.any { it.recurringId == r.id && (kotlin.math.abs(it.at - at) <= near || (period != null && it.at in period)) }) continue
                // 카드 알림으로 이미 들어온 같은 금액이 있으면 그 거래에 규칙만 이어 줍니다.
                val captured = ledger.value.txns.values.firstOrNull {
                    it.recurringId == null && !it.personal && it.kind == r.kind && it.amount == r.amount && it.cancelOf == null &&
                        kotlin.math.abs(it.at - at) <= 3L * 86_400_000 && (r.accountId == null || it.accountId == r.accountId)
                }
                if (captured != null) {
                    ledger.saveTxn(captured.copy(recurringId = r.id, categoryId = r.categoryId ?: captured.categoryId))
                    continue
                }
                val t = Txn(id = id, kind = r.kind, amount = r.amount, at = at, accountId = r.accountId, categoryId = r.categoryId,
                    merchant = r.name, memberId = r.memberId, source = Source.RECURRING, recurringId = r.id,
                    createdBy = installId, createdAt = System.currentTimeMillis())
                ledger.saveTxn(t)
                made += t
            }
        }
        return made
    }
}
