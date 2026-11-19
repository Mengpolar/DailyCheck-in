@file:OptIn(ExperimentalMaterial3Api::class)

package com.mengpolar.dailycheck.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpolar.dailycheck.data.CheckRecord
import com.mengpolar.dailycheck.data.DetailUiState
import com.mengpolar.dailycheck.data.formatDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** 历史（打卡记录）弹窗内容：只展示打卡记录，支持堆叠滚动与左滑删除 */
@Composable
fun TaskDetailScreen(
    state: DetailUiState,
    onDeleteRecord: (CheckRecord) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxHeight(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.records.isEmpty()) {
            item(key = "empty") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "暂无打卡记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
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
                        .stackCard(listState, i),
                )
            }
        }
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

/** 消散星光粒子参数（生成时固定，位置由消散进度决定） */
private data class StarBit(
    val x0f: Float,
    val y0f: Float,
    val vx: Float,
    val vy: Float,
    val size: Float,
    val hue: Float,
    val phase: Float,
)

private val DeleteRed = Color(0xFFCF6A5B)

/**
 * 左滑删除：向左拖动露出右侧红色删除按钮（卡片右侧圆角随滑动渐变为方角，与按钮无缝衔接）；
 * 点击删除后卡片与按钮一起化作随机星光向上飘散、逐渐消散
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
    val corner = 12.dp
    val offset = remember(record.id) { Animatable(0f) }
    val dissolve = remember(record.id) { Animatable(0f) }
    var dragJob by remember { mutableStateOf<Job?>(null) }
    var bits by remember(record.id) { mutableStateOf<List<StarBit>?>(null) }

    val revealP = ((-offset.value) / revealWidth).coerceIn(0f, 1f)
    val fading = dissolve.value > 0f
    val cardAlpha = if (fading) 1f - min(dissolve.value * 4f, 1f) else 1f

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
        // 消散星光层：粒子从卡片区域向上飘散（可越出卡片边界）
        Canvas(Modifier.matchParentSize()) {
            val t = dissolve.value
            val list = bits ?: return@Canvas
            if (t <= 0f) return@Canvas
            list.forEach { b ->
                val px = b.x0f * size.width + b.vx * t * size.width * 0.35f
                val py = b.y0f * size.height - b.vy * t * size.height * 1.4f + 30f * t * t
                val tw = 0.7f + 0.3f * sin(t * 22f + b.phase)
                val alpha = ((1f - t) * (1f - t) * tw).coerceIn(0f, 1f)
                if (alpha <= 0.01f) return@forEach
                val r = b.size * (1f - 0.4f * t)
                val c = Color.hsl(b.hue, 0.72f, 0.78f, 1f)
                drawCircle(
                    Brush.radialGradient(
                        listOf(c.copy(alpha = 0.5f * alpha), Color.Black.copy(alpha = 0f)),
                        center = Offset(px, py),
                        radius = r * 2.6f,
                    ),
                    r * 2.6f,
                    Offset(px, py),
                )
                drawCircle(Color.White.copy(alpha = alpha * 0.9f), r * 0.35f, Offset(px, py))
            }
        }
        // 底层：删除按钮（左滑露出；左方角、右侧与卡片同圆角）
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(with(density) { 92.dp })
                .padding(vertical = 4.dp)
                .graphicsLayer { alpha = cardAlpha }
                .clip(
                    RoundedCornerShape(
                        topStart = 0.dp,
                        topEnd = corner,
                        bottomEnd = corner,
                        bottomStart = 0.dp,
                    ),
                )
                .background(DeleteRed)
                .clickable(enabled = !fading && offset.value < -revealWidth * 0.35f) {
                    if (dissolve.isRunning) return@clickable
                    bits = List(18) {
                        StarBit(
                            x0f = 0.08f + Random.nextFloat() * 0.84f,
                            y0f = 0.15f + Random.nextFloat() * 0.7f,
                            vx = -0.3f + Random.nextFloat() * 0.6f,
                            vy = 0.6f + Random.nextFloat() * 0.9f,
                            size = 2.5f + Random.nextFloat() * 3.5f,
                            hue = Random.nextInt(360).toFloat(),
                            phase = Random.nextFloat() * (PI.toFloat() * 2f),
                        )
                    }
                    scope.launch {
                        dissolve.animateTo(1f, tween(1400, easing = LinearEasing))
                        onDelete(record)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("删除", color = Color.White, fontSize = 13.sp)
        }
        // 前景：记录卡片（左滑；右侧圆角随滑动渐变为方角，与删除按钮无缝衔接）
        RecordRow(
            record,
            shape = RoundedCornerShape(
                topStart = corner,
                topEnd = corner * (1f - revealP),
                bottomEnd = corner * (1f - revealP),
                bottomStart = corner,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .graphicsLayer { alpha = cardAlpha }
                .pointerInput(record.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = { settle() },
                        onDragCancel = { settle() },
                    ) { change, dragAmount ->
                        if (dissolve.isRunning) return@detectHorizontalDragGestures
                        change.consume()
                        val next = (offset.value + dragAmount).coerceIn(-revealWidth, 0f)
                        dragJob?.cancel()
                        dragJob = scope.launch { offset.snapTo(next) }
                    }
                },
        )
    }
}

@Composable
private fun RecordRow(
    record: CheckRecord,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
) {
    val date = LocalDate.ofEpochDay(record.epochDay)
    val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(record.createdAtEpochMs), ZoneId.systemDefault())
    Card(shape = shape, modifier = modifier) {
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
            Column(Modifier.weight(1f)) {
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
