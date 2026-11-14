package ru.mocomk.raspisanie

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class WeekInfo(
    val start: String,
    val end: String,
    val groups: Map<String, String>, // название группы -> имя файла
    val source: String,
    val updated: String,
)

data class ScheduleIndex(val updated: String, val groups: List<String>, val weeks: List<WeekInfo>)

data class Lesson(val subject: String, val room: String, val teacher: String)

data class Slot(val time: String, val lessons: List<Lesson>)

/** Замена в расписании: kind = changed / added / removed; at — когда колледж её внёс. */
data class Change(
    val at: String,
    val date: String,
    val time: String,
    val kind: String,
    val old: List<Lesson>,
    val new: List<Lesson>,
)

data class GroupWeek(
    val group: String,
    val start: String,
    val end: String,
    val source: String,
    val days: Map<String, List<Slot>>,
    val scans: List<String>,
    val changes: List<Change> = emptyList(),
)

/** Строка справочника по колледжу: кто, где и когда. */
data class CollegeRow(
    val date: String,
    val time: String,
    val group: String,
    val subject: String,
    val room: String,
    val teacher: String,
)

class Loaded<T>(val value: T, val fromCache: Boolean)

/** Настройки приложения. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var group: String?
        get() = sp.getString("group", null)
        set(v) = sp.edit().putString("group", v).apply()

    var baseUrl: String
        get() = sp.getString("baseUrl", null) ?: BuildConfig.BASE_URL
        set(v) = sp.edit().putString("baseUrl", if (v.endsWith("/")) v else "$v/").apply()

    /** За сколько минут напоминать о паре (0 — не напоминать). */
    var remindMinutes: Int
        get() = sp.getInt("remind", 10)
        set(v) = sp.edit().putInt("remind", v).apply()

    /** Избранные преподаватели. */
    var favorites: Set<String>
        get() = sp.getStringSet("fav", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("fav", v).apply()

    /** Отметки "неделя -> время обновления", о которых уже сообщили. */
    var seenUpdates: JSONObject
        get() = runCatching { JSONObject(sp.getString("seen", "{}")!!) }.getOrDefault(JSONObject())
        set(v) = sp.edit().putString("seen", v.toString()).apply()
}

/**
 * Загрузка данных с сайта. Каждый скачанный файл сохраняется на телефоне,
 * поэтому все открытые недели доступны без интернета (это и есть архив).
 */
class Repository(private val context: Context) {
    val prefs = Prefs(context)
    private val cacheRoot = File(context.filesDir, "data")

    private fun cacheFile(path: String) = File(cacheRoot, path)

    private fun download(path: String): String {
        val encoded = path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        val conn = URL(prefs.baseUrl + "data/" + encoded).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.useCaches = false
        try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun fetch(path: String, preferCache: Boolean = false): Loaded<String> =
        withContext(Dispatchers.IO) {
            val f = cacheFile(path)
            if (preferCache && f.exists()) return@withContext Loaded(f.readText(), true)
            try {
                val text = download(path)
                JSONObject(text) // проверка, что это JSON
                f.parentFile?.mkdirs()
                f.writeText(text)
                Loaded(text, false)
            } catch (e: Exception) {
                if (f.exists()) Loaded(f.readText(), true) else throw e
            }
        }

    suspend fun loadIndex(): Loaded<ScheduleIndex> {
        val raw = fetch("index.json")
        val idx = parseIndex(JSONObject(raw.value))
        return Loaded(mergeLocalArchive(idx), raw.fromCache)
    }

    /** Добавить недели, которые есть только в памяти телефона. */
    private fun mergeLocalArchive(idx: ScheduleIndex): ScheduleIndex {
        val known = idx.weeks.associateBy { it.start }.toMutableMap()
        val dirs = File(cacheRoot, "weeks").listFiles() ?: return idx
        for (dir in dirs) {
            if (dir.name in known) continue
            val groups = mutableMapOf<String, String>()
            var end = dir.name
            var source = ""
            dir.listFiles { f -> f.extension == "json" }?.forEach { f ->
                runCatching {
                    val gw = parseGroupWeek(JSONObject(f.readText()))
                    groups[gw.group] = f.nameWithoutExtension
                    end = gw.end
                    source = gw.source
                }
            }
            if (groups.isNotEmpty()) known[dir.name] = WeekInfo(dir.name, end, groups, source, "")
        }
        val weeks = known.values.sortedByDescending { it.start }
        val allGroups = (idx.groups + weeks.flatMap { it.groups.keys }).distinct().sorted()
        return ScheduleIndex(idx.updated, allGroups, weeks)
    }

    suspend fun loadGroupWeek(week: WeekInfo, group: String, preferCache: Boolean = false): Loaded<GroupWeek>? {
        val file = week.groups[group] ?: return null
        val raw = fetch("weeks/${week.start}/$file.json", preferCache)
        return Loaded(parseGroupWeek(JSONObject(raw.value)), raw.fromCache)
    }

    /** Скачать все недели группы, которых ещё нет на телефоне (сохранить архив). */
    suspend fun saveArchive(index: ScheduleIndex, group: String) {
        for (w in index.weeks) {
            val file = w.groups[group] ?: continue
            if (!cacheFile("weeks/${w.start}/$file.json").exists()) {
                runCatching { loadGroupWeek(w, group) }
            }
        }
    }

    /** Сохранённая на телефоне копия файла (для виджета и напоминаний, без интернета). */
    fun cached(path: String): String? = cacheFile(path).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }

    /** Справочник по колледжу на неделю. */
    suspend fun loadCollege(week: WeekInfo, preferCache: Boolean = false): List<CollegeRow> {
        val raw = fetch("college/${week.start}.json", preferCache)
        val a = JSONObject(raw.value).optJSONArray("rows") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val r = a.getJSONArray(i)
            CollegeRow(r.optString(0), r.optString(1), r.optString(2), r.optString(3), r.optString(4), r.optString(5))
        }
    }

    fun scanUrl(week: GroupWeek, file: String) = prefs.baseUrl + "data/weeks/${week.start}/$file"

    companion object {
        fun parseIndex(o: JSONObject): ScheduleIndex {
            val weeks = mutableListOf<WeekInfo>()
            val wa = o.optJSONArray("weeks")
            if (wa != null) for (i in 0 until wa.length()) {
                val w = wa.getJSONObject(i)
                val g = w.optJSONObject("groups") ?: JSONObject()
                val map = mutableMapOf<String, String>()
                g.keys().forEach { k -> map[k] = g.getString(k) }
                weeks += WeekInfo(
                    w.getString("start"), w.getString("end"), map,
                    w.optString("source"), w.optString("updated"),
                )
            }
            val ga = o.optJSONArray("groups")
            val groups = if (ga == null) emptyList() else (0 until ga.length()).map { ga.getString(it) }
            return ScheduleIndex(o.optString("updated"), groups, weeks.sortedByDescending { it.start })
        }

        fun parseGroupWeek(o: JSONObject): GroupWeek {
            val days = mutableMapOf<String, List<Slot>>()
            val d = o.optJSONObject("days") ?: JSONObject()
            d.keys().forEach { date ->
                val arr = d.getJSONArray(date)
                days[date] = (0 until arr.length()).map { i ->
                    val p = arr.getJSONObject(i)
                    val items = p.getJSONArray("items")
                    Slot(p.getString("time"), (0 until items.length()).map { j ->
                        val it = items.getJSONObject(j)
                        Lesson(it.optString("subject"), it.optString("room"), it.optString("teacher"))
                    })
                }
            }
            val sc = o.optJSONArray("scans")
            val ch = o.optJSONArray("changes")
            val changes = if (ch == null) emptyList() else (0 until ch.length()).map { i ->
                val c = ch.getJSONObject(i)
                Change(
                    c.optString("at"), c.optString("date"), c.optString("time"), c.optString("kind"),
                    lessons(c.optJSONArray("old")), lessons(c.optJSONArray("new")),
                )
            }
            return GroupWeek(
                o.getString("group"), o.getString("start"), o.getString("end"),
                o.optString("source"), days,
                if (sc == null) emptyList() else (0 until sc.length()).map { sc.getString(it) },
                changes,
            )
        }

        private fun lessons(a: org.json.JSONArray?): List<Lesson> =
            if (a == null) emptyList() else (0 until a.length()).map { j ->
                val it = a.getJSONObject(j)
                Lesson(it.optString("subject"), it.optString("room"), it.optString("teacher"))
            }

        /** Время начала пары "9:00" -> LocalTime. */
        fun parseTime(t: String): java.time.LocalTime? =
            runCatching { java.time.LocalTime.parse(t.trim().padStart(5, '0')) }.getOrNull()

        /** Название предмета без пометки подгруппы "1. ". */
        fun cleanSubject(s: String) = s.replace(Regex("""^\d\.\s*"""), "")

        /** Номер подгруппы из "1. Предмет" или null. */
        fun subgroup(s: String) = Regex("""^(\d)\.\s*""").find(s)?.groupValues?.get(1)
    }
}
