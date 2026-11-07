@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package ru.mocomk.raspisanie

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val RU = Locale.forLanguageTag("ru")
private val Brand = Color(0xFF1F5F8B)
private val TodayGreen = Color(0xFF2E8B57)
private const val PAIR_MINUTES = 90

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF6FB3E0), secondary = Color(0xFF4CC082),
        background = Color(0xFF11161C), surface = Color(0xFF1A2129),
    ) else lightColorScheme(
        primary = Brand, secondary = TodayGreen,
        background = Color(0xFFF3F5F8), surface = Color.White,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(vm: ScheduleViewModel) {
    val s by vm.state.collectAsState()
    var showGroups by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val week = s.currentWeek
    val days: List<LocalDate> = week?.let { w ->
        val st = LocalDate.parse(w.start)
        (0 until 6).map { st.plusDays(it.toLong()) }
    } ?: emptyList()
    val today = LocalDate.now()

    LaunchedEffect(s.week) {
        val i = days.indexOf(today)
        if (s.week != null) listState.scrollToItem(if (i >= 0) i else 0)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                CenterAlignedTopAppBar(
                    title = {
                        Column(
                            Modifier.clickable { showGroups = true }.padding(horizontal = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Группа", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                (s.group ?: "—") + "  ▾", fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Обновить") }
                    },
                    actions = {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Меню") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val gw = s.week
                            if (gw != null && gw.scans.isNotEmpty()) DropdownMenuItem(
                                text = { Text("Оригинал (скан)") },
                                leadingIcon = { Icon(Icons.Default.Image, null) },
                                onClick = { menu = false; uri.openUri(vm.repo.scanUrl(gw, gw.scans.first())) },
                            )
                            if (!week?.source.isNullOrEmpty()) DropdownMenuItem(
                                text = { Text("PDF на сайте колледжа") },
                                leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                                onClick = { menu = false; uri.openUri(week!!.source) },
                            )
                            DropdownMenuItem(
                                text = { Text("Адрес сервера") },
                                leadingIcon = { Icon(Icons.Default.Settings, null) },
                                onClick = { menu = false; showSettings = true },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Версия ${BuildConfig.VERSION_NAME}", fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                onClick = { menu = false },
                                enabled = false,
                            )
                        }
                    },
                )
                if (week != null) WeekBar(s, week, vm)
                if (days.isNotEmpty() && s.week != null) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(days) { i, d ->
                            DayChip(d, d == today, s.week?.days?.get(d.toString()).isNullOrEmpty()) {
                                scope.launch { listState.animateScrollToItem(i) }
                            }
                        }
                    }
                }
                if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else HorizontalDivider()
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val gw = s.week
            when {
                gw != null -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(days) { _, d -> DayCard(d, d == today, gw.days[d.toString()].orEmpty()) }
                    item {
                        Text(
                            (if (s.offline) "Нет связи — показаны сохранённые данные. " else "") +
                                "Расписание распознано автоматически со скана; при сомнениях сверьтесь с оригиналом (меню ⋮).",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
                s.message != null -> Text(
                    s.message!!, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showGroups && s.index != null) {
        GroupSheet(s.index!!.groups, s.group, onDismiss = { showGroups = false }) {
            showGroups = false
            vm.selectGroup(it)
        }
    }
    if (showSettings) {
        var url by remember { mutableStateOf(vm.repo.prefs.baseUrl) }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Адрес сервера") },
            text = { OutlinedTextField(url, { url = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { showSettings = false; vm.setBaseUrl(url) }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun WeekBar(s: UiState, week: WeekInfo, vm: ScheduleViewModel) {
    val fmt = DateTimeFormatter.ofPattern("d MMMM", RU)
    val st = LocalDate.parse(week.start)
    val en = LocalDate.parse(week.end)
    val today = LocalDate.now()
    val note = when {
        !today.isBefore(st) && !today.isAfter(en.plusDays(1)) -> "текущая неделя"
        st.isAfter(today) -> "следующая неделя"
        else -> "архив"
    }
    val count = s.index?.weeks?.size ?: 0
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.shiftWeek(-1) }, enabled = s.weekIdx < count - 1) {
            Icon(Icons.Default.ChevronLeft, "Предыдущая неделя")
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${st.format(fmt)} — ${en.format(fmt)}", fontWeight = FontWeight.SemiBold)
            Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { vm.shiftWeek(1) }, enabled = s.weekIdx > 0) {
            Icon(Icons.Default.ChevronRight, "Следующая неделя")
        }
    }
}

@Composable
private fun DayChip(d: LocalDate, isToday: Boolean, empty: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (isToday) BorderStroke(2.dp, MaterialTheme.colorScheme.secondary) else null,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                d.dayOfWeek.getDisplayName(TextStyle.SHORT, RU).replaceFirstChar { it.uppercase() },
                fontWeight = FontWeight.Bold, fontSize = 13.sp,
                color = if (empty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(d.format(DateTimeFormatter.ofPattern("d.MM")), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DayCard(d: LocalDate, isToday: Boolean, slots: List<Slot>) {
    val title = d.dayOfWeek.getDisplayName(TextStyle.FULL, RU).replaceFirstChar { it.uppercase() } +
        ", " + d.format(DateTimeFormatter.ofPattern("d MMMM", RU))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (isToday) 2.dp else 1.dp,
            if (isToday) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp, 12.dp, 14.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            if (isToday) Text(
                "сегодня", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        if (slots.isEmpty()) {
            Text("Пар нет", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(14.dp, 0.dp, 14.dp, 14.dp))
        }
        val now = LocalTime.now()
        for (slot in slots) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val (h, m) = slot.time.split(":").map { it.toInt() }
            val start = LocalTime.of(h, m)
            val end = start.plusMinutes(PAIR_MINUTES.toLong())
            val active = isToday && !now.isBefore(start.minusMinutes(10)) && now.isBefore(end)
            Row(
                Modifier.fillMaxWidth()
                    .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                    .padding(14.dp, 10.dp),
            ) {
                Column(Modifier.width(56.dp)) {
                    Text(slot.time, fontWeight = FontWeight.Bold)
                    Text(end.format(DateTimeFormatter.ofPattern("H:mm")), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f)) {
                    slot.lessons.forEachIndexed { i, l ->
                        if (i > 0) Spacer(Modifier.height(8.dp))
                        LessonView(l)
                    }
                }
            }
        }
    }
}

@Composable
private fun LessonView(l: Lesson) {
    val m = Regex("^(\\d)\\.\\s*").find(l.subject)
    val subject = if (m != null) l.subject.substring(m.range.last + 1) else l.subject
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (m != null) {
                Text(
                    "${m.groupValues[1]} подгр.", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(subject, fontWeight = FontWeight.SemiBold)
        }
        val meta = listOfNotNull(
            l.room.takeIf { it.isNotBlank() }?.let { "каб. $it" },
            l.teacher.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (meta.isNotEmpty()) Text(meta, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GroupSheet(groups: List<String>, selected: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(
                q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск, например 22 фо") },
            )
            val norm = q.replace(" ", "").lowercase()
            val byCourse = groups.filter { norm.isEmpty() || it.replace(" ", "").contains(norm) }
                .groupBy { if (it.firstOrNull()?.isDigit() == true) "${it[0]} курс" else "Другие" }
                .toSortedMap()
            for ((course, list) in byCourse) {
                Text(course, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (g in list) {
                        val sel = g == selected
                        Surface(
                            onClick = { onPick(g) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (sel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        ) { Text(g, Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
