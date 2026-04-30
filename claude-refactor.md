# Refactor Log

Session: 2026-04-28

---

## 2026-04-28T10:01 — Fix 1: `send-command-handler` truthy-return contract removed

**File:** `src/openhab/effects.clj`

**Problem:** `send-command-handler` used `(when-not (send-fn thing effect) ...)` to detect
failure. Any `send-fn` returning a falsy value — including `nil`, the default return of a
function that forgets to return `true` — would trigger a spurious `command-failed` transition.
The `(catch Exception _)` branch already handled the correct failure case.

**Fix:** Removed the `when-not` wrapper. `send-fn` is now called bare inside `try`. Success
is assumed unless `send-fn` throws. Updated docstring to make this contract explicit:
"send-fn must throw on failure; any return value indicates success."

**Before:**

```clojure
(try
  (when-not (send-fn thing effect)
    (runtime/apply-transition! ... transition/command-failed ...))
  (catch Exception _
    (runtime/apply-transition! ... transition/command-failed ...)))
```

**After:**

```clojure
(try
  (send-fn thing effect)
  (catch Exception _
    (runtime/apply-transition! ... transition/command-failed ...)))
```

---

## 2026-04-28T10:07 — Fix 2: `add-link` throws on unknown profile replaced with structured error

**File:** `src/openhab/transition.clj`

**Problem:** `add-link` called `reproject-items` immediately after inserting the link.
`reproject-items` → `projection/project-item-state` → `profile/project-state` throws
`ex-info "Unknown profile"` when the link's `:profile` name is not registered. This throw
escaped the transition function as an uncontrolled exception rather than a structured
`{:ok false}` result — inconsistent with every other validation in `add-link`.

**Fix:** Added a profile existence check as an explicit `cond` branch before the `:else`
clause. Unknown profiles now return `{:ok false :reason :unknown-profile}` without touching
state. The `reproject-items` call is only reached when all preconditions pass, eliminating
any possibility of an unknown-profile throw inside `swap!`.

**Added to `add-link` cond:**

```clojure
(nil? (get-in profile-registry [:profiles (:profile lnk)]))
(result state {:result {:ok false :reason :unknown-profile}})
```

---

## 2026-04-28T10:14 — Fix 3: `dispatch-item-command!` catch scope narrowed to encoding only

**File:** `src/openhab/commands.clj`

**Problem:** The `try/catch` in `dispatch-item-command!` wrapped both the profile encoding
step and the `apply-transition!` call. A failure from `apply-transition!` (e.g. missing
dispatcher, re-entrancy guard throw) would be silently relabelled `:encoding-failed` — hiding
a programming error behind a misleading result code.

**Fix:** Extracted the encoding step into its own `try/catch` that returns either
`{:ok true :commands [...]}` or `{:ok false :reason :encoding-failed :error ex}`.
The `apply-transition!` call is outside the `try` and can propagate normally if it fails.

**Structure after fix:**

```clojure
(let [encode-result (try
                      {:ok true :commands (mapv ... links)}
                      (catch Exception ex
                        {:ok false :reason :encoding-failed :error ex}))]
  (if-not (:ok encode-result)
    (select-keys encode-result [:ok :reason :error])
    (let [transition-result (runtime/apply-transition! ...)]
      (:result transition-result))))
```

---

## 2026-04-28T10:19 — Fix 4: `item/set-state` clock impurity documented

**File:** `src/openhab/item.clj`

**Problem:** `item/set-state` calls `java.time.Instant/now`, which is a side effect. The
function is on the Pure side of the I/O boundary and is called from inside `swap!` via
`projection/project-items` → `transition/channels-reported`. On `swap!` retry, the clock is
read again and `:last-change` gets a different timestamp. This is a tolerated impurity —
removing it would require passing a clock into the projection pipeline — but it was
undocumented.

**Fix:** Extended the docstring to name the impurity and its consequence explicitly. No
behavioural change; `:last-change` continues to reflect the last successful swap! pass.

**Added to docstring:**

```text
Tolerated impurity: reads the system clock (Instant/now). This fn runs inside swap! and may
be called on retry; :last-change will reflect the clock time of the last successful pass.
```

---

## 2026-04-28T10:28 — Fix 5: Example addon created (`example-addon` namespace)

**Files created:**

- `src/example_addon/device.clj`
- `src/example_addon/api.clj`
- `src/example_addon/system.clj`

