package org.pacer.motion

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The tile grid a raster map is cut into, in the map's OWN datum.
 *
 * WHY THIS IS IN THE ENGINE AND NOT IN THE MAP VIEW: a tile grid is a coordinate
 * transform, and a wrong one does not look like a bug - the map still draws, still pans,
 * still accepts taps. The result is a route that is quietly a few hundred metres from
 * where it is shown, which is indistinguishable from "the spoof is off" on a device. It is
 * small, pure arithmetic, so it is tested here and the view just calls it.
 *
 * A grid answers two questions and nothing else: which tile covers a datum coordinate, and
 * where inside that tile (in pixels) the coordinate sits. Panning, zooming, drawing and
 * hit-testing are all built on top of those two answers.
 *
 * THE DATUM IS PART OF THE GRID, NOT OF THE CALLER. A caller holds WGS-84, because that is
 * what the module writes into a fix and what a route is stored in. A grid whose tiles are
 * drawn in GCJ-02 or BD-09 therefore converts on the way in; getting that conversion
 * outside the grid is how the offset ends up applied twice or not at all.
 */
interface TileGrid {

    /** Which grid this is, for the page to say out loud. */
    val name: String

    /** Smallest and largest zoom the source is known to serve, inclusive. */
    val minZoom: Int
    val maxZoom: Int

    /**
     * The continuous pixel position of a WGS-84 coordinate at [zoom].
     *
     * Continuous, not a tile index: the whole point is that a point is somewhere INSIDE a
     * tile. Tile (tx, ty) covers pixels [tx * TILE_SIZE, (tx + 1) * TILE_SIZE).
     */
    fun pixel(point: GeoPoint, zoom: Int): PixelPoint

    /** The WGS-84 coordinate at a continuous pixel position at [zoom]. */
    fun point(pixel: PixelPoint, zoom: Int): GeoPoint

    /**
     * Scales a distance in metres to pixels at [zoom].
     *
     * A grid is not conformal everywhere (BD-09's is not even linear in latitude), so this
     * is defined by the grid rather than derived from [pixel] by the caller.
     */
    fun metersToPixels(meters: Double, point: GeoPoint, zoom: Int): Double

    /**
     * The tile covering [pixel], with the pixel's offset inside that tile.
     *
     * Part of the grid because the tile INDEX is not universal even when the projection is:
     * Baidu counts its vertical index toward the north, so its index is the negation of the
     * screen-order one. Hiding that here is what keeps the caller from having to know which
     * map it is drawing.
     */
    fun tile(pixel: PixelPoint): TileRef

    companion object {
        /** Edge of one tile, in pixels. Every raster source here uses 256. */
        const val TILE_SIZE = 256
    }
}

/** A continuous position on a grid's pixel plane; fractional parts are meaningful. */
data class PixelPoint(val x: Double, val y: Double)

/** The tile containing a pixel position, and where inside it the position sits. */
data class TileRef(val x: Int, val y: Int, val insideX: Double, val insideY: Double)

/**
 * The usual screen-order tiling: x and y both grow to the right and down.
 *
 * [floor], not a cast: a position west of the prime meridian has negative coordinates, and
 * truncation toward zero would put the tile boundary in the wrong place there - a one-tile
 * jump that only shows up on one half of the world.
 */
internal fun screenOrderTile(pixel: PixelPoint): TileRef {
    val tx = kotlin.math.floor(pixel.x / TileGrid.TILE_SIZE).toInt()
    val ty = kotlin.math.floor(pixel.y / TileGrid.TILE_SIZE).toInt()
    return TileRef(
        x = tx,
        y = ty,
        insideX = pixel.x - tx.toDouble() * TileGrid.TILE_SIZE,
        insideY = pixel.y - ty.toDouble() * TileGrid.TILE_SIZE,
    )
}

