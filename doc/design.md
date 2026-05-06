# Architecture

## Goal

Idiomatic Clojure — immutable data, pure functions, core.async, spec — avoids classes of bugs common in mutable handler-based designs while producing a model that is simpler to reason about, test, and extend.

The motivating bug is the `commandGracePeriod` race: after sending a command, the polling loop immediately overwrites the optimistic state with stale API data, requiring a grace-period lock to suppress the poller. This failure follows from combining command handling, state management, and polling in one mutable object; it is not just an incidental implementation mistake. A data-driven, unidirectional-flow model removes it by construction.

---

## Motivation

### commandGracePeriod race

After sending a command the polling loop immediately overwrites the optimistic state with stale API data. The workaround is a grace-period lock that suppresses the poller after a command — a symptom of mixing command handling, state management, and polling in one mutable object.

### Mutable shared state through a callback chain

`BaseThingHandler` routes all state through `this.callback` with `synchronized` blocks. State flows: handler → callback → framework → items. Untestable without mocking the framework. Bridge holds a `ConcurrentHashMap<String, ApplianceDTO>` that child handlers reach into via `getBridge()` and a cast.

### Polling as a per-binding convention

No framework support. Every binding reinvents `ScheduledFuture` + null checks + `cancel(true)` in `dispose()`.

### Overloaded handler class

Command routing, API calls, state parsing, status management, channel updates, and property refresh — all mixed in one stateful class.

---

## Design decisions

### Retained domain abstractions

- Vocabulary: Thing, Channel, Bridge, Item, Link, Status
- Bridge as connection owner with child Things
- Item as the user-facing projection of device state
- Link as the connection between a Channel and an Item
- Status as a lifecycle signal

### Replaced Java/OSGi artifacts

| Java/OSGi | Clojure replacement |
| --- | --- |
| Mutable objects + `synchronized` | Immutable maps + single atom |
| `commandGracePeriod` lock | `:desired` overlay + projection from effective state |
| Callback chain (handler → framework → item) | Explicit transition fns + runtime wrapper |
| Item state mutated by addon code | Item state derived by projection only |
| Events as afterthought / observer | Events as first-class output of transitions |
| Opaque `:reported` blob | Normalized `{channel-id → value}` owned by framework contract |
| Ad hoc relationship lookups (scan) | Registry indexes maintained atomically by pure helpers |
| Bridge children stored on the bridge thing | `:bridge->things` reverse index derived from `:bridge-id` on child things |

---

## Design principles

- **Data all the way down** — state, commands, events, and effects are plain maps
- **Separation of concerns** — retrieval, parsing, validation, projection, and I/O are separate namespaces with no cross-contamination
- **Values, not mutable references** — the atom holds an immutable value; `swap!` replaces it atomically
- **Unidirectional flow** — device truth flows into items; commands flow into things; the two directions never mix
- **Explicit over implicit** — transitions declare what should happen; the runtime makes it happen; nothing is hidden in observer chains

---

## Core data shapes

### Thing

Thing separates static description from runtime state.

```clojure
{:thing-id   "ap-1"
 :thing-type :air-purifier
 :bridge-id  "bridge-1"
 :channels   {:fan-speed {:access :rw :channel-type :number}
              :temp      {:access :ro :channel-type :number}}
 :properties {:vendor "Acme" :model "X100" :serial "SN123"}
 :runtime    {:status   {:value :online}
              :reported {:fan-speed 3 :temp 21}
              :desired  {:fan-speed {:value 5 :age 2 :command-id "550e8400-e29b-41d4-a716-446655440000"}}}}
```

- `:channels` — static, declared by the addon at registration, never changes at runtime
- `:runtime` — owned by the framework; the addon never writes it directly
- `:status` — status map with `:value` and optional `:detail` / `:description`
- `:reported` — normalized `{channel-id → value}`; source of truth from the device; written by polls only
- `:desired` — `{channel-id → {:value v :age n :command-id id}}`; one entry per pending channel command. `:value` is the commanded value; `:age` is polls elapsed since the command was written; `:command-id` is an opaque token used by `command-failed` to distinguish current from stale entries
- **Effective state** = `(merge :reported (map-vals :value :desired))` — what the system believes is true now

