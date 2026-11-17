package raf.console.quran7hours.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import raf.console.quran7hours.data.InkPoint
import raf.console.quran7hours.data.InkStroke
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Q7H smart marker v7: independent ink-bounds for every line and verified ayah joins.
 *
 * The QPC layout is authoritative for the ORDER of words and ayah membership.
 * The currently displayed, trimmed Mushaf BITMAP is authoritative for pixel
 * positions. It is not safe to estimate an ayah's x coordinates by font width
 * alone: glyph widths, image variant, ornament sizes and word justification vary.
 *
 * For each page we fit text rows to image-ink projections, then snap predicted
 * word boundaries to *measured ink gaps*. We scan the selected horizontal slice
 * to include every visible letter and vowel mark vertically. A profile is kept
 * in application files, keyed by image sample/fingerprint and QPC version.
 *
 * This is image-layout calibration, NOT a neural OCR model. No guessed verse is
 * accepted if the page geometry or word boundaries are too ambiguous.
 */
internal object Q7HSmartAyahMarker {
    internal data class Ref(val surah: Int, val ayah: Int)
    internal data class Selection(val ref: Ref, val strokes: List<InkStroke>)
    internal data class Calibration(val ok: Boolean, val reason: String = "")

    private const val TAG = "Q7HSmartMarker"
    private const val PROFILE_VERSION = 7
    private data class Word(val ref: Ref, val glyph: String, val readable: String)
    private data class Line(val slot: Int, val words: List<Word>)
    private data class Layout(val lines: List<Line>, val slotCount: Int)
    private data class Band(val cy: Float, val top: Float, val bottom: Float,
                            val left: Float, val right: Float)
    private data class Profile(val bands: List<Band>, val cuts: List<FloatArray>,
                               val pitch: Float, val verified: List<BooleanArray>)
    private data class Prepared(val layout: Layout, val profile: Profile)

