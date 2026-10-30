package dev.museframework.plugin.maven.mojo

import dev.museframework.plugin.core.codegen.GeneratedFile
import dev.museframework.plugin.core.codegen.MuseAppConfig
import dev.museframework.plugin.core.runtime.*
import dev.museframework.plugin.core.translation.MissingMapperTranslationException
import dev.museframework.plugin.core.translation.MuseTranslationException
import dev.museframework.plugin.core.translation.TranslatorFacade
import dev.museframework.plugin.maven.util.PackageNameResolver
import org.apache.maven.model.Resource
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.format.DateTimeFormatter

@Mojo(
    name = "generate",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
    threadSafe = true,
)
class MuseGenerateMojo : AbstractMojo() {
    @Parameter(
        defaultValue = "\${project.basedir}/src/main/mule",
        property = "muse.sourceDirectory",
        required = true,
    )
    lateinit var sourceDirectory: File

    @Parameter(
        defaultValue = "\${project.build.directory}/generated-sources/muse",
        property = "muse.outputDirectory",
        required = true,
    )
    lateinit var outputDirectory: File

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var mavenProject: MavenProject

    @Parameter(property = "muse.runtime", required = false)
    var runtime: String? = null

    @Parameter(property = "muse.packageName", required = false)
    var packageName: String? = null

    @Parameter(property = "muse.skip", defaultValue = "false")
    var skip: Boolean = false

    @Parameter(property = "muse.nativeBuild", defaultValue = "false")
    var nativeBuild: Boolean = false

    @Parameter(property = "muse.lenient", defaultValue = "false")
    var lenient: Boolean = false

    override fun execute() {
        if (skip) {
            log.info("Muse generate skipped (muse.skip=true)")
            return
        }

        val artifacts =
            mavenProject.artifacts.map { a ->
                MavenArtifact(a.groupId, a.artifactId, a.scope ?: "compile")
            }
        val resolvedRuntime =
            try {
                RuntimeResolver.resolve(runtime, artifacts)
            } catch (e: MuseRuntimeException) {
                throw MojoExecutionException(e.message, e)
            }
        log.info("Muse runtime: ${resolvedRuntime.configKey}")

        val resolvedPackage = PackageNameResolver.resolve(packageName, mavenProject.groupId)

        val strategy = RuntimeStrategyFactory.get(resolvedRuntime)

        val translationResults =
            try {
                TranslatorFacade().translateAll(
                    sourceDirectory = sourceDirectory,
                    packageName = resolvedPackage,
                    configurationClassSpec = strategy.configurationClassSpec(),
                    nativeBuild = nativeBuild,
                    lenient = lenient,
                )
            } catch (e: MissingMapperTranslationException) {
                // The message already contains every missing element and the
                // remediation. Retaining its cause here makes Maven append the
                // raw Babelfish list and duplicate the user-facing diagnostic.
                throw MojoExecutionException(e.message)
            } catch (e: MuseTranslationException) {
                throw MojoExecutionException(e.message, e)
            }
        val dslFiles = translationResults.flatMap { it.generatedFiles }
        translationResults.flatMap { it.warnings }.forEach { log.warn(it) }

        val configClassNames =
            dslFiles.map { f ->
                f.relativePath.substringAfterLast('/').removeSuffix(".kt")
            }
        val appConfig =
            MuseAppConfig(
                packageName = resolvedPackage,
                applicationName = toApplicationName(mavenProject.artifactId),
                flowConfigClassNames = configClassNames,
                artifacts = artifacts,
            )

        strategy.validate(appConfig).forEach { error ->
            if (error.fatal) {
                throw MojoExecutionException(error.message)
            } else {
                log.warn(error.message)
            }
        }

        val runtimeFiles = strategy.generateRuntimeFiles(appConfig)

        val allFiles = dslFiles + runtimeFiles
        val header = buildHeader()
        outputDirectory.mkdirs()
        val outputRoot = outputDirectory.toPath().toAbsolutePath().normalize()
        val generatedRelativePaths = mutableListOf<String>()
        cleanPreviouslyGeneratedFiles(outputRoot)
        allFiles.forEach { file ->
            val relativePath = file.relativePath
            validateRelativePath(outputRoot, relativePath)
            val target = outputRoot.resolve(relativePath)
            Files.createDirectories(target.parent)
            val content =
                if (file.kind == GeneratedFile.Kind.KOTLIN_SOURCE) {
                    header + file.content
                } else {
                    file.content
                }
            Files.writeString(target, content, Charsets.UTF_8)
            generatedRelativePaths.add(relativePath)
            log.debug("Written: $target")
        }
        writeGeneratedFilesManifest(outputRoot, generatedRelativePaths)

        mavenProject.addCompileSourceRoot(outputDirectory.absolutePath)
        if (allFiles.any { it.kind == GeneratedFile.Kind.RESOURCE }) {
            mavenProject.addResource(
                Resource().apply {
                    directory = outputRoot.resolve("resources").toString()
                },
            )
        }
        log.info("Muse generate complete: ${allFiles.size} file(s) in $outputDirectory")
    }

