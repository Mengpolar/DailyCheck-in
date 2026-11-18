package com.mengpolar.dailycheck.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * 单例仓库：内存中持有全部数据，变更后原子写入本地 JSON 文件。
 * 数据量小（任务几十个、记录数千条以内），无需数据库。
 */
class TaskRepository private constructor(context: Context) {

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
            data = json.decodeFromString(AppData.serializer(), file.readText())
        } catch (e: Exception) {
            // 数据损坏时备份原文件并重新开始，避免崩溃循环
            runCatching { file.copyTo(File(file.parentFile, "dailycheck_data_backup.json"), overwrite = true) }
            data = AppData()
        }
    }

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

    fun addTask(name: String, note: String, cycleType: CycleType, intervalDays: Int): Task = synchronized(lock) {
        val task = Task(
            id = data.nextTaskId,
            name = name.trim(),
            note = note.trim(),
            cycleType = cycleType,
            intervalDays = if (cycleType == CycleType.EVERY_N_DAYS) intervalDays.coerceAtLeast(1) else 1,
            createdEpochDay = LocalDate.now().toEpochDay(),
        )
        data = data.copy(tasks = data.tasks + task, nextTaskId = data.nextTaskId + 1)
        persist()
        task
    }

    fun updateTask(task: Task) = synchronized(lock) {
        data = data.copy(tasks = data.tasks.map { if (it.id == task.id) task else it })
        persist()
    }

    fun deleteTask(taskId: Long) = synchronized(lock) {
        data = data.copy(
            tasks = data.tasks.filterNot { it.id == taskId },
            records = data.records.filterNot { it.taskId == taskId },
        )
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

    companion object {
        @Volatile
        private var instance: TaskRepository? = null

        fun get(context: Context): TaskRepository =
            instance ?: synchronized(this) {
                instance ?: TaskRepository(context.applicationContext).also { instance = it }
            }
    }
}
