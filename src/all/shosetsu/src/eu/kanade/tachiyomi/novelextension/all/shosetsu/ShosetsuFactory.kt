package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.app.Application
import android.util.Log
import android.webkit.WebSettings
import app.shosetsu.lib.ShosetsuSharedLib
import app.shosetsu.lib.lua.LuaExtension
import app.shosetsu.lib.lua.ShosetsuLuaLib
import app.shosetsu.lib.lua.shosetsuGlobals
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Suppress("UNUSED")
class ShosetsuFactory : SourceFactory {
    private val hostContext by lazy { Injekt.get<Application>() }

    init {
        ShosetsuSharedLib.httpClient = Injekt.get<NetworkHelper>().client

        ShosetsuSharedLib.logger = { extensionName, log ->
            Log.d("Shosetsu (ext)", "[$extensionName] $log")
        }

        ShosetsuSharedLib.shosetsuHeaders = arrayOf(
            // runCatching to prevents crash in CI
            "User-Agent" to runCatching {
                WebSettings.getDefaultUserAgent(hostContext)
            }.getOrDefault(""),
        )

        ShosetsuLuaLib.libLoader = libLoader@{ name ->
            Log.i("LuaLibLoader", "Loading ($name)")
            try {
                val result = ExtensionManager.getLibraryFile(name)
                val l =
                    shosetsuGlobals().load(result.readText(), "lib($name)")
                l.call()
            } catch (e: Throwable) {
                Log.e("Shosetsu", "Failed to load library $name", e)
                null
            }
        }
    }

    override fun createSources(): List<Source> {
        ExtensionManager.init(hostContext.filesDir)

        val extensions = withExtensionClassLoader(javaClass.classLoader!!) {
            ExtensionManager.getInstalledExtensionsFiles()
                .map { file ->
                    val ext = ShosetsuExtension.fromFile(file)
                    ext.loadLuaExtension() to ext.lang
                }
//                .plus(LuaExtension(luaExtTextContent, "DebugExt") to "all")
        }

        return extensions
            .mapNotNull(::safeCreateSource)
            .plus(
                // runCatching to prevents crash in CI
                runCatching {
                    ShosetsuSettings()
                }.getOrNull(),
            )
            .filterNotNull()
    }

    private fun safeCreateSource(pair: Pair<LuaExtension, String>): Source? {
        val (ext, lang) = pair
        return try {
            ShosetsuExtensionAdapter(ext, lang)
        } catch (e: Exception) {
            Log.e("Shosetsu", "Loading extension failed", e)
            null
        }
    }
}

// TODO debug extensions that are loaded from storage or direct text input
// TODO URLActivity that sends link to search where it is handled and added as novel to listing if id match formattedID of extension processing it
//  share.shosetsu.app https://shosetsuorg.gitlab.io/kotlin-lib/shosetsu-kotlin-lib/app.shosetsu.lib.share/-novel-link/index.html
