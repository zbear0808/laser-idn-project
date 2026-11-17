# Phase 4 — One owner for the engines; status the UI can trust

**Written:** 2026-11-16   **Written for commit:** `70e049e` (`master`)
**Status:** Done 2026-11-16: `a8b2fd2` (engine `stop-all!`), `5dce132` (output runtime, wiring, status), `20aeed9` (projectors tab), probes and docs after them. O6–O8 answered 2026-11-16 (A each; each projector keeps its own streaming thread). See §10 for what was built and where it differs   **Last verified against:** `354b798` (citations in §1–9 re-checked 2026-11-16 after Phases 2 and 3; §10 cites `20aeed9`)
**Supersedes / Superseded by:** none   **Related findings:** O-1, O-10 to O-13 (`00-overview.md` §2); review 04 findings 12, 13, 15, 16, MR4, MR5; 02 #23, M8

Probes p4–p7 show the operator pressing Stop while a laser keeps drawing. Today the streaming engines (threads, sockets, atoms) are stored in app state. Three futures write them there, with no ordering between them. Anything that overwrites app state, or any two lifecycle commands that overlap, can leave an engine running that nothing holds a reference to.

This phase moves the engines into one runtime registry owned by one output thread:
- lifecycle commands (start, stop, sync) run there, one at a time;
- each projector keeps its own streaming thread, as today. The output thread never computes or sends a frame, so one projector's slow frames or unreachable host can't delay another's;
- the UI gets pure-data status;
- exit drains the registry.

About 2–3 days.

## 1. Current behaviour (file:line)

| What | Where | Today |
|---|---|---|
| Who asks | the toolbar button picks start or stop from the render-time `connected?` (`toolbar.clj:220,235-243`) | No guard against a second click before the re-render (p4) |
| Start, stop, refresh | `events/core.clj:420-436`, `:437-448`, `:449-468` | Each runs in its own `future`, with no queue and no lock; only `Exception` is caught |
| Engine handles | `multi_engine.clj:279-304` (blind write at `:297`), `:305-326` (read at `:308`, write at `:320`, after stopping each engine in turn), `:327-387` (read at `:337`, write at `:379`) | Stored at `[:backend :streaming :multi-engine-state]`. `connection.clj:64-70` sets it to nil after a stop. |
| Running flags | `[:backend :streaming :running?]` (`connection.clj:40,54`); `[… :multi-engine-state :running?]` (`multi_engine.clj:299,322,381`); `[:backend :idn :connected?]` set optimistically (`connection.clj:42`) | Three flags that drift apart (p5: the toolbar says stopped while an engine streams) |
| Refresh triggers | returned by `projector.clj:213,231,294`, `menu.clj:60,94`, `handlers/history.clj:92` (via `hardware-changed?`, `:64-71`) | `update-settings` (host, name, `projector.clj:268-281`) and `add-manual` (`:238-253`) don't refresh. The Host field fires on every keystroke (`projectors.clj:318-324`). The Port field has no handler (`:325-331`). A blank host throws inside the handler (`projector.clj:274-279`). |
| Refresh diff | `multi_engine.clj:345-351,375-376` | Compares projector ids only, so an engine keeps the host, port, service and bit depth it was created with |
| Status in the UI | `toolbar.clj:221-233` ("Streaming to N" counts *enabled projectors*); `subs.clj:368-386`; the projector dot reads `[:projectors id :status :connected?]` (`projectors.clj:224`), set once to false at creation (`projector.clj:103`) | Nothing reports what is actually streaming. Engine stats (`streaming_engine.clj:255-265`) and `get-stats` (`:357-360`) have no reader. |
| Exit | `app.clj:195-203` (`stop-streaming!`), `:214-233`, `:235-242` | Stops only the engines found in state. No shutdown hook. In dev mode the JVM stays up after an exit (`93c638a`), so orphans keep streaming in the REPL. |
| New / Open while streaming | `menu.clj:57-60,89-94`; the new document has no projectors | A refresh closes every engine; the toolbar still says "Streaming to 0 projector(s)" |

## 2. Root cause

