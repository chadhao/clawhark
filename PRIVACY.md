# ClawHark 隐私政策

**最后更新:** 2026 年 2 月 28 日

## 概述

ClawHark 是一款面向 Wear OS 的开源音频录制应用。它在你的手表上录制音频并上传到**你自己的** Google Drive 账户。我们不运营任何服务器,也不收集任何数据。

## 数据收集

**ClawHark 不会向我们或任何第三方收集、存储或传输任何个人数据。**

### 应用访问的内容

| 数据 | 用途 | 去向 |
|------|---------|---------------|
| 麦克风音频 | 录制对话 | 本地保存在手表上,然后上传到你的 Google Drive |
| Google Drive(权限限定) | 录音的云备份 | 仅你的个人 Drive 账户,位于"ClawHark"文件夹中 |

### 我们不会收集的内容

- 无分析或遥测
- 无向外部服务的崩溃报告
- 无广告标识符
- 无位置数据
- 无联系信息
- 无使用情况追踪

## 数据存储和传输

1. **设备端:** 音频以 AAC 文件(M4A 容器)的形式录制并临时存储在手表上。本地日志文件(`clawhark.log`)记录应用活动元数据(时间戳、上传状态),但不记录音频内容。
2. **Google Drive:** 使用 `drive.file` 权限范围将文件上传到你的 Google Drive 中的"ClawHark"文件夹(应用只能访问自己创建的文件——无法读取你的其他 Drive 文件)
3. **自动清理:** 成功上传后删除本地文件。本地存储限制为 500 MB,优先删除最旧的文件。
4. **自动重启:** 如果你已登录并开始录音,设备重启后应用将自动恢复录音。

所有数据传输使用 HTTPS 加密。音频文件在手表或 Drive 上不进行静态加密。

## Google OAuth

ClawHark 使用 Google 的设备授权流程来关联你的 Google 账户。应用仅请求 `drive.file` 权限范围,该权限仅限于访问应用创建的文件。你可以随时通过 [Google 账户权限](https://myaccount.google.com/permissions)撤销访问权限。

## 第三方服务

应用仅使用:
- **Google Drive API** — 用于上传录音(受 [Google 隐私政策](https://policies.google.com/privacy)约束)

不包含其他第三方服务、SDK 或分析工具。

## 你的权利

- 你可以随时停止录音
- 你可以退出登录以撤销 Drive 访问权限
- 你可以从 Google Drive 中删除所有录音
- 你可以卸载应用以删除所有本地数据
- 你可以通过 Google 账户设置撤销 OAuth 访问权限

## 开源

ClawHark 完全开源。你可以检查完整的源代码来验证这些声明:
https://github.com/etticat/clawhark

## 儿童隐私

ClawHark 不面向 13 岁以下儿童。我们不会故意收集儿童数据。

## 变更

我们可能会更新此政策。变更将发布在 GitHub 仓库中,并反映在上面的"最后更新"日期中。

## 联系方式

关于此隐私政策的问题:
- GitHub Issues: https://github.com/etticat/clawhark/issues
