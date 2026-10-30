def buildLog = new File(basedir, "build.log").text
assert buildLog.contains("Muse Check — scanning") : "Expected check scanning message"
assert buildLog.contains("SET_PAYLOAD") || buildLog.contains("set-payload") : "Expected set-payload in output"
assert buildLog.contains("LOGGER") || buildLog.contains("logger") : "Expected logger in output"
assert buildLog.contains("SET_VARIABLE") || buildLog.contains("set-variable") : "Expected set-variable in output"
assert buildLog.contains("All components are implemented in Muse") : "Expected success message"
assert !buildLog.contains("BUILD FAILURE") || buildLog.contains("BUILD SUCCESS")