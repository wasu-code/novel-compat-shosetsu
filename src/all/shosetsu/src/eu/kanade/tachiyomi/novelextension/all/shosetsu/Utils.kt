package eu.kanade.tachiyomi.novelextension.all.shosetsu

import androidx.preference.Preference
import app.shosetsu.lib.Version

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
