package com.opentune.playback

import com.opentune.data.model.Song

/**
 * The song a spoken "play …" request named, out of YouTube's results, rather than whichever
 * song YouTube put first. The title the request named counts most, then the artist; when the
 * request only sends its words, how much of them a result's title and artist account for
 * decides. "Play money for nothing dire straits" leads with Yelawolf's "Money", which names one
 * of those five words, where Dire Straits' "Money for Nothing" names all of them.
 *
 * The song the request names most wins; YouTube's first is the answer when nothing it said is in
 * any of the results.
 */
fun bestVoiceMatch(songs: List<Song>, query: String, title: String?, artist: String?): Song? {
    val wantedTitle = title?.let(::titleKey)?.takeIf { it.isNotEmpty() }
    val wantedArtist = artist?.let(::norm)?.takeIf { it.isNotEmpty() }
    val asked = words(query)
    fun score(song: Song): Double =
        (wantedTitle?.let { 3 * overlap(it, titleKey(song.title)) } ?: 0.0) +
            (wantedArtist?.let { 2 * overlap(it, norm(song.artist)) } ?: 0.0) +
            if (asked.isEmpty()) 0.0 else 2 * coverage(asked, "${titleKey(song.title)} ${norm(song.artist)}")
    val best = songs.maxByOrNull { score(it) }
    // Nothing the request said shows up in the results: YouTube's own order is the best guess left.
    return best?.takeIf { score(it) > 0 } ?: songs.firstOrNull()
}

/** A title without its bracketed extras ("(Remastered 1996)", "[Live]") or its " - " suffix. */
private fun titleKey(s: String) = norm(s.replace(Regex("\\s*[(\\[][^)\\]]*[)\\]]"), "").substringBefore(" - "))

private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun words(s: String) = norm(s).split(' ').filter { it.isNotEmpty() }

/** How much of [wanted] the [actual] name is: the same, a longer or shorter form, or sharing words. */
private fun overlap(wanted: String, actual: String): Double {
    if (wanted.isEmpty() || actual.isEmpty()) return 0.0
    if (wanted == actual) return 1.0
    if (actual.contains(wanted) || wanted.contains(actual)) return 0.8
    val have = words(actual).toSet()
    val want = words(wanted)
    if (want.isEmpty()) return 0.0
    return 0.6 * want.count { it in have } / want.size
}

/** The share of the request's words the result's title and artist account for. */
private fun coverage(asked: List<String>, text: String): Double {
    val have = words(text).toSet()
    return asked.count { it in have }.toDouble() / asked.size
}
