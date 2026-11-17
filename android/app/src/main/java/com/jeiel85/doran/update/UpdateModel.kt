package com.jeiel85.doran.update

import android.content.Intent

/*
 * 두 배포 채널이 함께 쓰는 업데이트 상태(DECISIONS.md D-8·D-16). GitHub 빌드는 스스로 업데이트하고,
 * Play 빌드는 Google Play가 업데이트하므로 늘 Idle입니다.
 */

/** One GitHub release that carries an installable APK. */
data class Release(
    val version: String,
    val apkUrl: String,
    /** Lower-case hex SHA-256 of the APK; an update without one is never installed. */
    val sha256: String,
    val size: Long,
    val notes: String,
    val pageUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val percent: Int) : UpdateState
    /** Handed to the system installer; on success the process is replaced. */
    data class Installing(val release: Release) : UpdateState
    /** Android wants the owner's tap (first self-update, or the system does not allow silent ones). */
    data class NeedsConfirm(val release: Release, val confirm: Intent) : UpdateState
    data class Failed(val reason: String) : UpdateState
}
