package org.jetbrains.intellij.tasks

import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.internal.ConventionTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.jetbrains.intellij.IntelliJPluginConstants.INTELLIJ_DEPENDENCIES
import org.jetbrains.intellij.IntelliJPluginConstants.VERSION_LATEST
import org.jetbrains.intellij.Version
import org.jetbrains.intellij.debug
import org.jetbrains.intellij.logCategory
import org.jetbrains.intellij.model.MavenMetadata
import org.jetbrains.intellij.model.XmlExtractor
import org.jetbrains.intellij.utils.ArchiveUtils
import java.io.File
import java.net.URL
import javax.inject.Inject

@Suppress("UnstableApiUsage")
open class DownloadRobotServerPluginTask @Inject constructor(objectFactory: ObjectFactory) : ConventionTask() {

    companion object {
        private const val METADATA_URL = "$INTELLIJ_DEPENDENCIES/com/intellij/remoterobot/robot-server-plugin/maven-metadata.xml"
        private const val OLD_ROBOT_SERVER_DEPENDENCY = "org.jetbrains.test:robot-server-plugin"
        private const val NEW_ROBOT_SERVER_DEPENDENCY = "com.intellij.remoterobot:robot-server-plugin"
        private const val NEW_ROBOT_SERVER_VERSION = "0.11.0"

        fun resolveLatestVersion(): String {
            debug(message = "Resolving latest Robot Server Plugin version")
            val url = URL(METADATA_URL)
            return XmlExtractor<MavenMetadata>().unmarshal(url.openStream()).versioning?.latest
                ?: throw GradleException("Cannot resolve the latest Robot Server Plugin version")
        }

        /**
         * Resolves the Robot Server version.
         * If set to [org.jetbrains.intellij.IntelliJPluginConstants.VERSION_LATEST], there's request to [METADATA_URL]
         * performed for the latest available version.
         *
         * @return Robot Server version
         */
        fun resolveVersion(version: String?) = version?.takeIf { it != VERSION_LATEST } ?: resolveLatestVersion()

        fun getDependency(version: String) = when {
            Version.parse(version) >= Version.parse(NEW_ROBOT_SERVER_VERSION) -> NEW_ROBOT_SERVER_DEPENDENCY
            else -> OLD_ROBOT_SERVER_DEPENDENCY
        }
    }

    @Input
    val version = objectFactory.property<String>()

    @Input
    val pluginArchive = objectFactory.property<File>()

    @OutputDirectory
    val outputDir: DirectoryProperty = objectFactory.directoryProperty()

    private val archiveUtils = objectFactory.newInstance(ArchiveUtils::class.java)

    private val context = logCategory()

    @TaskAction
    fun downloadPlugin() {
        val target = outputDir.get().asFile
        archiveUtils.extract(pluginArchive.get(), target, context)
    }
}
