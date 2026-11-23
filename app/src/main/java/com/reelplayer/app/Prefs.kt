package com.reelplayer.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/** All app settings and small bits of remembered state, in one place. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("reelplayer", Context.MODE_PRIVATE)

    var languages: String
        get() = sp.getString("languages", null)?.takeIf { it.isNotBlank() } ?: "en"
        set(v) = sp.edit().putString("languages", v.trim()).apply()

    var osApiKey: String
        get() = sp.getString("os_api_key", "") ?: ""
        set(v) = sp.edit().putString("os_api_key", v.trim()).remove("os_token").apply()

    var osUser: String
        get() = sp.getString("os_user", "") ?: ""
        set(v) = sp.edit().putString("os_user", v.trim()).remove("os_token").apply()

    var osPass: String
        get() = sp.getString("os_pass", "") ?: ""
        set(v) = sp.edit().putString("os_pass", v).remove("os_token").apply()

    /** JWT from /login plus the API host it told us to use, cached for 12 h. */
    var osToken: String?
        get() {
            val t = sp.getString("os_token", null) ?: return null
            val at = sp.getLong("os_token_at", 0)
            return if (System.currentTimeMillis() - at < 12 * 3600_000L) t else null
        }
        set(v) = sp.edit().putString("os_token", v).putLong("os_token_at", System.currentTimeMillis()).apply()

    var osBaseUrl: String?
        get() = sp.getString("os_base_url", null)
        set(v) = sp.edit().putString("os_base_url", v).apply()

    var subdlApiKey: String
        get() = sp.getString("subdl_api_key", "") ?: ""
        set(v) = sp.edit().putString("subdl_api_key", v.trim()).apply()

    var hwDecoding: Boolean
        get() = sp.getBoolean("hw_decoding", true)
        set(v) = sp.edit().putBoolean("hw_decoding", v).apply()

    var subEncoding: String
        get() = sp.getString("sub_encoding", "") ?: ""
        set(v) = sp.edit().putString("sub_encoding", v.trim()).apply()

    var subSizePercent: Int
        get() = sp.getInt("sub_size", 100)
        set(v) = sp.edit().putInt("sub_size", v.coerceIn(30, 300)).apply()

    // ---- per-video memory: resume position and the subtitle the user picked ----

    fun getPosition(video: Uri): Long = sp.getLong("pos:$video", 0)
    fun setPosition(video: Uri, ms: Long) = sp.edit().putLong("pos:$video", ms).apply()
    fun clearPosition(video: Uri) = sp.edit().remove("pos:$video").apply()

    fun getSubtitle(video: Uri): String? = sp.getString("sub:$video", null)
    fun setSubtitle(video: Uri, path: String?) =
        sp.edit().apply { if (path == null) remove("sub:$video") else putString("sub:$video", path) }.apply()
}
