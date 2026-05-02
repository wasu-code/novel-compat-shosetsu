package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import app.shosetsu.lib.Novel
import app.shosetsu.lib.exceptions.InvalidMetaDataException
import app.shosetsu.lib.lua.LuaExtension
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
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

    private fun updateSettings() {
        ext.settingsModel.forEach { s ->
            updateSetting(s)
        }
    }

    private fun updateSetting(s: ShosetsuFilter<*>) {
        val id = s.id.toString()
        val value = when (s) {
            is ShosetsuFilter.Text, is ShosetsuFilter.Password -> preferences.getString(id, "")
            is ShosetsuFilter.Switch, is ShosetsuFilter.Checkbox -> preferences.getBoolean(id, false)
            is ShosetsuFilter.Dropdown, is ShosetsuFilter.RadioGroup, is ShosetsuFilter.TriState -> preferences.getString(id, "0")?.toInt() ?: 0
            is ShosetsuFilter.FList -> {
                s.filters.forEach { updateSetting(it) }
                return
            }
            is ShosetsuFilter.Group<*> -> {
                s.filters.forEach { updateSetting(it) }
                return
            }
            else -> return
        }
        ext.updateSetting(s.id, value)
    }

    /**
     * Retrieve index of desired listing from shared preferences
     *
     * @param key The listing type to fetch. Can be either:
     * - `PRIMARY` - always available
     * - `SECONDARY` - may not exist
     *
     * @return index of desired listing or `0`
     * @throws IllegalArgumentException if wrong key is provided
     */
    private fun getListingIndex(key: String = "PRIMARY"): Int {
        require(key in setOf("PRIMARY", "SECONDARY")) { "Invalid listing key: $key" }
        val desiredListingIndex = preferences.getString("LISTING_$key", "0")?.toInt() ?: 0
        if (desiredListingIndex > ext.listings.size - 1) return 0
        return desiredListingIndex
    }

    /**
     * Obtains listing with novels
     *
     * @param index Index of listing to retrieve novels from
     * @param page
     */
    private fun getListing(index: Int = 0, page: Int): NovelsPage {
        val listing = ext.listings[index]

        val novels = listing.getListing(
            mapOf(
                FID_PAGE to ext.startIndex + page - 1,
            ),
        ).map { it.toSNovel() }

        val hasNextPage = listing.isIncrementing && novels.isNotEmpty()

        return NovelsPage(novels, hasNextPage)
    }

    override fun fetchPopularNovels(page: Int): Observable<NovelsPage> {
        val listingIndex = getListingIndex("PRIMARY")
        val listing = getListing(listingIndex, page)
        return Observable.just(listing)
    }

    override fun popularNovelsParse(response: Response): NovelsPage = throw UnsupportedOperationException("Not used")
    override fun popularNovelsRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun fetchLatestUpdates(page: Int): Observable<NovelsPage> {
        val listingIndex = getListingIndex("SECONDARY")
        val listing = getListing(listingIndex, page)
        return Observable.just(listing)
    }

    override fun latestUpdatesParse(response: Response): NovelsPage = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")

    override fun getFilterList(): FilterList = FilterList(
        buildList {
            add(ListFilter("Apply filters to:", arrayOf("Search", "Primary listing", "Secondary listing"), 1))
            add(Filter.Header("When search query is provided filters will always be applied to search, regardless to the option selected above"))
            add(Filter.Separator())
            // Filters provided by extension
            ext.searchFiltersModel.forEach { filter ->
                when (filter) {
                    is ShosetsuFilter.FList -> addAll(filter.filters.map { it.toFilter() })
                    else -> add(filter.toFilter())
                }
            }
        },
    )

    override fun fetchSearchNovels(page: Int, query: String, filters: FilterList): Observable<NovelsPage> {
        // If query is not provided that is probably filtering, not search, and should still be handled
        if (!ext.hasSearch && query.isNotEmpty()) throw UnsupportedOperationException("Search not supported in this extensions")

        /** Map of FilterID→value that are meant to be sent to Shosetsu search or getListing functions */
        val filterMap = mutableMapOf<Int, Any>(
            FID_QUERY to query,
            FID_PAGE to ext.startIndex + page - 1,
        )

        /** Map of all filters from Shosetsu filter model as flatmap associated by filter name */
        val shosetsuFilterByName = buildList {
            ext.searchFiltersModel.forEach { filter ->
                when (filter) {
                    is ShosetsuFilter.FList -> addAll(filter.filters)
                    is ShosetsuFilter.Group<*> -> addAll(filter.filters)
                    else -> add(filter)
                }
            }
        }.associateBy { it.name }

        /** Takes Tsundoku filter as param and adds its value to `filterMap` */
        fun addFilter(f: Filter<*>) {
            val shosetsuFilter = shosetsuFilterByName[f.name] ?: return
            val value = f.state
            if (value != null) {
                filterMap[shosetsuFilter.id] = value
            }
        }

        filters.forEach { f ->
            if (f is Filter.Group<*>) {
                f.state.forEach { inner ->
                    addFilter(inner as Filter<*>)
                }
            } else {
                addFilter(f)
            }
        }

        // Should filtering use .search or .getListing?
        val filteringTarget = (filters[0] as ListFilter).state
        val (novels, hasNextPage) = when {
            // Searching
            query.isNotEmpty() || filteringTarget == 0 -> {
                val novels = ext.search(filterMap)
                val hasNextPage = ext.isSearchIncrementing && novels.isNotEmpty()
                novels to hasNextPage
            }
            // Filtering a listing
            else -> {
                val listing = ext.listings[filteringTarget - 1]
                val novels = listing.getListing(filterMap)
                val hasNextPage = listing.isIncrementing && novels.isNotEmpty()
                novels to hasNextPage
            }
        }

        return Observable.just(NovelsPage(novels.map { it.toSNovel() }, hasNextPage))
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
            .map { it.toSChapter(lang) }
            .reversed()
        return Observable.just(chapters)
    }

    override fun chapterListParse(response: Response): List<SChapter> = throw UnsupportedOperationException("Not used")

    override fun getChapterUrl(chapter: SChapter): String = ext.expandURL(chapter.url, KEY_CHAPTER_URL)

    override fun fetchNovelDetails(novel: SNovel): Observable<SNovel> {
        val parsedNovel = ext.parseNovel(novel.url, false).toSNovel()
        return Observable.just(parsedNovel)
    }

    override fun novelDetailsParse(response: Response): SNovel = throw UnsupportedOperationException("Not used")

    override fun getNovelUrl(novel: SNovel): String = ext.expandURL(novel.url, KEY_NOVEL_URL)

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val page = Page(0, chapter.url)
        return Observable.just(listOf(page))
    }

    override fun pageListParse(response: Response): List<Page> = throw UnsupportedOperationException("Not used")
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    override suspend fun fetchPageText(page: Page): String {
        val content = String(ext.getPassage(page.url))
        return when (ext.chapterType) {
            Novel.ChapterType.HTML -> content
            Novel.ChapterType.STRING -> """<pre style="white-space: pre-wrap">$content</pre>"""
        }
    }

    fun attachChildren(
        parent: PreferenceCategory,
        s: ShosetsuFilter<*>,
        context: Context,
    ) {
        val children = when (s) {
            is ShosetsuFilter.FList -> s.filters
            is ShosetsuFilter.Group<*> -> s.filters
            else -> emptyList()
        }

        children.forEach { child ->
            val preference = child.toPreference(context)

            parent.addPreference(preference)

            if (preference is PreferenceCategory) {
                attachChildren(preference, child, context)
            } else {
                preference.setOnPreferenceChangeListener { _, _ ->
                    handler.post { updateSettings() }
                    true
                }
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        if (ext.listings.size > 1) {
            ListPreference(screen.context).apply {
                key = "LISTING_PRIMARY"
                title = "Primary listing"
                entries = ext.listings.map { it.name }.toTypedArray()
                entryValues = Array(ext.listings.size) { it.toString() }
                setDefaultValue("0")
                summary = """
                Listing to be used when browsing Popular page
                Selected: %s
                """.trimIndent()
            }.also(screen::addPreference)

            ListPreference(screen.context).apply {
                key = "LISTING_SECONDARY"
                title = "Secondary listing"
                entries = ext.listings.map { it.name }.toTypedArray()
                entryValues = Array(ext.listings.size) { it.toString() }
                setDefaultValue("1")
                summary = """
                    Listing to be used when browsing Latest page
                    Selected: %s
                """.trimIndent()
            }.also(screen::addPreference)
        }

//      === Preferences provided by lua extension =============================

        if (ext.settingsModel.isNotEmpty()) {
            val extensionSettingsCat = PreferenceCategory(screen.context)
                .apply {
                    title = "Extension settings"
                }
                .also(screen::addPreference)

            ext.settingsModel.forEach { s ->

                val preference: Preference = s.toPreference(screen.context)

                extensionSettingsCat.addPreference(preference)

                if (preference is PreferenceCategory) {
                    attachChildren(preference, s, screen.context)
                } else {
                    preference.setOnPreferenceChangeListener { _, _ ->
                        handler.post { updateSettings() }
                        true
                    }
                }
            }
        }

//      === Info about extension ==============================================

        Preference::class.java
            .getConstructor(Context::class.java)
            .newInstance(screen.context)
            .apply {
                title = "Metadata"
                summary = try {
                    val meta = ext.exMetaData
                    """
                        internal ID: ${ext.formatterID}
                        version: ${meta.version} (library version ${meta.libVersion})
                        created by: ${meta.author}
                        depends on: ${meta.dependencies.entries.joinToString { it.key + " v" + it.value }}
                        from repo: ${meta.repo}
                    """.trimIndent()
                } catch (_: InvalidMetaDataException) {
                    "Extension provided invalid metadata"
                } catch (e: Exception) {
                    "Error during loading metadata for this extension: ${e.message}"
                }
                setEnabled(false)
                setIconReflect(android.R.drawable.ic_menu_info_details)
            }
            .also(screen::addPreference)
    }

    companion object {
        // IDs of Filters passed to search or listing functions
        const val FID_QUERY = 0
        const val FID_PAGE = 1

        // IDs of URL types
        const val KEY_NOVEL_URL = 1
        const val KEY_CHAPTER_URL = 2
    }
}
