package com.kronos.offlinechat.web

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

data class WebResult(val title: String, val snippet: String, val url: String)
data class SearchResult(val results: List<WebResult>)

object WebSearchClient {
  private const val tag = "OfflineChatSearch"
  fun search(template: String, query: String): SearchResult {
    require(template.contains("{query}")) { "Search provider URL is not configured." }; require(query.length in 2..512) { "Invalid search query." }
    val endpoint = template.replace("{query}", URLEncoder.encode(query, "UTF-8"))
    Log.i(tag, "request provider=${URL(endpoint).host} queryLength=${query.length}")
    val c = URL(endpoint).openConnection() as HttpURLConnection
    c.instanceFollowRedirects = true; c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; LocalGemmaChat)"); c.connectTimeout=15_000; c.readTimeout=20_000
    check(c.responseCode in 200..299) { "Search provider HTTP ${c.responseCode}" }
    val html = c.inputStream.bufferedReader().use { it.readText() }; Log.i(tag, "response status=${c.responseCode} bytes=${html.length}")
    val results = parseDuckDuckGoHtml(html)
    Log.i(tag, "parsedResults=${results.size}"); check(results.isNotEmpty()) { "DuckDuckGo returned no parseable results; do not use the search URL as evidence." }
    return SearchResult(results)
  }
  /** Parses only actual DuckDuckGo result cards; the provider endpoint is never returned as evidence. */
  internal fun parseDuckDuckGoHtml(html: String): List<WebResult> =
    Regex("""class="result__a" href="([^"]+)">(.*?)</a>.*?class="result__snippet"[^>]*>(.*?)</a>""", setOf(RegexOption.DOT_MATCHES_ALL))
      .findAll(html).mapNotNull { m ->
        val url = destination(m.groupValues[1]) ?: return@mapNotNull null
        WebResult(clean(m.groupValues[2]), clean(m.groupValues[3]), url)
      }.filter { it.title.isNotBlank() && it.snippet.isNotBlank() }.take(5).toList()
  private fun destination(href: String): String? = runCatching { URLDecoder.decode(href.substringAfter("uddg=").substringBefore('&'), "UTF-8") }.getOrNull()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
  private fun clean(html: String) = html.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&#x27;", "'").trim()
}
object StructuredToolCallParser { private val expression=Regex("""^\s*\{\s*"tool"\s*:\s*"web_search"\s*,\s*"query"\s*:\s*"([^"\n]{2,512})"\s*}\s*$"""); fun parse(text:String)=expression.matchEntire(text)?.groupValues?.get(1)?.takeIf{!it.contains('\n')} }
