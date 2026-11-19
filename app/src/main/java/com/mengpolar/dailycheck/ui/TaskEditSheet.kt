@file:OptIn(ExperimentalMaterial3Api::class)

package com.mengpolar.dailycheck.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
    onSave: (id: Long?, name: String, note: String, intervalDays: Int, anchorMin: Int, anchorWeekday: Int, image: Bitmap?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var intervalText by remember {
        mutableStateOf(initial?.intervalDays?.takeIf { it > 0 }?.toString() ?: "1")
    }
    var weekday by remember { mutableStateOf(initial?.anchorWeekday?.coerceIn(1, 7) ?: 1) }
    var anchorText by remember {
        mutableStateOf(
            initial?.anchorMin?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "00:00"
        )
    }
    val interval = intervalText.toIntOrNull()
    val anchorMin = anchorText.split(":").let { parts ->
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull()
        if (h != null && m != null && h in 0..23 && m in 0..59) h * 60 + m else null
    }
    val canSave = name.isNotBlank() && (interval ?: 0) >= 1 && anchorMin != null
    val hasPlanetImage = imageSet || pendingImage != null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("重复周期", fontSize = 12.sp, color = SheetDim, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("每天" to 1, "每周" to 7, "每月" to 30).forEach { (label, n) ->
                        FilterChip(
                            selected = interval == n,
                            onClick = { intervalText = n.toString() },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { intervalText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("每 N 天") },
                    supportingText = { Text("填 1 即每天，填 7 即每周；周期网格从创建日起算") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = interval == null || (interval ?: 0) < 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (interval == 7) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("起始星期", fontSize = 12.sp, color = SheetDim, fontFamily = FontFamily.Monospace)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5, "六" to 6, "日" to 7).forEach { (label, wd) ->
                            FilterChip(
                                selected = weekday == wd,
                                onClick = { weekday = wd },
                                label = { Text(label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = anchorText,
                onValueChange = { anchorText = it.filter { c -> c.isDigit() || c == ':' }.take(5) },
                label = { Text("起始时刻（起始线）") },
                supportingText = { Text("每周期以该时刻为始末，如 05:30、20:00；行星此刻沿轨道出发") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                isError = anchorMin == null,
                modifier = Modifier.fillMaxWidth(),
            )
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
                onClick = { onSave(initial?.id, name.trim(), note.trim(), interval ?: 1, anchorMin ?: 0, weekday, pendingImage) },
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
