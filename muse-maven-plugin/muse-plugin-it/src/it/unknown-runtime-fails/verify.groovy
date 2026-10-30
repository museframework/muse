def buildLog = new File(basedir, "build.log").text
assert buildLog.contains("Unknown runtime 'micronaut'") \
    : "Expected unknown-runtime message in build log"
assert buildLog.contains("spring-boot") \
    : "Build log must list valid runtimes"
