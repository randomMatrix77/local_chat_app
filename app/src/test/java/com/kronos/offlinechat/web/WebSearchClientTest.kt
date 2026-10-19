package com.kronos.offlinechat.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebSearchClientTest {
  @Test fun duckDuckGoCardsUseDestinationUrlInsteadOfSearchEndpoint() {
    val html = """
      <a class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.formula1.com%2Fen%2Fresults%2F2025%2Fdrivers">2025 results</a>
      <a class="result__snippet" href="#">Official driver standings for 2025.</a>
    """.trimIndent()

    val result = WebSearchClient.parseDuckDuckGoHtml(html).single()

    assertEquals("2025 results", result.title)
    assertEquals("Official driver standings for 2025.", result.snippet)
    assertEquals("https://www.formula1.com/en/results/2025/drivers", result.url)
    assertFalse(result.url.contains("duckduckgo.com/html"))
  }
}
