package dev.museframework.babelfish.mappers.file

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.file.api.config.FileConnectionConfig
import dev.museframework.file.api.config.FileCopyConfig
import dev.museframework.file.api.config.FileCreateDirectoryConfig
import dev.museframework.file.api.config.FileDeleteConfig
import dev.museframework.file.api.config.FileListConfig
import dev.museframework.file.api.config.FileListenerConfig
import dev.museframework.file.api.config.FileMatcherConfig
import dev.museframework.file.api.config.FileMoveConfig
import dev.museframework.file.api.config.FileReadConfig
import dev.museframework.file.api.config.FileRenameConfig
import dev.museframework.file.api.config.FileWriteConfig

object FileMappersRegistrar {
    fun register(registry: BabelfishRegistry) {
        registry.registerConfig("file", "config", FileConnectionConfigMapper)
        registry.registerConfig("file", "matcher", FileMatcherConfigMapper)
        registry.registerSource("file", "listener", FileListenerMapper)
        registry.registerElement("file", "read", FileReadMapper)
        registry.registerElement("file", "write", FileWriteMapper)
        registry.registerElement("file", "list", FileListMapper)
        registry.registerElement("file", "copy", FileCopyMapper)
        registry.registerElement("file", "move", FileMoveMapper)
        registry.registerElement("file", "delete", FileDeleteMapper)
        registry.registerElement("file", "rename", FileRenameMapper)
        registry.registerElement("file", "create-directory", FileCreateDirectoryMapper)
    }

    fun registerEmitters(emitter: KotlinCodeEmitter) {
        emitter.registerConfigEmitter(FileConnectionConfig::class, FileConnectionConfigCodeEmitter)
        emitter.registerConfigEmitter(FileMatcherConfig::class, FileMatcherConfigCodeEmitter)
        emitter.registerSourceEmitter(FileListenerConfig::class, FileListenerCodeEmitter)
        emitter.registerElementEmitter(FileReadConfig::class, FileReadCodeEmitter)
        emitter.registerElementEmitter(FileWriteConfig::class, FileWriteCodeEmitter)
        emitter.registerElementEmitter(FileListConfig::class, FileListCodeEmitter)
        emitter.registerElementEmitter(FileCopyConfig::class, FileCopyCodeEmitter)
        emitter.registerElementEmitter(FileMoveConfig::class, FileMoveCodeEmitter)
        emitter.registerElementEmitter(FileDeleteConfig::class, FileDeleteCodeEmitter)
        emitter.registerElementEmitter(FileRenameConfig::class, FileRenameCodeEmitter)
        emitter.registerElementEmitter(FileCreateDirectoryConfig::class, FileCreateDirectoryCodeEmitter)
    }
}
