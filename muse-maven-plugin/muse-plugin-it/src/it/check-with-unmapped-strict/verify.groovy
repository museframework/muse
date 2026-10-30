def buildLog = new File(basedir, "build.log").text

// Verify check ran
assert buildLog.contains("Muse Check — scanning") : "Expected check scanning message"

// Verify unmapped component is reported
assert buildLog.contains("NOT IMPLEMENTED") : "Expected NOT IMPLEMENTED warning"
assert buildLog.contains("publish") : "Expected jms:publish reference in output"

// Verify implemented components still appear
assert buildLog.contains("\u2705") : "Expected implemented components (✅) in output"

// Verify failure summary
assert buildLog.contains("unmapped component(s)") || buildLog.contains("not implemented") : "Expected unmapped summary"
