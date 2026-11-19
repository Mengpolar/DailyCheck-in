package com.mengpolar.dailycheck.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val ZONE: ZoneId = ZoneId.systemDefault()

/**
 * 一个循环周期性任务：每 intervalDays 天为一个周期，窗口以「起始时刻 anchorMin」
 * 为始末（如每天 20:00 → 次日 20:00）。填 1 即每天，填 7 即每周。
 * 周期网格锚定在创建日，打卡不移动窗口。
 */
@Serializable
data class Task(
    val id: Long,
    val name: String,
    val note: String = "",
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

/** 当前周期窗口：行星从 startMs 出发，durMs 走完一圈到达 endMs */
data class PeriodWindow(val startMs: Long, val endMs: Long, val durMs: Long)

fun Task.creationAnchorMs(): Long =
    // createdEpochDay 是本地时区的日期，0 点必须按本地时区换算 epoch
    // （直接乘 86400000 得到的是 UTC 0 点，UTC+8 下锚点整体偏移 +8 小时）
    LocalDate.ofEpochDay(createdEpochDay).atStartOfDay(ZONE).toInstant().toEpochMilli() +
        anchorMin.coerceIn(0, 1439) * 60_000L

fun Task.periodWindow(nowMs: Long): PeriodWindow {
    val dur = intervalDays.coerceAtLeast(1) * 86_400_000L
    val start0 = creationAnchorMs()
    val k = Math.floorDiv(nowMs - start0, dur)
    val start = start0 + k * dur
    return PeriodWindow(start, start + dur, dur)
}

fun Task.periodFraction(nowMs: Long): Float {
    val w = periodWindow(nowMs)
    return ((nowMs - w.startMs).toFloat() / w.durMs).coerceIn(0f, 1f)
}

/** 当前周期内是否已完成：最近一次打卡时刻落在当前窗口内即算完成 */
fun Task.isDoneForPeriod(nowMs: Long, lastCheckMs: Long?): Boolean =
    lastCheckMs != null && lastCheckMs >= periodWindow(nowMs).startMs

/** 行星视觉状态：点亮 / 临近到期（若隐若现）/ 待打卡（黑白）/ 逾期（暗红） */
enum class PlanetState { DONE, NEAR, PENDING, LATE }

fun Task.planetState(nowMs: Long, lastCheckMs: Long?): PlanetState {
    if (isDoneForPeriod(nowMs, lastCheckMs)) return PlanetState.DONE
    val w = periodWindow(nowMs)
    val dur = w.durMs
    val base = lastCheckMs ?: creationAnchorMs()
    // 距上次打卡已越过一整个完整周期仍未打卡 → 逾期
    if (nowMs - base > dur + 2 * 3_600_000L) return PlanetState.LATE
    // 距窗口结束不足 6 小时 → 临近到期
    if (w.endMs - nowMs in 1..6 * 3_600_000L) return PlanetState.NEAR
    return PlanetState.PENDING
}

fun Task.anchorLabel(): String = "%02d:%02d".format(anchorMin.coerceIn(0, 1439) / 60, anchorMin % 60)

fun Task.cycleLabel(): String = when (intervalDays.coerceAtLeast(1)) {
    1 -> "每天"
    7 -> "每周"
    else -> "每 ${intervalDays.coerceAtLeast(1)} 天"
}

/** 轨道分层：每天最内圈，每周中圈，更长周期外圈 */
fun Task.orbitRing(): Int = when {
    intervalDays <= 1 -> 0
    intervalDays <= 7 -> 1
    else -> 2
}

fun formatWindowStart(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZONE)
    return "${t.monthValue}/${t.dayOfMonth} %02d:%02d".format(t.hour, t.minute)
}

fun formatDate(d: LocalDate): String = "${d.year}年${d.monthValue}月${d.dayOfMonth}日"

fun formatDateShort(d: LocalDate): String = "${d.monthValue}月${d.dayOfMonth}日"

fun weekdayLabel(d: LocalDate): String = "周" + "一二三四五六日"[d.dayOfWeek.value - 1]
