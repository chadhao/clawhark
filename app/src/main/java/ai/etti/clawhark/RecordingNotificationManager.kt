package ai.etti.clawhark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Timer
import java.util.TimerTask

class RecordingNotificationManager(private val context: Context) {
    companion object {
        private const val TAG = "NotificationMgr"
        const val CHANNEL_ID = "clawhark_channel"
        const val NOTIFICATION_ID = 1
        
        private val LISTENING_WORDS = listOf(
            "正在聆听...", "全神贯注...", "专心听...", "吸收中...",
            "捕捉每个字...", "接收信号...", "竖起耳朵...", "倾听中...",
            "沉浸其中...", "调谐中...", "捕捉瞬间...", "全部记录...",
            "待命中...", "监听中...", "集中精力...", "精准锁定...",
            "保持跟踪...", "记录一切...", "锁定中...", "接收中...",
            "字字入耳...", "静静观察...", "保持警觉...", "待机中...",
            "专注中...", "实时跟进...", "登记中...", "全面记录...",
            "仔细聆听...", "已启动..."
        )
    }
    
    private var wordTimer: Timer? = null
    private var currentWordIndex = (0 until LISTENING_WORDS.size).random()
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    
    fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "录音",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "持续录音"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
        AppLog.d(TAG, "通知频道已创建")
    }
    
    fun createNotification(): Notification {
        return buildNotification(LISTENING_WORDS[currentWordIndex])
    }

    fun createChargingPausedNotification(): Notification {
        return buildNotification("充电中，已暂停录音")
    }

    private fun buildNotification(contentText: String): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("ClawHark")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }
    
    fun startWordRotation() {
        wordTimer?.cancel()
        wordTimer = Timer("word-rotation", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    currentWordIndex = (currentWordIndex + 1) % LISTENING_WORDS.size
                    notificationManager.notify(NOTIFICATION_ID, createNotification())
                    AppLog.d(TAG, "通知: ${LISTENING_WORDS[currentWordIndex]}")
                }
            }, 2 * 60 * 60 * 1000L, 2 * 60 * 60 * 1000L)
        }
    }
    
    fun stopWordRotation() {
        wordTimer?.cancel()
        wordTimer = null
    }
    
    fun randomizeWord() {
        currentWordIndex = (0 until LISTENING_WORDS.size).random()
    }
}
