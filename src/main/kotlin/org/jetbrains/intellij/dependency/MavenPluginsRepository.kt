package org.jetbrains.intellij.dependency

import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.jetbrains.intellij.debug
import org.jetbrains.intellij.utils.DependenciesDownloader
import org.jetbrains.intellij.utils.mavenRepository
import java.io.File

interface MavenRepository : PluginsRepository {

    var resolvedDependency: Boolean

    fun getPluginFile(project: Project, dependency: Dependency, repository: MavenArtifactRepository, url: String, context: String?): File? =
        runCatching {
            project.objects.newInstance(DependenciesDownloader::class.java)
                .downloadFromRepository(context, { dependency }, { repository })
                .first().also {
                    resolvedDependency = true
                }
        }.getOrNull()

    fun postResolve(project: Project, func: () -> Unit) {
        if (resolvedDependency) {
            return func.invoke()
        }
    }
}

@CompileStatic
class MavenRepositoryPluginByAction(private val maven: Action<in MavenArtifactRepository>) : MavenRepository {

    override var resolvedDependency = false

    override fun resolve(project: Project, plugin: PluginDependencyNotation, context: String?): File? {
        val dependency = plugin.toDependency(project)
        val repository = project.repositories.maven(maven)
        return getPluginFile(project, dependency, repository, repository.url.toString(), context)
    }

    override fun postResolve(project: Project, context: String?) =
        postResolve(project) {
            val repository = project.repositories.maven(maven)
            debug(context, "Adding Maven plugins repository: ${repository.url}")
            project.repositories.maven(maven)
        }
}

@CompileStatic
class MavenRepositoryPlugin(private val repositoryUrl: String) : MavenRepository {

    override var resolvedDependency = false

    override fun resolve(project: Project, plugin: PluginDependencyNotation, context: String?): File? {
        val dependency = plugin.toDependency(project)
        val mavenRepository = project.repositories.mavenRepository(repositoryUrl)
        return getPluginFile(project, dependency, mavenRepository, repositoryUrl, context)
    }

    override fun postResolve(project: Project, context: String?) =
        postResolve(project) {
            debug(context, "Adding Maven plugins repository: $repositoryUrl")
            project.repositories.mavenRepository(repositoryUrl)
        }
}