**Purpose:** Exercises the full pipeline end-to-end — bridge registration, child thing
registration, item/link wiring, polling, command dispatch, and event publication — using an
in-memory device state instead of a real HTTP API. Validates the addon-facing framework API
and surfaces friction before the design is locked.

**Structure:**

- `device.clj` — device type definition, channel specs, codec registration
- `api.clj` — in-memory API stub; `fetch!` returns channel map, `send!` applies commands
- `system.clj` — `start!`/`stop!` wiring, effect dispatcher construction, polling loop

**API friction discovered during wiring:**

`bridge/start!` calls `transition/channels-reported` with `bridge-id` as the thing-id. This
only makes sense when the bridge thing itself has channels. For the more common topology — a
bridge that polls child device state — `polling/start!` must be used directly with the
device's thing-id. `bridge/start!` does not serve the child-polling pattern as currently
implemented. This gap is documented in the `system/start!` docstring and parked for a future
design pass.

**Effect-dispatcher circularity pattern documented in `system/start!`:**

The effect-dispatcher must be in the context passed to effect handlers (for corrective
transitions), but the dispatcher is constructed from that context — a circular reference.
Resolved via an `atom` holding the real dispatcher behind a stable lazy-wrapper fn:

```clojure
(let [effect-dispatcher* (atom nil)
      lazy-dispatcher    (fn [effect] (@effect-dispatcher* effect))
      ctx                {:effect-dispatcher lazy-dispatcher ...}
      dispatcher         (effects/make-dispatcher handler-map ctx)]
  (reset! effect-dispatcher* dispatcher)
  ...)
```

---

## 2026-04-28T11:15 — Fix 6: Broken test repaired after Fix 1 changed send-fn contract

**File:** `test/openhab/commands_test.clj`

`send-failure-clears-desired-and-corrects-item-state` used `(fn [_ _] false)` as `send-fn`.
After Fix 1 removed the `when-not` wrapper, a falsy return no longer triggers `command-failed`.
Updated stub to `(fn [_ _] (throw (ex-info "send failed" {})))` to match the new contract.

---

## 2026-04-28T11:20 — Fix 7: nil-Thing guard added to send-command-handler

**File:** `src/openhab/effects.clj`

If a Thing is removed between effect emission and handler dispatch, `registry/get-thing`
returns nil. Without a guard, `send-fn` receives nil and may return normally, silently
treating the command as successful. Added an explicit `(when-not thing (throw ...))` inside
the `try` so a missing Thing routes through the `catch` and correctly fires `command-failed`.

---

## 2026-04-28T11:25 — Fix 8: desired-expiry-threshold made private

**File:** `src/openhab/transition.clj`

`desired-expiry-threshold` was a public var. Addons cannot meaningfully override it at the
namespace level without affecting all system instances on the JVM. Made `^:private`. Addons
requiring a different threshold must use the 2-arity `channels-reported` overload.

---

## 2026-04-28T11:30 — Fix 9: affected-item-names double-sort removed

**File:** `src/openhab/projection.clj`

`affected-item-names` ended with `sort`, returning a sorted seq. `project-items` then called
`(sort item-names)` again on that input. Removed `sort` from `affected-item-names` — it now
returns a plain set. `project-items` owns ordering and continues to sort before reducing.

---

## 2026-04-28T11:35 — Fix 10: bridge/get-children signature aligned with other bridge fns

**File:** `src/openhab/bridge.clj`, `test/openhab/bridge_test.clj`

All other functions in `bridge.clj` destructure a context map `{:keys [registry ...]}`.
`get-children` took a raw `registry` atom. Changed to accept context and destructure
`:registry` from it. Updated the bridge test call site accordingly.

---

## 2026-04-28T11:40 — Fix 11: send-command-handler tests added

**File:** `test/openhab/effects_test.clj`

Added three focused tests for `send-command-handler`:

- success path: `send-fn` returns normally, `:desired` stays intact
- failure path: `send-fn` throws, `:desired` is cleared via `command-failed`
- nil-Thing path: thing-id not in registry, handler must not throw, `command-failed` fires

---

## 2026-04-28T11:45 — Fix 12: unknown-profile test added for add-link

**File:** `test/openhab/transition_test.clj`

Added `add-link-rejects-unknown-profile` covering the new `:unknown-profile` cond branch
added in Fix 2. Verifies the result is `{:ok false :reason :unknown-profile}` and that no
link is written to the registry.

---

## 2026-04-28T11:50 — Fix 13: wire-items-and-links! asserts transition results

**File:** `src/example_addon/system.clj`

