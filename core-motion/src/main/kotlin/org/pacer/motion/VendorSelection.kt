// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Adapter selection and class-name merging, kept in the pure engine for the same
// reason as RomRules: a wrong answer here is silent. The device either gets the
// right class names or the hooks do nothing at all, with no error anywhere.
package org.pacer.motion

/**
 * A vendor adapter that has ALREADY decided whether it applies to this device.
 *
 * The Android side computes [applies], because that needs SystemProperties and
 * the API level; everything downstream of it is pure and unit tested.
 */
data class VendorCandidate(
    val id: String,
    val priority: Int,
    val applies: Boolean,
)

/**
 * The adapter that should be used: the applicable one with the highest priority.
 *
 * Ties break on [VendorCandidate.id] rather than on list position, so the answer
 * never depends on something as incidental as map iteration order. That matters
 * because this decision picks which class names get hooked.
 */
fun selectVendor(candidates: List<VendorCandidate>): VendorCandidate? =
    candidates.filter { it.applies }
        .maxWithOrNull(compareBy({ it.priority }, { it.id }))

/**
 * Vendor class names first, then the baseline, without duplicates.
 *
 * The order IS the contract: it is the order the class locator walks, so a vendor
 * candidate that exists wins over the baseline, while the baseline stays behind
 * it as the fallback that keeps an unadapted firmware working.
 */
fun mergeClassCandidates(baseline: List<String>, vendor: List<String>): List<String> =
    (vendor + baseline).distinct()
