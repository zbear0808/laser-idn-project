# Improvement Plan: Input & Triggers

**Date:** 2026-11-07
**Area:** `src/laser_show/input/**`, `src/laser_show/triggers/**`, plus the code that connects them to events/state (`events/handlers/input.clj`, `events/handlers/input_mapper.clj`, `events/handlers/triggers.clj`, and the gamepad/link wiring in `app.clj`).
**Related plans:**
- `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md` (keyboard → command routing; there is no keyboard *input source* yet, see F17)
- `plans/v3/plan-grid-trigger-v2.md` (the original design for learn, trigger-map, and unmapped OSC triggers; §1.4 and §1.5 have regressed, see F5 and F9)

---

## 1. Summary

The input layer is cleanly split on paper. Device adapters (`midi`, `osc`, `gamepad`, `link`) turn raw messages into plain event maps. `router` fans them out. `input-mapper` sends continuous values to a lock-free store (`input/store.clj`) that the frame loop reads, and sends discrete presses to `:trigger/input` in the cljfx event pipeline. The store/router split is good and follows the "keep high-frequency updates separate" rule in `docs/CLJFX_STATE_MANAGEMENT.md`.

In practice, several input paths are broken in production while their unit tests still pass, because the tests build messages by hand in a different shape than the libraries actually produce:
- MIDI on channels 2–16 is dropped.
- MIDI and OSC enable/disable writes the wrong state key, so OSC can never be turned on.
- Disconnecting a MIDI device leaves its handler attached, so it keeps sending events.
- The gamepad sends malformed events and re-fires held buttons every 16 ms.
- Link downbeat sync calls a library function with the wrong clock and expects a return value it never gives.
- Five OSC tests currently fail (verified with `clojure -M:test`).

The `laser-show.triggers.*` namespaces (state machine, shuffle bag, janitor, tap tempo) are pure and tested but **nothing in `src` uses them**. `events/handlers/triggers.clj` re-implements a smaller version of the same ideas by hand, with its own semantic bugs.

---

## 2. Findings

Severity: **High** = broken user-facing behavior or show-time risk. **Med** = correctness, latency, or maintainability risk. **Low** = cleanup.

### Device adapters

**F1. MIDI channels 2–16 are silently dropped. (High)**
`input/midi.clj:45-73`. `midi-msg->event` branches on `(:status msg)`. overtone's `midi-msg` maps the *full status byte* through `midi-shortmessage-keys` (`overtone/midi.clj:219`), and that map only has channel-0 values (144, 176, …). So `:status` is `nil` for every channel above 0. Checked in a REPL:
`{:status nil, :command :note-on, :channel 1} => nil`, and the same for CC on channel 5.
The correct field is `:command`, which is already channel-stripped and already turns velocity-0 note-on into `:note-off`. Tests (`test/laser_show/input/trigger_test.clj:11`) pass a hand-written numeric `:status 144`, which hides the bug.

**F2. MIDI/OSC enable flag written under the wrong key; OSC can never be enabled. (High)**
- `events/handlers/input.clj:38,43,130,135` write `:enabled`.
- `midi.clj:91`, `osc.clj:133`, `views/components/midi_settings.clj:91` and `osc_settings.clj:82` read `:enabled?`.
- `subs.clj:639,675` read `:enabled`, so the codebase disagrees with itself.

Result: the checkbox never changes, MIDI can't be disabled (default `:enabled? true`, `state/domains.clj:401`), and OSC stays at `:enabled? false` (`domains.clj:409`), so every OSC message is dropped at `osc.clj:133` even when the server is running.

**F3. Disconnecting a MIDI device does not detach it. (High)**
`midi.clj:127-140` only removes the device from state; the comment says "will be garbage collected". The `Receiver` set by `midi-handle-events` (`overtone/midi.clj:243`) stays attached to the open `Transmitter`, and the handler only checks `:enabled?`, not `:connected-devices`. A "disconnected" controller keeps firing cues. `shutdown`/`disconnect-all` have the same problem, and `app/stop!` (`app.clj:166-178`) never calls any of them. On Windows, the open device also blocks other apps from opening the port.

