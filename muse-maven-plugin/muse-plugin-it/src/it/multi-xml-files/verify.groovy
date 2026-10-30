def outputDir = new File(basedir, "target/generated-sources/muse")
def ktFiles = []
outputDir.eachFileRecurse { if (it.name.endsWith(".kt")) ktFiles << it }
assert ktFiles.size() >= 3 : "Expected at least 3 .kt files, got ${ktFiles.size()}"

def configFiles = ktFiles.findAll { it.name.endsWith("Config.kt") }
assert configFiles.size() == 3 : "Expected exactly 3 *Config.kt files (one per XML)"
