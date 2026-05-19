package ai.etti.clawhark

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

class StreamingEncoder(
    private val finalFile: File,
    private val sampleRate: Int,
    private val bitRate: Int
) {
    companion object {
        private const val TAG = "StreamingEncoder"
    }
    
    private val tmpFile = File(finalFile.parent, finalFile.name + ".tmp")
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var presentationTimeUs = 0L
    private var totalFed = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(tmpFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG)
        } catch (e: Exception) {
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            throw e
        }
    }

    fun feed(pcmData: ByteArray, length: Int = pcmData.size) {
        var pos = 0
        var stalls = 0
        while (pos < length) {
            val inputIndex = codec.dequeueInputBuffer(1_000L)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                val size = minOf(length - pos, inputBuffer.capacity())
                inputBuffer.clear()
                inputBuffer.put(pcmData, pos, size)
                codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                presentationTimeUs += (size.toLong() * 1_000_000L) / (sampleRate * 2)
                pos += size
                stalls = 0
            } else {
                stalls++
                if (stalls > 50) {
                    AppLog.e(TAG, "编码器停滞 - 丢弃 ${pcmData.size - pos} 字节")
                    break
                }
            }
            drainOutput(blocking = false)
        }
        totalFed += pos
    }

    fun complete(): File? {
        AppLog.d(TAG, "完成编码: ${totalFed / 1024}KB PCM -> ${finalFile.name}")
        val encodeStart = System.currentTimeMillis()

        try {
            var eosSent = false
            for (i in 0 until 100) {
                val inputIndex = codec.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosSent = true
                    break
                }
                drainOutput(blocking = false)
            }
            if (!eosSent) {
                AppLog.w(TAG, "100次尝试后未能发送EOS - 文件可能被截断")
            }

            drainOutput(blocking = true)

            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { muxer.stop() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}

            val elapsed = System.currentTimeMillis() - encodeStart
            val ratio = if (totalFed > 0 && tmpFile.length() > 0)
                String.format("%.1fx", totalFed.toFloat() / tmpFile.length()) else "?"
            AppLog.i(TAG, "编码完成: ${totalFed/1024}KB -> ${tmpFile.length()/1024}KB (${ratio}压缩) 耗时${elapsed}ms")

            if (tmpFile.length() > 0) {
                if (!tmpFile.renameTo(finalFile)) {
                    AppLog.e(TAG, "重命名失败 ${tmpFile.name} -> ${finalFile.name}, 尝试复制")
                    try {
                        tmpFile.copyTo(finalFile, overwrite = true)
                        tmpFile.delete()
                    } catch (copyErr: Exception) {
                        AppLog.e(TAG, "复制备选方案也失败", copyErr)
                        return null
                    }
                }
                return finalFile
            }
            tmpFile.delete()
            return null

        } catch (e: Exception) {
            AppLog.e(TAG, "编码完成失败", e)
            release()
            tmpFile.delete()
            return null
        }
    }

    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
        tmpFile.delete()
    }

    private fun drainOutput(blocking: Boolean) {
        var iterations = 0
        val maxIterations = if (blocking) 1000 else 100
        while (iterations++ < maxIterations) {
            val timeoutUs = if (blocking) 10_000L else 0L
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputIndex)
                    if (outputBuffer == null) {
                        codec.releaseOutputBuffer(outputIndex, false)
                        continue
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> {
                    if (!blocking) return
                }
            }
        }
    }
}
