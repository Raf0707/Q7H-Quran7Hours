package raf.console.quran7hours.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

/**
 * Q7M1: independent, read-only geometry lookup for the fixed 604-page Qur'an.
 *
 * IMPORTANT SAFETY/ACCURACY CONTRACT:
 * - This file is NOT an OCR/deep-neural network and performs NO image recognition.
 * - All coordinates are on the ORIGINAL Classic Light image: 960 x 2133 pixels.
 * - Bounds were automatically generated: no manually verified pixel IoU exists.
 * - Word boxes are PROVISIONAL: never treat them as verified for live highlighting.
 * - User marks/notes are not inputs to this model and cannot change its geometry.
 * - Stage 10 routes verified Classic Light pages through this loader.
 *   Other variants keep v7 until separate cross-variant validation.
 *
 * Binary structure (big endian):
 * header: magic[4] version:u16 width:u16 height:u16 pages:u16 indexSize:u16
 *         flags:u16 indexOffset:u32 dataOffset:u32    [24 bytes]
 * each index: offset:u32 csize:u32 usize:u32 crc32:u32
 *             verses:u16 words:u16 segments:u16 seals:u16 [24 bytes]
 * each zlib page: page:u16 verses:u16 words:u16 segments:u16 seals:u16 flags:u16
 *   verses: (surah:u8 ayah:u16 firstWord:u16 words:u16 flags:u8 segments:u8 sealIndex:u16)*
 *   segments: (line:u8 left:u16 top:u16 right:u16 bottom:u16)*
 *   seals: (x:u16 y:u16 halfWidth:u8)*
 *   words: (position:u16 line:u8 left:u16 top:u16 right:u16 bottom:u16 status:u8)*
 */
