package ai.etti.clawhark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Random

class ConfigServerService : Service() {

    private var httpServer: ConfigHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                if (httpServer == null) {
                    startServer()
                }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startServer() {
        AuthManager.init(applicationContext)
        ClawHarkConfig.migrateIfNeeded(applicationContext)

        val pin = generatePin()
        val port = ConfigHttpServer.PORT
        val url = buildLocalUrl(port)

        val server = ConfigHttpServer(applicationContext, pin, port)
        server.start(NanoHttpdTimeoutMs, false)
        httpServer = server

        ConfigServerState.update(running = true, pin = pin, url = url)
        acquireLocks()
        startForeground(NOTIFICATION_ID, buildNotification(url, pin))
        AppLog.i(TAG, "局域网配置服务已启动: $url PIN=$pin")
    }

    private fun stopServer() {
        httpServer?.stop()
        httpServer = null
        releaseLocks()
        ConfigServerState.update(running = false, pin = "", url = "")
        stopForeground(STOP_FOREGROUND_REMOVE)
        AppLog.i(TAG, "局域网配置服务已停止")
    }

    /** 配置期间保持 CPU 与 WiFi 活跃，避免息屏后局域网请求超时 */
    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClawHark::ConfigServer").apply {
            setReferenceCounted(false)
            acquire()
        }

        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ClawHark::ConfigServer").apply {
            setReferenceCounted(false)
            acquire()
        }
        AppLog.i(TAG, "配置服务 WakeLock/WifiLock 已获取")
    }

    private fun releaseLocks() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(url: String, pin: String): Notification {
        createChannel()

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ConfigServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ClawHark 网页设置")
            .setContentText("$url  PIN: $pin")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .addAction(
                Notification.Action.Builder(null, "关闭", stopPending).build()
            )
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "局域网网页设置",
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun generatePin(): String {
        val value = Random().nextInt(900_000) + 100_000
        return value.toString()
    }

    private fun buildLocalUrl(port: Int): String {
        val ip = getLocalIpAddress() ?: "0.0.0.0"
        return "http://$ip:$port"
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val addresses = interfaces.nextElement().inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "NetworkInterface 获取 IP 失败: ${e.message}")
        }

        return try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) null
            else {
                "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "ConfigServerService"
        private const val CHANNEL_ID = "clawhark_config_server"
        private const val NOTIFICATION_ID = 2002
        private const val NanoHttpdTimeoutMs = 30_000

        const val ACTION_START = "ai.etti.clawhark.CONFIG_SERVER_START"
        const val ACTION_STOP = "ai.etti.clawhark.CONFIG_SERVER_STOP"
    }
}

object ConfigServerState {
    @Volatile var isRunning: Boolean = false
        private set
    @Volatile var pin: String = ""
        private set
    @Volatile var localUrl: String = ""
        private set

    fun update(running: Boolean, pin: String, url: String) {
        isRunning = running
        this.pin = pin
        localUrl = url
    }
}
