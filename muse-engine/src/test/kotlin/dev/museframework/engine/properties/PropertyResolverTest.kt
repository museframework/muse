package dev.museframework.engine.properties

import dev.museframework.engine.properties.normalizer.PropertyNameNormalizer
import dev.museframework.engine.properties.resolver.PropertySourceBuilder
import dev.museframework.engine.properties.source.*
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PropertyResolverTest {
    
    @Test
    fun `priority ordering - higher priority wins`() {
        val resolver = PropertySourceBuilder()
            .withGlobalProperty("db.host", "default-value", PropertyPriority.DEFAULT)
            .withGlobalProperty("db.host", "env-value", PropertyPriority.ENVIRONMENT)
            .withGlobalProperty("db.host", "jvm-value", PropertyPriority.JVM_SYSTEM)
            .build()
        
        assertEquals("jvm-value", resolver.resolve("db.host"))
    }
    
    @Test
    fun `priority ordering - JVM overrides environment`() {
        val resolver = PropertySourceBuilder()
            .withEnvironment()
            .withJvmProperties()
            .build()
        
        val jvmSource = resolver.sources.find { it.sourceType == PropertySourceType.JVM_SYSTEM }
        val envSource = resolver.sources.find { it.sourceType == PropertySourceType.ENVIRONMENT }
        
        assertNotNull(jvmSource)
        assertNotNull(envSource)
        assertTrue(jvmSource.priority > envSource.priority)
    }
    
    @Test
    fun `placeholder resolution - simple reference`() {
        val resolver = PropertySourceBuilder()
            .withGlobalProperty("db.host", "localhost")
            .withGlobalProperty("db.url", "jdbc:postgresql://\${db.host}:5432/mydb")
            .build()
        
        assertEquals("jdbc:postgresql://localhost:5432/mydb", resolver.resolve("db.url"))
    }
    
    @Test
    fun `placeholder resolution - with default value`() {
        val resolver = PropertySourceBuilder()
            .withGlobalProperty("greeting", "Hello, \${name:World}!")
            .build()
        
        assertEquals("Hello, World!", resolver.resolve("greeting"))
    }
    
    @Test
    fun `placeholder resolution - nested placeholders`() {
        val resolver = PropertySourceBuilder()
            .withGlobalProperty("host", "localhost")
            .withGlobalProperty("port", "5432")
            .withGlobalProperty("db.host", "\${host}")
            .withGlobalProperty("db.url", "jdbc:postgresql://\${db.host}:\${port}/mydb")
            .build()
        
        assertEquals("jdbc:postgresql://localhost:5432/mydb", resolver.resolve("db.url"))
    }
    
    @Test
    fun `circular reference detection`() {
        val resolver = PropertySourceBuilder()
            .withGlobalProperty("a", "\${b}")
            .withGlobalProperty("b", "\${a}")
            .build()
        
        assertFailsWith<CircularPropertyReferenceException> {
            resolver.resolve("a")
        }
    }
    
    @Test
    fun `resolveRequired throws when not found`() {
        val resolver = PropertySourceBuilder().build()
        
        assertFailsWith<PropertyNotFoundException> {
            resolver.resolveRequired("nonexistent.property")
        }
    }
    
    @Test
    fun `resolveOrDefault returns default when not found`() {
        val resolver = PropertySourceBuilder().build()
        
        assertEquals("fallback", resolver.resolveOrDefault("nonexistent", "fallback"))
    }
    
    @Test
    fun `toMap creates resolved snapshot`() {
        val resolver = PropertySourceBuilder()
            .withGlobalProperty("db.host", "localhost")
            .withGlobalProperty("db.port", "5432")
            .withGlobalProperty("db.url", "jdbc:postgresql://\${db.host}:\${db.port}/mydb")
            .build()
        
        val map = resolver.toMap()
        
        assertEquals("localhost", map["db.host"])
        assertEquals("5432", map["db.port"])
        assertEquals("jdbc:postgresql://localhost:5432/mydb", map["db.url"])
    }
    
    @Test
    fun `getSourceForProperty identifies source`() {
        val resolver = PropertySourceBuilder()
            .withGlobalProperty("app.name", "myapp", PropertyPriority.DEFAULT)
            .withGlobalProperty("app.version", "1.0", PropertyPriority.ENVIRONMENT)
            .build()
        
        val nameSource = resolver.getSourceForProperty("app.name")
        val versionSource = resolver.getSourceForProperty("app.version")
        
        assertNotNull(nameSource)
        assertNotNull(versionSource)
        assertEquals(PropertyPriority.DEFAULT, nameSource.priority)
        assertEquals(PropertyPriority.ENVIRONMENT, versionSource.priority)
    }
}

