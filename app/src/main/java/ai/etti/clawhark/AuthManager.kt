package ai.etti.clawhark

import android.content.Context
import android.content.SharedPreferences
import android.net.Network
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 管理认证和存储配置。
 *
 * Google OAuth2 通过设备授权授予 (RFC 8628) 方式:
 *   1. 调用 requestDeviceCode() — 返回用户代码 + 验证 URL
 *   2. 用户在手机/电脑上访问该 URL 并输入代码
 *   3. 重复调用 pollForAuthorization(deviceCode) 直到成功
 *   4. Refresh token 存储在 EncryptedSharedPreferences 中供后续使用
 *
 * 后续启动:
 *   - 调用 getAccessToken() — 使用存储的 refresh token 自动刷新
 *
 * S3 存储:
 *   - 直接从配置文件读取凭证,无需 OAuth 流程
 *
 * 设置: 在 Google Cloud Console 中创建 OAuth 2.0 客户端:
 *   - 应用程序类型: "TV 和受限输入设备"
 *   - 此类型不需要 client_secret
 *   - 为项目启用 Google Drive API
 *   - 在 assets/oauth_config.json 中设置配置
 */
object AuthManager {
    private const val TAG = "Auth"

    private var storageConfig: StorageConfig? = null

    // OAuth 凭证在运行时从 assets/oauth_config.json 加载
    // 参考 oauth_config.json.example 了解格式
    // 创建你自己的: https://console.cloud.google.com/apis/credentials
    // 应用程序类型: "TV 和受限输入设备"
    private var CLIENT_ID = ""
    private var CLIENT_SECRET = ""

    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val CONNECT_TIMEOUT = 30_000
    private const val READ_TIMEOUT = 60_000

    private const val PREF_REFRESH_TOKEN = "refresh_token"
    private const val PREF_ACCESS_TOKEN = "access_token"
    private const val PREF_TOKEN_EXPIRY = "token_expiry"

    private const val ENCRYPTED_PREFS_FILE = "clawhark_auth_enc"
    private const val OLD_PREFS_FILE = "clawhark_auth"

    private var prefs: SharedPreferences? = null
    private val tokenMutex = Mutex()

