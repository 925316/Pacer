package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The tile vectors here are not invented: they are the tiles the three services actually
 * served for these places, checked by fetching the images. That is the only way this file
 * can catch a projection that is merely self-consistent - a round trip through the same
 * broken formula passes every test and still puts the route on the wrong street.
 */
class TileGridTest {

    private val beijing = GeoPoint(39.9042, 116.4074)
    private val shanghai = GeoPoint(31.2304, 121.4737)
    private val guangzhou = GeoPoint(23.1291, 113.2644)
    private val moscow = GeoPoint(55.7558, 37.6173)

    @Test
    fun `web mercator puts Beijing and Moscow on their real tiles`() {
        val grid = WebMercatorGrid("Google")
        val bj = tileOf(grid, beijing)
        assertEquals(3372, bj.x)
        assertEquals(1552, bj.y)
        // Inside the tile, not on its corner: 117.1 px from the left, 21.0 px from the top.
        assertEquals(117.13, bj.insideX, 0.05)
        assertEquals(21.02, bj.insideY, 0.05)
        assertEquals(2476, tileOf(grid, moscow).x)
        assertEquals(1280, tileOf(grid, moscow).y)
    }

    @Test
    fun `web mercator round trips to well under a pixel`() {
        val grid = WebMercatorGrid("Google")
        for (p in listOf(beijing, shanghai, guangzhou, moscow)) {
            for (z in listOf(3, 10, 12, 18, 19)) {
                val back = grid.point(grid.pixel(p, z), z)
                assertTrue("z=$z p=$p back=$back", abs(back.lat - p.lat) < 1e-7)
                assertTrue("z=$z p=$p back=$back", abs(back.lon - p.lon) < 1e-7)
            }
        }
    }

    @Test
    fun `the equator and the date line are the grid edges`() {
        val grid = WebMercatorGrid("Google")
        val origin = grid.pixel(GeoPoint(0.0, -180.0), 0)
        assertEquals(0.0, origin.x, 1e-6)
        assertEquals(TileGrid.TILE_SIZE / 2.0, origin.y, 1e-6)
        val far = grid.pixel(GeoPoint(0.0, 180.0), 1)
        assertEquals(TileGrid.TILE_SIZE * 2.0, far.x, 1e-6)
    }

    @Test
    fun `gcj grid shifts a point inside China by the expected offset`() {
        val gcj = GcjTileGrid()
        // The offset in China is roughly 500 m north-east; at zoom 18 that is hundreds of
        // pixels, so the pixel must differ from the plain Mercator one by a lot.
        val plain = WebMercatorGrid("Google").pixel(beijing, 18)
        val shifted = gcj.pixel(beijing, 18)
        val dx = abs(shifted.x - plain.x)
        val dy = abs(shifted.y - plain.y)
        assertTrue("dx=$dx dy=$dy", dx > 50 && dy > 50)
        // ...and at zoom 12 both land on the same tile, which is why a low zoom hides the
        // bug: the offset is real but smaller than a tile there.
        assertEquals(3372, tileOf(gcj, beijing).x)
        assertEquals(1552, tileOf(gcj, beijing).y)
    }

    @Test
    fun `gcj grid leaves a point outside China alone`() {
        val gcj = GcjTileGrid()
        val plain = WebMercatorGrid("Google").pixel(moscow, 16)
        val shifted = gcj.pixel(moscow, 16)
        assertEquals(plain.x, shifted.x, 1e-9)
        assertEquals(plain.y, shifted.y, 1e-9)
    }

    @Test
    fun `gcj grid round trips through the datum`() {
        val grid = GcjTileGrid()
        for (p in listOf(beijing, shanghai, guangzhou, moscow)) {
            val back = grid.point(grid.pixel(p, 18), 18)
            assertTrue(
                "p=$p back=$back offset=${GeoMath.haversine(p, back)}",
                GeoMath.haversine(p, back) < 1.0,
            )
        }
    }

    @Test
    fun `baidu puts its three cities on the tiles the service served`() {
        // Checked by fetching these very tiles: each one came back as a real 256x256 PNG
        // (a wrong index returns the 208-byte blank placeholder). Beijing is 791 because
        // its MC x is 12 959 961 m, and 12959961 / 16384 = 791.01.
        val grid = Bd09Grid()
        assertEquals(791, tileOf(grid, beijing).x)
        assertEquals(294, tileOf(grid, beijing).y)
        assertEquals(825, tileOf(grid, shanghai).x)
        assertEquals(222, tileOf(grid, shanghai).y)
        assertEquals(769, tileOf(grid, guangzhou).x)
        assertEquals(160, tileOf(grid, guangzhou).y)
    }

    @Test
    fun `baidu round trips to well under a metre`() {
        val grid = Bd09Grid()
        for (p in listOf(beijing, shanghai, guangzhou, moscow)) {
            for (z in listOf(10, 12, 15, 18)) {
                val pixel = grid.pixel(p, z)
                val back = grid.point(pixel, z)
                assertTrue(
                    "z=$z p=$p back=$back offset=${GeoMath.haversine(p, back)}",
                    GeoMath.haversine(p, back) < 0.5,
                )
            }
        }
    }

    @Test
    fun `baidu grows y toward the north, so its tile index is negated`() {
        val grid = Bd09Grid()
        val south = grid.pixel(GeoPoint(20.0, 110.0), 12)
        val north = grid.pixel(GeoPoint(45.0, 110.0), 12)
        // A point further north has a SMALLER pixel y (screen order), which is the whole
        // reason the Baidu tile index is -floor(mcY / tile). In TILE space the north point
        // therefore gets the larger index, because tile y counts the other way.
        assertTrue("south=$south north=$north", north.y < south.y)
        assertTrue(tileOf(grid, GeoPoint(45.0, 110.0)).y > tileOf(grid, GeoPoint(20.0, 110.0)).y)
    }

    @Test
    fun `meters to pixels shrinks as the zoom grows`() {
        for (grid in listOf(WebMercatorGrid("Google"), GcjTileGrid(), Bd09Grid())) {
            val wide = grid.metersToPixels(100.0, beijing, 10)
            val close = grid.metersToPixels(100.0, beijing, 18)
            assertTrue("${grid.name}: $wide -> $close", close > wide)
        }
    }

    @Test
    fun `a hundred metres at zoom 18 is the right number of pixels`() {
        // At this latitude one pixel is ~0.46 m, so 100 m is ~218 px. The value depends on
        // latitude because Mercator stretches toward the poles; using the equatorial 0.6 m
        // would be off by a third, and forgetting the tile size would be off by 256x.
        val grid = WebMercatorGrid("Google")
        val px = grid.metersToPixels(100.0, beijing, 18)
        assertTrue("px=$px", px in 200.0..240.0)
    }

    @Test
    fun `a negative pixel floors to the tile that contains it`() {
        // Truncating toward zero instead of flooring is only wrong on the negative side, and
        // Baidu's y plane IS negative (it grows northward before being negated), so this is
        // the half of the world that a cast would quietly break.
        val ref = screenOrderTile(PixelPoint(-1.0, -257.0))
        assertEquals(-1, ref.x)
        assertEquals(-2, ref.y)
        assertEquals(255.0, ref.insideX, 1e-9)
        assertEquals(255.0, ref.insideY, 1e-9)
    }

    private fun tileOf(grid: TileGrid, point: GeoPoint): TileRef =
        grid.tile(grid.pixel(point, 12))
}
