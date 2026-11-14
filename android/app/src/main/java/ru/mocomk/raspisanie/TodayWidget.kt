package ru.mocomk.raspisanie

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Виджет «Сегодня»: текущая/следующая пара и список пар на день. */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = build(ctx)
        for (id in ids) mgr.updateAppWidget(id, views)
        scheduleTick(ctx)
    }

    companion object {
        private val RU = Locale.forLanguageTag("ru")

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TodayWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(ctx)
            for (id in ids) mgr.updateAppWidget(id, views)
            scheduleTick(ctx)
        }

        private fun build(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_today)
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_root, open)

            val now = LocalDateTime.now()
            val today = now.toLocalDate()
            val group = LocalSchedule.group(ctx)
            val all = LocalSchedule.upcoming(ctx, today.atStartOfDay())
            val day: LocalDate? = all.firstOrNull { it.end.isAfter(now) }?.start?.toLocalDate()
            val hm = DateTimeFormatter.ofPattern("H:mm")
            val accent = ContextCompat.getColor(ctx, R.color.w_accent)
            val muted = ContextCompat.getColor(ctx, R.color.w_muted)

            if (day == null) {
                v.setTextViewText(R.id.w_title, group)
                v.setTextViewText(R.id.w_head, if (all.isEmpty()) "Нет сохранённого расписания" else "Пар больше нет")
                v.setTextViewText(R.id.w_list, "Откройте приложение, чтобы загрузить расписание на неделю.")
                return v
            }
            val dayName = when (day) {
                today -> "Сегодня"
                today.plusDays(1) -> "Завтра"
                else -> day.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() }
            }
            v.setTextViewText(R.id.w_title, "$group · $dayName, ${day.format(DateTimeFormatter.ofPattern("d MMMM", RU))}")

            val pairs = all.filter { it.start.toLocalDate() == day }
            val cur = pairs.firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }
            val next = pairs.firstOrNull { it.start.isAfter(now) }
            val head = SpannableStringBuilder()
            fun label(s: String) {
                val a = head.length
                head.append(s)
                head.setSpan(ForegroundColorSpan(accent), a, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val focus = cur ?: next
            when {
                cur != null -> label("Сейчас · до ${cur.end.format(hm)}\n")
                day == today && next != null -> label("Дальше · в ${next.start.format(hm)}\n")
                else -> label("Первая пара · в ${pairs.first().start.format(hm)}\n")
            }
            if (focus != null) {
                head.append(LocalSchedule.subjects(focus.slot).ifBlank { "Пара" })
                LocalSchedule.rooms(focus.slot).takeIf { it.isNotEmpty() }?.let { head.append(" · каб. $it") }
            }
            v.setTextViewText(R.id.w_head, head)

            val list = SpannableStringBuilder()
            for ((i, p) in pairs.withIndex()) {
                if (i > 0) list.append("\n")
                val a = list.length
                list.append(p.start.format(hm)).append("  ").append(LocalSchedule.subjects(p.slot))
                LocalSchedule.rooms(p.slot).takeIf { it.isNotEmpty() }?.let { list.append(" · $it") }
                when {
                    p == cur -> list.setSpan(StyleSpan(Typeface.BOLD), a, list.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    !p.end.isAfter(now) -> list.setSpan(ForegroundColorSpan(muted), a, list.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            v.setTextViewText(R.id.w_list, list)
            return v
        }

        /** Обновить виджет в момент начала или конца ближайшей пары (или в полночь). */
        private fun scheduleTick(ctx: Context) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val now = LocalDateTime.now()
            val bounds = LocalSchedule.upcoming(ctx, now, 2).flatMap { listOf(it.start, it.end) }.filter { it.isAfter(now) }
            val t = (bounds.minOrNull() ?: now.toLocalDate().plusDays(1).atStartOfDay()).plusSeconds(5)
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TodayWidget::class.java))
            val intent = Intent(ctx, TodayWidget::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            val pi = PendingIntent.getBroadcast(ctx, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            am.set(AlarmManager.RTC, t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
        }
    }
}