    // Limited cache: never retain full page bitmaps here.
    private val memory = object : LinkedHashMap<String, Profile>(15, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Profile>?) = size > 12
    }

    private fun layout(context: Context, page: Int): Layout? = runCatching {
        val filename = "quran/qpc-v4/layout/page-${page.toString().padStart(3, '0')}.json"
        val root = context.assets.open(filename).bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        if (root.optInt("page") != page) return null
        val rows = root.getJSONArray("lines")
        val result = ArrayList<Line>()
        for (slot in 0 until rows.length()) {
            val row = rows.getJSONObject(slot)
            if (row.optString("type") != "text") continue
            val items = row.optJSONArray("words") ?: continue
            val words = ArrayList<Word>()
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                val where = obj.optString("location").split(':')
                val surah = where.getOrNull(0)?.toIntOrNull() ?: continue
                val ayah = where.getOrNull(1)?.toIntOrNull() ?: continue
                val index = where.getOrNull(2)?.toIntOrNull() ?: continue
                if (surah !in 1..114 || ayah < 1 || index < 1) continue
                // The app uses Hanafi numbering on al-Fatihah. Basmalah is a preface,
                // and QPC's final ayah is divided after its first four words.
                val mapped = if (surah == 1) when (ayah) {
                    1 -> 0
                    in 2..6 -> ayah - 1
                    7 -> if (index <= 4) 6 else 7
                    else -> ayah
                } else ayah
                words += Word(Ref(surah, mapped), obj.optString("qpcV2"), obj.optString("word"))
            }
            if (words.isNotEmpty()) result += Line(slot, words)
        }
        if (result.isEmpty()) null else Layout(result, rows.length())
    }.getOrElse { Log.w(TAG, "Invalid QPC layout for page $page", it); null }

    internal fun includes(context: Context, page: Int, ref: Ref): Boolean =
        page in 1..604 && layout(context, page)?.lines?.any { line ->
            line.words.any { it.ref == ref }
        } == true

    private fun fingerprint(bitmap: Bitmap): String {
        var h = -3750763034362895579L
        // Denser fingerprint reduces collisions between different page image
        // variants that share the same dimensions and paper background.
        for (yi in 0..20) for (xi in 0..20) {
            val x = (xi * (bitmap.width - 1) / 20).coerceIn(0, bitmap.width - 1)
            val y = (yi * (bitmap.height - 1) / 20).coerceIn(0, bitmap.height - 1)
            h = (h xor bitmap.getPixel(x, y).toLong()) * 1099511628211L
        }
        return "v$PROFILE_VERSION-${bitmap.width}x${bitmap.height}-${java.lang.Long.toHexString(h)}"
    }

    private fun cacheFile(context: Context, page: Int, fingerprint: String): File {
        // Keep trained geometry in filesDir so the existing Q7H v4 backup
        // includes the 604-page calibration without modifying Room schemas.
        val dir = File(context.filesDir, "q7h_smart_ayah_v7")
        return File(dir, "${page.toString().padStart(3, '0')}-$fingerprint.json")
    }

    private fun decode(file: File, lineCount: Int): Profile? = runCatching {
        val root = JSONObject(file.readText(Charsets.UTF_8))
        if (root.optInt("version") != PROFILE_VERSION) return null
        val bands = root.getJSONArray("bands")
        val cuts = root.getJSONArray("cuts")
        val verified = root.getJSONArray("verified")
        if (bands.length() != lineCount || cuts.length() != lineCount ||
            verified.length() != lineCount) return null
        val outBands = ArrayList<Band>()
        val outCuts = ArrayList<FloatArray>()
        val outVerified = ArrayList<BooleanArray>()
        for (i in 0 until lineCount) {
            val v = bands.getJSONArray(i)
            outBands += Band(v.getDouble(0).toFloat(), v.getDouble(1).toFloat(),
                v.getDouble(2).toFloat(), v.getDouble(3).toFloat(), v.getDouble(4).toFloat())
            val array = cuts.getJSONArray(i)
            outCuts += FloatArray(array.length()) { j -> array.getDouble(j).toFloat() }
            val flags = verified.getJSONArray(i)
            if (flags.length() != array.length()) return null
            outVerified += BooleanArray(flags.length()) { j -> flags.getBoolean(j) }
        }
        Profile(outBands, outCuts, root.getDouble("pitch").toFloat(), outVerified)
    }.getOrNull()

    private fun encode(file: File, profile: Profile) = runCatching {
        file.parentFile?.mkdirs()
        val root = JSONObject().put("version", PROFILE_VERSION).put("pitch", profile.pitch)
        root.put("bands", JSONArray().apply {
            profile.bands.forEach { b -> put(JSONArray().put(b.cy).put(b.top).put(b.bottom)
                .put(b.left).put(b.right)) }
        })
        root.put("cuts", JSONArray().apply {
            profile.cuts.forEach { values ->
                put(JSONArray().apply { values.forEach { put(it.toDouble()) } })
            }
        })
        root.put("verified", JSONArray().apply {
            profile.verified.forEach { flags ->
                put(JSONArray().apply { flags.forEach { put(it) } })
            }
        })
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(root.toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) { temp.copyTo(file, overwrite = true); temp.delete() }
    }

    private class Pixels(val bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        private val bg: IntArray = run {
            val samples = ArrayList<Int>()
            for (k in 0..5) {
                val x = ((w - 1) * k / 5)
                samples += bitmap.getPixel(x, 0)
                samples += bitmap.getPixel(x, h - 1)
            }
            fun median(shift: Int): Int = samples.map { (it ushr shift) and 255 }
                .sorted()[samples.size / 2]
            intArrayOf(median(16), median(8), median(0))
        }
        fun hasInk(x: Int, y: Int): Boolean {
            val c = bitmap.getPixel(x, y)
            val dr = abs(((c ushr 16) and 255) - bg[0])
            val dg = abs(((c ushr 8) and 255) - bg[1])
            val db = abs((c and 255) - bg[2])
            return max(dr, max(dg, db)) > 42
        }
        fun yProjection(): IntArray {
            val projection = IntArray(h)
            for (y in (h * .07f).toInt() until (h * .965f).toInt()) {
                var hits = 0
                for (x in (w * .075f).toInt() until (w * .955f).toInt() step 4)
                    if (hasInk(x, y)) hits++
                projection[y] = hits
            }
            // Smooth only over 5 pixels, not 0.8% of the entire page height;
            // larger windows bridge harakat into the neighboring line.
            val smooth = IntArray(h)
            for (y in 3 until h - 3) {
                var total = 0
                for (dy in -3..3) total += projection[y + dy]
                smooth[y] = total / 7
            }
            return smooth
        }
        fun xProjection(top: Int, bottom: Int): IntArray {
            val projection = IntArray(w)
            // An ayah may begin at either physical page margin. Fixed 3.5%/96.5%
            // cropping cut off first/last letters on some pages.
            for (x in 2 until w - 2) {
                var hits = 0
                for (y in top..bottom step 2) if (hasInk(x, y)) hits++
                projection[x] = hits
            }
            return projection
        }
        /** Pixel-exact horizontal extent of THIS line; don't inherit another
         * line's alignment or a percentage margin from the whole page. */
        fun boundsX(y0: Int, y1: Int): Pair<Int, Int>? {
            val xs = xProjection(y0, y1)
            var left = w
            var right = -1
            for (x in 2 until w - 2) {
                if (xs[x] > 0) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
            return if (right > left + w * .08f) left to right else null
        }

        /** Find an actual blank pixel run between words, near the rough QPC
         * advance prediction. Never silently treat a cut THROUGH a glyph as
         * a trustworthy boundary between two verses. */
        fun verifiedGap(xs: IntArray, predicted: Float, lower: Int, upper: Int,
                        radius: Int): Float? {
            val lo = max(lower + 2, predicted.roundToInt() - radius)
            val hi = min(upper - 2, predicted.roundToInt() + radius)
            if (hi <= lo) return null
            val minRun = max(2, w / 950)
            var runStart = -1
            var best: Float? = null
            var bestScore = Float.POSITIVE_INFINITY
            for (x in lo..(hi + 1)) {
                val empty = x <= hi && xs[x] <= 1
                if (empty && runStart < 0) runStart = x
                if (!empty && runStart >= 0) {
                    val length = x - runStart
                    if (length >= minRun) {
                        val center = (runStart + x - 1) / 2f
                        // Prefer nearby real whitespace, not artificial all-text
                        // alignment nor a gap hundreds of pixels away.
                        val distance = abs(center - predicted) / radius.coerceAtLeast(1)
                        val score = distance * 5f - min(10, length).toFloat() * .08f
                        if (score < bestScore) {
                            bestScore = score
                            best = center
                        }
                    }
                    runStart = -1
                }
            }
            return best
        }

        /** Conservative round ayah-number ornament detector. Only detects a
         * near-complete, approximately circular ink ring next to a QPC ayah
         * transition. Unlike a hard-coded diameter, this scales with the actual
         * row pitch of the current bitmap. If uncertain, returns null. */
        fun sealNear(expectedX: Float, centerY: Float, pitch: Float): IntRange? {
            val radiusMin = max(8, (pitch * .14f).roundToInt())
            val radiusMax = max(radiusMin, (pitch * .33f).roundToInt())
            val xRadius = max(9, (pitch * .53f).roundToInt())
            val yRadius = max(6, (pitch * .25f).roundToInt())
            val xc = expectedX.roundToInt()
            val yc = centerY.roundToInt()
            var topScore = 0f
            var winner: IntRange? = null
            for (x in xc - xRadius..xc + xRadius step 4) {
                for (y in yc - yRadius..yc + yRadius step 4) {
                    for (r in radiusMin..radiusMax step 3) {
                        if (x - r < 2 || x + r >= w-2 ||
                            y-r < 2 || y+r >= h-2) continue
                        var ring = 0
                        var octants = 0
                        // Require ink around the perimeter in multiple sectors;
                        // ordinary Arabic letter loops seldom enclose a circle
                        // of this radius at all angles.
                        for (k in 0 until 16) {
                            val a = 2.0 * PI * k / 16
                            val dx = cos(a).toFloat()
                            val dy = sin(a).toFloat()
                            var ink = false
                            for (dr in -2..2) {
                                val rr = r + dr
                                val px = (x + rr*dx).roundToInt().coerceIn(0,w-1)
                                val py = (y + rr*dy).roundToInt().coerceIn(0,h-1)
                                if (hasInk(px,py)) { ink=true;break }
                            }
                            if(ink) {ring++;octants=octants or (1 shl (k/2))}
                        }
                        if (ring < 14 || Integer.bitCount(octants) < 7) continue
                        // A solid stroke/letter block can fake a ring: verify
                        // that the interior is much less ink-dense than its
                        // contour. Small ayah numerals are allowed inside.
                        var interior = 0
                        var totalInterior = 0
                        for (ix in -2..2) for (iy in -2..2) {
                            val sx=(x + ix*r*.18f).roundToInt()
                            val sy=(y + iy*r*.18f).roundToInt()
                            if (sx !in 0 until w || sy !in 0 until h) continue
                            totalInterior++
                            if (hasInk(sx,sy)) interior++
                        }
                        if (totalInterior == 0 || interior >= totalInterior * .55f) continue
                        val score = ring + 1.5f * (Integer.bitCount(octants)-7) -
                            abs(x-expectedX) / pitch.coerceAtLeast(1f) * 6f
                        if (score > topScore) {
                            topScore=score
                            winner=(x-r)..(x+r)
                        }
                    }
                }
            }
            return winner
        }

        fun boundsY(x0: Int, x1: Int, y0: Int, y1: Int): Pair<Int, Int>? {
            var top = h
            var bottom = -1
            var rows = 0
            for (y in y0..y1) {
                var hits = 0
                for (x in x0..x1 step 2) if (hasInk(x, y)) hits++
                // A small tashkeel dot may contain only a few pixels. Count it.
                if (hits >= 2) {top = min(top, y); bottom = max(bottom, y); rows++}
            }
            return if (rows >= 4 && bottom > top) top to bottom else null
        }
    }

    private fun weights(context: Context, page: Int, words: List<Word>): FloatArray {
        val face = runCatching { Typeface.createFromAsset(context.assets,
            "quran/qpc-v4/fonts/v2/p$page.ttf") }.getOrNull()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = 72f }
        val result = FloatArray(words.size) { index ->
            val entry = words[index]
            val w = if (face != null && entry.glyph.isNotBlank())
                paint.measureText(entry.glyph) else 0f
            if (w.isFinite() && w > 4f) w
            else entry.readable.count { ch ->
                Character.getType(ch) != Character.NON_SPACING_MARK.toInt() &&
                    Character.getType(ch) != Character.COMBINING_SPACING_MARK.toInt()
            }.coerceAtLeast(1) * 24f
        }
        // Uniform spaces are a fraction of average glyph widths.
        val mean = result.average().toFloat().coerceAtLeast(4f)
        for (i in result.indices) result[i] += mean * .14f
        return result
    }

    private fun fit(pixels: Pixels, layout: Layout, context: Context, page: Int): Profile? {
        val hist = pixels.yProjection()
        val h = pixels.h
        val w = pixels.w
        if (layout.slotCount < 2 || layout.lines.isEmpty()) return null
        val n = layout.slotCount
        val minPitch = max(16, (h * .62f / (n - 1)).toInt())
        val maxPitch = max(minPitch + 1, (h * .90f / (n - 1)).toInt())
        var bestScore = Double.NEGATIVE_INFINITY
        var bestTop = 0
        var bestPitch = 0
        // Use physical row slots (including surah headings/basmalah), not the
        // compact text-line index: otherwise pages with surah titles drift.
        for (pitch in minPitch..maxPitch step max(2, h / 370)) {
            for (first in (h * .075f).toInt()..(h * .22f).toInt() step max(2, h / 380)) {
                if (first + (n - 1) * pitch > h * .96f) continue
                var score = 0.0
                for (line in layout.lines) {
                    val cy = first + line.slot * pitch
                    val radius = max(3, (pitch * .18f).toInt())
                    var peak = 0
                    for (y in (cy-radius).coerceAtLeast(0)..(cy+radius).coerceAtMost(h-1) step 2)
                        peak = max(peak, hist[y])
                    if (peak < 2) score -= 5.0
                    else score += kotlin.math.sqrt(peak.toDouble())
                }
                if (score > bestScore) {bestScore = score;bestPitch = pitch;bestTop = first}
            }
        }
        if (bestPitch == 0) return null
        val bands = ArrayList<Band>(layout.lines.size)
        val cuts = ArrayList<FloatArray>(layout.lines.size)
        val verified = ArrayList<BooleanArray>(layout.lines.size)
        for (line in layout.lines) {
            val near = bestTop + line.slot * bestPitch
            val window = max(4, (bestPitch * .17f).toInt())
            val cy = ((near-window).coerceAtLeast(0)..(near+window).coerceAtMost(h-1))
                .maxByOrNull { hist[it] } ?: near
            val half = max(8, (bestPitch * .44f).toInt())
            val y0 = (cy - half).coerceAtLeast(0)
            val y1 = (cy + half).coerceAtMost(h-1)
            val xs = pixels.xProjection(y0, y1)
            val envelope = pixels.boundsX(y0, y1) ?: return null
            val left = envelope.first
            val right = envelope.second
            val span = right - left
            if (span < w * .20f) return null
            val flags = BooleanArray(line.words.size + 1) { true }
            // Image-based word-space fitting. QPC font widths provide only
            // initial guesses. The final cut always snaps to a low-ink column.
            val widthHints = weights(context, page, line.words)
            val total = widthHints.sum().coerceAtLeast(1f)
            val rawCuts = FloatArray(line.words.size + 1)
            rawCuts[0] = right.toFloat()
            rawCuts[line.words.size] = left.toFloat()
            var cum = 0f
            for (j in 1 until line.words.size) {
                cum += widthHints[j - 1]
                val expected = right - span * cum / total
                val crossingAyah = line.words[j - 1].ref != line.words[j].ref
                // On a shared line only the transition between ayahs must be
                // precisely split. Word-internal cuts are only for locating a
                // tap, not for drawing the edges of a whole-line selection.
                val radius = if (crossingAyah)
                    max(9, min((span * .105f).toInt(), (span / line.words.size * 1.65f).toInt()))
                else max(5, min((span * .075f).toInt(), (span / line.words.size * .9f).toInt()))
                val gap = pixels.verifiedGap(xs, expected, left, right, radius)
                // Without visible whitespace we must not blindly cut THROUGH
                // an adjacent ayah; mark this boundary unverified.
                flags[j] = !crossingAyah || gap != null
                val candidate = gap ?: expected
                val minDistance = max(1.5f, span.toFloat() / (line.words.size * 18f))
                val low = left.toFloat() + (line.words.size - j) * minDistance
                val high = rawCuts[j-1] - minDistance
                if (low >= high) return null
                rawCuts[j] = candidate.coerceIn(low, high)
                if (crossingAyah && abs(rawCuts[j] - candidate) > max(3f, radius * .22f))
                    flags[j] = false
            }
            if (rawCuts[rawCuts.size - 2] <= left + 1f) return null
            val ybounds = pixels.boundsY(left, right, y0, y1) ?: return null
            bands += Band(cy.toFloat()/h, ybounds.first.toFloat()/h,
                ybounds.second.toFloat()/h, left.toFloat()/w, right.toFloat()/w)
            cuts += FloatArray(rawCuts.size) { j -> rawCuts[j]/w }
            verified += flags
        }
        return Profile(bands, cuts, bestPitch.toFloat()/h, verified)
    }

    private fun prepared(context: Context, bitmap: Bitmap, page: Int): Prepared? {
        if (page !in 1..604 || bitmap.width < 250 || bitmap.height < 380) return null
        val layout = layout(context, page) ?: return null
        val fingerprint = fingerprint(bitmap)
        val key = "$page:$fingerprint"
        synchronized(memory) { memory[key] }?.let { return Prepared(layout, it) }
        val file = cacheFile(context, page, fingerprint)
        val cached = decode(file, layout.lines.size)?.takeIf { data ->
            data.cuts.indices.all { i ->
                data.cuts[i].size == layout.lines[i].words.size + 1 &&
                    data.verified[i].size == data.cuts[i].size
            }
        }
        val profile = cached
            ?: fit(Pixels(bitmap), layout, context, page)?.also { encode(file, it) }
            ?: return null
        synchronized(memory) { memory[key] = profile }
        return Prepared(layout, profile)
    }

    /** Called by an optional on-device calibration worker over all 604 pages. */
    internal fun calibrate(context: Context, bitmap: Bitmap, page: Int): Calibration {
        val model = prepared(context, bitmap, page)
            ?: return Calibration(false, "Нет достоверной геометрии страницы")
        if (model.profile.cuts.size != model.layout.lines.size)
            return Calibration(false, "Число строк не совпадает")
        return Calibration(true)
    }

    fun select(context: Context, bitmap: Bitmap, page: Int, x: Float, y: Float,
               color: Long, requested: Ref? = null): Selection? {
        val prepared = prepared(context, bitmap, page) ?: return null
        val lines = prepared.layout.lines
        val profile = prepared.profile
        val index = if (requested == null) {
            profile.bands.indices.minByOrNull { abs(profile.bands[it].cy - y) } ?: return null
        } else -1
        val chosen = requested ?: run {
            val band = profile.bands[index]
            if (y < band.top - .012f || y > band.bottom + .012f) return null
            val cuts = profile.cuts[index]
            val wordIndex = (0 until cuts.size - 1).firstOrNull { j ->
                x <= cuts[j] && x >= cuts[j+1]
            } ?: return null
            if (wordIndex > 0 && lines[index].words[wordIndex-1].ref != lines[index].words[wordIndex].ref &&
                cuts[wordIndex] - x < .007f) return null
            if (wordIndex < lines[index].words.lastIndex &&
                lines[index].words[wordIndex+1].ref != lines[index].words[wordIndex].ref &&
                x - cuts[wordIndex+1] < .007f) return null
            lines[index].words[wordIndex].ref
        }
        if (chosen.ayah < 1 || chosen.surah !in 1..114) return null
        val pixels = Pixels(bitmap)
        val out = ArrayList<InkStroke>()
        for (i in lines.indices) {
            val words = lines[i].words
            val indices = words.indices.filter { words[it].ref == chosen }
            if (indices.isEmpty()) continue
            if (indices.last() - indices.first() + 1 != indices.size) return null
            val band = profile.bands[i]
            val cuts = profile.cuts[i]
            val wholeLine = indices.first() == 0 && indices.last() == words.lastIndex
            // A whole-line ayah MUST use the measured edges of THIS particular
            // line, rather than cutting by proportional estimates of word widths.
            // Thus every first/last glyph and its diacritics are included.
            if (!wholeLine) {
                if (indices.first() > 0 && !profile.verified[i][indices.first()]) return null
                if (indices.last() < words.lastIndex &&
                    !profile.verified[i][indices.last() + 1]) return null
            }
            val pixel = 1f / bitmap.width
            var right = if (indices.first() == 0) band.right + 2f * pixel
                else cuts[indices.first()] - 1.25f * pixel
            var left = if (indices.last() == words.lastIndex) band.left - 2f * pixel
                else cuts[indices.last() + 1] + 1.25f * pixel
            // Detect ornate circular ayah-number signs at a shared boundary
            // and exclude the disk itself from BOTH neighboring highlights.
            // This is a measured bitmap test, never a fixed offset applied to
            // every line. No disk is inferred if the round glyph is absent.
            val pitchPixels = profile.pitch * bitmap.height
            val centerPixels = band.cy * bitmap.height
            fun circleAt(x: Float) = pixels.sealNear(x * bitmap.width,
                centerPixels, pitchPixels)
            val ornamentPad = 2.5f / bitmap.width
            if (indices.first() > 0) {
                circleAt(cuts[indices.first()])?.let { seal ->
                    right = min(right, seal.first.toFloat() / bitmap.width - ornamentPad)
                }
            }
            if (indices.last() < words.lastIndex) {
                circleAt(cuts[indices.last()+1])?.let { seal ->
                    left = max(left, seal.last.toFloat() / bitmap.width + ornamentPad)
                }
            }
            if (wholeLine) {
                // An ornament at the edge may still belong to a preceding or
                // following ayah, even when this row has only one ayah ref.
                circleAt(band.left)?.let { seal ->
                    if (seal.first.toFloat()/bitmap.width <= band.left + 6f*pixel)
                        left = max(left,seal.last.toFloat()/bitmap.width + ornamentPad)
                }
                circleAt(band.right)?.let { seal ->
                    if (seal.last.toFloat()/bitmap.width >= band.right - 6f*pixel)
                        right = min(right,seal.first.toFloat()/bitmap.width - ornamentPad)
                }
            }
            right = right.coerceAtMost((bitmap.width - 2f) / bitmap.width)
            left = left.coerceAtLeast(2f / bitmap.width)
            if (right - left < .008f) return null
            val centerY = band.cy
            // The scanned zone reaches close to each physical line's half-pitch,
            // retaining raised harakat without touching the next line's glyphs.
            val half = profile.pitch * .46f
            val y0 = ((centerY - half) * bitmap.height).roundToInt().coerceIn(0,bitmap.height-1)
            val y1 = ((centerY + half) * bitmap.height).roundToInt().coerceIn(y0,bitmap.height-1)
            val x0 = (left * bitmap.width).roundToInt().coerceIn(0,bitmap.width-1)
            val x1 = (right * bitmap.width).roundToInt().coerceIn(x0,bitmap.width-1)
            val bounds = pixels.boundsY(x0,x1,y0,y1) ?: return null
            // Small per-line ink padding includes high harakat / long descenders
            // while preserving a visible space above and below the next line.
            val pad = max(3f, profile.pitch * bitmap.height * .038f)
            val top = max(y0.toFloat(), bounds.first - pad)
            val bottom = min(y1.toFloat(), bounds.second + pad)
            if (bottom - top < 5f || bottom-top > profile.pitch * bitmap.height * .94f)
                return null
            val width = ((bottom-top)/bitmap.width).coerceAtLeast(.012f)
            val cy = (top + bottom) * .5f / bitmap.height
            out += InkStroke("smart_marker", color, width,
                listOf(InkPoint(left, cy), InkPoint(right, cy)),
                "smart:${chosen.surah}:${chosen.ayah}:$page")
        }
        return if (out.isEmpty()) null else Selection(chosen, out)
    }
}