There is no `:children` key on a bridge thing. The `:bridge-id` key on each child thing is canonical; `:bridge->things` in the registry is the maintained reverse index.

### Item

Item is a materialized view for UI and rules. It is never the origin of device truth.

```clojure
{:item-name   "LivingRoom_FanSpeed"
 :item-type   "Number"
 :label       "Fan Speed"
 :tags        #{"Point" "Setpoint"}
 :group-names #{"LivingRoom"}
 :state       5           ; typed EDN — rendered to string only at REST/event boundary
 :state-type  :number
 :last-change (java.time.Instant/parse "2026-03-27T12:00:00Z")}
```

`:state` holds typed EDN values (numbers, keywords, booleans). String serialization happens at the wire boundary, not inside the core model. `:last-change` moves only when the projected state or state-type actually changes. The timestamp is supplied by the imperative edge (`commands`, `reporting`, or lifecycle code) as `changed-at` and threaded through pure transitions and projection — this keeps transition functions deterministic under `swap!` retry. Group items are modeled in `item.clj`; group-level aggregation (item → group) is outside the current core scope.

`set-item-state!` is framework-internal. Addons do not call it.

### Link

Link is the projection boundary between a Channel and an Item.

```clojure
{:item-name  "LivingRoom_FanSpeed"
 :thing-id   "ap-1"
 :channel-id :fan-speed
 :profile    "system:default"}   ; names an entry in the profile registry
```

The profile determines how channel values become item states (inbound) and how item commands become channel commands (outbound).

### Registry

```clojure
{;; Primary collections
 :things {"ap-1" {…thing map…}}
 :items  {"LivingRoom_FanSpeed" {…item map…}}
 :links  {["LivingRoom_FanSpeed" "ap-1" :fan-speed] {…link map…}}

 ;; Indexes — maintained atomically with primary collections; never stale
 :item->links     {"LivingRoom_FanSpeed" #{["LivingRoom_FanSpeed" "ap-1" :fan-speed]}}
 :channel->links  {["ap-1" :fan-speed]   #{["LivingRoom_FanSpeed" "ap-1" :fan-speed]}}
 :bridge->things  {"bridge-1" #{"ap-1"}}}
```

`registry.clj` provides pure `state → state` helpers (e.g., `(put-thing state t)`, `(put-link state lnk)`) that maintain indexes atomically with primary collections. They are building blocks used by transitions and tests, not the public mutation path. `registry.clj` does not own `swap!`, event publication, or projection semantics.

**Runtime preservation invariant:** `put-thing` never replaces `:runtime` when the thing already exists. It stores the incoming thing with the existing `:runtime` merged back in, preserving `:status`, `:reported`, and pending `:desired` across structural updates (re-registration, config changes). Only runtime-state transitions may write `:runtime`.

**Immutable identity fields:** `:thing-type`, `:bridge?`, and `:channels` are immutable after first registration. `put-thing` silently preserves the existing values for these fields on re-registration. Mutable structural fields (`:bridge-id`, `:properties`, `:config`) are taken from the incoming thing. To change type or channel set, remove and re-register the Thing.

**Bridge removal cascade:** `transition/remove-thing` on a bridge cascade-removes all children first (depth-first). Each child's links are removed and its linked items are cleared before the child is deleted. The bridge is deleted last. Events are ordered child-first, bridge-removed last.

**Item structural immutability:** `:item-type` is immutable after first registration. `transition/add-item` preserves the existing `:item-type` on re-registration to prevent type mismatches (e.g., a "Number" item re-registered as "Switch" while keeping a numeric projected state). Mutable fields (`:label`, `:tags`, `:group-names`, `:metadata`, `:category`) are taken from the incoming item.