**F4. Gamepad is broken end to end. (High)**
- `gamepad.clj:61`: `(get prev-ctrl btn-kw false)` looks up `:a` in `{:buttons … :axes …}`, so it is always `false`. A held button sends `:pressed` every 16 ms, and `:released` is never sent.
- `app.clj:161` passes `events/dispatch!` (the cljfx pipeline) instead of `router/dispatch!`. The raw `{:type :trigger …}` maps have no `:event/type`, so `handlers.clj:36` `(namespace nil)` throws an NPE on the agent thread for every gamepad event. Gamepad input never reaches the router, the store, or the mapper.
- Axis changes above 0.005 are sent at about 60 Hz into the cljfx agent. That breaks the "high-frequency stays out of the context" rule even once routing is fixed. Axes should go to the store only.
- `start!` (`:86-93`) creates a non-daemon thread, has no try around `initSDLGamepad` (a missing native library aborts `app/start!`), and is never stopped. Each REPL `start!` leaks another poller.
- `stop!` (`:95-99`) calls `quitSDLGamepad` right after `interrupt`, while the poll thread may still be inside `updateControllers` (native use-after-free risk).
- Controller ids are positional indexes (`[ctrl-id btn-kw]`), so learned bindings move when controllers are re-plugged.

**F5. Unmapped OSC numeric messages become CC 0, not triggers; 5 tests fail. (High)**
`osc.clj:109-116`. Any unmapped message with a numeric first argument becomes `control-change` with `control 0`, so:
- Every unmapped fader overwrites the same store key `[:osc 0 0]` (`input_mapper.clj:52-53`).
- Common OSC buttons (TouchOSC sends `1.0`/`0.0` floats) never become triggers, so generalized learn (`input.clj:215-216`, which only accepts note-on and trigger) can't capture them.

This contradicts the design in `plans/v3/plan-grid-trigger-v2.md` §1.4 and fails `trigger_test.clj:37-56` (5 assertions; the commit "build out vibe tapping" introduced the change).

**F6. OSC value parsing heuristics are lossy. (Med)**
`osc.clj:65-72`:
- Integers are divided by 127, so a `0`/`1` toggle becomes `0.0079`, and ints above 127 are clamped.
- Booleans (`T`/`F`) and strings fall to `:else 1.0`, so a `false` reads as *pressed*.

There is no per-mapping range, so a mapping can't say "this address sends 0–1 floats" or "0–127 ints".

**F7. OSC lifecycle gaps. (Med)**
- `osc/init` (`osc.clj:324-338`) is never called, so `default-mappings` are only loaded through the manual "Load defaults" button.
- `start-server` (`:150-170`) closes the existing server first. If binding the new port fails, state keeps the closed `:server` and reports not running, and the port is lost.
- `stop!` in `app.clj` doesn't stop the OSC server, so a dev restart gets "address in use".
- `register-handler` (`:238-246`) returns `nil`-safe state, but its `osc-handle` side effect runs inside a supposedly pure function.

**F8. Link: downbeat sync can never work; BPM sync misses the first update. (High/Med)**
- `link.clj:207-219`: `bc/beat-at-time` expects Link-timeline microseconds based on `System/nanoTime` (see `beat_carabiner/core.clj:603,613`), but gets `currentTimeMillis*1000`. It is also *asynchronous*: it writes a socket message and returns the stream-flush result, not a beat. `(mod beat 1.0)` therefore throws, gets caught and logged, and returns `nil`, so `sync-to-downbeat!` (`:221-243`) does nothing on every call (wired at `timing.clj:106-112`). The result arrives later through the status listener (`:beat` in client state), which this code ignores.
- `link.clj:73-77` compares the incoming BPM against `(:link-bpm link-state)`, which `:timing/link-bpm-changed` (`:63-65`) already updates on every status. If the user turns sync on while Link is steady, `:timing/set-bpm` never fires. It should compare against the app's `[:timing :bpm]`.
- `:63-65` sends `link-bpm-changed` on every Carabiner status, even when the value is unchanged, causing state churn and re-renders.
- The listener ignores `:link-enabled?`, so it applies BPM even when Link is "disabled" in the UI.
- `[:timing :bpm]` is document state (`state/history.clj:23`), so every Link `set-bpm` and every tap-tempo result pushes an undo snapshot (`history.clj:133-157`). A drifting Link session can push real edits out of the 50-deep undo stack.

