package com.mengpolar.dailycheck.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 单例仓库：内存中持有全部数据，变更后原子写入本地 JSON 文件。
 * 另负责行星图片的存取（filesDir/planets/task_<id>.png）。
 */
class TaskRepository private constructor(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "dailycheck_data.json")
    private val lock = Any()
    private var data = AppData()

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) return
        try {
            data = json.decodeFromString(AppData.serializer(), migrateLegacy(file.readText()))
            healOrphanPlanetImages()
        } catch (e: Exception) {
            // 数据损坏时备份原文件并重新开始，避免崩溃循环
            runCatching { file.copyTo(File(file.parentFile, "dailycheck_data_backup.json"), overwrite = true) }
            data = AppData()
        }
    }

    /**
     * 旧版（v0.0.4 及以前）使用 cycleType 枚举；统一为「每 N 天 + 起始时刻」后，
     * 读取时把 WEEKLY→7、MONTHLY→30、DAILY→1 平滑迁移，旧字段丢弃。
     */
    private fun migrateLegacy(text: String): String = runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
        val tasks = obj["tasks"]?.jsonArray ?: return text
        val migrated = tasks.map { el ->
            val t = el.jsonObject.toMutableMap()
            val cycle = (t["cycleType"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (cycle != null) {
                when (cycle) {
                    "WEEKLY" -> t["intervalDays"] = kotlinx.serialization.json.JsonPrimitive(7)
                    "MONTHLY" -> t["intervalDays"] = kotlinx.serialization.json.JsonPrimitive(30)
                    "DAILY" -> t["intervalDays"] = kotlinx.serialization.json.JsonPrimitive(1)
                    else -> Unit // EVERY_N_DAYS：沿用其自身 intervalDays
                }
                t.remove("cycleType")
            }
            kotlinx.serialization.json.JsonObject(t)
        }
        val out = obj.toMutableMap()
        out["tasks"] = kotlinx.serialization.json.JsonArray(migrated)
        kotlinx.serialization.json.JsonObject(out).toString()
    }.getOrDefault(text)

    private fun persist() {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(AppData.serializer(), data))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    fun snapshot(): AppData = synchronized(lock) { data }

    /**
     * 新建任务。
     * 重复任务（intervalDays ≥ 1）：cycleDayX =「当前第 X 天」(1..N)，即今天已是周期第 X 天，
     * 周期始于 X-1 天前 → createdEpochDay = 今天 - (X-1)。
     * （历史版本误用 今天-X，导致第 N 天创建时窗口已翻入下一周期）
     * 一次性任务（intervalDays = 0）：onceDays = 还有 N 天，截止 = 今天 + N 天的 anchorMin 时刻。
     */
    fun addTask(
        name: String, note: String,
        intervalDays: Int, anchorMin: Int, image: Bitmap?,
        cycleDayX: Int = 1, onceDays: Int = 0,
    ): Task = synchronized(lock) {
        val today = LocalDate.now().toEpochDay()
        val once = intervalDays <= 0
        val interval = if (once) 0 else intervalDays.coerceIn(1, 9999)
        val x = if (once) 0 else cycleDayX.coerceIn(1, interval)
        var task = Task(
            id = data.nextTaskId,
            name = name.trim(),
            note = note.trim(),
            intervalDays = interval,
            anchorMin = anchorMin.coerceIn(0, 1439),
            createdEpochDay = if (once) today else today - (x - 1),
            onceDays = if (once) onceDays.coerceIn(0, 36500) else 0,
        )
        if (image != null) {
            savePlanetImageLocked(task.id, image)
            task = task.copy(imagePath = planetImagePath(task.id))
        }
        data = data.copy(tasks = data.tasks + task, nextTaskId = data.nextTaskId + 1)
        persist()
        task
    }

    /**
     * 重排任务（行星列表拖动排序）：data.tasks 列表顺序即轨道环序（内→外）。
     * orderedIds 允许是子集（如星海行星不参与排序）：给定 id 之间按新顺序排列，
     * 未给出的任务保持在各自原位置不变。
     */
    fun reorderTasks(orderedIds: List<Long>) = synchronized(lock) {
        val seq = orderedIds.mapNotNull { id -> data.tasks.firstOrNull { it.id == id } }
        if (seq.size != orderedIds.size || seq.size < 2) return@synchronized
        val ids = orderedIds.toSet()
        var qi = 0
        val reordered = data.tasks.map { t -> if (t.id in ids) seq[qi++] else t }
        data = data.copy(tasks = reordered)
        persist()
    }

    fun updateTask(task: Task) = synchronized(lock) {
        data = data.copy(tasks = data.tasks.map { if (it.id == task.id) task else it })
        persist()
    }

    fun setPlanetImage(taskId: Long, image: Bitmap?) = synchronized(lock) {
        val updated = data.tasks.map {
            if (it.id == taskId) {
                if (image != null) {
                    savePlanetImageLocked(taskId, image)
                    it.copy(imagePath = planetImagePath(taskId))
                } else {
                    deletePlanetImageLocked(taskId)
                    it.copy(imagePath = null)
                }
            } else it
        }
        data = data.copy(tasks = updated)
        persist()
    }

    fun deleteTask(taskId: Long) = synchronized(lock) {
        data = data.copy(
            tasks = data.tasks.filterNot { it.id == taskId },
            records = data.records.filterNot { it.taskId == taskId },
        )
        deletePlanetImageLocked(taskId)
        persist()
    }

    fun checkIn(taskId: Long, today: LocalDate): CheckRecord = synchronized(lock) {
        val record = CheckRecord(
            id = data.nextRecordId,
            taskId = taskId,
            epochDay = today.toEpochDay(),
            createdAtEpochMs = System.currentTimeMillis(),
        )
        data = data.copy(
            records = data.records + record,
            nextRecordId = data.nextRecordId + 1,
            tasks = data.tasks.map { if (it.id == taskId) it.copy(lastDoneEpochDay = record.epochDay) else it },
        )
        persist()
        record
    }

    /**
     * 回溯扫描：为上一周期补一条记录。记录不写实际操作时刻——
     * createdAtEpochMs 锚定在上一周期窗口起点（保证周期状态计算不被误判为当前周期已完成），
     * retro = true，追溯列表中以「补」呈现。
     */
    fun retroCheckIn(taskId: Long, window: PeriodWindow): CheckRecord = synchronized(lock) {
        val epochDay = Instant.ofEpochMilli(window.startMs).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
        val record = CheckRecord(
            id = data.nextRecordId,
            taskId = taskId,
            epochDay = epochDay,
            createdAtEpochMs = window.startMs,
            retro = true,
        )
        data = data.copy(
            records = data.records + record,
            nextRecordId = data.nextRecordId + 1,
            tasks = data.tasks.map {
                if (it.id == taskId) {
                    it.copy(lastDoneEpochDay = maxOf(it.lastDoneEpochDay ?: Long.MIN_VALUE, epochDay))
                } else it
            },
        )
        persist()
        record
    }

    /** 重构暗星：过期一次性任务以今天为窗口起点重新起算，恢复为正常有轨行星（历史保留） */
    fun reviveTask(taskId: Long) = synchronized(lock) {
        val today = LocalDate.now().toEpochDay()
        data = data.copy(
            tasks = data.tasks.map {
                if (it.id == taskId) it.copy(createdEpochDay = today, lastDoneEpochDay = null) else it
            },
        )
        persist()
    }

    /** 撤销一次打卡：删除该记录，并把任务最近打卡日期回退到剩余记录中的最大值 */
    fun undoCheck(recordId: Long) = synchronized(lock) {
        val record = data.records.firstOrNull { it.id == recordId } ?: return
        val rest = data.records.filterNot { it.id == recordId }
        val newLast = rest.filter { it.taskId == record.taskId }.maxOfOrNull { it.epochDay }
        data = data.copy(
            records = rest,
            tasks = data.tasks.map {
                if (it.id == record.taskId) it.copy(lastDoneEpochDay = newLast) else it
            },
        )
        persist()
    }

    // ───────────────────────── 行星图片 ─────────────────────────

    private val planetsDir: File get() = File(context.filesDir, "planets").apply { mkdirs() }

    private fun planetImagePath(taskId: Long) = "planets/task_$taskId.png"

    fun planetImageFile(task: Task): File? =
        task.imagePath?.let { File(context.filesDir, it).takeIf { f -> f.exists() } }

    private fun savePlanetImageLocked(taskId: Long, image: Bitmap) {
        runCatching {
            val out = File(planetsDir, "task_$taskId.png")
            out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 92, it) }
        }
    }

    private fun deletePlanetImageLocked(taskId: Long) {
        runCatching { File(planetsDir, "task_$taskId.png").delete() }
    }

    /** 历史版本写入过行星图片文件但未记录 imagePath，加载时自动找回 */
    private fun healOrphanPlanetImages() {
        val healed = data.tasks.map { t ->
            if (t.imagePath == null) {
                val f = File(context.filesDir, "planets/task_${t.id}.png")
                if (f.exists()) t.copy(imagePath = "planets/task_${t.id}.png") else t
            } else t
        }
        if (healed != data.tasks) {
            data = data.copy(tasks = healed)
            persist()
        }
    }

    companion object {
        @Volatile
        private var instance: TaskRepository? = null

        fun get(context: Context): TaskRepository =
            instance ?: synchronized(this) {
                instance ?: TaskRepository(context.applicationContext).also { instance = it }
            }
    }
}
