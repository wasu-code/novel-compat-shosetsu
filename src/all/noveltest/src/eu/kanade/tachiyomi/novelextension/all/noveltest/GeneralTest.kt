package eu.kanade.tachiyomi.novelextension.all.noveltest

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kuchihige.source.NovelHttpSource
import okhttp3.Request
import okhttp3.Response

class GeneralTest : NovelHttpSource() {
    override val baseUrl = "https://example.com"

    override val lang = "all"
    override val supportsLatest = false
    override val name = "Testowo"

//  === Parse Listings ========================================================

    override fun popularNovelsRequest(page: Int): Request = GET(baseUrl)

    override fun popularNovelsParse(response: Response): NovelsPage = NovelsPage(
        listOf(
            SNovel.create().apply {
                title = "Dummy"
                url = "/"
            },
        ),
        false,
    )

    override fun latestUpdatesRequest(page: Int): Request {
        TODO("Not yet implemented")
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        TODO("Not yet implemented")
    }

//  === Details ===============================================================

    override fun novelDetailsParse(response: Response): SNovel = SManga.create().apply {
        title = "Dummy"
    }

//  === Chapters ==============================================================

    override fun chapterListParse(response: Response): List<SChapter> = listOf(
        SChapter.create().apply {
            name = "Test"
            url = "/"
        },
    )

    override fun pageListParse(response: Response): List<Page> = listOf(
        Page(
            0,
            url = "/",
        ),
    )

    override suspend fun fetchPageText(page: Page): String = "sample_page_text_content"

    override fun imageUrlParse(response: Response): String {
        TODO("Not yet implemented")
    }

//  === Search ================================================================

    override fun searchNovelsRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        TODO("Not yet implemented")
    }

    override fun searchNovelsParse(response: Response): NovelsPage = MangasPage(
        listOf(
            SManga.create().apply {
                title = "Dummy"
                url = "/"
            },
        ),
        false,
    )
}
