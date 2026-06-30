package ai.etti.clawhark

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 侧车 JSON 元数据：记录 chunk 内各语音段的真实墙钟时间与音频偏移。
 * 文件名格式：chunk_YYYY-MM-DD_HH-mm-ss.opus.json
 */
object ChunkMetadata {
    const val VERSION = 1
    const val SIDECAR_SUFFIX = ".json"

    data class Segment(
        val wallClockStartMs: Long,
        val audioOffsetMs: Long,
        val durationMs: Long
    )

    class Builder(
        private val audioFile: File,
        private val chunkWallClockStartMs: Long,
        private val sampleRate: Int
    ) {
        private val segments = mutableListOf<Segment>()
        private var openSegmentWallClockStartMs: Long? = null
        private var openSegmentStartPcmBytes: Long = 0
        private var pcmFedBytes: Long = 0

        fun addPcmBytes(bytes: Int) {
            pcmFedBytes += bytes
        }

        /** 进入写入窗口（VAD 未超时）时调用；长静音后的新段会在此开启 */
        fun onFeeding(lastVoiceTimeMs: Long) {
            if (openSegmentWallClockStartMs == null) {
                openSegmentWallClockStartMs = lastVoiceTimeMs
                openSegmentStartPcmBytes = pcmFedBytes
            }
        }

        /** 长静音超时、块结束或录音停止时关闭当前段 */
        fun onLongSilence() = closeOpenSegment()

        fun closeOpenSegment() {
            val startMs = openSegmentWallClockStartMs ?: return
            val pcmDuration = pcmFedBytes - openSegmentStartPcmBytes
            val durationMs = pcmBytesToMs(pcmDuration)
            if (durationMs > 0) {
                segments.add(
                    Segment(
                        wallClockStartMs = startMs,
                        audioOffsetMs = pcmBytesToMs(openSegmentStartPcmBytes),
                        durationMs = durationMs
                    )
                )
            }
            openSegmentWallClockStartMs = null
        }

        fun write(): File? {
            closeOpenSegment()
            if (segments.isEmpty()) return null

            val sidecar = sidecarFileFor(audioFile)
            val json = JSONObject().apply {
                put("version", VERSION)
                put("audioFile", audioFile.name)
                put("chunkWallClockStartMs", chunkWallClockStartMs)
                put("sampleRate", sampleRate)
                put("segments", JSONArray().apply {
                    for (seg in segments) {
                        put(JSONObject().apply {
                            put("wallClockStartMs", seg.wallClockStartMs)
                            put("audioOffsetMs", seg.audioOffsetMs)
                            put("durationMs", seg.durationMs)
                        })
                    }
                })
            }
            sidecar.writeText(json.toString(2))
            AppLog.i("ChunkMetadata", "侧车元数据已写入: ${sidecar.name} (${segments.size} 段)")
            return sidecar
        }

        private fun pcmBytesToMs(bytes: Long): Long = bytes * 1000 / (sampleRate * 2L)
    }

    fun sidecarFileFor(audioFile: File): File =
        File(audioFile.parent, audioFile.name + SIDECAR_SUFFIX)
}
