# 21 · Rig write drain and Exit

**Status:** Implemented in `fab89cb` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24).

No plan page was written before implementation; the design brief the implementer worked from and its hand-off report are recorded here. Paths are relative to `src/laser_show/` unless they say otherwise, and citations refer to the code at the time of writing.

## Design brief

Audit: "A 'flushed' rig write can still be in flight" (section "Document, undo and asynchronous work"). Related decisions: plans/2026-11-19-zones/09-phase-zr-rig-file.md (in WT) — the rig file, its lock, backup/recovery, write-failed status and Exit's "Try again / Quit anyway" question.

Observed (persistence auditor, gated fake writer at a7e414e):
1. state/rig_store.clj `write-pending!` takes and clears `!pending` *before* the I/O; `flush!` returns true at once when the slot is empty — so with a blocked write in flight, `flush!` returned true while the write was still running. app.clj `stop-rig-file!` then calls `rig-store/unlock!`, releasing the lock before the file is written, and Exit can end the JVM.
2. `submit!` evaluates `(.schedule writer write-pending! delay)` *inside* the `reset!` argument, i.e. before the new pending map is published: with delay 0 (the retry path) the task can run, find nothing, and leave the new document unwritten.
3. Exit checks only `[:rig-file :status] :write-failed` (events/handlers/menu.clj `rig-guard-needed?`), not an outstanding write.
4. "Try again" in the rig question dispatches `:rig/retry-write` with `:then {:event/type :file/exit :force? true :rig-force? true}`; on success `events/handlers/rig.clj` `handle-written` dispatches that forced Exit even if the show gained a new unsaved edit while the retry ran.

Required:
- Writer-owned sequencing: track queued AND in-flight writes (e.g. a monotonically increasing submit sequence plus "written up to"/in-flight state owned by the writer thread). Publish the pending document before scheduling. `flush!` waits for the queued write and any write already running (the writer is single-threaded: a barrier task submitted after the running one is a simple, correct wait), bounded by its timeout, and returns false on timeout. Superseded rigs still never report results.
- Shutdown keeps the lock until the writer has drained (or the bounded wait timed out — then keep current behaviour of warning; don't hang shutdown forever). Never wait on the event thread for an event that must run on the event thread (no deadlock: Exit runs `app/stop!` on the event thread today).
- Exit continuation re-evaluates current state when it resumes: a successful retry continues Exit only through the normal guards (drop `:force? true` for the show guard, or keep it only if the document identity and dirty state are unchanged since the user authorized it — 20's `[:project :document-id]` has landed in this worktree, see `git log 7b320b3..HEAD`). Also consider an Exit requested while a write is merely pending/in flight: flush it as part of shutdown (existing) and report failure through the existing write-failed path if it fails.
- Keep ZR's behaviours: write-failed notice and status, read-only windows never write, lock semantics, backup/recovery.

Tests (NEW, names adaptable): laser-show.state.rig-store-test — `flush-waits-for-an-in-flight-write-test` (gate the actual write fn with a promise via with-redefs; assert flush! doesn't return true before release, returns false on timeout), `zero-delay-submit-always-writes-its-document-test`; app or rig-store test that unlock happens after drain; events tests (laser-show.events.menu-system-handlers-test / rig handler tests) — `retry-success-after-a-new-edit-asks-again-test` and the clean case still exits once. Use temp dirs (existing rig_store tests show the pattern). Keep laser-show.state.rig-store-test, rig handler tests, menu tests and laser-show.ui-test.rig-* tests that cover the rig file passing.

## As implemented

**Changes**
- `state/rig_store.clj`: `submit!` stores the rig before scheduling its write (zero-delay retry always finds the newest rig; superseded rigs never written/reported). `flush!` waits behind any running write as well as the pending one, and their results are reported before it returns; returns false after `flush-ms` (2 s, NEW constant, also used by the shutdown hook).
- `app.clj`: `stop-rig-file!` unlocks only after that wait; on timeout it warns and unlocks anyway (never hangs).
- NEW `:rig/flush` effect (`events/core.clj`): waits for the rig writer in a background thread (bounded), then dispatches its `:then` (writes' own results dispatched first).
- Exit (`handlers/menu.clj`): Exit with a rig write pending/running waits via `:rig/flush`, then comes back once marked `:rig-waited?`. If the write failed meanwhile → write-failed status + notice, then the rig question (Try again / Quit anyway). The rig question's parked Exit carries `:authorized` (taken when asked); Exit's own wait carries it too. `handle-file-exit` checks `may-discard?` first and re-asks `{:event/type :file/exit}` when it fails.
- ZR test `exit-after-a-failed-rig-write-asks-first-test`: parked Exit now includes `:authorized` (deliberate).

**Gaps**: a Try-again write replaced by a newer autosave before pickup loses its `:then` (pre-existing). Stuck write → quitting takes ≤ ~4 s. Don't-Save-confirmed edits during the wait are discarded (same as 20).

**Notes for 19**
- `app/stop!` order: claim `*app` → unmount renderer → stop show clock, input mapper, gamepad → drain output runtime → unload add-ons → (when an app existed) `events/flush!` and release devices → `stop-rig-file!` (`rig-store/flush!` ≤ `flush-ms`, warn on timeout, `rig-store/unlock!`) → clear exit fn, dispatch fn and write guard.
- Late rig results need 19's fence: results of the final flush are queued on the old app's agent when `flush!` returns and run later against the global state; with the same rig path in a new app, `this-file?` passes → could mark the new app's rig saved, show a notice, or dispatch a Try-again Exit.
- Late Exit continuations: `:rig/flush`'s continuation (`:file/exit` with `:rig-waited?`) can arrive after `stop!` and would call whichever exit fn is registered then.
- Fence the retired app before `stop-rig-file!`, but keep unlock after flush.
- Calling `flush!` on the event thread is safe (rig writer never needs the event thread).
- Option: after a timed-out wait the lock is released while a write may still run; for same-JVM restarts consider handing `unlock!` to the writer thread to run right after that write.
