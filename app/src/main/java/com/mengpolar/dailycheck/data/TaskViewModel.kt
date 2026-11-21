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
    val cycleLabel: String,
    val state: PlanetState,
    /** 周期进度 0..1 */
    val frac: Float,
    /** 彗尾长度 0..1（打卡后随时间淡出） */
    val tail: Float,
    val hue: Int,
    /** 轨道序号 = 创建顺序（每个任务一条独立轨道，由内向外） */
    val ring: Int,
    val recordCount: Int,
    /** 当前周期窗口结束时刻（epoch ms），聚焦页倒计时用 */
    val periodEndMs: Long,
    /** 一次性任务的有效天数（0 = 非一次性） */
    val onceDays: Int,
    /** 当前处于周期的第 X 天（编辑表单回填用） */
    val cycleDayX: Int,
    val hasImage: Boolean,
    val imagePath: String?,
)

data class OrbitUiState(
    val today: LocalDate,
    val items: List<OrbitTask> = emptyList(),
)

/** 编辑表单回填用（详情页仅展示 records，不再复用） */
data class TaskUi(
    val id: Long,
    val name: String,
    val note: String,
    val intervalDays: Int,
    val anchorMin: Int,
    val hasImage: Boolean,
    val onceDays: Int = 0,
    val cycleDayX: Int = 1,
)

/** 历史页状态：仅打卡记录列表 */
data class DetailUiState(
    val records: List<CheckRecord>,
)

class TaskViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = TaskRepository.get(application)
    private val dataFlow = MutableStateFlow(repo.snapshot())
    private val refreshTick = MutableStateFlow(System.currentTimeMillis())
    private val detailTaskId = MutableStateFlow<Long?>(null)

    // 界面设置：主页行星下方任务名称的显示（持久化）
    private val prefs = application.getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
    private val _showPlanetNames = MutableStateFlow(prefs.getBoolean("show_planet_names", true))
    val showPlanetNames: StateFlow<Boolean> = _showPlanetNames
    fun setShowPlanetNames(enabled: Boolean) {
        _showPlanetNames.value = enabled
        prefs.edit().putBoolean("show_planet_names", enabled).apply()
    }

    /** 新建流程暂存的行星图（放在 VM 中以在屏幕旋转后保留，变更可观察） */
    private val _pendingImage = MutableStateFlow<Bitmap?>(null)

    /** 可观察读取：Composable 用 collectAsState 订阅 */
    val pendingImageState: StateFlow<Bitmap?> = _pendingImage
    var pendingImage: Bitmap?
        get() = _pendingImage.value
        set(value) { _pendingImage.value = value }

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

    private fun hueOf(name: String): Int {
        val palette = listOf(42, 205, 160, 268, 8, 330, 190, 95, 65, 285)
        return palette[(name.hashCode().let { if (it < 0) it * -1 else it }) % palette.size]
    }

    private fun computeOrbit(d: AppData, nowMs: Long): OrbitUiState {
        val today = Instant.ofEpochMilli(nowMs).atZone(ZONE).toLocalDate()
        val todayEpochDay = today.toEpochDay()
        val items = d.tasks.mapIndexedNotNull { index, task ->
            val lastMs = lastCheckMs(d, task.id)
            val state = task.planetState(nowMs, lastMs)
            val done = state == PlanetState.DONE
            val window = task.periodWindow(nowMs)
            // 一次性任务过期未打卡 → 消散，不再呈现
            if (task.intervalDays <= 0 && !done && window.endMs < nowMs) return@mapIndexedNotNull null
            val records = d.records.filter { it.taskId == task.id }
            val tail = if (done && lastMs != null) {
                (1f - ((nowMs - lastMs).toFloat() / window.durMs).coerceIn(0f, 1f)) * 0.3f + 0.05f
            } else 0f
            val cycleDayX = if (task.intervalDays <= 0) 0
            else (((todayEpochDay - task.createdEpochDay).coerceAtLeast(0) % task.intervalDays.coerceAtLeast(1)) + 1).toInt()
            OrbitTask(
                id = task.id,
                name = task.name,
                note = task.note,
                intervalDays = task.intervalDays,
                anchorMin = task.anchorMin,
                cycleLabel = task.cycleLabel(),
                state = state,
                frac = task.periodFraction(nowMs),
                tail = tail,
                hue = hueOf(task.name),
                ring = index,
                recordCount = records.size,
                periodEndMs = window.endMs,
                onceDays = task.onceDays,
                cycleDayX = cycleDayX,
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

    /** 历史页状态：detailTaskId 为 null 时保持 null（不弹出历史面板） */
    val detailState: StateFlow<DetailUiState?> =
        combine(dataFlow, detailTaskId) { d, id ->
            if (id == null) return@combine null
            DetailUiState(
                records = d.records.filter { it.taskId == id }.sortedByDescending { it.createdAtEpochMs },
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
        intervalDays: Int, anchorMin: Int, image: Bitmap?,
        cycleDayX: Int = 1, onceDays: Int = 0,
    ) {
        repo.addTask(name, note, intervalDays, anchorMin, image, cycleDayX, onceDays)
        refresh()
    }

    fun editTask(
        id: Long, name: String, note: String,
        intervalDays: Int, anchorMin: Int,
        cycleDayX: Int = 1, onceDays: Int = 0,
    ) {
        val existing = dataFlow.value.tasks.firstOrNull { it.id == id } ?: return
        val today = LocalDate.now().toEpochDay()
        val once = intervalDays <= 0
        val interval = if (once) 0 else intervalDays.coerceIn(1, 9999)
        val x = if (once) 0 else cycleDayX.coerceIn(1, interval)
        repo.updateTask(
            existing.copy(
                name = name.trim(),
                note = note.trim(),
                intervalDays = interval,
                anchorMin = anchorMin.coerceIn(0, 1439),
                createdEpochDay = if (once) today else today - x,
                onceDays = if (once) onceDays.coerceIn(0, 36500) else 0,
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

    /** 删除一条打卡记录（同撤销逻辑：移除记录并回退任务最近打卡日） */
    fun deleteRecord(recordId: Long) {
        repo.undoCheck(recordId)
        refresh()
    }
}
