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

/**
 * Injects some patches on the beginning of file, after metadata line.
 *
 * Need for those patches probably steams from changes to [okhttp3.OkHttp] library.
 * Kotlin-lib uses `4.12.0` while host app environment provided `5.3.2`.
 */
fun injectLuaPatches(content: String) = content.replaceFirst("\n", "\n${FIX}\n")

private const val FIX = """
local _Request = Request
local Request = function(req)
    local res = _Request(req)
    return setmetatable({}, {__index = function(_, k)
        if k == "headers" then return function() return res.headers end end
        if k == "body" then return function() return res.body end end
        return res[k]
    end})
end

local function wrapCookie(cookie)
    return setmetatable({}, {__index = function(_, k)
        if k == "name" then return function() return cookie.name end end
        if k == "value" then return function() return cookie.value end end
        return cookie[k]
    end})
end

local _CookieJar = CookieJar
local CookieJar = function()
    local jar = _CookieJar()
    local cookies = nil
    return {
        loadForRequest = function(_, url)
            cookies = jar:loadForRequest(url)
            return {
                size = function() return cookies:size() end,
                get = function(_, i) return wrapCookie(cookies:get(i)) end
            }
        end
    }
end
"""