### Integration with events/state

**F9. Generalized learn leaks threads and races the agent. (High)**
`events/handlers/input.clj:204-241`:
- `future` blocks forever on `@learn-promise`. `handle-input-cancel-learn` (`:243-248`) unregisters the handler but never delivers, so every cancelled or restarted learn leaks a pooled thread forever.
- The future writes with `state/swap-state!` while the agent applies handler results with `reset-state!` (`events/core.clj:65-69`). The same press also goes through `::generalized-mapper`, which dispatches `:trigger/input`. If the agent reads state before the future swaps and resets after, **the learned binding is lost** (lost update).
- The learning press may also fire the cell's old binding, depending on the order of global handlers in a hash map.
- The binding bypasses history and dirty tracking.

This breaks the pure-handler / effects-as-data pattern in `CLJFX_STATE_MANAGEMENT.md` ("Pattern 6").

**F10. MIDI/OSC learn modes are dead features that store promises in state. (Med)**
`input.clj:88-111, 175-198`:
- A promise goes into `[:backend :input :midi :learn-mode]` and is delivered, but nothing reads it and `:learn-mode` is never cleared. The UI stays on "Waiting for MIDI input…" (`midi_settings.clj:192-199`) until the user cancels.
- The handler returns an undeclared effect key `:learn-promise`.
- Device objects, receivers, handler fns, servers and promises all live inside the cljfx context (`domains.clj:401-421`). That goes against the project's "pure data" principle (`CONTRIBUTING.md`) and makes the state hard to persist or diff.

**F11. Side effects inside event handlers. (Med)**
Several handlers open ports or sockets, mutate the router atom, or spawn threads, all on the cljfx agent thread:
- `handle-midi-connect-device` / `auto-connect` (`input.clj:53-63,113-121`) open MIDI ports.
- `handle-osc-start-server` (`:137-147`) binds a socket.
- `handle-*-start-learn` mutates the router atom and spawns futures.
- `handle-midi-refresh-devices` (`:45-51`) enumerates devices synchronously, which is slow on Windows and stalls every queued UI event.

These should be declared effects (`:input/connect-midi`, `:input/start-osc`, …) registered in `events/core.clj:352-371`.

**F12. Trigger latency: two agent hops and history diffing on every press. (Med)**
Path for one note: MIDI thread → `router/dispatch!` → `input-mapper` → `events/dispatch!` (agent queue shared with UI drags) → `wrap-history` (`extract-document-state` ×2 + `=`) → `:trigger/input` returns `:dispatch` → `effect-dispatch` puts it back in the queue → `:grid/trigger-cell` → `reset-state!`. During heavy UI activity (canvas drags), trigger latency grows with the queue backlog.
- `input_mapper.clj:2-10` still describes an "O(1) lookup, bypass cljfx queue" design that no longer exists.
- The input timestamp sent as `:time` (`input_mapper.clj:70`) is **overwritten** by the `:time` co-effect (`events/core.clj:58-61`), so tap-tempo from MIDI uses processing time rather than arrival time, adding jitter.

**F13. `events/handlers/triggers.clj` semantic bugs. (Med)**
- `:35-58`: `:momentary` release sends the *same* event as press (`:grid/trigger-cell` / `:effects/toggle-cell`). In toggle mode release turns the cue off by accident; in retrigger mode it restarts the cue. There is no "release → stop" action.
- `:50`: `:blackout` maps to `:transport/stop`, the same as `:stop-all`. A blackout should mute output without resetting the clocks.
- `:25-33`: `definition-action` runs `some` over a hash map of definitions, so when several definitions match, the winner depends on hash order.
- `:96-102`: `handle-fire` goes back through `definition-action`, so firing trigger X can run trigger Y's action. When there's no input-event, it passes the *pattern* (possibly containing `:*` or fns) as if it were an event.
- `:60-84`: every discrete input (including unmapped ones and note-offs) rewrites `:last-input-time`, `:tap-history`, `:held-inputs` and `:active-triggers` in the root context. `subs/trigger-runtime` (`subs.clj:725`) subscribes to the whole subtree, so every MIDI note re-renders its consumers. Unmapped presses with no release (OSC address-only messages, gamepad per F4, devices without note-off) build up forever in `:held-inputs` and `:active-triggers`. `janitor.clj` exists for this but isn't wired.
- `:111-125`: `handle-tick` rebuilds the `active-groups` map unconditionally (new identity, so a re-render). Nothing dispatches `:trigger/tick` or sets `:entered-at`.
- `handlers.clj:61` routes only `"trigger"`, so `:trigger-group/toggle-lock` (namespace `"trigger-group"`) hits "Unknown event domain" in the real pipeline. The unit test calls `triggers/handle` directly and misses this.