    private fun cleanPreviouslyGeneratedFiles(outputRoot: Path) {
        val manifestFile = outputRoot.resolve(GENERATED_FILES_MANIFEST)
        if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) return
        Files.readAllLines(manifestFile, Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { relativePath ->
                val stale = resolveChild(outputRoot, relativePath)
                if (Files.isRegularFile(stale, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.deleteIfExists(stale)
                        log.debug("Removed stale generated file: $stale")
                    } catch (e: IOException) {
                        throw MojoExecutionException("Failed to remove stale generated file: $stale", e)
                    }
                }
                stale.parent?.let { parent ->
                    if (parent != outputRoot && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            Files.deleteIfExists(parent)
                        } catch (e: IOException) {
                            // parent may legitimately contain other files
                        }
                    }
                }
            }
    }

    private fun writeGeneratedFilesManifest(outputRoot: Path, relativePaths: List<String>) {
        relativePaths.forEach { validateRelativePath(outputRoot, it) }
        val target = outputRoot.resolve(GENERATED_FILES_MANIFEST)
        val temp = Files.createTempFile(outputRoot, ".muse-generated-files", ".tmp")
        try {
            Files.writeString(
                temp,
                relativePaths.joinToString("\n", postfix = "\n"),
                Charsets.UTF_8,
            )
            Files.move(
                temp,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            try {
                Files.deleteIfExists(temp)
            } catch (_: IOException) {
                // best-effort cleanup
            }
            throw MojoExecutionException("Failed to write generated files manifest: $target", e)
        }
    }

    private fun resolveChild(outputRoot: Path, relativePath: String): Path {
        if (relativePath.isBlank()) {
            throw MojoExecutionException("Generated file relative path must not be blank")
        }
        if (Path.of(relativePath).isAbsolute) {
            throw MojoExecutionException("Generated file relative path must not be absolute: $relativePath")
        }
        val candidate = outputRoot.resolve(relativePath).normalize()
        if (!candidate.startsWith(outputRoot)) {
            throw MojoExecutionException("Generated file path escapes output directory: $relativePath")
        }
        if (candidate == outputRoot) {
            throw MojoExecutionException("Generated file path cannot be the output directory itself: $relativePath")
        }
        return candidate
    }

    private fun validateRelativePath(outputRoot: Path, relativePath: String) {
        resolveChild(outputRoot, relativePath)
    }

    companion object {
        private const val GENERATED_FILES_MANIFEST = ".muse-generated-files"
    }

    private fun buildHeader(): String {
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val pluginVersion =
            mavenProject.build
                ?.plugins
                ?.firstOrNull {
                    it.groupId == "dev.museframework" && it.artifactId == "muse-maven-plugin"
                }?.version ?: "unknown"
        val babelfishVersion =
            mavenProject.artifacts
                .firstOrNull {
                    it.artifactId == "muse-babelfish"
                }?.version ?: "unknown"

        return """
            // DO NOT EDIT — generated by muse-maven-plugin $pluginVersion
            // Generated at: $timestamp
            // Babelfish version: $babelfishVersion
            //

            """.trimIndent()
    }

    private fun toApplicationName(artifactId: String): String =
        artifactId
            .split("-", "_")
            .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
}
