package eu.kanade.tachiyomi.novelextension.all.ireader

import android.annotation.SuppressLint
import android.app.Application
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.net.toUri
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
}

object ExtensionManager {
    private val hostContext by lazy { Injekt.get<Application>() }

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

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun installExtension(ext: RepoExtension, repoUrl: String, onInstall: () -> Unit) {
        val apkName = ext.apkName
        val apkUrl = "$repoUrl/apk/$apkName"

//      === Download ============================================================

        val request = DownloadManager.Request(apkUrl.toUri())
            .setTitle("Downloading ${ext.name}")
            .setDescription("Downloading IReader extension ${ext.name}")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
            )
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                apkName,
            )
            .setMimeType("application/vnd.android.package-archive")

        val dm = hostContext.getSystemService(
            Context.DOWNLOAD_SERVICE,
        ) as DownloadManager

        val downloadId = dm.enqueue(request)

//      === Install when download completes =====================================

        val downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(
                    DownloadManager.EXTRA_DOWNLOAD_ID,
                    -1,
                )

                if (id != downloadId) return

                try {
                    installApk(context, dm, downloadId)
                } finally {
                    safeUnregister(this)
                }
            }
        }

        registerReceiverCompat(
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
        )

//      === Cleanup after package install =======================================

        val installReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val packageName = intent.data?.schemeSpecificPart ?: return
                if (packageName != ext.packageName) return

                cleanupApk(ext)
                onInstall()
                safeUnregister(this)
            }
        }

        registerReceiverCompat(
            installReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
        )
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

    private fun installApk(context: Context, dm: DownloadManager, downloadId: Long) {
        val uri = dm.getUriForDownloadedFile(downloadId) ?: return

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    }

    fun cleanupApk(ext: RepoExtension) {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            ext.apkName,
        )

        if (file.exists()) {
            file.delete()
        }
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
