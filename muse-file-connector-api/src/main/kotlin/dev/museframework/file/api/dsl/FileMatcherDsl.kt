package dev.museframework.file.api.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.file.api.config.FileMatcherConfig
import dev.museframework.file.api.model.LocalFileMatcher
import java.util.UUID

@MuseDsl
class FileMatcherBuilder {
    var name: String = ""
    var docName: String = ""
    var docId: String = UUID.randomUUID().toString()
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var matcher: LocalFileMatcher = LocalFileMatcher()

    fun build(): FileMatcherConfig {
        val callSite = CallSiteCapture.capture()
        val resolvedName = name.ifBlank { docName }
        return FileMatcherConfig(
            name = resolvedName,
            docName = docName.ifBlank { resolvedName },
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            matcher = matcher,
        )
    }
}

fun fileMatcher(block: FileMatcherBuilder.() -> Unit = {}): FileMatcherConfig =
    FileMatcherBuilder().apply(block).build()
