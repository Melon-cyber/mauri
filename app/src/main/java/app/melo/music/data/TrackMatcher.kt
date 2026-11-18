package app.melo.music.data

import java.text.Normalizer
import kotlin.math.abs

object TrackMatcher {
    private val versions = listOf("live", "remix", "cover", "karaoke", "sped", "slowed", "instrumental")
    private val decorations = Regex("(?i)\\((official.*?|lyrics?|audio|video|visuali[sz]er)\\)|\\[(official.*?|lyrics?|audio|video)\\]|\\b(official|lyrics|audio|video|hd|hq|vevo)\\b")
    fun normalized(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(decorations, " ").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Words that only describe a release edition and never change which recording is meant. */
    private const val EDITION_SAFE = "remaster(?:ed)?|radio\\s+edit|single\\s+version|album\\s+version|original\\s+version|bonus\\s+track|expanded\\s+edition|anniversary\\s+edition|deluxe\\s+edition"
    private const val EDITION = "$EDITION_SAFE|mono|stereo|explicit|clean|deluxe"
    private val editionBracket = Regex("(?i)\\s*[(\\[][^)\\]]*\\b(?:$EDITION)\\b[^)\\]]*[)\\]]")
    private val editionDash = Regex("(?i)\\s+[-–—]\\s+[^-–—]*\\b(?:$EDITION_SAFE)\\b.*$")
    private val fromDash = Regex("(?i)\\s+[-–—]\\s+from\\s+.*$")
    private val fromBracket = Regex("(?i)\\s*[(\\[]\\s*from\\s+[\"“«'].*?[)\\]]")
    private val decorationBracket = Regex("(?i)\\s*[(\\[][^)\\]]*\\b(?:official|lyrics?|lyric\\s+video|audio|video|visuali[sz]er|music\\s+video|mv|hd|hq|4k)\\b[^)\\]]*[)\\]]")
    private val featuring =Regex("(?i)\\s*[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|with)\\s+[^)\\]]*[)\\]]|\\s+(?:feat\\.?|ft\\.?|featuring)\\s+.*$")

    /** Title without edition/featuring noise; keeps live/remix/cover markers because they mean another recording. */
    fun cleanTitle(title: String): String {
        var value = title
        listOf(decorationBracket, featuring, editionBracket, fromBracket, editionDash, fromDash).forEach { value = value.replace(it, "") }
        return value.trim().ifBlank { title.trim() }
    }

    private val artistSeparators = Regex("(?i)\\s*(?:,|;|&| x | и | feat\\.?\\s| ft\\.?\\s| featuring | with | vs\\.?\\s)\\s*")
    fun artistNames(artist: String): List<String> = artist.removeSuffix(" - Topic").split(artistSeparators).map { it.trim() }.filter { it.isNotBlank() }
    fun primaryArtist(artist: String): String = artistNames(artist).firstOrNull() ?: artist.trim()

    /** Splits a YouTube video title like "Artist - Song (Official Video)" when the uploader is only a channel name. */
    fun splitVideoTitle(track: Track): Pair<String, String>? {
        val parts = track.title.split(Regex("\\s+[-–—]\\s+"), limit = 2)
        if (parts.size != 2 || parts.any { it.isBlank() }) return null
        val artist = parts[0].trim()
        val squashedArtist = latin(artist).replace(" ", "")
        val channel = latin(track.artist).replace(" ", "")
        val channelIsArtist = artistSimilarity(artist, track.artist) >= 0.5 || (squashedArtist.length >= 3 && channel.contains(squashedArtist))
        val genericChannel = Regex("vevo|records|recordings|music|official|channel|lyrics|label").containsMatchIn(channel)
        // "Song - Remastered 2011" by "Queen" must stay a Queen song; only split when the first part really names the artist.
        return if (channelIsArtist || genericChannel) artist to parts[1].trim() else null
    }
    /** Track with real artist/title for an "Artist - Song" upload, otherwise the track itself. */
    fun canonical(track: Track): Track {
        if (track.source != "youtube") return track
        val (artist, title) = splitVideoTitle(track) ?: return track
        return track.copy(artist = artist, title = title)
    }

    private val cyrillic = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya", 'і' to "i", 'ї' to "i", 'є' to "e", 'ґ' to "g",
    )
    /** Latin spelling of Cyrillic text, so "Кино" and "Kino" can be recognised as the same artist. */
    fun latin(value: String): String = normalized(value).map { cyrillic[it] ?: it.toString() }.joinToString("")
        .replace("kh", "h").replace("iy", "i").replace("yi", "i").replace("ii", "i")
    private fun looseLatin(word: String) = latin(word).replace("kh", "h").replace("j", "i").replace("y", "i").replace("ks", "x")

