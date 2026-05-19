package ai.etti.clawhark

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import android.os.BatteryManager
import android.telephony.TelephonyManager

class StatusLogger(private val context: Context) {
    companion object {
        private const val TAG = "StatusLogger"
    }
    
    fun logBatteryStatus() {
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            AppLog.d(TAG, "电池: ${level}% 充电=${bm.isCharging}")
        } catch (_: Exception) {
            AppLog.d(TAG, "电池: 无法读取")
        }
    }
    
    fun logAudioState() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val mode = when (am.mode) {
                AudioManager.MODE_NORMAL -> "NORMAL"
                AudioManager.MODE_RINGTONE -> "RINGTONE"
                AudioManager.MODE_IN_CALL -> "IN_CALL"
                AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                AudioManager.MODE_CALL_SCREENING -> "CALL_SCREENING"
                else -> "mode=${am.mode}"
            }
            AppLog.d(TAG, "音频: mode=$mode 麦克风静音=${am.isMicrophoneMute} 音乐活跃=${am.isMusicActive}")
            
            try {
                @Suppress("DEPRECATION")
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                @Suppress("DEPRECATION")
                val cs = when (tm.callState) {
                    TelephonyManager.CALL_STATE_IDLE -> "IDLE"
                    TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                    TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
                    else -> "unknown"
                }
                AppLog.d(TAG, "电话: callState=$cs")
            } catch (_: Exception) {
                AppLog.d(TAG, "电话: 无法读取")
            }
        } catch (_: Exception) {
            AppLog.d(TAG, "音频: 无法读取")
        }
    }
    
    fun logNetworkStatus() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val network = cm.activeNetwork
                val capabilities = cm.getNetworkCapabilities(network)
                if (capabilities != null) {
                    val type = when {
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙"
                        else -> "其他"
                    }
                    val metered = if (cm.isActiveNetworkMetered) "计费" else "免费"
                    AppLog.d(TAG, "网络: $type 已连接 计费状态=$metered")
                } else {
                    AppLog.d(TAG, "网络: 未连接")
                }
            } else {
                @Suppress("DEPRECATION")
                val activeNetwork = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                if (activeNetwork != null && activeNetwork.isConnected) {
                    @Suppress("DEPRECATION")
                    val type = when (activeNetwork.type) {
                        android.net.ConnectivityManager.TYPE_WIFI -> "WiFi"
                        android.net.ConnectivityManager.TYPE_MOBILE -> "移动网络"
                        android.net.ConnectivityManager.TYPE_BLUETOOTH -> "蓝牙"
                        else -> "类型=${activeNetwork.type}"
                    }
                    val metered = if (cm.isActiveNetworkMetered) "计费" else "免费"
                    AppLog.d(TAG, "网络: $type 已连接 计费状态=$metered")
                } else {
                    AppLog.d(TAG, "网络: 未连接")
                }
            }
        } catch (_: Exception) {
            AppLog.d(TAG, "网络: 无法读取")
        }
    }
    
    fun logPeriodicStatus(
        isRecording: Boolean,
        audioRecord: AudioRecord?,
        totalChunks: Int,
        chunksWithVoice: Int,
        chunksWithoutVoice: Int,
        totalBytesEncoded: Long,
        totalSilenceSkipped: Long,
        totalReadErrors: Int,
        wakeLockHeld: Boolean,
        recordingStartTime: Long,
        storageUsed: Long,
        freeSpace: Long,
        localFiles: Int,
        uploadIntervalMinutes: Long
    ) {
        val uptimeMin = (System.currentTimeMillis() - recordingStartTime) / 60000
        val localMB = String.format("%.1f", storageUsed / 1024.0 / 1024.0)
        AppLog.i(TAG, "=== 状态 (${uptimeMin}分钟运行) ===")
        AppLog.i(TAG, "  录音中: $isRecording | AudioRecord状态: ${audioRecord?.state}")
        AppLog.i(TAG, "  块: $totalChunks 总计 ($chunksWithVoice 有声, $chunksWithoutVoice 静音)")
        AppLog.i(TAG, "  PCM编码: ${totalBytesEncoded/1024/1024}MB | 跳过静音: ${totalSilenceSkipped/1024/1024}MB")
        AppLog.i(TAG, "  本地文件: $localFiles ($localMB MB) - 每 ${uploadIntervalMinutes}分钟上传")
        AppLog.i(TAG, "  读取错误: $totalReadErrors")
        AppLog.i(TAG, "  WakeLock持有: $wakeLockHeld")
        AppLog.i(TAG, "  可用空间: ${freeSpace / 1024 / 1024}MB")
        logBatteryStatus()
        logAudioState()
        logNetworkStatus()
    }
    
    fun logFinalStats(
        totalChunks: Int,
        chunksWithVoice: Int,
        chunksWithoutVoice: Int,
        totalBytesEncoded: Long,
        totalSilenceSkipped: Long,
        totalReadErrors: Int,
        recordingStartTime: Long
    ) {
        val uptimeSec = if (recordingStartTime > 0) {
            (System.currentTimeMillis() - recordingStartTime) / 1000
        } else 0
        AppLog.i(TAG, "=== 最终统计 (${uptimeSec}秒运行) ===")
        AppLog.i(TAG, "  块: $totalChunks ($chunksWithVoice 有声, $chunksWithoutVoice 静音)")
        AppLog.i(TAG, "  PCM编码: ${totalBytesEncoded/1024/1024}MB | 跳过静音: ${totalSilenceSkipped/1024/1024}MB")
        AppLog.i(TAG, "  读取错误: $totalReadErrors")
    }
}