**Item projection preservation:** `registry/put-item` is a simple storage primitive — it replaces whatever is passed in. The preservation logic lives in `transition/add-item`: on re-registration it preserves `:state`, `:state-type`, and `:last-change` from the existing entry, so label/tag/metadata changes do not overwrite projected state. Projection code calls `registry/put-item` directly with the fully-computed new item — the intentional overwrite is correct.

**Invariant layer boundary:** Pure helpers enforce structural consistency (index coherence, key presence). Business invariants (one link per item, bridge topology, bridge existence) are enforced by `transition.clj`, which has the full registry state in scope.

`runtime/apply-transition!` is the single public mutation entry point. All transitions — structural (add/remove thing, item, link) and runtime-state (reported, desired, status, projection) — flow through `transition.clj`, which reuses the pure helpers from `registry.clj`.

### Query read model

`query.clj` is the stable read boundary over the registry. It returns API-facing snapshots for Things, Items, Links, and the whole system without exposing raw index maps or private runtime details such as desired ages and command ids. The query layer keeps typed EDN values because it is still inside the Clojure core; JSON strings, camelCase keys, pagination, filtering syntax, and HTTP status codes belong to the later wire/API layer.

This boundary is intentionally pure and small. It accepts either the registry atom or a plain registry state value, derives channel effective values from `:reported` plus `:desired`, includes bridge children from the maintained reverse index, and returns sorted vectors for collection fields so callers get stable shapes. Keeping this layer separate lets REST, SSE, GraphQL, or REPL tooling share the same public read model without coupling the core registry to any one transport.

### API view boundary

`openhab.api.view` is the pure serialization boundary over `query.clj`. It converts query read models into public API maps whose values are safe for JSON encoders: keywords become strings, `java.time.Instant` values become ISO-8601 strings, UUIDs become strings, sets become sorted vectors, and nested map keys become strings. Ratios, NaN, Infinity, and unsupported object values fail fast with `ex-info` rather than leaking non-portable JVM values to the API edge.

This layer intentionally keeps Clojure keyword keys in the returned maps. That is still Clojure data, not an HTTP response body. The later transport layer decides whether external field names are kebab-case JSON strings, camelCase JSON strings, GraphQL field names, or something else. Keeping key naming out of the core API view prevents premature coupling to REST or GraphQL while still making value serialization explicit and testable.

### HTTP API boundary

`openhab.api.http` is the thin Ring/Reitit edge over `query.clj` and `openhab.api.view`. Read handlers close over the system context but read `(:registry ctx)` on every request, so a live registry atom exposes fresh state without route handlers owning synchronization or domain logic. The handler currently exposes read-only endpoints: `GET /api/system`, `GET /api/things`, `GET /api/things/:thing-id`, `GET /api/items`, and `GET /api/items/:item-name`.

HTTP handlers must not own framework behavior. They translate request parameters, call the read model, call the API view layer, and return JSON Ring response maps. Unknown Things, Items, and routes return JSON `404` responses; unsupported methods return JSON `405` responses. Command dispatch (`POST /api/items/:item-name/command`) and event streaming (`GET /api/events`) are separate follow-up slices because they cross into mutation/effects and core.async event subscriptions.

The selected stack is Ring + Reitit + http-kit + Cheshire. Ring provides the standard request/response abstraction, Reitit provides data-driven routing, Cheshire encodes JSON, and http-kit is the adapter selected for later SSE/WebSocket support. The current commit tests handlers as plain Ring function calls without starting a server.

---

## Namespace structure

