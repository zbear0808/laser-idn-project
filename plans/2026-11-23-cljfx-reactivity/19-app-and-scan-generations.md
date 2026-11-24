# 19 · App and scan generations

**Status:** Implemented in `39349c9` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24).

No plan page was written before implementation; the design brief the implementer worked from and its hand-off report are recorded here. Paths are relative to `src/laser_show/` unless they say otherwise, and citations refer to the code at the time of writing.

## Design brief

Audit: "A serialized event queue does not fence an old app instance" (section "Document, undo and asynchronous work"); external-bridges finding "A discovery result has no request or app-instance identity". The completed single-writer work (state guard, one event executor for every app instance, :state first) is in git history (`09b6db7`..`7412ac6`); keep all of it.

Observed (events auditor, isolated probes at a7e414e):
1. Same-JVM restart (tests and REPL do `app/stop!` then `app/start-test!`): `stop!` ignores a failed 1 s `events/flush!`; `start-test!` then calls `state/init-state!` on the caller's thread. An old handler that had already taken its `:state` snapshot can still commit it via `effect-state` after the re-init. Holding a fake MIDI refresh handler across the timeout restored the OLD document after the new one was initialized.
2. A successful flush doesn't fence future completions: a held device scan (events/core.clj `effect-projectors-scan` future) completed after restart and `handle-projectors-scan-complete` (events/handlers/projector.clj) moved the new rig's projector back to the old address (and backfills history; rig autosave can persist it).
3. Within one app, every Scan starts a future and completions are untagged: an older completion can clear a newer scan's `:scanning?` or restore an older address. Startup submits a scan independently of the button (app.clj `start!`).
Existing test laser-show.ui-test.app-lifecycle-test/old-and-new-app-share-the-event-thread-test only checks the thread.

Required:
- App generation: each mounted app instance has an identity; events dispatched by a retired instance's effects/dispatcher are dropped (logged at debug/info), and a commit computed from a snapshot taken before a context re-initialization is rejected rather than written (e.g. `init-state!` bumps a generation; the pipeline stamps the generation on the snapshot and `effect-state` refuses a stale stamp — or serialize re-init on the event thread; choose one, explain why). Never block the FX thread or wait on the event thread from itself (Exit runs `stop!` on the event thread). Keep `events/write-state!`, `events/flush!`, the no-app synchronous fallback used by unit tests, and the write guard working.
- Scan request ID: `:projectors/scan-network` records a NEW scan id in `[:projector-ui …]`, the effect echoes it, and `:projectors/scan-complete` / `:projectors/scan-failed` apply only for the current id (late/duplicate/old completions change nothing). Keep the startup scan and the UI's scanning indicator.
- 20 (document identity) and 21 (rig writer drain) have already landed in this worktree — reuse their mechanisms where natural; don't duplicate a second "document" identity.

Tests (NEW, names adaptable): laser-show.ui-test.app-lifecycle-test — `retired-app-cannot-commit-after-restart-test` (gate an old handler across stop!/start-test!, release, assert new state intact) and `retired-scan-completion-is-ignored-test`; unit tests in laser-show.events.projector-handlers-test (or the existing scan tests) for old/duplicate/reversed scan completions. Keep laser-show.ui-test.app-lifecycle-test, laser-show.events.core-test, reentry and discovery tests passing.

Boundaries: 31 (another agent, separate branch) changes `app/create-app` renderer options (`:renderer-error-handler`, `:renderer-middleware`) — keep your app.clj edits away from those option lines where possible; 24/25 own learn/device lifetimes.

## As implemented

**Changes**
- `state/core.clj`: every `init-state!` starts a new context epoch; every write keeps it (private `successor`). NEW `epoch`, NEW `swap-state-in-epoch!` (checks epoch + writes in one CAS; returns `[old new]`, or nil and writes nothing once re-initialised).
- `events/core.clj`: each mounted app is an app instance (NEW `new-instance!`, `retire!`, `instance-handler`). `instance-handler` puts the instance on the app's own agent and drops a retired instance's events as they arrive (info log); the `events/flush!` marker is still delivered. Every effect except `:dev/deliver` does nothing for a retired instance (`guard-all`); `run-inline` skips such an event's follow-ups. An instance's `effect-state`, `:output/write-status` and `:dev/swap-state` only write into the epoch it was made for. Scan effect sends back `:scan-id`.
- `app.clj`: in `create-app` only the `:event-handler` line changed → `(events/instance-handler (events/new-instance!))`. `stop!` calls `events/retire!` after the event flush and device release and before `stop-rig-file!`; rig unlock still after its flush. Late rig-writer results and late Exit continuation (21) dropped.
- Scan id: `:projectors/scan-network` records NEW `[:projector-ui :scan-id]`; complete/failed apply only to the current id (else `{}`); applying one clears the id. Startup scan and scanning indicator kept. `domains.clj` documents it; `.clj-kondo/config.edn` lists the new writer as discouraged outside `events.core`.

**Design**: epoch bump in `init-state!` + each instance bound to its epoch (not serialising re-init on the event thread — that would make start!/start-test! wait on a possibly stuck event thread, and Exit runs stop! on it). Live app always reads its own context; `init-state!` while an app runs is refused by the write guard. Co-effects stay `:state` and `:time`.

**Tests**: NEW `retired-app-cannot-commit-after-restart-test`, `retired-scan-completion-is-ignored-test`, `retired-app-learn-capture-commits-nothing-test`, `retired-app-rig-results-and-exit-do-nothing-test`; unit tests for old/duplicate/reversed scan results, id echo, retired-instance effects/inline follow-ups, write refused after re-init. `old-and-new-app-share-the-event-thread-test` now expects the old app's late event to be dropped (still checks the shared thread via the old queue's flush marker).

**Deviations**: no-app fallback (unit tests) commits aren't epoch-checked. A scan result without an id applies only while no scan with an id is running.

**Residual risks**: an effect already running at retirement still finishes its own side effect (e.g. zip write); only its continuation is dropped. A stuck handler that opens a device then has its commit dropped can leave the device open (25). 21's "unlock on writer thread after timed-out flush" not done. Leftover learn listener removal is 24's. A `write-state!` from another thread in the short tail of `stop!` is silently dropped. Docs could document the instance contract (orchestrator).

**Dispatcher contract for 31**
- `(:handler (fx/create-app …))` is the one handler: it's the `:fx.opt/map-event-handler`, the fn registered with `events/set-dispatch-fn!`, and the `dispatch` every effect receives. Middleware must not wrap/replace it or build a second handler.
- Keep: `:event-handler (events/instance-handler (events/new-instance!))` exactly once per mount and after `init-state!`; `:effects events/effects`; `:async-agent-options` with `events/event-executor` (the fence relies on cljfx running each app's events on that app's own agent).
- `create-app` still takes no args and returns `{:renderer :handler}`; calling it in isolation makes a new live instance (harmless in a separate JVM).
- Merge: 19's `create-app` edit touches only the `:event-handler` line and the docstring (docstring may conflict trivially with 31).

**Notes**: 24 — a stopped app's learn capture is dropped; learn identity within one app is still 24's. New effects added to the `effects` map are guarded automatically. Hand-built `:projectors/scan-complete` events against a state with a scan in progress must carry `:scan-id`.
