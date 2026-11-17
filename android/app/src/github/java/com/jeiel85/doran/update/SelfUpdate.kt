package com.jeiel85.doran.update

import android.content.Context
import com.jeiel85.doran.core.Hub
import kotlinx.coroutines.flow.StateFlow

/** GitHub 빌드: GitHub Releases에서 스스로 업데이트합니다 (DECISIONS.md D-8, 포너디 D-7·D-10과 같은 모양). */
object SelfUpdate {
    const val selfUpdates = true
    val supported: Boolean get() = Updater.supported
    val state: StateFlow<UpdateState> get() = Updater.state

    fun onAppStart(context: Context) = UpdateWorker.schedule(context, Hub.settings.value.autoUpdate)

    fun onAppVisible(visible: Boolean) { Updater.appVisible = visible }

    suspend fun run(context: Context, userInitiated: Boolean) { Updater.run(context, userInitiated) }

    /** 백업·복원·엑셀 저장 중에는 자동 설치를 미룹니다. */
    fun busy(on: Boolean) { if (on) Updater.busyWork++ else Updater.busyWork-- }
}
