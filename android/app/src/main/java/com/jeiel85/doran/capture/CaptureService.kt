package com.jeiel85.doran.capture

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.model.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 카드사·은행·카카오톡 알림톡 같은 앱 알림을 읽습니다 (DECISIONS.md D-4).
 * 금액·승인 형식이 아닌 알림은 메모리에서 바로 버리고 저장하지 않습니다.
 */
class CaptureService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg in SMS_APPS) return // 문자는 SmsReceiver가 받음, 도란 자기 알림은 제외
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString() ?: return
        // 카카오톡은 알림톡 채널(제목이 카드사·은행 이름)만 봅니다. 개인 대화의 "현대카드 3만원 썼어"를 거래로 읽지 않게.
        if (pkg == KAKAO && Issuers.all.none { it.matches(title.orEmpty()) }) return
        scope.launch {
            Hub.init(applicationContext)
            val s = Hub.settings.value
            if (!s.capturePush || pkg in s.mutedPackages) return@launch
            runCatching { Hub.capture.process(Source.PUSH, pkg, title, text, sbn.postTime) }
                .onFailure { Log.w("Capture", "process failed", it) }
        }
    }

    companion object {
        const val KAKAO = "com.kakao.talk"
        val SMS_APPS = setOf(
            "com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.mms",
            "com.android.messaging",
        )

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun component(context: Context) = ComponentName(context, CaptureService::class.java)
    }
}

/** 문자 수신. 문자 앱 대화방을 무음으로 해 두어도 놓치지 않도록 알림 대신 수신 방송을 씁니다 (D-4). */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val from = parts.first().displayOriginatingAddress
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        val at = parts.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Hub.init(context.applicationContext)
                if (Hub.settings.value.captureSms) Hub.capture.process(Source.SMS, from, null, body, at)
            } catch (e: Exception) {
                Log.w("Capture", "sms failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
