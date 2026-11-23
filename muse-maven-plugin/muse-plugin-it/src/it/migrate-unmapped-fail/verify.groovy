def buildLog = new File(basedir, "build.log").text
assert buildLog.contains("Muse Migrate") : "Expected Migrate start message"
assert buildLog.contains("NOT IMPLEMENTED") || buildLog.contains("not implemented") : "Expected unmapped components message"
assert buildLog.contains("publish") : "Expected jms:publish reference"
assert buildLog.contains("Skipped generation for 'mixed-flow.xml'")
def configs = []
new File(basedir, "src/main/kotlin").eachFileRecurse { if (it.name.endsWith("Config.kt")) configs << it }
assert configs*.name == ["ValidConfig.kt"] : "Lenient migration must skip only the affected XML file"
