package org.jetbrains.intellij.tasks

import org.apache.commons.io.FileUtils
import org.apache.tools.ant.util.TeeOutputStream
import org.gradle.api.GradleException
import org.gradle.api.InvalidUserDataException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.internal.ConventionTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.property
import org.gradle.process.ExecOperations
import org.gradle.process.internal.ExecException
import org.jetbrains.intellij.IntelliJPluginConstants
import org.jetbrains.intellij.IntelliJPluginConstants.CACHE_REDIRECTOR
import org.jetbrains.intellij.IntelliJPluginConstants.PLUGIN_VERIFIER_REPOSITORY
import org.jetbrains.intellij.IntelliJPluginConstants.VERSION_LATEST
import org.jetbrains.intellij.Version
import org.jetbrains.intellij.debug
import org.jetbrains.intellij.error
import org.jetbrains.intellij.ifFalse
import org.jetbrains.intellij.info
import org.jetbrains.intellij.jbr.JbrResolver
import org.jetbrains.intellij.logCategory
import org.jetbrains.intellij.model.MavenMetadata
import org.jetbrains.intellij.model.XmlExtractor
import org.jetbrains.intellij.utils.ArchiveUtils
import org.jetbrains.intellij.utils.DependenciesDownloader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.EnumSet
import javax.inject.Inject

