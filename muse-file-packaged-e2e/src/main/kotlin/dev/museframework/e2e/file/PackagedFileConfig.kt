package dev.museframework.e2e.file

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.flow
import dev.museframework.file.api.dsl.fileConnection
import dev.museframework.file.api.dsl.fileListener
import dev.museframework.file.api.dsl.fileWrite
import dev.museframework.springboot.MuseConfigComponent

@MuseConfigComponent
class PackagedFileConfig : MuseConfigDefinition() {
    val fileConnection =
        fileConnection {
            name = "packaged-files"
            workingDirectory =
                System.getProperty("packaged.file.directory", System.getProperty("java.io.tmpdir"))
        }

    val fileFlow =
        flow("packaged-file-flow") {
            fileListener("packaged-files") {
                directory = "incoming"
                pollingFrequencyMillis = 25
                moveToDirectory = "archive"
                renameTo = "processed.txt"
                overwrite = true
            }
            fileWrite(fileConnection("packaged-files")) {
                path = "output/copied.txt"
            }
        }
}
