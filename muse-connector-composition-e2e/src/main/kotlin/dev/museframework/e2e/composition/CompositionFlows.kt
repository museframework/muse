package dev.museframework.e2e.composition

import dev.museframework.db.api.dsl.dbConfig
import dev.museframework.db.api.dsl.dbInsert
import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.api.model.error.ErrorType
import dev.museframework.dsl.dsl.flow
import dev.museframework.file.api.dsl.fileConnection
import dev.museframework.file.api.dsl.fileListener
import dev.museframework.file.api.model.WatermarkMode
import dev.museframework.sftp.api.config.SftpHostKeyChecking
import dev.museframework.sftp.api.dsl.sftpConnection
import dev.museframework.sftp.api.dsl.sftpWrite
import dev.museframework.springboot.MuseConfigComponent

@MuseConfigComponent
class CompositionFlows : MuseConfigDefinition() {
    private val fileDirectory =
        System.getProperty(
            CompositionSystemProperties.FILE_DIRECTORY,
            System.getProperty("java.io.tmpdir"),
        )

    val fileConnection =
        fileConnection {
            name = "composition-files"
            workingDirectory = fileDirectory
        }

    val databaseConnection =
        dbConfig("composition-db") {
            generic {
                url = System.getProperty(CompositionSystemProperties.DB_JDBC_URL, "")
                driverClassName = "org.h2.Driver"
                user = System.getProperty(CompositionSystemProperties.DB_USER, "")
                password = System.getProperty(CompositionSystemProperties.DB_PASSWORD, "")
            }
        }

    val sftpConnection =
        sftpConnection {
            name = "composition-sftp"
            host = System.getProperty(CompositionSystemProperties.SFTP_HOST, "127.0.0.1")
            port = System.getProperty(CompositionSystemProperties.SFTP_PORT, "22").toInt()
            username = System.getProperty(CompositionSystemProperties.SFTP_USERNAME, "muse")
            password = System.getProperty(CompositionSystemProperties.SFTP_PASSWORD, "secret")
            hostKeyChecking = SftpHostKeyChecking.INSECURE
            connectionTimeoutMs = 30_000
            authenticationTimeoutMs = 30_000
            operationTimeoutMs = 180_000
        }

    val compositionTransferFlow =
        flow("composition-transfer") {
            maxConcurrency = 1
            fileListener("composition-files") {
                directory = "incoming"
                pollingFrequencyMillis = 10_000
                moveToDirectory = "archive"
                overwrite = true
                autoDelete = false
                applyPostActionWhenFailed = false
                watermarkMode = WatermarkMode.DISABLED
                outputMimeType = "application/octet-stream"
            }
            processor(
                CompositionProbeConfig(
                    phase = ProbePhase.ADMISSION,
                    docName = "Composition admission probe",
                ),
            )
            sftpWrite(sftpConnection) {
                path = "/#[attributes.fileName]"
                createParentDirectories = true
            }
            dbInsert(databaseConnection) {
                sql = "INSERT INTO audit (id, byte_count) VALUES (:id, :size)"
                inputParameters(
                    mapOf(
                        "id" to "#[attributes.fileName]",
                        "size" to "#[attributes.size]",
                    ),
                )
                target = "auditResult"
            }
            processor(
                CompositionProbeConfig(
                    phase = ProbePhase.SUCCESS,
                    docName = "Composition success probe",
                ),
            )
            errorHandler {
                onErrorPropagate(ErrorType.Mule.ANY) {
                    processor(
                        CompositionProbeConfig(
                            phase = ProbePhase.FAILURE,
                            docName = "Composition failure probe",
                        ),
                    )
                }
            }
        }
}

object CompositionSystemProperties {
    const val FILE_DIRECTORY = "composition.file.directory"
    const val SFTP_HOST = "composition.sftp.host"
    const val SFTP_PORT = "composition.sftp.port"
    const val SFTP_USERNAME = "composition.sftp.username"
    const val SFTP_PASSWORD = "composition.sftp.password"
    const val DB_JDBC_URL = "composition.db.jdbcUrl"
    const val DB_USER = "composition.db.user"
    const val DB_PASSWORD = "composition.db.password"
}
