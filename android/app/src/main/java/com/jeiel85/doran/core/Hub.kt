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
    /** "AI로 한 번 더 보기"(D-25). 켜지 않았으면 아무것도 보내지 않습니다. */
    lateinit var ai: com.jeiel85.doran.ai.AiReview; private set

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
        ai = com.jeiel85.doran.ai.AiReview(db, local)
        ready = true

        scope.launch { ledger.changes.collect { Hooks.onChanges(app, it) } }
        scope.launch {
            syncRequests.consumeAsFlow().debounce(1500).collect {
                if (!sync.sync()) SyncWorker.retrySoon(app)
            }
        }
        // 알림창 한 줄: 장부·설정이 바뀌거나 날이 바뀌면 고쳐 씁니다(켰을 때만, 끄면 거둠).
        scope.launch {
            kotlinx.coroutines.flow.combine(ledger.state, local.state, dayTicks()) { s, st, day -> Triple(s, st, day) }
                .debounce(1000)
                .collect { (s, st, day) ->
                    val me = identity.value
                    if (st.statusLine && me.onboarded) {
                        val (title, text) = com.jeiel85.doran.logic.Stats.statusLine(s, day,
                            com.jeiel85.doran.logic.Stats.viewStart(s, st.monthStartDay), me.myMemberId, st.showSenders)
                        com.jeiel85.doran.ui.Notifier.status(app, title, text)
                    } else com.jeiel85.doran.ui.Notifier.clearStatus(app)
                }
        }
        scope.launch {
            delay(2_000)
            Hooks.daily(app)
            com.jeiel85.doran.feature.Receipts.retryPending(app) // 앱을 열 때도 못 올린·못 지운 사진(#7)
        }
    }

    /** 오늘 날짜를 내보내고, 자정이 지나면 다음 날을 내보냅니다(오늘 지출이 0으로 돌아가게). */
    private fun dayTicks() = kotlinx.coroutines.flow.flow {
        while (true) {
            val today = java.time.LocalDate.now(Fmt.zone)
            emit(today)
            val next = today.plusDays(1).atStartOfDay(Fmt.zone).toInstant().toEpochMilli()
            delay((next - System.currentTimeMillis()).coerceAtLeast(1_000) + 1_000)
        }
    }

    /** 로컬 변경 뒤 곧 올리기. 실패하면 작업 관리자가 네트워크가 생길 때 다시 합니다. */
    fun requestSync() {
        if (ready) syncRequests.trySend(Unit)
    }
}
