package dev.museframework.connectors.sftp.client

import dev.museframework.sftp.api.config.SftpConnectionConfig
import dev.museframework.sftp.api.config.SftpHostKeyChecking
import dev.museframework.sftp.api.model.SftpErrorType
import dev.museframework.sftp.api.model.SftpFileMatcher
import dev.museframework.sftp.api.model.SftpFileType
import dev.museframework.sftp.api.model.SftpTransferRequest
import dev.museframework.sftp.api.model.SftpWriteMode
import dev.museframework.sftp.testkit.EmbeddedSftpServer
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64
import java.util.EnumSet
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApacheMinaSftpClientIT {
    @TempDir
    lateinit var temporaryDirectory: Path

    private lateinit var server: EmbeddedSftpServer

    @BeforeEach
    fun startServer() {
        server = EmbeddedSftpServer(temporaryDirectory)
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.close()
    }

    @Test
    fun `write read and recursive list use a real SFTP transport`() {
        server.home.resolve("tenant").createDirectories()
        val client = client(workingDirectory = "/tenant")

        client.createDirectory("/inbound/nested")
        client.write(
            "/inbound/orders.txt",
            ByteArrayInputStream("first".toByteArray()),
            SftpWriteMode.CREATE_NEW,
            createParentDirectories = true,
        )
        client.write(
            "/inbound/orders.txt",
            ByteArrayInputStream("-second".toByteArray()),
            SftpWriteMode.APPEND,
            createParentDirectories = false,
        )
        client.write(
            "/inbound/nested/ignored.csv",
            ByteArrayInputStream("csv".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )

        val read = client.openRead("/inbound/orders.txt")
        val content = read.use { it.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        val listed =
            client.list(
                "/inbound",
                recursive = true,
                matcher = SftpFileMatcher(filenamePattern = "*.txt", fileType = SftpFileType.REGULAR_FILE),
            )

        assertEquals("first-second", content)
        assertEquals(listOf("/inbound/orders.txt"), listed.map { it.path })
        assertEquals("orders.txt", listed.single().name)
        assertEquals(12, listed.single().size)
        assertTrue(listed.single().regularFile)
        assertTrue(server.home.resolve("tenant/inbound/orders.txt").exists())

        val duplicate =
            assertFailsWith<SftpClientFailure> {
                client.write(
                    "/inbound/orders.txt",
                    ByteArrayInputStream("duplicate".toByteArray()),
                    SftpWriteMode.CREATE_NEW,
                    createParentDirectories = false,
                )
            }
        assertEquals(SftpErrorType.FILE_ALREADY_EXISTS, duplicate.errorType)
    }

    @Test
    fun `overwrite replaces an existing file and missing parents are refused`() {
        val client = client()
        client.write(
            "/data/file.txt",
            ByteArrayInputStream("original".toByteArray()),
            SftpWriteMode.CREATE_NEW,
            createParentDirectories = true,
        )

        client.write(
            "/data/file.txt",
            ByteArrayInputStream("replaced".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )
        assertEquals("replaced", server.home.resolve("data/file.txt").readText())

        val missingParent =
            assertFailsWith<SftpClientFailure> {
                client.write(
                    "/missing-parent/file.txt",
                    ByteArrayInputStream("x".toByteArray()),
                    SftpWriteMode.CREATE_NEW,
                    createParentDirectories = false,
                )
            }
        assertEquals(SftpErrorType.ILLEGAL_PATH, missingParent.errorType)
    }

    @Test
    fun `list distinguishes recursive matching empty directories and non-directories`() {
        val client = client()
        client.createDirectory("/inbox/nested")
        client.write(
            "/inbox/keep.txt",
            ByteArrayInputStream("keep".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )
        client.write(
            "/inbox/nested/deep.txt",
            ByteArrayInputStream("deep".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )
        client.write(
            "/inbox/skip.csv",
            ByteArrayInputStream("csv".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )
        client.createDirectory("/empty")

        val flat =
            client.list(
                "/inbox",
                recursive = false,
                matcher = SftpFileMatcher(filenamePattern = "*.txt", fileType = SftpFileType.REGULAR_FILE),
            )
        assertEquals(listOf("/inbox/keep.txt"), flat.map { it.path })

        val recursive =
            client.list(
                "/inbox",
                recursive = true,
                matcher = SftpFileMatcher(filenamePattern = "*.txt", fileType = SftpFileType.REGULAR_FILE),
            )
        assertEquals(listOf("/inbox/keep.txt", "/inbox/nested/deep.txt"), recursive.map { it.path }.sorted())

        assertTrue(client.list("/empty", recursive = false, matcher = SftpFileMatcher()).isEmpty())

        val notADirectory =
            assertFailsWith<SftpClientFailure> {
                client.list("/inbox/keep.txt", recursive = false, matcher = SftpFileMatcher())
            }
        assertEquals(SftpErrorType.NOT_A_DIRECTORY, notADirectory.errorType)
    }

    @Test
    fun `read refuses directories and path jail escapes`() {
        server.home.resolve("jail").createDirectories()
        val client = client(workingDirectory = "/jail")
        client.write(
            "/file.txt",
            ByteArrayInputStream("ok".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = true,
        )

        val isDirectory =
            assertFailsWith<SftpClientFailure> {
                client.openRead("/").use { }
            }
        assertEquals(SftpErrorType.IS_A_DIRECTORY, isDirectory.errorType)

        val escaped =
            assertFailsWith<SftpClientFailure> {
                client.stat("../outside")
            }
        assertEquals(SftpErrorType.ILLEGAL_PATH, escaped.errorType)
    }

    @Test
    fun `copy move rename and recursive delete operate through SFTP`() {
        val client = client()
        client.createDirectory("/source/nested")
        client.write(
            "/source/nested/data.txt",
            ByteArrayInputStream("transport".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )

        client.copy(SftpTransferRequest(sourcePath = "/source",
            targetDirectory = "/archive",
            createParentDirectories = true,
            overwrite = false,
            renameTo = "snapshot",))
        assertEquals("transport", server.home.resolve("archive/snapshot/nested/data.txt").readText())

        val duplicate =
            assertFailsWith<SftpClientFailure> {
                client.copy(SftpTransferRequest("/source", "/archive", true, overwrite = false, renameTo = "snapshot"))
            }
        assertEquals(SftpErrorType.FILE_ALREADY_EXISTS, duplicate.errorType)

        client.copy(SftpTransferRequest("/source", "/archive", true, overwrite = true, renameTo = "snapshot"))
        assertEquals("transport", server.home.resolve("archive/snapshot/nested/data.txt").readText())

        client.move(SftpTransferRequest(sourcePath = "/archive/snapshot/nested/data.txt",
            targetDirectory = "/processed",
            createParentDirectories = true,
            overwrite = false,
            renameTo = "moved.txt",))
        client.rename("/processed/moved.txt", "final.txt", overwrite = false)
        assertEquals("transport", server.home.resolve("processed/final.txt").readText())
        assertFalse(server.home.resolve("archive/snapshot/nested/data.txt").exists())

        client.write(
            "/processed/conflict.txt",
            ByteArrayInputStream("other".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )
        val renameConflict =
            assertFailsWith<SftpClientFailure> {
                client.rename("/processed/final.txt", "conflict.txt", overwrite = false)
            }
        assertEquals(SftpErrorType.FILE_ALREADY_EXISTS, renameConflict.errorType)
        client.rename("/processed/final.txt", "conflict.txt", overwrite = true)
        assertEquals("transport", server.home.resolve("processed/conflict.txt").readText())

        val nestedTarget =
            assertFailsWith<SftpClientFailure> {
                client.copy(SftpTransferRequest("/source", "/source/nested", true, overwrite = false, renameTo = null))
            }
        assertEquals(SftpErrorType.ILLEGAL_PATH, nestedTarget.errorType)

        val missingParent =
            assertFailsWith<SftpClientFailure> {
                client.move(SftpTransferRequest("/processed/conflict.txt",
                    "/no-such-parent",
                    createParentDirectories = false,
                    overwrite = false,
                    renameTo = "x.txt",))
            }
        assertEquals(SftpErrorType.ILLEGAL_PATH, missingParent.errorType)

        client.delete("/archive/snapshot")
        assertFalse(server.home.resolve("archive/snapshot").exists())
        client.delete("/source")
        assertFalse(server.home.resolve("source").exists())
    }

    @Test
    fun `create directory refuses a path that already exists as a file`() {
        val client = client()
        client.write(
            "/blocked",
            ByteArrayInputStream("file".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = true,
        )

        val failure = assertFailsWith<SftpClientFailure> { client.createDirectory("/blocked") }
        assertEquals(SftpErrorType.NOT_A_DIRECTORY, failure.errorType)
    }

    @Test
    fun `transport classifies authentication host verification missing file connectivity and timeouts`() {
        val wrongPassword =
            assertFailsWith<SftpClientFailure> {
                client(password = "wrong").stat("/")
            }
        assertEquals(SftpErrorType.INVALID_CREDENTIALS, wrongPassword.errorType)

        val unknownHostKey =
            assertFailsWith<SftpClientFailure> {
                client(
                    hostKeyChecking = SftpHostKeyChecking.STRICT,
                    knownHostsFile = temporaryDirectory.resolve("missing-known-hosts").toString(),
                ).stat("/")
            }
        assertEquals(SftpErrorType.HOST_KEY_VERIFICATION, unknownHostKey.errorType)

        val knownHosts = server.writeKnownHosts(temporaryDirectory.resolve("known-hosts"))
        val strictClient =
            client(
                hostKeyChecking = SftpHostKeyChecking.STRICT,
                knownHostsFile = knownHosts.toString(),
            )
        assertTrue(strictClient.stat("/").directory)

        val missingFile = assertFailsWith<SftpClientFailure> { strictClient.stat("/missing.txt") }
        assertEquals(SftpErrorType.FILE_NOT_FOUND, missingFile.errorType)

        val refused =
            assertFailsWith<SftpClientFailure> {
                ApacheMinaSftpClient(
                    SftpConnectionConfig(
                        host = EmbeddedSftpServer.HOST,
                        port = 1,
                        username = EmbeddedSftpServer.USERNAME,
                        password = EmbeddedSftpServer.PASSWORD,
                        hostKeyChecking = SftpHostKeyChecking.INSECURE,
                        connectionTimeoutMs = 1_000,
                        authenticationTimeoutMs = 1_000,
                        operationTimeoutMs = 1_000,
                    ),
                ).stat("/")
            }
        assertEquals(SftpErrorType.CONNECTIVITY, refused.errorType)

        val timedOut =
            assertFailsWith<SftpClientFailure> {
                ApacheMinaSftpClient(
                    SftpConnectionConfig(
                        host = "192.0.2.1",
                        port = 22,
                        username = EmbeddedSftpServer.USERNAME,
                        password = EmbeddedSftpServer.PASSWORD,
                        hostKeyChecking = SftpHostKeyChecking.INSECURE,
                        connectionTimeoutMs = 200,
                        authenticationTimeoutMs = 200,
                        operationTimeoutMs = 200,
                    ),
                ).stat("/")
            }
        assertTrue(
            timedOut.errorType == SftpErrorType.CONNECTION_TIMEOUT ||
                timedOut.errorType == SftpErrorType.CONNECTIVITY,
            "expected timeout or connectivity, got ${timedOut.errorType}: ${timedOut.message}",
        )
    }

    @Test
    fun `transport classifies access denied for an unreadable remote file`() {
        val protectedFile = server.home.resolve("secret.txt")
        protectedFile.writeText("secret")
        Files.setPosixFilePermissions(
            protectedFile,
            EnumSet.noneOf(PosixFilePermission::class.java),
        )

        val failure =
            assertFailsWith<SftpClientFailure> {
                client().openRead("/secret.txt").use { }
            }
        assertEquals(SftpErrorType.ACCESS_DENIED, failure.errorType)
    }

    @Test
    fun `copy of a file leaves the source in place`() {
        val client = client()
        client.write(
            "/source/note.txt",
            ByteArrayInputStream("body".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = true,
        )

        client.copy(SftpTransferRequest("/source/note.txt", "/copies", true, overwrite = false, renameTo = "note-copy.txt"))

        assertEquals("body", server.home.resolve("source/note.txt").readText())
        assertEquals("body", server.home.resolve("copies/note-copy.txt").readText())
    }

    @Test
    fun `copy through a directory alias refuses overlap without deleting the source`() {
        val source = server.home.resolve("parent/file.txt")
        source.parent.createDirectories()
        source.writeText("keep-me")
        Files.createSymbolicLink(server.home.resolve("alias"), Path.of("parent"))
        val client = client()

        for ((sourcePath, targetDirectory) in
            listOf("/parent/file.txt" to "/alias", "/alias/file.txt" to "/parent", "/parent" to "/alias/new")
        ) {
            val failure =
                assertFailsWith<SftpClientFailure> {
                    client.copy(SftpTransferRequest(sourcePath, targetDirectory, overwrite = true))
                }

            assertEquals(SftpErrorType.ILLEGAL_PATH, failure.errorType)
            assertEquals("keep-me", source.readText())
            assertFalse(server.home.resolve("parent/new").exists())
        }
    }

    @Test
    fun `move through a directory alias refuses overlap without deleting the source`() {
        val source = server.home.resolve("parent/file.txt")
        source.parent.createDirectories()
        source.writeText("keep-me")
        Files.createSymbolicLink(server.home.resolve("alias"), Path.of("parent"))
        val client = client()

        for ((sourcePath, targetDirectory) in
            listOf("/parent/file.txt" to "/alias", "/alias/file.txt" to "/parent", "/parent" to "/alias/new")
        ) {
            val failure =
                assertFailsWith<SftpClientFailure> {
                    client.move(SftpTransferRequest(sourcePath, targetDirectory, overwrite = true))
                }

            assertEquals(SftpErrorType.ILLEGAL_PATH, failure.errorType)
            assertEquals("keep-me", source.readText())
            assertFalse(server.home.resolve("parent/new").exists())
        }
    }

    @Test
    fun `transfers refuse a symbolic source without removing its referent`() {
        val source = server.home.resolve("parent/file.txt")
        source.parent.createDirectories()
        source.writeText("keep-me")
        val link = server.home.resolve("parent/link.txt")
        Files.createSymbolicLink(link, Path.of("file.txt"))
        val client = client()
        val transfer = SftpTransferRequest("/parent/link.txt", "/parent", overwrite = true, renameTo = "file.txt")

        for (operation in listOf<() -> Unit>(
            { client.copy(transfer) },
            { client.move(transfer) },
            { client.rename("/parent/link.txt", "file.txt", overwrite = true) },
        )) {
            val failure = assertFailsWith<SftpClientFailure> { operation() }

            assertEquals(SftpErrorType.ILLEGAL_PATH, failure.errorType)
            assertEquals("keep-me", source.readText())
            assertTrue(Files.isSymbolicLink(link))
        }
    }

    @Test
    fun `same-path rename with overwrite refuses without deleting the source`() {
        val client = client()
        client.write(
            "/rename/a.txt",
            ByteArrayInputStream("keep-me".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = true,
        )

        val failure =
            assertFailsWith<SftpClientFailure> {
                client.rename("/rename/a.txt", "a.txt", overwrite = true)
            }

        assertEquals(SftpErrorType.ILLEGAL_PATH, failure.errorType)
        assertEquals("keep-me", server.home.resolve("rename/a.txt").readText())
    }

    @Test
    fun `move into ancestor with overwrite refuses without deleting the source tree`() {
        val client = client()
        client.write(
            "/move/sub/data.txt",
            ByteArrayInputStream("keep-me".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = true,
        )

        val failure =
            assertFailsWith<SftpClientFailure> {
                client.move(
                    SftpTransferRequest(
                        sourcePath = "/move/sub",
                        targetDirectory = "/",
                        createParentDirectories = true,
                        overwrite = true,
                        renameTo = "move",
                    ),
                )
            }

        assertEquals(SftpErrorType.ILLEGAL_PATH, failure.errorType)
        assertTrue(server.home.resolve("move").exists())
        assertEquals("keep-me", server.home.resolve("move/sub/data.txt").readText())
    }

    @Test
    fun `copy into ancestor with overwrite refuses without deleting the source tree`() {
        val client = client()
        client.write(
            "/copy/sub/data.txt",
            ByteArrayInputStream("keep-me".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = true,
        )

        val failure =
            assertFailsWith<SftpClientFailure> {
                client.copy(
                    SftpTransferRequest(
                        sourcePath = "/copy/sub",
                        targetDirectory = "/",
                        createParentDirectories = true,
                        overwrite = true,
                        renameTo = "copy",
                    ),
                )
            }

        assertEquals(SftpErrorType.ILLEGAL_PATH, failure.errorType)
        assertTrue(server.home.resolve("copy").exists())
        assertEquals("keep-me", server.home.resolve("copy/sub/data.txt").readText())
    }

    @Test
    fun `move relocates a directory and overwrite replaces a file`() {
        val client = client()
        client.createDirectory("/tree/child")
        client.write(
            "/tree/child/a.txt",
            ByteArrayInputStream("a".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )

        client.move(SftpTransferRequest("/tree", "/dest", createParentDirectories = true, overwrite = false, renameTo = "relocated"))

        assertFalse(server.home.resolve("tree").exists())
        assertEquals("a", server.home.resolve("dest/relocated/child/a.txt").readText())

        client.write(
            "/src/a.txt",
            ByteArrayInputStream("new".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = true,
        )
        client.write(
            "/dst/a.txt",
            ByteArrayInputStream("old".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = true,
        )
        client.move(SftpTransferRequest("/src/a.txt", "/dst", createParentDirectories = true, overwrite = true, renameTo = null))

        assertFalse(server.home.resolve("src/a.txt").exists())
        assertEquals("new", server.home.resolve("dst/a.txt").readText())
    }

    @Test
    fun `rename renames a directory and rejects a multi-segment name`() {
        val client = client()
        client.createDirectory("/inbox/nested")
        client.write(
            "/inbox/nested/a.txt",
            ByteArrayInputStream("a".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )

        client.rename("/inbox", "outbox", overwrite = false)

        assertFalse(server.home.resolve("inbox").exists())
        assertEquals("a", server.home.resolve("outbox/nested/a.txt").readText())

        val illegal =
            assertFailsWith<SftpClientFailure> {
                client.rename("/outbox/nested/a.txt", "nested/nope", overwrite = false)
            }
        assertEquals(SftpErrorType.ILLEGAL_PATH, illegal.errorType)
        assertTrue(server.home.resolve("outbox/nested/a.txt").exists())
    }

    @Test
    fun `delete removes a single file`() {
        val client = client()
        client.write(
            "/alone.txt",
            ByteArrayInputStream("x".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = true,
        )

        client.delete("/alone.txt")

        assertFalse(server.home.resolve("alone.txt").exists())
    }

    @Test
    fun `create directory leaves an existing directory unchanged`() {
        val client = client()
        client.createDirectory("/keep/nested")
        client.write(
            "/keep/nested/stay.txt",
            ByteArrayInputStream("stay".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )

        client.createDirectory("/keep")
        client.createDirectory("/keep/nested")

        assertEquals("stay", server.home.resolve("keep/nested/stay.txt").readText())
    }

    @Test
    fun `write refuses a directory and append creates a missing file`() {
        val client = client()
        client.createDirectory("/blocked")

        val directory =
            assertFailsWith<SftpClientFailure> {
                client.write(
                    "/blocked",
                    ByteArrayInputStream("x".toByteArray()),
                    SftpWriteMode.OVERWRITE,
                    createParentDirectories = false,
                )
            }
        assertEquals(SftpErrorType.IS_A_DIRECTORY, directory.errorType)

        client.write(
            "/fresh.txt",
            ByteArrayInputStream("hello".toByteArray()),
            SftpWriteMode.APPEND,
            createParentDirectories = true,
        )
        assertEquals("hello", server.home.resolve("fresh.txt").readText())
    }

    @Test
    fun `missing paths fail with file not found and copy refuses a missing parent`() {
        val client = client()
        client.createDirectory("/copies")
        client.write(
            "/source/note.txt",
            ByteArrayInputStream("body".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = true,
        )

        val read = assertFailsWith<SftpClientFailure> { client.openRead("/missing.txt").use { } }
        assertEquals(SftpErrorType.FILE_NOT_FOUND, read.errorType)

        val copyMissing =
            assertFailsWith<SftpClientFailure> {
                client.copy(SftpTransferRequest("/missing.txt", "/copies", false, overwrite = false, renameTo = null))
            }
        assertEquals(SftpErrorType.FILE_NOT_FOUND, copyMissing.errorType)

        val moveMissing =
            assertFailsWith<SftpClientFailure> {
                client.move(SftpTransferRequest("/missing.txt", "/copies", false, overwrite = false, renameTo = null))
            }
        assertEquals(SftpErrorType.FILE_NOT_FOUND, moveMissing.errorType)

        val renameMissing =
            assertFailsWith<SftpClientFailure> {
                client.rename("/missing.txt", "other.txt", overwrite = false)
            }
        assertEquals(SftpErrorType.FILE_NOT_FOUND, renameMissing.errorType)

        val deleteMissing = assertFailsWith<SftpClientFailure> { client.delete("/missing.txt") }
        assertEquals(SftpErrorType.FILE_NOT_FOUND, deleteMissing.errorType)

        val missingParent =
            assertFailsWith<SftpClientFailure> {
                client.copy(SftpTransferRequest("/source/note.txt", "/missing-parent", false, overwrite = false, renameTo = null))
            }
        assertEquals(SftpErrorType.ILLEGAL_PATH, missingParent.errorType)
        assertEquals("body", server.home.resolve("source/note.txt").readText())
    }

    @Test
    fun `working directory jail blocks write copy move and delete`() {
        server.home.resolve("jail").createDirectories()
        val outside = server.home.resolve("outside.txt")
        outside.writeText("secret")
        val client = client(workingDirectory = "/jail")
        client.write(
            "/inside.txt",
            ByteArrayInputStream("inside".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = true,
        )

        val write =
            assertFailsWith<SftpClientFailure> {
                client.write(
                    "../outside.txt",
                    ByteArrayInputStream("nope".toByteArray()),
                    SftpWriteMode.OVERWRITE,
                    createParentDirectories = false,
                )
            }
        assertEquals(SftpErrorType.ILLEGAL_PATH, write.errorType)

        val copy =
            assertFailsWith<SftpClientFailure> {
                client.copy(SftpTransferRequest("/inside.txt", "..", false, overwrite = false, renameTo = "outside.txt"))
            }
        assertEquals(SftpErrorType.ILLEGAL_PATH, copy.errorType)

        val move =
            assertFailsWith<SftpClientFailure> {
                client.move(SftpTransferRequest("/inside.txt", "..", false, overwrite = false, renameTo = "outside.txt"))
            }
        assertEquals(SftpErrorType.ILLEGAL_PATH, move.errorType)

        val delete = assertFailsWith<SftpClientFailure> { client.delete("../outside.txt") }
        assertEquals(SftpErrorType.ILLEGAL_PATH, delete.errorType)

        assertEquals("secret", outside.readText())
        assertEquals("inside", server.home.resolve("jail/inside.txt").readText())
    }

    @Test
    fun `list applies path size time case and directory criteria`() {
        val client = client()
        client.createDirectory("/inbox/nested")
        client.write(
            "/inbox/old.txt",
            ByteArrayInputStream("old".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )
        client.write(
            "/inbox/keep.txt",
            ByteArrayInputStream("kept-data".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )
        client.write(
            "/inbox/big.txt",
            ByteArrayInputStream("012345678901234567890123456789".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )
        client.write(
            "/inbox/Orders.CSV",
            ByteArrayInputStream("Case".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )
        Files.setLastModifiedTime(
            server.home.resolve("inbox/old.txt"),
            FileTime.from(Instant.parse("2020-01-01T00:00:00Z")),
        )
        Files.setLastModifiedTime(
            server.home.resolve("inbox/keep.txt"),
            FileTime.from(Instant.parse("2026-06-01T00:00:00Z")),
        )
        Files.setLastModifiedTime(
            server.home.resolve("inbox/big.txt"),
            FileTime.from(Instant.parse("2026-06-01T00:00:00Z")),
        )

        val selected =
            client.list(
                "/inbox",
                recursive = false,
                matcher =
                    SftpFileMatcher(
                        filenamePattern = "*.txt",
                        pathPattern = "/inbox/*.txt",
                        fileType = SftpFileType.REGULAR_FILE,
                        minSize = 5,
                        maxSize = 20,
                        modifiedSince = Instant.parse("2024-01-01T00:00:00Z"),
                        modifiedUntil = Instant.parse("2027-01-01T00:00:00Z"),
                    ),
            )
        assertEquals(listOf("/inbox/keep.txt"), selected.map { it.path })

        val byPath =
            client.list(
                "/inbox",
                recursive = false,
                matcher = SftpFileMatcher(pathPattern = "/inbox/keep.txt"),
            )
        assertEquals(listOf("/inbox/keep.txt"), byPath.map { it.path })
        assertTrue(
            client
                .list("/inbox", recursive = false, matcher = SftpFileMatcher(pathPattern = "/inbox/missing.txt"))
                .isEmpty(),
        )

        val byMinSize =
            client.list(
                "/inbox",
                recursive = false,
                matcher = SftpFileMatcher(filenamePattern = "*.txt", minSize = 5),
            )
        assertEquals(listOf("/inbox/big.txt", "/inbox/keep.txt"), byMinSize.map { it.path }.sorted())
        assertEquals(
            listOf("/inbox/big.txt"),
            client
                .list(
                    "/inbox",
                    recursive = false,
                    matcher = SftpFileMatcher(filenamePattern = "*.txt", minSize = 20),
                ).map { it.path },
        )

        val byModifiedSince =
            client.list(
                "/inbox",
                recursive = false,
                matcher =
                    SftpFileMatcher(
                        filenamePattern = "*.txt",
                        modifiedSince = Instant.parse("2024-01-01T00:00:00Z"),
                    ),
            )
        assertEquals(
            listOf("/inbox/big.txt", "/inbox/keep.txt"),
            byModifiedSince.map { it.path }.sorted(),
        )
        assertTrue(
            client
                .list(
                    "/inbox",
                    recursive = false,
                    matcher =
                        SftpFileMatcher(
                            filenamePattern = "old.txt",
                            modifiedSince = Instant.parse("2024-01-01T00:00:00Z"),
                        ),
                ).isEmpty(),
        )

        val byModifiedUntil =
            client.list(
                "/inbox",
                recursive = false,
                matcher =
                    SftpFileMatcher(
                        filenamePattern = "*.txt",
                        modifiedUntil = Instant.parse("2021-01-01T00:00:00Z"),
                    ),
            )
        assertEquals(listOf("/inbox/old.txt"), byModifiedUntil.map { it.path })
        assertTrue(
            client
                .list(
                    "/inbox",
                    recursive = false,
                    matcher =
                        SftpFileMatcher(
                            filenamePattern = "keep.txt",
                            modifiedUntil = Instant.parse("2021-01-01T00:00:00Z"),
                        ),
                ).isEmpty(),
        )

        val caseInsensitive =
            client.list(
                "/inbox",
                recursive = false,
                matcher = SftpFileMatcher(filenamePattern = "orders.csv", caseSensitive = false),
            )
        assertEquals(listOf("/inbox/Orders.CSV"), caseInsensitive.map { it.path })

        val directories =
            client.list(
                "/inbox",
                recursive = false,
                matcher = SftpFileMatcher(fileType = SftpFileType.DIRECTORY),
            )
        assertEquals(listOf("/inbox/nested"), directories.map { it.path })
    }

    @Test
    fun `list matches delivered regex filename patterns`() {
        val client = client()
        client.write(
            "/inbox/orders-2026.csv",
            ByteArrayInputStream("ok".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = true,
        )
        client.write(
            "/inbox/orders-26.csv",
            ByteArrayInputStream("no".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )

        val listed =
            client.list(
                "/inbox",
                recursive = false,
                matcher =
                    SftpFileMatcher(
                        filenamePattern = "regex:orders-\\d{4}\\.csv",
                        fileType = SftpFileType.REGULAR_FILE,
                    ),
            )

        assertEquals(listOf("/inbox/orders-2026.csv"), listed.map { it.path })
    }

    @Test
    fun `list reports a symbolic link when the server preserves it`() {
        val client = client()
        client.write(
            "/links/target.txt",
            ByteArrayInputStream("target".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = true,
        )
        Files.createSymbolicLink(server.home.resolve("links/alias.txt"), Path.of("target.txt"))

        val listed =
            client.list(
                "/links",
                recursive = false,
                matcher = SftpFileMatcher(filenamePattern = "alias.txt"),
            )

        val entry = listed.single()
        assertTrue(
            entry.symbolicLink,
            "embedded server reported regularFile=${entry.regularFile} directory=${entry.directory} for ${entry.path}",
        )
    }

    @Test
    fun `recursive delete removes a symbolic link to an outside directory without following it`() {
        val outside = outsideTreeWithSymbolicLinks()
        val client = client(workingDirectory = "/jail")

        client.delete("/tree")

        assertFalse(server.home.resolve("jail/tree").exists(LinkOption.NOFOLLOW_LINKS))
        assertOutsideTreeIntact(outside)
    }

    @Test
    fun `delete of a symbolic link to a directory removes only the link`() {
        val outside = outsideTreeWithSymbolicLinks()
        val client = client(workingDirectory = "/jail")

        client.delete("/tree/escape")

        assertFalse(server.home.resolve("jail/tree/escape").exists(LinkOption.NOFOLLOW_LINKS))
        assertEquals("kept", server.home.resolve("jail/tree/keep.txt").readText())
        assertOutsideTreeIntact(outside)
    }

    @Test
    fun `recursive copy refuses a symbolic link to an outside directory without following it`() {
        val outside = outsideTreeWithSymbolicLinks()
        server.home.resolve("jail/tree/dangling").deleteExisting()
        val client = client(workingDirectory = "/jail")

        val failure =
            assertFailsWith<SftpClientFailure> {
                client.copy(SftpTransferRequest("/tree", "/archive", true, overwrite = false, renameTo = null))
            }

        assertEquals(SftpErrorType.ILLEGAL_PATH, failure.errorType)
        assertFalse(server.home.resolve("jail/archive/tree/escape/secret.txt").exists(LinkOption.NOFOLLOW_LINKS))
        assertFalse(server.home.resolve("jail/archive/tree/escape/nested").exists(LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isSymbolicLink(server.home.resolve("jail/tree/escape")))
        assertOutsideTreeIntact(outside)
    }

    @Test
    fun `overwriting a symbolic link target replaces the link without deleting its destination`() {
        val outside = outsideTreeWithSymbolicLinks()
        val client = client(workingDirectory = "/jail")
        client.write(
            "/replacement/payload.txt",
            ByteArrayInputStream("payload".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = true,
        )

        client.copy(SftpTransferRequest("/replacement", "/tree", false, overwrite = true, renameTo = "escape"))

        assertFalse(Files.isSymbolicLink(server.home.resolve("jail/tree/escape")))
        assertEquals("payload", server.home.resolve("jail/tree/escape/payload.txt").readText())
        assertOutsideTreeIntact(outside)
    }

    @Test
    fun `copy to a dangling target link without overwrite refuses and does not write outside`() {
        prepareDanglingTargetLinkFixture()
        val client = client(workingDirectory = "/jail")

        val failure =
            assertFailsWith<SftpClientFailure> {
                client.copy(
                    SftpTransferRequest(
                        sourcePath = "/source.txt",
                        targetDirectory = "/target",
                        createParentDirectories = false,
                        overwrite = false,
                        renameTo = "link",
                    ),
                )
            }

        assertEquals(SftpErrorType.FILE_ALREADY_EXISTS, failure.errorType)
        assertTrue(Files.isSymbolicLink(server.home.resolve("jail/target/link")))
        assertFalse(server.home.resolve("outside/missing.txt").exists(LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `copy to a dangling target link with overwrite replaces the link without writing outside`() {
        prepareDanglingTargetLinkFixture()
        val client = client(workingDirectory = "/jail")

        client.copy(
            SftpTransferRequest(
                sourcePath = "/source.txt",
                targetDirectory = "/target",
                createParentDirectories = false,
                overwrite = true,
                renameTo = "link",
            ),
        )

        assertFalse(Files.isSymbolicLink(server.home.resolve("jail/target/link")))
        assertEquals("outside write probe", server.home.resolve("jail/target/link").readText())
        assertFalse(server.home.resolve("outside/missing.txt").exists(LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `private key passphrase authenticates and a wrong passphrase is rejected`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        server.allowPublicKey(keyPair.public)
        val privateKey = writeEncryptedPrivateKey(temporaryDirectory.resolve("identity.pem"), keyPair, "correct-pass")

        val wrong =
            assertFailsWith<SftpClientFailure> {
                passphraseClient(privateKey, "wrong-pass").stat("/")
            }
        assertEquals(SftpErrorType.INVALID_CREDENTIALS, wrong.errorType)
        assertTrue(passphraseClient(privateKey, "correct-pass").stat("/").directory)
    }

    @Test
    fun `write and delete classify access denied when the directory is not writable`() {
        val client = client()
        client.createDirectory("/readonly")
        client.createDirectory("/locked")
        client.write(
            "/locked/file.txt",
            ByteArrayInputStream("keep".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )
        val readonly = server.home.resolve("readonly")
        val locked = server.home.resolve("locked")
        ownerReadOnly(readonly)
        ownerReadOnly(locked)
        try {
            assumeDirectoryIsNotWritable(readonly)
            val write =
                assertFailsWith<SftpClientFailure> {
                    client.write(
                        "/readonly/file.txt",
                        ByteArrayInputStream("x".toByteArray()),
                        SftpWriteMode.CREATE_NEW,
                        createParentDirectories = false,
                    )
                }
            assertEquals(SftpErrorType.ACCESS_DENIED, write.errorType)

            assumeDirectoryIsNotWritable(locked)
            val delete = assertFailsWith<SftpClientFailure> { client.delete("/locked/file.txt") }
            assertEquals(SftpErrorType.ACCESS_DENIED, delete.errorType)
            assertEquals("keep", locked.resolve("file.txt").readText())
        } finally {
            ownerWritable(readonly)
            ownerWritable(locked)
        }
    }

    @Test
    fun `private key authentication loads a configured PKCS8 identity`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        server.allowPublicKey(keyPair.public)
        val privateKey = writePrivateKey(temporaryDirectory.resolve("identity.pem"), keyPair.private.encoded)
        val client =
            ApacheMinaSftpClient(
                SftpConnectionConfig(
                    host = EmbeddedSftpServer.HOST,
                    port = server.port,
                    username = EmbeddedSftpServer.USERNAME,
                    privateKeyPath = privateKey.toString(),
                    hostKeyChecking = SftpHostKeyChecking.INSECURE,
                ),
            )

        assertTrue(client.stat("/").directory)
    }

    private fun prepareDanglingTargetLinkFixture() {
        server.home.resolve("outside").createDirectories()
        server.home.resolve("jail/target").createDirectories()
        Files.createSymbolicLink(
            server.home.resolve("jail/target/link"),
            Path.of("../../outside/missing.txt"),
        )
        client(workingDirectory = "/jail").write(
            "/source.txt",
            ByteArrayInputStream("outside write probe".toByteArray()),
            SftpWriteMode.OVERWRITE,
            createParentDirectories = false,
        )
    }

    /**
     * Builds `jail/tree` holding a regular file, a symbolic link to the directory `outside` that lies beyond the
     * `/jail` working directory, and a dangling link.
     */
    private fun outsideTreeWithSymbolicLinks(): Path {
        val outside = server.home.resolve("outside")
        outside.resolve("nested").createDirectories()
        outside.resolve("secret.txt").writeText("secret")
        outside.resolve("nested/deep.txt").writeText("deep")
        val tree = server.home.resolve("jail/tree").createDirectories()
        tree.resolve("keep.txt").writeText("kept")
        Files.createSymbolicLink(tree.resolve("escape"), Path.of("../../outside"))
        Files.createSymbolicLink(tree.resolve("dangling"), Path.of("../../missing"))
        return outside
    }

    private fun assertOutsideTreeIntact(outside: Path) {
        assertEquals("secret", outside.resolve("secret.txt").readText())
        assertEquals("deep", outside.resolve("nested/deep.txt").readText())
    }

    private fun client(
        password: String = EmbeddedSftpServer.PASSWORD,
        workingDirectory: String = "/",
        hostKeyChecking: SftpHostKeyChecking = SftpHostKeyChecking.INSECURE,
        knownHostsFile: String = "~/.ssh/known_hosts",
    ) = ApacheMinaSftpClient(
        SftpConnectionConfig(
            host = EmbeddedSftpServer.HOST,
            port = server.port,
            username = EmbeddedSftpServer.USERNAME,
            password = password,
            hostKeyChecking = hostKeyChecking,
            knownHostsFile = knownHostsFile,
            workingDirectory = workingDirectory,
            connectionTimeoutMs = 5_000,
            authenticationTimeoutMs = 5_000,
            operationTimeoutMs = 5_000,
        ),
    )

    private fun passphraseClient(
        privateKey: Path,
        passphrase: String,
    ) = ApacheMinaSftpClient(
        SftpConnectionConfig(
            host = EmbeddedSftpServer.HOST,
            port = server.port,
            username = EmbeddedSftpServer.USERNAME,
            privateKeyPath = privateKey.toString(),
            privateKeyPassphrase = passphrase,
            hostKeyChecking = SftpHostKeyChecking.INSECURE,
            connectionTimeoutMs = 5_000,
            authenticationTimeoutMs = 5_000,
            operationTimeoutMs = 5_000,
        ),
    )

    private fun writeEncryptedPrivateKey(
        path: Path,
        keyPair: KeyPair,
        passphrase: String,
    ): Path {
        val encryption =
            OpenSSHKeyEncryptionContext().apply {
                password = passphrase
                cipherType = "128"
            }
        Files.newOutputStream(path).use { output ->
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "muse-test", encryption, output)
        }
        return path
    }

    private fun ownerReadOnly(directory: Path) {
        Files.setPosixFilePermissions(
            directory,
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
        )
    }

    private fun ownerWritable(directory: Path) {
        Files.setPosixFilePermissions(
            directory,
            EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }

    private fun assumeDirectoryIsNotWritable(directory: Path) {
        val probe = directory.resolve(".writable-probe")
        val writable =
            try {
                Files.writeString(probe, "x")
                true
            } catch (_: AccessDeniedException) {
                false
            } finally {
                Files.deleteIfExists(probe)
            }
        assumeTrue(!writable) { "owner can still modify this directory" }
    }

    private fun writePrivateKey(
        path: Path,
        encoded: ByteArray,
    ): Path {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encoded)
        Files.writeString(
            path,
            "-----BEGIN PRIVATE KEY-----\n$base64\n-----END PRIVATE KEY-----\n",
        )
        return path
    }
}
