package com.vidlab.app.data.scraper

data class ParsedMediaInfo(
    val cleanTitle: String,
    val seriesKey: String,
    val season: Int = 1,
    val episode: Int? = null,
    val year: String? = null,
    val isSeries: Boolean = false,
    val isAbsoluteEpisode: Boolean = false,
    val existingEpisodeTitle: String? = null
)

object FilenameSanitizer {
    private val bracketsPattern = Regex("""\[[^\]]*\]|\([^\)]*\)""", RegexOption.IGNORE_CASE)
    private val mediaExtPattern = Regex("""(?i)\.(mkv|mp4|avi|webm|mov|m4v|ts|flv|wmv|srt|ass|vtt|sub|nfo|part)$""")
    private val noisePattern = Regex(
        """(?i)\b(1080p|720p|480p|2160p|4k|uhd|hdr|dv|dovi|imax|remux|web-?dl|webrip|bluray|bdrip|brrip|dvdrip|hdtv|x264|x265|hevc|avc|10bit|8bit|aac|ac3|eac3|dts(?:-hd)?|truehd|atmos|ddp?\s*5\.?1|dd\s*5\.?1|5\.1|7\.1|2\.0|multi(?:ple)?|repack|proper|amzn|nf|hbo|cr|jbent|eztv|rarbg|psa|bone|hin(?:di)?|tam(?:il)?|tel(?:ugu)?|kan(?:nada)?|eng(?:lish)?|jap(?:anese)?|org|line|esubs?|subtitles?|subs?|4khdhub|absenth?|absinth|hdhub4u|moviesdrives(?:\.bid)?|bid|tv|com|nano-lumix|lumix|yt|opus|tv\d{4}|movie\d{4})\b"""
    )
    private val standardSeasonEpPattern = Regex("""(?i)\b(?:s(\d{1,2})[ ._-]*e(\d{1,3})|(\d{1,2})x(\d{1,3}))(?:v\d+)?\b""")
    private val ordinalSeasonEpPattern = Regex("""(?i)\b(?:(\d{1,2})(?:st|nd|rd|th)\s+season|season\s+0*(\d{1,2}))(?:\s*-\s*|\s+(?:ep(?:isode)?\.?\s*|e)?)(?:0*(\d{1,3})(?:\s*v\d+)?)?\b""")
    private val standaloneSeasonPattern = Regex("""(?i)\b(?:season|s)\.?\s*0*(\d{1,2})\b""")
    private val parenYearPattern = Regex("""\((19\d{2}|20\d{2})\)""")
    private val yearPattern = Regex("""\b(19\d{2}|20\d{2})\b""")