/**
 * The standard Web Mercator grid: 256-pixel tiles, 2^z tiles across the world.
 *
 * This is what Google, OpenStreetMap and every other "slippy map" serves, and it is the
 * grid AMap's raster tiles are cut into as well - only their DATUM differs, which is why
 * [GcjTileGrid] is this grid with a conversion in front of it rather than a different
 * projection.
 */
open class WebMercatorGrid(
    override val name: String,
    override val minZoom: Int = 3,
    override val maxZoom: Int = 19,
) : TileGrid {

    override fun pixel(point: GeoPoint, zoom: Int): PixelPoint {
        val world = worldSize(zoom)
        val lat = point.lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
        val x = (point.lon + 180.0) / 360.0 * world
        val s = sin(Math.toRadians(lat))
        val y = (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * world
        return PixelPoint(x, y)
    }

    override fun point(pixel: PixelPoint, zoom: Int): GeoPoint {
        val world = worldSize(zoom)
        val lon = pixel.x / world * 360.0 - 180.0
        val n = PI - 2.0 * PI * pixel.y / world
        val lat = Math.toDegrees(atan2(0.5 * (kotlin.math.exp(n) - kotlin.math.exp(-n)), 1.0))
        return GeoPoint(lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT), lon)
    }

    override fun metersToPixels(meters: Double, point: GeoPoint, zoom: Int): Double {
        // Ground resolution at this latitude, from the Mercator scale factor.
        val perPixel = EQUATOR_METERS_PER_PIXEL_AT_Z0 / 2.0.pow(zoom) *
            cos(Math.toRadians(point.lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)))
        return if (perPixel <= 0.0) 0.0 else meters / perPixel
    }

    override fun tile(pixel: PixelPoint): TileRef = screenOrderTile(pixel)

    protected fun worldSize(zoom: Int): Double =
        TileGrid.TILE_SIZE.toDouble() * 2.0.pow(zoom)

    private companion object {
        /** Mercator's latitude limit; beyond it a tile plane is unbounded. */
        const val MAX_MERCATOR_LAT = 85.05112878

        /** 156543.03392804097 m per pixel at zoom 0 on the equator, for a 256-pixel tile. */
        const val EQUATOR_METERS_PER_PIXEL_AT_Z0 = 156543.03392804097
    }
}

/**
 * AMap's grid: Web Mercator geometry, GCJ-02 datum.
 *
 * AMap's raster tiles are ordinary XYZ tiles, but the coordinates baked into them are
 * GCJ-02, so a WGS-84 point has to be shifted before it is placed on the grid. Both
 * directions go through [CoordinateTransform], which is the same transform the hook side
 * uses for the AMap location SDK.
 *
 * Outside China the transform is the identity, which is why a route in Moscow draws
 * correctly on this grid without a special case.
 */
class GcjTileGrid(
    override val minZoom: Int = 3,
    override val maxZoom: Int = 19,
) : WebMercatorGrid("AMap", minZoom, maxZoom) {

    override fun pixel(point: GeoPoint, zoom: Int): PixelPoint =
        super.pixel(CoordinateTransform.wgs84ToGcj02(point), zoom)

    override fun point(pixel: PixelPoint, zoom: Int): GeoPoint =
        CoordinateTransform.gcj02ToWgs84(super.point(pixel, zoom))
}

/**
 * Baidu's grid: BD-09 datum, and a projection that is NOT Web Mercator.
 *
 * WHY BAIDU NEEDS ITS OWN MATHS. Baidu cuts its tiles on a Mercator projection of the
 * Krasovsky 1940 ellipsoid, expressed in BD09MC "metres", and the grid is anchored at the
 * datum's origin rather than at 180 degrees west. Its tile index is therefore
 *
 *     tx = floor(mcX / tileMeters)        ty = -floor(mcY / tileMeters)
 *
 * with y growing toward the NORTH, so the vertical index is negated. Feeding such a tile
 * into a Web Mercator view draws a map that looks plausible and is a few hundred metres to
 * a kilometre off, depending on latitude - the worst possible failure, because a route
 * placed on it looks right until it is compared with the fix.
 *
 * The polynomial constants below are the published projection for each latitude band;
 * they are arithmetic, not code, and the tests pin them to tile indices observed on the
 * real service so a transcription slip cannot pass.
 */
