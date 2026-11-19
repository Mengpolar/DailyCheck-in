package com.mengpolar.dailycheck.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp

private val CropGold = Color(0xFFD8B36A)
private val CropInk = Color(0xFFD9DEE8)
private val CropDim = Color(0xFF7C8496)

private fun decodeUri(context: android.content.Context, uri: Uri, maxSide: Int): Bitmap? {
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        android.util.Log.d("CropScreen", "bounds=${bounds.outWidth}x${bounds.outHeight} mime=${bounds.outMimeType}")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        android.util.Log.d("CropScreen", "decoded=${bmp != null} size=${bmp?.width}x${bmp?.height}")
        return bmp
    } catch (t: Throwable) {
        android.util.Log.e("CropScreen", "decodeUri failed", t)
        return null
    }
}

/**
 * 行星图片圆形裁剪：拖动/双指缩放，圆内区域保存为行星头像。
 */
@Composable
fun CropScreen(
    uri: Uri,
    onConfirm: (Bitmap) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val source = remember(uri) { decodeUri(context, uri, 1600) }

    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var minScale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val circleR: Float
    if (viewSize == IntSize.Zero || source == null) {
        circleR = 0f
    } else {
        circleR = minOf(viewSize.width, viewSize.height) * 0.36f
    }

    // 圆选区（中心 circleR）必须完整落在显示图片内：偏移钳制到极限位置
    fun clampOffsetToImage() {
        val bmp = source ?: return
        if (viewSize == IntSize.Zero || circleR <= 0f) return
        val bs = minOf(viewSize.width.toFloat() / bmp.width, viewSize.height.toFloat() / bmp.height)
        val total = bs * scale
        val imgLeft0 = (viewSize.width - bmp.width * total) / 2f
        val imgTop0 = (viewSize.height - bmp.height * total) / 2f
        val cx = viewSize.width / 2f; val cy = viewSize.height / 2f
        val maxX = cx - circleR - imgLeft0
        val minX = cx + circleR - imgLeft0 - bmp.width * total
        val maxY = cy - circleR - imgTop0
        val minY = cy + circleR - imgTop0 - bmp.height * total
        offset = Offset(
            offset.x.coerceIn(minOf(minX, maxX), maxOf(minX, maxX)),
            offset.y.coerceIn(minOf(minY, maxY), maxOf(minY, maxY)),
        )
    }

    // 初始缩放：保证圆区被图片覆盖
    LaunchedEffect(source, viewSize) {
        val bmp = source ?: return@LaunchedEffect
        if (viewSize == IntSize.Zero) return@LaunchedEffect
        val bs = minOf(
            viewSize.width.toFloat() / bmp.width,
            viewSize.height.toFloat() / bmp.height,
        )
        minScale = if (bs > 0f) (circleR * 2f) / (minOf(bmp.width, bmp.height) * bs) else 1f
        scale = maxOf(1f, minScale)
        offset = Offset.Zero
   
        clampOffsetToImage()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF06070B))
            .statusBarsPadding()
    ) {
        // 顶栏
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text("取消", color = CropDim, fontFamily = FontFamily.Serif, letterSpacing = 2.sp)
            }
            Text(
                "裁剪行星",
                color = CropInk, fontSize = 15.sp, fontFamily = FontFamily.Serif, letterSpacing = 3.sp,
            )
            TextButton(
                onClick = {
                    val bmp = source
                    if (bmp != null && viewSize != IntSize.Zero && circleR > 0f) {
                        val bs = minOf(
                            viewSize.width.toFloat() / bmp.width,
                            viewSize.height.toFloat() / bmp.height,
                        )
                        val total = bs * scale
                        val imgLeft = (viewSize.width - bmp.width * total) / 2f + offset.x
                        val imgTop = (viewSize.height - bmp.height * total) / 2f + offset.y
                        val cx = (viewSize.width / 2f - imgLeft) / total
                        val cy = (viewSize.height / 2f - imgTop) / total
                        val r = circleR / total
                        var x = (cx - r).toInt().coerceIn(0, bmp.width - 1)
                        var y = (cy - r).toInt().coerceIn(0, bmp.height - 1)
                        var w = (2f * r).toInt().coerceAtMost(bmp.width - x)
                        var h = (2f * r).toInt().coerceAtMost(bmp.height - y)
                        if (w < 8 || h < 8) {
                            // 兜底：中心方形
                            val side = minOf(bmp.width, bmp.height)
                            x = (bmp.width - side) / 2; y = (bmp.height - side) / 2
                            w = side; h = side
                        }
                        onConfirm(Bitmap.createBitmap(bmp, x, y, w, h))
                    }
                },
                enabled = source != null,
            ) {
                Text("确定", color = CropGold, fontFamily = FontFamily.Serif, letterSpacing = 2.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "拖动与双指缩放 · 圆内区域将作为行星",
            color = CropDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        // 裁剪视图（正方形）
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .align(Alignment.CenterHorizontally)
                .clipToBounds()
                .onSizeChanged { viewSize = it },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = source
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .pointerInput(bmp) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(minScale, 8f)
                                offset += pan
                                clampOffsetToImage()
                            }
                        },
                )
                // 圆形遮罩 + 描边
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = minOf(size.width, size.height) * 0.36f
                    val mask = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(Offset.Zero, size))
                        addOval(Rect(Offset(c.x - r, c.y - r), Size(r * 2f, r * 2f)))
                    }
                    drawPath(mask, Color(0x99000000))
                    drawCircle(CropGold.copy(alpha = 0.7f), r, c, style = Stroke(1.2f))
                }
            } else {
                Text("图片加载失败", color = CropDim, fontSize = 13.sp, fontFamily = FontFamily.Serif)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "未选图的任务将使用程序生成的星体",
            color = CropDim.copy(alpha = 0.7f), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
