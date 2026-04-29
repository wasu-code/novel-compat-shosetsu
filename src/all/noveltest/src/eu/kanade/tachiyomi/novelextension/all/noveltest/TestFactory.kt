package eu.kanade.tachiyomi.novelextension.all.noveltest

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory

class TestFactory : SourceFactory {
    override fun createSources(): List<Source> = listOf(
        PreferenceIconTest(),
        PreferenceDialogTest(),
        PreferenceWebViewTest(),
        GeneralTest(),
    )
}
