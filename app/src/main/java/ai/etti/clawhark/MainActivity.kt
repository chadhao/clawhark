package ai.etti.clawhark

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.app.Activity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat

import kotlinx.coroutines.*

class MainActivity : Activity() {

    private var service: RecordingService? = null
    private var bound = false
    private var bindRequested = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Recording views
    private lateinit var recordGroup: LinearLayout
    private lateinit var toggleBtn: Button
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView

    // Auth views
    private lateinit var authGroup: LinearLayout
    private lateinit var authTitle: TextView
    private lateinit var authStatus: TextView
    private lateinit var authCode: TextView
    private lateinit var authBtn: Button

    private var authPollingJob: Job? = null

    // Double-tap protection
    private var lastToggleTime = 0L
    private var lastAuthTapTime = 0L

    // Stop confirmation (two-tap)
    private var confirmPending = false
    private var confirmResetJob: Job? = null

    // Auth polling animation
    private var dotCount = 0

    private companion object {
        const val DEBOUNCE_MS = 600L
        const val CONFIRM_TIMEOUT_MS = 3000L
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as RecordingService.LocalBinder).getService()
            bound = true
            updateUI()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AppLog.init(this)
        AuthManager.init(this)

        // Recording views
        recordGroup = findViewById(R.id.recordGroup)
        toggleBtn = findViewById(R.id.toggleBtn)
        statusText = findViewById(R.id.statusText)
        infoText = findViewById(R.id.infoText)

        // Auth views
        authGroup = findViewById(R.id.authGroup)
        authTitle = findViewById(R.id.authTitle)
        authStatus = findViewById(R.id.authStatus)
        authCode = findViewById(R.id.authCode)
        authBtn = findViewById(R.id.authBtn)

