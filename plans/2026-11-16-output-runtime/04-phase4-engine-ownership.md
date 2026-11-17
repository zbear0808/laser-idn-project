# Phase 4 — One owner for the engines; status the UI can trust

**Written:** 2026-11-16   **Written for commit:** `70e049e` (`master`)
**Status:** Proposed. O6–O8 answered 2026-11-16 (A each; each projector keeps its own streaming thread). Needs Phase 3   **Last verified against:** `70e049e`
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
| Start, stop, refresh | `events/core.clj:339-354`, `:356-366`, `:368-383` | Each runs in its own `future`, with no queue and no lock; only `Exception` is caught |
| Engine handles | `multi_engine.clj:271-295` (blind write at `:289`), `:297-317` (read at `:300`, write at `:312`, after stopping each engine in turn), `:319-378` (read at `:329`, write at `:371`) | Stored at `[:backend :streaming :multi-engine-state]`. `connection.clj:64-70` sets it to nil after a stop. |
| Running flags | `[:backend :streaming :running?]` (`connection.clj:40,54`); `[… :multi-engine-state :running?]` (`multi_engine.clj:291,314,373`); `[:backend :idn :connected?]` set optimistically (`connection.clj:42`) | Three flags that drift apart (p5: the toolbar says stopped while an engine streams) |
| Refresh triggers | returned by `projector.clj:213,231,294`, `menu.clj:60,94`, `handlers/history.clj:92` (via `hardware-changed?`, `:64-71`) | `update-settings` (host, name, `projector.clj:268-281`) and `add-manual` (`:238-253`) don't refresh. The Host field fires on every keystroke (`projectors.clj:318-324`). The Port field has no handler (`:325-331`). A blank host throws inside the handler (`projector.clj:274-279`). |
| Refresh diff | `multi_engine.clj:338-343,367-368` | Compares projector ids only, so an engine keeps the host, port, service and bit depth it was created with |
| Status in the UI | `toolbar.clj:221-233` ("Streaming to N" counts *enabled projectors*); `subs.clj:368-386`; the projector dot reads `[:projectors id :status :connected?]` (`projectors.clj:224`), set once to false at creation (`projector.clj:103`) | Nothing reports what is actually streaming. Engine stats (`streaming_engine.clj:244-254`) and `get-stats` (`:340-343`) have no reader. |
| Exit | `app.clj:190-198` (`stop-streaming!`), `:209-228`, `:230-237` | Stops only the engines found in state. No shutdown hook. In dev mode the JVM stays up after an exit (`93c638a`), so orphans keep streaming in the REPL. |
| New / Open while streaming | `menu.clj:57-60,89-94`; the new document has no projectors | A refresh closes every engine; the toolbar still says "Streaming to 0 projector(s)" |

## 2. Root cause

Live objects are kept in app state. Several threads with no common order write that state, and the state can be overwritten whole (O-9, fixed by Phase 3). There is no single owner that could make "start", "stop" and "refresh" mean the same thing however they overlap.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `create-engine-for-projector` (`multi_engine.clj:218-248`), the provider (`:139-212`) | Unchanged; the registry calls them |
| `engine/start!`, `stop!`, `get-stats` (`streaming_engine.clj:270-343`) | Called only from the output thread (and from `drain!`). `start!` still gives each engine its own thread. `stop!` becomes `(stop-all! [engine])` (§4) |
| `hardware-changed?` (`handlers/history.clj:64-71`) | The idea moves into a middleware: compare per-projector fingerprints before and after every event |
| Phase 3: callbacks re-enter through the agent | `:output/status` is dispatched with `events/dispatch!` from the output thread |
| Phase 2: `output-errors/snapshot` | Per-projector error text in the status |
| `app/stop!`, `exit!` (`app.clj:209-237`) | Call `drain!` instead of `stop-streaming!` |

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
- `laser-show.backend.streaming-engine`: NEW `stop-all! [engines]`. It clears every engine's `running?` flag first, then joins each thread (up to 1 s each, as `stop!` does today, `streaming_engine.clj:310-311`) and sends its close pair. `stop!` becomes `(stop-all! [engine])`. Today `stop-engines!` stops one engine after another (`multi_engine.clj:297-317`), so with N projectors the last one goes dark up to N frames after the first.
- `laser-show.events.core` (`:339-383`): the three `:multi-engine/*` effects become `submit-sync!` with the projector snapshot carried in the effect value (`{:projectors …}`), or `submit-stop!`. No futures. The data is read by the handler, not by a worker (`docs/CLJFX_STATE_MANAGEMENT.md` §Best Practices 1).
- `laser-show.events.handlers.connection` (`:30-76`):
  - `:idn/start-multi-streaming` sets `[:backend :streaming :running?]` (the operator's intent) and returns a sync with `(:projectors state)`. Pressing it twice gives two identical syncs, and the second changes nothing.
  - `:idn/stop-multi-streaming` clears the flag and returns the stop.
  - NEW `:output/status` writes `[:backend :streaming :status]`.
  - `:idn/multi-streaming-started`, `-stopped`, `-refreshed` and `:idn/connection-failed` are removed, along with `[:backend :idn :connected? :connecting? :error]`.
- `laser-show.events.handlers.output-sync` (NEW middleware, added to `handle-event-pipeline`, `events/core.clj:388-396`): after any event, when streaming is on and the fingerprints of `(:projectors old)` and `(:projectors new)` differ, it adds `:multi-engine/refresh {:projectors …}`. The six hand-placed refreshes (`projector.clj:213,231,294`, `menu.clj:60,94`, `handlers/history.clj:92`) go away, and host, port, add-manual and undo are covered too.
- `laser-show.events.handlers.projector`: `update-settings` (`:268-281`) ignores a blank host instead of throwing. `make-projector-config` (`:78-104`) stops writing `:status`; old project files that still have it are harmless.
- `laser-show.views.tabs.projectors`: the Host and Port fields (`:318-331`) commit on Enter or focus loss (O7-A). A half-typed address never reaches an engine: Java resolves `192.168.1` (on its way to `192.168.1.50`) to 192.168.0.1, often the router (checked with `InetAddress/getByName` on JDK 25). The dot (`:220-226`) reads the new per-projector status.
- `laser-show.views.toolbar` (`:219-243`) and `subs.clj` (`connection-status`, `:368-386`): the toolbar shows "Streaming to 2 of 3" from the status, plus "Starting…" while a sync is queued and an error count. The button is chosen from the operator's intent (`running?`), not from what has finished starting.
- `laser-show.app`: `stop!` calls `output-runtime/drain! 2000` (replacing `stop-streaming!`, `:190-198`). `start!` registers a JVM shutdown hook that drains, which covers a kill or `System/exit` from anywhere.
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
> | B | The same registry, but the commands run under a `locking` monitor on the thread that asks (the agent) | No new thread | Stopping N engines blocks the agent for up to N × 1 s (`streaming_engine.clj:310-311`), freezing input and the UI's events meanwhile |
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
