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

/**
 * 나만 보는 용돈의 은행 출금을 옮긴 돈([moved]) 또는 쓴 돈으로 늘 보라는 기억(DECISIONS.md D-26). key = `Moves.ruleKey`(은행|메모).
 * 용돈 내역은 이 폰에서만 열리므로 규칙도 이 폰에만 둡니다.
 */
data class MoveRule(val key: String, val label: String, val moved: Boolean, val createdAt: Long = System.currentTimeMillis()) {
    fun json(): org.json.JSONObject = org.json.JSONObject().put("key", key).put("label", label).put("moved", moved).put("createdAt", createdAt)
    companion object {
        fun from(o: org.json.JSONObject) = MoveRule(o.getString("key"), o.optString("label"), o.optBoolean("moved", true), o.optLong("createdAt"))
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
    /** "AI로 한 번 더 보기"(D-25): "" = 끔, "openai", "claude". 키는 [LocalSettings.aiKey]에 따로 암호화해 둡니다. */
    val aiProvider: String = "",
    /** 비어 있으면 그 회사의 기본 모델. */
    val aiModel: String = "",
    /** 이 폰에서 보는 한 달 시작일(1~28). 0이면 가족 공통 한 달 시작일을 따릅니다(DECISIONS.md D-33, `Stats.viewStart`). */
    val monthStartDay: Int = 0,
    /** 알림창에 늘 떠 있는 한 줄(오늘 지출 · 남은 예산 · 쓰기, 비주얼 가계부). 기본은 꺼 둡니다. */
    val statusLine: Boolean = false,
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
        aiProvider = prefs.getString("aiProvider", "") ?: "",
        aiModel = prefs.getString("aiModel", "") ?: "",
        // 101~128: 그 날짜가 쉬는 날이면 앞 영업일부터(D-40, Stats.period)
        monthStartDay = prefs.getInt("monthStartDay", 0).takeIf { it in 1..28 || it in 101..128 } ?: 0,
        statusLine = prefs.getBoolean("statusLine", false),
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
            .putString("aiProvider", s.aiProvider).putString("aiModel", s.aiModel)
            .putInt("monthStartDay", s.monthStartDay).putBoolean("statusLine", s.statusLine)
            .apply()
        _state.value = s
    }

    // --- "AI로 한 번 더 보기" API 키 (D-25): 이 폰에만, 안드로이드 키 저장소로 암호화. 백업·동기화하지 않음 -------
    private val aiCipher = com.jeiel85.doran.crypto.KeystoreCipher("doran-ai-key")

    /** 회사별로 따로 둡니다(바꿔 가며 시험해도 다시 넣지 않게). 키 저장소 키가 없어졌으면 null. */
    fun aiKey(provider: String): String? =
        aiCipher.decrypt(prefs.getString("aiKey.$provider", null))?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }

    /** 화면에 보일 키 끝 4자리(키 자체는 보이지 않음). */
    fun aiKeyTail(provider: String): String? = prefs.getString("aiKeyTail.$provider", null)

    fun setAiKey(provider: String, key: String?) {
        val k = key?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit().apply {
            if (k == null) remove("aiKey.$provider").remove("aiKeyTail.$provider")
            else putString("aiKey.$provider", aiCipher.encrypt(k.toByteArray())).putString("aiKeyTail.$provider", k.takeLast(4))
        }.apply()
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

    // --- 옮긴 돈·쓴 돈 기억 (D-26, 이 폰에만) ------------------------------------------------------
    private val _moveRules = MutableStateFlow(readMoveRules())
    val moveRules: StateFlow<List<MoveRule>> = _moveRules.asStateFlow()

    private fun readMoveRules(): List<MoveRule> = prefs.getString("moveRules", null)?.let { s ->
        runCatching { JSONArray(s) }.getOrNull()?.let { a -> (0 until a.length()).mapNotNull { runCatching { MoveRule.from(a.getJSONObject(it)) }.getOrNull() } }
    } ?: emptyList()

    @Synchronized
    fun saveMoveRule(r: MoveRule) = setMoveRules(_moveRules.value.filter { it.key != r.key } + r)

    @Synchronized
    fun removeMoveRule(key: String) = setMoveRules(_moveRules.value.filter { it.key != key })

    private fun setMoveRules(list: List<MoveRule>) {
        prefs.edit().putString("moveRules", JSONArray(list.map { it.json() }).toString()).apply()
        _moveRules.value = list
    }

    /** 백업에서 되살린 기억을 더합니다(같은 은행·메모는 이 폰의 것이 이김). */
    @Synchronized
    fun importMoveRules(list: List<MoveRule>) = setMoveRules((list + _moveRules.value).associateBy { it.key }.values.toList())

    /** 기억한 판정: 옮긴 돈 true, 쓴 돈 false, 없으면 null. */
    fun movedBy(key: String?): Boolean? = key?.let { k -> _moveRules.value.firstOrNull { it.key == k }?.moved }

    // --- 정기결제 제안에서 "아니에요"를 고른 가게(이 폰에만) -------------------------------------------
    fun dismissedSuggestions(): Set<String> = prefs.getStringSet("suggest.dismissed", emptySet())?.toSet() ?: emptySet()
    fun dismissSuggestion(key: String) = prefs.edit().putStringSet("suggest.dismissed", dismissedSuggestions() + key).apply()

    // --- 입금 바로잡기 카드에서 "그대로 두기"를 고른 거래(이 폰에만, #106) ------------------------------
    fun keptMisread(): Set<String> = prefs.getStringSet("misread.kept", emptySet())?.toSet() ?: emptySet()
    fun keepMisread(ids: Collection<String>) = prefs.edit().putStringSet("misread.kept", keptMisread() + ids).apply()

    // --- 월말 마감 체크(이 폰에만) -----------------------------------------------------------------
    fun checked(key: String): Boolean = prefs.getBoolean("check.$key", false)
    fun setChecked(key: String, on: Boolean) = prefs.edit().putBoolean("check.$key", on).apply()

    // --- 한 달에 한 번만 보내는 알림 표시(예산 80%·100%) -------------------------------------
    fun once(key: String): Boolean {
        if (prefs.getBoolean("once.$key", false)) return false
        prefs.edit().putBoolean("once.$key", true).apply()
        return true
    }
}