**F14. Three tap-tempo implementations. (Med)**
- `triggers/tap_tempo.clj` (pure, tested, validates intervals, bounded) is unused.
- The real path is `timing.clj:28-45` (unbounded `:tap-times` vector) plus `events/core.clj:85-99`, an effect that reads `get-raw-state` *after* the state effect and relies on effect-map key order.
- `triggers.clj:67-69` keeps a third `:tap-history` from every input.
- The thresholds disagree: 2000 ms timeout and 40–300 BPM clamp in one place, 20–300 BPM validity in another.

**F15. The `laser-show.triggers.*` engine is unused and has gaps. (Med)**
`core`, `state_machine`, `shuffle_bag`, `janitor`, `definitions` and `tap_tempo` are only used by their tests (grep: only `matcher` is required from `src`, at `events/handlers/triggers.clj:7`).
- `state_machine.clj:24` appends to `:history` without limit; a running show would leak memory.
- `core.clj` expects `:definitions` as a vector, but state stores a map `{id → def}` (`domains.clj:90`).
- `core/make` filters out disabled definitions but `add-definition` doesn't.
- `matcher` supports `fn?` values, which can't be saved in EDN project files.

Either wire it in (as the runtime behind `handlers/triggers.clj`) or delete it.

### Router / events / store

**F16. Router error handling and API surface. (Low/Med)**
- `router.clj:116-127` logs only `.getMessage` (no stack, no handler id, no rate limit). A handler that throws on every CC at about 1 kHz floods the log.
- `log-event!` (`:90-101`) re-reads the atom, and `take-last` copies the log on every event once it is full.
- `router.clj:10-30` are one-line wrappers around `store` that duplicate its API.
- `on-note!`, `on-control!`, `on-trigger!`, `on-any!`, `dispatch-many!` and `events/event-id` are unused in `src`.

**F17. No keyboard input source. (Med)**
`trigger_bank.clj:62` displays `:keyboard` bindings, and tests use `{:source :keyboard :id :space}`, but nothing produces keyboard input events. The 2026-10-07 shortcut plan builds a scene-level key filter and a command registry. Keyboard *cue triggers* should hook into that same filter (as a fallback when no command matches and no text input has focus) instead of adding a second key listener.

**F18. Event model inconsistencies. (Low)**
- `events.clj:7-15` lists `:pitch-bend`, but there is no constructor or conversion for it; MIDI pitch-bend, aftertouch and clock are all dropped.
- Timestamps use `currentTimeMillis` instead of `nanoTime` or the device timestamp.
- `normalized-to-midi` (`:172-175`) truncates and doesn't clamp, and `unscale-value` divides by zero when `min = max`.
- Velocity is captured but never passed to the cue (missed opportunity: velocity → intensity).

**F19. Channel numbering is ambiguous across the boundary. (Low/Med, cross-area)**
Input stores 0-based channels (`input_mapper.clj:46`). `animation/modulator_evaluators.clj:175-184` guesses by falling back between channel 0 and channel 1, which mixes up two physical channels. The convention should be settled once at the input boundary (0-based internally, 1-based in the UI).