    fun parse(rawInput: String, forceMovie: Boolean = false): ParsedMediaInfo {
        val baseName = rawInput.replace(mediaExtPattern, "")
        var text = baseName.replace(Regex("""(?i)(tv|movie)\d{4}"""), " ")

        var year: String? = null
        val parenYear = parenYearPattern.findAll(text).lastOrNull()
        if (parenYear != null) {
            year = parenYear.groupValues[1]
            text = text.removeRange(parenYear.range)
        } else {
            val years = yearPattern.findAll(text).toList()
            val chosen = when {
                years.size >= 2 -> years.last()
                years.size == 1 && years[0].range.first > 0 -> years[0]
                else -> null
            }
            if (chosen != null) {
                year = chosen.groupValues[1]
                text = text.removeRange(chosen.range)
            }
        }

        var existingTitle: String? = null
        val epMatch = standardSeasonEpPattern.find(text)
        if (epMatch != null && !forceMovie) {
            val afterEp = text.substring(epMatch.range.last + 1).trim()
            val cleanedAfter = noisePattern.replace(bracketsPattern.replace(afterEp, " "), " ")
                .replace(Regex("""^[ ._-]+"""), "").trim()
            if (cleanedAfter.length > 2) existingTitle = cleanedAfter
        }

        val preStripText = text
        val animeDashOrBracketEp = Regex(
            """(?:\s-\s|\[\s*)0*(\d{1,3})(?:\s*v\d+)?(?=\s*(?:\]|\[|\(|1080p|720p|480p|2160p|web|bluray|bdrip|x264|x265|hevc|$))""",
            RegexOption.IGNORE_CASE
        ).find(preStripText)
        val standaloneEpBeforeStrip = Regex("""(?i)\b(?:ep(?:isode)?\.?\s*|e)0*(\d{1,3})\b""").find(preStripText)

        text = bracketsPattern.replace(text, " ")
        text = noisePattern.replace(text, " ")
        text = text.replace(Regex("""-[A-Za-z0-9]{2,15}$"""), " ")
        text = text.replace(Regex("""[._\-]"""), " ")

        var season = 1
        var episode: Int? = null
        var isSeries = false
        var isAbsolute = false

        if (forceMovie) {
            if (epMatch != null) text = text.substring(0, epMatch.range.first.coerceAtMost(text.length))
            text = text.replace(Regex("""(?i)\b(?:s\d{1,2}e\d{1,3}|\d{1,2}x\d{1,3}|s\d{1,2})\b"""), " ")
            text = noisePattern.replace(text, " ")
            val clean = text.replace(Regex("""\s+"""), " ").trim()
            return ParsedMediaInfo(clean.ifEmpty { baseName }, clean.lowercase().replace(Regex("""[^a-z0-9]"""), ""), 1, null, year, false, false, null)
        }

        val ordMatch = ordinalSeasonEpPattern.find(text)
        if (epMatch != null) {
            isSeries = true
            val sStr = epMatch.groupValues[1].ifEmpty { epMatch.groupValues[3] }
            val eStr = epMatch.groupValues[2].ifEmpty { epMatch.groupValues[4] }
            if (sStr.isNotBlank()) season = sStr.toIntOrNull() ?: 1
            if (eStr.isNotBlank()) {
                val epVal = eStr.toIntOrNull()
                if (epVal != null && epVal >= 100 && season > 1) isAbsolute = true
                episode = epVal
            }
            text = text.substring(0, epMatch.range.first.coerceAtMost(text.length))
        } else if (ordMatch != null) {
            isSeries = true
            val sStr = ordMatch.groupValues[1].ifEmpty { ordMatch.groupValues[2] }
            if (sStr.isNotBlank()) season = sStr.toIntOrNull() ?: 1
            if (ordMatch.groupValues[3].isNotBlank()) episode = ordMatch.groupValues[3].toIntOrNull()
            text = text.substring(0, ordMatch.range.first.coerceAtMost(text.length))
        } else {
            val sMatch = standaloneSeasonPattern.find(text)
            if (sMatch != null) {
                isSeries = true
                season = sMatch.groupValues[1].toIntOrNull() ?: 1
                val epAfter = Regex("^(?:ep?|episode)?\\s*0*(\\d{1,3})\\b", RegexOption.IGNORE_CASE).find(text.substring(sMatch.range.last + 1).trim())
                if (epAfter != null) episode = epAfter.groupValues[1].toIntOrNull()
                text = text.substring(0, sMatch.range.first)
            } else if (standaloneEpBeforeStrip != null) {
                isSeries = true
                episode = standaloneEpBeforeStrip.groupValues[1].toIntOrNull()
                val cutIdx = standaloneEpBeforeStrip.range.first.coerceAtMost(text.length)
                text = text.substring(0, cutIdx)
            } else if (animeDashOrBracketEp != null) {
                isSeries = true
                episode = animeDashOrBracketEp.groupValues[1].toIntOrNull()
                val cleanedPrefix = noisePattern.replace(bracketsPattern.replace(preStripText.substring(0, animeDashOrBracketEp.range.first), " "), " ").replace(Regex("""[._\-]"""), " ")
                if (cleanedPrefix.isNotBlank()) text = cleanedPrefix
            } else {
                val explicitEp = Regex("(?i)\\b(?:ep|episode|e)\\s*0*(\\d{1,3})\\b").find(text)
                if (explicitEp != null) {
                    isSeries = true
                    episode = explicitEp.groupValues[1].toIntOrNull()
                    text = text.substring(0, explicitEp.range.first)
                } else {
                    val bareNumEp = Regex("^0*(\\d{1,3})(?:\\s*v\\d+)?$", RegexOption.IGNORE_CASE).find(text.trim())
                    val trailingEp = Regex("^(.{3,}?)\\s+0*(\\d{1,3})(?:\\s+v\\d+)?$", RegexOption.IGNORE_CASE).find(text.trim())
                    if (bareNumEp != null) {
                        val epVal = bareNumEp.groupValues[1].toIntOrNull()
                        if (epVal != null && epVal in 1..999) {
                            isSeries = true
                            episode = epVal
                            text = ""
                        }
                    } else if (trailingEp != null) {
                        val epVal = trailingEp.groupValues[2].toIntOrNull()
                        if (epVal != null && epVal in 1..999 && trailingEp.groupValues[1].trim().length >= 3) {
                            isSeries = true
                            episode = epVal
                            text = trailingEp.groupValues[1].trim()
                        }
                    }                    }
                }
            }
        }

        text = noisePattern.replace(text, " ").replace(Regex("""\b(5\s*1|7\s*1|2\s*0)\b"""), " ")
        val cleanTitle = text.replace(Regex("""\s+"""), " ").trim().let { if (it.isBlank() && !isSeries) baseName else it }
        return ParsedMediaInfo(cleanTitle, cleanTitle.lowercase().replace(Regex("""[^a-z0-9]"""), ""), season, episode, year, isSeries, isAbsolute, existingTitle)
    }
}
