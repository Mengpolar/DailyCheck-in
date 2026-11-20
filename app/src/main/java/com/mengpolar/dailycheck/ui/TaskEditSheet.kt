@file:OptIn(ExperimentalMaterial3Api::class)

package com.mengpolar.dailycheck.ui

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpolar.dailycheck.data.TaskUi
import kotlin.random.Random

// ============ 设备色板：现代银色金属外壳 + 灰黑 CRT 屏幕天蓝荧光 ============
private val ShellTop = Color(0xFFC6CBD2) // 壳面受光（银）
private val ShellBottom = Color(0xFF9BA2AB) // 壳面背光（银）
private val ShellMetal = Color(0xFF6E767F) // 铆钉钢材
private val ShellMetalHi = Color(0xFFA6AEB8) // 金属高光
private val ShellInk = Color(0xFF2C3037) // 丝印刻字（暗）
private val ScreenBg = Color(0xFF101214) // 屏幕底（灰黑）
private val ScreenGrid = Color(0xFF262B30) // 屏内网格（明显）
private val TermBlue = Color(0xFF6BE3FF) // 天蓝主色（高亮）
private val TermBlueBright = Color(0xFFE6FAFF) // 高亮/光标
private val TermBlueDim = Color(0xFF58BCD9) // 次级文字
private val TermRed = Color(0xFFFF7A6B) // 荧光警示红
private val TermInk = Color(0xFFF0F8FC) // 输入内容近白

// ============ 设备姿态（陀螺仪）→ 金属反光偏移 ============
private data class Tilt(val x: Float = 0f, val y: Float = 0f)

/** 归一化设备倾角（低通滤波）；无传感器时恒为 0，反光保持静置位 */
@Composable
private fun rememberTilt(): State<Tilt> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(Tilt()) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sm == null || sensor == null) return@DisposableEffect onDispose { }
        val rm = FloatArray(9)
        val angles = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                runCatching {
                    SensorManager.getRotationMatrixFromVector(rm, e.values)
                    SensorManager.getOrientation(rm, angles)
                    val pitch = Math.toDegrees(angles[1].toDouble()).toFloat() // 前后倾
                    val roll = Math.toDegrees(angles[2].toDouble()).toFloat() // 左右倾
                    val cur = tilt.value
                    val nx = (pitch / 30f).coerceIn(-1f, 1f)
                    val ny = (roll / 30f).coerceIn(-1f, 1f)
                    tilt.value = Tilt(
                        x = cur.x + (nx - cur.x) * 0.4f,
                        y = cur.y + (ny - cur.y) * 0.4f,
                    )
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
    return tilt
}

