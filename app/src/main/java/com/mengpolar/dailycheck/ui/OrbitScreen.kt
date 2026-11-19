package com.mengpolar.dailycheck.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import com.mengpolar.dailycheck.R
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
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

/** 长按打卡所需时长（秒）：按住每秒 +1，松手每秒 -1 */
private const val CHECK_HOLD_S = 3f

/** 聚焦页按钮默认底色（灰黑）与长按填充的淡金 */
private val BtnBg = Color(0xE6202630)
private val HoldGold = Color(0xFFDFBE7E)

/** 几何：以 dp 为单位（Canvas 内按屏幕密度整体缩放） */
private class Geo(
    val w: Float, val h: Float, val cx: Float, val cy: Float,
    val q: Float, val radiiPx: List<Float>, val s: Float,
    /** 相机缩放（1 = 全景），聚焦时 = 目标倍率 × 聚焦进度 */
    val zoom: Float = 1f,
) {
    /** dp 常数转 px */
    fun dp(v: Float) = v * s
}

private fun computeGeo(wPx: Float, hPx: Float, d: Float): Geo {
    val q = if (hPx > wPx * 1.15f) 0.62f else 0.42f
    val s = d
    val capX = wPx / 2f - 26f * s
    val capY = hPx / 2f * 0.92f - 26f * s
    val baseScale = (wPx / 2f - 20f * s) / (264f + 30f)
    val radii = RING_DEFS.map { it * baseScale * s }.toMutableList()
    radii[0] = maxOf(radii[0], 58f * s)
    radii[1] = maxOf(radii[1], radii[0] + 52f * s)
    radii[2] = maxOf(radii[2], radii[1] + 52f * s)
    val cap = minOf(capX, capY)
    if (radii[2] > cap) {
        val sp = maxOf(26f * s, (cap - 90f * s) / 2f)
        radii.clear(); radii.addAll(listOf(90f * s, 90f * s + sp, 90f * s + 2f * sp))
    }
    return Geo(wPx, hPx, wPx / 2f, hPx / 2f, q, radii, s)
}

/** 相机变换：K 为镜头中心（显示在屏幕中央的世界点），camScale 为倍率；camScale=1 且 K=几何中心时为恒等 */
private fun transformGeo(g: Geo, camScale: Float, kx: Float, ky: Float): Geo = Geo(
    w = g.w, h = g.h,
    cx = g.cx + (g.cx - kx) * camScale,
    cy = g.cy + (g.cy - ky) * camScale,
    q = g.q,
    radiiPx = g.radiiPx.map { it * camScale },
    s = g.s,
    zoom = camScale,
)

private fun planetAngle(frac: Float) = frac * 2f * Math.PI.toFloat()

private class PlanetPos(val x: Float, val y: Float, val nearness: Float, val r: Float)

private fun planetPos(g: Geo, t: OrbitTask): PlanetPos {
    val a = planetAngle(t.frac) + t.jitterDeg * Math.PI.toFloat() / 180f
    val R = g.radiiPx[t.ring]
    val x = g.cx + sin(a) * R
    val y = g.cy - cos(a) * R * g.q
    val nearness = (1f - cos(a)) / 2f
    val pr = (if (t.ring == 0) 18f else if (t.ring == 1) 20f else 22f) * g.s * g.zoom * (0.85f + 0.35f * nearness)
    return PlanetPos(x, y, nearness, pr)
}

private fun hsla(hue: Int, sat: Float, light: Float, alpha: Float) =
    Color.hsl(hue.toFloat(), sat, light, alpha)

