package com.jeiel85.doran.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

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

    // --- 한 달에 한 번만 보내는 알림 표시(예산 80%·100%) -------------------------------------
    fun once(key: String): Boolean {
        if (prefs.getBoolean("once.$key", false)) return false
        prefs.edit().putBoolean("once.$key", true).apply()
        return true
    }
}
