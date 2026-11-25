package com.vktracks.player.data.api

/**
 * Общие правила очистки, нормализации и сопоставления метаданных треков и артистов
 * (используется в YandexMusicApiClient, ItunesApiClient, LrclibApiClient и VkApiClient).
 */
object TrackMetadataMatcher {

    val JUNK_KEYWORDS = listOf(
        "karaoke", "tribute", "cover version", "in the style of",
        "originally performed by", "sunfly", "backing track",
        "various artists", "разные артисты", "hits 20", "best of 20",
        "top 100", "soundtrack", "instrumental version", "piano version",
        "acoustic cover", "bbq", "bbq soundtrack", "baroque", "all-baroque",
        "compilation", "greatest hits", "100 hits", "party hits",
        "cover band", "tribute band", "studio group", "workout hits", "fitness"
    )

    private val GENERIC_ARTISTS = setOf(
        "unknown artist", "неизвестный исполнитель", "неизвестный", "unknown",
        "various artists", "разные артисты", "разные исполнители", ""
    )

    private val GENERIC_TITLES = setOf(
        "untitled", "без названия", "аудиозапись", "track", "audio", ""
    )

    private val GENERIC_NUMBERED_REGEX = Regex("^(track|audio|аудиозапись|untitled)?\\s*\\d+$", RegexOption.IGNORE_CASE)
    private val SPLIT_ARTISTS_REGEX = Regex("[,&/]|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bwith\\b|\\bx\\b", RegexOption.IGNORE_CASE)
    private val VK_TAG_REGEX = Regex("\\[vk\\.com/.*?\\]", RegexOption.IGNORE_CASE)
    private val FEAT_PARENS_REGEX = Regex("\\((feat|ft|with).*?\\)", RegexOption.IGNORE_CASE)
    private val FEAT_BRACKETS_REGEX = Regex("\\[(feat|ft|with).*?\\]", RegexOption.IGNORE_CASE)
    private val EDITION_PARENS_REGEX = Regex("\\((official\\s*(video|audio|music\\s*video)|audio|video|клип|премьера).*?\\)", RegexOption.IGNORE_CASE)
    private val EDITION_BRACKETS_REGEX = Regex("\\[(official\\s*(video|audio|music\\s*video)|audio|video|клип|премьера).*?\\]", RegexOption.IGNORE_CASE)
    private val REMASTER_PARENS_REGEX = Regex("\\((remastered|remaster|deluxe|bonus\\s*track).*?\\)", RegexOption.IGNORE_CASE)
    private val REMASTER_BRACKETS_REGEX = Regex("\\[(remastered|remaster|deluxe|bonus\\s*track).*?\\]", RegexOption.IGNORE_CASE)
    private val YEAR_PARENS_REGEX = Regex("\\(\\d{4}\\)")
    private val YEAR_BRACKETS_REGEX = Regex("\\[\\d{4}\\]")
    private val NON_ALNUM_REGEX = Regex("[^a-zа-я0-9\\s]")
    private val BRACKETS_TO_SPACE_REGEX = Regex("[()\\[\\]]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    fun isGeneric(artist: String, title: String): Boolean {
        val a = artist.trim().lowercase()
        val t = title.trim().lowercase()
        if (a in GENERIC_ARTISTS || t in GENERIC_TITLES) return true
        if (t.matches(GENERIC_NUMBERED_REGEX)) return true
        return false
    }

    fun containsJunkKeyword(combinedLowerText: String): Boolean {
        return JUNK_KEYWORDS.any { combinedLowerText.contains(it) }
    }

    fun extractMainArtist(artist: String): String {
        return artist.split(SPLIT_ARTISTS_REGEX).firstOrNull()?.trim() ?: artist.trim()
    }

    fun splitArtists(artist: String): List<String> {
        return artist.split(SPLIT_ARTISTS_REGEX)
            .map { normalizeArtistName(it) }
            .filter { it.isNotEmpty() }
    }

    fun cleanTrackTitle(title: String, stripEditionTags: Boolean = false): String {
        var result = title
            .replace(VK_TAG_REGEX, "")
            .replace(FEAT_PARENS_REGEX, "")
            .replace(FEAT_BRACKETS_REGEX, "")
        if (stripEditionTags) {
            result = result
                .replace(EDITION_PARENS_REGEX, "")
                .replace(EDITION_BRACKETS_REGEX, "")
                .replace(REMASTER_PARENS_REGEX, "")
                .replace(REMASTER_BRACKETS_REGEX, "")
        }
        return result
            .replace(YEAR_PARENS_REGEX, "")
            .replace(YEAR_BRACKETS_REGEX, "")
            .trim()
    }

    fun normalizeArtistName(name: String): String {
        return name.lowercase()
            .replace(NON_ALNUM_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    fun normalizeTitle(title: String): String {
        return cleanTrackTitle(title).lowercase()
            .replace(BRACKETS_TO_SPACE_REGEX, " ")
            .replace(NON_ALNUM_REGEX, "")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
    }

    fun isArtistExactMatch(rawFoundArtist: String, cleanArtist: String, trackArtists: List<String>): Boolean {
        val normFound = normalizeArtistName(rawFoundArtist)
        val normClean = normalizeArtistName(cleanArtist)
        if (normFound.isEmpty() || normClean.isEmpty()) return false
        if (normFound == normClean || trackArtists.contains(normFound)) {
            return true
        }
        val foundTokens = splitArtists(rawFoundArtist)
        for (token in foundTokens) {
            if (token == normClean || trackArtists.contains(token)) {
                return true
            }
        }
        return false
    }

    fun isTitleExactMatch(rawFoundTitle: String, rawCleanTitle: String): Boolean {
        val normFound = normalizeTitle(rawFoundTitle)
        val normClean = normalizeTitle(rawCleanTitle)
        if (normFound.isEmpty() || normClean.isEmpty()) return false
        if (normFound == normClean) return true
        if (normFound.startsWith("$normClean ") || normFound.startsWith("$normClean(")) return true
        if (normClean.startsWith("$normFound ") || normClean.startsWith("$normFound(")) return true
        return false
    }
}
