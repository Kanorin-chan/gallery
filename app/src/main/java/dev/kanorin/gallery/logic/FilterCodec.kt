package dev.kanorin.gallery.logic

/**
 * A [MediaFilter] as one string, so the viewer's route carries the grid's exact query (and survives process death).
 * `<6 flags>.<sort>.<group>[.<from>.<until>.<text>]`; the text is last, so it may hold anything, dots included.
 */
object FilterCodec {
    private const val SEP = '.'
    private const val TEXT = 't'

    fun encode(f: MediaFilter): String = buildString {
        listOf(f.photos, f.videos, f.gifs, f.screenshotsOnly, f.favouritesOnly, f.descending).forEach { append(if (it) '1' else '0') }
        append(SEP).append(f.sort.name).append(SEP).append(f.groupBy.name)
        f.search?.let { s ->
            append(SEP).append(s.from?.toString().orEmpty())
            append(SEP).append(s.until?.toString().orEmpty())
            // "" = no text, "t..." = text (which may itself be empty).
            append(SEP).append(s.text?.let { "$TEXT$it" }.orEmpty())
        }
    }

    /** Null for anything [encode] didn't write. */
    fun decode(s: String): MediaFilter? {
        val parts = s.split(SEP, limit = 6)
        if (parts.size != 3 && parts.size != 6) return null
        val flags = parts[0]
        if (flags.length != 6 || flags.any { it != '0' && it != '1' }) return null
        val bit = { i: Int -> flags[i] == '1' }
        val sort = SortField.entries.firstOrNull { it.name == parts[1] } ?: return null
        val group = GroupBy.entries.firstOrNull { it.name == parts[2] } ?: return null
        val search = if (parts.size == 6) {
            val from = parts[3].ifEmpty { null }?.let { it.toLongOrNull() ?: return null }
            val until = parts[4].ifEmpty { null }?.let { it.toLongOrNull() ?: return null }
            val text = parts[5].ifEmpty { null }?.let { if (it[0] == TEXT) it.substring(1) else return null }
            SearchQuery(text, from, until)
        } else {
            null
        }
        return MediaFilter(bit(0), bit(1), bit(2), bit(3), bit(4), sort, bit(5), group, search)
    }
}
