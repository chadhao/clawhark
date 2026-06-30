#!/usr/bin/env python3
"""
ClawHark Transcription Pipeline

4-phase pipeline to turn watch recordings into speaker-diarized transcripts.

Usage:
    python3 transcribe.py 2026-02-28
    python3 transcribe.py 2026-02-28 --provider gemini

Phases:
    1. Whisper  — local speech detection, filter silent chunks
    2. Segment  — group chunks into conversations by time gaps
    3. Concat   — merge related chunks into conversation audio
    4. Diarize  — speaker-separated transcription (AssemblyAI or Gemini)

Requirements:
    - ffmpeg + ffprobe
    - whisper (pip install openai-whisper) OR faster-whisper
    - AssemblyAI API key (ASSEMBLYAI_API_KEY env var) OR Gemini API key (GEMINI_API_KEY)
"""

import argparse
import glob
import json
import os
import subprocess
import sys
from datetime import datetime, timedelta
from pathlib import Path

def get_recordings_dir():
    return os.environ.get("CLAWHARK_OUTPUT", os.path.expanduser("~/.clawhark/recordings"))

def get_transcripts_dir():
    return os.environ.get("CLAWHARK_TRANSCRIPTS", os.path.expanduser("~/.clawhark/transcripts"))

def sidecar_path(chunk_path):
    """侧车元数据路径：chunk_xxx.opus → chunk_xxx.opus.json"""
    return Path(str(chunk_path) + ".json")

def load_chunk_metadata(chunk_path):
    """加载 chunk 侧车 JSON，不存在则返回 None。"""
    path = sidecar_path(chunk_path)
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text())
    except (json.JSONDecodeError, OSError) as e:
        print(f"  警告: 无法读取元数据 {path.name}: {e}")
        return None

def parse_chunk_filename_time(chunk_path):
    """从文件名解析 chunk 墙钟起点（秒级精度）。"""
    stem = Path(chunk_path).stem
    try:
        return datetime.strptime(stem.split("chunk_")[1], "%Y-%m-%d_%H-%M-%S")
    except (ValueError, IndexError):
        return None

def chunk_effective_start(chunk_path, meta=None):
    """chunk 内第一段语音的真实墙钟时间。"""
    meta = meta if meta is not None else load_chunk_metadata(chunk_path)
    if meta and meta.get("segments"):
        return datetime.fromtimestamp(meta["segments"][0]["wallClockStartMs"] / 1000)
    t = parse_chunk_filename_time(chunk_path)
    return t or datetime.min

def chunk_effective_end(chunk_path, meta=None):
    """chunk 内最后一段语音结束的真实墙钟时间。"""
    meta = meta if meta is not None else load_chunk_metadata(chunk_path)
    if meta and meta.get("segments"):
        last = meta["segments"][-1]
        end_ms = last["wallClockStartMs"] + last["durationMs"]
        return datetime.fromtimestamp(end_ms / 1000)
    t = parse_chunk_filename_time(chunk_path)
    if t:
        return t + timedelta(minutes=15)
    return datetime.min

def get_audio_duration_ms(audio_path):
    """用 ffprobe 获取音频时长（毫秒）。"""
    try:
        result = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(audio_path)],
            capture_output=True, text=True, timeout=30
        )
        if result.returncode == 0 and result.stdout.strip():
            return int(float(result.stdout.strip()) * 1000)
    except (subprocess.TimeoutExpired, FileNotFoundError, ValueError):
        pass
    return 0

def build_merged_metadata(chunks):
    """合并多个 chunk 的侧车元数据，audioOffsetMs 按拼接顺序累加。"""
    merged_segments = []
    audio_offset = 0
    sample_rate = 16000
    for chunk in chunks:
        meta = load_chunk_metadata(chunk)
        if meta:
            sample_rate = meta.get("sampleRate", sample_rate)
            for seg in meta.get("segments", []):
                merged_segments.append({
                    "wallClockStartMs": seg["wallClockStartMs"],
                    "audioOffsetMs": audio_offset + seg["audioOffsetMs"],
                    "durationMs": seg["durationMs"],
                })
        audio_offset += get_audio_duration_ms(chunk)
    if not merged_segments:
        return None
    return {"version": 1, "sampleRate": sample_rate, "segments": merged_segments}

def audio_ms_to_wall_clock(meta, audio_ms):
    """将文件内音频偏移（毫秒）映射为真实墙钟 datetime。"""
    if not meta or not meta.get("segments"):
        return None
    for seg in meta["segments"]:
        start = seg["audioOffsetMs"]
        end = start + seg["durationMs"]
        if start <= audio_ms < end:
            wall_ms = seg["wallClockStartMs"] + (audio_ms - start)
            return datetime.fromtimestamp(wall_ms / 1000)
    return None

