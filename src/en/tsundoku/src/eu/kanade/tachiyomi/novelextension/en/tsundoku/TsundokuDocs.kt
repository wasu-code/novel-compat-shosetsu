package eu.kanade.tachiyomi.novelextension.en.tsundoku

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.asJsoup
import kuchihige.source.NovelHttpSource
import okhttp3.Request
import okhttp3.Response

class TsundokuDocs :
    NovelHttpSource(),
    NovelSource {
    override val baseUrl: String = "https://tsundoku-otaku.github.io"
    override val lang: String = "en"
    override val supportsLatest: Boolean = false
    override val name: String = "Tsundoku Docs"

//    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
//        timeZone = TimeZone.getTimeZone("UTC")
//    }

    override fun popularNovelsRequest(page: Int): Request = GET("$baseUrl/docs/guides/getting-started")

    override fun popularNovelsParse(response: Response): NovelsPage {
        val elems = response.asJsoup().select(".VPSidebarItem.is-link .VPLink")
        val novels = elems.map {
            SNovel.create().apply {
                title = it.text()
                setUrlWithoutDomain(it.absUrl("href"))
            }
        }
        return NovelsPage(novels, false)
    }

    override fun novelDetailsParse(response: Response): SNovel {
        val doc = response.asJsoup()
        return SNovel.create().apply {
            title = doc.selectFirst("meta[property=og:title]")?.attr("content")!!
            thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
            description = doc.selectFirst("meta[property=og:description]")?.attr("content")
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            status = SManga.COMPLETED
//            memo = buildJsonObject {
//                put("date_upload", doc.selectFirst(".VPLastUpdated time")?.attr("datetime"))
//            }
        }
    }

    override suspend fun getChapterList(novel: SNovel): List<SChapter> = listOf(
        SChapter.create().apply {
            name = novel.title
            url = novel.url
//            date_upload = dateFormat.tryParse(novel.memo["date_upload"])
        },
    )

    override suspend fun fetchPageText(page: Page): String {
        val response = client.newCall(GET(baseUrl + page.url, headers)).execute()
        val doc = response.asJsoup()

        val main = doc.selectFirst(".main")!!

        main.prepend(
            """
            <style>
            svg {
                width: 1em;
                height: 1em;
            }
            img.avatar {
                height: 100px;
            }
            </style>
            """.trimIndent(),
        )

        return main.outerHtml()
    }

//  === Unused ================================================================
    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException()
    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException()
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()
    override fun searchNovelsRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = throw UnsupportedOperationException()
    override fun searchNovelsParse(response: Response): NovelsPage = throw UnsupportedOperationException()
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()
    override fun latestUpdatesParse(response: Response): NovelsPage = throw UnsupportedOperationException()
}
