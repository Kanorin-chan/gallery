package dev.kanorin.gallery.logic

/** The ⋮ sheet's wording for a sort and its order. */
object SortText {
    /** Dates, sizes and lengths start big-first, names A to Z. */
    fun defaultDescending(sort: SortField): Boolean = sort != SortField.NAME

    /** The arrow points the way the list reads from its top. */
    fun order(sort: SortField, descending: Boolean): String = when (sort) {
        SortField.DATE_TAKEN, SortField.DATE_MODIFIED -> if (descending) "newest first ↓" else "oldest first ↑"
        SortField.NAME -> if (descending) "Z to A ↑" else "A to Z ↓"
        SortField.SIZE -> if (descending) "largest first ↓" else "smallest first ↑"
        SortField.DURATION -> if (descending) "longest first ↓" else "shortest first ↑"
    }

    fun title(sort: SortField): String = when (sort) {
        SortField.DATE_TAKEN -> "Date taken"
        SortField.DATE_MODIFIED -> "Date modified"
        SortField.NAME -> "Name"
        SortField.SIZE -> "Size"
        SortField.DURATION -> "Length"
    }
}
