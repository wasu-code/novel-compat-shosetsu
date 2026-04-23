package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.CheckBoxPreference
import androidx.preference.DropDownPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import app.shosetsu.lib.Novel
import app.shosetsu.lib.exceptions.InvalidMetaDataException
import app.shosetsu.lib.lua.LuaExtension
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.utils.getPreferences
import kuchihige.source.NovelHttpSource
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import app.shosetsu.lib.Filter as ShosetsuFilter

/**
 * Adapts Shosetsu extension to [Source][eu.kanade.tachiyomi.source.Source]
 *
 * @property ext Shosetsu extension in Lua language
 * @param language language in ISO 639-1 format or "all"
 */
class ShosetsuExtensionAdapter(private val ext: LuaExtension, language: String) :
    NovelHttpSource(),
    ConfigurableSource {

    override val baseUrl: String = ext.baseURL
    override val lang: String = language
    override val supportsLatest: Boolean = ext.listings.size > 1
    override val name: String = ext.name
    override val versionId: Int = ext.formatterID

    val preferences = getPreferences(id)
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    init {
        updateSettings()
    }

    fun updateSettings() {
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

    /**
     * Obtains listing with novels
     *
     * @param index Index of listing to retrieve novels from
     * @param page
     */
    fun getListing(index: Int = 0, page: Int): NovelsPage {
        val listing = ext.listings[index]

        val novels = listing.getListing(
            mapOf(
                FID_PAGE to ext.startIndex + page - 1,
            ),
        ).map { it.toSNovel() }

        return NovelsPage(novels, listing.isIncrementing)
    }

    // TODO choose primary and secondary listing in settings
    override fun fetchPopularNovels(page: Int): Observable<NovelsPage> = Observable.just(getListing(0, page))

    override fun popularNovelsParse(response: Response): NovelsPage = throw UnsupportedOperationException("Not used")
    override fun popularNovelsRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun fetchLatestUpdates(page: Int): Observable<NovelsPage> = Observable.just(getListing(1, page))

    override fun latestUpdatesParse(response: Response): NovelsPage = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun getFilterList(): FilterList = FilterList(
        ext.searchFiltersModel.flatMap { filter ->
            when (filter) {
                is ShosetsuFilter.FList -> filter.filters.map { it.toFilter() }
                else -> listOf(filter.toFilter())
            }
        },
    )

    override fun fetchSearchNovels(page: Int, query: String, filters: FilterList): Observable<NovelsPage> {
        // If query is not provided that is probably filtering, not search, and should still be handled
        // Should filtering use .search or .getListing?
        if (!ext.hasSearch && query.isNotEmpty()) throw UnsupportedOperationException("Search not supported in this extensions")

        val filterMap = mutableMapOf<Int, Any>(
            FID_QUERY to query,
            FID_PAGE to page,
        )

        val filterByName = ext.searchFiltersModel
            .flatMap { filter ->
                when (filter) {
                    is ShosetsuFilter.FList -> filter.filters
                    else -> listOf(filter)
                }
            }
            .associateBy { it.name }

        filters.forEach { f ->
            val shosetsuFilter = filterByName[f.name] ?: return@forEach

            when (shosetsuFilter) {
                is ShosetsuFilter.Group<*> -> {
                    val stateList = f.state as? List<*> ?: emptyList<Any?>()

                    shosetsuFilter.filters.forEach { inner ->
                        val match = stateList.firstOrNull { item ->
                            (item as? ShosetsuFilter<*>)?.name == inner.name
                        }

                        val value = (match as? ShosetsuFilter<*>)?.state

                        if (value != null) {
                            filterMap[inner.id] = value
                        }
                    }
                }

                else -> {
                    val value = f.state
                    if (value != null) {
                        filterMap[shosetsuFilter.id] = value
                    }
                }
            }
        }

        val novels = ext.search(filterMap)
            .map { it.toSNovel() }

        return Observable.just(NovelsPage(novels, ext.isSearchIncrementing))
    }

    override fun searchNovelsParse(response: Response): NovelsPage = throw UnsupportedOperationException("Not used")
    override fun searchNovelsRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request = throw UnsupportedOperationException("Not used")

    override fun fetchChapterList(novel: SNovel): Observable<List<SChapter>> {
        val chapters = ext
            .parseNovel(novel.url, true)
            .chapters
            .map { it.toSChapter() }
        return Observable.just(chapters)
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException("Not used")

    override fun fetchNovelDetails(novel: SNovel): Observable<SNovel> {
        val novel = ext.parseNovel(novel.url, false).toSNovel()
        return Observable.just(novel)
    }

    override fun novelDetailsParse(response: Response): SNovel = throw UnsupportedOperationException("Not used")

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
        val extensionSettingsCat = PreferenceCategory(screen.context)
            .apply {
                title = "Extension settings"
                summary = try {
                    val meta = ext.exMetaData
                    """
                        formattedID:    ${ext.formatterID}
                        version         ${meta.version} (library version ${meta.libVersion})
                        created by      ${meta.author}
                        depends on      ${meta.dependencies.entries.joinToString { it.key + " v" + it.value }}
                        source          ${meta.repo}
                    """.trimIndent()
                } catch (_: InvalidMetaDataException) {
                    "Extension provided invalid metadata"
                }
            }
            .also(screen::addPreference)

        ext.settingsModel.forEach { s ->
            val preference: Preference = convertPreference(s, screen.context)
            preference.setOnPreferenceChangeListener { _, _ ->
                handler.post { updateSettings() }
                true
            }
            extensionSettingsCat.addPreference(preference)
        }
    }

    companion object {
        // IDs of Filters passed to search or listing functions
        const val FID_QUERY = 0
        const val FID_PAGE = 1
    }
}
