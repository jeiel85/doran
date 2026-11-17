package com.jeiel85.doran.update

import android.content.Context
import com.jeiel85.doran.core.Hub

/**
 * 앱이 부르는 업데이트 진입점 (DECISIONS.md D-8, 포너디 D-7·D-10과 같은 모양).
 * 지금은 GitHub APK 하나뿐이라 여기서 바로 GitHub Releases 업데이터를 씁니다. Play로 낼 때는
 * 포너디처럼 이 객체만 채널별로 나누면 됩니다(전역 지침: 채널 분리는 Play·F-Droid 첫 배포 때).
 */
object SelfUpdate {
    fun onAppStart(context: Context) = UpdateWorker.schedule(context, Hub.settings.value.autoUpdate)

    fun onAppVisible(visible: Boolean) { Updater.appVisible = visible }
}
