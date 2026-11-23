package com.reelplayer.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.reelplayer.app.subs.MovieHash
import com.reelplayer.app.subs.SubtitleFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var libVlc: LibVLC
    private lateinit var player: MediaPlayer
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var controls: View
    private lateinit var titleView: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var seek: SeekBar
    private lateinit var timeCurrent: TextView
    private lateinit var timeTotal: TextView
    private lateinit var centerInfo: TextView

    private var pendingUri: Uri? = null
    private var videoUri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var displayName = "Video"
    private var movieHash: String? = null
    private var fileSize: Long = -1

    private var opened = false
    private var ended = false
    private var resumePlaying = true
    private var userSeeking = false
    private var scaleIndex = 0
    private var orientationIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsTask = Runnable { setControlsVisible(false) }
    private val hideCenterTask = Runnable { centerInfo.visibility = View.GONE }

    private val scales = listOf(
        MediaPlayer.ScaleType.SURFACE_BEST_FIT to "Fit",
        MediaPlayer.ScaleType.SURFACE_FIT_SCREEN to "Fill screen (crop)",
        MediaPlayer.ScaleType.SURFACE_FILL to "Stretch",
        MediaPlayer.ScaleType.SURFACE_16_9 to "16:9",
        MediaPlayer.ScaleType.SURFACE_4_3 to "4:3",
        MediaPlayer.ScaleType.SURFACE_ORIGINAL to "Original size",
    )

    private val orientations = listOf(
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE to "Landscape",
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT to "Portrait",
        ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR to "Auto-rotate",
    )

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { SubtitleFiles.importFromUri(this@PlayerActivity, uri) }
            }
            result.onSuccess { attachSubtitle(it, remember = true) }
                .onFailure { toast(it.message ?: "Couldn't load that subtitle") }
        }
    }

    private val searchSubtitle = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val path = r.data?.getStringExtra(SubtitleSearchActivity.EXTRA_RESULT_PATH)
        if (r.resultCode == RESULT_OK && path != null) attachSubtitle(File(path), remember = true)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = orientations[0].first
        hideSystemUi()

        pendingUri = intent?.data
        if (pendingUri == null) {
            toast("Nothing to play")
            finish()
            return
        }

        bindViews()

        val options = arrayListOf(
            "--audio-time-stretch",
            "--network-caching=1500",
            "--sub-text-scale=${prefs.subSizePercent}",
        )
        if (prefs.subEncoding.isNotBlank()) options += "--subsdec-encoding=${prefs.subEncoding}"

        libVlc = LibVLC(this, options)
        player = MediaPlayer(libVlc)
        player.setEventListener(object : MediaPlayer.EventListener {
            override fun onEvent(event: MediaPlayer.Event) = onPlayerEvent(event)
        })
    }

    override fun onStart() {
        super.onStart()
        if (!::player.isInitialized) return
        player.attachViews(videoLayout, null, true, false)
        if (!opened) {
            opened = true
            pendingUri?.let { openVideo(it) }
        } else if (resumePlaying && !ended) {
            player.play()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!::player.isInitialized) return
        resumePlaying = player.isPlaying
        player.pause()
        savePosition()
        player.detachViews()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (!::player.isInitialized) return
        savePosition()
        player.setEventListener(null)
        player.stop()
        player.release()
        libVlc.release()
        runCatching { pfd?.close() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = intent.data ?: return
        if (!::player.isInitialized) return
        savePosition()
        player.stop()
        openVideo(uri)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ------------------------------------------------------------------ setup

    @SuppressLint("ClickableViewAccessibility")
    private fun bindViews() {
        videoLayout = findViewById(R.id.videoLayout)
        controls = findViewById(R.id.controls)
        titleView = findViewById(R.id.title)
        btnPlay = findViewById(R.id.btnPlay)
        seek = findViewById(R.id.seek)
        timeCurrent = findViewById(R.id.timeCurrent)
        timeTotal = findViewById(R.id.timeTotal)
        centerInfo = findViewById(R.id.centerInfo)

        // Keep buttons clear of camera notches.
        ViewCompat.setOnApplyWindowInsetsListener(controls) { v, insets ->
            val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(c.left, c.top, c.right, c.bottom)
            insets
        }

        fun button(id: Int, action: () -> Unit) =
            findViewById<View>(id).setOnClickListener { action(); scheduleHide() }

        button(R.id.btnBack) { finish() }
        button(R.id.btnPlay) { togglePlay() }
        button(R.id.btnRewind) { seekBy(-10_000) }
        button(R.id.btnForward) { seekBy(10_000) }
        button(R.id.btnSubs) { showSubtitleMenu() }
        button(R.id.btnAudio) { chooseTrack(subtitles = false) }
        button(R.id.btnAspect) { cycleScale() }
        button(R.id.btnRotate) { cycleOrientation() }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) timeCurrent.text = formatTime(player.length * progress / 1000)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                userSeeking = true
                handler.removeCallbacks(hideControlsTask)
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                val len = player.length
                if (len > 0) player.setTime(len * sb.progress / 1000)
                userSeeking = false
                scheduleHide()
            }
        })

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControlsVisible(controls.visibility != View.VISIBLE)
                return true
            }

            // Double-tap left third = back 10 s, right third = forward 10 s, middle = play/pause.
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = videoLayout.width.coerceAtLeast(1)
                when {
                    e.x < w / 3f -> seekBy(-10_000)
                    e.x > w * 2 / 3f -> seekBy(10_000)
                    else -> togglePlay()
                }
                return true
            }
        })
        findViewById<View>(R.id.touchLayer).setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }
    }

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------------------------ playback

    private fun openVideo(uri: Uri) {
        videoUri = uri
        ended = false
        movieHash = null
        fileSize = -1
        runCatching { pfd?.close() }
        pfd = null

        displayName = SubtitleFiles.displayName(this, uri) ?: uri.toString()
        titleView.text = displayName

        val media = try {
            if (uri.scheme == "content") {
                // libVLC reads Android content:// URIs through a file descriptor.
                val fd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("The file couldn't be opened")
                pfd = fd
                Media(libVlc, fd.fileDescriptor)
            } else {
                Media(libVlc, uri) // file://, http(s)://, rtsp://, etc.
            }
        } catch (e: Exception) {
            toast("Can't open this video: ${e.message}")
            finish()
            return
        }

        media.setHWDecoderEnabled(prefs.hwDecoding, false)
        val resumeAt = prefs.getPosition(uri)
        if (resumeAt > 5_000) media.addOption(":start-time=${resumeAt / 1000.0}")

        player.setMedia(media)
        media.release()
        player.play()

        // Reload the subtitle the user picked last time for this video.
        prefs.getSubtitle(uri)?.let { File(it) }?.takeIf { it.exists() }
            ?.let { attachSubtitle(it, remember = false, quiet = true) }

        if (resumeAt > 5_000) showCenter("Resumed at ${formatTime(resumeAt)}")
        setControlsVisible(true)

        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { MovieHash.compute(applicationContext, uri) }
            if (videoUri == uri) {
                movieHash = r?.first
                fileSize = r?.second ?: -1
            }
        }
    }

    private fun onPlayerEvent(e: MediaPlayer.Event) {
        when (e.type) {
            MediaPlayer.Event.Playing -> {
                btnPlay.setImageResource(R.drawable.ic_pause)
                scheduleHide()
            }
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped ->
                btnPlay.setImageResource(R.drawable.ic_play)
            MediaPlayer.Event.LengthChanged ->
                timeTotal.text = formatTime(player.length)
            MediaPlayer.Event.TimeChanged ->
                updateProgress()
            MediaPlayer.Event.EndReached -> {
                ended = true
                videoUri?.let { prefs.clearPosition(it) }
                btnPlay.setImageResource(R.drawable.ic_play)
                setControlsVisible(true)
            }
            MediaPlayer.Event.EncounteredError ->
                toast("Playback error — try turning hardware decoding off in Settings")
        }
    }

    private fun updateProgress() {
        if (userSeeking) return
        val len = player.length
        val t = player.time
        timeCurrent.text = formatTime(t)
        if (len > 0) seek.progress = (t * 1000 / len).toInt()
    }

    private fun togglePlay() {
        when {
            ended -> videoUri?.let { openVideo(it) } // replay from the start
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val len = player.length
        val target = (player.time + deltaMs).coerceAtLeast(0).let { if (len > 0) it.coerceAtMost(len - 1000) else it }
        player.setTime(target)
        showCenter((if (deltaMs > 0) "+" else "−") + "${kotlin.math.abs(deltaMs) / 1000}s  ·  ${formatTime(target)}")
    }

    private fun savePosition() {
        val uri = videoUri ?: return
        if (ended) return
        val t = player.time
        val len = player.length
        when {
            len <= 0 -> Unit
            t >= len - 15_000 -> prefs.clearPosition(uri)
            t > 5_000 -> prefs.setPosition(uri, t)
        }
    }

    // ------------------------------------------------------------------ subtitles & tracks

    private fun attachSubtitle(file: File, remember: Boolean, quiet: Boolean = false) {
        val ok = player.addSlave(IMedia.Slave.Type.Subtitle, Uri.fromFile(file), true)
        if (remember && ok) videoUri?.let { prefs.setSubtitle(it, file.absolutePath) }
        if (!quiet) showCenter(if (ok) "Subtitle loaded: ${file.name}" else "Couldn't load that subtitle")
    }

    private fun showSubtitleMenu() {
        val options = arrayOf(
            "Search online…",
            "Load .srt file…",
            "Choose subtitle track",
            "Subtitle timing (sync)…",
            "Turn subtitles off",
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.subtitles)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openSubtitleSearch()
                    1 -> pickSubtitle.launch(arrayOf("application/x-subrip", "text/*", "application/*", "*/*"))
                    2 -> chooseTrack(subtitles = true)
                    3 -> showDelayDialog()
                    4 -> {
                        player.setSpuTrack(-1)
                        videoUri?.let { prefs.setSubtitle(it, null) }
                        showCenter("Subtitles off")
                    }
                }
            }
            .show()
    }

    private fun openSubtitleSearch() {
        searchSubtitle.launch(
            Intent(this, SubtitleSearchActivity::class.java)
                .putExtra(SubtitleSearchActivity.EXTRA_FILE_NAME, displayName)
                .putExtra(SubtitleSearchActivity.EXTRA_HASH, movieHash)
                .putExtra(SubtitleSearchActivity.EXTRA_SIZE, fileSize)
        )
    }

    private fun chooseTrack(subtitles: Boolean) {
        val tracks = (if (subtitles) player.spuTracks else player.audioTracks) ?: emptyArray()
        if (tracks.isEmpty()) {
            toast(if (subtitles) "No subtitle tracks — use Search online or Load .srt file" else "No audio tracks")
            return
        }
        val current = if (subtitles) player.spuTrack else player.audioTrack
        val names = tracks.map { it.name ?: "Track ${it.id}" }.toTypedArray()
        val checked = tracks.indexOfFirst { it.id == current }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (subtitles) "Subtitle track" else "Audio track")
            .setSingleChoiceItems(names, checked) { d, which ->
                val id = tracks[which].id
                if (subtitles) player.setSpuTrack(id) else player.setAudioTrack(id)
                d.dismiss()
            }
            .show()
    }

    private fun showDelayDialog() {
        var delayUs = player.spuDelay
        val label = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            minWidth = (120 * resources.displayMetrics.density).toInt()
        }
        fun apply() {
            label.text = String.format(Locale.ROOT, "%+.2f s", delayUs / 1_000_000.0)
            player.setSpuDelay(delayUs)
        }
        fun step(text: String, deltaUs: Long) = Button(this).apply {
            this.text = text
            setOnClickListener { delayUs += deltaUs; apply() }
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, 0)
            addView(step("−1s", -1_000_000))
            addView(step("−0.1", -100_000))
            addView(label)
            addView(step("+0.1", 100_000))
            addView(step("+1s", 1_000_000))
        }
        val hint = TextView(this).apply {
            text = "Subtitles too early? Press +. Too late? Press −."
            alpha = 0.7f
            gravity = Gravity.CENTER
            setPadding(pad, pad / 2, pad, 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(hint)
        }
        apply()
        MaterialAlertDialogBuilder(this)
            .setTitle("Subtitle timing")
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton("Reset") { _, _ -> delayUs = 0; apply() }
            .show()
    }

    private fun cycleScale() {
        scaleIndex = (scaleIndex + 1) % scales.size
        player.setVideoScale(scales[scaleIndex].first)
        showCenter(scales[scaleIndex].second)
    }

    private fun cycleOrientation() {
        orientationIndex = (orientationIndex + 1) % orientations.size
        requestedOrientation = orientations[orientationIndex].first
        showCenter(orientations[orientationIndex].second)
    }

    // ------------------------------------------------------------------ UI helpers

    private fun setControlsVisible(visible: Boolean) {
        controls.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) scheduleHide() else handler.removeCallbacks(hideControlsTask)
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControlsTask)
        if (::player.isInitialized && player.isPlaying) handler.postDelayed(hideControlsTask, 4_000)
    }

    private fun showCenter(text: String) {
        centerInfo.text = text
        centerInfo.visibility = View.VISIBLE
        handler.removeCallbacks(hideCenterTask)
        handler.postDelayed(hideCenterTask, 1_500)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // Remote controls, keyboards, Android TV.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!::player.isInitialized) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_HEADSETHOOK -> togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (!player.isPlaying) togglePlay()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (player.isPlaying) player.pause()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-10_000)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(10_000)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if (controls.visibility != View.VISIBLE) setControlsVisible(true) else return super.onKeyDown(keyCode, event)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
