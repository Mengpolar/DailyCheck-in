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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

/** 几何：以 dp 为单位（Canvas 内按屏幕密度整体缩放） */
private class Geo(
    val w: Float, val h: Float, val cx: Float, val cy: Float,
    val q: Float, val radiiPx: List<Float>, val s: Float,
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

private fun planetAngle(frac: Float) = frac * 2f * Math.PI.toFloat()

private class PlanetPos(val x: Float, val y: Float, val nearness: Float, val r: Float)

private fun planetPos(g: Geo, t: OrbitTask, zf: Float = 1f): PlanetPos {
    val a = planetAngle(t.frac) + t.jitterDeg * Math.PI.toFloat() / 180f
    val R = g.radiiPx[t.ring] * zf
    val x = g.cx + sin(a) * R
    val y = g.cy - cos(a) * R * g.q
    val nearness = (1f - cos(a)) / 2f
    val pr = (if (t.ring == 0) 18f else if (t.ring == 1) 20f else 22f) * g.s * (0.85f + 0.35f * nearness)
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

    // 点击星光：触摸处留下随机颜色的四芒星光，5 秒淡出
    val sparks = remember { mutableStateListOf<Spark>() }
    val sparkRnd = remember { Random(System.nanoTime()) }
    val addSpark: (Float, Float) -> Unit = { x, y ->
        val now = System.nanoTime() / 1_000_000L  // 与帧时钟同基准（uptime ms）
        sparks.removeAll { now - it.born > 5200 }
        sparks.add(Spark(x, y, sparkRnd.nextInt(360), sparkRnd.nextFloat(), now))
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
                        val g = computeGeo(viewSize.width.toFloat(), viewSize.height.toFloat(), screenDensity)
                        val tx = tap.x; val ty = tap.y
                        // 弧形导航区域内的 tap 交给弧自身处理，不生成星光
                        if (tap.y < size.height - 62f * screenDensity) addSpark(tap.x, tap.y)
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
            val g = computeGeo(size.width, size.height, d)
            val z = zoom.value
            val zf = 1f + 0.05f * z

            try {
                // 背景图：原始像素坐标绘制（不经 scale 变换，规避 dst 单位歧义），cover 铺满 + 视差
                val bg = bgImage
                if (bg != null) {
                    val bw = bg.width.toFloat(); val bh = bg.height.toFloat()
                    val cover = maxOf(size.width / bw, size.height / bh) * 1.16f
                    val dw = bw * cover; val dh = bh * cover
                    val marginX = (dw - size.width) / 2f; val marginY = (dh - size.height) / 2f
                    val left = (size.width - dw) / 2f - parallax.x * d
                    val top = (size.height - dh) / 2f - parallax.y * d
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
                drawStars(g, stars, timeMs, Offset(parallax.x * 1.3f, parallax.y * 1.3f), 1f - 0.3f * z)

                // 星系层：聚焦缩放通过轨道半径放大实现（无变换层，规避设备差异）
                drawOrbitLayer(g, zf)
                drawAnchorTicks(g, state.items, zf)
                state.items.sortedBy { planetPos(g, it, zf).nearness }.forEach { t ->
                    drawTask(g, t, timeMs, z, zf, selected, planetImages, dimmed = selected != null && selected.id != t.id)
                }

                // 点击星光（最上层）
                drawSparks(g, sparks, timeMs)
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

        // 中心：聚焦时展示详情卡（未聚焦 = 纯星空）
        if (selected != null) {
            FocusCard(
                t = selected,
                zoom = zoom.value,
                onCheck = { doCheck(selected) },
                onOpenRecords = { onOpenRecords(selected.id) },
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 诊断行（定位渲染问题用，确认正常后移除）
        Text(
            "画布 ${viewSize.width}x${viewSize.height}px · 帧 ${frame / 1_000_000} · 任务 ${state.items.size} · 背景${if (bgImage != null) "√" else "×"} · 中心(${diagGeo.cx.toInt()},${diagGeo.cy.toInt()}) 轨道${diagGeo.radiiPx.map { (it / screenDensity).toInt() }}dp",
            color = Color(0xFF9BA5B8), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp),
        )

        // 底部弧形导航：上滑展开 设置/添加，下滑收起
        ArcNavBar(
            onAdd = onAdd,
            onOpenSettings = onOpenSettings,
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
    // 外层为触摸热区盒：比视觉月面高 64dp，弧上方的轻点/上滑也能命中
    Box(
        modifier
            .fillMaxWidth()
            .height(heightDp + 64.dp)
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
            },
    ) {
        // 视觉月面带：对齐热区盒底部
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(heightDp)
                .align(Alignment.BottomCenter)
        ) {
            val d = this.density
            val w = size.width; val h = size.height
            // 弧度恒定的月面：曲线相对盒顶形状不变，整条弧随盒刚性上移（盒随屏底对齐）
            val rise = h - 40f * d
            val edgeY = h - 18f * d - rise
            val apexY = h - 40f * d - rise
            val ctrlY = 2f * apexY - edgeY
            val curve = Path().apply {
                moveTo(0f, edgeY)
                quadraticBezierTo(w / 2f, ctrlY, w, edgeY)
            }
            // 月面：灰色渐变带
            val band = Path().apply {
                moveTo(0f, h)
                lineTo(0f, edgeY)
                quadraticBezierTo(w / 2f, ctrlY, w, edgeY)
                lineTo(w, h)
                close()
            }
            drawPath(
                band,
                Brush.verticalGradient(
                    listOf(Color(0xFF3E4552), Color(0xFF1A1F28), Color(0xFF0D1016)),
                    startY = edgeY, endY = h,
                ),
            )
            // 月面边缘光（晕 + 亮线）
            drawPath(
                curve,
                Brush.horizontalGradient(
                    listOf(Color(0x1FD8DEEE), Color(0x59E8ECF4), Color(0x1FD8DEEE)),
                    startX = 0f, endX = w,
                ),
                style = Stroke(5f),
            )
            drawPath(curve, Color(0x99E8ECF4), style = Stroke(1.2f))
            // 陨石坑（沿曲线均匀分布）
            listOf(0.14f, 0.30f, 0.5f, 0.70f, 0.86f).forEachIndexed { i, t0 ->
                val mt = 1f - t0
                val cx = 2f * mt * t0 * (w / 2f) + t0 * t0 * w
                val cy = mt * mt * edgeY + 2f * mt * t0 * ctrlY + t0 * t0 * edgeY
                val cr = (3.5f + (i % 3) * 2.2f) * d
                drawCircle(Color(0x66222A35), cr, Offset(cx, cy + cr * 0.4f))
                drawCircle(Color(0x4D5A6478), cr, Offset(cx, cy + cr * 0.4f), style = Stroke(1f))
            }
        }
        val clickable = progress > 0.5f
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (12.dp + 60.dp * progress))
                .graphicsLayer { alpha = progress; translationY = (1f - progress) * 40f },
            horizontalArrangement = Arrangement.spacedBy(64.dp),
        ) {
            IconButton(onClick = onAdd, enabled = clickable) {
                Icon(Icons.Rounded.Add, "添加任务", tint = GoldColor)
            }
            IconButton(onClick = onOpenSettings, enabled = clickable) {
                Icon(Icons.Rounded.Settings, "设置", tint = DimColor)
            }
        }
    }
}

private data class Spark(
    val x: Float, val y: Float,
    val hue: Int, val seed: Float,
    val born: Long, // uptime ms，与帧时钟同基准
)

private fun DrawScope.drawSparks(g: Geo, sparks: List<Spark>, timeMs: Long) {
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
        // 四芒射线（水平/垂直长，对角短）
        val rayL = base * (1.1f + 0.5f * tw)
        val rayS = base * 0.45f
        val stroke = 1.4f * g.s
        drawLine(c.copy(alpha = 0.9f * alpha), Offset(s.x - rayL, s.y), Offset(s.x + rayL, s.y), stroke)
        drawLine(c.copy(alpha = 0.9f * alpha), Offset(s.x, s.y - rayL), Offset(s.x, s.y + rayL), stroke)
        drawLine(c.copy(alpha = 0.55f * alpha), Offset(s.x - rayS, s.y - rayS), Offset(s.x + rayS, s.y + rayS), stroke * 0.8f)
        drawLine(c.copy(alpha = 0.55f * alpha), Offset(s.x - rayS, s.y + rayS), Offset(s.x + rayS, s.y - rayS), stroke * 0.8f)
        // 白核
        drawCircle(Color.White.copy(alpha = alpha), (0.16f * base + 0.6f * g.s), center)
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
        drawCircle(color.copy(alpha = tw * 0.85f * dimF), s.r * g.s, Offset(x, y))
        if (s.flare) {
            val l = s.r * 5f * g.s
            drawLine(color.copy(alpha = tw * 0.5f * dimF), Offset(x - l, y), Offset(x + l, y), strokeWidth = 0.8f * g.s)
            drawLine(color.copy(alpha = tw * 0.5f * dimF), Offset(x, y - l), Offset(x, y + l), strokeWidth = 0.8f * g.s)
        }
    }
}

private fun DrawScope.drawOrbitLayer(g: Geo, zf: Float) {
    g.radiiPx.forEach { R ->
        drawArc(
            Color(0xFFBEC8E1).copy(alpha = 0.16f * (1f - 0.35f * zf)),
            0f, 360f, false,
            topLeft = Offset(g.cx - R, g.cy - R * g.q),
            size = Size(R * 2f, R * g.q * 2f),
            style = Stroke(1.2f * g.s),
        )
    }
}

private fun DrawScope.drawAnchorTicks(g: Geo, items: List<OrbitTask>, zf: Float) {
    anchorPaint.textSize = 9.5f * g.s
    g.radiiPx.forEachIndexed { i, R ->
        val y = g.cy - R * zf * g.q
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
    zf: Float,
    selected: OrbitTask?,
    images: Map<Long, Bitmap>,
    dimmed: Boolean,
) {
    val p = planetPos(g, t, zf)
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
    val R = g.radiiPx[t.ring] * zf
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

    // 选中：星表十字准星
    if (sel) {
        val gap = p.r + 9f * g.s
        val l = (5f + sin(timeMs / 900f) * 1.2f) * g.s
        val c = Color(0xFFD8DEEE).copy(alpha = 0.75f * (0.4f + 0.6f * z))
        listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f).forEach { (dx, dy) ->
            drawLine(c, Offset(p.x + dx * (gap - l), p.y + dy * (gap - l)), Offset(p.x + dx * (gap + l), p.y + dy * (gap + l)), 1f * g.s)
        }
        drawCircle(Color(0xFFD8DEEE).copy(alpha = 0.22f * z), gap, Offset(p.x, p.y), style = Stroke(1f * g.s))
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
    namePaint.textSize = 11.5f * g.s
    namePaint.setShadowLayer(5f * g.s, 0f, 1f * g.s, 0xE604060C.toInt())
    drawTextNc(t.name, p.x, p.y + p.r + 14f * g.s, namePaint, nameColor.toArgb())
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