**F20. Dead or duplicated state schema. (Low)**
- `domains.clj:416-421`: `:keyboard`, `:router`, `:values` under `[:backend :input]` are never read (router state is a separate atom; values live in `input/store`).
- `domains.clj:228-231`: `[:config :midi]` and `[:config :osc]` duplicate the backend settings and are unused, so MIDI device, OSC port and enable state are **not persisted** in the project.
- `midi.clj:255-256` `:note-mappings` and `:cc-mappings` are unused.
- `link.clj:245-251` `connected?` is deprecated.

### Tests

**F21. Tests exercise invented message shapes, not real ones. (Med)**
- MIDI tests use numeric `:status` (F1).
- There are no tests for: the `input.clj` enable/disable handlers (F2), MIDI disconnect (F3), gamepad diffing (logic not extracted, F4), learn lifecycle and cancel (F9), the Link listener when sync is turned on at a steady BPM (F8), momentary release (F13), or `trigger-group` routing through `handlers/handle-event` (F13).
- `trigger_test.clj:37-56` is currently failing (F5).

---

## 3. Proposed Improvements

### Quick wins (each S, low risk)

**Q1. Fix MIDI message decoding (F1).**
Steps:
- Switch `midi-msg->event` to branch on `(:command msg)`, keeping numeric-status fallback only for hand-built maps.
- Add `:pitch-bend` as a CC-like event (`control :pitch-bend`) if wanted.
- Add tests that build messages with `overtone.midi/midi-msg` from real `javax.sound.midi.ShortMessage` instances on channels 0, 1 and 15, including note-on with velocity 0.

Files: `input/midi.clj`, `test/laser_show/input/trigger_test.clj` (or a new `midi_test.clj`).

**Q2. Unify the enable key on `:enabled?` (F2).**
Steps:
- Change the four `assoc-in` paths in `events/handlers/input.clj` and the two subs in `subs.clj:639,675` to `:enabled?`.
- Add handler tests asserting that `midi.clj`/`osc.clj` `enabled?` sees the change.

Files: `events/handlers/input.clj`, `subs.clj`, tests.

**Q3. Make MIDI disconnect real (F3).**
Steps:
- Store the `midi-in` map in state as now (until M2).
- In `disconnect-device`, call `(.setReceiver (:transmitter dev) nil)`, `(.close (:transmitter dev))` and `(.close (:device dev))`, each in a try.
- Also check `(contains? (:connected-devices s) device-name)` in the handler as a second guard.
- Call `disconnect-all` from `app/stop!`.

Files: `input/midi.clj`, `app.clj`.

**Q4. Fix gamepad routing and diffing (F4).**
Steps:
- Change the lookup to `(get-in prev-ctrl [:buttons btn-kw] false)`.
- Pass `router/dispatch!` in `app.clj:161`.
- Mark the thread as a daemon and name it `"gamepad-poll"`.
- Wrap `initSDLGamepad` in a try (log and return `nil`).
- Store the returned handle in a `defonce` atom and call `stop!` from `app/stop!`.
- In `stop!`, `.join` the thread (with a timeout) before `quitSDLGamepad`.
- Pull the diff out into a pure `(diff-controller-states prev next) -> [events]` and unit-test it.

Files: `input/gamepad.clj`, `app.clj`, new `test/laser_show/input/gamepad_test.clj`.

**Q5. Restore unmapped-OSC trigger semantics (F5).**
Steps:
- Restore the behavior from `plan-grid-trigger-v2.md` §1.4, but keep faders usable: for unmapped addresses, *always* emit a trigger (`>0` → pressed, `0` → released) **and** write the value to the store under `[:osc path]` only.
- Drop the `control 0` CC so that unmapped faders stop colliding.
- Update `input_mapper` so OSC store writes don't depend on `control-change` events.
- Get the 5 failing assertions passing.

Files: `input/osc.clj`, `events/handlers/input_mapper.clj`, `test/laser_show/input/trigger_test.clj`.

**Q6. Route the `trigger-group` namespace (F13).**
Steps:
- Add `"trigger-group" (triggers/handle event)` to `events/handlers.clj`.
- Add a test that goes through `handlers/handle-event`.

Files: `events/handlers.clj`, `test/.../triggers_test.clj`.

