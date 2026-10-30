def outputDir = new File(basedir, "target/generated-sources/muse")
if (outputDir.exists()) {
    def ktFiles = []
    outputDir.eachFileRecurse { if (it.name.endsWith(".kt")) ktFiles << it }
    assert ktFiles.size() == 0 : "No Kotlin files should be generated when the DB mapper is project-only"
}

def buildLog = new File(basedir, "build.log").text
assert buildLog.contains("BUILD FAILURE") : "Expected BUILD FAILURE"
def header = "Failed to translate 'db-flow.xml': 2 distinct unmapped occurrences"
def config = "no mapper registered for 'db:config'"
def select = "no mapper registered for 'db:select'"
def dependencyGuidance = "Maven plugin's <dependencies> element"
def realmGuidance = "ordinary project dependency is not visible to the plugin realm"

assert buildLog.count(header) == 1 : "Expected one counted missing-mapper header"
assert buildLog.count(config) == 1 : "Expected one db:config occurrence"
assert buildLog.count(select) == 1 : "Expected one db:select occurrence"
assert buildLog.indexOf(config) < buildLog.indexOf(select) :
    "Expected db:config before db:select"
assert buildLog.count(dependencyGuidance) == 1 : "Expected one plugin dependency guidance footer"
assert buildLog.count(realmGuidance) == 1 : "Expected one Maven realm guidance footer"
assert !buildLog.contains("Translation failed with 2 unmapped element(s)") :
    "Raw Babelfish aggregate must not duplicate the formatted diagnostic"
assert !buildLog.contains("No mapper registered for 'db:config'") :
    "Raw config cause must not be repeated"
assert !buildLog.contains("No mapper registered for 'db:select'") :
    "Raw select cause must not be repeated"
