package com.jeiel85.doran.sync

import com.jeiel85.doran.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class RelayError(val status: Int, val code: String) : IOException("relay $status $code")

/** 중계 서버 HTTP 클라이언트 (design/PROTOCOL.md). 모든 호출은 IO 스레드에서. */
class Relay(private val base: String = BuildConfig.RELAY_URL, private val token: () -> String?) {

    private fun call(method: String, path: String, body: String? = null, auth: Boolean = true, contentType: String = "application/json"): String {
        val conn = (URL(base.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "doran-android/${BuildConfig.VERSION_NAME}")
            if (auth) token()?.let { setRequestProperty("Authorization", "Bearer $it") } ?: throw RelayError(401, "no_token")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "$contentType; charset=utf-8")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val err = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty().ifEmpty { "http_$code" }
                throw RelayError(code, err)
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun json(method: String, path: String, body: JSONObject? = null, auth: Boolean = true) =
        JSONObject(call(method, path, body?.toString(), auth).ifEmpty { "{}" })

    data class Registered(val deviceId: String, val token: String)

    fun register(fcmToken: String?): Registered {
        val r = json("POST", "/v1/devices", JSONObject().put("fcmToken", fcmToken ?: JSONObject.NULL), auth = false)
        return Registered(r.getString("deviceId"), r.getString("token"))
    }

    fun updateFcm(fcmToken: String?) { json("PUT", "/v1/devices/me", JSONObject().put("fcmToken", fcmToken ?: JSONObject.NULL)) }
    fun unregister() { json("DELETE", "/v1/devices/me") }

    fun createSpace(): String = json("POST", "/v1/spaces").getString("spaceId")

    data class SpaceMember(val deviceId: String, val role: String, val createdAt: Long, val seenAt: Long, val push: Boolean, val me: Boolean)
    data class SpaceInfo(val members: List<SpaceMember>, val ops: Long, val blobBytes: Long, val blobQuota: Long)

    fun space(spaceId: String): SpaceInfo {
        val r = json("GET", "/v1/spaces/$spaceId")
        val a = r.getJSONArray("members")
        return SpaceInfo(
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                SpaceMember(it.getString("deviceId"), it.getString("role"), it.optLong("createdAt"), it.optLong("seenAt"),
                    it.optBoolean("push"), it.optBoolean("me"))
            },
            r.optLong("ops"), r.optLong("blobBytes"), r.optLong("blobQuota"),
        )
    }

    fun removeMember(spaceId: String, deviceId: String) { json("DELETE", "/v1/spaces/$spaceId/members/$deviceId") }

    fun createInvite(spaceId: String, inviteId: String, role: String, bundle: String, ttlSec: Int): Long =
        json("POST", "/v1/spaces/$spaceId/invites", JSONObject().put("inviteId", inviteId).put("role", role)
            .put("bundle", bundle).put("ttlSec", ttlSec)).getLong("expiresAt")

    fun cancelInvite(spaceId: String, inviteId: String) { json("DELETE", "/v1/spaces/$spaceId/invites/$inviteId") }

    data class Claimed(val spaceId: String, val role: String, val bundle: String)

    fun claim(inviteId: String): Claimed {
        val r = json("POST", "/v1/invites/$inviteId/claim")
        return Claimed(r.getString("spaceId"), r.getString("role"), r.getString("bundle"))
    }

    data class OutOp(val ek: String, val h: String, val env: String)

    fun push(spaceId: String, ops: List<OutOp>): JSONObject =
        json("POST", "/v1/spaces/$spaceId/ops", JSONObject().put("ops", JSONArray(ops.map {
            JSONObject().put("ek", it.ek).put("h", it.h).put("env", it.env)
        })))

    data class InOp(val seq: Long, val ek: String, val h: String, val env: String)
    data class Page(val ops: List<InOp>, val next: Long, val more: Boolean)

    fun pull(spaceId: String, after: Long, limit: Int = 500): Page {
        val r = json("GET", "/v1/spaces/$spaceId/ops?after=$after&limit=$limit")
        val a = r.getJSONArray("ops")
        return Page(
            (0 until a.length()).map { a.getJSONObject(it) }.map { InOp(it.getLong("seq"), it.getString("ek"), it.getString("h"), it.getString("env")) },
            r.getLong("next"), r.optBoolean("more"),
        )
    }

    fun putBlob(spaceId: String, id: String, env: String) { call("PUT", "/v1/spaces/$spaceId/blobs/$id", env, contentType = "text/plain") }
    fun getBlob(spaceId: String, id: String): String = call("GET", "/v1/spaces/$spaceId/blobs/$id")
    fun deleteBlob(spaceId: String, id: String) { call("DELETE", "/v1/spaces/$spaceId/blobs/$id") }
}
