def outputDir = new File(basedir, "target/generated-sources/muse")
if (outputDir.exists()) {
    def ktFiles = []
    outputDir.eachFileRecurse { if (it.name.endsWith(".kt")) ktFiles << it }
    assert ktFiles.size() == 0 : "No Kotlin files should be generated when the SFTP mapper is missing"
}

def buildLog = new File(basedir, "build.log").text
assert buildLog.contains("BUILD FAILURE") : "Expected BUILD FAILURE"
def header = "Failed to translate 'sftp-flow.xml': 3 distinct unmapped occurrences"
def config = "no mapper registered for 'sftp:config'"
def write = "no mapper registered for 'sftp:write'"
def read = "no mapper registered for 'sftp:read'"
def dependencyGuidance = "Maven plugin's <dependencies> element"
def realmGuidance = "ordinary project dependency is not visible to the plugin realm"

assert buildLog.count(header) == 1 : "Expected one counted missing-mapper header"
assert buildLog.count(config) == 1 : "Expected one sftp:config occurrence"
assert buildLog.count(write) == 1 : "Expected one sftp:write occurrence"
assert buildLog.count(read) == 1 : "Expected one sftp:read occurrence"
assert buildLog.indexOf(config) < buildLog.indexOf(write) :
    "Expected sftp:config before sftp:write"
assert buildLog.indexOf(write) < buildLog.indexOf(read) :
    "Expected sftp:write before sftp:read"
assert buildLog.count(dependencyGuidance) == 1 : "Expected one plugin dependency guidance footer"
assert buildLog.count(realmGuidance) == 1 : "Expected one Maven realm guidance footer"
assert !buildLog.contains("Translation failed with 3 unmapped element(s)") :
    "Raw Babelfish aggregate must not duplicate the formatted diagnostic"
assert !buildLog.contains("No mapper registered for 'sftp:config'") :
    "Raw config cause must not be repeated"
assert !buildLog.contains("No mapper registered for 'sftp:write'") :
    "Raw write cause must not be repeated"
assert !buildLog.contains("No mapper registered for 'sftp:read'") :
    "Raw read cause must not be repeated"
