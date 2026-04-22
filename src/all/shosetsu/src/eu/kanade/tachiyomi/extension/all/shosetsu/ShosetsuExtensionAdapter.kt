package eu.kanade.tachiyomi.extension.all.shosetsu

import android.content.Context
import androidx.preference.CheckBoxPreference
import androidx.preference.DropDownPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import app.shosetsu.lib.Novel
import app.shosetsu.lib.lua.LuaExtension
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferences
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import app.shosetsu.lib.Filter as ShosetsuFilter
import eu.kanade.tachiyomi.source.model.MangasPage as NovelsPage
import eu.kanade.tachiyomi.source.model.SManga as SNovel

class ShosetsuExtensionAdapter(private val ext: LuaExtension) :
    HttpSource(),
    NovelSource,
    ConfigurableSource {

    override val baseUrl: String = ext.baseURL
    override val lang: String = "all" // TODO ISO 639-1 or "all"
    override val supportsLatest: Boolean = false // TODO listing.size>1
    override val name: String = ext.name

    val preferences = getPreferences(id)

    init {
        ext.settingsModel.forEach { s ->
            updateSetting(s)
        }
    }

    fun updateSetting(s: ShosetsuFilter<*>) {
        val id = s.id.toString()
        val value = when (s) {
            is ShosetsuFilter.Text, is ShosetsuFilter.Password -> preferences.getString(id, "")
            is ShosetsuFilter.Switch, is ShosetsuFilter.Checkbox -> preferences.getBoolean(id, false)
            is ShosetsuFilter.Dropdown, is ShosetsuFilter.RadioGroup, is ShosetsuFilter.TriState -> preferences.getString(id, "0")?.toInt() ?: 0
            is ShosetsuFilter.FList -> s.filters.forEach { updateSetting(it) }
            is ShosetsuFilter.Group<*> -> s.filters.forEach { updateSetting(it) }
            else -> return
        }
        ext.updateSetting(s.id, value)
    }

    // TODO choose listing in settings, common function for fetchingListing(int) for popular/latest
    override fun fetchPopularManga(page: Int): Observable<NovelsPage> {
        // TODO: ext.searchFiltersModel
        val listing = ext.listings[0]

        val novels = listing.getListing(
            mapOf(
                FID_PAGE to ext.startIndex + page - 1,
            ),
        ).map { it.toSNovel() }

        return Observable.just(NovelsPage(novels, listing.isIncrementing))
    }

    override fun popularMangaParse(response: Response): NovelsPage = throw UnsupportedOperationException("Not used")
    override fun popularMangaRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun latestUpdatesParse(response: Response): NovelsPage = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<NovelsPage> {
        if (!ext.hasSearch) throw UnsupportedOperationException("Search not supported in this extensions")
        // TODO ext.searchFiltersModel

        val novels = ext.search(
            mapOf(
                FID_QUERY to query,
                FID_PAGE to page,
            ),
        ).map { it.toSNovel() }

        return Observable.just(NovelsPage(novels, ext.isSearchIncrementing))
    }

    override fun searchMangaParse(response: Response): NovelsPage = throw UnsupportedOperationException("Not used")
    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = throw UnsupportedOperationException("Not used")

    override fun fetchChapterList(manga: SNovel): Observable<List<SChapter>> {
        val chapters = ext
            .parseNovel(manga.url, true)
            .chapters
            .map { it.toSChapter() }
        return Observable.just(chapters)
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException("Not used")

    override fun fetchMangaDetails(manga: SNovel): Observable<SNovel> {
        val novel = ext.parseNovel(manga.url, false).toSNovel()
        return Observable.just(novel)
    }

    override fun mangaDetailsParse(response: Response): SNovel = throw UnsupportedOperationException("Not used")

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val page = Page(0, chapter.url)
        return Observable.just(listOf(page))
    }

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException("Not used")
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    override suspend fun fetchPageText(page: Page): String {
        val content = ext.getPassage(page.url).toString()
        return when (ext.chapterType) {
            Novel.ChapterType.HTML -> content
            Novel.ChapterType.STRING -> """<pre style="white-space: pre-wrap">$content</pre>"""
        }
    }

    fun convertPreference(s: ShosetsuFilter<*>, context: Context): Preference = when (s) {
        is ShosetsuFilter.Text, is ShosetsuFilter.Password -> EditTextPreference(context).apply {
            setDefaultValue("")
        }
        is ShosetsuFilter.Switch -> SwitchPreferenceCompat(context).apply {
            setDefaultValue(false)
        }
        is ShosetsuFilter.Checkbox -> CheckBoxPreference(context).apply {
            setDefaultValue(false)
        }
        is ShosetsuFilter.Dropdown -> DropDownPreference(context).apply {
            entries = s.choices.toTypedArray()
            entryValues = Array(s.choices.size) { it.toString() }
            setDefaultValue("0")
        }
        is ShosetsuFilter.RadioGroup -> ListPreference(context).apply {
            entries = s.choices.toTypedArray()
            entryValues = Array(s.choices.size) { it.toString() }
            setDefaultValue("0")
        }
        is ShosetsuFilter.TriState -> DropDownPreference(context).apply {
            entries = arrayOf("Default", "Include", "Exclude")
            entryValues = arrayOf("0", "1", "2")
            setDefaultValue("0")
        }
        is ShosetsuFilter.Header, is ShosetsuFilter.Separator -> PreferenceCategory(context)
        is ShosetsuFilter.FList -> PreferenceCategory(context).apply {
            s.filters.map { convertPreference(it, context) }
                .forEach(::addPreference)
        }
        is ShosetsuFilter.Group<*> -> PreferenceCategory(context).apply {
            s.filters.map { convertPreference(it, context) }
                .forEach(::addPreference)
        }
    }.apply {
        key = s.id.toString()
        title = s.name
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // TODO add info to restart app after changes
        ext.settingsModel.forEach { s ->
            val preference: Preference = convertPreference(s, screen.context)
            screen.addPreference(preference)
        }
    }

    companion object {
        // IDs of Filters passed to search or listing functions
        const val FID_QUERY = 0
        const val FID_PAGE = 1
    }
}
