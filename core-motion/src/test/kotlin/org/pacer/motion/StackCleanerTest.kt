package org.pacer.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StackCleanerTest {

    @Test
    fun `detects known framework frames`() {
        assertTrue(StackCleaner.isFrameworkFrame("de.robv.android.xposed.XposedBridge"))
        assertTrue(StackCleaner.isFrameworkFrame("org.lsposed.lspd.core.HookBridge"))
        assertTrue(StackCleaner.isFrameworkFrame("io.github.libxposed.api.XposedModule"))
        assertFalse(StackCleaner.isFrameworkFrame("com.example.myapp.MainActivity"))
        assertFalse(StackCleaner.isFrameworkFrame("java.lang.Thread.run"))
    }

    @Test
    fun `removes framework frames and keeps the rest`() {
        val frames = listOf(
            "com.example.Runner.start",
            "de.robv.android.xposed.XposedBridge.invokeOriginalMethod",
            "org.lsposed.lspd.Hook",
            "java.lang.Thread.run",
        )
        val cleaned = StackCleaner.clean(frames)
        assertEquals(listOf("com.example.Runner.start", "java.lang.Thread.run"), cleaned)
    }

    @Test
    fun `keeps original when everything is framework frames`() {
        val frames = listOf("de.robv.android.xposed.A", "org.lsposed.B")
        assertEquals(frames, StackCleaner.clean(frames))
    }

    @Test
    fun `empty input stays empty`() {
        assertTrue(StackCleaner.clean(emptyList()).isEmpty())
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(StackCleaner.isFrameworkFrame("De.Robv.Android.Xposed.Foo"))
    }
}
