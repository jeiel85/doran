package com.jeiel85.doran.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * 사용자가 "최근 받은 알림"에서 골라 보내며 "앞으로도"를 켠 발신처. 그 앱·번호의 금액 알림은 [issuer]로 읽어 자동으로 보냅니다.
 * key = "push:<패키지>" 또는 "sms:<번호 숫자>".
 */
data class ForwardRule(val key: String, val label: String, val issuer: String, val card: Boolean, val createdAt: Long = System.currentTimeMillis()) {
    fun json(): org.json.JSONObject = org.json.JSONObject().put("key", key).put("label", label).put("issuer", issuer).put("card", card).put("createdAt", createdAt)
    companion object {
        fun key(source: String, from: String) = "$source:" + if (source == "sms") from.filter(Char::isDigit) else from
        fun from(o: org.json.JSONObject) = ForwardRule(o.getString("key"), o.optString("label"), o.getString("issuer"), o.optBoolean("card", true), o.optLong("createdAt"))
    }
}

/** 이 폰에만 있는 설정(동기화하지 않음). */
data class Settings(
    val theme: String = "system",
    val appLock: Boolean = false,
    val autoUpdate: Boolean = true,
    /** 알림·문자 읽기. 권한과 별개로 사용자가 끌 수 있습니다. */
    val capturePush: Boolean = true,
    val captureSms: Boolean = true,
    /** 읽기를 끈 앱 패키지. */
    val mutedPackages: Set<String> = emptySet(),
    /** 다른 구성원·가족이 쓴 내역 알림. */
    val notifyFamily: Boolean = true,
    /** 내 결제가 나만 보는 용돈으로 기록될 때 남은 용돈과 함께. */
    val notifyAllowance: Boolean = true,
    val notifyBudget: Boolean = true,
    val notifyRecurring: Boolean = true,
    val lastBackupAt: Long = 0,
    val showSenders: Boolean = true,
)

class LocalSettings(context: Context) {
    private val prefs = context.getSharedPreferences("doran-local", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val value: Settings get() = _state.value

    private fun read() = Settings(
        theme = prefs.getString("theme", "system") ?: "system",
        appLock = prefs.getBoolean("appLock", false),
        autoUpdate = prefs.getBoolean("autoUpdate", true),
        capturePush = prefs.getBoolean("capturePush", true),
        captureSms = prefs.getBoolean("captureSms", true),
        mutedPackages = prefs.getStringSet("mutedPackages", emptySet())?.toSet() ?: emptySet(),
        notifyFamily = prefs.getBoolean("notifyFamily", true),
        notifyAllowance = prefs.getBoolean("notifyAllowance", true),
        notifyBudget = prefs.getBoolean("notifyBudget", true),
        notifyRecurring = prefs.getBoolean("notifyRecurring", true),
        lastBackupAt = prefs.getLong("lastBackupAt", 0),
        showSenders = prefs.getBoolean("showSenders", true),
    )

    fun update(f: (Settings) -> Settings) {
        val s = f(_state.value)
        prefs.edit()
            .putString("theme", s.theme).putBoolean("appLock", s.appLock).putBoolean("autoUpdate", s.autoUpdate)
            .putBoolean("capturePush", s.capturePush).putBoolean("captureSms", s.captureSms)
            .putStringSet("mutedPackages", s.mutedPackages).putBoolean("notifyFamily", s.notifyFamily)
            .putBoolean("notifyAllowance", s.notifyAllowance)
            .putBoolean("notifyBudget", s.notifyBudget).putBoolean("notifyRecurring", s.notifyRecurring)
            .putLong("lastBackupAt", s.lastBackupAt).putBoolean("showSenders", s.showSenders)
            .apply()
        _state.value = s
    }

    // --- "새 내역" 표시: 이 폰에서 아직 확인하지 않은 자동 등록 거래 (DECISIONS.md D-5) -----------
    private val _unseen = MutableStateFlow(prefs.getString("unseen", null)?.let { s ->
        runCatching { JSONArray(s) }.getOrNull()?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    } ?: emptySet())
    val unseen: StateFlow<Set<String>> = _unseen.asStateFlow()

    @Synchronized
    fun markUnseen(ids: Collection<String>) {
        if (ids.isEmpty()) return
        setUnseen((_unseen.value + ids).toList().takeLast(500).toSet())
    }

    @Synchronized
    fun markSeen(ids: Collection<String>) = setUnseen(_unseen.value - ids.toSet())

    @Synchronized
    fun markAllSeen() = setUnseen(emptySet())

    private fun setUnseen(s: Set<String>) {
        prefs.edit().putString("unseen", JSONArray(s.toList()).toString()).apply()
        _unseen.value = s
    }

    // --- "앞으로도 자동으로 보내기" 발신처 규칙 (이 폰에만, 동기화하지 않음) -------------------
    private val _rules = MutableStateFlow(readRules())
    val rules: StateFlow<List<ForwardRule>> = _rules.asStateFlow()

    private fun readRules(): List<ForwardRule> = prefs.getString("forwardRules", null)?.let { s ->
        runCatching { JSONArray(s) }.getOrNull()?.let { a -> (0 until a.length()).mapNotNull { runCatching { ForwardRule.from(a.getJSONObject(it)) }.getOrNull() } }
    } ?: emptyList()

    @Synchronized
    fun saveRule(r: ForwardRule) = setRules(_rules.value.filter { it.key != r.key } + r)

    @Synchronized
    fun removeRule(key: String) = setRules(_rules.value.filter { it.key != key })

    private fun setRules(list: List<ForwardRule>) {
        prefs.edit().putString("forwardRules", JSONArray(list.map { it.json() }).toString()).apply()
        _rules.value = list
    }

    /** 백업에서 되살린 규칙을 더합니다(같은 발신처는 이 폰의 것이 이김). */
    @Synchronized
    fun importRules(list: List<ForwardRule>) = setRules((list + _rules.value).associateBy { it.key }.values.toList())

    fun ruleFor(source: String, from: String?): ForwardRule? = from?.let { f -> _rules.value.firstOrNull { it.key == ForwardRule.key(source, f) } }

    // --- 한 달에 한 번만 보내는 알림 표시(예산 80%·100%) -------------------------------------
    fun once(key: String): Boolean {
        if (prefs.getBoolean("once.$key", false)) return false
        prefs.edit().putBoolean("once.$key", true).apply()
        return true
    }
}
