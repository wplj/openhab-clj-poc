# openhab-clj-poc

An experimental Clojure implementation of OpenHAB-style home-automation core concepts. The goal is not to translate the Java/OpenHAB implementation into Clojure syntax. The goal is to test a Clojure-native model built from immutable data, pure transitions, explicit effects, and derived item state.

The current baseline focuses on the framework core: Things, Channels, Bridges, Items, Links, profile-based projection, commands, reporting, polling, events, and effect dispatch. It is intentionally small and in-memory so the state model and runtime boundaries stay easy to inspect.

## Current Scope

Implemented:

- Immutable registry state with indexes for item links, channel links, and bridge children.
- Pure transitions returning `{:state ... :events ... :effects ... :result ...}`.
- A single imperative runtime boundary that owns `swap!`, event publication, and effect dispatch.
- Separate `:reported` and `:desired` channel state, with projection from effective state into items.
- Profile and codec registries for typed inbound projection and outbound command encoding.
- Bridge topology rules, status propagation, polling/reporting lifecycle helpers, and command failure correction.
- Example addon slice with an in-memory device API, profile codecs, synchronous/asynchronous startup reporting, command dispatch, event publication, and integration tests.
- Pure query/read-model views for future API and UI edges, without exposing raw registry indexes or runtime internals.
- Pure API view serialization over the query model, converting Clojure-native values to JSON-safe public values.
- Ring/Reitit/http-kit HTTP handler and server lifecycle for system, Thing, Item, Item command, and SSE event endpoints.

Not implemented yet:

- Persistence.
- Authentication/authorization.
- Production HTTP hardening.
- Group item aggregation.
- Production addon packaging.

## Design Principles

- State is plain EDN data.
- Transitions are pure and retry-safe.
- Addons do not mutate item state directly.
- Commands never write device truth into `:reported`.
- Runtime effects are explicit data, not hidden callbacks.
- The HTTP/API layer should be a thin edge over the registry, command entrypoint, and event bus.

## Development

Run the test suite:

```powershell
clojure -M:test
```

Run lint:

```powershell
clojure -M:lint
```

Run the in-memory example addon and local HTTP API:

```powershell
clojure -M:run
```

The demo binds to `127.0.0.1:8080` by default. Set `PORT` or JVM property `-Dport` to override the port.

Start the example addon plus local HTTP API from a REPL:

```clojure
(require '[example-addon.system :as system]
         '[openhab.api.server :as server])

(def ctx (system/start!))
(def http-server (server/start! ctx {:port 8080}))

;; Later:
(server/stop! http-server)
(system/stop! ctx)
```

The project currently uses Clojure 1.12.x at runtime through `deps.edn`.

## Documentation

- [Architecture](doc/design.md)
- [HTTP API research](doc/rest-api-findings.md)

## Status

This is a proof-of-concept framework core, not a drop-in OpenHAB replacement. The core is ready for iterative addon and API experiments, but the external API, persistence model, and product shell are still open design areas.
