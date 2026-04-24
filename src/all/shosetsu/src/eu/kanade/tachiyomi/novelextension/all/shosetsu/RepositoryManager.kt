package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.util.Log
import app.shosetsu.lib.json.RepoExtension
import app.shosetsu.lib.json.RepoIndex
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import java.net.URL

object RepositoryManager {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun getRepoPlugins(repoUrl: String): List<RepoExtension> {
        Log.d("Shosetsu", "Parsing remote repository: $repoUrl")
        val response = URL(URL(repoUrl), "index.json").readText()
        return response.parseAs<RepoIndex>(json).extensions
    }
}
