package com.jeiel85.doran.data

import android.content.Context
import com.jeiel85.doran.core.newId
import com.jeiel85.doran.crypto.Crypto
import com.jeiel85.doran.crypto.KeystoreCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** 이 폰에서 다른 가구로 보내는 링크(부모님 폰 쪽에서 쓰는 것). */
data class OutLink(
    val spaceId: String,
    val key: ByteArray,
    /** 받는 가구가 정해 준 내 구성원 id — 보낸 거래의 memberId가 됩니다. */
    val memberId: String,
    val label: String,
    /** 받는 가구가 이 폰을 부르는 이름(예: 어머니). */
    val memberName: String? = null,
    /** 보낼 카드사·은행 이름. 비어 있으면 읽은 것 전부. */
    val issuers: List<String> = emptyList(),
    val createdAt: Long,
) {
    fun json(): JSONObject = JSONObject().put("spaceId", spaceId).put("key", Crypto.b64(key)).put("memberId", memberId)
        .put("label", label).put("memberName", memberName ?: JSONObject.NULL).put("issuers", JSONArray(issuers)).put("createdAt", createdAt)
    override fun equals(other: Any?) = other is OutLink && other.spaceId == spaceId && other.memberId == memberId &&
        other.issuers == issuers && other.label == label
    override fun hashCode() = spaceId.hashCode()
    companion object {
        fun from(o: JSONObject) = OutLink(
            spaceId = o.getString("spaceId"), key = Crypto.unb64(o.getString("key")), memberId = o.getString("memberId"),
            label = o.optString("label", "가족"),
            memberName = o.optString("memberName").takeIf { it.isNotEmpty() && it != "null" },
            issuers = o.optJSONArray("issuers")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            createdAt = o.optLong("createdAt"),
        )
    }
}

/** 화면에 보여 줄 연결 상태. 키 자체는 들어 있지 않습니다. */
data class IdState(
    val registered: Boolean = false,
    val deviceId: String? = null,
    /** 내 가구. null이면 아직 가계부를 시작하지 않았거나 보내기 전용 폰. */
    val spaceId: String? = null,
    val inHousehold: Boolean = false,
    val myMemberId: String? = null,
    val outLinks: List<OutLink> = emptyList(),
    /** 온보딩을 마쳤는지(가계부 시작 / 합류 / 보내기 전용 중 하나를 고름). */
    val onboarded: Boolean = false,
    val fcmToken: String? = null,
)

/**
 * 기기 신원과 키 고리 (DECISIONS.md D-3). 토큰·키는 Android Keystore 키로 암호화해 둡니다.
 * installId는 HLC에 쓰는 이 설치본의 고정 id(서버 등록 전에도 필요)입니다.
 */
