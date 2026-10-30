def outputDir = new File(basedir, "target/generated-sources/muse")
if (outputDir.exists()) {
    def ktFiles = []
    outputDir.eachFileRecurse { if (it.name.endsWith(".kt")) ktFiles << it }
    assert ktFiles.size() == 0 : "No Kotlin files should be generated when the File mapper is project-only"
}

def buildLog = new File(basedir, "build.log").text
assert buildLog.contains("BUILD FAILURE") : "Expected BUILD FAILURE"
def header = "Failed to translate 'file-listener-flow.xml': 3 distinct unmapped occurrences"
def config = "no mapper registered for 'file:config'"
def listener = "no mapper registered for 'file:listener'"
def read = "no mapper registered for 'file:read'"
def dependencyGuidance = "Maven plugin's <dependencies> element"
def realmGuidance = "ordinary project dependency is not visible to the plugin realm"

assert buildLog.count(header) == 1 : "Expected one counted missing-mapper header"
assert buildLog.count(config) == 1 : "Expected one file:config occurrence"
assert buildLog.count(listener) == 1 : "Expected one file:listener occurrence"
assert buildLog.count(read) == 1 : "Expected one file:read occurrence"
assert buildLog.indexOf(config) < buildLog.indexOf(listener) :
    "Expected file:config before file:listener"
assert buildLog.indexOf(listener) < buildLog.indexOf(read) :
    "Expected file:listener before file:read"
assert buildLog.count(dependencyGuidance) == 1 : "Expected one plugin dependency guidance footer"
assert buildLog.count(realmGuidance) == 1 : "Expected one Maven realm guidance footer"
assert !buildLog.contains("Translation failed with 3 unmapped element(s)") :
    "Raw Babelfish aggregate must not duplicate the formatted diagnostic"
assert !buildLog.contains("No mapper registered for 'file:config'") :
    "Raw config cause must not be repeated"
assert !buildLog.contains("No mapper registered for 'file:listener'") :
    "Raw listener cause must not be repeated"
assert !buildLog.contains("No mapper registered for 'file:read'") :
    "Raw read cause must not be repeated"
