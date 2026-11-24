# Phase 3 — Background results come back through the event agent

**Written:** 2026-11-16   **Written for commit:** `70e049e` (`master`)
**Status:** Done 2026-11-16 (`47e7f8d`, `a37f690`; as built in §10). O5 answered 2026-11-16 (A)   **Last verified against:** `a37f690`
**Supersedes / Superseded by:** none   **Related findings:** O-9 (`00-overview.md` §2), and through it O-1; review 02 M1 (output writers), M7

`events/core.clj` says that co-effects, handlers and effects all run on the agent thread (`:20-24`). That is not true for anything an effect does later from another thread:
- a future (scan, learn, `:dispatch-later`, the three streaming lifecycle effects);
- the FX thread (the file chooser result and everything it chains into, up to a Save-then-exit).

Those callbacks run the whole pipeline on their own thread, then write the whole state back, at the same time as the agent. p7 shows the result: the engine handles vanish from state and Stop no longer stops the lasers.

This phase sends every callback from another thread through the agent's queue, while follow-ups within a turn stay inline as today. In a running app, only the agent then runs the pipeline. About 1 day.

## 1. Current behaviour (file:line)

**The wiring**
1. `app/create-app` passes `events/event-handler` to `fx/create-app` (`app.clj:90-99`, `:95`). That handler is already wrapped with our co-effects and effects (`events/core.clj:398-460`; `wrap-co-effects` at `:430`, `wrap-effects` at `:435`).
2. cljfx wraps it again: co-effects, effects, then the agent (`cljfx/api.clj:597-601`).
3. The outer `wrap-effects` calls our handler with one argument. The one-argument arity of our inner `wrap-effects` passes **itself** (`dispatch-sync!`) to every effect as the dispatcher (`cljfx/event_handler.clj:22-25`). Our handler returns nil, so the outer layer has nothing left to do.

So an effect's `dispatch` runs the full pipeline on the calling thread: co-effect snapshot (`events/core.clj:53-61`), handler, then `reset-state!` with that result (`:70-74`, `state/core.clj:115-126`).

**Who calls it off the agent**

| Callback | Thread | Where |
|---|---|---|
| `:dispatch-later` target | future (`clojure-agent-send-off-pool-*`) | `events/core.clj:86-88`; used by `modulator.clj:94` |
| `:projectors/scan-complete`, `:projectors/scan-failed` | scan future | `events/core.clj:166-197` (`:191`, `:196`) |
| learn capture (`:on-captured`) | learn future | `events/core.clj:212-215` |
| file chooser result, plus its chain (`:project/save` → `:on-saved` → for example `:file/exit`) | FX thread | `events/core.clj:292-335` (`:329`, `:335`); `:106-117` |
| `:idn/multi-streaming-started`, `:idn/connection-failed`, `-stopped`, `-refreshed` | lifecycle futures | `events/core.clj:343-354,359-364,373-381` |

Writers that don't go through the pipeline at all: the 30 Hz preview timer (`swap-state!` with a function, `frame_service.clj:129-146,832-836`), the multi-engine handle writes (`multi_engine.clj:289,312,371`, Phase 4), and the link setup in `app/start!` (`app.clj:153-160`, review 02 M1).

**Tests depend on synchronous behaviour.** `menu_file_handlers_test.clj:4-6` says the wired `events/event-handler` "applies effects synchronously when called directly". The save-then-continue and pipeline tests rely on that.

**The fallback without an app** (`dispatch!`, `events/core.clj:504-540`) is a hand-written interpreter that applies `:state`, `:dispatch` and two clipboard effects and ignores the rest (review 02 M7).

## 2. Root cause

