package com.videotyper.data

/**
 * Turns a release-style video filename into a clean search title (+ optional year, + TV flag), the
 * way media-server scrapers (Plex/Emby/Jellyfin) do. The load-bearing trick is "anchor first, then
 * strip": cut the string at the SxxEyy or 4-digit-year anchor BEFORE removing junk tokens, so a
 * junk token can never be mistaken for part of the title.
 */
object TitleParser {

    private val SEPS = Regex("""[._]+""")
    private val TVEP = Regex("""[Ss](\d{1,2})[\s.\-]?[Ee](\d{1,2})""")
    private val YEAR = Regex("""[(\.\s\[]((?:19|20)\d{2})[)\.\s\]]""")
    private val JUNK = Regex(
        """(?i)\b(1080p|2160p|720p|480p|4k|bluray|bdrip|brrip|webrip|web[-.]?dl|web|hdtv|dvdrip|hdrip|""" +
            """x264|x265|h\.?264|h\.?265|hevc|avc|xvid|divx|aac|ac3|eac3|dts|dd5\.1|ddp5\.1|truehd|flac|mp3|""" +
            """proper|repack|extended|unrated|remastered|remux|10bit|8bit|hdr|hdr10|imax)\b.*"""
    )
    private val GROUP = Regex("""-\w+$""")
    private val WS = Regex("""\s+""")

    data class Parsed(val title: String, val year: String?, val isTv: Boolean)

    fun parse(fileName: String): Parsed {
        var s = fileName.substringBeforeLast('.', fileName) // drop extension
        s = s.replace(SEPS, " ")

        TVEP.find(s)?.let { m ->
            return Parsed(clean(s.substring(0, m.range.first)), year = null, isTv = true)
        }

        var year: String? = null
        YEAR.find(s)?.let { m ->
            year = m.groupValues[1]
            s = s.substring(0, m.range.first)
        }
        return Parsed(clean(s), year = year, isTv = false)
    }

    private fun clean(raw: String): String =
        raw.replace(JUNK, "")
            .replace(GROUP, "")
            .replace(WS, " ")
            .trim(' ', '-', '(', '[')
}
