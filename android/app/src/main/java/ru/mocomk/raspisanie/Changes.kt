package ru.mocomk.raspisanie

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale.forLanguageTag("ru")
val ChangeYellow = Color(0xFFE0A100)

/** Последняя замена для каждой пары: дата -> (время -> замена). */
fun latestChanges(week: GroupWeek?): Map<String, Map<String, Change>> {
    if (week == null) return emptyMap()
    val out = mutableMapOf<String, MutableMap<String, Change>>()
    for (c in week.changes.sortedBy { it.at }) out.getOrPut(c.date) { mutableMapOf() }[c.time] = c
    return out
}

private fun subjects(l: List<Lesson>) =
    l.map { Repository.cleanSubject(it.subject) }.filter { it.isNotBlank() }.distinct().joinToString(" / ").ifBlank { "пара" }

private fun rooms(l: List<Lesson>) = l.map { it.room }.filter { it.isNotBlank() }.distinct().joinToString(", ")

private fun teachers(l: List<Lesson>) = l.map { it.teacher }.filter { it.isNotBlank() }.distinct().joinToString(", ")

/** Короткое описание замены: «Анатомия → История России», «каб. 13 → 6», «отменена». */
fun describeChange(c: Change): String = when (c.kind) {
    "removed" -> "отменена: ${subjects(c.old)}"
    "added" -> "новая пара: ${subjects(c.new)}" + rooms(c.new).let { if (it.isNotEmpty()) ", каб. $it" else "" }
    else -> {
        val so = subjects(c.old); val sn = subjects(c.new)
        val ro = rooms(c.old); val rn = rooms(c.new)
        when {
            so != sn -> "$so → $sn" + if (rn.isNotEmpty()) ", каб. $rn" else ""
            ro != rn -> "$sn: каб. ${ro.ifEmpty { "—" }} → ${rn.ifEmpty { "—" }}"
            else -> "$sn: преподаватель ${teachers(c.old).ifEmpty { "—" }} → ${teachers(c.new).ifEmpty { "—" }}"
        }
    }
}

/** «Ср 12.11, 10:40» */
fun changeWhen(c: Change): String {
    val d = runCatching { LocalDate.parse(c.date) }.getOrNull() ?: return c.time
    return d.dayOfWeek.getDisplayName(TextStyle.SHORT, RU).replaceFirstChar { it.uppercase() } +
        " " + d.format(DateTimeFormatter.ofPattern("d.MM")) + ", " + c.time
}

fun changeLabel(c: Change) = when (c.kind) { "added" -> "новая"; "removed" -> "отменена"; else -> "изменено" }

@Composable
fun ChangeBadge(c: Change, onClick: () -> Unit) {
    Text(
        changeLabel(c), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black,
        modifier = Modifier.background(ChangeYellow, RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun LessonsBlock(title: String, l: List<Lesson>, struck: Boolean) {
    Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (l.isEmpty()) Text("—")
    for (x in l) {
        val sg = Repository.subgroup(x.subject)
        val deco = if (struck) TextDecoration.LineThrough else null
        Text(
            (if (sg != null) "$sg подгр. · " else "") + Repository.cleanSubject(x.subject),
            fontWeight = FontWeight.SemiBold, textDecoration = deco,
            color = if (struck) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        val meta = listOfNotNull(x.room.takeIf { it.isNotBlank() }?.let { "каб. $it" }, x.teacher.takeIf { it.isNotBlank() })
        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), fontSize = 13.sp, textDecoration = deco,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun fmtAt(at: String): String = runCatching {
    OffsetDateTime.parse(at).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMMM, H:mm", RU))
}.getOrDefault(at)

@Composable
fun ChangeDialog(c: Change, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Замена · ${changeWhen(c)}") },
        text = {
            Column {
                if (c.kind != "added") LessonsBlock("Было", c.old, struck = true)
                if (c.kind != "added" && c.kind != "removed") Spacer(Modifier.height(12.dp))
                if (c.kind != "removed") LessonsBlock("Стало", c.new, struck = false)
                Spacer(Modifier.height(12.dp))
                Text("Колледж внёс изменение ${fmtAt(c.at)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Понятно") } },
    )
}

/** Отменённая пара — остаётся в дне зачёркнутой. */
@Composable
fun RemovedRow(c: Change, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp, 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(c.time, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.LineThrough,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
        Column(Modifier.weight(1f)) {
            Text(subjects(c.old), textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            ChangeBadge(c, onClick)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesSheet(week: GroupWeek, onPick: (Change) -> Unit, onDismiss: () -> Unit) {
    val byAt = week.changes.groupBy { it.at }.toSortedMap(compareByDescending { it })
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("История изменений", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
        Text("${week.group} · неделя с ${LocalDate.parse(week.start).format(DateTimeFormatter.ofPattern("d MMMM", RU))}",
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        if (byAt.isEmpty()) Text(
            "За эту неделю колледж ничего не менял. Если расписание перезальют с заменами — они появятся здесь, а изменённые пары подсветятся.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
        )
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp)) {
            for ((at, list) in byAt) {
                item {
                    Text("Обновление ${fmtAt(at)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
                }
                items(list.sortedWith(compareBy({ it.date }, { Repository.parseTime(it.time) }))) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(c) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChangeBadge(c) { onPick(c) }
                        Column(Modifier.weight(1f)) {
                            Text(changeWhen(c), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(describeChange(c))
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
