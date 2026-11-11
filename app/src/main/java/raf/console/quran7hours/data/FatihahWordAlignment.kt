package raf.console.quran7hours.data

import java.text.Normalizer

/**
 * Mapping for the user's Fatihah numbering (Basmala is separate, not verse 1).
 * The source word corpus has Basmala at 1:1 and a nine-word 1:7.
 * We never change the text/translation of any source word.
 */
internal object FatihahWordAlignment {
    data class Token(val arabic: String, val translation: String)

    fun normalized(text: String): String = buildString {
        // NFKD also handles presentation forms; filter out all harakat, madd and
        // Quran annotation signs, whitespace and punctuation.
        for (ch in Normalizer.normalize(text, Normalizer.Form.NFKD)) {
            val t = Character.getType(ch)
            if (t == Character.NON_SPACING_MARK.toInt() ||
                t == Character.ENCLOSING_MARK.toInt() ||
                t == Character.FORMAT.toInt() || ch == 'ـ') continue
            val mapped = when (ch) {
                'أ', 'إ', 'آ', 'ٱ', 'ٲ', 'ٳ' -> 'ا'
                'ى' -> 'ي'
                else -> ch
            }
            if (mapped in 'ء'..'ي') append(mapped)
        }
    }

    private fun norm(tokens: List<Token>): String =
        normalized(tokens.joinToString("") { it.arabic })

    private fun isKnownAcademyFatiha(source: Map<Int, List<Token>>): Boolean {
        val expectedSizes = listOf(4, 4, 2, 3, 4, 3, 9)
        if (expectedSizes.indices.any { source[it + 1]?.size != expectedSizes[it] }) return false
        if (norm(source.getValue(1)) != normalized("بسم الله الرحمن الرحيم")) return false
        if (!normalized(source.getValue(2).first().arabic).startsWith(normalized("الحمد"))) return false
        val last = source.getValue(7)
        if (normalized(last[0].arabic) !in setOf(normalized("صراط"), normalized("صِرَٰط")) ||
            normalized(last[4].arabic) != normalized("غير")) return false
        return true
    }

    private fun academyHanafiSegments(source: Map<Int, List<Token>>): Map<Int, List<Token>>? {
        if (!isKnownAcademyFatiha(source)) return null
        return mapOf(
            1 to source.getValue(2),
            2 to source.getValue(3),
            3 to source.getValue(4),
            4 to source.getValue(5),
            5 to source.getValue(6),
            6 to source.getValue(7).take(4),
            7 to source.getValue(7).drop(4)
        )
    }

    private fun matchByArabic(
        verses: List<Pair<Int, String>>,
        source: Map<Int, List<Token>>
    ): Map<Int, List<Token>>? {
        val tokens = (1..7).flatMap { source[it].orEmpty() }
        if (tokens.isEmpty()) return null
        val candidates = listOf(tokens, tokens.drop(4))
        for (candidate in candidates) {
            if (candidate.isEmpty()) continue
            if (candidate !== tokens && norm(tokens.take(4)) != normalized("بسم الله الرحمن الرحيم")) continue
            var cursor = 0
            val mapped = mutableMapOf<Int, List<Token>>()
            var ok = true
            for ((ayah, ar) in verses) {
                if (ar.isBlank()) { ok = false; break }
                val target = normalized(ar)
                var value = ""
                val chunk = mutableListOf<Token>()
                while (cursor < candidate.size && value.length < target.length) {
                    val token = candidate[cursor++]
                    value += normalized(token.arabic)
                    if (!target.startsWith(value)) { ok = false; break }
                    chunk += token
                }
                if (!ok || value != target || chunk.isEmpty()) { ok = false; break }
                mapped[ayah] = chunk
            }
            if (ok && cursor == candidate.size && mapped.size == verses.size) return mapped
        }
        return null
    }

    /** Returns null only when the source is invalid or cannot be safely mapped. */
    fun align(
        tafsir: List<Pair<Int, String>>,
        source: Map<Int, List<Token>>
    ): Map<Int, List<Token>>? {
        val verses = tafsir.sortedBy { it.first }
        if (verses.isEmpty()) return null

        // When both sources use identical numbering, preserve it.
        if (verses.all { (number, text) ->
                text.isNotBlank() && source[number]?.let { it.isNotEmpty() && norm(it) == normalized(text) } == true
            }) return verses.associate { it.first to source.getValue(it.first) }

        // The original Tafsir uses 7 verses without a numbered Basmala.
        // First prefer a complete word-by-word match with the Tafsir text.
        if (verses.map { it.first } == (1..7).toList()) {
            matchByArabic(verses, source)?.let { return it }
        }

        // If the two texts have orthographic differences, use the *known and
        // verified* 29-word source layout, only when the target numbering starts
        // at Al-Hamd and not at Basmala. This prevents a second accidental shift.
        val standard = academyHanafiSegments(source) ?: return null
        // This project intentionally uses the seven-verse reading without a
        // separately numbered Basmala. The Academy's 29-token layout has been
        // validated above, including the split after word 4 of their 1:7.
        // Do not reject correct translations simply because Tafsir contains
        // alternative orthography, HTML markup or diacritics.
        val first = verses.firstOrNull { it.first == 1 }?.second.orEmpty()
        if (first.isNotBlank() && normalized(first).startsWith(normalized("بسم الله"))) {
            // A different Tafsir scheme: do not silently shift its verse numbers.
            return null
        }
        return standard.filterKeys { k -> verses.any { it.first == k } }.takeIf { it.isNotEmpty() }
    }
}
