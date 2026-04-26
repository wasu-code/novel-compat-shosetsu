package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.util.Log
import app.shosetsu.lib.json.RepoIndex
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import java.net.URL

object RepoCache {
    private val REPOS = mutableMapOf<String, RepoIndex>()

    fun get(repoUrl: String): RepoIndex? = REPOS[repoUrl]
    fun put(repoUrl: String, index: RepoIndex) {
        REPOS[repoUrl] = index
    }
}

object RepositoryManager {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun getRepo(url: String): RepoIndex {
        Log.d("Shosetsu", "Getting repository: $url")
        RepoCache.get(url)?.let { return it }
        Log.d("Shosetsu", "Fetching remote repository: $url")
        val response = URL(URL(url), "index.json").readText()
        val repo = response.parseAs<RepoIndex>(json)
        return repo.also { RepoCache.put(url, it) }
    }
}
