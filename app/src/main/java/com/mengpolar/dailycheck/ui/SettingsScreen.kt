package com.mengpolar.dailycheck.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpolar.dailycheck.data.TaskViewModel
import com.mengpolar.dailycheck.update.UpdateManager
import com.mengpolar.dailycheck.update.UpdateState
import com.mengpolar.dailycheck.update.UpdateViewModel

/** 设置内容：「系统控制单元」设备面板（由 MainActivity 的 ModalBottomSheet 承载） */
@Composable
fun SettingsScreen(
    vm: UpdateViewModel,
    taskVm: TaskViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val autoCheck by vm.autoCheck.collectAsState()
    val showNames by taskVm.showPlanetNames.collectAsState()
    val context = LocalContext.current

    DeviceShell(modifier = Modifier.fillMaxSize(), nameplate = "系统控制单元") {
        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    "系统设置",
                    color = TermBlueBright, fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace, letterSpacing = 3.sp,
                )
                Text(
                    "_",
                    color = TermBlueBright.copy(alpha = blink),
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                BracketButton("[ 关闭 ]", onClick = onBack)
            }

            Text(
                "通用 >",
                color = TermBlueDim, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
            )
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "自动检查更新",
                            color = TermInk, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "启动时检查 GitHub 上的最新版本",
                            color = TermBlueDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = autoCheck,
                        onCheckedChange = { vm.setAutoCheck(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TermBlueBright,
                            checkedTrackColor = TermBlue.copy(alpha = 0.45f),
                            checkedBorderColor = TermBlue,
                            uncheckedThumbColor = TermBlueDim,
                            uncheckedTrackColor = Color(0x2AFFFFFF),
                            uncheckedBorderColor = TermBlueDim.copy(alpha = 0.5f),
                        ),
                    )
                }
                RowDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "行星名称",
                            color = TermInk, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "隐藏或展示主页行星下方的任务名称",
                            color = TermBlueDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = showNames,
                        onCheckedChange = { taskVm.setShowPlanetNames(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TermBlueBright,
                            checkedTrackColor = TermBlue.copy(alpha = 0.45f),
                            checkedBorderColor = TermBlue,
                            uncheckedThumbColor = TermBlueDim,
                            uncheckedTrackColor = Color(0x2AFFFFFF),
                            uncheckedBorderColor = TermBlueDim.copy(alpha = 0.5f),
                        ),
                    )
                }
            }

            Text(
                "关于 >",
                color = TermBlueDim, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp,
            )
            Column {
                SettingRow("当前版本") {
                    Text(
                        "v" + UpdateManager.currentVersion(),
                        color = TermBlue, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    )
                }
                RowDivider()
                SettingRow("检查更新", onClick = { vm.checkForUpdate(manual = true) }) {
                    when (val s = state) {
                        is UpdateState.Checking ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = TermBlue,
                            )
                        is UpdateState.UpToDate ->
                            RowText("已是最新", TermBlueDim)
                        is UpdateState.Failed ->
                            RowText("连接失败", TermRed)
                        is UpdateState.Available ->
                            RowText("有新版本", TermBlueBright)
                        else ->
                            RowText("待命", TermBlueDim)
                    }
                }
                RowDivider()
                SettingRow("GitHub 仓库", onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManager.REPO_URL)))
                    }
                }) {
                    BracketButton("[ 打开 ]", onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateManager.REPO_URL)))
                        }
                    })
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun RowText(text: String, color: Color) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.sp,
    )
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(TermBlueDim.copy(alpha = 0.25f)),
    )
}

@Composable
private fun SettingRow(
    label: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = TermInk,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}