**Q7. Link listener fixes (F8, parts 2–4).**
Steps:
- Send `link-bpm-changed` only when `bpm-changed?` against the stored `:link-bpm`.
- Gate the `set-bpm` decision on `(and link-enabled? sync-enabled?)`, and compare against the *app* BPM. Pass in a `get-app-bpm-fn` or have `get-state-fn` return both.
- Add a test: sync turned on while Link is steady at 128 and the app at 120 → `set-bpm 128`.

Files: `input/link.clj`, `app.clj:145-151`, `test/laser_show/input/link_test.clj`.

**Q8. Stop trigger runtime churn (F13 part 5, F14 part 3).**
Steps:
- In `update-runtime`, skip all runtime writes when no action matched (unmapped inputs).
- Drop `:tap-history` and `:last-input-time` from per-event updates.
- Have `handle-tick` return state unchanged (`identical?`) when no group changed.
- Narrow `subs/trigger-runtime` consumers to specific paths.

Files: `events/handlers/triggers.clj`, `subs.clj`.

**Q9. Router hygiene (F16).**
Steps:
- Log with `(log/error e "Input handler failed" id)`, rate-limited per handler id (for example, the first error, then every Nth).
- Delete the store wrappers in `router.clj` and point callers at `input.store`.
- Delete the unused `on-*` helpers, or mark them as REPL helpers.
- Fix the stale `input_mapper` ns docstring.

Files: `input/router.clj`, `events/handlers/input_mapper.clj`, `test/.../router_test.clj`.

### Medium refactors (M, medium risk)

**M1. Make learn a proper, cancellable, event-driven flow (F9, F10).**
Steps:
1. Replace promise + `future` with a router global handler that only calls `events/dispatch! {:event/type :input/learn-captured :input-event e}` when `[:backend :input :learning-target]` is set. Use a flag in a small atom, or read `get-raw-state`.
2. `:input/learn-captured` is a pure handler that writes `[:config :input :trigger-map key]`, clears `:learning-target`, and goes through `wrap-history`, so it is undoable and marks the project dirty.
3. Suppress the mapper's `:trigger/input` for the event that was captured. One way: the mapper checks the learning flag first.
4. Remove the MIDI/OSC-specific learn handlers, `:learn-mode` promises and `:learn-promise`. Either point the settings-panel buttons at the generalized learn ("monitor" mode showing the last event), or remove them.
5. Add a learn timeout (for example 15 s) using `:dispatch-later`.

Files: `events/handlers/input.clj`, `events/handlers/input_mapper.clj`, `views/components/midi_settings.clj`, `osc_settings.clj`, `subs.clj`, `state/domains.clj`, tests.

**M2. Move device handles out of cljfx state; do device I/O through effects (F10, F11, F20).**
Steps:
1. Create a `laser-show.input.devices` runtime registry (a `defonce` atom, like `input/store`) holding MIDI `midi-in` maps, the OSC server, and gamepad/link handles.
2. Keep only serializable status in the context: connected device names, `:server-running?`, port, last error.
3. Add effects `:input/midi-connect`, `:input/midi-disconnect`, `:input/osc-start`, `:input/osc-stop` and `:input/midi-scan` in `events/core.clj`. Each does the I/O off the agent (`future`) and dispatches a `*-result` event.
4. Move user settings (enabled, port, auto-connect device names, channel filter) to `[:config :input …]`, reusing the dead `[:config :midi]`/`[:config :osc]` slots, so they persist. Remove the unused `:keyboard`/`:router`/`:values` keys.
5. Add an `input/lifecycle` `start!`/`stop!` called from `app/start!`/`stop!` that restores saved MIDI devices and the OSC server, and shuts everything down cleanly (fixes F3, F4 and F7 restart issues).

Files: `input/midi.clj`, `input/osc.clj`, `input/gamepad.clj`, `input/link.clj`, new `input/devices.clj`, `events/core.clj`, `events/handlers/input.clj`, `state/domains.clj`, `app.clj`, `subs.clj`, the two settings views.

**M3. OSC value model (F6, F7).**
Steps:
- Make values per mapping: `{:type … :range [min max]}` (default `[0 1]`, or `[0 127]` when explicitly configured).
- Treat booleans as `1.0`/`0.0` and ignore strings for triggers.
- Call `load-default-mappings` on first init only when `:address-mappings` is empty.
- If a rebind fails, keep the old server running (bind the new one first, then close the old).

