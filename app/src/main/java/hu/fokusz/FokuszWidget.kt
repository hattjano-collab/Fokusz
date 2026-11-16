package hu.fokusz

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class FokuszWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        updateAll(context, manager, ids)
    }
    companion object {
        fun updateAll(c: Context, manager: AppWidgetManager, ids: IntArray) {
            val score = Store.bal(c)
            val title = QuestStore.current(c)?.title ?: "Válassz kezdő questet!"
            ids.forEach { id ->
                val views = RemoteViews(c.packageName, R.layout.fokusz_widget)
                views.setTextViewText(R.id.widget_balance, clock(score))
                views.setTextViewText(R.id.widget_quest, title)
                views.setTextViewText(R.id.widget_xp, "Lv. " + ProgressStore.level(c) + " • " + ProgressStore.streak(c) + " nap")
                val intent = Intent(c, MainActivity::class.java)
                val pending = PendingIntent.getActivity(c, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                views.setOnClickPendingIntent(R.id.widget_root, pending)
                manager.updateAppWidget(id, views)
            }
        }
    }
}