@Composable
fun TaskEditSheet(
    initial: TaskUi?,
    pendingImage: Bitmap?,
    imageSet: Boolean,
    existingPreview: androidx.compose.ui.graphics.ImageBitmap?,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (
        id: Long?, name: String, note: String,
        intervalDays: Int, anchorMin: Int, image: Bitmap?,
        cycleDayX: Int, onceDays: Int,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    // 周期类型：intervalDays == 0 → 一次性（永不重复）
    var onceMode by remember { mutableStateOf(initial?.let { it.intervalDays <= 0 } ?: false) }
    var intervalText by remember {
        mutableStateOf(initial?.intervalDays?.takeIf { it > 0 }?.toString() ?: "1")
    }
    var dayXText by remember { mutableStateOf(initial?.cycleDayX?.takeIf { it > 0 }?.toString() ?: "1") }
    var onceDaysText by remember {
        mutableStateOf(initial?.onceDays?.takeIf { it > 0 }?.toString() ?: "1")
    }
    var anchorText by remember {
        mutableStateOf(
            initial?.anchorMin?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "00:00"
        )
    }
    val interval = intervalText.toIntOrNull()
    val dayX = dayXText.toIntOrNull()
    val onceDays = onceDaysText.toIntOrNull()
    val anchorMin = anchorText.split(":").let { parts ->
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull()
        if (h != null && m != null && h in 0..23 && m in 0..59) h * 60 + m else null
    }
    val intervalOk = !onceMode && (interval ?: 0) >= 1 &&
        (interval == 1 || (dayX ?: 0) in 1..(interval ?: 1))
    val onceOk = onceMode && (onceDays ?: -1) >= 0
    val canSave = name.isNotBlank() && anchorMin != null && (intervalOk || onceOk)
    val hasPlanetImage = imageSet || pendingImage != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        DeviceShell(modifier = Modifier.fillMaxSize()) {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 屏内标题行（带闪烁光标）
                val blinkT = rememberInfiniteTransition(label = "blink")
                val blink by blinkT.animateFloat(
                    1f, 0.15f,
                    infiniteRepeatable(tween(850, easing = LinearEasing), RepeatMode.Reverse),
                    label = "blinkA",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (initial == null) "新建任务" else "编辑任务",
                        color = TermBlueBright, fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 3.sp,
                    )
                    Text(
                        "_",
                        color = TermBlueBright.copy(alpha = blink),
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                TerminalField(
                    label = "名称",
                    value = name,
                    onValueChange = { name = it },
                )
                SegmentedToggle(
                    options = listOf("重复", "永不重复"),
                    selected = if (onceMode) 1 else 0,
                    onSelected = { onceMode = it == 1 },
                )
                if (!onceMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TerminalField(
                            label = "每 N 天",
                            value = intervalText,
                            onValueChange = { intervalText = it.filter { c -> c.isDigit() }.take(3) },
                            isError = interval == null || (interval ?: 0) < 1,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1.1f),
                        )
                        TerminalField(
                            label = "始末时刻",
                            value = anchorText,
                            onValueChange = { anchorText = it.filter { c -> c.isDigit() || c == ':' }.take(5) },
                            isError = anchorMin == null,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    val n = interval ?: 1
                    if (n > 1) {
                        TerminalField(
                            label = "当前第 X 天",
                            value = dayXText,
                            onValueChange = { dayXText = it.filter { c -> c.isDigit() }.take(3) },
                            isError = dayX == null || dayX < 1 || dayX > n,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.width(190.dp),
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TerminalField(
                            label = "还有 N 天",
                            value = onceDaysText,
                            onValueChange = { onceDaysText = it.filter { c -> c.isDigit() }.take(4) },
                            isError = onceDays == null || (onceDays ?: 0) < 0,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1.1f),
                        )
                        TerminalField(
                            label = "结束时刻",
                            value = anchorText,
                            onValueChange = { anchorText = it.filter { c -> c.isDigit() || c == ':' }.take(5) },
                            isError = anchorMin == null,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                TerminalField(
                    label = "备注",
                    value = note,
                    onValueChange = { note = it },
                )

                // 行星图像：样本装载槽
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "行星图像 >",
                        color = TermBlueDim, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val preview = pendingImage?.asImageBitmap() ?: existingPreview
                        Box(Modifier.size(54.dp)) {
                            if (preview != null) {
                                Image(
                                    bitmap = preview,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .border(1.dp, TermBlueDim.copy(alpha = 0.5f), CircleShape),
                                )
                            } else {
                                // 空载入槽：虚线圆 + 十字准星
                                Canvas(Modifier.fillMaxSize()) {
                                    drawCircle(
                                        TermBlueDim.copy(alpha = 0.5f),
                                        style = Stroke(
                                            width = 1.5.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f)),
                                        ),
                                    )
                                    drawLine(
                                        TermBlueDim.copy(alpha = 0.4f),
                                        Offset(size.width / 2, size.height * 0.28f),
                                        Offset(size.width / 2, size.height * 0.72f), 1f,
                                    )
                                    drawLine(
                                        TermBlueDim.copy(alpha = 0.4f),
                                        Offset(size.width * 0.28f, size.height / 2),
                                        Offset(size.width * 0.72f, size.height / 2), 1f,
                                    )
                                }
                            }
                        }
                        BracketButton("[ 载入图像 ]", onClick = onPickImage)
                        if (hasPlanetImage) {
                            Text(
                                "· 已装载",
                                color = TermBlue, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                "移除",
                                color = TermBlueDim,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .padding(4.dp)
                                    .clickable(onClick = onRemoveImage),
                            )
                        } else {
                            Text(
                                "未装载 · 将生成程序星体",
                                color = TermBlueDim.copy(alpha = 0.75f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                // 确认录入指令键
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TermBlue.copy(alpha = if (canSave) 0.16f else 0.05f))
                        .border(
                            1.dp,
                            if (canSave) TermBlue else TermBlueDim.copy(alpha = 0.45f),
                            RoundedCornerShape(8.dp),
                        )
                        .clickable(enabled = canSave) {
                            if (onceMode) {
                                onSave(initial?.id, name.trim(), note.trim(), 0, anchorMin ?: 0, pendingImage, 0, onceDays ?: 0)
                            } else {
                                val n = (interval ?: 1).coerceIn(1, 999)
                                val x = if (n == 1) 1 else (dayX ?: 1).coerceIn(1, n)
                                onSave(initial?.id, name.trim(), note.trim(), n, anchorMin ?: 0, pendingImage, x, 0)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "[ 确认录入 ]",
                        color = if (canSave) TermBlueBright else TermBlueDim,
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                    )
                }
            }
        }
    }
}

// ============ 外壳：陈旧金属面板 ============

/** 设备外壳：金属渐变 + 拉丝 + 铆钉 + 提手凹槽 + 磨损掉漆 + 陀螺仪反光；内容为内嵌 CRT 屏幕 */
@Composable
private fun DeviceShell(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val tilt by rememberTilt()
    val brushLines = remember {
        val r = Random(8)
        List(120) { Triple(r.nextInt(3), r.nextFloat(), r.nextFloat()) }
    }
    // 掉漆斑：分布在左右边带与顶部铭牌带（屏幕会盖住中部）
    val chips = remember {
        val r = Random(421)
        List(8) { i ->
            val x = if (i % 2 == 0) 0.006f + r.nextFloat() * 0.03f else 0.964f + r.nextFloat() * 0.03f
            Triple(x, r.nextFloat(), 1.5f + r.nextFloat() * 3f)
        } + List(3) { Triple(r.nextFloat(), 0.015f + r.nextFloat() * 0.02f, 1.5f + r.nextFloat() * 3f) }
    }
    val scratches = remember {
        val r = Random(97)
        List(3) { Triple(r.nextFloat(), 0.03f + r.nextFloat() * 0.02f, 30f + r.nextFloat() * 50f) }
    }

    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            // 基底：顶部受光金属渐变
            drawRect(Brush.verticalGradient(listOf(ShellTop, ShellBottom)))
            // 拉丝纹理
            brushLines.forEach { (kind, a, fy) ->
                val y = fy * size.height
                drawLine(
                    if (kind == 0) Color.White.copy(alpha = 0.015f + 0.025f * a)
                    else Color.Black.copy(alpha = 0.02f + 0.035f * a),
                    Offset(0f, y), Offset(size.width, y), 1f,
                )
            }
            // 边缘磨损暗角（银色机身、收敛的旧感）
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.16f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.22f)),
                ),
            )
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = 0.10f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.10f)),
                ),
            )
            // 陀螺仪反光：随姿态漂移的强光斑 + 双高光带（固定光源照在银色金属上）
            val cx = size.width * (0.5f - tilt.y * 0.32f)
            val cy = size.height * (0.40f - tilt.x * 0.28f)
            drawRect(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.34f), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = size.width * 0.72f,
                ),
            )
            val band = size.width * 0.24f
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.24f), Color.Transparent),
                    startX = cx - band,
                    endX = cx + band,
                ),
                topLeft = Offset(cx - band, 0f),
                size = Size(band * 2, size.height),
            )
            // 窄锐利次级高光条（镜面反光的亮线）
            val nb = size.width * 0.045f
            drawRect(
                Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.42f), Color.Transparent),
                    startX = cx + band * 0.55f - nb,
                    endX = cx + band * 0.55f + nb,
                ),
                topLeft = Offset(cx + band * 0.55f - nb, 0f),
                size = Size(nb * 2, size.height),
            )
            // 顶部提手凹槽（兼作下滑提示）
            val gw = 120.dp.toPx()
            val gx = size.width / 2f - gw / 2f
            val gy = 10.dp.toPx()
            val gh = 5.dp.toPx()
            drawRoundRect(
                Color(0xFF1B1E22).copy(alpha = 0.85f),
                topLeft = Offset(gx, gy),
                size = Size(gw, gh),
                cornerRadius = CornerRadius(gh / 2),
            )
            drawLine(
                Color.White.copy(alpha = 0.20f),
                Offset(gx + 8, gy + gh + 1f), Offset(gx + gw - 8, gy + gh + 1f), 1f,
            )
            // 掉漆斑与划痕
            chips.forEach { (fx, fy, s) ->
                val p = Offset(fx * size.width, fy * size.height)
                drawCircle(Color(0xFF14161A).copy(alpha = 0.55f), s, p)
                drawCircle(Color.White.copy(alpha = 0.10f), s * 0.65f, Offset(p.x - 1f, p.y - 1.5f))
            }
            scratches.forEach { (fx, fy, len) ->
                val y = fy * size.height
                drawLine(
                    Color.Black.copy(alpha = 0.08f),
                    Offset(fx * size.width, y), Offset(fx * size.width + len, y + len * 0.35f), 1f,
                )
            }
            // 打穿屏幕区域：外壳在此留洞，星空透过半透明屏幕可见
            //（比屏幕区略小 1dp，避免外壳与屏幕间露缝；边界须与布局同步：
            //  屏幕区 上 67dp=26+22+5+1+1+12，左右 14dp，下 14dp，圆角 10dp）
            drawRoundRect(
                Color.Black, // 颜色无效，Clear 模式擦除为透明
                topLeft = Offset(15.dp.toPx(), 68.dp.toPx()),
                size = Size(size.width - 30.dp.toPx(), size.height - 15.dp.toPx() - 68.dp.toPx()),
                cornerRadius = CornerRadius(9.dp.toPx()),
                blendMode = BlendMode.Clear,
            )
            // 四角铆钉（画在打穿之后，避免被擦除）
            val pad = 12.dp.toPx()
            val rr = 3.dp.toPx()
            listOf(
                Offset(pad, pad), Offset(size.width - pad, pad),
                Offset(pad, size.height - pad), Offset(size.width - pad, size.height - pad),
            ).forEach { c ->
                drawCircle(Color.Black.copy(alpha = 0.45f), rr + 1.5f, Offset(c.x + 1.2f, c.y + 1.8f))
                drawCircle(
                    Brush.radialGradient(
                        listOf(ShellMetalHi, ShellMetal),
                        center = Offset(c.x - rr * 0.3f, c.y - rr * 0.35f),
                        radius = rr * 1.7f,
                    ),
                    rr, c,
                )
                drawCircle(Color.Black.copy(alpha = 0.25f), rr + 2.5f, c, style = Stroke(1f))
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(top = 26.dp, bottom = 14.dp),
        ) {
            // 铭牌：丝印刻字 + 编号（高度固定 22dp，外壳打穿区域的边界依赖此布局）
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "任务登记单元",
                    color = ShellInk,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    style = TextStyle(shadow = Shadow(color = Color(0x59FFFFFF), offset = Offset(0f, 1f))),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "编号 v" + com.mengpolar.dailycheck.update.UpdateManager.currentVersion(),
                    color = ShellInk.copy(alpha = 0.75f),
                    fontSize = 9.sp,
                    letterSpacing = 2.sp,
                    style = TextStyle(shadow = Shadow(color = Color(0x33FFFFFF), offset = Offset(0f, 1f))),
                )
            }
            // 铭牌下刻线（凹槽）
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(1.dp)
                    .background(Color(0x40000000)),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(1.dp)
                    .background(Color(0x14FFFFFF)),
            )
            Spacer(Modifier.height(12.dp))
            // 内嵌 CRT 屏幕
            DeviceScreen(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp)) {
                content()
            }
        }
    }
}

