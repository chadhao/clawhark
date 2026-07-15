package ai.etti.clawhark

import android.content.Context
import java.io.File

class StorageManager(
    private val context: Context,
    private val config: ServiceConfig
) {
    companion object {
        private const val TAG = "StorageManager"
        private const val RECORDINGS_DIR = "recordings"
    }
    
    fun getChunkDir(): File {
        val dir = File(context.filesDir, RECORDINGS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
    
    fun getRecordings(): List<File> {
        return getChunkDir().listFiles()?.filter {
            it.isFile && !it.name.endsWith(".uploading") &&
                (it.name.endsWith(".opus") || it.name.endsWith(".opus.json") || it.extension == "m4a")
        }?.sortedBy { it.name } ?: emptyList()
    }
    
    fun getStorageUsed(): Long = getRecordings().sumOf { it.length() }
    
    fun hasEnoughDiskSpace(): Boolean {
        val freeSpace = getChunkDir().usableSpace
        if (freeSpace < config.minFreeSpaceBytes) {
            AppLog.w(TAG, "磁盘空间不足: ${freeSpace / 1024 / 1024}MB 可用 (最小 ${config.minFreeSpaceBytes / 1024 / 1024}MB) - 跳过编码")
            return false
        }
        return true
    }
    
    fun enforceStorageLimit() {
        val recordings = getRecordings()
        var totalSize = recordings.sumOf { it.length() }
        if (totalSize <= config.maxLocalStorageBytes) return

        // 按 basename 成组删除，避免只删 .opus 或只删 .opus.json
        val groups = groupRecordingPairs(recordings)
        val sorted = groups.sortedBy { it.oldestModified }
        val target = (config.maxLocalStorageBytes * 0.8).toLong()
        for (group in sorted) {
            if (totalSize <= target) break
            val size = group.totalSize
            AppLog.w(
                TAG,
                "存储限制: 删除最旧录音组 ${group.label} (${size / 1024}KB, ${group.files.size} 个文件)"
            )
            for (file in group.files) {
                file.delete()
            }
            totalSize -= size
        }
    }

    private data class RecordingGroup(
        val label: String,
        val files: List<File>,
        val totalSize: Long,
        val oldestModified: Long
    )

    /** .opus 与对应 .opus.json 归为一组；孤立侧车或其它扩展名各自成组 */
    private fun groupRecordingPairs(recordings: List<File>): List<RecordingGroup> {
        val byName = recordings.associateBy { it.name }
        val consumed = mutableSetOf<String>()
        val groups = mutableListOf<RecordingGroup>()

        for (audio in recordings.filter { it.extension == "opus" }.sortedBy { it.name }) {
            if (audio.name in consumed) continue
            val sidecar = byName[audio.name + ".json"]
            val files = listOfNotNull(audio, sidecar)
            files.forEach { consumed.add(it.name) }
            groups.add(
                RecordingGroup(
                    label = audio.name,
                    files = files,
                    totalSize = files.sumOf { it.length() },
                    oldestModified = files.minOf { it.lastModified() }
                )
            )
        }

        for (file in recordings.sortedBy { it.name }) {
            if (file.name in consumed) continue
            consumed.add(file.name)
            groups.add(
                RecordingGroup(
                    label = file.name,
                    files = listOf(file),
                    totalSize = file.length(),
                    oldestModified = file.lastModified()
                )
            )
        }
        return groups
    }
    
    fun cleanupOrphanedTmpFiles() {
        val dir = getChunkDir()
        if (!dir.exists()) return
        val now = System.currentTimeMillis()
        val tmpFiles = dir.listFiles()?.filter { it.extension == "tmp" } ?: emptyList()
        
        for (tmp in tmpFiles) {
            val ageMs = now - tmp.lastModified()
            when {
                ageMs > config.staleTmpThresholdMs && tmp.length() > 0 -> {
                    val m4aName = tmp.name.removeSuffix(".tmp")
                    val recovered = File(dir, m4aName)
                    if (tmp.renameTo(recovered)) {
                        AppLog.i(TAG, "恢复孤立的.tmp -> ${recovered.name} (${tmp.length()/1024}KB, 存在${ageMs/1000}秒)")
                    } else {
                        AppLog.w(TAG, "无法恢复 ${tmp.name} - 删除")
                        tmp.delete()
                    }
                }
                ageMs > config.staleTmpThresholdMs -> {
                    AppLog.d(TAG, "删除空的孤立.tmp: ${tmp.name}")
                    tmp.delete()
                }
                else -> {
                    AppLog.d(TAG, "跳过最近的.tmp: ${tmp.name} (存在${ageMs/1000}秒)")
                }
            }
        }
    }
}
