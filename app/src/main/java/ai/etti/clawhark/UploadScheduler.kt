package ai.etti.clawhark

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class UploadScheduler(
    private val context: Context,
    private val config: ServiceConfig
) {
    companion object {
        private const val TAG = "UploadScheduler"
        const val UPLOAD_FALLBACK_WORK_NAME = "upload_fallback"
        const val IMMEDIATE_WORK_NAME = "upload_immediate"
    }
    
    fun schedulePeriodicUploads() {
        val wm = WorkManager.getInstance(context)

        val uploadConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
            
        val uploadWork = PeriodicWorkRequestBuilder<UploadWorker>(
            config.uploadIntervalMinutes, TimeUnit.MINUTES
        ).setConstraints(uploadConstraints).build()
        
        wm.enqueueUniquePeriodicWork(
            UploadWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            uploadWork
        )

        val fallbackConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
            
        val fallbackWork = if (config.uploadFallbackIntervalHours > 0) {
            PeriodicWorkRequestBuilder<UploadWorker>(
                config.uploadFallbackIntervalHours, TimeUnit.HOURS
            ).setConstraints(fallbackConstraints).build()
        } else {
            PeriodicWorkRequestBuilder<UploadWorker>(
                config.uploadFallbackIntervalMinutes, TimeUnit.MINUTES
            ).setConstraints(fallbackConstraints).build()
        }
        
        wm.enqueueUniquePeriodicWork(
            UPLOAD_FALLBACK_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            fallbackWork
        )

        val fallbackInterval = if (config.uploadFallbackIntervalHours > 0) {
            "${config.uploadFallbackIntervalHours}小时"
        } else {
            "${config.uploadFallbackIntervalMinutes}分钟"
        }
        AppLog.i(TAG, "上传已调度: 每 ${config.uploadIntervalMinutes}分钟 (仅WiFi) + 每 ${fallbackInterval} (备用WiFi)")
    }
    
    fun cancelPeriodicUploads() {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UploadWorker.WORK_NAME)
        wm.cancelUniqueWork(UPLOAD_FALLBACK_WORK_NAME)
    }
    
    fun triggerImmediateUpload() {
        val oneTimeWork = OneTimeWorkRequestBuilder<UploadWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            oneTimeWork
        )
        AppLog.i(TAG, "立即上传已触发")
    }
}
