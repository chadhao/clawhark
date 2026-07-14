package ai.etti.clawhark

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 统一配置入口 — 读写 filesDir/clawhark.jsonc（JSONC，支持注释）。
 */
object ClawHarkConfig {
    const val FILE_NAME = "clawhark.jsonc"
    private const val TAG = "ClawHarkConfig"
    private const val ASSET_EXAMPLE = "clawhark.jsonc.example"
    private const val ASSET_DEFAULT = "clawhark.jsonc"
    private const val LEGACY_OAUTH = "oauth_config.json"

    const val MIN_UPLOAD_INTERVAL_MINUTES = 15L
    const val MAX_UPLOAD_INTERVAL_MINUTES = 1440L
    const val DEFAULT_UPLOAD_INTERVAL_MINUTES = 60L

    fun clampUploadIntervalMinutes(value: Long): Long =
        value.coerceIn(MIN_UPLOAD_INTERVAL_MINUTES, MAX_UPLOAD_INTERVAL_MINUTES)

    data class RecordingSettings(
        val pauseOnCharge: Boolean = true,
        val opusBitRate: Int = OpusBitRate.DEFAULT_BIT_RATE,
        val debugMode: Boolean = false,
        /** 主上传间隔（分钟），WorkManager 最短约 15 */
        val uploadIntervalMinutes: Long = DEFAULT_UPLOAD_INTERVAL_MINUTES
    )

    data class UploadNotifySettings(
        val enabled: Boolean = false,
        val ntfyUrl: String = "",
        val authToken: String = ""
    )

    data class FullConfig(
        val storageType: StorageType,
        val googleDrive: GoogleDriveConfig?,
        val s3: S3Config?,
        val recording: RecordingSettings,
        val uploadNotify: UploadNotifySettings = UploadNotifySettings()
    ) {
        fun toStorageConfig(): StorageConfig = StorageConfig(
            storageType = storageType,
            googleDriveConfig = googleDrive,
            s3Config = s3
        )

        fun validateStorage(): Boolean = toStorageConfig().validate()
    }

    @Volatile
    private var cached: FullConfig? = null

    fun migrateIfNeeded(context: Context) {
        val file = configFile(context)
        if (file.exists()) return

        AppLog.i(TAG, "首次运行 — 迁移配置到 ${file.name}")
        val merged = mergeLegacyConfig(context)
        saveInternal(context, merged)
    }

    fun load(context: Context): FullConfig {
        cached?.let { return it }
        migrateIfNeeded(context)
        val config = parseConfig(readConfigText(context))
        cached = config
        AppLog.i(TAG, "配置已加载: 存储=${config.storageType.toDisplayName()}")
        return config
    }

    fun reload(context: Context): FullConfig {
        cached = null
        return load(context)
    }

    fun save(context: Context, config: FullConfig) {
        saveInternal(context, config)
        cached = config
        AppLog.i(TAG, "配置已保存")
    }

    fun readRawText(context: Context): String {
        migrateIfNeeded(context)
        return readConfigText(context)
    }

    fun configFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun updateRecording(context: Context, transform: (RecordingSettings) -> RecordingSettings) {
        val current = load(context)
        save(context, current.copy(recording = transform(current.recording)))
    }

    fun toJsonObject(config: FullConfig, maskSecrets: Boolean = false): JSONObject {
        val root = JSONObject()
        root.put("storage_type", when (config.storageType) {
            StorageType.GOOGLE_DRIVE -> "google_drive"
            StorageType.S3 -> "s3"
        })

        val gd = JSONObject()
        gd.put("client_id", config.googleDrive?.clientId ?: "")
        gd.put(
            "client_secret",
            maskSecret(config.googleDrive?.clientSecret ?: "", maskSecrets)
        )
        root.put("google_drive", gd)

        val s3 = JSONObject()
        val s3c = config.s3
        s3.put("endpoint", s3c?.endpoint ?: "")
        s3.put("region", s3c?.region ?: "")
        s3.put("bucket", s3c?.bucket ?: "")
        s3.put("access_key", maskSecret(s3c?.accessKey ?: "", maskSecrets))
        s3.put("secret_key", maskSecret(s3c?.secretKey ?: "", maskSecrets))
        s3.put("path_prefix", s3c?.pathPrefix ?: "ClawHark/")
        root.put("s3", s3)

        val rec = JSONObject()
        rec.put("pause_on_charge", config.recording.pauseOnCharge)
        rec.put("opus_bit_rate", config.recording.opusBitRate)
        rec.put("debug_mode", config.recording.debugMode)
        rec.put("upload_interval_minutes", config.recording.uploadIntervalMinutes)
        root.put("recording", rec)

        val notify = JSONObject()
        notify.put("enabled", config.uploadNotify.enabled)
        notify.put("ntfy_url", config.uploadNotify.ntfyUrl)
        notify.put("auth_token", maskSecret(config.uploadNotify.authToken, maskSecrets))
        root.put("upload_notify", notify)

        return root
    }

