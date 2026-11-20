@file:OptIn(ExperimentalMaterial3Api::class)

package com.mengpolar.dailycheck.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpolar.dailycheck.data.TaskUi

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
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        tonalElevation = 0.dp,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        DeviceShell(modifier = Modifier.fillMaxSize(), nameplate = "任务登记单元") {
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
