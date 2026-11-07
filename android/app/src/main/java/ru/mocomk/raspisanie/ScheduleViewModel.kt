package ru.mocomk.raspisanie

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

const val DEFAULT_GROUP = "22 фо"

data class UiState(
    val loading: Boolean = true,
    val index: ScheduleIndex? = null,
    val group: String? = null,
    val weekIdx: Int = 0,
    val week: GroupWeek? = null,
    val offline: Boolean = false,
    val message: String? = null,
) {
    val currentWeek: WeekInfo? get() = index?.weeks?.getOrNull(weekIdx)
}

class ScheduleViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var weekJob: Job? = null

    init {
        refresh(initial = true)
    }

    fun refresh(initial: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            val idx = runCatching { repo.loadIndex() }.getOrNull()
            if (idx == null) {
                _state.update {
                    it.copy(loading = false, message = "Нет связи с сервером и нет сохранённых данных.\nПроверьте интернет или адрес сервера в настройках.")
                }
                return@launch
            }
            val index = idx.value
            val group = repo.prefs.group?.takeIf { it in index.groups }
                ?: DEFAULT_GROUP.takeIf { it in index.groups }
                ?: index.groups.firstOrNull()
            val keepWeek = _state.value.currentWeek?.start
            val weekIdx = if (!initial && keepWeek != null)
                index.weeks.indexOfFirst { it.start == keepWeek }.coerceAtLeast(0)
            else pickWeek(index)
            _state.update { it.copy(index = index, group = group, weekIdx = weekIdx, offline = idx.fromCache) }
            loadWeek()
            if (group != null && !idx.fromCache) launch { repo.saveArchive(index, group) }
        }
    }

    private fun pickWeek(index: ScheduleIndex): Int {
        var d = LocalDate.now()
        if (d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
        val t = d.toString()
        val cur = index.weeks.indexOfFirst {
            it.start <= t && t <= LocalDate.parse(it.end).plusDays(1).toString()
        }
        if (cur >= 0) return cur
        val past = index.weeks.indexOfFirst { it.start <= t }
        return if (past >= 0) past else (index.weeks.size - 1).coerceAtLeast(0)
    }

    private fun loadWeek() {
        val s = _state.value
        val w = s.currentWeek
        val g = s.group
        weekJob?.cancel()
        if (w == null || g == null) {
            _state.update { it.copy(loading = false, week = null) }
            return
        }
        if (g !in w.groups) {
            _state.update { it.copy(loading = false, week = null, message = "На эту неделю для группы «$g» расписания нет.") }
            return
        }
        weekJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            val res = runCatching { repo.loadGroupWeek(w, g, preferCache = isPastWeek(w)) }
            res.onSuccess { r ->
                _state.update { it.copy(loading = false, week = r?.value, offline = it.offline || (r?.fromCache == true && !isPastWeek(w))) }
            }.onFailure {
                _state.update { it.copy(loading = false, week = null, message = "Не удалось загрузить расписание.") }
            }
        }
    }

    /** Прошедшие недели не меняются — берём из памяти телефона. */
    private fun isPastWeek(w: WeekInfo) = LocalDate.parse(w.end).plusDays(7).isBefore(LocalDate.now())

    fun selectGroup(g: String) {
        repo.prefs.group = g
        repo.prefs.seenUpdates = org.json.JSONObject() // уведомления — с чистого листа
        _state.update { it.copy(group = g) }
        loadWeek()
        _state.value.index?.let { idx -> viewModelScope.launch { repo.saveArchive(idx, g) } }
        UpdateWorker.schedule(getApplication())
    }

    fun shiftWeek(delta: Int) {
        val idx = _state.value.index ?: return
        // список недель отсортирован от новых к старым: "назад" = индекс +1
        val n = (_state.value.weekIdx - delta).coerceIn(0, idx.weeks.size - 1)
        if (n == _state.value.weekIdx) return
        _state.update { it.copy(weekIdx = n) }
        loadWeek()
    }

    fun setBaseUrl(url: String) {
        repo.prefs.baseUrl = url.trim()
        refresh(initial = true)
    }
}
