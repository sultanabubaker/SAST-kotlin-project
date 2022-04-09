package org.jetbrains.intellij

import org.jetbrains.intellij.dependency.PluginDependencyNotation
import kotlin.test.Test
import kotlin.test.assertEquals

class UtilsTest {

    @Test
    fun `dependency parsing`() {
        assertEquals(PluginDependencyNotation("hello", "1.23", "alpha"), PluginDependencyNotation.parsePluginDependencyString("hello:1.23@alpha"))
        assertEquals(PluginDependencyNotation("hello", null, "alpha"), PluginDependencyNotation.parsePluginDependencyString("hello:@alpha"))
        assertEquals(PluginDependencyNotation("hello", null, "alpha"), PluginDependencyNotation.parsePluginDependencyString("hello@alpha"))
        assertEquals(PluginDependencyNotation("hello", null, null), PluginDependencyNotation.parsePluginDependencyString("hello"))
        assertEquals(PluginDependencyNotation("hello", "1.23", null), PluginDependencyNotation.parsePluginDependencyString("hello:1.23"))
    }
}
