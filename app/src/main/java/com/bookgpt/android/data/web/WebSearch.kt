package com.bookgpt.android.data.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class WebResult(
    val title: String,
    val href: String,
    val body: String,
)

@Singleton
class WebSearch @Inject constructor() {
    suspend fun search(query: String, limit: Int = 5): List<WebResult> =
        withContext(Dispatchers.IO) {
            val document = Jsoup.connect("https://html.duckduckgo.com/html/")
                .userAgent("Mozilla/5.0 (Android) BookGPT/1.0")
                .timeout(20_000)
                .data("q", query)
                .post()

            document.select(".result").mapNotNull { result ->
                val link = result.selectFirst(".result__a") ?: return@mapNotNull null
                val title = link.text().trim()
                val href = unwrapDuckDuckGoUrl(link.attr("abs:href").ifBlank { link.attr("href") })
                val body = result.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
                if (title.isBlank() || href.isBlank()) null else WebResult(title, href, body)
            }.take(limit)
        }

    private fun unwrapDuckDuckGoUrl(url: String): String {
        val encoded = Regex("""[?&]uddg=([^&]+)""").find(url)?.groupValues?.get(1) ?: return url
        return runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrDefault(url)
    }
}
