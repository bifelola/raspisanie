@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ru.mocomk.raspisanie

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale.forLanguageTag("ru")

/** Одно занятие в справочнике (подгруппы и потоки объединены). */
private data class Occ(val date: String, val time: String, val room: String, val subject: String, val teacher: String, val groups: List<String>)

private fun merge(rows: List<CollegeRow>): List<Occ> =
    // одно занятие = дата, время, кабинет и преподаватель; разночтения названия при распознавании — берём самое частое
    rows.groupBy { listOf(it.date, it.time, it.room, it.teacher) }
        .map { (k, v) ->
            val subj = v.map { Repository.cleanSubject(it.subject) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""
            Occ(k[0], k[1], k[2], subj, k[3], v.map { it.group }.distinct())
        }
        .sortedWith(compareBy({ it.date }, { Repository.parseTime(it.time) }))

/** Несколько преподавателей через запятую -> список (без повторов). */
fun teacherNames(t: String) = t.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** То же, что canon_room на сервере: одно название для кабинета. */
fun canonRoom(r: String): String {
    val s = r.trim()
    val toks = s.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    if (toks.isNotEmpty() && toks.all { t -> t.all { it.isDigit() } }) return toks.distinct().joinToString(", ")
    val low = s.lowercase().filter { it in 'а'..'я' || it == 'ё' || it in 'a'..'z' || it.isDigit() }
    if ("акт" in low) return "Актовый зал"
    if ("спор" in low || low == "сзал" || low == "зал" || low.startsWith("сп")) return "Спортзал"
    return s
}

/** Настоящий кабинет (номер или зал), а не ошибка распознавания. */
private fun isRealRoom(r: String) = r == "Спортзал" || r == "Актовый зал" || Regex("""\d+(, \d+)*""").matches(r)

private fun roomKey(r: String) = (r.takeWhile { it.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE) to r

private fun startOf(o: Occ): LocalDateTime? {
    val t = Repository.parseTime(o.time) ?: return null
    return runCatching { LocalDate.parse(o.date).atTime(t) }.getOrNull()
}

/** «Сейчас: каб. 13, у 22 фо» / «в 10:40: каб. 6» / «сегодня пар больше нет». */
private fun status(list: List<Occ>, now: LocalDateTime, where: (Occ) -> String): String {
    val today = now.toLocalDate().toString()
    for (o in list.filter { it.date == today }) {
        val s = startOf(o) ?: continue
        val e = s.plusMinutes(PAIR_MINUTES.toLong())
        if (!now.isBefore(s) && now.isBefore(e)) return "Сейчас: ${where(o)} (до ${e.toLocalTime().format(DateTimeFormatter.ofPattern("H:mm"))})"
        if (now.isBefore(s)) return "Сегодня в ${o.time}: ${where(o)}"
    }
    return if (list.any { it.date == today }) "Сегодня пар больше нет" else "Сегодня пар нет"
}

private fun dayTitle(date: String): String {
    val d = LocalDate.parse(date)
    return d.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() } +
        ", " + d.format(DateTimeFormatter.ofPattern("d MMMM", RU))
}

@Composable
fun CollegeSheet(vm: ScheduleViewModel, week: WeekInfo, initialRoom: String?, onDismiss: () -> Unit) {
    var rows by remember { mutableStateOf<List<CollegeRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(week.start) {
        runCatching { vm.repo.loadCollege(week) }
            .onSuccess { rows = it }
            .onFailure { error = "Не удалось загрузить справочник. Проверьте интернет." }
    }
    var tab by remember { mutableIntStateOf(if (initialRoom != null) 1 else 0) }
    var teacher by remember { mutableStateOf<String?>(null) }
    var room by remember { mutableStateOf(initialRoom) }
    var favs by remember { mutableStateOf(vm.repo.prefs.favorites) }
    val toggleFav = { t: String ->
        favs = if (t in favs) favs - t else favs + t
        vm.repo.prefs.favorites = favs
    }
    val now = rememberNow()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            val r = rows
            when {
                error != null -> Text(error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                r == null -> Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator() }
                teacher != null -> TeacherDetail(teacher!!, merge(r.filter { teacher!! in teacherNames(it.teacher) }), now,
                    teacher in favs, { toggleFav(teacher!!) }, onRoom = { room = it; teacher = null; tab = 1 }) { teacher = null }
                room != null -> RoomDetail(room!!, merge(r.filter { it.room == room || canonRoom(it.room) == room }), now,
                    onTeacher = { teacher = it; room = null }) { room = null }
                else -> {
                    TabRow(selectedTabIndex = tab) {
                        Tab(tab == 0, { tab = 0 }, text = { Text("Где преподаватель?") })
                        Tab(tab == 1, { tab = 1 }, text = { Text("Свободные кабинеты") })
                    }
                    if (tab == 0) TeacherList(r, now, favs, toggleFav) { teacher = it }
                    else FreeRooms(r, week, now) { room = it }
                }
            }
        }
    }
}

@Composable
private fun BackHeader(title: String, subtitle: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Default.ChevronLeft, "Назад") }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        trailing()
    }
}

