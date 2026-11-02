def log = new File(basedir, "build.log").text
assert log.contains("BUILD FAILURE")
assert log.contains("no mapper registered for 'os:object-store'")
assert log.contains("no mapper registered for 'os:store'")
assert log.contains("Maven plugin's <dependencies> element")
assert log.contains("ordinary project dependency is not visible to the plugin realm")
def output = new File(basedir, "target/generated-sources/muse")
if (output.exists()) {
    def sources = []
    output.eachFileRecurse { if (it.name.endsWith(".kt")) sources << it }
    assert sources.empty : "Failed translation must not leave generated Kotlin"
}
