# Mule APIKit parity — Maven Invoker fixture

This fixture deliberately keeps the XML, RAML, and properties from
`examples/mule-test-backend` unchanged while building a separate application
with the same Muse Spring Boot configuration.

## Test ownership

The `muse-plugin-it` module's Maven Invoker uses an isolated repository.
Its POM lists the runtime Spring adapters as reactor dependencies so that
`muse-apikit-connector`, `muse-http-connector-api`, and their host adapters
are installed from the **current source tree**, not resolved as old published
`0.1.0` artifacts.

`invoker.properties` runs `muse:check verify`. After generated source
compilation, `verify.groovy` starts `muse:run` in a child Maven process and
checks all five HTTP scenarios on port 8080:

| Scenario | Expected |
| --- | ---: |
| GET /api/resources | 200 |
| POST /api/resources, application/octet-stream | 200 |
| GET /api/missing | 404 |
| POST /api/resources, application/json | 415 |
| PUT /api/resources | 405 |

The error responses must use `application/json` and contain their expected
messages. The child process and its descendants are shut down after the checks.

This proves Muse-side behavior against the recorded contract. It does **not**
start Mule EE, compare two independently running runtimes, or demonstrate a
publicly released Maven artifact. Use
`examples/mule-test-backend/scripts/compare-mule-muse-http.py` to capture and
compare both runtimes in the environment where Mule EE is available.
