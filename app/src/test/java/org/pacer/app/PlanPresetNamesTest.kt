package org.pacer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pacer.motion.PlanDraft

/**
 * The preset buttons carry translated labels, looked up by [PlanDraft.Preset.id], so a
 * preset with no entry would silently show its English fallback in a Chinese UI.
 */
class PlanPresetNamesTest {

    @Test
    fun `every preset is named in both languages`() {
        val names = mapOf("en" to EnglishStrings.planPresetNames, "zh" to ChineseStrings.planPresetNames)
        for ((language, table) in names) {
            assertEquals(language, PlanDraft.PRESETS.map { it.id }.toSet(), table.keys)
            for ((id, label) in table) {
                assertTrue("$language/$id is blank", label.isNotBlank())
            }
        }
    }

    @Test
    fun `the two languages do not reuse the same label by accident`() {
        // A copy-paste that left the English word in the Chinese table is the mistake this
        // catches; a translation that legitimately matches (a product name) is not possible
        // for these five.
        for (preset in PlanDraft.PRESETS) {
            val en = EnglishStrings.planPresetNames.getValue(preset.id)
            val zh = ChineseStrings.planPresetNames.getValue(preset.id)
            assertTrue("${preset.id} is untranslated ($zh)", en != zh)
        }
    }
}
