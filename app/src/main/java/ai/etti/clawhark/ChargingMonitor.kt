package ai.etti.clawhark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.*

class ChargingMonitor(
    private val context: Context,
    private val onPowerConnected: () -> Unit,
    private val onPowerDisconnected: () -> Unit
) {
    companion object {
        private const val DEBOUNCE_MS = 2000L

        fun isCharging(context: Context): Boolean {
            return try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                bm.isCharging
            } catch (_: Exception) {
                false
            }
        }

        fun isPauseOnChargeEnabled(context: Context): Boolean {
            return context.getSharedPreferences(RecordingService.PREF_FILE, Context.MODE_PRIVATE)
                .getBoolean(RecordingService.PREF_PAUSE_ON_CHARGE, true)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var debounceJob: Job? = null
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> scheduleDebounced(onPowerConnected)
                Intent.ACTION_POWER_DISCONNECTED -> scheduleDebounced(onPowerDisconnected)
            }
        }
    }

    private fun scheduleDebounced(action: () -> Unit) {
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            action()
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
        AppLog.d("ChargingMonitor", "充电状态监听已注册")
    }

    fun unregister() {
        if (!registered) return
        debounceJob?.cancel()
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        registered = false
        AppLog.d("ChargingMonitor", "充电状态监听已注销")
    }

    fun dispose() {
        unregister()
        scope.cancel()
    }
}