class PropertyNameNormalizerTest {
    
    private val normalizer = PropertyNameNormalizer
    
    @Test
    fun `normalize dot notation`() {
        assertEquals("db.host", normalizer.normalizeToDotNotation("db.host"))
    }
    
    @Test
    fun `normalize kebab case`() {
        assertEquals("db.host", normalizer.normalizeToDotNotation("db-host"))
    }
    
    @Test
    fun `normalize upper snake case`() {
        assertEquals("db.host", normalizer.normalizeToDotNotation("DB_HOST"))
    }
    
    @Test
    fun `normalize camelCase`() {
        assertEquals("db.host", normalizer.normalizeToDotNotation("dbHost"))
    }
    
    @Test
    fun `normalize mixed case`() {
        assertEquals("my.app.db.host", normalizer.normalizeToDotNotation("MY_APP_DB_HOST"))
    }
    
    @Test
    fun `generate variants from dot notation`() {
        val variants = normalizer.generateVariants("db.host")
        
        assertTrue(variants.contains("db.host"))
        assertTrue(variants.contains("db-host"))
        assertTrue(variants.contains("DB_HOST"))
        assertTrue(variants.contains("dbHost"))
    }
    
    @Test
    fun `generate variants from multi-part name`() {
        val variants = normalizer.generateVariants("my.app.database.host")
        
        assertTrue(variants.contains("my.app.database.host"))
        assertTrue(variants.contains("my-app-database-host"))
        assertTrue(variants.contains("MY_APP_DATABASE_HOST"))
        assertTrue(variants.contains("myAppDatabaseHost"))
    }
}

class MapPropertySourceTest {
    
    @Test
    fun `getProperty returns value for existing key`() {
        val source = MapPropertySource.of(
            "test",
            PropertyPriority.DEFAULT,
            PropertySourceType.DEFAULT,
            "key1" to "value1",
            "key2" to "value2"
        )
        
        assertEquals("value1", source.getProperty("key1"))
        assertEquals("value2", source.getProperty("key2"))
    }
    
    @Test
    fun `getProperty returns null for missing key`() {
        val source = MapPropertySource.of(
            "test",
            PropertyPriority.DEFAULT,
            PropertySourceType.DEFAULT
        )
        
        assertNull(source.getProperty("nonexistent"))
    }
    
    @Test
    fun `getPropertyNames returns all keys`() {
        val source = MapPropertySource.of(
            "test",
            PropertyPriority.DEFAULT,
            PropertySourceType.DEFAULT,
            "key1" to "value1",
            "key2" to "value2"
        )
        
        assertEquals(setOf("key1", "key2"), source.getPropertyNames())
    }
}

class ProfilePropertySourceWrapperTest {
    
    @Test
    fun `wrapper adjusts priority`() {
        val baseSource = MapPropertySource.of(
            "base",
            PropertyPriority.APPLICATION_FILE,
            PropertySourceType.APPLICATION_FILE,
            "key" to "value"
        )
        
        val wrapped = ProfilePropertySourceWrapper(baseSource, "dev")
        
        assertEquals(PropertyPriority.forProfile(PropertyPriority.APPLICATION_FILE), wrapped.priority)
        assertEquals("base[dev]", wrapped.name)
        assertEquals(PropertySourceType.PROFILE_SPECIFIC, wrapped.sourceType)
    }
    
    @Test
    fun `wrapper delegates property access`() {
        val baseSource = MapPropertySource.of(
            "base",
            PropertyPriority.APPLICATION_FILE,
            PropertySourceType.APPLICATION_FILE,
            "key" to "value"
        )
        
        val wrapped = ProfilePropertySourceWrapper(baseSource, "dev")
        
        assertEquals("value", wrapped.getProperty("key"))
        assertEquals(setOf("key"), wrapped.getPropertyNames())
    }
}