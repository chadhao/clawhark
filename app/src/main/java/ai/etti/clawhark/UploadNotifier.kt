package ai.etti.clawhark

import android.content.Context
import android.net.Network
import org.json.JSONObject
import java.io.OutputStreamWriter

/** 上传完成后向 ntfy topic 发送通知，触发 omi_mini 后端自动处理。 */
object UploadNotifier {
    private const val TAG = "UploadNotify"

    fun notify(
        context: Context,
        network: Network?,
        audioCount: Int,
        sidecarCount: Int,
        storageType: StorageType
    ) {
        val config = ClawHarkConfig.load(context)
        val notify = config.uploadNotify
        if (!notify.enabled) return
        if (notify.ntfyUrl.isBlank()) {
            AppLog.w(TAG, "上传通知已启用但 ntfy_url 为空，跳过")
            return
        }
        if (storageType != StorageType.S3) {
            AppLog.d(TAG, "非 S3 存储，跳过上传通知")
            return
        }

        val payload = JSONObject().apply {
            put("v", 1)
            put("type", "upload_complete")
            put("audio_count", audioCount)
            put("sidecar_count", sidecarCount)
            put("storage", "s3")
        }

        try {
            val conn = NetworkHttp.openConnection(notify.ntfyUrl, network)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Priority", "min")
            if (notify.authToken.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${notify.authToken}")
            }
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            val code = conn.responseCode
            if (code in 200..299) {
                AppLog.i(TAG, "上传通知已发送 (HTTP $code)")
            } else {
                AppLog.w(TAG, "上传通知失败 HTTP $code")
            }
            conn.disconnect()
        } catch (e: Exception) {
            AppLog.w(TAG, "上传通知异常: ${e.message}")
        }
    }
}
