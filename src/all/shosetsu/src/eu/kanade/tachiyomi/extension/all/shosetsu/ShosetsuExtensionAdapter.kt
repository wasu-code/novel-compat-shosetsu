package eu.kanade.tachiyomi.extension.all.shosetsu

import androidx.preference.PreferenceScreen
import app.shosetsu.lib.lua.LuaExtension
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import eu.kanade.tachiyomi.source.model.MangasPage as NovelsPage
import eu.kanade.tachiyomi.source.model.SManga as SNovel

class ShosetsuExtensionAdapter(private val ext: LuaExtension) :
    HttpSource(),
    NovelSource,
    ConfigurableSource {
    override val baseUrl: String = ext.baseURL
    override val lang: String = "all" // TODO
    override val supportsLatest: Boolean = false // TODO listing.size>1
    override val name: String = ext.name

    override fun fetchPopularManga(page: Int): Observable<NovelsPage> {
        val listing = ext.listings[0]

        val novels = listing.getListing(
            mapOf(
                FID_PAGE to ext.startIndex + page - 1,
            ),
        ).map { it.toSNovel() }

        return Observable.just(NovelsPage(novels, listing.isIncrementing))
    }

    override fun popularMangaParse(response: Response): NovelsPage = throw UnsupportedOperationException("Not Used")
    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException("Not Used")
    override fun latestUpdatesParse(response: Response): NovelsPage = throw UnsupportedOperationException("Not Used")
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not Used")

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<NovelsPage> = super.fetchSearchManga(page, query, filters)

    override fun searchMangaParse(response: Response): NovelsPage = throw UnsupportedOperationException("Not Used")
    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = throw UnsupportedOperationException("Not Used")

    override fun chapterListParse(response: Response): List<SChapter> {
        TODO()
    }

    override fun imageUrlParse(response: Response): String {
        TODO("Not yet implemented")
    }

    override fun mangaDetailsParse(response: Response): SNovel {
        TODO("Not yet implemented")
    }

    override fun pageListParse(response: Response): List<Page> {
        TODO("Not yet implemented")
    }

    override suspend fun fetchPageText(page: Page): String {
        TODO("Not yet implemented")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        TODO("Not yet implemented")
    }

    companion object {
        // IDs of Filters passed to search or listing functions
        const val FID_QUERY = 0
        const val FID_PAGE = 1
    }
}
