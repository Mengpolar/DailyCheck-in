@file:OptIn(ExperimentalMaterial3Api::class)

package com.mengpolar.dailycheck.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpolar.dailycheck.data.TaskUi
import androidx.compose.animation.core.spring

private val SheetGold = Color(0xFFD8B36A)
private val SheetGoldInk = Color(0xFF1A1408)
private val SheetDim = Color(0xFF7C8496)
private val SheetFaint = Color(0xFF4A5164)

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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Text(
                if (initial == null) "新建任务" else "编辑任务",
                fontSize = 20.sp, fontFamily = FontFamily.Serif,
                letterSpacing = 3.sp, color = Color(0xFFD9DEE8),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("任务名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SegmentedToggle(
                options = listOf("重复", "永不重复"),
                selected = if (onceMode) 1 else 0,
                onSelected = { onceMode = it == 1 },
            )
            if (!onceMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("每 N 天") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = interval == null || (interval ?: 0) < 1,
                        modifier = Modifier.weight(1.1f),
                    )
                    OutlinedTextField(
                        value = anchorText,
                        onValueChange = { anchorText = it.filter { c -> c.isDigit() || c == ':' }.take(5) },
                        label = { Text("始末时刻") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = anchorMin == null,
                        modifier = Modifier.weight(1f),
                    )
                }
                val n = interval ?: 1
                if (n > 1) {
                    OutlinedTextField(
                        value = dayXText,
                        onValueChange = { dayXText = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("当前第 X 天（1-$n）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = dayX == null || dayX < 1 || dayX > n,
                        modifier = Modifier.width(180.dp),
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = onceDaysText,
                        onValueChange = { onceDaysText = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("还有 N 天") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = onceDays == null || (onceDays ?: 0) < 0,
                        modifier = Modifier.weight(1.1f),
                    )
                    OutlinedTextField(
                        value = anchorText,
                        onValueChange = { anchorText = it.filter { c -> c.isDigit() || c == ':' }.take(5) },
                        label = { Text("结束时刻") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = anchorMin == null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            // 行星图片
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("行星图片", fontSize = 12.sp, color = SheetDim, fontFamily = FontFamily.Monospace)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val preview = pendingImage?.asImageBitmap() ?: existingPreview
                    if (preview != null) {
                        Image(
                            bitmap = preview,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .border(1.dp, Color(0x33D8DEEE), CircleShape),
                        )
                    }
                    OutlinedButton(onClick = onPickImage) {
                        Text(if (initial == null) "选择行星图片" else "更换图片")
                    }
                    if (hasPlanetImage) {
                        Text("✓ 已设置", color = SheetGold, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = onRemoveImage) {
                            Text("移除", color = SheetDim, fontSize = 12.sp)
                        }
                    } else {
                        Text("未选图将生成程序星体", color = SheetFaint, fontSize = 11.sp)
                    }
                }
            }

            Button(
                onClick = {
                    if (onceMode) {
                        onSave(initial?.id, name.trim(), note.trim(), 0, anchorMin ?: 0, pendingImage, 0, onceDays ?: 0)
                    } else {
                        val n = (interval ?: 1).coerceIn(1, 999)
                        val x = if (n == 1) 1 else (dayX ?: 1).coerceIn(1, n)
                        onSave(initial?.id, name.trim(), note.trim(), n, anchorMin ?: 0, pendingImage, x, 0)
                    }
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SheetGold,
                    contentColor = SheetGoldInk,
                ),
            ) {
                Text("保存", fontSize = 15.sp, fontFamily = FontFamily.Serif, letterSpacing = 6.sp)
            }
        }
    }
}

/** 双段切换：选中高亮块滑向另一侧，带过冲回弹 */
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
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1A1E26))
            .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(14.dp)),
    ) {
        val cell = maxWidth / options.size
        Box(
            Modifier
                .offset(x = cell * anim.value)
                .width(cell)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color(0xFF2E5D51)),
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
                        color = if (i == selected) Color(0xFFEAF2EE) else SheetDim,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Serif,
                    )
                }
            }
        }
    }
}
