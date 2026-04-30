# Design Notes — Clojure OpenHAB Binding

## Goal

Idiomatic Clojure — immutable data, pure functions, core.async, spec — removes entire classes of bugs present in the Java/OSGi implementation while producing a design that is simpler to reason about, test, and extend.

The `commandGracePeriod` race is the motivating bug, but the broader claim is architectural: these failures come from the shape of the Java design, not from incidental implementation mistakes. A data-driven, unidirectional-flow model removes them by construction.

---

## Java binding problems (reference)

### commandGracePeriod race

After sending a command the polling loop immediately overwrites the optimistic state with stale API data. The workaround is a grace-period lock that suppresses the poller after a command. Symptom of complecting command handling, state management, and polling in one mutable object.

### Mutable shared state through a callback chain

`BaseThingHandler` routes all state through `this.callback` with `synchronized` blocks. State flows: handler → callback → framework → items. Untestable without mocking the framework. Bridge holds a `ConcurrentHashMap<String, ApplianceDTO>` that child handlers reach into via `getBridge()` and a cast.

### Polling as a per-binding convention

No framework support. Every binding reinvents `ScheduledFuture` + null checks + `cancel(true)` in `dispose()`.

### Complected handler class

Command routing, API calls, state parsing, status management, channel updates, and property refresh — all mixed in one stateful class.

---

## What was borrowed vs redesigned

### Borrowed — good domain abstractions

- Vocabulary: Thing, Channel, Bridge, Item, Link, Status
- Bridge as connection owner with child Things
- Item as the user-facing projection of device state
- Link as the connection between a Channel and an Item
- Status as a lifecycle signal

### Thrown away — Java/OSGi artifacts

| Java/OSGi | Clojure replacement |
| --- | --- |
| Mutable objects + `synchronized` | Immutable maps + single atom |
| `commandGracePeriod` lock | `:desired` overlay + projection from effective state |
| Callback chain (handler → framework → item) | Explicit transition fns + runtime wrapper |
| Item state mutated by addon code | Item state derived by projection only |
| Events as afterthought / observer | Events as first-class output of transitions |
| Opaque `:reported` blob | Normalized `{channel-id → value}` owned by framework contract |
| Ad hoc relationship lookups (scan) | Registry indexes maintained atomically by pure registry helpers used from transitions |
| Bridge children stored on the bridge thing | `:bridge->things` reverse index derived from `:bridge-id` on child things |

---

## Mental models

### Terraform analogy

- Provider ≈ Bridge — holds credentials, manages the connection lifecycle
- Resource type ≈ device type — schema/spec for a class of device
- Resource instance ≈ Thing — one specific device with its own state

### Clojure principles

- **Data all the way down** — state, commands, events, effects are plain maps
- **Don't complect** — retrieval, parsing, validation, projection, I/O are separate
- **Values, not mutable references** — atom holds an immutable value; `swap!` replaces it
- **Unidirectional flow** — device truth flows down into items; commands flow down into things
- **Simple vs easy** — explicit transitions over implicit observer chains

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
- `:desired` — `{channel-id → {:value v :age n :command-id id}}`; one entry per pending channel command. `:value` is the commanded value; `:age` is polls elapsed since the command was written; `:command-id` is an opaque command token used by `command-failed` to distinguish current from stale entries
- **Effective state** = `(merge :reported (map-vals :value :desired))` — what the system believes is true now

There is no `:children` key on a bridge thing. The `:bridge-id` key on each child thing is canonical; `:bridge->things` in the registry is the maintained reverse index derived from it.

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

`:state` holds typed EDN values (numbers, keywords, booleans). String serialization happens at the REST/event wire boundary, not inside the core model. Codecs produce typed values; they do not stringify. `:last-change` is projection-owned and only moves when the projected state or state-type actually changes. The timestamp is supplied by the imperative edge (`commands`, `reporting`, or lifecycle helper code) as `changed-at`, then threaded through pure transitions and projection. This keeps transition/projection functions deterministic under `swap!` retry instead of reading the clock internally. Group items are modeled in `item.clj` but group-level projection (item → group aggregation) is explicitly deferred — the single-layer thing → item projection is complete and correct; group aggregation is a separate concern with its own design requirements.

