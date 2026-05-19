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
| ☁️ **多云存储支持** | 支持 Google Drive 或 S3 兼容存储(七牛云、阿里云等),5 分钟 WAV 音频块通过 WiFi 上传,上传后自动删除 |
| 🔄 **启动持久化** | 手表重启后自动恢复录音 |
| 🎯 **单按钮界面** | 点击开始,双击停止。就这么简单。 |
| 📱 **无需配套应用** | 完全独立运行在手表上 |
| 🔒 **隐私优先** | `drive.file` 权限范围——只能访问自己创建的文件。无分析,无追踪 |

## 🔄 工作原理

> **手表** → 通过 VAD 全天候录音 → **云存储(Google Drive/S3)** → 自动上传 5 分钟音频块 → **你的电脑** → 拉取、转录、输入 AI

1. **录音** — 手表持续捕捉音频,语音活动检测过滤静音
2. **上传** — 音频块上传到你的云存储中的 `ClawHark/` 文件夹
3. **拉取** — 电脑上的脚本下载并按日期整理
4. **转录** — Whisper + AssemblyAI 生成说话人分离的转录文本
5. **执行** — 你的 AI 助手读取转录文本并提取行动项目

## 🚀 快速开始

### 前置要求

- Wear OS 4+ 手表(已在 Pixel Watch 3 上测试)
- 启用了 Drive API 的 [Google Cloud 项目](https://console.cloud.google.com/)
- JDK 17 + Android SDK
- 用于手表安装的 [ADB](https://developer.android.com/tools/adb)

### 1. 设置存储

ClawHark 支持两种云存储方式:

#### 选项 A: Google Drive (国际用户)

在 [Google Cloud Console](https://console.cloud.google.com/apis/credentials) 中创建 OAuth 2.0 客户端:

- **类型:** TVs and Limited Input devices(电视和受限输入设备)
- **范围:** `drive.file`

复制 `oauth_config.json.example` → `app/src/main/assets/oauth_config.json` 并配置:

```json
{
  "storage_type": "google_drive",
  "google_drive": {
    "client_id": "YOUR_CLIENT_ID.apps.googleusercontent.com",
    "client_secret": "YOUR_CLIENT_SECRET"
  }
}
```

#### 选项 B: S3 兼容存储 (中国用户推荐)

使用任何 S3 兼容的对象存储服务,如七牛云、阿里云 OSS、腾讯云 COS 等:

```json
{
  "storage_type": "s3",
  "s3": {
    "endpoint": "https://s3.cn-east-1.qiniucs.com",
    "region": "cn-east-1",
    "bucket": "your-bucket-name",
    "access_key": "YOUR_ACCESS_KEY",
    "secret_key": "YOUR_SECRET_KEY",
    "path_prefix": "ClawHark/"
  }
}
```

**S3 兼容服务配置示例:**

| 服务商 | endpoint 示例 | 文档链接 |
|--------|--------------|---------|
| 七牛云 | `https://s3.cn-east-1.qiniucs.com` | [七牛云 S3 兼容](https://developer.qiniu.com/kodo/4086/aws-s3-compatible) |
| 阿里云 OSS | `https://oss-cn-hangzhou.aliyuncs.com` | [OSS S3 兼容](https://help.aliyun.com/document_detail/64919.html) |
| 腾讯云 COS | `https://cos.ap-guangzhou.myqcloud.com` | [COS S3 兼容](https://cloud.tencent.com/document/product/436/37421) |

### 2. 构建

```bash
git clone https://github.com/etticat/clawhark.git
cd clawhark
./gradlew assembleDebug
```

### 3. 安装到手表

#### 查看应用日志（排查问题）

**Windows PowerShell（避免乱码）：**
```powershell
# 方法 1: 使用脚本
.\view-logs.ps1

# 方法 2: 直接命令
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
adb shell "run-as ai.etti.clawhark cat files/logs/clawhark.log" | Select-Object -Last 50
```

**Linux/Mac：**
```bash
adb shell "run-as ai.etti.clawhark cat files/logs/clawhark.log" | tail -50
```

#### 启用开发者选项和无线调试

在手表上：
1. 打开 **设置** → **系统** → **关于**
2. 连续点击 **版本号** 7次，启用开发者选项
3. 返回 **设置** → **系统** → **开发者选项**
4. 启用 **无线调试**

#### 首次配对（仅需一次）

```bash
# 1. 在手表上点击"无线调试"，选择"使用配对码配对设备"
# 2. 手表会显示：
#    - 配对码（6位数字）
#    - IP地址和端口（例如：192.168.1.100:12345）

# 3. 在电脑上执行配对命令
adb pair <watch-ip>:<pairing-port>
# 示例: adb pair 192.168.1.100:12345

# 4. 输入手表上显示的6位配对码

# 5. 配对成功后，连接手表（使用无线调试主界面显示的IP和端口）
adb connect <watch-ip>:<port>
# 示例: adb connect 192.168.0.112:36371
```

**注意**：配对码端口（用于`adb pair`）和连接端口（用于`adb connect`）是不同的。配对成功后，以后只需使用`adb connect`即可。

#### 后续连接（已配对）

```bash
# 直接连接（使用无线调试主界面显示的IP和端口）
adb connect <watch-ip>:<port>

# 验证连接
adb devices

# 安装APK
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

## 🔧 调试与ADB常用命令

### 设备连接

```bash
# 首次配对（仅需一次）
# 手表上：无线调试 → 使用配对码配对设备
adb pair <watch-ip>:<pairing-port>
# 然后输入手表显示的6位配对码
# 示例: adb pair 192.168.1.100:12345

# 连接手表（无线调试）
adb connect <watch-ip>:<port>
# 示例: adb connect 192.168.1.100:5555

# 查看已连接的设备
adb devices
# 输出示例:
# List of devices attached
# 192.168.1.100:5555    device
# emulator-5554         device

# 断开指定设备
adb disconnect <watch-ip>:<port>

# 断开所有设备
adb disconnect

# 通过USB连接（如支持）
adb usb
```

### 应用安装与管理

```bash
# 安装APK
adb install app/build/outputs/apk/debug/app-debug.apk

# 安装到指定设备（多设备时）
adb -s emulator-5554 install app/build/outputs/apk/debug/app-debug.apk

# 卸载应用
adb uninstall ai.etti.clawhark

# 重新安装（保留数据）
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 启动应用
adb shell am start -n ai.etti.clawhark/.MainActivity

# 强制停止应用
adb shell am force-stop ai.etti.clawhark
```

### 日志查看

```bash
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
adb logcat | Select-String "WR\."
```

### 文件系统操作

```bash
# 查看手表上的录音文件
adb shell "run-as ai.etti.clawhark ls -la files/recordings/"

# 下载录音文件到电脑
adb shell "run-as ai.etti.clawhark cat files/recordings/recording_xxx.wav" > recording.wav

# 查看应用数据目录
adb shell "run-as ai.etti.clawhark ls -la files/"

# 查看应用日志目录
adb shell "run-as ai.etti.clawhark ls -la files/logs/"

# 清空录音文件（谨慎使用）
adb shell "run-as ai.etti.clawhark rm files/recordings/*.wav"
```

### 应用信息查询

```bash
# 检查应用是否已安装
adb shell pm list packages | grep clawhark

# 查看应用详细信息
adb shell dumpsys package ai.etti.clawhark

# 查看应用权限
adb shell dumpsys package ai.etti.clawhark | grep permission

# 查看应用存储使用情况
adb shell du -sh /data/data/ai.etti.clawhark
```

### 调试技巧

```bash
# 授予录音权限（如果未授权）
adb shell pm grant ai.etti.clawhark android.permission.RECORD_AUDIO

# 查看后台服务状态
adb shell dumpsys activity services ai.etti.clawhark

# 查看电池优化状态
adb shell dumpsys deviceidle whitelist | grep clawhark

# 模拟低电量模式（测试后台运行）
adb shell cmd battery set level 15

# 恢复正常电量
adb shell cmd battery reset
```

### 多设备管理

```bash
# 列出所有设备及其状态
adb devices -l

# 指定设备执行命令
adb -s <device-id> shell <command>

# 设置默认设备（环境变量）
export ANDROID_SERIAL=emulator-5554  # Linux/Mac
$env:ANDROID_SERIAL = "emulator-5554"  # Windows PowerShell
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
