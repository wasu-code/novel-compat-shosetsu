package eu.kanade.tachiyomi.novelextension.all.ireader

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import rx.Observable
import ireader.core.source.CatalogSource as IReaderCatalogueSource

class IReaderExtensionAdapter(private val ext: IReaderCatalogueSource) : CatalogueSource {
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