// ── 共享画笔（避免每帧分配） ──
private val namePaint = Paint().apply {
    typeface = Typeface.SERIF
    textAlign = Paint.Align.CENTER; isAntiAlias = true
}
private val anchorPaint = Paint().apply {
    typeface = Typeface.MONOSPACE
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

@Composable
fun OrbitScreen(
    state: OrbitUiState,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecords: (Long) -> Unit,
    onCheck: (Long) -> CheckRecord,
    onUndo: (Long) -> Unit,
    onEdit: (Long) -> Unit,
) {
    val context = LocalContext.current
    val screenDensity = LocalDensity.current.density
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val selected = state.items.firstOrNull { it.id == selectedId }
    // 相机锚点任务：退焦后 selected 变 null，镜头仍需围绕最后选中的行星缩小（动画期间保持锚点不丢）
    var recentSelected by remember { mutableStateOf<OrbitTask?>(null) }
    if (selected != null) recentSelected = selected
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // 帧时钟：驱动星空闪烁 / 琥珀脉冲 / 行星实时位置
    val frame by produceState(0L) {
        while (true) withFrameNanos { value = it }
    }
    val timeMs = frame / 1_000_000

    // 聚焦缩放动画：推近 ~1.2s / 退焦 ~0.9s，缓入缓出（行星自中心放大 / 缩小）
    val zoom = remember { Animatable(0f) }
    LaunchedEffect(selectedId) {
        if (selectedId != null) {
            zoom.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
        } else {
            zoom.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
        }
    }

    // 倾斜视差：加速度计求得手机倾角（竖屏左右倾斜 → 左右偏移），低通平滑后驱动星空轻移
    var parallax by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val listener = object : SensorEventListener {
            private var sx = 0f; private var sy = 0f
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                // 竖屏持机：gx 随左右倾斜变化；gy≈9.8 为基准，上下倾斜取差值
                val tx = (-(e.values[0]) / 9.8f).coerceIn(-1f, 1f) * 30f
                val ty = ((e.values[1]) / 9.8f - 1f).coerceIn(-1f, 1f) * 18f
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
        buildMap<Long, ImageBitmap> {
            state.items.forEach { t ->
                val path = t.imagePath ?: return@forEach
                val f = File(ctx.filesDir, path)
                if (!f.exists()) return@forEach
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, bounds)
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 1024) sample *= 2
                    BitmapFactory.decodeFile(
                        f.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )?.let { put(t.id, circleCrop(it).asImageBitmap()) }
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

    // 点击星光：触摸处留下随机颜色的四芒星光，5 秒淡出
    val sparks = remember { mutableStateListOf<Spark>() }
    val sparkRnd = remember { Random(System.nanoTime()) }
    val addSpark: (Float, Float) -> Unit = { x, y ->
        val now = System.nanoTime() / 1_000_000L  // 与帧时钟同基准（uptime ms）
        sparks.removeAll { now - it.born > 5200 }
        sparks.add(Spark(x, y, sparkRnd.nextInt(360), sparkRnd.nextFloat(), now))
    }

    // 月球贴图（用户提供的月面照片，透明背景）：弧形导航的自转月面
    val moonImage = remember {
        runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.moon)?.let { processMoon(it) }?.asImageBitmap()
        }.getOrNull()
    }
    // 星光贴图：star.svg 转白的位图，点击特效动态染色/缩放/旋转
    val sparkStar = remember {
        val sizePx = 256
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val dr = context.getDrawable(R.drawable.ic_star_add)
        if (dr != null) {
            dr.setBounds(0, 0, sizePx, sizePx)
            dr.setTint(Color.White.toArgb())
            dr.draw(c)
        }
        out.asImageBitmap()
    }
    // 背景图（黑洞星云）：与星系画在同一个 Canvas 里，保证行星永远在背景之上
    val bgImage = remember {
        runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.bg)?.asImageBitmap()
        }.getOrNull()
    }

    // 诊断几何（与 Canvas 内一致）
    val diagGeo = remember(viewSize) {
        computeGeo(viewSize.width.toFloat(), viewSize.height.toFloat(), screenDensity)
    }

    // 背景图在 Composable 层用 Image 绘制；此处移除位图解码逻辑

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

    // 长按打卡：按住填充（每秒 +1），松手渐退（每秒 -1），满 3 秒完成打卡并点亮行星
    var holdDown by remember { mutableStateOf(false) }
    val holdProgress = remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(selectedId) {
        holdDown = false
        holdProgress.floatValue = 0f
        if (selectedId == null) return@LaunchedEffect
        val task = state.items.firstOrNull { it.id == selectedId } ?: return@LaunchedEffect
        var last = withFrameNanos { it }
        var logTick = 0
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
            last = now
            val next = if (holdDown) holdProgress.floatValue + dt else holdProgress.floatValue - dt
            val v = next.coerceIn(0f, CHECK_HOLD_S)
            holdProgress.floatValue = v
            if (++logTick % 30 == 0) Log.d("OrbitCanvas", "hold loop p=$v down=$holdDown")
            if (holdDown && v >= CHECK_HOLD_S) {
                holdDown = false
                holdProgress.floatValue = 0f
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                doCheck(task)
            }
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
                        Log.d("OrbitCanvas", "main tap=(${tap.x},${tap.y}) sparkZone=${tap.y < size.height - 100f * screenDensity} canvasSize=${size.width}x${size.height}")
                        val g0 = computeGeo(viewSize.width.toFloat(), viewSize.height.toFloat(), screenDensity)
                        // 相机状态与绘制一致（行星先原地放大、中后段滑向屏心）。
                        // 注意：pointerInput 的闭包可能持有旧组合的 selected，这里按 selectedId 现取，保证命中测试与绘制一致
                        val selNow = state.items.firstOrNull { it.id == selectedId } ?: recentSelected
                        var camScale = 1f
                        var kx = g0.cx; var ky = g0.cy
                        if (selNow != null) {
                            val fp = planetPos(g0, selNow)
                            val targetZoom = (viewSize.width / (fp.r * 2f)).coerceIn(1f, 40f)
                            camScale = 1f + (targetZoom - 1f) * zoom.value
                            val dp = ((zoom.value - 0.4f) / 0.6f).coerceIn(0f, 1f)
                            val d = dp * dp * (3f - 2f * dp)
                            val px = fp.x + (g0.cx - fp.x) * d
                            val py = fp.y + (g0.cy - fp.y) * d
                            kx = fp.x - (px - g0.cx) / camScale
                            ky = fp.y - (py - g0.cy) / camScale
                        }
                        val g = transformGeo(g0, camScale, kx, ky)
                        val tx = tap.x; val ty = tap.y
                        var best: OrbitTask? = null
                        var bd = 1e9f
                        state.items.forEach { t ->
                            val p = planetPos(g, t)
                            val dist = hypot((tx - p.x).toDouble(), (ty - p.y).toDouble()).toFloat()
                            if (dist < p.r + 30f * screenDensity && dist < bd) { bd = dist; best = t }
                        }
                        // 已聚焦时行星吞没整屏、无空白可点：再点选中行星本身 = 退焦；未聚焦时点行星 = 聚焦
                        selectedId = if (best != null && best.id == selectedId) null else best?.id
                        // 弧形导航区域内的 tap 交给弧自身处理；点中行星时不生成星光（避免放大的行星表面残留星点）
                        if (best == null && tap.y < size.height - 100f * screenDensity) addSpark(tap.x, tap.y)
                    }
                }
        ) {
            val d = this.density
            val g0 = computeGeo(size.width, size.height, d)
            val z = zoom.value
            // 相机：行星先原地放大（前 40% 行程），随后在放大过程中缓缓滑向屏幕中心；
            // 落定时行星居中且直径≈屏宽。退焦沿同一曲线反向（先滑回轨道位置、再原地缩小）。
            // recentSelected 保证退焦动画期间镜头目标不丢
            var camScale = 1f
            var kx = g0.cx; var ky = g0.cy
            val selNow = selected ?: recentSelected
            if (selNow != null) {
                val fp = planetPos(g0, selNow)
                val targetZoom = (size.width / (fp.r * 2f)).coerceIn(1f, 40f)
                camScale = 1f + (targetZoom - 1f) * z
                val dp = ((z - 0.4f) / 0.6f).coerceIn(0f, 1f)
                val d = dp * dp * (3f - 2f * dp)   // smoothstep：位移集中在中后段
                val px = fp.x + (g0.cx - fp.x) * d
                val py = fp.y + (g0.cy - fp.y) * d
                // 由"行星屏幕位置 = 屏心 + (fp - K) × camScale"反解镜头中心 K
                kx = fp.x - (px - g0.cx) / camScale
                ky = fp.y - (py - g0.cy) / camScale
            }
            val g = transformGeo(g0, camScale, kx, ky)

            try {
                // 背景图：原始像素坐标绘制（不经 scale 变换，规避 dst 单位歧义），cover 铺满 + 视差
                val bg = bgImage
                if (bg != null) {
                    val bw = bg.width.toFloat(); val bh = bg.height.toFloat()
                    val bgScale = 1f + (camScale - 1f) * 0.12f
                    val cover = maxOf(size.width / bw, size.height / bh) * 1.16f * bgScale
                    val dw = bw * cover; val dh = bh * cover
                    val marginX = (dw - size.width) / 2f; val marginY = (dh - size.height) / 2f
                    val camShiftX = (size.width / 2f - kx) * 0.08f * z
                    val camShiftY = (size.height / 2f - ky) * 0.08f * z
                    val left = (size.width - dw) / 2f - parallax.x * d + camShiftX.coerceIn(-marginX, marginX)
                    val top = (size.height - dh) / 2f - parallax.y * d + camShiftY.coerceIn(-marginY, marginY)
                    drawImage(
                        bg,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(bg.width, bg.height),
                        dstOffset = IntOffset(left.toInt(), top.toInt()),
                        dstSize = IntSize(dw.toInt(), dh.toInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                } else {
                    drawRect(Color(0xFF06070B), size = size)
                }
                // 暗角：边缘压暗，突出中心星系与文字（原始像素坐标）
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0x2206070B), Color(0x96000000)),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = maxOf(size.width, size.height) * 0.72f,
                    )
                )
                val starShift = Offset((g0.cx - kx) * 0.25f * z, (g0.cy - ky) * 0.25f * z)
                drawStars(g0, stars, timeMs, Offset(parallax.x * 1.3f + starShift.x, parallax.y * 1.3f + starShift.y), (1f - 0.55f * z).coerceIn(0.15f, 1f), sparkStar)

                // 星系层：聚焦缩放通过轨道半径放大实现（无变换层，规避设备差异）
                drawOrbitLayer(g)
                drawAnchorTicks(g, state.items, z)
                state.items.sortedBy { planetPos(g, it).nearness }.forEach { t ->
                    drawTask(
                        g, t, timeMs, z, selected, planetImages,
                        dimmed = selected != null && selected.id != t.id,
                        fill = if (selected?.id == t.id && selected.state != PlanetState.DONE)
                            (holdProgress.floatValue / CHECK_HOLD_S).coerceIn(0f, 1f) else 0f,
                    )
                }

                // 点击星光（最上层）
                drawSparks(g, sparks, timeMs, sparkStar)
            } catch (t: Throwable) {
                // 渲染异常兜底：屏上直接显示错误，避免整片黑无从排查
                Log.e("OrbitCanvas", "draw failed", t)
                drawRect(Color(0xFF160A0A))
                drawContext.canvas.nativeCanvas.drawText(
                    "渲染异常: ${t.message?.take(80)}",
                    24f, size.height / 2f,
                    Paint().apply {
                        color = Color(0xFFFF8888).toArgb(); textSize = 36f; isAntiAlias = true
                    },
                )
            }
        }

        // ── 聚焦态界面 ── 退焦动画期间沿用最后选中任务，随缩放淡出
        val cardTask = selected ?: recentSelected.takeIf { zoom.value > 0.55f }
        if (cardTask != null) {
            val t = cardTask
            val pa = ((zoom.value - 0.55f) / 0.45f).coerceIn(0f, 1f)
            val fillFrac = (holdProgress.floatValue / CHECK_HOLD_S).coerceIn(0f, 1f)
            val done = t.state == PlanetState.DONE
            val remainingMs = (t.periodEndMs - System.currentTimeMillis()).coerceAtLeast(0L)
            val remainColor = when (t.state) {
                PlanetState.LATE -> LateColor
                PlanetState.NEAR -> GoldColor
                else -> Color(0xFFB9C2D4)
            }

            // 顶部信息卡：任务名称 / 周期 / 倒计时 / 已完成周期数；聚焦时从上往下出现，退焦反向
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 44.dp, start = 18.dp, end = 18.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = pa
                        translationY = -(1f - pa) * 90f
                    }
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xC010141A))
                    .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(24.dp))
                    .pointerInput(Unit) { detectTapGestures { } }   // 消费卡内点击，避免透传退焦
                    .padding(horizontal = 22.dp, vertical = 16.dp),
            ) {
                Column {
                    Text(
                        t.name,
                        color = InkColor, fontSize = 21.sp, fontFamily = FontFamily.Serif,
                        letterSpacing = 2.sp, maxLines = 1,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${t.cycleLabel} · 已完成 ${t.recordCount} 个周期",
                        color = DimColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (remainingMs > 0L) "本周期还剩 ${formatRemaining(remainingMs)}" else "本周期已结束 · 即将重置",
                        color = remainColor, fontSize = 13.sp, fontFamily = FontFamily.Serif, letterSpacing = 1.sp,
                    )
                    if (t.note.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(t.note, color = DimColor, fontSize = 12.sp, maxLines = 2)
                    }
                }
            }

            // 底部操作卡：编辑 / 长按打卡 / 历史；聚焦时从下往上出现，退焦反向
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 18.dp, end = 18.dp, bottom = 32.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = pa
                        translationY = (1f - pa) * 90f
                    }
                    // 仅占位命中路径挡住点按透传（空 handler 不消费事件，子按钮才能收到按压）
                    .pointerInput(Unit) { }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .weight(0.8f).height(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(BtnBg)
                            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(14.dp))
                            .clickable { onEdit(t.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("编辑", color = InkColor, fontSize = 14.sp, fontFamily = FontFamily.Serif, letterSpacing = 1.sp)
                    }
                    Box(
                        Modifier
                            .weight(1.4f).height(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(BtnBg)
                            .border(
                                1.dp,
                                if (fillFrac > 0f) Color(0x66D8B36A) else Color(0x2EFFFFFF),
                                RoundedCornerShape(14.dp),
                            )
                            .pointerInput(t.id, done) {
                                if (!done) detectTapGestures(
                                    onPress = {
                                        Log.d("OrbitCanvas", "hold press START")
                                        holdDown = true
                                        try { awaitRelease() } finally { Log.d("OrbitCanvas", "hold press END"); holdDown = false }
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        // 长按填充层：从左往右逐渐铺满淡金色
                        Canvas(Modifier.matchParentSize()) {
                            if (fillFrac > 0f) {
                                drawRect(HoldGold, size = Size(size.width * fillFrac, size.height))
                            }
                        }
                        Text(
                            when {
                                done -> "✓ 本周期已点亮"
                                fillFrac > 0f -> "${(fillFrac * 100).toInt()}%"
                                else -> "长按打卡"
                            },
                            color = if (done) GoldDim else lerp(InkColor, Color(0xFF241A08), fillFrac),
                            fontSize = 14.sp, fontFamily = FontFamily.Serif, letterSpacing = 2.sp,
                        )
                    }
                    Box(
                        Modifier
                            .weight(0.8f).height(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(BtnBg)
                            .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(14.dp))
                            .clickable { onOpenRecords(t.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("历史", color = InkColor, fontSize = 14.sp, fontFamily = FontFamily.Serif, letterSpacing = 1.sp)
                    }
                }
            }
        }

        // 诊断行（定位渲染问题用，确认正常后移除）
        Text(
            "画布 ${viewSize.width}x${viewSize.height}px · 帧 ${frame / 1_000_000} · 任务 ${state.items.size} · 背景${if (bgImage != null) "√" else "×"} · 中心(${diagGeo.cx.toInt()},${diagGeo.cy.toInt()}) 轨道${diagGeo.radiiPx.map { (it / screenDensity).toInt() }}dp",
            color = Color(0xFF9BA5B8), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp),
        )

        // 底部弧形导航：上滑展开 设置/添加，下滑收起；聚焦时随镜头淡出并停用触摸
        ArcNavBar(
            onAdd = onAdd,
            onOpenSettings = onOpenSettings,
            moonImage = moonImage,
            timeMs = timeMs,
            dim = zoom.value,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 150.dp),
        )
    }
}

