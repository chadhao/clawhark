package ai.etti.clawhark

import java.io.File

object RecordingStats {
    data class LocalRecordingCounts(
        val audioCount: Int,
        val metadataCount: Int
    ) {
        val totalUploadCount: Int get() = audioCount + metadataCount

        fun formatLabel(): String = "${totalUploadCount}文件"

        fun formatPendingUpload(): String = "${totalUploadCount}文件待上传"
    }

    fun countLocalRecordings(dir: File): LocalRecordingCounts {
        if (!dir.exists()) return LocalRecordingCounts(0, 0)
        val files = dir.listFiles()?.filter {
            it.isFile && !it.name.endsWith(".uploading")
        } ?: emptyList()
        return LocalRecordingCounts(
            audioCount = files.count { it.name.endsWith(".opus") },
            metadataCount = files.count { it.name.endsWith(".opus.json") }
        )
    }

    fun localRecordingsSizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.listFiles()?.filter {
            it.isFile && !it.name.endsWith(".uploading") &&
                (it.name.endsWith(".opus") || it.name.endsWith(".opus.json"))
        }?.sumOf { it.length() } ?: 0L
    }
}
