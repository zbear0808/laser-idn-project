# No state writes outside the event pipeline

**Written:** 2026-11-20   **Written for commit:** `3229c95` (`master`)
**Status:** Proposed. Needs W1–W6 answered   **Last verified against:** `cc9e000` (re-checked after `master` moved from `3229c95`: since then only the Decimate files changed in `src/` and `test/`, and this plan cites none of them by line; the counts in §1.3 are at `cc9e000`. Probes w1–w6 in `probes/state_write_probes_test.clj` were run four times on 2026-11-20, the first two at `3229c95` and the last at `cc9e000`: macOS arm64, JDK 25.0.5, load average 2–8)
**Supersedes / Superseded by:** takes over the Link item of review 02 M1 step 2 and the `[:styles :menu-theme]` item of 02 M6 step 1, and the "link setup write … stays with review 02 M1" line of `plans/2026-11-16-output-runtime/03-phase3-event-reentry.md` §9 / none   **Related findings:** review 02 #2, #21, #23, M1, M6; output runtime O-9 (03 §1, §9, §10 "Observed, not changed"); keyboard K-7 and probes p5, p9 (the clipboard write: `plans/2026-11-13-keyboard-focus-window-lifecycle/04-phase4-clipboard.md`, Step 4a, not here)
**Size:** about 4½ days (about 3½ with W2 B and W3 B)

Every event takes the whole state as its `:state` co-effect and writes a whole state back. Anything written in between, by another thread or by an effect of the same event that ran before `:state`, is lost, and outside dev mode nothing says so. Output-runtime Phases 3–4 and the show clock took the busiest writers away. These are left:
- the Link connection at start-up: w1 ends with the app believing Carabiner is disconnected;
- the teardown in `stop!`: w2, where a second `stop!` disconnects again and a close runs twice;
- the dev reloads;
- three project helpers that only tests call;
- 69 lines in the UI tests that write state while the app's agent runs.

This plan sends each of them through the event agent. It also:
- makes `:state` the first effect of every event;
- runs every app instance's events on one thread;
- guards the rule: while an app runs, a write from any other thread throws in dev mode and in tests and is logged in production, and kondo flags direct writers in `src/`.

Paths are under `src/laser_show/` unless they start with `test/`, `dev/`, `plans/`, `docs/`, `.clj-kondo/`, `.agents/`, `.github/`, or a library's own path (`cljfx/…`, `beat_carabiner/…`, `clj_kondo/…`, read from their jars in `~/.m2`). **NEW** marks anything that doesn't exist yet. *Verified by running* means a probe printed it (§1.6). Everything else is from reading the code: line numbers are at `cc9e000`, and the files cited are the same at `3229c95`.

## 1. Current behaviour (file:line)

### 1.1 The mechanism (verified)

1. **The snapshot.** `co-effect-state` (`events/core.clj:81-89`) is `state/get-raw-state` (`state/core.clj:80-92`): the whole map, read when the event starts (`co-effects`, `events/core.clj:498-503`).
2. **The new state.** The handler and the middlewares (`handle-event-pipeline`, `:472-496`) compute a new whole state from that snapshot.
3. **The write-back.** `effect-state` (`:150-158`) writes it:
   - with `reset-state-vals!` in dev mode, `reset-state!` otherwise (`state/core.clj:115-137`), both `(fx/swap-context ctx (constantly new-state))`;
   - the swap is atomic, but the value is the snapshot plus this event's own changes;
   - so whatever reached the atom between steps 1 and 3 is replaced (w1, w2a, w6).
4. **Effect order.** Effects run in the order the effects map iterates. cljfx's `wrap-effects` is a `doseq` over the handler's result (`cljfx/event_handler.clj:22-33`, the `doseq` at `:27`). That holds in both wirings:
   - production: `app/create-app`, `app.clj:94-108` → `cljfx/api.clj:596-602`;
   - the synchronous `events/event-handler` (`events/core.clj:557-562`).

   So two things lose their write when they run before `:state`: an effect that writes state, and an inline follow-up (`run-inline`, `:106-110`). See w3. A map literal of up to 8 keys keeps insertion order. At 9 keys it becomes a hash map, which iterates in hash order: in w3, `:output/write-status` and `:project/save` run before `:state` whether `:state` went in first or last.
5. **The detector only logs.** The dev detector (`wrap-lost-update-check`, `:135-148`; `check-lost-update!`, `:124-133`):
   - logs a WARN, and only with `-Dlaser-show.dev=true`;
   - that property is set only by the `:dev` alias (`deps.edn:64`), not by `:test` (`deps.edn:132-147`) or `:test-ui` (`:203-217`);
   - so in the suites it never runs;
   - when it does run, it names the culprit. w1: `[[:show-clock/status [[:backend :link]]]]`.

`docs/CLJFX_STATE_MANAGEMENT.md` says a `swap!` "from ANY thread" is safe (`:216-239`), and its Patterns 1–5 swap from futures (`:261-438`). That holds for a plain atom. In this app such a swap is still atomic, but an event in flight undoes it (w1, w2a).

### 1.2 Every writer of the context

`state/*context` is private (`state/core.clj:34-37`). It is written only through the fns below. No code `swap!`s the atom that `get-context-atom` (`:53-56`) hands out: `app.clj:101` and `:290` give it to cljfx, and tests only add watches.

