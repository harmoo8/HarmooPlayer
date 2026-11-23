package com.reelplayer.app

import android.net.Uri

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val size: Long,
    val dateAdded: Long,
    /** Folder path from the storage root, e.g. ["Phone storage", "Movies", "Action"]. */
    val folders: List<String>,
)

/** One folder in the browsable tree. Only folders that contain videos (somewhere below) exist. */
class FolderNode(val name: String, val parent: FolderNode?) {
    val children = HashMap<String, FolderNode>()
    val videos = ArrayList<VideoItem>()
    var totalVideos = 0
        private set

    fun child(name: String) = children.getOrPut(name) { FolderNode(name, this) }

    /** Path of folder names from the root (root itself excluded). */
    val path: List<String>
        get() = generateSequence(this) { it.parent }.filter { it.parent != null }.map { it.name }.toList().reversed()

    fun sortedFolders() = children.values.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    fun sortedVideos() = videos.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }

    fun finish(): Int {
        totalVideos = videos.size + children.values.sumOf { it.finish() }
        return totalVideos
    }

    /** Skips through chains of folders that only hold one sub-folder (e.g. "Android / media / com.whatsapp"). */
    fun collapsed(): Pair<String, FolderNode> {
        var node = this
        var label = name
        while (node.videos.isEmpty() && node.children.size == 1) {
            node = node.children.values.first()
            label += " / " + node.name
        }
        return label to node
    }

    fun find(path: List<String>): FolderNode? {
        var n: FolderNode = this
        for (p in path) n = n.children[p] ?: return null
        return n
    }

    companion object {
        fun build(videos: List<VideoItem>): FolderNode {
            val root = FolderNode("", null)
            for (v in videos) {
                var n = root
                for (f in v.folders) n = n.child(f)
                n.videos += v
            }
            root.finish()
            return root
        }
    }
}

/** Sorts "Episode 2" before "Episode 10", ignoring case. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")
    override fun compare(a: String, b: String): Int {
        val ca = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val cb = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return ca.size - cb.size
    }
}
