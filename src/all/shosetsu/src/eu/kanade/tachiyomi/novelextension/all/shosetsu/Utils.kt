package eu.kanade.tachiyomi.novelextension.all.shosetsu

import androidx.preference.Preference
import app.shosetsu.lib.Version
import kotlin.jvm.javaClass

fun Preference.setIconReflect(resId: Int): Preference {
    try {
        // get the context from Preference via reflection
        val contextField = this.javaClass.getDeclaredField("mContext")
        contextField.isAccessible = true
        val context = contextField.get(this)

        val drawable = context.javaClass
            .getMethod("getDrawable", Int::class.javaPrimitiveType)
            .invoke(context, resId)

        val drawableClass = Class.forName("android.graphics.drawable.Drawable")

        this.javaClass
            .getMethod("setIcon", drawableClass)
            .invoke(this, drawable)

        // always reserve space for icon
        this.javaClass
            .getMethod("setIconSpaceReserved", Boolean::class.javaPrimitiveType)
            .invoke(this, true)
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return this
}

fun Version.toVersionString(): String = "$major.$minor.$patch"

/**
 * Executes the given [block] using the extension's class loader as the current thread context
 * class loader.
 *
 * This is required due to some necessary resources being packaged with the extension rather
 * than the host application. By default, the host app's class loader would be used, which can
 * prevent the extension from accessing its own resources.
 *
 * Restores the original class loader after execution.
 *
 * @param T the return type of the [block]
 * @param block the code to execute with the extension class loader
 * @return the result of [block]
 */
inline fun <T> withExtensionClassLoader(classLoader: ClassLoader, block: () -> T): T {
    val thread = Thread.currentThread()
    val original = thread.contextClassLoader

    return try {
        thread.contextClassLoader = classLoader
        block()
    } finally {
        thread.contextClassLoader = original
    }
}
