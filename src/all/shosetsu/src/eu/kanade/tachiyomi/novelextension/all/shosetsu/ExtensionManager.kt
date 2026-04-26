package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.util.Log
import app.shosetsu.lib.Version
import app.shosetsu.lib.json.RepoExtension
import app.shosetsu.lib.lua.LuaExtension
import app.shosetsu.lib.lua.LuaLibrary
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ShosetsuExtension(
    val metadata: RepoExtension,
    repoUrl: String,
) {
    val identity = metadata.toIdentity(repoUrl)
    private val extensionFile = ExtensionManager.getExtensionFile(identity)
    val isInstalled = extensionFile.exists()
    val localMetadata = if (isInstalled) {
        withExtensionClassLoader(javaClass.classLoader!!) { LuaExtension(extensionFile) }.exMetaData
    } else {
        null
    }
    val state = when {
        localMetadata!=null && metadata.version > localMetadata.version -> ExtensionState.UpdatePending
        isInstalled -> ExtensionState.Installed
        else -> ExtensionState.Available
    }
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

data class ExtensionIdentity(
    val repoUrl: String,
    val lang: String,
    val fileName: String,
) {
    val id: Int get() = listOf(repoUrl, lang, fileName).joinToString("\u0000").hashCode()
}

fun RepoExtension.toIdentity(repoUrl: String) = ExtensionIdentity(
    repoUrl = repoUrl.trimEnd('/'),
    lang = lang,
    fileName = fileName,
)

object ExtensionCache {

}

object ExtensionManager {
    private lateinit var srcDir: File
    private lateinit var libDir: File

    fun init(filesDir: File) {
        srcDir = File(filesDir, "shosetsu/src").also { it.mkdirs() }
        libDir = File(filesDir, "shosetsu/lib").also { it.mkdirs() }
    }

    private fun requireInit() {
        check(::srcDir.isInitialized) {
            "ExtensionManager.init(filesDir) must be called before using ExtensionManager"
        }
    }

//  === Paths =================================================================

    fun getExtensionFile(identity: ExtensionIdentity): File {
        requireInit()
        return File(srcDir, "${identity.lang}/${identity.id}.lua")
    }

    fun getLibraryFile(name: String): File {
        requireInit()
        return File(libDir, "$name.lua")
    }

    fun isInstalled(identity: ExtensionIdentity): Boolean = getExtensionFile(identity).exists()

    fun isLibraryInstalled(name: String): Boolean = getLibraryFile(name).exists()

//  === Download ==============================================================

    fun downloadExtension(identity: ExtensionIdentity): File? {
        requireInit()

        val destFile = getExtensionFile(identity)
        val tempFile = File(destFile.absolutePath + ".tmp")

        val remoteUrl = URL(
            URL(identity.repoUrl + "/"),
            "src/${identity.lang}/${identity.fileName}.lua",
        )

        // download to temp first
        val tempResult = download(remoteUrl, tempFile) ?: return null

        // load the extension to get its name and formatterID
        val incomingExt = try {
            withExtensionClassLoader(javaClass.classLoader!!) {
                LuaExtension(tempResult)
            }
        } catch (e: Exception) {
            Log.e("ExtensionManager", "Failed to load downloaded extension for conflict check", e)
            tempFile.delete()
            return null
        }

        // check against all installed extensions for host app ID conflict
        // TODO keep some index to avoid re-parsing files every time
        val conflict = getInstalledExtensions()
            .filter { it.absolutePath != destFile.absolutePath } // ignore self (reinstall/update case)
            .any { installedFile ->
                try {
                    val installedExt = LuaExtension(installedFile)
                    val installedLang = installedFile.parentFile?.name ?: "all"

                    installedLang == identity.lang &&
                        installedExt.name == incomingExt.name &&
                        installedExt.formatterID == incomingExt.formatterID
                } catch (e: Exception) {
                    Log.w("ExtensionManager", "Failed to load installed extension ${installedFile.name} for conflict check", e)
                    false
                }
            }

        if (conflict) {
            Log.e("ExtensionManager", "Extension ${identity.fileName} conflicts with an existing extension (same lang+name+formatterID). Download blocked.")
            tempFile.delete()
            return null
        }

        // no conflict → commit
        if (destFile.exists()) destFile.delete()
        return if (tempFile.renameTo(destFile)) {
            destFile.setReadOnly()
            destFile
        } else {
            Log.e("ExtensionManager", "Failed to rename temp to dest for ${identity.fileName}")
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

//  === Delete ================================================================

    fun deleteExtension(identity: ExtensionIdentity): Boolean = getExtensionFile(identity).let { it.exists() && it.delete() }

    fun deleteLibrary(name: String): Boolean = getLibraryFile(name).let { it.exists() && it.delete() }

    fun deleteAllExtensions(): Boolean {
        requireInit()
        return srcDir.deleteRecursively().also { srcDir.mkdirs() }
    }

    fun deleteAllLibraries(): Boolean {
        requireInit()
        return libDir.deleteRecursively().also { libDir.mkdirs() }
    }

    fun deleteAll(): Boolean {
        requireInit()
        return deleteAllExtensions() && deleteAllLibraries()
    }

//  === Installed =============================================================

    fun getInstalledExtensions(): List<File> {
        requireInit()
        return srcDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "lua" }
            .toList()
    }

    fun getInstalledLibraries(): List<File> {
        requireInit()
        return libDir
            .listFiles { it.isFile && it.extension == "lua" }
            ?.toList()
            ?: emptyList()
    }
}
