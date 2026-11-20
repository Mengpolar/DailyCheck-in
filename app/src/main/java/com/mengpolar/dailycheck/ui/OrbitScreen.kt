package com.mengpolar.dailycheck.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import com.mengpolar.dailycheck.R
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.graphics.drawscope.clipPath
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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpolar.dailycheck.data.CheckRecord
import com.mengpolar.dailycheck.data.OrbitTask
import com.mengpolar.dailycheck.data.OrbitUiState
import com.mengpolar.dailycheck.data.PlanetState
import com.mengpolar.dailycheck.data.weekdayLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
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

/** 长按打卡所需时长（秒）：按住每秒 +1，松手每秒 -1 */
private const val CHECK_HOLD_S = 3f

/** 聚焦页按钮默认底色（灰黑）与长按填充的淡金 */
private val BtnBg = Color(0xB3101214)
private val HoldGold = Color(0xFFDFBE7E)

/** 几何：以 dp 为单位（Canvas 内按屏幕密度整体缩放） */
private class Geo(
    val w: Float, val h: Float, val cx: Float, val cy: Float,
    val q: Float, val radiiPx: List<Float>, val s: Float,
    /** 相机缩放（1 = 全景），聚焦时 = 目标倍率 × 聚焦进度 */
    val zoom: Float = 1f,
    /** 行星尺寸系数：轨道盘被压缩时行星随之略微缩小 */
    val planetScale: Float = 1f,
) {
    /** dp 常数转 px */
    fun dp(v: Float) = v * s
}

/** 轨道半径序列（px）：单轨道放大；多轨道 150dp 起步、60dp 间距；放不下整体等比缩小 */
private fun orbitRadii(wPx: Float, hPx: Float, d: Float, orbitCount: Int): List<Float> {
    if (orbitCount <= 0 || wPx <= 0f || hPx <= 0f) return emptyList()
    val s = d
    val capX = wPx / 2f - 26f * s
    val capY = hPx / 2f * 0.92f - 26f * s
    val cap = minOf(capX, capY)
    return when {
        orbitCount == 1 -> listOf(cap * 0.60f)
        else -> {
            val outerDp = 150f + 60f * (orbitCount - 1)
            val scale = minOf(1f, cap / (outerDp * s))
            (0 until orbitCount).map { i -> (150f + 60f * i) * scale * s }
        }
    }
}

/** 半径插值：长度不同时以末值补位（新增轨道自上一外圈滑出，移除时滑回补位） */
private fun lerpRadii(from: List<Float>, to: List<Float>, f: Float): List<Float> {
    val n = maxOf(from.size, to.size)
    if (n == 0) return emptyList()
    return (0 until n).map { i ->
        val a = from.getOrElse(i) { from.lastOrNull() ?: to.lastOrNull() ?: 0f }
        val b = to.getOrElse(i) { to.lastOrNull() ?: from.lastOrNull() ?: 0f }
        a + (b - a) * f
    }
}

private fun computeGeo(wPx: Float, hPx: Float, d: Float, orbitCount: Int, tiltQ: Float): Geo {
    val s = d
    val radii = orbitRadii(wPx, hPx, d, orbitCount)
    val planetScale = if (orbitCount <= 1) 1f
        else (radii.lastOrNull()?.let { it / ((150f + 60f * (orbitCount - 1)) * s) } ?: 1f).coerceIn(0.62f, 1f)
    return Geo(wPx, hPx, wPx / 2f, hPx / 2f, tiltQ.coerceIn(0.16f, 1f), radii, s, planetScale = planetScale)
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
    planetScale = g.planetScale,
)

private fun planetAngle(frac: Float) = frac * 2f * Math.PI.toFloat()

private class PlanetPos(val x: Float, val y: Float, val nearness: Float, val r: Float)

private fun planetPos(g: Geo, t: OrbitTask): PlanetPos {
    val a = planetAngle(t.frac)
    // 布局过渡帧可能存在半径表短暂滞后，越界时回退到最外圈，避免绘制崩溃
    val R = g.radiiPx.getOrElse(t.ring) { g.radiiPx.lastOrNull() ?: 0f }
    val x = g.cx + sin(a) * R
    val y = g.cy - cos(a) * R * g.q
    val nearness = (1f - cos(a)) / 2f
    val pr = 18f * g.planetScale * g.s * g.zoom * (0.85f + 0.35f * nearness)
    return PlanetPos(x, y, nearness, pr)
}

