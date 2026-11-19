package com.jeiel85.doran.data

import com.jeiel85.doran.core.Hlc
import com.jeiel85.doran.model.Account
import com.jeiel85.doran.model.AllowanceSummary
import com.jeiel85.doran.model.Budget
import com.jeiel85.doran.model.Category
import com.jeiel85.doran.model.Defaults
import com.jeiel85.doran.model.Goal
import com.jeiel85.doran.model.Household
import com.jeiel85.doran.model.Link
import com.jeiel85.doran.model.Member
import com.jeiel85.doran.model.MerchantRule
import com.jeiel85.doran.model.Recurring
import com.jeiel85.doran.model.T
import com.jeiel85.doran.model.Txn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** 화면이 보는 장부 전체(불변). 메모리에 통째로 둡니다 (DECISIONS.md D-2). */
data class LedgerState(
    val household: Household = Household(),
    val members: Map<String, Member> = emptyMap(),
    val accounts: Map<String, Account> = emptyMap(),
    val categories: Map<String, Category> = emptyMap(),
    val txns: Map<String, Txn> = emptyMap(),
    val budgets: Map<String, Budget> = emptyMap(),
    val recurrings: Map<String, Recurring> = emptyMap(),
    val goals: Map<String, Goal> = emptyMap(),
    val rules: Map<String, MerchantRule> = emptyMap(),
    val allowances: Map<String, AllowanceSummary> = emptyMap(),
    val links: Map<String, Link> = emptyMap(),
    val version: Long = 0,
) {
    val activeMembers: List<Member> get() = members.values.filter { !it.removed }.sortedWith(compareBy({ it.isSender }, { it.order }, { it.name }))
    fun category(id: String?) = id?.let(categories::get)
    fun account(id: String?) = id?.let(accounts::get)
    fun member(id: String?) = id?.let(members::get)

    /** 취소 엔티티를 원거래에 묶은 표. 원거래 id → 취소된 금액 합. */
    val canceled: Map<String, Long> by lazy {
        txns.values.filter { it.cancelOf != null }.groupBy { it.cancelOf!! }.mapValues { (_, v) -> v.sumOf { it.amount } }
    }

    fun with(type: String, id: String, json: JSONObject?): LedgerState {
        fun <V> Map<String, V>.upd(v: V?): Map<String, V> = if (v == null) this - id else this + (id to v)
        val v = version + 1
        return when (type) {
            T.HOUSEHOLD -> copy(household = json?.let(Household::from) ?: Household(), version = v)
            T.MEMBER -> copy(members = members.upd(json?.let { Member.from(id, it) }), version = v)
            T.ACCOUNT -> copy(accounts = accounts.upd(json?.let { Account.from(id, it) }), version = v)
            T.CATEGORY -> copy(categories = categories.upd(json?.let { Category.from(id, it) }), version = v)
            T.TXN -> copy(txns = txns.upd(json?.let { Txn.from(id, it) }), version = v)
            T.BUDGET -> copy(budgets = budgets.upd(json?.let { Budget.from(id, it) }), version = v)
            T.RECURRING -> copy(recurrings = recurrings.upd(json?.let { Recurring.from(id, it) }), version = v)
            T.GOAL -> copy(goals = goals.upd(json?.let { Goal.from(id, it) }), version = v)
            T.RULE -> copy(rules = rules.upd(json?.let { MerchantRule.from(id, it) }), version = v)
            T.ALLOWANCE -> copy(allowances = allowances.upd(json?.let { AllowanceSummary.from(id, it) }), version = v)
            T.LINK -> copy(links = links.upd(json?.let { Link.from(id, it) }), version = v)
            else -> this
        }
    }
}

/**
 * 장부 변경 알림. local = 이 폰에서 바꿈, remote = 동기화로 받음.
 * [auto] = 사람이 아니라 앱이 스스로 고친 것(같은 결제 묶기 등) — 가맹점 카테고리 규칙으로 배우지 않습니다(#19).
 */
data class Change(val type: String, val id: String, val before: JSONObject?, val after: JSONObject?, val local: Boolean,
                  val auto: Boolean = false)

data class RemoteRow(val type: String, val id: String, val hlc: String, val deleted: Boolean, val json: JSONObject?)

class Ledger(private val db: Db, val hlc: Hlc) {
    private val _state = MutableStateFlow(LedgerState())
    val state: StateFlow<LedgerState> = _state.asStateFlow()
    private val _changes = MutableSharedFlow<List<Change>>(extraBufferCapacity = 64)
    val changes: SharedFlow<List<Change>> = _changes.asSharedFlow()
    val value: LedgerState get() = _state.value

    @Synchronized
    fun load() {
        var s = LedgerState()
        val rows = db.allEntities()
        for (r in rows) {
            if (r.deleted) continue
            runCatching { s = s.with(r.type, r.id, JSONObject(r.json)) }
        }
        if (rows.none { it.type == T.CATEGORY }) s = seed(s)
        _state.value = s
    }

    /** 기본 카테고리. HLC가 SEED라 누가 고치든 고친 쪽이 이기고, 동기화로 보내지 않습니다. */
    private fun seed(start: LedgerState): LedgerState {
        var s = start
        db.writableDatabase.transaction {
            for (c in Defaults.categories) {
                db.writeEntity(this, Db.Row(T.CATEGORY, c.id, Hlc.SEED, false, c.json().toString()))
                s = s.with(T.CATEGORY, c.id, c.json())
            }
        }
        return s
    }

