package com.mengpolar.dailycheck.ui

import androidx.compose.ui.graphics.Color
import com.mengpolar.dailycheck.data.OrbitTask
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ── 星图配色（行星态描边与逾期红，其余界面色见 DeviceUi） ──
internal val LateColor = Color(0xFFCF6A5B)

/** 长按打卡所需时长（秒）：按住每秒 +1，松手每秒 -1 */
internal const val CHECK_HOLD_S = 3f

/** 聚焦页按钮默认底色（灰黑，与设备屏幕同族） */
internal val BtnBg = Color(0xB3101214)

/** 几何：以 dp 为单位（Canvas 内按屏幕密度整体缩放） */
internal class Geo(
    val w: Float, val h: Float, val cx: Float, val cy: Float,
    val q: Float, val radiiPx: List<Float>, val s: Float,
    /** 相机缩放（1 = 全景），聚焦时 = 目标倍率 × 聚焦进度 */
    val zoom: Float = 1f,
    /** 行星尺寸系数：轨道盘被压缩时行星随之略微缩小 */
    val planetScale: Float = 1f,
) {
}

/** 轨道半径序列（px）：单轨道放大；多轨道 60dp 起步、环距下限 56dp（≥行星直径防堆叠），
 *  可见数量少时按可用半径带摊开（筛选补位放大），超出时整盘等比缩 */
internal fun orbitRadii(wPx: Float, hPx: Float, d: Float, orbitCount: Int): List<Float> {
    if (orbitCount <= 0 || wPx <= 0f || hPx <= 0f) return emptyList()
    val s = d
    val capX = wPx / 2f - 26f * s
    val capY = hPx / 2f * 0.92f - 26f * s
    val cap = minOf(capX, capY)
    return when {
        orbitCount == 1 -> listOf(cap * 0.60f)
        else -> {
            val capDp = cap / s
            val innerDp = 60f
            val minStepDp = 72f
            val stepDp = maxOf(minStepDp, (capDp - innerDp) * 0.42f / (orbitCount - 1))
            val outerDp = innerDp + stepDp * (orbitCount - 1)
            val fit = minOf(1f, capDp * 0.92f / outerDp)
            (0 until orbitCount).map { i -> (innerDp + stepDp * i) * fit * s }
        }
    }
}

/** 行星尺寸系数：可见数量越少行星越大（配合筛选补位放大）；盘面被等比缩时同步缩小 */
internal fun planetScaleFor(orbitCount: Int, wPx: Float, hPx: Float, d: Float): Float {
    val radii = orbitRadii(wPx, hPx, d, orbitCount)
    if (radii.isEmpty()) return 1f
    val countFactor = if (orbitCount <= 1) 1.35f
        else (1.30f - 0.10f * (orbitCount - 1)).coerceIn(0.90f, 1.35f)
    val idealOuterDp = if (orbitCount <= 1) radii[0] / d else 60f + 56f * (orbitCount - 1)
    val fit = if (idealOuterDp <= 0f) 1f else (radii.last() / d / idealOuterDp).coerceIn(0.62f, 1f)
    return (countFactor * fit).coerceIn(0.62f, 1.35f)
}

/** 半径插值：长度不同时以末值补位（新增轨道自上一外圈滑出，移除时滑回补位） */
internal fun lerpRadii(from: List<Float>, to: List<Float>, f: Float): List<Float> {
    val n = maxOf(from.size, to.size)
    if (n == 0) return emptyList()
    return (0 until n).map { i ->
        val a = from.getOrElse(i) { from.lastOrNull() ?: to.lastOrNull() ?: 0f }
        val b = to.getOrElse(i) { to.lastOrNull() ?: from.lastOrNull() ?: 0f }
        a + (b - a) * f
    }
}

internal fun computeGeo(wPx: Float, hPx: Float, d: Float, orbitCount: Int, tiltQ: Float): Geo {
    val s = d
    val radii = orbitRadii(wPx, hPx, d, orbitCount)
    return Geo(wPx, hPx, wPx / 2f, hPx / 2f, tiltQ.coerceIn(0.16f, 1f), radii, s, planetScale = planetScaleFor(orbitCount, wPx, hPx, d))
}

/** 相机变换：K 为镜头中心（显示在屏幕中央的世界点），camScale 为倍率；camScale=1 且 K=几何中心时为恒等 */
internal fun transformGeo(g: Geo, camScale: Float, kx: Float, ky: Float): Geo = Geo(
    w = g.w, h = g.h,
    cx = g.cx + (g.cx - kx) * camScale,
    cy = g.cy + (g.cy - ky) * camScale,
    q = g.q,
    radiiPx = g.radiiPx.map { it * camScale },
    s = g.s,
    zoom = camScale,
    planetScale = g.planetScale,
)

