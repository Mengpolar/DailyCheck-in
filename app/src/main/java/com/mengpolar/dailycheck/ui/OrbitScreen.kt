package com.mengpolar.dailycheck.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.util.Log
import com.mengpolar.dailycheck.R
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.VibrationEffect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
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
import com.mengpolar.dailycheck.data.GROWTH_NAMES
import com.mengpolar.dailycheck.data.OrbitTask
import com.mengpolar.dailycheck.data.OrbitUiState
import com.mengpolar.dailycheck.data.PlanetState
import com.mengpolar.dailycheck.data.formatWindowStart
import com.mengpolar.dailycheck.data.growthStageOf
import com.mengpolar.dailycheck.data.nextGrowthGap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun OrbitScreen(
    state: OrbitUiState,
    showPlanetNames: Boolean,
    onAdd: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRecords: (Long) -> Unit,
    onCheck: (Long) -> CheckRecord,
    onRetroScan: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onReconstruct: (Long) -> Unit,
    onReorderTasks: (List<Long>) -> Unit,
) {
    val context = LocalContext.current
    val screenDensity = LocalDensity.current.density
    var selectedId by remember { mutableStateOf<Long?>(null) }
    // 月球导航展开状态（提升到顶层：空白点击收起 / 聚焦时自动收起）
    var arcTarget by remember { mutableFloatStateOf(0f) }
    // 行星筛选：0=全部 1=未完成 2=已完成（月球导航筛选键循环切换）
    var filterMode by remember { mutableIntStateOf(0) }
    // 暗星（过期一次性任务）：无轨道停靠在盘外空白区，不参与轨道布局与筛选
    val darkStars = remember(state.items) { state.items.filter { it.darkStar } }
    // 星海（已完成的一次性任务过期后安息）：固定位置、不随轨道转动，可在星海界面聚焦
    val seaStars = remember(state.items) { state.items.filter { it.seaStar } }
    // 按筛选模式取可见任务；显示环序按可见集合重排（隐藏/恢复时轨道补位让位）
    val orbitTasks = remember(state.items) { state.items.filterNot { it.darkStar || it.seaStar } }
    val visibleTasks = remember(orbitTasks, filterMode) {
        when (filterMode) {
            1 -> orbitTasks.filter { it.state != PlanetState.DONE }
            2 -> orbitTasks.filter { it.state == PlanetState.DONE }
            else -> orbitTasks
        }
    }
    val displayItems = remember(visibleTasks) {
        visibleTasks.mapIndexed { i, t -> t.copy(ring = i) }
    }
    // 选中任务按显示集取（筛选重排环序后，镜头/命中须与绘制一致）；暗星/星海行星可被聚焦
    val selected = displayItems.firstOrNull { it.id == selectedId }
        ?: darkStars.firstOrNull { it.id == selectedId }
        ?: seaStars.firstOrNull { it.id == selectedId }
    // 星海界面模式 / 行星名录弹层
    var seaMode by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    // 相机锚点任务：退焦后 selected 变 null，镜头仍需围绕最后选中的行星缩小（动画期间保持锚点不丢）
    var recentSelected by remember { mutableStateOf<OrbitTask?>(null) }
    if (selected != null) {
        recentSelected = selected
    } else {
        // 选中项状态变化（如暗星化）时同步刷新锚点数据，保证镜头/命中与当前渲染一致
        recentSelected = recentSelected?.let { rs -> state.items.firstOrNull { it.id == rs.id } ?: rs }
    }
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
    // 上一个 / 下一个行星切换：协程必须存活于聚焦 UI 离开组合之后（退焦中途），
    // 因此 scope 与函数定义在顶层，而非聚焦条件块内
    val cycleScope = rememberCoroutineScope()
    fun cycleFocus(dir: Int) {
        // 切换序列：星海模式内循环星海行星；星轨模式为轨道行星 + 暗星
        val cycleList = if (seaMode) seaStars else displayItems + darkStars
        if (cycleList.size <= 1) return
        cycleScope.launch {
            val idx = cycleList.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
            val next = cycleList[(idx + dir + cycleList.size) % cycleList.size].id
            // 置空触发 LaunchedEffect 的退焦动画（900ms）；此处直接驱动 zoom 会与之互相取消
            // 导致本协程在 animateTo 处被打断、无法聚焦下一颗行星——故用延时衔接
            selectedId = null
            delay(750)
            selectedId = next
        }
    }
    LaunchedEffect(selectedId) {
        if (selectedId != null) {
            zoom.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))
        } else {
            zoom.animateTo(0f, tween(900, easing = FastOutSlowInEasing))
        }
    }
    // 星海界面：系统返回键 = 返回星轨
    BackHandler(enabled = seaMode) {
        selectedId = null
        seaMode = false
    }
    // 聚焦目标被筛选移除（如「未完成」筛选下扫描完成即被过滤）→ 自动退焦，避免空白聚焦页
    LaunchedEffect(displayItems, darkStars, seaMode) {
        if (seaMode) return@LaunchedEffect
        if (selectedId != null &&
            displayItems.none { it.id == selectedId } &&
            darkStars.none { it.id == selectedId }
        ) selectedId = null
    }

    // 倾斜视差：加速度计求得手机倾角（竖屏左右倾斜 → 左右偏移），低通平滑后驱动星空轻移
    var parallax by remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val listener = object : SensorEventListener {
            internal var sx = 0f; internal var sy = 0f
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

    // 轨道盘倾角：0.20（侧视）/ 0.62（斜视）/ 1.0（俯视）三档；
    // 空白区上下滑动自由调节，松手按速度惯性滑行后弹簧吸附到最近档位
    val tiltAnim = remember { Animatable(0.62f) }
    val tiltDetents = remember { floatArrayOf(0.20f, 0.62f, 1f) }
    val tiltScope = rememberCoroutineScope()
    var tiltDragJob by remember { mutableStateOf<Job?>(null) }
    var tiltVelocity by remember { mutableFloatStateOf(0f) }
    var tiltLastUptime by remember { mutableStateOf(0L) }
    var tiltLastQ by remember { mutableFloatStateOf(0.62f) }

    // 轨道布局过渡：任务增删 / 筛选切换 / 盘面缩放时，轨道半径与行星尺寸旧→新平滑插值
    val targetRadii = remember(viewSize, displayItems.size) {
        orbitRadii(viewSize.width.toFloat(), viewSize.height.toFloat(), screenDensity, displayItems.size)
    }
    val targetScale = remember(viewSize, displayItems.size) {
        planetScaleFor(displayItems.size, viewSize.width.toFloat(), viewSize.height.toFloat(), screenDensity)
    }
    var settledRadii by remember { mutableStateOf<List<Float>?>(null) }
    var settledScale by remember { mutableFloatStateOf(1f) }
    var animFrom by remember { mutableStateOf<List<Float>?>(null) }
    var animFromScale by remember { mutableFloatStateOf(1f) }
    var animTo by remember { mutableStateOf<List<Float>?>(null) }
    val orbitAnim = remember { Animatable(1f) }
    LaunchedEffect(targetRadii, targetScale) {
        val from = settledRadii
        if (from == null || (from == targetRadii && settledScale == targetScale)) {
            settledRadii = targetRadii
            settledScale = targetScale
            orbitAnim.snapTo(1f)
            return@LaunchedEffect
        }
        animFrom = from
        animTo = targetRadii
        animFromScale = settledScale
        orbitAnim.snapTo(0f)
        orbitAnim.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        settledRadii = targetRadii
        settledScale = targetScale
    }

    // 新增行星登场时刻 / 消散残影 / 吞噬残影 / 上一帧可见任务快照
    var spawnedAt by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var dissolving by remember { mutableStateOf(listOf<DissolvingPlanet>()) }
    var swallowGhosts by remember { mutableStateOf(listOf<SwallowGhost>()) }
    // 待播放吞噬动画的任务 id（删除动作先登记，diff 时转成吞噬残影而非消散）
    val swallowIds = remember { mutableStateListOf<Long>() }
    var prevDisplay by remember { mutableStateOf<List<OrbitTask>>(emptyList()) }
    var prevDark by remember { mutableStateOf<List<OrbitTask>>(emptyList()) }
    var prevSea by remember { mutableStateOf<List<OrbitTask>>(emptyList()) }
    // 拖动排序过渡：环序变化的行星从旧环半径平滑滑向新环（无消散/登场动画）
    var skipDiffOnce by remember { mutableStateOf(false) }
    var ringFromMap by remember { mutableStateOf<Map<Long, Float>>(emptyMap()) }
    val ringLerp = remember { Animatable(1f) }
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
        spawnedAt = spawnedAt.filterKeys { id -> state.items.any { it.id == id } }
        // 消失（被筛选隐藏 / 环序变化让位）：旧位置放大渐隐；
        // 若登记过吞噬则改为螺旋坠入盘心的残影（删除动作）
        val gone = prevDisplay.filter { (it.id to it.ring) !in newIds }
        if (gone.isNotEmpty()) {
            dissolving = dissolving + gone.filter { it.id !in swallowIds }.map { t ->
                DissolvingPlanet(frac = t.frac, hue = t.hue, ring = t.ring, born = nowMs)
            }
            swallowGhosts = swallowGhosts + gone.filter { it.id in swallowIds }.map { t ->
                SwallowGhost(id = t.id, ring = t.ring, frac = t.frac, hue = t.hue, darkStar = false, darkIndex = 0, born = nowMs)
            }
            swallowIds.removeAll(gone.map { it.id })
        }
        // 拖动排序路径：跳过消散/登场，改为旧环→新环的半径插值
        if (skipDiffOnce) {
            skipDiffOnce = false
            prevDisplay = displayItems
            if (viewSize != IntSize.Zero) {
                val base = orbitRadii(viewSize.width.toFloat(), viewSize.height.toFloat(), screenDensity, displayItems.size)
                val prevRingById = prevDisplay.associate { it.id to it.ring }
                val changed = displayItems.filter { prevRingById[it.id] != null && prevRingById[it.id] != it.ring }
                if (changed.isNotEmpty()) {
                    ringFromMap = changed.mapNotNull { t ->
                        val from = base.getOrNull(prevRingById[t.id] ?: -1) ?: return@mapNotNull null
                        t.id to from
                    }.toMap()
                    ringLerp.snapTo(0f)
                    try {
                        ringLerp.animateTo(1f, tween(680, easing = FastOutSlowInEasing))
                    } finally {
                        ringFromMap = emptyMap()
                    }
                }
            }
            return@LaunchedEffect
        }
        prevDisplay = displayItems
    }
    // 暗星登场 / 被吞噬：与轨道行星的 diff 分开观察（暗星不在 displayItems 中）
    LaunchedEffect(darkStars) {
        val nowMs = withFrameNanos { it } / 1_000_000
        val added = darkStars.filter { ds -> prevDark.none { it.id == ds.id } }
        if (added.isNotEmpty()) {
            spawnedAt = spawnedAt + added.associate { it.id to nowMs }
        }
        val gone = prevDark.filter { ds -> darkStars.none { it.id == ds.id } }
        if (gone.isNotEmpty()) {
            swallowGhosts = swallowGhosts + gone.filter { it.id in swallowIds }.map { t ->
                SwallowGhost(id = t.id, ring = -1, frac = 0f, hue = t.hue, darkStar = true, darkIndex = t.darkIndex, born = nowMs)
            }
            swallowIds.removeAll(gone.map { it.id })
        }
        prevDark = darkStars
    }
    // 星海登场 / 被吞噬 / 被改造召回（召回时退出星海界面，回星轨观看行星重新入轨）
    LaunchedEffect(seaStars) {
        val nowMs = withFrameNanos { it } / 1_000_000
        val added = seaStars.filter { ss -> prevSea.none { it.id == ss.id } }
        if (added.isNotEmpty()) {
            spawnedAt = spawnedAt + added.associate { it.id to nowMs }
        }
        val gone = prevSea.filter { ss -> seaStars.none { it.id == ss.id } }
        if (gone.isNotEmpty()) {
            swallowGhosts = swallowGhosts + gone.filter { it.id in swallowIds }.map { t ->
                SwallowGhost(
                    id = t.id, ring = -1, frac = 0f, hue = t.hue,
                    darkStar = false, darkIndex = 0, seaStar = true, seaIndex = t.seaIndex, born = nowMs,
                )
            }
            swallowIds.removeAll(gone.map { it.id })
        }
        prevSea = seaStars
    }
    LaunchedEffect(seaMode, seaStars) {
        if (seaMode && selectedId != null && seaStars.none { it.id == selectedId }) {
            selectedId = null
            seaMode = false
        }
    }

    // 拖尾星光粒子：已完成行星的彗尾上随机生成，向拖尾末端漂移并渐隐
    val tailStars = remember { mutableStateListOf<TailStar>() }
    LaunchedEffect(displayItems) {
        while (true) {
            withFrameNanos { frameNanos ->
                val nowMs = frameNanos / 1_000_000
                tailStars.removeAll { nowMs - it.born > it.life }
                dissolving = dissolving.filter { nowMs - it.born < 1000 }
                swallowGhosts = swallowGhosts.filter { nowMs - it.born < 1000 }
                // 点击星光过期清理（防止长会话下列表无界增长）
                sparks.removeAll { nowMs - it.born > 5200 }
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

    // 背景图在 Composable 层用 Image 绘制；此处移除位图解码逻辑

    // 长按扫描：按住填充（每秒 +1），松手渐退（每秒 -1），满 3 秒完成扫描并点亮行星；
    // 暗星聚焦时同按钮变长按重构（恢复为正常有轨行星）
    // 长按回溯扫描：同填充动画，补录上一周期记录
    // 长按吞噬：聚焦信息卡右侧区域，红色填充满 3 秒删除任务（无撤销）
    var holdDown by remember { mutableStateOf(false) }
    val holdProgress = remember { mutableFloatStateOf(0f) }
    var retroDown by remember { mutableStateOf(false) }
    val retroProgress = remember { mutableFloatStateOf(0f) }
    var delDown by remember { mutableStateOf(false) }
    val delProgress = remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    // 完成振动：长按扫描/回溯扫描达成时的一下明确震感（叠加轻触反馈）
    val vibrator = remember {
        runCatching { context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator }.getOrNull()
    }
    val completeBuzz: () -> Unit = {
        runCatching {
            vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
    LaunchedEffect(selectedId) {
        holdDown = false
        holdProgress.floatValue = 0f
        retroDown = false
        retroProgress.floatValue = 0f
        delDown = false
        delProgress.floatValue = 0f
        if (selectedId == null) return@LaunchedEffect
        val task = state.items.firstOrNull { it.id == selectedId } ?: return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
            last = now
            val hn = if (holdDown) holdProgress.floatValue + dt else holdProgress.floatValue - dt
            holdProgress.floatValue = hn.coerceIn(0f, CHECK_HOLD_S)
            val rn = if (retroDown) retroProgress.floatValue + dt else retroProgress.floatValue - dt
            retroProgress.floatValue = rn.coerceIn(0f, CHECK_HOLD_S)
            val dn = if (delDown) delProgress.floatValue + dt else delProgress.floatValue - dt
            delProgress.floatValue = dn.coerceIn(0f, CHECK_HOLD_S)
            if (holdDown && holdProgress.floatValue >= CHECK_HOLD_S) {
                holdDown = false
                holdProgress.floatValue = 0f
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                completeBuzz()
                if (task.darkStar) {
                    selectedId = null // 重构：先退焦，暗星随之恢复为正常轨道行星
                    onReconstruct(task.id)
                } else {
                    onCheck(task.id)
                }
            }
            if (retroDown && retroProgress.floatValue >= CHECK_HOLD_S) {
                retroDown = false
                retroProgress.floatValue = 0f
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                completeBuzz()
                onRetroScan(task.id)
            }
            if (delDown && delProgress.floatValue >= CHECK_HOLD_S) {
                delDown = false
                delProgress.floatValue = 0f
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                swallowIds.add(task.id)
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
                .pointerInput(seaMode) {
                    // 空白区上下滑动：轨道盘绕 X 轴倾角（中心固定）；松手惯性滑行后吸附档位
                    // 星海界面无轨道盘，禁用倾角手势
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            if (seaMode) return@detectVerticalDragGestures
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
                .pointerInput(displayItems, viewSize, seaMode) {
                    detectTapGestures { tap ->
                        if (viewSize == IntSize.Zero) return@detectTapGestures
                        val g0 = if (seaMode) {
                            // 星海：无轨道几何，行星位于固定安息位
                            Geo(
                                viewSize.width.toFloat(), viewSize.height.toFloat(),
                                viewSize.width / 2f, viewSize.height / 2f, 1f, emptyList(), screenDensity,
                                planetScale = planetScaleFor(seaStars.size, viewSize.width.toFloat(), viewSize.height.toFloat(), screenDensity),
                            )
                        } else {
                            computeGeo(viewSize.width.toFloat(), viewSize.height.toFloat(), screenDensity, displayItems.size, tiltAnim.value)
                        }
                        // 相机状态与绘制一致（行星先原地放大、中后段滑向屏心）。
                        // 注意：pointerInput 的闭包可能持有旧组合的 selected，这里按 selectedId 现取，保证命中测试与绘制一致
                        val selNow = displayItems.firstOrNull { it.id == selectedId }
                            ?: darkStars.firstOrNull { it.id == selectedId }
                            ?: seaStars.firstOrNull { it.id == selectedId } ?: recentSelected
                        var camScale = 1f
                        var kx = g0.cx; var ky = g0.cy
                        if (selNow != null) {
                            val fp = planetPos(g0, selNow)
                            // 暗星本体是虚空，放大到能看清引力圈即可（9x），行星则铺满屏宽
                            val zcap = if (selNow.darkStar) 9f else 40f
                            val targetZoom = (viewSize.width / (fp.r * 2f)).coerceIn(1f, zcap)
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
                        // 聚焦态下其他行星已隐藏：只允许点中当前目标（点击=退焦），避免瞬跳到隐藏行星
                        val candidates = if (zoom.value > 0.5f) selNow?.let { listOf(it) } ?: emptyList()
                        else if (seaMode) seaStars else displayItems + darkStars
                        candidates.forEach { t ->
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
            val g0raw = if (seaMode) {
                // 星海：无轨道几何，行星位于固定安息位
                Geo(
                    size.width, size.height, size.width / 2f, size.height / 2f, 1f, emptyList(), d,
                    planetScale = planetScaleFor(seaStars.size, size.width, size.height, d),
                )
            } else computeGeo(size.width, size.height, d, displayItems.size, tiltAnim.value)
            // 布局过渡中：以插值半径重排几何（新增轨道滑出 / 移除补位）
            val g0 = if (!seaMode && orbitAnim.value < 1f && animFrom != null && animTo != null) {
                Geo(
                    g0raw.w, g0raw.h, g0raw.cx, g0raw.cy, g0raw.q,
                    lerpRadii(animFrom!!, animTo!!, orbitAnim.value), g0raw.s,
                    planetScale = animFromScale + (targetScale - animFromScale) * orbitAnim.value,
                )
            } else g0raw
            // 拖动排序过渡中：行星半径从旧环滑向新环
            val ringLerpV = ringLerp.value
            val orbitRadius: (OrbitTask) -> Float? = { t ->
                ringFromMap[t.id]?.let { from ->
                    val to = g0.radiiPx.getOrElse(t.ring) { g0.radiiPx.lastOrNull() ?: 0f }
                    from + (to - from) * ringLerpV
                }
            }
            val z = zoom.value
            // 相机：行星先原地放大（前 40% 行程），随后在放大过程中缓缓滑向屏幕中心；
            // 落定时行星居中且直径≈屏宽。退焦沿同一曲线反向（先滑回轨道位置、再原地缩小）。
            // recentSelected 保证退焦动画期间镜头目标不丢
            var camScale = 1f
            var kx = g0.cx; var ky = g0.cy
            val selNow = selected ?: recentSelected
            if (selNow != null) {
                val fp = planetPos(g0, selNow)
                // 暗星聚焦上限 9x（见命中测试块）；行星铺满屏宽
                val zcap = if (selNow.darkStar) 9f else 40f
                val targetZoom = (size.width / (fp.r * 2f)).coerceIn(1f, zcap)
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

                // 星系层：轨道半径已含相机缩放（轨道线弱化，仅作存在暗示）；星海模式无轨道
                drawOrbitLayer(g)
                // 退焦动画期间（zoom 未归零）沿用最近选中继续暗化其他行星，实现逐渐恢复
                val dimRef = selected ?: recentSelected.takeIf { zoom.value > 0.05f }
                // 星海模式渲染安息行星；星轨模式渲染轨道行星（含排序半径插值）
                val renderItems = if (seaMode) seaStars else displayItems
                renderItems.sortedBy { planetPos(g, it, orbitRadius(it)).nearness }.forEach { t ->
                    drawTask(
                        g, t, timeMs, z, selected, planetImages,
                        tilt = deviceTilt,
                        showName = showPlanetNames,
                        dimmed = dimRef != null && dimRef.id != t.id,
                        spawn = ((timeMs - (spawnedAt[t.id] ?: 0L)).coerceAtLeast(0L))
                            .toFloat().coerceIn(0f, 550f) / 550f,
                        delFill = if (selected?.id == t.id)
                            (delProgress.floatValue / CHECK_HOLD_S).coerceIn(0f, 1f) else 0f,
                        fill = if (selected?.id == t.id && selected.state != PlanetState.DONE)
                            (holdProgress.floatValue / CHECK_HOLD_S).coerceIn(0f, 1f) else 0f,
                        radiusOverride = orbitRadius(t),
                    )
                }
                // 暗星（盘外停靠的过期一次性任务）：在轨道行星之上绘制（星海模式下不渲染）
                if (!seaMode) darkStars.forEach { ds ->
                    drawDarkStar(
                        g, ds, timeMs, selected,
                        showName = showPlanetNames,
                        dimmed = dimRef != null && dimRef.id != ds.id,
                        spawn = ((timeMs - (spawnedAt[ds.id] ?: 0L)).coerceAtLeast(0L))
                            .toFloat().coerceIn(0f, 550f) / 550f,
                        fill = if (selected?.id == ds.id)
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

                // 吞噬残影：删除的任务螺旋坠入盘心（暗星原地塌缩），终点黑洞闪现
                swallowGhosts.forEach { drawSwallowGhost(g, it, timeMs) }

                // 拖尾星光粒子：沿轨道向拖尾末端漂移、渐隐（星海行星无拖尾）
                drawTailStars(g, if (seaMode) emptyList() else displayItems, tailStars, timeMs, sparkStar)

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
            // 成长阶段：累计扫描次数驱动（暗星不再成长）
            val stage = growthStageOf(t.recordCount)
            val gap = nextGrowthGap(t.recordCount)
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
                            if (t.darkStar) "一次性任务 · 已过期"
                            else "${t.cycleLabel} · 已完成 ${t.recordCount} 个周期",
                            color = TermBlueDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (t.seaStar) "已安息于星海 · 固定坐标 ${t.seaIndex + 1}"
                            else if (t.darkStar) "已于 ${formatWindowStart(t.periodEndMs)} 到期 · 沉寂 ${t.expiredDays} 天"
                            else if (remainingMs > 0L) "本周期还剩 ${formatRemaining(remainingMs)}" else "本周期已结束 · 即将重置",
                            color = remainColor, fontSize = 13.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                        )
                        if (!t.darkStar) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (gap == null) "成长阶段：${GROWTH_NAMES[stage]}（已满阶）"
                                else "成长阶段：${GROWTH_NAMES[stage]} · 距下一阶段 $gap 次扫描",
                                color = TermBlueDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                            )
                        }
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
                    // 长按 3 秒吞噬任务（整个票根右侧区域可按）：红色从左往右填充，完成后行星螺旋坠入盘心，无撤销
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
                        // 自绘吞噬符记（✕ 交叉线）+ 标签，替代 material 图标库
                        Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Canvas(Modifier.size(18.dp)) {
                                val glyph = lerp(TermRed, Color.White, delFill)
                                val rr = 7.dp.toPx()
                                val stroke = 2.2.dp.toPx()
                                drawLine(glyph, Offset(size.width / 2 - rr, size.height / 2 - rr), Offset(size.width / 2 + rr, size.height / 2 + rr), strokeWidth = stroke)
                                drawLine(glyph, Offset(size.width / 2 - rr, size.height / 2 + rr), Offset(size.width / 2 + rr, size.height / 2 - rr), strokeWidth = stroke)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "吞噬",
                                color = lerp(TermRed, Color.White, delFill),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp,
                            )
                        }
                    }
                }
            }

            // 底部操作卡：改造 / 长按扫描（暗星=长按重构）/ 追溯；聚焦时从下往上出现，退焦反向
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
                // 上一个 / 下一个行星（<<< / >>>：无框粗体 + 方向漂移动画；cycleFocus 定义在顶层）
                val arrowT = rememberInfiniteTransition(label = "arrow")
                val arrowP by arrowT.animateFloat(
                    0f, 1f,
                    infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
                    label = "arrowP",
                )
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(0.8f).height(34.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "<<<",
                                color = TermBlueBright,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .graphicsLayer { translationX = -7f * arrowP }
                                    .clickable { cycleFocus(-1) },
                            )
                        }
                        Spacer(Modifier.weight(1.4f))
                        Box(Modifier.weight(0.8f).height(34.dp), contentAlignment = Alignment.Center) {
                            Text(
                                ">>>",
                                color = TermBlueBright,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .graphicsLayer { translationX = 7f * arrowP }
                                    .clickable { cycleFocus(1) },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // 回溯扫描：上一周期未扫描时出现在扫描键上方（一次性任务/暗星无追溯）
                    if (t.retroAvailable && !t.darkStar) {
                        val retroFillFrac = (retroProgress.floatValue / CHECK_HOLD_S).coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .fillMaxWidth().height(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BtnBg)
                                .pointerInput(t.id) {
                                    detectTapGestures(
                                        onPress = {
                                            retroDown = true
                                            try { awaitRelease() } finally { retroDown = false }
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(Modifier.matchParentSize()) {
                                if (retroFillFrac > 0f) {
                                    drawRect(TermBlue, size = Size(size.width * retroFillFrac, size.height))
                                }
                                // 虚线边框：区别于主操作键
                                drawRoundRect(
                                    TermBlueDim.copy(alpha = 0.55f),
                                    topLeft = Offset(1f, 1f),
                                    size = Size(size.width - 2f, size.height - 2f),
                                    cornerRadius = CornerRadius(10.dp.toPx()),
                                    style = Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f))),
                                )
                            }
                            Text(
                                if (retroFillFrac > 0f) "${(retroFillFrac * 100).toInt()}%"
                                else "长按回溯扫描 · 补录上一周期",
                                color = lerp(TermInk, Color(0xFF06222B), retroFillFrac),
                                fontSize = 13.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
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
                        Text("改造", color = TermBlue, fontSize = 14.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
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
                                        holdDown = true
                                        try { awaitRelease() } finally { holdDown = false }
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
                                t.darkStar -> "长按重构"
                                done -> "本周期已点亮"
                                fillFrac > 0f -> "${(fillFrac * 100).toInt()}%"
                                else -> "长按扫描"
                            },
                            color = if (done || t.darkStar && fillFrac <= 0f) TermBlueBright else lerp(TermInk, Color(0xFF06222B), fillFrac),
                            fontSize = 14.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                        )
                    }
                    if (!t.darkStar) {
                        Box(
                            Modifier
                                .weight(0.8f).height(52.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(BtnBg)
                                .border(1.dp, TermBlueDim.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .clickable { onOpenRecords(t.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("追溯", color = TermBlue, fontSize = 14.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                        }
                    }
                }
                }
            }
        }

        // 星海界面标识与返回（聚焦时随镜头淡出）
        if (seaMode) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .graphicsLayer { alpha = (1f - zoom.value).coerceIn(0f, 1f) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "星海 · ${seaStars.size} 颗安息的行星",
                    color = TermBlueDim, fontSize = 13.sp, fontFamily = FontFamily.Monospace, letterSpacing = 3.sp,
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(BtnBg)
                        .border(1.dp, TermBlueDim.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .clickable {
                            selectedId = null
                            seaMode = false
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        "[ 返回星轨 ]",
                        color = TermBlue, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                    )
                }
            }
        }

        // 行星名录弹层：卡片列表 + 左侧手柄拖动排序 + 星海入口
        if (showList) {
            PlanetListSheet(
                items = orbitTasks,
                seaStars = seaStars,
                onReorder = { ids ->
                    skipDiffOnce = true
                    onReorderTasks(ids)
                },
                onOpenPlanet = { id ->
                    showList = false
                    filterMode = 0 // 目标行星可能被筛选隐藏：重置筛选保证可见
                    selectedId = id
                    if (arcTarget > 0.5f) arcTarget = 0f
                },
                onOpenSea = {
                    showList = false
                    selectedId = null
                    seaMode = true
                },
                onDismiss = { showList = false },
            )
        }

        // 底部弧形导航：上滑展开 列表/筛选/添加/设置，下滑收起；聚焦与星海模式随镜头淡出并停用触摸
        ArcNavBar(
            onAdd = onAdd,
            onOpenList = { showList = true },
            onOpenSettings = onOpenSettings,
            filterIcon = when (filterMode) {
                1 -> R.drawable.ic_filter_unfinished
                2 -> R.drawable.ic_filter_finished
                else -> R.drawable.ic_filter_all
            },
            onFilterCycle = { filterMode = (filterMode + 1) % 3 },
            moonImage = moonImage,
            timeMs = timeMs,
            dim = if (seaMode) 1f else zoom.value,
            target = arcTarget,
            onTargetChange = { arcTarget = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 底部弧形导航：月球表面形态的弧形地平线，向上凸起；整条弧带动画升降，升起后带出按钮 */
