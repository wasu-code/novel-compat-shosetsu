package eu.kanade.tachiyomi.novelextension.all.ireader

import android.annotation.SuppressLint
import keiyoushi.utils.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URL

object RepoCache {
    private val REPOS = mutableMapOf<String, List<RepoExtension>>()

    fun get(repoUrl: String): List<RepoExtension>? = REPOS[repoUrl]
    fun put(repoUrl: String, extensions: List<RepoExtension>) {
        REPOS[repoUrl] = extensions
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class RepoExtension(
    @SerialName("pkg")
    val packageName: String,
    @SerialName("apk")
    val apkName: String,
    val name: String,
    val id: Long,
    val lang: String,
    val code: Int,
    val version: String,
    val description: String,
    @SerialName("nsfw")
    val isNSFW: Boolean,
    val sourceDir: String,
)

object RepositoryManager {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun getRepo(url: String): List<RepoExtension> {
        RepoCache.get(url)?.let { return it }
        val response = URL(URL(url.trimEnd('/') + "/"), "index.json").readText()
        val repo = response.parseAs<List<RepoExtension>>(json)
        return repo.also { RepoCache.put(url, it) }
    }
}
