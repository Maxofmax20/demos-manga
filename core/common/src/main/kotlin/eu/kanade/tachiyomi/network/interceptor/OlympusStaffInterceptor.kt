package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * The Team X extension still uses the former apex domain. Olympus Staff's
 * working host is the www subdomain, so normalize requests without changing
 * manga or chapter paths, source IDs, or saved library entries.
 */
object OlympusStaffUrlFix {
    private const val OLD_HOST = "olympustaff.com"
    private const val NEW_HOST = "www.olympustaff.com"

    fun normalize(url: HttpUrl): HttpUrl {
        return if (url.host == OLD_HOST) {
            url.newBuilder()
                .scheme("https")
                .host(NEW_HOST)
                .build()
        } else {
            url
        }
    }

    fun normalize(url: String): String {
        val parsedUrl = url.toHttpUrlOrNull() ?: return url
        return normalize(parsedUrl).toString()
    }

    internal fun isDiscordRedirect(sourceUrl: HttpUrl, finalUrl: HttpUrl): Boolean {
        return sourceUrl.host == NEW_HOST &&
            finalUrl.host in setOf("discord.gg", "discord.com")
    }
}

/**
 * Normalize Team X requests at the app networking boundary, including clients
 * derived by installed extensions. Avoid feeding Discord HTML to manga parsers
 * if the upstream site is still redirecting individual chapter URLs.
 */
class OlympusStaffInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val normalizedUrl = OlympusStaffUrlFix.normalize(originalRequest.url)
        val request = if (normalizedUrl != originalRequest.url) {
            originalRequest.newBuilder().url(normalizedUrl).build()
        } else {
            originalRequest
        }
        val response = chain.proceed(request)
        if (OlympusStaffUrlFix.isDiscordRedirect(normalizedUrl, response.request.url)) {
            response.close()
            throw IOException("Olympus Staff redirected to Discord; the source website is currently unavailable.")
        }
        return response
    }
}
