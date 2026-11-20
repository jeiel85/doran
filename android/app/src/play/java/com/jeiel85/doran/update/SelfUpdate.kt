package com.jeiel85.doran.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.requestAppUpdateInfo
import com.jeiel85.doran.BuildConfig
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.ui.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Play 빌드: 설치는 Google Play가 하고, 둥지는 새 버전이 있는지만 알려 줍니다(DECISIONS.md D-21).
 * Play In-App Updates API로 이 사용자에게 열린 트랙(내부·비공개 테스트 포함)에 더 높은 버전이 있는지 보고,
 * 있으면 홈 배너·설정·알림으로 알려 누르면 Play 스토어의 둥지 페이지로 보냅니다.
 * 자가 업데이트 코드·설치 권한·APK 내려받기는 여전히 없습니다(D-16).
 */
object SelfUpdate {
    const val selfUpdates = false
    val supported: Boolean get() = true

    private const val TAG = "StoreUpdate"
    private const val RECHECK_MS = 6L * 3_600_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    @Volatile private var lastCheckAt = 0L
    @Volatile private var appContext: Context? = null

    fun onAppStart(context: Context) {
        appContext = context.applicationContext
        StoreUpdateWorker.schedule(context, Hub.settings.value.autoUpdate)
        scope.launch { check(context) }
    }

    /** 앱을 다시 열 때 6시간이 지났으면 다시 봅니다. */
    fun onAppVisible(visible: Boolean) {
        val ctx = appContext ?: return
        if (visible && System.currentTimeMillis() - lastCheckAt > RECHECK_MS) scope.launch { check(ctx) }
    }

    /** 새 버전이 있으면 Play 스토어로, 없으면 지금 다시 확인합니다. */
    suspend fun run(context: Context, userInitiated: Boolean) {
        if (_state.value is UpdateState.StoreUpdate) openStore(context) else check(context, userInitiated)
    }

    fun busy(on: Boolean) = Unit

    /**
     * Play에 이 사용자가 받을 수 있는 더 높은 버전이 있는지. Play가 답하지 않으면(시간 초과·Play 스토어가 바쁨·Play에서 받지 않은
     * 직접 설치본) 알던 새 버전은 그대로 두고 다음에 다시 묻습니다 — 배너가 잠깐의 실패로 사라지거나 "최신 버전"으로 잘못 보이지 않게.
     */
    suspend fun check(context: Context, userInitiated: Boolean = false): UpdateState {
        if (userInitiated) _state.value = UpdateState.Checking
        val answer: UpdateState? = withTimeoutOrNull(TIMEOUT_MS) {
            try {
                val info = AppUpdateManagerFactory.create(context.applicationContext).requestAppUpdateInfo()
                if (offered(info.updateAvailability(), info.availableVersionCode(), BuildConfig.VERSION_CODE))
                    UpdateState.StoreUpdate(versionName(info.availableVersionCode()))
                else UpdateState.UpToDate
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "update check unavailable: ${e.message}")
                null
            }
        }
        if (answer != null) lastCheckAt = System.currentTimeMillis()
        val next = next(known, answer, userInitiated)
        if (answer != null) known = answer as? UpdateState.StoreUpdate
        _state.value = next
        return next
    }

    /** Play가 이 설치본보다 높은 버전을 내놓았는지. 스토어보다 새로 빌드한 설치본에는 띄우지 않습니다. */
    fun offered(availability: Int, availableCode: Int, installedCode: Int): Boolean =
        availability == UpdateAvailability.UPDATE_AVAILABLE && availableCode > installedCode

    /** Play의 답([answer], 못 받았으면 null)과 지금까지 알던 새 버전([known])으로 보여 줄 상태를 정합니다. */
    fun next(known: UpdateState.StoreUpdate?, answer: UpdateState?, userInitiated: Boolean): UpdateState = when {
        answer != null -> answer
        known != null -> known
        userInitiated -> UpdateState.Failed(NO_ANSWER)
        else -> UpdateState.Idle
    }

    const val NO_ANSWER = "Play 스토어가 답하지 않았어요 · Play 스토어에서 받은 둥지에서만 확인할 수 있어요"
    private const val TIMEOUT_MS = 15_000L
    /** 마지막으로 Play가 알려 준 새 버전(없으면 null). */
    @Volatile private var known: UpdateState.StoreUpdate? = null

    /** Play 스토어 앱의 둥지 페이지(없으면 웹). 테스트 참여자에게는 그 트랙의 새 버전이 보입니다. */
    fun openStore(context: Context) {
        val pkg = context.packageName
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).setPackage("com.android.vending")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun storeIntent(context: Context): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}")).setPackage("com.android.vending")

    /** versionCode(주×10000 + 부×100 + 수, app/build.gradle.kts)를 "0.1.7"로. */
    fun versionName(code: Int): String = "${code / 10000}.${code / 100 % 100}.${code % 100}"
}

/** 하루 두 번 Play에 새 버전이 있는지 보고, 버전마다 한 번 알립니다(설정 "새 버전 알림"). */
class StoreUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Hub.init(applicationContext)
        if (!Hub.settings.value.autoUpdate) return Result.success()
        val st = SelfUpdate.check(applicationContext)
        if (st is UpdateState.StoreUpdate && Hub.db.kv("store.notified") != st.version) {
            Notifier.storeUpdate(applicationContext, st.version, SelfUpdate.storeIntent(applicationContext))
            Hub.db.setKv("store.notified", st.version)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "doran-store-update"
        fun schedule(context: Context, on: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!on) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<StoreUpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(10, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