```text
src/
  openhab/
    thing.clj        ← specs: Thing, Channel, Bridge, Status; constructors
    item.clj         ← specs: Item, GroupItem; projection cache shape
    link.clj         ← specs: Link; profile reference
    api/http.clj     ← Ring/Reitit HTTP read edge over query + api.view
    api/view.clj     ← pure API view serialization over query read models
    registry.clj     ← state atom; read helpers; pure state→state helpers; raw storage
    query.clj        ← pure read model for API/UI edges; stable snapshots without raw indexes
    profile.clj      ← named profile registry; validated profile/codec contract; system:default; codec API
    projection.clj   ← pure: affected items, effective-state lookup, single-link item projection
    transition.clj   ← pure: state transitions returning {:state … :events […] :effects […] :result …}
    runtime.clj      ← imperative: apply-transition!, swap! + event/effect dispatch
    effects.clj      ← effect handler registry; dispatch; failure → corrective transition
    commands.clj     ← command entrypoint; generates command ids; delegates resolution to transitions
    reporting.clj    ← channel reporting lifecycle adapter; optional startup readiness
    bridge.clj       ← bridge topology/status helpers routed through transitions
    events.clj       ← publication layer over transition outputs
    polling.clj      ← generic scheduling primitive

  <addon>/
    config.clj        ← credentials + settings spec
    api.clj           ← HTTP client, auth, token refresh
    device.clj        ← device-type dispatch; channel codec registration
    device/<type>.clj ← type-specific channel codecs + constructors
    system.clj        ← wires framework + addon; start!/stop!
```

---

## Runtime behavior

### Transition model

Transitions are **pure functions** that take the current registry state and return:

```clojure
{:state   new-registry-state   ; the new value to swap! in
 :events  [event-map …]        ; domain notifications to publish on the event bus
 :effects [effect-map …]       ; imperative follow-up work (send to device, etc.)
 :result  {:ok true …}}        ; caller-facing outcome
```

**Naming convention:** Structural transitions use `add-*` / `remove-*` names and are upsert-like on add. `transition/add-thing` and `transition/add-item` mean "insert if new, otherwise replace mutable structural fields while preserving immutable/projection/runtime invariants." Pure storage helpers in `registry.clj` use `put-*` names because they are low-level state operations, not public transitions.

**Bridge discriminator:** A Thing is a bridge when `:bridge?` is `true`. `make-bridge` sets it; `thing/bridge?` tests it. `make-thing` never sets `:bridge?`.

**Bridge referential integrity:** `transition/add-thing` validates referential integrity on every registration or re-registration that includes a `:bridge-id`. The referenced thing must exist, `(thing/bridge? parent)` must be true, and the resulting graph must be acyclic. Self-parenting and cycles are rejected with `{:ok false :reason :bridge-cycle}`. Missing or non-bridge parents return `:bridge-not-found` or `:not-a-bridge`. When a Thing is added or re-parented under an offline bridge, it and its descendants are marked offline immediately. `transition/remove-child` validates that the parent currently owns the child; a mismatch returns `{:ok false :reason :not-a-child}`.

**Constraints on transitions:** No I/O. No event publication inside `swap!`. No clock reads — the runtime edge captures `changed-at` once and passes it as data. A transition may run multiple times under `swap!` retry; only the last successful result is acted on.

### Runtime wrapper

`runtime.clj` is the **only** place that runs `swap!`, publishes events, and dispatches effects.

```clojure
(defn apply-transition!
  "Applies a pure transition fn to the registry atom.
   Publishes events and dispatches effects after the swap! completes.

   effect-dispatcher — single-arg (fn [effect]); closes over context at the call site.
                       apply-transition! never sees API clients or other dependencies.
                       May be nil only when the transition emits no effects."
  [registry bus effect-dispatcher transition-fn & args]
  (let [result* (volatile! nil)]  ; volatile! captures last swap! result outside retry loop
    (swap! registry
           (fn [state]
             (let [result (apply transition-fn state args)]
               (vreset! result* result)
               (:state result))))
    (doseq [event (:events @result*)]
      (when bus
        (events/publish! bus event)))
    (let [effects (:effects @result*)]
      (when (seq effects)
        (when-not effect-dispatcher
          (throw (ex-info "Missing :effect-dispatcher for effectful transition"
                          {:effect-count (count effects)})))
        (doseq [effect effects]
          (try
            (effect-dispatcher effect)
            (catch Exception ex
              (log/error ex "Effect dispatch failed" {:effect effect}))))))
    @result*))
```

