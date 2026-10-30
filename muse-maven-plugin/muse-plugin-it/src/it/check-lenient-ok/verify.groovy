def buildLog = new File(basedir, "build.log").text
assert buildLog.contains("Muse Check — scanning") : "Expected check scanning message"
assert buildLog.contains("NOT IMPLEMENTED") : "Expected NOT IMPLEMENTED warning for jms:publish"
assert buildLog.contains("Build would fail in strict mode") : "Expected lenient warning message"