package com.mengpolar.dailycheck.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.mengpolar.dailycheck.data.OrbitTask
import com.mengpolar.dailycheck.data.PlanetState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// ── 共享画笔（避免每帧分配） ──
private val namePaint = Paint().apply {
    typeface = Typeface.SERIF
    textAlign = Paint.Align.CENTER; isAntiAlias = true
}
private val dimMatrix = ColorMatrix(floatArrayOf(
    0.88f, 0f, 0f, 0f, 0f,
    0f, 0.88f, 0f, 0f, 0f,
    0f, 0f, 0.88f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
))
private val planetPaintNormal = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
private val planetPaintGray = Paint().apply {
    isAntiAlias = true; isFilterBitmap = true
    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f); postConcat(dimMatrix) })
}

// 碎裂贴图画笔：暗色本体 + 浅色错位衬底（任意行星表面可见）
private val crackPaintDark = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
private val crackPaintLight = Paint().apply {
    isAntiAlias = true; isFilterBitmap = true
    colorFilter = PorterDuffColorFilter(0xFFF2E7D8.toInt(), PorterDuff.Mode.SRC_IN)
}


/** polie.png 已是透明底裂纹贴图：仅保证 ARGB_8888 软件位图（可 getPixels/安全绘制） */
internal fun processCrackTex(src: Bitmap): Bitmap =
    src.copy(Bitmap.Config.ARGB_8888, false)