The wrapping order. Because the already-wrapped handler goes into `fx/create-app`, cljfx's own `wrap-effects`, the one that hands effects the agent's `dispatch-async!` (`cljfx/event_handler.clj:40-48`), never sees an effect.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `fx/create-app` `:co-effects` and `:effects` options (`cljfx/api.clj:555-602`; defaults filled by `cljfx/defaults.clj` `fill-co-effects` and `fill-effects`) | Given the bare pipeline, cljfx passes `dispatch-async!` to every effect |
| The co-effect and effect maps (`events/core.clj:430-460`) | Become named defs and are shared by both wirings |
| `events/event-handler` | Unchanged. It stays the synchronous interpreter for tests and the no-app fallback. |
| `handle-event-pipeline` (`events/core.clj:388-396`) | Passed to `create-app` as is |

## 4. Changes per namespace

- `laser-show.events.core`:
  - NEW public `co-effects` and `effects` maps, moved out of `event-handler` (`:430-460`). `event-handler` = `(-> handle-event-pipeline (fx/wrap-co-effects co-effects) (fx/wrap-effects effects))`, which behaves exactly as today.
  - NEW private `run-inline [event dispatch]` = `(event-handler event dispatch)`. It runs a follow-up event now, in the same turn and on the same thread, and gives that event's effects the same `dispatch`.
  - **Effects that continue an event within the current turn keep doing so inline**, through `run-inline` instead of `dispatch`:
    - `:dispatch` (`:76-80`)
    - `:timing/calculate-bpm` (`:104`)
    - `:project/save` (`:115`, `:117`)
    - `:project/load` (`:125`, `:127`)
    - the two clipboard paste effects (`:141`, `:152`)
    
    Their timing doesn't change. In particular, the list operations' `:dispatch` round trip (`events/handlers/list.clj:160-375`) can't interleave with other events.
  - **Effects that call back later from another thread keep calling `dispatch`**, which in production is now the agent's queue: `:dispatch-later` (`events/core.clj:88`), the scan (`:191`, `:196`), learn (`:215`), the file chooser (`:329`, `:335`) and the streaming lifecycle (`:349`, `:353`, `:364`, `:380`).
  - `dispatch!`'s no-app fallback (`:504-540`) becomes `(event-handler event)`, so a REPL or test dispatch runs every effect as tests do (review 02 M7 step 1).
  - Namespace doc (`:10-24`): which threads run what.
  - Dev mode only, a lost-update detector (review 02 M1 step 1). A middleware keeps the co-effect snapshot on the effects map. `effect-state` logs a WARN with the event type when the context's current value isn't that snapshot, meaning some other writer got in between.
- `laser-show.app/create-app` (`app.clj:90-99`): `:event-handler events/handle-event-pipeline`, `:effects events/effects`, and `:co-effects (assoc events/co-effects :fx/context (constantly nil))`. cljfx adds a `:fx/context` co-effect unless one is given (`cljfx/defaults.clj`, `fill-co-effects`), and events shouldn't carry the context around.

**What changes in behaviour:** only the threads in §7 table. Every result that arrives from a future or the FX thread now waits its turn on the agent. Nothing that happens within a turn moves.

## 5. State placement

No new keys.

## 6. Events and effects

| Event / effect | Exists? | Change |
|---|---|---|
| the `dispatch` argument of every effect | exists | production: the agent's `dispatch-async!` (was `dispatch-sync!` on the calling thread); tests and fallback: unchanged |
| `:dispatch`, `:timing/calculate-bpm`, `:project/save`, `:project/load`, clipboard paste | exist | continue inline through `run-inline`, as today |

## 7. Data examples and invariants

| Callback | Thread today | After |
|---|---|---|
| `:projectors/scan-complete` | scan future | agent |
| learn capture | learn future | agent |
| `:file/open-result`, `:file/save-as-result` and their chains | FX thread | agent |
| `:idn/multi-streaming-*` | lifecycle future | agent |
| `:dispatch-later` target | future | agent |
| a handler's `:dispatch`; `:on-saved` after a save | inline in the same turn | unchanged |