/** 内凹 CRT 屏幕：屏底 + 网格 + 常驻扫描线 + 暗角 + 玻璃反光 */
@Composable
private fun DeviceScreen(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scanT = rememberInfiniteTransition(label = "scan")
    val scanY by scanT.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "scanY",
    )
    Box(
        modifier
            .border(1.dp, Color(0xFF2A2E32), RoundedCornerShape(10.dp)),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val radius = 10.dp.toPx()
            val clip = Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
            }
            clipPath(clip) {
                drawRect(ScreenBg.copy(alpha = 0.87f)) // 轻微透明：可透视舱外星空
                // 细网格
                val step = 26.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawLine(ScreenGrid, Offset(0f, y), Offset(size.width, y), 1f); y += step
                }
                var x = 0f
                while (x < size.width) {
                    drawLine(ScreenGrid, Offset(x, 0f), Offset(x, size.height), 1f); x += step
                }
                // 常驻扫描光波：整屏始终被柔和波覆盖，亮心从上到下扫过
                val ph = (scanY * 2f - 0.5f) * size.height
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.42f to TermBlue.copy(alpha = 0.22f),
                        0.5f to TermBlueBright.copy(alpha = 0.32f),
                        0.58f to TermBlue.copy(alpha = 0.22f),
                        1f to Color.Transparent,
                        startY = ph - size.height,
                        endY = ph + size.height,
                    ),
                )
                // 暗角
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.42f)),
                        center = Offset(size.width / 2, size.height / 2),
                        radius = size.width * 0.78f,
                    ),
                )
                // 玻璃反光
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.045f), Color.Transparent),
                        start = Offset(0f, size.height * 0.25f),
                        end = Offset(size.width * 0.6f, 0f),
                    ),
                )
            }
            // 内凹描边
            drawRoundRect(
                Color.Black.copy(alpha = 0.5f),
                cornerRadius = CornerRadius(radius),
                style = Stroke(2.dp.toPx()),
            )
        }
        Box(Modifier.matchParentSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
            content()
        }
    }
}

