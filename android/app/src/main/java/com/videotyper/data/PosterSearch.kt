package com.videotyper.data

import java.net.URLEncoder
import org.json.JSONObject

/**
 * Looks up poster art by title, no API key required (Plex/Emby-style). Movies come from Apple's
 * iTunes Search API, TV shows from TVmaze — both keyless and HTTPS. We query both and merge, so the
 * thumbnail manager can show every candidate; a TV-episode filename (SxxEyy) prefers TVmaze first.
 *
 * iTunes quirk: the documented `media=movie&entity=movie` filter currently returns zero results, so
 * we do a plain `term` search and keep records whose `kind` is "feature-movie". iTunes artwork URLs
 * end in a size segment ("/100x100bb.jpg"); "bb" is a bounding box that preserves aspect ratio, so
 * swapping in "600x600bb" yields a high-res poster (400x600 for a 2:3 source).
 */
object PosterSearch {

    data class Candidate(
        val title: String,
        val year: String?,
        val artUrl: String,
    )

    private const val ITUNES_SIZE = "600x600bb"

    suspend fun search(query: String, preferTv: Boolean = false): List<Candidate> {
        if (query.isBlank()) return emptyList()
        val itunes = searchItunesMovies(query)
        val tvmaze = searchTvmaze(query)
        val merged = if (preferTv) tvmaze + itunes else itunes + tvmaze
        // De-dup by artwork URL, preserving order.
        val seen = LinkedHashMap<String, Candidate>()
        for (c in merged) seen.putIfAbsent(c.artUrl, c)
        return seen.values.toList()
    }

    private suspend fun searchItunesMovies(query: String): List<Candidate> {
        val term = URLEncoder.encode(query, "UTF-8")
        val json = Http.getString("https://itunes.apple.com/search?term=$term&limit=25&country=US")
            ?: return emptyList()
        val results = try {
            JSONObject(json).optJSONArray("results") ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<Candidate>()
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            if (o.optString("kind") != "feature-movie") continue // the media=movie filter is broken
            val art100 = o.optString("artworkUrl100").ifBlank { continue }
            val title = o.optString("trackName").ifBlank { continue }
            val year = o.optString("releaseDate").take(4).takeIf { it.length == 4 }
            out.add(Candidate(title, year, itunesHighRes(art100)))
        }
        return out
    }

    private suspend fun searchTvmaze(query: String): List<Candidate> {
        val term = URLEncoder.encode(query, "UTF-8")
        val json = Http.getString("https://api.tvmaze.com/search/shows?q=$term") ?: return emptyList()
        val arr = try {
            org.json.JSONArray(json)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<Candidate>()
        for (i in 0 until arr.length()) {
            val show = arr.getJSONObject(i).optJSONObject("show") ?: continue
            val image = show.optJSONObject("image") ?: continue // no art -> skip
            val art = image.optString("original").ifBlank { image.optString("medium") }.ifBlank { continue }
            val title = show.optString("name").ifBlank { continue }
            val year = show.optString("premiered").take(4).takeIf { it.length == 4 }
            out.add(Candidate(title, year, art))
        }
        return out
    }

    private fun itunesHighRes(artworkUrl: String): String =
        artworkUrl.replace(Regex("""/\d+x\d+bb\.(jpg|png)$"""), "/$ITUNES_SIZE.$1")
}
