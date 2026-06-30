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

        recoverStaleFiles(dir)

        if (!AuthManager.isAuthenticated()) {
            AppLog.d(TAG, "未认证 — 跳过上传")
            return Result.success()
        }

        val audioFiles = dir.listFiles()?.filter {
            it.extension == "opus" && !it.name.endsWith(".uploading")
        }?.sortedBy { it.name } ?: emptyList()

        val orphanedSidecars = findOrphanedSidecars(dir)

        if (audioFiles.isEmpty() && orphanedSidecars.isEmpty()) {
            AppLog.d(TAG, "没有文件需要上传")
            return Result.success()
        }

        val storageConfig = AuthManager.getStorageConfig()
        val networkManager = WearNetworkManager(applicationContext)
        val currentNetwork = networkManager.getCurrentNetworkInfo()
        AppLog.i(TAG, "当前网络: $currentNetwork")

        val networkResult = networkManager.requestHighBandwidthNetwork()
        if (networkResult !is WearNetworkManager.NetworkResult.Connected) {
            val reason = when (networkResult) {
                is WearNetworkManager.NetworkResult.Unavailable -> "Wi-Fi 不可用"
                is WearNetworkManager.NetworkResult.Timeout -> "网络请求超时"
                is WearNetworkManager.NetworkResult.Error -> "网络错误: ${networkResult.exception.message}"
                else -> "未知原因"
            }
            AppLog.w(TAG, "无法获取高带宽网络: $reason — 稍后重试")
            return Result.retry()
        }

        val network = networkResult.network
        AppLog.i(TAG, "高带宽网络已就绪,开始上传")

        val uploader: StorageUploader = when (storageConfig?.storageType) {
            StorageType.GOOGLE_DRIVE -> DriveUploader(network)
            StorageType.S3 -> {
                storageConfig.s3Config?.let { S3Uploader(it) }
                    ?: run {
                        networkManager.releaseNetwork()
                        AppLog.e(TAG, "S3 配置缺失")
                        return Result.failure()
                    }
            }
            null -> {
                networkManager.releaseNetwork()
                AppLog.e(TAG, "存储配置缺失")
                return Result.failure()
            }
        }

        val pairedSidecarCount = audioFiles.count { ChunkMetadata.sidecarFileFor(it).exists() }
        val totalUploadCount = audioFiles.size + pairedSidecarCount + orphanedSidecars.size

        AppLog.i(TAG, "上传 worker 已启动 — ${audioFiles.size} 个音频" +
            (if (pairedSidecarCount + orphanedSidecars.size > 0) {
                " + ${pairedSidecarCount + orphanedSidecars.size} 个元数据"
            } else "") +
            ", 共 $totalUploadCount 个文件 (${uploader.getStorageInfo()})")

        var audioSucceeded = 0
        var sidecarSucceeded = 0
        var failed = 0
        var consecutiveFailures = 0

        try {
            for (file in audioFiles) {
                val result = uploadFileWithLock(file, uploader, "音频")
                when (result) {
                    UploadResult.SUCCESS -> {
                        audioSucceeded++
                        consecutiveFailures = 0
                        val sidecar = ChunkMetadata.sidecarFileFor(file)
                        if (sidecar.exists()) {
                            when (uploadFileWithLock(sidecar, uploader, "侧车元数据")) {
                                UploadResult.SUCCESS -> sidecarSucceeded++
                                UploadResult.FAILED -> {
                                    failed++
                                    consecutiveFailures++
                                }
                                UploadResult.SKIPPED -> {}
                            }
                        }
                    }
                    UploadResult.FAILED -> {
                        failed++
                        consecutiveFailures++
                    }
                    UploadResult.SKIPPED -> {}
                }

                if (consecutiveFailures >= 3) {
                    AppLog.e(TAG, "连续 3 次失败 — 停止(可能是网络问题)")
                    break
                }
            }

            if (orphanedSidecars.isNotEmpty() && consecutiveFailures < 3) {
                AppLog.i(TAG, "上传 ${orphanedSidecars.size} 个孤立侧车元数据")
                for (sidecar in orphanedSidecars) {
                    when (uploadFileWithLock(sidecar, uploader, "孤立侧车元数据")) {
                        UploadResult.SUCCESS -> sidecarSucceeded++
                        UploadResult.FAILED -> {
                            failed++
                            consecutiveFailures++
                        }
                        UploadResult.SKIPPED -> {}
                    }
                    if (consecutiveFailures >= 3) {
                        AppLog.e(TAG, "连续 3 次失败 — 停止孤立元数据上传")
                        break
                    }
                }
            }
        } finally {
            networkManager.releaseNetwork()
            AppLog.d(TAG, "网络资源已释放")
        }

        val summary = buildString {
            append("上传 worker 完成 — 音频 $audioSucceeded/${audioFiles.size} 成功")
            val totalSidecars = pairedSidecarCount + orphanedSidecars.size
            if (totalSidecars > 0) append(", 元数据 $sidecarSucceeded/$totalSidecars 成功")
            if (failed > 0) append(", $failed 失败")
            append(", 共 $totalUploadCount 个文件")
        }
        AppLog.i(TAG, summary)
        return if (failed == 0) Result.success() else Result.retry()
    }

    private enum class UploadResult { SUCCESS, FAILED, SKIPPED }

    private suspend fun uploadFileWithLock(
        file: File,
        uploader: StorageUploader,
        label: String
    ): UploadResult {
        val uploadingFile = File(file.parent, file.name + ".uploading")
        if (!file.renameTo(uploadingFile)) {
            AppLog.d(TAG, "无法占用 ${file.name} — 跳过(可能是另一个 worker)")
            return UploadResult.SKIPPED
        }

        AppLog.i(TAG, "上传中($label): ${file.name} (${uploadingFile.length() / 1024}KB)")
        val startMs = System.currentTimeMillis()
        val ok = uploader.uploadFile(uploadingFile)
        val elapsed = System.currentTimeMillis() - startMs

        return if (ok) {
            uploadingFile.delete()
            AppLog.i(TAG, "上传成功($label): ${file.name} 耗时 ${elapsed}ms")
            UploadResult.SUCCESS
        } else {
            if (!uploadingFile.renameTo(file)) {
                AppLog.w(TAG, "无法恢复 ${uploadingFile.name} — 文件可能孤立")
            }
            AppLog.e(TAG, "上传失败($label): ${file.name} 耗时 ${elapsed}ms")
            UploadResult.FAILED
        }
    }

    /** 侧车 JSON 存在但对应 .opus 已上传删除的遗留文件 */
    private fun findOrphanedSidecars(dir: File): List<File> {
        return dir.listFiles()?.filter { file ->
            file.isFile &&
                file.name.endsWith(".opus.json") &&
                !file.name.endsWith(".uploading") &&
                !File(dir, file.name.removeSuffix(".json")).exists()
        }?.sortedBy { it.name } ?: emptyList()
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
