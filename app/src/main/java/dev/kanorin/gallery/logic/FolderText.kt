package dev.kanorin.gallery.logic

import java.util.Locale

/** Words of the inside-folder screen. */
object FolderText {
    /** App bar line under the name: "1 item · 4 KB", "1,204 items · 3.2 GB". [size] is already formatted. */
    fun subtitle(count: Int, size: String): String =
        String.format(Locale.ENGLISH, if (count == 1) "%,d item · %s" else "%,d items · %s", count, size)

    /** A folder's order before the user picks one: the inside-folder screen and a VIEW intent's folder share it. */
    val DEFAULT_SORT = SortField.DATE_TAKEN

    /** A folder's saved sort (field by name, order), else [DEFAULT_SORT]; an unsaved order is the field's usual one. */
    fun folderSort(savedField: String?, savedDescending: Boolean?): Pair<SortField, Boolean> {
        val field = savedField?.let { s -> SortField.entries.firstOrNull { it.name == s } } ?: DEFAULT_SORT
        return field to (savedDescending ?: SortText.defaultDescending(field))
    }

    /** The sort chip's label. */
    fun sortLabel(sort: SortField): String = SortText.title(sort)

    /** The chip's arrow: ↓ for the field's usual order (newest, A to Z, largest, longest), ↑ for the reverse. */
    fun sortArrow(sort: SortField, descending: Boolean): String = if (descending == SortText.defaultDescending(sort)) "↓" else "↑"

    /** What a screen reader says for the arrow. */
    fun sortDirection(sort: SortField, descending: Boolean): String = "Sort direction, " + when (sort) {
        SortField.DATE_TAKEN, SortField.DATE_MODIFIED -> if (descending) "newest first" else "oldest first"
        SortField.NAME -> if (descending) "Z to A" else "A to Z"
        SortField.SIZE -> if (descending) "largest first" else "smallest first"
        SortField.DURATION -> if (descending) "longest first" else "shortest first"
    }
}
