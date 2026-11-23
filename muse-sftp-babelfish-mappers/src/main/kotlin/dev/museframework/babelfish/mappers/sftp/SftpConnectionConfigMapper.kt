package dev.museframework.babelfish.mappers.sftp

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.buildCodeBlock
import dev.museframework.babelfish.api.ConfigMapper
import dev.museframework.babelfish.emitter.CodegenContext
import dev.museframework.babelfish.emitter.ConfigCodeEmitter
import dev.museframework.babelfish.emitter.kotlinNumericLiteral
import dev.museframework.babelfish.mapper.MappingContext
import dev.museframework.babelfish.model.RawConfig
import dev.museframework.sftp.api.config.SftpConnectionConfig
import dev.museframework.sftp.api.config.SftpHostKeyChecking
import java.util.concurrent.TimeUnit

object SftpConnectionConfigMapper : ConfigMapper {
    override fun map(
        config: RawConfig,
        ctx: MappingContext,
    ): SftpConnectionConfig {
        val configName = config.attrs["name"] ?: config.id
        val connection =
            config.children.find { it.tag == "connection" }
                ?: throw IllegalArgumentException("sftp:config requires a nested sftp:connection element")
        val password = connection.attrs["password"]
        val privateKeyPath =
            connection.attrs["identityFile"]
                ?: connection.attrs["privateKeyPath"]
                ?: connection.attrs["keyPath"]
        val knownHosts =
            connection.attrs["knownHostsFile"]
                ?: connection.attrs["knownHosts"]
        val hostKeyChecking =
            connection.attrs["hostKeyChecking"]?.toSftpEnum<SftpHostKeyChecking>()
                ?: SftpHostKeyChecking.STRICT

        return SftpConnectionConfig(
            name = configName,
            docName = config.attrs["doc:name"] ?: configName,
            host = connection.requireAttr("host", connection.location),
            port = connection.attrs["port"]?.toIntOrNull() ?: 22,
            portExpression = connection.attrs["port"]?.takeIf { it.toIntOrNull() == null },
            username = connection.requireAttr("username", connection.location),
            password = password,
            privateKeyPath = privateKeyPath,
            privateKeyPassphrase = connection.attrs["passphrase"] ?: connection.attrs["privateKeyPassphrase"],
            knownHostsFile = knownHosts ?: "~/.ssh/known_hosts",
            hostKeyChecking = hostKeyChecking,
            workingDirectory =
                connection.attrs["workingDir"]
                    ?: connection.attrs["workingDirectory"]
                    ?: "/",
            connectionTimeoutMs =
                durationToMillis(
                    connection.attrs["connectionTimeout"],
                    connection.attrs["connectionTimeoutUnit"],
                    TimeUnit.SECONDS,
                ) ?: 10_000L,
            authenticationTimeoutMs =
                durationToMillis(
                    connection.attrs["authenticationTimeout"],
                    connection.attrs["authenticationTimeoutUnit"],
                    TimeUnit.SECONDS,
                ) ?: 10_000L,
            operationTimeoutMs =
                durationToMillis(
                    connection.attrs["responseTimeout"],
                    connection.attrs["responseTimeoutUnit"],
                    TimeUnit.SECONDS,
                ) ?: durationToMillis(
                    connection.attrs["operationTimeout"],
                    connection.attrs["operationTimeoutUnit"],
                    TimeUnit.SECONDS,
                ) ?: 30_000L,
        )
    }
}

object SftpConnectionConfigCodeEmitter : ConfigCodeEmitter<SftpConnectionConfig> {
    override fun emit(
        config: SftpConnectionConfig,
        ctx: CodegenContext,
    ): CodeBlock = buildCodeBlock {
        beginControlFlow("%M", sftpDsl("sftpConnection"))
        addString("name", config.name)
        addString("docName", config.docName)
        addString("host", config.host)
        if (config.port != 22) addStatement("port = %L", kotlinNumericLiteral(config.port))
        addString("portExpression", config.portExpression)
        addString("username", config.username)
        addString("password", config.password)
        addString("privateKeyPath", config.privateKeyPath)
        addString("privateKeyPassphrase", config.privateKeyPassphrase)
        if (config.knownHostsFile != "~/.ssh/known_hosts") addString("knownHostsFile", config.knownHostsFile)
        if (config.hostKeyChecking != SftpHostKeyChecking.STRICT) {
            addStatement("hostKeyChecking = %T.%N", SftpHostKeyChecking::class, config.hostKeyChecking.name)
        }
        if (config.workingDirectory != "/") addString("workingDirectory", config.workingDirectory)
        if (config.connectionTimeoutMs != 10_000L) {
            addStatement("connectionTimeoutMs = %L", kotlinNumericLiteral(config.connectionTimeoutMs))
        }
        if (config.authenticationTimeoutMs != 10_000L) {
            addStatement("authenticationTimeoutMs = %L", kotlinNumericLiteral(config.authenticationTimeoutMs))
        }
        if (config.operationTimeoutMs != 30_000L) {
            addStatement("operationTimeoutMs = %L", kotlinNumericLiteral(config.operationTimeoutMs))
        }
        endControlFlow()
    }
}
