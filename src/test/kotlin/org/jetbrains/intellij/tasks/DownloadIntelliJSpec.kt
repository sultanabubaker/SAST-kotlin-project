package org.jetbrains.intellij.tasks

import org.gradle.api.plugins.BasePlugin
import org.jetbrains.intellij.IntelliJPluginSpecBase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadIntelliJSpec : IntelliJPluginSpecBase() {

    @Test
    fun `download idea dependencies`() {
        val cacheDir = File(gradleHome, "caches/modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIC/2020.1")

        build(BasePlugin.ASSEMBLE_TASK_NAME)

        assertTrue(cacheDir.list()?.toSet()?.containsAll(
            setOf("cbeeb1f1aebd4c9ea8fb5ab990c5904a676fc41a", "116a3a8911c3a4bd49b2cb23f9576d13eaa721df")
        ) ?: false)
        assertEquals(
            setOf("ideaIC-2020.1.pom"),
            File(cacheDir, "116a3a8911c3a4bd49b2cb23f9576d13eaa721df").list()?.toSet(),
        )
        assertEquals(
            setOf("ideaIC-2020.1", "ideaIC-2020.1.zip"),
            File(cacheDir, "cbeeb1f1aebd4c9ea8fb5ab990c5904a676fc41a").list()?.toSet(),
        )
    }

    @Test
    fun `download sources if option is enabled`() {
        val cacheDir = File(gradleHome, "caches/modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIC/2020.1")

        buildFile.groovy("""
            intellij {
                downloadSources = true
            }
        """)

        build(BasePlugin.ASSEMBLE_TASK_NAME)

        assertEquals(
            setOf(
                "cbeeb1f1aebd4c9ea8fb5ab990c5904a676fc41a",
                "6becac80419981b057df9cf0c62efcd94e6075a8",
                "116a3a8911c3a4bd49b2cb23f9576d13eaa721df",
            ),
            cacheDir.list()?.toSet()
        )
        assertEquals(
            setOf("ideaIC-2020.1.pom"),
            File(cacheDir, "116a3a8911c3a4bd49b2cb23f9576d13eaa721df").list()?.toSet(),
        )
        assertEquals(
            setOf("ideaIC-2020.1", "ideaIC-2020.1.zip"),
            File(cacheDir, "cbeeb1f1aebd4c9ea8fb5ab990c5904a676fc41a").list()?.toSet(),
        )
        assertEquals(
            setOf("ideaIC-2020.1-sources.jar"),
            File(cacheDir, "6becac80419981b057df9cf0c62efcd94e6075a8").list()?.toSet(),
        )
    }

    @Test
    fun `download ultimate idea dependencies`() {
        val cacheDir = File(gradleHome, "caches/modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIU/14.1.4")
        val ideaCommunityCacheDir = File(gradleHome, "caches/modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIC/14.1.4")

        buildFile.groovy("""
            intellij {
                version = 'IU-14.1.4'
                downloadSources = true
            }
        """)
        build(BasePlugin.ASSEMBLE_TASK_NAME)

        assertEquals(
            setOf("b8993c44c83fe4a39dbb6b72ab6d87a117769534", "f8eb5ad49abba6374eeec643cecf20f7268cbfee"),
            cacheDir.list()?.toSet(),
        )
        assertEquals(
            setOf("ideaIU-14.1.4.pom"),
            File(cacheDir, "b8993c44c83fe4a39dbb6b72ab6d87a117769534").list()?.toSet(),
        )
        assertEquals(
            setOf("ideaIU-14.1.4", "ideaIU-14.1.4.zip"),
            File(cacheDir, "f8eb5ad49abba6374eeec643cecf20f7268cbfee").list()?.toSet(),
        )

        // do not download ideaIC dist
        assertTrue(cacheDir.list()?.toSet()?.containsAll(
            setOf("f8eb5ad49abba6374eeec643cecf20f7268cbfee", "b8993c44c83fe4a39dbb6b72ab6d87a117769534")
        ) ?: false)
        assertEquals(
            setOf("ideaIC-14.1.4.pom"),
            File(ideaCommunityCacheDir, "87ce88382f970b94fc641304e0a80af1d70bfba7").list()?.toSet(),
        )
        assertEquals(
            setOf("ideaIC-14.1.4-sources.jar"),
            File(ideaCommunityCacheDir, "f5169c4a780da12ca4eec17553de9f6d43a49d52").list()?.toSet(),
        )
    }
}
