package com.mengpolar.dailycheck

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mengpolar.dailycheck.data.CheckRecord
import com.mengpolar.dailycheck.data.TaskUi
import com.mengpolar.dailycheck.data.TaskViewModel
import com.mengpolar.dailycheck.ui.CropScreen
import com.mengpolar.dailycheck.ui.GalleryPickerDialog
import com.mengpolar.dailycheck.ui.OrbitScreen
import com.mengpolar.dailycheck.ui.SettingsScreen
import com.mengpolar.dailycheck.ui.TaskDetailScreen
import com.mengpolar.dailycheck.ui.TaskEditSheet
import com.mengpolar.dailycheck.ui.UpdateDialogs
import com.mengpolar.dailycheck.ui.theme.DailyCheckTheme
import com.mengpolar.dailycheck.update.UpdateViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // 星空语言：全局锁定深色
            DailyCheckTheme(darkTheme = true) {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    val taskVm: TaskViewModel = viewModel()
    val updateVm: UpdateViewModel = viewModel()

    val orbitState by taskVm.orbitState.collectAsState()
    val detailState by taskVm.detailState.collectAsState()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showEditSheet by rememberSaveable { mutableStateOf(false) }
    var editTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingImage by remember { mutableStateOf<Bitmap?>(null) } // 新建流程暂存的行星图
    var cropUri by remember { mutableStateOf<Uri?>(null) }
    var showGallery by remember { mutableStateOf(false) }

    val pickImage: () -> Unit = { showGallery = true }

    // 启动时静默检查更新
    LaunchedEffect(Unit) { updateVm.autoCheckOnLaunch() }

    BackHandler(enabled = cropUri != null) { cropUri = null }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = !showSettings && detailState != null) { taskVm.openDetail(null) }

    val openEdit: (Long?) -> Unit = { id ->
        editTaskId = id
        showEditSheet = true
    }
    val checkIn: (Long) -> CheckRecord = { taskVm.checkIn(it) }

    when {
        showSettings -> SettingsScreen(
            vm = updateVm,
            onBack = { showSettings = false },
        )
        detailState != null -> detailState?.let { ds ->
            TaskDetailScreen(
                state = ds,
                onBack = { taskVm.openDetail(null) },
                onEdit = { openEdit(it.id) },
                onDelete = { taskVm.deleteTask(it) },
                onCheck = checkIn,
                onUndo = { taskVm.undoCheck(it) },
            )
        }
        else -> OrbitScreen(
            state = orbitState,
            onAdd = { openEdit(null) },
            onOpenSettings = { showSettings = true },
            onOpenRecords = { taskVm.openDetail(it) },
            onCheck = checkIn,
            onUndo = { taskVm.undoCheck(it) },
        )
    }

    val context = LocalContext.current
    val imageKey = orbitState.items.joinToString("|") { it.imagePath ?: "-" }
    val existingPreview = remember(imageKey, editTaskId) {
        val path = orbitState.items.firstOrNull { it.id == editTaskId }?.imagePath
        path?.let { p ->
            val f = java.io.File(context.filesDir, p)
            if (f.exists()) {
                runCatching {
                    BitmapFactory.decodeFile(
                        f.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = 4 },
                    )?.asImageBitmap()
                }.getOrNull()
            } else {
                null
            }
        }
    }

    if (showEditSheet) {
        val initial = editTaskId?.let { id ->
            orbitState.items.firstOrNull { it.id == id }?.let { o ->
                TaskUi(
                    id = o.id,
                    name = o.name,
                    note = o.note,
                    intervalDays = o.intervalDays,
                    anchorMin = o.anchorMin,
                    anchorWeekday = o.anchorWeekday,
                    cycleLabel = o.cycleLabel,
                    anchorLabel = o.anchorLabel,
                    done = o.state == com.mengpolar.dailycheck.data.PlanetState.DONE,
                    statusText = "",
                    hasImage = o.hasImage,
                )
            }
        }
        TaskEditSheet(
            initial = initial,
            pendingImage = pendingImage,
            imageSet = initial?.hasImage == true,
            existingPreview = existingPreview,
            onPickImage = pickImage,
            onRemoveImage = {
                val id = editTaskId
                if (id != null) taskVm.setPlanetImage(id, null) else pendingImage = null
            },
            onDismiss = { showEditSheet = false },
            onSave = { id, name, note, intervalDays, anchorMin, anchorWeekday, image ->
                if (id == null) {
                    taskVm.addTask(name, note, intervalDays, anchorMin, anchorWeekday, image)
                } else {
                    taskVm.editTask(id, name, note, intervalDays, anchorMin, anchorWeekday)
                }
                pendingImage = null
                showEditSheet = false
            },
        )
    }

    if (showGallery) {
        GalleryPickerDialog(
            onPick = { uri ->
                showGallery = false
                cropUri = uri
            },
            onDismiss = { showGallery = false },
        )
    }

    cropUri?.let { uri ->
        // 全屏 Dialog：盖在底部信息面板之上，选图后无需先关闭面板
        Dialog(
            onDismissRequest = { cropUri = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            CropScreen(
                uri = uri,
                onConfirm = { bmp ->
                    val id = editTaskId
                    if (id != null) taskVm.setPlanetImage(id, bmp) else pendingImage = bmp
                    cropUri = null
                },
                onCancel = { cropUri = null },
            )
        }
    }

    UpdateDialogs(updateVm)
}
