package com.jeiel85.doran.feature

import android.content.Context
import android.net.Uri
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.crypto.Crypto
import com.jeiel85.doran.data.RemoteRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 비밀번호로 암호화한 백업 파일 (DECISIONS.md D-11).
 * 형식: JSON {"doran":"backup","v":1,"kdf":{"alg":"pbkdf2-sha256","iter":N,"salt":b64},"data":봉투}
 * data 평문 = {"entities":[{type,id,hlc,deleted,json}], "keys":{...}, "at":ms}
 */
object Backup {
    private const val ITERATIONS = 310_000
    private val AAD = "doran1|backup".toByteArray()

    suspend fun export(context: Context, uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        val entities = JSONArray()
        for (r in Hub.db.allEntities()) {
            entities.put(JSONObject().put("type", r.type).put("id", r.id).put("hlc", r.hlc).put("deleted", r.deleted).put("json", r.json))
        }
        val plain = JSONObject().put("entities", entities).put("keys", Hub.identity.exportKeys()).put("at", System.currentTimeMillis())
            .put("rules", JSONArray(Hub.local.rules.value.map { it.json() }))
        val salt = Crypto.randomBytes(16)
        val key = Crypto.passwordKey(password, salt, ITERATIONS)
        val file = JSONObject().put("doran", "backup").put("v", 1)
            .put("kdf", JSONObject().put("alg", "pbkdf2-sha256").put("iter", ITERATIONS).put("salt", Crypto.b64(salt)))
            .put("data", Crypto.seal(key, plain.toString().toByteArray(), AAD))
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(file.toString().toByteArray()) }
            ?: error("파일을 열 수 없어요")
        Hub.local.update { it.copy(lastBackupAt = System.currentTimeMillis()) }
    }

    class WrongPassword : Exception("비밀번호가 맞지 않아요")

    data class Summary(val entities: Int, val txns: Int, val at: Long)

    /**
     * 복원: 백업의 엔티티를 HLC 기준으로 합칩니다(더 새것이 이김). 키 고리도 되살리고, 가구는 서버에 새로 만들어
     * 전부 다시 올립니다 — 잃어버린 옛 폰들이 아직 옛 가구의 member로 남아 있기 때문입니다.
     * 배우자 폰이 살아 있다면 그 폰에서 다시 초대하면 같은 id끼리 합쳐집니다.
     */
    suspend fun restore(context: Context, uri: Uri, password: CharArray): Summary = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("파일을 열 수 없어요")
        val file = runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.optString("doran") == "backup" }
            ?: error("둥지 백업 파일이 아니에요")
        val kdf = file.getJSONObject("kdf")
        val key = Crypto.passwordKey(password, Crypto.unb64(kdf.getString("salt")), kdf.getInt("iter"))
        val plain = Crypto.open(key, file.getString("data"), AAD) ?: throw WrongPassword()
        val o = JSONObject(String(plain))
        val arr = o.getJSONArray("entities")
        val rows = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            RemoteRow(it.getString("type"), it.getString("id"), it.getString("hlc"), it.optBoolean("deleted"),
                if (it.optBoolean("deleted")) null else JSONObject(it.getString("json")))
        }
        val keys = o.getJSONObject("keys")
        val sameHousehold = Hub.identity.value.spaceId != null && Hub.identity.householdKey?.let { mine ->
            keys.optString("hk").takeIf { it.isNotEmpty() }?.let { Crypto.kid(Crypto.unb64(it)) == Crypto.kid(mine) }
        } == true
        // 지금 같은 가구에 연결돼 있으면(지운 내역만 되살리는 경우) 연결은 그대로 둡니다.
        if (!sameHousehold) {
            keys.remove("spaceId")
            Hub.identity.importKeys(keys)
        }
        Hub.ledger.applyRemote(rows)
        o.optJSONArray("rules")?.let { a ->
            Hub.local.importRules((0 until a.length()).mapNotNull { runCatching { com.jeiel85.doran.data.ForwardRule.from(a.getJSONObject(it)) }.getOrNull() })
        }
        Hub.sync.enqueueAll()
        Hub.requestSync()
        Summary(rows.size, rows.count { it.type == "txn" && !it.deleted }, o.optLong("at"))
    }
}
