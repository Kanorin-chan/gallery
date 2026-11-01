package dev.kanorin.gallery.logic

/** Clash-free names for files the app writes: "a.jpg" -> "a (1).jpg" -> "a (2).jpg". */
object FileNames {
    private const val MAX_TRIES = 10_000

    /** The suffix goes before the last dot; a name with no extension (or a dot-file) gets it at the end. */
    fun withSuffix(name: String, n: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
    }

    /** "jpg" for "a.b.jpg"; "" when there is none or the only dot starts the name. */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "" else name.substring(dot + 1)
    }

    /** [name] if free, else the first free suffixed name. */
    fun free(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        for (n in 1..MAX_TRIES) withSuffix(name, n).let { if (!taken(it)) return it }
        error("no free name for $name")
    }
}
