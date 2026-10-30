package dev.museframework.sftp.api.dsl

import dev.museframework.dsl.api.annotation.MuseDsl
import dev.museframework.dsl.api.util.CallSiteCapture
import dev.museframework.sftp.api.config.SftpConnectionConfig
import dev.museframework.sftp.api.config.SftpConnectionRef
import dev.museframework.sftp.api.config.SftpHostKeyChecking
import java.util.UUID

@MuseDsl
class SftpConnectionBuilder {
    var name: String = ""
    var docName: String = ""
    var docId: String = UUID.randomUUID().toString()
    var docDescription: String? = null
    var sourceFile: String? = null
    var sourceLine: Int? = null
    var sourceClass: String? = null
    var host: String = ""
    var port: Int = 22
    var portExpression: String? = null
    var username: String = ""
    var password: String? = null
    var privateKeyPath: String? = null
    var privateKeyPassphrase: String? = null
    var knownHostsFile: String = "~/.ssh/known_hosts"
    var hostKeyChecking: SftpHostKeyChecking = SftpHostKeyChecking.STRICT
    var workingDirectory: String = "/"
    var connectionTimeoutMs: Long = 10_000
    var authenticationTimeoutMs: Long = 10_000
    var operationTimeoutMs: Long = 30_000

    fun build(): SftpConnectionConfig {
        val callSite = CallSiteCapture.capture()
        return SftpConnectionConfig(
            name = name,
            docName = docName,
            docId = docId,
            docDescription = docDescription,
            sourceFile = sourceFile ?: callSite.sourceFile,
            sourceLine = sourceLine ?: callSite.sourceLine,
            sourceClass = sourceClass ?: callSite.sourceClass,
            host = host,
            port = port,
            username = username,
            password = password,
            privateKeyPath = privateKeyPath,
            privateKeyPassphrase = privateKeyPassphrase,
            knownHostsFile = knownHostsFile,
            hostKeyChecking = hostKeyChecking,
            workingDirectory = workingDirectory,
            connectionTimeoutMs = connectionTimeoutMs,
            authenticationTimeoutMs = authenticationTimeoutMs,
            operationTimeoutMs = operationTimeoutMs,
            portExpression = portExpression,
        )
    }
}

fun sftpConnection(block: SftpConnectionBuilder.() -> Unit): SftpConnectionConfig =
    SftpConnectionBuilder().apply(block).build()

fun sftpConnection(name: String): SftpConnectionRef = SftpConnectionRef.ByName(name)

fun sftpConnection(config: SftpConnectionConfig): SftpConnectionRef = SftpConnectionRef.ByReference(config)
