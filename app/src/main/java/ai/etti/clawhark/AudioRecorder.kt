package ai.etti.clawhark

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AudioRecorder(
    private val context: Context,
    private val config: ServiceConfig,
    private val storageManager: StorageManager,
    private val onStatsUpdate: (AudioRecorderStats) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
        const val OPUS_BIT_RATE = 16000
        const val READ_BUFFER_SAMPLES = 16384
        const val MIC_RECOVERY_MAX_RETRIES = 5
    }
    
    data class AudioRecorderStats(
        val totalBytesEncoded: Long = 0L,
        val totalSilenceSkipped: Long = 0L,
        val totalReadErrors: Int = 0,
        val chunksWithVoice: Int = 0,
        val chunksWithoutVoice: Int = 0,
        val totalChunks: Int = 0
    )
    
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    private var recordJob: Job? = null
    
    private val stats = AudioRecorderStats()
    private var totalBytesEncoded = 0L
    private var totalSilenceSkipped = 0L
    private var totalReadErrors = 0
    private var chunksWithVoice = 0
    private var chunksWithoutVoice = 0
    private var totalChunks = 0
    
    fun start(scope: CoroutineScope): Boolean {
        if (isRecording) {
            AppLog.w(TAG, "已在录音 - 忽略启动请求")
            return false
        }
        
        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val readBufBytes = READ_BUFFER_SAMPLES * 2
        val internalBufSize = maxOf(minBufSize, readBufBytes) * 2
        
        AppLog.i(TAG, "AudioRecord minBufSize=$minBufSize, internal=$internalBufSize, readSamples=$READ_BUFFER_SAMPLES (${READ_BUFFER_SAMPLES * 1000 / SAMPLE_RATE}ms)")
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                internalBufSize
            )
        } catch (e: SecurityException) {
            AppLog.e(TAG, "致命错误: 无麦克风权限!", e)
            return false
        } catch (e: Exception) {
            AppLog.e(TAG, "致命错误: 无法创建AudioRecord", e)
            return false
        }
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            AppLog.e(TAG, "致命错误: AudioRecord未初始化 - 麦克风可能被占用")
            audioRecord?.release()
            audioRecord = null
            return false
        }
        
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClawHark::Recording").apply {
            setReferenceCounted(false)
        }
        wakeLock?.acquire()
        AppLog.i(TAG, "WakeLock已获取")
        
        try {
            audioRecord?.startRecording()
            isRecording = true
            AppLog.i(TAG, "=== 录音已开始 === sampleRate=$SAMPLE_RATE chunkDuration=${config.chunkDurationMs/1000}s vadThreshold=${config.vadThreshold} codec=Opus@${OPUS_BIT_RATE/1000}kbps")
        } catch (e: Exception) {
            AppLog.e(TAG, "致命错误: AudioRecord.startRecording()失败", e)
            isRecording = false
            audioRecord?.release()
            audioRecord = null
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            return false
        }
        
        recordJob = scope.launch {
            try {
                recordLoop()
            } catch (e: CancellationException) {
                AppLog.d(TAG, "录音循环已取消")
            } catch (e: Exception) {
                AppLog.e(TAG, "致命错误: 录音循环崩溃!", e)
            }
        }
        
        return true
    }
    
    fun stop() {
        if (!isRecording) {
            AppLog.d(TAG, "未在录音")
            return
        }
        AppLog.i(TAG, "=== 停止录音 ===")
        isRecording = false
    }
    
    fun isCurrentlyRecording() = isRecording
    
    fun getAudioRecord() = audioRecord
    
    fun isWakeLockHeld() = wakeLock?.isHeld ?: false
    
    private suspend fun recordLoop() {
        val buffer = ShortArray(READ_BUFFER_SAMPLES)
        var chunkStartTime = System.currentTimeMillis()
        var lastVoiceTime = System.currentTimeMillis()
        var hasVoiceInChunk = false
        var chunkNumber = 0
        var readsSinceLastLog = 0
        var voiceReadsSinceLastLog = 0
        var silenceReadsSinceLastLog = 0
        var maxAmplSinceLastLog = 0
        
        val pcmByteBuffer = ByteArray(READ_BUFFER_SAMPLES * 2)
        var encoder: StreamingEncoder? = null
        var pcmFed = 0L
        
        fun startNewChunk() {
            encoder?.release()
            encoder = null
            
            chunkStartTime = System.currentTimeMillis()
            hasVoiceInChunk = false
            pcmFed = 0L
            chunkNumber++
            totalChunks++
            
            wakeLock?.acquire()
            storageManager.enforceStorageLimit()
            
            AppLog.i(TAG, "新块 #$chunkNumber")
        }
        
        try {
            startNewChunk()
            
            while (isRecording) {
                val ar = audioRecord ?: break
                val read = ar.read(buffer, 0, buffer.size)
                
                if (read < 0) {
                    totalReadErrors++
                    val errorName = when (read) {
                        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                        else -> "ERROR($read)"
                    }
                    AppLog.e(TAG, "AudioRecord.read()返回 $errorName - 总错误=$totalReadErrors")
                    
                    if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        if (!recoverMicrophone()) {
                            AppLog.e(TAG, "所有 $MIC_RECOVERY_MAX_RETRIES 次麦克风恢复尝试失败 - 录音将停止")
                        }
                    } else {
                        delay(100)
                    }
                    continue
                }
                
                if (read == 0) {
                    delay(10)
                    continue
                }
                
                readsSinceLastLog++
                
                var maxAmplitude = 0
                for (i in 0 until read) {
                    val abs = kotlin.math.abs(buffer[i].toInt())
                    if (abs > maxAmplitude) maxAmplitude = abs
                }
                if (maxAmplitude > maxAmplSinceLastLog) maxAmplSinceLastLog = maxAmplitude
                val now = System.currentTimeMillis()
                
                if (maxAmplitude > config.vadThreshold) {
                    lastVoiceTime = now
                    hasVoiceInChunk = true
                    voiceReadsSinceLastLog++
                } else {
                    silenceReadsSinceLastLog++
                }
                
                val silenceDuration = now - lastVoiceTime
                if (silenceDuration < config.vadSilenceTimeoutMs) {
                    val pcmBytes = read * 2
                    for (i in 0 until read) {
                        pcmByteBuffer[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        pcmByteBuffer[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                    }
                    
                    if (encoder == null && storageManager.hasEnoughDiskSpace()) {
                        try {
                            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                                .format(Date(chunkStartTime))
                            val opusFile = File(storageManager.getChunkDir(), "chunk_${timestamp}.opus")
                            encoder = StreamingEncoder(opusFile, SAMPLE_RATE, OPUS_BIT_RATE)
                            AppLog.d(TAG, "编码器已创建: #$chunkNumber: ${opusFile.name}")
                        } catch (e: Exception) {
                            AppLog.e(TAG, "无法创建编码器 #$chunkNumber", e)
                        }
                    }
                    
                    encoder?.let { enc ->
                        try {
                            enc.feed(pcmByteBuffer, pcmBytes)
                            pcmFed += pcmBytes
                            totalBytesEncoded += pcmBytes
                        } catch (e: Exception) {
                            AppLog.e(TAG, "编码器feed错误 - 释放编码器", e)
                            enc.release()
                            encoder = null
                        }
                    }
                } else {
                    totalSilenceSkipped += read * 2
                }
                
                val readsPerInterval = (30 * SAMPLE_RATE) / READ_BUFFER_SAMPLES
                if (readsSinceLastLog >= readsPerInterval) {
                    AppLog.d(TAG, "chunk#$chunkNumber: ${pcmFed/1024}KB PCM编码, reads=$readsSinceLastLog voice=$voiceReadsSinceLastLog silence=$silenceReadsSinceLastLog maxAmpl=$maxAmplSinceLastLog")
                    readsSinceLastLog = 0
                    voiceReadsSinceLastLog = 0
                    silenceReadsSinceLastLog = 0
                    maxAmplSinceLastLog = 0
                }
                
                if (now - chunkStartTime >= config.chunkDurationMs) {
                    AppLog.i(TAG, "块 #$chunkNumber 完成 (${(now - chunkStartTime)/1000}s). hasVoice=$hasVoiceInChunk pcmFed=${pcmFed/1024}KB")
                    
                    val enc = encoder
                    if (enc != null) {
                        chunksWithVoice++
                        encoder = null
                        val encoded = enc.complete()
                        if (encoded != null) {
                            AppLog.i(TAG, "块已完成: ${encoded.name} (${encoded.length()/1024}KB)")
                        } else {
                            AppLog.e(TAG, "块编码失败 #$chunkNumber - 数据丢失")
                        }
                    } else {
                        chunksWithoutVoice++
                        AppLog.d(TAG, "块 #$chunkNumber 静音 - 未创建编码器 ($chunksWithoutVoice 静音总计)")
                    }
                    
                    updateStats()
                    startNewChunk()
                }
            }
        } finally {
            withContext(NonCancellable) {
                AppLog.i(TAG, "录音循环: 最终化和清理")
                
                val finalEnc = encoder
                if (finalEnc != null) {
                    encoder = null
                    try {
                        val encoded = finalEnc.complete()
                        if (encoded != null) {
                            AppLog.i(TAG, "最终块: ${encoded.name} (${encoded.length()/1024}KB)")
                        }
                    } catch (e: Exception) {
                        AppLog.e(TAG, "最终块完成失败", e)
                        finalEnc.release()
                    }
                } else {
                    AppLog.d(TAG, "最终块无声音 - 无需完成编码器")
                }
                
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                audioRecord = null
                
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                
                updateStats()
                AppLog.d(TAG, "录音循环: 清理完成")
            }
        }
    }
    
    private suspend fun recoverMicrophone(): Boolean {
        AppLog.e(TAG, "DEAD OBJECT - 麦克风被其他应用占用。尝试恢复...")
        val ar = audioRecord ?: return false
        
        try { ar.stop() } catch (_: Exception) {}
        try { ar.release() } catch (_: Exception) {}
        audioRecord = null
        
        for (attempt in 1..MIC_RECOVERY_MAX_RETRIES) {
            val backoffMs = minOf(5000L * (1L shl (attempt - 1)), 60_000L)
            AppLog.i(TAG, "麦克风恢复尝试 $attempt/$MIC_RECOVERY_MAX_RETRIES 在 ${backoffMs/1000}秒后...")
            delay(backoffMs)
            
            if (!isRecording) return false
            
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val readBuf = READ_BUFFER_SAMPLES * 2
                val intBuf = maxOf(minBuf, readBuf) * 2
                val newAr = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    intBuf
                )
                
                if (newAr.state == AudioRecord.STATE_INITIALIZED) {
                    newAr.startRecording()
                    audioRecord = newAr
                    AppLog.i(TAG, "AudioRecord已在尝试 $attempt 时恢复")
                    return true
                } else {
                    AppLog.w(TAG, "麦克风恢复尝试 $attempt 失败 - 麦克风仍在使用")
                    newAr.release()
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "麦克风恢复尝试 $attempt 异常", e)
            }
        }
        
        return false
    }
    
    private fun updateStats() {
        onStatsUpdate(
            AudioRecorderStats(
                totalBytesEncoded = totalBytesEncoded,
                totalSilenceSkipped = totalSilenceSkipped,
                totalReadErrors = totalReadErrors,
                chunksWithVoice = chunksWithVoice,
                chunksWithoutVoice = chunksWithoutVoice,
                totalChunks = totalChunks
            )
        )
    }
}
