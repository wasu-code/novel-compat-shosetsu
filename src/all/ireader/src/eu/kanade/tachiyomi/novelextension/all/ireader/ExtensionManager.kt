package eu.kanade.tachiyomi.novelextension.all.ireader

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

object ExtensionRegistry {
    // list of packagename?
}

object ExtensionManager {
    fun installExtension(ext: RepoExtension) {}
    fun uninstallExtension(ext: RepoExtension) {}
}
