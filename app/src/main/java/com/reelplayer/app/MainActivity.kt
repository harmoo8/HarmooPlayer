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
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
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

data class VideoItem(val id: Long, val uri: Uri, val name: String, val durationMs: Long, val size: Long, val folder: String?)

class MainActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var emptyText: TextView
    private lateinit var grantButton: MaterialButton
    private lateinit var progress: View
    private val adapter = VideoAdapter { play(it.uri) }

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
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        emptyText = findViewById(R.id.emptyText)
        grantButton = findViewById(R.id.grantButton)
        progress = findViewById(R.id.progress)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        adapter.thumbLoader = { item, view -> loadThumb(item, view) }

        findViewById<View>(R.id.fab).setOnClickListener { openDocument.launch(arrayOf("video/*", "application/*", "*/*")) }
        grantButton.setOnClickListener { askPermission() }

        if (hasPermission()) loadVideos() else askPermission()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_refresh -> { loadVideos(); true }
        R.id.action_url -> { askUrl(); true }
        else -> super.onOptionsItemSelected(item)
    }

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
            progress.visibility = View.GONE
            adapter.submit(items)
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            emptyText.setText(R.string.no_videos)
        }
    }

    private fun queryVideos(): List<VideoItem> {
        val out = ArrayList<VideoItem>()
        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        runCatching {
            contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(projection[0])
                val nameCol = c.getColumnIndexOrThrow(projection[1])
                val durCol = c.getColumnIndexOrThrow(projection[2])
                val sizeCol = c.getColumnIndexOrThrow(projection[3])
                val bucketCol = c.getColumnIndexOrThrow(projection[4])
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out += VideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(nameCol) ?: "Video $id",
                        durationMs = c.getLong(durCol),
                        size = c.getLong(sizeCol),
                        folder = c.getString(bucketCol),
                    )
                }
            }
        }
        return out
    }

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

class VideoAdapter(private val onClick: (VideoItem) -> Unit) : RecyclerView.Adapter<VideoAdapter.VH>() {
    private var items: List<VideoItem> = emptyList()
    var thumbLoader: ((VideoItem, ImageView) -> Unit)? = null

    fun submit(newItems: List<VideoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        h.title.text = item.name
        val parts = listOfNotNull(
            item.durationMs.takeIf { it > 0 }?.let { formatTime(it) },
            Formatter.formatShortFileSize(h.itemView.context, item.size).takeIf { item.size > 0 },
            item.name.substringAfterLast('.', "").uppercase().takeIf { it.length in 2..4 },
            item.folder,
        )
        h.meta.text = parts.joinToString("  ·  ")
        thumbLoader?.invoke(item, h.thumb)
        h.itemView.setOnClickListener { onClick(item) }
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
