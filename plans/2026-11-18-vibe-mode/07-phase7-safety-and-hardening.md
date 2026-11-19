# Phase 7: safety and hardening

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** Proposed. Needs Phases 1–6   **Last verified against:** `b1da0f2` (probes v4, v10, v12 in `00-overview.md` §3)
**Supersedes / Superseded by:** none   **Related findings:** the review overview's "Next" item (the event agent's blocking I/O) is the real fix for R-1; this phase only keeps the performer out of its way

Everything that makes a show survive a bad moment: a lock against accidental edits, protection against a key that never lets go, a loud "no output", an audience mark, a soak test, a hardware checklist and the docs. Parts of it can land earlier; the blackout and the audience rule already did (Phase 1).

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| Destructive edits are always live | `events/handlers/grid.clj:147-193`, `events/handlers/effects.clj:71-104`, `events/handlers/menu.clj:386-406` | a drag moves a cue, a menu clears it, Ctrl+Z undoes, File > Open replaces the project, any time |
| The agent blocks on I/O | `events/core.clj:189-210` (save, load); `events/handlers/input.clj:53-63` (MIDI connect) | every key waits (probe v10: about 180 ms behind a 200 ms effect; probe v12: a tap becomes a hold) |
| The pipeline | `events/core.clj:444-460` | `handle-event` wrapped by timeline normalization, history, dirty, output sync, and in dev mode the lost-update check |
| All Notes Off | `events/handlers/input_mapper.clj:41-60` | CC 123 and 120 are stored like any CC (`beyond-vibe` releases every key on them: `beyond-vibe/src/engine/mod.rs:291-293`) |
| Disconnecting a device | `events/handlers/input.clj:65-77`; `input/midi.clj:175-196` | removes the handler. Nothing polls for an unplug (Java Sound has no hot-plug callback; unverified on a real device), so a pad pulled out mid-hold is never noticed. `beyond-vibe` has a poller (`beyond-vibe/src/midi_io.rs:33-90`) |
| Expiry helper | `triggers/janitor.clj:4-6` | `expired?` exists and has no caller |
| Output state | `subs.clj:364`; `views/toolbar.clj:218-245` | the toolbar shows whether streaming is on; the Perform tab (Phase 2) has a chip |
| Zone group `:crowd` | `state/domains.clj:359-362` | exists |
| Docs | `README.md:3, 48-52`; `QUICKSTART.md:27-49`; `.github/copilot-instructions.md:13-22` | no mention of vibes |
| Hardware checks as a habit | `plans/2026-11-17-show-clock/00-overview.md` §7 (hidden-window timers), `plans/2026-11-16-output-runtime/05-phase5-frame-timing.md` §8 | a list of what to try on real gear |

## 2. Root cause

The engine is correct on paper and tested, but a show has two kinds of failure that tests can't see: a person moving something they shouldn't, and hardware that stops telling the truth (a pad that never sends its release).

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| The pipeline as a function composition (`events/core.clj:455-460`) | one more wrapper, innermost |
| `triggers.janitor/expired?` (`triggers/janitor.clj:4-6`) | the stuck-hold check |
| The clock's reports (`animation/show_time.clj:204-215`; Phase 6 `:vibe-sent`) | the watchdog's event goes the same way as the rotation's |
| `:vibe/release-all` (Phase 1) | the one function that lets go of everything |
| The Perform header (Phase 2) | where the lock switch, the notice and the banners go |
| `subs/connection-status` (`subs.clj:364`) | the "no output" banner |
| Probe files as evidence (`plans/…/probes/`) | the soak test and the cost probes |

## 4. Changes per namespace

