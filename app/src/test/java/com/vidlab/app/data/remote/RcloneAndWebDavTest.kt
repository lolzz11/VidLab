package com.vidlab.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RcloneAndWebDavTest {

    @Test
    fun parseRemotesFromConf_extractsSectionsAndTypes() {
        val sampleConf = """
            # User rclone configuration
            [gdrive]
            type = drive
            scope = drive.file

            [decrypted]
            type = crypt
            remote = gdrive:EncryptedVault

            [vault]
            type = combine
            upstreams = EncryptedVault=gdrive:EncryptedVault

            [_internal_cache]
            type = local
        """.trimIndent()

        assertEquals(
            listOf("gdrive" to "drive", "decrypted" to "crypt", "vault" to "combine"),
            RcloneEngine.parseRemotesFromConf(sampleConf)
        )
    }

    @Test
    fun parseRemotesFromConf_defaultsMissingTypeAndIgnoresMalformedHeaders() {
        val sampleConf = """
            []
            orphan_key = value
            [custom_remote]
            url = https://example.com/dav
        """.trimIndent()

        assertEquals(listOf("custom_remote" to "remote"), RcloneEngine.parseRemotesFromConf(sampleConf))
    }

    @Test
    fun webDavPathEscaping_preservesLiteralPlusAndEncodesSpaces() {
        val client = WebDavClient()
        val rawPath = "/VidLab/Shows/C++ & Anime [1080p+]/Ep 01.mkv"
        val encoded = client.escapeUrlPath(rawPath)

        assertEquals("/VidLab/Shows/C%2B%2B%20%26%20Anime%20%5B1080p%2B%5D/Ep%2001.mkv", encoded)
        assertEquals(rawPath, client.decodeUrlPath(encoded))
    }

    @Test
    fun parseMultiStatus_parsesFoldersVideosAndSkipsSelfHref() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/VidLab/Anime/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/VidLab/Anime/Frieren%20S01/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>http://127.0.0.1:18080/VidLab/Anime/Movie%20%2B%20Special.mkv</D:href>
                <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>2147483648</D:getcontentlength></D:prop></D:propstat>
              </D:response>
              <D:response>
                <D:href>/VidLab/Anime/Movie%20%2B%20Special.srt</D:href>
                <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>42100</D:getcontentlength></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val items = WebDavClient().parseMultiStatus(xml, "/VidLab/Anime")
        assertEquals(3, items.size)
        assertTrue(items[0].isDirectory)
        assertEquals("Frieren S01", items[0].name)
        assertTrue(items[1].isVideo)
        assertEquals("Movie + Special.mkv", items[1].name)
        assertEquals(2147483648L, items[1].size)
        assertFalse(items[2].isVideo)
        assertEquals("Movie + Special.srt", items[2].name)
    }
}
