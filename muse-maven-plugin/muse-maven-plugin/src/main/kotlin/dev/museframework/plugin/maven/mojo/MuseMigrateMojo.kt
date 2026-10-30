package dev.museframework.plugin.maven.mojo

import dev.museframework.babelfish.parser.MuleXmlParser
import dev.museframework.plugin.core.check.CompatibilityReport
import dev.museframework.plugin.core.check.ComponentType
import dev.museframework.plugin.core.check.MuseCompatibilityChecker
import dev.museframework.plugin.core.codegen.GeneratedFile
import dev.museframework.plugin.core.codegen.MigrationProjectConfig
import dev.museframework.plugin.core.codegen.MuseAppConfig
import dev.museframework.plugin.core.runtime.*
import dev.museframework.plugin.core.translation.MuseTranslationException
import dev.museframework.plugin.core.translation.TranslatorFacade
import dev.museframework.plugin.core.translation.TranslatorRegistry
import dev.museframework.plugin.maven.util.PackageNameResolver
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.project.MavenProject
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

@Mojo(
    name = "migrate",
    threadSafe = true,
)
class MuseMigrateMojo : AbstractMojo() {

    @Parameter(
        defaultValue = "\${project.basedir}/src/main/mule",
        property = "muse.sourceDirectory",
        required = true,
    )
    lateinit var sourceDirectory: File

    @Parameter(property = "muse.skip", defaultValue = "false")
    var skip: Boolean = false

    @Parameter(property = "muse.packageName", required = false)
    var packageName: String? = null

    @Parameter(property = "muse.nativeBuild", defaultValue = "false")
    var nativeBuild: Boolean = false

    @Parameter(property = "muse.runtime", required = false)
    var runtime: String? = null

    @Parameter(defaultValue = "\${project}", readonly = true, required = true)
    lateinit var mavenProject: MavenProject

    override fun execute() {
        if (skip) {
            log.info("Muse migrate skipped (muse.skip=true)")
            return
        }

        if (!sourceDirectory.exists()) {
            log.warn("Source directory does not exist: $sourceDirectory — skipping")
            return
        }

        val xmlFiles =
            sourceDirectory
                .walkTopDown()
                .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
                .toList()
        if (xmlFiles.isEmpty()) {
            log.warn("No XML files found in $sourceDirectory — nothing to migrate")
            return
        }

        val artifacts =
            mavenProject.artifacts.map { artifact ->
                MavenArtifact(artifact.groupId, artifact.artifactId, artifact.scope ?: "compile")
            }
        val detectedRuntime =
            try {
                RuntimeResolver.resolve(runtime, artifacts)
            } catch (e: MuseRuntimeException) {
                throw MojoExecutionException(e.message, e)
            }
        val resolvedRuntime =
            if (detectedRuntime == MuseRuntime.None) {
                MuseRuntime.SpringBoot
            } else {
                detectedRuntime
            }
        val strategy = RuntimeStrategyFactory.get(resolvedRuntime)
        val resolvedPackage = PackageNameResolver.resolve(packageName, mavenProject.groupId)
        val applicationName =
            mavenProject.artifactId
                .split("-", "_")
                .joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }

        log.info("Muse Migrate — migrating ${xmlFiles.size} XML file(s)")
        log.info("  Runtime: ${resolvedRuntime.configKey}")
        log.info("  Package: $resolvedPackage")
        log.info("  Application: $applicationName")
        log.info("  Native build: $nativeBuild")
        log.info("")

        val registry = TranslatorRegistry.load().createRegistry()
        val parser = MuleXmlParser()
        val documents = xmlFiles.map { parser.parse(it.toPath()) }
        val checker = MuseCompatibilityChecker(registry)
        val compatibilityReport = checker.check(documents)
        logResults(compatibilityReport)

        val translationResults =
            try {
                TranslatorFacade().translateAll(
                    sourceDirectory = sourceDirectory,
                    packageName = resolvedPackage,
                    configurationClassSpec = strategy.configurationClassSpec(),
                    nativeBuild = nativeBuild,
                    lenient = true,
                )
            } catch (e: MuseTranslationException) {
                throw MojoExecutionException(e.message, e)
            }
        translationResults.flatMap { it.warnings }.forEach { log.warn(it) }
        val generatedFlows = translationResults.flatMap { it.generatedFiles }
        val configClassNames =
            generatedFlows.map { it.relativePath.substringAfterLast('/').removeSuffix(".kt") }
        val appConfig =
            MuseAppConfig(
                packageName = resolvedPackage,
                applicationName = applicationName,
                flowConfigClassNames = configClassNames,
                artifacts = artifacts,
                namespaces = compatibilityReport.namespacesFound,
            )
        strategy.validateMigration(appConfig).forEach { error ->
            if (error.fatal) {
                throw MojoExecutionException(error.message)
            }
            log.warn(error.message)
        }
        val runtimeFiles = strategy.generateRuntimeFiles(appConfig)
        val pomConfig =
            MigrationProjectConfig(
                groupId = mavenProject.groupId ?: "com.example",
                artifactId = mavenProject.artifactId,
                version = mavenProject.version ?: "0.1.0-SNAPSHOT",
                namespaces = compatibilityReport.namespacesFound,
                packageName = resolvedPackage,
                nativeBuild = nativeBuild,
            )