class Identity(context: Context) {
    private val prefs = context.getSharedPreferences("doran-id", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("doran-identity")

    val installId: String = prefs.getString("installId", null) ?: newId().also { prefs.edit().putString("installId", it).apply() }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<IdState> = _state.asStateFlow()
    val value: IdState get() = _state.value

    private fun secret(k: String): ByteArray? = cipher.decrypt(prefs.getString(k, null))
    private fun putSecret(k: String, v: ByteArray?) {
        prefs.edit().apply { if (v == null) remove(k) else putString(k, cipher.encrypt(v)) }.commit()
    }

    private fun read(): IdState {
        val links = secret("outLinks")?.let { runCatching { JSONArray(String(it)) }.getOrNull() }
            ?.let { a -> (0 until a.length()).mapNotNull { runCatching { OutLink.from(a.getJSONObject(it)) }.getOrNull() } }
            ?: emptyList()
        val token = secret("token")
        return IdState(
            registered = token != null && prefs.getString("deviceId", null) != null,
            deviceId = prefs.getString("deviceId", null),
            spaceId = prefs.getString("spaceId", null),
            inHousehold = prefs.getBoolean("inHousehold", false),
            myMemberId = prefs.getString("myMemberId", null),
            outLinks = links,
            onboarded = prefs.getBoolean("onboarded", false),
            fcmToken = prefs.getString("fcmToken", null),
        )
    }

    private fun refresh() { _state.value = read() }

    val token: String? get() = secret("token")?.let(::String)
    val householdKey: ByteArray? get() = secret("hk")
    val personalKey: ByteArray? get() = secret("pk")

    fun setDevice(deviceId: String, token: String) {
        prefs.edit().putString("deviceId", deviceId).commit()
        putSecret("token", token.toByteArray())
        refresh()
    }

    /** 서버가 이 기기를 모른다고 할 때(401) 다시 등록하도록 지웁니다. 가구 키는 그대로 둡니다. */
    fun clearDevice() {
        prefs.edit().remove("deviceId").commit()
        putSecret("token", null)
        refresh()
    }

    fun setFcmToken(token: String?) {
        prefs.edit().putString("fcmToken", token).putBoolean("fcmDirty", true).commit()
        refresh()
    }
    var fcmDirty: Boolean
        get() = prefs.getBoolean("fcmDirty", false)
        set(v) { prefs.edit().putBoolean("fcmDirty", v).apply() }

    /** 새 가계부를 시작하거나 합류했을 때. */
    fun joinHousehold(memberId: String, householdKey: ByteArray, personalKey: ByteArray?, spaceId: String?) {
        putSecret("hk", householdKey)
        putSecret("pk", personalKey ?: Crypto.newKey())
        prefs.edit().putBoolean("inHousehold", true).putString("myMemberId", memberId).putBoolean("onboarded", true)
            .apply { if (spaceId != null) putString("spaceId", spaceId) else remove("spaceId") }.commit()
        refresh()
    }

    /** 가계부를 먼저 오프라인으로 시작했다가 서버에 가구를 만들었을 때. */
    fun setSpace(spaceId: String) {
        prefs.edit().putString("spaceId", spaceId).commit()
        refresh()
    }

    fun leaveHousehold() {
        putSecret("hk", null)
        putSecret("pk", null)
        prefs.edit().remove("spaceId").remove("myMemberId").putBoolean("inHousehold", false).commit()
        refresh()
    }

    fun markOnboarded() {
        prefs.edit().putBoolean("onboarded", true).commit()
        refresh()
    }

    fun saveOutLinks(links: List<OutLink>) {
        putSecret("outLinks", JSONArray(links.map { it.json() }).toString().toByteArray())
        refresh()
    }

    fun addOutLink(link: OutLink) = saveOutLinks(value.outLinks.filter { it.spaceId != link.spaceId } + link)
    fun removeOutLink(spaceId: String) = saveOutLinks(value.outLinks.filter { it.spaceId != spaceId })

    /** 백업 파일에 넣는 키 고리. */
    fun exportKeys(): JSONObject = JSONObject().apply {
        householdKey?.let { put("hk", Crypto.b64(it)) }
        personalKey?.let { put("pk", Crypto.b64(it)) }
        put("spaceId", value.spaceId ?: JSONObject.NULL)
        put("myMemberId", value.myMemberId ?: JSONObject.NULL)
        put("outLinks", JSONArray(value.outLinks.map { it.json() }))
    }

    fun importKeys(o: JSONObject) {
        val hk = o.optString("hk").takeIf { it.isNotEmpty() }?.let(Crypto::unb64)
        val pk = o.optString("pk").takeIf { it.isNotEmpty() }?.let(Crypto::unb64)
        val member = o.optString("myMemberId").takeIf { it.isNotEmpty() && it != "null" }
        val space = o.optString("spaceId").takeIf { it.isNotEmpty() && it != "null" }
        if (hk != null && member != null) joinHousehold(member, hk, pk, space)
        o.optJSONArray("outLinks")?.let { a ->
            saveOutLinks((0 until a.length()).mapNotNull { runCatching { OutLink.from(a.getJSONObject(it)) }.getOrNull() })
        }
        markOnboarded()
    }
}
