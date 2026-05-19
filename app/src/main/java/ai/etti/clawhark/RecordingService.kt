package ai.etti.clawhark

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.*
import java.io.File

class RecordingService : Service() {

    companion object {
        const val TAG = "Service"
        const val PREF_FILE = "clawhark"
        const val PREF_SHOULD_RECORD = "should_record"
        const val ACTION_STOP = "STOP"
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private lateinit var config: ServiceConfig
    private lateinit var storageManager: StorageManager
    private lateinit var uploadScheduler: UploadScheduler
    private lateinit var statusLogger: StatusLogger
    private lateinit var notificationManager: RecordingNotificationManager
    private lateinit var audioRecorder: AudioRecorder
    
    private var stats = AudioRecorder.AudioRecorderStats()
    
    @Volatile var recordingStartTime: Long = 0L
        private set

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }
    
    val totalChunks: Int
        get() = stats.totalChunks

    override fun onBind(intent: Intent): IBinder {
        AppLog.d(TAG, "onBind() called")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AppLog.d(TAG, "onUnbind() called")
        return super.onUnbind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        
        config = ServiceConfig.load(this)
        storageManager = StorageManager(this, config)
        uploadScheduler = UploadScheduler(this, config)
        statusLogger = StatusLogger(this)
        notificationManager = RecordingNotificationManager(this)
        audioRecorder = AudioRecorder(this, config, storageManager) { newStats ->
            stats = newStats
        }
        
        AppLog.i(TAG, "=== 服务已创建 ===")
        AppLog.i(TAG, "设备: ${android.os.Build.MODEL} (${android.os.Build.DEVICE})")
        AppLog.i(TAG, "Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        AppLog.i(TAG, "模式: ${if (config.isDebugMode) "调试" else "生产"}")
        AppLog.i(TAG, "Codec: AAC ${AudioRecorder.AAC_BIT_RATE/1000}kbps | Chunk: ${config.chunkDurationMs/60000}min | Upload: every ${config.uploadIntervalMinutes}min")
        
        // 调试模式输出编码器信息
        if (config.isDebugMode) {
            CodecDetector.detectAndLog()
        }
        
        statusLogger.logBatteryStatus()
        notificationManager.createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "START"
        AppLog.i(TAG, "onStartCommand() action=$action flags=$flags startId=$startId")

        when (action) {
            ACTION_STOP -> {
                AppLog.i(TAG, "停止请求 - 关闭服务")
                getSharedPreferences(PREF_FILE, MODE_PRIVATE)
                    .edit().putBoolean(PREF_SHOULD_RECORD, false).apply()
                logFinalStats()
                stopRecording()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (intent == null) {
                    val shouldRecord = getSharedPreferences(PREF_FILE, MODE_PRIVATE)
                        .getBoolean(PREF_SHOULD_RECORD, true)
                    if (!shouldRecord) {
                        AppLog.i(TAG, "START_STICKY重启但用户已停止 - 不重启")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    AppLog.i(TAG, "START_STICKY重启 - 恢复录音")
                }
                startForeground(
                    RecordingNotificationManager.NOTIFICATION_ID,
                    notificationManager.createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
                startRecording()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.i(TAG, "=== 服务已销毁 ===")
        logFinalStats()
        audioRecorder.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppLog.w(TAG, "onTaskRemoved() - 应用从最近任务中移除。服务应作为前台服务持续。")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        val levelName = when (level) {
            TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "level=$level"
        }
        AppLog.w(TAG, "onTrimMemory($levelName)")
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        AppLog.e(TAG, "onLowMemory() - 系统内存严重不足!")
        super.onLowMemory()
    }


    private fun startRecording() {
        if (audioRecorder.isCurrentlyRecording()) {
            AppLog.w(TAG, "已在录音 - 忽略")
            return
        }
        
        recordingStartTime = System.currentTimeMillis()
        notificationManager.randomizeWord()
        notificationManager.startWordRotation()
        updateComplication()
        
        statusLogger.logAudioState()
        
        storageManager.cleanupOrphanedTmpFiles()
        uploadScheduler.schedulePeriodicUploads()
        
        val success = audioRecorder.start(scope)
        if (!success) {
            AppLog.e(TAG, "录音启动失败")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        
        scope.launch {
            while (audioRecorder.isCurrentlyRecording()) {
                delay(config.statusLogIntervalMs)
                if (audioRecorder.isCurrentlyRecording()) {
                    logPeriodicStatus()
                }
            }
        }
    }

    private fun stopRecording() {
        if (!audioRecorder.isCurrentlyRecording()) {
            AppLog.d(TAG, "未在录音")
            return
        }
        AppLog.i(TAG, "=== 停止录音 ===")
        audioRecorder.stop()
        notificationManager.stopWordRotation()
        updateComplication()
        
        uploadScheduler.cancelPeriodicUploads()
        uploadScheduler.triggerImmediateUpload()
    }

    fun isCurrentlyRecording() = audioRecorder.isCurrentlyRecording()


    private fun logPeriodicStatus() {
        statusLogger.logPeriodicStatus(
            isRecording = audioRecorder.isCurrentlyRecording(),
            audioRecord = audioRecorder.getAudioRecord(),
            totalChunks = stats.totalChunks,
            chunksWithVoice = stats.chunksWithVoice,
            chunksWithoutVoice = stats.chunksWithoutVoice,
            totalBytesEncoded = stats.totalBytesEncoded,
            totalSilenceSkipped = stats.totalSilenceSkipped,
            totalReadErrors = stats.totalReadErrors,
            wakeLockHeld = audioRecorder.isWakeLockHeld(),
            recordingStartTime = recordingStartTime,
            storageUsed = storageManager.getStorageUsed(),
            freeSpace = storageManager.getChunkDir().usableSpace,
            localFiles = storageManager.getRecordings().size,
            uploadIntervalMinutes = config.uploadIntervalMinutes
        )
    }

    private fun logFinalStats() {
        statusLogger.logFinalStats(
            totalChunks = stats.totalChunks,
            chunksWithVoice = stats.chunksWithVoice,
            chunksWithoutVoice = stats.chunksWithoutVoice,
            totalBytesEncoded = stats.totalBytesEncoded,
            totalSilenceSkipped = stats.totalSilenceSkipped,
            totalReadErrors = stats.totalReadErrors,
            recordingStartTime = recordingStartTime
        )
    }

    fun getChunkDir(): File = storageManager.getChunkDir()
    
    fun getRecordings(): List<File> = storageManager.getRecordings()
    
    fun getStorageUsed(): Long = storageManager.getStorageUsed()

    private fun updateComplication() {
        try {
            ComplicationDataSourceUpdateRequester.create(
                this,
                ComponentName(this, RecordingComplicationService::class.java)
            ).requestUpdateAll()
        } catch (e: Exception) {
            AppLog.d(TAG, "Complication update failed (may not be on watch face): ${e.message}")
        }
    }
}
