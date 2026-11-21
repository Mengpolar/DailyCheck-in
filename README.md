# 每日打卡 DailyCheck-in

深空主题的安卓打卡应用：把循环周期性任务变成**轨道上的一颗颗行星**——
主界面是一片星空轨道盘，每次打卡点亮一颗行星并拖出彗尾；
操作界面则是一台**服役多年的宇航设备**，银色金属外壳里嵌着天蓝荧光的 CRT 屏幕。

## 功能

- 🪐 **星空轨道盘**：每个任务一条独立轨道，行星沿椭圆轨道实时公转
  - 打卡后行星点亮并拖出彗尾，彗尾上星光粒子向末端漂移渐隐
  - 逾期/临近到期以红圈/琥珀脉冲提示，过期一次性任务自行消散
  - 空白区上下滑动调整轨道盘倾角（惯性滑行 + 三档吸附）
- 🎯 **聚焦模式**：点击行星推近镜头，行星居中放大
  - 长按打卡：琥珀→天蓝填充 3 秒，行星自下而上恢复色彩
  - 长按删除：票根式分隔的红色删除区，行星碎裂裂纹随进度蔓延
  - 陀螺仪质感：翻转/俯仰手机时行星明暗面移动、高光扫动、轮廓压缩
  - `<<<` / `>>>` 在行星间平滑切换镜头
- 🧭 **筛选与补位**：月球导航上的筛选键循环「全部/未完成/已完成」，
  行星渐隐消散、轨道补位让位，全程平滑插值
- 🖥 **宇航设备面板**：新建/编辑/设置/历史均为「设备模块」风格——
  金属外壳（拉丝/铆钉/划痕）+ CRT 屏幕（网格/扫描光波/半透明透视星空），
  全中文终端字段，金属反光随陀螺仪漂移
- ⬇️ **应用内更新**：启动静默检查 GitHub Releases，多镜像容错下载 APK 并校验
- 🔒 数据全部保存在本地（单 JSON 文件 + 行星图片目录），不上传任何服务器

## 代码结构

```
app/src/main/java/com/mengpolar/dailycheck/
├── MainActivity.kt          # 组装轨道页与各底部弹窗
├── data/
│   ├── Models.kt            # Task/CheckRecord + 周期时钟（行星位置与状态的唯一事实来源）
│   ├── TaskRepository.kt    # 单例仓库：JSON 原子持久化 + 行星图片存取 + 旧数据迁移
│   └── TaskViewModel.kt     # 渲染模型（OrbitTask/OrbitUiState）+ 界面设置
├── update/
│   ├── UpdateManager.kt     # GitHub API 查询 + 多源下载 + APK 校验/安装
│   ├── UpdateViewModel.kt   # 更新状态机
│   └── …
└── ui/
    ├── OrbitScreen.kt       # 轨道页主组合：状态/手势/相机/聚焦 UI
    ├── OrbitLayout.kt       # 几何：轨道半径布局/行星尺寸系数/相机变换
    ├── OrbitDraw.kt         # 绘制：行星/状态环/裂纹/星空/星光/消散残影（共享画笔）
    ├── ArcNavBar.kt         # 月球弧形导航（筛选/添加/设置）
    ├── DeviceUi.kt          # 设备面板共用组件：金属外壳/CRT 屏/终端输入行/陀螺仪
    ├── TaskEditSheet.kt     # 新建/编辑任务（任务登记单元）
    ├── TaskDetailScreen.kt  # 历史记录（航行日志单元）
    ├── SettingsScreen.kt    # 设置（系统控制单元）
    └── CropScreen.kt        # 行星图片圆形裁剪
```

设计语言约定：**外壳负责"旧"，屏幕负责"活"** ——金属是哑光磨损的，屏幕是天蓝荧光
响应式的。界面色集中定义在 `DeviceUi.kt`（天蓝主色 `#6BE3FF` / 灰黑屏底 / 荧光警示红）。

## 构建与发布

```bash
./gradlew assembleRelease          # 本地构建（签名配置见 app/build.gradle.kts）
```

版本号由 git tag 自动注入（CI 读取 tag → `versionName`/`versionCode`）：

```bash
git commit -m "feat: xxx"
git tag v0.1.1 && git push origin main v0.1.1   # 推送 tag 触发 GitHub Actions 构建发布
```

## 应用内更新机制

1. 启动时（可开关）或手动触发，调用 GitHub API `releases/latest` 比较版本号
2. 发现新版本后经多镜像（gh-proxy 系 → GitHub 直连）容错下载 APK，校验 PK 头
3. 下载完成调起系统安装器；首次安装需允许「安装未知应用」
