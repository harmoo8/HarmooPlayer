package com.reelplayer.app.subs

import com.reelplayer.app.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** SubDL.com API (https://api.subdl.com/api/v1/subtitles). Needs a free API key. Downloads are .zip files. */
class SubDLProvider(private val prefs: Prefs) : SubtitleProvider {
    override val name = "SubDL"
    override fun isConfigured() = prefs.subdlApiKey.isNotBlank()

    override suspend fun search(q: SearchQuery): List<SubtitleResult> = withContext(Dispatchers.IO) {
        if (q.title.isBlank()) return@withContext emptyList()
        val url = "https://api.subdl.com/api/v1/subtitles".toHttpUrl().newBuilder().apply {
            addQueryParameter("api_key", prefs.subdlApiKey)
            addQueryParameter("film_name", q.title)
            addQueryParameter("languages", q.languages.joinToString(",") { it.uppercase() })
            addQueryParameter("subs_per_page", "30")
            if (q.season != null) {
                addQueryParameter("type", "tv")
                addQueryParameter("season_number", q.season.toString())
                q.episode?.let { addQueryParameter("episode_number", it.toString()) }
            } else {
                q.year?.let { addQueryParameter("year", it.toString()) }
            }
        }.build()

        Http.client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
            .execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val o = runCatching { JSONObject(text) }.getOrElse {
                    throw IOException("SubDL error ${resp.code}")
                }
                if (!o.optBoolean("status", resp.isSuccessful)) {
                    val err = o.optString("error")
                    // "not found" is just an empty result, not an error.
                    if (err.contains("not found", ignoreCase = true) || err.contains("can't find", ignoreCase = true))
                        return@withContext emptyList()
                    throw IOException("SubDL: ${err.ifBlank { "error ${resp.code}" }}")
                }
                val subs = o.optJSONArray("subtitles") ?: return@withContext emptyList()
                (0 until subs.length()).mapNotNull { i ->
                    val s = subs.getJSONObject(i)
                    val path = s.optString("url").ifBlank { return@mapNotNull null }
                    SubtitleResult(
                        provider = this@SubDLProvider,
                        id = path,
                        release = s.optString("release_name").ifBlank { s.optString("name", "Subtitle") },
                        language = s.optString("lang").ifBlank { s.optString("language") },
                        hearingImpaired = s.optBoolean("hi", false),
                        uploader = s.optString("author").takeIf { it.isNotBlank() },
                    )
                }
            }
    }

    override suspend fun download(r: SubtitleResult): ByteArray = withContext(Dispatchers.IO) {
        val url = if (r.id.startsWith("http")) r.id else "https://dl.subdl.com" + (if (r.id.startsWith("/")) "" else "/") + r.id
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("SubDL download failed (HTTP ${resp.code})")
            resp.body?.bytes() ?: throw IOException("Empty download")
        }
    }
}
