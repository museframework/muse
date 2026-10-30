package dev.museframework.plugin.maven.util

object PackageNameResolver {
    fun resolve(customPackage: String?, groupId: String?): String =
        customPackage?.takeIf { it.isNotBlank() }
            ?: groupId?.takeIf { it.isNotBlank() }?.lowercase()
            ?: "com.example.muse"
}
