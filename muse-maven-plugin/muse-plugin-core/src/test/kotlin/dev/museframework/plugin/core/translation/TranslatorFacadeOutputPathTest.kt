package dev.museframework.plugin.core.translation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Locale
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class TranslatorFacadeOutputPathTest {
    @Test
    fun `output path collision detection is case insensitive for portable generation`(@TempDir dir: Path) {
        val facade = TranslatorFacade(TranslatorRegistry.of(emptyList()))
        val first = dir.resolve("first").resolve("order-XML.xml")
        val second = dir.resolve("second").resolve("order-Xml.xml")
        first.parent.toFile().mkdirs()
        second.parent.toFile().mkdirs()
        first.toFile().writeText("<mule/>")
        second.toFile().writeText("<mule/>")

        val error = assertFailsWith<MuseTranslationException> { facade.translateAll(dir.toFile(), "com.example") }

        assertContains(error.message!!, first.toString())
        assertContains(error.message!!, second.toString())
        assertContains(error.message!!.lowercase(Locale.ROOT), "com/example/orderxmlconfig.kt")
    }
}
