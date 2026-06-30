package ai.etti.clawhark

import android.content.Context

object OpusBitRate {
    const val PREF_OPUS_BIT_RATE = "opus_bit_rate"
    const val DEFAULT_BIT_RATE = 32000

    data class Option(
        val bitRate: Int,
        val label: String,
        val hint: String
    )

    val OPTIONS = listOf(
        Option(16000, "16 kbps", "省空间"),
        Option(24000, "24 kbps", "平衡"),
        Option(32000, "32 kbps", "推荐"),
        Option(48000, "48 kbps", "高音质")
    )

    fun loadBitRate(context: Context): Int {
        val saved = context.getSharedPreferences(ServiceConfig.PREF_FILE, Context.MODE_PRIVATE)
            .getInt(PREF_OPUS_BIT_RATE, DEFAULT_BIT_RATE)
        return OPTIONS.find { it.bitRate == saved }?.bitRate ?: DEFAULT_BIT_RATE
    }

    fun saveBitRate(context: Context, bitRate: Int) {
        val valid = OPTIONS.find { it.bitRate == bitRate }?.bitRate ?: DEFAULT_BIT_RATE
        context.getSharedPreferences(ServiceConfig.PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_OPUS_BIT_RATE, valid)
            .apply()
    }

    fun labelFor(bitRate: Int): String =
        OPTIONS.find { it.bitRate == bitRate }?.label ?: "${bitRate / 1000} kbps"
}
