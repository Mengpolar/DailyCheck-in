package com.mengpolar.dailycheck.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

private val GalleryGold = Color(0xFFD8B36A)
private val GalleryInk = Color(0xFFD9DEE8)
private val GalleryDim = Color(0xFF7C8496)

private fun mediaPermission(): String =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

private fun hasMediaPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, mediaPermission()) == PackageManager.PERMISSION_GRANTED

/**
 * 应用内相册选择器：显式申请媒体权限 → 展示系统相册网格 → 选中后交给裁剪页。
 * 全屏 Dialog，可直接盖在编辑面板上。
 */
@Composable
fun GalleryPickerDialog(
    onPick: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasMediaPermission(context)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    // 打开即申请权限（未授权时）
    LaunchedEffect(Unit) {
        if (!hasMediaPermission(context)) permLauncher.launch(mediaPermission())
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xF206070B))
                .statusBarsPadding()
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = GalleryDim, fontFamily = FontFamily.Serif, letterSpacing = 2.sp)
                }
                Text(
                    "选择行星图片",
                    color = GalleryInk, fontSize = 15.sp, fontFamily = FontFamily.Serif, letterSpacing = 3.sp,
                )
                // 占位保持标题居中
                TextButton(onClick = {}, enabled = false) { Text(" ", color = Color.Transparent) }
            }

            if (granted) {
                ImageGrid(onPick)
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "需要媒体权限",
                        color = GalleryInk, fontSize = 17.sp, fontFamily = FontFamily.Serif, letterSpacing = 3.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "「每日打卡」需要读取相册，\n仅使用你手动挑选的图片作为行星。",
                        color = GalleryDim, fontSize = 13.sp, fontFamily = FontFamily.Serif,
                        textAlign = TextAlign.Center, lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(24.dp))
                    TextButton(onClick = { permLauncher.launch(mediaPermission()) }) {
                        Text("授予媒体权限", color = GalleryGold, fontFamily = FontFamily.Serif, letterSpacing = 2.sp)
                    }
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) {
                        Text("去应用设置", color = GalleryDim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageGrid(onPick: (Uri) -> Unit) {
    val context = LocalContext.current
    val items by produceState<List<Pair<Long, Uri>>?>(null) {
        value = runCatching {
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Images.Media._ID),
                null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val list = mutableListOf<Pair<Long, Uri>>()
                while (c.moveToNext() && list.size < 120) {
                    list.add(c.getLong(idCol) to ContentUris.withAppendedId(collection, c.getLong(idCol)))
                }
                list
            }
        }.getOrNull()
    }

    when {
        items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = GalleryGold)
        }
        items!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("相册中没有图片", color = GalleryDim, fontSize = 13.sp, fontFamily = FontFamily.Serif)
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(2.dp),
        ) {
            items(items!!, key = { it.first }) { (_, uri) ->
                GalleryThumb(uri, onPick)
            }
        }
    }
}

@Composable
private fun GalleryThumb(uri: Uri, onPick: (Uri) -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, uri) {
        value = runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(uri, android.util.Size(300, 300), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(
                    context.contentResolver,
                    ContentUris.parseId(uri),
                    MediaStore.Images.Thumbnails.MINI_KIND,
                    null,
                )
            }?.asImageBitmap()
        }.getOrNull()
    }
    Box(
        Modifier
            .padding(1.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .clickable { onPick(uri) }
    ) {
        val bmp = thumb
        if (bmp != null) {
            Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Color(0xFF12151D)))
        }
    }
}
