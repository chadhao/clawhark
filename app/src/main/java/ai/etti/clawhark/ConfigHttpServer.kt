package ai.etti.clawhark

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File

class ConfigHttpServer(
    private val appContext: Context,
    private val pin: String,
    port: Int = PORT
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (uri.startsWith("/api/") && method != Method.OPTIONS) {
            if (!isAuthorized(session)) {
                return jsonResponse(
                    Response.Status.UNAUTHORIZED,
                    JSONObject().put("error", "PIN 无效或未提供")
                )
            }
        }

        return when {
            uri == "/" && method == Method.GET -> serveIndex()
            uri == "/api/config" && method == Method.GET -> serveConfig()
            uri == "/api/config" && (method == Method.PUT || method == Method.POST) -> updateConfig(session)
            uri == "/api/config/raw" && method == Method.GET -> serveRawConfig()
            uri == "/api/status" && method == Method.GET -> serveStatus()
            uri == "/api/upload/trigger" && method == Method.POST -> triggerUpload()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val headerPin = session.headers["x-clawhark-pin"] ?: session.headers["X-ClawHark-Pin"]
        if (headerPin == pin) return true

        val query = session.parms
        return query["pin"] == pin
    }

    private fun serveIndex(): Response {
        return try {
            val html = appContext.assets.open("web/config.html").bufferedReader().readText()
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        } catch (e: Exception) {
            AppLog.e(TAG, "加载配置页失败", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "配置页加载失败")
        }
    }

    private fun serveConfig(): Response {
        val config = ClawHarkConfig.load(appContext)
        return jsonResponse(Response.Status.OK, ClawHarkConfig.toJsonObject(config, maskSecrets = true))
    }

    private fun serveRawConfig(): Response {
        val raw = ClawHarkConfig.readRawText(appContext)
        return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", raw)
    }

    private fun updateConfig(session: IHTTPSession): Response {
        return try {
            val body = readBody(session)
            if (body.isBlank()) {
                return jsonResponse(
                    Response.Status.BAD_REQUEST,
                    JSONObject().put("error", "请求体为空")
                )
            }

            val incoming = ClawHarkConfig.fromJsonObject(JSONObject(body))
            val existing = ClawHarkConfig.load(appContext)
            val merged = ClawHarkConfig.mergeForSave(incoming, existing)

            if (!merged.validateStorage()) {
                return jsonResponse(
                    Response.Status.BAD_REQUEST,
                    JSONObject().put("error", "存储配置不完整，请检查必填字段")
                )
            }

            ClawHarkConfig.save(appContext, merged)
            AuthManager.reloadStorageConfig(appContext, clearAuthOnChange = true)

            jsonResponse(
                Response.Status.OK,
                JSONObject()
                    .put("ok", true)
                    .put("storage_changed", ClawHarkConfig.storageFingerprint(merged) != ClawHarkConfig.storageFingerprint(existing))
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "保存配置失败", e)
            jsonResponse(
                Response.Status.BAD_REQUEST,
                JSONObject().put("error", e.message ?: "保存失败")
            )
        }
    }

    private fun serveStatus(): Response {
        val config = ClawHarkConfig.load(appContext)
        val recordingsDir = File(appContext.filesDir, "recordings")
        val counts = RecordingStats.countLocalRecordings(recordingsDir)
        val prefs = appContext.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)

        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = network?.let { cm.getNetworkCapabilities(it) }
        val hasWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val status = JSONObject()
            .put("storage_type", config.storageType.name.lowercase())
            .put("authenticated", AuthManager.isAuthenticated())
            .put("should_record", prefs.getBoolean(RecordingService.PREF_SHOULD_RECORD, false))
            .put("pause_on_charge", config.recording.pauseOnCharge)
            .put("debug_mode", config.recording.debugMode)
            .put("opus_bit_rate", config.recording.opusBitRate)
            .put("pending_files", counts.totalUploadCount)
            .put("wifi_connected", hasWifi)

        return jsonResponse(Response.Status.OK, status)
    }

    private fun triggerUpload(): Response {
        return try {
            val scheduler = UploadScheduler(appContext, ServiceConfig.load(appContext))
            scheduler.triggerImmediateUpload()
            jsonResponse(Response.Status.OK, JSONObject().put("ok", true))
        } catch (e: Exception) {
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("error", e.message ?: "触发上传失败")
            )
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val files = HashMap<String, String>()
        session.parseBody(files)

        // POST + application/json → postData
        files["postData"]?.takeIf { it.isNotEmpty() }?.let { return it }

        // PUT → NanoHTTPD 写入临时文件，路径在 content
        files["content"]?.takeIf { it.isNotEmpty() }?.let { path ->
            val file = File(path)
            if (file.exists()) {
                return file.readText(Charsets.UTF_8)
            }
        }

        return ""
    }

    private fun jsonResponse(status: Response.Status, json: JSONObject): Response {
        return newFixedLengthResponse(status, "application/json; charset=utf-8", json.toString())
    }

    companion object {
        private const val TAG = "ConfigHttpServer"
        const val PORT = 8765
    }
}
