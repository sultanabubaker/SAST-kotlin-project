@file:JvmName("Utils")
@file:Suppress("DEPRECATION", "BooleanMethodIsAlwaysInverted")

package org.jetbrains.intellij

import com.jetbrains.plugin.structure.base.plugin.PluginCreationFail
import com.jetbrains.plugin.structure.base.plugin.PluginCreationSuccess
import com.jetbrains.plugin.structure.base.plugin.PluginProblem
import com.jetbrains.plugin.structure.base.utils.isJar
import com.jetbrains.plugin.structure.base.utils.isZip
import com.jetbrains.plugin.structure.intellij.beans.PluginBean
import com.jetbrains.plugin.structure.intellij.extractor.PluginBeanExtractor
import com.jetbrains.plugin.structure.intellij.plugin.IdePlugin
import com.jetbrains.plugin.structure.intellij.plugin.IdePluginManager
import com.jetbrains.plugin.structure.intellij.utils.JDOMUtil
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.AbstractFileFilter
import org.apache.commons.io.filefilter.FalseFileFilter
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.SourceSet
import org.gradle.internal.os.OperatingSystem
import org.gradle.process.JavaForkOptions
import org.jdom2.Document
import org.jdom2.JDOMException
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import org.jetbrains.intellij.IntelliJPluginConstants.RELEASE_SUFFIX_CUSTOM_SNAPSHOT
import org.jetbrains.intellij.IntelliJPluginConstants.RELEASE_SUFFIX_EAP
import org.jetbrains.intellij.IntelliJPluginConstants.RELEASE_SUFFIX_EAP_CANDIDATE
import org.jetbrains.intellij.IntelliJPluginConstants.RELEASE_SUFFIX_SNAPSHOT
import org.jetbrains.intellij.IntelliJPluginConstants.RELEASE_TYPE_NIGHTLY
import org.jetbrains.intellij.IntelliJPluginConstants.RELEASE_TYPE_RELEASES
import org.jetbrains.intellij.IntelliJPluginConstants.RELEASE_TYPE_SNAPSHOTS
import org.jetbrains.intellij.dependency.IdeaDependency
import org.jetbrains.intellij.model.ProductInfo
import org.xml.sax.SAXParseException
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.nio.file.Files.createTempDirectory
import java.util.function.Predicate

val MAJOR_VERSION_PATTERN = "(RIDER-|GO-)?\\d{4}\\.\\d-(EAP\\d*-)?SNAPSHOT".toPattern()

@Suppress("DEPRECATION")
fun mainSourceSet(project: Project): SourceSet = project
    .convention.getPlugin(JavaPluginConvention::class.java)
//    .extensions.getByType(JavaPluginExtension::class.java) // available since Gradle 7.1
    .sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)

fun sourcePluginXmlFiles(project: Project) = mainSourceSet(project).resources.srcDirs.mapNotNull {
    File(it, "META-INF/plugin.xml").takeIf { file -> file.exists() && file.length() > 0 }
}

fun parsePluginXml(pluginXml: File, logCategory: String?): PluginBean? {
    try {
        val document = JDOMUtil.loadDocument(pluginXml.inputStream())
        return PluginBeanExtractor.extractPluginBean(document)
    } catch (e: SAXParseException) {
        warn(logCategory, "Cannot read: ${pluginXml.canonicalPath}. Skipping", e)
    } catch (e: JDOMException) {
        warn(logCategory, "Cannot read: ${pluginXml.canonicalPath}. Skipping", e)
    } catch (e: IOException) {
        warn(logCategory, "Cannot read: ${pluginXml.canonicalPath}. Skipping", e)
    }
    return null
}

fun transformXml(document: Document, file: File) {
    val xmlOutput = XMLOutputter()
    xmlOutput.format.apply {
        indent = "  "
        omitDeclaration = true
        textMode = Format.TextMode.TRIM
    }

    StringWriter().use {
        xmlOutput.output(document, it)
        file.writeText(it.toString())
    }
}

fun getIdeaSystemProperties(
    configDirectory: File,
    systemDirectory: File,
    pluginsDirectory: File,
    requirePluginIds: List<String>,
): Map<String, String> {
    val result = mapOf(
        "idea.config.path" to configDirectory.absolutePath,
        "idea.system.path" to systemDirectory.absolutePath,
        "idea.plugins.path" to pluginsDirectory.absolutePath,
    )
    if (requirePluginIds.isNotEmpty()) {
        return result + mapOf("idea.required.plugins.id" to requirePluginIds.joinToString(","))
    }
    return result
}

