package dev.museframework.plugin.maven.util

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PackageNameResolverTest {

    @Test
    fun `uses customPackage when provided`() {
        assertEquals("my.app", PackageNameResolver.resolve("my.app", "com.foo"))
    }

    @Test
    fun `falls back to groupId when customPackage is null`() {
        assertEquals("com.foo", PackageNameResolver.resolve(null, "com.foo"))
    }

    @Test
    fun `falls back to groupId when customPackage is blank`() {
        assertEquals("com.foo", PackageNameResolver.resolve("", "com.foo"))
        assertEquals("com.foo", PackageNameResolver.resolve("  ", "com.foo"))
    }

    @Test
    fun `falls back to default when both are null`() {
        assertEquals("com.example.muse", PackageNameResolver.resolve(null, null))
    }

    @Test
    fun `falls back to default when groupId is blank`() {
        assertEquals("com.example.muse", PackageNameResolver.resolve(null, ""))
    }

    @Test
    fun `lowercases groupId`() {
        assertEquals("com.example.muse", PackageNameResolver.resolve(null, "com.example.Muse"))
    }
}
