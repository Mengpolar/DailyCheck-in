package com.mengpolar.dailycheck

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mengpolar.dailycheck.data.CheckRecord
import com.mengpolar.dailycheck.data.TaskUi
import com.mengpolar.dailycheck.data.TaskViewModel
import com.mengpolar.dailycheck.ui.CropScreen
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

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) cropUri = uri }

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

    if (showEditSheet) {
        val initial = editTaskId?.let { id ->
            orbitState.items.firstOrNull { it.id == id }?.let { o ->
                TaskUi(
                    id = o.id,
                    name = o.name,
                    note = o.note,
                    intervalDays = o.intervalDays,
                    anchorMin = o.anchorMin,
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
            onPickImage = {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onRemoveImage = {
                val id = editTaskId
                if (id != null) taskVm.setPlanetImage(id, null) else pendingImage = null
            },
            onDismiss = { showEditSheet = false },
            onSave = { id, name, note, intervalDays, anchorMin, image ->
                if (id == null) {
                    taskVm.addTask(name, note, intervalDays, anchorMin, image)
                } else {
                    taskVm.editTask(id, name, note, intervalDays, anchorMin)
                }
                pendingImage = null
                showEditSheet = false
            },
        )
    }

    cropUri?.let { uri ->
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

    UpdateDialogs(updateVm)
}
