package com.mengpolar.dailycheck.data

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** 循环周期类型 */
@Serializable
enum class CycleType { DAILY, WEEKLY, MONTHLY, EVERY_N_DAYS }

/** 一个循环周期性任务 */
@Serializable
data class Task(
    val id: Long,
    val name: String,
    val note: String = "",
    val cycleType: CycleType,
    /** 仅 EVERY_N_DAYS 使用 */
    val intervalDays: Int = 1,
    val createdEpochDay: Long,
    /** 最近一次打卡的日期（epoch day），null 表示从未打卡 */
    val lastDoneEpochDay: Long? = null,
)

/** 一次打卡记录 */
@Serializable
data class CheckRecord(
    val id: Long,
    val taskId: Long,
    val epochDay: Long,
    val createdAtEpochMs: Long,
)

/** 本地持久化的完整数据（单 JSON 文件） */
@Serializable
data class AppData(
    val tasks: List<Task> = emptyList(),
    val records: List<CheckRecord> = emptyList(),
    val nextTaskId: Long = 1,
    val nextRecordId: Long = 1,
)

fun weekStart(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun sameWeek(a: LocalDate, b: LocalDate): Boolean = weekStart(a) == weekStart(b)

fun sameMonth(a: LocalDate, b: LocalDate): Boolean = a.year == b.year && a.month == b.month

/** 当前周期（今天/本周/本月/间隔窗口）内是否已完成 */
fun Task.isDoneForPeriod(today: LocalDate): Boolean {
    val last = lastDoneEpochDay ?: return false
    val lastDate = LocalDate.ofEpochDay(last)
    return when (cycleType) {
        CycleType.DAILY -> lastDate == today
        CycleType.WEEKLY -> sameWeek(lastDate, today)
        CycleType.MONTHLY -> sameMonth(lastDate, today)
        CycleType.EVERY_N_DAYS -> today.isBefore(lastDate.plusDays(intervalDays.toLong()))
    }
}

/** 下一次需要打卡的日期 */
fun Task.nextDueDate(today: LocalDate): LocalDate {
    val done = isDoneForPeriod(today)
    return when (cycleType) {
        CycleType.DAILY -> if (done) today.plusDays(1) else today
        CycleType.WEEKLY -> if (done) weekStart(today).plusWeeks(1) else today
        CycleType.MONTHLY -> if (done) today.plusMonths(1).withDayOfMonth(1) else today
        CycleType.EVERY_N_DAYS -> {
            val last = lastDoneEpochDay?.let { LocalDate.ofEpochDay(it) }
            if (done && last != null) last.plusDays(intervalDays.toLong()) else today
        }
    }
}

fun Task.cycleLabel(): String = when (cycleType) {
    CycleType.DAILY -> "每天"
    CycleType.WEEKLY -> "每周"
    CycleType.MONTHLY -> "每月"
    CycleType.EVERY_N_DAYS -> "每 $intervalDays 天"
}

fun formatDate(d: LocalDate): String = "${d.year}年${d.monthValue}月${d.dayOfMonth}日"

fun formatDateShort(d: LocalDate): String = "${d.monthValue}月${d.dayOfMonth}日"

fun weekdayLabel(d: LocalDate): String = "周" + "一二三四五六日"[d.dayOfWeek.value - 1]
