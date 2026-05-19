package eu.kanade.tachiyomi.novelextension.all.ireader

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dalvik.system.PathClassLoader
import ireader.core.http.HttpClients
import ireader.core.log.Log
import ireader.core.source.Dependencies
import ireader.core.source.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okio.Path
import okio.Path.Companion.toPath
import java.io.File

suspend fun <T> withIOContext(block: suspend CoroutineScope.() -> T) = withContext(Dispatchers.IO, block)

sealed class Catalog {
    abstract val name: String
    abstract val description: String
    abstract val sourceId: Long
}

sealed class CatalogLocal : Catalog() {
    abstract val source: Source?
    override val sourceId get() = source?.id ?: -1L
    abstract val nsfw: Boolean
    abstract val isPinned: Boolean
    open val hasUpdate: Boolean = false
}

sealed class CatalogInstalled : CatalogLocal() {
    abstract val pkgName: String
    abstract val versionName: String
    abstract val iconUrl: String
    abstract val versionCode: Int
    abstract val installDir: Path?

    data class SystemWide(
        override val name: String,
        override val description: String,
        override val source: Source?,
        override val pkgName: String,
        override val versionName: String,
        override val versionCode: Int,
        override val nsfw: Boolean,
        override val isPinned: Boolean = false,
        override val hasUpdate: Boolean = false,
        override val iconUrl: String,
        override val installDir: Path?,
    ) : CatalogInstalled()
}

// //

// Extension to explicitly convert String to okio.Path
private fun String.toOkioPath(): Path = this.toPath()

/**
 * Class that handles the loading of the catalogs installed in the system and the app.
 */
