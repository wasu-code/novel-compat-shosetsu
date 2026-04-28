package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.util.Log
import app.shosetsu.lib.IExtension
import app.shosetsu.lib.Version
import app.shosetsu.lib.json.RepoExtension
import app.shosetsu.lib.lua.LuaExtension
import app.shosetsu.lib.lua.LuaLibrary
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

typealias Hash = String

fun sha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

sealed class ExtensionState {
    /** Extension is available to download */
    data object Available : ExtensionState()

    /** In process of (un)installing the extension */
    data object Processing : ExtensionState()

    /** Extension is installed */
    data object Installed : ExtensionState()

    /** Extension is installed and remote repo offers a higher version */
    data object UpdatePending : ExtensionState()

    /** Extension is installed but do not appear in a repository associated with it */
    data object Orphaned : ExtensionState()

    /** Operation taken on the extension failed */
    data object OperationFailed : ExtensionState()

    /** Extension was just removed */
    data object Removed : ExtensionState()
}

object ExtensionRegistry {
    private val map = ConcurrentHashMap<Pair<String, Hash>, ShosetsuExtension>()

    fun getOrCreate(lang: String, hash: Hash): ShosetsuExtension = map.getOrPut(lang to hash) {
        ShosetsuExtension(lang, hash)
    }

    /**
     * Returns extensions that have no associated repository.
     *
     * This can happen when:
     * - The extension is obsolete and was removed from its repository.
     * - The repository was never loaded (i.e. not enabled in settings since the app started).
     */
    fun orphaned(): Collection<ShosetsuExtension> = map.values.filter {
        it.repoUrl == null
    }

    fun all(): Collection<ShosetsuExtension> = map.values
}

class ShosetsuExtension(val lang: String, val hash: Hash) {
    var repoUrl: String? = null
    var isInstalled: Boolean = false
    var localMeta: IExtension.ExMetaData? = null
    var remoteMeta: RepoExtension? = null

    val name: String
        get() = remoteMeta?.name ?: localMeta?.id?.let { "ID $it" } ?: hash

    val id: Int?
        get() = remoteMeta?.id ?: localMeta?.id

    fun loadLuaExtension(): LuaExtension {
        val file = ExtensionManager.getExtensionFile(lang, hash)
        return LuaExtension(file).also {
            localMeta = it.exMetaData
        }
    }

    val hasUpdate: Boolean
        get() = (remoteMeta?.version ?: return false) >
            (localMeta?.version ?: return false)

    fun getState() = when {
        hasUpdate -> ExtensionState.UpdatePending
        isInstalled && repoUrl == null -> ExtensionState.Orphaned
        isInstalled -> ExtensionState.Installed
        else -> ExtensionState.Available
    }

    fun getVersionString(): String {
        if (hasUpdate) return "${localMeta?.version?.toVersionString()} → ${remoteMeta?.version?.toVersionString()}"
        return localMeta?.version?.toVersionString() ?: remoteMeta?.version?.toVersionString() ?: "?"
    }

    companion object {
        fun fromFile(file: File): ShosetsuExtension {
            val lang = file.parentFile?.name ?: "all"
            val hash = file.nameWithoutExtension

            return ExtensionRegistry.getOrCreate(lang, hash).also {
                it.isInstalled = true
            }
        }

        fun fromRemote(ext: RepoExtension, repoUrl: String): ShosetsuExtension {
            val hash = sha256("$repoUrl|${ext.lang}|${ext.fileName}")

            return ExtensionRegistry.getOrCreate(ext.lang, hash).also {
                it.repoUrl = repoUrl
                it.remoteMeta = ext
            }
        }
    }
}

object ExtensionManager {

    private lateinit var srcDir: File
    private lateinit var libDir: File

    fun init(filesDir: File) {
        srcDir = File(filesDir, "shosetsu/src").also { it.mkdirs() }
        libDir = File(filesDir, "shosetsu/lib").also { it.mkdirs() }
    }

    private fun requireInit() {
        check(::srcDir.isInitialized)
    }

    // FILE RESOLUTION

    fun getExtensionFile(lang: String, hash: Hash): File = File(srcDir, "$lang/$hash.lua")

    // DOWNLOAD EXTENSION

    fun downloadExtension(ext: ShosetsuExtension): File? {
        requireInit()

        val destFile = getExtensionFile(ext.lang, ext.hash)
        destFile.parentFile?.mkdirs()
        val tempFile = File(destFile.absolutePath + ".tmp")

        val url = URL(URL("${ext.repoUrl}/"), "src/${ext.lang}/${ext.remoteMeta!!.fileName}.lua")

        return try {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
            }

            conn.inputStream.use { input ->
                tempFile.outputStream().use(input::copyTo)
            }

            val isConflict = ExtensionRegistry.all()
                .filter { it.hash != ext.hash }
                .any {
                    // ID for host app is created from lang/name/formattedID
                    // we check if there is any extension installed that uses it
                    it.isInstalled && it.lang == ext.lang && it.name == ext.name && it.id == ext.id
                }

            if (isConflict) {
                Log.e("ExtensionManager", "Extension ${ext.name} conflicts with an existing extension (same lang+name+formatterID). Download blocked.")
                tempFile.delete()
                return null
            }

            if (destFile.exists()) destFile.delete()
            check(tempFile.renameTo(destFile)) { "Failed to rename temp file to $destFile" }

            destFile.setReadOnly()

            ext.isInstalled = true

            destFile
        } catch (e: Exception) {
            Log.e("ExtensionManager", "Download failed: $url", e)
            tempFile.delete()
            null
        }
    }

    private fun download(remoteUrl: URL, destFile: File): File? {
        Log.d("Shosetsu", "Downloading remote file: $remoteUrl")
        val tempFile = File(destFile.absolutePath + ".tmp")
        return try {
            destFile.parentFile?.mkdirs()

            val connection = (remoteUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
            }

            try {
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) error("HTTP $code for $remoteUrl")
                connection.inputStream.use { input ->
                    tempFile.outputStream().use(input::copyTo)
                }
            } finally {
                connection.disconnect()
            }

            if (destFile.exists()) destFile.delete()
            check(tempFile.renameTo(destFile)) { "Failed to rename temp file to $destFile" }
            destFile.setReadOnly()
            destFile
        } catch (e: Exception) {
            Log.e("ExtensionManager", "Download failed: $remoteUrl", e)
            tempFile.delete()
            null
        }
    }

    fun downloadLibrary(repoUrl: String, name: String, version: Version): File? {
        requireInit()

        val libFile = getLibraryFile(name)
        if (libFile.exists()) {
            // compare versions
            val currentVersion = LuaLibrary(libFile).libMetaData.version
            if (version >= currentVersion) return libFile
        }

        val remoteUrl = URL(URL(repoUrl.trimEnd('/') + "/"), "lib/$name.lua")
        return download(remoteUrl, getLibraryFile(name))
    }

    // FILE SCAN

    fun getInstalledExtensionsFiles(): List<File> {
        requireInit()

        return srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "lua" }
            .toList()
    }

    fun getLibraryFile(name: String): File {
        requireInit()
        return File(libDir, "$name.lua")
    }

//  === Delete ================================================================

    fun deleteExtension(ext: ShosetsuExtension): Boolean {
        requireInit()

        val file = getExtensionFile(ext.lang, ext.hash)
        val ok = file.exists() && file.delete()

        if (ok) {
            ext.isInstalled = false
        }

        return ok
    }
}