/** 月图处理：moon2 为居中正方形透图——圆内（半径 = 边长/2）强制不透明，圆外透明 */
internal fun processMoon(src: Bitmap): Bitmap {
    val w = src.width; val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val cx = w / 2f; val cy = h / 2f
    val r = w / 2f
    // 圆内：alpha 提升到 ~78%（月面可见但保留一定透明度，星空微透）；圆外：透明
    val moonAlpha = 204
    for (i in px.indices) {
        val x = (i % w).toFloat(); val y = (i / w).toFloat()
        val inside = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
        if (inside) {
            val a = maxOf(px[i] ushr 24, moonAlpha)
            px[i] = (a shl 24) or (px[i] and 0x00FFFFFF)
        } else {
            px[i] = px[i] and 0x00FFFFFF
        }
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(px, 0, w, 0, 0, w, h)
    return out
}

/** 剩余时长文案：X 天 X 小时 / X 小时 X 分 / X 分钟 */
internal fun formatRemaining(ms: Long): String {
    val totalMin = ms / 60_000L
    val d = totalMin / 1440
    val h = (totalMin % 1440) / 60
    val m = totalMin % 60
    return when {
        d >= 1 -> "$d 天 $h 小时"
        h >= 1 -> "$h 小时 $m 分"
        else -> "$m 分钟"
    }
}

internal data class Spark(
    val x: Float, val y: Float,
    val hue: Int, val seed: Float,
    val born: Long, // uptime ms，与帧时钟同基准
)

/** 拖尾星光粒子：从行星位置沿轨道向拖尾末端漂移并渐隐 */
internal data class TailStar(
    val taskId: Long,
    val born: Long,
    val life: Long,
    val hue: Int,
    val seed: Float,
    val dir0: Float, // 出生时沿拖尾的起始位置（0 = 行星处，1 = 拖尾末端）
)

/** 消散中的行星残影：任务移除 / 过期时短暂呈现，放大 + 渐隐 */
internal data class DissolvingPlanet(
    val frac: Float,
    val hue: Int,
    val ring: Int,
    val born: Long,
)

internal fun DrawScope.drawSparks(g: Geo, sparks: List<Spark>, timeMs: Long, starBmp: ImageBitmap) {
    sparks.forEach { s ->
        val age = (timeMs - s.born).coerceAtLeast(0)
        if (age > 5000) return@forEach
        val life = age / 5000f
        val popIn = (age / 160f).coerceAtMost(1f)          // 出生 160ms 弹入
        val alpha = ((1f - life) * (0.35f + 0.65f * popIn)).coerceIn(0f, 1f)
        if (alpha <= 0.01f) return@forEach
        val tw = 0.7f + 0.3f * sin(age / 110f + s.seed * 10f)
        val base = (9f + 6f * s.seed) * g.s * (0.5f + 0.5f * popIn) * (1f - 0.35f * life)
        val c = hsla(s.hue, 0.75f, 0.8f, 1f)
        val center = Offset(s.x, s.y)
        // 光晕
        drawCircle(
            Brush.radialGradient(
                listOf(c.copy(alpha = 0.5f * alpha * tw), Color.Black.copy(alpha = 0f)),
                center = center, radius = base * 2.6f,
            ),
            base * 2.6f, center,
        )
        // 四芒星贴图：随机 0/45° 起始角 + 缓慢自旋
        val sizePx = (base * 2.6f).toInt().coerceAtLeast(1)
        val rot = s.seed * 45f + age / 300f
        rotate(degrees = rot, pivot = center) {
            drawImage(
                starBmp,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(starBmp.width, starBmp.height),
                dstOffset = IntOffset((s.x - sizePx / 2f).toInt(), (s.y - sizePx / 2f).toInt()),
                dstSize = IntSize(sizePx, sizePx),
                alpha = alpha.coerceIn(0f, 1f),
                filterQuality = FilterQuality.Medium,
            )
        }
        // 白核
        drawCircle(Color.White.copy(alpha = alpha), (0.14f * base + 0.5f * g.s), center)
    }
}

/** 拖尾星光：从行星位置沿轨道向拖尾末端漂移，逐渐缩小渐隐 */
internal fun DrawScope.drawTailStars(
    g: Geo,
    items: List<OrbitTask>,
    stars: List<TailStar>,
    timeMs: Long,
    starBmp: ImageBitmap,
) {
    stars.forEach { s ->
        val t = items.firstOrNull { it.id == s.taskId } ?: return@forEach
        if (t.tail <= 0.02f) return@forEach
        val R = g.radiiPx.getOrNull(t.ring) ?: return@forEach
        val age = (timeMs - s.born).coerceAtLeast(0)
        val lifeT = (age.toFloat() / s.life).coerceIn(0f, 1f)
        if (lifeT >= 1f) return@forEach
        val span = t.tail * 360f
        val dir = s.dir0 + (1f - s.dir0) * lifeT
        val aDeg = Math.toDegrees(planetAngle(t.frac).toDouble()).toFloat() - span * dir
        val rad = Math.toRadians(aDeg.toDouble())
        val wobble = sin(age / 90f + s.seed * 9f) * 3f * g.s
        val x = g.cx + sin(rad).toFloat() * (R + wobble)
        val y = g.cy - cos(rad).toFloat() * (R + wobble) * g.q
        val alpha = ((1f - lifeT) * (1f - lifeT) * 0.9f).coerceIn(0f, 1f)
        val sizePx = ((2.6f + 3.4f * s.seed) * g.s * (1f - 0.35f * lifeT)).coerceAtLeast(1f)
        val c = hsla(s.hue, 0.75f, 0.82f, 1f)
        // 光晕
        drawCircle(
            Brush.radialGradient(
                listOf(c.copy(alpha = 0.45f * alpha), Color.Black.copy(alpha = 0f)),
                center = Offset(x, y), radius = sizePx * 2.4f,
            ),
            sizePx * 2.4f, Offset(x, y),
        )
        // 四芒星本体
        val bmp = (sizePx * 2f).toInt().coerceAtLeast(2)
        rotate(degrees = s.seed * 45f + age / 260f, pivot = Offset(x, y)) {
            drawImage(
                starBmp,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(starBmp.width, starBmp.height),
                dstOffset = IntOffset((x - bmp / 2f).toInt(), (y - bmp / 2f).toInt()),
                dstSize = IntSize(bmp, bmp),
                alpha = alpha,
                filterQuality = FilterQuality.Medium,
            )
        }
        // 白核
        drawCircle(Color.White.copy(alpha = alpha), 0.9f * g.s, Offset(x, y))
    }
}

/** 方形位图 → 圆形（透明背景），供行星绘制 */
internal fun circleCrop(src: Bitmap): Bitmap {
    val size = minOf(src.width, src.height)
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val paint = Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    c.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    paint.shader = android.graphics.BitmapShader(
        src,
        android.graphics.Shader.TileMode.CLAMP,
        android.graphics.Shader.TileMode.CLAMP,
    )
    c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    return out
}

internal data class StarSpec(
    val xf: Float, val yf: Float, val r: Float,
    val warm: Boolean, val cool: Boolean,
    val phase: Float, val speed: Float, val flare: Boolean,
)

internal fun DrawScope.drawStars(
    g: Geo, stars: List<StarSpec>, timeMs: Long, par: Offset, dimF: Float, starBmp: ImageBitmap?,
) {
    stars.forEach { s ->
        val tw = 0.4f + 0.6f * abs(sin(timeMs / 1400f * s.speed + s.phase))
        val color = when {
            s.warm -> Color(0xFFFFE9C4)
            s.cool -> Color(0xFFCFE0FF)
            else -> Color(0xFFE2E6F0)
        }
        val x = s.xf * g.w + par.x; val y = s.yf * g.h + par.y
        drawCircle(color.copy(alpha = tw * 0.85f * dimF), s.r * g.s, Offset(x, y))
        if (s.flare && starBmp != null) {
            // 四芒星贴图：缓慢闪烁 + 固定旋转
            val sizePx = (s.r * 9f * g.s * (0.7f + 0.5f * tw)).toInt().coerceAtLeast(1)
            val rot = s.phase * 57.3f
            rotate(degrees = rot, pivot = Offset(x, y)) {
                drawImage(
                    starBmp,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(starBmp.width, starBmp.height),
                    dstOffset = IntOffset((x - sizePx / 2f).toInt(), (y - sizePx / 2f).toInt()),
                    dstSize = IntSize(sizePx, sizePx),
                    alpha = (tw * 0.85f * dimF).coerceIn(0f, 1f),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}

internal fun DrawScope.drawOrbitLayer(g: Geo) {
    g.radiiPx.forEach { R ->
        drawArc(
            Color(0xFFBEC8E1).copy(alpha = 0.08f),
            0f, 360f, false,
            topLeft = Offset(g.cx - R, g.cy - R * g.q),
            size = Size(R * 2f, R * g.q * 2f),
            style = Stroke(1.2f * g.s),
        )
    }
}

internal fun DrawScope.drawTextNc(text: String, x: Float, y: Float, paint: Paint, argb: Int) {
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint.apply { setColor(argb) })
}

internal fun DrawScope.drawTask(
    g: Geo,
    t: OrbitTask,
    timeMs: Long,
    z: Float,
    selected: OrbitTask?,
    images: Map<Long, ImageBitmap>,
    tilt: Tilt = Tilt(),
    showName: Boolean = true,
    dimmed: Boolean,
    fill: Float,
    delFill: Float,
    spawn: Float,
    crackTex: ImageBitmap?,
) {
    val p = planetPos(g, t)
    val aDeg = Math.toDegrees(planetAngle(t.frac).toDouble()).toFloat()
    // 新行星登场：0.55→1.0 放大浮现（先快后慢）
    val se = spawn * spawn * (3f - 2f * spawn)
    val r = p.r * (0.55f + 0.45f * se)
    val dim = if (dimmed) 1f - z else 1f // 聚焦时其他行星随缩放完全淡出（同月球）
    val depthA = 0.72f + 0.28f * p.nearness
    val sel = selected?.id == t.id
    val done = t.state == PlanetState.DONE
    val late = t.state == PlanetState.LATE
    val R = g.radiiPx.getOrElse(t.ring) { g.radiiPx.lastOrNull() ?: 0f }
    val ovalTopLeft = Offset(g.cx - R, g.cy - R * g.q)
    val ovalSize = Size(R * 2f, R * g.q * 2f)

    // 彗尾（打卡后随时间淡出，拖在行星后方）：柔光底层 + 渐细渐隐主弧
    if (done && t.tail > 0f) {
        val span = t.tail * 360f
        drawArc(
            hsla(t.hue, 0.6f, 0.6f, 0.07f * dim),
            -90f + aDeg - span, span, false,
            topLeft = ovalTopLeft, size = ovalSize,
            style = Stroke((r * 0.6f).coerceAtLeast(3f * g.s)),
        )
        val segs = 24
        for (i in 0 until segs) {
            val f0 = i / segs.toFloat(); val f1 = (i + 1) / segs.toFloat()
            val al = (1f - f0) * (1f - f0) * 0.42f * dim
            drawArc(
                hsla(t.hue, 0.65f, 0.68f, al),
                -90f + aDeg - span * f1, span * (f1 - f0), false,
                topLeft = ovalTopLeft, size = ovalSize,
                style = Stroke(maxOf(0.8f * g.s, r * 0.55f * (1f - f0))),
            )
        }
    }

    // 聚焦态轻微左右翻转（提前计算：状态环/完成光晕/行星本体/玻璃轮廓全部随翻转压缩）
    val yaw = (tilt.y * 1.2f).coerceIn(-0.9f, 0.9f)
    val halfW = if (sel) r * cos(yaw) else r
    val flipScale = halfW / r.coerceAtLeast(1f)

    // 状态环：临近到期 = 琥珀脉冲；逾期 = 暗红；待办 = 星图描边圈（保证黑白星可见）
    scale(flipScale, 1f, pivot = Offset(p.x, p.y)) {
        if (t.state == PlanetState.NEAR) {
            val pulse = 0.3f + 0.32f * (0.5f + 0.5f * sin(timeMs / 500f + t.ring * 2f))
            drawCircle(hsla(40, 0.85f, 0.62f, pulse * dim), r + 5f * g.s, Offset(p.x, p.y), style = Stroke(1.3f * g.s))
        }
        if (late) {
            drawCircle(LateColor.copy(alpha = 0.5f * dim), r + 5f * g.s, Offset(p.x, p.y), style = Stroke(1.2f * g.s))
        }
        if (t.state == PlanetState.PENDING) {
            drawCircle(Color(0x59C4CCDE).copy(alpha = 0.4f * dim), r + 2.5f * g.s, Offset(p.x, p.y), style = Stroke(1f * g.s))
        }

        // 已完成光晕（克制）
        if (done) {
            drawCircle(
                Brush.radialGradient(
                    listOf(hsla(t.hue, 0.6f, 0.62f, 0.22f * dim), Color.Black.copy(alpha = 0f)),
                    center = Offset(p.x, p.y), radius = r * 2.1f,
                ),
                r * 2.1f, Offset(p.x, p.y),
            )
        }
    }

    // 行星本体
    val alpha = ((depthA * dim * when (t.state) {
        PlanetState.NEAR -> 0.55f + 0.28f * (0.5f + 0.5f * sin(timeMs / 500f + t.ring * 2f))
        PlanetState.LATE -> 0.62f
        else -> 1f
    } + if (sel) 0.3f * z else 0f).coerceIn(0f, 1f)) * se

    val bmp = images[t.id]
    if (bmp != null) {
        val paint = if (done) planetPaintNormal else planetPaintGray
        paint.alpha = (alpha * 255).toInt()
        drawContext.canvas.nativeCanvas.drawBitmap(
            bmp.asAndroidBitmap(), null,
            RectF(p.x - halfW, p.y - r, p.x + halfW, p.y + r),
            paint,
        )
        // 长按打卡填充：从下往上恢复行星原本的颜色（仅聚焦选中且未完成时）
        if (sel && !done && fill > 0f) {
            planetPaintNormal.alpha = (alpha * 255).toInt()
            clipRect(
                left = p.x - halfW,
                top = p.y + r - 2f * r * fill,
                right = p.x + halfW,
                bottom = p.y + r,
            ) {
                drawContext.canvas.nativeCanvas.drawBitmap(
                    bmp.asAndroidBitmap(), null,
                    RectF(p.x - halfW, p.y - r, p.x + halfW, p.y + r),
                    planetPaintNormal,
                )
            }
        }
    } else {
        scale(flipScale, 1f, pivot = Offset(p.x, p.y)) {
            drawProceduralPlanet(p.x, p.y, r, t.hue, done, alpha)
            if (sel && !done && fill > 0f) {
                clipRect(
                    left = p.x - r,
                    top = p.y + r - 2f * r * fill,
                    right = p.x + r,
                    bottom = p.y + r,
                ) {
                    drawProceduralPlanet(p.x, p.y, r, t.hue, true, alpha)
                }
            }
        }
    }
    // 聚焦态陀螺仪质感：重力投影驱动——暗面移向背光侧、高光斑移向抬起的边缘（与面板反光同源）
    if (sel) {
        val sphere = Path().apply { addOval(Rect(p.x - halfW, p.y - r, p.x + halfW, p.y + r)) }
        clipPath(sphere) {
            // 暗面：随左右翻转移动
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.12f + 0.50f * abs(tilt.y))),
                    center = Offset(p.x - tilt.y * r * 0.9f, p.y - r * 0.1f),
                    radius = r * 1.02f,
                ),
                r, Offset(p.x, p.y),
            )
            // 高光斑：加色模式（任意底色上都发亮），随姿态扫向抬起的边缘
            drawCircle(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.55f),
                    0.5f to Color.White.copy(alpha = 0.20f),
                    1f to Color.Transparent,
                    center = Offset(p.x - tilt.y * r * 0.8f, p.y - r * 0.45f + tilt.x * r * 0.30f),
                    radius = r * 0.6f,
                ),
                r, Offset(p.x, p.y),
                blendMode = BlendMode.Plus,
            )
        }
    }
    // 液态玻璃质感（球面暗角 + 边缘亮环随翻转一起水平压缩，行星整体透明度淡出）
    scale(flipScale, 1f, pivot = Offset(p.x, p.y)) {
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0x00000000), Color(0x59000000).copy(alpha = 0x59 / 255f * alpha)),
                center = Offset(p.x, p.y), radius = r,
            ),
            r, Offset(p.x, p.y),
        )
        drawCircle(Color(0x59FFFFFF).copy(alpha = 0x59 / 255f * alpha), r, Offset(p.x, p.y), style = Stroke(1.1f * g.s))
    }

    // 删除长按裂纹：polie 碎裂贴图固定贴合行星，随进度以行星中心为圆心向外扩散揭示（裂纹蔓延感）
    if (sel && delFill > 0f && r > 10f && crackTex != null) {
        val rnd = Random(t.id)
        val rot = rnd.nextFloat() * 360f
        val reveal = ((delFill - 0.06f) / 0.94f).coerceIn(0f, 1f)
        val alpha = ((0.45f + 0.55f * reveal) * delFill).coerceIn(0f, 1f)
        val side = r * 2f
        val dstL = p.x - side / 2f
        val dstT = p.y - side / 2f
        val nc = drawContext.canvas.nativeCanvas
        val revealR = reveal * r * 1.45f
        val revealCircle = Path().apply {
            addOval(Rect(p.x - revealR, p.y - revealR, p.x + revealR, p.y + revealR))
        }
        clipPath(revealCircle) {
            nc.save()
            nc.rotate(rot, p.x, p.y)
            // 浅色衬底：轻微错位，形成受光裂缝缘
            crackPaintLight.alpha = (alpha * 255f * 0.55f).toInt()
            nc.drawBitmap(
                crackTex.asAndroidBitmap(), null,
                RectF(dstL + 1.6f * g.s, dstT + 1.6f * g.s, dstL + 1.6f * g.s + side, dstT + 1.6f * g.s + side),
                crackPaintLight,
            )
            // 暗色主体
            crackPaintDark.alpha = (alpha * 255f).toInt()
            nc.drawBitmap(
                crackTex.asAndroidBitmap(), null,
                RectF(dstL, dstT, dstL + side, dstT + side),
                crackPaintDark,
            )
            // 高进度：再叠一层旋转贴图，碎裂更密
            if (delFill > 0.6f) {
                val a2 = ((delFill - 0.6f) / 0.4f).coerceIn(0f, 1f)
                crackPaintDark.alpha = (alpha * 0.85f * a2 * 255f).toInt()
                nc.save()
                nc.rotate(137f, p.x, p.y)
                nc.drawBitmap(
                    crackTex.asAndroidBitmap(), null,
                    RectF(dstL, dstT, dstL + side * 0.92f, dstT + side * 0.92f),
                    crackPaintDark,
                )
                nc.restore()
            }
            nc.restore()
        }
    }

    // 名字注记（衬线 + 投影）；可经设置隐藏，聚焦淡出时同步消隐
    if (showName) {
        val nameColor = if (done)
            hsla(t.hue, 0.45f, 0.8f, 0.95f * dim)
        else
            Color(0xFFC4CCDE).copy(alpha = 0.85f * dim)
        val nameScale = minOf(g.zoom, 2.6f)
        namePaint.textSize = 11.5f * g.s * nameScale
        namePaint.setShadowLayer(5f * g.s * nameScale, 0f, 1f * g.s * nameScale, 0xE604060C.toInt())
        val labelFade = (1f - z * 0.9f).coerceIn(0.12f, 1f)
        drawTextNc(t.name, p.x, p.y + r + 14f * g.s * nameScale, namePaint, nameColor.copy(alpha = nameColor.alpha * labelFade * se).toArgb())
    }
}

internal fun DrawScope.drawProceduralPlanet(
    x: Float, y: Float, r: Float, hue: Int, done: Boolean, alpha: Float,
) {
    val sat = if (done) 0.62f else 0.08f
    val light = if (done) 0.5f else 0.6f
    drawCircle(
        Brush.radialGradient(
            listOf(
                hsla(hue, sat, (light + 0.2f).coerceAtMost(0.9f), 1f),
                hsla(hue, sat, light, 1f),
                hsla((hue + 12) % 360, sat, (light - 0.28f).coerceAtLeast(0.12f), 1f),
            ),
            center = Offset(x - r * 0.3f, y - r * 0.3f),
            radius = r * 1.7f,
        ),
        r, Offset(x, y), alpha = alpha,
    )
    drawCircle(
        hsla(hue, 0.5f, 0.72f, 0.3f * alpha),
        r - 0.8f, Offset(x, y), style = Stroke(1.2f),
    )
}
