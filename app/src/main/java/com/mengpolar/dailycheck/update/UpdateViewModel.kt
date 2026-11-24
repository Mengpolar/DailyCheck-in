package com.mengpolar.dailycheck.update

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: UpdateManager.LatestRelease) : UpdateState
    data object UpToDate : UpdateState
    data class Downloading(val release: UpdateManager.LatestRelease, val progress: Float) : UpdateState
    data class Ready(val release: UpdateManager.LatestRelease, val file: File) : UpdateState
    data class Failed(
        val message: String,
        val retry: Retry,
        val release: UpdateManager.LatestRelease? = null,
    ) : UpdateState

    enum class Retry { CHECK, DOWNLOAD }
}

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("update_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val _autoCheck = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CHECK, true))
    val autoCheck: StateFlow<Boolean> = _autoCheck

    private val _needsInstallPermission = MutableStateFlow(false)
    val needsInstallPermission: StateFlow<Boolean> = _needsInstallPermission

    private var downloadJob: Job? = null

    fun setAutoCheck(enabled: Boolean) {
        _autoCheck.value = enabled
        prefs.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
    }

    /** manual=false 时为启动静默检查：失败不打扰用户 */
    fun checkForUpdate(manual: Boolean) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val release = UpdateManager.fetchLatestRelease()
                when {
                    release == null ->
                        _state.value = if (manual) UpdateState.Failed("仓库中还没有发布版本", UpdateState.Retry.CHECK) else UpdateState.Idle
                    UpdateManager.isNewer(UpdateManager.currentVersion(), release.version) ->
                        _state.value = UpdateState.Available(release)
                    else ->
                        _state.value = if (manual) UpdateState.UpToDate else UpdateState.Idle
                }
            } catch (e: Exception) {
                _state.value = if (manual)
                    UpdateState.Failed("检查更新失败：${e.message ?: "网络异常"}", UpdateState.Retry.CHECK)
                else UpdateState.Idle
            }
        }
    }

    fun autoCheckOnLaunch() {
        if (_autoCheck.value) checkForUpdate(manual = false)
    }

    fun startDownload(release: UpdateManager.LatestRelease) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(release, 0f)
        downloadJob = viewModelScope.launch {
            try {
                val file = UpdateManager.apkFile(getApplication(), release.version)
                UpdateManager.downloadApk(release.apkUrl, file) { p ->
                    _state.value = UpdateState.Downloading(release, p)
                }
                // 下载完成先验签名：与当前安装的应用不一致即拒装（更新源可能被篡改）
                if (!UpdateManager.signatureMatchesInstalled(getApplication(), file)) {
                    file.delete()
                    _state.value = UpdateState.Failed(
                        "更新包签名校验失败，已拒绝安装（更新源可能被篡改）",
                        UpdateState.Retry.DOWNLOAD,
                        release,
                    )
                    return@launch
                }
                _state.value = UpdateState.Ready(release, file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed("下载失败：${e.message ?: "网络异常"}", UpdateState.Retry.DOWNLOAD, release)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateState.Idle
    }

    fun install(file: File) {
        val ok = UpdateManager.installApk(getApplication(), file)
        if (!ok) _needsInstallPermission.value = true
    }

    fun requestInstallPermission() {
        UpdateManager.openInstallPermissionSettings(getApplication())
        _needsInstallPermission.value = false
    }

    fun dismissPermissionPrompt() {
        _needsInstallPermission.value = false
    }

    fun dismiss() {
        if (_state.value is UpdateState.Downloading) cancelDownload()
        _state.value = UpdateState.Idle
    }

    companion object {
        private const val KEY_AUTO_CHECK = "auto_check"
    }
}
