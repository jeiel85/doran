package com.jeiel85.doran.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jeiel85.doran.R

/** 이 폰에서 만드는 알림. 금액·가맹점은 폰에서 복호화한 뒤에만 알림에 들어갑니다 (DECISIONS.md D-9). */
object Notifier {
    const val FAMILY = "family"
    const val BUDGET = "budget"
    const val RECURRING = "recurring"
    const val UPDATE = "update"
    const val SYSTEM = "system"
    const val EXTRA_TXN = "txnId"

    fun channels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(FAMILY, "가족 내역", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "다른 가족의 카드·계좌 내역이 들어오면 알려 줘요"
            },
            NotificationChannel(BUDGET, "예산", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "예산의 80%와 100%를 넘으면 알려 줘요"
            },
            NotificationChannel(RECURRING, "고정지출", NotificationManager.IMPORTANCE_LOW).apply {
                description = "고정지출이 장부에 들어가면 알려 줘요"
            },
            NotificationChannel(UPDATE, "업데이트", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(SYSTEM, "연결·백업", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "새 기기가 연결되거나 백업할 때가 되면 알려 줘요"
            },
        ))
    }

    private fun can(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(context: Context, txnId: String? = null, req: Int = 0): PendingIntent =
        PendingIntent.getActivity(context, req,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (txnId != null) putExtra(EXTRA_TXN, txnId) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun builder(context: Context, channel: String, title: String, text: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_doran)
            .setColor(0xFF2E7A62.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)

    @Suppress("MissingPermission")
    private fun post(context: Context, id: Int, n: NotificationCompat.Builder) {
        if (can(context)) NotificationManagerCompat.from(context).notify(id, n.build())
    }

    /** "남편 · 현대카드 12,300원" / "스타벅스 · 이번 달 누적 523,000원" */
    fun familyTxn(context: Context, txnId: String, title: String, text: String) {
        post(context, txnId.hashCode(), builder(context, FAMILY, title, text)
            .setContentIntent(open(context, txnId, txnId.hashCode()))
            .setGroup("doran.family"))
    }

    fun budget(context: Context, key: String, title: String, text: String) =
        post(context, key.hashCode(), builder(context, BUDGET, title, text).setContentIntent(open(context)))

    fun recurring(context: Context, title: String, text: String) =
        post(context, 3001, builder(context, RECURRING, title, text).setContentIntent(open(context)))

    fun system(context: Context, id: Int, title: String, text: String) =
        post(context, id, builder(context, SYSTEM, title, text).setContentIntent(open(context)))

    fun updateReady(context: Context, version: String, confirm: Intent) {
        val pi = PendingIntent.getActivity(context, 4001, confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        post(context, 4001, builder(context, UPDATE, "도란 $version 설치 준비 완료", "눌러서 업데이트를 마쳐 주세요").setContentIntent(pi))
    }

    fun updated(context: Context, version: String) =
        post(context, 4002, builder(context, UPDATE, "도란 $version 으로 업데이트했어요", "새 버전을 써 보세요").setContentIntent(open(context)))
}
