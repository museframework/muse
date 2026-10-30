# Mule Test Backend on Quarkus

This example uses Muse to translate the Mule XML application into Kotlin and run it on Quarkus.

## Prerequisites

- JDK 21
- Maven 3.9+
- Muse `0.1.0` artifacts available to Maven

## Run

From this directory:

```bash
mvn compile quarkus:dev
```

The `compile` phase runs the Muse generator. Quarkus then starts the generated application on port `8080`.

Check the backend with:

```bash
curl http://localhost:8080/api/resources
```

The request returns JSON containing the simulated execution time. Stop the application with `Ctrl+C`.
