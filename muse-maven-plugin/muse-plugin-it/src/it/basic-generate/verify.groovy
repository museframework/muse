def outputDir = new File(basedir, "target/generated-sources/muse")
assert outputDir.exists() : "Output directory missing: $outputDir"

def ktFiles = []
outputDir.eachFileRecurse { if (it.name.endsWith(".kt")) ktFiles << it }
assert ktFiles.size() > 0 : "No .kt files in $outputDir"

def configFile = ktFiles.find { it.name.endsWith("Config.kt") }
assert configFile != null : "No *Config.kt file found"
assert configFile.text.contains("@MuseConfig")          : "@MuseConfig annotation missing"
assert configFile.text.contains("MuseConfigDefinition") : "MuseConfigDefinition base class missing"
assert configFile.text.contains("hello-flow") || configFile.text.contains("helloFlow") \
                                            : "Flow name not referenced in generated code"
