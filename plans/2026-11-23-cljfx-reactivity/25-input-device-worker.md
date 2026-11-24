# 25 · Input device worker

**Status:** Implemented in `797d44e` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24).

No plan page was written before implementation; the design brief the implementer worked from and its hand-off report are recorded here. Paths are relative to `src/laser_show/` unless they say otherwise, and citations refer to the code at the time of writing.

## Design brief

Audit: "Opening devices blocks every other event" (section "Device and runtime boundaries"). Related open item: plans/2026-11-07-improvement-review/02-events-state.md M4 (live MIDI/OSC objects and the Link listener in app state) and 00-overview.md row 14 ("Device I/O is a separate open item (02 M4)").

Observed (events auditor, gated fakes at a7e414e):
- Settings' Refresh, Connect and Start Server controls (views/components/midi_settings.clj, views/components/osc_settings.clj) dispatch to handlers in events/handlers/input.clj (`handle-midi-refresh-devices`, `handle-midi-connect-device`, `handle-midi-auto-connect`, `handle-osc-start-server`, `handle-osc-stop-server`, disconnects) that enumerate/open MIDI devices or bind/close sockets BEFORE returning `:state`. A gated fake refresh ran on `laser-show-events`; a mapped tap and a queue marker waited until it returned.
- Auto-Connect is repeatable while connected: two calls against one fake device installed two receivers, both passed the name-based "connected" guard, and one note was delivered twice (input/midi.clj connect/auto-connect ~107–153,278–285).
- App state holds live device objects and handler fns (`[:backend :input :midi :devices]`, `:handlers`, `[:backend :input :osc :server]`, see state/domains.clj), and app/stop! `release-devices!` closes them from a state read.
Existing tests: test/laser_show/events/handlers/input_test.clj (stubs socket startup), test/laser_show/input/midi_test.clj, osc_test.clj; fixtures shaped like the libraries' output in test/laser_show/input/fixtures.clj.

