package dev.museframework.plugin.core.runtime

/**
 * Detects which Muse runtime is present based on the project's dependency list.
 *
 * Detection uses artifactId substring matching on compile/runtime scope artifacts only.
 * The markers map is the single source of truth — add new runtimes here.
 *
 * The map is a LinkedHashMap to guarantee evaluation order: more specific markers
 * (e.g. "muse-spring-boot") appear before generic ones (e.g. "spring-boot").
 */
object RuntimeDetector {

    private val markers: Map<String, MuseRuntime> = linkedMapOf(
        "muse-spring-boot"         to MuseRuntime.SpringBoot,
        "spring-boot-autoconfigure" to MuseRuntime.SpringBoot,
        "spring-boot"               to MuseRuntime.SpringBoot,
        "muse-quarkus"             to MuseRuntime.Quarkus,
        "quarkus-core"              to MuseRuntime.Quarkus,
        "quarkus-arc"               to MuseRuntime.Quarkus,
        "muse-ktor"                to MuseRuntime.Ktor,
        "ktor-server-core"          to MuseRuntime.Ktor,
        "ktor-server"               to MuseRuntime.Ktor
    )

    sealed class DetectionResult {
        data class Found(val runtime: MuseRuntime) : DetectionResult()
        data class Ambiguous(val found: List<MuseRuntime>) : DetectionResult()
        object NotFound : DetectionResult()
    }

    fun detect(artifacts: List<MavenArtifact>): DetectionResult {
        val relevant = artifacts.filter { it.isRelevant() }
        val matched: List<MuseRuntime> = markers.entries
            .filter { (marker, _) -> relevant.any { marker in it.artifactId } }
            .map { it.value }
            .distinct()

        return when (matched.size) {
            0    -> DetectionResult.NotFound
            1    -> DetectionResult.Found(matched.first())
            else -> DetectionResult.Ambiguous(matched)
        }
    }
}
