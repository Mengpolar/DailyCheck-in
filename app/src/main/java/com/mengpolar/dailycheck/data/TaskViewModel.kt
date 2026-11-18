package com.mengpolar.dailycheck.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 首页列表项的展示状态 */
data class TaskUi(
    val id: Long,
    val name: String,
    val note: String,
    val cycleType: CycleType,
    val intervalDays: Int,
    val cycleLabel: String,
    val done: Boolean,
    val statusText: String,
)

data class HomeUiState(
    val today: LocalDate,
    val pending: List<TaskUi> = emptyList(),
    val done: List<TaskUi> = emptyList(),
) {
    val total: Int get() = pending.size + done.size
    val doneCount: Int get() = done.size
}

data class DetailUiState(
    val task: TaskUi,
    val recordCount: Int,
    val lastRecordDate: LocalDate?,
    val records: List<CheckRecord>,
)

class TaskViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TaskRepository.get(application)
    private val dataFlow = MutableStateFlow(repo.snapshot())
    private val todayFlow = MutableStateFlow(LocalDate.now())
    private val detailTaskId = MutableStateFlow<Long?>(null)

    init {
        // 跨天时自动刷新状态（应用长时间停留时）
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                val now = LocalDate.now()
                if (now != todayFlow.value) todayFlow.value = now
            }
        }
    }

    val homeState: StateFlow<HomeUiState> =
        combine(dataFlow, todayFlow) { d, today -> computeHome(d, today) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                computeHome(repo.snapshot(), LocalDate.now()),
            )

    val detailState: StateFlow<DetailUiState?> =
        combine(dataFlow, todayFlow, detailTaskId) { d, today, id ->
            val task = d.tasks.firstOrNull { it.id == id } ?: return@combine null
            val records = d.records.filter { it.taskId == id }.sortedByDescending { it.createdAtEpochMs }
            DetailUiState(
                task = task.toUi(today),
                recordCount = records.size,
                lastRecordDate = records.maxOfOrNull { LocalDate.ofEpochDay(it.epochDay) },
                records = records,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun computeHome(d: AppData, today: LocalDate): HomeUiState {
        val uis = d.tasks.map { it.toUi(today) }
        return HomeUiState(
            today = today,
            pending = uis.filterNot { it.done },
            done = uis.filter { it.done },
        )
    }

    private fun Task.toUi(today: LocalDate): TaskUi {
        val done = isDoneForPeriod(today)
        val due = nextDueDate(today)
        val status = when {
            done -> when (cycleType) {
                CycleType.DAILY -> "今日已完成"
                CycleType.WEEKLY -> "本周已完成"
                CycleType.MONTHLY -> "本月已完成"
                CycleType.EVERY_N_DAYS -> "下次 ${formatDateShort(due)} 打卡"
            }
            due.isEqual(today) -> "今日待打卡"
            due.isBefore(today) -> "已逾期 ${today.toEpochDay() - due.toEpochDay()} 天"
            else -> "待打卡"
        }
        return TaskUi(
            id = id,
            name = name,
            note = note,
            cycleType = cycleType,
            intervalDays = intervalDays,
            cycleLabel = cycleLabel(),
            done = done,
            statusText = status,
        )
    }

    fun openDetail(id: Long?) {
        detailTaskId.value = id
    }

    private fun refresh() {
        dataFlow.value = repo.snapshot()
    }

    fun addTask(name: String, note: String, cycleType: CycleType, intervalDays: Int) {
        repo.addTask(name, note, cycleType, intervalDays)
        refresh()
    }

    fun editTask(id: Long, name: String, note: String, cycleType: CycleType, intervalDays: Int) {
        val existing = dataFlow.value.tasks.firstOrNull { it.id == id } ?: return
        repo.updateTask(
            existing.copy(
                name = name.trim(),
                note = note.trim(),
                cycleType = cycleType,
                intervalDays = if (cycleType == CycleType.EVERY_N_DAYS) intervalDays.coerceAtLeast(1) else 1,
            )
        )
        refresh()
    }

    fun deleteTask(id: Long) {
        repo.deleteTask(id)
        refresh()
        if (detailTaskId.value == id) openDetail(null)
    }

    fun checkIn(taskId: Long): CheckRecord {
        val record = repo.checkIn(taskId, LocalDate.now())
        refresh()
        return record
    }

    fun undoCheck(recordId: Long) {
        repo.undoCheck(recordId)
        refresh()
    }
}
