package raf.console.quran7hours.media

/** Pure, testable seek math: no negative jumps and no seeks beyond the MP3's end. */
object HadrSeekPolicy {
    fun clampMs(position: Long, duration: Long): Long =
        if (duration <= 0L) 0L else position.coerceIn(0L, duration - 1L)

    fun fromFraction(value: Float, duration: Long): Long {
        if (!value.isFinite() || duration <= 0L) return 0L
        val fraction = value.coerceIn(0f, 1f).toDouble()
        return clampMs((fraction * duration).toLong(), duration)
    }

    fun step(position: Long, amount: Long, duration: Long): Long =
        clampMs(if (amount > 0 && position > Long.MAX_VALUE - amount) Long.MAX_VALUE
            else if (amount < 0 && position < Long.MIN_VALUE - amount) Long.MIN_VALUE
            else position + amount, duration)
}
