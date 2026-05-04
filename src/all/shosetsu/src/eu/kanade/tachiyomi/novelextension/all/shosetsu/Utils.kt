package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.preference.Preference
import app.shosetsu.lib.Version
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.jvm.javaClass

fun Preference.setIcon(resId: Int): Preference {
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

// === Parsing dates ==========================================================

@RequiresApi(Build.VERSION_CODES.O)
fun parseSmartDateToMillis(input: String, lang: String): Long? = parseSmartDate(input, lang)
    ?.atStartOfDay(ZoneOffset.UTC)
    ?.toInstant()
    ?.toEpochMilli()

@RequiresApi(Build.VERSION_CODES.O)
fun parseSmartDate(input: String, lang: String): LocalDate? {
    val locale = resolveLocale(lang)
    val text = normalize(input)

    // 1. Textual formats
    val textFormatters = listOf(
        DateTimeFormatter.ofPattern("d MMM yyyy", locale),
        DateTimeFormatter.ofPattern("d MMMM yyyy", locale),
        DateTimeFormatter.ofPattern("MMM d yyyy", locale),
        DateTimeFormatter.ofPattern("MMMM d yyyy", locale),
        DateTimeFormatter.ofPattern("MMM d yyyy", locale),
        DateTimeFormatter.ofPattern("MMMM d yyyy", locale),
    )

    for (f in textFormatters) {
        try {
            return LocalDate.parse(text, f)
        } catch (_: Exception) {}
    }

    // 2. ISO / structured formats
    val isoFormatters = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("yyyy-M-d"),
    )

    for (f in isoFormatters) {
        try {
            return LocalDate.parse(text, f)
        } catch (_: Exception) {}
    }

    // 3. Numeric fallback with heuristic
    val parts = Regex("""\d+""").findAll(text).map { it.value.toInt() }.toList()
    if (parts.size != 3) return null

    val (a, b, c) = parts

    return when {
        // yyyy/mm/dd
        a > 31 -> safeDate(a, b, c)
        // dd/mm/yyyy OR mm/dd/yyyy
        c > 31 -> {
            val year = c
            val (day, month) = when {
                a > 12 -> a to b
                b > 12 -> b to a
                else -> {
                    // locale-based fallback
                    if (locale.language == "en") {
                        b to a // US
                    } else {
                        a to b // EU
                    }
                }
            }
            safeDate(year, month, day)
        }

        else -> null
    }
}

fun normalize(input: String): String = input
    .replace(Regex("""(\d+)(st|nd|rd|th)"""), "$1")
    .replace(",", "") // remove commas
    .trim()

@RequiresApi(Build.VERSION_CODES.O)
fun safeDate(year: Int, month: Int, day: Int): LocalDate? = try {
    LocalDate.of(year, month, day)
} catch (_: Exception) {
    null
}

fun resolveLocale(lang: String): Locale = if (lang == "all") Locale.ENGLISH else Locale.forLanguageTag(lang)

// === Fix for okhttp version mismatch ========================================
// causing errors when accessing .function() instead of .property

/**
 * Injects some patches on the beginning of file, after metadata line.
 *
 * Need for those patches probably steams from changes to [okhttp3.OkHttp] library.
 * Kotlin-lib uses `4.12.0` while host app environment provided `5.3.2`.
 */
fun injectLuaPatches(content: String) = content.replaceFirst("\n", "\n${LUA_PATCHES}\n")

private const val LUA_PATCHES = """
local function wrapCallable(obj)
    return setmetatable({}, {
        __index = function(_, k)
            local v = obj[k]
            if type(v) == "function" then
                return function(self, ...)
                    if self == _ then
                        local result = v(obj, ...)
                        if type(result) == "table" or type(result) == "userdata" then
                            return wrapCallable(result)
                        end
                        return result
                    end
                    return v(self, ...)
                end
            end
            return setmetatable({}, {
                __index = function(_, k2) return v[k2] end,
                __call = function() return v end,
                __tostring = function() return tostring(v) end,
            })
        end
    })
end

local function wrapConstructor(fn)
    return function(...)
        return wrapCallable(fn(...))
    end
end

local Request         = wrapConstructor(Request)
local CookieJar       = wrapConstructor(CookieJar)
"""

val LUA_PATCHES_length = LUA_PATCHES.split("\n").size + 1
