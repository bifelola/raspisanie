package ru.mocomk.raspisanie

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Раз в несколько часов проверяет сайт: новая неделя или изменения -> уведомление. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val repo = Repository(applicationContext)
        val loaded = runCatching { repo.loadIndex() }.getOrNull() ?: return Result.retry()
        if (loaded.fromCache) return Result.retry()
        val index = loaded.value
        val group = repo.prefs.group ?: DEFAULT_GROUP
        if (group !in index.groups) return Result.success()

        val seen = repo.prefs.seenUpdates
        val firstRun = seen.length() == 0
        val fresh = mutableListOf<Triple<WeekInfo, Boolean, String>>() // неделя, новая ли, прошлая отметка
        for (w in index.weeks) {
            if (group !in w.groups) continue
            val prev = seen.optString(w.start, "")
            if (prev != w.updated) {
                if (!firstRun && LocalDate.parse(w.end) >= LocalDate.now()) fresh += Triple(w, prev.isEmpty(), prev)
                seen.put(w.start, w.updated)
            }
        }
        repo.prefs.seenUpdates = seen
        repo.saveArchive(index, group)

        for ((w, isNew, prev) in fresh) {
            // что именно поменялось — из списка замен, который считает сервер
            val lines = if (isNew) emptyList() else runCatching { repo.loadGroupWeek(w, group)?.value }.getOrNull()
                ?.changes.orEmpty().filter { it.at > prev }
                .sortedWith(compareBy({ it.date }, { Repository.parseTime(it.time) }))
                .map { "${changeWhen(it)} — ${describeChange(it)}" }
            notify(w, group, isNew, lines)
        }
        Reminders.schedule(applicationContext)
        TodayWidget.refresh(applicationContext)
        return Result.success()
    }

    private fun notify(w: WeekInfo, group: String, isNew: Boolean, lines: List<String>) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Новое расписание", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val fmt = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))
        val range = "${LocalDate.parse(w.start).format(fmt)} — ${LocalDate.parse(w.end).format(fmt)}"
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_mono)
            .setContentTitle(when {
                isNew -> "Новое расписание: $group"
                lines.isNotEmpty() -> "Замены в расписании: $group"
                else -> "Расписание изменилось: $group"
            })
            .setContentText(lines.firstOrNull() ?: range)
        if (lines.isNotEmpty()) b.setStyle(NotificationCompat.BigTextStyle().bigText(
            lines.take(8).joinToString(NL) + if (lines.size > 8) "$NL…и ещё ${lines.size - 8}" else ""
        ))
        val n = b
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(w.start.hashCode(), n)
    }

    companion object {
        private const val CHANNEL = "schedule"
        private const val NL = "\n"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("schedule-check", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
