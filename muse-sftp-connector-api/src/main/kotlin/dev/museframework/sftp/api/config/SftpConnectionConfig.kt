package dev.museframework.sftp.api.config

import dev.museframework.dsl.api.model.config.MuseConfiguration
import java.util.UUID

enum class SftpHostKeyChecking {
    STRICT,
    INSECURE,
}

data class SftpConnectionConfig(
    val name: String = "",
    override val docName: String = "",
    override val docId: String = UUID.randomUUID().toString(),
    override val docDescription: String? = null,
    override val sourceFile: String? = null,
    override val sourceLine: Int? = null,
    override val sourceClass: String? = null,
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String? = null,
    val privateKeyPath: String? = null,
    val privateKeyPassphrase: String? = null,
    val knownHostsFile: String = "~/.ssh/known_hosts",
    val hostKeyChecking: SftpHostKeyChecking = SftpHostKeyChecking.STRICT,
    val workingDirectory: String = "/",
    val connectionTimeoutMs: Long = 10_000,
    val authenticationTimeoutMs: Long = 10_000,
    val operationTimeoutMs: Long = 30_000,
    /** Property-based port resolved before client creation; overrides the static port. */
    val portExpression: String? = null,
) : MuseConfiguration {
    init {
        require(host.isNotBlank()) { "SFTP host must not be blank" }
        require(port in 1..65_535) { "SFTP port must be between 1 and 65535" }
        require(username.isNotBlank()) { "SFTP username must not be blank" }
        require(password != null || privateKeyPath != null) {
            "SFTP password or privateKeyPath must be configured"
        }
        require(privateKeyPath == null || privateKeyPath.isNotBlank()) {
            "SFTP privateKeyPath must not be blank"
        }
        require(privateKeyPassphrase == null || privateKeyPath != null) {
            "SFTP privateKeyPassphrase requires privateKeyPath"
        }
        require(hostKeyChecking != SftpHostKeyChecking.STRICT || knownHostsFile.isNotBlank()) {
            "SFTP knownHostsFile must not be blank when strict host key checking is enabled"
        }
        require(workingDirectory.isNotBlank()) { "SFTP workingDirectory must not be blank" }
        require(connectionTimeoutMs > 0) { "SFTP connectionTimeoutMs must be greater than zero" }
        require(authenticationTimeoutMs > 0) { "SFTP authenticationTimeoutMs must be greater than zero" }
        require(operationTimeoutMs > 0) { "SFTP operationTimeoutMs must be greater than zero" }
    }

    override fun toString(): String =
        "SftpConnectionConfig(" +
            "name='$name', docName='$docName', docId='$docId', host='$host', port=$port, username='$username', " +
            "password=${password?.let { "<redacted>" }}, privateKeyPath=$privateKeyPath, " +
            "privateKeyPassphrase=${privateKeyPassphrase?.let { "<redacted>" }}, " +
            "knownHostsFile='$knownHostsFile', hostKeyChecking=$hostKeyChecking, " +
            "workingDirectory='$workingDirectory', connectionTimeoutMs=$connectionTimeoutMs, " +
            "authenticationTimeoutMs=$authenticationTimeoutMs, operationTimeoutMs=$operationTimeoutMs)"
}
