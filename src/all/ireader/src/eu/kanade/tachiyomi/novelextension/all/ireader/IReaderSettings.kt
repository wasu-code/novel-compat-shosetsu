package eu.kanade.tachiyomi.novelextension.all.ireader

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.EditText
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.novelextension.all.ireader.ExtensionManager.installExtension
import eu.kanade.tachiyomi.novelextension.all.ireader.ExtensionManager.uninstallExtension
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kuchihige.utils.getPreference
import kuchihige.utils.getPreferenceCount
import kuchihige.utils.launchIO
import kuchihige.utils.newPreference
import kuchihige.utils.removeAll
import kuchihige.utils.removePreference
import kuchihige.utils.runOnMain
import kuchihige.utils.setIcon
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.intersect

class IReaderSettings :
    Source,
    NovelSource,
    ConfigurableSource {
    override val id: Long = 738210197100101114
    val lang: String = "all"

    // `!` character to pin it to the top of the list
    override val name: String = "! IReader Settings"

    // display name
    override fun toString(): String = "Settings"

    private val hostContext by lazy { Injekt.get<Application>() }

    private data class ExtensionListFilters(
        var allRepos: Set<String> = emptySet(),
        var repos: Set<String> = emptySet(),
        var query: String = "",
        var languages: Set<String> = emptySet(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val filters = ExtensionListFilters()

        // remove prefs added by host app, we don't need them for this dummy source
        screen.removeAll()

        val allRepos = EditTextPreference(screen.context).apply {
            key = "REPOS"
            title = "Repositories"
            summary = "Add URLs of repositories providing IReader extensions"
            dialogTitle = "Repositories URLs"
            dialogMessage = "One per line"
        }.also(screen::addPreference)

        val filterCategory = PreferenceCategory(screen.context).apply {
            summary = "Limit the amount of extensions on the list"
        }.also(screen::addPreference)

        val enabledRepos = MultiSelectListPreference(screen.context).apply {
            key = "ENABLED_REPOS"
            title = "Select repositories"
            summary = "Enable/disable repositories to display extensions from"
            val repos = parseRepos(allRepos.text ?: "")
            entries = repos.map { tryParseRepoName(it) }.toTypedArray()
            entryValues = repos.toTypedArray()
            values = values.intersect(repos)
            setDefaultValue(repos)
        }.also(filterCategory::addPreference)

        val enabledLanguages = MultiSelectListPreference(screen.context).apply {
            key = "LANG_FILTER"
            title = "Filter by language"
            summary = "Initialized with your local language and multilingual extensions"

            val languages = setOf("all" to "Multilingual") +
                Locale.getISOLanguages()
                    .map { code ->
                        val locale = Locale(code)
                        code to locale.getDisplayLanguage(locale)
                    }
                    .sortedBy { it.second.lowercase(Locale.getDefault()) }
            entries = languages.map { it.second }.toTypedArray()
            entryValues = languages.map { it.first }.toTypedArray()

            val userLang = Locale.getDefault().language
            setDefaultValue(setOf("all", userLang))
        }.also(filterCategory::addPreference)

        newPreference(screen.context) {
            setIcon(android.R.drawable.ic_menu_search)
            setOnPreferenceClickListener {
                val input = EditText(screen.context).apply {
                    setText(filters.query)
                }

                AlertDialog.Builder(screen.context)
                    .setTitle("Search")
                    .setMessage("Use global search to search across all languages and repos")
                    .setPositiveButton("Search") { _, _ ->
                        val query = input.text.toString()

                        filters.query = query
                        updateExtensionList(screen, filters)
                        summary = query
                    }
                    .setNeutralButton("Global search") { _, _ ->
                        val query = input.text.toString()

                        filters.query = query
                        // search across all repos and ignore language for this search only
                        val tempFilters = filters.copy().apply {
                            this.repos = this.allRepos
                            this.languages = emptySet()
                        }
                        updateExtensionList(screen, tempFilters)
                        summary = query
                    }
                    .setNegativeButton("Clear") { _, _ ->
                        filters.query = ""
                        updateExtensionList(screen, filters)
                        summary = ""
                    }
                    .setView(input)
                    .show()
                false
            }
        }.also(screen::addPreference)

        allRepos.setOnPreferenceChangeListener { _, newValue ->
            val repos = parseRepos(newValue as String)
            filters.allRepos = repos

            enabledRepos.apply {
                entries = repos.map { tryParseRepoName(it) }.toTypedArray()
                entryValues = repos.toTypedArray()

                // Fix invalid stored values
                val current = values ?: emptySet()

                val valid = current.intersect(repos)

                if (valid != current) {
                    values = valid
                    filters.repos = valid
                }
            }
            true
        }

        enabledRepos.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("unchecked_cast")
            filters.repos = newValue as Set<String>
            updateExtensionList(screen, filters)
            true
        }

        enabledLanguages.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("unchecked_cast")
            filters.languages = newValue as Set<String>
            updateExtensionList(screen, filters)
            true
        }

        // initial load of extensions lists
        filters.apply {
            this.allRepos = parseRepos(allRepos.text ?: "")
            this.repos = enabledRepos.values
            this.languages = enabledLanguages.values
        }
        updateExtensionList(screen, filters)
    }

    private val requestGeneration = AtomicInteger(0)
    private fun updateExtensionList(screen: PreferenceScreen, filters: ExtensionListFilters) {
        val context = screen.context
        val current = filters.copy()

        // Every new update invalidates previous requests
        val generation = requestGeneration.incrementAndGet()

        val toRemove = (0 until screen.getPreferenceCount())
            .mapNotNull { screen.getPreference(it) }
            .filter { it.key?.startsWith("repo_") == true }
        toRemove.forEach { screen.removePreference(it) }

        val latch = CountDownLatch(current.repos.size)

        current.repos.forEachIndexed { index, repoUrl ->
            val category = PreferenceCategory(context).apply {
                key = "repo_$index"
                title = tryParseRepoName(repoUrl)
                summary = repoUrl
                initialExpandedChildrenCount = 3
            }.also(screen::addPreference)

            category.addPreference(
                newPreference(context) {
                    title = "Loading…"
                    setEnabled(false)
                },
            )

            launchIO {
                try {
                    val extensions = RepositoryManager.getRepo(repoUrl)
                    extensions.forEach { ExtensionRegistry.knownPackageNames += it.packageName }
                    val filteredExtensions = extensions.filter { extension ->
                        (current.query.isBlank() || extension.name.contains(current.query, ignoreCase = true)) &&
                            (current.languages.isEmpty() || extension.lang in current.languages)
                    }

                    runOnMain {
                        if (generation != requestGeneration.get()) return@runOnMain

                        category.removeAll()
                        if (filteredExtensions.isEmpty()) {
                            category.addPreference(
                                newPreference(context) {
                                    title = if (extensions.isEmpty()) "No extensions found" else "No extensions match selected filters"
                                    setEnabled(false)
                                },
                            )
                        } else {
                            // .sortedWith(
                            //                                compareByDescending<RepoExtension> { it.hasUpdate }
                            //                                    .thenByDescending { it.isInstalled }
                            //                                    .thenBy { it.name },
                            //                            )
                            filteredExtensions.forEach { ext ->
                                category.addPreference(createExtensionPreference(context, ext, repoUrl))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ShosetsuSettings", "Failed to load $repoUrl", e)
                    runOnMain {
                        if (generation != requestGeneration.get()) return@runOnMain

                        category.removeAll()
                        category.addPreference(
                            newPreference(context) {
                                title = "Failed to load"
                                summary = e.message
                                setEnabled(false)
                            },
                        )
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        launchIO {
            latch.await()

            runOnMain {
                if (generation != requestGeneration.get()) return@runOnMain

                val orphanedExtensions = ExtensionRegistry.orphaned
                if (orphanedExtensions.isNotEmpty()) {
                    val orphanedCategory = PreferenceCategory(context).apply {
                        key = "repo_unknown"
                        title = "Orphaned extensions"
                        summary = "Enable all repos to determine which are truly obsolete."
                        initialExpandedChildrenCount = 3
                    }.also(screen::addPreference)

                    orphanedExtensions.forEach { catalog ->
                        val fakeExt = RepoExtension(
                            packageName = catalog.pkgName,
                            apkName = "",
                            name = catalog.name,
                            id = catalog.sourceId,
                            lang = "",
                            code = 0,
                            version = "",
                            description = "",
                            isNSFW = false,
                            sourceDir = "",
                        )
                        orphanedCategory.addPreference(createExtensionPreference(context, fakeExt, ""))
                    }
                }
            }
        }
    }

    private fun createExtensionPreference(
        context: Context,
        ext: RepoExtension,
        repoUrl: String,
    ): Preference = newPreference(context) {
        title = ext.name
        val info = runCatching {
            hostContext.packageManager.getPackageInfo(ext.packageName, 0)
        }.getOrNull()
        val installedVersionCode: Long? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info?.longVersionCode
        } else {
            @Suppress("Deprecation")
            info?.versionCode?.toLong()
        }
        val isInstalled = info != null
        val hasUpdate = isInstalled && (ext.code > (installedVersionCode ?: -1))
        summary = """
            ${ext.lang} •  ${ext.version} ${"🔺 ${info?.versionName}".takeIf { hasUpdate } ?: ""}
            ${ext.description}
        """.trimIndent()
        updateExtensionIcon(
            when {
                repoUrl.isBlank() -> ExtensionState.Orphaned
                hasUpdate -> ExtensionState.UpdatePending
                isInstalled -> ExtensionState.Installed
                else -> ExtensionState.Available
            },
        )

        setOnPreferenceClickListener {
            val items = arrayOf(
                "Install/Update",
                "Uninstall",
            )

            AlertDialog.Builder(context)
                .setTitle("Manage Extension")
                .setIcon(android.R.drawable.ic_dialog_dialer)
                .setItems(items) { _, which ->
                    setEnabled(false)
                    updateExtensionIcon(ExtensionState.Processing)

                    when (which) {
                        0 -> installExtension(ext, repoUrl) {
                            setEnabled(true)
                            updateExtensionIcon(ExtensionState.Installed)
                        }
                        1 -> uninstallExtension(ext) {
                            setEnabled(true)
                            updateExtensionIcon(ExtensionState.Removed)
                        }
                    }
                }
                .show()
            true
        }
    }

    fun Preference.updateExtensionIcon(state: ExtensionState) {
        this.setIcon(
            when (state) {
                is ExtensionState.Processing -> android.R.drawable.stat_notify_sync
                is ExtensionState.Available -> android.R.drawable.presence_invisible
                is ExtensionState.Installed -> android.R.drawable.presence_online
                is ExtensionState.UpdatePending -> android.R.drawable.presence_away
                is ExtensionState.Orphaned -> android.R.drawable.presence_busy
                is ExtensionState.OperationFailed -> android.R.drawable.ic_popup_disk_full
                is ExtensionState.Removed -> android.R.drawable.presence_offline
            },
        )
    }

    /** Parses provided text as list of repo URLs separated by new lines */
    private fun parseRepos(text: String): Set<String> = text
        .split("\n")
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotEmpty() }
        .toSet()

    fun tryParseRepoName(repoUrl: String): String = try {
        val cleaned = repoUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trim('/')

        val path = cleaned.substringAfter('/', "")
        val parts = path.split("/").filter { it.isNotBlank() }

        when {
            // <domain>/<owner>/<repo> (GitHub, Gitlab)
            (
                cleaned.startsWith("raw.githubusercontent.com") ||
                    cleaned.startsWith("gitlab.com")
                ) &&
                parts.size >= 2
            -> "${parts[1].replaceFirstChar { it.uppercase() }} by ${parts[0]}"

            else -> repoUrl
        }
    } catch (_: Exception) {
        repoUrl
    }
//  === Unused ================================================================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = throw UnsupportedOperationException("Not used")
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = throw UnsupportedOperationException("Not used")
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = throw UnsupportedOperationException("Not used")
    override suspend fun fetchPageText(page: Page): String = throw UnsupportedOperationException("Not used")
}
