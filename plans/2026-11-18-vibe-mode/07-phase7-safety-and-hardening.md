# Phase 7: safety and hardening

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** Done in `5b59774` (2026-11-19), built on branch `vibe-phase7` from `989c315` (vibe Phases 1–6 and zones Z0 in it). As built: §10   **Last verified against:** `5b59774` (§10)
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
12. **Tap reactions** (Phase 4) at 120 and 128 BPM: Punch, Step spin and Scatter land on the press (film as in 1); a Scatter or a Swell never leaves the projector's calibrated area (the corner pin runs after every reaction, and calibration effects don't react to presses). A Fade in starts dark on a VIBE tap and is full again a beat later. **Cost:** with the heaviest FX set the show will use (four effects or more), play four cues at once for five minutes and watch the status bar's frame time (p95 and max); if max spikes, run once with `-Xlog:gc` and look for pauses. R-5 estimates 0.9 ms and 6 MB of garbage per tick for four effects over four cues in one zone (unverified).
13. **Zone effects** (only once Phase 8 has landed), with a real projector per zone and a projector in the crowd zone: a chase lights one projector at a time and the others go dark at once (film a pad's beam and the wall, as in 1); a swap and a shift land on the projectors that list those zones, and a picture moved out of `:all` is not drawn twice by a projector in two zones; a vibe whose zone cell lists the crowd zone leaves it off (V10) and Perform says why, while a click on the effects grid switches it on; the frame time stays flat with a chase running (as in 12).

## 9. Out of scope / alternatives considered

- **Auto-lock when the Perform tab opens**: rejected: surprising, and editing on the Perform tab is sometimes the point.
- **A hardware unplug poller** like `beyond-vibe/src/midi_io.rs:33-90`: the watchdog covers the failure; a poller (a thread, port scanning on every OS) is its own plan, and it belongs with the input device work in the review ("Next").
- **Hard-blocking audience cells for every vibe**: V6 C.
- **Locking the toolbar's Stop**: never. Stop is a safety control.
- **Controller LED feedback**: the app has no MIDI output.
- **Taking blocking I/O off the agent**: the review's "Next" item; if it lands before this phase, R-1 shrinks and the lock's file rules can relax.

## 10. As built (2026-11-19)

Committed as `5b59774` on top of `989c315` (zones Z0 done, on top of vibe Phase 6). §1–9 cite `b1da0f2`; the lines below are at `5b59774`. The session that first built it lost its worktree, so this is the second build, from `989c315`; nothing of the first survived.

**Where it is now** (paths under `src/laser_show/`)
- `events/show_lock.clj` (NEW, pure): `blocked-events` `:21`, `refused-text` `:43` (Perform's notice, "Move Cell refused"), `notice-text` `:49` (the status bar's), `locked?` `:54`, `wrap-show-lock` `:59`. `events/core.clj:480`: the pipeline's innermost wrapper, right after `handlers/handle-event` (one require, one line, the docstring). Z0's dead `:clipboard/paste-projector-effects` effect is untouched, for Z0 to remove after this merges.
- `events/handlers/vibes.clj`: NEW private `handle-set-show-lock` `:106`, routed `:223` in its own block; `handle-clear-control` `:90` lets go of a role whose key it unbound (item a).
- `vibes/watchdog.clj` (NEW, pure): `limit-ms` `:25`, `due` `:46` (`[state now-ms pulses]`), `stuck?` `:68` (the engine's re-check); private `pulse-only?` `:35`, `any-pulse-held?` `:42`.
- `vibes/engine.clj`: NEW private `let-go-of-hold` `:490` (release-all's per-role step, now shared), `let-go-of-stuck` `:501`; `release` routes `:reason :watchdog` to it; `release-all` `:548`; NEW public `release-unbound` `:561` (item a); NEW private `refresh-audience` `:653`, the last step of `reconcile` `:670` (item b). The ns docstring says both.
- `services/show_clock.clj`: `rotation-step` is now `vibe-step` `:153`: auto-advance's report and the watchdog's (with the tick's pulse view, which `tick!` already makes) go through one `show-time/due-reports` call on the runtime's `:vibe-sent`; `tick!` `:194`. `animation/show_time.clj`: the ns docstring only.
- `events/handlers/input_mapper.clj`: NEW private `all-notes-off-controls` `:68`, `all-notes-off?` `:73`; the continuous branch dispatches `:vibe/release-all` after the store write (`:91`). The discrete branch, `learning?` and `store-keys` are as they were.
- `events/handlers/input.clj`: MIDI disconnect (`:72-87`, one device and all) dispatches `:vibe/release-all {:only :input}`; `handle-input-learn-captured` lets go of a role whose key the learn rebound (`:371`); one require.
- `state/domains.clj`: `ui` `:show-lock?` `:206`, `:show-lock-notice` `:208`; `[:config :vibes :settings :max-hold-secs] 60` `:282`; the `:problem` doc names `:stuck-key`. `state/persistent.clj:166`: `reset-session` resets `[:ui :vibes]` (item c), one line after the notice's. `vibes/library.clj`: the ns docstring's settings.
- `subs.clj`, a section of its own at the end (`:1326-1382`): `vibe-show-lock` `:1330`, private `vibe-played?` `:1337`, `vibe-output-banner` `:1345`, `vibe-audience?` `:1358`, `vibe-pad-audience?` `:1364`, `vibes-tab-label` `:1377`. Phase 2's `vibe-header` and `vibe-pad` keep their shapes (their tests compare whole maps).
- `views/tabs/perform.clj`: NEW private `output-banner` `:36` (`#vibe-no-output`, under the BLACKOUT banner, always in the tree), `show-lock-controls` `:50` (`#vibe-lock-notice`, the toggle `#vibe-show-lock`, after AUTO in the header's second row `:149`); the header's AUDIENCE ALLOWED badge `#vibe-header-audience` `:125`. `views/components/vibe_pads.clj`: `#vibe-pad-<role>-audience` `:115`; "Stuck key released" `:42`, before "Effects only". `views/root.clj:89`: `tab-bar` takes the Vibes label from `subs/vibes-tab-label`. `css/vibes.clj:88-123`: `.toggle-button.vibe-show-lock` (and `:selected`, red), `.vibe-lock-notice`, `.vibe-header-audience`, `.vibe-pad-audience`.
- Docs: `README.md` "Vibe Mode" after "Input System", with the keys, the faders, setup, tap reactions, auto-advance, safety and a "Vibe mode cheat sheet"; `QUICKSTART.md` "Vibe mode in ten minutes" under Usage, the Vibes tab's checklist as steps; `.github/copilot-instructions.md`: `src/laser_show/vibes/` in the key directories and the state rule on vibe presses and faders.

**Items a–c (from earlier phases' "Found, not done")**
- a. A key that loses its binding while it holds a role lets go of it at once: `engine/release-unbound [state was t]` compares the trigger map before the edit with after, and every role that lost a binding and is held by a key (`:src :input`, the engine's hold or, for a press that played nothing, its pulse) is let go of as `release-all` does. Called by a learn that rebinds a key or moves a role (`handle-input-learn-captured`) and by Clear (`handle-clear-control`). A pad's hold stays. The engine doesn't record which key holds a role, so a role with two keys that loses one lets go even if the other holds it (a role keeps one key through learn).
- b. Unticking **Allow audience cells** stops an audience cue that plays at once, whichever role plays it: `refresh-audience`, the last step of `reconcile`, stops each HIT, ACCENT or FLASH voice whose cell the old vibe could play and the edited one can't; HIT's and ACCENT's are forgotten, the flash's cue stops while its effect cells stay on until FLASH is let go (they aren't cues). The bed was already `refresh-bed`'s. Ticking it again starts nothing; the next press may pick them.
- c. `reset-session` resets `[:ui :vibes]` (the selection, the slot, Learn all) with the rest of the session, on New and Open.

**Deviations, and why**

| Plan | As built | Why |
|---|---|---|
| a hold is `[:vibe-runtime :held role]`; `due [state now-ms]` | also the pulse of a press that played nothing, held that long by the tick's pulse view: `due [state now-ms pulses]`, and its report carries the press's `:pulse` id | follow the code: Phase 4 made a press that plays nothing hold its pulse only, and Phase 6 counts that as a key down (`rotation/key-down?`). Stuck, such a key held a Swell at its peak and auto-advance off for ever. App state has no time for a pulse; the pulse view has its age |
| `:vibe/release {:reason :watchdog}` sets the problem | the engine asks again first (`watchdog/stuck?`: the engine's hold still that old at the event's time, or the same press's pulse still held) and lets go as `release-all` does: no switch, no lock, no auto-advance on FLASH | Phase 6's pattern: a report that comes late or twice changes nothing, and a key pressed again since keeps its hold. The table's VIBE row (no lock, no switch) holds for every role: a stuck key mustn't change the look |
| §7's blocked list | also `:file/open-result` and `:file/project-loaded` (a read can't finish while locked), Z0's `:projectors/request-remove`, `:projectors/remove`, `:zone-groups/request-remove`, `:zone-groups/remove` (the zones overview asks for Z0's remove; both re-route the engines), and every vibe edit with an undo label, which the test checks against `history/event-descriptions` (`:vibe/clear-control`, `:vibe/add-reaction`, `:vibe/add-master`, `:vibe/add-morph` besides Phase 3's eleven) | invariants 1 and 3 by construction. `:file/save-as-result` and the unsaved dialog's buttons are left alone, so Exit's Save still writes the project (exit-still-asks-test) |
| the notice at `[:ui :show-lock-notice]` | that (`{:text "Move Cell refused" :event type}`, under the switch until unlocked), and the status bar's notice for 6 s ("Show lock is on: Move Cell refused. Unlock it on the Perform tab") | a drag refused on the Grid tab says why where it happened |
| NO OUTPUT when `:running?` is false and a pad was pressed | from `subs/connection-status` (brief note 3): "press Stream" while `:stopped` or `:stopping`, "no projectors enabled" while `:idle`, nothing while starting, streaming or streaming with errors; after any vibe press of the session (a vibe made current, or HIT, ACCENT or FLASH pressed, by a pad or a key) | the toolbar and Perform agree; a key's press plays into nothing as a pad's does |
| an audience mark on a pad "whose cue is audience-capable" | AUDIENCE on HIT's, ACCENT's or FLASH's pad while the vibe that plays allows audience cells and its pool (or flash cue) has one it may play; the header says AUDIENCE ALLOWED | without the box, V6 never plays them, so there is nothing to warn of |
| `:max-hold-secs` written by the Vibes tab (§5) | no editor (§4 and §8 list none, as Phase 6's rotation settings); a file can set it; a value that is no positive number is 60 | |
| the lint badge on the tab label | "Vibes ⚠ 2" (errors only), from `subs/vibes-tab-label`; `tab-bar` maps the label into `main-tabs` | brief note 2; the label was fixed data |
| delete the plan folder | kept; 07 marked done | brief note 1: Phase 8 is open (V10) |

**Tests**: 26 new unit tests (1,153 → 1,179), 1 soak test (`^:performance`), 5 UI tests
- NEW `laser-show.events.show-lock-test` (7): the seven of §8. `locked-blocks-structure-edits-test` runs twelve real edits (moves, clears, vibe edits, Clear control, a projector's switch and removal, a zone group's delete) unlocked (they edit) and locked (only the notices change, and no effect but the notice's timer), undo and redo both ways, the four file events (no chooser, read or write) and a read that finishes while locked. `locked-allows-performance-events-test`: twelve events give the same state locked or not (requests' random ids aside), and Stream, Play and a learn give their effects. `blocked-events-name-real-events-test` also checks every vibe edit with an undo label is blocked.
- NEW `laser-show.vibes.watchdog-test` (6): the four of §8 (the table's rows: 59 s, 61 s, FLASH with what it stepped aside, VIBE, the setting and bad values), `a-press-that-played-nothing-is-a-hold-too-test`, `due-allocates-nothing-when-idle-test` (under 4 KB over 10,000 calls).
- `laser-show.events.handlers.input-mapper-test` (+3): the three of §8, on midi-clj messages (`input/fixtures.clj`), a key's hold and a pad's. `laser-show.events.handlers.input-test` (+2): `midi-disconnect-releases-input-holds-only-test` (one device, all devices), `a-key-that-loses-its-role-lets-go-test` (item a: a learn moves HIT, a cell learned on HIT's key, HIT on its own key, a pad's hold). `laser-show.events.handlers.vibes-test` (+2): `clear-control-lets-go-of-a-held-role-test` (item a), `unticking-allow-audience-stops-what-plays-test` (item b). `laser-show.state.persistent-test` (+1): `session-reset-clears-the-vibes-tab-selection-test` (item c). `laser-show.services.show-clock-test` (+1): `watchdog-event-is-sent-once-per-interval-test`; `one-state-and-input-read-per-tick-test` and `snapshot-shape-test` unchanged and green. `laser-show.subs-test` (+4): `vibe-output-banner-sub-test`, `vibe-show-lock-sub-test`, `vibe-audience-subs-test`, `vibes-tab-label-sub-test`.
- `laser-show.vibes.engine-fuzz-test` (widened): the show lock switched (a locked walk refuses its moves, clears, vibe edits and undos, and skips the direct edits an undo would make), undo and redo through their handlers, keys that stick (a press, then 10.5 s of clock) and stuck-key reports that come late or for another press; the world's limit is 10 s, so the walk's own long holds meet it too. The clock's part sends the watchdog's reports with auto-advance's, as `tick!` does. New checks: `check-lock` (07 invariants 1, 2, 8: refused events change only the notices, unlocking only the switch and the notice, a vibe event does locked what it does unlocked), `check-watchdog` and `check-stuck-holds` (invariant 5: let go of once, alone, as a cut, with its problem, no switch or lock; nothing held past the limit after a tick), and in `check-always` invariant 7: no engine cue plays without its voice ("two voices for a role"), none is held without its role held, no count is negative; `check-vibe-edit` allows an audience cue to stop when the box is unticked, and checks that none plays after. NEW public `new-world`, `random-step`, `take-step` for the soak.
- NEW `laser-show.vibes.engine-soak-test/engine-soak-test` (`^:performance`): the fuzz's walk as one long walk, every step's checks, and its own: what vibe mode keeps stays under the world's ceilings (bags, voices, holds, ducks, FX cells, cues playing, press sources, the undo stack, the runtime printed), and the median of the last 1,000 presses' pipeline time is at most 3 times the first 1,000's.
- UI `laser-show.ui-test.perform-test` (+4): the four of §8; `stuck-pad-shows-its-problem-test` holds a pad past a 0.5 s limit and ticks the clock by hand; `no-output-banner-test` clicks Stream with no projector enabled (the toolbar and the banner agree), then Stop, and drains the output runtime. `laser-show.ui-test.vibes-test` (+1): `vibes-tab-label-counts-lint-errors-test`. Pads are clicked with `ui/mouse-click!` and held with `ui/press-node!` (brief note 9), `:quick-tap-ms` raised where a click must stay a tap.

**Red first**
- The tests against `989c315`'s `src/`: the five extended namespaces, 104 tests, 702 assertions, 25 failures (CC 123 and 120 let go of nothing; a disconnect, a learn and Clear left the key's hold, pulse and cue; unticking left both audience cues playing; the clock sent no stuck-key report; New and Open kept the Vibes tab's selection). `show-lock-test` and `watchdog-test` didn't load (no such namespaces).
- UI, with the views, CSS and subs set aside: 5 tests, 5 errors, each timing out.
- First green run: `blocked-events-name-real-events-test` failed for `:file/open-result`, which returns `{}` without a file (a cancelled chooser): the test passes a `:file-path`.
- The widened fuzz, first run: all three seeds failed "inv 3: bag … refilled after playing …". The walk's cycle tracker renamed the picks of a move the lock had refused; it now renames only when the grid changed, takes the picks of a tick (a stuck FLASH let go of starts what it kept waiting) and marks the cycles unclean after an undo or redo that changed the document (cells renamed behind the bags' backs). No engine change.
- The soak, 1,000,000 events of seed 7: step 277,178, "a tick step changed the vibe with 2 :vibe pulses": a stuck key's 10.5 s tick step holds two auto-advances once the key is let go of at 10 s. The walk's check now counts one pulse per tick that changes the vibe. No engine change; it also showed that a second press of a role that plays nothing leaves the engine's hold at the first press's time, so the watchdog counts from the first (below).

**Mutants**: each against the watchdog, show-lock, vibes-handlers and input tests and the fuzz's three seeds (68 tests). All killed.

| # | Mutant | Fuzz | Unit tests failing |
|---|---|---|---|
| W1 | `stuck?` doesn't ask the age again | 3 of 3 ("a stuck-key report … which isn't stuck, changed the state") | `released-once-test` |
| W2 | `due` leaves the pulse-only holds out | 2 of 3 ("…'s pulse is held 10483 ms, past the limit") | `a-press-that-played-nothing-is-a-hold-too-test` |
| L1 | the lock not in the pipeline | 3 of 3 (07 inv 1) | 57: `locked-blocks-structure-edits-test` (56), `unlock-restores-everything-test` (1) |
| L2 | `:file/project-loaded` not blocked | 0 of 3 | `locked-blocks-structure-edits-test` (1) |
| A1 | `reconcile` without `refresh-audience` | 1 of 3 ("…'s audience rule left :flash playing [2 1]") | `unticking-allow-audience-stops-what-plays-test` (4) |
| U1 | `release-unbound` lets go of a pad's hold too | 0 of 3 | `a-key-that-loses-its-role-lets-go-test`, `clear-control-lets-go-of-a-held-role-test` |
| V1 | a stuck VIBE released as a VIBE release (locks) | 3 of 3 ("a stuck :vibe locked or unlocked the rotation") | `stuck-hold-is-released-test` |

U1's first version (the filter before the reduce) survived: it was equivalent, the reduce checks `:src` itself; the filter is gone.

**Soak**: 200,000 events (the default, seed 20261119) in 36 s: 0 failures; 54,970 presses, median 31.0 µs over the first 1,000 (the JIT still warming) and 7.4 µs over the last 1,000; at most 8 bags, 4 voices, 4 holds, 3 ducked, 3 FX cells on, 9 cues playing, 5 press sources, 50 undo steps, the runtime 1,373 characters printed. A million events of seed 7 failed at step 277,178 (red first above); after the walk's fix, 300,000 events of seed 7, past that step: 0 failures in 177 s with the machine under load (82,423 presses, median 74.3 µs over the first 1,000 and 8.6 µs over the last 1,000; the same ceilings, at most 10 cues playing). The run is about 5,500 events a second here, so the half-hour soak is `VIBE_SOAK_EVENTS=10000000 clj -M:mac-arm:test-timing soak` (another walk: `VIBE_SOAK_SEED=<n>`); it prints its progress every 20,000 events.

**Found, not done**
- `:input/midi-disable` drops a held key's note-off as a disconnect does, but lets go of nothing; the watchdog covers it.
- A playing cue that becomes audience-capable through a projector's tag or zone groups, or its own destination, plays on: only the vibe's box (item b) stops one.
- Two sources, one role: a second press that plays nothing (a pad after a key, or the other way) leaves the engine's hold at the first press's time, and the watchdog counts from it (04 §10's two sources).
- After a show clock restart a pulse-only hold has no age in the pulse view (`reset-runtime!` seeds the stamps without press times) until its next press, so the watchdog can't see it.
- The zones plan's later events that re-route or change safety (Z1's safety edits, Z2's order and membership, Z4's map switches, ZR's rig) join `blocked-events` when they land, and zone group create and edit aren't blocked (the zones overview, "Running alongside vibe Phases 6 and 7"; whether a safety edit should be refused is for Z1 and this plan to agree).
- The agent's blocking I/O (R-1) is as it was: the lock only refuses save, open and new while it is on.

**Hardware check (§8): unverified.** None of the thirteen was run: there is no DAC, controller or venue screen here.

| # | Check | Result |
|---|---|---|
| 1 | Latency feel (filmed at 120 fps) | unverified |
| 2 | Tap and hold, the 150 ms line | unverified |
| 3 | Blackout within a frame | unverified |
| 4 | A pulled cable: the watchdog ends the hold within the limit, the pad says so | unverified |
| 5 | Faders | unverified |
| 6 | Legibility at 2 m (now with the NO OUTPUT banner, AUDIENCE ALLOWED and the SHOW LOCK switch) | unverified |
| 7 | Touch, two pads at once | unverified |
| 8 | Auto-advance through a 20-minute set, then locked | unverified |
| 9 | The window hidden or covered | unverified |
| 10 | A save mid-show with the lock off (the key delay), refused with it on | unverified |
| 11 | Thirty minutes of random presses into the running app | unverified (the soak test walks the handlers and the clock's step, not the running app) |
| 12 | Tap reactions and their cost | unverified |
| 13 | Zone effects | not applicable until Phase 8 lands |

**Verification, 2026-11-19, macOS arm64, JDK 25**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test show-lock watchdog vibes input-mapper input show-clock persistent core engine reactions subs` | 394 tests, 2,698 assertions, 0 failures |
| `VIBE_FUZZ_SEEDS=200 clj -M:mac-arm:test -n laser-show.vibes.engine-fuzz-test` | seeds 1–200, 2,000 steps each: 0 failures (113 s with the machine at load 10 to 97; 81 s at load 5 before the walk's last fix) |
| `clj -M:mac-arm:test-timing soak` | 200,000 events, 0 failures (36 s); 300,000 events of seed 7, 0 failures (177 s, load 10 to 97) |
| `clj -M:test-ui:mac-arm:test-ui-headless -n` perform-test, vibes-test, grid-test, menu-bar-test, main-window-test | 60 tests, 411 assertions, 0 failures |
| `clj -M:mac-arm:test` (full unit suite) | 1,179 tests, 34,887 assertions, 0 failures (baseline at `989c315`: 1,153 tests, 34,606 assertions) |
| `clj -M:kondo --lint src test` | 0 errors, 21 warnings, the same 21 as at `989c315` |
| layout | a headless screenshot at 1200×800: the header's two rows hold AUDIENCE ALLOWED, AUTO and its countdown, the refused notice and SHOW LOCK, with the NO OUTPUT banner above and "Vibes ⚠ 1" on the tab |
| full UI suite | not run here: it runs after the merge |
| manual (unverified) | the hardware check above; a visible (non-headless) run; the header's layout at 2 m |