The effect dispatcher is constructed at startup and closed over its context:

```clojure
;; at system startup
(def dispatch-effect!
  (partial effects/dispatch! handler-map {:api-client client …}))

;; passed to apply-transition! at every call site
(runtime/apply-transition! registry bus dispatch-effect! transition/channels-reported …)
```

`apply-transition!` receives a plain `(fn [effect])` with no knowledge of API clients or handler maps. A missing dispatcher is acceptable only when the transition emits no effects. If `:effects` is non-empty and `effect-dispatcher` is nil, `apply-transition!` throws — this is a programming error, not a recoverable runtime condition.

`transition-fn` declares what should happen; `runtime` makes it happen. These concerns never mix.

### Events vs Effects

- **Events** — internal domain notifications with keyword `:event/type` and typed EDN payload. `events.clj` decorates them with external `:event/name` and `:event/topic` metadata before publication.
- **Effects** — imperative follow-up work declared by a transition, executed by the runtime. Core example: `{:kind :openhab/send-command :thing-id … :commands {…}}`. Addons may define additional effect kinds.

#### Effect system (`effects.clj`)

Effect handlers are plain functions registered in a handler map, keyed by effect `:kind`:

```clojure
{:openhab/send-command (fn [effect context] …)
 :addon/custom-effect  (fn [effect context] …)}
```

`context` carries anything a handler needs beyond the effect map (e.g. the API client, the registry atom, the bus). Handlers and context are injected at startup.

```clojure
(defn dispatch! [handler-map context effect]
  (if-let [h (get handler-map (:kind effect))]
    (h effect context)
    (throw (ex-info "No handler for effect kind" {:effect effect}))))
```

**Startup handler coverage:** System startup must assert that every required core effect kind has a registered handler. The required core set is `#{:openhab/send-command}`. Missing handlers become immediate startup failures rather than silent runtime faults.

Effect dispatch is synchronous: handlers are invoked inline on the caller's thread after state commit. Handlers may perform bounded blocking I/O; long-lived work must offload itself or be modeled as a scheduling effect.

Handler failures are isolated from the committed transition. `apply-transition!` wraps each dispatch in try/catch: a handler exception is logged but does not propagate — state is already committed and events are already published. The `:openhab/send-command` handler logs send failures, then applies `transition/command-failed`, which clears only the `:desired` entries whose `:command-id` still matches the failed command, emits `:command/failed` only when at least one current desired entry was cleared, and re-projects affected items. A stale failure callback for a superseded command is a no-op event-wise.

**Corrective transitions dispatched by effect handlers must not emit further effects.** `runtime/apply-transition!` enforces this. Re-entrant effects must be mediated by a separate scheduling step.

### Inbound pipeline (device → item)

```text
reporting poll fires
  → addon fetch-fn returns raw data
  → addon parses into normalized {channel-id → value} map
  → runtime/apply-transition! called with transition/channels-reported
      transition computes:
        if thing status value is not :online → set status to {:value :online}
        diff old :reported vs new :reported → reported-changed-channel-ids
        for each channel-id present in new :reported:
          if that channel-id exists in :desired → drop that :desired entry
          (device responded; command cycle for this channel is closed)
        desired-pruned-channel-ids = channels dropped from :desired in this step
        for each remaining :desired entry: increment :age
        expire entries whose :age reached threshold → desired-expired-channel-ids
        affected-channel-ids = union(reported-changed, desired-pruned, desired-expired)
        compute effective state = (merge :reported (map-vals :value :desired))
        for each channel-id in affected-channel-ids:
          look up :channel->links index → affected items
          re-project each affected item from its single allowed link's effective channel value
          write projected item state into :items
        emit ItemStateChangedEvent per changed item
        emit ThingStatusInfoChangedEvent if status changed
      returns {:state … :events […] :result …}
  → runtime publishes events
```

