# Codex Refactor Log

Session: 2026-04-28

---

## 2026-04-28T20:23:58+02:00 — Fix 1: Channel reporting lifecycle extracted from bridge polling

**Files:** `src/openhab/reporting.clj`, `src/openhab/bridge.clj`, `src/openhab/polling.clj`, `src/example_addon/system.clj`, `test/openhab/reporting_test.clj`, `doc/design.md`

**Problem:** `bridge/start!` treated the bridge as the Thing whose channels are reported. That is only valid when the bridge itself has channels. The common binding shape is different: a bridge owns the connection and polls child device state. That made the bridge abstraction lie and forced the example addon to bypass it with raw `polling/start!` wiring.

**Decision:** Add `openhab.reporting` as a small lifecycle adapter between generic polling and channel-state transitions. `polling.clj` remains a scheduler with no registry knowledge. `bridge.clj` remains topology/status-oriented. `reporting.clj` owns the reusable workflow: report a full channel snapshot for an explicit `thing-id`, mark that Thing offline on fetch failure, and optionally perform a synchronous initial fetch during startup.

**Reasoning:** Reporting by explicit `thing-id` fits standalone Things, bridge Things with their own channels, and child Things polled through a bridge connection. It avoids recreating Java-style handler inheritance while keeping each namespace simple: polling schedules, reporting applies channel snapshots, bridge maintains parent/child topology.

**Changes:** Added `report-channels!`, `mark-offline!`, `start-channel-polling!`, and `stop-channel-polling!` in `openhab.reporting`. Added `:initial-delay-ms` to `polling/start!` so a synchronous initial fetch does not cause an immediate duplicate background fetch. Replaced the ambiguous bridge polling path with `bridge/start-own-channel-polling!` and kept `bridge/start!` as a temporary compatibility alias. Updated the example addon to use `reporting/start-channel-polling!` with `:initial-fetch? true`, and to assert bridge/device registration results during startup.

**Tests:** Added `openhab.reporting-test` coverage for reporting projection, synchronous startup readiness, and initial fetch failure marking the Thing offline.

**Docs:** Updated `doc/design.md` to explain why reporting is separate from polling and bridge topology, why startup readiness is explicit, and how `bridge/start-own-channel-polling!` should be used only for bridge-owned channels.

---

## 2026-04-29T10:39:50+02:00 — Fix 2: Cleanup pass after Claude cross-check

**Files:** `src/openhab/item.clj`, `src/openhab/projection.clj`, `src/openhab/transition.clj`, `src/openhab/commands.clj`, `src/openhab/effects.clj`, `src/openhab/reporting.clj`, `src/openhab/bridge.clj`, `src/example_addon/system.clj`, `test/openhab/*`, `doc/design.md`, `claude-refactor.md`

**Problem:** Four low-severity issues remained after the baseline review. `item/set-state` still read the system clock indirectly inside transition/projection paths, which made the pure transition claim slightly overstated under `swap!` retry. `reporting/start-channel-polling!` returned differently-shaped `:initial-result` data on success vs failure. One transition test still used misleading "partial poll" wording even though `channels-reported` has full-snapshot semantics. `claude-refactor.md` still listed the old `bridge/start!` child-polling gap as parked even though `openhab.reporting` resolved the design gap.

**Decision:** Treat the timestamp issue as a real purity fix, not just documentation cleanup. `item/set-state` now requires `changed-at`; projection and transition functions thread it as data. Imperative edge namespaces (`commands`, `effects`, `reporting`, `bridge`, and example addon wiring) capture `Instant/now` before calling transitions that may update item projection state. `reporting` now normalizes `:initial-result` to include `:ok`, `:result`, and `:transition` on both success and failure, with `:error` only on failure.

**Reasoning:** Transition functions may be retried by `swap!`, so hidden clock reads inside projection are not referentially transparent. Passing `changed-at` explicitly keeps the core model deterministic while still letting runtime edges stamp item state changes. This is more idiomatic Clojure than hiding time inside a helper: time is just another explicit input value.

**Changes:** Updated tests for the explicit timestamp contract, renamed the misleading full-snapshot convergence test, corrected stale `claude-refactor.md` parked wording, and updated `doc/design.md` to explain why timestamps are supplied by the imperative edge.

**Tests:** `clojure -M:lint` and `clojure -M:test` both pass after the refactor.

---

## 2026-04-29T11:31:09+02:00 — Fix 3: Revert accidental edits to Claude's log

**File:** `claude-refactor.md`

**Problem:** I edited Claude's changelog as if it were mutable project documentation. That was wrong: it is provenance for Claude's work, not my place to revise.

**Fix:** Reverted the remaining Codex edits in `claude-refactor.md` back to Claude's original wording for Fix 27 and the parked `bridge/start!` child-polling note. Future corrections to Claude-log facts should be recorded here in `codex-refactor.md` or in project docs, not by rewriting Claude's log.

---

## 2026-04-29T14:44:30+02:00 — Fix 4: Remove dead bridge alias and close transition coverage gaps

**Files:** `src/openhab/bridge.clj`, `test/openhab/transition_test.clj`, `doc/design.md`

