package com.jeiel85.doran

import android.app.Application
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.sync.FcmService
import com.jeiel85.doran.sync.SyncWorker
import com.jeiel85.doran.ui.Notifier
import com.jeiel85.doran.update.UpdateWorker
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        Notifier.channels(this)
        SyncWorker.schedule(this)
        UpdateWorker.schedule(this, Hub.settings.value.autoUpdate)
        Hub.scope.launch { FcmService.refreshToken() }
        Hub.requestSync()
    }
}
