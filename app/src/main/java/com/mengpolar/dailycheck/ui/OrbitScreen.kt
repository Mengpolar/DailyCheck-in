package com.mengpolar.dailycheck.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.mengpolar.dailycheck.R
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpolar.dailycheck.data.CheckRecord
import com.mengpolar.dailycheck.data.OrbitTask
import com.mengpolar.dailycheck.data.OrbitUiState
import com.mengpolar.dailycheck.data.PlanetState
import com.mengpolar.dailycheck.data.weekdayLabel
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

// ── 星图配色 ──
private val InkColor = Color(0xFFD9DEE8)
private val DimColor = Color(0xFF7C8496)
private val FaintColor = Color(0xFF4A5164)
private val GoldColor = Color(0xFFD8B36A)
private val GoldDim = Color(0x8CD8B36A)
private val LateColor = Color(0xFFCF6A5B)
private val DueColor = Color(0xFFB9C2D4)
private val CardBg = Color(0x8C080A10)
private val CardBorder = Color(0x24D8DEEE)

private val RING_DEFS = floatArrayOf(150f, 208f, 264f)

/** 几何：以 dp 为单位（Canvas 内按屏幕密度整体缩放） */
private class Geo(
    val w: Float, val h: Float, val cx: Float, val cy: Float,
    val q: Float, val radii: List<Float>,
)

private fun computeGeo(w: Float, h: Float): Geo {
    val q = if (h > w * 1.15f) 0.62f else 0.42f
    val capX = w / 2f - 26f
    val baseScale = (w / 2f - 20f) / (264f + 30f)
    val radii = RING_DEFS.map { it * baseScale }.toMutableList()
    radii[0] = maxOf(radii[0], 58f)
    radii[1] = maxOf(radii[1], radii[0] + 52f)
    radii[2] = maxOf(radii[2], radii[1] + 52f)
    if (radii[2] > capX) {
        val sp = maxOf(30f, (capX - 58f) / 2f)
        radii.clear(); radii.addAll(listOf(58f, 58f + sp, 58f + 2f * sp))
    }
    return Geo(w, h, w / 2f, h / 2f, q, radii)
}

private fun planetAngle(frac: Float) = frac * 2f * Math.PI.toFloat()

private class PlanetPos(val x: Float, val y: Float, val nearness: Float, val r: Float)

private fun planetPos(g: Geo, t: OrbitTask): PlanetPos {
    val a = planetAngle(t.frac)
    val R = g.radii[t.ring]
    val x = g.cx + sin(a) * R
    val y = g.cy - cos(a) * R * g.q
    val nearness = (1f - cos(a)) / 2f
    val pr = (if (t.ring == 0) 16f else if (t.ring == 1) 18f else 20f) * (0.72f + 0.5f * nearness)
    return PlanetPos(x, y, nearness, pr)
}

private fun hsla(hue: Int, sat: Float, light: Float, alpha: Float) =
    Color.hsl(hue.toFloat(), sat, light, alpha)

// ── 共享画笔（避免每帧分配） ──
private val namePaint = Paint().apply {
    textSize = 11.5f; typeface = Typeface.SERIF
    textAlign = Paint.Align.CENTER; isAntiAlias = true
    setShadowLayer(5f, 0f, 1f, 0xE604060C.toInt())
}
private val anchorPaint = Paint().apply {
    textSize = 9.5f; typeface = Typeface.MONOSPACE
    textAlign = Paint.Align.CENTER; isAntiAlias = true
    setShadowLayer(4f, 0f, 1f, 0xE604060C.toInt())
}
private val dimMatrix = ColorMatrix(floatArrayOf(
    0.8f, 0f, 0f, 0f, 0f,
    0f, 0.8f, 0f, 0f, 0f,
    0f, 0f, 0.8f, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
))
private val planetPaintNormal = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
private val planetPaintGray = Paint().apply {
    isAntiAlias = true; isFilterBitmap = true
    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f); postConcat(dimMatrix) })
}
private val bgPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }

