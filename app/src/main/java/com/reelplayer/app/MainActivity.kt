package com.reelplayer.app

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var grantButton: MaterialButton
    private lateinit var progress: View
    private val adapter = LibraryAdapter(
        onVideo = { play(it.uri) },
        onFolder = { openFolder(it) },
    )

    private var allVideos: List<VideoItem> = emptyList()
    private var root = FolderNode("", null)
    private var current = root
    private var restorePath: List<String>? = null

    private val backToParent = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goUp()
    }

    private val videoPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        loadVideos()
    }

    // Any file type is allowed: many file managers don't tag .mkv/.avi as video/*.
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            play(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { goUp() }
        onBackPressedDispatcher.addCallback(this, backToParent)

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.emptyText)
        grantButton = findViewById(R.id.grantButton)
        progress = findViewById(R.id.progress)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        adapter.thumbLoader = { item, view -> loadThumb(item, view) }
        adapter.sizeLevel = prefs.listSize

        findViewById<View>(R.id.fab).setOnClickListener { openDocument.launch(arrayOf("video/*", "application/*", "*/*")) }
        grantButton.setOnClickListener { askPermission() }

        restorePath = savedInstanceState?.getStringArrayList("path")
        showLastCrash()
        if (hasPermission()) loadVideos() else askPermission()
    }

    private fun showLastCrash() {
        val f = App.crashFile(application)
        if (!f.exists()) return
        val report = runCatching { f.readText() }.getOrDefault("")
        f.delete()
        if (report.isBlank()) return
        MaterialAlertDialogBuilder(this)
            .setTitle("HarmooPlayer closed unexpectedly")
            .setMessage("Sorry about that. Tap Copy and paste the details to whoever is fixing the app.\n\n" + report.take(1500))
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("HarmooPlayer crash", report))
                android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Picks up a change made in Settings.
        if (adapter.sizeLevel != prefs.listSize) adapter.sizeLevel = prefs.listSize
    }

    private fun chooseSize() {
        val names = arrayOf("Small", "Medium", "Large")
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.display_size)
            .setSingleChoiceItems(names, prefs.listSize) { d, which ->
                prefs.listSize = which
                adapter.sizeLevel = which
                d.dismiss()
            }
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("path", ArrayList(current.path))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_view)?.apply {
            if (prefs.browseFolders) {
                setIcon(R.drawable.ic_list); setTitle(R.string.view_all)
            } else {
                setIcon(R.drawable.ic_folder); setTitle(R.string.view_folders)
            }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_refresh -> { restorePath = current.path; loadVideos(); true }
        R.id.action_url -> { askUrl(); true }
        R.id.action_size -> { chooseSize(); true }
        R.id.action_view -> {
            prefs.browseFolders = !prefs.browseFolders
            invalidateOptionsMenu()
            render()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------ browsing

    private fun openFolder(node: FolderNode) {
        current = node
        render()
        list.scrollToPosition(0)
    }

    /** Up one level, skipping folders that only pass through to a single sub-folder. */
    private fun goUp() {
        var p = current.parent ?: return
        while (p.parent != null && p.videos.isEmpty() && p.children.size == 1) p = p.parent!!
        current = p
        render()
    }

    private fun render() {
        val folders = prefs.browseFolders
        val atRoot = current === root
        backToParent.isEnabled = folders && !atRoot

        if (!folders) {
            toolbar.title = getString(R.string.app_name)
            toolbar.subtitle = "All videos · newest first"
            toolbar.navigationIcon = null
            adapter.submit(allVideos.sortedByDescending { it.dateAdded }.map { LibraryAdapter.Row.Video(it, showFolder = true) })
        } else {
            toolbar.title = if (atRoot) getString(R.string.app_name) else current.name
            toolbar.subtitle = if (atRoot) "Folders with videos"
            else current.path.dropLast(1).joinToString(" › ").ifEmpty { "Storage" }
            if (atRoot) toolbar.navigationIcon = null else toolbar.setNavigationIcon(R.drawable.ic_back)
            val rows = ArrayList<LibraryAdapter.Row>()
            current.sortedFolders().forEach { f ->
                val (label, target) = f.collapsed()
                rows += LibraryAdapter.Row.Folder(label, target, isVolume = atRoot && f.name in volumeLabels)
            }
            current.sortedVideos().forEach { rows += LibraryAdapter.Row.Video(it, showFolder = false) }
            adapter.submit(rows)
        }
    }

    // ------------------------------------------------------------------ loading

    private fun askUrl() {
        val input = EditText(this).apply { hint = "https://…/video.mkv"; setSingleLine() }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.open_url)
            .setView(box)
            .setPositiveButton("Play") { _, _ ->
                val u = input.text.toString().trim()
                if (u.isNotEmpty()) play(Uri.parse(u))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun play(uri: Uri) {
        startActivity(Intent(this, PlayerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun hasPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, videoPermission) == PackageManager.PERMISSION_GRANTED) return true
        // Android 14+: "Allow limited access" still lets us list the videos the user picked.
        return Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun askPermission() {
        val perms = if (Build.VERSION.SDK_INT >= 34)
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else arrayOf(videoPermission)
        requestPermission.launch(perms)
    }

    private fun loadVideos() {
        if (!hasPermission()) {
            adapter.submit(emptyList())
            empty.visibility = View.VISIBLE
            emptyText.setText(R.string.need_permission)
            grantButton.visibility = View.VISIBLE
            return
        }
        grantButton.visibility = View.GONE
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { queryVideos() }
            val tree = withContext(Dispatchers.Default) { FolderNode.build(items) }
            progress.visibility = View.GONE
            allVideos = items
            root = tree
            current = restorePath?.let { tree.find(it) } ?: tree
            restorePath = null
            render()
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            emptyText.setText(R.string.no_videos)
        }
    }

    private val volumeLabels = HashSet<String>()

    @Suppress("DEPRECATION")
    private fun queryVideos(): List<VideoItem> {
        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cols = arrayListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
        )
        if (Build.VERSION.SDK_INT >= 29) {
            cols += MediaStore.Video.Media.RELATIVE_PATH
            cols += MediaStore.Video.Media.VOLUME_NAME
        } else {
            cols += MediaStore.Video.Media.DATA
        }

        // First pass: raw (volume, folder list) per video.
        data class Raw(val id: Long, val name: String, val dur: Long, val size: Long, val added: Long, val vol: String, val dirs: List<String>)
        val raws = ArrayList<Raw>()
        runCatching {
            contentResolver.query(collection, cols.toTypedArray(), null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(cols[0])
                val nameCol = c.getColumnIndexOrThrow(cols[1])
                val durCol = c.getColumnIndexOrThrow(cols[2])
                val sizeCol = c.getColumnIndexOrThrow(cols[3])
                val addedCol = c.getColumnIndexOrThrow(cols[4])
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val (vol, dirs) = if (Build.VERSION.SDK_INT >= 29) {
                        val rel = c.getString(c.getColumnIndexOrThrow(cols[5])).orEmpty()
                        val v = c.getString(c.getColumnIndexOrThrow(cols[6])) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                        v to rel.split('/').filter { it.isNotBlank() }
                    } else {
                        parseDataPath(c.getString(c.getColumnIndexOrThrow(cols[5])).orEmpty())
                    }
                    raws += Raw(id, c.getString(nameCol) ?: "Video $id", c.getLong(durCol), c.getLong(sizeCol), c.getLong(addedCol), vol, dirs)
                }
            }
        }

        // Only show "Phone storage" / "SD card" at the top when there's more than one.
        val volumes = raws.map { it.vol }.distinct()
        val multi = volumes.size > 1
        volumeLabels.clear()
        return raws.map { r ->
            val label = volumeLabel(r.vol)
            if (multi) volumeLabels += label
            VideoItem(
                id = r.id,
                uri = ContentUris.withAppendedId(collection, r.id),
                name = r.name,
                durationMs = r.dur,
                size = r.size,
                dateAdded = r.added,
                folders = if (multi) listOf(label) + r.dirs else r.dirs,
            )
        }
    }

    private fun parseDataPath(path: String): Pair<String, List<String>> {
        val m = Regex("^/storage/(emulated/\\d+|[^/]+)/(.*)$").find(path)
        val (vol, rest) = if (m != null) {
            val v = m.groupValues[1]
            (if (v.startsWith("emulated")) "external_primary" else v.lowercase()) to m.groupValues[2]
        } else "external_primary" to path.trimStart('/')
        return vol to rest.split('/').dropLast(1).filter { it.isNotBlank() }
    }

    private fun volumeLabel(vol: String) =
        if (vol == "external_primary" || vol == "external") "Phone storage" else "SD card / USB (${vol.uppercase()})"

    private val thumbs = LruCache<Long, Bitmap>(80)

    @Suppress("DEPRECATION")
    private fun loadThumb(item: VideoItem, view: ImageView) {
        thumbs.get(item.id)?.let { view.setImageBitmap(it); return }
        view.setImageResource(R.drawable.ic_movie)
        view.tag = item.id
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 29) contentResolver.loadThumbnail(item.uri, Size(320, 180), null)
                    else MediaStore.Video.Thumbnails.getThumbnail(
                        contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null
                    )
                }.getOrNull()
            }
            if (bmp != null) {
                thumbs.put(item.id, bmp)
                if (view.tag == item.id) view.setImageBitmap(bmp)
            }
        }
    }
}

