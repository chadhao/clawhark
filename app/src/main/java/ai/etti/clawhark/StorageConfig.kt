package ai.etti.clawhark

enum class StorageType {
    GOOGLE_DRIVE,
    S3;

    companion object {
        fun fromString(value: String): StorageType {
            return when (value.lowercase()) {
                "google_drive" -> GOOGLE_DRIVE
                "s3" -> S3
                else -> GOOGLE_DRIVE
            }
        }
    }

    fun toDisplayName(): String {
        return when (this) {
            GOOGLE_DRIVE -> "Google Drive"
            S3 -> "S3"
        }
    }
}

data class GoogleDriveConfig(
    val clientId: String,
    val clientSecret: String
)

data class S3Config(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val accessKey: String,
    val secretKey: String,
    val pathPrefix: String,
    val storageClass: String = "STANDARD"
)

data class StorageConfig(
    val storageType: StorageType,
    val googleDriveConfig: GoogleDriveConfig?,
    val s3Config: S3Config?
) {
    fun validate(): Boolean {
        return when (storageType) {
            StorageType.GOOGLE_DRIVE -> {
                googleDriveConfig != null && googleDriveConfig.clientId.isNotEmpty()
            }
            StorageType.S3 -> {
                s3Config != null &&
                s3Config.endpoint.isNotEmpty() &&
                s3Config.bucket.isNotEmpty() &&
                s3Config.accessKey.isNotEmpty() &&
                s3Config.secretKey.isNotEmpty()
            }
        }
    }
}
