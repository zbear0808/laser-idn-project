# Phase 3 — Background results come back through the event agent

**Written:** 2026-11-16   **Written for commit:** `70e049e` (`master`)
**Status:** Proposed. Needs O5   **Last verified against:** `70e049e`
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

Writers that don't go through the pipeline at all: the 30 Hz preview timer (`swap-state!` with a function, `frame_service.clj:128-145,827-831`), the multi-engine handle writes (`multi_engine.clj:289,312,371`, Phase 4), and the link setup in `app/start!` (`app.clj:153-160`, review 02 M1).

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
  - `dispatch-later-runs-on-the-agent-test`: `:modulator/toggle` on an effect whose keyframes are on returns `:dispatch` plus `:dispatch-later` (`modulator.clj:85-100`). The delayed `:chain/update-param` is handled on an agent thread; today it runs on a future thread.
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

> **DECISION O5 — How do background results re-enter the event flow?** *(open · needed before Phase 3 · recommended: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Give `fx/create-app` the bare pipeline plus our co-effect and effect maps, so cljfx hands every effect the agent's queue. Effects that continue an event within the turn run the follow-up inline, as today. `events/event-handler` stays the synchronous interpreter for tests and the no-app fallback. | One wiring change covers every callback, present and future; it's how cljfx intends effects to work; nothing that happens within a turn moves; tests keep their semantics | Effect authors must know the difference between continuing now (`run-inline`) and calling back later (`dispatch`); the namespace doc and the effect docstrings say which |
> | B | Keep the wiring and make each async effect call `events/dispatch!` (about 8 call sites) | Explicit at each site | Every new async effect must remember to; tests then need an app or the fallback for these paths |
> | C | Keep the wiring and make `effect-state` merge only what the handler changed | Callbacks stay where they are | Diffing whole states on every event; conflicting writes still race; doesn't fix the off-agent handlers themselves |
>
> **Your answer:** _pending_
