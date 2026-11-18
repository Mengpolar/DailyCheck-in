package com.mengpolar.dailycheck.data

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 循环周期类型 */
@Serializable
enum class CycleType { DAILY, WEEKLY, MONTHLY, EVERY_N_DAYS }

/**
 * 一个循环周期性任务。
 * anchorMin：每日任务的起始时刻（当天 0 点起的分钟数），行星从该时刻出发，
 * 周期 = 该时刻到次日同一时刻；其余周期使用固定锚点（每周一 0 点 / 每月 1 日 0 点 /
 * 每 N 天 = 上次打卡时刻）。
 */
@Serializable
data class Task(
    val id: Long,
    val name: String,
    val note: String = "",
    val cycleType: CycleType,
    val intervalDays: Int = 1,
    val anchorMin: Int = 0,
    val createdEpochDay: Long,
    val lastDoneEpochDay: Long? = null,
    val imagePath: String? = null,
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

// ───────────────────────── 周期时钟（行星位置与状态的唯一事实来源） ─────────────────────────

/** 当前周期窗口：行星从 startMs 出发，沿轨道用 durMs 走完一圈到达 endMs */
data class PeriodWindow(val startMs: Long, val endMs: Long, val durMs: Long)

private val ZONE: ZoneId = ZoneId.systemDefault()

fun Task.periodWindow(nowMs: Long, lastCheckMs: Long?): PeriodWindow {
    val now = Instant.ofEpochMilli(nowMs).atZone(ZONE)
    return when (cycleType) {
        CycleType.DAILY -> {
            var start = now.toLocalDate().atStartOfDay(ZONE).toInstant().toEpochMilli() +
                anchorMin.coerceIn(0, 1439) * 60_000L
            if (nowMs < start) start -= 86_400_000L
            PeriodWindow(start, start + 86_400_000L, 86_400_000L)
        }
        CycleType.WEEKLY -> {
            val start = weekStart(now.toLocalDate()).atStartOfDay(ZONE).toInstant().toEpochMilli()
            PeriodWindow(start, start + 7 * 86_400_000L, 7 * 86_400_000L)
        }
        CycleType.MONTHLY -> {
            val start = now.toLocalDate().withDayOfMonth(1).atStartOfDay(ZONE).toInstant().toEpochMilli()
            val len = now.toLocalDate().lengthOfMonth() * 86_400_000L
            PeriodWindow(start, start + len, len)
        }
        CycleType.EVERY_N_DAYS -> {
            // 锚点 = 上次打卡时刻（从未打卡则用创建日 0 点）
            val start = lastCheckMs ?: (createdEpochDay * 86_400_000L)
            val dur = intervalDays.coerceAtLeast(1) * 86_400_000L
            PeriodWindow(start, start + dur, dur)
        }
    }
}

fun Task.periodFraction(nowMs: Long, lastCheckMs: Long?): Pair<Float, Boolean> {
    val w = periodWindow(nowMs, lastCheckMs)
    val elapsed = nowMs - w.startMs
    return if (cycleType == CycleType.EVERY_N_DAYS) {
        // 允许跑多圈（逾期继续绕行）
        val f = ((elapsed % w.durMs) + w.durMs) % w.durMs
        Pair((f / w.durMs).toFloat(), elapsed > w.durMs)
    } else {
        Pair((elapsed.toFloat() / w.durMs).coerceIn(0f, 1f), false)
    }
}

/** 当前周期内是否已完成：以最近一次打卡时刻是否落在当前周期窗口内为准 */
fun Task.isDoneForPeriod(nowMs: Long, lastCheckMs: Long?): Boolean {
    if (cycleType == CycleType.EVERY_N_DAYS) {
        val last = lastDoneEpochDay ?: return false
        return !Instant.ofEpochMilli(nowMs).atZone(ZONE).toLocalDate()
            .isBefore(LocalDate.ofEpochDay(last).plusDays(intervalDays.toLong()))
    }
    val w = periodWindow(nowMs, lastCheckMs)
    return lastCheckMs != null && lastCheckMs >= w.startMs
}

/** 行星视觉状态：点亮 / 临近到期（若隐若现）/ 待打卡（黑白）/ 逾期（暗红） */
enum class PlanetState { DONE, NEAR, PENDING, LATE }

fun Task.planetState(nowMs: Long, lastCheckMs: Long?): PlanetState {
    if (isDoneForPeriod(nowMs, lastCheckMs)) return PlanetState.DONE
    val w = periodWindow(nowMs, lastCheckMs)
    val base = lastCheckMs ?: (createdEpochDay * 86_400_000L)
    val missed = nowMs - base
    val dur = w.durMs
    val grace = when (cycleType) {
        CycleType.DAILY -> 2 * 3_600_000L
        CycleType.WEEKLY -> 6 * 3_600_000L
        CycleType.MONTHLY -> 12 * 3_600_000L
        CycleType.EVERY_N_DAYS -> 2 * 3_600_000L
    }
    if (missed > dur + grace) return PlanetState.LATE
    val remaining = base + dur - nowMs
    val nearThreshold = when (cycleType) {
        CycleType.DAILY -> 4 * 3_600_000L
        CycleType.WEEKLY -> 24 * 3_600_000L
        CycleType.MONTHLY -> 72 * 3_600_000L
        CycleType.EVERY_N_DAYS -> 6 * 3_600_000L
    }
    if (remaining in 1..nearThreshold) return PlanetState.NEAR
    return PlanetState.PENDING
}

fun weekStart(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun sameWeek(a: LocalDate, b: LocalDate): Boolean = weekStart(a) == weekStart(b)

fun sameMonth(a: LocalDate, b: LocalDate): Boolean = a.year == b.year && a.month == b.month

fun Task.nextDueDate(today: LocalDate, done: Boolean): LocalDate {
    return when (cycleType) {
        CycleType.DAILY -> if (done) today.plusDays(1) else today
        CycleType.WEEKLY -> if (done) weekStart(today).plusWeeks(1) else today
        CycleType.MONTHLY -> if (done) today.plusMonths(1).withDayOfMonth(1) else today
        CycleType.EVERY_N_DAYS -> {
            val last = lastDoneEpochDay?.let { LocalDate.ofEpochDay(it) }
            if (last != null && today.isBefore(last.plusDays(intervalDays.toLong())))
                last.plusDays(intervalDays.toLong())
            else today
        }
    }
}

fun Task.cycleLabel(): String = when (cycleType) {
    CycleType.DAILY -> "每天"
    CycleType.WEEKLY -> "每周"
    CycleType.MONTHLY -> "每月"
    CycleType.EVERY_N_DAYS -> "每 $intervalDays 天"
}

/** 轨道起始线的注记文本 */
fun Task.anchorLabel(): String = when (cycleType) {
    CycleType.DAILY -> "%02d:%02d".format(anchorMin / 60, anchorMin % 60)
    CycleType.WEEKLY -> "周一"
    CycleType.MONTHLY -> "1日"
    CycleType.EVERY_N_DAYS -> "每 $intervalDays 天"
}

fun formatDate(d: LocalDate): String = "${d.year}年${d.monthValue}月${d.dayOfMonth}日"

fun formatDateShort(d: LocalDate): String = "${d.monthValue}月${d.dayOfMonth}日"

fun weekdayLabel(d: LocalDate): String = "周" + "一二三四五六日"[d.dayOfWeek.value - 1]
