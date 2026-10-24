package eu.kanade.tachiyomi.data.sync.service

import java.security.MessageDigest

/** Same stable source+URL identity as Demos Manga Cloud's permitted SyncYomi backup. */
// KMK -->
object CinemaLinkIdentity {
    private val reference = Regex("[0-9a-f]{24}")
    fun validReference(id: String) = reference.matches(id)
    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(24)

    fun manga(source: Long, url: String): String = fingerprint("$source|$url")
    fun chapter(mangaId: String, url: String): String = fingerprint("$mangaId|$url")
}
// KMK <--
