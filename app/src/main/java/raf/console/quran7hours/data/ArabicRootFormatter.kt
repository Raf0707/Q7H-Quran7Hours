package raf.console.quran7hours.data

/** Display supplied morphological root as separate Arabic letters, without inventing one. */
object ArabicRootFormatter {
    fun format(root: String?): String {
        if (root.isNullOrBlank()) return "—"
        val letters = root.filter { (it in '\u0621'..'\u064A') || (it in '\u0671'..'\u06D3') }
        return if (letters.isEmpty()) "—" else letters.map(Char::toString).joinToString(" - ")
    }
}