**Problem:** `bridge/start!` was a deprecated positional compatibility alias for `start-own-channel-polling!`, but this is a greenfield PoC with no external compatibility surface yet. Keeping dead API on day one makes the baseline noisier. The review also identified direct transition-test gaps around `remove-item` and `set-thing-status`; `remove-link` already had direct coverage in the current tree.

**Decision:** Remove `bridge/start!` entirely and keep only the explicit `start-own-channel-polling!` helper. Add direct tests for `transition/remove-item` and `transition/set-thing-status`. Leave Claude's changelog untouched; project-facing design wording now refers to bridge-owned polling rather than the removed alias.

**Reasoning:** In a PoC before first commit, compatibility aliases are not compatibility; they are accidental API surface. Removing the alias keeps the public shape smaller and clearer. Direct tests for simple transitions are cheap and make the baseline safer before building the next layer.

**Tests:** `clojure -M:lint` passes with 0 warnings/errors. `clojure -M:test` passes: 96 tests, 219 assertions, 0 failures/errors.

---

## 2026-04-30T09:23:57+02:00 — Fix 5: Transition-bound command resolution, stricter reporting, current dependency baseline

**Files:** `deps.edn`, `.clj-kondo/config.edn`, `src/openhab/commands.clj`, `src/openhab/transition.clj`, `src/openhab/effects.clj`, `src/openhab/events.clj`, `src/openhab/polling.clj`, `src/openhab/profile.clj`, `test/openhab/transition_test.clj`, `test/openhab/profile_test.clj`, `test/openhab/events_test.clj`, `test/openhab/commands_test.clj`, `test/openhab/effects_test.clj`, `doc/design.md`, `doc/rest-api-findings.md`

**Problem:** Claude's latest pass left the core mostly coherent, but several medium-risk seams still violated the intended architecture: item commands read links/profile state before entering the pure transition, `channels-reported` accepted undeclared channel ids into `:reported`, cascade removals removed links without emitting `:link/removed`, send failures corrected state without a domain event, and profile/codec registration allowed malformed runtime data. The dependency baseline also lagged behind current releases for several libraries, and the HTTP/API direction was not captured in project docs.

**Decision:** Move item command resolution into `transition/item-command-accepted` so read/encode/write all happen against one registry snapshot. Reject unknown reported channels as addon contract violations. Emit structural link-removal events during item/thing cascade removal. Add `:command/failed` as an observable event for send failures. Validate profile and codec registrations at registration time. Upgrade the current dependency set and document the thin HTTP edge recommendation.

**Reasoning:** This keeps the pure/imperative boundary honest: `commands.clj` creates command ids and invokes transitions; `transition.clj` owns all state-dependent decisions. Rejecting undeclared reported keys prevents silent drift between Thing schema and runtime state. Event completeness matters for an eventual UI/event stream: if state changed structurally or a command failed, subscribers should not have to infer it from side effects. Registration-time validation is the idiomatic Clojure compromise here: keep data-driven extension points, but fail bad addon data early.

**Changes:** Added `transition/item-command-accepted`, `profile/known-profile?`, profile/codec shape validation, `:command/failed` event naming/topic decoration, link-removal event emission for cascades, idempotent exact-link re-add behavior, and `channels-reported` undeclared-channel rejection. `effects/send-command-handler` now logs failures before applying `command-failed`. `polling.clj` uses `core.async/io-thread`, and clj-kondo is configured for the current tooling false positive on that var. Updated docs to explain these decisions and added a `doc/rest-api-findings.md` section comparing Ring, Reitit, http-kit, Pedestal, Aleph, Lacinia, Kit, and Biff.

**Dependencies:** Updated `core.async` to `1.9.865`, `spec.alpha` to `0.6.249`, `tools.logging` to `1.3.1`, `clj-http` to `3.13.1`, `cheshire` to `6.2.0`, and `nrepl` to `1.6.0`. Kept Clojure `1.12.4`, Kaocha `1.91.1392`, clj-kondo `2026.04.15`, and Cognitect test-runner `v0.5.1`.

**Tests:** `clojure -M:lint` passes with 0 warnings/errors. `clojure -M:test` passes: 107 tests, 243 assertions, 0 failures/errors.

---

## 2026-04-30T10:06:27+02:00 — Fix 6: Design doc aligned with Claude Fixes 40–41

**File:** `doc/design.md`

**Problem:** Claude's Fix 40 and Fix 41 changed the command-path semantics correctly, but `doc/design.md` still described the older behavior. The outbound item-command flow listed profile encoding before offline rejection, while the implementation now rejects offline Things before calling `profile/encode-command`. The effect-failure section also implied every `command-failed` transition emits `:command/failed`, while the implementation now suppresses the event for stale callbacks that clear no current desired state.

**Decision:** Update the design doc to make the code's intended behavior explicit: offline rejection happens before profile encoding, and `:command/failed` is emitted only when at least one current desired entry is cleared.

**Reasoning:** These are not cosmetic details. Encoding may have profile-specific assumptions and should not run for commands that are already invalid due to Thing status. Likewise, an event named `:command/failed` should represent an observable current command failure, not a stale callback for a command that has already been superseded.

**Tests:** `clojure -M:lint` passes with 0 warnings/errors. `clojure -M:test` passes: 109 tests, 246 assertions, 0 failures/errors.
