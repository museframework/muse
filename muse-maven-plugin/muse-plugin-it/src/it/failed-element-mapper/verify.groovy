def log = new File(basedir, "build.log").text
assert log.contains("Required attribute 'value' missing on ':set-payload'")
assert !new File(basedir, "target/generated-sources/muse").exists()
