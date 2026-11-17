package raf.console.quran7hours.ui

import android.content.Context
import raf.console.quran7hours.data.MushafStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Stage 11: fail-closed verification of exact original WebP source and calibrated
 * variant/page translation. Both Q7S2 & Q7A1 contain immutable offline tables
 * derived from all four supplied packs; neither contains user annotations.
 *
 * Model coordinates always remain in the original Classic Light pixel space.
 * They are translated ONLY after SHA-256 verification against the selected
 * variant and corresponding page. Unknown/recompressed files use v7.
 */
internal object Q7HAyahSourceTrust {
    private const val BASE = "q7h_smart_ayah/compact_model_v1/"
    private const val HASHES = BASE + "source_sha256_four_variants.q7s"
    private const val SHIFTS = BASE + "calibration_four_variants.q7a"
    private const val PAGES = 604
    private const val VARIANTS = 4
    private const val HEADER = 8
    private const val HASH_BYTES = 32
    private const val SHIFT_BYTES = 5
    private const val CRC_BYTES = 4

    data class Calibration(val dx: Float, val dy: Float)
    private data class Tables(val hashes: ByteArray, val shifts: ByteArray)

    @Volatile private var tables: Tables? = null

    private fun checked(bytes: ByteArray, magic: String, stride: Int): Boolean {
        if (bytes.size != HEADER + VARIANTS * PAGES * stride + CRC_BYTES) return false
        if (!bytes.copyOfRange(0,4).contentEquals(magic.toByteArray(Charsets.US_ASCII))) return false
        if (bytes[4].toInt() and 0xff != VARIANTS ||
            bytes[5].toInt() and 0xff != (PAGES shr 8) ||
            bytes[6].toInt() and 0xff != (PAGES and 0xff) || bytes[7].toInt() != 0) return false
        val payloadEnd = bytes.size-CRC_BYTES
        val computed = CRC32().apply { update(bytes,0,payloadEnd) }.value
        val stored = ByteBuffer.wrap(bytes,payloadEnd,CRC_BYTES)
            .order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xffffffffL
        return computed == stored
    }

    @Synchronized
    private fun load(context: Context): Tables? {
        tables?.let { return it }
        val result = runCatching {
            val hashes = context.applicationContext.assets.open(HASHES).use { it.readBytes() }
            val shifts = context.applicationContext.assets.open(SHIFTS).use { it.readBytes() }
            if (!checked(hashes,"Q7S2",HASH_BYTES) || !checked(shifts,"Q7A1",SHIFT_BYTES))
                return@runCatching null
            Tables(hashes,shifts)
        }.getOrNull()
        if (result != null) tables = result
        return result
    }

    private fun variantIndex(variant: MushafStore.Variant): Int = when (variant) {
        MushafStore.Variant.LIGHT_PLAIN -> 0
        MushafStore.Variant.DARK_PLAIN -> 1
        MushafStore.Variant.LIGHT_TAJWEED -> 2
        MushafStore.Variant.DARK_TAJWEED -> 3
    }

    /** Returns null for an untrusted image, bad index, or unverified registration. */
    fun calibration(
        context: Context, variant: MushafStore.Variant, page: Int,
        digest: ByteArray?
    ): Calibration? {
        if (page !in 1..PAGES || digest?.size != HASH_BYTES) return null
        val t = load(context) ?: return null
        val record = variantIndex(variant)*PAGES + (page-1)
        val hashOff = HEADER+record*HASH_BYTES
        if (!MessageDigest.isEqual(digest,t.hashes.copyOfRange(hashOff,hashOff+HASH_BYTES))) return null
        val shiftOff = HEADER+record*SHIFT_BYTES
        if (t.shifts[shiftOff+4].toInt() != 1) return null
        val xy = ByteBuffer.wrap(t.shifts,shiftOff,4).order(ByteOrder.BIG_ENDIAN)
        val dx = xy.short.toInt() / 32f
        val dy = xy.short.toInt() / 32f
        if (!dx.isFinite() || !dy.isFinite() || kotlin.math.abs(dx)>24f || kotlin.math.abs(dy)>24f)
            return null
        return Calibration(dx,dy)
    }
}
