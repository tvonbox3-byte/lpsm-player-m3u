package com.lpsm.player.data

import com.lpsm.player.model.ContentType
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class M3uParserTest {
    @Test fun splitCatalogDoesNotSpendCapacityOnOtherApp() {
        val source = buildString {
            appendLine("#EXTM3U")
            repeat(500) { appendLine("#EXTINF:-1 group-title=\"TV\",Canal $it\nhttps://example.com/live/$it.m3u8") }
            repeat(30) { appendLine("#EXTINF:-1 group-title=\"Filmes\",Filme $it\nhttps://example.com/movie/$it.mp4") }
            repeat(30) { appendLine("#EXTINF:-1 group-title=\"Series\",Serie S01E$it\nhttps://example.com/series/$it.mp4") }
        }
        val live = M3uParser.parse(StringReader(source), 50, allowedTypes = setOf(ContentType.LIVE))
        val cinema = M3uParser.parse(StringReader(source), 50, allowedTypes = setOf(ContentType.VOD, ContentType.SERIES))
        assertEquals(50, live.size)
        assertTrue(live.all { it.type == ContentType.LIVE })
        assertEquals(50, cinema.size)
        assertTrue(cinema.none { it.type == ContentType.LIVE })
        assertTrue(cinema.any { it.type == ContentType.VOD })
        assertTrue(cinema.any { it.type == ContentType.SERIES })
    }
    @Test fun separatesLiveMoviesSeriesAndKeepsNamesWithCommas() {
        val source = """
            #EXTM3U
            #EXTINF:-1 tvg-id="one" group-title="TV",Canal, Cultura
            https://example.com/live/1.m3u8
            #EXTINF:-1 group-title="Filmes",Filme autorizado
            https://example.com/movie/2.mp4
            #EXTINF:-1 group-title="Series",Minha serie S01E02
            https://example.com/series/3.mp4
        """.trimIndent()
        val items = M3uParser.parse(StringReader(source))
        assertEquals(3, items.size)
        assertEquals(ContentType.LIVE, items[0].type)
        assertEquals("Canal, Cultura", items[0].name)
        assertEquals(ContentType.VOD, items[1].type)
        assertEquals(ContentType.SERIES, items[2].type)
        assertEquals(1, items[2].season)
        assertEquals(2, items[2].episode)
    }
    @Test fun quotaStillIncludesMoviesAndSeriesAfterThousandsOfLiveEntries() {
        val source = buildString {
            appendLine("#EXTM3U")
            repeat(2000) { appendLine("#EXTINF:-1 group-title=\"TV\",Canal $it\nhttps://example.com/live/$it.m3u8") }
            repeat(200) { appendLine("#EXTINF:-1 group-title=\"Filmes\",Filme $it\nhttps://example.com/movie/$it.mp4") }
            repeat(200) { appendLine("#EXTINF:-1 group-title=\"Series\",Serie S01E$it\nhttps://example.com/series/$it.mp4") }
        }
        val items = M3uParser.parse(StringReader(source),100)
        assertTrue(items.size <= 100)
        ContentType.entries.forEach { type -> assertTrue("Missing $type",items.any { it.type == type }) }
    }
}
