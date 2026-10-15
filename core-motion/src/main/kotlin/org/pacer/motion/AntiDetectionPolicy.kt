// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

/**
 * Pure decision logic for the anti-detection suite (M4).
 *
 * Real hooks (Xposed/LSPosed) will call into detectors and (where possible)
 * reach this policy for the boolean "should we intercept / lie?" decisions,
 * which makes the rules themselves unit-testable without any framework
 * dependency. Only constants and pure string/int matching live here.
 *
 * NOTE: the actual read-side interception (AppOps, Settings.Secure, reflection
 * on Location.mMock) is Android-API work that lands in :xposed when hardware
 * is available; this module supplies the policy those hooks consult.
 */
object AntiDetectionPolicy {

    /** What kind of query the target app is performing. */
    enum class Query {
        IS_FROM_MOCK_PROVIDER,
        IS_MOCK,
        APP_OPS_MOCK_LOCATION,
        SETTINGS_SECURE_MOCK_LOCATION,
        TEST_PROVIDER_CHECK,
    }

    /**
     * Decide whether a given query should be intercepted for the app whose
     * package is [callingPackage]. We only lie for packages that the user
     * has explicitly scoped into the hook (non-empty allow-list); for
     * everything else we pass through to the original.
     */
    fun shouldIntercept(callingPackage: String, scopedPackages: Set<String>, query: Query): Boolean {
        if (callingPackage.isBlank()) return false
        if (callingPackage !in scopedPackages) return false
        return when (query) {
            Query.IS_FROM_MOCK_PROVIDER,
            Query.IS_MOCK,
            Query.APP_OPS_MOCK_LOCATION,
            Query.SETTINGS_SECURE_MOCK_LOCATION,
            Query.TEST_PROVIDER_CHECK -> true
        }
    }

    /** Lying value we return for each boolean query. */
    fun liedBool(query: Query): Boolean = when (query) {
        // We always report "not a mock" and "no mock location appops".
        Query.IS_FROM_MOCK_PROVIDER,
        Query.IS_MOCK,
        Query.TEST_PROVIDER_CHECK -> false
        else -> false
    }

    /** Lying value for AppOps int result (MODE_IGNORED = 1 in AOSP). */
    fun liedAppOpsMode(): Int = 1 // AppOpsManager.MODE_IGNORED

    /** Lying value for Settings.Secure mock_location read (0 = disabled). */
    fun liedSecureMockLocation(): Int = 0

    /** Names of providers we should hide entirely ("gps"/"network" names stay). */
    val HIDDEN_TEST_PROVIDERS: Set<String> = setOf("mock", "test", "passive_mock")

    /** Is [providerName] a test provider we should pretend does not exist? */
    fun isHiddenTestProvider(providerName: String): Boolean =
        providerName.lowercase() in HIDDEN_TEST_PROVIDERS

    /** Xposed characteristic frames were identified by [StackCleaner]. */
    fun shouldCleanStackFor(callingPackage: String, scopedPackages: Set<String>): Boolean =
        shouldIntercept(callingPackage, scopedPackages, Query.IS_FROM_MOCK_PROVIDER)
}
