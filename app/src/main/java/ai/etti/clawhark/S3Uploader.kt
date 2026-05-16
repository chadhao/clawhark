package ai.etti.clawhark

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.S3ClientOptions
import com.amazonaws.services.s3.model.PutObjectRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

class S3Uploader(private val config: S3Config) : StorageUploader {

    companion object {
        const val TAG = "S3"
    }

    private val s3Client: AmazonS3Client by lazy {
        val credentials: AWSCredentials = BasicAWSCredentials(config.accessKey, config.secretKey)
        
        val clientConfig = ClientConfiguration()
        clientConfig.signerOverride = "AWSS3V4SignerType"
        
        val client = AmazonS3Client(credentials, clientConfig)
        client.endpoint = config.endpoint
        
        // 使用路径风格访问,避免虚拟主机风格的 DNS 问题
        client.setS3ClientOptions(
            S3ClientOptions.builder()
                .setPathStyleAccess(true)
                .build()
        )
        
        client
    }

    override suspend fun uploadFile(file: File): Boolean = withContext(Dispatchers.IO) {
        val fileSize = file.length()
        AppLog.i(TAG, "=== 上传开始: ${file.name} (${fileSize/1024}KB) ===")

        if (!file.exists()) {
            AppLog.w(TAG, "文件在上传前已删除: ${file.name} — 跳过")
            return@withContext true
        }

        try {
            // 移除 .uploading 后缀(如果存在)以获得正确的文件名
            val cleanFileName = file.name.removeSuffix(".uploading")
            val key = config.pathPrefix + cleanFileName
            AppLog.d(TAG, "上传到 S3: bucket=${config.bucket}, key=$key, endpoint=${config.endpoint}")

            val uploadStart = System.currentTimeMillis()

            val putRequest = PutObjectRequest(config.bucket, key, file)
            s3Client.putObject(putRequest)

            val elapsed = System.currentTimeMillis() - uploadStart
            val speedKBps = if (elapsed > 0) (fileSize / 1024.0) / (elapsed / 1000.0) else 0.0

            AppLog.i(TAG, "=== 上传成功 === ${file.name} -> S3 key=$key | ${elapsed}ms | ${String.format("%.0f", speedKBps)} KB/s")
            true
        } catch (e: FileNotFoundException) {
            AppLog.w(TAG, "文件在上传前已删除: ${file.name} — 跳过")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "=== 上传失败 === ${file.name}", e)
            false
        }
    }

    override fun getStorageType(): StorageType = StorageType.S3

    override fun getStorageInfo(): String = "S3"
}
