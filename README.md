<p align="center">
  <img src="icon.png" width="120" height="120" alt="ClawHark logo" />
</p>

<h1 align="center">ClawHark</h1>

<p align="center">
  <strong>将任何 Wear OS 手表变成 AI 可穿戴设备。</strong><br>
  开源 · 无订阅 · 数据属于你
</p>

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=ai.etti.clawhark"><img src="https://img.shields.io/badge/Google%20Play-Download-red.svg?logo=google-play" alt="Google Play" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-red.svg" alt="MIT License" /></a>
  <a href="https://developer.android.com/wear"><img src="https://img.shields.io/badge/platform-Wear%20OS%204%2B-green.svg" alt="Wear OS" /></a>
  <a href="https://github.com/etticat/clawhark/releases"><img src="https://img.shields.io/badge/version-1.0.0-blue.svg" alt="Version" /></a>
</p>

---

类似 [Omi](https://omi.me)、[Limitless](https://limitless.ai) 或 [Bee](https://bee.computer)——但运行在你已拥有的硬件上。

ClawHark 在后台录制你的日常活动,过滤静音,上传到你的 Google Drive,并输入任何 AI 转录管道。与 [OpenClaw](https://github.com/openclaw/openclaw) 配对,实现完全自动化的可穿戴 AI 设置。

## ✨ 功能特性

| 功能 | 详情 |
|---------|---------|
| 🎙️ **始终开启录音** | 前台服务配合唤醒锁——可在息屏和重启后继续运行 |
| 🔇 **语音活动检测** | 仅在有人说话时保存音频——节省电量和存储空间 |
| ☁️ **自动上传 Google Drive** | 5 分钟 WAV 音频块通过 WiFi 上传,上传后自动删除 |
| 🔄 **启动持久化** | 手表重启后自动恢复录音 |
| 🎯 **单按钮界面** | 点击开始,双击停止。就这么简单。 |
| 📱 **无需配套应用** | 完全独立运行在手表上 |
| 🔒 **隐私优先** | `drive.file` 权限范围——只能访问自己创建的文件。无分析,无追踪 |

## 🔄 工作原理

> **手表** → 通过 VAD 全天候录音 → **Google Drive** → 自动上传 5 分钟音频块 → **你的电脑** → 拉取、转录、输入 AI

1. **录音** — 手表持续捕捉音频,语音活动检测过滤静音
2. **上传** — 音频块上传到你的 Google Drive 中的 `ClawHark/` 文件夹
3. **拉取** — 电脑上的脚本下载并按日期整理
4. **转录** — Whisper + AssemblyAI 生成说话人分离的转录文本
5. **执行** — 你的 AI 助手读取转录文本并提取行动项目

## 🚀 快速开始

### 前置要求

- Wear OS 4+ 手表(已在 Pixel Watch 3 上测试)
- 启用了 Drive API 的 [Google Cloud 项目](https://console.cloud.google.com/)
- JDK 17 + Android SDK
- 用于手表安装的 [ADB](https://developer.android.com/tools/adb)

### 1. 设置 OAuth

在 [Google Cloud Console](https://console.cloud.google.com/apis/credentials) 中创建 OAuth 2.0 客户端:

- **类型:** TVs and Limited Input devices(电视和受限输入设备)
- **范围:** `drive.file`

复制 `oauth_config.json.example` → `app/src/main/assets/oauth_config.json` 并填入你的凭据:

```json
{
  "client_id": "YOUR_CLIENT_ID.apps.googleusercontent.com",
  "client_secret": "YOUR_CLIENT_SECRET"
}
```

### 2. 构建

```bash
git clone https://github.com/etticat/clawhark.git
cd clawhark
./gradlew assembleDebug
```

### 3. 安装到手表

```bash
# 在手表上启用无线调试:
# 设置 → 开发者选项 → 无线调试

adb connect <watch-ip>:<port>
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 4. 开始录音

在你的手表上打开 **ClawHark** → **关联**你的 Google Drive → 点击 **Start**。完成。

## 🤖 与 OpenClaw 配合使用

[OpenClaw](https://github.com/openclaw/openclaw) 将 ClawHark 转变为完全自动化的 AI 可穿戴管道。查看**[完整的 OpenClaw 设置指南](openclaw/)**了解详细说明,或快速开始:

### 安装技能

```bash
cp -r openclaw/skills/clawhark ~/.openclaw/skills/
```

### 添加拉取定时任务

```bash
openclaw cron create \
  --name "ClawHark Pull" \
  --cron "*/30 8-23 * * *" \
  --message "Run scripts/pull.sh from the ClawHark repo to sync watch recordings"
```

### 完整流程

```
会议进行中 → 手表录音
  → Drive 上传(自动)
  → OpenClaw 拉取 + 转录(定时任务)
  → AI 提取:"你告诉 Sarah 会在周五前发送提案"
  → 创建任务 → Telegram 通知
```

查看 [openclaw/README.md](openclaw/README.md) 了解完整的集成指南,包括转录设置、心跳自动化和行动项提取。

## 🔧 调试

```bash
# 查看日志
adb shell "run-as ai.etti.clawhark cat files/logs/clawhark.log" | tail -50

# 实时 logcat
adb logcat -s "CH.Service" "CH.Drive" "CH.Auth"

# 检查手表上的录音
adb shell "run-as ai.etti.clawhark ls -la files/recordings/"
```

<details>
<summary><strong>常见问题</strong></summary>

| 问题 | 原因 | 解决方法 |
|---------|-------|-----|
| 所有音频块都是静音 | VAD 阈值过高 | 在 `RecordingService.kt` 中降低 `VAD_THRESHOLD` |
| 上传失败 | WiFi 断开 | 检查手表 WiFi 设置,禁用省电模式 |
| `ERROR_DEAD_OBJECT` | 电话占用了麦克风 | 通话结束后自动恢复 |
| 服务被杀死 | 内存压力 | 为 ClawHark 禁用电池优化 |
| 重启后无录音 | 启动接收器 | 手动启动一次应用 |

</details>

## 📁 项目结构

```
clawhark/
├── app/src/main/
│   ├── assets/
│   │   └── oauth_config.json.example    # OAuth 凭据模板
│   ├── java/.../
│   │   ├── AppLog.kt                    # 持久化文件日志记录器
│   │   ├── AuthManager.kt              # 设备代码 OAuth2 流程
│   │   ├── DriveUploader.kt            # Google Drive 上传
│   │   ├── MainActivity.kt             # 单按钮界面
│   │   └── RecordingService.kt         # 音频捕获、VAD、分块
│   └── res/                             # 图标、布局、颜色
├── openclaw/
│   ├── skills/clawhark/SKILL.md         # OpenClaw 技能定义
│   └── README.md                        # OpenClaw 集成指南
├── scripts/
│   ├── pull.sh                          # 从 Google Drive 拉取录音
│   └── transcribe.py                    # 4 阶段转录管道
├── store-listing/                       # Play Store 资源
├── icon.png                             # 应用图标(源文件)
├── PRIVACY.md                           # 隐私政策
├── LICENSE                              # MIT 许可证
└── README.md
```

## 🔐 隐私与安全

- **无服务器** — 音频路径:手表 → 你的 Drive → 你的电脑
- **无分析** — 零追踪,零遥测
- **权限限定的 OAuth** — `drive.file` 意味着应用只能访问自己创建的文件
- **自动删除链** — 上传后手表删除,拉取后 Drive 删除
- **开源** — 你可以亲自阅读每一行代码

## 🤔 为什么不选 Omi / Limitless / Bee?

| | ClawHark | 专用可穿戴设备 |
|---|---|---|
| **硬件** | 你已拥有的手表 | 额外设备($99-299) |
| **订阅** | 永久免费 | $10-24/月 |
| **数据** | 你的 Drive,你的电脑 | 他们的云 |
| **转录** | 你的选择(Whisper、AssemblyAI 等) | 他们的管道 |
| **可定制性** | 完全开源 | 闭源 |
| **AI 集成** | 任意(OpenClaw、ChatGPT、Claude...) | 仅他们的应用 |

## 🤝 贡献

欢迎提交 PR。这个应用有意保持简单——只有几百行 Kotlin 代码。

**适合新手的贡献方向:**
- 支持更多手表(Galaxy Watch、TicWatch)
- 替代上传后端(S3、WebDAV、本地 WiFi)
- 设备端转录(Wear OS 上的 Whisper)
- 更好的 VAD 算法
- 更易设置的配套手机应用

## 📄 许可证

[MIT](LICENSE) — 你可以随意使用。
