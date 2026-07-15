package ai.etti.clawhark

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "Upload"
        const val WORK_NAME = "upload_recordings"
        const val STALE_THRESHOLD_MS = 60 * 1000L // 60 seconds
        private const val FRESH_TMP_THRESHOLD_MS = 2 * 60 * 1000L
        /** 无侧车时，音频需稳定超过此时长才视为「故意无 JSON」的完整单文件块 */
        private const val STABLE_AUDIO_WITHOUT_SIDECAR_MS = 2 * 60 * 1000L
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

        val readyPairs = findReadyPairs(dir)
        val orphanedSidecars = findOrphanedSidecars(dir)
        val notReadyCount = countNotReadyAudio(dir)

        if (readyPairs.isEmpty() && orphanedSidecars.isEmpty()) {
            if (hasFreshOpusTmp(dir) || notReadyCount > 0) {
                AppLog.i(TAG, "检测到未就绪块(新鲜tmp=${hasFreshOpusTmp(dir)}, 未配对音频=$notReadyCount) — 稍后重试")
                return Result.retry()
            }
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

        val storageType = storageConfig!!.storageType
        val pairWithSidecar = readyPairs.count { it.sidecar != null }
        val pairAudioOnly = readyPairs.size - pairWithSidecar
        val totalUnits = readyPairs.size + orphanedSidecars.size

        AppLog.i(
            TAG,
            "上传 worker 已启动 — ${readyPairs.size} 个就绪块" +
                " (成对 $pairWithSidecar, 仅音频 $pairAudioOnly)" +
                (if (orphanedSidecars.isNotEmpty()) ", 孤立侧车 ${orphanedSidecars.size}" else "") +
                (if (notReadyCount > 0) ", 未就绪跳过 $notReadyCount" else "") +
                ", 共 $totalUnits 个单元 (${uploader.getStorageInfo()})"
        )

        var audioSucceeded = 0
        var sidecarSucceeded = 0
        var failed = 0
        var consecutiveFailures = 0

        try {
            for (pair in readyPairs) {
                val result = uploadPairWithLock(pair, uploader)
                when (result) {
                    UploadResult.SUCCESS -> {
                        audioSucceeded++
                        if (pair.sidecar != null) sidecarSucceeded++
                        consecutiveFailures = 0
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

            // 历史残留：仅侧车、本地音频已不存在时单独补传
            if (orphanedSidecars.isNotEmpty() && consecutiveFailures < 3) {
                AppLog.i(TAG, "上传 ${orphanedSidecars.size} 个孤立侧车元数据(历史残留)")
                for (sidecar in orphanedSidecars) {
                    when (uploadSingleWithLock(sidecar, uploader, "孤立侧车元数据", deleteOnSuccess = true)) {
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
            if (audioSucceeded + sidecarSucceeded > 0) {
                UploadNotifier.notify(
                    applicationContext,
                    network,
                    audioSucceeded,
                    sidecarSucceeded,
                    storageType
                )
            }
            networkManager.releaseNetwork()
            AppLog.d(TAG, "网络资源已释放")
        }

        val summary = buildString {
            append("上传 worker 完成 — 音频 $audioSucceeded/${readyPairs.size} 成功")
            val expectedSidecars = pairWithSidecar + orphanedSidecars.size
            if (expectedSidecars > 0) append(", 元数据 $sidecarSucceeded/$expectedSidecars 成功")
            if (failed > 0) append(", $failed 失败")
            append(", 共 $totalUnits 个单元")
        }
        AppLog.i(TAG, summary)
        return if (failed == 0) {
            if (notReadyCount > 0 || hasFreshOpusTmp(dir)) Result.retry() else Result.success()
        } else {
            Result.retry()
        }
    }

    private data class ReadyPair(val audio: File, val sidecar: File?)

    private enum class UploadResult { SUCCESS, FAILED, SKIPPED }

    /** 成对就绪：有侧车；或故意无侧车且音频已稳定 */
    private fun findReadyPairs(dir: File): List<ReadyPair> {
        val now = System.currentTimeMillis()
        return dir.listFiles()?.filter {
            it.isFile && it.extension == "opus" && !it.name.endsWith(".uploading")
        }?.sortedBy { it.name }?.mapNotNull { audio ->
            val sidecar = ChunkMetadata.sidecarFileFor(audio)
            when {
                sidecar.exists() && !sidecar.name.endsWith(".uploading") ->
                    ReadyPair(audio, sidecar)
                isAudioNotReadyWithoutSidecar(audio, now) ->
                    null // 未就绪：对应 tmp 仍在或音频过新
                else ->
                    ReadyPair(audio, null) // 稳定无侧车 → 合法单文件块
            }
        } ?: emptyList()
    }

    private fun countNotReadyAudio(dir: File): Int {
        val now = System.currentTimeMillis()
        return dir.listFiles()?.count { file ->
            file.isFile &&
                file.extension == "opus" &&
                !file.name.endsWith(".uploading") &&
                !ChunkMetadata.sidecarFileFor(file).exists() &&
                isAudioNotReadyWithoutSidecar(file, now)
        } ?: 0
    }

    /** 无侧车且仍可能在 finalize（有同名 .opus.tmp，或音频刚出现） */
    private fun isAudioNotReadyWithoutSidecar(audio: File, now: Long = System.currentTimeMillis()): Boolean {
        val matchingTmp = File(audio.parent, audio.name + ".tmp")
        if (matchingTmp.exists() && (now - matchingTmp.lastModified() < FRESH_TMP_THRESHOLD_MS)) {
            return true
        }
        return now - audio.lastModified() < STABLE_AUDIO_WITHOUT_SIDECAR_MS
    }

    /**
     * 占用成对文件 → 依次上传 → 全部成功才删除；任一步失败则恢复本地文件名。
     */
    private suspend fun uploadPairWithLock(pair: ReadyPair, uploader: StorageUploader): UploadResult {
        val audioUploading = File(pair.audio.parent, pair.audio.name + ".uploading")
        if (!pair.audio.renameTo(audioUploading)) {
            AppLog.d(TAG, "无法占用 ${pair.audio.name} — 跳过(可能是另一个 worker)")
            return UploadResult.SKIPPED
        }

        var sidecarUploading: File? = null
        val sidecar = pair.sidecar
        if (sidecar != null) {
            val target = File(sidecar.parent, sidecar.name + ".uploading")
            if (!sidecar.renameTo(target)) {
                AppLog.d(TAG, "无法占用 ${sidecar.name} — 恢复音频并跳过")
                audioUploading.renameTo(pair.audio)
                return UploadResult.SKIPPED
            }
            sidecarUploading = target
        }

        AppLog.i(
            TAG,
            "上传中(成对): ${pair.audio.name}" +
                (if (sidecar != null) " + ${sidecar.name}" else " (无侧车)") +
                " (${audioUploading.length() / 1024}KB)"
        )
        val startMs = System.currentTimeMillis()

        val audioOk = uploader.uploadFile(audioUploading)
        if (!audioOk) {
            restoreUploading(audioUploading, pair.audio)
            sidecarUploading?.let { restoreUploading(it, sidecar!!) }
            AppLog.e(TAG, "上传失败(成对-音频): ${pair.audio.name} 耗时 ${System.currentTimeMillis() - startMs}ms")
            return UploadResult.FAILED
        }

        if (sidecarUploading != null) {
            val sidecarOk = uploader.uploadFile(sidecarUploading)
            if (!sidecarOk) {
                // 半成功：两端都不删，下次整对重传覆盖
                restoreUploading(audioUploading, pair.audio)
                restoreUploading(sidecarUploading, sidecar!!)
                AppLog.e(
                    TAG,
                    "上传失败(成对-侧车): ${sidecar!!.name} — 本地成对已保留 耗时 ${System.currentTimeMillis() - startMs}ms"
                )
                return UploadResult.FAILED
            }
        }

        audioUploading.delete()
        sidecarUploading?.delete()
        AppLog.i(
            TAG,
            "上传成功(成对): ${pair.audio.name}" +
                (if (sidecar != null) " + ${sidecar.name}" else "") +
                " 耗时 ${System.currentTimeMillis() - startMs}ms"
        )
        return UploadResult.SUCCESS
    }

    private suspend fun uploadSingleWithLock(
        file: File,
        uploader: StorageUploader,
        label: String,
        deleteOnSuccess: Boolean
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
            if (deleteOnSuccess) uploadingFile.delete()
            else restoreUploading(uploadingFile, file)
            AppLog.i(TAG, "上传成功($label): ${file.name} 耗时 ${elapsed}ms")
            UploadResult.SUCCESS
        } else {
            restoreUploading(uploadingFile, file)
            AppLog.e(TAG, "上传失败($label): ${file.name} 耗时 ${elapsed}ms")
            UploadResult.FAILED
        }
    }

    private fun restoreUploading(uploading: File, original: File) {
        if (!uploading.renameTo(original)) {
            AppLog.w(TAG, "无法恢复 ${uploading.name} — 文件可能孤立")
        }
    }

    /** 侧车 JSON 存在但对应 .opus 已不存在的遗留文件 */
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
            it.name.endsWith(".uploading") && (now - it.lastModified() > STALE_THRESHOLD_MS)
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

    /** 停录 finalize 尚未 rename 时，避免空跑 success 漏传最后一块 */
    private fun hasFreshOpusTmp(dir: File): Boolean {
        val now = System.currentTimeMillis()
        return dir.listFiles()?.any { file ->
            file.isFile &&
                file.name.endsWith(".opus.tmp") &&
                (now - file.lastModified() < FRESH_TMP_THRESHOLD_MS)
        } == true
    }
}