**Full-snapshot contract:** `channels-reported` has snapshot semantics, not incremental semantics. The addon must pass the complete current channel state on every call. If a device API returns only deltas, the addon is responsible for merging them with the last known full state before calling this transition. Every reported key must be declared in the Thing's `:channels`; an undeclared key returns `{:ok false :reason :unknown-reported-channel :channel-id ...}` and leaves state unchanged. The framework provides `thing/merge-reported` as the helper for overlaying a partial delta onto the current `:reported` map.

**Convergence policy:** A `:desired` entry is dropped as soon as `:reported` contains any value for that channel — regardless of whether the values match. A returned poll value means the device has processed (or ignored/clamped) the command; the command cycle for that channel is closed. If the device returned a different value than commanded, `:reported` captures the truth and projection corrects item state automatically.

This is "device answered, truth wins" semantics. On eventually-consistent APIs where a poll may briefly return the old value before the command propagates, the UI will snap back for one poll cycle before settling. If a specific device requires sticky optimistic state across multiple polls, that belongs in a custom profile.

**Per-channel expiry after N missed polls:** `:age` inside each `:desired` entry tracks polls elapsed since the command was written:

```clojure
:desired {:fan-speed  {:value 5    :age 2 :command-id "cmd-42"}
          :work-mode  {:value :auto :age 0 :command-id "cmd-43"}}
```

On each successful poll: every `:age` is incremented; entries for channels present in the new `:reported` are dropped; entries whose `:age` reaches the configured threshold (default 3) are expired — cleared from `:desired` and included in `desired-expired-channel-ids` for re-projection. Per-channel tracking ensures a freshly-commanded channel does not reset the age counter of a stale one.

**Status rule:** `transition/channels-reported` sets thing status to `{:value :online}` if the current status is not `:online`. A successful report is evidence of liveness. `transition/set-bridge-status` marks all descendants offline when a bridge goes offline, not only immediate children.

### Outbound pipeline (item command → device)

```text
dispatch! called with {:item-name "X" :value 5}
  → commands.clj generates a UUID command-id and captures changed-at
  → runtime/apply-transition! called with transition/item-command-accepted
      transition (all within one registry snapshot):
        resolves item → links via :item->links index
        rejects missing/unlinked/offline/ambiguous/read-only-channel conditions before any state is changed
        profile/encode-command → channel-value (typed scalar)
        writes {channel-id {:value v :age 0 :command-id id}} into :runtime :desired on thing
        computes effective state = (merge :reported (map-vals :value :desired))
        re-projects affected items from effective state (optimistic)
        emits ItemStateChangedEvent
        emits effect: {:kind :openhab/send-command :thing-id … :commands {channel-id → value} :command-id id}
      returns {:state … :events […] :effects […] :result …}
```

`commandGracePeriod` is eliminated by construction: `:reported` is never written by commands; `:desired` provides the optimistic overlay; the next poll's convergence step either prunes `:desired` (device responded) or leaves it (still pending). Projection always reads effective state.

### Profile system

A profile is a named map of two pure functions registered in `profile.clj`:

```clojure
{:project-state  (fn [{:keys [channel-id channel-value thing link profile-registry]}]
                    {:state 5 :state-type :number})   ; returns typed EDN, not a string
 :encode-command (fn [{:keys [channel-id item-value thing link profile-registry]}]
                    channel-value)}   ; returns typed scalar channel value
```

Profile functions receive one context map. The framework adds `:profile-registry` to that context so advanced profiles can inspect registered codecs. `project-state` returns typed EDN; if it returns a map with `:state` but no `:state-type`, the framework infers `:state-type` from the value. `encode-command` returns a typed scalar. Neither function serializes to string — that is the wire boundary's responsibility.