@Composable
fun OrbitScreen(
    state: OrbitUiState,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecords: (Long) -> Unit,
    onCheck: (Long) -> CheckRecord,
    onUndo: (Long) -> Unit,
) {
    val context = LocalContext.current
    val screenDensity = LocalDensity.current.density
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val selected = state.items.firstOrNull { it.id == selectedId }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // 帧时钟：驱动星空闪烁 / 琥珀脉冲 / 行星实时位置
    val frame by produceState(0L) {
        while (true) withFrameNanos { value = it }
    }
    val timeMs = frame / 1_000_000

    // 聚焦缩放动画
    val zoom = remember { Animatable(0f) }
    LaunchedEffect(selectedId) {
        zoom.animateTo(if (selectedId != null) 1f else 0f, tween(380))
    }

    // 倾斜视差：加速度计求得手机倾角，低通平滑后驱动星空轻移
    var parallax by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val listener = object : SensorEventListener {
            private var sx = 0f; private var sy = 0f
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val tx = (-(e.values[0]) / 9.8f).coerceIn(-1f, 1f) * 14f
                val ty = ((e.values[1]) / 9.8f).coerceIn(-1f, 1f) * 10f
                sx = sx * 0.85f + tx * 0.15f
                sy = sy * 0.85f + ty * 0.15f
                parallax = Offset(sx, sy)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(
            listener,
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_GAME,
        )
        onDispose { sm.unregisterListener(listener) }
    }

    // 行星图片（用户裁剪的圆形头像）
    val imageKey = state.items.joinToString("|") { it.imagePath ?: "-" }
    val planetImages = remember(imageKey) {
        val ctx = context.applicationContext
        buildMap<Long, Bitmap> {
            state.items.forEach { t ->
                val path = t.imagePath ?: return@forEach
                val f = File(ctx.filesDir, path)
                if (!f.exists()) return@forEach
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 256) sample *= 2
                    BitmapFactory.decodeFile(
                        f.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )?.let { put(t.id, it) }
                }
            }
        }
    }

    // 星空（固定种子）
    val stars = remember {
        val rnd = Random(20261118)
        List(320) {
            StarSpec(
                xf = rnd.nextFloat(), yf = rnd.nextFloat(),
                r = rnd.nextFloat() * 1.15f + 0.3f,
                warm = rnd.nextFloat() < 0.3f,
                cool = rnd.nextFloat() < 0.4f,
                phase = rnd.nextFloat() * 6.28f, speed = rnd.nextFloat() * 0.9f + 0.15f,
                flare = rnd.nextFloat() < 0.045f,
            )
        }
    }

    // 背景图（黑洞星云，打包在 drawable-nodpi；加载失败时退回纯色）
    val bgBitmap = remember { runCatching { BitmapFactory.decodeResource(context.resources, R.drawable.bg) }.getOrNull() }

    val doCheck: (OrbitTask) -> Unit = { t ->
        val record = onCheck(t.id)
        scope.launch {
            val result = snackbar.showSnackbar(
                message = "已打卡「${t.name}」",
                actionLabel = "撤销",
                withDismissAction = false,
            )
            if (result == SnackbarResult.ActionPerformed) onUndo(record.id)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF06070B))
            .onSizeChanged { viewSize = it }
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(state.items, viewSize) {
                    detectTapGestures { tap ->
                        if (viewSize == IntSize.Zero) return@detectTapGestures
                        val g = computeGeo(viewSize.width / screenDensity, viewSize.height / screenDensity)
                        val tx = tap.x / screenDensity; val ty = tap.y / screenDensity
                        var best: OrbitTask? = null
                        var bd = 1e9f
                        state.items.forEach { t ->
                            val p = planetPos(g, t)
                            val dist = hypot((tx - p.x).toDouble(), (ty - p.y).toDouble()).toFloat()
                            if (dist < 34f && dist < bd) { bd = dist; best = t }
                        }
                        selectedId = best?.id   // 点空白 → 退焦
                    }
                }
        ) {
            val d = this.density
            val g = computeGeo(size.width / d, size.height / d)
            val z = zoom.value

            scale(d, d) {
                // 背景图 cover-crop 居中（视差偏移减半，保留暗角保证文字可读）
                if (bgBitmap != null) {
                    val bw = bgBitmap.width.toFloat(); val bh = bgBitmap.height.toFloat()
                    val cover = maxOf(g.w / bw, g.h / bh) * 1.05f
                    val dw = bw * cover; val dh = bh * cover
                    val left = (g.w - dw) / 2f - parallax.x * 0.5f
                    val top = (g.h - dh) / 2f - parallax.y * 0.5f
                    drawContext.canvas.nativeCanvas.drawBitmap(
                        bgBitmap, null,
                        RectF(left, top, left + dw, top + dh),
                        bgPaint,
                    )
                } else {
                    drawRect(Color(0xFF06070B), size = Size(g.w, g.h))
                }
                // 暗角：边缘压暗，突出中心星系与文字
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0x2206070B), Color(0x96000000)),
                        center = Offset(g.cx, g.cy),
                        radius = maxOf(g.w, g.h) * 0.72f,
                    ),
                    size = Size(g.w, g.h),
                )
                drawStars(g, stars, timeMs, parallax, 1f - 0.3f * z)

                // 星系层：视差 + 聚焦缩放
                scale(1f + 0.05f * z, 1f + 0.05f * z, pivot = Offset(g.cx, g.cy)) {
                    drawOrbitLayer(g, z)
                    drawAnchorTicks(g, state.items)
                    state.items.sortedBy { planetPos(g, it).nearness }.forEach { t ->
                        drawTask(g, t, timeMs, z, selected, planetImages, dimmed = selected != null && selected.id != t.id)
                    }
                }
            }
        }

        // 顶部：日期 + 入口
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxSize()
                .padding(start = 20.dp, top = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${state.today.monthValue}月${state.today.dayOfMonth}日 · ${weekdayLabel(state.today)}",
                    color = InkColor, fontSize = 16.sp, fontFamily = FontFamily.Serif, letterSpacing = 2.sp,
                )
                Text(
                    "TODAY ${state.doneCount}/${state.total} · LIT ${state.doneCount}",
                    color = DimColor, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Rounded.Settings, "设置", tint = DimColor)
            }
            IconButton(onClick = onAdd) {
                Icon(Icons.Rounded.Add, "添加任务", tint = GoldColor)
            }
        }

        // 中心：未聚焦 = 空白提示；聚焦 = 详情卡
        if (selected == null) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("＋", color = CardBorder, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Text(
                    "轻点行星 · 查看其周期",
                    color = FaintColor, fontSize = 12.sp,
                    fontFamily = FontFamily.Serif, letterSpacing = 5.sp,
                )
            }
        } else {
            FocusCard(
                t = selected,
                zoom = zoom.value,
                onCheck = { doCheck(selected) },
                onOpenRecords = { onOpenRecords(selected.id) },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun FocusCard(
    t: OrbitTask,
    zoom: Float,
    onCheck: () -> Unit,
    onOpenRecords: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(248.dp)
            .graphicsLayer {
                alpha = zoom.coerceIn(0f, 1f)
                scaleX = 0.9f + 0.1f * zoom
                scaleY = 0.9f + 0.1f * zoom
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(CardBg)
                .border(1.dp, CardBorder, CircleShape)
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    t.name,
                    color = InkColor, fontSize = 22.sp, fontFamily = FontFamily.Serif,
                    letterSpacing = 4.sp, maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${t.cycleLabel} · 第 ${t.circleNo} 圈",
                    color = DimColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(6.dp))
                val statusColor = when (t.state) {
                    PlanetState.DONE -> GoldColor
                    PlanetState.LATE -> LateColor
                    else -> DueColor
                }
                Text(
                    when (t.state) {
                        PlanetState.DONE -> "✓ 本周期已点亮"
                        PlanetState.LATE -> "⚠ 已逾期 · 正在熄灭"
                        PlanetState.NEAR -> "临近到期 · 若隐若现"
                        PlanetState.PENDING -> "待打卡 · 黑白星"
                    },
                    color = statusColor, fontSize = 14.sp, fontFamily = FontFamily.Serif, letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "⏱ ${t.nextText}",
                    color = DimColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(10.dp))
                val done = t.state == PlanetState.DONE
                OutlinedButton(
                    onClick = onCheck,
                    enabled = !done,
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(1.dp, if (done) GoldDim else GoldDim),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = GoldColor,
                        disabledContentColor = GoldColor.copy(alpha = 0.7f),
                        disabledContainerColor = Color.Transparent,
                    ),
                ) {
                    Text(
                        if (done) "✓ 已打卡" else "打 卡",
                        fontSize = 13.sp, fontFamily = FontFamily.Serif,
                        letterSpacing = 6.sp, modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "累计 ${t.recordCount} 圈 · 最近 ${t.lastCheckText}",
                    color = FaintColor, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "打卡记录 (${t.recordCount}) ›",
                    color = DimColor, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { onOpenRecords() },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "点击空白处 · 退出聚焦",
                    color = FaintColor.copy(alpha = 0.8f), fontSize = 10.sp,
                    fontFamily = FontFamily.Serif, letterSpacing = 2.sp,
                )
            }
        }
    }
}

