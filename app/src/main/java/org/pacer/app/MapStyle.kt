package org.pacer.app

import org.pacer.motion.Bd09Grid
import org.pacer.motion.GcjTileGrid
import org.pacer.motion.TileGrid
import org.pacer.motion.WebMercatorGrid

/**
 * The three raster sources, and how each one is addressed.
 *
 * NO KEY, NO SDK, AND THAT IS A CHOICE WITH A COST. Each of these is the tile endpoint the
 * corresponding web map calls while it is being used in a browser, not a published API. They
 * are addressed directly so that a build needs no key, no account and no proprietary
 * dependency. The cost is that the addresses are undocumented and can change or stop
 * answering without notice; the app treats that as "no tiles", which is a grey map, not a
 * crash. A build that needs to be dependable should point these at its own tile proxy.
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
) {

    /**
     * AMap: the tiles most Chinese users recognise, drawn in GCJ-02.
     *
     * `style=7` is the road map, `style=6` the satellite image. The `wprd0N` host is one of
     * four shards (`wprd01` .. `wprd04`), not a region.
     */
    AMAP(
        label = "AMap",
        grid = GcjTileGrid(),
    ) {
        override fun url(x: Int, y: Int, zoom: Int): String =
            "https://wprd0${shardOf(x, y) + 1}.is.autonavi.com/appmaptile" +
                "?lang=zh_cn&size=1&style=7&x=$x&y=$y&z=$zoom"
    },

    /**
     * Baidu: BD-09, on its own projection, and the vertical tile index counts NORTH.
     *
     * This is why [Bd09Grid.tile] exists. `styles=pl` is the road map. `scaler=1` asks for
     * the standard 256-pixel tile rather than the retina variant, and `showtext=1` keeps the
     * place labels, without which a tile is close to useless for choosing a spot.
     */
    BAIDU(
        label = "Baidu",
        grid = Bd09Grid(),
    ) {
        override fun url(x: Int, y: Int, zoom: Int): String =
            "https://maponline${shardOf(x, y)}.bdimg.com/tile/" +
                "?qt=vtile&x=$x&y=$y&z=$zoom&styles=pl&scaler=1&showtext=1"
    },

    /**
     * Google: plain Web Mercator in WGS-84, the easiest of the three and the one most likely
     * to be unreachable from where this app is used.
     *
     * `lyrs=m` is the road map; `mt0` .. `mt3` are shards.
     */
    GOOGLE(
        label = "Google",
        grid = WebMercatorGrid("Google"),
    ) {
        override fun url(x: Int, y: Int, zoom: Int): String =
            "https://mt${shardOf(x, y)}.google.com/vt/lyrs=m&x=$x&y=$y&z=$zoom"
    };

    /** The tile at (x, y) on [zoom], addressed the way this source expects. */
    abstract fun url(x: Int, y: Int, zoom: Int): String

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