def format_wall_clock(dt):
    if dt is None:
        return "?"
    return dt.strftime("%Y-%m-%d %H:%M:%S")

def phase1_detect_speech(date_dir, chunks):
    """Use whisper to detect which chunks have actual speech."""
    print(f"\n📝 Phase 1: Speech detection ({len(chunks)} chunks)")
    speech_chunks = []

    for chunk in chunks:
        # Quick check with whisper tiny model
        try:
            result = subprocess.run(
                ["whisper", str(chunk), "--model", "tiny", "--language", "en",
                 "--output_format", "json", "--output_dir", "/tmp/clawhark_whisper"],
                capture_output=True, text=True, timeout=30
            )
            json_out = Path(f"/tmp/clawhark_whisper/{chunk.stem}.json")
            if json_out.exists():
                data = json.loads(json_out.read_text())
                text = data.get("text", "").strip()
                if len(text) > 10:  # More than just noise
                    speech_chunks.append(chunk)
                    print(f"  ✅ {chunk.name}: {text[:60]}...")
                else:
                    print(f"  ⏭️  {chunk.name}: silent/noise")
                json_out.unlink()
        except (subprocess.TimeoutExpired, FileNotFoundError):
            # If whisper not available, include all chunks
            speech_chunks.append(chunk)

    print(f"  {len(speech_chunks)}/{len(chunks)} chunks have speech")
    return speech_chunks

def phase2_segment(chunks):
    """Group chunks into conversations based on time gaps (优先使用侧车元数据)."""
    print(f"\n🔗 Phase 2: Segmentation")
    if not chunks:
        return []

    conversations = []
    current = [chunks[0]]

    for i in range(1, len(chunks)):
        prev_meta = load_chunk_metadata(chunks[i - 1])
        curr_meta = load_chunk_metadata(chunks[i])
        prev_end = chunk_effective_end(chunks[i - 1], prev_meta)
        curr_start = chunk_effective_start(chunks[i], curr_meta)
        gap = (curr_start - prev_end).total_seconds()

        if gap > 600:  # >10 min gap = new conversation
            conversations.append(current)
            current = [chunks[i]]
            print(f"  📍 Gap of {gap/60:.0f}min → new conversation")
        else:
            current.append(chunks[i])

    conversations.append(current)
    print(f"  Found {len(conversations)} conversation(s)")
    return conversations

def phase3_concat(conversations, output_dir):
    """Concatenate chunks in each conversation into single audio files."""
    print(f"\n🔊 Phase 3: Concatenation")
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    outputs = []

    for i, conv in enumerate(conversations):
        merged_meta = build_merged_metadata(conv)
        if len(conv) == 1:
            outputs.append((conv[0], merged_meta or load_chunk_metadata(conv[0])))
            continue

        output = output_dir / f"conversation_{i+1}.opus"
        filelist = output_dir / f"filelist_{i+1}.txt"

        with open(filelist, "w") as f:
            for chunk in conv:
                f.write(f"file '{chunk.absolute()}'\n")

        subprocess.run([
            "ffmpeg", "-y", "-f", "concat", "-safe", "0",
            "-i", str(filelist), "-c", "copy", str(output)
        ], capture_output=True)

        filelist.unlink()
        print(f"  Merged {len(conv)} chunks → {output.name}")
        outputs.append((output, merged_meta))

    return outputs

def phase4_diarize(audio_entries, transcript_path, provider="assemblyai"):
    """Speaker-diarized transcription with wall-clock timestamps from sidecar metadata."""
    print(f"\n🎙️ Phase 4: Diarization ({provider})")

    transcript_path = Path(transcript_path)
    transcript_path.parent.mkdir(parents=True, exist_ok=True)

    all_text = []

    for audio, meta in audio_entries:
        print(f"  Transcribing: {audio.name}")

        if provider == "assemblyai":
            text = _diarize_assemblyai(audio, meta)
        elif provider == "gemini":
            text = _diarize_gemini(audio, meta)
        else:
            text = f"Unknown provider: {provider}"

        all_text.append(text)

    full_transcript = "\n\n---\n\n".join(all_text)
    transcript_path.write_text(full_transcript)
    print(f"\n✅ Transcript saved: {transcript_path}")
    return transcript_path

