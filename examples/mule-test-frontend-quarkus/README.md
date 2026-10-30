# Mule Test Frontend on Quarkus

This example uses Muse to translate the Mule XML application into Kotlin and run it on Quarkus. It calls the companion backend at `localhost:8080`.

## Prerequisites

- JDK 21
- Maven 3.9+
- Muse `0.1.0` artifacts available to Maven

## Run

Start [`mule-test-backend-quarkus`](../mule-test-backend-quarkus/) first. In a second terminal, run from this directory:

```bash
mvn compile quarkus:dev
```

The `compile` phase runs the Muse generator. Quarkus then starts the generated application on port `8081`; its scheduler calls the backend once per second.

Check the streaming proxy with:

```bash
curl --fail-with-body \
  -H 'Content-Type: application/octet-stream' \
  --data-binary 'hello from Muse' \
  http://localhost:8081/streaming/echo
```

The response echoes `hello from Muse`. Stop the application with `Ctrl+C`.
