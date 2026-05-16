# S3 上传调试指南

## 已完成的修改

### 1. 支持蜂窝网络上传（调试模式）
- ✅ 修改网络约束从 `UNMETERED`（仅WiFi）改为 `CONNECTED`（任何网络）
- ✅ 支持 LTE/蜂窝网络，适用于手表和模拟器

### 2. 添加立即上传触发
- ✅ 服务启动时自动触发一次立即上传
- ✅ 不需要等待 15 分钟的定期任务

### 3. 修复 S3 客户端配置
- ✅ 启用路径风格访问（Path-Style Access）
- ✅ 添加 S3 V4 签名支持
- ✅ 解决 SSL 证书验证错误

### 4. 缩短上传间隔
- ✅ 上传间隔改为 1 分钟（虽然系统最小是 15 分钟）

## 重新测试步骤

### 1. 重新构建并安装

```powershell
# 构建应用
.\gradlew assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. 启动应用并查看日志

```powershell
# 使用脚本查看日志（无乱码）
.\view-logs.ps1

# 或实时查看
adb logcat | Select-String "WR\."
```

### 3. 期望看到的日志

#### 启动时：
```
I/WR.StorageConfig: 配置已加载: 存储类型=S3
I/WR.Service: === SERVICE CREATED ===
I/WR.Service: 上传已调度: 每 1分钟 (任何网络) + 每 4小时 (备用)
I/WR.Service: 立即上传已触发 (测试模式)
```

#### 上传执行时：
```
I/WR.Upload: 上传 worker 已启动 — X 个文件待上传 (S3)
I/WR.Upload: 上传中: chunk_2026-05-16_13-06-57.m4a (243KB)
I/WR.S3: === 上传开始: chunk_2026-05-16_13-06-57.m4a (243KB) ===
D/WR.S3: 上传到 S3: bucket=clawhark, key=ClawHark/chunk_xxx.m4a, endpoint=https://s3.cn-south-1.qiniucs.com
I/WR.S3: === 上传成功 === chunk_xxx.m4a -> S3 key=ClawHark/chunk_xxx.m4a | 500ms | 486 KB/s
I/WR.Upload: 上传成功: chunk_xxx.m4a 耗时 500ms (1/7)
I/WR.Upload: 上传 worker 完成 — 7 成功, 0 失败,共 7 个
```

#### 如果上传失败：
```
E/WR.S3: === 上传失败 === chunk_xxx.m4a
  [错误堆栈信息]
E/WR.Upload: 上传失败: chunk_xxx.m4a 耗时 XXXms (1 次失败)
```

## 验证上传成功

### 方法 1: 查看七牛云控制台
1. 登录 https://portal.qiniu.com/
2. 进入对象存储 → 选择你的 bucket
3. 查看 `ClawHark/` 目录下是否有文件

### 方法 2: 使用七牛云命令行工具 qshell
```bash
# 下载并配置 qshell
qshell account <AccessKey> <SecretKey> <Name>

# 列出文件
qshell listbucket <bucket> ClawHark/
```

## 常见问题排查

### 1. 上传没有执行
**检查项：**
- [ ] 是否有网络连接（LTE或WiFi）
- [ ] 日志中是否看到 "立即上传已触发"
- [ ] 是否有 .m4a 文件待上传

**解决：**
```powershell
# 检查网络
adb shell "dumpsys connectivity | grep -A 5 'NetworkAgentInfo'"

# 检查待上传文件
adb shell "run-as ai.etti.clawhark ls -lh files/recordings/"
```

### 2. SSL 证书错误
**错误示例：**
```
Hostname clawhark.clawhark.s3.cn-south-1.qiniucs.com not verified
```

**原因：** S3 客户端配置问题（已修复）

### 3. 403 权限错误
**可能原因：**
- AccessKey 或 SecretKey 错误
- Bucket 名称错误
- Bucket 权限设置问题

**验证配置：**
```bash
# 查看配置文件
adb shell "run-as ai.etti.clawhark cat files/logs/clawhark.log" | Select-String "配置已加载"
```

## 生产环境配置恢复

调试完成后，请恢复以下配置：

### RecordingService.kt

```kotlin
// 上传间隔: 60分钟 (生产环境)
const val UPLOAD_INTERVAL_MINUTES = 60L
```

```kotlin
// 主要上传任务: 仅 WiFi
val uploadConstraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED) // 生产: 仅WiFi
    .build()
```

```kotlin
// 注释掉立即上传
// triggerImmediateUpload()
```

## 配置文件示例

### oauth_config.json (七牛云华南区)

```json
{
  "storage_type": "s3",
  "s3": {
    "endpoint": "https://s3.cn-south-1.qiniucs.com",
    "region": "cn-south-1",
    "bucket": "your-bucket-name",
    "access_key": "YOUR_ACCESS_KEY",
    "secret_key": "YOUR_SECRET_KEY",
    "path_prefix": "ClawHark/"
  }
}
```

## 其他 S3 兼容服务配置

### 阿里云 OSS
```json
{
  "endpoint": "https://oss-cn-hangzhou.aliyuncs.com",
  "region": "cn-hangzhou",
  "bucket": "your-bucket"
}
```

### 腾讯云 COS
```json
{
  "endpoint": "https://cos.ap-guangzhou.myqcloud.com",
  "region": "ap-guangzhou",
  "bucket": "your-bucket"
}
```