| # | Writer (file:line) | Writes | Thread | In the pipeline? | Prod / REPL / test | Can race? | Target |
|---|---|---|---|---|---|---|---|
| 1 | `effect-state` (`events/core.clj:150-158`) → `reset-state-vals!` / `reset-state!` (`state/core.clj:115-137`) | the whole state | the event agent; without an app, the caller | yes: it is the `:state` effect | all | Within a turn, the effects that ran before it are overwritten (w3). Without an app, two callers' turns can overlap (w4) | **Keep**. Make it the first effect of every event (W1) |
| 2 | `effect-output-write-status` (`events/core.clj:421-425`): `swap-state! assoc-in` `[:backend :streaming :status]` | one path | the agent; without an app, the runtime's `output-status` thread (`backend/output_runtime.clj:246-256`) | yes, as an effect | prod, tests | Only when a `:state` runs after it in the same event. No event does that today (w3: `:output/status` returns `[:output/write-status]` and nothing else). A middleware that added `:state` would run after it: w3 then gives `nil` | **Keep** as an effect, ordered after `:state` (W1) |
| 3 | cljfx's `:context` effect (`cljfx/defaults.clj:32-35`, `make-reset-effect`) | `reset!` of the atom | the agent | yes | production wiring only | No handler returns `:context` (grep of `src/laser_show/events/`) | **Keep** (unused). Noted in §9 |
| 4 | `init-state!` (`state/core.clj:168-178`) ← `start!` (`app.clj:193`), `start-test!` (`:331`) | a new context | main or REPL thread; the test thread | no | prod, tests | Not in `start!`: it runs before `create-app`. In `start-test!`, after `stop!`, an old app's late event can still be running (w6) | **Keep**, as the one write before an app runs. Refused while an app runs (W2) |
| 5 | `init-styles!` (`app.clj:111-119`) ← `start!` (`:195`), `start-test!` (`:333`), `reload-app-code!` (`dev/reload.clj:130-134`) | `[:styles :menu-theme]`, which is **read nowhere** (grep: only this write and `domains.clj:545`) | main or test thread; from a reload, the agent or the REPL | no | prod, tests, dev | Not from `start!` or `start-test!` (before `create-app`). From the REPL, yes | **Delete**, with the key (02 #21, M6 step 1) |
| 6 | Link at start-up (`app.clj:213-220`): `assoc-in-state! [:backend :link]` with a map computed from a read at `:214` | the whole `[:backend :link]` | main thread | no | prod (`start!` only) | **Yes** (w1). By then the app is mounted (`:201`), `dispatch!` reaches the agent (`:204`), `after-rig-loaded!` (`:208`) and the show clock (`:211`) are sending events, and the Link listener can report from `link.clj:97` on | **Event**: the existing `:timing/carabiner-connected` (`events/handlers/timing.clj:204-207`), with NEW `:listener` |
| 7 | `stop-input-devices!` (`app.clj:237-248`): `swap-state!` whose fn closes the MIDI ports and the OSC server (`input/midi.clj:189-196`, `input/osc.clj:173-188`) | `[:backend :input :midi]`, `[:backend :input :osc]` | the agent on Quit (`:system/exit` → `exit!`, `app.clj:304-311`); otherwise whoever calls `stop!` (`start-test!`, the UI fixture, the REPL) | only on the Quit path, inside an effect | prod, tests | Off the agent, an event in flight writes the old handles back (as in w2a). Anywhere, the closing runs again when the swap retries (w2b: twice) | **Keep the closing, drop the write** (W4) |
| 8 | `disconnect-link!` (`app.clj:260-267`): `swap-state! update-in [:backend :link] link/disconnect-carabiner!` | `[:backend :link]` | as row 7 | as row 7 | prod, tests | w2a: the write is lost, so a second `stop!` disconnects again (2 calls). w2b's retry applies too | **Keep the disconnect, drop the write** (W4) |
| 9 | `save-project!` (`state/persistent.clj:282-290`, write at `:288`), `load-project!` (`:292-300`, at `:298`), `new-project!` (`:302-308`, at `:307`). Section heading: "REPL / tests" (`:280`) | `[:project …]`, the whole show | the test thread | no | **tests only**: `persistent_test.clj:114,154,166,205,207,405,409`. No caller in `src/` or `dev/`; `new-project!` has no caller at all | Not in those tests (no app). From a REPL with the app running, yes. `load-project!` also skips the Open handler (`events/handlers/menu.clj:114-135`) and every middleware | **Delete** (W6) |
| 10 | `reload-all-styles!` (`css/reload.clj:43-73`; write at `:66-68`, via `resolve`) | `[:styles :reload-trigger]` | the agent, in the `:dev/reload` effect (`events/core.clj:322-338`); or the REPL (app-repl skill `:80`, `dev/user.clj:165-188`) | From the effect, yes. Its handler returns no `:state` on purpose so the write survives (`menu.clj:410-416`, pinned by `menu_system_handlers_test.clj:52-57`) | dev | from the REPL, yes | **Event**: NEW `:dev/reloaded {:kind :styles}` |
| 11 | `reload-app-code!` (`dev/reload.clj:87-154`): `init-styles!` (`:130-134`) and `assoc-in-state! [:ui :last-reload-timestamp]` (`:136-143`, via `resolve`) | two paths | as row 10 | as row 10 | dev | as row 10 | **Event**: `:dev/reloaded {:kind :app}`. The `init-styles!` step goes (row 5) |
| 12 | `watch-styles!`'s var watch (`dev/user.clj:140-151`; write at `:149-150`, via `resolve`) | `[:styles :reload-trigger]` | the thread that redefines a style var (the REPL) | no | dev | yes | **Event**: `:dev/reloaded {:kind :styles}` |
| 13 | `set-internal-clipboard!` (`state/clipboard.clj:31-34`). Called by the `copy-*!` fns: from handlers on the agent (`grid.clj:162`, `effects.clj:65`, `cue_chain.clj:164,173`, `list.clj:244-246`), from the `:clipboard/copy-effects` effect (`events/core.clj:214-217`), from a view on the FX thread (`views/dialogs/effect_chain_editor.clj:144-145`); also by `clear-clipboard!` (`clipboard.clj:111-115`) | `[:ui :clipboard]` | agent, FX | Called inside handlers, before their `:state` | prod | yes: keyboard p5 and p9 show it lost after a list copy | **The clipboard plan, Step 4a** (`04-phase4-clipboard.md` §4): it removes this fn with every other clipboard write outside the pipeline, including the effect chain editor's `:on-copy-fn` and the `:clipboard/*-effects` effects. Not planned here. If this plan's guard lands before 4a, it lets this one path through until 4a removes it (§4) |

The primitives:
- `swap-state!` (`state/core.clj:98-113`);
- `reset-state!` (`:115-126`);
- `reset-state-vals!` (`:128-137`);
- `assoc-in-state!` (`:152-162`), which is a `swap-state!`;
- `init-state!` (`:168-178`).

`update-in-state!` is documented (`state/core.clj:219,243`; `state/domains.clj:12-15`) but doesn't exist.

Checked, and not writers:
- the show clock: it reads, publishes its snapshot to a runtime atom and sends events (`services/show_clock.clj:226-262`, `:261`);
- the output runtime: its status goes in by event;
- `mount-renderer` / `unmount-renderer`: they add and remove a watch (`cljfx/renderer.clj:88-94`);
- every view except the one in row 13;
- the tests' watches: `test/laser_show/services/show_clock_test.clj:405-411`; `test/laser_show/backend/output_lifecycle_test.clj:346-376`, `:402-426`.

### 1.3 Tests and REPL use

| # | What | Where | App running? | Today | Target |
|---|---|---|---|---|---|
| T1 | `init-state!` in fixtures | 28 lines in 20 unit-test files | no | safe | keep |
| T2 | direct writes between steps | 90 lines in 12 unit-test files (88 at `3229c95`; Decimate added two to `multi_engine_test`): `multi_engine_test`, `show_clock_test`, `menu_file_handlers_test`, `persistent_test`, `core_test`, `show_time_test`, `output_lifecycle_test`, `frame_service_test`, `input_mapper_test`, `engine_wire_test`, `zone_routing_integration_test`, `menu_system_handlers_test`. `core_test.clj:100-154` writes between snapshot and write-back on purpose | no | Safe: one thread, or set up before a background thread starts (e.g. `output_lifecycle_test.clj:292`, `:573`) | Keep. Kondo leaves `test/` alone, and there is no guard without an app. `persistent_test` per W6 |
| T3 | direct writes while the app's agent runs | 69 lines in 20 namespaces under `test/laser_show/ui_test/`: `streaming_status_test` 12, `perform_test` 12, `vibes_test` 8, `rig_card_test` 6, `setup_dialogs_test`, `safety_editor_test`, `preview_test`, `grid_test` 3 each, `zone_groups_tab_test`, `trigger_editor_test`, `timeline_visual_test`, `settings_test`, `rig_strip_test`, `modulator_editor_test`, `cue_load_test` 2 each, `rig_report_test`, `reentry_test`, `menu_bar_test`, `main_window_test`, `cue_chain_list_test` 1 each | yes (`with-app`, `ui_test/fixtures.clj:48-59`) | An event in flight can lose the write. The tests know it: "a direct write to app state while one is handled is lost when it writes back" (`modulator_editor_test.clj:27-31`); "Written before any event is queued, so no event's write-back can lose it" (`preview_test.clj:43-46`) | `events/write-state!` (W5) |
| T4 | waits that set the BPM as a marker | `modulator_editor_test.clj:27-34`, `cue_chain_list_test.clj:33-40`, `perform_test.clj:59-66`, `vibes_test.clj:48-55`; also `plans/2026-11-13-keyboard-focus-window-lifecycle/probes/keyboard_probes_test.clj:39-45` (as committed at `cc9e000`) | yes | They change the BPM to learn that the queue is empty | `events/flush!` |
| T5 | copies of the production wiring | `show_clock_test.clj:109-114`; `agent-handler` in `plans/2026-11-16-output-runtime/probes/output_probes_test.clj:161-165` and in this plan's probes | own agent | – | `events/ordered-pipeline` and `:fx/executor events/event-executor` |
| T6 | the REPL | The app-repl skill reads state (`.agents/skills/app-repl/SKILL.md:87-94`) and calls `reload-all-styles!` directly (`:80`). It says nothing about writing | yes, with `(start)` or `start-test!` | A REPL `swap-state!` races like T3 | The skill gives `dispatch!`, `flush!`, `write-state!` |

Probes in other plan folders write state directly while an app is mounted: `keyboard_probes_test.clj:90,99,210` as committed at `cc9e000`, and the probes the clipboard plan adds to that file. After commit 9 they throw. Switch them to `events/write-state!` if they are run again.

### 1.4 Start-up and shutdown today

**`start!`** (`app.clj:171-235`), in order:

| Step | Line | What can send events from here |
|---|---|---|
| read the rig; `init-state!` | `:192-193` | nothing (no agent yet) |
| `init-styles!` | `:195` | nothing |
| open the output runtime; shutdown hooks | `:197-199` | nothing |
| `create-app`: the agent, and the renderer mounted. The first render is queued with `Platform/runLater` (`cljfx/renderer.clj:41-46`) | `:201` | cljfx map events from views, e.g. `:vibe/window-focus` (`views/root.clj:208`); they go straight to the app's handler |
| `set-dispatch-fn!`, the exit fn, the input mapper's dispatch fn | `:204-206` | from here, `events/dispatch!` reaches the agent |
| `after-rig-loaded!` | `:208` (`:162-169`) | `:rig/startup-notice`, `:rig/retry-write` |
| `show-clock/start!` | `:211` | `:show-clock/status`, at least at each beat change (`show_clock.clj:163-190`) |
| **Link:** read `[:backend :link]` (`:214`); connect (`link.clj:84-105`, which registers the listener at `:96-97`); write the whole subtree back (`:219`) | `:213-220` | the listener's `:timing/link-*` events (`link.clj:55-77`), from beat-carabiner's response thread (`beat_carabiner/core.clj:487`) |
| `:projectors/scan-network` | `:224` | the scan (queued after the Link write, not before it) |
| input mapper, gamepad | `:227-232` | MIDI, OSC and gamepad events |

So the Link write can land while the agent is in an event. In w1 (an event in flight from `:show-clock/status`) it ends as `{:carabiner-connected? false}`, with no `:listener`. Two consequences:
- Link can't be turned on: `link/enable-link!` checks that flag (`link.clj:148-155`);
- `stop!` never disconnects (`app.clj:263`).

03 §9 left this write with review 02 M1 because "it happens once, before any event can race it". That stopped being true when the show clock was started before it (`app.clj:210-211`) and `after-rig-loaded!` was added. How often it happens when nothing is slowed: *not measured*. The window is one handler's run time, and the events come from the clock's status and the Link listener.

**What must exist before the first event.** The order above already gives these:
- **The context.** Without it the co-effect gives `nil` and `effect-state` throws "State not initialized" (`state/core.clj:113,126,136`).
- **The output runtime, opened.** A closed runtime drops `:multi-engine/*` commands (`output_runtime.clj:288-301`).
- **`[:rig-file]`**, set by `load-rig`, for the `:rig/*` events.
- **The dispatch fn**, before the first `events/dispatch!` caller starts: the clock (`:211`, its comment at `:210`), the input mapper (`:206`, `:227`), the Link listener (`:218`), the gamepad (`:231`).

The views' first map events don't need the dispatch fn: they reach the app's handler directly. A view's imperative `events/dispatch!` between `:201` and `:204` would run the fallback on the FX thread. The first render is only queued by then, so this is accepted (§9).

**`stop!`** (`app.clj:279-302`) runs these steps in order:
1. unmount (`:289-290`);
2. the show clock (`:291`);
3. the input mapper (`:292`);
4. drain the engines (`:293`);
5. `stop-input-devices!` (`:294`, `:237-248`);
6. `disconnect-link!` (`:295`, `:260-267`);
7. the rig file (`:296`);
8. clear the exit fn and the dispatch fn (`:297-298`).

Where it runs:
- **On the event agent**, when Quit reaches `:system/exit` (`events/core.clj:301-308` → `app/exit!`, `app.clj:304-311`).
- **On the caller's thread** otherwise: `start-test!` (`:328`), the UI fixture (`fixtures.clj:58`), the REPL.

Problems with the two teardown writes:
- **Retries.** Both `swap-state!` calls do their side effects inside the swap fn, and `swap!` runs that fn again when another write lands meanwhile (w2b: `midi/disconnect-all` twice for one stop).
- **Lost writes.** Off the agent, an event in flight writes the old handles back (w2a). The flag that makes `stop!` idempotent then says "connected" again, and a second `stop!` disconnects again.

**When the agent can't run the next event.**
- **On the Quit path,** `stop!` holds the event thread until it returns. In production `System/exit` follows inside the same turn. The JVM shutdown hooks then run while the event thread waits in `System/exit`. Neither waits for an event:
  - `output-drain-on-shutdown` drains (`output_runtime.clj:348-357`);
  - `rig-flush-on-shutdown` waits only for the rig-writer thread (`rig_store.clj:293-321`), whose result callback queues with `send-via` and returns (`events/core.clj:196-210`, `rig_store.clj:269-277`).
- **After `stop!`,** `events/dispatch!` falls back to the synchronous handler (`events/core.clj:583-607`). Callbacks that kept the old agent's `dispatch` still queue on the old agent: a `:dispatch-later` future, the scan, learn, the rig writer. That agent runs on the shared send pool, next to the next app's agent. In w6 an old app's late event and the new app's event run at once, and the new app's BPM 99 is lost.

### 1.5 `:output/write-status` and the other writes inside a turn

`:output/status` (`events/handlers/connection.clj:28-34`) returns only `{:output/write-status status}`. The effect (`events/core.clj:421-425`) swaps that one path.

Phase 4 chose this for the case with no app. There the status thread runs the pipeline next to the caller, and a path-only write can't undo the caller's writes (`plans/2026-11-16-output-runtime/04-phase4-engine-ownership.md:205`). w4 confirms it:
- path-only, Stop survives: `{:running? false …}`;
- folded into `:state`, Stop is undone: `{:running? true …}`.

No middleware adds a `:state` when the handler returned none:
- `wrap-history` and `wrap-dirty` (`events/handlers/history.clj:255-256`, `:339-343`);
- `output_sync.clj:32`, `rig_autosave.clj:44`;
- `wrap-lost-update-check` (`events/core.clj:142-144`);
- the show lock doesn't block it (`events/show_lock.clj:24-53`, `:102-107`).

So today it never meets a `:state` (w3: the pipeline returns `[:output/write-status]`). If it ever did, a middleware's `assoc` would put `:state` after it and the status would be gone (w3: `nil`).

The same applies to every write inside a turn:
- An inline follow-up that runs before `:state` is lost (w3: BPM 120, not 99).
- The dev reload works only because its handler returns no `:state` (row 10).

No handler today puts `:state` after another effect:
- The literal maps that start with an inline effect have no `:state`: `grid.clj:188-190`, `modulator.clj:87-109`, `vibes.clj:75-87`, `triggers.clj:163-164`, `menu.clj:104-106`.
- The middlewares `assoc` onto an existing `:state` or add their keys after it.

But nothing makes sure it stays that way.

### 1.6 What was run, and what was read

The probes are in `probes/state_write_probes_test.clj` (namespace `probes.state-write-probes-test`; the run command is in its header). They were run four times: the first two at `3229c95`, the third while `master` moved, and the last at `cc9e000` with a clean source tree. Every run gave the same results, except w5's timings, which are given as ranges:

| Probe | Result |
|---|---|
| w1 | **Control:** `{:carabiner-connected? true, :listener …}`. **With `:show-clock/status` in flight** (slowed to 200 ms): `true` right after the start-up write, `{:carabiner-connected? false}` after the event's write-back. **Dev detector:** `[[:show-clock/status [[:backend :link]]]]` |
| w2a | `disconnect-link!` with `:timing/link-peers-changed` in flight: `false` right after; `{:carabiner-connected? true, :listener …}` after the write-back; a second call disconnects again (**2 calls**) |
| w2b | One other write during `stop-input-devices!`: `midi/disconnect-all` ran **2 times** |
| w3 | `[:state :output/write-status]`: status kept. `[:output/write-status :state]`: `nil`. `[:state :dispatch]`: BPM 99.0. `[:dispatch :state]`: 120.0. `:output/status` through `handle-event-pipeline` returns `[:output/write-status]`. Nine effect keys make a `PersistentHashMap` that runs `[:rig/write :output/write-status :project/save :state :multi-engine/refresh :dispatch :system/exit :fx/show-file-chooser :dispatch-later]`, whether `:state` went in first or last |
| w4 | No app. Path-only status: `{:running? false, :status {:engines 0}}`. Folded into `:state`: `{:running? true, …}` |
| w5 | **A no-op marker's round trip** on an idle agent (2,000 runs): median 18–45 µs, p95 31–168 µs. **`:fx/sync` on a handler that throws:** still waiting after 1 s, because the promise is never delivered (`cljfx/event_handler.clj:35-38`); the agent goes on with the next event (error mode `:continue`). **A thread-identity check:** 1.5–2.7 ns. **Putting `:state` first:** 31–36 ns, against 3.6–3.9 µs for that event's pipeline (`:timing/set-bpm`; measured in the last two runs) |
| w6 | Two agents on the send pool: BPM 120.0, on threads `clojure-agent-send-pool-2` and `-3`. One shared single-thread executor: BPM 99.0, both events on it |

**Read, not run:** the inventory (§1.2, by grep and reading), the start-up and shutdown order, cljfx's internals, clj-kondo's config handling (§3), beat-carabiner's listener and connect (`beat_carabiner/core.clj:212-222`, `:492-552`), and every test citation. **Not run:** the GUI app, the full suites, a real Carabiner.

### 1.7 Prior work, and what this plan takes over

| Item | At `cc9e000` | Here |
|---|---|---|
| Review 02 M1 step 1: instrument (`plans/2026-11-07-improvement-review/02-events-state.md:235`) | Done: the dev detector (03 §10; now `events/core.clj:124-158`) | Kept. Making it fail the suites is W2 D |
| 02 M1 step 2: route every out-of-band writer through `dispatch!` (`:236-239`) | Learn: done (the learn future dispatches, `events/core.clj:283-292`). Clipboard: the clipboard plan's Step 4a. Link init: open | **Taken over:** the Link init. It uses the existing `:timing/carabiner-connected`, not the review's new `:timing/link-initialized` |
| 02 M1 step 3: a runtime atom for the per-tick writes (`:240`) | Done: output-runtime Phase 4 took the engine handles out of state, and the show clock took the timing writes (`check-lost-update!` has no exemptions, `events/core.clj:124-127`, pinned by `core_test.clj:133-146`) | – |
| 02 M6 step 1: delete `[:styles :menu-theme]` (`:290`, finding #21 at `:156`) | Open | **Taken over** (row 5). The rest of M6 stays with 02 |
| 02 #23: `stop!` isn't symmetric with `start!` (`:162-169`) | Mostly done: the clock, the gamepad, the engines, Link, MIDI/OSC and the dispatch fn are released | Changes how the devices are released (W4) |
| 03 §1: "Writers that don't go through the pipeline at all" (`03-phase3-event-reentry.md:34`) | The preview timer: gone (the show clock). The multi-engine handles: gone (Phase 4). The Link setup: open | **Taken over** |
| 03 §9: "The link setup write … stays with review 02 M1. It happens once, before any event can race it" (`:118`); and the overview's "the other writers (link init, clipboard) stay with 02" (`plans/2026-11-16-output-runtime/00-overview.md:152`) | No longer true (§1.4, w1) | **Taken over**: the Link init here; the clipboard goes to the clipboard plan's Step 4a |
| 03 §10, observed: `stop!` doesn't clear the dispatch fn (`:178`) | Done (`app.clj:298`) | – |
| 03 §10, observed: `:opts` and `:error-handler` are ignored (`:177`) | Still true | Not here (§9) |
| The four BPM-marker waits in the UI tests (T4) | Copied by hand in 4 namespaces | **Taken over:** replaced by `events/flush!` |

## 2. Root cause

The `:state` effect writes back a whole snapshot. That is lossless only if nothing else writes between the co-effect and the effect. Three things break that today:
1. writers that don't go through the agent (§1.2 rows 6–12; T3; T6);
2. effects run in whatever order the map iterates, so a write inside the turn can come before the write-back (§1.5);
3. each app instance has its own agent on a shared pool, so an old instance's late event runs at the same time as the new instance's events (w6).

Nothing enforces the rule:
- every write fn is public and callable from any thread;
- the only check is dev-only and silent in the suites;
- the state doc recommends swapping from futures.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `:timing/carabiner-connected` (`events/handlers/timing.clj:204-207`, routed at `:269`; tested at `timing_handlers_link_test.clj:49,58`; no dispatcher in `src/` today) | Carries the Link connection's result. Gains `:listener` |
| cljfx `wrap-async`'s `:fx/executor` option (`cljfx/event_handler.clj:40-48`), reached through `fx/create-app`'s `:async-agent-options` (`cljfx/api.clj:584-602`; set at `app.clj:108`) | One executor for every app instance's agent |
| `events/dispatch!` and its synchronous no-app fallback (`events/core.clj:583-607`) | `flush!` and `write-state!` dispatch through it, so without an app they stay synchronous |
| The `:dev/reload` effect (`events/core.clj:322-338`), `:help/reload-styles` and `:help/reload-app` (`menu.clj:410-422`) | Unchanged. The reload fns now dispatch their trigger |
| `[:styles :reload-trigger]`, read by `stylesheet-urls` (`subs.clj:1096-1099`) | The same trigger, written by an event |
| `show-clock/stop!` (`show_clock.clj:310-320`), `output-runtime/drain!` (`output_runtime.clj:325-346`, idempotent), `gamepad/stop!` (`input/gamepad.clj:131-136`, idempotent), `rig-store/flush!` (`rig_store.clj:293-308`) | `stop!`'s steps, reordered |
| `persistent/project-files`, `mark-saved`, `apply-loaded-project`, `write-project!`, `read-project` (`state/persistent.clj:135-277`) | What `persistent_test` uses instead of the helpers |
| clj-kondo `:discouraged-var` with `:ns-groups` and `:config-in-ns`. Read in 2026.10.26 (the version in `deps.edn`): per-var `:level`, and `:level :off` per namespace or group (`clj_kondo/impl/namespace.clj:738-768`); groups by name or filename pattern (`clj_kondo/impl/config.clj:532-541`, `clj_kondo/impl/analyzer/namespace.clj:566-584`). Plus `.clj-kondo/config.edn:1-15` and CI's `--lint src test --fail-level error` (`.github/workflows/build-and-test.yml:49-50`) | The static check |
| `clojure.tools.logging.test` (used by `test/laser_show/events/core_test.clj:9`) | The guard's log test |

Only one NEW namespace: `laser-show.events.handlers.dev`. It holds three tooling events, keeps them out of the domain handlers, and gives the router one place for the `"dev"` domain.

## 4. Changes per namespace

### `laser-show.events.core` (existing)

- **`:state` first.** NEW private `state-first [handler]`: the result's `:state` entry first, then the other entries in the map's own order, as a seq of map entries. A map without `:state`, `{}` or `nil` is returned unchanged. Both wirings iterate it the same way (`cljfx/event_handler.clj:27`).
  - NEW public `ordered-pipeline` = `(state-first handle-event-pipeline)`.
  - `event-handler` (`:557-562`) wraps `ordered-pipeline`, and `app/create-app` passes it to cljfx.
  - `handle-event-pipeline` still returns a map, so the tests that call it are unchanged.
- **One event thread.**
  - NEW `event-thread-name`: `"laser-show-events"`.
  - NEW `defonce event-executor`: a single-thread executor whose one daemon thread has that name and is recorded in NEW private `defonce !event-thread`.
  - NEW `on-event-thread? []`.
  - Both are `defonce`, so a dev code reload (which reloads this namespace, `dev/reload.clj:87-128`) keeps the thread.
- **NEW `flush! ([] [timeout-ms])`**, 2,000 ms by default:
  - It dispatches NEW `:dev/flush` with a promise and waits for it.
  - It returns true once every event queued before the marker has been handled, and false on a timeout.
  - On the event thread or the FX thread it returns false at once: the event thread is busy with the current event, and the FX thread may be what that event is waiting for (`clipboard.clj:64,82,116`).
  - Without an app it returns true at once.
  - The waiting idiom for tests and the REPL is `(events/dispatch! e)` then `(events/flush!)`.
- **NEW `write-state! [f & args]`**, for tests and the REPL:
  - It dispatches NEW `:dev/write-state {:f :args}`, then calls `flush!`, and throws `ex-info` if the flush times out.
  - Its effect is like today's direct writes: no undo step, the unsaved flag untouched, no output sync, no rig write. The event returns no `:state`, so no middleware sees it.
- **NEW effects**, added to `effects` (`:505-555`) and its docstring:
  - `:dev/deliver` delivers the promise;
  - `:dev/swap-state` does `(apply state/swap-state! f args)` on the event thread, in the agent's turn.

  Clipboard Step 4a edits the same map: it removes `:clipboard/copy-effects` and `:clipboard/paste-effects` and adds `:clipboard/write` and `:clipboard/read`. Whichever plan lands second edits what the other left, and `effects-and-co-effects-are-shared-maps-test` (`core_test.clj:36-47`) with it.
- **`effect-output-write-status`** (`:421-425`): no code change. Its docstring says it runs after `:state`.
- **The namespace doc** (`:21-44`):
  - events run on `laser-show-events`, not `clojure-agent-send-pool-*` (`:24`);
  - the guard;
  - `:state` first;
  - the "Writers outside the pipeline" sentence (`:36-41`) loses start-up and shutdown (app.clj) and the dev code reload. Step 4a takes the clipboard helper out of the same sentence. Whichever plan lands second edits what the other left. With both in, the sentence says that nothing writes app state outside the pipeline, and that while an app runs, a write from any other thread than the event thread is refused.

### `laser-show.events.handlers.dev` (NEW)

`handle [event]`:

| Event | Returns |
|---|---|
| `:dev/flush {:promise p}` | `{:dev/deliver p}` |
| `:dev/write-state {:f :args}` | `{:dev/swap-state {:f :args}}` |
| `:dev/reloaded {:kind :styles}` | `{:state …}`, with `[:styles :reload-trigger]` bumped (`(fnil inc 0)`) |
| `:dev/reloaded {:kind :app}` | `{:state …}`, with `[:ui :last-reload-timestamp]` set to the event's `:time` co-effect |
| anything else | `{}` |

### `laser-show.events.handlers` (existing)

`handle-event` (`events/handlers.clj:37-73`) routes `"dev"` to the new namespace. Today an unknown domain logs an ERROR and returns `{}` (`:70-73`).

### `laser-show.events.handlers.timing` (existing)

`handle-timing-carabiner-connected` (`:204-207`) keeps `:carabiner-connected?` and also stores `:listener` when the event has one. The other keys under `[:backend :link]` are kept.

### `laser-show.state.core` (existing)

- **The guard.** NEW private `defonce !write-guard`: `nil`, or `{:allowed? (fn []) :strict? bool}`. NEW `guard-writes! [guard]` installs it; `nil` removes it.
- **The check.** Each write fn checks first: `swap-state!` (`:98-113`), `reset-state!` (`:115-126`), `reset-state-vals!` (`:128-137`), `assoc-in-state!` (`:152-162`) and `init-state!` (`:168-178`).
  - The check fails when a guard is set and `(allowed?)` is false.
  - One exception, only if this lands before clipboard Step 4a: `assoc-in-state!` on `[:ui :clipboard]`. Step 4a removes that call and the exception with it. If 4a lands first, there is no exception.
  - On a failed check, strict mode throws `ex-info` "App state written outside the event pipeline: dispatch an event (tests and the REPL: events/write-state!)" with `{:thread name}`.
  - Otherwise the write is logged at ERROR with a stack trace, then done as today.
  - The read fns are unchanged.
- **The docstrings** say handlers return `:state`: the namespace doc ("Mutate via: (assoc-in-state! …)", `:14-18`) and `defstate`'s (`:217-243`, which also names `update-in-state!`).

### `laser-show.app` (existing)

- **`create-app`** (`:94-108`): `:event-handler events/ordered-pipeline`, and `:async-agent-options {:error-handler agent-error-handler :fx/executor events/event-executor}`.
- **`start!`** (`:171-235`):
  - Delete `init-styles!` (`:111-119`) and its calls (`:195`; `:333` in `start-test!`).
  - Right after `set-dispatch-fn!` (`:204`), install the guard: `(state/guard-writes! {:allowed? events/on-event-thread? :strict? (dev-config/dev-mode?)})`.
  - The Link block (`:213-220`) becomes NEW private `connect-link! []`. It reads `[:backend :link]`, connects on this thread as today (`link/connect-carabiner!`, which blocks as today), then `events/dispatch!`es `{:event/type :timing/carabiner-connected :connected? … :listener …}`.
  - After this, the only direct write in `start!` is `init-state!` (`:193`), before any agent exists.
- **`start-test!`** (`:313-339`): the same guard, with `:strict? true`.
- **`stop!`** (`:279-302`):
  1. Claim `*app` with `swap-vals!`; a second call finds `nil`.
  2. Unmount.
  3. Stop the show clock and the input mapper, then `gamepad/stop!` (moved here from `stop-input-devices!`), then drain the engines.
  4. `(events/flush! 1000)`.
  5. Only when this call claimed an app: NEW private `release-devices! [state]`, on one read of app state. It calls `midi/disconnect-all` and `osc/stop-server` (when `[:backend :input]`) and `link/disconnect-carabiner!` (when `:carabiner-connected?`), each in its own `try`, and drops their results.
  6. The rig file, as today.
  7. Clear the exit fn, the dispatch fn and the guard.
  8. Implicit exit, as today.

  `stop-input-devices!` and `disconnect-link!` go. Nothing in `stop!` writes app state.
- **`exit!`** (`:304-311`): unchanged.

### `laser-show.state.persistent` (existing)

Delete `save-project!`, `load-project!` and `new-project!` (`:280-308`). (W6)

### `laser-show.css.reload`, `laser-show.dev.reload`, `dev/user.clj` (existing)

- After the reload, dispatch the trigger:
  - `{:event/type :dev/reloaded :kind :styles}` from `reload-all-styles!` (`css/reload.clj:43-73`) and from `watch-styles!`'s watch (`dev/user.clj:140-151`);
  - `{:event/type :dev/reloaded :kind :app}` from `reload-app-code!` (`dev/reload.clj:87-154`).
- Each resolves `laser-show.events.core/dispatch!` when called, as they resolve `state.core` today.
- `reload-app-code!` loses its `init-styles!` step (`:130-134`).
- Return values are unchanged.

### `laser-show.state.domains` (existing)

`build-initial-state` (`:533-545`) gives `{:styles {}}`: `:menu-theme` goes. The namespace docstring (`:12-15`) changes as `state.core`'s does.

### `.clj-kondo/config.edn` (existing, `:1-15`)

- `:discouraged-var` at `:error` for the five write fns. Message: "App state is written by the event pipeline: return :state from a handler, or dispatch an event (docs/CLJFX_STATE_MANAGEMENT.md)".
- `:ns-groups [{:filename-pattern "(^|/)test/" :name test-code}]`.
- `:config-in-ns`:

  | Namespace or group | Exempt from |
  |---|---|
  | `test-code`, `laser-show.state.core` | the whole linter |
  | `laser-show.events.core` | `swap-state!`, `reset-state!`, `reset-state-vals!` |
  | `laser-show.app` | `init-state!` |
  | `laser-show.state.clipboard` | `assoc-in-state!`, only while clipboard Step 4a hasn't landed |

CI lints `src test` (`build-and-test.yml:50`), so `dev/` relies on the runtime guard.

### Tests and docs

- **Tests:**
  - The 69 UI-test writes become `events/write-state!` with the same arguments, and the four BPM-marker helpers become `events/flush!` (T3, T4).
  - `show_clock_test.clj:109-114` uses `events/ordered-pipeline` and `:fx/executor events/event-executor` (T5).
  - `reentry_test.clj:18-19` checks `events/event-thread-name`.
  - `persistent_test` (`:42-74`, `:110-215`, `:400-411`) uses the pure fns (W6).
- **`docs/CLJFX_STATE_MANAGEMENT.md`:** after the warning at `:232-239`, add an "In this app" box:
  - only the event pipeline writes app state; from other threads, dispatch an event (Pattern 6, `:440-570`);
  - a `swap!` from another thread is atomic, but an event in flight undoes it;
  - tests and the REPL use `events/write-state!` and `events/flush!`.

  Patterns 1–5 get one line saying this app doesn't use them for app state. Best Practice 2 (`:596-608`) is necessary here, but not enough.
- **`.agents/skills/app-repl/SKILL.md`:**
  - after "Read app state" (`:87-94`), a "Change app state" paragraph: `dispatch!` then `flush!`, `write-state!` for a raw write, and that `state/swap-state!` throws while the app runs;
  - the trap at `:80` still holds: `reload-all-styles!` still returns `{:success? true}`, and its trigger now arrives by event.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:backend :link :carabiner-connected?]`, `[:backend :link :listener]` | app state, runtime part (exists; not saved) | `:timing/carabiner-connected`. Was: `start!` directly | no / no |
| `[:backend :input :midi]`, `[:backend :input :osc]`, `[:backend :link]` at shutdown | app state, runtime part | nobody. Was: `stop!`. The devices are closed from one read | – |
| `[:styles :reload-trigger]` | app state, UI (exists) | `:dev/reloaded`. Was: `css.reload`, `watch-styles!` | no / no |
| `[:ui :last-reload-timestamp]` | app state, UI (exists) | `:dev/reloaded`. Was: `dev.reload` | no / no |
| `[:styles :menu-theme]` | removed | was `init-styles!`; read nowhere | – |
| `[:project …]`, from the REPL | document | the File events only, now that the helpers are gone | as the events |
| `[:backend :streaming :status]` | app state, runtime part (exists) | `:output/write-status`, now always after `:state` | no / no |
| any path, from a test or the REPL | app state | `:dev/swap-state`, on the event thread | no / no: no middleware sees it |
| NEW `!write-guard` | runtime atom in `state.core` | `start!`, `start-test!`, `stop!` | – |
| NEW `event-executor`, `!event-thread` | runtime, JVM-wide `defonce` in `events.core` | – | – |
| the promise in `:dev/flush` | the event map only | – | – |

## 6. Events and effects

| Event / effect | Exists? | Dispatched from (file:line) | Pure? |
|---|---|---|---|
| `:state` | exists (`events/core.clj:150-158`) | every handler | Effect. Now runs first (NEW `state-first`) |
| `:timing/carabiner-connected` | exists (`timing.clj:204-207`); gains `:listener` | NEW `app/connect-link!` ← `start!`; tests | yes |
| `:dev/reloaded` | NEW | **Styles:** `reload-all-styles!` ← the `:dev/reload :styles` effect (`events/core.clj:326-338`) ← `:help/reload-styles` (`menu.clj:410-416`) ← title bar (`views/components/title_bar.clj:222`); also the REPL (`dev/user.clj:165-188`) and `watch-styles!` (`dev/user.clj:140-151`). **App:** `reload-app-code!` ← `:dev/reload :app` ← `:help/reload-app` (`menu.clj:418-422`) ← title bar (`:227`) | yes |
| `:dev/flush` | NEW | `events/flush!` ← `app/stop!`, `events/write-state!`, tests, REPL | yes |
| `:dev/write-state` | NEW | `events/write-state!` ← UI tests, REPL | yes |
| `:dev/deliver`, `:dev/swap-state` | NEW effects | the two events above | effects |
| `:output/write-status` | exists (`events/core.clj:421-425`) | `:output/status` (`connection.clj:28-34`) ← `output-runtime/publish-status!` (`output_runtime.clj:246-256`), through the dispatch fn `submit!` keeps (`:297`) | Effect. Now after `:state` |
| `:system/exit` | exists (`events/core.clj:301-308`) | `:file/exit` (`menu.clj:213-222`) ← File > Exit (`title_bar.clj:100`), the window's close (`views/root.clj:209-215`), Don't Save (`views/dialogs/unsaved_confirm.clj:61,106` → `:file/confirm-discard`, `menu.clj:236-241`) | Unchanged. The `stop!` behind it now writes nothing and doesn't wait on the event thread |

## 7. Data examples and invariants

**Effect order**

| The handler and middlewares return | Runs today | Runs after |
|---|---|---|
| `{:state s :dispatch e}` | `:state`, `:dispatch` | the same |
| `(array-map :dispatch {set-bpm 99} :state s)` | The follow-up writes BPM 99, then `:state` writes 120 (w3) | `:state`, then the follow-up: BPM 99 |
| `(assoc {:output/write-status st} :state s)` | The status is written, then replaced: `nil` (w3) | The status is kept |
| nine keys including `:state` (a hash map) | `[:rig/write :output/write-status :project/save :state :multi-engine/refresh :dispatch :system/exit :fx/show-file-chooser :dispatch-later]` (w3) | `[:state :rig/write :output/write-status :project/save :multi-engine/refresh :dispatch :system/exit :fx/show-file-chooser :dispatch-later]` |
| `{:output/write-status st}` (today's `:output/status`) | as is | as is |
| `{}`, `nil` | nothing | nothing |

**Link at start-up**

```clojure
;; connect-link! after link/connect-carabiner! returned (link.clj:100-102)
{:event/type :timing/carabiner-connected :connected? true :listener #object[…]}

;; [:backend :link] before, with a listener report that landed first → after
{:carabiner-connected? false :link-peers 2 :auto-connect? true …}
;; → {:carabiner-connected? true :listener #object[…] :link-peers 2 :auto-connect? true …}

;; a connect that failed (link.clj:103-105)
{:event/type :timing/carabiner-connected :connected? false}
;; → {:carabiner-connected? false …}, and no :listener

;; the tests' event today (timing_handlers_link_test.clj:49), no :listener → as today
{:event/type :timing/carabiner-connected :connected? true}
```

**`start!`, after**

| Step | Today (`app.clj`) | After |
|---|---|---|
| read the rig; `init-state!` | `:192-193` | the same: the only direct write, before any agent |
| `init-styles!` | `:195` | gone |
| open the runtime; hooks | `:197-199` | the same |
| `create-app` | `:201` | `ordered-pipeline`; events on `laser-show-events` |
| dispatch fns | `:204-206` | the same, then `guard-writes!` |
| `after-rig-loaded!`, `show-clock/start!` | `:208-211` | the same |
| Link | `:213-220`: a direct write | `connect-link!`: connect, then `:timing/carabiner-connected`, queued like every other event |
| scan, input mapper, gamepad | `:222-232` | the same |

**`stop!`**

| Step | Called on the test thread or the REPL | Called on the event thread (Quit) |
|---|---|---|
| claim `*app` | the app; `nil` on a second call | the app |
| unmount, clock, mapper, gamepad, drain | as today | as today |
| `(events/flush! 1000)` | waits until the queued events have run (at most 1 s) | false at once |
| `release-devices!` (only with an app claimed) | one read; each device closed once | the same, from this turn's state |
| rig file; clear the exit fn, dispatch fn, guard | as today | as today |
| writes to app state | none | none |

**The guard**

| Writer | App mounted? | Thread | Result |
|---|---|---|---|
| the `:state` effect | yes | `laser-show-events` | written |
| `:dev/swap-state` (`write-state!`) | yes | `laser-show-events` | written |
| a UI test's `state/swap-state!` | yes, through `start-test!` (strict) | the test thread | throws `ex-info` "App state written outside the event pipeline…"; state unchanged |
| a REPL `state/swap-state!` after `(start)` in dev mode | yes (strict) | the nREPL thread | throws |
| a regression in production | yes (lenient) | any | logged at ERROR with a stack trace; written as today |
| `assoc-in-state! [:ui :clipboard] …` from the FX thread (`effect_chain_editor.clj:144-145`) | yes | FX | written: exempt only until clipboard Step 4a, which removes this call |
| `init-state!` in `start-test!` | no: `stop!` removed the guard | the test thread | written |
| a unit test without an app | no guard | any | written |

**`flush!` and `write-state!`**

| Call | App? | Thread | Result |
|---|---|---|---|
| `(events/flush!)` | no | any | true at once (synchronous fallback) |
| `(events/flush!)` | yes, idle | test or REPL | true after one round trip (w5: median 18–45 µs) |
| `(events/flush!)` | yes | event thread or FX thread | false at once |
| `(events/flush! 100)` | yes, one event stuck for 5 s | test | false after 100 ms. (`:fx/sync` would never return: w5) |
| `(events/write-state! assoc-in [:timing :bpm] 128.0)` with an event in flight | yes | test | written after that event: BPM 128.0, nothing lost, no undo entry |
| `(events/write-state! …)` | no | any | written at once |

**Invariants**

1. **Who writes.** While an app is mounted, the context is written only on `laser-show-events`: by the `:state` effect, or by an effect of the same turn that runs after it (`:output/write-status`, `:dev/swap-state`). The one exception, until clipboard Step 4a, is `[:ui :clipboard]`.
2. **Effect order.** In every event, `:state` runs before every other effect, and the other effects keep the map's order.
3. **One event at a time.** Every app instance's events run on the one event thread, one at a time.
4. **Start-up.** `start!` writes app state directly once, with `init-state!`, before `create-app`.
5. **Shutdown.** `stop!` writes nothing to app state, and closes each MIDI port, the OSC server and the Carabiner connection at most once per started app.
6. **Direct callers in `src/`.** Only these files call a write fn:
   - `state/core.clj`;
   - `events/core.clj`, for `swap-state!`, `reset-state!` and `reset-state-vals!`;
   - `app.clj`, for `init-state!`;
   - `state/clipboard.clj`, until clipboard Step 4a.

   Kondo enforces this in CI.
7. **No hangs.** `flush!` never blocks the event thread or the FX thread, and neither it nor `write-state!` can hang (w5: `:fx/sync` can).
8. **No app, no change.** Without an app, `dispatch!` and `event-handler` stay synchronous, and the unit tests are unchanged (T1, T2).

**Cost on the real-time path: none.**
- The show clock (60 Hz, on the `show-clock` thread) and the engine threads only read app state and send events (§1.2, "Checked").
- The guard runs only on writes, once an event: 1.5–2.7 ns (w5).
- `state-first` also runs once an event: 31–36 ns, next to 3.6–3.9 µs for the pipeline (w5).
- `flush!` is called only from tests, the REPL and `stop!`.
- With one event thread, events still run one at a time, as they do on one agent today.

## 8. Tests (named test vars + fixtures)

Write each one failing first, where it can fail today (marked *red today*).

**Existing unit namespaces**

- **`laser-show.events.core-test`** (fixture `:each`: `init-state!`, dispatch fn `nil`, exit fn `nil`; `core_test.clj:14-18`):
  - `state-runs-first-test` (NEW, *red today*: BPM 120 and status `nil`, w3). `event-handler` with `timing/handle` redefined to return `(array-map :dispatch {set-bpm 99} :state …)`, then `(assoc {:output/write-status st} :state …)`. Expect BPM 99 and the status kept.
  - `state-first-keeps-the-other-effects-in-order-test` (NEW). Applies `(#'events/state-first (constantly m))` to w3's nine-key map: `:state` first, then `(keys (dissoc m :state))` in order. `nil` and `{}` pass through unchanged.
  - `flush-test` (NEW): without an app, true at once; run on `events/event-executor`, false at once (a future submitted there).
  - `write-state-test` (NEW): without an app, it writes `[:chains :cue-chains [0 0] :name]`, `:history` is unchanged, `[:project :dirty?]` stays false, and nothing reaches the output runtime.
  - `dev-events-are-routed-test` (NEW): `:dev/flush`, `:dev/write-state` and `:dev/reloaded` reach the new handler, with no "Unknown event domain" ERROR (`log-test/with-log`).
  - `effects-and-co-effects-are-shared-maps-test` (existing, `:36-47`): the list gains `:dev/deliver` and `:dev/swap-state`.
  - `lost-update-detector-test`, `lost-update-check-has-no-exemptions-test`, `lost-update-detector-is-off-outside-dev-mode-test` and `dispatch-continues-inline-test` (existing): pass unchanged.
- **`laser-show.state.core-test`** (NEW namespace; fixture `:each`: `init-state!`, with `guard-writes! nil` in a `finally`):
  - `no-guard-writes-from-any-thread-test`.
  - `strict-guard-refuses-a-write-off-the-event-thread-test`: with `:allowed? (constantly false)`, each of `swap-state!`, `reset-state!`, `reset-state-vals!`, `assoc-in-state!` and `init-state!` throws `ex-info` with `:thread`, and the state is unchanged.
  - `guard-allows-the-event-thread-test`.
  - `lenient-guard-logs-and-writes-test`: one ERROR, and the write lands.
  - `guard-lets-the-clipboard-through-test`: `assoc-in-state! [:ui :clipboard] x` under a refusing guard. Only if commit 9 lands before clipboard Step 4a; 4a then removes it with the exemption.
- **`laser-show.events.timing-handlers-link-test`** (existing): `carabiner-connected-keeps-the-listener-test` (NEW). The three events in §7. Other link keys are kept.
- **`laser-show.events.menu-system-handlers-test`** (existing, fixture `:10-13`):
  - `dev-reloaded-test` (NEW): `:styles` bumps the trigger; `:app` sets `[:ui :last-reload-timestamp]` to `:time`; no undo entry; not dirty.
  - `reload-handlers-return-effects-only-test` and `reload-styles-pipeline-keeps-trigger-test` (existing, `:52-67`): pass unchanged. Without an app the trigger now comes through `:dev/reloaded`, synchronously.
- **`laser-show.state.persistent-test`** (existing; fixture `cleanup-test-files`, `:24-36`): `test-save-project`, `test-load-project`, `test-round-trip` and `safety-round-trips-test` keep their names and assertions, and run on the pure fns (W6).
- **`laser-show.backend.output-lifecycle-test`** (existing): `app-stop-closes-engines-test` (`:658-667`) passes unchanged. With no app there is nothing to release, and the drain is as before.
- **`laser-show.services.show-clock-test`** (existing): `slow-event-cannot-move-a-cue-back-test` (`:116-136`) passes on the new wiring (T5).

**UI namespaces** (`with-toolkit` once, `with-app` each)

- **`laser-show.ui-test.app-lifecycle-test`** (NEW):
  - `old-and-new-app-share-the-event-thread-test` (*red today*: w6 gives BPM 120 on two pool threads). Keep the first app's handler, `start-test!` again, send a slowed event to the old handler and `:timing/set-bpm 99` to the new app, then `flush!`. BPM 99, and both events ran on `laser-show-events`.
  - `link-connection-survives-an-event-in-flight-test` (*red today* when the test calls today's inline block: w1 gives false). `link/connect-carabiner!` redefined to a fake, `:show-clock/status` slowed and in flight, then `connect-link!` and `flush!`. `:carabiner-connected?` is true and `:listener` is there.
  - `write-state-lands-after-the-event-in-flight-test`. Today's direct write is lost (w1's mechanism). `write-state!` lands, and so does the event's change.
  - `a-direct-write-while-the-app-runs-throws-test`.
  - `stop-writes-nothing-and-closes-each-device-once-test` (*red today*: w2a, two disconnects; w2b, two runs).
    - Setup: `midi/disconnect-all`, `osc/stop-server` and `link/disconnect-carabiner!` redefined to count; `[:backend :link :carabiner-connected?]` set with `write-state!`; a slowed `:show-clock/status` in flight.
    - Then `stop!` twice.
    - Expect each count to be 1, and `[:backend :input]` and `[:backend :link]` `identical?` before and after.
  - `stop-on-the-event-thread-does-not-wait-test`. Exit fn `#(app/stop!)`, then `:file/exit` on a clean project. `flush!`, wrapped to record its calls, returned false on `laser-show-events` with no `:dev/flush` dispatched, and the app is stopped.
- **`laser-show.ui-test.reentry-test`** (existing): `lifecycle-results-run-on-the-agent-test` and `dispatch-later-runs-on-the-agent-test` pass, checking the new thread name (`:18-19`).
- **The 20 migrated UI namespaces** (T3, T4) pass unchanged in behaviour.

**Kondo**

`clj -M:kondo --lint src test --parallel --fail-level error` reports 0 errors. A `state/swap-state!` added to a file in `src/` gives 1 error: check that, then remove the line.

**Probes**

Re-run w1–w6 after the work. w1, w2, w3 and w6 become the tests above. w4 stays as the evidence for W1, and w5 for the costs.

**Run**

1. `clj -M:mac-arm:test events.core state.core timing-handlers-link menu-system persistent output-lifecycle output-sync show-clock`
2. `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.app-lifecycle-test`, then `-n laser-show.ui-test.reentry-test`, then each migrated namespace.
3. Both full suites, last (the targeted-ui-testing skill).

## 9. Out of scope / alternatives considered

**Out of scope**
- The clipboard: the clipboard plan's Step 4a.
- Engine threads reading live state partway through a frame: sibling plan `01-engine-snapshot.md`.
- The `get-state` / `get-in-state` `sub-val` read API (`state/core.clj:59-78`, `:139-150`): no write needs it changed.
- MIDI and OSC objects and the Link listener held in app state (review 02 M4 "Devices"). This plan reads them to close them; it doesn't move them.

**Observed, not changed**
- `create-app` passes `:opts` and `:error-handler`, which `fx/create-app` ignores (03 §10; still at `app.clj:106-107`).
- The window between mounting (`app.clj:201`) and `set-dispatch-fn!` (`:204`): a view's imperative `dispatch!` there would take the fallback. The first render is only queued by then (`cljfx/renderer.clj:41-46`). Closing the window would mean building `create-app`'s handler and renderer separately.
- A late event of an old app still applies to the next app's state. With W3 A it no longer overlaps the next app's events.
- beat-carabiner may drop the connection 1 s after `connect` when no status packet came (`beat_carabiner/core.clj:537-552`), while app state still says connected.
- `stop!` doesn't clear the input mapper's dispatch fn.
- The Link handlers call `link/enable-link!` and the like inside handlers (`timing.clj:160-182`); those calls only log.

**Alternatives**
- **A merging `:state` effect** that writes only the paths the event changed: rejected in 03 (O5 C). It diffs every event, and conflicting writes still race.
- **Moving the connect into an effect on a future**, with the result coming back as an event: it changes nothing about who writes, and `start!` already does I/O for the clock, the mapper and the gamepad.
- **A `dispatch-and-wait!` fn**: the idiom is two calls, `dispatch!` then `flush!`.
- **cljfx's `:fx/sync`** for waiting: it can't time out, and it hangs when the handler throws (w5) or when called on the event thread.
- **`fx/create-app` replaced by its parts**, to set the dispatch fn before mounting: more change than that window is worth.
- **Disabling cljfx's `:context` effect:** nothing uses it. A kondo rule can't see it; the runtime guard would still catch a write that came from it off the event thread.
- The rest are the options of W1–W6 below.

## 10. Commit plan

Commits 2 and 3 come before 4, and 4 before 9: the strict guard needs the UI tests migrated first.

| # | Commit | Tests |
|---|---|---|
| 1 | `fix(events): every event writes its :state before its other effects run, so a write inside the turn (an inline follow-up, :output/write-status) can't be undone by it` | `state-runs-first-test`, `state-first-keeps-the-other-effects-in-order-test` |
| 2 | `feat(events): one event thread for every app instance, so an old app's late event can't run alongside the new app's` | `old-and-new-app-share-the-event-thread-test`; `reentry-test` with the new name |
| 3 | `feat(events): events/flush! waits until the queue is empty and events/write-state! writes on the event thread, for tests and the REPL` | `flush-test`, `write-state-test`, `dev-events-are-routed-test`, `write-state-lands-after-the-event-in-flight-test` |
| 4 | `test(ui): UI tests write app state with events/write-state! and wait with events/flush!` | the 20 namespaces; the full UI suite |
| 5 | `fix(app): the Link connection reaches app state through :timing/carabiner-connected, so an event in flight can't drop it; the unused [:styles :menu-theme] goes` | `carabiner-connected-keeps-the-listener-test`, `link-connection-survives-an-event-in-flight-test` |
| 6 | `fix(app): stop! writes nothing to app state and closes each device once` | `stop-writes-nothing-and-closes-each-device-once-test`, `stop-on-the-event-thread-does-not-wait-test`, `app-stop-closes-engines-test` |
| 7 | `refactor(dev): style and code reloads bump their triggers through :dev/reloaded` | `dev-reloaded-test`, `reload-styles-pipeline-keeps-trigger-test` |
| 8 | `refactor(state): remove the project helpers that wrote app state directly; the tests use the pure functions` | `persistent-test` |
| 9 | `feat(state): while an app runs, a write off the event thread throws (dev mode, start-test!) or is logged (production); kondo flags direct writers in src` | `state.core-test`, `a-direct-write-while-the-app-runs-throws-test`, the kondo check, both full suites |
| 10 | `docs: how this app writes state (CLJFX_STATE_MANAGEMENT.md, the app-repl skill, the state.core, events.core and domains docstrings)` | – |

**Size:** about 4½ days. Commit 4 is the largest (69 call sites, then the UI suite). Choosing W2 B and W3 B drops commit 2 and most of commit 9, which leaves about 3½ days.

---

## Decisions

> **DECISION W1 — How does a write inside a turn survive the event's `:state`? (`:output/write-status`, inline follow-ups, the dev reload)**
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★** | `:state` runs first in every event (`state-first`, outside `handle-event-pipeline`). The path-only `:output/write-status` stays | One step covers the whole class (w3). The no-app guarantee Phase 4 chose holds (w4). 31–36 ns an event (w5). No handler's order changes today (§1.5) | Two writers stay in the pipeline (`:state` and the path effect) |
> | B | Fold the status into `:state`, and give the synchronous interpreter (`event-handler`) one lock for the turn | One writer; the status gets the dev detector | Without the lock, w4 shows Stop undone. The lock touches every test and the fallback, and brings lock-order questions (the output runtime's `lock`, the clipboard's FX derefs) |
> | C | Fold the status into `:state`, with no lock | One writer | w4: without an app, the status thread undoes Stop |
> | D | Leave the code; add a test that no event returns `:state` with a path write | Nothing changes | Inline follow-ups keep depending on map order, and a nine-key map breaks it (w3) |
>
> **Your answer:**

> **DECISION W2 — What keeps direct writers from coming back?**
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★** | Both of these: kondo's `:discouraged-var` at `:error` in CI (static), and the runtime guard (strict in dev mode and `start-test!`, an ERROR log in production) | Kondo catches a new writer in `src/` without running it. The guard catches the UI tests, the REPL, dynamic `resolve`s, and a pipeline run off the event thread (the Phase 3 class). It costs 1.5–2.7 ns a write (w5). Production behaviour is unchanged (it logs and writes) | Two mechanisms. Needs the event thread (W3 A), or W3 B's name check |
> | B | Kondo only | No runtime code | UI tests and the REPL aren't covered (test files are exempt), and neither is a pipeline run off the agent |
> | C | The runtime guard only | Covers what runs | A new writer on a path no test exercises goes unseen |
> | D | Make the dev detector fail the suites (a property on the test aliases) | Already written | Catches only collisions that happen during a run, so it is flaky as a guard |
> | E | Make the write fns private, or move them to a test-support namespace | Structural | 90 unit-test lines plus 28 `init-state!` lines to move, and the REPL loses them; the guard gives the same protection while an app runs |
>
> **Your answer:**

> **DECISION W3 — One event thread for every app instance?**
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★** | One daemon single-thread executor, `laser-show-events`, passed as cljfx's `:fx/executor` for every app's agent | An old app's late event can no longer run alongside the new app's (w6). The guard and `flush!` know the event thread exactly. Same semantics: an agent already runs one action at a time | `reentry_test` and `show_clock_test`'s copy of the wiring change; the thread name in logs changes |
> | B | Keep the send pool, and recognise event threads by the prefix `clojure-agent-send-pool-` | No wiring change | w6 stays. The prefix covers every agent `send` in the JVM |
>
> **Your answer:**

> **DECISION W4 — What does `stop!` do with the device handles in app state?**
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★** | Write nothing. Claim `*app` once, `flush!`, read the handles once, close each once | No write at all, so nothing to lose (w2a) and no side effect inside a swap (w2b). The next start replaces the state anyway (`init-state!`) | After `stop!` app state still lists closed handles (only a REPL would see that) |
> | B | Close the devices, then dispatch NEW `:app/devices-released` and `flush!` | State tells the truth after `stop!` | On the event thread (Quit) it can only be queued; one more event for a state that is about to be replaced |
> | C | Keep the writes, but close outside the swap fn (read, close, `swap-state!` the result) | Smallest change | Still a write off the event thread: lost as in w2a, and refused by W2's guard |
>
> **Your answer:**

> **DECISION W5 — How do UI tests and the REPL write app state while the app runs?**
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★** | `events/write-state!` (an effect on the event thread, as the agent's next action; no undo, dirty flag, output sync or rig write, like today's direct writes) and `events/flush!` | Closes the race, and keeps the tests' meaning: the same arguments, the same state. One mechanical edit per line | A generic write effect in `src/` (kondo and §6 name its only dispatchers) |
> | B | Events only, plus `start-test!`'s initial state | Tests exercise real paths | Many writes have no event (fake MIDI devices, `[:vibe-runtime …]`, active cues). Set-up through events adds undo entries and dirty flags the tests don't expect |
> | C | `flush!`, then a direct write | No new effect | Narrows the race without closing it: FX-thread events and the output status at 4 Hz can still land in between |
>
> **Your answer:**

> **DECISION W6 — `persistent/save-project!`, `load-project!`, `new-project!`?**
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★** | Delete them. `persistent_test` uses the pure fns; the REPL uses the File events (`:file/save-as-result {:file-path}`, `:file/open-result {:file-path}`, `:file/new-project`) | Removes three direct writers, and a load path that skips the Open handler's rig report and every middleware | Four tests rewritten |
> | B | Keep them as REPL helpers that dispatch the File events and `flush!` | REPL convenience | Three more fns that duplicate the events, for no caller in `src/` or `dev/` |
>
> **Your answer:**

---

## Dependencies on the sibling plans

- **`01-engine-snapshot.md`** (engine threads reading live state partway through a frame): no dependency either way, and 01 says the same (its §10).
  - **Reads and writes.** 01 changes what the engines read; this plan changes who writes. After 01, the engines write no app state and read none.
  - **Files.** 01 edits no event, effect or `events/core.clj` key (its §6). The one file both plans touch is `test/laser_show/services/show_clock_test.clj`, in different places: this plan changes only its copy of the wiring (`:109-114`).
  - **01's new tests** set state with `state/swap-state!` in unit tests without an app (its §10). They are unaffected: there is no guard without an app, and kondo exempts `test/`.
  - **Future writers.** If anything in 01 ever writes app state from an engine or the clock thread, it has to be an event. W2's guard refuses it while an app runs.
- **The clipboard plan** (`plans/2026-11-13-keyboard-focus-window-lifecycle/04-phase4-clipboard.md`, Step 4a): no order either way. Its §0 "Sibling plans" note says the same.
  - **The clipboard write.** 4a removes `set-internal-clipboard!` (`state/clipboard.clj:31-34`, the write at `:34`) with every other clipboard write outside the pipeline. After 4a, row 13 of §1.2 is gone ("the direct-writes plan (02) has no clipboard row left", its §5).
  - **Shared edits.** Both plans edit the same three things:
    - the "Writers outside the pipeline" sentence in `events/core.clj` (`:36-41`);
    - the `effects` map (`:505-555`) and its docstring;
    - `effects-and-co-effects-are-shared-maps-test`.

    Whichever plan lands second edits what the other left (§4).
  - **If commit 9 lands before 4a,** it carries the `[:ui :clipboard]` exemption: in the guard, in the kondo config, and in `guard-lets-the-clipboard-through-test`. 4a then removes all three. If 4a lands first, commit 9 has no exemption.
  - **4a fits this plan's rule as it is.** Its handlers write `[:ui :clipboard]` in the `:state` they return. Its `:clipboard/write` and `:clipboard/read` effects write no app state; `:clipboard/read` calls back through the effect's `dispatch`, so the follow-up runs on the event thread. W1 A's `:state` first also makes sure a copy's `:state` is written before its `:clipboard/write` runs.