Both `add-item` and `add-link` calls in startup wiring previously discarded their results.
A failed link registration (e.g. `:unknown-profile`) would leave the system silently broken.
Each call now checks `[:result :ok]` and throws `ex-info` on failure, failing startup loudly.

---

---

## 2026-04-28T12:00 — Fix 14: `channels-reported` nil guard added

**File:** `src/openhab/transition.clj`

Calling `channels-reported` with nil `reported` previously silently coerced nil → `{}` via `(or reported {})`, wiping all reported channel state. This is a programming error — addons that encounter a device error should call `set-thing-status :offline`, not pass nil to `channels-reported`. Added an explicit nil check that throws `ex-info` with a clear message before the function body executes. Removed the `(or reported {})` rebinding. Updated the docstring to state the nil contract and expose the default expiry-threshold value (3 polls).

---

## 2026-04-28T12:05 — Fix 15: `project-item-state` lazy-seq double-traverse eliminated

**File:** `src/openhab/projection.clj`

`(count links)` realized the full lazy seq from `links-for-item`, then `(first links)` re-traversed it from the start — two full walks for what is almost always a one-element collection. Changed to destructuring `[lnk second-lnk] links` which materializes at most two elements. The multi-link throw no longer includes `:link-count` (would require re-counting), which was only for debugging and not part of any contract.

---

## 2026-04-28T12:10 — Fix 16 & 17: `commands.clj` cleanup

**File:** `src/openhab/commands.clj`

**Fix 16:** Added a docstring to `dispatch-channel-command!` making explicit that it bypasses profile encoding and requires a device-native channel value. Without this, callers could mistake it for the item-level dispatch.

**Fix 17:** Removed `:error ex` from the `:encoding-failed` catch clause and the downstream `select-keys`. All other error results in this namespace return `{:ok false :reason ...}` without embedding the exception; embedding it in encoding-failed was inconsistent and exposed internal exception objects to callers.

---

## 2026-04-28T12:15 — Fix 18: `update-thing`/`update-item` invariant-bypass warnings

**File:** `src/openhab/registry.clj`

Both functions update registry values directly, bypassing the immutability guarantees enforced by `put-thing` (`:thing-type`, `:bridge?`, `:channels`, `:runtime`, bridge indexes) and the transition layer. Added "WARNING: bypasses all invariants" to both docstrings so callers understand the risk and prefer `put-thing`/`put-item` or transition functions.

---

## 2026-04-28T12:20 — Fix 19: `event-topic` fallback for unknown event types

**File:** `src/openhab/events.clj`

The `case` fallback returned `nil`, leaving `:event/topic nil` on custom addon events. Subscribers using `subscribe-topic!` could not match them. Changed the fallback to compute `"openhab/events/<namespace>/<name>"` for keyword types (e.g. `:addon/custom` → `"openhab/events/addon/custom"`). Non-keyword types still return nil; all first-party event types are keywords so this does not affect existing behavior.

---

## 2026-04-28T12:25 — Fix 20: `status-value` nil-input documented

**File:** `src/openhab/thing.clj`

`(contains? nil :runtime)` returns false in Clojure, so `status-value` silently returns nil when passed nil. Added "Returns nil for nil input." to the docstring so callers do not need to read the implementation to know this is safe.

---

## 2026-04-28T12:30 — Fix 21: `effects_test.clj` cleanup

**File:** `test/openhab/effects_test.clj`

**Success test:** `send-command-handler-success-leaves-desired-intact` created a dispatcher with send-fn A, then immediately created a separate handler with a different send-fn B and called it directly — the dispatcher was never exercised. Refactored to call through the dispatcher, consistent with the other two handler tests.

**Nil-thing test:** `send-command-handler-nil-thing-fires-command-failed` asserted only `(some? @registry)` — trivially true regardless of outcome. Replaced with: (1) verify `send-fn` is never called (atom flag), and (2) verify the real thing's `:desired` is unaffected (command-failed on a nonexistent thing-id is a no-op on registry state).

---

## 2026-04-28T12:35 — Fix 22: `projection_test.clj` created

**File:** `test/openhab/projection_test.clj` (new)

Added 11 tests covering the previously-untested projection layer:

- `affected-item-names`: single channel, multiple channels, unlinked channel, unknown thing, return type is a set
- `project-item-state`: codec path, system-default path, nil for unlinked item, throws on multi-link
- `project-items`: state updated + changes recorded, skips unchanged items, processes multiple items deterministically, skips missing items

---

## 2026-04-28T12:40 — Fix 23–27: Missing `transition_test.clj` tests