`"system:default"` ships with the framework and delegates to a per-channel codec registered by the addon:

```clojure
;; Addon registers codecs at startup — keyed by [thing-type channel-id]
(profile/register-codec [:air-purifier :fan-speed]
  {:to-state   (fn [v] {:state v :state-type :number})
   :from-state (fn [s] s)})
```

The profile registry is passed as explicit context in system wiring — it is not a global atom. `register-profile` and `register-codec` validate the shape of registrations at registration time so addon mistakes fail at startup rather than surfacing as runtime exceptions.

Profile layering:

- Link names a profile (`"system:default"`, `"system:on-off"`, or a custom name)
- `system:default` delegates to the addon's channel codec
- Custom profiles override the entire transform for specific use cases

### Multi-link projection policy

At most one link per item is allowed. `transition/add-link` rejects a second distinct link with `{:ok false :reason :ambiguous-item-links}`. Projection is therefore state-derived and order-independent: each item is re-projected from its single link's effective channel value. `projection.clj` throws if it encounters a multi-link item — fail loudly rather than silently picking a winner. Reducer-based many-to-one projection belongs in a later group aggregation design.

### Polling and reporting

`polling.clj` is a generic scheduling primitive — it knows nothing about the registry, Things, statuses, or projection. It answers one question: call this function repeatedly, contain callback failures, and stop promptly. Blocking fetches run on `core.async/io-thread`.

`reporting.clj` is the lifecycle adapter between polling and the transition system. It owns the channel-reporting workflow: fetch a full normalized channel snapshot, call `transition/channels-reported` through `runtime/apply-transition!`, mark the Thing offline when fetch fails, and optionally perform one synchronous initial fetch before returning from startup.

Bridge topology, polling mechanics, and channel-state reporting are separate concerns. A Bridge is a connection owner, not necessarily the Thing whose channels are reported — many bindings have a bridge poll child device state. Reporting therefore takes an explicit `thing-id` and works for bridge Things, child Things, and standalone Things equally.

**Startup readiness:** commands are rejected while a Thing is not online. With `:initial-fetch? true`, `reporting/start-channel-polling!` performs one bounded synchronous fetch before returning, so `start!` returns a context whose projections and Thing status reflect current device state.

**Contract:** `fetch-fn` must enforce its own read timeout. `stop!` prevents late callbacks from in-flight fetches that complete after shutdown and waits only for the polling loop to exit. Callback exceptions are logged and contained so the next poll can proceed.

```clojure
(reporting/start-channel-polling!
  ctx
  {:thing-id thing-id
   :fetch-fn #(device/parse-channels (api/get-state client appliance-id))
   :interval-ms 60000
   :initial-fetch? true})
```

`bridge/start-own-channel-polling!` is a convenience wrapper for the case where the bridge Thing itself has channels. Child device polling uses `reporting/start-channel-polling!` with the child `thing-id`.

---

## I/O boundary

```text
Pure                          │ Imperative / I/O
──────────────────────────────┼──────────────────────────────────────
openhab.thing                 │
openhab.item                  │
openhab.link                  │
openhab.registry (reads)      │ openhab.runtime   (apply-transition!)
openhab.query                 │
openhab.api.view              │
openhab.projection            │ openhab.api.http  (Ring handler)
openhab.transition            │ openhab.events    (publish!)
                              │ openhab.effects   (dispatch!, handlers)
openhab.profile               │ openhab.reporting (channel reporting lifecycle)
                              │ openhab.bridge    (bridge topology/status helpers)
                              │ openhab.commands  (command entrypoint)
                              │ openhab.polling   (go-loop, io-thread)
<addon>.device                │ <addon>.api       (HTTP)
                              │ <addon>.system    (start!/stop!)
```

Everything to the left is pure functions on plain maps — fully testable without mocks or async machinery. Everything to the right is isolated at the edge and injected as functions.
