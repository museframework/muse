package dev.museframework.connectors.sftp.client

import dev.museframework.sftp.api.config.SftpConnectionConfig
import dev.museframework.sftp.api.config.SftpHostKeyChecking
import dev.museframework.sftp.api.model.SftpErrorType
import dev.museframework.sftp.api.model.SftpFileAttributes
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpTransferRequest
import dev.museframework.sftp.api.model.SftpWriteMode
import kotlinx.coroutines.CancellationException
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.SshException
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.keyprovider.FileKeyPairProvider
import org.apache.sshd.common.keyprovider.KeyIdentityProvider
import org.apache.sshd.core.CoreModuleProperties
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.sshd.sftp.client.SftpClient as ApacheSftpClient

internal class ApacheMinaSftpClientFactory : MuseSftpClientFactory {
    override fun create(config: SftpConnectionConfig): MuseSftpClient = ApacheMinaSftpClient(config)
}

internal class ApacheMinaSftpClient(
    private val config: SftpConnectionConfig,
) : MuseSftpClient {
    private val paths = SftpRemotePath(config.workingDirectory)

    override fun stat(path: String): SftpFileAttributes {
        val resolved = paths.resolve(path)
        return execute("stat", resolved) { sftp -> attributes(resolved, sftp.stat(resolved)) }
    }

    override fun list(
        directoryPath: String,
        recursive: Boolean,
        matcher: SftpFileMatcher,
    ): List<SftpFileAttributes> {
        val resolved = paths.resolve(directoryPath)
        val predicate = matcher.compile()
        return execute("list", resolved) { sftp ->
            val directory = attributes(resolved, sftp.stat(resolved))
            if (!directory.directory) {
                throw SftpClientFailure(
                    SftpErrorType.NOT_A_DIRECTORY,
                    "SFTP path '$directoryPath' is not a directory",
                )
            }
            buildList { collectEntries(sftp, resolved, recursive, predicate, this) }
        }
    }

    override fun openRead(path: String): SftpReadHandle {
        val resolved = paths.resolve(path)
        val ssh = configuredSshClient()
        var session: ClientSession? = null
        var sftp: ApacheSftpClient? = null
        var input: InputStream? = null
        try {
            ssh.start()
            session = connectAndAuthenticate(ssh)
            sftp = SftpClientFactory.instance().createSftpClient(session)
            val attributes = attributes(resolved, sftp.stat(resolved))
            if (attributes.directory) {
                throw SftpClientFailure(
                    SftpErrorType.IS_A_DIRECTORY,
                    "SFTP path '$path' is a directory and cannot be read as a file",
                )
            }
            input = sftp.read(resolved)
            val closed = AtomicBoolean(false)
            val openedInput = input
            val openedSftp = sftp
            val openedSession = session
            return SftpReadHandle(openedInput, attributes) {
                if (closed.compareAndSet(false, true)) {
                    runCatching { openedInput.close() }
                    runCatching { openedSftp.close() }
                    runCatching { openedSession.close() }
                    runCatching { ssh.stop() }
                }
            }
        } catch (failure: Throwable) {
            runCatching { input?.close() }
            runCatching { sftp?.close() }
            runCatching { session?.close() }
            runCatching { ssh.stop() }
            throw classify("read", resolved, failure)
        }
    }

    override fun write(
        path: String,
        content: InputStream,
        mode: SftpWriteMode,
        createParentDirectories: Boolean,
    ) {
        val resolved = paths.resolve(path)
        execute("write", resolved) { sftp ->
            val existing = statOrNull(sftp, resolved)
            if (existing?.directory == true) {
                throw SftpClientFailure(
                    SftpErrorType.IS_A_DIRECTORY,
                    "SFTP path '$path' is a directory and cannot be written as a file",
                )
            }
            if (existing != null && mode == SftpWriteMode.CREATE_NEW) {
                throw SftpClientFailure(
                    SftpErrorType.FILE_ALREADY_EXISTS,
                    "SFTP path '$path' already exists",
                )
            }
            ensureParentDirectory(sftp, resolved, createParentDirectories)
            sftp.write(resolved, *mode.openModes()).use { output -> content.copyTo(output, COPY_BUFFER_SIZE) }
        }
    }

    override fun copy(transfer: SftpTransferRequest) {
        val source = paths.resolve(transfer.sourcePath)
        val targetDirectoryResolved = paths.resolve(transfer.targetDirectory)
        val target = paths.child(targetDirectoryResolved, transfer.renameTo ?: paths.name(source))
        requireTargetOutsideSource(source, target)
        execute("copy", source) { sftp ->
            requireTransferPathsWithoutSymlinks(sftp, source, target)
            ensureDirectory(sftp, targetDirectoryResolved, transfer.createParentDirectories)
            copyEntry(sftp, source, target, transfer.overwrite)
        }
    }

    override fun move(transfer: SftpTransferRequest) {
        val source = paths.resolve(transfer.sourcePath)
        val targetDirectoryResolved = paths.resolve(transfer.targetDirectory)
        val target = paths.child(targetDirectoryResolved, transfer.renameTo ?: paths.name(source))
        requireTargetOutsideSource(source, target)
        execute("move", source) { sftp ->
            requireTransferPathsWithoutSymlinks(sftp, source, target)
            statRequired(sftp, source)
            ensureDirectory(sftp, targetDirectoryResolved, transfer.createParentDirectories)
            prepareTarget(sftp, target, transfer.overwrite)
            sftp.rename(source, target)
        }
    }

    override fun delete(path: String) {
        val resolved = paths.resolve(path)
        execute("delete", resolved) { sftp ->
            deleteEntry(sftp, resolved)
        }
    }

    override fun rename(
        path: String,
        newName: String,
        overwrite: Boolean,
    ) {
        val source = paths.resolve(path)
        val target = paths.child(paths.parent(source), newName)
        requireTargetOutsideSource(source, target)
        execute("rename", source) { sftp ->
            requireTransferPathsWithoutSymlinks(sftp, source, target)
            statRequired(sftp, source)
            prepareTarget(sftp, target, overwrite)
            sftp.rename(source, target)
        }
    }

    override fun createDirectory(path: String) {
        val resolved = paths.resolve(path)
        execute("create directory", resolved) { sftp -> createDirectories(sftp, resolved) }
    }

    private fun collectEntries(
        sftp: ApacheSftpClient,
        directory: String,
        recursive: Boolean,
        matcher: SftpFilePredicate,
        destination: MutableList<SftpFileAttributes>,
    ) {
        for (entry in sftp.readDir(directory)) {
            if (entry.filename == "." || entry.filename == "..") continue
            val path = paths.child(directory, entry.filename)
            val attributes = attributes(path, entry.attributes)
            if (matcher.matches(attributes)) destination += attributes
            if (recursive && attributes.directory) collectEntries(sftp, path, true, matcher, destination)
        }
    }

    private fun copyEntry(
        sftp: ApacheSftpClient,
        source: String,
        target: String,
        overwrite: Boolean,
    ) {
        // lstat: a symbolic link is never recursed as a directory, so the walk stays in the lexical root.
        val sourceAttributes = lstatRequired(sftp, source)
        if (sourceAttributes.symbolicLink && statOrNull(sftp, source)?.directory == true) {
            throw SftpClientFailure(
                SftpErrorType.ILLEGAL_PATH,
                "SFTP path '${paths.visible(source)}' is a symbolic link to a directory and cannot be copied",
            )
        }
        prepareTarget(sftp, target, overwrite)
        if (sourceAttributes.directory) {
            sftp.mkdir(target)
            for (entry in sftp.readDir(source)) {
                if (entry.filename == "." || entry.filename == "..") continue
                copyEntry(
                    sftp,
                    paths.child(source, entry.filename),
                    paths.child(target, entry.filename),
                    overwrite,
                )
            }
        } else {
            ensureParentDirectory(sftp, target, create = true)
            sftp.read(source).use { input ->
                sftp.write(target).use { output -> input.copyTo(output, COPY_BUFFER_SIZE) }
            }
        }
    }

    private fun prepareTarget(
        sftp: ApacheSftpClient,
        target: String,
        overwrite: Boolean,
    ) {
        // lstat: a dangling symbolic link is still an existing directory entry.
        if (lstatOrNull(sftp, target) == null) return
        if (!overwrite) {
            throw SftpClientFailure(
                SftpErrorType.FILE_ALREADY_EXISTS,
                "SFTP target '$target' already exists",
            )
        }
        deleteEntry(sftp, target)
    }

    private fun deleteEntry(
        sftp: ApacheSftpClient,
        path: String,
    ) {
        // lstat: a symbolic link is removed as a link and never recursed into.
        val attributes = lstatRequired(sftp, path)
        if (attributes.directory) {
            for (entry in sftp.readDir(path)) {
                if (entry.filename == "." || entry.filename == "..") continue
                deleteEntry(sftp, paths.child(path, entry.filename))
            }
            sftp.rmdir(path)
        } else {
            sftp.remove(path)
        }
    }

    private fun ensureParentDirectory(
        sftp: ApacheSftpClient,
        path: String,
        create: Boolean,
    ) = ensureDirectory(sftp, paths.parent(path), create)

    private fun ensureDirectory(
        sftp: ApacheSftpClient,
        directory: String,
        create: Boolean,
    ) {
        val existing = statOrNull(sftp, directory)
        if (existing != null) {
            if (!existing.directory) {
                throw SftpClientFailure(
                    SftpErrorType.NOT_A_DIRECTORY,
                    "SFTP path '$directory' is not a directory",
                )
            }
            return
        }
        if (!create) {
            throw SftpClientFailure(
                SftpErrorType.ILLEGAL_PATH,
                "SFTP parent directory '$directory' does not exist",
            )
        }
        createDirectories(sftp, directory)
    }

    private fun createDirectories(
        sftp: ApacheSftpClient,
        directory: String,
    ) {
        if (directory == "/") return
        val existing = statOrNull(sftp, directory)
        if (existing != null) {
            if (!existing.directory) {
                throw SftpClientFailure(
                    SftpErrorType.NOT_A_DIRECTORY,
                    "SFTP path '$directory' is not a directory",
                )
            }
            return
        }
        createDirectories(sftp, paths.parent(directory))
        sftp.mkdir(directory)
    }

    private fun statRequired(
        sftp: ApacheSftpClient,
        path: String,
    ): SftpFileAttributes =
        statOrNull(sftp, path)
            ?: throw SftpClientFailure(SftpErrorType.FILE_NOT_FOUND, "SFTP path '$path' does not exist")

    private fun lstatRequired(
        sftp: ApacheSftpClient,
        path: String,
    ): SftpFileAttributes =
        lstatOrNull(sftp, path)
            ?: throw SftpClientFailure(SftpErrorType.FILE_NOT_FOUND, "SFTP path '$path' does not exist")

    private fun lstatOrNull(
        sftp: ApacheSftpClient,
        path: String,
    ): SftpFileAttributes? = missingAsNull { attributes(path, sftp.lstat(path)) }

    private fun statOrNull(
        sftp: ApacheSftpClient,
        path: String,
    ): SftpFileAttributes? = missingAsNull { attributes(path, sftp.stat(path)) }

    private inline fun <T> missingAsNull(block: () -> T): T? =
        try {
            block()
        } catch (failure: SftpException) {
            if (failure.status == SftpConstants.SSH_FX_NO_SUCH_FILE ||
                failure.status == SftpConstants.SSH_FX_NO_SUCH_PATH
            ) {
                null
            } else {
                throw failure
            }
        }

    private fun attributes(
        path: String,
        attributes: ApacheSftpClient.Attributes,
    ): SftpFileAttributes =
        SftpFileAttributes(
            path = paths.visible(path),
            name = paths.name(path),
            size = attributes.size,
            modifiedAt = attributes.modifyTime?.toInstant(),
            regularFile = attributes.isRegularFile,
            directory = attributes.isDirectory,
            symbolicLink = attributes.isSymbolicLink,
            permissions = attributes.permissions,
        )

    private fun SftpWriteMode.openModes(): Array<ApacheSftpClient.OpenMode> =
        when (this) {
            SftpWriteMode.OVERWRITE ->
                arrayOf(
                    ApacheSftpClient.OpenMode.Write,
                    ApacheSftpClient.OpenMode.Create,
                    ApacheSftpClient.OpenMode.Truncate,
                )
            SftpWriteMode.APPEND ->
                arrayOf(
                    ApacheSftpClient.OpenMode.Write,
                    ApacheSftpClient.OpenMode.Create,
                    ApacheSftpClient.OpenMode.Append,
                )
            SftpWriteMode.CREATE_NEW ->
                arrayOf(
                    ApacheSftpClient.OpenMode.Write,
                    ApacheSftpClient.OpenMode.Create,
                    ApacheSftpClient.OpenMode.Exclusive,
                )
        }

    private fun requireTransferPathsWithoutSymlinks(
        sftp: ApacheSftpClient,
        source: String,
        target: String,
    ) {
        // ponytail: reject link traversal; remote link resolution can be added if alias transfers are required.
        // The final target link may be replaced, so only its parents are checked.
        for (path in listOf(source, paths.parent(target))) {
            var current = path
            while (true) {
                if (missingAsNull { sftp.lstat(current).isSymbolicLink } == true) {
                    throw SftpClientFailure(
                        SftpErrorType.ILLEGAL_PATH,
                        "SFTP transfer path '$current' is a symbolic link",
                    )
                }
                if (current == "/") break
                current = current.substringBeforeLast('/').ifEmpty { "/" }
            }
        }
    }

    private fun requireTargetOutsideSource(
        source: String,
        target: String,
    ) {
        if (source == target || isStrictAncestor(source, target) || isStrictAncestor(target, source)) {
            throw SftpClientFailure(
                SftpErrorType.ILLEGAL_PATH,
                "SFTP target '$target' must not overlap source '$source'",
            )
        }
    }

    private fun isStrictAncestor(
        ancestor: String,
        descendant: String,
    ): Boolean =
        if (ancestor == "/") {
            descendant != "/"
        } else {
            descendant.startsWith("$ancestor/")
        }

    private fun <T> execute(
        operation: String,
        path: String,
        block: (ApacheSftpClient) -> T,
    ): T =
        try {
            withSftp(block)
        } catch (failure: Throwable) {
            throw classify(operation, path, failure)
        }

    private fun <T> withSftp(block: (ApacheSftpClient) -> T): T {
        val ssh = configuredSshClient()
        ssh.start()
        return try {
            connectAndAuthenticate(ssh).use { session ->
                SftpClientFactory.instance().createSftpClient(session).use(block)
            }
        } finally {
            ssh.stop()
        }
    }

    private fun configuredSshClient(): SshClient =
        SshClient.setUpDefaultClient().apply {
            // Authentication identities are supplied explicitly by the connection configuration.
            keyIdentityProvider = KeyIdentityProvider.EMPTY_KEYS_PROVIDER
            serverKeyVerifier =
                when (config.hostKeyChecking) {
                    SftpHostKeyChecking.STRICT ->
                        KnownHostsServerKeyVerifier(
                            RejectAllServerKeyVerifier.INSTANCE,
                            expandHome(config.knownHostsFile),
                        )
                    SftpHostKeyChecking.INSECURE -> AcceptAllServerKeyVerifier.INSTANCE
                }
            CoreModuleProperties.IDLE_TIMEOUT.set(this, Duration.ofMillis(config.operationTimeoutMs))
        }

    private fun connectAndAuthenticate(ssh: SshClient): ClientSession {
        val session =
            ssh.connect(config.username, config.host, config.port)
                .verify(Duration.ofMillis(config.connectionTimeoutMs))
                .session
        try {
            config.privateKeyPath?.let { privateKeyPath ->
                val provider = FileKeyPairProvider(expandHome(privateKeyPath))
                config.privateKeyPassphrase?.let { provider.passwordFinder = FilePasswordProvider.of(it) }
                for (keyPair in provider.loadKeys(session)) {
                    session.addPublicKeyIdentity(keyPair)
                }
            }
            config.password?.let(session::addPasswordIdentity)
            session.auth().verify(Duration.ofMillis(config.authenticationTimeoutMs))
            return session
        } catch (failure: Throwable) {
            session.close()
            throw failure
        }
    }

    private fun expandHome(path: String): Path =
        if (path == "~") {
            Path.of(System.getProperty("user.home"))
        } else if (path.startsWith("~/")) {
            Path.of(System.getProperty("user.home"), path.substring(2))
        } else {
            Path.of(path)
        }

    private fun classify(
        operation: String,
        path: String,
        failure: Throwable,
    ): SftpClientFailure {
        if (failure is CancellationException) throw failure
        if (failure is SftpClientFailure) return failure
        val message = generateSequence(failure as Throwable?) { it.cause }
            .mapNotNull(Throwable::message)
            .joinToString(" | ")
        val errorType =
            when {
                failure is SftpException ->
                    when (failure.status) {
                        SftpConstants.SSH_FX_NO_SUCH_FILE,
                        SftpConstants.SSH_FX_NO_SUCH_PATH,
                        -> SftpErrorType.FILE_NOT_FOUND
                        SftpConstants.SSH_FX_PERMISSION_DENIED,
                        SftpConstants.SSH_FX_WRITE_PROTECT,
                        -> SftpErrorType.ACCESS_DENIED
                        SftpConstants.SSH_FX_FILE_ALREADY_EXISTS -> SftpErrorType.FILE_ALREADY_EXISTS
                        SftpConstants.SSH_FX_NOT_A_DIRECTORY -> SftpErrorType.NOT_A_DIRECTORY
                        SftpConstants.SSH_FX_FILE_IS_A_DIRECTORY -> SftpErrorType.IS_A_DIRECTORY
                        SftpConstants.SSH_FX_NO_CONNECTION,
                        SftpConstants.SSH_FX_CONNECTION_LOST,
                        -> SftpErrorType.CONNECTIVITY
                        else -> SftpErrorType.OPERATION_FAILED
                    }
                failure is SocketTimeoutException || message.contains("timeout", ignoreCase = true) ->
                    SftpErrorType.CONNECTION_TIMEOUT
                failure is UnknownHostException || failure is UnresolvedAddressException ||
                    failure is ConnectException -> SftpErrorType.CONNECTIVITY
                failure is SshException &&
                    (message.contains("auth", ignoreCase = true) ||
                        message.contains("no more", ignoreCase = true)) -> SftpErrorType.INVALID_CREDENTIALS
                failure is SshException &&
                    (message.contains("server key", ignoreCase = true) ||
                        message.contains("key did not validate", ignoreCase = true)) -> SftpErrorType.HOST_KEY_VERIFICATION
                failure is IOException -> SftpErrorType.CONNECTIVITY
                failure is IllegalArgumentException -> SftpErrorType.ILLEGAL_PATH
                else -> SftpErrorType.OPERATION_FAILED
            }
        return SftpClientFailure(
            errorType,
            "SFTP $operation failed for '$path': ${failure.message ?: failure::class.simpleName}",
            failure,
        )
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 32 * 1024
    }
}