@Composable
private fun TeacherList(rows: List<CollegeRow>, now: LocalDateTime, favs: Set<String>, toggleFav: (String) -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = remember(rows) { rows.flatMap { teacherNames(it.teacher) }.distinct().sorted() }
    val byTeacher = remember(rows) { rows.flatMap { r -> teacherNames(r.teacher).map { it to r } }.groupBy({ it.first }, { it.second }).mapValues { merge(it.value) } }
    val norm = q.trim().lowercase()
    val shown = all.filter { norm.isEmpty() || it.lowercase().contains(norm) }
        .sortedWith(compareBy({ it !in favs }, { it }))
    OutlinedTextField(
        q, { q = it }, singleLine = true, placeholder = { Text("Фамилия, например Чусова") },
        modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp),
    )
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        if (shown.isEmpty()) item { Text("Никого не найдено", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(shown) { t ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(t) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t, fontWeight = FontWeight.SemiBold)
                    Text(status(byTeacher[t].orEmpty(), now) { o -> "каб. ${o.room.ifBlank { "?" }}" },
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { toggleFav(t) }) {
                    Icon(if (t in favs) Icons.Default.Star else Icons.Default.StarBorder,
                        if (t in favs) "Убрать из избранного" else "В избранное",
                        tint = if (t in favs) ChangeYellow else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TeacherDetail(
    name: String, list: List<Occ>, now: LocalDateTime, fav: Boolean, toggleFav: () -> Unit,
    onRoom: (String) -> Unit, onBack: () -> Unit,
) {
    BackHeader(name, status(list, now) { o -> "каб. ${o.room.ifBlank { "?" }}, ${o.groups.joinToString(", ")}" }, onBack) {
        IconButton(onClick = toggleFav) {
            Icon(if (fav) Icons.Default.Star else Icons.Default.StarBorder, "Избранное",
                tint = if (fav) ChangeYellow else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    OccList(list, now) { o ->
        Text(Repository.cleanSubject(o.subject), fontWeight = FontWeight.SemiBold)
        Row {
            if (o.room.isNotBlank()) Text("каб. ${o.room}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onRoom(o.room) })
            Text((if (o.room.isNotBlank()) " · " else "") + o.groups.joinToString(", "), fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RoomDetail(room: String, list: List<Occ>, now: LocalDateTime, onTeacher: (String) -> Unit, onBack: () -> Unit) {
    BackHeader("Кабинет $room", status(list, now) { o -> "${o.groups.joinToString(", ")}, ${Repository.cleanSubject(o.subject)}" }
        .replace("Сегодня пар больше нет", "Сегодня больше свободен").replace("Сегодня пар нет", "Сегодня свободен весь день"), onBack)
    OccList(list, now) { o ->
        Text(Repository.cleanSubject(o.subject), fontWeight = FontWeight.SemiBold)
        Row {
            Text(o.groups.joinToString(", "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for ((i, t) in teacherNames(o.teacher).withIndex()) Text((if (i == 0) " · " else ", ") + t, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onTeacher(t) })
        }
    }
}

@Composable
private fun OccList(list: List<Occ>, now: LocalDateTime, body: @Composable (Occ) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        if (list.isEmpty()) item { Text("На этой неделе занятий нет", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        for ((date, day) in list.groupBy { it.date }) {
            item {
                Text(dayTitle(date) + if (date == now.toLocalDate().toString()) " · сегодня" else "",
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 4.dp))
            }
            items(day) { o ->
                val s = startOf(o)
                val active = s != null && !now.isBefore(s) && now.isBefore(s.plusMinutes(PAIR_MINUTES.toLong()))
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (active) MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text(o.time, fontWeight = FontWeight.Bold, modifier = Modifier.width(52.dp),
                        color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                    Column(Modifier.weight(1f)) { body(o) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun FreeRooms(rows: List<CollegeRow>, week: WeekInfo, now: LocalDateTime, onPick: (String) -> Unit) {
    val dates = remember(rows) { rows.map { it.date }.distinct().sorted() }
    val today = now.toLocalDate().toString()
    var date by remember { mutableStateOf(if (today in dates) today else dates.firstOrNull() ?: week.start) }
    val times = rows.filter { it.date == date }.map { it.time }.distinct().sortedBy { Repository.parseTime(it) }
    val defaultTime = times.firstOrNull { t ->
        date != today || Repository.parseTime(t)?.plusMinutes(PAIR_MINUTES.toLong())?.isAfter(now.toLocalTime()) == true
    } ?: times.firstOrNull()
    var timeSel by remember(date) { mutableStateOf(defaultTime) }
    val allRooms = remember(rows) { rows.map { it.room }.filter { isRealRoom(it) }.distinct().sortedWith(compareBy({ roomKey(it).first }, { roomKey(it).second })) }
    val busy = rows.filter { it.date == date && it.time == timeSel }.map { it.room }.toSet()
    val free = allRooms.filter { it !in busy }

    val dayRow = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = dates.indexOf(date).coerceAtLeast(0))
    LazyRow(state = dayRow, contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(dates) { d ->
            val ld = LocalDate.parse(d)
            Chip(ld.dayOfWeek.getDisplayName(TextStyle.SHORT, RU).replaceFirstChar { it.uppercase() } + " " +
                ld.format(DateTimeFormatter.ofPattern("d.MM")), d == date, d == today) { date = d }
        }
    }
    LazyRow(contentPadding = PaddingValues(16.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(times) { t -> Chip(t, t == timeSel, false) { timeSel = t } }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp)) {
        item {
            Text(
                if (timeSel == null) "В этот день занятий нет — свободны все кабинеты."
                else "Свободно в $timeSel: ${free.size} из ${allRooms.size}",
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (rm in free) Chip("каб. $rm", false, false) { onPick(rm) }
            }
        }
        item {
            Text(
                "По расписанию всех групп. Нажмите на кабинет, чтобы увидеть, кто и когда в нём занимается. " +
                    "Кабинет могут занять и вне расписания.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, highlight: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = if (highlight && !selected) BorderStroke(2.dp, MaterialTheme.colorScheme.secondary) else null,
    ) { Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 14.sp) }
}
