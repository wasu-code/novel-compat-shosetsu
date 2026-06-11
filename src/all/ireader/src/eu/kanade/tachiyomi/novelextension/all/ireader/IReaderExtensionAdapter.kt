package eu.kanade.tachiyomi.novelextension.all.ireader

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import ireader.core.source.model.ChapterInfo
import keiyoushi.utils.getPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import ireader.core.source.CatalogSource as IReaderCatalogueSource
import ireader.core.source.HttpSource as IReaderHttpSource

open class CatalogueSourceAdapter(private val ext: IReaderCatalogueSource) :
    CatalogueSource,
    ConfigurableSource,
    NovelSource {
    override val id: Long = ext.id
    override val name: String = ext.name
    override val lang: String = ext.lang
    override val supportsLatest: Boolean = ext.supportsLatest()
    override fun toString(): String = name

    val preferences = getPreferences(id)

    /**
     * Retrieve index of desired listing from shared preferences
     *
     * @param key The listing type to fetch. Can be either:
     * - `PRIMARY` - always available
     * - `SECONDARY` - may not exist
     *
     * @return index of desired listing or `null`
     * @throws IllegalArgumentException if wrong key is provided
     */
    private fun getListingIndex(key: String = "PRIMARY"): Int? {
        val defaultIndex = when (key) {
            "PRIMARY" -> 0
            "SECONDARY" -> 1
            else -> throw IllegalArgumentException("Invalid listing key: $key")
        }

        val desiredListingIndex = preferences
            .getString("LISTING_$key", null)
            ?.toIntOrNull()
            ?: defaultIndex

        return desiredListingIndex.takeIf { it in ext.getListings().indices }
    }

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = runBlocking {
        val listingIndex = getListingIndex("PRIMARY") ?: throw UnsupportedOperationException("No primary listing")
        val listing = ext.getListings()[listingIndex]
        Observable.just(
            ext.getMangaList(listing, page).toMangasPage(),
        )
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = runBlocking {
        val listingIndex = getListingIndex("SECONDARY") ?: throw UnsupportedOperationException("No secondary listing")
        val listing = ext.getListings()[listingIndex]
        Observable.just(ext.getMangaList(listing, page).toMangasPage())
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

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        // TODO: if ext.supportsPaginatedChapters() go through all pages
        val chapters = ext.getChapterList(manga.toMangaInfo(), emptyList())
        return chapters.map { it.toSChapter() }.reversed()
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        val details = ext.getMangaDetails(manga.toMangaInfo(), emptyList())
        return details.toSManga()
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = runBlocking {
        Observable.just(listOf(Page(index = 0, url = chapter.url)))
    }

    override suspend fun fetchPageText(page: Page): String {
        val chapterInfo = ChapterInfo(key = page.url, name = "")

        val pages = ext.getPageList(chapterInfo, emptyList())
        val chapterContent = pages.map { it.toPage() }.joinToString("") { "<p>" + it.url + "</p>" }

        return chapterContent
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val listings = ext.getListings()
        if (listings.size > 1) {
            ListPreference(screen.context).apply {
                key = "LISTING_PRIMARY"
                title = "Primary listing"
                entries = listings.map { it.name }.toTypedArray()
                entryValues = Array(listings.size) { it.toString() }
                setDefaultValue("0")
                summary = """
                Listing to be used when browsing Popular page
                Selected: %s
                """.trimIndent()
            }.also(screen::addPreference)

            ListPreference(screen.context).apply {
                key = "LISTING_SECONDARY"
                title = "Secondary listing"
                entries = listings.map { it.name }.toTypedArray()
                entryValues = Array(listings.size) { it.toString() }
                setDefaultValue("1")
                summary = """
                    Listing to be used when browsing Latest page
                    Selected: %s
                """.trimIndent()
            }.also(screen::addPreference)
        }
    }
}

class HttpSourceAdapter(
    ext: IReaderHttpSource,
) : HttpSource(),
    NovelSource {

    private val c = CatalogueSourceAdapter(ext)

    override val id: Long = c.id
    override val name: String = c.name
    override val lang: String = c.lang
    override val supportsLatest: Boolean = c.supportsLatest

    override val baseUrl: String = ext.baseUrl

    private fun getUrl(url: String): String = if (url.startsWith("http")) {
        url
    } else {
        baseUrl.trimEnd('/') + "/" + url.trimStart('/')
    }

    override fun getMangaUrl(manga: SManga): String = getUrl(manga.url)

    override fun getChapterUrl(chapter: SChapter): String = getUrl(chapter.url)

    override fun fetchPopularManga(page: Int): Observable<MangasPage> = c.fetchPopularManga(page)

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> = c.fetchLatestUpdates(page)

    override fun getFilterList(): FilterList = c.getFilterList()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = c.fetchSearchManga(page, query, filters)

    override suspend fun getMangaDetails(manga: SManga): SManga = c.getMangaDetails(manga)

    override suspend fun getChapterList(manga: SManga): List<SChapter> = c.getChapterList(manga)

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = c.fetchPageList(chapter)

    override suspend fun fetchPageText(page: Page): String = c.fetchPageText(page)

    // Not used
    override fun popularMangaRequest(page: Int): Request = throw Exception("I expected it not to be used")
    override fun popularMangaParse(response: Response): MangasPage = throw Exception("I expected it not to be used")
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw Exception("I expected it not to be used")
    override fun searchMangaParse(response: Response): MangasPage = throw Exception("I expected it not to be used")
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("I expected it not to be used")
    override fun latestUpdatesParse(response: Response): MangasPage = throw Exception("I expected it not to be used")
    override fun mangaDetailsParse(response: Response): SManga = throw Exception("I expected it not to be used")
    override fun chapterListParse(response: Response): List<SChapter> = throw Exception("I expected it not to be used")
    override fun pageListParse(response: Response): List<Page> = throw Exception("I expected it not to be used")
    override fun imageUrlParse(response: Response): String = throw Exception("I expected it not to be used")
}
