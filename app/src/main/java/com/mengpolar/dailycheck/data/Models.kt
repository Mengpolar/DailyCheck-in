package com.mengpolar.dailycheck.data

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val ZONE: ZoneId = ZoneId.systemDefault()

/**
 * 一个循环周期性任务：每 intervalDays 天为一个周期，窗口以「起始时刻 anchorMin」
 * 为始末（如每天 20:00 → 次日 20:00）。填 1 即每天。
 * createdEpochDay 为当前周期的起始日；「当前第 X 天」创建时换算为 createdEpochDay = 今天 - X。
 * once = true 时为一次性任务：intervalDays 视为 0，窗口为创建日起 onceDays 天，过期未打卡即消散。
 */
@Serializable
data class Task(
    val id: Long,
    val name: String,
    val note: String = "",
    val intervalDays: Int = 1,
    val anchorMin: Int = 0,
    /** 每周任务的起始星期（1=周一..7=周日）——已废弃，保留字段兼容旧数据 */
    val anchorWeekday: Int = 1,
    val createdEpochDay: Long,
    val lastDoneEpochDay: Long? = null,
    val imagePath: String? = null,
    /** 一次性任务（intervalDays 视为 0）的有效天数：截止 = createdEpochDay + onceDays 天 */
    val onceDays: Int = 0,
)

/** 一次扫描记录（retro = 回溯补录：不写具体时刻，仅锚定所补周期的起点） */
@Serializable
data class CheckRecord(
    val id: Long,
    val taskId: Long,
    val epochDay: Long,
    val createdAtEpochMs: Long,
    val retro: Boolean = false,
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
    // 一次性任务：窗口 = 创建日 anchorMin 起，onceDays 天后截止
    if (intervalDays <= 0) {
        val start = creationAnchorMs()
        val dur = (onceDays.coerceAtLeast(0) * 86_400_000L).coerceAtLeast(3_600_000L)
        return PeriodWindow(start, start + dur, dur)
    }
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

/** 上一周期窗口（倒数第二个完整周期）；一次性任务或仍处首个周期时为 null（无追溯意义） */
fun Task.previousPeriodWindow(nowMs: Long): PeriodWindow? {
    if (intervalDays <= 0) return null
    val dur = intervalDays.coerceAtLeast(1) * 86_400_000L
    val start0 = creationAnchorMs()
    val k = Math.floorDiv(nowMs - start0, dur)
    if (k <= 0) return null
    val start = start0 + (k - 1) * dur
    return PeriodWindow(start, start + dur, dur)
}

/** 窗口内是否存在扫描记录（按记录时刻落在 [start, end) 判定） */
fun Iterable<CheckRecord>.anyInWindow(w: PeriodWindow): Boolean =
    any { it.createdAtEpochMs >= w.startMs && it.createdAtEpochMs < w.endMs }

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

fun Task.cycleLabel(): String = when {
    intervalDays <= 0 -> "一次性"
    intervalDays.coerceAtLeast(1) == 1 -> "每天"
    else -> "每 ${intervalDays.coerceAtLeast(1)} 天"
}

// ───────────────────────── 行星成长系统 ─────────────────────────
// 以累计扫描次数分级，每个阶段在视觉上多一层特效（层层叠加）；
// 后续开放更多阶段只需在两个表尾部追加。

/** 各阶段累计扫描门槛（第 0 阶段从 0 次起） */
val GROWTH_THRESHOLDS = intArrayOf(0, 7, 15, 30, 50, 75, 100)

/** 阶段名：星胚→微星→岩星→虹星→环星→炽星→耀星 */
val GROWTH_NAMES = listOf("星胚", "微星", "岩星", "虹星", "环星", "炽星", "耀星")

/** 累计扫描次数 → 成长阶段序号（0..GROWTH_NAMES.lastIndex） */
fun growthStageOf(scans: Int): Int {
    var stage = 0
    for (i in GROWTH_THRESHOLDS.indices) if (scans >= GROWTH_THRESHOLDS[i]) stage = i
    return stage
}

/** 距下一阶段还差的扫描次数；已满阶返回 null */
fun nextGrowthGap(scans: Int): Int? =
    GROWTH_THRESHOLDS.firstOrNull { it > scans }?.let { it - scans }

fun formatWindowStart(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZONE)
    return "${t.monthValue}/${t.dayOfMonth} %02d:%02d".format(t.hour, t.minute)
}

fun formatDate(d: LocalDate): String = "${d.year}年${d.monthValue}月${d.dayOfMonth}日"

fun weekdayLabel(d: LocalDate): String = "周" + "一二三四五六日"[d.dayOfWeek.value - 1]
