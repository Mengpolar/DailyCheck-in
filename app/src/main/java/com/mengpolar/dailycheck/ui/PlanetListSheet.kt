@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mengpolar.dailycheck.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mengpolar.dailycheck.data.GROWTH_NAMES
import com.mengpolar.dailycheck.data.OrbitTask
import com.mengpolar.dailycheck.data.PlanetState
import com.mengpolar.dailycheck.data.growthStageOf
import kotlin.math.roundToInt

/**
 * 行星名录弹层：按轨道环序（内→外，与 <<< / >>> 切换序一致）展示行星卡片。
 * 按住最左侧手柄可上下拖动排序——松手后新顺序持久化，主屏轨道环序随之重排；
 * 点击卡片跳转到对应行星的聚焦页。星海行星（已完成的一次性任务）在名录底部单列展示。
 */
@Composable
fun PlanetListSheet(
    items: List<OrbitTask>,
    seaStars: List<OrbitTask>,
    onReorder: (List<Long>) -> Unit,
    onOpenPlanet: (Long) -> Unit,
    onOpenSea: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 本地顺序：拖动中即时重排预览，松手提交；VM 刷新后与属性同步
    var local by remember { mutableStateOf(items) }
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(items, dragIndex) {
        if (dragIndex < 0) local = items
    }
    val density = LocalDensity.current
    val rowHeight = with(density) { 66.dp.toPx() }
    // 拖动目标槽位：按位移量换算（实时读取，供组合期 rowShift 与手势回调 commitDrag 共用）
    fun currentTarget(): Int =
        if (dragIndex < 0) dragIndex
        else (dragIndex + (dragOffset / rowHeight).roundToInt()).coerceIn(0, local.lastIndex)

    fun rowShift(i: Int): Float {
        val target = currentTarget()
        return when {
            dragIndex < 0 -> 0f
            i == dragIndex -> dragOffset
            dragIndex < target && i in (dragIndex + 1)..target -> -rowHeight
            dragIndex > target && i in target until dragIndex -> rowHeight
            else -> 0f
        }
    }

    fun commitDrag() {
        if (dragIndex < 0) return
        val target = currentTarget()
        if (target != dragIndex && target >= 0) {
            val list = local.toMutableList()
            val moved = list.removeAt(dragIndex)
            list.add(target, moved)
            local = list
            onReorder(list.map { it.id })
        }
        dragIndex = -1
        dragOffset = 0f
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        DeviceShell(modifier = Modifier.fillMaxWidth().height(560.dp), nameplate = "行星名录单元") {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
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
                        "行星名录",
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
                Spacer(Modifier.height(4.dp))
                Text(
                    "按住左侧手柄拖动排序 · 轨道由内到外",
                    color = TermBlueDim, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(10.dp))

                if (local.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().padding(top = 60.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "星图上还没有行星",
                            color = TermBlueDim, fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                        )
                    }
                }
                local.forEachIndexed { i, t ->
                    PlanetRow(
                        t = t,
                        orderNo = i + 1,
                        dragging = dragIndex == i,
                        shift = rowShift(i),
                        onDragStart = {
                            dragIndex = i
                            dragOffset = 0f
                        },
                        onDragDelta = { dragOffset += it },
                        onDragEnd = { commitDrag() },
                        onClick = { if (dragIndex < 0) onOpenPlanet(t.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                // 星海入口：已完成的一次性任务安息于此
                if (seaStars.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(BtnBg)
                            .border(1.dp, TermBlueDim.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenSea)
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "星海 · ${seaStars.size} 颗安息的行星",
                                color = TermInk, fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                            )
                            Text(
                                "已完成的一次性任务长眠于此 · 位置永恒固定",
                                color = TermBlueDim, fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                            )
                        }
                        Text(
                            "进入 >",
                            color = TermBlue, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    seaStars.forEach { ss ->
                        SeaRow(ss, onClick = { if (dragIndex < 0) onOpenPlanet(ss.id) })
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

/** 名录行：左侧拖动手柄 + 序号 + 色点 + 名称/状态 + 成长阶段；整行可点跳转聚焦 */
@Composable
private fun PlanetRow(
    t: OrbitTask,
    orderNo: Int,
    dragging: Boolean,
    shift: Float,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit,
) {
    val stateLabel = when (t.state) {
        PlanetState.DONE -> "已点亮"
        PlanetState.NEAR -> "临近到期"
        PlanetState.PENDING -> "待扫描"
        PlanetState.LATE -> "已逾期"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(66.dp)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = shift }
            .clip(RoundedCornerShape(10.dp))
            .background(if (dragging) Color(0xFF1E242B) else Color(0xFF161A1E))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 拖动手柄（≡）
        Box(
            Modifier
                .size(34.dp)
                .pointerInput(t.id) {
                    detectVerticalDragGestures(
                        onDragStart = { onDragStart() },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            onDragDelta(dy)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(width = 16.dp, height = 12.dp)) {
                val ys = listOf(size.height * 0.15f, size.height * 0.5f, size.height * 0.85f)
                ys.forEach { y ->
                    drawLine(
                        TermBlueDim.copy(alpha = 0.8f),
                        Offset(0f, y), Offset(size.width, y),
                        strokeWidth = 1.6f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "%02d".format(orderNo),
            color = TermBlueDim, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
        )
        Spacer(Modifier.width(10.dp))
        // 行星色点（未点亮时暗淡）
        Canvas(Modifier.size(12.dp)) {
            val lit = t.state == PlanetState.DONE
            drawCircle(
                if (lit) hsla(t.hue, 0.62f, 0.62f, 1f)
                else Color(0xFF5A6472),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.name,
                color = TermInk, fontSize = 14.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${t.cycleLabel} · $stateLabel · ${t.recordCount} 次扫描 · ${GROWTH_NAMES[growthStageOf(t.recordCount)]}",
                color = TermBlueDim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                maxLines = 1,
            )
        }
    }
}

/** 星海行星行（名录底部）：固定安息位提示，可点击聚焦 */
@Composable
private fun SeaRow(t: OrbitTask, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF121518))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(10.dp)) {
            drawCircle(hsla(t.hue, 0.5f, 0.55f, 0.9f))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.name,
                color = Color(0xFF9AA6B8), fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                maxLines = 1,
            )
            Text(
                "安息位 ${t.seaIndex + 1} · ${t.recordCount} 次扫描",
                color = TermBlueDim, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
            )
        }
        Text(
            "聚焦 >",
            color = TermBlueDim, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
        )
    }
}
