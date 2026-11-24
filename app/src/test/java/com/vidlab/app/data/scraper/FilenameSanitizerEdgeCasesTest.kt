package com.vidlab.app.data.scraper

import org.junit.Assert.*
import org.junit.Test

class FilenameSanitizerEdgeCasesTest {
    @Test
    fun parsesNumericAndDoubleYearMovies() {
        val m1 = FilenameSanitizer.parse("1917 (2019) [720p] [WEBRip] [AV1].mp4")
        assertEquals("1917", m1.cleanTitle)
        assertEquals("2019", m1.year)

        val m2 = FilenameSanitizer.parse("Blade.Runner.2049.2017.1080p.BluRay.x265.10bit.mkv")
        assertEquals("Blade Runner 2049", m2.cleanTitle)
        assertEquals("2017", m2.year)

        val m3 = FilenameSanitizer.parse("2001.A.Space.Odyssey.1968.2160p.UHD.BluRay.REMUX.mkv")
        assertEquals("2001 A Space Odyssey", m3.cleanTitle)
        assertEquals("1968", m3.year)
    }

    @Test
    fun parsesVersionedOrdinalAndBareEpisodes() {
        val f = FilenameSanitizer.parse("[Judas] Frieren - Beyond Journey's End - S01E02v2 [1080p][HEVC x265 10bit].mkv")
        assertEquals("Frieren Beyond Journey's End", f.cleanTitle)
        assertEquals(1, f.season)
        assertEquals(2, f.episode)

        val jjk = FilenameSanitizer.parse("[Erai-raws] Jujutsu Kaisen 2nd Season - 05 [720p][Multiple Subtitle].mkv")
        assertEquals("Jujutsu Kaisen", jjk.cleanTitle)
        assertEquals(2, jjk.season)
        assertEquals(5, jjk.episode)

        val bare = FilenameSanitizer.parse("Episode 03.mkv")
        assertEquals("", bare.cleanTitle)
        assertEquals(3, bare.episode)
    }
}
