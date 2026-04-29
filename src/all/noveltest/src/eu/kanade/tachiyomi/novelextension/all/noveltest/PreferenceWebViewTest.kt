package eu.kanade.tachiyomi.novelextension.all.noveltest

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.preference.Preference
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

class PreferenceWebViewTest :
    Source,
    NovelSource,
    ConfigurableSource {
    override val id: Long = 260975526
    override val name: String = "Opening WebView example"
    override fun toString(): String = name

    fun Preference.setIconReflect(resId: Int): Preference {
        try {
            // get the context from Preference via reflection
            val contextField = this.javaClass.getDeclaredField("mContext")
            contextField.isAccessible = true
            val context = contextField.get(this)

            val drawable = context.javaClass
                .getMethod("getDrawable", Int::class.javaPrimitiveType)
                .invoke(context, resId)

            val drawableClass = Class.forName("android.graphics.drawable.Drawable")

            this.javaClass
                .getMethod("setIcon", drawableClass)
                .invoke(this, drawable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return this
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        Preference::class.java
            .getConstructor(Context::class.java)
            .newInstance(screen.context)
            .apply {
                title = "README"
                summary = "Learn how to use this extension"
                setOnPreferenceClickListener {
                    val context = Injekt.get<Application>()
                    val intent = Intent().apply {
                        component = ComponentName(
                            context,
                            "eu.kanade.tachiyomi.ui.webview.WebViewActivity",
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("url_key", "https://example.org")
                        putExtra("source_key", id)
                        putExtra("title_key", "Example title")
                    }
                    context.startActivity(intent)
                    true
                }
            }.setIconReflect(android.R.drawable.ic_menu_myplaces)
            .also(screen::addPreference)

        Preference::class.java
            .getConstructor(Context::class.java)
            .newInstance(screen.context)
            .apply {
                title = "Open external link"
                summary = "Learn how to use this extension"
                setOnPreferenceClickListener {
                    val context = Injekt.get<Application>()
                    val intent = Intent().apply {
                        component = ComponentName(
                            context,
                            "eu.kanade.tachiyomi.ui.webview.WebViewActivity",
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("url_key", "https://example.org")
                        putExtra("source_key", id)
                        putExtra("title_key", "Example title")
                    }
                    context.startActivity(intent)
                    true
                }
            }.setIconReflect(android.R.drawable.ic_menu_set_as)
            .also(screen::addPreference)

        Preference::class.java
            .getConstructor(Context::class.java)
            .newInstance(screen.context)
            .apply {
                title = "HELP"
                summary = "Learn how to use this extension"
                setOnPreferenceClickListener {
                    val context = Injekt.get<Application>()
                    val intent = Intent().apply {
                        component = ComponentName(
                            context,
                            "eu.kanade.tachiyomi.ui.webview.WebViewActivity",
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra("url_key", "data:text/html,<html>Hello World!</html>")
                        putExtra("source_key", id)
                        putExtra("title_key", "Example title")
                    }
                    context.startActivity(intent)
                    true
                }
            }.setIconReflect(android.R.drawable.ic_menu_help)
            .also(screen::addPreference)
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        TODO("Not yet implemented")
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        TODO("Not yet implemented")
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        TODO("Not yet implemented")
    }

    override suspend fun fetchPageText(page: Page): String {
        TODO("Not yet implemented")
    }
}
