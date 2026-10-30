def buildLog = new File(basedir, "build.log").text
assert buildLog.contains("Muse Check — scanning") : "Expected check scanning message"
assert buildLog.contains("NOT IMPLEMENTED") : "Expected NOT IMPLEMENTED warning for jms:publish"
assert buildLog.contains("publish") : "Expected jms:publish in output"
assert buildLog.contains("unmapped component(s)") || buildLog.contains("not implemented") : "Expected unmapped summary"