- `laser-show.events.show-lock` (NEW, pure): `blocked-events` (a set, §7) and `wrap-show-lock [handler]`: while `[:ui :show-lock?]` is true, a blocked event returns `{:state (assoc-in state [:ui :show-lock-notice] {:text "…" :event type})}` and nothing else. It is the **innermost** wrapper, around `handlers/handle-event` (`events/core.clj:455`), so history, dirty and output sync see a change to `[:ui]` only.
- `laser-show.events.core` (existing, `:455-460`): the threading becomes `(-> (show-lock/wrap-show-lock handlers/handle-event) timeline-handlers/wrap-normalize-timelines …)`, so the lock wraps the handler before anything else does.
- `laser-show.events.handlers.vibes` (existing): `:vibe/set-show-lock {:locked? bool}`; clears the notice on unlock.
- `laser-show.events.handlers.history` (existing): none (`:ui` isn't a document root).
- `laser-show.state.domains` (existing): `ui` gets `:show-lock? false`, `:show-lock-notice nil` (`:128-189`).
- `laser-show.events.handlers.input-mapper` (existing, `:41-60`): a control change on 120 or 123 also dispatches `:vibe/release-all`. The store write stays.
- `laser-show.events.handlers.input` (existing, `:65-77`): disconnecting a MIDI device dispatches `:vibe/release-all {:only :input}`.
- `laser-show.vibes.watchdog` (NEW, pure): `due [state now-ms] → [report …]`: one `{:event/type :vibe/release :role r :reason :watchdog}` for each role held longer than `:max-hold-secs` (`janitor/expired?`). A **hold** is `[:vibe-runtime :held role]`; a `:sustain` voice is not a hold.
- `laser-show.services.show-clock` (existing, `tick!`): the watchdog's reports join the rotation's (`:vibe-sent` memory).
- `laser-show.vibes.engine` (existing): a release with `:reason :watchdog` also sets `:problem {:role r :why :stuck-key}`.
- `laser-show.state.domains` (existing): `[:config :vibes :settings]` gets `:max-hold-secs 60`.
- `laser-show.views.tabs.perform` (existing): the **show lock** switch and the last notice; a red banner **NO OUTPUT: press Stream** when `[:backend :streaming :running?]` is false and any pad has been pressed in this session; an amber badge on the header while the current vibe has `:allow-audience? true`; an audience mark on a pad whose cue is audience-capable; the stuck-key problem text on the pad.
- `laser-show.views.tabs.vibes` (existing): a lint badge on the tab label (error count).
- `README.md`, `QUICKSTART.md`, `.github/copilot-instructions.md`: see §7.
- `plans/2026-11-18-vibe-mode/`: when everything is done, mark each doc done and delete the folder (`.agents/rules/planning-guide.md` rule 11).

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:ui :show-lock?]`, `[:ui :show-lock-notice]` (NEW) | UI state, session. Never saved | `:vibe/set-show-lock`, `wrap-show-lock` | no / no |
| `[:config :vibes :settings :max-hold-secs]` (NEW) | document | the Vibes tab | yes / yes |
| `[:vibe-runtime :problem]` (existing) | app state, session | the engine | no / no |

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:vibe/set-show-lock {:locked?}` | NEW | the Perform switch (`views/tabs/perform.clj`) | yes |
| `:vibe/release-all {:only}` | Phase 1/2 | CC 120 and 123 (`events/handlers/input_mapper.clj:41-60`); a MIDI disconnect (`:input`, `events/handlers/input.clj:65-77`); focus loss (`:pad`, Phase 2); blackout (the engine) | yes |
| `:vibe/release {:reason :watchdog}` | Phase 1 gains `:reason` | the show clock (`services/show_clock.clj:161`) | yes |

No new effect key.

## 7. Data examples and invariants

### What the lock blocks