    fun fromJsonObject(json: JSONObject): FullConfig {
        val storageType = StorageType.fromString(json.optString("storage_type", "google_drive"))

        val googleDrive = if (json.has("google_drive")) {
            val gd = json.getJSONObject("google_drive")
            GoogleDriveConfig(
                clientId = gd.optString("client_id", ""),
                clientSecret = gd.optString("client_secret", "")
            )
        } else {
            GoogleDriveConfig("", "")
        }

        val s3 = if (json.has("s3")) {
            val s3j = json.getJSONObject("s3")
            S3Config(
                endpoint = s3j.optString("endpoint", ""),
                region = s3j.optString("region", ""),
                bucket = s3j.optString("bucket", ""),
                accessKey = s3j.optString("access_key", ""),
                secretKey = s3j.optString("secret_key", ""),
                pathPrefix = s3j.optString("path_prefix", "ClawHark/")
            )
        } else {
            S3Config("", "", "", "", "", "ClawHark/")
        }

        val rec = if (json.has("recording")) {
            json.getJSONObject("recording")
        } else {
            JSONObject()
        }
        val bitRate = rec.optInt("opus_bit_rate", OpusBitRate.DEFAULT_BIT_RATE)
        val validBitRate = OpusBitRate.OPTIONS.find { it.bitRate == bitRate }?.bitRate
            ?: OpusBitRate.DEFAULT_BIT_RATE

        val uploadNotify = if (json.has("upload_notify")) {
            val n = json.getJSONObject("upload_notify")
            UploadNotifySettings(
                enabled = n.optBoolean("enabled", false),
                ntfyUrl = n.optString("ntfy_url", ""),
                authToken = n.optString("auth_token", "")
            )
        } else {
            UploadNotifySettings()
        }

        return FullConfig(
            storageType = storageType,
            googleDrive = googleDrive,
            s3 = s3,
            recording = RecordingSettings(
                pauseOnCharge = rec.optBoolean("pause_on_charge", true),
                opusBitRate = validBitRate,
                debugMode = rec.optBoolean("debug_mode", false),
                uploadIntervalMinutes = clampUploadIntervalMinutes(
                    rec.optLong("upload_interval_minutes", DEFAULT_UPLOAD_INTERVAL_MINUTES)
                )
            ),
            uploadNotify = uploadNotify
        )
    }

    /** 合并 API 提交的配置，保留未修改的掩码密钥 */
    fun mergeForSave(incoming: FullConfig, existing: FullConfig): FullConfig {
        val incomingGd = incoming.googleDrive ?: GoogleDriveConfig("", "")
        val existingGd = existing.googleDrive ?: GoogleDriveConfig("", "")
        val mergedGd = GoogleDriveConfig(
            clientId = incomingGd.clientId,
            clientSecret = preserveSecret(incomingGd.clientSecret, existingGd.clientSecret)
        )

        val incomingS3 = incoming.s3 ?: S3Config("", "", "", "", "", "ClawHark/")
        val existingS3 = existing.s3 ?: S3Config("", "", "", "", "", "ClawHark/")
        val mergedS3 = S3Config(
            endpoint = incomingS3.endpoint,
            region = incomingS3.region,
            bucket = incomingS3.bucket,
            accessKey = preserveSecret(incomingS3.accessKey, existingS3.accessKey),
            secretKey = preserveSecret(incomingS3.secretKey, existingS3.secretKey),
            pathPrefix = incomingS3.pathPrefix.ifEmpty { "ClawHark/" }
        )

        val incomingNotify = incoming.uploadNotify
        val existingNotify = existing.uploadNotify
        val mergedNotify = UploadNotifySettings(
            enabled = incomingNotify.enabled,
            ntfyUrl = incomingNotify.ntfyUrl,
            authToken = preserveSecret(incomingNotify.authToken, existingNotify.authToken)
        )

        return incoming.copy(googleDrive = mergedGd, s3 = mergedS3, uploadNotify = mergedNotify)
    }

    private fun preserveSecret(incoming: String, existing: String): String {
        if (incoming.isBlank() || incoming == "***") return existing
        return incoming
    }

    fun storageFingerprint(config: FullConfig): String {
        val gd = config.googleDrive
        val s3 = config.s3
        return buildString {
            append(config.storageType.name)
            append('|')
            append(gd?.clientId ?: "")
            append('|')
            append(gd?.clientSecret ?: "")
            append('|')
            append(s3?.endpoint ?: "")
            append('|')
            append(s3?.bucket ?: "")
            append('|')
            append(s3?.accessKey ?: "")
            append('|')
            append(s3?.secretKey ?: "")
        }
    }

    private fun saveInternal(context: Context, config: FullConfig) {
        configFile(context).writeText(toJsonc(config))
    }

    private fun readConfigText(context: Context): String {
        val file = configFile(context)
        if (file.exists()) return file.readText()

        return readAssetText(context, ASSET_DEFAULT)
            ?: readAssetText(context, ASSET_EXAMPLE)
            ?: defaultJsoncTemplate()
    }

    private fun parseConfig(text: String): FullConfig = fromJsonObject(JsoncParser.parse(text))

