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

        /** 用最新配置强制重调度周期上传（配置保存后调用） */
        fun rescheduleFromConfig(context: Context) {
            UploadScheduler(context, ServiceConfig.load(context)).schedulePeriodicUploads(force = true)
        }
    }
    
    fun schedulePeriodicUploads(force: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        val policy = if (force) {
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
        } else {
            ExistingPeriodicWorkPolicy.UPDATE
        }

        val uploadConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
            
        val uploadWork = PeriodicWorkRequestBuilder<UploadWorker>(
            config.uploadIntervalMinutes, TimeUnit.MINUTES
        ).setConstraints(uploadConstraints).build()
        
        wm.enqueueUniquePeriodicWork(
            UploadWorker.WORK_NAME,
            policy,
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
            policy,
            fallbackWork
        )

        val fallbackInterval = if (config.uploadFallbackIntervalHours > 0) {
            "${config.uploadFallbackIntervalHours}小时"
        } else {
            "${config.uploadFallbackIntervalMinutes}分钟"
        }
        AppLog.i(
            TAG,
            "上传已调度: 每 ${config.uploadIntervalMinutes}分钟 (仅WiFi) + 每 ${fallbackInterval} (备用WiFi)" +
                if (force) " [强制刷新]" else ""
        )
    }

    fun reschedulePeriodicUploads() {
        schedulePeriodicUploads(force = true)
    }
    
    fun cancelPeriodicUploads() {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UploadWorker.WORK_NAME)
        wm.cancelUniqueWork(UPLOAD_FALLBACK_WORK_NAME)
    }
    
    /**
     * @param append true 时排队到现有立即上传之后；若上一轮已结束则重新入队（APPEND_OR_REPLACE）
     */
    fun triggerImmediateUpload(append: Boolean = false) {
        val oneTimeWork = OneTimeWorkRequestBuilder<UploadWorker>().build()
        val policy = if (append) {
            ExistingWorkPolicy.APPEND_OR_REPLACE
        } else {
            ExistingWorkPolicy.REPLACE
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            policy,
            oneTimeWork
        )
        AppLog.i(TAG, if (append) "立即上传已排队(APPEND_OR_REPLACE)" else "立即上传已触发")
    }
}
