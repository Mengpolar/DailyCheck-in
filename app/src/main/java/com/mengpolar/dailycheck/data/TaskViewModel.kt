package com.mengpolar.dailycheck.data

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val ZONE: ZoneId = ZoneId.systemDefault()

/** 首页轨道盘的渲染模型 */
data class OrbitTask(
    val id: Long,
    val name: String,
    val note: String,
    val intervalDays: Int,
    val anchorMin: Int,
    val anchorWeekday: Int,
    val cycleLabel: String,
    val anchorLabel: String,
    val state: PlanetState,
    /** 周期进度 0..1 */
    val frac: Float,
    /** 彗尾长度 0..1（打卡后随时间淡出） */
    val tail: Float,
    val hue: Int,
    /** 轨道序号 = 创建顺序（每个任务一条独立轨道，由内向外） */
    val ring: Int,
    val nextText: String,
    val circleNo: Int,
    val lastCheckText: String,
    val recordCount: Int,
    /** 当前周期窗口结束时刻（epoch ms），聚焦页倒计时用 */
    val periodEndMs: Long,
    val hasImage: Boolean,
    val imagePath: String?,
)

data class OrbitUiState(
    val today: LocalDate,
    val items: List<OrbitTask> = emptyList(),
) {
    val doneCount: Int get() = items.count { it.state == PlanetState.DONE }
    val total: Int get() = items.size
}

/** 列表项展示状态（详情页复用） */
data class TaskUi(
    val id: Long,
    val name: String,
    val note: String,
    val intervalDays: Int,
    val anchorMin: Int,
    val anchorWeekday: Int,
    val cycleLabel: String,
    val anchorLabel: String,
    val done: Boolean,
    val statusText: String,
    val hasImage: Boolean,
)

data class DetailUiState(
    val task: TaskUi,
    val recordCount: Int,
    val lastRecordDate: LocalDate?,
    val records: List<CheckRecord>,
)

class TaskViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TaskRepository.get(application)
    private val dataFlow = MutableStateFlow(repo.snapshot())
    private val refreshTick = MutableStateFlow(System.currentTimeMillis())
    private val detailTaskId = MutableStateFlow<Long?>(null)

    init {
        // 定期刷新：跨周期边界时自动更新行星状态
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                refreshTick.value = System.currentTimeMillis()
            }
        }
    }

    private fun lastCheckMs(d: AppData, taskId: Long?): Long? =
        taskId?.let { id -> d.records.filter { it.taskId == id }.maxOfOrNull { it.createdAtEpochMs } }

    private fun lastCheckText(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(ZONE)
        val today = LocalDate.now()
        val time = "%02d:%02d".format(t.hour, t.minute)
        return when (t.toLocalDate()) {
            today -> "今日 $time"
            today.minusDays(1) -> "昨日 $time"
            else -> "${t.monthValue}/${t.dayOfMonth} $time"
        }
    }

    private fun nextText(t: Task, state: PlanetState, nowMs: Long): String = when (state) {
        PlanetState.DONE -> "下次 ${formatWindowStart(t.periodWindow(nowMs).endMs)}"
        PlanetState.LATE -> "打卡后重新点亮"
        PlanetState.NEAR -> "即将到期 · 若隐若现"
        PlanetState.PENDING -> "周期内任意时间可打卡"
    }

    private fun hueOf(name: String): Int {
        val palette = listOf(42, 205, 160, 268, 8, 330, 190, 95, 65, 285)
        return palette[(name.hashCode().let { if (it < 0) it * -1 else it }) % palette.size]
    }

    private fun computeOrbit(d: AppData, nowMs: Long): OrbitUiState {
        val today = Instant.ofEpochMilli(nowMs).atZone(ZONE).toLocalDate()
        val items = d.tasks.mapIndexed { index, task ->
            val lastMs = lastCheckMs(d, task.id)
            val state = task.planetState(nowMs, lastMs)
            val done = state == PlanetState.DONE
            val records = d.records.filter { it.taskId == task.id }
            val window = task.periodWindow(nowMs)
            val tail = if (done && lastMs != null) {
                (1f - ((nowMs - lastMs).toFloat() / window.durMs).coerceIn(0f, 1f)) * 0.3f + 0.05f
            } else 0f
            OrbitTask(
                id = task.id,
                name = task.name,
                note = task.note,
                intervalDays = task.intervalDays,
                anchorMin = task.anchorMin,
                anchorWeekday = task.anchorWeekday,
                cycleLabel = task.cycleLabel(),
                anchorLabel = task.anchorLabel(),
                state = state,
                frac = task.periodFraction(nowMs),
                tail = tail,
                hue = hueOf(task.name),
                ring = index,
                nextText = nextText(task, state, nowMs),
                circleNo = records.size + 1,
                lastCheckText = lastMs?.let { lastCheckText(it) } ?: "从未打卡",
                recordCount = records.size,
                periodEndMs = window.endMs,
                hasImage = task.imagePath != null,
                imagePath = task.imagePath,
            )
        }
        return OrbitUiState(today = today, items = items)
    }

    val orbitState: StateFlow<OrbitUiState> =
        combine(dataFlow, refreshTick) { d, now -> computeOrbit(d, now) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                computeOrbit(repo.snapshot(), System.currentTimeMillis()),
            )

    val detailState: StateFlow<DetailUiState?> =
        combine(dataFlow, detailTaskId) { d, id ->
            val task = d.tasks.firstOrNull { it.id == id } ?: return@combine null
            val nowMs = System.currentTimeMillis()
            val lastMs = lastCheckMs(d, id)
            val state = task.planetState(nowMs, lastMs)
            val records = d.records.filter { it.taskId == id }.sortedByDescending { it.createdAtEpochMs }
            val statusText = when (state) {
                PlanetState.DONE -> "✓ 本周期已点亮"
                PlanetState.LATE -> "⚠ 已逾期 · 正在熄灭"
                PlanetState.NEAR -> "临近到期 · 若隐若现"
                PlanetState.PENDING -> "待打卡 · 黑白星"
            }
            DetailUiState(
                task = TaskUi(
                    id = task.id,
                    name = task.name,
                    note = task.note,
                    intervalDays = task.intervalDays,
                    anchorMin = task.anchorMin,
                    anchorWeekday = task.anchorWeekday,
                    cycleLabel = task.cycleLabel(),
                    anchorLabel = task.anchorLabel(),
                    done = state == PlanetState.DONE,
                    statusText = statusText,
                    hasImage = task.imagePath != null,
                ),
                recordCount = records.size,
                lastRecordDate = records.maxOfOrNull { LocalDate.ofEpochDay(it.epochDay) },
                records = records,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun openDetail(id: Long?) {
        detailTaskId.value = id
    }

    private fun refresh() {
        dataFlow.value = repo.snapshot()
        refreshTick.value = System.currentTimeMillis()
    }

    fun addTask(
        name: String, note: String,
        intervalDays: Int, anchorMin: Int, anchorWeekday: Int, image: Bitmap?,
    ) {
        repo.addTask(name, note, intervalDays, anchorMin, anchorWeekday, image)
        refresh()
    }

    fun editTask(id: Long, name: String, note: String, intervalDays: Int, anchorMin: Int, anchorWeekday: Int) {
        val existing = dataFlow.value.tasks.firstOrNull { it.id == id } ?: return
        repo.updateTask(
            existing.copy(
                name = name.trim(),
                note = note.trim(),
                intervalDays = intervalDays.coerceAtLeast(1),
                anchorMin = anchorMin.coerceIn(0, 1439),
                anchorWeekday = anchorWeekday.coerceIn(1, 7),
            )
        )
        refresh()
    }

    fun setPlanetImage(id: Long, image: Bitmap?) {
        repo.setPlanetImage(id, image)
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