    /**
     * 로컬 변경. 저장된 JSON 위에 덮어써 모르는 필드를 지키고(Model.kt 주석), 같은 트랜잭션에서
     * outbox에 넣어 앱이 죽어도 다음에 올라가게 합니다.
     */
    @Synchronized
    fun put(type: String, id: String, json: JSONObject, auto: Boolean = false) = putAll(listOf(Triple(type, id, json)), auto)

    @Synchronized
    fun putAll(items: List<Triple<String, String, JSONObject>>, auto: Boolean = false) {
        if (items.isEmpty()) return
        val changes = mutableListOf<Change>()
        var s = _state.value
        db.writableDatabase.transaction {
            for ((type, id, json) in items) {
                val old = db.entity(type, id)
                val before = old?.takeIf { !it.deleted }?.let { JSONObject(it.json) }
                val merged = (before?.let { JSONObject(it.toString()) } ?: JSONObject()).also { m -> json.keys().forEach { k -> m.put(k, json.get(k)) } }
                // 공유하던 거래를 용돈으로 돌리면 표시해 두고, 동기화가 가족 폰의 사본을 지우는 묘비를 그때만 보냅니다(SyncEngine).
                if (type == T.TXN && merged.optBoolean("private") && before != null && !before.optBoolean("private")) merged.put("wasShared", true)
                db.writeEntity(this, Db.Row(type, id, hlc.next(), false, merged.toString()))
                db.enqueue(this, type, id)
                s = s.with(type, id, merged)
                changes += Change(type, id, before, merged, local = true, auto = auto)
            }
        }
        _state.value = s
        _changes.tryEmit(changes)
    }

    @Synchronized
    fun delete(type: String, id: String) = deleteAll(listOf(type to id))

    @Synchronized
    fun deleteAll(items: List<Pair<String, String>>) {
        if (items.isEmpty()) return
        val changes = mutableListOf<Change>()
        var s = _state.value
        db.writableDatabase.transaction {
            for ((type, id) in items) {
                val old = db.entity(type, id) ?: continue
                if (old.deleted) continue
                db.writeEntity(this, Db.Row(type, id, hlc.next(), true, "{}"))
                db.enqueue(this, type, id)
                s = s.with(type, id, null)
                changes += Change(type, id, JSONObject(old.json), null, local = true)
            }
        }
        _state.value = s
        _changes.tryEmit(changes)
    }

    /** 받은 op 적용. 더 새 HLC만 이깁니다. 적용한 것만 돌려줍니다. */
    @Synchronized
    fun applyRemote(rows: List<RemoteRow>, afterWrite: (android.database.sqlite.SQLiteDatabase) -> Unit = {}): List<Change> {
        val changes = mutableListOf<Change>()
        var s = _state.value
        db.writableDatabase.transaction {
            for (r in rows) {
                if (r.type !in T.ALL) continue
                hlc.observe(r.hlc)
                val old = db.entity(r.type, r.id)
                if (old != null && old.hlc >= r.hlc) continue
                val json = if (r.deleted) null else r.json ?: continue
                db.writeEntity(this, Db.Row(r.type, r.id, r.hlc, r.deleted, json?.toString() ?: "{}"))
                s = s.with(r.type, r.id, json)
                changes += Change(r.type, r.id, old?.takeIf { !it.deleted }?.let { JSONObject(it.json) }, json, local = false)
            }
            afterWrite(this)
        }
        _state.value = s
        if (changes.isNotEmpty()) _changes.tryEmit(changes)
        return changes
    }

    /** 동기화가 올릴 때 읽는 현재 행. */
    fun row(type: String, id: String): Db.Row? = db.entity(type, id)

    // --- 편의 함수 -------------------------------------------------------------------------------
    fun saveHousehold(h: Household) = put(T.HOUSEHOLD, Household.ID, h.json())
    fun saveMember(m: Member) = put(T.MEMBER, m.id, m.json())
    fun saveAccount(a: Account) = put(T.ACCOUNT, a.id, a.json())
    fun saveCategory(c: Category) = put(T.CATEGORY, c.id, c.json())
    fun saveTxn(t: Txn, auto: Boolean = false) = put(T.TXN, t.id, t.json(), auto)
    fun saveBudget(b: Budget) = if (b.amount <= 0) delete(T.BUDGET, b.id) else put(T.BUDGET, b.id, b.json())
    fun saveRecurring(r: Recurring) = put(T.RECURRING, r.id, r.json())
    fun saveGoal(g: Goal) = put(T.GOAL, g.id, g.json())
    fun saveRule(r: MerchantRule) = put(T.RULE, r.id, r.json())
    fun saveAllowance(a: AllowanceSummary) = put(T.ALLOWANCE, a.id, a.json())
    fun saveLink(l: Link) = put(T.LINK, l.id, l.json())

    /** 거래와, 그 거래를 취소한 취소 엔티티까지 함께 지웁니다. */
    fun deleteTxn(id: String) {
        val cancels = value.txns.values.filter { it.cancelOf == id }.map { T.TXN to it.id }
        deleteAll(listOf(T.TXN to id) + cancels)
    }

    @Synchronized
    fun reset() {
        _state.value = LedgerState()
        load()
    }
}