Required:
- A device runtime owner (NEW namespace or an extension of input/midi + input/osc) with its own single daemon worker thread that owns MIDI receivers/transmitters and the OSC server. Handlers become pure: they return NEW registered effects (e.g. `:input/midi-refresh`, `:input/midi-connect`, `:input/midi-disconnect`, `:input/osc-start`, `:input/osc-stop` — names yours) carrying a request id; the worker performs I/O and dispatches completion events with plain data (available device names, connected device names, server-running?/port, error text). Handlers apply a completion only if it matches the latest request for that device/operation (reversed/stale results ignored).
- Idempotent operations: connecting an already-connected device (by stable device identity, not just name) adds no receiver; stopping a stopped server is a no-op; one note → one delivery.
- No Java device objects or handler fns in app state if achievable within this package (move them into the runtime owner; keep the plain status the views subscribe to). If removing them entirely is too large, at minimum move blocking I/O off the event thread and make connect idempotent — and report what remains.
- Keep enable/disable flags, channel filter, OSC address mappings, the input router dispatch path (`router/dispatch!`), `:vibe/release-all :only :input` on disconnect, and app/stop! releasing devices (through the runtime owner, once).
- Notices for failures (e.g. port won't open) as today via the completion handler.

Tests (NEW, names adaptable): `device-io-does-not-block-the-event-thread-test` (gate a fake refresh/connect in the device layer via with-redefs; a `:timing/set-bpm` dispatched after it is handled before the gate opens — use the mounted test app's event thread, laser-show.ui-test fixtures, or an equivalent executor-based unit test), `second-auto-connect-adds-no-receiver-test` (one fixture note → one router delivery), `stale-device-completion-is-ignored-test`, OSC start failure notice, stop! releases runtime handles. No real devices or sockets: stub the javax.sound/osc layer as existing tests do. Keep input handler, midi, osc, settings UI (laser-show.ui-test.settings-test) and app lifecycle tests passing.

Boundaries: 26 (physical input releases), 09-OSC (OSC port field/Start uses configured port) and 24 (learn lifecycle, Settings MIDI/OSC Learn status) follow you in this worktree — make OSC start read the configured port from state at handling time, and keep the MIDI/OSC learn code paths working as today (24 will redesign them). 19 (app generations, other branch) also edits app.clj stop! — keep your app.clj edits limited to device release.

## As implemented

**Changes**
- NEW `src/laser_show/input/devices.clj`: device owner with one background worker (lists MIDI inputs, connects/disconnects, starts/stops OSC server); open handles kept here, not app state. `submit!` queues; `release!` closes everything once, drops queued requests, refuses new ones until `open!`; `open-ports` reports plain data. Results go back through the dispatcher of the event that asked (19's fence).
- Connect idempotent by device identity (same `javax.sound.midi` device → nothing new opened); an input's messages delivered only while its connection is the registered one.
- `events/handlers/input.clj`: pure handlers (Refresh, Connect, Auto-Connect, Disconnect one/all, Start/Stop Server) record a request id under `[:backend :input :midi|:osc :requests]` and return one of five NEW effects `:input/midi-list`, `:input/midi-connect`, `:input/midi-disconnect`, `:input/osc-start`, `:input/osc-stop`. Five NEW result events `:input/midi-listed`, `-connected`, `-disconnected`, `:input/osc-started`, `-stopped`; each applies only if it answers the latest request of its kind (else `{}`).
- OSC start reads `[:backend :input :osc :port]` at handling time (ignores the button's `:port`); "Could not listen for OSC on port N" notice from the result.
- `events/core.clj` registers the five effects. `state/domains.clj`: plain status only; `:devices`, `:handlers`, `:server` gone.
- `input/midi.clj`, `input/osc.clj`: state-map fns replaced by `open-input!`/`close-input!`, `open-server!`/`close-server!`; `find-device` exact name first, then case-insensitive substring (no regex).
- `app.clj`: `release-devices!` calls `devices/release!`; one `(devices/open!)` line each in `start!` and `start-test!`; `create-app` untouched; `stop!` order unchanged (flush, release devices, retire).
- Tests: NEW `laser-show.input.devices-test` (10), input-test `stale-device-completion-is-ignored-test`, `midi-connect-results-test`; settings-test end-to-end Refresh/Connect/Disconnect; app-lifecycle stop test counts closes; midi-test migrated; NEW fake MIDI device in `input/fixtures.clj` (`midi-source`, `send-midi!`).

**Deviations**: latest request tracked per kind (MIDI list, MIDI connections as a whole, OSC server), not per device (superseded request's failure notice dropped). Behaviour: unconnectable MIDI input shows a notice; failed OSC start turns `:enabled?` off; Stop Server stops letting messages in immediately and marks stopped on worker confirm; `:vibe/release-all :only :input` fires when the disconnect result arrives.

**Still open**: learn promises (`:learn-mode`) still in app state (24). Link listener still in app state; Carabiner closed from a state read. `plans/2026-11-20-state-integrity/probes/state_write_probes_test.clj` calls removed `midi/disconnect-all` (won't compile if run; plans/ folder — note: that folder is deleted in the user's working tree). `dev/reload.clj` docstring says MIDI/OSC "managed by state atoms" (stale).

**For 26**: retirement signal = an applied `:input/midi-disconnected` (a superseded disconnect result releases nothing). `:input/midi-disable` and `:input/osc-stopped` don't release anything today.
**For 24**: status fields `:connected-devices`, `:enabled?`, `:server-running?`; events `:input/midi-disconnected`, `:input/osc-stopped`, failed `:input/osc-started`. Learn code untouched.
**For 09-OSC**: Start still sends `:port` (now ignored) — remove it; port field commit is 09-OSC's. A successful start writes the listening port back to `:port`. Stub sockets by redefining `osc/open-server!` (port, handler) and `osc/close-server!`; see `with-fake-server` in devices-test.
