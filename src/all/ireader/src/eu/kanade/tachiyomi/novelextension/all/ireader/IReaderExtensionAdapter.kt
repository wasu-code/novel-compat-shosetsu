package eu.kanade.tachiyomi.novelextension.all.ireader

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import ireader.core.source.model.PageUrl
import ireader.core.source.model.Text
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import ireader.core.source.CatalogSource as IReaderCatalogueSource
import ireader.core.source.HttpSource as IReaderHttpSource

open class CatalogueSourceAdapter(private val ext: IReaderCatalogueSource) : CatalogueSource {
    override val id: Long = ext.id
    override val name: String = ext.name
    override val lang: String = ext.lang
    override val supportsLatest: Boolean = ext.supportsLatest()
    override fun toString(): String = name

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = Observable.empty()

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = runBlocking {
        val listing = ext.getListings().first()
        Observable.just(
            ext.getMangaList(listing, page).toMangasPage(),
        )
    }

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> = runBlocking {
        val smth = ext.getMangaList(filters.toFilterList(), page)
        Observable.just(smth.toMangasPage())
    }

    override fun getFilterList(): FilterList = ext.getFilters().toFilterList()

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = runBlocking {
        // TODO: if ext.supportsPaginatedChapters() go through all pages
        val chapters = ext.getChapterList(manga.toMangaInfo(), emptyList())
        Observable.just(chapters.map { it.toSChapter() })
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = runBlocking {
        // TODO: if ext.supportsPaginatedChapters() go through all pages
        val details = ext.getMangaDetails(manga.toMangaInfo(), emptyList())
        Observable.just(details.toSManga())
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = runBlocking {
        val pages = ext.getPageList(chapter.toChapterInfo(), emptyList())
        Observable.just(pages.map { it.toPage() })
    }
}

class HttpSourceAdapter(
    private val ext: IReaderHttpSource,
) : HttpSource(),
    NovelSource {

    private val c = CatalogueSourceAdapter(ext)

    override val id: Long = c.id
    override val name: String = c.name
    override val lang: String = c.lang
    override val supportsLatest: Boolean = c.supportsLatest

    override val baseUrl: String = ext.baseUrl

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = c.fetchPopularManga(page)
    override fun popularMangaRequest(page: Int): Request = throw Exception("I expected it not to be used")
    override fun popularMangaParse(response: Response): MangasPage = throw Exception("I expected it not to be used")

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = c.fetchSearchManga(page, query, filters)
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw Exception("I expected it not to be used")
    override fun searchMangaParse(response: Response): MangasPage = throw Exception("I expected it not to be used")

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = c.fetchLatestUpdates(page)
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("I expected it not to be used")
    override fun latestUpdatesParse(response: Response): MangasPage = throw Exception("I expected it not to be used")

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = c.fetchMangaDetails(manga)
    override fun mangaDetailsParse(response: Response): SManga = throw Exception("I expected it not to be used")

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = c.fetchChapterList(manga)
    override fun chapterListParse(response: Response): List<SChapter> = throw Exception("I expected it not to be used")

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = c.fetchPageList(chapter)
    override fun pageListParse(response: Response): List<Page> = throw Exception("I expected it not to be used")

    override fun imageUrlParse(response: Response): String = throw Exception("I expected it not to be used")

    //TODO: fix NoSuchMethodException
    override suspend fun fetchPageText(page: Page): String = (ext.getPage(PageUrl(page.url)) as Text).text
}
