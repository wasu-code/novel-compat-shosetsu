package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import app.shosetsu.lib.Version
import app.shosetsu.lib.json.RepoExtension
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import rx.Observable

fun Version.toVersionString(): String = "$major.$minor.$patch"

class ShosetsuSettings :
    Source,
    ConfigurableSource {
    override val id: Long = 1774169168
    val lang: String = "all"
    override val name: String = "! 书 Shosetsu Settings"
    override fun toString(): String = name

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun launchIO(block: () -> Unit) {
        Thread(block).start()
    }

    private fun runOnMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
                title = repoUrl
            }
            screen.addPreference(category)
            category.addPreference(
                newPreference(context) {
                    title = "Loading…"
                    setEnabled(false)
                },
            )

            launchIO {
                try {
                    val plugins = RepositoryManager.getRepoPlugins(repoUrl)

                    runOnMain {
                        category.removeAll()
                        if (plugins.isEmpty()) {
                            category.addPreference(
                                newPreference(context) {
                                    title = "No extensions found"
                                    setEnabled(false)
                                },
                            )
                        } else {
                            plugins.forEach { ext ->
                                category.addPreference(createExtensionPreference(context, repoUrl, ext))
                            }
                        }
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
            ${ext.lang} • ${ext.version.toVersionString()}
        """.trimIndent()
        setOnPreferenceClickListener {
            val identity = ext.toIdentity(repoUrl)
            launchIO {
                PluginManager.downloadExtension(identity)
            }
            true
        }
    }

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

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = throw UnsupportedOperationException("Not used")
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = throw UnsupportedOperationException("Not used")
    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = throw UnsupportedOperationException("Not used")
}
