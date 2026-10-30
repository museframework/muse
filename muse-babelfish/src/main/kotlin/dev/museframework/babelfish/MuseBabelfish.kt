package dev.museframework.babelfish

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.mapper.MuleDocumentMapper
import dev.museframework.babelfish.mapper.TranslationResult
import dev.museframework.babelfish.parser.MuleXmlParser
import java.nio.file.Path

object MuseBabelfish {
    fun translateXmlToMuseKotlinDsl(
        file: Path,
        registry: BabelfishRegistry,
        lenient: Boolean = false,
    ): TranslationResult {
        val doc = MuleXmlParser().parse(file)
        return MuleDocumentMapper(registry).map(doc, lenient)
    }
}
