package dev.museframework.babelfish.mappers.sftp

import dev.museframework.babelfish.api.BabelfishRegistry
import dev.museframework.babelfish.emitter.KotlinCodeEmitter
import dev.museframework.sftp.api.config.SftpConnectionConfig
import dev.museframework.sftp.api.config.SftpCopyConfig
import dev.museframework.sftp.api.config.SftpCreateDirectoryConfig
import dev.museframework.sftp.api.config.SftpDeleteConfig
import dev.museframework.sftp.api.config.SftpListConfig
import dev.museframework.sftp.api.config.SftpListenerConfig
import dev.museframework.sftp.api.config.SftpMoveConfig
import dev.museframework.sftp.api.config.SftpReadConfig
import dev.museframework.sftp.api.config.SftpRenameConfig
import dev.museframework.sftp.api.config.SftpWriteConfig

object SftpMappersRegistrar {
    fun register(registry: BabelfishRegistry) {
        registry.registerConfig("sftp", "config", SftpConnectionConfigMapper)
        registry.registerSource("sftp", "listener", SftpListenerMapper)
        registry.registerElement("sftp", "read", SftpReadMapper)
        registry.registerElement("sftp", "write", SftpWriteMapper)
        registry.registerElement("sftp", "list", SftpListMapper)
        registry.registerElement("sftp", "copy", SftpCopyMapper)
        registry.registerElement("sftp", "move", SftpMoveMapper)
        registry.registerElement("sftp", "delete", SftpDeleteMapper)
        registry.registerElement("sftp", "rename", SftpRenameMapper)
        registry.registerElement("sftp", "create-directory", SftpCreateDirectoryMapper)
    }

    fun registerEmitters(emitter: KotlinCodeEmitter) {
        emitter.registerConfigEmitter(SftpConnectionConfig::class, SftpConnectionConfigCodeEmitter)
        emitter.registerSourceEmitter(SftpListenerConfig::class, SftpListenerCodeEmitter)
        emitter.registerElementEmitter(SftpReadConfig::class, SftpReadCodeEmitter)
        emitter.registerElementEmitter(SftpWriteConfig::class, SftpWriteCodeEmitter)
        emitter.registerElementEmitter(SftpListConfig::class, SftpListCodeEmitter)
        emitter.registerElementEmitter(SftpCopyConfig::class, SftpCopyCodeEmitter)
        emitter.registerElementEmitter(SftpMoveConfig::class, SftpMoveCodeEmitter)
        emitter.registerElementEmitter(SftpDeleteConfig::class, SftpDeleteCodeEmitter)
        emitter.registerElementEmitter(SftpRenameConfig::class, SftpRenameCodeEmitter)
        emitter.registerElementEmitter(SftpCreateDirectoryConfig::class, SftpCreateDirectoryCodeEmitter)
    }
}
