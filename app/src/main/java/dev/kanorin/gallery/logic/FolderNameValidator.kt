package dev.kanorin.gallery.logic

enum class NameError(val message: String) {
    EMPTY("Give it a name"),
    ILLEGAL("Can't use \\ / : * ? \" < > |"),
    DUPLICATE("A folder with that name already exists"),
}

/** New folder / Rename name check (spec 5.3). [siblings] = names already in the same parent folder. */
object FolderNameValidator {
    private const val ILLEGAL_CHARS = "\\/:*?\"<>|"

    fun validate(name: String, siblings: Collection<String>): NameError? {
        val n = name.trim()
        if (n.isEmpty()) return NameError.EMPTY
        // "." and ".." name the folder itself / its parent; control characters break paths.
        if (n == "." || n == ".." || n.any { it in ILLEGAL_CHARS || it < ' ' }) return NameError.ILLEGAL
        if (siblings.any { it.equals(n, ignoreCase = true) }) return NameError.DUPLICATE
        return null
    }
}