**File:** `test/openhab/transition_test.clj`

- **Fix 23** — `add-item-preserves-projection-fields-on-reregistration`: existing test only checked `:item-type`. Added check that `:state`, `:state-type`, and `:last-change` are all preserved from the existing entry.
- **Fix 24** — `add-thing-reregistration-under-offline-bridge-emits-status-event`: re-registering an online child under an offline bridge must (a) set the child offline and (b) emit a `:thing/status-changed` event. Verified both.
- **Fix 25** — `channels-reported-throws-on-nil-reported`: exercises the new nil guard from Fix 14.
- **Fix 26** — `channels-reported-expires-desired-after-threshold-polls`: uses the 5-arity overload with threshold=2 to confirm desired ages to 1 after the first unreported poll and is removed at age=2.
- **Fix 27** — `channels-reported-partial-poll-settles-and-ages-desired`: two commands pending (fan-speed, mode); poll only reports fan-speed. Verifies fan-speed desired is pruned and mode desired is aged.

---

---

## 2026-04-28T13:30 — Fix 28: Codex integration — stale status table corrected

**File:** `doc/rest-api-findings.md`

Codex implemented `openhab.reporting`, `openhab.bridge` (updated), and `doc/design.md`
since the previous Claude session. The "missing abstractions" status table in
`rest-api-findings.md` still listed Item, Link, and Events as "missing" — all three
were implemented in earlier sessions. Updated the table to show all core abstractions
as done and added Reporting and Projection rows.

---

## 2026-04-28T13:35 — Fix 29: Tests added for bridge/stop!, start-own-channel-polling!, reporting/mark-offline

**Files:** `test/openhab/bridge_test.clj`, `test/openhab/reporting_test.clj`

Three functions added by Codex were untested:

- `bridge/start-own-channel-polling!` — `start-own-channel-polling-reports-bridge-channels-and-marks-online`:
  registers a bridge with a channel, starts polling with `:initial-fetch? true`,
  verifies bridge status goes `:online`, and calls `bridge/stop!` in finally.

- `bridge/stop!` — `stop-marks-bridge-and-children-offline`:
  bridge and child both start `:online`; after `bridge/stop!`, both are `:offline`.

- `reporting/mark-offline!` — `mark-offline-sets-thing-status-offline`:
  report-channels! first brings the thing `:online`; mark-offline! then sets it
  to `:offline` through the transition path.

Total test count: 91 (up from 88).

---

## Parked (not addressed in this session)

- **Group-item projection** — explicitly deferred; groups are core to OpenHAB UX but require separate design work.
- **`bridge/start!` child-polling gap** — bridge polls child device state is a common pattern that `bridge/start!` does not support; noted in Fix 5 above.
- **Specs not enforced on hot path** — `clojure.spec.alpha` specs are defined but no `s/valid?` / `s/assert` / `s/instrument` in transitions or tests.
- **Persistence** — registry is in-memory only; restart loses all state.
- **REST/HTTP + SSE layer** — no external exposure of the registry or event bus.

---

Session: 2026-04-29

---

## 2026-04-29 — Fix 30: `transition/remove-link` direct tests added

**File:** `test/openhab/transition_test.clj`

Three tests for `remove-link` covering all three execution paths:

- `remove-link-returns-link-not-found-for-unknown-link` — rejection path.
- `remove-link-clears-projected-item-state-when-last-link-removed` — item has projected state 30; after removing its only link, state is nil and both `:link/removed` and `:item/state-changed` are emitted.
- `remove-link-preserves-item-state-when-item-still-linked` — two-link item (forced via `registry/put-link` bypass); after removing one link, item state stays at 30 and no `:item/state-changed` is emitted.

---

## 2026-04-29 — Fixes 31–39: Clojure 1.11+ idiom sweep and code quality pass

**Files:** `src/openhab/events.clj`, `src/openhab/thing.clj`, `src/openhab/transition.clj`, `src/openhab/profile.clj`

**Fix 31 — `events/decorate-event`: `cond-> … true … true` antipattern removed**

`cond->` with unconditional `true` branches is a misuse of the macro. Replaced with a single `assoc` call for both `:event/name` and `:event/topic`.

**Fix 32 — `events/event-topic`: link-key positional access replaced with destructuring**

`(first link-key)`, `(second link-key)`, `(nth link-key 2)` replaced with `(let [[item-name thing-id ch-id] link-key] …)` in both `:link/added` and `:link/removed` cases.

**Fix 33 — `events/event-name`: fallback now preserves keyword namespace**

