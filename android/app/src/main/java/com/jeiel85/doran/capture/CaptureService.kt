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
import androidx.core.app.NotificationCompat
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
        if (pkg == packageName) return // 둥지 자기 알림은 제외
        // GitHub 빌드는 문자를 SmsReceiver로 받으므로 문자 앱 알림은 건너뜁니다. Play 빌드는 SMS 권한이 없어 문자 앱 알림으로 읽습니다.
        val smsApp = pkg in SMS_APPS
        if (smsApp && com.jeiel85.doran.BuildConfig.SMS_RECEIVER) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()
        // 카카오톡은 알림톡 채널(제목이 카드사·은행 이름)만 봅니다. 개인 대화의 "현대카드 3만원 썼어"를 거래로 읽지 않게.
        if (pkg == KAKAO && Issuers.all.none { it.matches(title.orEmpty()) }) return
        if (smsApp) {
            // 문자 앱 알림: 제목이 보낸 번호(또는 연락처 이름). 휴대폰 번호면 전달 앱이 넘긴 것이라 읽지 않음(D-4).
            val from = title.orEmpty()
            if (SmsReceiver.isPersonalNumber(from)) return
            // 문자 앱은 한 대화방의 여러 문자를 한 알림(MessagingStyle)으로 다시 올립니다. 문자마다 나눠 읽고,
            // 이미 읽은 문자(보낸 곳·시각·내용)는 건너뜁니다.
            val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages
                ?.mapNotNull { m -> m.text?.toString()?.takeIf { it.isNotBlank() }?.let { it to (m.timestamp.takeIf { t -> t > 0 } ?: sbn.postTime) } }
                ?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(text?.let { it to sbn.postTime })
            if (messages.isEmpty()) return
            scope.launch {
                Hub.init(applicationContext)
                if (!Hub.settings.value.captureSms) return@launch
                for ((body, at) in messages) {
                    if (!Hub.db.markSeenSms(from, at, body)) continue
                    runCatching { Hub.capture.process(Source.SMS, from, null, body, at, appLabel = "문자 $from") }
                        .onFailure { Log.w("Capture", "sms notification failed", it) }
                }
            }
            return
        }
        if (text == null) return
        scope.launch {
            Hub.init(applicationContext)
            val s = Hub.settings.value
            if (!s.capturePush || pkg in s.mutedPackages) return@launch
            val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            runCatching { Hub.capture.process(Source.PUSH, pkg, title, text, sbn.postTime, label) }
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
        // 카드사·은행은 1577·1588 같은 대표번호로 보냅니다. 휴대폰 번호에서 온 카드 문자는 "문자 자동 전달" 앱이
        // 가족 폰에서 넘겨 준 것이라, 읽으면 그 가족의 결제를 내 결제로 한 번 더 기록하게 됩니다 (DECISIONS.md D-4).
        if (isPersonalNumber(from)) return
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        val at = parts.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Hub.init(context.applicationContext)
                if (Hub.settings.value.captureSms) Hub.capture.process(Source.SMS, from, null, body, at, appLabel = "문자 $from")
            } catch (e: Exception) {
                Log.w("Capture", "sms failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** 010·011·016~019 휴대폰 번호(+82 포함). */
        fun isPersonalNumber(from: String?): Boolean {
            val d = from?.filter(Char::isDigit) ?: return false
            val local = if (d.startsWith("82")) "0" + d.drop(2) else d
            return local.length in 10..11 && Regex("""^01[016789]""").containsMatchIn(local)
        }
    }
}