Live objects are kept in app state. Several threads with no common order write that state, and the state can be overwritten whole (O-9, fixed by Phase 3). There is no single owner that could make "start", "stop" and "refresh" mean the same thing however they overlap.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `create-engine-for-projector` (`multi_engine.clj:225-257`), the provider (`projector-frame` and `create-projector-frame-provider`, `:142-224`) | Unchanged; the registry calls them |
| `engine/start!`, `stop!`, `get-stats` (`streaming_engine.clj:287-360`) | Called only from the output thread (and from `drain!`). `start!` still gives each engine its own thread. `stop!` becomes `(stop-all! [engine])` (§4) |
| `hardware-changed?` (`handlers/history.clj:64-71`) | The idea moves into a middleware: compare per-projector fingerprints before and after every event |
| Phase 3: callbacks re-enter through the agent | `:output/status` is dispatched with `events/dispatch!` from the output thread |
| Phase 2: `output-errors/snapshot` | Per-projector error text in the status |
| `app/stop!`, `exit!` (`app.clj:214-242`) | Call `drain!` instead of `stop-streaming!` |

## 4. Changes per namespace

- `laser-show.backend.output-runtime` (NEW): owns every engine. It is kept apart from `multi-engine`, which keeps frame production and engine creation.
  - `!registry` (runtime atom): `{projector-id {:engine e :fingerprint f :started-ms t}}`. An engine goes in before its thread starts and comes out only after its close pair was sent.
  - One single-thread executor, `output-lifecycle`. `submit-sync! [projectors]` and `submit-stop! []` queue commands. A queued sync is replaced by a newer one, so only the latest projector snapshot is applied. A stop drops any queued sync.
  - `sync!` (on the output thread): computes the desired fingerprints from the snapshot. For each projector, the engine is stopped if it's no longer wanted or its fingerprint changed, and started if it's new or changed. An engine that fails to start is closed and reported, never kept.
  - Engines keep one streaming thread each (`engine/start!`, unchanged). The output thread only starts, stops and replaces them; frames are computed and sent on the engines' own threads.
  - Stopping several engines (a stop, `drain!`, or a sync that removes or replaces several) goes through `engine/stop-all!`, so every projector goes dark within about one frame of the others instead of one after another.
  - `fingerprint [projector]` = `(select-keys p [:host :port :service-id :output-config :scan-rate])`, only for enabled projectors with a non-blank host. Zone groups and names are read live by the provider, so changing them doesn't restart anything.
  - `drain! [timeout-ms]`, synchronous: stops the executor, waits for the running command up to the timeout, then stops every engine in the registry on the calling thread. It is idempotent.
  - The status publisher (O8-A): while the registry isn't empty, 4 times a second it builds the status (§5) from `get-stats` and `output-errors/snapshot`. It dispatches `:output/status` only when the status changed.