/** 底部弧形导航：月球表面形态的弧形地平线，向上凸起；整条弧带动画升降，升起后带出按钮 */
@Composable
private fun ArcNavBar(
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    moonImage: ImageBitmap?,
    timeMs: Long,
    dim: Float,
    modifier: Modifier = Modifier,
) {
    val anim = remember { Animatable(0f) }
    var target by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val progress = anim.value

    // 轻点切换：整体动画升起 / 收起
    LaunchedEffect(target) {
        anim.animateTo(target, tween(450, easing = FastOutSlowInEasing))
    }

    val heightDp = 40.dp + 100.dp * progress
    // 聚焦淡出（dim→1）时整条弧必须对命中测试透明：不挂 pointerInput、不组合按钮行，
    // 否则它的空手势节点会遮住聚焦页底部按钮的按压事件
    val interactive = dim <= 0.5f
    // 外层为触摸热区盒：比视觉月面高 64dp，弧上方的轻点/上滑也能命中
    Box(
        modifier
            .fillMaxWidth()
            .height(heightDp + 64.dp)
            .graphicsLayer { alpha = (1f - dim).coerceIn(0f, 1f) }
            .then(
                if (interactive) {
                    Modifier
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { change, dragAmount ->
                                change.consume()
                                val next = (anim.value - dragAmount / 240f).coerceIn(0f, 1f)
                                target = next
                                scope.launch { anim.snapTo(next) } // 拖动跟手
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures {
                                Log.d("OrbitCanvas", "arc tapped, anim=${anim.value} -> ${if (anim.value > 0.5f) 0 else 1}")
                                target = if (anim.value > 0.5f) 0f else 1f
                            }
                        }
                } else Modifier
                ,
            ),
    ) {
        // 视觉月面：月球圆盘自转，上缘即弧形地平线；随 progress 刚性升起
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(heightDp)
                .align(Alignment.BottomCenter)
        ) {
            val d = this.density
            val w = size.width; val h = size.height
            val moon = moonImage
            if (moon != null) {
                // 圆盘直径 1.44 倍屏宽，圆心沉入屏幕底部，只露上部边缘
                val diskR = w * 0.72f
                val reveal = 44f * d + 106f * d * progress
                val cym = h - reveal + diskR
                val cxm = w / 2f
                // 自转 ~1.5°/s：月面纹理沿边缘缓缓移动
                val rot = (timeMs * 0.0015f) % 360f
                // 月缘上方柔光
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0x2BE8ECF4), Color(0x000D1016)),
                        center = Offset(cxm, cym - diskR * 0.92f),
                        radius = diskR * 0.85f,
                    ),
                    diskR * 0.85f, Offset(cxm, cym - diskR * 0.92f),
                )
                val dw = diskR * 2f
                val dh = dw * moon.height.toFloat() / moon.width.toFloat()
                rotate(degrees = rot, pivot = Offset(cxm, cym)) {
                    drawImage(
                        moon,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(moon.width, moon.height),
                        dstOffset = IntOffset((cxm - dw / 2f).toInt(), (cym - dh / 2f).toInt()),
                        dstSize = IntSize(dw.toInt(), dh.toInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            } else {
                // 兜底：无月图时绘制灰色弧带
                val band = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, h * 0.4f)
                    quadraticBezierTo(w / 2f, -h * 0.2f, w, h * 0.4f)
                    lineTo(w, h)
                    close()
                }
                drawPath(
                    band,
                    Brush.verticalGradient(
                        listOf(Color(0xFF3E4552), Color(0xFF0D1016)),
                        startY = h * 0.3f, endY = h,
                    ),
                )
            }
        }
        val clickable = progress > 0.5f && dim <= 0.5f
        if (interactive) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = (6.dp + 18.dp * progress))
                    .graphicsLayer { alpha = progress; translationY = (1f - progress) * 40f },
                horizontalArrangement = Arrangement.spacedBy(44.dp),
            ) {
                listOf(
                    R.drawable.ic_star_add to onAdd,
                    R.drawable.ic_setting_btn to onOpenSettings,
                ).forEach { (iconRes, onClick) ->
                    Box(contentAlignment = Alignment.Center) {
                        // 轻微黑色光晕：黑图标在亮月面上的衬底
                        Box(
                            Modifier
                                .size(68.dp)
                                .background(
                                    Brush.radialGradient(
                                        listOf(Color(0x59000000), Color(0x00000000))
                                    )
                                )
                        )
                        IconButton(onClick = onClick, enabled = clickable) {
                            Icon(
                                painter = painterResource(iconRes),
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 月图处理：moon2 为居中正方形透图——圆内（半径 = 边长/2）强制不透明，圆外透明 */
private fun processMoon(src: Bitmap): Bitmap {
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
private fun formatRemaining(ms: Long): String {
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

private data class Spark(
    val x: Float, val y: Float,
    val hue: Int, val seed: Float,
    val born: Long, // uptime ms，与帧时钟同基准
)

private fun DrawScope.drawSparks(g: Geo, sparks: List<Spark>, timeMs: Long, starBmp: ImageBitmap) {
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

/** 方形位图 → 圆形（透明背景），供行星绘制 */
private fun circleCrop(src: Bitmap): Bitmap {
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

private data class StarSpec(
    val xf: Float, val yf: Float, val r: Float,
    val warm: Boolean, val cool: Boolean,
    val phase: Float, val speed: Float, val flare: Boolean,
)

private fun DrawScope.drawStars(
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

private fun DrawScope.drawOrbitLayer(g: Geo) {
    g.radiiPx.forEach { R ->
        drawArc(
            Color(0xFFBEC8E1).copy(alpha = 0.16f),
            0f, 360f, false,
            topLeft = Offset(g.cx - R, g.cy - R * g.q),
            size = Size(R * 2f, R * g.q * 2f),
            style = Stroke(1.2f * g.s),
        )
    }
}

private fun DrawScope.drawAnchorTicks(g: Geo, items: List<OrbitTask>, labelFade: Float) {
    anchorPaint.textSize = 9.5f * g.s
    g.radiiPx.forEachIndexed { i, R ->
        val y = g.cy - R * g.q
        drawLine(Color(0x4DD8DEEE), Offset(g.cx, y - 4f * g.s), Offset(g.cx, y + 4f * g.s), strokeWidth = 1f * g.s)
        val label = items.filter { it.ring == i }.joinToString(" / ") { it.anchorLabel }
        if (label.isNotBlank()) {
            drawTextNc(label, g.cx, y - 9f * g.s, anchorPaint, DimColor.copy(alpha = 0.85f).toArgb())
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
    images: Map<Long, ImageBitmap>,
    dimmed: Boolean,
    fill: Float,
) {
    val p = planetPos(g, t)
    if (timeMs % 2000L < 20L) {
        Log.d(
            "OrbitCanvas",
            "planet \"${t.name}\" frac=${t.frac} ring=${t.ring} R=${g.radiiPx[t.ring]} pos=(${p.x}, ${p.y}) size=${g.w}x${g.h} nearness=${p.nearness}",
        )
    }
    val aDeg = Math.toDegrees(planetAngle(t.frac).toDouble()).toFloat()
    val dim = if (dimmed) 1f - 0.62f * z else 1f
    val depthA = 0.72f + 0.28f * p.nearness
    val sel = selected?.id == t.id
    val done = t.state == PlanetState.DONE
    val late = t.state == PlanetState.LATE
    val R = g.radiiPx[t.ring]
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
                topLeft = ovalTopLeft, size = ovalSize, style = Stroke(1.6f * g.s),
            )
        }
    } else {
        drawArc(
            hsla(t.hue, 0.5f, 0.58f, arcAlpha), -90f, aDeg, false,
            topLeft = ovalTopLeft, size = ovalSize, style = Stroke(1.5f * g.s),
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
                style = Stroke(maxOf(0.8f * g.s, p.r * 0.55f * (1f - f0))),
            )
        }
    }

    // 状态环：临近到期 = 琥珀脉冲；逾期 = 暗红；待办 = 星图描边圈（保证黑白星可见）
    if (t.state == PlanetState.NEAR) {
        val pulse = 0.3f + 0.32f * (0.5f + 0.5f * sin(timeMs / 500f + t.ring * 2f))
        drawCircle(hsla(40, 0.85f, 0.62f, pulse * dim), p.r + 5f * g.s, Offset(p.x, p.y), style = Stroke(1.3f * g.s))
    }
    if (late) {
        drawCircle(LateColor.copy(alpha = 0.5f * dim), p.r + 5f * g.s, Offset(p.x, p.y), style = Stroke(1.2f * g.s))
    }
    if (t.state == PlanetState.PENDING) {
        drawCircle(Color(0x59C4CCDE).copy(alpha = 0.4f * dim), p.r + 2.5f * g.s, Offset(p.x, p.y), style = Stroke(1f * g.s))
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
    } + if (sel) 0.3f * z else 0f).coerceIn(0f, 1f)

    val bmp = images[t.id]
    if (bmp != null) {
        val paint = if (done) planetPaintNormal else planetPaintGray
        paint.alpha = (alpha * 255).toInt()
        drawContext.canvas.nativeCanvas.drawBitmap(
            bmp.asAndroidBitmap(), null,
            RectF(p.x - p.r, p.y - p.r, p.x + p.r, p.y + p.r),
            paint,
        )
        // 长按打卡填充：从下往上恢复行星原本的颜色（仅聚焦选中且未完成时）
        if (sel && !done && fill > 0f) {
            planetPaintNormal.alpha = (alpha * 255).toInt()
            clipRect(
                left = p.x - p.r,
                top = p.y + p.r - 2f * p.r * fill,
                right = p.x + p.r,
                bottom = p.y + p.r,
            ) {
                drawContext.canvas.nativeCanvas.drawBitmap(
                    bmp.asAndroidBitmap(), null,
                    RectF(p.x - p.r, p.y - p.r, p.x + p.r, p.y + p.r),
                    planetPaintNormal,
                )
            }
        }
    } else {
        drawProceduralPlanet(p.x, p.y, p.r, t.hue, done, alpha)
        if (sel && !done && fill > 0f) {
            clipRect(
                left = p.x - p.r,
                top = p.y + p.r - 2f * p.r * fill,
                right = p.x + p.r,
                bottom = p.y + p.r,
            ) {
                drawProceduralPlanet(p.x, p.y, p.r, t.hue, true, alpha)
            }
        }
    }
    // 液态玻璃质感：球面暗角 + 边缘亮环（高光斑移除——聚焦放大后会显形为一个圆叠在行星上）
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0x00000000), Color(0x59000000)),
            center = Offset(p.x, p.y), radius = p.r,
        ),
        p.r, Offset(p.x, p.y),
    )
    drawCircle(Color(0x59FFFFFF), p.r, Offset(p.x, p.y), style = Stroke(1.1f * g.s))

    // 名字注记（衬线 + 投影）
    val nameColor = if (done)
        hsla(t.hue, 0.45f, 0.8f, 0.95f * maxOf(dim, 0.5f))
    else
        Color(0xFFC4CCDE).copy(alpha = 0.85f * maxOf(dim, 0.5f))
    val nameScale = minOf(g.zoom, 2.6f)
    namePaint.textSize = 11.5f * g.s * nameScale
    namePaint.setShadowLayer(5f * g.s * nameScale, 0f, 1f * g.s * nameScale, 0xE604060C.toInt())
    val labelFade = (1f - z * 0.9f).coerceIn(0.12f, 1f)
    drawTextNc(t.name, p.x, p.y + p.r + 14f * g.s * nameScale, namePaint, nameColor.copy(alpha = nameColor.alpha * labelFade).toArgb())
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
