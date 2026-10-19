package org.pacer.app

import org.pacer.motion.Bd09Grid
import org.pacer.motion.GcjTileGrid
import org.pacer.motion.TileGrid
import org.pacer.motion.WebMercatorGrid

/** What a source is asked to draw: the road map, the imagery, or the imagery with labels. */
enum class MapLayer(val label: String) {
    ROAD("Road"),
    SATELLITE("Satellite"),
    HYBRID("Hybrid"),
}

/**
 * The three raster sources, the layers each one serves, and how each tile is addressed.
 *
 * NO KEY, NO SDK, AND THAT IS A CHOICE WITH A COST. Each endpoint is the one the
 * corresponding web map calls while it is being used in a browser, not a published API.
 * They are addressed directly so that a build needs no key, no account and no proprietary
 * dependency. The cost is that the addresses are undocumented and can change or stop
 * answering without notice; the app treats that as "no tiles", which is a grey map, not a
 * crash. A build that needs to be dependable should point these at its own tile proxy.
 *
 * NOT EVERY SOURCE HAS EVERY LAYER. AMap and Google serve imagery as well as roads; Baidu's
 * keyless satellite endpoint answers with a fixed placeholder image for every tile, so it
 * offers roads only rather than a satellite button that draws nothing. The page asks
 * [layers] before offering a choice.
 *
 * THE GRID IS THE HARD PART, NOT THE URL. Google and AMap both cut 256-pixel XYZ tiles, but
 * AMap's are drawn in GCJ-02, and Baidu does not use Web Mercator at all. Each source
 * therefore carries the [TileGrid] that knows how to place a WGS-84 coordinate on it, so the
 * map view never has to ask which map it is drawing.
 *
 * Coordinates stay WGS-84 everywhere in this app: the module writes a fix in WGS-84, a route
 * is stored in WGS-84, and only a tile source's own boundary converts.
 */
enum class MapStyle(
    val label: String,
    val grid: TileGrid,
    val layers: List<MapLayer>,
) {

    AMAP(
        label = "AMap",
        grid = GcjTileGrid(),
        layers = MapLayer.entries,
    ) {
        override fun url(x: Int, y: Int, zoom: Int, layer: MapLayer): String =
            when (layer) {
                MapLayer.ROAD ->
                    "https://wprd0${shardOf(x, y) + 1}.is.autonavi.com/appmaptile" +
                        "?lang=zh_cn&size=1&style=7&x=$x&y=$y&z=$zoom"
                // style=6 is the imagery. It answers with JPEG, not PNG; the decoder does
                // not care, but a test that expected a PNG signature would.
                MapLayer.SATELLITE, MapLayer.HYBRID ->
                    "https://webst0${shardOf(x, y) + 1}.is.autonavi.com/appmaptile" +
                        "?style=6&x=$x&y=$y&z=$zoom"
            }

        /**
         * AMap's hybrid is the imagery plus a transparent label tile, because its `style=8`
         * tile carries only the labels and the road outlines.
         */
        override fun overlay(x: Int, y: Int, zoom: Int, layer: MapLayer): String? =
            if (layer == MapLayer.HYBRID) {
                "https://webst0${shardOf(x, y) + 1}.is.autonavi.com/appmaptile" +
                    "?style=8&x=$x&y=$y&z=$zoom"
            } else {
                null
            }
    },

    /**
     * Baidu: BD-09, on its own projection, and the vertical tile index counts NORTH.
     *
     * This is why [Bd09Grid.tile] exists. `styles=pl` is the road map. `scaler=1` asks for
     * the standard 256-pixel tile rather than the retina variant, and `showtext=1` keeps the
     * place labels, without which a tile is close to useless for choosing a spot.
     *
     * ROADS ONLY: the `starpic` endpoint answers every request with the same 2506-byte
     * placeholder JPEG, and the `styles=sl` tile is a transparent label overlay rather
     * than imagery. Both were checked before this list was written.
     */
    BAIDU(
        label = "Baidu",
        grid = Bd09Grid(),
        layers = listOf(MapLayer.ROAD),
    ) {
        override fun url(x: Int, y: Int, zoom: Int, layer: MapLayer): String =
            "https://maponline${shardOf(x, y)}.bdimg.com/tile/" +
                "?qt=vtile&x=$x&y=$y&z=$zoom&styles=pl&scaler=1&showtext=1"
    },

    /**
     * Google: plain Web Mercator in WGS-84, the easiest of the three and the one most likely
     * to be unreachable from where this app is used.
     *
     * `lyrs=m` is the road map, `s` the imagery, `y` the imagery with its labels already
     * composited in, so Google needs no overlay pass. `mt0` .. `mt3` are shards.
     */
    GOOGLE(
        label = "Google",
        grid = WebMercatorGrid("Google"),
        layers = MapLayer.entries,
    ) {
        override fun url(x: Int, y: Int, zoom: Int, layer: MapLayer): String {
            val code = when (layer) {
                MapLayer.ROAD -> "m"
                MapLayer.SATELLITE -> "s"
                MapLayer.HYBRID -> "y"
            }
            return "https://mt${shardOf(x, y)}.google.com/vt/lyrs=$code&x=$x&y=$y&z=$zoom"
        }
    };

    /** The tile at (x, y) on [zoom] in [layer], addressed the way this source expects. */
    abstract fun url(x: Int, y: Int, zoom: Int, layer: MapLayer): String

    /** A transparent tile drawn over the base one, when this source composes labels itself. */
    open fun overlay(x: Int, y: Int, zoom: Int, layer: MapLayer): String? = null

    fun supports(layer: MapLayer): Boolean = layers.contains(layer)

    companion object {
        /** The source a build uses unless the user picks another. */
        val DEFAULT = AMAP

        fun byName(name: String): MapStyle? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * The shard for a tile, so one host is not asked for every tile on screen.
 *
 * At file level rather than in the enum: an enum constant's body is initialised before the
 * enum instance's own members can be called from it, so a member function here would not
 * compile.
 *
 * Derived from the index rather than random, so the same tile always comes from the same
 * host: connections stay warm per host, and a fetch that alternated would defeat that.
 */
private fun shardOf(x: Int, y: Int): Int = (x + y) and 3
