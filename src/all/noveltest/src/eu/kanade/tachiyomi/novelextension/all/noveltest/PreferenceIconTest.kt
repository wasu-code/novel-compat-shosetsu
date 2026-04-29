package eu.kanade.tachiyomi.novelextension.all.noveltest

import android.content.Context
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import rx.Observable

class PreferenceIconTest :
    Source,
    NovelSource,
    ConfigurableSource {
    override val id: Long = 1309723131
    override val name: String = "Preference Icons"
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
        val fields = android.R.drawable::class.java.fields

        for (field in fields) {
            try {
                val name = field.name
                val id = field.getInt(null)

                Preference::class.java
                    .getConstructor(Context::class.java)
                    .newInstance(screen.context)
                    .apply {
                        key = "DUMMY$name"
                        title = name
                    }.setIconReflect(id)
                    .also(screen::addPreference)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
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