private data class StarSpec(
    val xf: Float, val yf: Float, val r: Float,
    val warm: Boolean, val cool: Boolean,
    val phase: Float, val speed: Float, val flare: Boolean,
)

private fun DrawScope.drawStars(
    g: Geo, stars: List<StarSpec>, timeMs: Long, par: Offset, dimF: Float,
) {
    stars.forEach { s ->
        val tw = 0.4f + 0.6f * abs(sin(timeMs / 1400f * s.speed + s.phase))
        val color = when {
            s.warm -> Color(0xFFFFE9C4)
            s.cool -> Color(0xFFCFE0FF)
            else -> Color(0xFFE2E6F0)
        }
        val x = s.xf * g.w + par.x; val y = s.yf * g.h + par.y
        drawCircle(color.copy(alpha = tw * 0.85f * dimF), s.r, Offset(x, y))
        if (s.flare) {
            val l = s.r * 5f
            drawLine(color.copy(alpha = tw * 0.5f * dimF), Offset(x - l, y), Offset(x + l, y), strokeWidth = 0.8f)
            drawLine(color.copy(alpha = tw * 0.5f * dimF), Offset(x, y - l), Offset(x, y + l), strokeWidth = 0.8f)
        }
    }
}

private fun DrawScope.drawOrbitLayer(g: Geo, z: Float) {
    g.radii.forEach { R ->
        drawArc(
            Color(0xFFBEC8E1).copy(alpha = 0.10f * (1f - 0.35f * z)),
            0f, 360f, false,
            topLeft = Offset(g.cx - R, g.cy - R * g.q),
            size = Size(R * 2f, R * g.q * 2f),
            style = Stroke(1f),
        )
    }
}

