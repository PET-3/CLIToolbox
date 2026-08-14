package com.example.clitoolbox.ui.settings

import android.content.Context
import com.example.clitoolbox.ui.theme.AppThemeMode

/** Small SharedPreferences-backed store for theme/language, no extra deps needed. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("cli_toolbox_settings", Context.MODE_PRIVATE)

    var themeMode: AppThemeMode
        get() = runCatching { AppThemeMode.valueOf(prefs.getString(KEY_THEME, AppThemeMode.SYSTEM.name)!!) }
            .getOrDefault(AppThemeMode.SYSTEM)
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /** "system", "en", or "zh-rCN". */
    var languageTag: String
        get() = prefs.getString(KEY_LANGUAGE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    companion object {
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LANGUAGE = "language_tag"
    }
}
