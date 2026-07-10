# ClawHark — Definitive Reference

> **Last updated:** 2026-07-10
> **Package:** `ai.etti.clawhark` | **Version:** 1.1.0 (versionCode 3)
> **Repo:** `git@github.com:etticat/clawhark.git` | **Branch:** `main`
> **Play Store:** https://play.google.com/store/apps/details?id=ai.etti.clawhark

---

## What Is ClawHark

An open-source Wear OS app that turns any smartwatch into an always-on AI wearable. Like Omi, Limitless, or Bee — but running on hardware you already own, with zero third-party servers.

**The loop (OpenClaw path):** Watch records → VAD filters silence → 15-min Opus chunks + sidecar JSON → auto-upload to Google Drive or S3 → pull script downloads + deletes from cloud → transcription pipeline (Whisper + AssemblyAI) → speaker-diarized transcripts → OpenClaw scans for action items.

**The loop (omi_mini path, S3 only):** Watch uploads to S3 → `UploadNotifier` POSTs ntfy `upload_complete` → omi_mini backend subscribes and runs sync-pipeline (S3 sync → STT → LLM ingest) → Web Omim / Android client.

**Etti uses this every day.** It replaced Omi completely as of March 2026. Daily coaching and action extraction can run via OpenClaw or omi_mini depending on storage backend.

---

## Architecture

### Watch App (Kotlin, modular ~2,300+ LOC)

```
app/src/main/java/ai/etti/clawhark/
├── MainActivity.kt              # Compose UI — toggle, OAuth, debug mode
├── RecordingService.kt          # Foreground service coordinator
├── AudioRecorder.kt             # AudioRecord, VAD, 15-min chunking, segment tracking
├── ChunkMetadata.kt             # Sidecar .opus.json (wall-clock segments)
├── StreamingEncoder.kt          # PCM → Opus (MediaCodec + MediaMuxer)
├── ServiceConfig.kt             # Prod/debug: chunk duration, VAD, upload intervals
├── StorageManager.kt            # Local storage limits, cleanup
├── UploadScheduler.kt           # WorkManager upload scheduling
├── UploadWorker.kt              # WiFi upload worker (.opus + .opus.json)
├── UploadNotifier.kt            # POST ntfy upload_complete (S3 + upload_notify only)
├── DriveUploader.kt             # Google Drive REST upload
├── S3Uploader.kt                # S3-compatible upload (Qiniu, Aliyun, etc.)
├── AuthManager.kt               # OAuth2 device code flow (Drive)
├── ClawHarkConfig.kt            # Unified clawhark.jsonc read/write
├── ConfigHttpServer.kt          # LAN config web UI (port 8765)
├── BootReceiver.kt              # Resume recording after reboot
├── ComplicationToggleReceiver.kt
├── RecordingComplicationService.kt
└── AppLog.kt                    # Persistent structured logging
```

### Key Technical Details

| Aspect | Detail |
|--------|--------|
| **Audio format** | Opus 16 kbps (OGG container), 16 kHz mono |
| **Chunk size** | 15 min prod / 2 min debug |
| **Sidecar metadata** | `chunk_*.opus.json` — wall-clock segment offsets |
| **VAD** | Energy-based (threshold 600 prod); skips silence within chunk |
| **Upload** | WiFi-only via WorkManager; Drive (`drive.file`) or S3 |
| **Upload notify** | Optional ntfy POST after successful S3 upload batch |
| **Config** | Single `clawhark.jsonc` (JSONC); LAN web config on :8765 |
| **Boot persist** | `RECEIVE_BOOT_COMPLETED` → auto-restart recording |
| **Min SDK** | 30 (Wear OS 3+), target 34 |
| **Tested on** | Pixel Watch 3 |
| **Battery** | ~4-6 hours active recording (with VAD savings) |

### Privacy Model
- `drive.file` scope — can only see its OWN files (Drive mode)
- S3 credentials stored on-device in `clawhark.jsonc`
- No analytics, no telemetry, no crash reporting, no ClawHark-owned servers
- ntfy/omi_mini are **user-deployed** optional integrations
- MIT licensed, fully auditable

---

## Configuration (`clawhark.jsonc`)

Template: `app/src/main/assets/clawhark.jsonc.example`

