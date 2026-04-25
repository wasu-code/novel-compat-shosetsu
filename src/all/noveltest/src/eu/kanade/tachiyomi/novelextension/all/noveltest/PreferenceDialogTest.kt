package eu.kanade.tachiyomi.novelextension.all.noveltest

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import rx.Observable

class PreferenceDialogTest :
    Source,
    NovelSource,
    ConfigurableSource {
    override val id: Long = 1188813951
    override val name: String = "Preference Dialogs"
    override fun toString(): String = name // necessary when extending Source directly

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        Preference::class.java
            .getConstructor(Context::class.java)
            .newInstance(screen.context)
            .apply {
                key = "DUMMY$name"
                title = name
                setOnPreferenceClickListener {
                    showMessage(screen.context)
                    true
                }
            }
            .also(screen::addPreference)

        Preference::class.java
            .getConstructor(Context::class.java)
            .newInstance(screen.context)
            .apply {
                key = "DUMMY$name"
                title = name
                setOnPreferenceClickListener {
//                    DatePickerDialog(
//                        screen.context,
//                        { _, year, month, day ->
//                            // handle date
//                        },
//                        2026,
//                        3,
//                        10,
//                    ).show()
//
//                    TimePickerDialog(
//                        screen.context,
//                        { _, hour, minute ->
//                            // handle time
//                        },
//                        14,
//                        30,
//                        true,
//                    ).show()

                    val items = arrayOf("Install", "Uninstall", "Check for update")

                    AlertDialog.Builder(screen.context)
                        .setTitle("Manage Plugin")
                        .setIcon(android.R.drawable.ic_dialog_dialer)
                        .setItems(items) { _, which ->
                            Toast.makeText(screen.context, "Selected: ${items[which]}", Toast.LENGTH_SHORT).show()
                        }
                        .show()

                    true
                }
            }
            .also(screen::addPreference)
    }

    fun showMessage(context: Context) {
        val input = EditText(context)
        val progress = ProgressBar(context)

        AlertDialog.Builder(context)
            .setTitle("Hello")
            .setMessage("This replaces fragment UI")
            .setPositiveButton("OK", null)
            .setNeutralButton("Action", null)
            .setNegativeButton("Cancel", null)
            .setView(input)
//            .setView(progress)
//            .setCancelable(false)
            .show()
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
