package org.pacer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three tile services are addressed directly, with no SDK and no key, so a
 * wrong host, parameter name or layer code is invisible until someone looks at a
 * blank map. The compiler cannot check a URL and a device test may be offline;
 * pinning the exact shapes here is the only check that runs on every change.
 *
 * The shard is part of the expected string on purpose: it is derived from the
 * tile index, and a change to that derivation silently moves every tile.
 */
class MapStyleTest {

    private val x = 3372
    private val y = 1552
    private val zoom = 12

    @Test
    fun `amap addresses the road host and the imagery host separately`() {
        assertEquals(
            "https://wprd01.is.autonavi.com/appmaptile" +
                "?lang=zh_cn&size=1&style=7&x=3372&y=1552&z=12",
            MapStyle.AMAP.url(x, y, zoom, MapLayer.ROAD),
        )
        // Both the plain imagery and the hybrid base are the same JPEG; the labels
        // arrive as a second, transparent tile.
        for (layer in listOf(MapLayer.SATELLITE, MapLayer.HYBRID)) {
            assertEquals(
                "https://webst01.is.autonavi.com/appmaptile?style=6&x=3372&y=1552&z=12",
                MapStyle.AMAP.url(x, y, zoom, layer),
            )
        }
    }

    @Test
    fun `amap hybrid is the only layer with a label overlay`() {
        assertEquals(
            "https://webst01.is.autonavi.com/appmaptile?style=8&x=3372&y=1552&z=12",
            MapStyle.AMAP.overlay(x, y, zoom, MapLayer.HYBRID),
        )
        assertNull(MapStyle.AMAP.overlay(x, y, zoom, MapLayer.ROAD))
        assertNull(MapStyle.AMAP.overlay(x, y, zoom, MapLayer.SATELLITE))
    }

    @Test
    fun `baidu has roads only and keeps its own tile parameters`() {
        assertEquals(
            "https://maponline1.bdimg.com/tile/" +
                "?qt=vtile&x=791&y=294&z=12&styles=pl&scaler=1&showtext=1",
            MapStyle.BAIDU.url(791, 294, zoom, MapLayer.ROAD),
        )
        assertEquals(listOf(MapLayer.ROAD), MapStyle.BAIDU.layers)
        assertFalse(MapStyle.BAIDU.supports(MapLayer.SATELLITE))
        assertFalse(MapStyle.BAIDU.supports(MapLayer.HYBRID))
    }

    @Test
    fun `google maps each layer to its own code and needs no overlay`() {
        val expected = mapOf(
            MapLayer.ROAD to "m",
            MapLayer.SATELLITE to "s",
            MapLayer.HYBRID to "y",
        )
        for ((layer, code) in expected) {
            assertEquals(
                "https://mt0.google.com/vt/lyrs=$code&x=3372&y=1552&z=12",
                MapStyle.GOOGLE.url(x, y, zoom, layer),
            )
            assertNull(MapStyle.GOOGLE.overlay(x, y, zoom, layer))
        }
    }

    @Test
    fun `the stored style name is resolved case-insensitively and trimmed`() {
        assertEquals(MapStyle.AMAP, MapStyle.byName(" amap "))
        assertEquals(MapStyle.BAIDU, MapStyle.byName("Baidu"))
        assertNull(MapStyle.byName(""))
        assertNull(MapStyle.byName("nokia"))
    }

    @Test
    fun `every advertised layer has a working address`() {
        // Guards the case where a layer is added to `layers` but the `when` in url()
        // was not extended: the enum would offer a button that cannot fetch.
        for (style in MapStyle.entries) {
            for (layer in style.layers) {
                val url = style.url(100, 200, 10, layer)
                assertTrue("${style.label} $layer: $url", url.startsWith("https://"))
            }
        }
    }
}
