def outputDir = new File(basedir, "target/generated-sources/muse")
assert outputDir.exists() : "Output directory missing: $outputDir"

def ktFiles = []
outputDir.eachFileRecurse { if (it.name.endsWith(".kt")) ktFiles << it }
assert ktFiles.size() > 0 : "No .kt files in $outputDir"

def entryPointFile = ktFiles.find { it.name.contains("Application") }
assert entryPointFile != null : "No *Application.kt file found"
assert entryPointFile.text.contains("runMuseApplication") : "Entry point must contain runMuseApplication"