private fun DrawScope.drawAnchorTicks(g: Geo, items: List<OrbitTask>) {
    g.radii.forEachIndexed { i, R ->
        val y = g.cy - R * g.q
        drawLine(Color(0x4DD8DEEE), Offset(g.cx, y - 4f), Offset(g.cx, y + 4f), strokeWidth = 1f)
        val label = items.filter { it.ring == i }.joinToString(" / ") { it.anchorLabel }
        if (label.isNotBlank()) {
            drawTextNc(label, g.cx, y - 9f, anchorPaint, DimColor.copy(alpha = 0.85f).toArgb())
        }
    }
}

private fun DrawScope.drawTextNc(text: String, x: Float, y: Float, paint: Paint, argb: Int) {
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint.apply { setColor(argb) })
}

private fun DrawScope.drawTask(
    g: Geo,
    t: OrbitTask,
    timeMs: Long,
    z: Float,
    selected: OrbitTask?,
    images: Map<Long, Bitmap>,
    dimmed: Boolean,
) {
    val p = planetPos(g, t)
    val aDeg = Math.toDegrees(planetAngle(t.frac).toDouble()).toFloat()
    val dim = if (dimmed) 1f - 0.62f * z else 1f
    val depthA = 0.5f + 0.5f * p.nearness
    val sel = selected?.id == t.id
    val done = t.state == PlanetState.DONE
    val late = t.state == PlanetState.LATE
    val R = g.radii[t.ring]
    val ovalTopLeft = Offset(g.cx - R, g.cy - R * g.q)
    val ovalSize = Size(R * 2f, R * g.q * 2f)

    // 周期进度弧：从起始线到行星当前位置（逾期用暗红弧）
    val arcAlpha = 0.42f * dim
    if (late) {
        val segs = 14
        for (i in 0 until segs) {
            val f0 = i / segs.toFloat(); val f1 = (i + 1) / segs.toFloat()
            drawArc(
                LateColor.copy(alpha = 0.55f * dim * (1f - 0.4f * abs(f0 - 0.5f) * 2f)),
                -90f + aDeg * f0, aDeg * (f1 - f0), false,
                topLeft = ovalTopLeft, size = ovalSize, style = Stroke(1.6f),
            )
        }
    } else {
        drawArc(
            hsla(t.hue, 0.5f, 0.58f, arcAlpha), -90f, aDeg, false,
            topLeft = ovalTopLeft, size = ovalSize, style = Stroke(1.5f),
        )
    }

    // 彗尾（打卡后随时间淡出，拖在行星后方）
    if (done && t.tail > 0f) {
        val span = t.tail * 360f
        val segs = 18
        for (i in 0 until segs) {
            val f0 = i / segs.toFloat(); val f1 = (i + 1) / segs.toFloat()
            val al = (1f - f0) * (1f - f0) * 0.4f * dim
            drawArc(
                hsla(t.hue, 0.65f, 0.68f, al),
                -90f + aDeg - span * f1, span * (f1 - f0), false,
                topLeft = ovalTopLeft, size = ovalSize,
                style = Stroke(maxOf(0.8f, p.r * 0.55f * (1f - f0))),
            )
        }
    }

    // 状态环：临近到期 = 琥珀脉冲；逾期 = 暗红
    if (t.state == PlanetState.NEAR) {
        val pulse = 0.3f + 0.32f * (0.5f + 0.5f * sin(timeMs / 500f + t.ring * 2f))
        drawCircle(hsla(40, 0.85f, 0.62f, pulse * dim), p.r + 5f, Offset(p.x, p.y), style = Stroke(1.3f))
    }
    if (late) {
        drawCircle(LateColor.copy(alpha = 0.5f * dim), p.r + 5f, Offset(p.x, p.y), style = Stroke(1.2f))
    }

    // 选中：星表十字准星
    if (sel) {
        val gap = p.r + 9f
        val l = 5f + sin(timeMs / 900f) * 1.2f
        val c = Color(0xFFD8DEEE).copy(alpha = 0.75f * (0.4f + 0.6f * z))
        listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f).forEach { (dx, dy) ->
            drawLine(c, Offset(p.x + dx * (gap - l), p.y + dy * (gap - l)), Offset(p.x + dx * (gap + l), p.y + dy * (gap + l)), 1f)
        }
        drawCircle(Color(0xFFD8DEEE).copy(alpha = 0.22f * z), gap, Offset(p.x, p.y), style = Stroke(1f))
    }

    // 已完成光晕（克制）
    if (done) {
        drawCircle(
            Brush.radialGradient(
                listOf(hsla(t.hue, 0.6f, 0.62f, 0.22f * dim), Color.Black.copy(alpha = 0f)),
                center = Offset(p.x, p.y), radius = p.r * 2.1f,
            ),
            p.r * 2.1f, Offset(p.x, p.y),
        )
    }

    // 行星本体
    val alpha = (depthA * dim * when (t.state) {
        PlanetState.NEAR -> 0.55f + 0.28f * (0.5f + 0.5f * sin(timeMs / 500f + t.ring * 2f))
        PlanetState.LATE -> 0.62f
        else -> 1f
    }).coerceIn(0f, 1f)

    val bmp = images[t.id]
    if (bmp != null) {
        val paint = if (done) planetPaintNormal else planetPaintGray
        paint.alpha = (alpha * 255).toInt()
        drawContext.canvas.nativeCanvas.drawBitmap(
            bmp, null,
            RectF(p.x - p.r, p.y - p.r, p.x + p.r, p.y + p.r),
            paint,
        )
    } else {
        drawProceduralPlanet(p.x, p.y, p.r, t.hue, done, alpha)
    }

    // 名字注记（衬线 + 投影）
    val nameColor = if (done)
        hsla(t.hue, 0.45f, 0.8f, 0.95f * maxOf(dim, 0.5f))
    else
        Color(0xFFC4CCDE).copy(alpha = 0.85f * maxOf(dim, 0.5f))
    drawTextNc(t.name, p.x, p.y + p.r + 14f, namePaint, nameColor.toArgb())
}

private fun DrawScope.drawProceduralPlanet(
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