Files: `input/osc.clj`, `osc_settings.clj`, tests.

**M4. Single-hop, timestamp-faithful trigger dispatch (F12).**
Steps:
1. Have the mapper send `:input-time` (the input timestamp) instead of `:time`, so the co-effect no longer overwrites it.
2. In `handlers/triggers.clj`, compose the pure domain handlers directly (call `grid/handle` or `effects/handle` with the same `state`) instead of returning `:dispatch`. This removes one agent hop and one `reset-state!`.
3. Add `:trigger/input` to `history-excluded-events` *only* when no document state changes, or make `wrap-history` short-circuit on `identical?` document subtrees before calling `extract-document-state`.
4. Measure with `docs/PROFILING.md` tooling: time from MIDI arrival to `reset-state!`, under an idle UI and during a canvas drag.

Files: `events/handlers/input_mapper.clj`, `events/handlers/triggers.clj`, `events/handlers/history.clj`, `events/core.clj`.

**M5. Fix trigger action semantics (F13).**
Steps:
- Add explicit press/release actions: `:momentary` press → `:grid/activate-cell`, release → `:grid/deactivate-cell` (new idempotent grid events instead of toggles).
- Map `:blackout` to a real output mute (coordinate with the output pipeline area).
- Make definition order deterministic (sorted by a `:priority`, then by id), or keep the definitions as a vector.
- Make `handle-fire` look up the action by `trigger-id` directly.
- Wire `janitor/remove-expired` into `:trigger/tick` to clear stale held or active inputs, and dispatch `:trigger/tick` from an existing 1 Hz timer or via `:dispatch-later`.

Files: `events/handlers/triggers.clj`, `events/handlers/grid.clj`, `events/handlers/effects.clj`, tests.

**M6. One tap-tempo implementation (F14).**
Steps:
- Make `triggers/tap-tempo` the only algorithm.
- Store its state at `[:timing :tap]` and compute BPM inside `handle-timing-tap-tempo` (pure, no `:timing/calculate-bpm` effect reading raw state).
- Use the input timestamp from M4.
- Exclude tap and Link BPM changes from undo, or merge them into one snapshot (F8 part 5).

Files: `triggers/tap_tempo.clj`, `events/handlers/timing.clj`, `events/core.clj`, `events/handlers/history.clj`, `events/handlers/triggers.clj`, tests.

### Larger efforts (L, higher risk)

**L1. Decide the fate of the `laser-show.triggers.*` engine (F15).**
Either:
- **(a) Adopt it** as the runtime for `[:triggers :definitions]`: definitions as a map keyed by id with a stable order, a capped state-machine `:history` (via `janitor/prune-history`), shuffle bags stored in `[:trigger-runtime :shuffle-bags]`, and `handlers/triggers.clj` reduced to a thin adapter over `core/dispatch` that turns `:effects` into domain events. Or:
- **(b) Delete** the unused namespaces and their tests.

In both cases, ban `fn?` matchers in persisted definitions (keep them for tests or code-defined triggers only). Recommendation: (a) only if grouped or sequenced triggers are on the roadmap; otherwise (b).

Files: `triggers/*`, `events/handlers/triggers.clj`, `state/domains.clj`, tests. Risk: medium (behavior change for definitions).

**L2. Correct Link phase sync (F8 part 1).**
Steps:
1. Replace `get-link-beat-position` with an async request: call `bc/beat-at-time` with `(.toMicros TimeUnit/NANOSECONDS (System/nanoTime))`.
2. Read the reply from the status listener or client state (`:beat` / phase data), and send `:timing/set-phase-offset-target` from there.
3. Never call this from a hot path.
4. Honor `:latency-ms` through `bc/set-latency`.
5. Add an integration test under `test/integration` that is skipped when Carabiner is absent.

Files: `input/link.clj`, `events/handlers/timing.clj`. Risk: medium (external daemon behavior, needs manual verification with Ableton or another Link peer).