`set-item-state!` is framework-internal. Addons do not call it.

### Link

Link is the projection boundary between a Channel and an Item.

```clojure
{:item-name  "LivingRoom_FanSpeed"
 :thing-id   "ap-1"
 :channel-id :fan-speed
 :profile    "system:default"}   ; names an entry in the profile registry
```

The profile determines how channel values become item states (inbound) and how item commands become channel commands (outbound). Profile is meaningful runtime data, not dead metadata.

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

`registry.clj` provides pure `state → state` helper fns (e.g., `(put-thing state t)`, `(put-link state lnk)`) that maintain indexes atomically with primary collections. These helpers are building blocks, not the public mutation path. They are used by transitions and by tests that construct plain registry values. `registry.clj` does not own `swap!`, event publication, or projection semantics.

**Runtime preservation invariant:** `put-thing` never replaces `:runtime` when the thing already exists. It stores the incoming thing with the existing `:runtime` merged back in, preserving `:status`, `:reported`, and pending `:desired` across structural updates (re-registration, config changes, REPL reloads). Only runtime-state transitions may write `:runtime`.

**Immutable identity fields:** `:thing-type`, `:bridge?`, and `:channels` are immutable after first registration. `put-thing` silently preserves the existing values for these fields on re-registration — the incoming values are ignored. Mutable structural fields (`:bridge-id`, `:properties`, `:config`) are taken from the incoming thing. Consequence: a bridge can never lose its `:bridge?` status, so bridge referential integrity is maintained across parent re-registrations without a separate check. If a device genuinely changes type or channel set, remove and re-register it.

**Bridge removal cascade policy:** `transition/remove-thing` on a bridge cascade-removes all children first (depth-first). Each child's links are removed and its linked items are cleared before the child is deleted. The bridge is deleted last. Orphaned children are structurally impossible: no child can survive the removal of its parent bridge. Events are ordered child-first, bridge-removed last.

**Item structural immutability:** `:item-type` is immutable after first registration. `transition/add-item` silently preserves the existing `:item-type` on re-registration. This prevents mismatched item semantics (e.g., a "Number" item re-registered as "Switch" while keeping a numeric projected state). Mutable fields (`:label`, `:tags`, `:group-names`, `:metadata`, `:category`) are taken from the incoming item.

**Item projection preservation invariant:** `registry/put-item` is a simple storage primitive — it replaces whatever is passed in. The preservation logic lives in `transition/add-item` (the structural transition): on re-registration it preserves `:state`, `:state-type`, and `:last-change` from the existing entry, so label/tag/metadata changes do not overwrite projection-derived state. Projection code (e.g. `projection/project-items`) calls `registry/put-item` directly with the fully-computed new item — it intentionally overwrites, which is correct.

**Invariant layer boundary:** Pure helpers enforce structural consistency (index coherence, key presence). Business invariants (one link per item, bridge topology, bridge existence) are enforced by `transition.clj`, which has the full registry state in scope. Tests that construct state directly via pure helpers bypass transition-layer invariants and are responsible for honoring them manually.

Write-path boundary: `runtime/apply-transition!` is the single public mutation choke point. Structural transitions (add a thing, add an item, add a link, remove a thing) and runtime-state transitions (`:reported`, `:desired`, `:status`, item projection) both flow through `transition.clj`, which reuses the pure helpers from `registry.clj` underneath.

---

## Namespace structure

```text
src/
  openhab/
    thing.clj        ← specs: Thing, Channel, Bridge, Status; constructors
    item.clj         ← specs: Item, GroupItem; projection cache shape
    link.clj         ← specs: Link; profile reference
    registry.clj     ← state atom; read helpers; pure state→state helpers; raw storage
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

## Architecture

### Transition model

Transitions are **pure functions** that take the current registry state and return:

```clojure
{:state   new-registry-state   ; the new value to swap! in
 :events  [event-map …]        ; domain notifications to publish on the event bus
 :effects [effect-map …]       ; imperative follow-up work (send to device, etc.)
 :result  {:ok true …}}        ; caller-facing transition outcome
