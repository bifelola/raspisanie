package ru.mocomk.raspisanie

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Пары группы из памяти телефона — для виджета и напоминаний (интернет не нужен). */
object LocalSchedule {
    data class Occur(val start: LocalDateTime, val end: LocalDateTime, val slot: Slot)

    fun group(ctx: Context): String = Prefs(ctx).group ?: DEFAULT_GROUP

    fun upcoming(ctx: Context, from: LocalDateTime, days: Long = 8): List<Occur> {
        val repo = Repository(ctx)
        val idx = repo.cached("index.json")?.let { runCatching { Repository.parseIndex(JSONObject(it)) }.getOrNull() }
            ?: return emptyList()
        val g = group(ctx)
        val until = from.toLocalDate().plusDays(days)
        val out = mutableListOf<Occur>()
        for (w in idx.weeks) {
            if (LocalDate.parse(w.end).isBefore(from.toLocalDate()) || LocalDate.parse(w.start).isAfter(until)) continue
            val file = w.groups[g] ?: continue
            val gw = repo.cached("weeks/${w.start}/$file.json")
                ?.let { runCatching { Repository.parseGroupWeek(JSONObject(it)) }.getOrNull() } ?: continue
            for ((date, slots) in gw.days) {
                val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: continue
                for (s in slots) {
                    val t = Repository.parseTime(s.time) ?: continue
                    val st = d.atTime(t)
                    val en = st.plusMinutes(PAIR_MINUTES.toLong())
                    if (en.isAfter(from)) out += Occur(st, en, s)
                }
            }
        }
        return out.sortedBy { it.start }
    }

    fun subjects(s: Slot) = s.lessons.map { Repository.cleanSubject(it.subject) }.filter { it.isNotBlank() }.distinct().joinToString(" / ")
    fun rooms(s: Slot) = s.lessons.map { it.room }.filter { it.isNotBlank() }.distinct().joinToString(", ")
}

object Reminders {
    const val CHANNEL = "reminders"

    private fun pi(ctx: Context, start: String?): PendingIntent = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, ReminderReceiver::class.java).putExtra("start", start),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Поставить будильник на ближайшее напоминание (старое отменяется). */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pi(ctx, null))
        val min = Prefs(ctx).remindMinutes
        if (min <= 0) return
        val now = LocalDateTime.now()
        val next = LocalSchedule.upcoming(ctx, now).firstOrNull { it.start.minusMinutes(min.toLong()).isAfter(now) } ?: return
        val at = next.start.minusMinutes(min.toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val p = pi(ctx, next.start.toString())
        runCatching {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setWindow(AlarmManager.RTC_WAKEUP, at, 5 * 60_000L, p)
        }
    }

    fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val start = intent.getStringExtra("start")
        val min = Prefs(ctx).remindMinutes
        val now = LocalDateTime.now()
        // данные могли измениться (замены) — берём пару заново из памяти телефона
        val occ = LocalSchedule.upcoming(ctx, now.minusHours(1)).firstOrNull { it.start.toString() == start }
        if (occ != null && min > 0 && occ.start.isAfter(now.minusMinutes(1)) && Reminders.canNotify(ctx)) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(Reminders.CHANNEL, "Напоминания о парах", NotificationManager.IMPORTANCE_HIGH))
            val left = java.time.Duration.between(now, occ.start).toMinutes().coerceAtLeast(0) + 1
            val hm = DateTimeFormatter.ofPattern("H:mm")
            val rooms = LocalSchedule.rooms(occ.slot)
            val teachers = occ.slot.lessons.map { it.teacher }.filter { it.isNotBlank() }.distinct().joinToString(", ")
            val text = listOfNotNull(
                "${occ.start.format(hm)}–${occ.end.format(hm)}",
                rooms.takeIf { it.isNotEmpty() }?.let { "каб. $it" },
                teachers.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, Reminders.CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_mono)
                .setContentTitle("Через $left мин: ${LocalSchedule.subjects(occ.slot).ifBlank { "пара" }}")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setTimeoutAfter(PAIR_MINUTES * 60_000L)
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(7001, n) }
        }
        Reminders.schedule(ctx)
        TodayWidget.refresh(ctx)
    }
}

/** После перезагрузки, обновления приложения или смены времени — заново ставим напоминания и обновляем виджет. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.schedule(ctx)
        TodayWidget.refresh(ctx)
    }
}

@Composable
fun RemindersDialog(prefs: Prefs, group: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var sel by remember { mutableIntStateOf(prefs.remindMinutes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Напоминания о парах") },
        text = {
            Column {
                Text(
                    "Уведомление перед каждой парой группы «${group ?: DEFAULT_GROUP}»: предмет, кабинет и преподаватель. " +
                        "Работает без интернета и подстраивается под замены.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for ((v, label) in listOf(5 to "За 5 минут", 10 to "За 10 минут", 15 to "За 15 минут", 0 to "Не напоминать")) {
                    Row(
                        Modifier.fillMaxWidth().clickable { sel = v }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sel == v, onClick = { sel = v })
                        Text(label)
                    }
                }
                if (!Reminders.canNotify(ctx)) Text(
                    "Уведомления для приложения выключены — включите их в настройках телефона.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.remindMinutes = sel
                Reminders.schedule(ctx)
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
