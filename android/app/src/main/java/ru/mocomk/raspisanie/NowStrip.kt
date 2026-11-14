package ru.mocomk.raspisanie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

const val PAIR_MINUTES = 90

/** Текущее время, обновляется каждые 20 секунд — чтобы подсветка и отсчёт шли сами. */
@Composable
fun rememberNow(): LocalDateTime {
    val now by produceState(LocalDateTime.now()) {
        while (true) { delay(20_000); value = LocalDateTime.now() }
    }
    return now
}

data class NowInfo(val date: LocalDate, val slot: Slot, val start: LocalDateTime, val end: LocalDateTime, val running: Boolean)

/** Идущая сейчас пара или ближайшая следующая в открытой неделе. */
fun findNow(week: GroupWeek, now: LocalDateTime): NowInfo? {
    for (date in week.days.keys.sorted()) {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: continue
        if (d.isBefore(now.toLocalDate())) continue
        val slots = week.days[date].orEmpty().mapNotNull { s ->
            val t = runCatching { LocalTime.parse(s.time.padStart(5, '0')) }.getOrNull() ?: return@mapNotNull null
            s to d.atTime(t)
        }.sortedBy { it.second }
        for ((slot, start) in slots) {
            val end = start.plusMinutes(PAIR_MINUTES.toLong())
            if (now.isBefore(end)) return NowInfo(d, slot, start, end, !now.isBefore(start))
        }
    }
    return null
}

private fun human(d: Duration): String {
    val m = (d.toMinutes() + 1).coerceAtLeast(1)
    return when {
        m < 60 -> "$m мин"
        m < 24 * 60 -> "${m / 60} ч" + if (m % 60 != 0L) " ${m % 60} мин" else ""
        else -> ""
    }
}

@Composable
fun NowStrip(info: NowInfo, now: LocalDateTime, onClick: () -> Unit) {
    val ru = Locale.forLanguageTag("ru")
    val l = info.slot.lessons.firstOrNull()
    val subject = l?.subject?.replace(Regex("""^\d\.\s*"""), "") ?: "Пара"
    val extra = if (info.slot.lessons.size > 1) " +${info.slot.lessons.size - 1}" else ""
    val room = l?.room?.takeIf { it.isNotBlank() }?.let { " · каб. $it" } ?: ""
    val (label, whenText) = when {
        info.running -> "Сейчас" to "до конца ${human(Duration.between(now, info.end))}"
        info.date == now.toLocalDate() -> "Дальше" to "через ${human(Duration.between(now, info.start))}, в ${info.slot.time}"
        info.date == now.toLocalDate().plusDays(1) -> "Завтра" to "первая пара в ${info.slot.time}"
        else -> info.date.dayOfWeek.getDisplayName(TextStyle.FULL, ru).replaceFirstChar { it.uppercase() } to
            "${info.date.format(DateTimeFormatter.ofPattern("d MMMM", ru))}, в ${info.slot.time}"
    }
    val accent = if (info.running) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .background(accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(accent, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(subject + extra, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(whenText + room, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
