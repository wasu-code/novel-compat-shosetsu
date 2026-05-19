package eu.kanade.tachiyomi.novelextension.all.ireader

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import ireader.core.source.model.ChapterInfo
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.MangasPageInfo
import ireader.core.source.model.PageUrl
import ireader.core.source.model.Filter as IReaderFilter
import ireader.core.source.model.FilterList as IReaderFilterList
import ireader.core.source.model.Page as IReaderPage

class TextFilter(name: String, state: String) : Filter.Text(name, state)
class BooleanFilter(name: String, state: Boolean) : Filter.CheckBox(name, state)
class TriStateFilter(name: String, state: Int) : Filter.TriState(name, state)
class ListFilter(name: String, values: Array<String>, state: Int) : Filter.Select<String>(name, values, state)
class GroupFilter<T>(name: String, state: List<T>) : Filter.Group<T>(name, state)
class SortFilter(name: String, values: Array<String>, state: Selection) : Filter.Sort(name, values, state)

fun IReaderFilter.Sort.Selection.toSelection(): Filter.Sort.Selection = Filter.Sort.Selection(index, ascending)

fun IReaderFilter<*>.toFilter(): Filter<*> = when (this) {
    is IReaderFilter.Note -> Filter.Header(name)
    is IReaderFilter.Text -> TextFilter(name, value)
    is IReaderFilter.Group -> GroupFilter(name, filters.map { it.toFilter() })
    is IReaderFilter.Select -> ListFilter(name, options, value)
    is IReaderFilter.Sort -> SortFilter(name, options, value?.toSelection() ?: Filter.Sort.Selection(0, true)) // TODO
    is IReaderFilter.Check -> TriStateFilter(
        name,
        value.let {
            when (it) {
                true -> Filter.TriState.STATE_INCLUDE
                false -> Filter.TriState.STATE_EXCLUDE
                null -> Filter.TriState.STATE_IGNORE
            }
        },
    )
}

fun List<IReaderFilter<*>>.toFilterList(): FilterList = FilterList(this.map { it.toFilter() })

fun Filter<*>.toFilter(): IReaderFilter<*> = when (this) {
    is Filter.Header -> IReaderFilter.Note(name)

    is TextFilter -> IReaderFilter.Text(
        name = name,
        value = state,
    )

    is GroupFilter<*> -> IReaderFilter.Group(
        name = name,
        filters = state.map { (it as Filter<*>).toFilter() },
    )

    is ListFilter -> IReaderFilter.Select(
        name = name,
        options = values,
        value = state,
    )

    is SortFilter -> IReaderFilter.Sort(
        name = name,
        options = values,
        value = state?.let {
            IReaderFilter.Sort.Selection(
                index = it.index,
                ascending = it.ascending,
            )
        },
    )

    is TriStateFilter -> IReaderFilter.Check(
        name = name,
        value = when (state) {
            Filter.TriState.STATE_INCLUDE -> true
            Filter.TriState.STATE_EXCLUDE -> false
            Filter.TriState.STATE_IGNORE -> null
            else -> null
        },
    )

    else -> error("Unsupported filter type: ${this::class}")
}

fun FilterList.toFilterList(): IReaderFilterList = this@toFilterList.map { it.toFilter() }

fun MangaInfo.toSManga() = SManga.create().apply {
    url = this@toSManga.key
    title = this@toSManga.title
    artist = this@toSManga.artist
    author = this@toSManga.author
    description = this@toSManga.description
    genre = this@toSManga.genres.joinToString()
    status = this@toSManga.status.toInt()
    thumbnail_url = this@toSManga.cover
//    update_strategy
//    initialized
}

fun SManga.toMangaInfo() = MangaInfo(
    key = this@toMangaInfo.url,
    title = this@toMangaInfo.title,
    artist = this@toMangaInfo.artist ?: "",
    author = this@toMangaInfo.author ?: "",
    description = this@toMangaInfo.description ?: "",
    genres = this@toMangaInfo.genre?.split(",") ?: emptyList(),
    status = this@toMangaInfo.status.toLong(),
    cover = this@toMangaInfo.thumbnail_url ?: "",
)

fun MangasPageInfo.toMangasPage() = MangasPage(mangas.map { it.toSManga() }, hasNextPage)

fun ChapterInfo.toSChapter() = SChapter.create().apply {
    url = this@toSChapter.key
    name = this@toSChapter.name
    date_upload = this@toSChapter.dateUpload
    chapter_number = this@toSChapter.number
    scanlator = this@toSChapter.scanlator
}

fun SChapter.toChapterInfo() = ChapterInfo(
    key = this@toChapterInfo.url,
    name = this@toChapterInfo.name,
    dateUpload = this@toChapterInfo.date_upload,
    number = this@toChapterInfo.chapter_number,
    scanlator = this@toChapterInfo.scanlator ?: "",
)

fun IReaderPage.toPage() = Page(
    index = 0, // this is ignored anyway iirc. I hope
    url = (this@toPage as PageUrl).url,
)