def _format_utterance_time(meta, start_ms):
    """优先输出墙钟时间，无元数据时回退为音频内偏移秒数。"""
    wall = audio_ms_to_wall_clock(meta, start_ms)
    if wall:
        return format_wall_clock(wall)
    return f"{start_ms / 1000:.0f}s"

def _diarize_assemblyai(audio_path, meta=None):
    """Transcribe with AssemblyAI Universal-3 + speaker diarization."""
    import requests

    api_key = os.environ.get("ASSEMBLYAI_API_KEY")
    if not api_key:
        return "Error: ASSEMBLYAI_API_KEY not set"

    headers = {"authorization": api_key}

    # Upload
    with open(audio_path, "rb") as f:
        upload = requests.post("https://api.assemblyai.com/v2/upload",
                             headers=headers, data=f)
    upload_url = upload.json()["upload_url"]

    # Transcribe with diarization
    resp = requests.post("https://api.assemblyai.com/v2/transcript",
                        headers=headers,
                        json={"audio_url": upload_url, "speaker_labels": True})
    transcript_id = resp.json()["id"]

    # Poll
    while True:
        result = requests.get(f"https://api.assemblyai.com/v2/transcript/{transcript_id}",
                            headers=headers).json()
        if result["status"] == "completed":
            break
        elif result["status"] == "error":
            return f"Error: {result.get('error', 'unknown')}"
        import time; time.sleep(3)

    # Format with speakers and wall-clock time
    lines = []
    for utterance in result.get("utterances", []):
        speaker = utterance["speaker"]
        text = utterance["text"]
        start = utterance["start"]
        time_label = _format_utterance_time(meta, start)
        lines.append(f"**Speaker {speaker}** ({time_label}): {text}")

    return "\n\n".join(lines) if lines else result.get("text", "No text")

def _diarize_gemini(audio_path, meta=None):
    """Transcribe with Gemini multimodal."""
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        return "Error: GEMINI_API_KEY not set"

    import base64, requests

    with open(audio_path, "rb") as f:
        audio_b64 = base64.b64encode(f.read()).decode()

    resp = requests.post(
        f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={api_key}",
        json={
            "contents": [{"parts": [
                {"inlineData": {"mimeType": "audio/wav", "data": audio_b64}},
                {"text": "Transcribe this audio with speaker diarization. Format as:\n**Speaker A** (timestamp): text\n\nIdentify different speakers and label them consistently."}
            ]}]
        }
    )

    result = resp.json()
    try:
        return result["candidates"][0]["content"]["parts"][0]["text"]
    except (KeyError, IndexError):
        return f"Error: {json.dumps(result)[:200]}"

def main():
    parser = argparse.ArgumentParser(description="ClawHark Transcription Pipeline")
    parser.add_argument("date", help="Date to transcribe (YYYY-MM-DD)")
    parser.add_argument("--provider", default="assemblyai", choices=["assemblyai", "gemini"],
                       help="Transcription provider (default: assemblyai)")
    parser.add_argument("--recordings", default=None, help="Recordings directory")
    parser.add_argument("--transcripts", default=None, help="Transcripts directory")
    args = parser.parse_args()

    recordings_dir = Path(args.recordings or get_recordings_dir())
    transcripts_dir = Path(args.transcripts or get_transcripts_dir())

    date_dir = recordings_dir / args.date
    if not date_dir.exists():
        print(f"No recordings found for {args.date} in {recordings_dir}")
        sys.exit(1)

    chunks = sorted(date_dir.glob("chunk_*.opus")) + sorted(date_dir.glob("chunk_*.wav")) + sorted(date_dir.glob("chunk_*.m4a"))
    if not chunks:
        print(f"No audio chunks found in {date_dir}")
        sys.exit(1)

    print(f"🎧 ClawHark Transcription Pipeline")
    print(f"   Date: {args.date}")
    print(f"   Chunks: {len(chunks)}")
    print(f"   Provider: {args.provider}")

    # Phase 1: Speech detection
    speech_chunks = phase1_detect_speech(date_dir, chunks)
    if not speech_chunks:
        print("\nNo speech detected in any chunks.")
        sys.exit(0)

    # Phase 2: Segment into conversations
    conversations = phase2_segment(speech_chunks)

    # Phase 3: Concatenate
    concat_dir = date_dir / "concat"
    audio_files = phase3_concat(conversations, concat_dir)

    # Phase 4: Diarize
    transcript_path = transcripts_dir / f"{args.date}-diarized.md"
    phase4_diarize(audio_files, transcript_path, args.provider)

if __name__ == "__main__":
    main()
