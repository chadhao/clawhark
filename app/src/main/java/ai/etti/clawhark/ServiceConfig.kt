package ai.etti.clawhark

import android.content.Context

data class ServiceConfig(
    val chunkDurationMs: Long,
    val vadThreshold: Int,
    val vadSilenceTimeoutMs: Long,
    val uploadIntervalMinutes: Long,
    val uploadFallbackIntervalHours: Long,
    val uploadFallbackIntervalMinutes: Long,
    val statusLogIntervalMs: Long,
    val minFreeSpaceBytes: Long,
    val maxLocalStorageBytes: Long,
    val staleTmpThresholdMs: Long,
    val isDebugMode: Boolean
) {
    companion object {
        const val PREF_FILE = "clawhark"
        const val PREF_DEBUG_MODE = "debug_mode"
        
        // 生产环境配置
        private const val CHUNK_DURATION_MS_PROD = 15 * 60 * 1000L // 音频块时长: 15分钟
        private const val VAD_THRESHOLD_PROD = 600 // 语音活动检测阈值: 600 (低于此值视为静音)
        private const val VAD_SILENCE_TIMEOUT_MS_PROD = 3000L // 静音超时: 3秒 (超过3秒静音则停止录音)
        private const val UPLOAD_INTERVAL_MINUTES_PROD = 60L // 上传间隔: 60分钟
        private const val UPLOAD_FALLBACK_INTERVAL_HOURS_PROD = 4L // 备用上传间隔: 4小时
        private const val STATUS_LOG_INTERVAL_MS_PROD = 3600_000L // 状态日志输出间隔: 1小时
        private const val MIN_FREE_SPACE_BYTES_PROD = 50 * 1024 * 1024L // 最小可用空间: 50MB
        private const val MAX_LOCAL_STORAGE_BYTES_PROD = 500 * 1024 * 1024L // 本地存储上限: 500MB
        private const val STALE_TMP_THRESHOLD_MS_PROD = 20 * 60 * 1000L // 过时临时文件阈值: 20分钟
        
        // 调试模式配置
        private const val CHUNK_DURATION_MS_DEBUG = 2 * 60 * 1000L // 音频块时长: 2分钟 (快速生成文件)
        private const val VAD_THRESHOLD_DEBUG = 0 // 语音活动检测阈值: 0 (禁用静音检测)
        private const val VAD_SILENCE_TIMEOUT_MS_DEBUG = 3000L // 静音超时: 3秒
        private const val UPLOAD_INTERVAL_MINUTES_DEBUG = 15L // 上传间隔: 15分钟 (快速测试上传)
        private const val UPLOAD_FALLBACK_INTERVAL_HOURS_DEBUG = 0L // 备用上传间隔: 0小时 (使用分钟配置)
        private const val UPLOAD_FALLBACK_INTERVAL_MINUTES_DEBUG = 30L // 备用上传间隔: 30分钟
        private const val STATUS_LOG_INTERVAL_MS_DEBUG = 10 * 60_000L // 状态日志输出间隔: 10分钟 (更频繁的日志)
        private const val MIN_FREE_SPACE_BYTES_DEBUG = 10 * 1024 * 1024L // 最小可用空间: 10MB (更宽松的限制)
        private const val MAX_LOCAL_STORAGE_BYTES_DEBUG = 100 * 1024 * 1024L // 本地存储上限: 100MB (更小的存储上限)
        private const val STALE_TMP_THRESHOLD_MS_DEBUG = 5 * 60 * 1000L // 过时临时文件阈值: 5分钟 (更快的清理)
        
        fun load(context: Context): ServiceConfig {
            val isDebugMode = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .getBoolean(PREF_DEBUG_MODE, false)
                
            return if (isDebugMode) {
                ServiceConfig(
                    chunkDurationMs = CHUNK_DURATION_MS_DEBUG,
                    vadThreshold = VAD_THRESHOLD_DEBUG,
                    vadSilenceTimeoutMs = VAD_SILENCE_TIMEOUT_MS_DEBUG,
                    uploadIntervalMinutes = UPLOAD_INTERVAL_MINUTES_DEBUG,
                    uploadFallbackIntervalHours = UPLOAD_FALLBACK_INTERVAL_HOURS_DEBUG,
                    uploadFallbackIntervalMinutes = UPLOAD_FALLBACK_INTERVAL_MINUTES_DEBUG,
                    statusLogIntervalMs = STATUS_LOG_INTERVAL_MS_DEBUG,
                    minFreeSpaceBytes = MIN_FREE_SPACE_BYTES_DEBUG,
                    maxLocalStorageBytes = MAX_LOCAL_STORAGE_BYTES_DEBUG,
                    staleTmpThresholdMs = STALE_TMP_THRESHOLD_MS_DEBUG,
                    isDebugMode = true
                )
            } else {
                ServiceConfig(
                    chunkDurationMs = CHUNK_DURATION_MS_PROD,
                    vadThreshold = VAD_THRESHOLD_PROD,
                    vadSilenceTimeoutMs = VAD_SILENCE_TIMEOUT_MS_PROD,
                    uploadIntervalMinutes = UPLOAD_INTERVAL_MINUTES_PROD,
                    uploadFallbackIntervalHours = UPLOAD_FALLBACK_INTERVAL_HOURS_PROD,
                    uploadFallbackIntervalMinutes = 0L,
                    statusLogIntervalMs = STATUS_LOG_INTERVAL_MS_PROD,
                    minFreeSpaceBytes = MIN_FREE_SPACE_BYTES_PROD,
                    maxLocalStorageBytes = MAX_LOCAL_STORAGE_BYTES_PROD,
                    staleTmpThresholdMs = STALE_TMP_THRESHOLD_MS_PROD,
                    isDebugMode = false
                )
            }
        }
    }
}