        toggleBtn.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastToggleTime < DEBOUNCE_MS) return@setOnClickListener
            lastToggleTime = now
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (checkPermissions()) {
                toggle()
            }
        }

        // Long press on toggle to sign out
        toggleBtn.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            signOut()
            true
        }

        authBtn.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastAuthTapTime < DEBOUNCE_MS) return@setOnClickListener
            lastAuthTapTime = now
            startDeviceCodeFlow()
        }

        checkPermissions()
        showCorrectScreen()

        // Update UI periodically
        scope.launch {
            while (isActive) {
                updateUI()
                delay(1000)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        requestBatteryExemption()
        if (AuthManager.isAuthenticated()) {
            val shouldRecord = getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
                .getBoolean(RecordingService.PREF_SHOULD_RECORD, true)
            if (shouldRecord) {
                val intent = Intent(this, RecordingService::class.java)
                startForegroundService(intent)
                doBind(intent)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        doUnbind()
    }

    override fun onDestroy() {
        authPollingJob?.cancel()
        confirmResetJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun doBind(intent: Intent) {
        if (!bindRequested) {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
            bindRequested = true
        }
    }

    private fun doUnbind() {
        if (bindRequested) {
            unbindService(connection)
            bindRequested = false
            bound = false
            service = null
        }
    }

    private fun showCorrectScreen() {
        val storageConfig = AuthManager.getStorageConfig()
        val storageType = storageConfig?.storageType ?: StorageType.GOOGLE_DRIVE
        
        if (AuthManager.isAuthenticated()) {
            authGroup.visibility = View.GONE
            recordGroup.visibility = View.VISIBLE
        } else {
            recordGroup.visibility = View.GONE
            authGroup.visibility = View.VISIBLE
            
            // 根据存储类型显示不同的认证提示
            when (storageType) {
                StorageType.GOOGLE_DRIVE -> {
                    authStatus.text = "点击关联按钮连接\n你的Google Drive"
                    authCode.visibility = View.GONE
                }
                StorageType.S3 -> {
                    // S3 不需要 OAuth 流程,直接显示已配置
                    authGroup.visibility = View.GONE
                    recordGroup.visibility = View.VISIBLE
                }
            }
        }
    }

    // ─── Sign Out ─────────────────────────────────────────────────────────

    private fun signOut() {
        val svc = service
        if (svc != null && svc.isCurrentlyRecording()) {
            getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
                .edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, false).apply()
            val intent = Intent(this, RecordingService::class.java).apply { action = RecordingService.ACTION_STOP }
            startService(intent)
        }
        AuthManager.clearAuth()
        Toast.makeText(this, "已退出", Toast.LENGTH_SHORT).show()
        showCorrectScreen()
    }

    // ─── Device Code Auth Flow ───────────────────────────────────────────

    private fun startDeviceCodeFlow() {
        authBtn.isEnabled = false
        authBtn.text = "..."
        authStatus.text = "正在请求代码..."
        authCode.visibility = View.GONE

        scope.launch {
            val response = AuthManager.requestDeviceCode()
            if (response == null) {
                authBtn.isEnabled = true
                authBtn.text = "重试"
                authStatus.text = "连接失败\n检查WiFi后重试"
                return@launch
            }

            // Show the user code prominently
            authTitle.text = "在此网址输入代码"
            authStatus.text = "google.com/device"
            authCode.text = response.userCode
            authCode.visibility = View.VISIBLE
            authBtn.text = "等待中"
            dotCount = 0

            // Poll for authorization
            val interval = maxOf(response.interval, 5) * 1000L
            authPollingJob = scope.launch pollLoop@{
                while (isActive) {
                    delay(interval)
                    // Animate dots while polling
                    dotCount = (dotCount + 1) % 4
                    authBtn.text = "等待中" + ".".repeat(dotCount)

                    val result = AuthManager.pollForAuthorization(response.deviceCode)
                    when (result) {
                        is AuthManager.PollResult.Success -> {
                            getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
                                .edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, true).apply()
                            authTitle.text = "已连接"
                            authStatus.text = ""
                            authCode.visibility = View.GONE
                            authBtn.text = "确定"
                            delay(1500)
                            showCorrectScreen()
                            val intent = Intent(this@MainActivity, RecordingService::class.java)
                            startForegroundService(intent)
                            doBind(intent)
                            return@pollLoop
                        }
                        is AuthManager.PollResult.Pending -> {
                            // Keep waiting — dots already animated above
                        }
                        is AuthManager.PollResult.SlowDown -> {
                            delay(5000)
                        }
                        is AuthManager.PollResult.Expired -> {
                            authTitle.text = "关联Google Drive"
                            authStatus.text = "代码已过期\n点击重试"
                            authCode.visibility = View.GONE
                            authBtn.isEnabled = true
                            authBtn.text = "重试"
                            return@pollLoop
                        }
                        is AuthManager.PollResult.Error -> {
                            authTitle.text = "关联Google Drive"
                            authStatus.text = "错误: ${result.message}\n点击重试"
                            authCode.visibility = View.GONE
                            authBtn.isEnabled = true
                            authBtn.text = "重试"
                            return@pollLoop
                        }
                    }
                }
            }
        }
    }

    // ─── Recording Controls ──────────────────────────────────────────────

    private fun toggle() {
        val prefs = getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
        val svc = service
        if (svc == null || !svc.isCurrentlyRecording()) {
            // Start recording — clear any pending stop confirmation
            confirmPending = false
            confirmResetJob?.cancel()
            prefs.edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, true).apply()
            val intent = Intent(this, RecordingService::class.java)
            startForegroundService(intent)
            doBind(intent)
        } else {
            // Stop recording — require two taps for confirmation
            if (!confirmPending) {
                confirmPending = true
                toggleBtn.text = "确定?"
                statusText.text = "再次点击停止"
                toggleBtn.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                confirmResetJob = scope.launch {
                    delay(CONFIRM_TIMEOUT_MS)
                    confirmPending = false
                    updateUI()
                }
                return
            }
            // Second tap — actually stop
            confirmPending = false
            confirmResetJob?.cancel()
            prefs.edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, false).apply()
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_STOP
            }
            startService(intent)
        }
        updateUI()
    }

    private fun updateUI() {
        if (!AuthManager.isAuthenticated()) return

        val storageConfig = AuthManager.getStorageConfig()
        val storageInfo = when (storageConfig?.storageType) {
            StorageType.GOOGLE_DRIVE -> "Drive"
            StorageType.S3 -> "S3"
            null -> "未知"
        }

        val svc = service
        if (svc != null && svc.isCurrentlyRecording()) {
            // 录音状态 — 红色按钮
            toggleBtn.setBackgroundResource(R.drawable.circle_button_recording)
            if (!confirmPending) {
                statusText.text = "录音中"
                statusText.setTextColor(0xFFCC3333.toInt())
                toggleBtn.text = "停止"
            }

            val elapsed = System.currentTimeMillis() - svc.recordingStartTime
            val mins = (elapsed / 60000).toInt()
            val hrs = mins / 60
            val m = mins % 60
            val chunks = svc.totalChunks
            val mb = String.format("%.1f", svc.getStorageUsed() / 1024.0 / 1024.0)

            infoText.text = "${hrs}小时 ${m}分钟 | ${chunks} 片段\n${mb} MB | $storageInfo"
        } else {
            // 停止状态 — 默认按钮
            toggleBtn.setBackgroundResource(R.drawable.circle_button)
            confirmPending = false
            statusText.text = "已停止"
            statusText.setTextColor(0xFF888888.toInt())
            toggleBtn.text = "开始"
            infoText.text = "点击录音\n长按退出登录"
        }
    }

    // ─── Battery Exemption ─────────────────────────────────────────────

    private fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        // Only prompt once — don't nag on every app open
        val prefs = getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
        if (prefs.getBoolean("battery_exemption_asked", false)) return
        prefs.edit().putBoolean("battery_exemption_asked", true).apply()

        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Some watches may not support this intent
        }
    }

    // ─── Permissions ─────────────────────────────────────────────────────

    private fun checkPermissions(): Boolean {
        val perms = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
        val needed = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
            false
        } else true
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (AuthManager.isAuthenticated()) {
                toggle()
            }
        } else {
            val denied = perms.filterIndexed { i, _ -> results[i] != PackageManager.PERMISSION_GRANTED }
            val permanentlyDenied = denied.any { !ActivityCompat.shouldShowRequestPermissionRationale(this, it) }
            if (permanentlyDenied) {
                Toast.makeText(this, "需要权限\n前往设置 > 应用", Toast.LENGTH_LONG).show()
            }
        }
    }
}