class LibraryAdapter(
    private val onVideo: (VideoItem) -> Unit,
    private val onFolder: (FolderNode) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Folder(val label: String, val node: FolderNode, val isVolume: Boolean) : Row()
        data class Video(val item: VideoItem, val showFolder: Boolean) : Row()
    }

    private var rows: List<Row> = emptyList()
    var thumbLoader: ((VideoItem, ImageView) -> Unit)? = null

    /** 0 = small, 1 = medium, 2 = large. */
    var sizeLevel: Int = 1
        set(v) { field = v; notifyDataSetChanged() }

    private class Dims(val title: Float, val meta: Float, val thumbW: Int, val thumbH: Int, val icon: Int, val iconPad: Int, val lines: Int)
    private val dims = listOf(
        Dims(title = 14f, meta = 11f, thumbW = 96, thumbH = 54, icon = 40, iconPad = 10, lines = 1),
        Dims(title = 16f, meta = 12f, thumbW = 120, thumbH = 68, icon = 48, iconPad = 12, lines = 2),
        Dims(title = 19f, meta = 14f, thumbW = 168, thumbH = 95, icon = 58, iconPad = 15, lines = 3),
    )

    private fun View.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    class FolderVH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val count: TextView = v.findViewById(R.id.count)
    }

    class VideoVH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    override fun getItemViewType(position: Int) = if (rows[position] is Row.Folder) 0 else 1
    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) FolderVH(inf.inflate(R.layout.item_folder, parent, false))
        else VideoVH(inf.inflate(R.layout.item_video, parent, false))
    }

    override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Folder -> {
                h as FolderVH
                val d = dims[sizeLevel]
                h.name.text = row.label
                h.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, d.title)
                h.count.setTextSize(TypedValue.COMPLEX_UNIT_SP, d.meta)
                h.name.maxLines = d.lines
                h.icon.layoutParams = h.icon.layoutParams.apply { width = h.icon.dp(d.icon); height = h.icon.dp(d.icon) }
                h.icon.setPadding(h.icon.dp(d.iconPad), h.icon.dp(d.iconPad), h.icon.dp(d.iconPad), h.icon.dp(d.iconPad))
                h.icon.setImageResource(if (row.isVolume) R.drawable.ic_storage else R.drawable.ic_folder)
                val n = row.node
                val subs = n.children.size
                h.count.text = listOfNotNull(
                    "${n.totalVideos} video${if (n.totalVideos == 1) "" else "s"}",
                    subs.takeIf { it > 0 }?.let { "$it folder${if (it == 1) "" else "s"}" },
                ).joinToString("  ·  ")
                h.itemView.setOnClickListener { onFolder(n) }
            }
            is Row.Video -> {
                h as VideoVH
                val item = row.item
                val d = dims[sizeLevel]
                h.title.text = item.name
                h.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, d.title)
                h.meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, d.meta)
                h.title.maxLines = d.lines
                h.thumb.layoutParams = h.thumb.layoutParams.apply { width = h.thumb.dp(d.thumbW); height = h.thumb.dp(d.thumbH) }
                val parts = listOfNotNull(
                    item.durationMs.takeIf { it > 0 }?.let { formatTime(it) },
                    Formatter.formatShortFileSize(h.itemView.context, item.size).takeIf { item.size > 0 },
                    item.name.substringAfterLast('.', "").uppercase().takeIf { it.length in 2..4 },
                    item.folders.lastOrNull()?.takeIf { row.showFolder },
                )
                h.meta.text = parts.joinToString("  ·  ")
                thumbLoader?.invoke(item, h.thumb)
                h.itemView.setOnClickListener { onVideo(item) }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
