package eu.kanade.tachiyomi.novelextension.all.ireader

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

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
    /** Holds a list of extensions installed on the system */
    val installed = mutableListOf<CatalogInstalled>()

    /** Holds a set of packageNames that were seen in remote repositories */
    val knownPackageNames = mutableSetOf<String>()

    /** List of extensions that are installed but not yet associated with any repo  */
    val orphaned
        get() = installed.filter { it.pkgName !in knownPackageNames }

    fun get(packageName: String) = installed.find { it.pkgName == packageName }

    fun remove(packageName: String) = installed.removeIf { it.pkgName == packageName }

    fun add(packageName: String) {
        val pm = Injekt.get<Application>().packageManager

        val packageInfo = runCatching {
            pm.getPackageInfo(packageName, 0)
        }.getOrNull() ?: return

        val appInfo = runCatching {
            pm.getApplicationInfo(packageName, 0)
        }.getOrNull()

        installed.add(
            CatalogInstalled.SystemWide(
                name = appInfo?.loadLabel(pm)?.toString() ?: packageName,
                description = "",
                source = null,
                pkgName = packageName,
                versionName = packageInfo.versionName ?: "",
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toInt()
                } else {
                    @Suppress("Deprecation")
                    packageInfo.versionCode
                },
                nsfw = false,
                iconUrl = "",
                installDir = null,
            ),
        )
    }
}

object ExtensionManager {
    private val hostContext by lazy { Injekt.get<Application>() }
    private val httpClient = OkHttpClient()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val packageName =
                    intent.data?.schemeSpecificPart ?: return

                val action = intent.action

                val shouldReload = when (action) {
                    Intent.ACTION_PACKAGE_REMOVED -> true

                    Intent.ACTION_PACKAGE_ADDED,
                    Intent.ACTION_PACKAGE_REPLACED,
                    -> {
                        runCatching {
                            context.packageManager
                                .getPackageInfo(
                                    packageName,
                                    PackageManager.GET_CONFIGURATIONS,
                                )
                                .reqFeatures
                                .orEmpty()
                                .any {
                                    it.name ==
                                        AndroidCatalogLoader.EXTENSION_FEATURE
                                }
                        }.getOrDefault(false)
                    }

                    else -> false
                }

                if (shouldReload) {
                    reloadExtensions()
                }
            }
        }

        registerReceiverCompat(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
        )
    }

    fun installExtension(
        ext: RepoExtension,
        repoUrl: String,
        onInstall: () -> Unit,
    ) {
        val apkUrl = "$repoUrl/apk/${ext.apkName}"
        val tmpFile = File(hostContext.cacheDir, "extension_${ext.packageName}_${System.currentTimeMillis()}.apk")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                if (pkg != ext.packageName) return

                runCatching { tmpFile.delete() }
                ExtensionRegistry.add(pkg)
                onInstall()

                safeUnregister(this)
            }
        }

        registerReceiverCompat(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(apkUrl)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Download failed: ${response.code}")

                    response.body.byteStream().use { input ->
                        tmpFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    installApk(hostContext, tmpFile)
                }
            } catch (e: Exception) {
                runCatching { tmpFile.delete() }
                safeUnregister(receiver)
                Log.e("ExtensionManager", "failded to isnatll", e)
            }
        }
    }

    private fun registerReceiverCompat(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hostContext.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            hostContext.registerReceiver(receiver, filter)
        }
    }

    private fun safeUnregister(
        receiver: BroadcastReceiver,
    ) {
        runCatching {
            hostContext.unregisterReceiver(receiver)
        }
    }

    private fun installApk(
        context: Context,
        apkFile: File,
    ) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            apkFile,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                uri,
                "application/vnd.android.package-archive",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    }

    /**
     * Prompt host app to reload all extensions.
     * That will make newly installed Shosetsu extensions appear in the host app without app restart.
     */
    private fun reloadExtensions() {
        val applicationId = hostContext.packageName // theoretically should be BuildConfig.APPLICATION_ID of host app
        val extensionPackageName = this::class.java.`package`?.name
        Intent("$applicationId.ACTION_EXTENSION_REPLACED").apply {
            data = "package:$extensionPackageName".toUri()
            `package` = hostContext.packageName
            hostContext.sendBroadcast(this)
        }
    }

    fun uninstallExtension(ext: RepoExtension, onUninstall: () -> Unit) {
        val intent = Intent(Intent.ACTION_DELETE).apply {
            data = "package:${ext.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        hostContext.startActivity(intent)

        val uninstallReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val packageName = intent.data?.schemeSpecificPart ?: return
                if (packageName != ext.packageName) return

                onUninstall()
                ExtensionRegistry.remove(packageName)
                safeUnregister(this)
            }
        }

        registerReceiverCompat(
            uninstallReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
        )
    }
}
