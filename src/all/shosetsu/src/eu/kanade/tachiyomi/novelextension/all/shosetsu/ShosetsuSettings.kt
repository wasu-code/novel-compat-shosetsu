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
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ShosetsuSettings :
    Source,
    ConfigurableSource {
    override val id: Long = 1774169168
    val lang: String = "all"
    override val name: String = "! 书 Shosetsu Settings"
    override fun toString(): String = name

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
        Preference::class.java
            .getConstructor(Context::class.java)
            .newInstance(screen.context)
            .apply {
                // TODO: update this summary when host adds support for keeping js/css in Advanced tab
                summary = "Extensions that rely on injecting scripts/styles into HTML may not work correctly"
                setIconReflect(android.R.drawable.ic_menu_info_details)
            }
            .also(screen::addPreference)

        val reposPref = EditTextPreference(screen.context).apply {
            key = "REPOS"
            title = "Repositories"
            summary = "Add URLs of repositories providing Shosetsu extensions"
            dialogTitle = "Repositories URLs"
            dialogMessage = "One per line"
        }.also(screen::addPreference)

        val enabledRepos = MultiSelectListPreference(screen.context).apply {
            key = "ENABLED_REPOS"
            title = "Select repositories"
            summary = "Enable/disable repositories to display extensions (and load libraries) from"
            val repos = reposPref.text.split("\n").toSet()
            entries = repos.map { tryParseRepoName(it) }.toTypedArray()
            entryValues = repos.toTypedArray()
            values = values.intersect(repos)
            setDefaultValue(repos)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("unchecked_cast")
                updateRepoList(screen, newValue as Set<String>)
                true
            }
        }.also(screen::addPreference)

        updateRepoList(screen, enabledRepos.values ?: emptySet())
    }

    private fun updateRepoList(screen: PreferenceScreen, repoSet: Set<String>) {
        val context = screen.context

        val repos = repoSet.map { it.trim() }
            .filter { it.isNotEmpty() }

        val toRemove = (0 until getPreferenceCount(screen))
            .mapNotNull { getPreference(screen, it) }
            .filter { it.key?.startsWith("repo_") == true }
        toRemove.forEach { removePreference(screen, it) }

        repos.forEachIndexed { index, repoUrl ->
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
                    val extensions = repo.extensions.map { ShosetsuExtension(it, repoUrl) }
                    val libraries = repo.libraries

                    runOnMain {
                        category.removeAll()
                        if (extensions.isEmpty()) {
                            category.addPreference(
                                newPreference(context) {
                                    title = "No extensions found"
                                    setEnabled(false)
                                },
                            )
                        } else {
                            extensions.sortedByDescending { it.isInstalled }.forEach { ext ->
                                category.addPreference(createExtensionPreference(context, ext))
                            }
                        }
                    }

                    libraries.forEach { lib ->
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
                }
            }
        }
    }

    private fun createExtensionPreference(
        context: Context,
        ext: ShosetsuExtension,
    ): Preference = newPreference(context) {
        title = ext.metadata.name
        summary = """
            ${ext.metadata.lang} • ${ext.metadata.version.toVersionString()}
        """.trimIndent()
        updateExtensionIcon(ext.state)

        setOnPreferenceClickListener {
            val identity = ext.identity

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
                        0 -> installExtension(identity)
                        1 -> uninstallExtension(identity)
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
        this.setIconReflect(
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

    fun Preference.installExtension(identity: ExtensionIdentity) = performExtensionAction(
        action = { ExtensionManager.downloadExtension(identity) != null },
        successState = ExtensionState.Installed,
    )

    fun Preference.uninstallExtension(identity: ExtensionIdentity) = performExtensionAction(
        action = { ExtensionManager.deleteExtension(identity) },
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

    private fun getPreference(screen: PreferenceScreen, index: Int): Preference? = try {
        PreferenceScreen::class.java
            .getMethod("getPreference", Int::class.javaPrimitiveType)
            .invoke(screen, index) as? Preference
    } catch (_: Exception) {
        null
    }

    private fun getPreferenceCount(screen: PreferenceScreen): Int = try {
        PreferenceScreen::class.java
            .getMethod("getPreferenceCount")
            .invoke(screen) as Int
    } catch (_: Exception) {
        0
    }

    private fun removePreference(screen: PreferenceScreen, pref: Preference) {
        try {
            PreferenceScreen::class.java
                .getMethod("removePreference", Preference::class.java)
                .invoke(screen, pref)
        } catch (_: Exception) {
        }
    }

//  === Unused ================================================================

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = throw UnsupportedOperationException("Not used")
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = throw UnsupportedOperationException("Not used")
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = throw UnsupportedOperationException("Not used")
}
