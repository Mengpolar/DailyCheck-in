package com.mengpolar.dailycheck.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mengpolar.dailycheck.update.UpdateState
import com.mengpolar.dailycheck.update.UpdateViewModel
import java.util.Locale

@Composable
fun UpdateDialogs(vm: UpdateViewModel) {
    val state by vm.state.collectAsState()
    val needsPermission by vm.needsInstallPermission.collectAsState()

    when (val s = state) {
        is UpdateState.Available -> {
            AlertDialog(
                onDismissRequest = { vm.dismiss() },
                title = { Text("发现新版本 v${s.release.version}") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        val meta = buildList {
                            s.release.displayDate?.let { add("发布于 $it") }
                            if (s.release.apkSize > 0) add(formatSize(s.release.apkSize))
                        }.joinToString(" · ")
                        if (meta.isNotBlank()) {
                            Text(
                                meta,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        s.release.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { notes ->
                            Text(
                                notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .heightIn(max = 160.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { vm.startDownload(s.release) }) { Text("立即更新") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismiss() }) { Text("以后再说") }
                },
            )
        }
        is UpdateState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("正在下载 v${s.release.version}") },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { s.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "已下载 ${(s.progress * 100).toInt()}%（经 gh-proxy.org 加速）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { vm.cancelDownload() }) { Text("取消") }
                },
            )
        }
        is UpdateState.Ready -> {
            AlertDialog(
                onDismissRequest = { vm.dismiss() },
                title = { Text("下载完成") },
                text = { Text("新版本 v${s.release.version} 已下载完成，是否立即安装？\n\n若系统提示，请允许「安装未知应用」。") },
                confirmButton = {
                    TextButton(onClick = { vm.install(s.file) }) { Text("立即安装") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismiss() }) { Text("稍后") }
                },
            )
        }
        is UpdateState.Failed -> {
            AlertDialog(
                onDismissRequest = { vm.dismiss() },
                title = { Text("更新失败") },
                text = { Text(s.message) },
                confirmButton = {
                    TextButton(onClick = {
                        when (s.retry) {
                            UpdateState.Retry.CHECK -> vm.checkForUpdate(manual = true)
                            UpdateState.Retry.DOWNLOAD -> s.release?.let { vm.startDownload(it) }
                        }
                    }) { Text("重试") }
                },
                dismissButton = {
                    TextButton(onClick = { vm.dismiss() }) { Text("关闭") }
                },
            )
        }
        else -> {}
    }

    if (needsPermission) {
        AlertDialog(
            onDismissRequest = { vm.dismissPermissionPrompt() },
            title = { Text("需要安装权限") },
            text = { Text("请在系统设置中允许「每日打卡」安装未知应用，然后重新点击安装。") },
            confirmButton = {
                TextButton(onClick = { vm.requestInstallPermission() }) { Text("去设置") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissPermissionPrompt() }) { Text("取消") }
            },
        )
    }
}

private fun formatSize(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
