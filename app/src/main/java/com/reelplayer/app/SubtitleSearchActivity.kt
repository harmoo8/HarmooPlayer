package com.reelplayer.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.reelplayer.app.subs.NameParser
import com.reelplayer.app.subs.OpenSubtitlesProvider
import com.reelplayer.app.subs.SearchQuery
import com.reelplayer.app.subs.SubDLProvider
import com.reelplayer.app.subs.SubtitleFiles
import com.reelplayer.app.subs.SubtitleProvider
import com.reelplayer.app.subs.SubtitleResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat

class SubtitleSearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_HASH = "hash"
        const val EXTRA_SIZE = "size"
        const val EXTRA_RESULT_PATH = "result_path"
    }

    private lateinit var prefs: Prefs
    private lateinit var providers: List<SubtitleProvider>
    private lateinit var queryInput: EditText
    private lateinit var seasonInput: EditText
    private lateinit var episodeInput: EditText
    private lateinit var langInput: EditText
    private lateinit var status: TextView
    private lateinit var progress: View
    private lateinit var searchButton: MaterialButton
    private val adapter = ResultAdapter { download(it) }

    private var fileName = ""
    private var movieHash: String? = null
    private var fileSize: Long = -1
    private lateinit var parsed: NameParser.Parsed
    private var job: Job? = null
    private var autoSearched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subtitle_search)
        prefs = Prefs(this)
        providers = listOf(OpenSubtitlesProvider(prefs), SubDLProvider(prefs))

        fileName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()
        movieHash = intent.getStringExtra(EXTRA_HASH)
        fileSize = intent.getLongExtra(EXTRA_SIZE, -1)
        parsed = runCatching { NameParser.parse(fileName) }
            .getOrElse { NameParser.Parsed(fileName.substringBeforeLast('.'), null, null, null) }

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        findViewById<TextView>(R.id.fileName).text = fileName
        queryInput = findViewById(R.id.query)
        seasonInput = findViewById(R.id.season)
        episodeInput = findViewById(R.id.episode)
        langInput = findViewById(R.id.languages)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        searchButton = findViewById(R.id.searchButton)

        queryInput.setText(parsed.title)
        parsed.season?.let { seasonInput.setText(it.toString()) }
        parsed.episode?.let { episodeInput.setText(it.toString()) }
        langInput.setText(prefs.languages)

        findViewById<RecyclerView>(R.id.results).apply {
            layoutManager = LinearLayoutManager(this@SubtitleSearchActivity)
            adapter = this@SubtitleSearchActivity.adapter
        }

        searchButton.setOnClickListener { onSearchClicked() }
        queryInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { onSearchClicked(); true } else false
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check after returning from Settings.
        if (providers.none { it.isConfigured() }) {
            status.text = "To search online, add a free API key for OpenSubtitles and/or SubDL in Settings."
            searchButton.text = "Open settings"
        } else {
            searchButton.setText(R.string.search)
            if (!autoSearched) { autoSearched = true; search() }
        }
    }

    private fun onSearchClicked() {
        if (providers.none { it.isConfigured() }) {
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(queryInput.windowToken, 0)
            search()
        }
    }

    private fun search() {
        val title = queryInput.text.toString().trim()
        val langs = langInput.text.toString().split(',', ' ', ';').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("en") }
        val q = SearchQuery(
            title = title,
            year = parsed.year.takeIf { title.equals(parsed.title, ignoreCase = true) },
            season = seasonInput.text.toString().toIntOrNull(),
            episode = episodeInput.text.toString().toIntOrNull(),
            languages = langs,
            // Hash only identifies the original file; skip it if the user is searching for something else.
            movieHash = movieHash.takeIf { title.equals(parsed.title, ignoreCase = true) },
            fileSize = fileSize.takeIf { it > 0 },
            fileName = fileName,
        )
        if (q.title.isBlank() && q.movieHash == null) {
            status.text = "Type a title to search for."
            return
        }

        job?.cancel()
        adapter.submit(emptyList())
        progress.visibility = View.VISIBLE
        status.text = "Searching ${providers.filter { it.isConfigured() }.joinToString(" and ") { it.name }}…"

        job = lifecycleScope.launch {
            val errors = ArrayList<String>()
            val all = coroutineScope {
                providers.filter { it.isConfigured() }.map { p ->
                    async {
                        runCatching { p.search(q) }.getOrElse { e ->
                            synchronized(errors) { errors += "${p.name}: ${e.message ?: e.javaClass.simpleName}" }
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
            val sorted = all.sortedWith(
                compareByDescending<SubtitleResult> { it.hashMatch }
                    .thenBy { langs.indexOf(it.language.lowercase().take(2)).let { i -> if (i < 0) 99 else i } }
                    .thenByDescending { it.downloads }
            )
            progress.visibility = View.GONE
            adapter.submit(sorted)
            status.text = buildString {
                append(
                    when {
                        sorted.isEmpty() -> "No subtitles found. Try a simpler title, or clear season/episode."
                        else -> "${sorted.size} found · tap one to download and load it"
                    }
                )
                if (sorted.any { it.hashMatch }) append("\n✓ = matched to this exact file (best sync)")
                errors.forEach { append("\n⚠ ").append(it) }
            }
        }
    }

    private fun download(r: SubtitleResult) {
        job?.cancel()
        progress.visibility = View.VISIBLE
        status.text = "Downloading from ${r.provider.name}…"
        job = lifecycleScope.launch {
            val result = runCatching {
                val bytes = r.provider.download(r)
                withContext(Dispatchers.IO) {
                    SubtitleFiles.saveDownloaded(
                        this@SubtitleSearchActivity, bytes,
                        baseName = fileName.substringBeforeLast('.', fileName).ifBlank { r.release },
                        lang = r.language,
                        episodeTag = episodeTag(),
                    )
                }
            }
            progress.visibility = View.GONE
            result.onSuccess { f ->
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, f.absolutePath))
                finish()
            }.onFailure { e ->
                status.text = "Download failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun episodeTag(): String? {
        val s = seasonInput.text.toString().toIntOrNull() ?: return null
        val e = episodeInput.text.toString().toIntOrNull() ?: return null
        return "s%02de%02d".format(s, e)
    }

    private class ResultAdapter(private val onClick: (SubtitleResult) -> Unit) :
        RecyclerView.Adapter<ResultAdapter.VH>() {
        private var items: List<SubtitleResult> = emptyList()
        private val nf = NumberFormat.getIntegerInstance()

        fun submit(list: List<SubtitleResult>) {
            items = list
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val details: TextView = v.findViewById(R.id.details)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_subtitle, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = items[position]
            h.name.text = (if (r.hashMatch) "✓ " else "") + r.release
            h.details.text = listOfNotNull(
                r.language.uppercase().ifBlank { null },
                r.provider.name,
                r.downloads.takeIf { it > 0 }?.let { "${nf.format(it)} downloads" },
                "hearing impaired".takeIf { r.hearingImpaired },
                r.uploader?.let { "by $it" },
            ).joinToString("  ·  ")
            h.itemView.setOnClickListener { onClick(r) }
        }
    }
}
