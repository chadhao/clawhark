# ClawHark — Always-On Audio Recording for Wear OS

> **注意：** 本文档为早期产品规格草案，多项细节已过时。请以 [README.md](README.md) 与 [CLAUDE.md](CLAUDE.md) 为准。

## 当前实现摘要（2026-07）

| 项目 | 早期规格 | 当前实现 |
|------|----------|----------|
| 音频格式 | OGG/Opus（草案） | Opus OGG，16 kHz  mono |
| 块时长 | 5 分钟 | 15 分钟（生产）/ 2 分钟（调试） |
| 云同步 | 无 | Google Drive 或 S3 兼容存储 |
| 拉取方式 | 手表 HTTP :8080 | 云存储 + `scripts/pull.sh`；S3 可选 omi_mini 自动处理 |
| 侧车元数据 | 无 | `.opus.json` 墙钟时间轴（v1.1.0+） |
| 上传通知 | 无 | `upload_notify` → ntfy → omi_mini sync-pipeline（仅 S3） |

---

## What This Is (Original Spec)

A Wear OS app for Pixel Watch 3 that continuously records audio 24/7. Simple on/off toggle UI. An external trigger pulls recordings from cloud storage.

## Requirements (Original — partially implemented)

### Core
- **Always-on recording** via a Wear OS foreground service with microphone access
- **On/off toggle** — single screen with a big toggle button
- **Persistent notification** (required by Android for foreground mic service)
- **Voice Activity Detection (VAD)** — only save chunks when someone is speaking
- **Chunked storage** — save audio in timed chunks locally on watch storage
- **Cloud upload** — implemented via WorkManager (Drive or S3), not watch HTTP server

### Technical
- **Target:** Wear OS 4+ (API 33+), Pixel Watch 3
- **Language:** Kotlin
- **Storage location:** app-specific internal storage, then cloud

### Pull Mechanism (Original)

Option A (watch HTTP server) — **not implemented** as primary path.

Option B (ADB) — still valid for debugging:

```bash
adb shell "run-as ai.etti.clawhark ls files/recordings/"
```

Production path: cloud upload → `scripts/pull.sh` or omi_mini S3 sync.

## Build

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Related Docs

- [README.md](README.md) — 用户文档、配置、omi_mini 联调
- [CLAUDE.md](CLAUDE.md) — AI/开发者参考
- [openclaw/README.md](openclaw/README.md) — OpenClaw 集成
- [../omi_mini/docs/ntfy配置.md](../omi_mini/docs/ntfy配置.md) — 手表 upload_complete 与 omi_mini 后端