private fun hsla(hue: Int, sat: Float, light: Float, alpha: Float) =
    Color.hsl(hue.toFloat(), sat, light, alpha)

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

@Composable
fun OrbitScreen(
    state: OrbitUiState,
    showPlanetNames: Boolean,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecords: (Long) -> Unit,
    onCheck: (Long) -> CheckRecord,
    onEdit: (Long) -> Unit,
    onDeleteTask: (Long) -> Unit,
) {
    val context = LocalContext.current
    val screenDensity = LocalDensity.current.density
    var selectedId by remember { mutableStateOf<Long?>(null) }
    // 月球导航展开状态（提升到顶层：空白点击收起 / 聚焦时自动收起）
    var arcTarget by remember { mutableFloatStateOf(0f) }
    // 行星筛选：0=全部 1=未完成 2=已完成（月球导航筛选键循环切换）
    var filterMode by remember { mutableIntStateOf(0) }
    // 按筛选模式取可见任务；显示环序按可见集合重排（隐藏/恢复时轨道补位让位）
    val visibleTasks = remember(state.items, filterMode) {
        when (filterMode) {
            1 -> state.items.filter { it.state != PlanetState.DONE }
            2 -> state.items.filter { it.state == PlanetState.DONE }
            else -> state.items
        }
    }
    val displayItems = remember(visibleTasks) {
        visibleTasks.mapIndexed { i, t -> t.copy(ring = i) }
    }
    // 选中任务按显示集取（筛选重排环序后，镜头/命中须与绘制一致）
    val selected = displayItems.firstOrNull { it.id == selectedId }
    // 相机锚点任务：退焦后 selected 变 null，镜头仍需围绕最后选中的行星缩小（动画期间保持锚点不丢）
    var recentSelected by remember { mutableStateOf<OrbitTask?>(null) }
    if (selected != null) recentSelected = selected
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // 帧时钟：驱动星空闪烁 / 琥珀脉冲 / 行星实时位置
    val frame by produceState(0L) {
        while (true) withFrameNanos { value = it }
    }
    val timeMs = frame / 1_000_000

    // 聚焦缩放动画：推近 ~1.2s / 退焦 ~0.9s，缓入缓出（行星自中心放大 / 缩小）
    val zoom = remember { Animatable(0f) }
    // 设备姿态（陀螺仪）：驱动聚焦行星的质感与轻微翻转
    val deviceTilt by rememberTilt()
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
    // 碎裂贴图（polie.png 白底裂纹 → 黑色裂纹透明贴图）：长按删除时叠加在行星上
    val crackImage = remember {
        runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.polie)?.let { processCrackTex(it) }?.asImageBitmap()
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

    // 轨道盘倾角：0.20（侧视）/ 0.62（斜视）/ 1.0（俯视）三档；
    // 空白区上下滑动自由调节，松手按速度惯性滑行后弹簧吸附到最近档位
    val tiltAnim = remember { Animatable(0.62f) }
    val tiltDetents = remember { floatArrayOf(0.20f, 0.62f, 1f) }
    val tiltScope = rememberCoroutineScope()
    var tiltDragJob by remember { mutableStateOf<Job?>(null) }
    var tiltVelocity by remember { mutableFloatStateOf(0f) }
    var tiltLastUptime by remember { mutableStateOf(0L) }
    var tiltLastQ by remember { mutableFloatStateOf(0.62f) }

    // 轨道布局过渡：任务增删 / 筛选切换 / 盘面缩放时，轨道半径旧→新平滑插值
    val targetRadii = remember(viewSize, displayItems.size) {
        orbitRadii(viewSize.width.toFloat(), viewSize.height.toFloat(), screenDensity, displayItems.size)
    }
    var settledRadii by remember { mutableStateOf<List<Float>?>(null) }
    var animFrom by remember { mutableStateOf<List<Float>?>(null) }
    var animTo by remember { mutableStateOf<List<Float>?>(null) }
    val orbitAnim = remember { Animatable(1f) }
    LaunchedEffect(targetRadii) {
        val from = settledRadii
        if (from == null || from == targetRadii) {
            settledRadii = targetRadii
            orbitAnim.snapTo(1f)
            return@LaunchedEffect
        }
        animFrom = from
        animTo = targetRadii
        orbitAnim.snapTo(0f)
        orbitAnim.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        settledRadii = targetRadii
    }

    // 新增行星登场时刻 / 消散残影 / 上一帧可见任务快照
    var spawnedAt by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var dissolving by remember { mutableStateOf(listOf<DissolvingPlanet>()) }
    var prevDisplay by remember { mutableStateOf<List<OrbitTask>>(emptyList()) }
    LaunchedEffect(state.items) {
        // 选中任务被删除时自动退焦，避免聚焦卡引用已移除任务
        val ids = state.items.map { it.id }.toSet()
        if (selectedId != null && ids.none { it == selectedId }) selectedId = null
    }
    LaunchedEffect(displayItems) {
        val nowMs = withFrameNanos { it } / 1_000_000
        val newIds = displayItems.map { it.id to it.ring }.toSet()
        val prevIds = prevDisplay.map { it.id to it.ring }.toSet()
        // 新登场（新任务 / 筛选后重新可见 / 环序变化）：重新播放入场动画
        val added = displayItems.filter { (it.id to it.ring) !in prevIds }
        if (added.isNotEmpty()) {
            spawnedAt = spawnedAt + added.associate { it.id to nowMs }
        }
        spawnedAt = spawnedAt.filterKeys { id -> displayItems.any { it.id == id } }
        // 消失（删除 / 被筛选隐藏 / 环序变化让位）：旧位置放大渐隐
        val gone = prevDisplay.filter { (it.id to it.ring) !in newIds }
        if (gone.isNotEmpty()) {
            dissolving = dissolving + gone.map { t ->
                DissolvingPlanet(frac = t.frac, hue = t.hue, ring = t.ring, born = nowMs)
            }
        }
        prevDisplay = displayItems
    }

    // 拖尾星光粒子：已完成行星的彗尾上随机生成，向拖尾末端漂移并渐隐
    val tailStars = remember { mutableStateListOf<TailStar>() }
    LaunchedEffect(displayItems) {
        while (true) {
            withFrameNanos { frameNanos ->
                val nowMs = frameNanos / 1_000_000
                tailStars.removeAll { nowMs - it.born > it.life }
                dissolving = dissolving.filter { nowMs - it.born < 1000 }
                if (tailStars.size < 90) {
                    displayItems.forEach { t ->
                        if (t.state == PlanetState.DONE && t.tail > 0.04f && Random.nextFloat() < 0.03f) {
                            tailStars.add(
                                TailStar(
                                    taskId = t.id,
                                    born = nowMs,
                                    life = Random.nextInt(1400, 2800).toLong(),
                                    hue = Random.nextInt(360),
                                    seed = Random.nextFloat(),
                                    dir0 = Random.nextFloat() * 0.25f,
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    // 诊断几何（与 Canvas 内一致）
    val diagGeo = remember(viewSize, state.items.size) {
        computeGeo(viewSize.width.toFloat(), viewSize.height.toFloat(), screenDensity, state.items.size, tiltAnim.value)
    }

    // 背景图在 Composable 层用 Image 绘制；此处移除位图解码逻辑

    // 长按打卡：按住填充（每秒 +1），松手渐退（每秒 -1），满 3 秒完成打卡并点亮行星
    // 长按删除：聚焦信息卡右侧删除钮，同填充动画但为红色，满 3 秒删除任务（无撤销）
    var holdDown by remember { mutableStateOf(false) }
    val holdProgress = remember { mutableFloatStateOf(0f) }
    var delDown by remember { mutableStateOf(false) }
    val delProgress = remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(selectedId) {
        holdDown = false
        holdProgress.floatValue = 0f
        delDown = false
        delProgress.floatValue = 0f
        if (selectedId == null) return@LaunchedEffect
        val task = state.items.firstOrNull { it.id == selectedId } ?: return@LaunchedEffect
        var last = withFrameNanos { it }
        var logTick = 0
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
            last = now
            val hn = if (holdDown) holdProgress.floatValue + dt else holdProgress.floatValue - dt
            holdProgress.floatValue = hn.coerceIn(0f, CHECK_HOLD_S)
            val dn = if (delDown) delProgress.floatValue + dt else delProgress.floatValue - dt
            delProgress.floatValue = dn.coerceIn(0f, CHECK_HOLD_S)
            if (++logTick % 30 == 0) Log.d("OrbitCanvas", "hold p=${holdProgress.floatValue} down=$holdDown d=${delProgress.floatValue} deldown=$delDown")
            if (holdDown && holdProgress.floatValue >= CHECK_HOLD_S) {
                holdDown = false
                holdProgress.floatValue = 0f
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onCheck(task.id)
            }
            if (delDown && delProgress.floatValue >= CHECK_HOLD_S) {
                delDown = false
                delProgress.floatValue = 0f
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDeleteTask(task.id)
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
                .pointerInput(Unit) {
                    // 空白区上下滑动：轨道盘绕 X 轴倾角（中心固定）；松手惯性滑行后吸附档位
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val target = (tiltAnim.value - dragAmount / 550f).coerceIn(0.16f, 1f)
                            val nowMs = System.nanoTime() / 1_000_000L
                            val dt = (nowMs - tiltLastUptime).coerceAtLeast(1L)
                            val instant = -(target - tiltLastQ) / (dt / 1000f)
                            tiltVelocity = tiltVelocity * 0.55f + instant * 0.45f
                            tiltLastUptime = nowMs
                            tiltLastQ = target
                            tiltDragJob?.cancel()
                            tiltDragJob = tiltScope.launch { tiltAnim.snapTo(target) }
                        },
                        onDragEnd = {
                            // 速度惯性滑行 0.12s 的投影位置 → 最近档位，弹簧带初速吸附
                            val projected = (tiltAnim.value + tiltVelocity * 0.12f).coerceIn(0.16f, 1f)
                            val detent = tiltDetents.minByOrNull { abs(it - projected) } ?: tiltAnim.value
                            tiltDragJob?.cancel()
                            tiltDragJob = tiltScope.launch {
                                tiltAnim.animateTo(
                                    detent,
                                    spring(dampingRatio = 0.85f, stiffness = 280f),
                                    initialVelocity = tiltVelocity,
                                )
                            }
                            tiltVelocity = 0f
                        },
                        onDragCancel = {
                            val detent = tiltDetents.minByOrNull { abs(it - tiltAnim.value) } ?: tiltAnim.value
                            tiltDragJob?.cancel()
                            tiltDragJob = tiltScope.launch {
                                tiltAnim.animateTo(detent, spring(dampingRatio = 0.9f, stiffness = 300f))
                            }
                            tiltVelocity = 0f
                        },
                    )
                }
                .pointerInput(displayItems, viewSize) {
                    detectTapGestures { tap ->
                        if (viewSize == IntSize.Zero) return@detectTapGestures
                        Log.d("OrbitCanvas", "main tap=(${tap.x},${tap.y}) sparkZone=${tap.y < size.height - 100f * screenDensity} canvasSize=${size.width}x${size.height}")
                        val g0 = computeGeo(viewSize.width.toFloat(), viewSize.height.toFloat(), screenDensity, displayItems.size, tiltAnim.value)
                        // 相机状态与绘制一致（行星先原地放大、中后段滑向屏心）。
                        // 注意：pointerInput 的闭包可能持有旧组合的 selected，这里按 selectedId 现取，保证命中测试与绘制一致
                        val selNow = displayItems.firstOrNull { it.id == selectedId } ?: recentSelected
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
                        displayItems.forEach { t ->
                            val p = planetPos(g, t)
                            val dist = hypot((tx - p.x).toDouble(), (ty - p.y).toDouble()).toFloat()
                            if (dist < p.r + 30f * screenDensity && dist < bd) { bd = dist; best = t }
                        }
                        // 已聚焦时行星吞没整屏、无空白可点：再点选中行星本身 = 退焦；未聚焦时点行星 = 聚焦
                        selectedId = if (best != null && best.id == selectedId) null else best?.id
                        // 聚焦发生时自动收起月球导航；展开状态下点击空白区域也收起
                        if (selectedId != null && arcTarget > 0.5f) arcTarget = 0f
                        if (best == null && zoom.value <= 0.05f && arcTarget > 0.5f) arcTarget = 0f
                        // 弧形导航区域内的 tap 交给弧自身处理；点中行星时不生成星光（避免放大的行星表面残留星点）
                        if (best == null && tap.y < size.height - 100f * screenDensity) addSpark(tap.x, tap.y)
                    }
                }
        ) {
            val d = this.density
            val g0raw = computeGeo(size.width, size.height, d, displayItems.size, tiltAnim.value)
            // 布局过渡中：以插值半径重排几何（新增轨道滑出 / 移除补位）
            val g0 = if (orbitAnim.value < 1f && animFrom != null && animTo != null) {
                Geo(
                    g0raw.w, g0raw.h, g0raw.cx, g0raw.cy, g0raw.q,
                    lerpRadii(animFrom!!, animTo!!, orbitAnim.value), g0raw.s,
                    planetScale = g0raw.planetScale,
                )
            } else g0raw
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

                // 星系层：轨道半径已含相机缩放（轨道线弱化，仅作存在暗示）
                drawOrbitLayer(g)
                // 退焦动画期间（zoom 未归零）沿用最近选中继续暗化其他行星，实现逐渐恢复
                val dimRef = selected ?: recentSelected.takeIf { zoom.value > 0.05f }
                displayItems.sortedBy { planetPos(g, it).nearness }.forEach { t ->
                    drawTask(
                        g, t, timeMs, z, selected, planetImages,
                        tilt = deviceTilt,
                        showName = showPlanetNames,
                        dimmed = dimRef != null && dimRef.id != t.id,
                        spawn = ((timeMs - (spawnedAt[t.id] ?: 0L)).coerceAtLeast(0L))
                            .toFloat().coerceIn(0f, 550f) / 550f,
                        delFill = if (selected?.id == t.id)
                            (delProgress.floatValue / CHECK_HOLD_S).coerceIn(0f, 1f) else 0f,
                        crackTex = crackImage,
                        fill = if (selected?.id == t.id && selected.state != PlanetState.DONE)
                            (holdProgress.floatValue / CHECK_HOLD_S).coerceIn(0f, 1f) else 0f,
                    )
                }

                // 消散残影：任务移除/过期后放大渐隐
                dissolving.forEach { dz ->
                    val age = ((timeMs - dz.born) / 1000f).coerceIn(0f, 1f)
                    if (age < 1f) {
                        val R = g.radiiPx.getOrElse(dz.ring) { g.radiiPx.lastOrNull() ?: 0f }
                        if (R > 0f) {
                            val a = planetAngle(dz.frac)
                            val rr = 18f * g.planetScale * g.s * (1f + 1.2f * age * age)
                            val x = g.cx + sin(a) * R
                            val y = g.cy - cos(a) * R * g.q
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(hsla(dz.hue, 0.5f, 0.7f, 0.5f * (1f - age)), Color.Black.copy(alpha = 0f)),
                                    center = Offset(x, y), radius = rr * 1.7f,
                                ),
                                rr * 1.7f, Offset(x, y),
                            )
                            drawCircle(hsla(dz.hue, 0.4f, 0.75f, 0.6f * (1f - age)), rr, Offset(x, y), style = Stroke(1.2f * g.s))
                        }
                    }
                }

                // 拖尾星光粒子：沿轨道向拖尾末端漂移、渐隐（仅当前可见行星）
                drawTailStars(g, displayItems, tailStars, timeMs, sparkStar)

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
                PlanetState.LATE -> TermRed
                PlanetState.NEAR -> TermBlueBright
                else -> TermInk
            }

            // 顶部信息卡（票根式）：左侧数据区 | 虚线分隔 | 右侧长按删除区；聚焦时从上往下出现，退焦反向
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 44.dp, start = 18.dp, end = 18.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = pa
                        translationY = -(1f - pa) * 90f
                    }
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xB3101214))
                    .border(1.dp, TermBlueDim.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    // 仅占位命中路径挡住透传（空 handler 不消费事件，子删除区才能收到长按）
                    .pointerInput(Unit) { },
            ) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Column(Modifier.weight(1f).padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Text(
                            t.name,
                            color = TermInk, fontSize = 20.sp, fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp, maxLines = 1,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${t.cycleLabel} · 已完成 ${t.recordCount} 个周期",
                            color = TermBlueDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (remainingMs > 0L) "本周期还剩 ${formatRemaining(remainingMs)}" else "本周期已结束 · 即将重置",
                            color = remainColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                        )
                        if (t.note.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(t.note, color = TermBlueDim, fontSize = 12.sp, maxLines = 2, fontFamily = FontFamily.Monospace)
                        }
                    }
                    // 票根虚线打孔分隔
                    Canvas(Modifier.fillMaxHeight().width(1.dp)) {
                        drawLine(
                            TermBlueDim.copy(alpha = 0.55f),
                            Offset(size.width / 2, 10.dp.toPx()),
                            Offset(size.width / 2, size.height - 10.dp.toPx()),
                            strokeWidth = 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                        )
                    }
                    // 长按 3 秒删除任务（整个票根右侧区域可按）：红色从左往右填充，完成后轨道行星消散，无撤销
                    val delFill = (delProgress.floatValue / CHECK_HOLD_S).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .width(84.dp)
                            .fillMaxHeight()
                            .pointerInput(t.id) {
                                detectTapGestures(
                                    onPress = {
                                        delDown = true
                                        try { awaitRelease() } finally { delDown = false }
                                    },
                                )
                            },
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            if (delFill > 0f) {
                                drawRect(
                                    TermRed,
                                    topLeft = Offset.Zero,
                                    size = Size(size.width * delFill, size.height),
                                )
                            }
                        }
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "长按删除任务",
                            tint = lerp(TermRed, Color.White, delFill),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(20.dp),
                        )
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
                            .clip(RoundedCornerShape(10.dp))
                            .background(BtnBg)
                            .border(1.dp, TermBlueDim.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .clickable { onEdit(t.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("编辑", color = TermBlue, fontSize = 14.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                    }
                    Box(
                        Modifier
                            .weight(1.4f).height(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(BtnBg)
                            .border(
                                1.dp,
                                if (fillFrac > 0f) TermBlue.copy(alpha = 0.6f) else TermBlueDim.copy(alpha = 0.5f),
                                RoundedCornerShape(10.dp),
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
                        // 长按填充层：从左往右逐渐铺满天蓝
                        Canvas(Modifier.matchParentSize()) {
                            if (fillFrac > 0f) {
                                drawRect(TermBlue, size = Size(size.width * fillFrac, size.height))
                            }
                        }
                        Text(
                            when {
                                done -> "本周期已点亮"
                                fillFrac > 0f -> "${(fillFrac * 100).toInt()}%"
                                else -> "长按打卡"
                            },
                            color = if (done) TermBlueBright else lerp(TermInk, Color(0xFF06222B), fillFrac),
                            fontSize = 14.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                        )
                    }
                    Box(
                        Modifier
                            .weight(0.8f).height(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(BtnBg)
                            .border(1.dp, TermBlueDim.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .clickable { onOpenRecords(t.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("历史", color = TermBlue, fontSize = 14.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
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

        // 底部弧形导航：上滑展开 筛选/添加/设置，下滑收起；聚焦时随镜头淡出并停用触摸
        ArcNavBar(
            onAdd = onAdd,
            onOpenSettings = onOpenSettings,
            filterIcon = when (filterMode) {
                1 -> R.drawable.ic_filter_unfinished
                2 -> R.drawable.ic_filter_finished
                else -> R.drawable.ic_filter_all
            },
            onFilterCycle = { filterMode = (filterMode + 1) % 3 },
            moonImage = moonImage,
            timeMs = timeMs,
            dim = zoom.value,
            target = arcTarget,
            onTargetChange = { arcTarget = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 底部弧形导航：月球表面形态的弧形地平线，向上凸起；整条弧带动画升降，升起后带出按钮 */
@Composable
private fun ArcNavBar(
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    filterIcon: Int,
    onFilterCycle: () -> Unit,
    moonImage: ImageBitmap?,
    timeMs: Long,
    dim: Float,
    target: Float,
    onTargetChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anim = remember { Animatable(target) }
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
                                onTargetChange(next)
                                scope.launch { anim.snapTo(next) } // 拖动跟手
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures {
                                Log.d("OrbitCanvas", "arc tapped, anim=${anim.value} -> ${if (anim.value > 0.5f) 0 else 1}")
                                onTargetChange(if (anim.value > 0.5f) 0f else 1f)
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
                    Triple("筛选", filterIcon, onFilterCycle),
                    Triple("添加", R.drawable.ic_star_add, onAdd),
                    Triple("设置", R.drawable.ic_setting_btn, onOpenSettings),
                ).forEach { (label, iconRes, onClick) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            // 黑色光晕：黑图标在亮月面上的衬底
                            Box(
                                Modifier
                                    .size(78.dp)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(Color(0xB3000000), Color(0x00000000))
                                        )
                                    )
                            )
                            IconButton(onClick = onClick, enabled = clickable) {
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription = label,
                                    tint = Color.Black,
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                        }
                        Text(
                            label,
                            color = Color.Black, fontSize = 12.sp,
                            fontFamily = FontFamily.Serif, letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/** polie.png 已是透明底裂纹贴图：仅保证 ARGB_8888 软件位图（可 getPixels/安全绘制） */
private fun processCrackTex(src: Bitmap): Bitmap =
    src.copy(Bitmap.Config.ARGB_8888, false)

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

/** 拖尾星光粒子：从行星位置沿轨道向拖尾末端漂移并渐隐 */
private data class TailStar(
    val taskId: Long,
    val born: Long,
    val life: Long,
    val hue: Int,
    val seed: Float,
    val dir0: Float, // 出生时沿拖尾的起始位置（0 = 行星处，1 = 拖尾末端）
)

/** 消散中的行星残影：任务移除 / 过期时短暂呈现，放大 + 渐隐 */
private data class DissolvingPlanet(
    val frac: Float,
    val hue: Int,
    val ring: Int,
    val born: Long,
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

/** 拖尾星光：从行星位置沿轨道向拖尾末端漂移，逐渐缩小渐隐 */
private fun DrawScope.drawTailStars(
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
            Color(0xFFBEC8E1).copy(alpha = 0.08f),
            0f, 360f, false,
            topLeft = Offset(g.cx - R, g.cy - R * g.q),
            size = Size(R * 2f, R * g.q * 2f),
            style = Stroke(1.2f * g.s),
        )
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
    tilt: Tilt = Tilt(),
    showName: Boolean = true,
    dimmed: Boolean,
    fill: Float,
    delFill: Float,
    spawn: Float,
    crackTex: ImageBitmap?,
) {
    val p = planetPos(g, t)
    if (timeMs % 2000L < 20L) {
        Log.d(
            "OrbitCanvas",
            "planet \"${t.name}\" frac=${t.frac} ring=${t.ring} R=${g.radiiPx[t.ring]} pos=(${p.x}, ${p.y}) size=${g.w}x${g.h} nearness=${p.nearness}",
        )
    }
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

    // 状态环：临近到期 = 琥珀脉冲；逾期 = 暗红；待办 = 星图描边圈（保证黑白星可见）
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

    // 行星本体
    val alpha = ((depthA * dim * when (t.state) {
        PlanetState.NEAR -> 0.55f + 0.28f * (0.5f + 0.5f * sin(timeMs / 500f + t.ring * 2f))
        PlanetState.LATE -> 0.62f
        else -> 1f
    } + if (sel) 0.3f * z else 0f).coerceIn(0f, 1f)) * se

    val bmp = images[t.id]
    // 聚焦态轻微左右翻转：水平压缩（幅度随陀螺仪，很小）
    val yaw = tilt.y * 0.3f
    val halfW = if (sel) r * cos(yaw) else r
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
    // 聚焦态陀螺仪质感：随姿态偏移的球面暗面 + 高光斑（与设备面板反光同源）
    if (sel) {
        val sphere = Path().apply { addOval(Rect(p.x - r, p.y - r, p.x + r, p.y + r)) }
        clipPath(sphere) {
            // 暗面：随左右翻转移动
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.10f + 0.26f * abs(sin(yaw)))),
                    center = Offset(p.x + sin(yaw) * r * 0.9f, p.y - r * 0.1f),
                    radius = r * 1.02f,
                ),
                r, Offset(p.x, p.y),
            )
            // 高光斑：随姿态漂移
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                    center = Offset(p.x - tilt.y * r * 0.45f, p.y - r * 0.42f - tilt.x * r * 0.15f),
                    radius = r * 0.85f,
                ),
                r, Offset(p.x, p.y),
            )
        }
    }
    // 液态玻璃质感：球面暗角 + 边缘亮环（随行星整体透明度淡出——聚焦时其他行星彻底隐藏）
    drawCircle(
        Brush.radialGradient(
            listOf(Color(0x00000000), Color(0x59000000).copy(alpha = 0x59 / 255f * alpha)),
            center = Offset(p.x, p.y), radius = r,
        ),
        r, Offset(p.x, p.y),
    )
    drawCircle(Color(0x59FFFFFF).copy(alpha = 0x59 / 255f * alpha), r, Offset(p.x, p.y), style = Stroke(1.1f * g.s))

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