| Blocked while locked | Why |
|---|---|
| `:grid/move-cell :grid/clear-cell :grid/paste-cell` `:effects/move-cell :effects/clear-cell :effects/paste-cell` `:edit/clear-cell :edit/paste` | a cell that moves or goes breaks the vibes that name it |
| `:edit/undo :edit/redo :history/undo :history/redo` | an undo can remove a vibe mid-show |
| `:file/new-project :file/open :file/save :file/save-as` | they replace the document, or block the agent with I/O (probes v10, v12) |
| every vibe **edit** event (`:vibe/add … :vibe/clear-control`, the list in `03-phase3-setup-and-grouping.md` §6) | the vibes are fixed for the show |
| `:projectors/add-manual :projectors/update-settings :projectors/toggle-enabled :projectors/toggle-zone-group :projectors/vp-toggle-zone-group :projectors/set-service-enabled :projectors/enable-all-by-ip` | they rebuild or re-route the engines |

Never blocked: `:vibe/press :vibe/release :vibe/select :vibe/release-all :vibe/arm :vibe/advance :vibe/set-auto :vibe/set-show-lock`, `:grid/trigger-cell :grid/activate-cell :grid/release-cell`, `:effects/toggle-cell`, `:timing/*`, `:transport/*`, `:idn/start-multi-streaming :idn/stop-multi-streaming`, `:input/*` (learning a key is allowed), `:file/exit` (the unsaved-changes question still asks).

### The watchdog

Settings `:max-hold-secs 60`.

| Role held | Held for | Result |
|---|---|---|
| HIT (`:mode :decay`) | 59 s | nothing |
| HIT | 61 s | `{:event/type :vibe/release :role :hit :reason :watchdog}`, then `:problem {:role :hit :why :stuck-key}` |
| FLASH | 61 s | the same for FLASH (the flash ends, the ducked voices return) |
| HIT in `:sustain` mode, playing for 5 minutes | not held | nothing: a sustain voice is not a hold |
| VIBE | 61 s | released without a lock or a switch |

### Invariants

1. While locked, no blocked event changes the document, the engines or the files. The only state change is the notice.
2. Unlocking changes nothing but the flag and the notice.
3. The lock is never saved, and a freshly opened project starts unlocked.
4. CC 123 or 120 lets go of every role, hardware and pad alike.
5. A hold older than the limit is released exactly once, with a visible problem, and the other roles are untouched.
6. A press while streaming is off still works (the cue plays into nothing) and the banner says why nothing is visible.
7. No sequence of presses, releases, switches, blackouts, rotations, deletes and undos leaves two voices for a role, a voice whose cue is gone but still shown as playing, a negative count, or an exception (§8 fuzz and soak).
8. A pad or key held down at the moment of the lock still releases normally.

### The docs (what gets written)

- **README.md**: a "Vibe mode" section after "Input System": the idea in five lines, the controls table (`00-overview.md` §4), the Setup steps (cues on the grid → Vibes tab → make vibes from rows → Learn all → Perform), a safety note (audience cells, show lock, blackout).
- **QUICKSTART.md**: "Vibe mode in ten minutes" under "Usage" (`:27-49`), numbered, matching the checklist in the Vibes tab.
- **A one-page cheat sheet** (a printable section of the README): five keys, two faders, "tap / hold / hold 0.6 s", and what the lights on a pad mean.
- **`.github/copilot-instructions.md`**: `src/laser_show/vibes/` in the key directories table (`:13-22`), and "vibe presses are pure events; faders never go through the agent" under the state rules.

## 8. Tests (named test vars + fixtures)

