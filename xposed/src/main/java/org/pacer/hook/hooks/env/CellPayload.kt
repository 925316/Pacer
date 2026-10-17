// What cells the device should be reporting right now.
//
// Shared by the app-process hooker and the phone-process one on purpose: both
// answer the same question about the same device, and two implementations would
// eventually disagree with each other, which is exactly the inconsistency a
// risk-control SDK looks for.
//
// Fabrication is attempted first and suppression is the fallback; see
// CellFabricator for why a failure there is safe.
package org.pacer.hook.hooks.env

import org.pacer.hook.config.PacerRuntimeConfig

object CellPayload {

    /** A device occupies one coarse area at a time, so one entry is enough. */
    private data class AreaCache(
        val areaKey: Long,
        val seed: Long,
        val operator: String,
        val cells: List<Any>,
    )

    @Volatile private var cache: AreaCache? = null

    /**
     * Fabricated cells for the current fix, or null when the caller must
     * suppress instead. Cached per coarse area, session seed and operator,
     * because the tower set only changes at an area boundary while building it
     * costs a dozen reflective constructions.
     *
     * @param operatorNumeric the device's current network operator as "46001",
     *   or "" when it cannot be read.
     */
    fun payload(operatorNumeric: String, classLoader: ClassLoader?): List<Any>? {
        val cfg = PacerRuntimeConfig.snapshot()
        if (!cfg.active || !EnvGeo.hasFix(cfg.latitude, cfg.longitude)) return null

        val seed = EnvGeo.sessionSeed(cfg.startTimestamp)
        val areaKey = FakeRadioEnvironment.cellAreaKey(cfg.latitude, cfg.longitude)
        cache?.let {
            if (it.areaKey == areaKey && it.seed == seed && it.operator == operatorNumeric) return it.cells
        }

        val model = FakeRadioEnvironment.cells(cfg.latitude, cfg.longitude, seed)
        val built = CellFabricator.fabricate(onOperator(model, operatorNumeric), classLoader) ?: return null
        cache = AreaCache(areaKey, seed, operatorNumeric, built)
        return built
    }

    /** "46001" -> (460, 1). Null when the string is absent or malformed. */
    fun parseOperator(raw: String): Pair<Int, Int>? {
        if (raw.length < 5 || !raw.all { it in '0'..'9' }) return null
        val mcc = raw.substring(0, 3).toIntOrNull() ?: return null
        val mnc = raw.substring(3).toIntOrNull() ?: return null
        if (mcc <= 0) return null
        return mcc to mnc
    }

    /**
     * Puts the fabricated cells on the operator the device is really camped on.
     * Everything else about a cell (area, identity, radio code, signal) is what
     * changes as a device moves, so nothing is lost by taking the operator from
     * the device instead of from the model. Reporting a different operator than
     * the untouched getNetworkOperator() would be a one-string comparison to
     * notice.
     */
    private fun onOperator(
        cells: List<FakeRadioEnvironment.Cell>,
        operatorNumeric: String,
    ): List<FakeRadioEnvironment.Cell> {
        val parsed = parseOperator(operatorNumeric) ?: return cells
        return cells.map { it.copy(mcc = parsed.first, mnc = parsed.second) }
    }
}