    private fun words(value: String) = normalized(value).split(" ").filter { it.isNotBlank() }.toSet()
    private fun similarity(a: String, b: String): Double {
        val left = words(a)
        val right = words(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val direct = 2.0 * left.intersect(right).size / (left.size + right.size)
        val l2 = left.map(::looseLatin).toSet()
        val r2 = right.map(::looseLatin).toSet()
        val transliterated = 2.0 * l2.intersect(r2).size / (l2.size + r2.size)
        return maxOf(direct, transliterated)
    }
    fun titleSimilarity(wanted: String, candidate: String): Double {
        val requested = normalized(cleanTitle(wanted))
        val offered = normalized(cleanTitle(candidate))
        val contained = requested.isNotBlank() && (" $offered ".contains(" $requested ") || " ${looseLatin(offered)} ".contains(" ${looseLatin(requested)} "))
        return maxOf(similarity(cleanTitle(wanted), cleanTitle(candidate)), if (contained) 0.95 else 0.0)
    }
    fun artistSimilarity(wanted: String, candidate: String): Double {
        val names = artistNames(wanted).ifEmpty { listOf(wanted) }
        return maxOf(similarity(wanted, candidate), names.maxOf { similarity(it, candidate) })
    }

    fun score(wanted: Track, candidate: Track): Double {
        val title = titleSimilarity(wanted.title, candidate.title)
        val artist = maxOf(artistSimilarity(wanted.artist, candidate.artist), similarity(wanted.artist, candidate.title).coerceAtMost(0.9))
        val duration = if (wanted.durationMs > 0 && candidate.durationMs > 0) (1.0 - abs(wanted.durationMs - candidate.durationMs) / 45_000.0).coerceAtLeast(0.0) else 0.5
        val wantedVersions = versionSet(wanted.title)
        val offeredVersions = versionSet(candidate.title)
        val unwanted = (offeredVersions - wantedVersions).size
        val missingVersion = (wantedVersions - offeredVersions).size
        return title * 0.60 + artist * 0.27 + duration * 0.13 - unwanted * 0.23 - missingVersion * 0.35
    }
    private fun versionSet(title: String): Set<String> = versions.filter { Regex("\\b$it\\b").containsMatchIn(normalized(title)) }.toSet()
    private val artistNoise = setOf("the", "and", "feat", "with", "ft", "topic", "music", "record", "records", "sound", "official")
    private fun artistWords(value: String): Set<String> = normalized(value).split(" ").filter { it.length >= 2 && it !in artistNoise }
        .flatMap { listOf(it, looseLatin(it)) }.toSet()
    fun sameArtist(wanted: Track, candidate: Track): Boolean {
        val artists = artistWords(wanted.artist)
        val evidence = artistWords("${candidate.artist} ${candidate.title}")
        return artists.isNotEmpty() && artists.intersect(evidence).isNotEmpty()
    }
    /** All acceptable candidates, best first. */
    fun ranked(wanted: Track, candidates: List<Track>, minimum: Double = 0.55): List<Track> {
        val wantedVersions = versionSet(wanted.title)
        return candidates.distinctBy { it.id }
            .filter { sameArtist(wanted, it) && wantedVersions == versionSet(it.title) }
            .map { it to score(wanted, it) }
            .filter { it.second >= minimum }
            .sortedByDescending { it.second }
            .map { it.first }
    }
    fun best(wanted: Track, candidates: List<Track>): Track? = ranked(wanted, candidates).firstOrNull()
}
