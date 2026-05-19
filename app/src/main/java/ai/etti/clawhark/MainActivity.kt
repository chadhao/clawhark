package ai.etti.clawhark

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import kotlinx.coroutines.*
import java.io.File

class MainActivity : ComponentActivity() {

    private var service: RecordingService? = null
    private var bound = false
    private var bindRequested = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var authPollingJob: Job? = null

    private var lastToggleTime = 0L
    private var lastAuthTapTime = 0L

    private var confirmPending = false
    private var confirmResetJob: Job? = null

    private var dotCount = 0

    private var uiState by mutableStateOf(UIState())

    private companion object {
        const val DEBOUNCE_MS = 600L
        const val CONFIRM_TIMEOUT_MS = 3000L
    }

    data class UIState(
        val isAuthenticated: Boolean = false,
        val isRecording: Boolean = false,
        val statusText: String = "已停止",
        val statusColor: Color = Color(0xFF888888),
        val infoText: String = "点击开始录音",
        val authTitle: String = "关联Google Drive",
        val authStatus: String = "点击关联按钮连接\n你的Google Drive",
        val authCode: String? = null,
        val authBtnText: String = "关联",
        val authBtnEnabled: Boolean = true,
        val storageInfo: String = "Drive",
        val confirmPending: Boolean = false,
        val uploadStatus: String = "",
        val isDebugMode: Boolean = false
    )

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

        AppLog.init(this)
        AuthManager.init(this)

        checkPermissions()
        updateUIState()

        setContent {
            MaterialTheme(
                colors = Colors(
                    primary = Color(0xFFAA6639),
                    primaryVariant = Color(0xFF885028),
                    secondary = Color(0xFFAA6639),
                    secondaryVariant = Color(0xFF885028),
                    background = Color(0xFF000000),
                    surface = Color(0xFF1A1A1A),
                    error = Color(0xFFCC3333),
                    onPrimary = Color.White,
                    onSecondary = Color.White,
                    onBackground = Color.White,
                    onSurface = Color.White,
                    onSurfaceVariant = Color(0xFFBBBBBB),
                    onError = Color.White
                )
            ) {
                MainScreen()
            }
        }

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