**L3. Keyboard as an input source (F17), built on the shortcut plan.**
Once the scene-level key filter from `2026-10-07-keyboard-shortcuts-and-focus-consolidation.md` exists:
- Send unmatched, non-text-focus `KEY_PRESSED`/`KEY_RELEASED` events (with auto-repeat filtered out) to `router/dispatch!` as `(events/trigger :keyboard <KeyCode-keyword> :pressed|:released)`.
- Learn then works for keys with no extra code.
- Define priority clearly: command registry first, cue triggers second.

Files: `views/root.clj` (scene filter), `input/events.clj`, the shortcuts registry from that plan. Risk: medium (focus interactions).

**L4. Input test harness (F21).**
Steps:
- Add `test/laser_show/input/fixtures.clj` with real-shaped message builders: overtone `midi-msg` from `ShortMessage`, osc-clj-style `{:path :args}` with Float/Integer/Boolean args, and Carabiner status maps.
- Add an end-to-end pure test: raw msg → adapter → router → mapper (with a capturing dispatch fn) → `handlers/handle-event` → expected `:playback :active-cues`.
- Cover learn → bind → press → cue active.

Files: `test/laser_show/input/*`, `test/laser_show/events/handlers/*`. Risk: low.

---

## 4. Suggested Order of Work

1. **Stop the bleeding (one PR, about a day):** Q1, Q2, Q5, Q6, Q4, Q3, Q7. All are small and each fixes a feature that is broken today. Add the real-shape tests from L4 alongside, starting with the MIDI and OSC fixtures, so these don't regress.
2. **Hygiene:** Q8, Q9.
3. **Learn and lifecycle:** M1, then M2. M1 is simpler once M2's registry exists, but it can go first; do M1 first if learn bugs are hurting users.
4. **Semantics and latency:** M4, then M5 and M6. M4's composed dispatch changes how M5's press/release events are emitted, so do M4 first.
5. **Strategic:** L1 (decide adopt vs. delete before more work lands in `handlers/triggers.clj`), L2, L3.

### Dependencies on other areas
- **events/state:**
  - M2 adds effect keys to `events/core.clj` and changes `state/domains.clj` and persistence (config now holds input settings).
  - M4 and M6 touch `wrap-history` and `history-excluded-events`.
  - F9's lost-update race is one example of a general problem: `effect-state` uses `reset-state!` while other threads use `swap-state!`. The events/state area should decide on a single-writer rule for the context.
- **views/UI:**
  - Q2 and M1 change `midi_settings.clj`, `osc_settings.clj` and `subs.clj`.
  - L3 depends on the scene-level key filter and command registry from `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md`.
  - Q8 narrows `trigger-runtime` subscriptions.
- **animation/effects:**
  - F19's channel convention and `[:osc path]` store keys (Q5) affect `modulator_evaluators.clj:171-199`. Agree on the store key schema (`[:midi ch0 cc]`, `[:osc path]`, `[:gamepad ctrl axis]`) before Q5 lands.
  - Velocity → intensity (F18) would be a new modulator input.
- **output pipeline:** a real `:blackout` (M5) needs an output-mute primitive in the backend/multi-engine.
- **testing/build/tooling:** `trigger_test.clj` is red today and should block CI. L4's fixtures should live with the other test helpers, and the Link integration test (L2) needs a "skip if daemon absent" convention.


---

## Carried over from old plans (2026-11-08 audit, see 07-old-plans-audit.md)
- **Reference for F5** (from the deleted `plans/v3/plan-grid-trigger-v2.md` §1.4): unmapped OSC messages with positive or empty arguments should be `:trigger` events with the OSC address string as `:id`, so ad-hoc OSC buttons can be learned without a static mapping. This is currently broken at `osc.clj:109-112` (unmapped messages become `control-change :osc 0 0`).
- **Lesson from that plan's learn design:** "register a temporary router handler, capture the next note, write the mapping" was implemented as `future` + promise + `swap-state!` from outside the event flow, which is F9. The redesign must keep learn inside events and effects.
- **Keyboard trigger source:** `{:source :keyboard}` exists in the trigger key schema but there's no input source behind it (F17). Coordinate it with `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md`.
