package ai.etti.clawhark

import android.net.Network
import java.net.HttpURLConnection
import java.net.URL

/** 在指定 [Network] 上打开 HTTP 连接，Wear OS 上传时必须使用以避免走蓝牙代理。 */
object NetworkHttp {
    fun openConnection(url: String, network: Network? = null): HttpURLConnection {
        return if (network != null) {
            network.openConnection(URL(url)) as HttpURLConnection
        } else {
            URL(url).openConnection() as HttpURLConnection
        }
    }
}
