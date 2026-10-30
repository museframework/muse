package dev.museframework.plugin.core.runtime

/**
 * How to run the generated application.
 *
 * [DelegateMavenPlugin] delegates to a runtime's dedicated Maven plugin
 * (Spring Boot → spring-boot:run, Quarkus → quarkus:dev).
 *
 * [ExecMainClass] is the fallback — invokes exec:java with the generated main class.
 * Useful for Ktor or standalone JVM targets.
 */
sealed class RunCommand {

    data class DelegateMavenPlugin(
        val groupId: String,
        val artifactId: String,
        val goal: String,
        val configuration: Map<String, String> = emptyMap()
    ) : RunCommand()

    data class ExecMainClass(
        val mainClass: String,
        val jvmArgs: List<String> = emptyList()
    ) : RunCommand()
}
