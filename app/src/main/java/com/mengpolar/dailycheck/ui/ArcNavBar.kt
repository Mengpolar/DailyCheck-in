package com.mengpolar.dailycheck.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpolar.dailycheck.R
import kotlinx.coroutines.launch

@Composable
internal fun ArcNavBar(
    onAdd: () -> Unit,
    onOpenList: () -> Unit,
    onOpenSettings: () -> Unit,
    filterIcon: Int,
    onFilterCycle: () -> Unit,
    moonImage: ImageBitmap?,
    timeMs: Long,
    dim: Float,
    target: Float,
    onTargetChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anim = remember { Animatable(target) }
    val scope = rememberCoroutineScope()
    val progress = anim.value

    // 轻点切换：整体动画升起 / 收起
    LaunchedEffect(target) {
        anim.animateTo(target, tween(450, easing = FastOutSlowInEasing))
    }

    val heightDp = 40.dp + 100.dp * progress
    // 聚焦淡出（dim→1）时整条弧必须对命中测试透明：不挂 pointerInput、不组合按钮行，
    // 否则它的空手势节点会遮住聚焦页底部按钮的按压事件
    val interactive = dim <= 0.5f
    // 外层为触摸热区盒：比视觉月面高 64dp，弧上方的轻点/上滑也能命中
    Box(
        modifier
            .fillMaxWidth()
            .height(heightDp + 64.dp)
            .graphicsLayer { alpha = (1f - dim).coerceIn(0f, 1f) }
            .then(
                if (interactive) {
                    Modifier
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { change, dragAmount ->
                                change.consume()
                                val next = (anim.value - dragAmount / 240f).coerceIn(0f, 1f)
                                onTargetChange(next)
                                scope.launch { anim.snapTo(next) } // 拖动跟手
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures {
                                onTargetChange(if (anim.value > 0.5f) 0f else 1f)
                            }
                        }
                } else Modifier
                ,
            ),
    ) {
        // 视觉月面：月球圆盘自转，上缘即弧形地平线；随 progress 刚性升起
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(heightDp)
                .align(Alignment.BottomCenter)
        ) {
            val d = this.density
            val w = size.width; val h = size.height
            val moon = moonImage
            if (moon != null) {
                // 圆盘直径 1.44 倍屏宽，圆心沉入屏幕底部，只露上部边缘
                val diskR = w * 0.72f
                val reveal = 44f * d + 106f * d * progress
                val cym = h - reveal + diskR
                val cxm = w / 2f
                // 自转 ~1.5°/s：月面纹理沿边缘缓缓移动
                val rot = (timeMs * 0.0015f) % 360f
                // 月缘上方柔光
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color(0x2BE8ECF4), Color(0x000D1016)),
                        center = Offset(cxm, cym - diskR * 0.92f),
                        radius = diskR * 0.85f,
                    ),
                    diskR * 0.85f, Offset(cxm, cym - diskR * 0.92f),
                )
                val dw = diskR * 2f
                val dh = dw * moon.height.toFloat() / moon.width.toFloat()
                rotate(degrees = rot, pivot = Offset(cxm, cym)) {
                    drawImage(
                        moon,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(moon.width, moon.height),
                        dstOffset = IntOffset((cxm - dw / 2f).toInt(), (cym - dh / 2f).toInt()),
                        dstSize = IntSize(dw.toInt(), dh.toInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            } else {
                // 兜底：无月图时绘制灰色弧带
                val band = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, h * 0.4f)
                    quadraticTo(w / 2f, -h * 0.2f, w, h * 0.4f)
                    lineTo(w, h)
                    close()
                }
                drawPath(
                    band,
                    Brush.verticalGradient(
                        listOf(Color(0xFF3E4552), Color(0xFF0D1016)),
                        startY = h * 0.3f, endY = h,
                    ),
                )
            }
        }
        val clickable = progress > 0.5f && dim <= 0.5f
        if (interactive) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = (6.dp + 18.dp * progress))
                    .graphicsLayer { alpha = progress; translationY = (1f - progress) * 40f },
                horizontalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                listOf(
                    Triple("筛选", filterIcon, onFilterCycle),
                    Triple("列表", R.drawable.ic_list_btn, onOpenList),
                    Triple("添加", R.drawable.ic_star_add, onAdd),
                    Triple("设置", R.drawable.ic_setting_btn, onOpenSettings),
                ).forEach { (label, iconRes, onClick) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            // 黑色光晕：黑图标在亮月面上的衬底
                            Box(
                                Modifier
                                    .size(78.dp)
                                    .background(
                                        Brush.radialGradient(
                                            listOf(Color(0xB3000000), Color(0x00000000))
                                        )
                                    )
                            )
                            IconButton(onClick = onClick, enabled = clickable) {
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription = label,
                                    tint = Color.Black,
                                    modifier = Modifier.size(40.dp),
                                )
                            }
                        }
                        Text(
                            label,
                            color = Color.Black, fontSize = 12.sp,
                            fontFamily = FontFamily.Serif, letterSpacing = 2.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