    fun init(context: Context) {
        if (prefs != null) return
        val appContext = context.applicationContext

        // 从 assets 加载存储配置
        storageConfig = StorageConfig.loadFromAssets(appContext)
        if (storageConfig == null || !storageConfig!!.validate()) {
            AppLog.e(TAG, "存储配置无效或缺失 — 功能将受限")
        }

        // 如果使用 Google Drive,加载 OAuth 配置
        if (storageConfig?.storageType == StorageType.GOOGLE_DRIVE) {
            storageConfig?.googleDriveConfig?.let { gdConfig ->
                CLIENT_ID = gdConfig.clientId
                CLIENT_SECRET = gdConfig.clientSecret
                AppLog.i(TAG, "Google Drive OAuth 配置已加载 (client_id 末尾 ...${CLIENT_ID.takeLast(12)})")
            }
        }
        try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            prefs = EncryptedSharedPreferences.create(
                ENCRYPTED_PREFS_FILE,
                masterKeyAlias,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Keystore or encrypted prefs corrupted — delete and recreate.
            // User will need to re-authenticate, but app won't crash-loop.
            AppLog.e(TAG, "EncryptedSharedPreferences corrupted — resetting", e)
            try { appContext.deleteSharedPreferences(ENCRYPTED_PREFS_FILE) } catch (_: Exception) {}
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            prefs = EncryptedSharedPreferences.create(
                ENCRYPTED_PREFS_FILE,
                masterKeyAlias,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
        
        // 迁移旧的未加密首选项(如果存在)
        migrateFromPlainPrefs(appContext)
    }

    fun getStorageConfig(): StorageConfig? = storageConfig

    private fun requirePrefs(): SharedPreferences =
        prefs ?: throw IllegalStateException("AuthManager.init() not called")

    private fun migrateFromPlainPrefs(context: Context) {
        val oldPrefs = context.getSharedPreferences(OLD_PREFS_FILE, Context.MODE_PRIVATE)
        val oldRefreshToken = oldPrefs.getString(PREF_REFRESH_TOKEN, null) ?: return

        // Only migrate if encrypted prefs don't already have a refresh token
        if (requirePrefs().getString(PREF_REFRESH_TOKEN, null) != null) {
            // Already migrated — clear old prefs
            oldPrefs.edit().clear().apply()
            return
        }

        AppLog.i(TAG, "Migrating tokens to encrypted storage")
        requirePrefs().edit()
            .putString(PREF_REFRESH_TOKEN, oldRefreshToken)
            .putString(PREF_ACCESS_TOKEN, oldPrefs.getString(PREF_ACCESS_TOKEN, null))
            .putLong(PREF_TOKEN_EXPIRY, oldPrefs.getLong(PREF_TOKEN_EXPIRY, 0))
            .apply()

        // Clear old unencrypted prefs
        oldPrefs.edit().clear().apply()
        AppLog.i(TAG, "Migration complete — old prefs cleared")
    }

    fun isAuthenticated(): Boolean {
        return when (storageConfig?.storageType) {
            StorageType.GOOGLE_DRIVE -> requirePrefs().getString(PREF_REFRESH_TOKEN, null) != null
            StorageType.S3 -> storageConfig?.s3Config != null
            null -> false
        }
    }

    fun clearAuth() {
        val refreshToken = requirePrefs().getString(PREF_REFRESH_TOKEN, null)
        requirePrefs().edit().clear().apply()
        AppLog.i(TAG, "Auth cleared")

        // Revoke refresh token at Google so it can't be reused if previously extracted
        if (refreshToken != null) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val conn = URL("https://oauth2.googleapis.com/revoke?token=$refreshToken")
                        .openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = CONNECT_TIMEOUT
                    conn.readTimeout = READ_TIMEOUT
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    conn.outputStream.close()
                    val code = conn.responseCode
                    AppLog.i(TAG, "Token revocation HTTP $code")
                    conn.disconnect()
                } catch (e: Exception) {
                    AppLog.w(TAG, "Token revocation failed (non-critical): ${e.message}")
                }
            }
        }
    }

    /** Invalidate cached access token (call on 401 from API) */
    fun invalidateAccessToken() {
        requirePrefs().edit()
            .remove(PREF_ACCESS_TOKEN)
            .putLong(PREF_TOKEN_EXPIRY, 0)
            .apply()
        AppLog.d(TAG, "Access token cache invalidated")
    }

    // ─── Device Code Flow ────────────────────────────────────────────────

    data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresIn: Int,
        val interval: Int
    )

    sealed class PollResult {
        data class Success(val accessToken: String) : PollResult()
        object Pending : PollResult()
        object SlowDown : PollResult()
        object Expired : PollResult()
        data class Error(val message: String) : PollResult()
    }

    /**
     * Step 1: Request a device code from Google.
     * Returns a DeviceCodeResponse with the user code to display on screen.
     */
    suspend fun requestDeviceCode(): DeviceCodeResponse? = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "Requesting device code...")
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://oauth2.googleapis.com/device/code").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val body = "client_id=$CLIENT_ID&scope=$SCOPE"
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(resp)
                val result = DeviceCodeResponse(
                    deviceCode = json.getString("device_code"),
                    userCode = json.getString("user_code"),
                    verificationUrl = json.optString("verification_url", "https://www.google.com/device"),
                    expiresIn = json.getInt("expires_in"),
                    interval = json.getInt("interval")
                )
                AppLog.i(TAG, "Device code received: ${result.userCode} (expires in ${result.expiresIn}s)")
                return@withContext result
            } else {
                AppLog.e(TAG, "Device code request failed HTTP $code")
                return@withContext null
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Device code request error", e)
            return@withContext null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Step 2: Poll Google's token endpoint to check if the user has authorized.
     * Call this every `interval` seconds (from DeviceCodeResponse).
     */
    suspend fun pollForAuthorization(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            var body = "client_id=$CLIENT_ID&device_code=$deviceCode&grant_type=urn:ietf:params:oauth:grant-type:device_code"
            if (CLIENT_SECRET.isNotEmpty()) {
                body += "&client_secret=$CLIENT_SECRET"
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val code = conn.responseCode
            val resp = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }

            if (code == 200) {
                val json = JSONObject(resp)
                val accessToken = json.getString("access_token")
                val refreshToken = json.getString("refresh_token")
                val expiresIn = json.getInt("expires_in")

                requirePrefs().edit()
                    .putString(PREF_REFRESH_TOKEN, refreshToken)
                    .putString(PREF_ACCESS_TOKEN, accessToken)
                    .putLong(PREF_TOKEN_EXPIRY, System.currentTimeMillis() + expiresIn * 1000L)
                    .apply()

                AppLog.i(TAG, "Authorization successful — tokens stored (encrypted)")
                return@withContext PollResult.Success(accessToken)
            }

            // Parse error
            val json = try { JSONObject(resp) } catch (_: Exception) { null }
            val error = json?.optString("error", "") ?: ""

            return@withContext when (error) {
                "authorization_pending" -> PollResult.Pending
                "slow_down" -> PollResult.SlowDown
                "expired_token" -> {
                    AppLog.w(TAG, "Device code expired")
                    PollResult.Expired
                }
                "access_denied" -> {
                    AppLog.w(TAG, "User denied access")
                    PollResult.Error("Access denied")
                }
                else -> {
                    AppLog.e(TAG, "Poll error HTTP $code: $error")
                    PollResult.Error("HTTP $code: $error")
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Poll error", e)
            return@withContext PollResult.Error(e.message ?: "Unknown error")
        } finally {
            conn?.disconnect()
        }
    }

    // ─── Access Token ────────────────────────────────────────────────────

    /**
     * Returns a valid access token, refreshing if needed.
     * Uses Mutex to prevent concurrent refresh races.
     * Returns null if not authenticated or refresh fails.
     */
    suspend fun getAccessToken(network: Network? = null): String? {
        val refreshToken = requirePrefs().getString(PREF_REFRESH_TOKEN, null)
        if (refreshToken == null) {
            AppLog.w(TAG, "No refresh token — not authenticated")
            return null
        }

        // Return cached token if still valid (with 2 min buffer)
        val cachedToken = requirePrefs().getString(PREF_ACCESS_TOKEN, null)
        val expiry = requirePrefs().getLong(PREF_TOKEN_EXPIRY, 0)
        if (cachedToken != null && System.currentTimeMillis() < expiry - 120_000) {
            return cachedToken
        }

        // Refresh with mutex to prevent concurrent refreshes
        return tokenMutex.withLock {
            // Double-check after acquiring lock — another coroutine may have refreshed
            val recheckedToken = requirePrefs().getString(PREF_ACCESS_TOKEN, null)
            val recheckedExpiry = requirePrefs().getLong(PREF_TOKEN_EXPIRY, 0)
            if (recheckedToken != null && System.currentTimeMillis() < recheckedExpiry - 120_000) {
                return@withLock recheckedToken
            }
            refreshAccessToken(refreshToken, network)
        }
    }

    private suspend fun refreshAccessToken(refreshToken: String, network: Network? = null): String? = withContext(Dispatchers.IO) {
        AppLog.d(TAG, "Refreshing access token...")
        var conn: HttpURLConnection? = null
        try {
            conn = NetworkHttp.openConnection("https://oauth2.googleapis.com/token", network)
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            var body = "client_id=$CLIENT_ID&refresh_token=$refreshToken&grant_type=refresh_token"
            if (CLIENT_SECRET.isNotEmpty()) {
                body += "&client_secret=$CLIENT_SECRET"
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val code = conn.responseCode
            if (code == 200) {
                val resp = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(resp)
                val accessToken = json.getString("access_token")
                val expiresIn = json.getInt("expires_in")

                requirePrefs().edit()
                    .putString(PREF_ACCESS_TOKEN, accessToken)
                    .putLong(PREF_TOKEN_EXPIRY, System.currentTimeMillis() + expiresIn * 1000L)
                    .apply()

                AppLog.d(TAG, "Token refreshed — expires in ${expiresIn}s")
                return@withContext accessToken
            } else {
                // Don't log full error body — may contain sensitive token data
                AppLog.e(TAG, "Token refresh failed HTTP $code")
                if (code == 400 || code == 401) {
                    AppLog.e(TAG, "Refresh token invalid — clearing auth")
                    clearAuth()
                }
                return@withContext null
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Token refresh error", e)
            return@withContext null
        } finally {
            conn?.disconnect()
        }
    }
}