fun getIdeJvmArgs(options: JavaForkOptions, arguments: List<String>, ideDirectory: File?): List<String> {
    options.maxHeapSize = options.maxHeapSize ?: "512m"
    options.minHeapSize = options.minHeapSize ?: "256m"

    ideDirectory?.let {
        val bootJar = File(ideDirectory, "lib/boot.jar")
        if (bootJar.exists()) {
            return arguments + "-Xbootclasspath/a:${bootJar.absolutePath}"
        }
    }
    return arguments
}

fun ideBuildNumber(ideDirectory: File) = (
    File(ideDirectory, "Resources/build.txt").takeIf { OperatingSystem.current().isMacOsX && it.exists() }
        ?: File(ideDirectory, "build.txt")
    ).readText().trim()

fun ideProductInfo(ideDirectory: File) = (
    File(ideDirectory, "Resources/product-info.json").takeIf { OperatingSystem.current().isMacOsX && it.exists() }
        ?: File(ideDirectory, "product-info.json")
    )
    .runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<ProductInfo>(readText()) }
    .getOrNull()

fun ideaDir(path: String) = File(path).let {
    it.takeUnless { it.name.endsWith(".app") } ?: File(it, "Contents")
}

fun File.isJar() = toPath().isJar()

fun File.isZip() = toPath().isZip()

fun collectJars(directory: File, filter: Predicate<File>): Collection<File> = when {
    !directory.isDirectory -> emptyList()
    else -> FileUtils.listFiles(directory, object : AbstractFileFilter() {
        override fun accept(file: File) = file.isJar() && filter.test(file)
    }, FalseFileFilter.FALSE)
}

fun releaseType(version: String) = when {
    version.endsWith(RELEASE_SUFFIX_EAP) ||
        version.endsWith(RELEASE_SUFFIX_EAP_CANDIDATE) ||
        version.endsWith(RELEASE_SUFFIX_CUSTOM_SNAPSHOT) ||
        version.matches(MAJOR_VERSION_PATTERN.toRegex())
    -> RELEASE_TYPE_SNAPSHOTS
    version.endsWith(RELEASE_SUFFIX_SNAPSHOT) -> RELEASE_TYPE_NIGHTLY
    else -> RELEASE_TYPE_RELEASES
}

fun error(logCategory: String? = null, message: String, e: Throwable? = null) = log(LogLevel.ERROR, logCategory, message, e)
fun warn(logCategory: String? = null, message: String, e: Throwable? = null) = log(LogLevel.WARN, logCategory, message, e)
fun info(logCategory: String? = null, message: String, e: Throwable? = null) = log(LogLevel.INFO, logCategory, message, e)
fun debug(logCategory: String? = null, message: String, e: Throwable? = null) = log(LogLevel.DEBUG, logCategory, message, e)

private fun log(level: LogLevel, logCategory: String?, message: String, e: Throwable?) {
    val category = "gradle-intellij-plugin ${logCategory ?: ""}".trim()
    val logger = Logging.getLogger(IntelliJPlugin::class.java)
    if (e != null && level != LogLevel.ERROR && !logger.isDebugEnabled) {
        logger.log(level, "[$category] $message. Run with --debug option to get more log output.")
    } else {
        logger.log(level, "[$category] $message", e)
    }
}

fun Project.logCategory(): String = path + name

fun Task.logCategory(): String = project.path + project.name + path

fun createPlugin(artifact: File, validatePluginXml: Boolean, context: String?): IdePlugin? {
    val extractDirectory = createTempDirectory("tmp")
    val creationResult = IdePluginManager.createManager(extractDirectory)
        .createPlugin(artifact.toPath(), validatePluginXml, IdePluginManager.PLUGIN_XML)

    return when (creationResult) {
        is PluginCreationSuccess -> creationResult.plugin
        is PluginCreationFail -> {
            val problems = creationResult.errorsAndWarnings.filter { it.level == PluginProblem.Level.ERROR }.joinToString()
            warn(context, "Cannot create plugin from file '$artifact': $problems")
            null
        }
        else -> {
            warn(context, "Cannot create plugin from file '$artifact'. $creationResult")
            null
        }
    }
}

fun isKotlinRuntime(name: String) =
    name == "kotlin-runtime" ||
        name == "kotlin-reflect" || name.startsWith("kotlin-reflect-") ||
        name == "kotlin-stdlib" || name.startsWith("kotlin-stdlib-") ||
        name == "kotlin-test" || name.startsWith("kotlin-test-")

fun isDependencyOnPyCharm(dependency: IdeaDependency): Boolean {
    return dependency.name == "pycharmPY" || dependency.name == "pycharmPC"
}

fun isPyCharmType(type: String): Boolean {
    return type == "PY" || type == "PC"
}

fun <T> T?.ifNull(block: () -> Unit): T? {
    if (this == null) {
        block()
    }
    return this
}

fun Boolean.ifFalse(block: () -> Unit): Boolean {
    if (!this) {
        block()
    }
    return this
}
