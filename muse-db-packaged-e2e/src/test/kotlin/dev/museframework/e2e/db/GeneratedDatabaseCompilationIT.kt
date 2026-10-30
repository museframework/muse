package dev.museframework.e2e.db

import dev.museframework.dsl.api.config.MuseConfigDefinition
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GeneratedDatabaseCompilationIT {
    @Test
    fun `generated database configuration and application classes load from compiled bytecode`() {
        val databaseConfig = loadGeneratedClass("DatabaseConfig")
        assertTrue(
            MuseConfigDefinition::class.java.isAssignableFrom(databaseConfig),
            "${databaseConfig.name} must extend MuseConfigDefinition",
        )
        assertNotNull(databaseConfig.getDeclaredConstructor().newInstance())

        val applicationClass = loadGeneratedClass("MuseDbPackagedE2eApplicationKt")
        val mainMethod = applicationClass.getDeclaredMethod("main", Array<String>::class.java)
        assertTrue(Modifier.isPublic(mainMethod.modifiers))
        assertTrue(Modifier.isStatic(mainMethod.modifiers))
        assertEquals(Void.TYPE, mainMethod.returnType)
    }

    private fun loadGeneratedClass(simpleName: String): Class<*> =
        Class.forName("$GENERATED_PACKAGE.$simpleName")

    private companion object {
        const val GENERATED_PACKAGE = "dev.museframework.e2e.db.generated"
    }
}
