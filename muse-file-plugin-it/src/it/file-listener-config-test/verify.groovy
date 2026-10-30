def outputDir = new File(basedir, "target/generated-sources/muse")
assert outputDir.exists() : "Output directory missing: $outputDir"

def configFiles = []
outputDir.eachFileRecurse { if (it.name.endsWith("Config.kt")) configFiles << it }
assert configFiles.size() == 1 : "Expected one generated File config, got ${configFiles.size()}"

def generatedCode = configFiles[0].text
assert generatedCode.contains("import dev.museframework.file.api.dsl.fileConnection") : "File connection import missing"
assert generatedCode.contains("import dev.museframework.file.api.dsl.fileListener") : "File listener import missing"
assert generatedCode.contains("import dev.museframework.dsl.dsl.setPayload") : "Core provider import missing"
assert generatedCode.contains("fileConnection {") : "File connection DSL missing"
assert generatedCode.contains('name = "File_Config"') : "File connection name missing"
assert generatedCode.contains('workingDirectory = "/tmp/muse-file"') : "File working directory missing"
assert generatedCode.contains('fileListener("File_Config")') : "File listener DSL missing"
assert generatedCode.contains('directory = "input"') : "File listener directory missing"
assert generatedCode.contains('moveToDirectory = "output"') : "File listener archive directory missing"
assert generatedCode.contains('filenamePattern = "*.csv"') : "File listener matcher missing"
assert generatedCode.contains("setPayload") : "Core set-payload DSL missing"
assert !generatedCode.contains("fileRead") : "Unexpected file read in archive-only fixture"
assert !generatedCode.contains("dev.museframework.connectors.file") : "File runtime package leaked into generated DSL"
