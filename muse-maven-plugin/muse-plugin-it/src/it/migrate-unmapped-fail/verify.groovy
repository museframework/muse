def buildLog = new File(basedir, "build.log").text
assert buildLog.contains("Muse Migrate") : "Expected Migrate start message"
assert buildLog.contains("NOT IMPLEMENTED") || buildLog.contains("not implemented") : "Expected unmapped components message"
assert buildLog.contains("publish") : "Expected jms:publish reference"