- `laser-show.events.show-lock-test` (NEW): `locked-blocks-structure-edits-test`, `locked-allows-performance-events-test`, `unlock-restores-everything-test`, `blocked-events-name-real-events-test` (each key is routed, as `history-labels-name-real-events-test` does, `events/history_handlers_test.clj:252`), `lock-is-not-saved-test` (`persistent/project-files` has no `:ui`), `exit-still-asks-test`, `held-key-releases-after-the-lock-test`.
- `laser-show.events.handlers.input-mapper-test` (existing from Phase 5): `all-notes-off-releases-everything-test`, `all-sound-off-releases-everything-test`, `ordinary-cc-does-not-release-test`.
- `laser-show.events.handlers.input-test` (existing): `midi-disconnect-releases-input-holds-only-test`.
- `laser-show.vibes.watchdog-test` (NEW): the table above as `stuck-hold-is-released-test`, `sustain-is-not-a-hold-test`, `released-once-test`, `other-roles-untouched-test`.
- `laser-show.services.show-clock-test` (existing, pattern `:128`): `watchdog-event-is-sent-once-per-interval-test`.
- `laser-show.vibes.engine-fuzz-test` (existing from Phase 1): the generator now also produces rotation events, stuck holds, the lock and undo, and checks invariant 7 above after every step.
- `laser-show.vibes.engine-soak-test` (NEW, `^:performance`, so only in `:test-timing`): the same generator for 200,000 events; also asserts `[:vibe-runtime]` stays bounded (bags, voices) and that the last 1,000 presses cost no more than 3 times the first 1,000 (no growth).
- UI, `laser-show.ui-test.perform-test` (existing): `show-lock-switch-blocks-a-cell-move-test`, `no-output-banner-test`, `audience-badge-and-pad-mark-test`, `stuck-pad-shows-its-problem-test`. `laser-show.ui-test.grid-test` (existing) must pass unchanged with the lock off.

Run: `clj -M:mac-arm:test show-lock watchdog fuzz input-mapper show-clock`, `clj -M:mac-arm:test-timing soak`, the UI tests, then both full suites.

### Hardware check (manual, unverified until run; record the results here)

Gear: a real DAC, one real controller (pad and faders), the show laptop, the venue-sized screen.

1. **Latency feel.** Tap HIT against a click at 120 and 128 BPM; film the pad and the beam at 120 fps. Goal: the beam changes within 50 ms of the press (6 frames of a 120 fps film). Compare with a click on a grid cell.
2. **Tap and hold.** A quick tap decays after about a beat; a held pad cuts on release; the 150 ms line feels right for this player. Adjust `:quick-tap-ms` if not.
3. **Blackout.** The DAC goes dark within a frame (about 40 ms for 1,000 points at 25 kpps); other keys do nothing; pressing again brings the bed back; the clocks and tempo survive.
4. **A pulled cable.** Hold HIT, pull the USB cable. The hold ends by the watchdog within the limit, with a visible problem. Replug and carry on.
5. **Faders.** Intensity 0 → 100 % smoothly; unmoved = full; slider 2 "not wired" until an FX set wires it.
6. **Legibility** at 2 m: vibe name, the current pad states and the blackout banner.
7. **Touch**, if the show uses a touchscreen: two pads at once.
8. **Auto-advance** through a whole 20-minute set, then locked for one section.
9. **The window hidden or covered** (Windows 11, macOS App Nap): decay and rotation still on time. This is the open check from the show clock plan §7.
10. **A save mid-show with the lock off**: watch the key delay. With the lock on it is refused.
11. **Thirty minutes of random presses**: a script that dispatches the soak test's random events into the running app, watching memory and the projector status.
12. **Tap reactions** (Phase 4) at 120 and 128 BPM: Punch, Step spin and Scatter land on the press (film as in 1); a Scatter or a Swell never leaves the projector's calibrated area (the corner pin runs after every reaction, and calibration effects don't react to presses).

## 9. Out of scope / alternatives considered

- **Auto-lock when the Perform tab opens**: rejected: surprising, and editing on the Perform tab is sometimes the point.
- **A hardware unplug poller** like `beyond-vibe/src/midi_io.rs:33-90`: the watchdog covers the failure; a poller (a thread, port scanning on every OS) is its own plan, and it belongs with the input device work in the review ("Next").
- **Hard-blocking audience cells for every vibe**: V6 C.
- **Locking the toolbar's Stop**: never. Stop is a safety control.
- **Controller LED feedback**: the app has no MIDI output.
- **Taking blocking I/O off the agent**: the review's "Next" item; if it lands before this phase, R-1 shrinks and the lock's file rules can relax.
