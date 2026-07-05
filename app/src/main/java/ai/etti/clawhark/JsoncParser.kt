package ai.etti.clawhark

import org.json.JSONObject

/** 解析带注释的 JSONC（支持 // 行注释与块注释）。 */
object JsoncParser {

    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inString = false
        var escape = false

        while (i < text.length) {
            val c = text[i]

            if (inString) {
                out.append(c)
                if (escape) {
                    escape = false
                } else when (c) {
                    '\\' -> escape = true
                    '"' -> inString = false
                }
                i++
                continue
            }

            when (c) {
                '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }
                '/' -> {
                    if (i + 1 < text.length && text[i + 1] == '/') {
                        i += 2
                        while (i < text.length && text[i] != '\n') i++
                    } else if (i + 1 < text.length && text[i + 1] == '*') {
                        i += 2
                        while (i + 1 < text.length && !(text[i] == '*' && text[i + 1] == '/')) i++
                        i += 2
                    } else {
                        out.append(c)
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    fun parse(text: String): JSONObject = JSONObject(stripComments(text))
}