With an app running, the agent's threads (`clojure-agent-send-pool-*`, cljfx's default executor, `cljfx/event_handler.clj:42`) are told apart from futures (`clojure-agent-send-off-pool-*`) by name, which is what the test checks.

**Invariants:**
1. With an app running, `handle-event-pipeline` runs only on the agent.
2. `events/event-handler` called directly stays synchronous (tests unchanged).
3. In dev mode, a lost update logs a warning; the full UI suite logs none from pipeline writers. The preview timer's swaps are atomic, but an event can still roll back one timer tick. That is accepted until O10.

## 8. Tests

- `laser-show.ui-test.reentry-test` (NEW; `with-toolkit` once, `with-app` each):
  - `lifecycle-results-run-on-the-agent-test`: dispatch `:idn/start-multi-streaming` with no projectors. Record the thread that handles `:idn/multi-streaming-started`, using `with-redefs` around `connection/handle` with the original called inside. Its name starts with `clojure-agent-send-pool-`; today it's a `send-off` future thread.
  - `dispatch-later-runs-on-the-agent-test`: a notice's expiry timer delivers `:ui/clear-notice` on the event thread. This replaced the delayed `:chain/update-param` fixture when `:modulator/toggle` became a single state edit.
- `laser-show.events.core-test/fallback-runs-every-effect-test` (NEW): without an app, `dispatch!` of `:file/exit` on a clean project calls the exit fn registered with `events/set-exit-fn!`. Today the fallback drops `:system/exit`.
- `laser-show.events.core-test/lost-update-detector-test`: a write between snapshot and reset is reported.
- Existing: `menu-file-handlers-test` and `menu-system-handlers-test` pass unchanged (invariant 2); the full UI suite passes, including the dialogs and list operations that chain through `:dispatch`.

Run: `clj -M:test-ui:mac-arm:test-ui-headless reentry`, `clj -M:mac-arm:test events.core menu-file menu-system`, then both full suites.

## 9. Out of scope / alternatives considered

- **Making `effect-state` merge instead of overwrite** (O5-C): rejected, see the decision below.
- **Moving the 30 Hz timing writes out of app state**: O10 (`05-phase5-frame-timing.md`).
- **The link setup write in `app/start!`** (`app.clj:153-160`): stays with review 02 M1. It happens once, before any event can race it.

---

## Decisions

