package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import app.shosetsu.lib.json.RepoExtension
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
            dialogTitle = "Repositories URLs"
            dialogMessage = "One per line"

            setOnPreferenceChangeListener { _, newValue ->
                updateRepoList(screen, newValue as String)
                true
            }
        }.also(screen::addPreference)

        updateRepoList(screen, reposPref.text ?: "")
    }

    private fun updateRepoList(screen: PreferenceScreen, raw: String) {
        val context = screen.context

        val repos = raw.lines()
            .map { it.trim() }
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
                    val extensions = repo.extensions
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
                            extensions.forEach { ext ->
                                category.addPreference(createExtensionPreference(context, repoUrl, ext))
                            }
                        }
                    }

                    libraries.forEach { lib ->
                        PluginManager.downloadLibrary(repoUrl, lib.name, lib.version)
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
        repoUrl: String,
        ext: RepoExtension,
    ): Preference = newPreference(context) {
        title = ext.name
        summary = """
            ${ext.lang} • ${ext.version.toVersionString()} ${"• INSTALLED".takeIf { PluginManager.isInstalled(ext.toIdentity(repoUrl)) } ?: ""}
        """.trimIndent()
        setOnPreferenceClickListener {
            val identity = ext.toIdentity(repoUrl)

            val items = arrayOf(
                "Install/Update",
                "Uninstall",
            )

            AlertDialog.Builder(context)
                .setTitle("Manage Extension")
                .setIcon(android.R.drawable.ic_dialog_dialer)
                .setItems(items) { _, which ->
                    setEnabled(false)

                    launchIO {
                        val success = when (which) {
                            0 -> {
                                val file = PluginManager.downloadExtension(identity)
                                file != null
                            }
                            1 -> {
                                PluginManager.deleteExtension(identity)
                            }
                            else -> false
                        }

                        runOnMain {
                            if (success) {
                                Toast.makeText(context, "Success", Toast.LENGTH_SHORT).show()
                                reloadExtensions()
                            } else {
                                Toast.makeText(context, "Failed", Toast.LENGTH_SHORT).show()
                            }

                            setEnabled(true)
                        }
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
