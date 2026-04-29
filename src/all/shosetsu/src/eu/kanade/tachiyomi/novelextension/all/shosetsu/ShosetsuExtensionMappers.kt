package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.os.Build
import app.shosetsu.lib.Novel
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.SChapter
import novelsurcery.utils.setAltTitles
import app.shosetsu.lib.Filter as ShosetsuFilter
import eu.kanade.tachiyomi.source.model.SManga as SNovel

fun Novel.Status.toSNovelStatus(): Int = when (this) {
    Novel.Status.COMPLETED -> SNovel.COMPLETED
    Novel.Status.PUBLISHING -> SNovel.ONGOING
    Novel.Status.PAUSED -> SNovel.ON_HIATUS
    Novel.Status.UNKNOWN -> SNovel.UNKNOWN
}

fun Novel.Info.toSNovel(): SNovel = SNovel.create().apply {
    url = this@toSNovel.link
    title = this@toSNovel.title
    author = this@toSNovel.authors.joinToString()
    artist = this@toSNovel.artists.joinToString()
    genre = this@toSNovel.genres.joinToString().takeIf { it.isNotEmpty() } ?: tags.joinToString()
    description = this@toSNovel.description
    status = this@toSNovel.status.toSNovelStatus()
    thumbnail_url = imageURL
    setAltTitles(this@toSNovel.alternativeTitles.asList())
}

fun Novel.Chapter.toSChapter(lang: String = "all"): SChapter = SChapter.create().apply {
    url = link
    name = title
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        parseSmartDateToMillis(release, lang)?.let { date_upload = it }
    }
    chapter_number = order.toFloat()
}

class TextFilter(name: String, state: String) : Filter.Text(name, state)
class BooleanFilter(name: String, state: Boolean) : Filter.CheckBox(name, state)
class TriStateFilter(name: String, state: Int) : Filter.TriState(name, state)
class ListFilter(name: String, values: Array<String>, state: Int) : Filter.Select<String>(name, values, state)
class GroupFilter<T>(name: String, state: List<T>) : Filter.Group<T>(name, state)

fun ShosetsuFilter<*>.toFilter(): Filter<*> = when (this) {
    is ShosetsuFilter.Separator -> Filter.Separator()

    is ShosetsuFilter.Header -> Filter.Header(name)

    is ShosetsuFilter.Text,
    is ShosetsuFilter.Password,
    -> TextFilter(name, state)

    is ShosetsuFilter.Switch,
    is ShosetsuFilter.Checkbox,
    -> BooleanFilter(name, state)

    is ShosetsuFilter.TriState -> TriStateFilter(name, state)

    is ShosetsuFilter.RadioGroup -> ListFilter(name, choices.toTypedArray(), state)
    is ShosetsuFilter.Dropdown -> ListFilter(name, choices.toTypedArray(), state)

    is ShosetsuFilter.Group<*> -> { // TODO check this
        val listState = state
            .toSortedMap() // ensures index order
            .values
            .toList()
        GroupFilter(name, listState)
    }

    else -> error("Unknown filter type: $this")
}
