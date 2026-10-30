def outputDir = new File(basedir, "target/generated-sources/muse")
assert outputDir.exists() : "Output directory missing: $outputDir"

def configFiles = []
outputDir.eachFileRecurse { if (it.name.endsWith("Config.kt")) configFiles << it }
assert configFiles.size() == 3 : "Expected exactly 3 *Config.kt files, got ${configFiles.size()}"

def generatedCode = configFiles.collect { it.text }.join("\n")
assert generatedCode.contains('flowRef("validate-input-subflow")') : "Cross-file validation flow reference missing"
assert generatedCode.contains('flowRef("process-data-subflow")') : "Cross-file processing flow reference missing"
assert generatedCode.contains('subFlow("format-output-subflow")') : "Formatting subflow missing"
assert generatedCode.contains('subFlow("transform-data-subflow")') : "Transformation subflow missing"
