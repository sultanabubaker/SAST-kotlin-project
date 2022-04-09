package org.jetbrains.intellij.utils

import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import org.jetbrains.intellij.debug
import java.io.File
import java.util.function.BiConsumer
import java.util.function.Predicate
import javax.inject.Inject

open class ArchiveUtils @Inject constructor(
    private val archiveOperations: ArchiveOperations,
    private val fileSystemOperations: FileSystemOperations,
) {

    @Suppress("UnstableApiUsage")
    fun extract(
        archiveFile: File,
        targetDirectory: File,
        context: String?,
        isUpToDate: Predicate<File>? = null,
        markUpToDate: BiConsumer<File, File>? = null,
    ): File {
        val name = archiveFile.name
        val markerFile = File(targetDirectory, "markerFile")
        if (markerFile.exists() && (isUpToDate == null || isUpToDate.test(markerFile))) {
            return targetDirectory
        }

        targetDirectory.deleteRecursively()
        targetDirectory.mkdirs()

        debug(context, "Extracting: $name")

        when {
            name.endsWith(".zip") || name.endsWith(".sit") -> {
                fileSystemOperations.copy {
                    from(archiveOperations.zipTree(archiveFile))
                    into(targetDirectory)
                }
            }
            name.endsWith(".tar.gz") -> {
                fileSystemOperations.copy {
                    from(archiveOperations.tarTree(archiveFile))
                    into(targetDirectory)
                }
            }
            else -> throw IllegalArgumentException("Unknown type archive type: $name")
        }

        debug(context, "Extracted: $name")

        markerFile.createNewFile()
        markUpToDate?.accept(targetDirectory, markerFile)
        return targetDirectory
    }
}
