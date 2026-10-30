def buildLog = new File(basedir, "build.log").text

// Verify check ran
assert buildLog.contains("Muse Check — scanning") : "Expected check scanning message"

// Verify all implemented constructs appear as ✅ (implemented)
def implementedMarkers = buildLog.readLines().findAll { it.contains("\u2705") }
assert implementedMarkers.size() >= 20 : "Expected at least 20 implemented components, got ${implementedMarkers.size()}"

// Verify NO unmapped components
assert !buildLog.contains("\u274C") : "Found unmapped components (❌) in output"

// Verify success message
assert buildLog.contains("All components are implemented in Muse") : "Expected success message"

// Verify build succeeded
assert !buildLog.contains("BUILD FAILURE") || buildLog.contains("BUILD SUCCESS")
