# Object Store connector

Muse provides an application-owned Object Store connector with the seven Mule Object Store operations. The built-in backend is local H2: its default store is persistent, and named stores can be persistent or transient. Flows in one application share their selected backend.

| Operation | Required input | Optional input |
| --- | --- | --- |
| `objectStoreClear` | None | Store reference |
| `objectStoreContains` | Key | Store reference, target variable/value |
| `objectStoreRemove` | Key | Store reference |
| `objectStoreRetrieve` | Key | Default value, store reference, target variable/value |
| `objectStoreRetrieveAll` | None | Store reference, target variable/value |
| `objectStoreRetrieveAllKeys` | None | Store reference, target variable/value |
| `objectStoreStore` | Key | Value (defaults to payload), `failIfPresent` (false), `failOnNullValue` (true), store reference |

The functions are in `dev.museframework.objectstore.api.dsl`. `objectStore { ... }` declares a named store; include the returned `ObjectStoreConfig` in the application's global configurations. Operations accept `ObjectStoreRef.Default`, `ObjectStoreRef.ByName`, or `ObjectStoreRef.ByReference`, and can also take an `ObjectStoreConfig` directly. `objectStoreConnector { ... }` declares optional lock settings. A missing or duplicate named store or connector fails during bootstrap, including references in nested flows.

## Backend selection

Set the deployment property `muse.object-store.backend` to the ID of an `ObjectStoreBackendProvider` installed on the application classpath. For example, add a provider JAR as an application dependency and declare a Muse global property:

```kotlin
GlobalProperty(docName = "object-store-backend", name = "muse.object-store.backend", value = "example-backend")
```

The property defaults to `h2`, which is reserved for the built-in implementation. Adding a provider dependency by itself does not change that default, and providers that are not selected do not affect startup. Muse selects one external provider during bootstrap, then asks it to open the implicit default store and each declared named store. An unknown, blank, or multiply registered selected ID fails bootstrap; Muse never silently falls back to H2. The property is read after all global property sources are applied, regardless of their declaration order.

A provider JAR implements `dev.museframework.connectors.objectstore.backend.ObjectStoreBackendProvider` and includes this service descriptor:

```text
META-INF/services/dev.museframework.connectors.objectstore.backend.ObjectStoreBackendProvider
```

The descriptor contains the provider's fully qualified class name. The provider exposes a unique `id` and returns an `ObjectStoreBackend` for each `open` call. The runtime passes the resolved store configuration, its optional connector configuration, `default` or `named:<name>` identity, and string deployment properties. The provider validates its own settings and must clean up resources if its `open` call throws. Muse closes previously opened stores on bootstrap failure and all stores on application shutdown. Provider implementations can use the public backend exceptions to preserve `OS:KEY_ALREADY_EXISTS`, `OS:KEY_NOT_FOUND`, and `MULE:VALIDATION` error categories.

This SPI selects implementations; it does not provide an Object Store v2 client. A future remote provider must implement its own authentication, encoding, limits, expiration, and consistency contract. Changing the selected backend does not migrate H2 data.

Returning operations replace the payload unless a `TargetConfig` is supplied; a target preserves the original payload and writes the result to the named variable. Mutating operations preserve the event. `retrieve` evaluates its default only when the key is absent. Keys that evaluate to null or an empty string fail with `OS:INVALID_KEY`; whitespace-only keys are valid.

## Local state

With the `h2` backend, set `muse.object-store.data-dir` and `muse.object-store.namespace` as Muse global properties to choose the state location. The namespace defaults to `default`. Without an explicit data directory, Muse uses `${user.home}/.muse/object-store/<hash-of-canonical-user.dir>`. Keep the directory outside source control. Two applications sharing a data directory should use different namespaces. A persistent store survives process restart; a transient store exists only for the current application. H2 permits one process at a time to open the same embedded database.

For H2, named stores accept `persistent` (true), `maxEntries` (unbounded), `entryTtl` (no time limit), and `expirationInterval` (one minute). Positive TTL and capacity limits are applied by the periodic sweep, beginning one full interval after application start. An interval of zero or less disables the sweep. Capacity eviction removes the oldest write first, with key order breaking ties. A changed TTL or capacity for an existing persistent store fails at bootstrap instead of reinterpreting stored data. Other providers must define how they interpret or reject these settings.

Values are materialized snapshots of strings, booleans, finite numeric scalars, byte arrays, lists, and string-keyed maps of those values. MIME type and charset are retained for a top-level retrieved value. Streams, arbitrary objects, and cyclic values fail with `MULE:VALIDATION` before the store changes. The connector does not deserialize Java objects.

The H2 backend uses application-scoped locks and H2 transactions. It does not provide Object Store v2, distributed locks, multi-worker consistency, cross-operation transactions, or CloudHub state sharing. Backend failures use `OS:STORE_NOT_AVAILABLE` and retain their cause; key errors use the documented `OS:*` types.

## Mule XML translation

The independent `muse-object-store-babelfish-mappers` provider translates `os:config`, `os:object-store`, and all seven `os:*` operation elements. It preserves omitted defaults, target expressions, and child CDATA text. It accepts the local default connection and rejects custom providers, private nested stores, and unsupported attributes with source-location diagnostics.

Focused verification: `mvn verify -pl :muse-object-store-connector,:muse-object-store-babelfish-mappers -am -Dkotlin.daemon.enabled=false`. Family-owned packaged and Maven Invoker acceptance live in `muse-object-store-packaged-e2e` and `muse-object-store-plugin-it`.
