@file:OptIn(ExperimentalMaterial3Api::class)

package com.mengpolar.dailycheck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mengpolar.dailycheck.data.CheckRecord
import com.mengpolar.dailycheck.data.DetailUiState
import com.mengpolar.dailycheck.data.TaskUi
import com.mengpolar.dailycheck.data.formatDate
import com.mengpolar.dailycheck.data.formatDateShort
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 任务详情（历史）内容：作为底部弹窗嵌入（由 MainActivity 的 ModalBottomSheet 承载） */
@Composable
fun TaskDetailScreen(
    state: DetailUiState,
    onBack: () -> Unit,
    onEdit: (TaskUi) -> Unit,
    onDelete: (Long) -> Unit,
    onCheck: (Long) -> CheckRecord,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.task.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onEdit(state.task) }) {
                Icon(Icons.Rounded.Edit, contentDescription = "编辑")
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Rounded.Delete, contentDescription = "删除")
            }
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭")
            }
        }
        LazyColumn(
            modifier = Modifier.heightIn(max = 520.dp),
            contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") {
                Card(shape = MaterialTheme.shapes.large) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (state.task.done) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (state.task.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(state.task.statusText, style = MaterialTheme.typography.titleMedium)
                        }
                        val last = state.lastRecordDate?.let { " · 最近 ${formatDateShort(it)}" } ?: ""
                        Text(
                            "${state.task.cycleLabel} · 累计完成 ${state.recordCount} 次$last",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.task.note.isNotBlank()) {
                            Text(
                                state.task.note,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (!state.task.done) {
                item(key = "check_button") {
                    val t = state.task
                    Button(
                        onClick = { onCheck(t.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Text("打卡")
                    }
                }
            }
            item(key = "records_header") {
                Text(
                    "打卡记录",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.records.isEmpty()) {
                item(key = "records_empty") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "暂无记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(state.records, key = { "r${it.id}" }) { record ->
                    RecordRow(record)
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除任务") },
            text = { Text("将删除「${state.task.name}」及其全部打卡记录，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete(state.task.id)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RecordRow(record: CheckRecord) {
    val date = LocalDate.ofEpochDay(record.epochDay)
    val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(record.createdAtEpochMs), ZoneId.systemDefault())
    Card(shape = MaterialTheme.shapes.medium) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(formatDate(date), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "打卡时间 %02d:%02d".format(time.hour, time.minute),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
