package kuchihige.utils

import android.content.Context
import androidx.preference.Preference
import androidx.preference.PreferenceScreen

//  === Preference Helpers ====================================================

fun newPreference(context: Context, block: Preference.() -> Unit): Preference = Preference::class.java
    .getConstructor(Context::class.java)
    .newInstance(context)
    .apply(block)

fun PreferenceScreen.getPreference(index: Int): Preference? = try {
    PreferenceScreen::class.java
        .getMethod("getPreference", Int::class.javaPrimitiveType)
        .invoke(this, index) as? Preference
} catch (_: Exception) {
    null
}

fun PreferenceScreen.getPreferenceCount(): Int = try {
    PreferenceScreen::class.java
        .getMethod("getPreferenceCount")
        .invoke(this) as Int
} catch (_: Exception) {
    0
}

fun PreferenceScreen.removePreference(pref: Preference) {
    try {
        PreferenceScreen::class.java
            .getMethod("removePreference", Preference::class.java)
            .invoke(this, pref)
    } catch (_: Exception) {
    }
}

fun PreferenceScreen.removeAll() {
    try {
        PreferenceScreen::class.java
            .getMethod("removeAll")
            .invoke(this)
    } catch (_: Exception) {}
}
