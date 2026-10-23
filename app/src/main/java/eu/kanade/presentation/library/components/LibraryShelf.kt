package eu.kanade.presentation.library.components

/**
 * Derived views of library progress. These do not add, delete, relabel, or
 * permanently move titles; a book automatically changes shelf as it is read.
 */
internal enum class LibraryShelf(val label: String) {
    ALL("All titles"),
    READING("Continue reading"),
    NOT_STARTED("Not started"),
    CAUGHT_UP("Caught up"),
    ;

    fun matches(readCount: Long, totalChapters: Long): Boolean = when (this) {
        ALL -> true
        READING -> readCount > 0 && totalChapters > readCount
        NOT_STARTED -> readCount == 0L && totalChapters > 0
        CAUGHT_UP -> totalChapters > 0 && readCount >= totalChapters
    }
}
