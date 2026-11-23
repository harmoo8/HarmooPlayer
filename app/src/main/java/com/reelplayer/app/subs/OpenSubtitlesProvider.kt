package com.reelplayer.app.subs

import com.reelplayer.app.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * OpenSubtitles.com REST API v1 (https://api.opensubtitles.com/api/v1).
 * Needs a free API key; username/password is optional and raises the daily download quota.
 */
class OpenSubtitlesProvider(private val prefs: Prefs) : SubtitleProvider {
    override val name = "OpenSubtitles"
    override fun isConfigured() = prefs.osApiKey.isNotBlank()

    private val defaultBase = "https://api.opensubtitles.com/api/v1"
    private val json = "application/json".toMediaType()

    private fun base(): String = prefs.osBaseUrl?.let { "https://$it/api/v1" } ?: defaultBase

    private fun Request.Builder.auth(): Request.Builder {
        header("Api-Key", prefs.osApiKey)
        header("Accept", "application/json")
        prefs.osToken?.let { header("Authorization", "Bearer $it") }
        return this
    }

    override suspend fun search(q: SearchQuery): List<SubtitleResult> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, SubtitleResult>()
        val langs = q.languages.joinToString(",")

        // 1) Exact-file match by hash — best sync.
        if (q.movieHash != null) {
            runCatching { query(mapOf("languages" to langs, "moviehash" to q.movieHash)) }
                .getOrNull()?.forEach { out.putIfAbsent(it.id, it) }
        }
        // 2) Title search (plus season/episode for TV).
        if (q.title.isNotBlank()) {
            val p = mutableMapOf("languages" to langs, "query" to q.title.lowercase())
            q.season?.let { p["season_number"] = it.toString() }
            q.episode?.let { p["episode_number"] = it.toString() }
            if (q.season == null) q.year?.let { p["year"] = it.toString() }
            query(p).forEach { out.putIfAbsent(it.id, it) }
        }
        out.values.toList()
    }

    private fun query(params: Map<String, String>): List<SubtitleResult> {
        // The API asks for alphabetically sorted, lower-case parameters (otherwise it redirects).
        val url = "${base()}/subtitles".toHttpUrl().newBuilder().apply {
            params.toSortedMap().forEach { (k, v) -> if (v.isNotBlank()) addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).auth().get().build()
        Http.client.newCall(req).execute().use { resp ->
            val body = resp.checked()
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            val list = ArrayList<SubtitleResult>()
            for (i in 0 until data.length()) {
                val a = data.getJSONObject(i).optJSONObject("attributes") ?: continue
                val files = a.optJSONArray("files") ?: continue
                if (files.length() == 0) continue
                val file = files.getJSONObject(0)
                val fileId = file.optLong("file_id", -1)
                if (fileId < 0) continue
                val release = a.optString("release").ifBlank { file.optString("file_name") }
                    .ifBlank { a.optJSONObject("feature_details")?.optString("title") ?: "Subtitle" }
                list += SubtitleResult(
                    provider = this,
                    id = fileId.toString(),
                    release = release,
                    language = a.optString("language"),
                    downloads = a.optInt("download_count"),
                    hashMatch = a.optBoolean("moviehash_match", false),
                    hearingImpaired = a.optBoolean("hearing_impaired", false),
                    uploader = a.optJSONObject("uploader")?.optString("name")?.takeIf { it.isNotBlank() },
                )
            }
            return list
        }
    }

    private fun loginIfPossible() {
        if (prefs.osToken != null || prefs.osUser.isBlank() || prefs.osPass.isBlank()) return
        val body = JSONObject().put("username", prefs.osUser).put("password", prefs.osPass)
            .toString().toRequestBody(json)
        val req = Request.Builder().url("$defaultBase/login")
            .header("Api-Key", prefs.osApiKey).header("Accept", "application/json")
            .post(body).build()
        Http.client.newCall(req).execute().use { resp ->
            val o = JSONObject(resp.checked())
            prefs.osToken = o.optString("token").takeIf { it.isNotBlank() }
            prefs.osBaseUrl = o.optString("base_url").takeIf { it.isNotBlank() }
        }
    }

    override suspend fun download(r: SubtitleResult): ByteArray = withContext(Dispatchers.IO) {
        runCatching { loginIfPossible() } // a failed login still allows anonymous downloads
        val body = JSONObject().put("file_id", r.id.toLong()).toString().toRequestBody(json)
        val req = Request.Builder().url("${base()}/download").auth().post(body).build()
        val link = Http.client.newCall(req).execute().use { resp ->
            val o = JSONObject(resp.checked())
            o.optString("link").ifBlank { throw IOException(o.optString("message", "No download link")) }
        }
        Http.client.newCall(Request.Builder().url(link).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed (HTTP ${resp.code})")
            resp.body?.bytes() ?: throw IOException("Empty download")
        }
    }

    private fun Response.checked(): String {
        val text = body?.string().orEmpty()
        if (!isSuccessful) {
            val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            throw IOException(
                when (code) {
                    401, 403 -> "OpenSubtitles rejected the API key or login${msg?.let { ": $it" } ?: ""}"
                    406, 429 -> msg ?: "OpenSubtitles download limit reached for today"
                    else -> "OpenSubtitles error $code${msg?.let { ": $it" } ?: ""}"
                }
            )
        }
        return text
    }
}
