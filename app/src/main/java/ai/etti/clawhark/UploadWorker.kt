package ai.etti.clawhark

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "Upload"
        const val WORK_NAME = "upload_recordings"
        const val STALE_THRESHOLD_MS = 10 * 60 * 1000L // 10 minutes
    }

    override suspend fun doWork(): Result {
        AppLog.init(applicationContext)
        AppLog.i(TAG, "上传任务已触发")
        AuthManager.init(applicationContext)

        val dir = File(applicationContext.filesDir, "recordings")
        if (!dir.exists()) return Result.success()

        // 首先恢复过时的 .uploading 文件 — 在任何提前返回之前
        // 这些文件来自之前崩溃的 worker。只恢复超过 10 分钟的文件
        // 以避免窃取并发 worker 正在活跃上传的文件
        recoverStaleFiles(dir)

        if (!AuthManager.isAuthenticated()) {
            AppLog.d(TAG, "未认证 — 跳过上传")
            return Result.success()
        }

        // 只上传 .m4a 文件(不上传仍在写入的 .tmp 文件)
        val files = dir.listFiles()?.filter { it.extension == "m4a" }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) {
            AppLog.d(TAG, "没有文件需要上传")
            return Result.success()
        }

        // 根据配置创建上传器
        val storageConfig = AuthManager.getStorageConfig()
        val uploader: StorageUploader = when (storageConfig?.storageType) {
            StorageType.GOOGLE_DRIVE -> DriveUploader()
            StorageType.S3 -> {
                storageConfig.s3Config?.let { S3Uploader(it) } 
                    ?: run {
                        AppLog.e(TAG, "S3 配置缺失")
                        return Result.failure()
                    }
            }
            null -> {
                AppLog.e(TAG, "存储配置缺失")
                return Result.failure()
            }
        }

        AppLog.i(TAG, "上传 worker 已启动 — ${files.size} 个文件待上传 (${uploader.getStorageInfo()})")
        var succeeded = 0
        var failed = 0
        var consecutiveFailures = 0

        for (file in files) {
            // 通过重命名为 .uploading 来占用文件 — 防止并发 worker 上传同一文件
            val uploadingFile = File(file.parent, file.name + ".uploading")
            if (!file.renameTo(uploadingFile)) {
                // 文件消失或被另一个 worker 占用
                AppLog.d(TAG, "无法占用 ${file.name} — 跳过(可能是另一个 worker)")
                continue
            }

            AppLog.i(TAG, "上传中: ${file.name} (${uploadingFile.length() / 1024}KB)")
            val startMs = System.currentTimeMillis()
            val ok = uploader.uploadFile(uploadingFile)
            val elapsed = System.currentTimeMillis() - startMs

            if (ok) {
                uploadingFile.delete()
                succeeded++
                consecutiveFailures = 0
                AppLog.i(TAG, "上传成功: ${file.name} 耗时 ${elapsed}ms ($succeeded/${files.size})")
            } else {
                // 重命名回来以便下次重试
                if (!uploadingFile.renameTo(file)) {
                    AppLog.w(TAG, "无法恢复 ${uploadingFile.name} — 文件可能孤立")
                }
                failed++
                consecutiveFailures++
                AppLog.e(TAG, "上传失败: ${file.name} 耗时 ${elapsed}ms ($failed 次失败)")
                if (consecutiveFailures >= 3) {
                    AppLog.e(TAG, "连续 3 次失败 — 停止(可能是网络问题)")
                    break
                }
            }
        }

        AppLog.i(TAG, "上传 worker 完成 — $succeeded 成功, $failed 失败,共 ${files.size} 个")
        return if (failed == 0) Result.success() else Result.retry()
    }

    private fun recoverStaleFiles(dir: File) {
        val now = System.currentTimeMillis()
        val staleUploading = dir.listFiles()?.filter {
            it.extension == "uploading" && (now - it.lastModified() > STALE_THRESHOLD_MS)
        } ?: emptyList()
        for (stale in staleUploading) {
            val ageMs = now - stale.lastModified()
            val originalName = stale.name.removeSuffix(".uploading")
            val original = File(dir, originalName)
            if (stale.renameTo(original)) {
                AppLog.i(TAG, "恢复过时的上传文件: $originalName (年龄: ${ageMs / 1000}s)")
            }
        }
    }
}
