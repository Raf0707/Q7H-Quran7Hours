package raf.console.quran7hours.data

/** Memory-safe bound on simultaneous network AND extraction jobs; no network speed promises. */
object TransferTuning {
    private const val MIB = 1024L * 1024L

    fun parallelJobs(availableBytes: Long, compressedRemainingBytes: Long): Int {
        if (compressedRemainingBytes <= 0L) return 1
        return when {
            availableBytes > compressedRemainingBytes * 3L + 512L * MIB -> 6
            availableBytes > compressedRemainingBytes * 2L + 256L * MIB -> 3
            else -> 1
        }
    }
}
