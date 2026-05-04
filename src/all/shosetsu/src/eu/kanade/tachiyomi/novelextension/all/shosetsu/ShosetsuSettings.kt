package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import java.util.concurrent.CountDownLatch

class ShosetsuSettings :
    Source,
    NovelSource,
    ConfigurableSource {
    override val id: Long = 1774169168
    val lang: String = "all"

    // `!` character to pin it to the top of the list
    override val name: String = "! 书 Shosetsu Settings"

    // display name
    override fun toString(): String = "Settings"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hostContext by lazy { Injekt.get<Application>() }

    private fun launchIO(block: () -> Unit) {
        Thread(block).start()
    }

    private fun runOnMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    /**
     * Prompt host app to reload all extensions.
     * That will make newly installed Shosetsu extensions appear in the host app without app restart.
     */
    fun reloadExtensions() {
        val applicationId = hostContext.packageName // theoretically should be BuildConfig.APPLICATION_ID of host app
        val extensionPackageName = this::class.java.`package`?.name
        Intent("$applicationId.ACTION_EXTENSION_REPLACED").apply {
            data = "package:$extensionPackageName".toUri()
            `package` = hostContext.packageName
            hostContext.sendBroadcast(this)
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // remove prefs added by host app, we don't need them for this dummy source
        screen.removeAll()

        Preference::class.java
            .getConstructor(Context::class.java)
            .newInstance(screen.context)
            .apply {
                // TODO: update this summary when host adds support for keeping js/css in Advanced tab
                summary = "Extensions that rely on injecting scripts/styles may have limited functionality"
                setIcon(android.R.drawable.ic_menu_info_details)
            }
            .also(screen::addPreference)

        val reposPref = EditTextPreference(screen.context).apply {
            key = "REPOS"
            title = "Repositories"
            summary = "Add URLs of repositories providing Shosetsu extensions"
            dialogTitle = "Repositories URLs"
            dialogMessage = "One per line"
        }.also(screen::addPreference)

        val filterCategory = PreferenceCategory(screen.context).apply {
            summary = "Limit the amount of extensions on the list"
        }.also(screen::addPreference)

        val enabledRepos = MultiSelectListPreference(screen.context).apply {
            key = "ENABLED_REPOS"
            title = "Select repositories"
            summary = "Enable/disable repositories to display extensions (and load libraries) from"
            val repos = parseRepos(reposPref.text ?: "")
            entries = repos.map { tryParseRepoName(it) }.toTypedArray()
            entryValues = repos.toTypedArray()
            values = values.intersect(repos)
            setDefaultValue(repos)
        }.also(filterCategory::addPreference)

        val languageFilter = MultiSelectListPreference(screen.context).apply {
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

        reposPref.setOnPreferenceChangeListener { _, newValue ->
            enabledRepos.apply {
                val repos = parseRepos(newValue as String)
                entries = repos.map { tryParseRepoName(it) }.toTypedArray()
                entryValues = repos.toTypedArray()

                // Fix invalid stored values
                val current = values ?: emptySet()

                @Suppress("unchecked_cast")
                val valid = current.intersect(repos)

                if (valid != current) {
                    values = valid
                }
            }
            true
        }

        enabledRepos.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("unchecked_cast")
            updateRepoList(screen, newValue as Set<String>, languageFilter.values)
            true
        }

        languageFilter.setOnPreferenceChangeListener { _, newValue ->
            @Suppress("unchecked_cast")
            updateRepoList(screen, enabledRepos.values, newValue as Set<String>)
            true
        }

        // initial load of extensions lists
        updateRepoList(screen, enabledRepos.values, languageFilter.values)
    }

    /** Parses provided text as list of repo URLs separated by new lines */
    private fun parseRepos(text: String): Set<String> = text
        .split("\n")
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun updateRepoList(screen: PreferenceScreen, repoSet: Set<String> = emptySet(), languageSet: Set<String> = emptySet()) {
        val context = screen.context

        val toRemove = (0 until screen.getPreferenceCount())
            .mapNotNull { screen.getPreference(it) }
            .filter { it.key?.startsWith("repo_") == true }
        toRemove.forEach { screen.removePreference(it) }

        val latch = CountDownLatch(repoSet.size)

        repoSet.forEachIndexed { index, repoUrl ->
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
                    val repo = RepositoryManager.getRepo(repoUrl)
                    val extensions = repo.extensions.map { ShosetsuExtension.fromRemote(it, repoUrl) }
                    val filteredExtensions = extensions.filter { it.lang in languageSet }

                    runOnMain {
                        category.removeAll()
                        if (filteredExtensions.isEmpty()) {
                            category.addPreference(
                                newPreference(context) {
                                    title = if (extensions.isEmpty()) "No extensions found" else "No extensions match selected filters"
                                    setEnabled(false)
                                },
                            )
                        } else {
                            filteredExtensions.sortedWith(
                                compareByDescending<ShosetsuExtension> { it.hasUpdate }
                                    .thenByDescending { it.isInstalled }
                                    .thenBy { it.name },
                            ).forEach { ext ->
                                category.addPreference(createExtensionPreference(context, ext))
                            }
                        }
                    }

                    repo.libraries.forEach { lib ->
                        ExtensionManager.downloadLibrary(repoUrl, lib.name, lib.version)
                    }
                } catch (e: Exception) {
                    Log.e("ShosetsuSettings", "Failed to load $repoUrl", e)
                    runOnMain {
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
                val orphanedExtensions = ExtensionRegistry.orphaned()
                if (orphanedExtensions.isNotEmpty()) {
                    val orphanedCategory = PreferenceCategory(context).apply {
                        key = "repo_unknown"
                        title = "Orphaned extensions"
                        summary = "Enable all repos to determine which are truly obsolete."
                        initialExpandedChildrenCount = 3
                    }.also(screen::addPreference)

                    orphanedExtensions.forEach { ext ->
                        orphanedCategory.addPreference(createExtensionPreference(context, ext))
                    }
                }
            }
        }
    }

    private fun createExtensionPreference(
        context: Context,
        ext: ShosetsuExtension,
    ): Preference = newPreference(context) {
        title = ext.name
        summary = """
            ${ext.lang} • ${ext.getVersionString()}
        """.trimIndent()
        updateExtensionIcon(ext.getState())

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
                        0 -> installExtension(ext)
                        1 -> uninstallExtension(ext)
                    }
                }
                .show()
            true
        }
    }

