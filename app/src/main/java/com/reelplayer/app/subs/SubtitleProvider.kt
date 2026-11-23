package com.reelplayer.app.subs

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** What we know about the video when searching. */
data class SearchQuery(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** Lower-case 2-letter codes, e.g. ["en", "es"]. */
    val languages: List<String>,
    /** OpenSubtitles hash of the file, if it could be computed. */
    val movieHash: String? = null,
    val fileSize: Long? = null,
    val fileName: String? = null,
)

data class SubtitleResult(
    val provider: SubtitleProvider,
    /** Provider-specific identifier used to download. */
    val id: String,
    val release: String,
    val language: String,
    val downloads: Int = 0,
    /** True when the provider matched the exact file by hash: almost always in sync. */
    val hashMatch: Boolean = false,
    val hearingImpaired: Boolean = false,
    val uploader: String? = null,
)

interface SubtitleProvider {
    val name: String
    fun isConfigured(): Boolean
    suspend fun search(q: SearchQuery): List<SubtitleResult>
    /** Returns the raw downloaded bytes (an .srt, or a .zip / .gz containing one). */
    suspend fun download(r: SubtitleResult): ByteArray
}

object Http {
    const val USER_AGENT = "ReelPlayer v1.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }
}
