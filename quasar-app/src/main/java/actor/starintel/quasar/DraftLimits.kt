package actor.starintel.quasar

/** Count before copying/encoding. Reject the whole edit; never silently truncate source. */
internal object DraftLimits {
    const val MAX_BYTES = 256 * 1024
    const val MAX_ENTRIES = 32
    const val MAX_MEMORY_BYTES = 2 * 1024 * 1024

    fun replacementFits(source: CharSequence, start: Int, end: Int, dest: CharSequence, dstart: Int, dend: Int): Boolean {
        val size = dstart.toLong() + end - start + dest.length - dend
        if (size > MAX_BYTES || size < 0) return false
        val combined = object : CharSequence {
            override val length = size.toInt()
            override fun get(index: Int): Char = when {
                index < dstart -> dest[index]
                index < dstart + end - start -> source[start + index - dstart]
                else -> dest[dend + index - dstart - (end - start)]
            }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("Not copied")
        }
        return runCatching { utf8Bytes(combined) }.isSuccess
    }

    fun utf8Bytes(source: CharSequence, start: Int = 0, end: Int = source.length): Int {
        require(start >= 0 && end >= start && end <= source.length)
        require(end - start <= MAX_BYTES) { "Draft exceeds 256 KiB" }
        var bytes = 0
        var i = start
        while (i < end) {
            val c = source[i++]
            bytes += when {
                c.code < 128 -> 1
                c.code < 2048 -> 2
                Character.isHighSurrogate(c) && i < end && Character.isLowSurrogate(source[i]) -> { i++; 4 }
                Character.isSurrogate(c) -> 1 // JVM UTF-8 replacement for malformed UTF-16
                else -> 3
            }
            require(bytes <= MAX_BYTES) { "Draft exceeds 256 KiB" }
        }
        return bytes
    }
}
