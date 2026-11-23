def log = new File(basedir, "build.log").text
assert log.contains("Babelfish registration collision for element mapper key 'set-payload'")
assert log.contains("existing owner id='core', class='dev.museframework.babelfish.mappers.core.CoreMapperProvider'")
assert log.contains("incoming owner id='duplicate-payload', class='dev.museframework.it.DuplicatePayloadProvider'")
assert !new File(basedir, "target/generated-sources/muse").exists()