class AndroidCatalogLoader(
    private val context: Context,
    private val httpClients: HttpClients,
//    val simpleStorage: GetSimpleStorage,
) {

    private val pkgManager = context.packageManager

    // Create secure directories for extension loading
    private val secureExtensionsDir = File(context.codeCacheDir, "secure_extensions").apply { mkdirs() }
    private val secureDexCacheDir = File(context.codeCacheDir, "dex-cache").apply { mkdirs() }

    init {
        // Initialize secure directories at startup
        createSecureDirectories()
    }

    /**
     * Creates secure directories for extension loading
     */
    private fun createSecureDirectories() {
        try {
            // Ensure secure directories exist
            secureExtensionsDir.mkdirs()
            secureDexCacheDir.mkdirs()

            // Clean any stale extension files
            secureExtensionsDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".apk")) {
                    try {
                        file.delete()
                    } catch (_: Exception) {
                        // Ignore errors
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore errors
        }
    }

    /**
     * Return a list of all the installed catalogs initialized concurrently.
     */
    @SuppressLint("QueryPermissionsNeeded")
    suspend fun loadAll(): List<CatalogLocal> {
        val systemPkgs =
            pkgManager.getInstalledPackages(PACKAGE_FLAGS).filter(::isPackageAnExtension)

        // Load each catalog concurrently and wait for completion
        val installedCatalogs = withIOContext {
            val system = systemPkgs.map { pkgInfo ->
                async(Dispatchers.Default) {
                    loadSystemCatalog(pkgInfo.packageName, pkgInfo)
                }
            }
            system.awaitAll()
        }.filterNotNull()

        return (installedCatalogs).distinctBy { it.sourceId }.toSet().toList()
    }

    /**
     * Loads a catalog given its package name.
     *
     * @param pkgName The package name of the catalog to load.
     * @param pkgInfo The package info of the catalog.
     */
    private fun loadSystemCatalog(
        pkgName: String,
        pkgInfo: PackageInfo,
        iconFile: File? = null,
    ): CatalogInstalled.SystemWide? {
        val data = validateMetadata(pkgName, pkgInfo) ?: return null

        val loader = PathClassLoader(pkgInfo.applicationInfo!!.sourceDir, null, this::class.java.classLoader)
        val source = loadSource(pkgName, loader, data)

        return CatalogInstalled.SystemWide(
            name = source?.name ?: "Unknown",
            description = data.description,
            source = source,
            pkgName = pkgName,
            versionName = data.versionName,
            versionCode = data.versionCode,
            nsfw = data.nsfw,
            iconUrl = data.icon,
            installDir = iconFile?.parentFile?.absolutePath?.toOkioPath(),
        )
    }

    /**
     * Returns true if the given package is an catalog.
     *
     * @param pkgInfo The package info of the application.
     */
    private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean = pkgInfo.reqFeatures.orEmpty().any { it.name == EXTENSION_FEATURE }

    private fun validateMetadata(pkgName: String, pkgInfo: PackageInfo): ValidatedData? {
        if (!isPackageAnExtension(pkgInfo)) {
            return null
        }

        if (pkgName != pkgInfo.packageName) {
            return null
        }

        @Suppress("DEPRECATION")
        val versionCode = pkgInfo.versionCode
        val versionName = pkgInfo.versionName

        // Validate lib version
        val majorLibVersion = versionName!!.substringBefore('.').toInt()
        if (majorLibVersion !in LIB_VERSION_MIN..LIB_VERSION_MAX) {
            return null
        }

        val appInfo = pkgInfo.applicationInfo

        val metadata = appInfo!!.metaData
        val sourceClassName = metadata.getString(METADATA_SOURCE_CLASS)?.trim() ?: return null

        val description = metadata.getString(METADATA_DESCRIPTION).orEmpty()
        val icon = metadata.getString(METADATA_ICON).orEmpty()

        val classToLoad = if (sourceClassName.startsWith(".")) {
            pkgInfo.packageName + sourceClassName
        } else {
            sourceClassName
        }

        val nsfw = metadata.getInt(METADATA_NSFW, 0) == 1

//        val preferenceSource = Injekt.get<Application>().getSharedPreferences("source_$pkgName", 0x0000)
        val dependencies = Dependencies(httpClients, DummyPreferenceStore())

        return ValidatedData(
            versionCode,
            versionName,
            description,
            icon,
            nsfw,
            classToLoad,
            dependencies,
        )
    }

    private fun loadSource(pkgName: String, loader: ClassLoader, data: ValidatedData): Source? {
        return try {
            val obj = Class.forName(data.classToLoad, false, loader)
                .getConstructor(Dependencies::class.java)
                .newInstance(data.dependencies)

            obj as? Source ?: throw Exception("Unknown source class type! ${obj.javaClass}")
        } catch (e: Throwable) {
            android.util.Log.e("AAA", "Failed to load source for $pkgName", e)
            return null
        }
    }

    private data class ValidatedData(
        val versionCode: Int,
        val versionName: String,
        val description: String,
        val icon: String,
        val nsfw: Boolean,
        val classToLoad: String,
        val dependencies: Dependencies,
    )

    /**
     * Clear the cached DEX/class data for a specific catalog.
     * This clears both the secure APK copy and the compiled DEX cache,
     * ensuring the next load will use the fresh APK file.
     *
     * @param pkgName The package name of the catalog to clear cache for
     */
    fun clearCatalogCache(pkgName: String) {
        try {
            Log.info("AndroidCatalogLoader: Clearing cache for $pkgName")

            // Clear the secure APK copy
            val secureApkFile = File(secureExtensionsDir, "$pkgName.apk")
            if (secureApkFile.exists()) {
                secureApkFile.delete()
                Log.debug { "AndroidCatalogLoader: Deleted secure APK: ${secureApkFile.absolutePath}" }
            }

            // Clear the DEX cache directory for this package
            val dexCacheDir = File(secureDexCacheDir, pkgName)
            if (dexCacheDir.exists() && dexCacheDir.isDirectory) {
                dexCacheDir.deleteRecursively()
                Log.debug { "AndroidCatalogLoader: Deleted DEX cache: ${dexCacheDir.absolutePath}" }
            }

            // Also clear any .dex files that might be directly in the cache dir
            secureDexCacheDir.listFiles()?.filter {
                it.name.startsWith(pkgName) && it.name.endsWith(".dex")
            }?.forEach {
                it.delete()
                Log.debug { "AndroidCatalogLoader: Deleted DEX file: ${it.absolutePath}" }
            }

            Log.info("AndroidCatalogLoader: Cache cleared for $pkgName")
        } catch (e: Exception) {
            Log.error("AndroidCatalogLoader: Failed to clear cache for $pkgName", e)
        }
    }

    private companion object {
        const val EXTENSION_FEATURE = "ireader"
        const val METADATA_SOURCE_CLASS = "source.class"
        const val METADATA_DESCRIPTION = "source.description"
        const val METADATA_NSFW = "source.nsfw"
        const val METADATA_ICON = "source.icon"
        const val LIB_VERSION_MIN = 2
        const val LIB_VERSION_MAX = 2

        const val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or PackageManager.GET_META_DATA
    }
}