//  === Etc. ==================================================================

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

    fun Preference.installExtension(ext: ShosetsuExtension) = performExtensionAction(
        action = { ExtensionManager.downloadExtension(ext) != null },
        successState = ExtensionState.Installed,
    )

    fun Preference.uninstallExtension(ext: ShosetsuExtension) = performExtensionAction(
        action = { ExtensionManager.deleteExtension(ext) },
        successState = ExtensionState.Removed,
    )

    /**
     * Executes an extension-related action and updates the UI state accordingly.
     *
     * @param action A function performing the extension operation.
     *               Should return `true` on success, `false` otherwise.
     * @param successState The [ExtensionState] to apply when the action succeeds.
     *
     * On failure, [ExtensionState.OperationFailed] is applied automatically.
     */
    private fun Preference.performExtensionAction(
        action: () -> Boolean,
        successState: ExtensionState,
    ) {
        launchIO {
            val success = try {
                action()
            } catch (_: Exception) {
                false
            }

            runOnMain {
                if (success) {
                    updateExtensionIcon(successState)
                    reloadExtensions()
                } else {
                    updateExtensionIcon(ExtensionState.OperationFailed)
                }
                setEnabled(true)
            }
        }
    }

//  === Preference Helpers ====================================================

    private fun newPreference(context: Context, block: Preference.() -> Unit): Preference = Preference::class.java
        .getConstructor(Context::class.java)
        .newInstance(context)
        .apply(block)

    private fun PreferenceScreen.getPreference(index: Int): Preference? = try {
        PreferenceScreen::class.java
            .getMethod("getPreference", Int::class.javaPrimitiveType)
            .invoke(this, index) as? Preference
    } catch (_: Exception) {
        null
    }

    private fun PreferenceScreen.getPreferenceCount(): Int = try {
        PreferenceScreen::class.java
            .getMethod("getPreferenceCount")
            .invoke(this) as Int
    } catch (_: Exception) {
        0
    }

    private fun PreferenceScreen.removePreference(pref: Preference) {
        try {
            PreferenceScreen::class.java
                .getMethod("removePreference", Preference::class.java)
                .invoke(this, pref)
        } catch (_: Exception) {
        }
    }

    private fun PreferenceScreen.removeAll() {
        try {
            PreferenceScreen::class.java
                .getMethod("removeAll")
                .invoke(this)
        } catch (_: Exception) {}
    }

//  === Unused ================================================================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = throw UnsupportedOperationException("Not used")
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = throw UnsupportedOperationException("Not used")
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = throw UnsupportedOperationException("Not used")
    override suspend fun fetchPageText(page: Page): String = throw UnsupportedOperationException("Not used")
}