@Suppress("UnstableApiUsage")
open class RunPluginVerifierTask @Inject constructor(
    private val objectFactory: ObjectFactory,
    private val execOperations: ExecOperations,
) : ConventionTask() {

    companion object {
        private const val METADATA_URL = "$PLUGIN_VERIFIER_REPOSITORY/org/jetbrains/intellij/plugins/verifier-cli/maven-metadata.xml"
        private const val IDEA_DOWNLOAD_URL = "https://data.services.jetbrains.com/products/download"
        private const val ANDROID_STUDIO_DOWNLOAD_URL = "https://redirector.gvt1.com/edgedl/android/studio/ide-zips"

        fun resolveLatestVersion(): String {
            debug(message = "Resolving latest Plugin Verifier version")
            val url = URL(METADATA_URL)
            return XmlExtractor<MavenMetadata>().unmarshal(url.openStream()).versioning?.latest
                ?: throw GradleException("Cannot resolve the latest Plugin Verifier version")
        }
    }

    /**
     * List of the [FailureLevel] values used for failing the task if any reported issue will match.
     */
    @Input
    val failureLevel = objectFactory.listProperty<FailureLevel>()

    /**
     * A fallback file with a list of the releases generated with [ListProductsReleasesTask].
     * Used if [ideVersions] is not provided.
     */
    @Input
    @Optional
    val productsReleasesFile = objectFactory.property<File>()

    /**
     * List of the specified IDE versions used for the verification.
     * By default, it uses the plugin target IDE version.
     */
    @Input
    @Optional
    val ideVersions = objectFactory.listProperty<String>()

    /**
     * List of the paths to the specified IDE versions in [ideVersions] used for the verification.
     * By default, it resolves paths to the downloaded [ideVersions] IDEs.
     */
    @Input
    val ides = objectFactory.listProperty<File>()

    /**
     * List of the paths to locally installed IDE distributions that should be used for verification
     * in addition to those specified in [ideVersions].
     */
    @Input
    val localPaths = objectFactory.listProperty<File>()

    /**
     * Returns the version of the IntelliJ Plugin Verifier that will be used.
     * By default, set to "latest".
     */
    @Input
    @Optional
    val verifierVersion = objectFactory.property<String>()

    /**
     * Local path to the IntelliJ Plugin Verifier that will be used.
     * If provided, [verifierVersion] is ignored.
     */
    @Input
    @Optional
    val verifierPath = objectFactory.property<String>()

    /**
     * An instance of the distribution file generated with the build task.
     * If empty, task will be skipped.
     */
    @InputFile
    @SkipWhenEmpty
    val distributionFile: RegularFileProperty = objectFactory.fileProperty()

    /**
     * The path to directory where verification reports will be saved.
     * By default, set to ${project.buildDir}/reports/pluginVerifier.
     */
    @OutputDirectory
    @Optional
    val verificationReportsDir = objectFactory.property<String>()

    /**
     * The path to directory where IDEs used for the verification will be downloaded.
     * By default, set to ${project.buildDir}/pluginVerifier.
     */
    @Input
    @Optional
    val downloadDir = objectFactory.property<String>()

    /**
     * JBR version used by the IntelliJ Plugin Verifier, i.e. "11_0_2b159".
     * All JetBrains Java versions are available at JetBrains Space Packages: https://cache-redirector.jetbrains.com/intellij-jbr
     */
    @Input
    @Optional
    val jbrVersion = objectFactory.property<String>()

    /**
     * JetBrains Java runtime variant to use when running the IDE with the plugin.
     * Example values: `jcef`, `sdk`, `dcevm`, `fd`, `nomod`.
     * See JetBrains Runtime Releases: https://github.com/JetBrains/JetBrainsRuntime/releases
     */
    @Input
    @Optional
    val jbrVariant = objectFactory.property<String>()

    /**
     * Url of repository for downloading JetBrains Java Runtime.
     */
    @Input
    @Optional
    val jreRepository = objectFactory.property<String>()

    /**
     * The path to directory containing JVM runtime, overrides [jbrVersion].
     */
    @Input
    @Optional
    val runtimeDir = objectFactory.property<String>()

    /**
     * The list of classes prefixes from the external libraries.
     * The Plugin Verifier will not report 'No such class' for classes of these packages.
     */
    @Input
    @Optional
    val externalPrefixes = objectFactory.listProperty<String>()

    /**
     * A flag that controls the output format - if set to <code>true</code>, the TeamCity compatible output
     * will be returned to stdout.
     */
    @Input
    @Optional
    val teamCityOutputFormat = objectFactory.property<Boolean>()

    /**
     * Specifies which subsystems of IDE should be checked.
     * Available options: `all` (default), `android-only`, `without-android`.
     */
    @Input
    @Optional
    val subsystemsToCheck = objectFactory.property<String>()

    @Internal
    val ideDir = objectFactory.property<File>()

    @Internal
    val offline = objectFactory.property<Boolean>()

    private val archiveUtils = objectFactory.newInstance(ArchiveUtils::class.java)

    private val context = logCategory()

    /**
     * Runs the IntelliJ Plugin Verifier against the plugin artifact.
     */
    @TaskAction
    fun runPluginVerifier() {
        val file = distributionFile.orNull
        if (file == null || !file.asFile.exists()) {
            throw IllegalStateException("Plugin file does not exist: $file")
        }

        val paths = (ides.get() + localPaths.get()).mapNotNull(File::getCanonicalPath)
        if (paths.isEmpty()) {
            throw GradleException("'ideVersions' and 'localPaths' properties should not be empty")
        }

        val verifierPath = resolveVerifierPath()
        val verifierArgs = listOf("check-plugin") + getOptions() + file.asFile.canonicalPath + paths

        debug(context, "Distribution file: ${file.asFile.canonicalPath}")
        debug(context, "Verifier path: $verifierPath")

        ByteArrayOutputStream().use { os ->
            try {
                execOperations.javaexec {
                    classpath = objectFactory.fileCollection().from(verifierPath)
                    mainClass.set("com.jetbrains.pluginverifier.PluginVerifierMain")
                    args = verifierArgs
                    standardOutput = TeeOutputStream(System.out, os)
                }
            } catch (e: ExecException) {
                error(context, "Error during Plugin Verifier CLI execution:\n$os")
                throw e
            }

            debug(context, "Current failure levels: ${FailureLevel.values().joinToString(", ")}")
            FailureLevel.values().forEach { level ->
                if (failureLevel.get().contains(level) && os.toString().contains(level.testValue)) {
                    debug(context, "Failing task on '$failureLevel' failure level")
                    throw GradleException(level.toString())
                }
            }
        }
    }

    /**
     * Resolves path to the IntelliJ Plugin Verifier file.
     * At first, checks if it was provided with [verifierPath].
     * Fetches IntelliJ Plugin Verifier artifact from the [IntelliJPluginConstants.PLUGIN_VERIFIER_REPOSITORY]
     * repository and resolves the path to verifier-cli jar file.
     *
     * @return path to verifier-cli jar
     */
    private fun resolveVerifierPath(): String {
        val path = verifierPath.orNull
        if (path != null && path.isNotEmpty()) {
            val verifier = File(path)
            if (verifier.exists()) {
                return path
            }
        }

        throw InvalidUserDataException("Provided Plugin Verifier path doesn't exist: '$path'. Downloading Plugin Verifier: $verifierVersion")
    }

    /**
     * Resolves the Java Runtime directory. `runtimeDir` property is used if provided with the task configuration.
     * Otherwise, `jbrVersion` is used for resolving the JBR. If it's not set, or it's impossible to resolve valid
     * version, built-in JBR will be used.
     * As a last fallback, current JVM will be used.
     *
     * @return path to the Java Runtime directory
     */
    private fun resolveRuntimeDir(): String {
        val dependenciesDownloader = objectFactory.newInstance(DependenciesDownloader::class.java)
        val jbrResolver = objectFactory.newInstance(
            JbrResolver::class.java,
            jreRepository.orNull ?: "",
            offline.get(),
            archiveUtils,
            dependenciesDownloader,
            context,
        )

        return jbrResolver.resolveRuntimeDir(
            runtimeDir = runtimeDir.orNull,
            jbrVersion = jbrVersion.orNull,
            jbrVariant = jbrVariant.orNull,
            ideDir = ideDir.orNull,
        ) {
            validateRuntimeDir(it)
        } ?: throw InvalidUserDataException(when {
            requiresJava11() -> "Java Runtime directory couldn't be resolved. Note: Plugin Verifier 1.260+ requires Java 11"
            else -> "Java Runtime directory couldn't be resolved"
        })
    }

    /**
     * Verifies if provided Java Runtime directory points to Java 11 in case of Plugin Verifier 1.260+.
     *
     * @return Java Runtime directory points to Java 8 for Plugin Verifier version < 1.260, or Java 11 for 1.260+.
     */
    private fun validateRuntimeDir(runtimeDirPath: String) = ByteArrayOutputStream().use { os ->
        debug(context, "Plugin Verifier JRE verification: $runtimeDirPath")

        if (!requiresJava11()) {
            return true
        }

        execOperations.exec {
            executable = File(runtimeDirPath).resolve("bin/java").canonicalPath
            args = listOf("-version")
            errorOutput = os
        }
        val version = Version.parse(os.toString())
        val result = version >= Version(11)

        result.ifFalse {
            debug(context, "Plugin Verifier 1.260+ requires Java 11, but '$version' was provided with 'runtimeDir': $runtimeDirPath")
        }
    }

    /**
     * Checks Plugin Verifier version, if 1.260+ – require Java 11 to run.
     */
    private fun requiresJava11() = Version.parse(resolveVerifierVersion(verifierVersion.orNull)) >= Version(1, 260)

    /**
     * Collects all the options for the Plugin Verifier CLI provided with the task configuration.
     *
     * @return array with available CLI options
     */
    private fun getOptions(): List<String> {
        val args = mutableListOf(
            "-verification-reports-dir", verificationReportsDir.get(),
            "-runtime-dir", resolveRuntimeDir(),
        )

        externalPrefixes.get().takeIf { it.isNotEmpty() }?.let {
            args.add("-external-prefixes")
            args.add(it.joinToString(":"))
        }
        if (teamCityOutputFormat.get()) {
            args.add("-team-city")
        }
        if (subsystemsToCheck.orNull != null) {
            args.add("-subsystems-to-check")
            args.add(subsystemsToCheck.get())
        }
        if (offline.get()) {
            args.add("-offline")
        }

        return args
    }

    /**
     * Retrieve the Plugin Verifier home directory used for storing downloaded IDEs.
     * Following home directory resolving method is taken directly from the Plugin Verifier to keep the compatibility.
     *
     * @return Plugin Verifier home directory
     */
    private fun verifierHomeDir(): Path {
        System.getProperty("plugin.verifier.home.dir")?.let {
            return Paths.get(it)
        }

        System.getProperty("user.home")?.let {
            return Paths.get(it, ".pluginVerifier")
        }

        return FileUtils.getTempDirectory().toPath().resolve(".pluginVerifier")
    }


    /**
     * Resolves the Plugin Verifier version.
     * If set to [IntelliJPluginConstants.VERSION_LATEST], there's request to [METADATA_URL]
     * performed for the latest available version.
     *
     * @return Plugin Verifier version
     */
    internal fun resolveVerifierVersion(version: String?) = version?.takeIf { it != VERSION_LATEST } ?: resolveLatestVersion()

    /**
     * Resolves the IDE type and version. If just version is provided, type is set to "IC".
     *
     * @param ideVersion IDE version. Can be "2020.2", "IC-2020.2", "202.1234.56"
     * @return path to the resolved IDE
     */
    internal fun resolveIdePath(
        ideVersion: String,
        downloadDir: File,
        context: String?,
        block: (type: String, version: String, buildType: String) -> File,
    ): String {
        debug(context, "Resolving IDE path for: $ideVersion")
        var (type, version) = ideVersion.trim().split('-', limit = 2) + null

        if (version == null) {
            debug(context, "IDE type not specified, setting type to IC")
            version = type
            type = "IC"
        }

        val name = "$type-$version"
        val ideDir = downloadDir.resolve(name)

        if (ideDir.exists()) {
            debug(context, "IDE already available in: $ideDir")
            return ideDir.canonicalPath
        }

        val buildTypes = when (type) {
            IntelliJPluginConstants.ANDROID_STUDIO_TYPE -> listOf("")
            else -> listOf("release", "rc", "eap", "beta")
        }

        buildTypes.forEach { buildType ->
            debug(context, "Downloading IDE '$type-$version' from '$buildType' channel to: $downloadDir")
            try {
                return block(type!!, version!!, buildType).absolutePath.also {
                    debug(context, "Resolved IDE '$type-$version' path: $it")
                }
            } catch (e: IOException) {
                debug(context, "Cannot download IDE '$type-$version' from '$buildType' channel. Trying another channel...", e)
            }
        }

        throw GradleException("IDE '$ideVersion' cannot be downloaded. Please verify the specified IDE version against the products available for testing: https://jb.gg/intellij-platform-builds-list")
    }

    /**
     * Resolves direct IDE download URL provided by the JetBrains Data Services.
     * The URL created with [IDEA_DOWNLOAD_URL] contains HTTP redirection, which is supposed to be resolved.
     * Direct download URL is prepended with [CACHE_REDIRECTOR] host for providing caching mechanism.
     *
     * @param type IDE type, i.e. IC, PS
     * @param version IDE version, i.e. 2020.2 or 203.1234.56
     * @param buildType release, rc, eap, beta
     * @return direct download URL prepended with [CACHE_REDIRECTOR] host
     */
    internal fun resolveIdeUrl(type: String, version: String, buildType: String, context: String?): String {
        val isAndroidStudio = type == IntelliJPluginConstants.ANDROID_STUDIO_TYPE
        val url = when {
            isAndroidStudio -> "$ANDROID_STUDIO_DOWNLOAD_URL/$version/android-studio-$version-linux.tar.gz"
            else -> "$IDEA_DOWNLOAD_URL?code=$type&platform=linux&type=$buildType&${versionParameterName(version)}=$version"
        }

        debug(context, "Resolving direct IDE download URL for: $url")

        var connection: HttpURLConnection? = null

        try {
            with(URL(url).openConnection() as HttpURLConnection) {
                connection = this
                instanceFollowRedirects = false
                inputStream

                if ((responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) && !isAndroidStudio) {
                    val redirectUrl = URL(getHeaderField("Location"))
                    disconnect()
                    debug(context, "Resolved IDE download URL: $url")
                    return "$CACHE_REDIRECTOR/${redirectUrl.host}${redirectUrl.file}"
                } else {
                    debug(context, "IDE download URL has no redirection provided. Skipping")
                }
            }
        } catch (e: Exception) {
            info(context, "Cannot resolve direct download URL for: $url")
            debug(context, "Download exception stacktrace:", e)
            throw e
        } finally {
            connection?.disconnect()
        }

        return url
    }

    /**
     * Obtains version parameter name used for downloading IDE artifact.
     * Examples:
     * - 202.7660.26 -> build
     * - 2020.1, 2020.2.3 -> version
     *
     * @param version current version
     * @return version parameter name
     */
    private fun versionParameterName(version: String) = when {
        version.matches("\\d{3}(\\.\\d+)+".toRegex()) -> "build"
        else -> "version"
    }

    /**
     * Provides target directory used for storing downloaded IDEs.
     * Path is compatible with the Plugin Verifier approach.
     *
     * @return directory for downloaded IDEs
     */
    internal fun ideDownloadDir(): Path = verifierHomeDir().resolve("ides").also {
        Files.createDirectories(it)
    }

    enum class FailureLevel(val testValue: String) {
        COMPATIBILITY_WARNINGS("Compatibility warnings"), COMPATIBILITY_PROBLEMS("Compatibility problems"), DEPRECATED_API_USAGES("Deprecated API usages"), EXPERIMENTAL_API_USAGES(
            "Experimental API usages"),
        INTERNAL_API_USAGES("Internal API usages"), OVERRIDE_ONLY_API_USAGES("Override-only API usages"), NON_EXTENDABLE_API_USAGES("Non-extendable API usages"), PLUGIN_STRUCTURE_WARNINGS(
            "Plugin structure warnings"),
        MISSING_DEPENDENCIES("Missing dependencies"), INVALID_PLUGIN("The following files specified for the verification are not valid plugins"), NOT_DYNAMIC(
            "Plugin cannot be loaded/unloaded without IDE restart");

        companion object {
            val ALL: EnumSet<FailureLevel> = EnumSet.allOf(FailureLevel::class.java)
            val NONE: EnumSet<FailureLevel> = EnumSet.noneOf(FailureLevel::class.java)
        }
    }
}
