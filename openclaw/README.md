# ClawHark + OpenClaw

This folder contains everything you need to integrate ClawHark with [OpenClaw](https://github.com/openclaw/openclaw).

## What's here

```
openclaw/
├── skills/
│   └── clawhark/
│       └── SKILL.md     # OpenClaw skill for pull, transcribe, action extraction
└── README.md
```

## Quick Setup

### 1. Install the skill

**Option A: Copy directly**
```bash
cp -r openclaw/skills/clawhark ~/.openclaw/skills/
```

**Option B: Symlink from the repo**
```bash
ln -s $(pwd)/openclaw/skills/clawhark ~/.openclaw/skills/clawhark
```

**Option C: Via ClawHub** *(coming soon)*
```bash
clawhub install clawhark
```

### 2. Set up credentials

```bash
mkdir -p ~/.clawhark

# Create credentials (get these from the watch OAuth flow)
cat > ~/.clawhark/credentials.json << 'EOF'
{
  "client_id": "YOUR_CLIENT_ID.apps.googleusercontent.com",
  "client_secret": "YOUR_CLIENT_SECRET",
  "refresh_token": "YOUR_REFRESH_TOKEN"
}
EOF

# Set transcription API key
export ASSEMBLYAI_API_KEY="your-key"
# OR
export GEMINI_API_KEY="your-key"
```

### 3. Add automation

Add the pull cron:
```bash
openclaw cron create \
  --name "ClawHark Pull" \
  --cron "*/30 8-23 * * *" \
  --message "Run scripts/pull.sh from the ClawHark repo to sync watch recordings"
```

### 4. Start a new OpenClaw session

The skill will be picked up automatically. Try:
- *"Sync my watch recordings"*
- *"Transcribe today's audio"*
- *"What action items came from my meetings today?"*

## The Full Loop

```
You're in a meeting → watch records everything
  → ClawHark uploads to Drive (automatic)
  → OpenClaw pulls from Drive (cron, every 30min)
  → Whisper detects speech, segments conversations
  → AssemblyAI/Gemini produces speaker-diarized transcript
  → OpenClaw scans for action items
  → You get a notification: "You told Sarah you'd send the proposal by Friday"
```

## Alternative: omi_mini (S3 + ntfy)

If you use **S3-compatible storage** instead of Google Drive, you can skip the OpenClaw pull/transcribe loop and use [omi_mini](https://github.com/etticat/omi_mini) as the backend:

1. Configure ClawHark `storage_type: s3` and `upload_notify` (ntfy topic)
2. Configure omi_mini `NTFY_WATCH_SUBSCRIBE_*` and S3 sync prefix
3. Watch uploads → ntfy `upload_complete` → omi_mini auto sync-pipeline → STT + LLM memories/todos

See [ClawHark README — 与 omi_mini 配合使用](../README.md#-与-omi_mini-配合使用s3--ntfy可选) and [omi_mini ntfy 配置](../../omi_mini/docs/ntfy配置.md).

OpenClaw and omi_mini can coexist; choose based on your cloud backend (Drive vs S3).

## Links

- [ClawHark on Google Play](https://play.google.com/store/apps/details?id=ai.etti.clawhark)
- [ClawHark Source Code](https://github.com/etticat/clawhark)
- [OpenClaw Documentation](https://docs.openclaw.ai)
- [OpenClaw Skills Guide](https://docs.openclaw.ai/tools/skills.md)