```

**Transition naming convention:** Structural transitions use `add-*` / `remove-*` names and are upsert-like on add. `transition/add-thing` and `transition/add-item` both mean "insert if new, otherwise replace the mutable structural fields while preserving immutable/projection/runtime invariants." Pure storage helpers in `registry.clj` keep `put-*` names because they are low-level state operations, not public transitions.

**Bridge discriminator:** A Thing is a bridge when `:bridge?` is `true`. `make-bridge` sets it; `thing/bridge?` tests it. `(s/def ::bridge (s/and ::thing (s/keys :req-un [::bridge?]) #(true? (:bridge? %))))`. `make-thing` never sets `:bridge?`. This gives `transition/add-thing` a reliable, spec-backed predicate to enforce referential integrity.

**Bridge referential integrity:** `transition/add-thing` validates referential integrity whenever a Thing is registered or re-registered with a `:bridge-id` present — including re-parenting to a different bridge. The referenced thing must already exist in the registry, `(thing/bridge? parent)` must be true, and the resulting bridge graph must remain acyclic. Self-parenting and descendant cycles are rejected with `{:ok false :reason :bridge-cycle}`. Missing or non-bridge parents return `{:ok false :reason :bridge-not-found}` or `{:ok false :reason :not-a-bridge}`. When a Thing is added or re-parented under an offline bridge, that Thing is marked offline immediately; if the re-parented Thing is itself a bridge, its descendants are also marked offline. `transition/remove-child` validates that the supplied parent exists, is a bridge, and currently owns the child; parent mismatch returns `{:ok false :reason :not-a-child}` and leaves state unchanged. Registry helpers intentionally do not perform this check: they are responsible for structural storage and index coherence, while transitions own business invariant enforcement.

No I/O inside transitions. No event publication inside `swap!`. No clock reads inside projection-producing transitions: the runtime edge captures `changed-at` once and passes it in as data. A transition fn may run multiple times if `swap!` retries — only the last successful result is acted on.

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

`apply-transition!` receives a plain `(fn [effect])`. It has no knowledge of API clients, handler maps, or any other dependency. Context is wired once at the call site. A missing dispatcher is acceptable only when the transition returns no effects. If `:effects` is non-empty and `effect-dispatcher` is nil, `apply-transition!` throws immediately — this is a startup/programming error, not a recoverable runtime condition.

Key rule: `transition-fn` computes the next state and declares what should happen; `runtime` makes it happen. These concerns never mix.

### Events vs Effects

- **Events** — internal domain notifications with keyword `:event/type` and typed EDN payload. `events.clj` decorates them with external `:event/name` and `:event/topic` metadata before publication.
- **Effects** — imperative follow-up work declared by a transition, executed by the runtime. Core example: `{:kind :openhab/send-command :thing-id … :commands {…}}`. Addons may define additional effect kinds for their own scheduling or integration needs.

#### Effect system (`effects.clj`)

Effect handlers are plain functions registered in a handler map, keyed by effect `:kind`:

```clojure
{:openhab/send-command (fn [effect context] …)
 :addon/custom-effect  (fn [effect context] …)}
```

`context` carries anything a handler needs beyond the effect map itself (e.g. the API client, the registry atom, the bus). Handlers and context are injected at startup — `effects.clj` does not hardcode either.

```clojure
(defn dispatch! [handler-map context effect]
  (if-let [h (get handler-map (:kind effect))]
    (h effect context)
    (throw (ex-info "No handler for effect kind" {:effect effect}))))
```

The call site closes over handler-map and context once at startup, producing the single-arg `effect-dispatcher` that `apply-transition!` receives.

**Startup handler coverage:** System startup must assert that every required core effect `:kind` has a registered handler in the handler-map. This converts missing-handler faults from silent runtime log entries into immediate startup failures. The set of required core effect kinds is finite and known at compile time: `#{:openhab/send-command}`. Addons may define additional effect kinds; each addon is responsible for validating the handlers for the effect kinds it emits before the system begins operation.

Effect dispatch is synchronous: `runtime/apply-transition!` invokes handlers inline on the caller's thread after state commit. Handlers may perform bounded blocking I/O there, but long-lived or delayed work must offload themselves or be modeled as scheduling effects.

Handler failures are isolated from the committed transition. `apply-transition!` wraps each effect dispatch in try/catch: an exception from a handler is logged but does not propagate — state is already committed and events are already published. The `:openhab/send-command` handler logs send failures, then calls `runtime/apply-transition!` with `transition/command-failed`; that corrective transition clears only `:desired` entries whose `:command-id` still matches the failed command, emits `:command/failed` only when at least one current desired entry was cleared, and re-projects affected items. A stale failure callback for a superseded command is a no-op event-wise. Retry policy is the handler's responsibility. **Corrective transitions dispatched by effect handlers must not emit further effects.** `runtime/apply-transition!` enforces this by rejecting nested effectful transitions during effect dispatch before the nested state is committed. If re-entrancy is needed, it must be mediated by a separate scheduling step (for example, an addon-defined scheduling effect that re-enters the command path on a new call frame).

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

**Full-snapshot contract:** `channels-reported` has snapshot semantics, not incremental semantics. The addon must pass the complete current channel state from the device on every call. If a device API returns only changed channels, the addon is responsible for merging them with the last known full state before calling this transition. Absent channels are treated as absent — they are not "unchanged." Every reported key must also be declared in the Thing's `:channels`; an undeclared key returns `{:ok false :reason :unknown-reported-channel :channel-id ...}` and leaves state unchanged. This is a hard invariant on the addon, not an implementation detail. The framework provides `thing/merge-reported` as the sanctioned helper for overlaying a partial device delta onto the current Thing's `:reported` map before calling `transition/channels-reported`.

**Convergence policy:**

A `:desired` entry is dropped as soon as `:reported` contains any value for that channel — regardless of whether the values match. A returned poll value means the device has processed (or ignored/clamped) the command; the command cycle for that channel is closed. If the device returned a different value than commanded, `:reported` captures the truth and projection corrects item state automatically.

**Intentional tradeoff:** this is "device answered, truth wins" semantics. On eventually consistent APIs where a poll may briefly return the old value before the command propagates, the UI will snap back to the old state for one poll cycle before settling at the commanded value. This is a deliberate choice — correctness over optimism. If a specific addon requires sticky optimistic state across multiple polls, that belongs in a custom profile, not in the framework convergence policy.

**Safety net — per-channel expiry after N missed polls:**

`:age` inside each `:desired` entry tracks polls elapsed since that channel's command was written:

```clojure
:desired {:fan-speed  {:value 5    :age 2 :command-id "cmd-42"}
          :work-mode  {:value :auto :age 0 :command-id "cmd-43"}}
```

On each successful poll: every `:age` is incremented; entries for channels present in the new `:reported` are dropped; entries whose `:age` reaches the configured threshold (default 3) are forcibly expired — cleared from `:desired` and included in `desired-expired-channel-ids` for re-projection.

Per-channel tracking means a freshly-commanded channel does not reset the age of a stale one. A single thing-level counter cannot model multiple independently-pending channels correctly.

**Status rule:**

`transition/channels-reported` sets thing status to `{:value :online}` if its current status value is not `:online`. A successful report is the evidence of liveness. No separate transition is needed. If status was already online, no status change and no `ThingStatusInfoChangedEvent` is emitted. `transition/set-bridge-status` rejects non-bridge targets with `{:ok false :reason :not-a-bridge}` and marks all descendants offline when a bridge goes offline, not only immediate children.

No scan. No watch-driven diffing. Projection runs immediately at the write site.

### Outbound pipeline (item command → device)

```text
dispatch! called with {:item-name "X" :value 5}
  → commands.clj generates a UUID command-id and captures changed-at
  → commands require a real `:effect-dispatcher` in context; missing dispatcher is a startup/programming error
  → runtime/apply-transition! called with transition/item-command-accepted
      transition:
        resolves item → links via :item->links index inside the same registry snapshot that will be written
        rejects missing/unlinked/ambiguous items before any state is changed
        rejects command if thing status value is not :online → returns {:ok false :reason :thing-offline}
        profile/encode-command → channel-value (typed scalar; profile knows channel-id from context)
        delegates to the channel-command path
        writes {channel-id {:value v :age 0 :command-id id}} into :runtime :desired on thing
        computes effective state = (merge :reported (map-vals :value :desired))
        re-projects affected items from effective state (optimistic)
        emits ItemStateChangedEvent
        emits effect: {:kind :openhab/send-command :thing-id … :commands {channel-id → value} :command-id id}
      returns {:state … :events […] :effects […] :result …}
```

`commandGracePeriod` is eliminated by construction: `:reported` is never written by commands, `:desired` provides the optimistic overlay, and the next poll's convergence step either prunes `:desired` (device responded) or leaves it (still pending). Projection always reads effective state.

### Profile system

A profile is a named map of two pure functions registered in `profile.clj`:

```clojure
{:project-state  (fn [{:keys [channel-id channel-value thing link profile-registry]}]
                    {:state 5 :state-type :number})   ; returns typed EDN, not a string
 :encode-command (fn [{:keys [channel-id item-value thing link profile-registry]}]
                    channel-value)}   ; returns typed scalar channel value
```

Profile functions receive one context map. The framework adds `:profile-registry` to that context so advanced profiles can inspect registered codecs without introducing a global atom. `project-state` returns typed EDN; if it returns a map with `:state` but no `:state-type`, the framework infers `:state-type` from `:state`. `encode-command` returns a typed scalar. Neither serializes to string — that is the wire boundary's responsibility.

`encode-command` returns a channel value (scalar). It does not redirect or fan out to other channels — that is a commands-layer concern. The channel-id is known from the link context.

`"system:default"` ships with the framework. It delegates to a per-channel **codec** registered by the addon:

```clojure
;; Addon registers codecs at startup — keyed by [thing-type channel-id]
(profile/register-codec [:air-purifier :fan-speed]
  {:to-state   (fn [v] {:state v :state-type :number})
   :from-state (fn [s] s)})
```

The profile registry is passed explicitly as context in system wiring — it is not a namespace-global atom. This keeps tests and REPL reloads clean and makes dependencies visible. `register-profile` and `register-codec` validate the shape of registrations up front so addon mistakes fail at startup/configuration time instead of surfacing later as projection or command-path exceptions.

This layers cleanly:

- Public abstraction: link names a profile (`"system:default"`, `"system:on-off"`, custom)
- Internal default: `system:default` delegates to the addon's channel codec
- Custom profiles override the whole transform for specific use cases

### Multi-link projection policy

Current policy (explicit, not implicit):

- The framework currently allows at most one link per item.
- `transition/add-link` rejects a second distinct link to the same item with `{:ok false :reason :ambiguous-item-links}`.
- Projection therefore stays state-derived and order-independent: each item is re-projected from its single allowed link's effective channel value.
- `projection.clj` throws if it encounters a multi-link item at projection time — fail loudly rather than silently picking a winner. This is the detection-site guard for the invariant enforced at the transition layer.
- Reducer-based many-to-one item projection is a future extension, not an implicit default.

### Polling and reporting

`polling.clj` is a generic scheduling primitive — it knows nothing about the registry, Things, statuses, or projection. It answers only one question: "call this function repeatedly, contain callback failures, and stop promptly." Blocking fetches run on `core.async/io-thread`; on modern `core.async` + JDK this maps to the library's I/O-friendly execution path rather than occupying a go-block worker. This keeps scheduling reusable for addons that need polling but do not report channel state.

`reporting.clj` is the small lifecycle adapter between polling and the transition system. It owns the common channel-reporting workflow: fetch a full normalized channel snapshot, call `transition/channels-reported` through `runtime/apply-transition!`, mark the Thing offline when fetch fails, and optionally perform one synchronous initial fetch before returning from startup.

**Design reason:** bridge topology, polling mechanics, and channel-state reporting are different concerns. A Bridge is a parent/connection owner, not necessarily the Thing whose channels are being reported. Many real bindings have a bridge poll child device state; forcing that through bridge-owned polling makes the bridge abstraction lie. Reporting therefore takes an explicit `thing-id` and works for bridge Things, child Things, or standalone Things equally.

**Startup readiness:** commands are rejected while a Thing is not online. With `:initial-fetch? true`, `reporting/start-channel-polling!` performs one bounded synchronous fetch before returning, so `start!` can return a context whose item projections and Thing status reflect the current device state. This avoids hidden command queues and keeps readiness visible in the registry. If a caller wants fully asynchronous startup, it can pass `:initial-fetch? false` and accept that commands may return `{:ok false :reason :thing-offline}` until the first successful report.

**Contract:** `fetch-fn` must enforce its own read timeout. `stop!` requests shutdown promptly, prevents delivery of late `on-success` / `on-error` callbacks from any in-flight fetch that finishes after stop, and waits only for the polling loop itself to exit. It does not wait for a hung worker thread to finish. If `fetch-fn` can block forever, the polling loop still stops cleanly, but the worker thread may linger until the underlying call times out or otherwise returns. Callback exceptions from `on-success` or `on-error` are logged and contained so the next polling cycle can continue. The addon is still responsible for providing bounded-latency I/O and callback functions that do not rely on exceptions for normal control flow.

```clojure
(reporting/start-channel-polling!
  ctx
  {:thing-id thing-id
   :fetch-fn #(device/parse-channels (api/get-state client appliance-id))
   :interval-ms 60000
   :initial-fetch? true})
```

`bridge/start-own-channel-polling!` is a narrow convenience wrapper for the less common case where the bridge Thing itself has channels. Child device polling should use `reporting/start-channel-polling!` with the child `thing-id`.

---

## I/O boundary

```text
Pure                          │ Imperative / I/O
──────────────────────────────┼──────────────────────────────────────
openhab.thing                 │
openhab.item                  │
openhab.link                  │
openhab.registry (reads)      │ openhab.runtime   (apply-transition!)
openhab.projection            │ openhab.events    (publish!)
openhab.transition            │ openhab.effects   (dispatch!, handlers)
openhab.profile               │ openhab.reporting (channel reporting lifecycle)
                              │ openhab.bridge    (bridge topology/status helpers)
                              │ openhab.commands  (command entrypoint)
                              │ openhab.polling   (go-loop, io-thread)
<addon>.device                │ <addon>.api       (HTTP)
                              │ <addon>.system    (start!/stop!)
```

Everything to the left is pure functions on plain maps — fully testable without mocks or async machinery. Everything to the right is isolated at the edge and injected as functions.

---

## Implementation status

### Core framework — foundation implemented

1. `openhab.thing`       — runtime sub-map, status map, bridge discriminator
2. `openhab.item`        — typed EDN state, internal projection updates
3. `openhab.link`        — profile reference and runtime projection boundary
4. `openhab.registry`    — indexes plus pure `state → state` helpers
5. `openhab.profile`     — profile registry, codec registration, `system:default`
6. `openhab.projection`  — effective-state helpers and single-link item projection
7. `openhab.transition`  — pure structural and runtime-state transitions
8. `openhab.effects`     — handler registry, dispatch, failure routing
9. `openhab.runtime`     — `apply-transition!`, event publication, effect dispatch, re-entrancy guard
10. `openhab.commands`   — command entrypoint routed through transitions
11. `openhab.reporting`  — channel reporting lifecycle adapter and startup readiness
12. `openhab.bridge`     — bridge topology/status helpers routed through transitions
13. `openhab.events`     — publication layer over transition outputs
14. `openhab.polling`    — generic scheduler

### Next layers

1. `<addon>.config`
2. `<addon>.device` + `<addon>.device.<type>`
3. `<addon>.api`
4. `<addon>.system`







