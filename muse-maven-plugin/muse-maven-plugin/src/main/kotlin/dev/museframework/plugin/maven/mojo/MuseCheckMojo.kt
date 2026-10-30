package dev.museframework.plugin.maven.mojo

import dev.museframework.babelfish.parser.MuleXmlParser
import dev.museframework.plugin.core.check.ComponentType
import dev.museframework.plugin.core.check.MuseCompatibilityChecker
import dev.museframework.plugin.core.translation.TranslatorRegistry
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import java.io.File

@Mojo(
    name = "check",
    defaultPhase = LifecyclePhase.VALIDATE,
    threadSafe = true,
)
class MuseCheckMojo : AbstractMojo() {

    @Parameter(
        defaultValue = "\${project.basedir}/src/main/mule",
        property = "muse.sourceDirectory",
        required = true,
    )
    lateinit var sourceDirectory: File

    @Parameter(property = "muse.check.skip", defaultValue = "false")
    var skip: Boolean = false

    @Parameter(property = "muse.lenient", defaultValue = "false")
    var lenient: Boolean = false

    override fun execute() {
        if (skip) {
            log.info("Muse check skipped (muse.check.skip=true)")
            return
        }

        if (!sourceDirectory.exists()) {
            log.warn("Source directory does not exist: $sourceDirectory — skipping")
            return
        }

        val xmlFiles = sourceDirectory
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
            .toList()

        if (xmlFiles.isEmpty()) {
            log.info("No XML files found in $sourceDirectory")
            return
        }

        log.info("Muse Check — scanning ${xmlFiles.size} file(s) from $sourceDirectory")
        log.info("")

        val registry = TranslatorRegistry.load().createRegistry()
        val parser = MuleXmlParser()
        val documents = xmlFiles.map { parser.parse(it.toPath()) }

        val checker = MuseCompatibilityChecker(registry)
        val report = checker.check(documents)

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
            log.info("")
        }

        log.info("Muse Check SUMMARY: ${report.totalComponents} components checked")
        if (report.totalUnmapped > 0) {
            log.warn("  ${report.totalUnmapped} component(s) not implemented in Muse")
        }

        if (report.hasBlockingIssues()) {
            if (lenient) {
                log.warn("Build would fail in strict mode (use -Dmuse.lenient=false)")
            } else {
                throw MojoExecutionException(
                    "Muse check failed: ${report.totalUnmapped} unmapped component(s)"
                )
            }
        } else {
            log.info("  All components are implemented in Muse")
        }
    }
}
