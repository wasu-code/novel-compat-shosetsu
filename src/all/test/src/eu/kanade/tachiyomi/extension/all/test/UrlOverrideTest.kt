package eu.kanade.tachiyomi.extension.all.test

import eu.kanade.tachiyomi.lib.textinterceptor.TextInterceptor
import eu.kanade.tachiyomi.lib.textinterceptor.TextInterceptorHelper
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class UrlOverrideTest : ParsedHttpSource() {

    override val name = "Test (UrlOverrideTest)"
    override val baseUrl = "https://wasu-code.github.io"
    override val lang = "all"
    override val supportsLatest = false

    override val client = network.client.newBuilder()
        .addInterceptor(TextInterceptor())
        .build()

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/textholder?popular_page")

    override fun popularMangaSelector(): String = "body"

    override fun popularMangaNextPageSelector(): String? = null

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        title = "initial_title"
        author = "initial_author"
        artist = "initial_artist"
        description = "initial_description"
        thumbnail_url = "https://placecats.com/neo_banana/200/300"
        setUrlWithoutDomain("https://wasu-code.github.io/textholder/?initial_url")
        status = SManga.ONGOING
    }

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = "new_title"
        author = "new_author"
        description = "new_description"
        thumbnail_url = "https://placecats.com/neo_2/200/300"
        setUrlWithoutDomain("https://wasu-code.github.io/textholder/?new_url") // Won't override
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        return listOf(
            SChapter.create().apply {
                name = "Chapter 2 - Manga Url"
                setUrlWithoutDomain(response.request.url.toString())
            },
            SChapter.create().apply {
                name = "Chapter 1 - Hardcoded Url"
                setUrlWithoutDomain("https://wasu-code.github.io/textholder/?chapter1_url")
            },
        )
    }

    override fun pageListParse(document: Document): List<Page> = listOf(
        Page(
            0,
            "",
            TextInterceptorHelper.createUrl(
                "Example page",
                "url: ${document.location()}",
            ),
        ),
    )

    // ================================ Not Used ================================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used. (latestUpdatesRequest)")
    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException("Not used. (latestUpdatesSelector)")
    override fun latestUpdatesFromElement(element: Element): SManga = throw UnsupportedOperationException("Not used. (latestUpdatesFromElement)")
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException("Not Used")
    override fun searchMangaSelector(): String = throw UnsupportedOperationException("Not Used")
    override fun searchMangaFromElement(element: Element): SManga = throw UnsupportedOperationException("Not Used")
    override fun searchMangaNextPageSelector(): String? = null
    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used. (imageUrlParse)")
    override fun chapterListSelector(): String = throw UnsupportedOperationException()
    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException()
}