    @Composable
    fun MainScreen() {
        val listState = rememberScalingLazyListState()
        
        Scaffold(
            timeText = { TimeText() }
        ) {
            ScalingLazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF000000)),
                contentPadding = PaddingValues(
                    top = 32.dp,
                    start = 10.dp,
                    end = 10.dp,
                    bottom = 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                state = listState
            ) {
                if (uiState.isAuthenticated) {
                    item { RecordingScreen() }
                } else {
                    item { AuthScreen() }
                }
            }
        }
    }

    @Composable
    fun RecordingScreen() {
        val haptic = LocalHapticFeedback.current

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = uiState.statusText,
                color = uiState.statusColor,
                style = MaterialTheme.typography.body1,
                textAlign = TextAlign.Center
            )

            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastToggleTime < DEBOUNCE_MS) return@Button
                    lastToggleTime = now
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (checkPermissions()) {
                        toggle()
                    }
                },
                modifier = Modifier.size(100.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (uiState.isRecording) Color(0xFFCC3333) else Color(0xFFAA6639)
                )
            ) {
                Text(
                    text = if (uiState.confirmPending) "确定?" else if (uiState.isRecording) "停止" else "开始",
                    style = MaterialTheme.typography.button
                )
            }

            Text(
                text = uiState.infoText,
                color = Color(0xFFBBBBBB),
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { manualUploadAll() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF444444))
            ) {
                Text("手动上传全部", style = MaterialTheme.typography.caption1)
            }

            if (uiState.uploadStatus.isNotEmpty()) {
                Text(
                    text = uiState.uploadStatus,
                    color = Color(0xFF88CC88),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = { checkConnection() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF444444))
            ) {
                Text("检查WiFi连接", style = MaterialTheme.typography.caption1)
            }

            Button(
                onClick = { toggleDebugMode() },
                enabled = !uiState.isRecording,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (uiState.isDebugMode) Color(0xFFFFAA00) else Color(0xFF444444),
                    disabledBackgroundColor = Color(0xFF222222)
                )
            ) {
                Text(
                    text = if (uiState.isDebugMode) "调试模式 (开)" else "调试模式 (关)",
                    style = MaterialTheme.typography.caption1
                )
            }
        }
    }

    @Composable
    fun AuthScreen() {
        val haptic = LocalHapticFeedback.current

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = uiState.authTitle,
                color = Color.White,
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center
            )

            Text(
                text = uiState.authStatus,
                color = Color(0xFFBBBBBB),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center
            )

            uiState.authCode?.let { code ->
                Text(
                    text = code,
                    color = Color.White,
                    style = MaterialTheme.typography.title1,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastAuthTapTime < DEBOUNCE_MS) return@Button
                    lastAuthTapTime = now
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    startDeviceCodeFlow()
                },
                enabled = uiState.authBtnEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = uiState.authBtnText)
            }
        }
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

    private fun updateUIState() {
        val storageConfig = AuthManager.getStorageConfig()
        val storageType = storageConfig?.storageType ?: StorageType.GOOGLE_DRIVE
        val prefs = getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
        val isDebugMode = prefs.getBoolean(RecordingService.PREF_DEBUG_MODE, false)

        uiState = uiState.copy(
            isAuthenticated = AuthManager.isAuthenticated() || storageType == StorageType.S3,
            isDebugMode = isDebugMode
        )
    }

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
        updateUIState()
    }

    private fun startDeviceCodeFlow() {
        uiState = uiState.copy(
            authBtnEnabled = false,
            authBtnText = "...",
            authStatus = "正在请求代码...",
            authCode = null
        )

        scope.launch {
            val response = AuthManager.requestDeviceCode()
            if (response == null) {
                uiState = uiState.copy(
                    authBtnEnabled = true,
                    authBtnText = "重试",
                    authStatus = "连接失败\n检查WiFi后重试"
                )
                return@launch
            }

            uiState = uiState.copy(
                authTitle = "在此网址输入代码",
                authStatus = "google.com/device",
                authCode = response.userCode,
                authBtnText = "等待中"
            )
            dotCount = 0

            val interval = maxOf(response.interval, 5) * 1000L
            authPollingJob = scope.launch pollLoop@{
                while (isActive) {
                    delay(interval)
                    dotCount = (dotCount + 1) % 4
                    uiState = uiState.copy(authBtnText = "等待中" + ".".repeat(dotCount))

                    val result = AuthManager.pollForAuthorization(response.deviceCode)
                    when (result) {
                        is AuthManager.PollResult.Success -> {
                            getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
                                .edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, true).apply()
                            uiState = uiState.copy(
                                authTitle = "已连接",
                                authStatus = "",
                                authCode = null,
                                authBtnText = "确定"
                            )
                            delay(1500)
                            updateUIState()
                            val intent = Intent(this@MainActivity, RecordingService::class.java)
                            startForegroundService(intent)
                            doBind(intent)
                            return@pollLoop
                        }
                        is AuthManager.PollResult.Pending -> {
                        }
                        is AuthManager.PollResult.SlowDown -> {
                            delay(5000)
                        }
                        is AuthManager.PollResult.Expired -> {
                            uiState = uiState.copy(
                                authTitle = "关联Google Drive",
                                authStatus = "代码已过期\n点击重试",
                                authCode = null,
                                authBtnEnabled = true,
                                authBtnText = "重试"
                            )
                            return@pollLoop
                        }
                        is AuthManager.PollResult.Error -> {
                            uiState = uiState.copy(
                                authTitle = "关联Google Drive",
                                authStatus = "错误: ${result.message}\n点击重试",
                                authCode = null,
                                authBtnEnabled = true,
                                authBtnText = "重试"
                            )
                            return@pollLoop
                        }
                    }
                }
            }
        }
    }

    private fun manualUploadAll() {
        scope.launch(Dispatchers.IO) {
            try {
                val recordingsDir = File(filesDir, "recordings")
                if (!recordingsDir.exists()) {
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(uploadStatus = "无录音文件")
                    }
                    return@launch
                }

                val files = recordingsDir.listFiles()?.filter { 
                    it.isFile && it.name.endsWith(".m4a") && !it.name.endsWith(".uploading")
                } ?: emptyList()

                if (files.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(uploadStatus = "无待上传文件")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(uploadStatus = "上传中: 0/${files.size}")
                }

                var uploaded = 0
                val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>().build()
                WorkManager.getInstance(this@MainActivity).enqueue(uploadRequest)

                files.forEachIndexed { index, _ ->
                    delay(500)
                    uploaded = index + 1
                    withContext(Dispatchers.Main) {
                        uiState = uiState.copy(uploadStatus = "上传中: $uploaded/${files.size}")
                    }
                }

                delay(1000)
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(uploadStatus = "已加入上传队列")
                }
                delay(3000)
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(uploadStatus = "")
                }

            } catch (e: Exception) {
                AppLog.e("Upload", "Manual upload failed", e)
                withContext(Dispatchers.Main) {
                    uiState = uiState.copy(uploadStatus = "上传失败")
                    Toast.makeText(this@MainActivity, "上传失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun checkConnection() {
        scope.launch(Dispatchers.IO) {
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                
                val hasWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                
                withContext(Dispatchers.Main) {
                    if (hasWifi) {
                        Toast.makeText(this@MainActivity, "WiFi已连接", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "未连接WiFi", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                AppLog.e("Connection", "Check failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "连接检查失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleDebugMode() {
        val prefs = getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
        val currentDebugMode = prefs.getBoolean(RecordingService.PREF_DEBUG_MODE, false)
        val newDebugMode = !currentDebugMode
        
        prefs.edit().putBoolean(RecordingService.PREF_DEBUG_MODE, newDebugMode).apply()
        
        uiState = uiState.copy(isDebugMode = newDebugMode)
        
        val modeText = if (newDebugMode) "调试模式已开启" else "调试模式已关闭"
        Toast.makeText(this, "$modeText\n重启应用后生效", Toast.LENGTH_LONG).show()
        
        AppLog.i("MainActivity", "调试模式切换: $newDebugMode (需要重启)")
    }

    private fun toggle() {
        val prefs = getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
        val svc = service
        if (svc == null || !svc.isCurrentlyRecording()) {
            confirmPending = false
            confirmResetJob?.cancel()
            prefs.edit().putBoolean(RecordingService.PREF_SHOULD_RECORD, true).apply()
            val intent = Intent(this, RecordingService::class.java)
            startForegroundService(intent)
            doBind(intent)
        } else {
            if (!confirmPending) {
                confirmPending = true
                uiState = uiState.copy(
                    statusText = "再次点击停止",
                    confirmPending = true
                )
                confirmResetJob = scope.launch {
                    delay(CONFIRM_TIMEOUT_MS)
                    confirmPending = false
                    updateUI()
                }
                return
            }
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
        if (!AuthManager.isAuthenticated()) {
            updateUIState()
            return
        }

        val storageConfig = AuthManager.getStorageConfig()
        val storageInfo = when (storageConfig?.storageType) {
            StorageType.GOOGLE_DRIVE -> "Drive"
            StorageType.S3 -> "S3"
            null -> "未知"
        }

        val prefs = getSharedPreferences(RecordingService.PREF_FILE, MODE_PRIVATE)
        val isDebugMode = prefs.getBoolean(RecordingService.PREF_DEBUG_MODE, false)

        val recordingsDir = File(filesDir, "recordings")
        val localFileCount = if (recordingsDir.exists()) {
            recordingsDir.listFiles()?.count { 
                it.isFile && it.name.endsWith(".m4a")
            } ?: 0
        } else {
            0
        }

        val svc = service
        if (svc != null && svc.isCurrentlyRecording()) {
            val elapsed = System.currentTimeMillis() - svc.recordingStartTime
            val mins = (elapsed / 60000).toInt()
            val hrs = mins / 60
            val m = mins % 60
            val mb = String.format("%.1f", svc.getStorageUsed() / 1024.0 / 1024.0)

            uiState = uiState.copy(
                isRecording = true,
                statusText = if (!confirmPending) "录音中" else "再次点击停止",
                statusColor = Color(0xFFCC3333),
                infoText = "${hrs}小时${m}分钟 | ${localFileCount}文件\n${mb} MB | $storageInfo",
                storageInfo = storageInfo,
                isDebugMode = isDebugMode
            )
        } else {
            confirmPending = false
            val mb = if (recordingsDir.exists()) {
                val totalSize = recordingsDir.listFiles()?.filter { 
                    it.isFile && it.name.endsWith(".m4a")
                }?.sumOf { it.length() } ?: 0L
                String.format("%.1f", totalSize / 1024.0 / 1024.0)
            } else {
                "0.0"
            }
            
            uiState = uiState.copy(
                isRecording = false,
                statusText = "已停止",
                statusColor = Color(0xFF888888),
                infoText = if (localFileCount > 0) "${localFileCount}文件待上传 | ${mb} MB" else "点击开始录音",
                confirmPending = false,
                storageInfo = storageInfo,
                isDebugMode = isDebugMode
            )
        }
    }

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
