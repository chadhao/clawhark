package ai.etti.clawhark

import java.io.File

interface StorageUploader {
    suspend fun uploadFile(file: File): Boolean
    
    fun getStorageType(): StorageType
    
    fun getStorageInfo(): String
}
