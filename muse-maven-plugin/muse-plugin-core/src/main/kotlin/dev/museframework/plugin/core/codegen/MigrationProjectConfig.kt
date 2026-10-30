package dev.museframework.plugin.core.codegen

/** Runtime-neutral inputs used to generate a migrated application's Maven POM. */
data class MigrationProjectConfig(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val namespaces: Set<String>,
    val packageName: String,
    val nativeBuild: Boolean = false,
)
