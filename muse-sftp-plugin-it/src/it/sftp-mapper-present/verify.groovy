def outputDir = new File(basedir, "target/generated-sources/muse")
assert outputDir.exists() : "Output directory missing: $outputDir"

def configFiles = []
outputDir.eachFileRecurse { if (it.name.endsWith("Config.kt")) configFiles << it }
assert configFiles.size() == 1 : "Expected one generated SFTP config, got ${configFiles.size()}"

def generatedCode = configFiles[0].text
assert generatedCode.contains("import dev.museframework.sftp.api.dsl.sftpConnection") : "SFTP connection import missing"
assert generatedCode.contains("import dev.museframework.sftp.api.dsl.sftpWrite") : "SFTP write import missing"
assert generatedCode.contains("import dev.museframework.sftp.api.dsl.sftpRead") : "SFTP read import missing"
assert generatedCode.contains("import dev.museframework.dsl.dsl.setPayload") : "Core provider import missing"
assert generatedCode.contains("sftpConnection {") : "SFTP connection DSL missing"
assert generatedCode.contains('name = "Sftp_Config"') : "SFTP connection name missing"
assert generatedCode.contains('host = "127.0.0.1"') : "SFTP host missing"
assert generatedCode.contains('portExpression = "\\${sftp.port}"') : "SFTP port placeholder missing"
assert generatedCode.contains('workingDirectory = "/data"') : "SFTP working directory missing"
assert generatedCode.contains('sftpWrite(sftpConnection("Sftp_Config"))') : "SFTP write DSL missing"
assert generatedCode.contains('path = "/outbound/request.bin"') : "SFTP write path missing"
assert generatedCode.contains('modeExpression = "#[vars.mode]"') : "Dynamic SFTP write mode missing"
assert generatedCode.contains('outputMimeType = "application/csv; streaming=true; header=true"') : "CSV reader metadata missing"
assert generatedCode.contains('nonRepeatable = true') : "Non-repeatable streaming intent missing"
assert generatedCode.contains('batchSizeExpression = "') && generatedCode.contains('batch.size') : "Property-based batching missing"
assert generatedCode.contains('DwExpression.Resource("dataweave/to-text.dwl")') : "DataWeave resource missing"
assert generatedCode.contains('sftpRead(sftpConnection("Sftp_Config"))') : "SFTP read DSL missing"
assert generatedCode.contains("setPayload") : "Core set-payload DSL missing"
assert !generatedCode.contains("dev.museframework.connectors.sftp") : "SFTP runtime package leaked into generated DSL"
