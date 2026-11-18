package com.jeiel85.doran.core

import android.content.Context
import com.jeiel85.doran.capture.CaptureProcessor
import com.jeiel85.doran.data.Db
import com.jeiel85.doran.data.Identity
import com.jeiel85.doran.data.Ledger
import com.jeiel85.doran.data.LocalSettings
import com.jeiel85.doran.data.Settings
import com.jeiel85.doran.logic.Hooks
import com.jeiel85.doran.sync.Relay
import com.jeiel85.doran.sync.SyncEngine
import com.jeiel85.doran.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** 앱 전체가 함께 쓰는 객체들. 알림 수신·문자 수신·작업 관리자가 앱 화면 없이 깨워도 같은 상태를 씁니다. */
object Hub {
    lateinit var app: Context; private set
    lateinit var db: Db; private set
    lateinit var ledger: Ledger; private set
    lateinit var identity: Identity; private set
    lateinit var local: LocalSettings; private set
    lateinit var sync: SyncEngine; private set
    lateinit var capture: CaptureProcessor; private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings: StateFlow<Settings> get() = local.state
    private val syncRequests = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var ready = false

    @OptIn(FlowPreview::class)
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        app = context.applicationContext
        db = Db(app)
        identity = Identity(app)
        local = LocalSettings(app)
        ledger = Ledger(db, Hlc(identity.installId)).also { it.load() }
        sync = SyncEngine(db, ledger, identity, Relay { identity.token }, onLinkLost = { label ->
            com.jeiel85.doran.ui.Notifier.system(app, 5003, "'$label'에 더 보내지 않아요",
                "그 가계부에서 이 폰 연결을 끊었어요. 다시 보내려면 가족에게 새 QR을 받아 찍어 주세요")
        }) { changes -> Hooks.onRemote(app, changes) }
        capture = CaptureProcessor(db, ledger, identity, local, sync, ::requestSync)
        ready = true

        scope.launch { ledger.changes.collect { Hooks.onChanges(app, it) } }
        scope.launch {
            syncRequests.consumeAsFlow().debounce(1500).collect {
                if (!sync.sync()) SyncWorker.retrySoon(app)
            }
        }
        scope.launch {
            delay(2_000)
            Hooks.daily(app)
            com.jeiel85.doran.feature.Receipts.retryPending(app) // 앱을 열 때도 못 올린·못 지운 사진(#7)
        }
    }

    /** 로컬 변경 뒤 곧 올리기. 실패하면 작업 관리자가 네트워크가 생길 때 다시 합니다. */
    fun requestSync() {
        if (ready) syncRequests.trySend(Unit)
    }
}
