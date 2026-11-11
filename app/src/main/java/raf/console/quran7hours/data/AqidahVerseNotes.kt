package raf.console.quran7hours.data

/**
 * Editorial explanatory notices, NOT literal translation or Quran text.
 * Sunni / Maturidi-oriented note for verses with language about the Throne,
 * heaven, transcendence and divine attributes. Review before publication.
 */
object AqidahVerseNotes {
    private val arsh = setOf("7:54", "10:3", "13:2", "20:5", "25:59", "32:4", "57:4")
    private val heaven = setOf("6:3", "67:16", "67:17")
    private val attributes = setOf("6:18", "16:50", "42:11", "55:27", "89:22")

    fun note(surah: Int, ayah: Int): String? {
        val key = "$surah:$ayah"
        return if (key in arsh || key in heaven || key in attributes) {
            "(Богословское пояснение: Аллаху не присущи человеческие качества — " +
                "образ, место, направление, нахождение в пространстве или перемещение. " +
                "Упоминания Арша, небес и выражения об атрибутах не следует понимать " +
                "как уподобление Аллаха творениям.)"
        } else null
    }
}
