package ai.etti.clawhark

import android.content.Context
import org.json.JSONObject

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
    companion object {
        private const val TAG = "StorageConfig"

        fun loadFromAssets(context: Context): StorageConfig? {
            return try {
                val json = context.assets.open("oauth_config.json").bufferedReader().readText()
                val config = JSONObject(json)

                val storageTypeStr = config.optString("storage_type", "google_drive")
                val storageType = StorageType.fromString(storageTypeStr)

                val googleDriveConfig = if (config.has("google_drive")) {
                    val gd = config.getJSONObject("google_drive")
                    GoogleDriveConfig(
                        clientId = gd.getString("client_id"),
                        clientSecret = gd.optString("client_secret", "")
                    )
                } else null

                val s3Config = if (config.has("s3")) {
                    val s3 = config.getJSONObject("s3")
                    S3Config(
                        endpoint = s3.getString("endpoint"),
                        region = s3.getString("region"),
                        bucket = s3.getString("bucket"),
                        accessKey = s3.getString("access_key"),
                        secretKey = s3.getString("secret_key"),
                        pathPrefix = s3.optString("path_prefix", "ClawHark/")
                    )
                } else null

                AppLog.i(TAG, "配置已加载: 存储类型=${storageType.toDisplayName()}")
                StorageConfig(storageType, googleDriveConfig, s3Config)
            } catch (e: Exception) {
                AppLog.e(TAG, "加载 oauth_config.json 失败", e)
                null
            }
        }
    }

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
