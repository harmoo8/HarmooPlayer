package com.reelplayer.app.subs

/**
 * Turns a release-style file name into something a subtitle site can search:
 *   "The.Office.US.S03E07.720p.WEB-DL.mkv" -> title "The Office US", season 3, episode 7
 *   "Blade.Runner.1982.Final.Cut.1080p.BluRay.x264.avi" -> title "Blade Runner", year 1982
 */
object NameParser {
    data class Parsed(val title: String, val year: Int?, val season: Int?, val episode: Int?) {
        val episodeTag: String? get() =
            if (season != null && episode != null) "s%02de%02d".format(season, episode) else null
    }

    private val episodeRx = Regex("""(?i)\bS(\d{1,2})[ ._-]?E(\d{1,3})\b""")
    private val episodeAltRx = Regex("""(?i)\b(\d{1,2})x(\d{2,3})\b""")
    private val yearRx = Regex("""\b(19[2-9]\d|20[0-4]\d)\b""")
    private val junkRx = Regex(
        """(?i)\b(2160p|1080p|1080i|720p|576p|480p|4k|uhd|hdr|hdr10|dv|bluray|blu-ray|brrip|bdrip|dvdrip|dvdscr|""" +
            """web-?dl|webrip|web|hdtv|hdrip|pdtv|xvid|divx|x264|x265|h\.?264|h\.?265|hevc|avc|aac|ac3|dts|ddp?5\.1|""" +
            """5\.1|7\.1|remux|proper|repack|extended|unrated|directors\.?cut|internal|limited|multi|subbed|dubbed|nf|amzn|dsnp|hmax)\b"""
    )

    fun parse(fileName: String): Parsed {
        var s = fileName.substringBeforeLast('.', fileName)
        s = s.replace(Regex("""\[[^\]]*]|\{[^}]*}"""), " ")   // [group] tags
        s = s.replace('_', ' ').replace('.', ' ').replace(Regex("\\s+"), " ").trim()

        var season: Int? = null
        var episode: Int? = null
        var cut = s.length

        (episodeRx.find(s) ?: episodeAltRx.find(s))?.let {
            season = it.groupValues[1].toInt()
            episode = it.groupValues[2].toInt()
            cut = minOf(cut, it.range.first)
        }

        // Use the LAST plausible year before the episode tag (handles titles like "2001 A Space Odyssey 1968").
        val year = yearRx.findAll(s.substring(0, cut)).lastOrNull()
            ?.takeIf { it.range.first > 0 }
            ?.also { cut = minOf(cut, it.range.first) }
            ?.value?.toInt()

        junkRx.find(s)?.let { if (it.range.first > 0) cut = minOf(cut, it.range.first) }

        val title = s.substring(0, cut)
            .replace(Regex("""[(\[\-–]+\s*$"""), "")
            .replace(Regex("""\(\s*\)"""), "")
            .trim()
            .ifBlank { s }
        return Parsed(title, year, season, episode)
    }
}
