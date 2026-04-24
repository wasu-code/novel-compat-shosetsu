package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.util.Log
import app.shosetsu.lib.json.RepoIndex
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import java.net.URL

object RepositoryManager {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun getRepo(url: String): RepoIndex {
        Log.d("Shosetsu", "Parsing remote repository: $url")
        val response = URL(URL(url), "index.json").readText()
        return response.parseAs<RepoIndex>(json)
    }
}