`(name type)` stripped the namespace from custom event keywords (`:addon/custom` → `"custom"`), inconsistent with the `event-topic` fallback which preserves it. Changed to `(str (some-> (namespace type) (str ".")) (name type))` so `:addon/custom` → `"addon.custom"`, matching Java event-name convention.

**Fix 34 — `thing/effective-channels`: `into {}` + `map` replaced with `update-vals`**

```clojure
;; before:
(into {} (map (fn [[ch e]] [ch (:value e)])) desired)
;; after:
(update-vals desired :value)
```

**Fix 35 — `thing/effective-channel`: full-map merge replaced with direct desired-entry check**

Previous implementation called `effective-channels` (O(n) merge across all channels) then did a `get`. Replaced with `(if-let [entry (get-in thing [:runtime :desired channel-id])] (:value entry) (get-in thing [:runtime :reported channel-id]))` — correct for falsy desired values and avoids building the intermediate map.

**Fix 36 — `transition/channels-reported` `aged-desired`: `into {}` + `map` replaced with `update-vals`**

```clojure
;; before:
(into {} (map (fn [[ch e]] [ch (update e :age inc)])) remaining-desired)
;; after:
(update-vals remaining-desired #(update % :age inc))
```

**Fix 37 — `transition`: `err-result` private helper extracted; all 19 call sites updated**

`(result state {:result {:ok false :reason :xyz}})` repeated across every validation branch. Extracted to `(defn- err-result [state reason] (result state {:result {:ok false :reason reason}}))` and replaced all 19 occurrences. No behaviour change; reason keywords are now the only thing visible at call sites.

**Fix 38 — `profile/register-codec` docstring: codec return convention documented**

`:to-state` functions may return either a raw value (auto-wrapped to `{:state … :state-type …}`) or a map with a `:state` key (used directly). This implicit protocol was invisible to addon authors. Added to the docstring.

**Fix 39 — `thing/::command-id` spec: dead `uuid?` branch removed**

`(s/or :uuid uuid? :string …)` allowed both `java.util.UUID` objects and strings, but `commands/next-command-id` always produces `(str (UUID/randomUUID))`. Simplified to `(s/and string? seq)`.

**Result:** `clojure -M:lint` 0 warnings/errors. `clojure -M:test` 96 tests, 219 assertions, 0 failures.

---

Session: 2026-04-30

---

## 2026-04-30 — Fixes 40–42: offline guard placement, stale command-failed event, SSE adapter doc

**Files:** `src/openhab/transition.clj`, `test/openhab/transition_test.clj`, `doc/rest-api-findings.md`

**Fix 40 — `transition/item-command->channel-commands`: offline guard moved inside the resolver**

The `:thing-offline` check lived in `validate-channel-command` (called from `command-accepted`), which meant `profile/encode-command` ran unconditionally even when the thing was offline. The guard is now a `cond` branch in `item-command->channel-commands` between `:channel-not-found` and `:else`, so encoding is skipped entirely for offline things. Semantics are unchanged — the command is still rejected before any state is written — but the guard now sits at the correct decision point.

Test added: `item-command-accepted-rejects-offline-thing` — sets thing status to `:offline` in `base-state`, confirms `{:ok false :reason :thing-offline}` and empty effects.

**Fix 41 — `transition/command-failed`: stale callback emits no event**

When a command-failed callback arrives for a command-id that has already been superseded by a newer command, `cleared-channel-ids` is `#{}`. Previously the function still emitted `{:event/type :command/failed :channel-ids #{}}`. An event with no cleared channels is meaningless to subscribers and breaks the invariant that `:command/failed` signals actual state change. The event is now emitted only when `(seq cleared-channel-ids)`.

Test added: `command-failed-emits-no-event-when-command-id-is-stale` — issues cmd-1, then cmd-2 for the same channel (cmd-2 overwrites cmd-1 in `:desired`), then fires command-failed for cmd-1; asserts no `:command/failed` event in the result.

**Fix 42 — `doc/rest-api-findings.md`: SSE adapter named explicitly**

"Ring + Reitit for REST/SSE" was inaccurate — Reitit is a routing library and adds nothing for SSE. SSE depends on the Ring adapter. Updated: http-kit table entry rewritten to identify it as the Ring adapter providing `as-channel` for SSE and WebSocket; recommendation updated to "Ring + Reitit + http-kit" with a sentence explaining the division of responsibility. Pre-existing lint warnings also cleaned up (bare URL, escaped angle brackets, table column alignment, blank line before list).
