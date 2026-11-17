package com.jeiel85.doran.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Play 빌드: 업데이트는 Google Play가 합니다. 이 빌드에는 자가 업데이트 코드·설치 권한·APK 내려받기가 아예 없습니다
 * (Play 정책, DECISIONS.md D-16 — 포너디 D-10과 같은 방식).
 */
object SelfUpdate {
    const val selfUpdates = false
    val supported: Boolean get() = false
    val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.Idle)

    fun onAppStart(context: Context) = Unit

    fun onAppVisible(visible: Boolean) = Unit

    suspend fun run(context: Context, userInitiated: Boolean) = Unit

    fun busy(on: Boolean) = Unit
}
