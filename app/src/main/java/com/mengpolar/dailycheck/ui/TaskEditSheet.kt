@file:OptIn(ExperimentalMaterial3Api::class)

package com.mengpolar.dailycheck.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpolar.dailycheck.data.CycleType
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
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (id: Long?, name: String, note: String, cycleType: CycleType, intervalDays: Int, anchorMin: Int, image: Bitmap?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var type by remember { mutableStateOf(initial?.cycleType ?: CycleType.DAILY) }
    var intervalText by remember {
        mutableStateOf(initial?.intervalDays?.takeIf { it > 0 }?.toString() ?: "3")
    }
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
    val canSave = name.isNotBlank() &&
        (type != CycleType.EVERY_N_DAYS || (interval ?: 0) >= 1) &&
        (type != CycleType.DAILY || anchorMin != null)
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
                    val options = listOf(
                        "每天" to CycleType.DAILY,
                        "每周" to CycleType.WEEKLY,
                        "每月" to CycleType.MONTHLY,
                        "每 N 天" to CycleType.EVERY_N_DAYS,
                    )
                    options.forEach { (label, t) ->
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (type == CycleType.DAILY) {
                OutlinedTextField(
                    value = anchorText,
                    onValueChange = { anchorText = it.filter { c -> c.isDigit() || c == ':' }.take(5) },
                    label = { Text("起始时刻（起始线）") },
                    supportingText = { Text("行星每天从此刻出发，如 05:30、20:00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = anchorMin == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (type == CycleType.EVERY_N_DAYS) {
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { intervalText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("间隔天数") },
                    supportingText = { Text("完成一次后，隔 N 天再次待打卡；锚点为上次打卡时刻") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                onClick = { onSave(initial?.id, name.trim(), note.trim(), type, interval ?: 1, anchorMin ?: 0, pendingImage) },
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
