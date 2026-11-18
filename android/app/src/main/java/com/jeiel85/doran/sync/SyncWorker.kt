package com.jeiel85.doran.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.jeiel85.doran.BuildConfig
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.logic.Hooks
import com.jeiel85.doran.ui.Notifier
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

/** 주기 동기화(15분)와 FCM·실패 뒤 재시도. 하루 일(고정지출 기록)도 여기서 돌립니다. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Hub.init(applicationContext)
        val ok = Hub.sync.sync()
        if (ok) com.jeiel85.doran.feature.Receipts.retryPending(applicationContext) // 못 올린·못 지운 사진(#7)
        if (tags.contains(PERIODIC)) Hooks.daily(applicationContext)
        return if (ok || runAttemptCount >= 5) Result.success() else Result.retry()
    }

    companion object {
        private const val PERIODIC = "sync-periodic"
        private const val NOW = "sync-now"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network).addTag(PERIODIC).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** FCM으로 깨웠을 때: 할당량이 있으면 바로(expedited), 없으면 일반 작업으로. */
        fun now(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(network).addTag(NOW).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        fun retrySoon(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(network).setInitialDelay(30, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(NOW).build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-retry", ExistingWorkPolicy.KEEP, req)
        }
    }
}

/** 서버가 보낸 "sync"/"joined" 깨우기. 내용은 없고, 폰이 받아서 동기화합니다 (DECISIONS.md D-9). */
class FcmService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        Hub.init(applicationContext)
        when (message.data["k"]) {
            "sync" -> SyncWorker.now(applicationContext)
            "joined" -> {
                Notifier.system(applicationContext, 5002, "새 기기가 가족 가계부에 연결됐어요",
                    "내가 초대한 게 아니라면 가족 화면에서 바로 내보내세요")
                SyncWorker.now(applicationContext)
            }
        }
    }

    override fun onNewToken(token: String) {
        Hub.init(applicationContext)
        Hub.identity.setFcmToken(token)
        Hub.requestSync()
    }

    companion object {
        /** Firebase 설정이 있는 빌드에서만. 실패해도 주기 동기화로 동작합니다. */
        suspend fun refreshToken() {
            if (!BuildConfig.HAS_FIREBASE) return
            try {
                val t = FirebaseMessaging.getInstance().token.await()
                if (t != Hub.identity.value.fcmToken) {
                    Hub.identity.setFcmToken(t)
                    Hub.requestSync()
                }
            } catch (e: Exception) {
                Log.w("Fcm", "token unavailable", e)
            }
        }
    }
}
