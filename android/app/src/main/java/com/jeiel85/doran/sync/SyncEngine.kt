package com.jeiel85.doran.sync

import android.util.Log
import com.jeiel85.doran.BuildConfig
import com.jeiel85.doran.crypto.Crypto
import com.jeiel85.doran.data.Change
import com.jeiel85.doran.data.Db
import com.jeiel85.doran.data.Identity
import com.jeiel85.doran.data.Ledger
import com.jeiel85.doran.data.RemoteRow
import com.jeiel85.doran.data.transaction
import com.jeiel85.doran.model.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SyncStatus(
    val running: Boolean = false,
    val lastOkAt: Long = 0,
    val error: String? = null,
    val pending: Int = 0,
    /** 서버에서 이 기기가 가구에서 빠졌다고 알려 옴(다른 가족이 내보냄). 장부는 그대로 둡니다. */
    val removed: Boolean = false,
)

/**
 * 장부 동기화 (DECISIONS.md D-2·D-3, design/PROTOCOL.md).
 * 올리기: outbox의 엔티티를 지금 상태 그대로 봉해서 보냄. 받기: 커서 이후 op를 열어 HLC로 합침.
 */
class SyncEngine(
    private val db: Db,
    private val ledger: Ledger,
    private val identity: Identity,
    private val relay: Relay,
    /** 받는 가구가 이 폰의 보내기 링크를 끊었을 때(그 가구 이름). 부모님 폰에 알려 줍니다(#10). */
    private val onLinkLost: (String) -> Unit = {},
    /** 동기화로 받은 변경을 알림·요약 갱신 등에 넘깁니다. */
    private val onRemote: (List<Change>) -> Unit,
) {
    private val lock = Mutex()
    private val _status = MutableStateFlow(SyncStatus(lastOkAt = db.kv("sync.lastOk")?.toLongOrNull() ?: 0))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val realtime: Boolean get() = BuildConfig.HAS_FIREBASE && identity.value.fcmToken != null

    fun refreshPending() { _status.value = _status.value.copy(pending = db.outboxCount()) }

    suspend fun sync(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            _status.value = _status.value.copy(running = true, pending = db.outboxCount())
            val ok = try {
                runOnce()
                true
            } catch (e: RelayError) {
                Log.w(TAG, "sync relay error ${e.status} ${e.code}")
                _status.value = _status.value.copy(error = describe(e))
                recordFailure("${describe(e)} (${e.status} ${e.code})")
                false
            } catch (e: Exception) {
                Log.w(TAG, "sync failed", e)
                _status.value = _status.value.copy(error = "인터넷 연결을 확인해 주세요")
                recordFailure("연결 실패 (${e.javaClass.simpleName})")
                false
            }
            val now = System.currentTimeMillis()
            if (ok) db.setKv("sync.lastOk", now.toString())
            _status.value = _status.value.copy(
                running = false, pending = db.outboxCount(),
                lastOkAt = if (ok) now else _status.value.lastOkAt, error = if (ok) null else _status.value.error,
            )
            ok
        }
    }

    /** 최근 동기화 실패 10건(시각·이유). 설정 화면에서 보여 줍니다. */
    fun recentFailures(): List<Pair<Long, String>> {
        val a = db.kv("sync.failures")?.let { runCatching { org.json.JSONArray(it) }.getOrNull() } ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { it.optLong("at") to it.optString("msg") }
    }

    private fun recordFailure(msg: String) {
        val list = recentFailures() + (System.currentTimeMillis() to msg)
        db.setKv("sync.failures", org.json.JSONArray(list.takeLast(10).map { (at, m) -> JSONObject().put("at", at).put("msg", m) }).toString())
    }

    private fun describe(e: RelayError) = when {
        e.status == 429 -> "잠시 뒤 다시 시도할게요"
        e.status >= 500 -> "동기화 서버가 잠시 응답하지 않아요"
        else -> "동기화하지 못했어요 (${e.code})"
    }

    private fun runOnce() {
        ensureRegistered()
        val id = identity.value
        // 가계부를 오프라인으로 먼저 시작하면 그때는 기기 id가 없어 구성원에 못 넣었습니다. 등록된 뒤 채워 둡니다.
        val me = id.myMemberId?.let { ledger.value.member(it) }
        if (me != null && id.deviceId != null && id.deviceId !in me.deviceIds) ledger.saveMember(me.copy(deviceIds = me.deviceIds + id.deviceId))
        if (id.inHousehold) {
            val space = id.spaceId ?: createSpace()
            try {
                try {
                    pushOutbox(space)
                    pull(space)
                } catch (e: RelayError) {
                    // 중계가 이 기기를 모른다고 확실히 답하면(정리된 기기, D-41) 다시 등록하고 한 번 더 — 그래야 가구가 정리된 경우의
                    // 404로 이어져 아래에서 되살립니다(#101 리뷰). 다른 401은 그대로 올립니다(#9).
                    if (e.status != 401 || e.code != "unauthorized") throw e
                    reRegister()
                    pushOutbox(space)
                    pull(space)
                }
                if (_status.value.removed) _status.value = _status.value.copy(removed = false)
            } catch (e: RelayError) {
                if (e.status == 404 && e.code == "space_not_found") {
                    // 혼자 쓰던 가계부(함께 쓰는 사람이 나뿐)가 중계에서 정리됐으면(오래 접속이 없던 기기, D-41) 새 가구를 만들어
                    // 이 폰의 장부를 그대로 다시 올립니다(#99). 가족이 있는 가계부에서 빠진 것이면 예전처럼 '빠졌어요'로 알립니다.
                    if (soloHere()) {
                        val fresh = createSpace()
                        pushOutbox(fresh)
                        pull(fresh)
                        if (_status.value.removed) _status.value = _status.value.copy(removed = false)
                        pushForwards()
                        return
                    }
                    _status.value = _status.value.copy(removed = true)
                    throw RelayError(404, "removed")
                }
                throw e
            }
        }
        pushForwards()
    }

    private fun ensureRegistered() {
        val fcm = identity.value.fcmToken
        if (identity.token == null || identity.value.deviceId == null) {
            val r = relay.register(fcm)
            identity.setDevice(r.deviceId, r.token)
            identity.fcmDirty = false
            return
        }
        if (identity.fcmDirty) {
            try {
                relay.updateFcm(fcm)
                identity.fcmDirty = false
            } catch (e: RelayError) {
                // 중계가 이 기기를 모른다고 확실히 답할 때만 새로 등록합니다. 다른 401(프록시·오류 페이지)에
                // 기기 id를 버리면 모든 가구 연결을 잃습니다(#9).
                if (e.status == 401 && e.code == "unauthorized") reRegister() else throw e
            }
        }
    }

    /** 서버가 이 기기를 잊었으면(401) 새로 등록합니다. 가구 membership은 다시 초대받아야 합니다. */
    private fun reRegister() {
        identity.clearDevice()
        ensureRegistered()
    }

    /**
     * 이 장부에 나 말고 아무도 없는지. 보내 주는 가족(부모님 폰)이 있으면 그 폰들은 옛 가구로 보내고 있어 새 가구를 만들면 끊기므로
     * 저절로 다시 만들지 않습니다.
     */
    private fun soloHere(): Boolean {
        val me = identity.value.myMemberId ?: return false
        return ledger.value.activeMembers.none { it.id != me }
    }

    /** 오프라인으로 시작한 가계부를 서버에 처음 올림: 가구를 만들고 모든 엔티티를 올릴 목록에 넣습니다. */
    private fun createSpace(): String {
        val space = relay.createSpace()
        identity.setSpace(space)
        enqueueAll()
        return space
    }

    /** 합류·복원 직후: 이 폰의 장부(기본 카테고리 제외)를 모두 올려 가구 장부와 합칩니다. */
    fun enqueueAll() {
        db.writableDatabase.transaction {
            for (r in db.allEntities()) if (r.hlc != com.jeiel85.doran.core.Hlc.SEED) db.enqueue(this, r.type, r.id)
        }
    }

    private fun pushOutbox(space: String) {
        val hk = identity.householdKey ?: return
        val pk = identity.personalKey
        while (true) {
            val batch = db.outbox(100)
            if (batch.isEmpty()) return
            val ops = ArrayList<Relay.OutOp>()
            for ((_, type, id) in batch) {
                val row = ledger.row(type, id) ?: continue
                val json = if (row.deleted) null else JSONObject(row.json)
                // 용돈 거래는 개인 키로만 (DECISIONS.md D-7).
                val isPrivate = type == T.TXN && json?.optBoolean("private") == true && pk != null
                // 공유하던 거래를 용돈으로 돌렸으면 배우자 폰의 사본도 지워야 하므로 가구 키로 묘비를 함께 보냅니다.
                // 묘비 HLC는 같은 시각의 가장 낮은 값이라 옛 공유본보다는 새롭고, 내 다른 기기에서는 용돈 op에 집니다.
                // 처음부터 용돈이던 거래는 묘비를 보내지 않습니다(가족이 개인 거래 수를 알 수 없게).
                if (isPrivate && json?.optBoolean("wasShared") == true) ops += seal(space, hk, type, id, com.jeiel85.doran.core.Hlc.lowestAt(row.hlc), true, null)
                ops += seal(space, if (isPrivate) pk!! else hk, type, id, row.hlc, row.deleted, json)
            }
            if (ops.isNotEmpty()) relay.push(space, ops)
            db.ackOutbox(batch.map { it.first })
            _status.value = _status.value.copy(pending = db.outboxCount())
        }
    }

    fun seal(space: String, key: ByteArray, type: String, id: String, h: String, deleted: Boolean, json: JSONObject?): Relay.OutOp {
        val ek = Crypto.entityKey(key, type, id)
        val plain = JSONObject().put("t", type).put("id", id).put("h", h).put("d", deleted)
            .put("v", json ?: JSONObject.NULL).put("by", identity.installId)
        return Relay.OutOp(ek, h, Crypto.seal(key, plain.toString().toByteArray(), Crypto.opAad(space, ek)))
    }

    private fun pushForwards() {
        while (true) {
            val batch = db.forwards(100)
            if (batch.isEmpty()) return
            for ((space, items) in batch.groupBy { it.space }) {
                try {
                    relay.push(space, items.map { Relay.OutOp(it.ek, it.h, it.env) })
                    db.ackForwards(items.map { it.seq })
                } catch (e: RelayError) {
                    if (e.status == 404 && e.code == "space_not_found") {
                        // 받는 가구가 이 링크를 끊었음: 더 보내지 않고 링크를 지웁니다. 다른 404(중계 배포 실수·오류
                        // 페이지)에는 지우지 않고 다음에 다시 보냅니다(#9).
                        val label = identity.value.outLinks.firstOrNull { it.spaceId == space }?.label
                        db.dropForwards(space)
                        identity.removeOutLink(space)
                        if (label != null) onLinkLost(label)
                    } else throw e
                }
            }
        }
    }

    private data class Opened(val row: RemoteRow, val viaLink: com.jeiel85.doran.model.Link?)

    /** kid로 키를 고르고 열어 봅니다. 링크 키로 연 op는 보낸 가족에게 허락된 것만 받습니다(아래 allowed). */
    private fun open(space: String, ek: String, env: String): Opened? {
        val kid = Crypto.kidOf(env) ?: return null
        val hk = identity.householdKey
        val pk = identity.personalKey
        val link = ledger.value.links.values.firstOrNull { !it.revoked && Crypto.kid(Crypto.unb64(it.key)) == kid }
        val key = when {
            hk != null && Crypto.kid(hk) == kid -> hk
            pk != null && Crypto.kid(pk) == kid -> pk
            link != null -> Crypto.unb64(link.key)
            else -> return null
        }
        val plain = Crypto.open(key, env, Crypto.opAad(space, ek)) ?: return null
        val o = JSONObject(String(plain))
        val type = o.getString("t")
        val id = o.getString("id")
        if (Crypto.entityKey(key, type, id) != ek) return null
        val deleted = o.optBoolean("d")
        val v = if (deleted || o.isNull("v")) null else o.getJSONObject("v")
        return Opened(RemoteRow(type, id, o.getString("h"), deleted, v), link)
    }

    /** 보낸 가족(sender)이 링크 키로 올릴 수 있는 것: 자기 이름으로 된 거래와 자기 카드 계좌뿐. */
    private fun allowed(o: Opened): Boolean {
        val link = o.viaLink ?: return true
        val v = o.row.json ?: return false
        return when (o.row.type) {
            T.TXN -> v.optString("memberId") == link.memberId && !v.optBoolean("private")
            T.ACCOUNT -> v.optString("ownerId") == link.memberId
            else -> false
        }
    }

    private fun pull(space: String) {
        val cursorKey = "sync.cursor.$space"
        var after = db.kv(cursorKey)?.toLongOrNull() ?: 0
        val all = ArrayList<Change>()
        // 지난번에 열지 못한 봉투부터(그사이 링크 키가 왔을 수 있음).
        retryPending(space, all)
        while (true) {
            val page = relay.pull(space, after)
            val rows = ArrayList<RemoteRow>()
            val unopened = ArrayList<Relay.InOp>()
            for (op in page.ops) {
                val opened = runCatching { open(space, op.ek, op.env) }.getOrNull()
                when {
                    opened == null -> unopened += op
                    allowed(opened) -> rows += opened.row
                    else -> Log.w(TAG, "dropped op outside sender scope")
                }
            }
            val next = page.next
            all += ledger.applyRemote(rows) { tx ->
                for (u in unopened) db.putPending(tx, u.ek, u.h, u.env, Crypto.kidOf(u.env))
                db.setKv(tx, cursorKey, next.toString())
            }
            after = next
            // 이번 쪽에서 링크가 새로 왔으면 같은 쪽의 sender op를 바로 열 수 있습니다.
            if (rows.any { it.type == T.LINK } && unopened.isNotEmpty()) retryPending(space, all)
            if (!page.more) break
        }
        db.prunePending(System.currentTimeMillis() - 90L * 86_400_000)
        if (all.isNotEmpty()) onRemote(all)
    }

    private fun retryPending(space: String, sink: MutableList<Change>) {
        val pend = db.pending()
        if (pend.isEmpty()) return
        val rows = ArrayList<RemoteRow>()
        val done = ArrayList<String>()
        for (p in pend) {
            val opened = runCatching { open(space, p.ek, p.env) }.getOrNull() ?: continue
            done += p.ek
            if (allowed(opened)) rows += opened.row
        }
        if (done.isEmpty()) return
        sink += ledger.applyRemote(rows) { tx -> done.forEach { db.removePending(tx, it) } }
    }

    companion object { private const val TAG = "Sync" }
}
