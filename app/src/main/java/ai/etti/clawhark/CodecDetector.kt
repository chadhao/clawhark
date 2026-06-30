package ai.etti.clawhark

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

object CodecDetector {
    private const val TAG = "CodecDetector"
    
    data class CodecInfo(
        val name: String,
        val canonicalName: String,
        val mimeType: String,
        val isEncoder: Boolean,
        val isHardwareAccelerated: Boolean,
        val isSoftwareOnly: Boolean,
        val isVendor: Boolean,
        val supportedBitrateMin: Int?,
        val supportedBitrateMax: Int?,
        val supportedSampleRates: IntArray?,
        val maxChannels: Int?
    )
    
    fun detectAndLog(opusBitRate: Int = OpusBitRate.DEFAULT_BIT_RATE) {
        AppLog.i(TAG, "==================== 开始检测编解码器 ====================")
        
        val audioEncoders = detectAudioEncoders()
        val audioDecoders = detectAudioDecoders()
        
        logEncoders(audioEncoders)
        logDecoders(audioDecoders)
        logSummary(audioEncoders, audioDecoders, opusBitRate)
        
        AppLog.i(TAG, "==================== 编解码器检测完成 ====================")
    }
    
    private fun detectAudioEncoders(): List<CodecInfo> {
        val encoders = mutableListOf<CodecInfo>()
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            
            for (mimeType in codecInfo.supportedTypes) {
                if (!mimeType.startsWith("audio/")) continue
                
                try {
                    val capabilities = codecInfo.getCapabilitiesForType(mimeType)
                    val audioCapabilities = capabilities.audioCapabilities
                    val bitrateRange = audioCapabilities?.bitrateRange
                    
                    encoders.add(CodecInfo(
                        name = codecInfo.name,
                        canonicalName = codecInfo.canonicalName,
                        mimeType = mimeType,
                        isEncoder = true,
                        isHardwareAccelerated = codecInfo.isHardwareAccelerated,
                        isSoftwareOnly = codecInfo.isSoftwareOnly,
                        isVendor = codecInfo.isVendor,
                        supportedBitrateMin = bitrateRange?.lower,
                        supportedBitrateMax = bitrateRange?.upper,
                        supportedSampleRates = audioCapabilities?.supportedSampleRates,
                        maxChannels = audioCapabilities?.maxInputChannelCount
                    ))
                } catch (e: Exception) {
                    AppLog.w(TAG, "无法获取编码器能力: ${codecInfo.name} $mimeType - ${e.message}")
                }
            }
        }
        
