package com.vidlab.app.data.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameSanitizerTest {

    @Test
    fun `parses standard SxxExx series filename`() {
        val result = FilenameSanitizer.parse("Breaking.Bad.S01E01.1080p.BluRay.x264-GROUP.mkv")
        assertEquals("Breaking Bad", result.cleanTitle)
        assertEquals("breakingbad", result.seriesKey)
        assertEquals(1, result.season)
        assertEquals(1, result.episode)
        assertTrue(result.isSeries)
        assertFalse(result.isAbsoluteEpisode)
    }

    @Test
    fun `extracts existing episode title after SxxExx`() {
        val result = FilenameSanitizer.parse("Dark.S01E01.Secrets.mkv")
        assertEquals("Dark", result.cleanTitle)
        assertEquals(1, result.season)
        assertEquals(1, result.episode)
        assertEquals("Secrets", result.existingEpisodeTitle)
    }

    @Test
    fun `parses anime dash episode format and strips release brackets`() {
        val result = FilenameSanitizer.parse("[SubsPlease] Frieren - Beyond Journey's End - 28 (1080p) [ABC12345].mkv")
        assertEquals("Frieren Beyond Journey's End", result.cleanTitle)
        assertEquals("frierenbeyondjourneysend", result.seriesKey)
        assertEquals(1, result.season)
        assertEquals(28, result.episode)
        assertTrue(result.isSeries)
    }

    @Test
    fun `parses 2x12 season-episode notation`() {
        val result = FilenameSanitizer.parse("Attack.on.Titan.2x12.1080p.WEB-DL.mkv")
        assertEquals("Attack on Titan", result.cleanTitle)
        assertEquals(2, result.season)
        assertEquals(12, result.episode)
        assertTrue(result.isSeries)
    }

    @Test
    fun `flags high episode numbers in later seasons as absolute`() {
        val result = FilenameSanitizer.parse("One.Piece.S02E105.720p.mkv")
        assertEquals("One Piece", result.cleanTitle)
        assertEquals(2, result.season)
        assertEquals(105, result.episode)
        assertTrue(result.isAbsoluteEpisode)
    }

    @Test
    fun `parses standalone Season and Ep keywords`() {
        val result = FilenameSanitizer.parse("Solo Leveling Season 2 Ep 04 1080p.mkv")
        assertEquals("Solo Leveling", result.cleanTitle)
        assertEquals(2, result.season)
        assertEquals(4, result.episode)
        assertTrue(result.isSeries)
    }

    @Test
    fun `parses movie filename with year and release noise`() {
        val result = FilenameSanitizer.parse("The.Matrix.1999.2160p.UHD.BluRay.x265.10bit.HDR.Atmos-GROUP.mkv")
        assertEquals("The Matrix", result.cleanTitle)
        assertEquals("thematrix", result.seriesKey)
        assertEquals("1999", result.year)
        assertFalse(result.isSeries)
        assertNull(result.episode)
    }

    @Test
    fun `forceMovie ignores accidental episode tags`() {
        val result = FilenameSanitizer.parse(
            "Dune.Part.Two.2024.S01E01.FakeTag.1080p.WEB-DL.mkv",
            forceMovie = true
        )
        assertEquals("Dune Part Two", result.cleanTitle)
        assertEquals("2024", result.year)
        assertFalse(result.isSeries)
        assertNull(result.episode)
        assertNull(result.existingEpisodeTitle)
    }
}
