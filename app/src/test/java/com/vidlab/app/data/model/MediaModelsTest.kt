package com.vidlab.app.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaModelsTest {

    @Test
    fun `totalEpisodesCount sums episodes across all seasons`() {
        val dummyFile = MediaFile(name = "ep.mkv", path = "/shows/ep.mkv", isDirectory = false, isVideo = true)
        val item = ScrapedMediaItem(
            id = "show-1",
            title = "Test Show",
            isSeries = true,
            primaryFile = dummyFile,
            seasons = listOf(
                SeasonGroup(
                    seasonNumber = 1,
                    episodes = listOf(
                        ProcessedEpisode(dummyFile, 1, 1, "Ep 1"),
                        ProcessedEpisode(dummyFile, 1, 2, "Ep 2")
                    )
                ),
                SeasonGroup(
                    seasonNumber = 2,
                    episodes = listOf(
                        ProcessedEpisode(dummyFile, 2, 1, "Ep 1")
                    )
                )
            )
        )
        assertEquals(3, item.totalEpisodesCount)
    }

    @Test
    fun `ScrapedMediaItem serializes and deserializes cleanly`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = ScrapedMediaItem(
            id = "movie-42",
            title = "Inception",
            releaseYear = "2010",
            isSeries = false,
            primaryFile = MediaFile(
                resolution = "1080p",
                codec = "HEVC",
                name = "Inception.2010.mkv",
                path = "/movies/Inception.2010.mkv",
                isDirectory = false,
                size = 4_500_000_000L,
                isVideo = true
            ),
            metadata = MediaMetadata(
                title = "Inception",
                genres = listOf("Sci-Fi", "Action"),
                rating = 8.8
            )
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ScrapedMediaItem>(encoded)
        assertEquals(original, decoded)
    }
}