        val projectDir = mavenProject.basedir.toPath()
        val pomFile = projectDir.resolve("pom.xml")
        val backupFile = projectDir.resolve("pom.xml.mulesoft")
        if (pomFile.toFile().exists() && !backupFile.toFile().exists()) {
            pomFile.toFile().renameTo(backupFile.toFile())
            log.info("Backed up pom.xml → pom.xml.mulesoft")
        }
        pomFile.toFile().writeText(strategy.generatePom(pomConfig), Charsets.UTF_8)
        log.info("Generated ${resolvedRuntime.configKey} pom.xml")

        val kotlinDir = projectDir.resolve("src/main/kotlin")
        kotlinDir.toFile().mkdirs()
        generatedFlows.forEach { file ->
            val target = kotlinDir.resolve(file.relativePath)
            target.parent.toFile().mkdirs()
            target.toFile().writeText(file.content, Charsets.UTF_8)
            log.debug("Written: ${target.toAbsolutePath()}")
        }
        log.info("Generated ${generatedFlows.size} Kotlin flow file(s)")

        runtimeFiles.forEach { file ->
            val target =
                when (file.kind) {
                    GeneratedFile.Kind.KOTLIN_SOURCE -> kotlinDir.resolve(file.relativePath)
                    GeneratedFile.Kind.RESOURCE -> projectDir.resolve("src/main/${file.relativePath}")
                }
            target.parent.toFile().mkdirs()
            val content =
                if (file.kind == GeneratedFile.Kind.KOTLIN_SOURCE) {
                    buildHeader() + file.content
                } else {
                    file.content
                }
            target.toFile().writeText(content, Charsets.UTF_8)
            log.info("Generated runtime file: $target")
        }

        generateGitignore(projectDir)

        log.info("")
        log.info("Migration complete!")
        log.info("  Runtime: ${resolvedRuntime.configKey}")
        log.info("  Kotlin sources: src/main/kotlin/$resolvedPackage")
        log.info("  Original POM backed up: pom.xml.mulesoft")
        log.info("")
        log.info("Next steps:")
        log.info("  1. Review generated files in src/main/kotlin/")
        log.info("  2. Add any missing dependencies from pom.xml.mulesoft")
        log.info("  3. Run: mvn compile")
        if (nativeBuild) {
            log.info("  4. Build native image: mvn package -Pnative")
        }
    }

    private fun logResults(report: CompatibilityReport) {
        for (fileReport in report.fileReports) {
            log.info("--- ${fileReport.sourceFile.fileName} ---")
            for (result in fileReport.results) {
                val indent = "  ".repeat(result.depth)
                val marker = if (result.implemented) "\u2705" else "\u274C"
                val typeLabel = when (result.type) {
                    ComponentType.SOURCE -> "SOURCE"
                    ComponentType.PROCESSOR -> "PROCESSOR"
                    ComponentType.CONFIG -> "CONFIG"
                }
                val fullTag = if (result.namespace.isEmpty()) result.tag else "${result.namespace}:${result.tag}"
                val msg = if (result.implemented) "" else " -- NOT IMPLEMENTED in Muse"
                log.info("$indent$marker [$typeLabel] $fullTag$msg")
            }
        }

        log.info("")
        log.info("${report.totalComponents} components checked, ${report.totalUnmapped} not implemented")
    }

    private fun generateGitignore(projectDir: java.nio.file.Path) {
        val file = projectDir.resolve(".gitignore")
        if (file.toFile().exists()) return
        file.toFile().writeText(
            """
            # Build directories
            target/
            build/
            out/

            # IDE files
            .idea/
            *.iml
            .vscode/

            # OS files
            .DS_Store
            Thumbs.db

            # Logs
            *.log

            # Maven wrapper
            .mvn/wrapper/
            """.trimIndent(),
            Charsets.UTF_8,
        )
        log.info("Generated .gitignore")
    }

    private fun buildHeader(): String {
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        return """
            // DO NOT EDIT — generated by muse-maven-plugin (migrate)
            // Generated at: $timestamp
            // Original MuleSoft XML: src/main/mule/
            //

            """.trimIndent()
    }
}
