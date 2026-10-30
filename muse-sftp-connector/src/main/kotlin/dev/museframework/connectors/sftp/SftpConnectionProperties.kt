package dev.museframework.connectors.sftp

import dev.museframework.sftp.api.config.SftpConnectionConfig
import java.util.Properties

private val propertyPlaceholder = Regex("""\$\{([^}]+)}""")

internal fun resolveSftpConnectionProperties(
    config: SftpConnectionConfig,
    properties: Properties,
): SftpConnectionConfig {
    fun resolve(field: String, value: String): String =
        propertyPlaceholder.replace(value) { match ->
            properties.getProperty(match.groupValues[1])
                ?: throw IllegalArgumentException(
                    "SFTP connection field '$field' references missing property '${match.groupValues[1]}'",
                )
        }

    val port = config.portExpression?.let { expression ->
        resolve("port", expression).toIntOrNull()
            ?: throw IllegalArgumentException("SFTP connection port must resolve to an integer")
    } ?: config.port

    return config.copy(
        host = resolve("host", config.host),
        port = port,
        portExpression = null,
        username = resolve("username", config.username),
        password = config.password?.let { resolve("password", it) },
        privateKeyPath = config.privateKeyPath?.let { resolve("privateKeyPath", it) },
        privateKeyPassphrase = config.privateKeyPassphrase?.let { resolve("privateKeyPassphrase", it) },
        knownHostsFile = resolve("knownHostsFile", config.knownHostsFile),
        workingDirectory = resolve("workingDirectory", config.workingDirectory),
    )
}