    private fun mergeLegacyConfig(context: Context): FullConfig {
        val base = loadBaseFromLegacyAssets(context)
        val prefs = context.getSharedPreferences(ServiceConfig.PREF_FILE, Context.MODE_PRIVATE)
        val savedBitRate = prefs.getInt(OpusBitRate.PREF_OPUS_BIT_RATE, OpusBitRate.DEFAULT_BIT_RATE)
        val recording = RecordingSettings(
            pauseOnCharge = prefs.getBoolean(RecordingService.PREF_PAUSE_ON_CHARGE, true),
            opusBitRate = OpusBitRate.OPTIONS.find { it.bitRate == savedBitRate }?.bitRate
                ?: OpusBitRate.DEFAULT_BIT_RATE,
            debugMode = prefs.getBoolean(ServiceConfig.PREF_DEBUG_MODE, false)
        )
        return base.copy(recording = recording)
    }

    private fun loadBaseFromLegacyAssets(context: Context): FullConfig {
        val legacyText = readAssetText(context, LEGACY_OAUTH)
        if (legacyText != null) {
            return fromJsonObject(JsoncParser.parse(legacyText)).copy(
                recording = RecordingSettings()
            )
        }
        val defaultText = readAssetText(context, ASSET_DEFAULT)
            ?: readAssetText(context, ASSET_EXAMPLE)
            ?: defaultJsoncTemplate()
        return fromJsonObject(JsoncParser.parse(defaultText))
    }

    private fun readAssetText(context: Context, name: String): String? {
        return try {
            context.assets.open(name).bufferedReader().readText()
        } catch (_: Exception) {
            null
        }
    }

    private fun maskSecret(value: String, mask: Boolean): String {
        if (!mask || value.isEmpty()) return value
        return "***"
    }

    private fun jsonString(value: String): String =
        JSONObject.quote(value)

    private fun toJsonc(config: FullConfig): String {
        val gd = config.googleDrive ?: GoogleDriveConfig("", "")
        val s3 = config.s3 ?: S3Config("", "", "", "", "", "ClawHark/")

        return """
            |{
            |  // 云存储后端: "google_drive" 或 "s3"
            |  "storage_type": ${jsonString(when (config.storageType) {
            StorageType.GOOGLE_DRIVE -> "google_drive"
            StorageType.S3 -> "s3"
        })},
            |
            |  "google_drive": {
            |    // Google Cloud Console → OAuth 2.0 客户端 → 类型: TV 和受限输入设备
            |    "client_id": ${jsonString(gd.clientId)},
            |    // 通常留空；TV/受限设备类型不需要 client_secret
            |    "client_secret": ${jsonString(gd.clientSecret)}
            |  },
            |
            |  "s3": {
            |    // S3 兼容端点 (七牛、MinIO、AWS 等)
            |    "endpoint": ${jsonString(s3.endpoint)},
            |    "region": ${jsonString(s3.region)},
            |    "bucket": ${jsonString(s3.bucket)},
            |    "access_key": ${jsonString(s3.accessKey)},
            |    "secret_key": ${jsonString(s3.secretKey)},
            |    // 上传路径前缀，末尾建议保留 /
            |    "path_prefix": ${jsonString(s3.pathPrefix)}
            |  },
            |
            |  "recording": {
            |    // 充电时自动暂停录音并触发上传
            |    "pause_on_charge": ${config.recording.pauseOnCharge},
            |    // Opus 码率 (bps): 16000 | 24000 | 32000 | 48000，修改后需重启录音
            |    "opus_bit_rate": ${config.recording.opusBitRate},
            |    // 调试模式: 更短分块等，修改后需重启应用（不再覆盖上传间隔）
            |    "debug_mode": ${config.recording.debugMode},
            |    // WiFi 周期上传间隔（分钟），范围 15–1440，保存后立即生效
            |    "upload_interval_minutes": ${config.recording.uploadIntervalMinutes}
            |  },
            |
            |  "upload_notify": {
            |    // 上传成功后向 ntfy 发消息，触发 omi_mini 后端自动处理（仅 S3 存储有效）
            |    "enabled": ${config.uploadNotify.enabled},
            |    // 完整 ntfy topic URL，含随机 topic 名作为密钥
            |    "ntfy_url": ${jsonString(config.uploadNotify.ntfyUrl)},
            |    // ntfy 开启鉴权时填写
            |    "auth_token": ${jsonString(config.uploadNotify.authToken)}
            |  }
            |}
        """.trimMargin() + "\n"
    }

    private fun defaultJsoncTemplate(): String = """
        {
          "storage_type": "google_drive",
          "google_drive": {
            "client_id": "",
            "client_secret": ""
          },
          "s3": {
            "endpoint": "",
            "region": "",
            "bucket": "",
            "access_key": "",
            "secret_key": "",
            "path_prefix": "ClawHark/"
          },
          "recording": {
            "pause_on_charge": true,
            "opus_bit_rate": 32000,
            "debug_mode": false,
            "upload_interval_minutes": 60
          },
          "upload_notify": {
            "enabled": false,
            "ntfy_url": "",
            "auth_token": ""
          }
        }
    """.trimIndent()
}
