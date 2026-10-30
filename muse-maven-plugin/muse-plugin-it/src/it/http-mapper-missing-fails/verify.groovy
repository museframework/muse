def outputDir = new File(basedir, "target/generated-sources/muse")
if (outputDir.exists()) {
    def ktFiles = []
    outputDir.eachFileRecurse { if (it.name.endsWith(".kt")) ktFiles << it }
    assert ktFiles.size() == 0 : "No Kotlin files should be generated when HTTP mapper is missing"
}

def buildLog = new File(basedir, "build.log").text
assert buildLog.contains("BUILD FAILURE") : "Expected BUILD FAILURE"
def header = "Failed to translate 'http-listener-flow.xml': 2 distinct unmapped occurrences"
def listenerConfig = "no mapper registered for 'http:listener-config'"
def listener = "no mapper registered for 'http:listener'"
def dependencyGuidance = "Maven plugin's <dependencies> element"
def realmGuidance = "ordinary project dependency is not visible to the plugin realm"

assert buildLog.count(header) == 1 : "Expected one counted missing-mapper header"
assert buildLog.count(listenerConfig) == 1 : "Expected one http:listener-config occurrence"
assert buildLog.count(listener) == 1 : "Expected one http:listener occurrence"
assert buildLog.indexOf(listenerConfig) < buildLog.indexOf(listener) :
    "Expected http:listener-config before http:listener"
assert buildLog.count(dependencyGuidance) == 1 : "Expected one plugin dependency guidance footer"
assert buildLog.count(realmGuidance) == 1 : "Expected one Maven realm guidance footer"
assert !buildLog.contains("Translation failed with 2 unmapped element(s)") :
    "Raw Babelfish aggregate must not duplicate the formatted diagnostic"
assert !buildLog.contains("No mapper registered for 'http:listener-config'") :
    "Raw listener-config cause must not be repeated"
assert !buildLog.contains("No mapper registered for 'http:listener'") :
    "Raw listener cause must not be repeated"
