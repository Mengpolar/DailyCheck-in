package com.mengpolar.dailycheck.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mengpolar.dailycheck.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Duration

/**
 * 应用内更新：
 * 1. 调 GitHub Releases API 查询最新版本（releases/latest）
 * 2. 通过 gh-proxy.org 加速下载 APK
 * 3. 下载完成后调起系统安装器
 */
object UpdateManager {

    const val REPO_URL = "https://github.com/Mengpolar/DailyCheck-in"
    const val RELEASES_PAGE_URL = "https://github.com/Mengpolar/DailyCheck-in/releases"
    private const val API_URL = "https://api.github.com/repos/Mengpolar/DailyCheck-in/releases/latest"
    /** 下载加速代理：拼接在 GitHub 文件原始地址前即可 */
    const val PROXY_PREFIX = "https://gh-proxy.org/"

    /** 下载源候选（镜像前缀，空串 = GitHub 直连），按顺序逐一尝试 */
    val DOWNLOAD_MIRRORS = listOf(
        "https://gh-proxy.org/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghfast.top/",
        "https://gh.llkk.cc/",
        "https://hub.gitmirror.com/",
        "",
    )

    private val json = Json { ignoreUnknownKeys = true }
    private const val TAG = "UpdateFlow"
    private val client = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(30))
        .build()

    @Serializable
    data class AssetDto(
        val name: String = "",
        val size: Long = 0,
        val browser_download_url: String = "",
    )

    @Serializable
    data class ReleaseDto(
        val tag_name: String = "",
        val body: String? = null,
        val published_at: String? = null,
        val html_url: String = "",
        val assets: List<AssetDto> = emptyList(),
    )

    data class LatestRelease(
        val version: String,
        val apkUrl: String,
        val apkSize: Long,
        val notes: String?,
        val publishedAt: String?,
    ) {
        val proxiedApkUrl: String get() = PROXY_PREFIX + apkUrl
        val displayDate: String? get() = publishedAt?.take(10)
    }

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /** 查询最新 Release；仓库还没有 Release 时返回 null */
    suspend fun fetchLatestRelease(): LatestRelease? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(API_URL)
            .header("User-Agent", "DailyCheck-in-App")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) throw RuntimeException("GitHub API 响应异常 (${resp.code})")
            val body = resp.body?.string() ?: throw RuntimeException("响应内容为空")
            val dto = json.decodeFromString(ReleaseDto.serializer(), body)
            val apk = dto.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: throw RuntimeException("最新版本中没有找到 APK 文件")
            LatestRelease(
                version = dto.tag_name.trim().removePrefix("v"),
                apkUrl = apk.browser_download_url,
                apkSize = apk.size,
                notes = dto.body?.take(2000),
                publishedAt = dto.published_at,
            )
        }
    }

    /** latest 是否比当前安装的版本新 */
    fun isNewer(current: String, latest: String): Boolean {
        val c = parseVersion(current) ?: return false
        val l = parseVersion(latest) ?: return false
        if (l.first != c.first) return l.first > c.first
        if (l.second != c.second) return l.second > c.second
        return l.third > c.third
    }

    private fun parseVersion(v: String): Triple<Int, Int, Int>? {
        val core = v.trim().removePrefix("v").substringBefore('-')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return null
        fun part(i: Int) = parts.getOrNull(i)?.toIntOrNull() ?: 0
        return Triple(part(0), part(1), part(2))
    }

    /** 流式下载（单源）；完成后校验 APK 魔数，防止镜像 200 返回错误页 */
    private suspend fun downloadFrom(url: String, dest: File, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            val req = Request.Builder().url(url).header("User-Agent", "DailyCheck-in-App").build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    val body = resp.body ?: throw RuntimeException("下载内容为空")
                    val total = body.contentLength()
                    var read = 0L
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                out.write(buf, 0, n)
                                read += n
                                if (total > 0) onProgress((read.toDouble() / total).toFloat().coerceIn(0f, 1f))
                            }
                        }
                    }
                    if (total > 0 && read < total) throw RuntimeException("下载不完整")
                }
                if (!looksLikeApk(tmp)) {
                    throw RuntimeException("内容校验失败（非 APK）")
                }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                dest
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }

    private fun looksLikeApk(f: File): Boolean = runCatching {
        if (f.length() < 500_000L) return false
        java.io.RandomAccessFile(f, "r").use { raf ->
            val b = ByteArray(2)
            raf.readFully(b)
            b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    /**
     * 多源容错下载：镜像逐一尝试（gh-proxy → ghfast → GitHub 直连）。
     * 任一源成功即返回；全部失败时抛出含各源信息异常。
     */
    suspend fun downloadApk(url: String, dest: File, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            var lastErr: Exception? = null
            DOWNLOAD_MIRRORS.forEachIndexed { i, prefix ->
                try {
                    return@withContext downloadFrom(prefix + url, dest, onProgress)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastErr = e
                    onProgress(0f)  // 重置进度，尝试下一源
                }
            }
            throw RuntimeException(
                "全部下载源均失败（gh-proxy / ghfast / 直连），最后错误：${lastErr?.message ?: "未知"}",
                lastErr,
            )
        }

    /** 调起系统安装器；返回 false 表示缺少“安装未知应用”权限 */
    fun installApk(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        if (!context.packageManager.canRequestPackageInstalls()) return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun apkFile(context: Context, version: String): File =
        File(File(context.filesDir, "updates"), "DailyCheck-in-$version.apk")
}