internal class Q7HCompactModelV1 private constructor(
    private val bytes: ByteArray,
    private val indexes: List<Index>,
    val width: Int,
    val height: Int,
) {
    internal enum class WordReliability {
        UNRESOLVED, ESTIMATED, GAP_ALIGNED_PROVISIONAL
    }

    internal data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(x: Int, y: Int): Boolean = x >= left && x < right && y >= top && y < bottom
    }

    internal data class Segment(val line: Int, val bounds: Bounds)
    internal data class Seal(val centerX: Int, val centerY: Int, val protectedHalfWidth: Int)
    internal data class Word(
        val position: Int,
        val line: Int,
        val candidateBounds: Bounds?,
        val reliability: WordReliability,
    )
    internal data class Verse(
        val surah: Int,
        val ayah: Int,
        val wordStartIndex: Int,
        val wordCountOnPage: Int,
        val continuesFromPreviousPage: Boolean,
        val continuesOnNextPage: Boolean,
        val hasEndSeal: Boolean,
        val segments: List<Segment>,
        val seal: Seal,
    )
    internal data class Page(
        val number: Int,
        val width: Int,
        val height: Int,
        val verses: List<Verse>,
        val words: List<Word>,
    ) {
        fun findVerse(surah: Int, ayah: Int): Verse? =
            verses.firstOrNull { it.surah == surah && it.ayah == ayah }

        fun wordsFor(verse: Verse): List<Word> =
            words.subList(verse.wordStartIndex, verse.wordStartIndex + verse.wordCountOnPage)

        fun findWord(surah: Int, ayah: Int, position: Int): Word? =
            findVerse(surah, ayah)?.let { v -> wordsFor(v).firstOrNull { it.position == position } }

        /** Only a geometric candidate. Tap-handling rules are a later integration stage. */
        fun candidateVerseAtNativePixel(x: Int, y: Int): Verse? =
            verses.firstOrNull { v -> v.segments.any { it.bounds.contains(x, y) } }
    }

    private data class Index(
        val offset: Int, val compressedSize: Int, val uncompressedSize: Int,
        val crc: Long, val verses: Int, val words: Int, val segments: Int, val seals: Int,
    )
    private data class VerseDraft(
        val surah: Int, val ayah: Int, val firstWord: Int, val wordCount: Int,
        val flags: Int, val segmentCount: Int, val sealIndex: Int,
    )

    private val cache = object : LinkedHashMap<Int, Page>(5, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Page>?): Boolean = size > 4
    }

    /** Throws IOException on corruption; callers should preserve their existing v7 fallback. */
    @Synchronized
    @Throws(IOException::class)
    fun page(pageNumber: Int): Page {
        if (pageNumber !in 1..indexes.size) throw IOException("Page outside 1..604")
        cache[pageNumber]?.let { return it }
        val index = indexes[pageNumber - 1]
        val uncompressed = inflateChecked(index)
        val parsed = parsePage(pageNumber, index, uncompressed)
        cache[pageNumber] = parsed
        return parsed
    }

    private fun inflateChecked(index: Index): ByteArray {
        try {
            val stream = ByteArrayInputStream(bytes, index.offset, index.compressedSize)
            val out = ByteArrayOutputStream(index.uncompressedSize)
            InflaterInputStream(stream).use { inflater ->
                val chunk = ByteArray(4096)
                var total = 0
                while (true) {
                    val n = inflater.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > index.uncompressedSize) throw IOException("Expanded page larger than index")
                    out.write(chunk, 0, n)
                }
            }
            val raw = out.toByteArray()
            if (raw.size != index.uncompressedSize) throw IOException("Expanded page size mismatch")
            val crc = CRC32().apply { update(raw) }.value
            if (crc != index.crc) throw IOException("Page checksum mismatch")
            return raw
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw IOException("Corrupted compressed Q7M1 page", e)
        }
    }

    private fun parsePage(number: Int, idx: Index, raw: ByteArray): Page {
        try {
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            fun u8() = buf.get().toInt() and 0xff
            fun u16() = buf.short.toInt() and 0xffff
            val current = u16()
            val vCount = u16()
            val wCount = u16()
            val sCount = u16()
            val mCount = u16()
            val flags = u16()
            if (current != number || vCount != idx.verses || wCount != idx.words ||
                sCount != idx.segments || mCount != idx.seals || flags != 0 || vCount != mCount ||
                raw.size != 12 + 11*vCount + 9*sCount + 5*mCount + 12*wCount)
                throw IOException("Q7M1 page header/count mismatch: $number")
            val drafts = ArrayList<VerseDraft>(vCount)
            val seenVerses = HashSet<Int>()
            repeat(vCount) {
                val surah=u8(); val ayah=u16(); val firstWord=u16(); val wordCount=u16()
                val opts=u8(); val nSegments=u8(); val sealIndex=u16()
                if (surah !in 1..114 || ayah !in 1..286 ||
                    !seenVerses.add(surah*1000+ayah) || nSegments < 1 ||
                    sealIndex >= mCount || firstWord+wordCount > wCount || opts and 0xf8 != 0)
                    throw IOException("Q7M1 invalid verse on page $number")
                drafts += VerseDraft(surah,ayah,firstWord,wordCount,opts,nSegments,sealIndex)
            }
            val segments = ArrayList<Segment>(sCount)
            repeat(sCount) {
                val line=u8(); val l=u16(); val t=u16(); val r=u16(); val b=u16()
                if (line !in 1..15 || l >= r || t >= b || r>width || b>height)
                    throw IOException("Q7M1 invalid segment on page $number")
                segments += Segment(line,Bounds(l,t,r,b))
            }
            val seals = ArrayList<Seal>(mCount)
            repeat(mCount) {
                val x=u16();val y=u16();val half=u8()
                if (x >= width || y >= height || half !in 1..100)
                    throw IOException("Q7M1 invalid seal on page $number")
                seals += Seal(x,y,half)
            }
            val words = ArrayList<Word>(wCount)
            repeat(wCount) {
                val position=u16();val line=u8();val l=u16();val t=u16();val r=u16();val b=u16();val status=u8()
                if (position !in 1..1024 || line !in 1..15 || status !in 0..2)
                    throw IOException("Q7M1 invalid word on page $number")
                val candidate = if (status == 0) null else {
                    if (l >= r || t >= b || r > width || b > height)
                        throw IOException("Q7M1 invalid word geometry on page $number")
                    Bounds(l,t,r,b)
                }
                words += Word(position,line,candidate,WordReliability.entries[status])
            }
            if (buf.hasRemaining()) throw IOException("Q7M1 trailing data")
            val resultVerses = ArrayList<Verse>(vCount)
            var segOffset=0
            var expectedWordOffset=0
            for (v in drafts) {
                if (v.firstWord != expectedWordOffset) throw IOException("Non-contiguous word ranges")
                expectedWordOffset += v.wordCount
                if (segOffset + v.segmentCount > segments.size) throw IOException("Invalid segment partition")
                val verseSegments=segments.subList(segOffset,segOffset+v.segmentCount).toList()
                segOffset+=v.segmentCount
                val distinctWordPositions=HashSet<Int>()
                for (i in v.firstWord until v.firstWord+v.wordCount) {
                    if (!distinctWordPositions.add(words[i].position)) throw IOException("Duplicate word position")
                }
                resultVerses += Verse(
                    v.surah,v.ayah,v.firstWord,v.wordCount,
                    v.flags and 1 != 0,v.flags and 2 != 0,v.flags and 4 != 0,
                    verseSegments,seals[v.sealIndex],
                )
            }
            if (segOffset != segments.size || expectedWordOffset != words.size)
                throw IOException("Incomplete segment or word partition")
            return Page(number,width,height,resultVerses,words)
        } catch (e: Exception) {
            if (e is IOException) throw e
            throw IOException("Unreadable Q7M1 page $number",e)
        }
    }

    companion object {
        private const val PAGE_COUNT = 604
        private const val MAX_MODEL_BYTES = 8*1024*1024
        private const val INDEX_BYTES = 24
        private const val HEADER_BYTES = 24

        @Throws(IOException::class)
        fun fromStream(input: InputStream): Q7HCompactModelV1 = fromBytes(input.readBytes())

        @Throws(IOException::class)
        fun fromBytes(raw: ByteArray): Q7HCompactModelV1 {
            if (raw.size < HEADER_BYTES + PAGE_COUNT*INDEX_BYTES || raw.size > MAX_MODEL_BYTES)
                throw IOException("Q7M1 size outside supported range")
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            val magic=ByteArray(4);buf.get(magic)
            if (!magic.contentEquals("Q7M1".toByteArray(Charsets.US_ASCII)))
                throw IOException("Invalid Q7M1 magic")
            fun u16() = buf.short.toInt() and 0xffff
            fun u32() = buf.int.toLong() and 0xffffffffL
            val version=u16();val width=u16();val height=u16();val pages=u16()
            val indexBytes=u16();val flags=u16();val indexOffset=u32();val dataOffset=u32()
            if (version != 1 || width != 960 || height != 2133 || pages != PAGE_COUNT ||
                indexBytes != INDEX_BYTES || flags != 1 || indexOffset != HEADER_BYTES.toLong() ||
                dataOffset != (HEADER_BYTES+PAGE_COUNT*INDEX_BYTES).toLong())
                throw IOException("Unsupported Q7M1 header")
            val indexes = ArrayList<Index>(PAGE_COUNT)
            var expectedOffset=dataOffset
            repeat(PAGE_COUNT) {
                val offset=u32(); val csize=u32();val usize=u32();val crc=u32()
                val vCount=u16();val wCount=u16();val sCount=u16();val mCount=u16()
                if (offset != expectedOffset || csize !in 1..200_000 || usize !in 12..200_000 ||
                    offset+csize > raw.size || vCount !in 1..300 || wCount !in 1..1024 ||
                    sCount !in 1..1024 || mCount != vCount)
                    throw IOException("Invalid Q7M1 page index $it")
                indexes += Index(offset.toInt(),csize.toInt(),usize.toInt(),crc,vCount,wCount,sCount,mCount)
                expectedOffset+=csize
            }
            if (expectedOffset != raw.size.toLong()) throw IOException("Invalid Q7M1 final size")
            return Q7HCompactModelV1(raw,indexes,width,height)
        }
    }
}
