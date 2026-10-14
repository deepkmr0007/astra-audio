package app.astra.core.common

/**
 * Minimal, dependency-free JSON writer and parser used for the app's own metadata,
 * transcripts and exports. It intentionally supports only standard JSON (RFC 8259)
 * and enforces depth / size limits so a malformed or hostile file cannot exhaust
 * memory or the stack.
 */
object Json {

    const val MAX_DEPTH = 64
    const val MAX_INPUT_CHARS = 64 * 1024 * 1024

    // ---------------------------------------------------------------- writing

    fun write(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        writeValue(sb, value, pretty, 0)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?, pretty: Boolean, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Float -> writeDouble(sb, value.toDouble())
            is Double -> writeDouble(sb, value)
            is Map<*, *> -> {
                if (value.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newline(sb, indent + 1)
                    writeString(sb, k.toString())
                    sb.append(if (pretty) ": " else ":")
                    writeValue(sb, v, pretty, indent + 1)
                }
                if (pretty) newline(sb, indent)
                sb.append('}')
            }
            is Iterable<*> -> {
                val list = value.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                list.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    if (pretty) newline(sb, indent + 1)
                    writeValue(sb, v, pretty, indent + 1)
                }
                if (pretty) newline(sb, indent)
                sb.append(']')
            }
            is Array<*> -> writeValue(sb, value.toList(), pretty, indent)
            else -> writeString(sb, value.toString())
        }
    }

    private fun newline(sb: StringBuilder, indent: Int) {
        sb.append('\n')
        repeat(indent) { sb.append("  ") }
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) { sb.append("null"); return }
        if (d == Math.floor(d) && Math.abs(d) < 1e15) sb.append(d.toLong().toString())
        else sb.append(d.toString())
    }

    fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    // ---------------------------------------------------------------- parsing

    class ParseException(message: String) : Exception(message)

    /** Parses JSON into Map<String, Any?>, List<Any?>, String, Double/Long, Boolean or null. */
    fun parse(text: String): Any? {
        if (text.length > MAX_INPUT_CHARS) throw ParseException("JSON input too large")
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue(0)
        p.skipWs()
        if (!p.atEnd()) throw ParseException("Trailing characters at ${p.pos}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw ParseException("Expected JSON object")

    private class Parser(val s: String) {
        var pos = 0
        fun atEnd() = pos >= s.length
        fun skipWs() { while (pos < s.length && s[pos].let { it == ' ' || it == '\n' || it == '\r' || it == '\t' }) pos++ }
        fun peek(): Char = if (pos < s.length) s[pos] else throw ParseException("Unexpected end of input")

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw ParseException("JSON nesting too deep")
            skipWs()
            return when (val c = peek()) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw ParseException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) throw ParseException("Invalid literal at $pos")
            pos += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWs()
                if (peek() != '"') throw ParseException("Expected key at $pos")
                val key = readString()
                skipWs()
                if (peek() != ':') throw ParseException("Expected ':' at $pos")
                pos++
                map[key] = readValue(depth + 1)
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw ParseException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (peek() == ']') { pos++; return list }
            while (true) {
                list.add(readValue(depth + 1))
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw ParseException("Expected ',' or ']' at $pos")
                }
            }
        }

        fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw ParseException("Unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) throw ParseException("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseException("Bad unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: throw ParseException("Bad unicode escape"))
                                pos += 4
                            }
                            else -> throw ParseException("Bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw ParseException("Control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && s[pos].isDigit()) pos++
            var isDouble = false
            if (pos < s.length && s[pos] == '.') { isDouble = true; pos++; while (pos < s.length && s[pos].isDigit()) pos++ }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true; pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos].isDigit()) pos++
            }
            val str = s.substring(start, pos)
            if (str == "-" || str.isEmpty()) throw ParseException("Bad number at $start")
            return if (isDouble) str.toDouble() else (str.toLongOrNull() ?: str.toDouble())
        }
    }
}

// Convenience accessors for parsed JSON maps.
fun Map<String, Any?>.str(key: String): String? = this[key] as? String
fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
fun Map<String, Any?>.double(key: String): Double? = (this[key] as? Number)?.toDouble()
fun Map<String, Any?>.bool(key: String): Boolean? = this[key] as? Boolean
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.list(key: String): List<Any?>? = this[key] as? List<Any?>
@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.objList(key: String): List<Map<String, Any?>> =
    (this[key] as? List<Any?>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
