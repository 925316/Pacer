// Pacer - free motion simulation tool for research and testing.
// Copyright (C) 2026 Pacer contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiDetectionPolicyTest {

    private val scoped = setOf("com/foo/runner", "org.bar.app")

    @Test
    fun `intercepts only scoped packages`() {
        assertTrue(AntiDetectionPolicy.shouldIntercept("com/foo/runner", scoped, AntiDetectionPolicy.Query.IS_FROM_MOCK_PROVIDER))
        assertFalse(AntiDetectionPolicy.shouldIntercept("com/unscoped/app", scoped, AntiDetectionPolicy.Query.IS_FROM_MOCK_PROVIDER))
        assertFalse(AntiDetectionPolicy.shouldIntercept("", scoped, AntiDetectionPolicy.Query.IS_FROM_MOCK_PROVIDER))
    }

    @Test
    fun `blank scoped set means no interception`() {
        assertFalse(
            AntiDetectionPolicy.shouldIntercept(
                "com/foo/runner",
                emptySet(),
                AntiDetectionPolicy.Query.APP_OPS_MOCK_LOCATION,
            ),
        )
    }

    @Test
    fun `lied values are safe defaults`() {
        assertEquals(false, AntiDetectionPolicy.liedBool(AntiDetectionPolicy.Query.IS_FROM_MOCK_PROVIDER))
        assertEquals(1, AntiDetectionPolicy.liedAppOpsMode())
        assertEquals(0, AntiDetectionPolicy.liedSecureMockLocation())
    }

    @Test
    fun `hidden test providers are flagged`() {
        assertTrue(AntiDetectionPolicy.isHiddenTestProvider("mock"))
        assertTrue(AntiDetectionPolicy.isHiddenTestProvider("PASSIVE_MOCK"))
        assertFalse(AntiDetectionPolicy.isHiddenTestProvider("gps"))
        assertFalse(AntiDetectionPolicy.isHiddenTestProvider("network"))
    }

    @Test
    fun `stack cleaning follows scoped policy`() {
        assertTrue(AntiDetectionPolicy.shouldCleanStackFor("com/foo/runner", scoped))
        assertFalse(AntiDetectionPolicy.shouldCleanStackFor("com/other", scoped))
    }
}
