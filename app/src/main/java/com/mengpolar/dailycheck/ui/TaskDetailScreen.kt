@file:OptIn(ExperimentalMaterial3Api::class)

package com.mengpolar.dailycheck.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpolar.dailycheck.data.CheckRecord
import com.mengpolar.dailycheck.data.DetailUiState
import com.mengpolar.dailycheck.data.TaskUi
import com.mengpolar.dailycheck.data.formatDate
import com.mengpolar.dailycheck.data.formatDateShort
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 任务详情（历史）内容：底部弹窗嵌入。记录卡片带堆叠滚动与左滑删除 */
@Composable
fun TaskDetailScreen(
    state: DetailUiState,
    onBack: () -> Unit,
    onEdit: (TaskUi) -> Unit,
    onDelete: (Long) -> Unit,
    onCheck: (Long) -> CheckRecord,
    onDeleteRecord: (CheckRecord) -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

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
        val recordsHeaderIndex = if (state.task.done) 1 else 2
        val recordsStartIndex = recordsHeaderIndex + 1
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = 520.dp),
            contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 40.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") {
                Card(
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .animateItem()
                        .stackCard(listState, 0),
                ) {
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
                itemsIndexed(state.records, key = { _, r -> "r${r.id}" }) { i, record ->
                    SwipeDeletableRecord(
                        record = record,
                        onDelete = onDeleteRecord,
                        modifier = Modifier
                            .animateItem()
                            .stackCard(listState, recordsStartIndex + i),
                    )
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

/**
 * 堆叠滚动：卡片随滚动位置形成层叠深度——
 * 越靠后（可见层叠数越多）越小、越淡、轻微上移压缩；滑出顶部时进一步缩小淡出
 */
private fun Modifier.stackCard(listState: LazyListState, index: Int): Modifier = graphicsLayer {
    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        ?: return@graphicsLayer
    val depth = (index - listState.firstVisibleItemIndex).coerceAtLeast(0).coerceAtMost(4)
    val exit = ((-info.offset) / info.size.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
    val s = (1f - 0.04f * depth - 0.10f * exit).coerceIn(0.80f, 1f)
    scaleX = s
    scaleY = s
    alpha = (1f - 0.05f * depth - 0.55f * exit).coerceIn(0.30f, 1f)
    translationY = -7f * depth * (1f - exit)
}

/**
 * 左滑删除：向左拖动露出右侧红色删除按钮，松手半程以上吸附展开；
 * 点击删除后卡片围绕删除钮沿弧线飞出，同时缩小、旋转、淡出
 */
@Composable
private fun SwipeDeletableRecord(
    record: CheckRecord,
    onDelete: (CheckRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val revealWidth = with(density) { 92.dp.toPx() }
    val offset = remember(record.id) { Animatable(0f) }
    val fly = remember(record.id) { Animatable(0f) }
    var dragJob by remember { mutableStateOf<Job?>(null) }
    var cardWidth by remember { mutableStateOf(0) }
    val deleting = fly.value > 0f

    fun settle() {
        dragJob?.cancel()
        dragJob = scope.launch {
            val target = if (offset.value < -revealWidth / 2f) -revealWidth else 0f
            offset.animateTo(target, spring(dampingRatio = 0.82f, stiffness = 380f))
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        // 底层：删除按钮（左滑露出）
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(with(density) { 92.dp })
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFCF6A5B))
                .clickable(enabled = !deleting && offset.value < -revealWidth * 0.35f) {
                    if (fly.isRunning) return@clickable
                    scope.launch {
                        fly.animateTo(1f, tween(430, easing = FastOutSlowInEasing))
                        onDelete(record)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("删除", color = Color.White, fontSize = 13.sp)
            }
        }
        // 前景：记录卡片（左滑 / 弧线飞出）
        RecordRow(
            record,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { cardWidth = it.width }
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .graphicsLayer {
                    val p = fly.value
                    if (p > 0f) {
                        val w = cardWidth.coerceAtLeast(1).toFloat()
                        translationX = w * (0.55f + 0.85f * p)
                        translationY = -w * 0.22f * sin(PI.toFloat() * p)
                        rotationZ = -16f * p
                        scaleX = 1f - 0.55f * p
                        scaleY = 1f - 0.55f * p
                        alpha = 1f - p
                    }
                }
                .pointerInput(record.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = { settle() },
                        onDragCancel = { settle() },
                    ) { change, dragAmount ->
                        change.consume()
                        val next = (offset.value + dragAmount).coerceIn(-revealWidth, 0f)
                        dragJob?.cancel()
                        dragJob = scope.launch { offset.snapTo(next) }
                    }
                },
        )
    }
}

private val AndroidDeleteRed = Color(0xFFCF6A5B)

@Composable
private fun RecordRow(record: CheckRecord, modifier: Modifier = Modifier) {
    val date = LocalDate.ofEpochDay(record.epochDay)
    val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(record.createdAtEpochMs), ZoneId.systemDefault())
    Card(shape = MaterialTheme.shapes.medium, modifier = modifier) {
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
