# 每日打卡 DailyCheck-in

一个简约风格的安卓应用，用于记录**循环周期性事件**的打卡：每日任务、每周任务、每月任务，或每 N 天一次的自定义周期。

## 功能

- ✅ 循环任务打卡：每天 / 每周 / 每月 / 每 N 天
- ✅ 打卡后自动进入下一个周期，支持撤销
- ✅ 打卡记录列表，累计次数统计
- ✅ 应用内更新：自动检查 GitHub Releases 新版本，通过 `gh-proxy.org` 加速下载 APK 并安装
- ✅ 数据全部保存在本地，不上传任何服务器
- 🎨 Material 3 简约设计，支持深色模式

## 应用内更新机制

1. 启动时（可开关）或手动触发，调用 GitHub API `releases/latest` 获取最新版本号，与当前版本比较
2. 发现新版本后，通过代理前缀下载 APK 加速：
   `https://gh-proxy.org/https://github.com/Mengpolar/DailyCheck-in/releases/download/vX.Y.Z/DailyCheck-in-X.Y.Z.apk`
3. 下载完成后调起系统安装器；首次安装需在系统设置中允许「安装未知应用」

## 发布新版本

版本号由 git tag 自动决定，无需手动改代码（CI 读取 tag 注入 `versionName`/`versionCode`）：

```bash
# 1. 提交代码
git add .
git commit -m "feat: xxx"

# 2. 打标签并推送（tag 决定版本号，如 v0.0.2）
git tag v0.0.2
git push origin main
git push origin v0.0.2
```

推送标签后，GitHub Actions 会自动构建 APK 并创建 Release。手机端收到更新后即可在应用内升级。

## 本地构建

需要 JDK 17 和 Android SDK（含 platform 34）：

```bash
./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

## 签名说明

为保证「应用内更新」能覆盖安装，所有版本的签名必须一致。本项目将签名文件
`keystore/release.keystore`（别名 `dailycheck`，密码 `dailycheck2026`）直接提交到仓库，
仅适用于个人项目。若仓库公开且在意安全性，建议改用 GitHub Secrets 注入 keystore 与密码。

## 技术栈

- Kotlin 2.0 + Jetpack Compose（Material 3）
- 数据存储：本地 JSON 文件（kotlinx.serialization），原子写入
- 网络：OkHttp（检查更新 + 下载）
- CI：GitHub Actions（`release.yml`，tag 触发）

## 目录结构

```
app/src/main/java/com/mengpolar/dailycheck/
├── MainActivity.kt          # 入口 + 页面导航
├── data/                    # 数据模型、本地仓库、ViewModel、周期逻辑
├── update/                  # 应用内更新（版本检查 / 下载 / 安装）
└── ui/                      # Compose 界面（首页 / 编辑 / 详情 / 设置 / 更新弹窗）
```