        return encoders
    }
    
    private fun detectAudioDecoders(): List<CodecInfo> {
        val decoders = mutableListOf<CodecInfo>()
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        
        for (codecInfo in codecList.codecInfos) {
            if (codecInfo.isEncoder) continue
            
            for (mimeType in codecInfo.supportedTypes) {
                if (!mimeType.startsWith("audio/")) continue
                
                try {
                    val capabilities = codecInfo.getCapabilitiesForType(mimeType)
                    val audioCapabilities = capabilities.audioCapabilities
                    val bitrateRange = audioCapabilities?.bitrateRange
                    
                    decoders.add(CodecInfo(
                        name = codecInfo.name,
                        canonicalName = codecInfo.canonicalName,
                        mimeType = mimeType,
                        isEncoder = false,
                        isHardwareAccelerated = codecInfo.isHardwareAccelerated,
                        isSoftwareOnly = codecInfo.isSoftwareOnly,
                        isVendor = codecInfo.isVendor,
                        supportedBitrateMin = bitrateRange?.lower,
                        supportedBitrateMax = bitrateRange?.upper,
                        supportedSampleRates = audioCapabilities?.supportedSampleRates,
                        maxChannels = audioCapabilities?.maxInputChannelCount
                    ))
                } catch (e: Exception) {
                    AppLog.w(TAG, "无法获取解码器能力: ${codecInfo.name} $mimeType - ${e.message}")
                }
            }
        }
        
        return decoders
    }
    
    private fun logEncoders(encoders: List<CodecInfo>) {
        AppLog.i(TAG, "")
        AppLog.i(TAG, "========== 音频编码器 (${encoders.size}个) ==========")
        
        val grouped = encoders.groupBy { it.mimeType }
        
        for ((mimeType, codecs) in grouped.toSortedMap()) {
            val readableName = getMimeTypeReadableName(mimeType)
            AppLog.i(TAG, "")
            AppLog.i(TAG, "--- $readableName ($mimeType) ---")
            
            for (codec in codecs) {
                val hwFlag = when {
                    codec.isHardwareAccelerated -> "硬件加速"
                    codec.isSoftwareOnly -> "纯软件"
                    else -> "未知"
                }
                val vendorFlag = if (codec.isVendor) "[厂商]" else ""
                
                AppLog.i(TAG, "  • ${codec.canonicalName} $vendorFlag")
                AppLog.i(TAG, "    类型: $hwFlag")
                AppLog.i(TAG, "    内部名: ${codec.name}")
                
                if (codec.supportedBitrateMin != null && codec.supportedBitrateMax != null) {
                    AppLog.i(TAG, "    码率: ${codec.supportedBitrateMin/1000}-${codec.supportedBitrateMax/1000} kbps")
                }
                
                codec.supportedSampleRates?.let { rates ->
                    if (rates.isNotEmpty()) {
                        val rateStr = rates.sorted().joinToString(", ")
                        AppLog.i(TAG, "    采样率: $rateStr Hz")
                    }
                }
                
                codec.maxChannels?.let {
                    AppLog.i(TAG, "    最大声道: $it")
                }
            }
        }
    }
    
    private fun logDecoders(decoders: List<CodecInfo>) {
        AppLog.i(TAG, "")
        AppLog.i(TAG, "========== 音频解码器 (${decoders.size}个) ==========")
        
        val grouped = decoders.groupBy { it.mimeType }
        
        for ((mimeType, codecs) in grouped.toSortedMap()) {
            val readableName = getMimeTypeReadableName(mimeType)
            AppLog.i(TAG, "")
            AppLog.i(TAG, "--- $readableName ($mimeType) ---")
            
            for (codec in codecs) {
                val hwFlag = when {
                    codec.isHardwareAccelerated -> "硬件加速"
                    codec.isSoftwareOnly -> "纯软件"
                    else -> "未知"
                }
                val vendorFlag = if (codec.isVendor) "[厂商]" else ""
                
                AppLog.i(TAG, "  • ${codec.canonicalName} $vendorFlag ($hwFlag)")
            }
        }
    }
    
    private fun logSummary(encoders: List<CodecInfo>, decoders: List<CodecInfo>, opusBitRate: Int) {
        AppLog.i(TAG, "")
        AppLog.i(TAG, "========== 关键编码器支持情况 ==========")
        
        val aacEncoders = encoders.filter { it.mimeType == MediaFormat.MIMETYPE_AUDIO_AAC }
        val opusEncoders = encoders.filter { it.mimeType == MediaFormat.MIMETYPE_AUDIO_OPUS }
        val mp3Encoders = encoders.filter { it.mimeType == MediaFormat.MIMETYPE_AUDIO_MPEG }
        val vorbisEncoders = encoders.filter { it.mimeType == MediaFormat.MIMETYPE_AUDIO_VORBIS }
        
        logCodecSummary("AAC", aacEncoders)
        logCodecSummary("Opus", opusEncoders)
        logCodecSummary("MP3", mp3Encoders)
        logCodecSummary("Vorbis", vorbisEncoders)
        
        AppLog.i(TAG, "")
        AppLog.i(TAG, "========== 当前应用配置 ==========")
        AppLog.i(TAG, "  使用编码: Opus (${MediaFormat.MIMETYPE_AUDIO_OPUS})")
        AppLog.i(TAG, "  采样率: ${AudioRecorder.SAMPLE_RATE} Hz")
        AppLog.i(TAG, "  码率: ${opusBitRate / 1000} kbps")
        
        if (opusEncoders.isEmpty()) {
            AppLog.w(TAG, "  ✗ 设备不支持Opus编码器")
        } else {
            val opusHw = opusEncoders.any { it.isHardwareAccelerated }
            if (opusHw) {
                AppLog.i(TAG, "  ✓ Opus硬件加速可用")
            } else {
                AppLog.i(TAG, "  ✓ 设备支持Opus编码器（软件编码）")
            }
        }
        
        val currentAacHw = aacEncoders.any { it.isHardwareAccelerated }
        if (currentAacHw) {
            AppLog.i(TAG, "  注: AAC硬件加速可用（未使用）")
        } else {
            AppLog.i(TAG, "  注: AAC仅软件编码可用（未使用）")
        }
    }
    
    private fun logCodecSummary(name: String, codecs: List<CodecInfo>) {
        when {
            codecs.isEmpty() -> {
                AppLog.i(TAG, "  $name: 不支持")
            }
            codecs.any { it.isHardwareAccelerated } -> {
                val hwCodec = codecs.first { it.isHardwareAccelerated }
                AppLog.i(TAG, "  $name: ✓ 硬件加速 (${hwCodec.canonicalName})")
            }
            codecs.any { it.isSoftwareOnly } -> {
                val swCodec = codecs.first { it.isSoftwareOnly }
                AppLog.i(TAG, "  $name: 软件编码 (${swCodec.canonicalName})")
            }
            else -> {
                AppLog.i(TAG, "  $name: 支持 (${codecs.first().canonicalName})")
            }
        }
    }
    
    private fun getMimeTypeReadableName(mimeType: String): String {
        return when (mimeType) {
            MediaFormat.MIMETYPE_AUDIO_AAC -> "AAC"
            MediaFormat.MIMETYPE_AUDIO_OPUS -> "Opus"
            MediaFormat.MIMETYPE_AUDIO_MPEG -> "MP3"
            MediaFormat.MIMETYPE_AUDIO_VORBIS -> "Vorbis"
            MediaFormat.MIMETYPE_AUDIO_FLAC -> "FLAC"
            MediaFormat.MIMETYPE_AUDIO_AMR_NB -> "AMR-NB"
            MediaFormat.MIMETYPE_AUDIO_AMR_WB -> "AMR-WB"
            "audio/3gpp" -> "3GPP"
            "audio/mp4a-latm" -> "AAC-LATM"
            "audio/raw" -> "PCM"
            else -> mimeType.substringAfter("audio/").uppercase()
        }
    }
    
    fun hasHardwareAacEncoder(): Boolean {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            if (MediaFormat.MIMETYPE_AUDIO_AAC in codecInfo.supportedTypes) {
                if (codecInfo.isHardwareAccelerated) {
                    return true
                }
            }
        }
        return false
    }
    
    fun hasOpusEncoder(): Boolean {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (codecInfo in codecList.codecInfos) {
            if (!codecInfo.isEncoder) continue
            if (MediaFormat.MIMETYPE_AUDIO_OPUS in codecInfo.supportedTypes) {
                return true
            }
        }
        return false
    }
}
