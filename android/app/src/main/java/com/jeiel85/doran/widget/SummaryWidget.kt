package com.jeiel85.doran.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.jeiel85.doran.R
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.ui.MainActivity

/** 홈 화면 위젯: 이번 달 지출·남은 예산·하루에 쓸 수 있는 돈 (RemoteViews, DECISIONS.md D-13). */
class SummaryWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Hub.init(context)
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SummaryWidget::class.java))
            if (ids.isEmpty()) return
            val v = views(context)
            for (id in ids) manager.updateAppWidget(id, v)
        }

        private fun views(context: Context): RemoteViews {
            val s = Hub.ledger.value
            val p = Stats.current(s, startDay = Stats.viewStart(s, Hub.settings.value.monthStartDay))
            val e = Stats.entries(s, p, Hub.identity.value.myMemberId)
            val spent = Stats.expense(e)
            val budget = s.household.budget
            val today = java.time.LocalDate.now(Fmt.zone)
            val daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, p.end).toInt().coerceAtLeast(1)
            return RemoteViews(context.packageName, R.layout.widget_summary).apply {
                setTextViewText(R.id.w_title, "${Fmt.month(p.label)} 지출")
                setTextViewText(R.id.w_amount, Fmt.won(spent))
                if (budget > 0) {
                    val left = budget - spent
                    setTextViewText(R.id.w_sub, if (left >= 0) "남은 예산 ${Fmt.won(left)} · 하루 ${Fmt.won(left / daysLeft)}"
                    else "예산보다 ${Fmt.won(-left)} 더 썼어요")
                    setProgressBar(R.id.w_progress, 1000, (spent * 1000 / budget).toInt().coerceIn(0, 1000), false)
                } else {
                    setTextViewText(R.id.w_sub, "수입 ${Fmt.won(Stats.income(e))}")
                    setProgressBar(R.id.w_progress, 1000, 0, false)
                }
                setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(context, 7001,
                    Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            }
        }
    }
}