class Bd09Grid(
    override val minZoom: Int = 3,
    override val maxZoom: Int = 19,
) : TileGrid {

    override val name: String get() = "Baidu"

    override fun pixel(point: GeoPoint, zoom: Int): PixelPoint {
        val bd = CoordinateTransform.wgs84ToBd09(point)
        val mc = toMeters(bd.lat, bd.lon)
        // Pixels of one tile: the metre size of a tile halves per zoom level, so the pixel
        // scale doubles. Baidu anchors z=18 at 1 metre per pixel.
        val perPixel = 2.0.pow(BASE_ZOOM - zoom)
        return PixelPoint(mc.x / perPixel, -mc.y / perPixel)
    }

    override fun point(pixel: PixelPoint, zoom: Int): GeoPoint {
        val perPixel = 2.0.pow(BASE_ZOOM - zoom)
        val bd = toDegrees(pixel.x * perPixel, -pixel.y * perPixel)
        return CoordinateTransform.bd09ToWgs84(bd)
    }

    override fun metersToPixels(meters: Double, point: GeoPoint, zoom: Int): Double =
        meters / 2.0.pow(BASE_ZOOM - zoom)

    /**
     * Baidu's vertical index counts toward the NORTH, while its pixel plane already grows
     * downward (that is why [pixel] negates). Undoing the negation here is what makes the
     * index match the tile the service names.
     */
    override fun tile(pixel: PixelPoint): TileRef {
        val base = screenOrderTile(PixelPoint(pixel.x, -pixel.y))
        return TileRef(base.x, base.y, base.insideX, TileGrid.TILE_SIZE - base.insideY)
    }

    /** BD-09 latitude/longitude to BD09MC metres. */
    fun toMeters(lat: Double, lon: Double): PixelPoint {
        val band = bandFor(abs(lat))
        val absLat = abs(lat)
        val x = band[0] + band[1] * abs(lon)
        val t = absLat / band[9]
        var tPow = t
        var y = band[2]
        for (i in 3..8) {
            y += band[i] * tPow
            tPow *= t
        }
        return PixelPoint(if (lon < 0) -x else x, if (lat < 0) -y else y)
    }

    /** BD09MC metres to BD-09 latitude/longitude. */
    fun toDegrees(xMeters: Double, yMeters: Double): GeoPoint {
        val band = degreeBandFor(abs(yMeters))
        val lon = band[0] + band[1] * abs(xMeters)
        val t = abs(yMeters) / band[9]
        var tPow = t
        var lat = band[2]
        for (i in 3..8) {
            lat += band[i] * tPow
            tPow *= t
        }
        return GeoPoint(if (yMeters < 0) -lat else lat, if (xMeters < 0) -lon else lon)
    }

    private fun bandFor(absLatDegrees: Double): DoubleArray = when {
        absLatDegrees > 75.0 -> LAT_BANDS[0]
        absLatDegrees > 60.0 -> LAT_BANDS[1]
        absLatDegrees > 45.0 -> LAT_BANDS[2]
        absLatDegrees > 30.0 -> LAT_BANDS[3]
        absLatDegrees > 15.0 -> LAT_BANDS[4]
        else -> LAT_BANDS[5]
    }

    private fun degreeBandFor(absMeters: Double): DoubleArray {
        for (i in METER_LIMITS.indices) {
            if (absMeters >= METER_LIMITS[i]) return DEGREE_BANDS[i]
        }
        return DEGREE_BANDS[METER_LIMITS.size - 1]
    }

    private companion object {
        /** The zoom whose pixel is one BD09MC metre. */
        const val BASE_ZOOM = 18

        /**
         * Coefficients for each latitude band, north to south in the first table. Each row
         * is (lonConstant, lonScale, then six latitude polynomial terms, bandEdgeLatitude);
         * the last entry is the band's edge, used to scale the polynomial argument.
         */
        val LAT_BANDS = arrayOf(
            doubleArrayOf(
                -0.0015702102444, 111320.7020616939, 1704480524535203.0, -10338987376042340.0,
                26112667856603880.0, -35149669176653700.0, 26595700718403920.0, -10725012454188240.0,
                1800819912950474.0, 82.5,
            ),
            doubleArrayOf(
                0.0008277824516172526, 111320.7020463578, 647795574.6671607, -4082003173.641316,
                10774905663.51142, -15171875531.51559, 12053065338.62167, -5124939663.577472,
                913311935.9512032, 67.5,
            ),
            doubleArrayOf(
                0.00337398766765, 111320.7020202162, 4481351.045890365, -23393751.19931662,
                79682215.47186455, -115964993.2797253, 97236711.15602145, -43661946.33752821,
                8477230.501135234, 52.5,
            ),
            doubleArrayOf(
                0.00220636496208, 111320.7020209128, 51751.86112841131, 3796837.749470245,
                992013.7397791013, -1221952.21711287, 1340652.697009075, -620943.6990984312,
                144416.9293806241, 37.5,
            ),
            doubleArrayOf(
                -0.0003441963504368392, 111320.7020576856, 278.2353980772752, 2485758.690035394,
                6070.750963243378, 54821.18345352118, 9540.606633304236, -2710.55326746645,
                1405.483844121726, 22.5,
            ),
            doubleArrayOf(
                -0.0003218135878613132, 111320.7020701615, 0.00369383431289, 823725.6402795718,
                0.46104986909093, 2351.343141331292, 1.58060784298199, 8.77738589078284,
                0.37238884252424, 7.45,
            ),
        )

        /** Meters band limits, north to south, paired with [DEGREE_BANDS]. */
        val METER_LIMITS = doubleArrayOf(
            12890594.86, 8362377.87, 5591021.0, 3481989.83, 1678043.12, 0.0,
        )

        /** Inverse coefficients, in the same band order as [METER_LIMITS]. */
        val DEGREE_BANDS = arrayOf(
            doubleArrayOf(
                -1.410526172116255e-8, 0.00000898305509648872, -1.9939833816331, 200.9824383106796,
                187.2403703815547, 91.6087516669843, -23.38765649603339, 2.57121317296198,
                -0.03801003308653, 17337981.2,
            ),
            doubleArrayOf(
                -7.435856389565537e-9, 0.000008983055097726239, -0.78625201886289, 96.32687599759846,
                -1.85204757529826, -59.36935905485877, 47.40033549296737, -16.50741931063887,
                2.28786674699375, 10260144.86,
            ),
            doubleArrayOf(
                -3.030883460898826e-8, 0.00000898305509983578, 0.30071316287616, 59.74293618442277,
                7.357984074871, -25.38371002664745, 13.45380521110908, -3.29883767235584,
                0.32710905363475, 6856817.37,
            ),
            doubleArrayOf(
                -1.981981304930552e-8, 0.000008983055099779535, 0.03278182852591, 40.31678527705744,
                0.65659298677277, -4.44255534477492, 0.85341911805263, 0.12923347998204,
                -0.04625736007561, 4482777.06,
            ),
            doubleArrayOf(
                3.09191371068437e-9, 0.000008983055096812155, 0.00006995724062, 23.10934304144901,
                -0.00023663490511, -0.6321817810242, -0.00663494467273, 0.03430082397953,
                -0.00466043876332, 2555164.4,
            ),
            doubleArrayOf(
                2.890871144776878e-9, 0.000008983055095805407, -3.068298e-8, 7.47137025468032,
                -0.00000353937994, -0.02145144861037, -0.00001234426596, 0.00010322952773,
                -0.00000323890364, 826088.5,
            ),
        )
    }
}
