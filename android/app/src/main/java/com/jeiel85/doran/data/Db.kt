package com.jeiel85.doran.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 로컬 저장소. 장부 엔티티는 동기화 op와 같은 JSON으로 한 테이블에 둡니다 (DECISIONS.md D-2).
 * 앱 데이터 백업은 꺼져 있고(manifest), 복구는 암호 백업 파일로만 합니다 (D-11).
 */
class Db(context: Context) : SQLiteOpenHelper(context, "doran.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE entities (
                 type TEXT NOT NULL, id TEXT NOT NULL, hlc TEXT NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
                 json TEXT NOT NULL, PRIMARY KEY (type, id))""")
        // 올려야 할 로컬 변경. 올릴 때 그 순간의 최신 상태를 읽어 보냅니다.
        db.execSQL("CREATE TABLE outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, id TEXT NOT NULL, UNIQUE (type, id))")
        // 보내기 링크(다른 가구)로 갈 op — 이미 그 링크 키로 봉해 둔 것.
        db.execSQL("CREATE TABLE fwd_outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, space TEXT NOT NULL, ek TEXT NOT NULL, h TEXT NOT NULL, env TEXT NOT NULL)")
        // 아직 키가 없어 열지 못한 봉투(링크 엔티티가 늦게 오는 경우 등). 키 고리가 바뀌면 다시 시도합니다.
        db.execSQL("CREATE TABLE pending_env (ek TEXT PRIMARY KEY, h TEXT NOT NULL, env TEXT NOT NULL, kid TEXT, at INTEGER NOT NULL)")
        // 수집한 알림 원문 — 이 폰에만, 30일 (DECISIONS.md D-4).
        db.execSQL(
            """CREATE TABLE capture_log (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, source TEXT NOT NULL,
                 pkg TEXT, title TEXT, text TEXT NOT NULL, result TEXT NOT NULL, txn_id TEXT,
                 status TEXT NOT NULL DEFAULT 'done', label TEXT)""")
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2(0.1.2): "최근 받은 알림"에서 골라 보내기 — 기록하지 못한 금액 알림(candidate)을 남깁니다. 기존 행은 모두 done.
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE capture_log ADD COLUMN status TEXT NOT NULL DEFAULT 'done'")
            db.execSQL("ALTER TABLE capture_log ADD COLUMN label TEXT")
        }
    }

    data class Row(val type: String, val id: String, val hlc: String, val deleted: Boolean, val json: String)

    fun allEntities(): List<Row> = readableDatabase.rawQuery("SELECT type, id, hlc, deleted, json FROM entities", null).use { c ->
        buildList { while (c.moveToNext()) add(Row(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getString(4))) }
    }

    fun entity(type: String, id: String): Row? =
        readableDatabase.rawQuery("SELECT type, id, hlc, deleted, json FROM entities WHERE type = ? AND id = ?", arrayOf(type, id)).use { c ->
            if (c.moveToFirst()) Row(c.getString(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getString(4)) else null
        }

    fun writeEntity(db: SQLiteDatabase, row: Row) {
        db.insertWithOnConflict("entities", null, ContentValues().apply {
            put("type", row.type); put("id", row.id); put("hlc", row.hlc); put("deleted", if (row.deleted) 1 else 0); put("json", row.json)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun enqueue(db: SQLiteDatabase, type: String, id: String) {
        db.execSQL("INSERT OR IGNORE INTO outbox (type, id) VALUES (?, ?)", arrayOf(type, id))
    }

    fun outbox(limit: Int): List<Triple<Long, String, String>> =
        readableDatabase.rawQuery("SELECT seq, type, id FROM outbox ORDER BY seq LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getLong(0), c.getString(1), c.getString(2))) }
        }

    /** 올리는 사이에 같은 엔티티가 또 바뀌었으면 그 행은 새 seq로 남아야 하므로 seq까지 맞춰 지웁니다. */
    fun ackOutbox(seqs: List<Long>) {
        if (seqs.isEmpty()) return
        writableDatabase.execSQL("DELETE FROM outbox WHERE seq IN (${seqs.joinToString(",")})")
    }

    fun outboxCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox", null).use { it.moveToFirst(); it.getInt(0) } +
        readableDatabase.rawQuery("SELECT COUNT(*) FROM fwd_outbox", null).use { it.moveToFirst(); it.getInt(0) }

    data class Fwd(val seq: Long, val space: String, val ek: String, val h: String, val env: String)

    fun addForward(space: String, ek: String, h: String, env: String) {
        writableDatabase.insert("fwd_outbox", null, ContentValues().apply { put("space", space); put("ek", ek); put("h", h); put("env", env) })
    }

    fun forwards(limit: Int): List<Fwd> = readableDatabase.rawQuery(
        "SELECT seq, space, ek, h, env FROM fwd_outbox ORDER BY seq LIMIT $limit", null).use { c ->
        buildList { while (c.moveToNext()) add(Fwd(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))) }
    }

    fun ackForwards(seqs: List<Long>) {
        if (seqs.isEmpty()) return
        writableDatabase.execSQL("DELETE FROM fwd_outbox WHERE seq IN (${seqs.joinToString(",")})")
    }

    fun dropForwards(space: String) = writableDatabase.delete("fwd_outbox", "space = ?", arrayOf(space))

    data class Pending(val ek: String, val h: String, val env: String)

    fun putPending(db: SQLiteDatabase, ek: String, h: String, env: String, kid: String?) {
        db.insertWithOnConflict("pending_env", null, ContentValues().apply {
            put("ek", ek); put("h", h); put("env", env); put("kid", kid); put("at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun pending(): List<Pending> = readableDatabase.rawQuery("SELECT ek, h, env FROM pending_env", null).use { c ->
        buildList { while (c.moveToNext()) add(Pending(c.getString(0), c.getString(1), c.getString(2))) }
    }

    fun removePending(db: SQLiteDatabase, ek: String) = db.delete("pending_env", "ek = ?", arrayOf(ek))

    /** 배우자의 용돈 op처럼 끝내 열 수 없는 봉투는 90일 뒤 버립니다. */
    fun prunePending(before: Long) = writableDatabase.delete("pending_env", "at < ?", arrayOf(before.toString()))

    fun kv(k: String): String? = readableDatabase.rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf(k)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun setKv(db: SQLiteDatabase, k: String, v: String) {
        db.insertWithOnConflict("kv", null, ContentValues().apply { put("k", k); put("v", v) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun setKv(k: String, v: String) = setKv(writableDatabase, k, v)

    // --- 수집 기록 ----------------------------------------------------------------------------

    data class CaptureRow(
        val id: Long, val at: Long, val source: String, val pkg: String?, val title: String?, val text: String,
        val result: String, val txnId: String?, val status: String = "done", val label: String? = null,
    ) {
        val isCandidate get() = status == "candidate"
    }

    fun logCapture(at: Long, source: String, pkg: String?, title: String?, text: String, result: String, txnId: String?,
                   status: String = "done", label: String? = null): Long {
        val id = writableDatabase.insert("capture_log", null, ContentValues().apply {
            put("at", at); put("source", source); put("pkg", pkg); put("title", title); put("text", text); put("result", result)
            put("txn_id", txnId); put("status", status); put("label", label)
        })
        writableDatabase.delete("capture_log", "at < ?", arrayOf((at - 30L * 86_400_000).toString()))
        return id
    }

    /** 처음 보는 문자면 기억하고 true. 문자 앱이 같은 문자를 다시 올린 알림을 건너뛰는 데 씁니다(최근 300건). */
    @Synchronized
    fun markSeenSms(from: String, at: Long, body: String): Boolean {
        val key = "$from|${at / 1000}|${body.hashCode()}"
        val a = kv("sms.seen")?.let { runCatching { org.json.JSONArray(it) }.getOrNull() } ?: org.json.JSONArray()
        val list = (0 until a.length()).map { a.getString(it) }
        if (key in list) return false
        setKv("sms.seen", org.json.JSONArray((list + key).takeLast(300)).toString())
        return true
    }

    /** 같은 발신처의 같은 원문이 이미 후보로 남아 있는지(반복 안내가 쌓이지 않게). */
    fun hasCandidate(source: String, pkg: String?, text: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM capture_log WHERE status = 'candidate' AND source = ? AND IFNULL(pkg, '') = ? AND text = ? LIMIT 1",
        arrayOf(source, pkg ?: "", text),
    ).use { it.moveToFirst() }

    /** 발신처마다 후보는 최근 [keep]건만 남깁니다. */
    fun trimCandidates(source: String, pkg: String?, keep: Int) {
        writableDatabase.execSQL(
            "DELETE FROM capture_log WHERE status = 'candidate' AND source = ? AND IFNULL(pkg, '') = ? AND id NOT IN " +
                "(SELECT id FROM capture_log WHERE status = 'candidate' AND source = ? AND IFNULL(pkg, '') = ? ORDER BY at DESC LIMIT ?)",
            arrayOf<Any>(source, pkg ?: "", source, pkg ?: "", keep),
        )
    }

    fun updateCapture(id: Long, status: String, result: String, txnId: String?) {
        writableDatabase.update("capture_log", ContentValues().apply { put("status", status); put("result", result); put("txn_id", txnId) },
            "id = ?", arrayOf(id.toString()))
    }

    private val captureCols = "id, at, source, pkg, title, text, result, txn_id, status, label"
    private fun readCapture(c: android.database.Cursor) = CaptureRow(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
        c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9))

    fun captures(limit: Int = 200): List<CaptureRow> = readableDatabase.rawQuery(
        "SELECT $captureCols FROM capture_log ORDER BY at DESC LIMIT $limit", null).use { c ->
        buildList { while (c.moveToNext()) add(readCapture(c)) }
    }

    fun capture(id: Long): CaptureRow? = readableDatabase.rawQuery(
        "SELECT $captureCols FROM capture_log WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) readCapture(c) else null }

    fun candidateCount(since: Long): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM capture_log WHERE status = 'candidate' AND at >= ?", arrayOf(since.toString())).use { it.moveToFirst(); it.getInt(0) }

    fun clearCaptures() = writableDatabase.delete("capture_log", null, null)

    /** 백업 복원·가구 나가기: 장부와 동기화 상태를 비웁니다(수집 기록은 이 폰 것이라 남김). */
    fun wipeLedger() {
        writableDatabase.transaction {
            listOf("entities", "outbox", "fwd_outbox", "pending_env").forEach { delete(it, null, null) }
            delete("kv", "k LIKE 'sync.%'", null)
        }
    }
}

inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        val r = block()
        setTransactionSuccessful()
        return r
    } finally {
        endTransaction()
    }
}
