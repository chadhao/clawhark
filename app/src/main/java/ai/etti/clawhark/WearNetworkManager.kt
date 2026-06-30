package ai.etti.clawhark

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class WearNetworkManager(private val context: Context) {
    companion object {
        private const val TAG = "WearNetworkManager"
        private const val NETWORK_REQUEST_TIMEOUT_MS = 30_000L
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun requestHighBandwidthNetwork(): NetworkResult {
        if (isHighBandwidthAvailable()) {
            val network = connectivityManager.activeNetwork
            if (network != null) {
                AppLog.d(TAG, "高带宽网络已可用,无需请求")
                connectivityManager.bindProcessToNetwork(network)
                return NetworkResult.Connected(network)
            }
        }

        AppLog.i(TAG, "请求高带宽网络(Wi-Fi)...")
        
        val result = withTimeoutOrNull(NETWORK_REQUEST_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        AppLog.i(TAG, "高带宽网络已连接: ${network}")
                        connectivityManager.bindProcessToNetwork(network)
                        connectivityManager.unregisterNetworkCallback(this)
                        continuation.resume(NetworkResult.Connected(network))
                    }

                    override fun onUnavailable() {
                        AppLog.w(TAG, "高带宽网络不可用")
                        connectivityManager.unregisterNetworkCallback(this)
                        continuation.resume(NetworkResult.Unavailable)
                    }

                    override fun onLost(network: Network) {
                        AppLog.w(TAG, "高带宽网络已断开")
                    }
                }

                continuation.invokeOnCancellation {
                    AppLog.d(TAG, "网络请求被取消")
                    connectivityManager.unregisterNetworkCallback(callback)
                }

                try {
                    connectivityManager.requestNetwork(request, callback)
                    AppLog.d(TAG, "已发起高带宽网络请求")
                } catch (e: Exception) {
                    AppLog.e(TAG, "请求网络失败", e)
                    continuation.resume(NetworkResult.Error(e))
                }
            }
        }

        return result ?: run {
            AppLog.w(TAG, "网络请求超时(${NETWORK_REQUEST_TIMEOUT_MS}ms)")
            NetworkResult.Timeout
        }
    }

    fun releaseNetwork() {
        try {
            connectivityManager.bindProcessToNetwork(null)
            AppLog.d(TAG, "已释放网络绑定")
        } catch (e: Exception) {
            AppLog.w(TAG, "释放网络绑定失败: ${e.message}")
        }
    }

    private fun isHighBandwidthAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun getCurrentNetworkInfo(): String {
        val network = connectivityManager.activeNetwork
        if (network == null) {
            return "无网络连接"
        }

        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities == null) {
            return "网络信息不可用"
        }

        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝网络"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙"
            else -> "其他"
        }

        val metered = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            "非计费"
        } else {
            "计费"
        }

        return "$transport ($metered)"
    }

    sealed class NetworkResult {
        object AlreadyAvailable : NetworkResult()
        data class Connected(val network: Network) : NetworkResult()
        object Unavailable : NetworkResult()
        object Timeout : NetworkResult()
        data class Error(val exception: Exception) : NetworkResult()

        fun isSuccess(): Boolean = this is AlreadyAvailable || this is Connected
    }
}
