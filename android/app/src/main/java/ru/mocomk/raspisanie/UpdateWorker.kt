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
        val fresh = mutableListOf<Pair<WeekInfo, Boolean>>() // неделя, новая ли
        for (w in index.weeks) {
            if (group !in w.groups) continue
            val prev = seen.optString(w.start, "")
            if (prev != w.updated) {
                if (!firstRun && LocalDate.parse(w.end) >= LocalDate.now()) fresh += w to prev.isEmpty()
                seen.put(w.start, w.updated)
            }
        }
        repo.prefs.seenUpdates = seen
        repo.saveArchive(index, group)

        for ((w, isNew) in fresh) notify(w, group, isNew)
        return Result.success()
    }

    private fun notify(w: WeekInfo, group: String, isNew: Boolean) {
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
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(if (isNew) "Новое расписание: $group" else "Расписание изменилось: $group")
            .setContentText(range)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(w.start.hashCode(), n)
    }

    companion object {
        private const val CHANNEL = "schedule"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("schedule-check", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