> **DECISION O5 — How do background results re-enter the event flow?** *(answered 2026-11-16: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (chosen)** | Give `fx/create-app` the bare pipeline plus our co-effect and effect maps, so cljfx hands every effect the agent's queue. Effects that continue an event within the turn run the follow-up inline, as today. `events/event-handler` stays the synchronous interpreter for tests and the no-app fallback. | One wiring change covers every callback, present and future; it's how cljfx intends effects to work; nothing that happens within a turn moves; tests keep their semantics | Effect authors must know the difference between continuing now (`run-inline`) and calling back later (`dispatch`); the namespace doc and the effect docstrings say which |
> | B | Keep the wiring and make each async effect call `events/dispatch!` (about 8 call sites) | Explicit at each site | Every new async effect must remember to; tests then need an app or the fallback for these paths |
> | C | Keep the wiring and make `effect-state` merge only what the handler changed | Callbacks stay where they are | Diffing whole states on every event; conflicting writes still race; doesn't fix the off-agent handlers themselves |
>
> **Your answer:** A ("most of the O5–O8 stuff is good"; the one exception concerned O6, see `04-phase4-engine-ownership.md`).

---

## 10. As built (2026-11-16)

Committed as `47e7f8d` (the co-effect and effect maps become public defs, no behaviour change) and `a37f690` (the wiring, `run-inline`, the fallback, the detector and the tests), on top of `5052cbe`. §1–9 describe the code before this phase; their citations are against `ab5ad2b`. Nothing in Phase 4 territory changed: `multi_engine.clj`, `streaming_engine.clj`, `events/handlers/connection.clj` and the streaming part of `app/stop!` are untouched; only the `dispatch` their callbacks get is now the agent's.

**Where it is now:** `app/create-app` (`app.clj:90-104`); in `events/core.clj`: `run-inline` (`:97`), the detector (`changed-paths` `:103`, `check-lost-update!` `:118`, `wrap-lost-update-check` `:131`, `effect-state` `:146`), `handle-event-pipeline` (`:469`), `co-effects` (`:484`), `effects` (`:491`), `event-handler` (`:540`), `dispatch!` (`:566`); NEW `state/reset-state-vals!` (`state/core.clj:128`); NEW `frame-service/preview-timer-paths` (`frame_service.clj:109`).

**Differences from the plan above**

| Plan | As built | Why |
|---|---|---|
| Six effects continue inline (§4) | Also `:link/sync-to-downbeat` (`events/core.clj:282`): `link/sync-to-downbeat!` gets `#(run-inline % dispatch)` | It dispatches synchronously inside the effect, like `:timing/calculate-bpm`. The correction is computed from the beat phase *now*, so applying it a turn later would apply a stale correction. The full audit is below |
| "A middleware keeps the co-effect snapshot on the effects map" (§4) | `wrap-lost-update-check`, outermost in `handle-event-pipeline`, puts the snapshot (the event's `:state` co-effect) and the event type into the **metadata of the `:state` value**. `effect-state` strips both before the write, so they never reach app state | An effect consumer only sees its own value, and the order of the effects map isn't guaranteed, so a separate effect key can't be relied on to run before `:state`. Being inside `handle-event-pipeline`, it works in both wirings, and `create-app` still gets `handle-event-pipeline` as is. Outside dev mode the middleware returns the result unchanged |
| "WARN when the context's current value isn't that snapshot" (§4) | `effect-state` writes with NEW `state/reset-state-vals!` and compares the value it actually replaced (an atomic swap, no separate read). If that isn't `identical?` to the snapshot, both are compared with every path in NEW `frame-service/preview-timer-paths` set to `nil`. If they still differ: WARN "Possible lost update: <event type> … another writer changed [<paths>]" with the differing paths up to two keys deep, e.g. `[[:backend :streaming]]`. If only timer paths differ: TRACE | Clarification 1: the 30 Hz timer's writes must not warn. Comparing paths needs no counters in the timer and names the culprit. The paths sit next to the code that writes them (`update-timing-accumulators!`, `update-preview-frame!`): `[:timing :global-clock]`, `[:playback :active-cues]`, `[:playback :playing?]`, `[:playback :active-cell]`, `[:backend :input :mtc]`, `[:backend :streaming :current-frame]`, `[:backend :streaming :frame-stats]`. TRACE rather than DEBUG because `laser-show` logs at DEBUG (`resources/logback.xml`) and a dev app hits this many times a second while dragging. The cost: a non-timer write *only* to those paths (e.g. `:playing?`) between snapshot and write-back isn't reported. After this phase only the timer writes them outside the pipeline |
| `dispatch!`'s fallback = `(event-handler event)` (§4) | As planned. It now returns `nil` (it returned the effects map; no caller used it). Callbacks that come later get the synchronous dispatcher on their own thread, as in tests; before, only the scan got `dispatch!` | The same interpreter as `event-handler`, as planned |
| Probes: "re-run" | The probes' `dispatch!` helper now goes through the production wiring (`fx/wrap-co-effects` + `fx/wrap-effects` with our maps, then `cljfx.event-handler/wrap-async`, waiting with `:fx/sync`). It called `events/event-handler` on the calling thread before | With the old helper the probes would still run callbacks on future threads, i.e. not test what this phase changed |
| Tests (§8) | As named, plus `events.core-test/effects-and-co-effects-are-shared-maps-test`, `/dispatch-continues-inline-test`, `/tap-tempo-bpm-continues-inline-test`, `/dispatch-later-calls-back-through-the-dispatcher-test`, `/lost-update-detector-is-off-outside-dev-mode-test` | They pin the inline / later split directly: `event-handler` is called with a recording dispatcher, and an inline follow-up must not reach it |

**Effect classification** (clarification 2; every entry of `events/effects`)

| Effect | Class | Thread of its callback in production |
|---|---|---|
| `:dispatch` | continue now (`run-inline`) | agent, same turn |
| `:timing/calculate-bpm` | continue now | agent, same turn |
| `:link/sync-to-downbeat` | continue now | agent, same turn |
| `:project/save` (`:on-saved`, `:on-failed`) | continue now | agent, same turn |
| `:project/load` (`:on-loaded`, `:on-failed`) | continue now | agent, same turn |
| `:clipboard/paste-effects`, `:clipboard/paste-projector-effects` | continue now | agent, same turn |
| `:dispatch-later` | call back later (`dispatch`) | future → queued on the agent |
| `:projectors/scan` | call back later | future → agent |
| `:input/await-learn` | call back later | future → agent |
| `:multi-engine/start`, `/stop`, `/refresh` | call back later | future → agent |
| `:fx/show-file-chooser` | call back later | FX thread → agent |
| `:state`, `:system/exit`, `:system/open-url`, `:dev/reload`, `:clipboard/copy-effects` | no follow-up event | – |
| `:context` (added by cljfx's `fill-effects`, production only) | no follow-up event; unused | – |

**Deadlock audit** (clarification 3). An event that an effect dispatches now runs after the current one, so nothing on the agent may wait for it:
- **Save, then quit** (`:file/confirm-save` → `:project/save` → `:on-saved` `:file/exit` → `:system/exit` → `app/exit!` → `app/stop!`): the whole chain after the file chooser runs inline in one agent turn (Save As: the chooser's result is one queued event). `stop!` waits on nothing the agent would have to run: `fx/unmount-renderer` removes the watch and queues the last render with `Platform/runLater`; the joins in `streaming_engine.clj:328` and `gamepad.clj:142` are bounded at 1 s and wait on their own threads. The menu's Quit already ran `exit!` on the agent before this phase.
- **Dialogs**: all dialogs except the file chooser are cljfx stages driven by state, so nothing blocks. The file chooser's `showOpenDialog`/`showSaveDialog` runs a nested loop on the FX thread inside `Platform/runLater` and only queues its result.
- **Promises, derefs, latches**: the learn promise is dereffed in its future, not on the agent (`events/core.clj`, `effect-input-await-learn`). The clipboard reads and writes deref `fx/on-fx-thread` (`clipboard.clj:64,82,116`) on the agent, i.e. the agent waits for the FX thread; the FX thread never waits for the agent, because nothing in `src` dispatches with `:fx/sync` and `dispatch!` doesn't block. The FX thread inside a file chooser still runs `runLater` work. No `CountDownLatch` or `showAndWait` in `src`.

**Observed, not changed** (pre-existing, outside this phase)
- `app/create-app` passes `:opts` and `:error-handler` to `fx/create-app`, which accepts neither (`cljfx/api.clj` `create-app` destructures `:renderer-error-handler`, no `:opts`). So the dev-mode `type->lifecycle` and `default-error-handler` (`app.clj:40-80`) are never used.
- `app/stop!` clears the exit fn but not the dispatch fn, so after `stop!` `dispatch!` still sends to the stopped app's agent rather than to the fallback.

**Tests** (all new)
- `laser-show.events.core-test` (NEW ns; state from `domains/build-initial-state`, no app): `fallback-runs-every-effect-test`, `lost-update-detector-test` (a write to `[:projectors …]` in between → one WARN naming `:timing/set-bpm` and `:projectors`; a timer-only write in between → no WARN; nothing in between → no log; no metadata in app state), `lost-update-detector-is-off-outside-dev-mode-test` and the four inline / later tests named above. Red first: with only `47e7f8d`, 7 failures and 2 errors (the inline tests saw the follow-up at the recording dispatcher, the fallback dropped `:system/exit`, no detector).
- `laser-show.ui-test.reentry-test` (NEW; `with-toolkit` once, `with-app` each): `lifecycle-results-run-on-the-agent-test`, `dispatch-later-runs-on-the-agent-test` (also checks the inline `:keyframe/toggle-enabled` ran first, on the agent, and the delayed update reached state). Red first with `app.clj` from `47e7f8d`: 3 failures, `:idn/multi-streaming-started` and `:chain/update-param` on `clojure-agent-send-off-pool-0`.
- Unchanged and passing: `menu-file-handlers-test`, `menu-system-handlers-test`, `timing-handlers-link-test`, `output-lifecycle-test` (invariant 2). No UI test needed a new `wait-for!`.

**Probes** (`probes/output_probes_test.clj`; before = `5052cbe` with the old `dispatch!` helper, after = `a37f690` with the agent helper, see above)

| Probe | Before | After |
|---|---|---|
| p7 | after both: 1 sender, `{:running? true, :toolbar-connected? true, :engines-in-state 0}`; after Stop: **1 sender still streaming** | **The same.** The lifecycle callback no longer races (it queues behind `:timing/set-bpm`), but the handles are written by `multi-engine/start-engines!` itself (`multi_engine.clj:297`), outside the pipeline, inside the slowed handler's window, so the write-back still drops them. Phase 4 takes the handles out of app state. With `-J-Dlaser-show.dev=true` the detector reports it: `WARN … Possible lost update: :timing/set-bpm wrote back a state snapshot, but another writer changed [[:backend :streaming]] in the meantime` |
| p6 | 5 engines for 3 projectors, senders per channel `{1 1, 2 2, 3 2}`; after Stop 2 still streaming | **4 engines, `{1 1, 2 2, 3 1}`; after Stop 1 still streaming** (the same in 4 runs). The `:idn/multi-streaming-refreshed` write-backs no longer run on the refresh futures; the two refreshes themselves still overlap (Phase 4) |
| p4, p5 | 2 engines, 1 sender left after Stop; 1 sender after Stop with the button offering "Stream" | the same (Phase 4) |
| p1–p3, p8–p13 | as in 02 §10 | the same findings (the output path is untouched). p12 43.0 fps and p13 43.2 vs 59.8 fps under a load average of ~19 (before: 53.5, and 52.6 vs 59.9); p2 and p3 frame counts vary with load in the same way |

**Live check** (headless dev REPL, `start-test!` plus the 30 Hz preview timer): 300 `:timing/set-bpm` via `dispatch!` (handler slowed 5 ms, like dragging the BPM) ran on `clojure-agent-send-pool-*` threads only; 66 of them had a timer tick between snapshot and write-back; 0 lost-update WARNs.

**Verification, 2026-11-16, macOS arm64, JDK 25.0.5, headless**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test events.core menu-file menu-system timing-handlers-link input-test output-lifecycle` | 65 tests, 233 assertions, 0 failures |
| `clj -M:test-ui:mac-arm:test-ui-headless reentry` | 2 tests, 5 assertions, 0 failures |
| `clj -M:mac-arm:test` (full unit suite) | 775 tests, 31,160 assertions, 0 failures (768 / 31,139 before) |
| `clj -M:test-ui:mac-arm:test-ui-headless` (full UI suite) | 49 tests, 184 assertions, 0 failures (47 / 179 before) |
| The same UI suite with `-J-Dlaser-show.dev=true` (invariant 3) | 49 tests, 184 assertions, 0 failures, 0 lost-update WARNs |
| `clj -M:kondo --lint src test --parallel --fail-level error` | 0 errors, 21 warnings (22 before), none in a touched file |

Not run locally: the Linux and Windows suites (CI). Not testable headless: the native file chooser; see the manual checks in the hand-off.