| Section | Purpose |
|---------|---------|
| `storage_type` | `"google_drive"` or `"s3"` |
| `google_drive` | OAuth client_id / client_secret |
| `s3` | endpoint, region, bucket, keys, `path_prefix` (default `ClawHark/`) |
| `recording` | `pause_on_charge`, `opus_bit_rate`, `debug_mode` |
| `upload_notify` | `enabled`, `ntfy_url`, `auth_token` — **S3 only** |

Runtime file: `filesDir/clawhark.jsonc` (copied from assets on first install).

### upload_notify → omi_mini

When `upload_notify.enabled=true` and `storage_type=s3`, `UploadWorker` calls `UploadNotifier.notify()` after at least one successful upload in the worker run.

Payload:
```json
{"v":1,"type":"upload_complete","audio_count":N,"sidecar_count":M,"storage":"s3"}
```

omi_mini backend: set `NTFY_WATCH_SUBSCRIBE_ENABLED=true`, matching `NTFY_WATCH_TOPIC` and `NTFY_WATCH_SUBSCRIBE_URL`. See `../omi_mini/docs/ntfy配置.md`.

---

## Scripts

### `scripts/pull.sh` — Download from cloud
Pulls from Google Drive `ClawHark/` folder (or configured source), organizes by date, deletes from cloud after download.

```bash
CLAWHARK_OUTPUT=~/.clawhark/recordings ./scripts/pull.sh
```

### `scripts/transcribe.py` — 4-Phase Pipeline
1. **Whisper** — local speech detection, filter silent chunks
2. **Segment** — group chunks into conversations by time gaps
3. **Concat** — merge related chunks into conversation audio
4. **Diarize** — speaker-separated transcription (AssemblyAI or Gemini); uses `.opus.json` for wall-clock timestamps

```bash
python3 scripts/transcribe.py 2026-03-07
python3 scripts/transcribe.py 2026-03-07 --provider gemini
```

---

## Build & Deploy

```bash
./gradlew assembleDebug
./gradlew assembleRelease   # needs keystore.properties
adb install app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep -E "ClawHark|UploadNotify|WR\\."
```

**Signing:** `keystore.properties` → `keystore/clawhark.jks`

---

## OpenClaw Integration

The `openclaw/` folder contains the OpenClaw skill for automating pull + transcribe + action extraction.

Daily pipeline via OpenClaw crons (e.g. 11am + 6pm "Watch Pipeline"):
1. Pull recordings from Drive
2. Transcribe with speaker diarization
3. Scan for action items, todos
4. Store transcripts at `~/.openclaw/workspace/watch-recordings/transcripts/YYYY-MM-DD-diarized.md`

**Note:** OpenClaw path assumes Drive pull. For S3 + automated backend processing, use omi_mini instead of or alongside OpenClaw.

---

## omi_mini Integration (S3 + ntfy)

| ClawHark | omi_mini |
|----------|----------|
| `s3.path_prefix` | `S3_SYNC_PREFIX` (e.g. `ClawHark/`) |
| `upload_notify.ntfy_url` topic | `NTFY_WATCH_TOPIC` |
| ntfy server base | `NTFY_WATCH_SUBSCRIBE_URL` |

Do not break the upload loop — if S3 uploads fail, recordings accumulate on watch until storage fills.

---

## Store Listing

- **Short description:** Turn any Wear OS watch into an AI wearable. Always-on recording + cloud upload.
- **Assets:** `store-listing/` — feature-graphic.png, screenshot-recording.png, LISTING.md

---

## What's Next / Ideas

- Marketing & growth, community building
- Companion phone app, more cloud backends, real-time streaming
- Tests, CI/CD, automated Play Store deployment
- On-device transcription (Whisper on Wear OS)

---

## Rules for This Codebase

1. **Read this file first** before any ClawHark work.
2. **Test on real hardware** — Wear OS emulators are unreliable for audio. Use `adb install` to the Pixel Watch.
3. **Don't break the upload loop** — cloud upload is the critical path. If recordings don't upload, they're lost when storage fills.
4. **upload_notify is best-effort** — failure must not block or rollback uploads.
5. **Keep it simple** — intentional small codebase; modular but not over-abstracted.
6. **Privacy is non-negotiable** — no analytics, no tracking, no ClawHark-operated servers. User-chosen ntfy/omi_mini endpoints are fine.
