package com.mengpolar.dailycheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mengpolar.dailycheck.data.CheckRecord
import com.mengpolar.dailycheck.data.TaskViewModel
import com.mengpolar.dailycheck.ui.HomeScreen
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
            DailyCheckTheme {
                App()
            }
        }
    }
}

@Composable
private fun App() {
    val taskVm: TaskViewModel = viewModel()
    val updateVm: UpdateViewModel = viewModel()

    val homeState by taskVm.homeState.collectAsState()
    val detailState by taskVm.detailState.collectAsState()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showEditSheet by rememberSaveable { mutableStateOf(false) }
    var editTaskId by rememberSaveable { mutableStateOf<Long?>(null) }

    // 启动时静默检查更新
    LaunchedEffect(Unit) { updateVm.autoCheckOnLaunch() }

    // 返回键处理：详情页返回首页，设置页返回
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
        else -> HomeScreen(
            state = homeState,
            onAdd = { openEdit(null) },
            onOpenTask = { taskVm.openDetail(it) },
            onCheck = checkIn,
            onUndo = { taskVm.undoCheck(it) },
            onOpenSettings = { showSettings = true },
        )
    }

    if (showEditSheet) {
        val initial = editTaskId?.let { id -> homeState.pending.firstOrNull { it.id == id } ?: homeState.done.firstOrNull { it.id == id } }
        TaskEditSheet(
            initial = initial,
            onDismiss = { showEditSheet = false },
            onSave = { id, name, note, cycleType, intervalDays ->
                if (id == null) {
                    taskVm.addTask(name, note, cycleType, intervalDays)
                } else {
                    taskVm.editTask(id, name, note, cycleType, intervalDays)
                }
                showEditSheet = false
            },
        )
    }

    UpdateDialogs(updateVm)
}