internal fun planetAngle(frac: Float) = frac * 2f * Math.PI.toFloat()

internal class PlanetPos(val x: Float, val y: Float, val nearness: Float, val r: Float)

internal fun planetPos(g: Geo, t: OrbitTask, radiusOverride: Float? = null): PlanetPos {
    // 星海行星：固定安息位，不随轨道转动
    if (t.seaStar) return seaPos(g, t.id, t.seaIndex)
    // 暗星不在轨道上：直接返回其停靠位
    if (t.darkStar) return darkStarPos(g, t.id, t.darkIndex)
    val a = planetAngle(t.frac)
    // 布局过渡帧可能存在半径表短暂滞后，越界时回退到最外圈，避免绘制崩溃；
    // 排序过渡时传入插值半径（旧环半径 → 新环半径）
    val R = radiusOverride
        ?: g.radiiPx.getOrElse(t.ring) { g.radiiPx.lastOrNull() ?: 0f }
    val x = g.cx + sin(a) * R
    val y = g.cy - cos(a) * R * g.q
    val nearness = (1f - cos(a)) / 2f
    val pr = 18f * g.planetScale * g.s * g.zoom * (0.85f + 0.35f * nearness)
    return PlanetPos(x, y, nearness, pr)
}

/**
 * 暗星停靠位：无轨道，散布在轨道盘外的空白带。
 * 锚点全部取盘外（四角 / 顶缘 / 两侧偏上），按 id 做确定性伪随机散布——
 * 每次刷新位置稳定不漂移；底部预留月球导航区不可停靠。
 */
internal fun darkStarPos(g: Geo, id: Long, index: Int): PlanetPos {
    val s = g.s
    val anchors = listOf(
        0.10f to 0.10f, 0.90f to 0.10f,   // 上部两角
        0.50f to 0.085f,                   // 顶缘中央
        0.075f to 0.30f, 0.925f to 0.30f,  // 两侧偏上
        0.075f to 0.70f, 0.925f to 0.70f,  // 两侧偏下
    )
    val rnd = Random(id * 31 + 7)
    val (fx, fy) = anchors[Math.abs((id + index * 3L).toInt()) % anchors.size]
    val jx = (rnd.nextFloat() - 0.5f) * 0.06f
    val jy = (rnd.nextFloat() - 0.5f) * 0.05f
    // 同槽多颗：沿固定步长错开，避免完全重叠
    val dx = index * 16f * s
    val dy = (index % 3) * 13f * s
    val x = ((fx + jx) * g.w + dx).coerceIn(30f * s, g.w - 30f * s)
    val y = ((fy + jy) * g.h + dy).coerceIn(44f * s, g.h - 160f * s)
    val r = 11f * g.planetScale * s * g.zoom * (0.92f + 0.08f * sin(id.toFloat() + index))
    return PlanetPos(x, y, 0.5f, r)
}

/**
 * 星海安息位：已完成一次性任务的行星长眠于此——固定位置、不随轨道转动。
 * 全屏 4×3 网格槽位 + 按 id 确定性扰动散布，每次进入位置稳定。
 */
internal fun seaPos(g: Geo, id: Long, index: Int): PlanetPos {
    val s = g.s
    val rnd = Random(id * 17 + 3)
    val cols = 4; val rows = 3
    val slot = Math.abs((id + index * 5L).toInt()) % (cols * rows)
    val fx = (slot % cols + 0.5f) / cols
    val fy = (slot / cols + 0.5f) / rows
    val jx = (rnd.nextFloat() - 0.5f) * 0.10f
    val jy = (rnd.nextFloat() - 0.5f) * 0.08f
    val dx = index * 13f * s
    val dy = (index % 2) * 11f * s
    val x = ((fx + jx) * g.w + dx).coerceIn(34f * s, g.w - 34f * s)
    val y = ((fy + jy) * g.h + dy).coerceIn(60f * s, g.h - 170f * s)
    val nearness = 0.5f
    val r = 18f * g.planetScale * s * g.zoom * (0.85f + 0.35f * nearness)
    return PlanetPos(x, y, nearness, r)
}

internal fun hsla(hue: Int, sat: Float, light: Float, alpha: Float) =
    Color.hsl(hue.toFloat(), sat, light, alpha)