- `laser-show.backend.streaming-engine`: NEW `stop-all! [engines]`. It clears every engine's `running?` flag first, then joins each thread (up to 1 s each, as `stop!` does today, `streaming_engine.clj:327-328`) and sends its close pair. `stop!` becomes `(stop-all! [engine])`. Today `stop-engines!` stops one engine after another (`multi_engine.clj:305-326`), so with N projectors the last one goes dark up to N frames after the first.
- `laser-show.events.core` (`:420-468`): the three `:multi-engine/*` effects become `submit-sync!` with the projector snapshot carried in the effect value (`{:projectors …}`), or `submit-stop!`. No futures. The data is read by the handler, not by a worker (`docs/CLJFX_STATE_MANAGEMENT.md` §Best Practices 1).
- `laser-show.events.handlers.connection` (`:30-76`):
  - `:idn/start-multi-streaming` sets `[:backend :streaming :running?]` (the operator's intent) and returns a sync with `(:projectors state)`. Pressing it twice gives two identical syncs, and the second changes nothing.
  - `:idn/stop-multi-streaming` clears the flag and returns the stop.
  - NEW `:output/status` writes `[:backend :streaming :status]`.
  - `:idn/multi-streaming-started`, `-stopped`, `-refreshed` and `:idn/connection-failed` are removed, along with `[:backend :idn :connected? :connecting? :error]`.
- `laser-show.events.handlers.output-sync` (NEW middleware, added to `handle-event-pipeline`, `events/core.clj:469-483`, inside Phase 3's `wrap-lost-update-check`, which stays outermost): after any event, when streaming is on and the fingerprints of `(:projectors old)` and `(:projectors new)` differ, it adds `:multi-engine/refresh {:projectors …}`. The six hand-placed refreshes (`projector.clj:213,231,294`, `menu.clj:60,94`, `handlers/history.clj:92`) go away, and host, port, add-manual and undo are covered too.
- `laser-show.events.handlers.projector`: `update-settings` (`:268-281`) ignores a blank host instead of throwing. `make-projector-config` (`:78-104`) stops writing `:status`; old project files that still have it are harmless.
- `laser-show.views.tabs.projectors`: the Host and Port fields (`:318-331`) commit on Enter or focus loss (O7-A). A half-typed address never reaches an engine: Java resolves `192.168.1` (on its way to `192.168.1.50`) to 192.168.0.1, often the router (checked with `InetAddress/getByName` on JDK 25). The dot (`:220-226`) reads the new per-projector status.
- `laser-show.views.toolbar` (`:219-243`) and `subs.clj` (`connection-status`, `:368-386`): the toolbar shows "Streaming to 2 of 3" from the status, plus "Starting…" while a sync is queued and an error count. The button is chosen from the operator's intent (`running?`), not from what has finished starting.
- `laser-show.app`: `stop!` calls `output-runtime/drain! 2000` (replacing `stop-streaming!`, `:195-203`). `start!` registers a JVM shutdown hook that drains, which covers a kill or `System/exit` from anywhere.
- `laser-show.state.domains` (`:399-410`): `:multi-engine-state`, `:engines`, `:connected-targets` and the `:idn` subtree go; `:status` is added.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `output-runtime/!registry` NEW | runtime atom | the output thread; `drain!` | No / no |
| `[:backend :streaming :running?]` | app state, backend (not saved) | `:idn/start-…`, `:idn/stop-multi-streaming` | No / no |
| `[:backend :streaming :status]` NEW: `{:engines 2 :wanted 3 :pending? false :projectors {pid {:state :streaming|:starting|:error :fps 59.8 :error "No route to host"}}}` | app state, backend | `:output/status` (at most 4 Hz) | No / no |
| `[:backend :streaming :multi-engine-state]`, `[:backend :idn]` | removed | – | – |

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:idn/start-multi-streaming`, `:idn/stop-multi-streaming` | changed | `toolbar.clj:241-243` | yes |
| `:output/status` | NEW | the output thread, via `events/dispatch!` | yes |
| `:multi-engine/start`, `:multi-engine/refresh` (value `{:projectors …}`), `:multi-engine/stop` | changed: queue on the output thread | connection handlers; the output-sync middleware | effect |
| `:idn/multi-streaming-started`, `-stopped`, `-refreshed`, `:idn/connection-failed` | removed | – | – |

## 7. Data examples and invariants

The races from the probes, replayed against the new design:

| Sequence | Today | After |
|---|---|---|
| Stream, Stream (p4) | 2 senders, 1 left after Stop | the second sync finds nothing to change: 1 sender; Stop closes it |
| Stream, Stop during start (p5) | 1 sender, the UI says stopped | the stop runs after the start on the same thread: 0 senders, close pair sent |
| Stop, Stream during stop | handles wiped, the next Stop does nothing | stop, then start: 1 sender per projector |
| Streaming A; enable B and C quickly (p6) | B and C streamed twice | the syncs coalesce: one engine each for A, B and C |
| An event overlaps a start (p7) | handles lost, Stop does nothing | nothing to lose: no handles in app state |
| A's host edited while streaming | old engine keeps the old host | on commit, A's fingerprint changes: close pair to the old host, new engine to the new one |
| File > New while streaming | all closed, "Streaming to 0 projector(s)" | all closed, "Streaming to 0 of 0 (no enabled projectors)"; still on, so enabling a projector starts it |
| Exit while a sync runs | the running engines are missed (dev: orphaned) | `drain!` waits up to 2 s, then closes every registry engine; the shutdown hook covers a kill |
| Stop with 3 projectors streaming | each engine is stopped and joined in turn: dark one after another | all three are signalled first: dark within about one frame of each other |
| A's frames take 250 ms each (heavy content, or a slow host lookup) | B streams at full rate on its own thread | unchanged: B streams at full rate on its own thread |

**Invariants:**
1. At most one engine per projector id exists, ever.
2. Every engine ever started is in the registry until its close pair has been sent.
3. After a stop completes, the fake DAC receives nothing.
4. App state never holds a `Thread`, socket or engine.
5. Status written to app state is at most 250 ms old while streaming and is never written more than 4 times a second.
6. Each engine computes and sends its frames on its own thread. The output thread never does either.
7. Stopping several engines signals all of them before waiting on any one.

## 8. Tests

Fake DAC, in `laser-show.backend.output-lifecycle-test` (Phase 1's namespace):
- `double-start-gives-one-sender-per-projector-test` (p4)
- `stop-during-start-leaves-nothing-streaming-test` (p5, with `start!` slowed as in the probe)
- `start-during-stop-gives-one-engine-set-test`
- `quick-enables-start-each-projector-once-test` (p6)
- `concurrent-event-cannot-lose-engines-test` (p7)
- `host-edit-restarts-the-engine-test`
- `zone-group-edit-restarts-nothing-test`
- `failed-start-is-closed-and-reported-test`
- `drain-closes-every-engine-test`
- `shutdown-hook-drains-test` (calls the hook's function directly)
- `status-reports-engines-and-errors-test`
- `slow-projector-does-not-delay-others-test` (invariant 6): two projectors; one provider sleeps 250 ms per frame. Over 1 s the other projector delivers at least 15 frames and the slow one at most 5. The bounds are loose on purpose: with one sending thread for both, each would get at most 5.
- `stop-all-signals-every-engine-first-test` (invariant 7): three engines whose providers each take 200 ms. `stop-all!` returns in under 450 ms (one after another takes about 600 ms), and every close pair arrives.

Middleware, `laser-show.events.output-sync-test`:
- `fingerprint-change-requests-a-sync-test`
- `name-or-zone-change-requests-nothing-test`
- `undo-of-a-host-edit-requests-a-sync-test`

UI, `laser-show.ui-test.streaming-status-test` (NEW):
- `toolbar-shows-streaming-n-of-m-test`
- `projector-dot-shows-status-test`
- `host-field-commits-on-enter-test`

Run: `clj -M:mac-arm:test output-lifecycle output-sync`, `clj -M:test-ui:mac-arm:test-ui-headless streaming-status`, then both full suites and the probes (p4–p7 should report 0 senders after Stop).

## 9. Out of scope / alternatives considered

- **One IDN link per DAC** (04 LE2): one socket and sequence per DAC, with projectors as channels. The registry makes it possible later, but it needs a multi-head DAC to verify.
- **One sending thread for all projectors:** not chosen (O6 answer). Every projector would wait for the others' frames to be computed and sent.
- **Restarting an engine whose thread died**: after Phase 2 that doesn't happen. A dead thread would show as `:error` in the status.
- **Keeping the toolbar button on "Stop" while the output thread is busy:** the button follows the operator's intent, and the status line says what is actually running.

---

## Decisions

> **DECISION O6 — Who owns the engines and orders their lifecycle?** *(answered 2026-11-16: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (chosen)** | A runtime registry plus one output thread that runs every start, stop and sync in order. Each engine keeps its own streaming thread. Newer syncs replace queued ones. App state gets pure-data status only. | Every race in §7 is gone by construction; Stop can't miss an engine; the agent never blocks on a socket or thread join | A new namespace and thread; status arrives a moment after the click |
> | B | The same registry, but the commands run under a `locking` monitor on the thread that asks (the agent) | No new thread | Stopping N engines blocks the agent for up to N × 1 s (`streaming_engine.clj:327-328`), freezing input and the UI's events meanwhile |
> | C | Keep the handles in app state, guarded by a lock | Smallest diff | Doesn't fix p7-style losses from whole-state writes; keeps live objects in the context, against `docs/CLJFX_STATE_MANAGEMENT.md` §Best Practices 5 |
>
> **Your answer:** "I still want separate streaming engines on different threads. I'm not sure how else we could ensure low latency frames being sent to different devices." A keeps that: each projector computes and sends its frames on its own thread, as today. The one output thread only runs start, stop and sync commands. §4, invariants 6–7 and two new tests (§8) make this explicit, and stopping now signals every engine at once.

> **DECISION O7 — What does a refresh restart?** *(answered 2026-11-16: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (chosen)** | A middleware compares per-projector fingerprints (host, port, service, output config, scan rate) after every event, and restarts exactly the projectors whose fingerprint changed. Host and Port commit on Enter or focus loss. | Every edit path is covered (settings, Add Manual, undo, Open), with nothing to remember; zone and name edits restart nothing | Typing a host no longer applies live; it applies on Enter |
> | B | Keep the id-only diff, plus a "Restart output" button | Small | Edits are silently ignored until someone remembers the button |
> | C | Restart every engine on any projector change | Simple | A blink on every projector for every name or zone edit while streaming |
>
> **Your answer:** A ("most of the O5–O8 stuff is good").

> **DECISION O8 — How is link health shown?** *(answered 2026-11-16: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (chosen)** | Per projector, from engine stats and the error registry, published at most 4 times a second: streaming, starting or error, the actual fps, the last error. Shown as the projector dot and the toolbar line. No network probing. | No hardware needed; catches dead threads, send errors and start failures | A DAC that is switched off but still routable looks fine, because UDP sends succeed |
> | B | A, plus an IDN-Hello ping every second per DAC on the engine's socket (`hello.clj:109` `send-ping` exists, unused), shown as reachable and round-trip time | Detects a DAC that is off or unplugged | Needs a receive path on the engine socket and hardware to confirm DAC behaviour (no IDN DAC available as of 2026-11-16); the fake DAC must answer pings in tests |
> | C | No health; only fix the toolbar count | Smallest | The operator still can't tell which projector is dark and why |
>
> **Your answer:** A ("most of the O5–O8 stuff is good").

---

## 10. As built (2026-11-16)

Committed as `a8b2fd2` (`streaming_engine.clj` only), `5dce132` (the runtime, the effects, the handlers, the middleware, `app.clj`, `domains.clj`, the toolbar and the moved tests) and `20aeed9` (the projectors tab and the UI tests), on top of `3dd0e36`. §1–9 describe the code before this phase; their citations are against `354b798`.

**Where it is now** (at `20aeed9`)
- NEW `laser-show.backend.output-runtime` (`backend/output_runtime.clj`): `fingerprint` `:35`, `wanted` `:41`, `!registry` `:55`, `registry` `:78`, `stop-engines!` `:90`, `start-engine!` `:116`, `sync!` `:131`, `run-next!` `:152`, `status` `:182`, `publish-status!` `:204`, `submit-sync!` `:261`, `submit-stop!` `:267`, `open!` `:275`, `drain!` `:283`, `install-shutdown-hook!` `:306`.
- `streaming_engine.clj`: `thread-name` `:296`, `start!` `:302`, `send-close-pair!` `:345`, `close!` `:365`, NEW `stop-all!` `:376`, `stop!` `:391` (= `(stop-all! [engine])`).
- NEW `laser-show.events.handlers.output-sync` (`events/handlers/output_sync.clj`): `sync-needed?` `:13`, `wrap-output-sync` `:25`, in `handle-event-pipeline` between `wrap-dirty` and `wrap-lost-update-check` (`events/core.clj:461`).
- `events/core.clj`: `effect-output-sync` `:424`, NEW `effect-output-write-status` `:431`, `effect-output-stop` `:437`. `connection.clj`: `:idn/start-multi-streaming` `:15`, `:idn/stop-multi-streaming` `:22`, NEW `:output/status` `:28`; the `"output"` namespace is routed to it (`events/handlers.clj:50`).
- `app.clj`: `start!` opens the runtime and installs the hook (`:149-150`); `stop!` drains (`:204`) and clears the dispatch fn (`:234`); `start-test!` opens (`:263`).
- `subs.clj`: NEW `streaming-status` `:368`, NEW `projector-output-status` `:374`, `connection-status` `:380`. `toolbar.clj`: `connection-indicator` `:202`, `connection-status` `:215`. `views/tabs/projectors.clj`: NEW `commit-field` `:48`, NEW `parse-port` `:76`, NEW `output-status-text` `:261`, `projector-list-item` `:270`. `handlers/projector.clj`: NEW `valid-port?` `:256`, `update-settings` `:261`.
- Removed: `multi-engine/start-engines!`, `stop-engines!`, `refresh-engines!`, `streaming-running?`; `handlers/history.clj` `hardware-changed?`; `extractors/idn-data`; the events `:idn/multi-streaming-started`, `-stopped`, `-refreshed`, `:idn/connection-failed`; the state paths `[:backend :idn]`, `[:backend :streaming :multi-engine-state :engines :connected-targets :engine-count]`; `:status` in new projector configs. `multi-engine/create-engines` stays (read-only, used by `multi_engine_test`).

**Differences from the plan above**

| Plan | As built | Why |
|---|---|---|
| `:output/status` is dispatched with `events/dispatch!` (§3, §6) | It is dispatched with the `dispatch` the lifecycle effect was given, stored by `submit!` (`output_runtime.clj:246`) | `events.core` requires the runtime for its effects, so the runtime can't require `events.core`. Since Phase 3 that `dispatch` *is* the agent's queue with the app running, the same thing `events/dispatch!` does; in tests without an app it is the synchronous handler. After `app/stop!` the runtime is closed, so no stale dispatch is used |
| `:output/status` writes `[:backend :streaming :status]` (§4) | Its handler returns NEW `:output/write-status`, an effect that updates only that path (`state/swap-state! assoc-in`) | Without an app (unit tests, clarification 2) the event runs on the status thread while the test thread also runs events. A whole-state write-back would not only lose the status (the next tick redoes it) but could also undo the other thread's writes, e.g. Stop's `running? false`. Writing one path loses neither. With the app it runs on the agent like every event. `output-sync-test/output-status-is-not-an-edit-test` pins: no undo entry, `:project` untouched, not dirty |
| The publisher runs "while the registry isn't empty" (§4) | The status thread (`output-status`) ticks every 250 ms while the runtime is open and dispatches only when `status` differs from what app state holds. `status` is `nil` when stopped with nothing queued or running, so an idle tick is a read and a compare | One rule covers the final "stopped" publish after the registry empties and re-sends a status write that got lost (clarification 2). Writes are ≥ 250 ms apart (`scheduleWithFixedDelay`), so at most 4 a second (invariant 5) |
| `stop-all!` joins "up to 1 s each" (§4) | All engines share one 1 s join deadline (`STOP_JOIN_MS`). Each engine is claimed with a compare-and-set on `running?`, so two concurrent `stop-all!` calls (drain and the lifecycle thread) never close an engine twice, and `stop-engines!` removes from the registry only the engines its own call closed | A drain with N stuck engines stays bounded at about 1 s instead of N s; invariant 2 holds even when drain and the lifecycle thread race |
| (clarification 1) never block stop on DNS | `send-close-pair!` uses the cached address or NEW `:last-address` (the last one resolved; the loop clears `:target-address` after a send error to re-resolve), and skips the close pair when the host was never resolved: nothing reached it. Stop never looks a host up | `stop-does-not-wait-for-a-host-lookup-test`: the engine thread is blocked in a lookup that never returns; `stop-all!` still returns, closes the socket and sends nothing |
| (clarification 3) nothing starts after `app/stop!` | The runtime is `:idle` → `:open` on the first submit → `:closed` by `drain!` → `:idle` again by `open!`. `app/start!` and `start-test!` call `open!`; `app/stop!` drains and clears the dispatch fn, so `dispatch!` falls back to the synchronous handler and its submits are dropped with a WARN ("Output runtime is closed (app stopped); dropping :sync"). While `drain!` runs, `register!` refuses new engines, and an engine whose `start!` returned after the drain began is stopped by the lifecycle thread itself | Unit tests that stream without an app still work (the runtime opens on demand); `app-stop-closes-engines-test` (second half), `drain-closes-every-engine-test` and `drain-during-a-sync-closes-what-started-test` pin it |
| Status shape (§5) | As planned, plus: `nil` when stopped and idle; a wanted projector not yet in the registry is `:starting`, or `:error` with the start failure; a dead engine thread is `:error` "The streaming thread ended"; the error is the first of `[:engine id]`, `[:provider id]`, `[:projector-effects id]` in `output-errors`; `:pending?` also covers the command that is running; `:fps` is the engine's `:actual-fps`, now smoothed over about 10 frames (an EMA in the loop) and rounded to 0.1 | One frame's interval has millisecond resolution, so the raw value jumps between 41.7 and 62.5 on this machine |
| Toolbar: "Streaming to 2 of 3", "Starting…", an error count (§4) | Texts: "Not streaming"; "Starting…" (on, and no status yet or a command pending); "Stopping…" (off, engines still in the status); "Streaming to N of M" with N = projectors in `:streaming`, plus " (no enabled projectors)" when M = 0 and " · k error(s)"; a tooltip lists "<name>: <error>". The indicator is green streaming, amber starting / stopping / none enabled, red stopped or with errors. Ids `#stream-btn`, `#streaming-status-label`, `#streaming-indicator` | The plan named the parts, not the strings |
| The dot reads the per-projector status (§4) | `#projector-dot-<id>`: green streaming, blue starting, red error, grey when not streaming (it was always red before). A NEW line under the host, `#projector-status-<id>`, says "Streaming · 59.8 fps", "Starting…" or "Error: <reason>" (in red) | The reason has to be visible somewhere other than a tooltip (manual check "an unresolvable host shows an error with a reason") |
| Host and Port commit on Enter or focus loss (O7-A; clarification 6) | NEW `commit-field`: the typed text is applied on Enter (`:on-action`) or when the field loses focus (a listener added in `ext-on-instance-lifecycle`; the current commit fn rides on `:user-data`, so it always has the latest render's projector id). Host is trimmed; a blank host is not dispatched and the field shows the stored host again. Port must parse (`parse-long` after trim) to an integer 1–65535; anything else is not dispatched and the field shows the stored port again. The handler also ignores a nil or blank `:host` and a `:port` that isn't an integer 1–65535, applying the other updates (`handle-projectors-update-settings-validates-port-test`) | The handler no longer throws on a blank host (§4); validating in both places keeps a bad value out of state whoever dispatches |
| §8 `slow-projector-does-not-delay-others-test`, `stop-all-signals-every-engine-first-test` with time bounds (clarification 8) | Both §8 tests are structural and in the unit suite. The first: the slow provider blocks on a promise until the fast projector has delivered 15 frames (impossible with one sending thread); providers and sends are recorded and ran only on `idn-streaming-fast` / `idn-streaming-slow`, never on `output-lifecycle`. The second: watches on each `running?` and a wrapped `close!` record the order: all three flags clear before the first close; every close pair arrives; Stop through the runtime makes one `stop-all!` call with all 3 engines. The plan's numeric bounds are `^:timing` companions (O1): `slow-projector-rate-test` (fast ≥ 15 and slow ≤ 5 frames in 1 s) and `stop-all-takes-about-one-frame-test` (< 450 ms) | The machine ran at a load average of 12–48 during this work; the structural versions don't depend on it |
| `fingerprint` for enabled projectors (§4) | `wanted` keeps the old default `(:enabled? p true)`, as `create-engines` had | Unchanged behaviour for projectors without the key. Observed, not changed: the list's checkbox shows such a projector unchecked |
| Engine thread names (optional) | `idn-streaming-<projector id>`; also `output-lifecycle`, `output-status`, `output-drain-on-shutdown` | Visible in thread dumps; the invariant 6 test asserts them |
| Six hand-placed refreshes go (§4) | Removed, and `hardware-changed?` with them. It also compared `:virtual-projectors` and `[:chains :projector-effects]`; neither is in the fingerprint, since the provider reads both live | As planned |

**Tests**
- `laser-show.backend.output-lifecycle-test` (fake DAC; the `stopping-every-engine` fixture opens the runtime, records every engine, then drains the runtime and stops every recorded engine): kept `start-streams-every-enabled-projector-test`, `stop-closes-every-engine-test`, `two-services-two-channels-test`; `app-stop-closes-engines-test` now reads the registry and also checks that a Stream dispatched after `app/stop!` starts nothing. NEW per §8: `double-start-gives-one-sender-per-projector-test`, `stop-during-start-leaves-nothing-streaming-test`, `start-during-stop-gives-one-engine-set-test`, `quick-enables-start-each-projector-once-test`, `concurrent-event-cannot-lose-engines-test` (also: no `Thread`, socket or engine anywhere in app state), `host-edit-restarts-the-engine-test` (a port edit moves the engine to a second fake DAC with a close pair to the first; a host edit `127.0.0.1` → `localhost` closes and replaces it; the other projector keeps its engine), `zone-group-edit-restarts-nothing-test`, `failed-start-is-closed-and-reported-test`, `drain-closes-every-engine-test`, `shutdown-hook-drains-test` (runs the registered hook thread's `run` directly), `status-reports-engines-and-errors-test` (via the publisher into app state; at most 5 status writes in 1 s; `nil` after Stop), `slow-projector-does-not-delay-others-test`, `stop-all-signals-every-engine-first-test`. NEW beyond §8: `stop-does-not-wait-for-a-host-lookup-test`, `drain-during-a-sync-closes-what-started-test`, and the two `^:timing` companions above.
- NEW `laser-show.events.output-sync-test`: `fingerprint-change-requests-a-sync-test` (host, port, output config, enable, disable, enable-all-by-IP, Add Manual, File > New; none of them while not streaming), `service-enabled-uses-the-deterministic-id-test`, `name-or-zone-change-requests-nothing-test` (also a disabled projector's host, a blank host, a port out of range, enable-all with no match, a BPM change), `undo-of-a-host-edit-requests-a-sync-test` (and redo; undoing a rename requests nothing), `output-status-is-not-an-edit-test`.
- Moved coverage (clarification 4): the refresh tests in `projector-handlers-test` (toggle-enabled, set-service-enabled, enable-all-by-ip, no match) now run the handlers through `events/handle-event-pipeline` and check the refresh carries the new projectors; the two update-settings "blank/nil host throws" tests became "is ignored" tests plus NEW `handle-projectors-update-settings-validates-port-test`; `menu-file-handlers-test` (New, project loaded, clean New) checks the refresh through the pipeline and that `menu/handle` itself returns only `:state`; `history-handlers-test/undo-emits-engine-refresh-when-projectors-change-test` streams and checks the restored snapshot, plus no refresh while not streaming; `state.history-test` uses `[:backend :streaming :running?]` as its runtime sample; `events.core-test` lists `:output/write-status`.
- UI: `laser-show.ui-test.reentry-test/lifecycle-results-run-on-the-agent-test` now waits for `:output/status` (published from the status thread with nothing to stream, then `nil` after Stop) and checks every connection event, `:output/status` included, ran on `clojure-agent-send-pool-*`. NEW `laser-show.ui-test.streaming-status-test` (fake DAC; `target-address!` redefined to fail for `unresolvable.test`): `toolbar-shows-streaming-n-of-m-test`, `toolbar-counts-errors-test` ("Streaming to 1 of 2 · 1 error"), `projector-dot-shows-status-test` (green, red with "UnknownHostException: unresolvable.test" in the status line, grey again after Stop), `host-field-commits-on-enter-test` (typing applies nothing, Enter applies, a blank host and port `abc` are put back, port 7300 applies on focus loss).
- Red first: the races are the probes below (p4–p7 before). The new lifecycle tests use the runtime's API, so they don't load at `3dd0e36`.

**Probes** (`probes/output_probes_test.clj`; `streaming-flags` now prints the intent, the published status's engine count and the registry size. Before = `3dd0e36`, after = `20aeed9`)

| Probe | Before | After |
|---|---|---|
| p4 | 2 engines; after Stop **1 sender still streaming** | 1 engine, 1 sender; after Stop **0**, `{:running? false, :status-engines nil, :engines-in-registry 0}` |
| p5 | after Stop **1 sender**, the button offering "Stream" | after Stop **0**, registry empty |
| p6 | 4 engines for 3 projectors, `{1 1, 2 2, 3 1}`; after Stop **1** | 3 engines, `{1 1, 2 1, 3 1}`, `{:running? true, :status-engines 3, :engines-in-registry 3}`; after Stop **0** |
| p7 | handles dropped from state; after Stop **1**. With `-J-Dlaser-show.dev=true`: `WARN … Possible lost update: :timing/set-bpm … changed [[:backend :streaming]]` | after both: 1 sender, registry 1, status 1; after Stop **0**. With `-J-Dlaser-show.dev=true`: **no lost-update WARN** (0 in the whole probe run) |
| p1–p3, p8–p13 | as in 03 §10 | the same findings; p12 43.5 fps and p13 42.4 vs 59.9 fps under load |

**Verification, 2026-11-16, macOS arm64, JDK 25.0.5, headless, load average 5–48**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test output-lifecycle` (run 5 times while iterating) | 19 tests, 121 assertions, 0 failures |
| `clj -M:mac-arm:test-timing output-lifecycle` | 2 tests, 7 assertions, 0 failures |
| `clj -M:mac-arm:test output-sync` | 5 tests, 34 assertions, 0 failures |
| `clj -M:mac-arm:test output-sync projector-handlers menu-file history events.core multi-engine streaming-engine engine-wire output-errors output-lifecycle` | 157 tests, 759 assertions, 0 failures |
| `clj -M:test-ui:mac-arm:test-ui-headless streaming-status reentry` | 6 tests, 23 assertions, 0 failures |
| `clj -M:mac-arm:test` (full unit suite) | 796 tests, 31,328 assertions, 0 failures (775 / 31,160 before) |
| `clj -M:test-ui:mac-arm:test-ui-headless` (full UI suite) | 53 tests, 202 assertions, 0 failures (49 / 184 before) |
| The probes, and again with `-J-Dlaser-show.dev=true` | p4–p7 0 senders after Stop; 0 lost-update WARNs |
| `clj -M:kondo --lint src test --parallel --fail-level error` | 0 errors, 21 warnings (21 before), none new in a touched file (`handlers/projector.clj`'s unused `laser-show.common.util` predates this phase) |

Observed, not changed: the full unit suite run with `-J-Dlaser-show.dev=true` fails `events.core-test/lost-update-detector-is-off-outside-dev-mode-test` by design, and logs one lost-update WARN from `concurrent-event-cannot-lose-engines-test`, which runs two events at once on two threads without an agent on purpose. Not testable headless: a real DAC (none available); see the manual checks in the hand-off.