// ============ 屏内终端控件 ============

/** 终端输入行：「标签 >」 + 天蓝下划线 + 等宽输入 + 聚焦/错误态 */
@Composable
private fun TerminalField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    var focused by remember { mutableStateOf(false) }
    val lineColor = when {
        isError -> TermRed
        focused -> TermBlueBright
        else -> TermBlueDim
    }
    val labelColor = when {
        isError -> TermRed
        focused -> TermBlue
        else -> TermBlueDim
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$label >",
                color = labelColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = TermInk,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(TermBlueBright),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
            )
            if (isError) {
                Text(
                    "!!",
                    color = TermRed,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(lineColor.copy(alpha = if (focused) 0.95f else 0.45f)),
        )
    }
}

/** 方括号指令按钮 */
@Composable
private fun BracketButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, TermBlueDim.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .background(TermBlue.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            label,
            color = TermBlue,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
        )
    }
}

/** 双段切换（屏内 TAB）：选中高亮块滑向另一侧，带过冲回弹 */
@Composable
private fun SegmentedToggle(
    options: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    val anim = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) {
        anim.animateTo(selected.toFloat(), spring(dampingRatio = 0.7f, stiffness = 600f))
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF171A1E))
            .border(1.dp, TermBlueDim.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
    ) {
        val cell = maxWidth / options.size
        Box(
            Modifier
                .offset(x = cell * anim.value)
                .width(cell)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(TermBlue.copy(alpha = 0.22f))
                .border(1.dp, TermBlue.copy(alpha = 0.35f), RoundedCornerShape(6.dp)),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelected(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = if (i == selected) TermBlueBright else TermBlueDim,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp,
                    )
                }
            }
        }
    }
}
