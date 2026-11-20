# Phase 6: auto-advance on phrase gaps, and one tap tempo

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** Done in `4a4a10a` (2026-11-19), built as `443e4b3` on branch `vibe-phase6` from `2d1f813`; V3 and V4 answered A. As built: §10   **Last verified against:** `4a4a10a` (§10)
**Supersedes / Superseded by:** supersedes the rotation scaffolding in `events/handlers/triggers.clj:134-155` (deleted in Phase 1, V7 A); implements review 05 M6 (one tap-tempo algorithm)   **Related findings:** review 05 F14, M6; review 02 #10 (input timestamps, for the taps only)

Two things that take decisions off the performer's hands: the feel changes by itself at a musical moment (off unless you turn it on), and the hits set the tempo with the algorithm `beyond-vibe` proved.

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| Rotation | `events/handlers/triggers.clj:141-155` (deleted in Phase 1); `state/domains.clj:95-99, 104-111` | `:trigger/tick` arms a group after `:group-duration-secs`, but nothing dispatches it and nothing sets `:entered-at` (grep) |
| The clock's reports | `animation/show_time.clj:187-215` (`pending-reports`, `due-reports`), `services/show_clock.clj:137-162` | timeline ends and timecode chases are reported as events, each at most every 250 ms (`report-interval-ms`, `:63-66`). `due-reports` reads the runtime's `:sent` map |
| The clock's own time | `animation/show_time.clj:33-43`; `services/show_clock.clj:143-150` | `step` uses a monotonic `now-ms`; app state's timestamps are wall-clock ms (`events/helpers.clj:11-18`) |
| Tap tempo, the button and the engine | `events/handlers/timing.clj:42-58`; `events/core.clj:170-184` (`:timing/calculate-bpm`) | an unbounded vector `[:timing :tap-times]` (`state/domains.clj:41-42`); a gap over 2000 ms clears it; the effect averages the last 8 intervals and clamps to 40–300 |
| Tap tempo, a pure version | `triggers/tap_tempo.clj:4-42` | unused: max 6 taps, 2000 ms timeout, drops intervals outside 20–300 BPM, **averages** |
| Tap tempo, a third copy | `events/handlers/triggers.clj:96-99` | `[:trigger-runtime :tap-history]`, never read |
| `beyond-vibe`'s algorithm | `beyond-vibe/src/engine/tap_tempo.rs:8-73` | ignore a tap closer than 100 ms to the last; a gap over 1.5 s starts a new series and keeps the old tempo; at most 5 taps; the **median** of the intervals; clamp 40–300 |
| What a tap also does | `events/handlers/timing.clj:55-57` | requests `:align`: every cue and the global clock glide onto the nearest whole beat (`animation/show_time.clj:89-104`) |
| Link | `state/extractors.clj:189-190` (`link-sync-enabled?`); `events/handlers/timing.clj:171-181` | with sync on, Link's BPM is applied by its own events |
| The tests that pin today's shape | `test/laser_show/events/timing_handlers_test.clj:21-67, 103-113`; `test/laser_show/events/core_test.clj:37-45, 59-66`; `test/laser_show/triggers/tap_tempo_test.clj:5-13`; `test/laser_show/ui_test/toolbar_test.clj:45-57` | the vector shape, the 2000 ms boundary, `:timing/calculate-bpm true`, the effect list. The toolbar test reads `[:timing :tap-times]` and waits for each click to change it |

## 2. Root cause

There is no timer that looks at a vibe, so the rotation scaffolding could never run. And three tap algorithms exist, none with a debounce or a median, so a bouncing button or one missed beat moves the tempo.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| The clock's report path (`animation/show_time.clj:187-215`; `services/show_clock.clj:161`) | the rotation timer is a pure check inside `tick!`, and its events go out beside the other reports |
| `due-reports` (`animation/show_time.clj:204-215`) | the same once-per-250-ms rule for vibe reports |
| `triggers.tap-tempo` (`triggers/tap_tempo.clj`) | becomes the one estimator |
| `triggers.janitor/expired?` (`triggers/janitor.clj:4-6`) | the hold watchdog of Phase 7 uses it too |
| `:timing/tap-tempo`, `:align` (`events/handlers/timing.clj:42-58`) | unchanged for callers |
| `ex/link-sync-enabled?` (`state/extractors.clj:189`) | HIT doesn't tap while Link sync is on |
| `beyond-vibe`'s tests (`beyond-vibe/src/engine/tap_tempo.rs:95-125`, `beyond-vibe/src/engine/mod.rs:577-587, 612-626`) | the examples below |

## 4. Changes per namespace

- `laser-show.vibes.rotation` (NEW, pure): `due [state now-ms bpm] → nil | report`, where a report is `{:event/type :vibe/arm :from id}` or `{:event/type :vibe/advance :reason :gap :from id}`. Rules in §7.
- `laser-show.animation.show-time` (existing): `due-reports` takes the memory map as an argument (`(:sent runtime)` in `step`); `initial-runtime` (`:33-43`) gets `:vibe-sent {}`.
- `laser-show.services.show-clock` (existing, `tick!` `:137-162`): after the step, `(rotation/due state (System/currentTimeMillis) bpm)` goes through `due-reports` with `:vibe-sent`, and its events are dispatched with the others (`:161`). The check is a handful of comparisons per tick.
- `laser-show.vibes.engine` (existing): `release` of FLASH, when `:rotation` is `:armed` and no HIT or ACCENT is held, advances (reason `:flash`). `switch-to` resets `:rotation` to `:active` (or leaves `:locked`) and `:entered-at` to now; since Phase 4 it also pulses `:vibe`, so an automatic change gets the same transition reactions as a VIBE tap. NEW `arm`, `advance`, `toggle-lock` (the release of VIBE after 600 ms, from Phase 1), `set-auto`.
- `laser-show.events.handlers.vibes` (existing): `:vibe/arm`, `:vibe/advance` (ignored if `:from` is no longer current, or if the state is no longer `:armed` for `:gap`), `:vibe/set-auto {:auto? bool}`.
- `laser-show.views.tabs.perform` (existing): an **AUTO** switch in the header, the countdown "ACTIVE 0:31 / 0:45" (drawn by `ext-clock-redraw`, setting its text only when the second changes), and an "ARMED → next: Build" state, pulsing at 2 Hz by the same widget. The VIBE pad shows "LOCKED" when locked.
- `laser-show.triggers.tap-tempo` (existing): the algorithm above: options `{:debounce-ms 100 :reset-ms 1500 :max-taps 5 :min-bpm 40.0 :max-bpm 300.0}`; `tap` ignores a bounce (state unchanged), clears the taps after a long gap but keeps `:bpm`, and sets `:bpm` to the clamped median. `beats-per-minute` follows.
- `laser-show.events.handlers.timing` (existing, `:42-58`): `handle-timing-tap-tempo` calls `tap/tap` on `[:timing :tap]` and writes `[:timing :bpm]` **in the same state**. It requests `:align` only for a tap that was counted. It no longer returns `:timing/calculate-bpm`. The time is `(or (:input-time event) (:time event))`.
- `laser-show.events.core` (existing): remove `effect-timing-calculate-bpm` (`:170-184`) and its entry in `effects`, and in the docstring list.
- `laser-show.state.domains` (existing): `timing` loses `:tap-times` and gets `:tap {:taps [] :bpm nil :last-tap nil}` (`:37-56`); `trigger-runtime` loses `:tap-history` (`:108-109`). `laser-show.state.core` (existing): the three docstring examples that name `:tap-times` (`:107, 235, 243`) use another path.
- `laser-show.events.handlers.triggers` (existing, `:91-114`): `update-runtime` no longer writes `:tap-history`.
- `laser-show.vibes.engine` (existing): HIT's `:dispatch :timing/tap-tempo` carries `:input-time` and is skipped when `:hit-taps-tempo?` is false or `link-sync-enabled?`.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:vibe-runtime :rotation]` one of `:active`, `:armed`, `:locked`; `:entered-at`, `:auto?` | app state, session | the engine, `:vibe/arm`, `:vibe/advance`, `:vibe/set-auto` | no / no |
| `[:config :vibes :settings :rotation]` `{:mode :off :seconds 45 :pause-beats 2.0}` | document | the Vibes tab (Phase 3 editor) | yes / yes |
| `[:timing :tap]` (replaces `:tap-times`) | app state, session | `:timing/tap-tempo` | no / no |
| `[:timing :bpm]` | document, live (`state/history.clj:19-21`) | tap, Link, the BPM box | no / no (unchanged) |
| `:vibe-sent` in `show-time/!runtime` | the clock's runtime atom | `tick!` | – |

`[:vibe-runtime :auto?]` is `nil` until the switch is used, which means "use the saved `:mode`". The Perform switch is a **session** choice: it never marks the project unsaved.

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:vibe/arm {:from}` | NEW | the show clock, `tick!` (`services/show_clock.clj:161`, with the other reports) | yes |
| `:vibe/advance {:reason :from}` | NEW | the show clock (`services/show_clock.clj:161`); a FLASH release (inline `:dispatch`, `events/core.clj:157-160`) | yes |
| `:vibe/set-auto {:auto?}` | NEW | the Perform switch (`views/tabs/perform.clj`, NEW) | yes |
| `:timing/tap-tempo` | yes | the TAP button (`views/toolbar.clj:117-129`); `:vibe/press :hit` | yes |
| `:timing/calculate-bpm` | **removed** | – | – |

## 7. Data examples and invariants

### When the clock sends what (`rotation/due`)

Settings `{:seconds 45 :pause-beats 2.0}`, 120 BPM (a beat is 500 ms), three vibes, auto on.

| `:rotation` | `now − :entered-at` | held | `now − :last-hit-at` | Result |
|---|---|---|---|---|
| `:active` | 44 000 ms | – | – | nil |
| `:active` | 45 000 ms | – | – | `{:event/type :vibe/arm :from :vibe-1}` |
| `:armed` | | HIT held | 5 000 ms | nil (a key is down) |
| `:armed` | | none | 900 ms | nil (less than 2 beats = 1 000 ms) |
| `:armed` | | none | 1 000 ms | `{:event/type :vibe/advance :reason :gap :from :vibe-1}` |
| `:locked` | any | none | any | nil |
| any, auto off | any | | | nil |
| any, one vibe only | any | | | nil |
| any, `:blackout? true` | any | | | nil; and on un-blackout `:entered-at` is set to now |

### What the handlers do

| Event | Before | After |
|---|---|---|
| `:vibe/arm :from :vibe-1` | `:rotation :active`, current `:vibe-1` | `:rotation :armed` |
| the same again | `:armed` | unchanged |
| `:vibe/advance :gap :from :vibe-1` | `:armed`, current `:vibe-1` | `switch-to :vibe-2`, `:rotation :active`, `:entered-at` now |
| the same, but the performer already tapped VIBE | current `:vibe-2` | unchanged (`:from` is stale) |
| FLASH release | `:armed`, HIT and ACCENT not held | advance (reason `:flash`) |
| VIBE tap | `:armed` | next vibe, `:active`, `:entered-at` now |
| VIBE hold (unlock) | `:locked` | `:active`, `:entered-at` now |

### Tap tempo (ported from `beyond-vibe`)

| Taps (ms) | Result |
|---|---|
| `0 500 1000 1500` | 120.0 BPM |
| `0 500 505 1000 1500` | 120.0: the tap at 505 is 5 ms after 500, so it is ignored |
| `0 400 800 1200`, then `10000` | 150.0, then 150.0: the gap starts a new series and keeps the last tempo until two new taps |
| `0 500 1500 2000 2500` (a missed beat) | intervals 500 1000 500 500, median 500: 120.0 |
| `0` | no change (one tap makes no interval) |
| `0 500`, then a 2.5 s pause, then `3000 3500 4000` | 120.0 throughout: the pause starts a new series, which gives 120.0 again after its second tap |

An extra tap between beats (`0 500 750 1000 1500`) gives 160 BPM, not 120: the median of four intervals can't undo it. V3's option C (an adaptive follower) is the answer to that, later.

### Invariants

1. A vibe never changes by itself while auto is off, locked, in blackout, with only one vibe, or while a HIT, ACCENT or FLASH key is down.
2. At most one `:vibe/advance` takes effect per armed period, however many reports are sent (stale `:from`, state guard).
3. Switching vibes by hand restarts the 45 s. Unlocking restarts it.
4. The Perform switch changes no document root and adds no undo step.
5. The rotation check costs a few comparisons per tick and allocates nothing when nothing is due. *Unverified*: measure with the clock's per-tick probes.
6. One tap algorithm: the TAP button and HIT give the same BPM for the same taps.
7. A bounced tap changes neither the tempo nor the alignment.
8. While Link sync is on, a HIT press doesn't tap. The TAP button still does, as today.

## 8. Tests (named test vars + fixtures)

- `laser-show.vibes.rotation-test` (NEW): `armed-after-the-duration-test`, `gap-advances-after-the-pause-test`, `held-key-defers-the-switch-test`, `locked-never-advances-test`, `auto-off-never-advances-test`, `single-vibe-never-advances-test`, `blackout-never-advances-test`, `due-allocates-nothing-when-idle-test` (a call count, not a timing).
- `laser-show.events.handlers.vibes-test` (existing): `arm-is-idempotent-test`, `stale-advance-is-ignored-test`, `flash-release-while-armed-advances-test`, `manual-next-restarts-the-timer-test`, `unlock-restarts-the-timer-test`, `auto-switch-is-session-only-test` (document roots identical), `auto-defaults-from-the-saved-setting-test`, `unblackout-restarts-the-timer-test`.
- `laser-show.animation.show-time-test` (existing): `due-reports-keeps-separate-memories-test`.
- `laser-show.services.show-clock-test` (existing, pattern `:128`): `vibe-report-is-sent-once-per-interval-test` (state armed with the gap past; `tick!` twice 100 ms apart: one event; 300 ms apart: two, the handler guard makes the second harmless).
- `laser-show.triggers.tap-tempo-test` (existing, `:5-13`): migrated, and NEW `steady-taps-test`, `bounce-is-ignored-test`, `pause-keeps-the-last-tempo-test`, `missed-beat-test`, `one-tap-changes-nothing-test` (the table above).
- `laser-show.events.timing-handlers-test` (existing): `timing-tap-tempo-test` and `timing-tap-tempo-phase-alignment-test` are **rewritten for the new shape**, not deleted. The vector `[:timing :tap-times]` becomes `[:timing :tap :taps]`; the 2000 ms boundary becomes 1500 ms; `:timing/calculate-bpm true` becomes "the BPM is in the same state"; each assertion keeps its meaning. NEW `bounced-tap-requests-no-align-test`, `input-time-wins-over-time-test`.
- `laser-show.events.core-test` (existing): `tap-tempo-bpm-continues-inline-test` (`:59-66`) becomes `tap-tempo-sets-bpm-in-one-state-test`; `:timing/calculate-bpm` leaves the effect list (`:37-45`).
- `laser-show.vibes.engine-test` (existing): `hit-skips-the-tap-while-link-sync-is-on-test`, `hit-skips-the-tap-when-the-setting-is-off-test`.
- UI, `laser-show.ui-test.perform-test` (existing): `auto-switch-and-countdown-test` (the switch changes `[:vibe-runtime :auto?]`; the label shows the countdown), `armed-state-shows-the-next-vibe-test`. `laser-show.ui-test.toolbar-test` (existing): `tap-tempo-button-records-taps` (`:45-57`) is **rewritten**: it reads `[:timing :tap :taps]`, spaces its clicks more than 100 ms apart (a faster click is a bounce and is now ignored, so it would never change the state the test waits on), and checks "kept taps are at most 1.5 s apart".

Run: `clj -M:mac-arm:test rotation tap-tempo timing show-clock show-time vibes core`, then the UI tests, then both full suites. Manual (unverified): tap the tempo from a real pad against a click track, and watch one auto-advance during a real set (Phase 7 hardware check).

## 9. Out of scope / alternatives considered

- **An adaptive tempo follower** (V3 C): later.
- **Advancing by energy** (G3): `:order` is the order. A `:rotation :policy` (`:order`, `:up`, `:random`) is a small later addition.
- **Pausing the countdown during a break in the music**: the 2-beat gap rule already waits for a pause before it switches.
- **Quantizing the switch to a bar line**: rejected in `00-overview.md` §9 for presses; for an automatic switch it would be fine and is a later option.
- **Keeping the tap vector for compatibility**: the only readers of `[:timing :tap-times]` are the tap handler, the `:timing/calculate-bpm` effect (both replaced), three docstring examples (`state/core.clj:107, 235, 243`, updated) and the tests above (grep). It isn't saved (`state/persistent.clj:50`), so there is no migration.

## 10. As built (2026-11-19)

Committed as `4a4a10a` on top of `3d82fe7` (built as `443e4b3` on branch `vibe-phase6` from `2d1f813`, vibe Phases 1–4b in it; the cherry-pick conflicted in the two require lists the merge-tree row below names, both resolved by keeping both requires). §1–9 cite `b1da0f2`; the lines below are at `443e4b3`: the same in `4a4a10a` except in the files Phase 5 also changed (animation/show_time.clj, css/vibes.clj, events/handlers/vibes.clj, services/show_clock.clj, state/domains.clj, subs.clj, views/tabs/perform.clj, events/handlers/vibes_test.clj, services/show_clock_test.clj, ui_test/perform_test.clj), where they are shifted by its lines. Vibe Phase 5 was built at the same time and is on `master` (`9f5d78a`, `0faa18c`); the merge is below.

**Where it is now** (paths under `src/laser_show/`)
- `vibes/rotation.clj` (NEW, pure): `settings` `:18`, `auto?` `:25` (the switch, else the saved `:mode`), private `setting` `:34` (a number, or the default), `seconds` `:40`, `key-down?` `:45`, private `turning?` `:55`, `may-advance?` `:69`, `due` `:77`.
- `vibes/engine.clj`: private `restart-rotation` `:203` (used by `switch-to` `:210`, `toggle-lock` `:419`, BLACKOUT off in `toggle-blackout` `:439` and `set-auto`), `change-vibe` `:219` (the pulse); `with-tap` `:264` carries `:input-time`; `let-go-of-flash-key` `:364` (`release-flash` `:371` restores on top of it); `press` `:466` sets `:last-hit-at` on every HIT press; `release` `:484` dispatches the FLASH advance; NEW public `arm` `:540`, `advance` `:550`, `set-auto` `:568` (private `bpm-of` `:538`).
- `events/handlers/vibes.clj:186-188`: `:vibe/arm`, `:vibe/advance`, `:vibe/set-auto`, in one block after `:vibe/release-all`.
- `animation/show_time.clj`: `initial-runtime` `:39` has `:vibe-sent {}`; `due-reports` `:220` is public and takes the memory map; `step` `:243` passes `(:sent rt)`. `services/show_clock.clj`: NEW private `rotation-step` `:146`; `tick!` `:155` runs it after `publish!`, beside the status, on the tick's one read of app state and the wall clock, and sends its reports after the step's.
- `triggers/tap_tempo.clj`: `default-options` `:11`, `make` `:13`, private `median` `:18`, `tap` `:25` (a 3-arity takes the options), `beats-per-minute` `:49`. `events/handlers/timing.clj`: `handle-timing-tap-tempo` `:43`. `events/core.clj`: `effect-timing-calculate-bpm`, its entry and its docstring line are gone. `events/handlers/triggers.clj`: `update-runtime` `:115` writes no `:tap-history`; a `:tap-tempo` binding carries `:input-time` (`:102`).
- `state/domains.clj`: `timing` `:tap` `:41` (was `:tap-times`); `trigger-runtime` without `:tap-history`; `vibe-runtime` `:rotation` `:115` (with `:armed`), NEW `:auto?` `:117`, and the docs of `:entered-at` and `:last-hit-at`; `[:config :vibes :settings :rotation]` `:276`. `state/core.clj`: the three docstring examples name other paths.
- `views/components/vibe_rotation.clj` (NEW): `countdown-text` `:21`, `armed-opacity` `:32`, `redraw-countdown!` `:38` (through `clock-view/ext-clock-redraw`), `rotation-controls` `:47`: the label `#vibe-countdown` and the toggle button `#vibe-auto`. `views/tabs/perform.clj:103`: in the header's second row, after a spacer. `subs.clj`: NEW `vibe-rotation` `:973`, after `vibe-header`, and one require. `css/vibes.clj:60-85`: `.vibe-countdown`, `-armed`, `.toggle-button.vibe-auto` and `:selected`.

**The brief's corrections**
1. `switch-to` doesn't pulse `:vibe` (4a); `advance` goes through `change-vibe`, which pulses it as a VIBE tap does, for a gap and for a FLASH release. Test `auto-advance-pulses-vibe-as-a-tap-does-test`; the fuzz checks one `:vibe` pulse per automatic change.
2. `[:timing :tap]` keeps at most `:max-taps` 5 taps; `tap-history-keeps-the-last-8-test` is now `tap-history-keeps-the-last-5-test` (20 taps: never more than 5 kept, the last 5, and the handler's BPM is the estimator's for steady and drifting taps).
3. The tap is at `(or (:input-time event) (:time event))`. HIT's tap carries its press's own time, so a press and its tap share one time (the input's, or a pad press's handling time); a bound tap-tempo key carries its input's `:timestamp`; the TAP button has no input time. Test `input-time-wins-over-time-test`, `tap-tempo-binding-taps-at-the-input-time-test`.
4. The show clock stays the only writer of show time: the rotation's reports go out from `tick!` through `due-reports` with the runtime's `:vibe-sent`. `one-state-and-input-read-per-tick-test` and `snapshot-shape-test` pass unchanged.
5. The fuzz walks AUTO on and off, arms and advances that come late or for another vibe, the clock's own reports (each handled twice: the second must change nothing), and quiet spells (every key let go of, then 2 s of the clock; or FLASH held through 3 s and let go of, also with HIT pressed first). Its checks: 06 invariants 1–4 worked out without `vibes.rotation`, and that every switch, an unlock, AUTO on and BLACKOUT off start the vibe's time again.
6. The countdown and the switch are their own component; the Perform tab gains two lines in the header. Phase 5's files are touched only locally: one block in `events/handlers/vibes.clj`, one require and one sub after `vibe-header` in `subs.clj`, and in `state/domains.clj` the `:tap` key, the vibe runtime's keys and one line of the `:vibes` settings default.
7. The new UI tests click no pad: AUTO is a toggle button, which takes the robot's clicks, and the clock is ticked by hand; the opacity is read with `ui/query-all`.

**Other differences from the plan**

| Plan | As built | Why |
|---|---|---|
| the BPM is written "in the same state" on every tap | only once the series has an interval; a tap after a pause starts a new series and leaves `[:timing :bpm]` as it is | "keeps the last tempo": a lone tap mustn't put back a tempo the BPM box or Link set since |
| a key down: HIT, ACCENT or FLASH | any role the engine holds, VIBE too, and any HIT, ACCENT or FLASH pulse held by a press that played nothing | a VIBE tap while the clock switches would switch twice; an effects-only press is a key down |
| the gap from `:last-hit-at` | as planned, and every HIT press outside blackout sets it, also one that plays nothing | Phase 1 set it only when a cue started: an effects-only HIT never held the gap off (test `a-hit-that-plays-nothing-holds-the-gap-off-test`; `empty-pool-reports-a-problem-test` and the fuzz's empty-pool check allow the key) |
| `:vibe/advance` ignored if `:from` is stale or no longer `:armed` | the handlers ask the rotation again: `:gap` and `:vibe/arm` only when `rotation/due` still gives that report, `:flash` when `rotation/may-advance?` still holds, and `:from` plays | invariant 1 against the agent's queue: a key pressed after the clock's report and handled before it |
| a FLASH release advances | the inline `:vibe/advance :flash` carries the release's time, and the release doesn't start what the solo flash stepped aside | the 200-seed run (7 seeds): a `:sustain` voice the flash kept waiting was picked and then stopped by leaving the look in the same turn (a bag turn lost, 01 inv 3), or took a grid cue's cell over and stopped it (inv 4). They end with the look anyway |
| `:vibe/set-auto {:auto?}` | turning it on (with a vibe current) starts the vibe's time; off, an armed change is called off | else AUTO turned on after ten minutes in a vibe changes it at the next gap, and a FLASH release still advances with AUTO off |
| `:mode :off` | `:auto` is on, anything else off; `:seconds` or `:pause-beats` that are no number are 45 and 2.0 | the doc names no on value; a bad hand-edited file mustn't throw on the clock's thread |
| the settings written by "the Vibes tab (Phase 3 editor)" (§5) | no editor: §4 and §8 list none | the saved `:mode` comes from a file; the switch is the session's |
| the countdown | hidden while AUTO is off, locked (the header says LOCKED), in blackout or with one vibe; "ACTIVE 0:00 / 0:45" before a vibe plays; never past its period; boxed in orange while armed | |
| the VIBE pad shows "LOCKED" | there since Phase 2 (its badge) | |
| `due-allocates-nothing-when-idle-test`: "a call count" | the bytes the thread allocated over 10,000 calls (`ThreadMXBean`), under 4 KB, for nine idle states | deterministic, and the mutants below show it catches a merge (664 bytes a call) and a boxed subtraction (24) |

**Found, not done**
- ACCENT presses and releases don't count toward the gap, as in §7; beyond-vibe counts both rhythm keys' presses and releases (`last_rhythm_activity`), so there a long HIT hold's release starts the gap, and here the change can come at the next tick after it.
- `[:timing :tap]` survives New and Open, as `:tap-times` did; harmless, a pause starts a new series.
- Invariant 5's time per tick is not measured with the clock's probes: `due` allocates nothing (tested), and its idle path is a handful of map lookups.

**Tests**: 34 new unit tests (1,054 → 1,088) and 2 UI tests
- `triggers.tap-tempo-test` (+6, two migrated): `calculates-bpm`, `timeout-starts-new-series` (the tempo stays); NEW `steady-taps-test` (5 kept, clamped 40–300), `bounce-is-ignored-test` (state identical; 100 ms counts; a tap from before the last is ignored), `pause-keeps-the-last-tempo-test` (and the 1.5 s boundary), `missed-beat-test` (and 160 BPM for an extra tap), `one-tap-changes-nothing-test`, `beats-per-minute-test`.
- `events.timing-handlers-test` (+2): `timing-tap-tempo-test` and `timing-tap-tempo-phase-alignment-test` rewritten (the 1.5 s boundary both ways, the BPM in the same state, no other effect), `tap-history-keeps-the-last-5-test`, NEW `bounced-tap-requests-no-align-test`, `input-time-wins-over-time-test`. `events.core-test`: `tap-tempo-sets-bpm-in-one-state-test`, and `:timing/calculate-bpm` is no effect. `events.handlers.triggers-test` (+1): `tap-tempo-binding-taps-at-the-input-time-test`.
- NEW `vibes.rotation-test` (8): the eight of §8.
- `events.handlers.vibes-test` (+11): the eight of §8, `auto-advance-pulses-vibe-as-a-tap-does-test`, `a-hit-that-plays-nothing-holds-the-gap-off-test`, `hit-taps-the-tempo-as-the-tap-button-does-test` (invariant 6). `stale-advance-is-ignored-test` also sends a FLASH advance for another vibe, with three vibes; `flash-release-while-armed-advances-test` also holds HIT or ACCENT, AUTO off, `release-all`, and a `:sustain` HIT waiting for the solo flash.
- `vibes.engine-test` (+2): `hit-skips-the-tap-while-link-sync-is-on-test` (the TAP button still taps), `hit-skips-the-tap-when-the-setting-is-off-test`; six tap assertions carry `:input-time`.
- `animation.show-time-test` (+1) `due-reports-keeps-separate-memories-test`; `services.show-clock-test` (+1) `vibe-report-is-sent-once-per-interval-test` (two vibes inline, so the file's requires stay as they are). NEW `views.components.vibe-rotation-test` (2): `countdown-text-test`, `armed-countdown-pulses-twice-a-second-test`. `vibes.engine-fuzz-test` extended (5 above): its three seeds see 31 arms, 11 changes at a gap and 4 at a FLASH release, and 288 arms and advances that change nothing.
- UI `perform-test` (+2): `auto-switch-and-countdown-test` (off in a new project; the switch writes `[:vibe-runtime :auto?]`, no undo step, nothing unsaved; "ACTIVE 0:00 / 0:45", then 0:31 of 0:45; hidden while locked), `armed-state-shows-the-next-vibe-test` (a hand-ticked clock arms, "ARMED → next: Lines", pulsing; at a gap the clock's advance makes Lines current and the countdown starts again). `toolbar-test/tap-tempo-button-records-taps` reads `[:timing :tap :taps]`, clicks 150 ms apart, and checks the kept taps are 100 ms to 1.5 s apart.

**Red first**
- `tap-tempo-test` against the old estimator: 8 tests, 14 failures, 1 error (it averages: 96 BPM for the missed beat, 120.4 with the bounce; a 2 s gap dropped the tempo; 6 taps kept; no options argument).
- `timing-handlers-test` with `core-test` against the old handler and effect: 19 failures (`[:timing :tap-times]`, `:timing/calculate-bpm`, and 300 BPM for three taps 400 ms apart by their input time: the old handler timed them when handled). `triggers-test`'s new test against the old `triggers.clj`: 2 failures.
- `rotation-test`: the namespace didn't load. `events.handlers.vibes-test` against the old engine and router: 30 tests, 21 failures. `show-time-test`: `due-reports` was private (compile error); `vibe-report-is-sent-once-per-interval-test` against the old `show_clock.clj`: 4 failures. The two `hit-skips-…` tests passed at once: Phase 1 built the skip; they pin it.
- UI: the two perform tests and the toolbar test against the old `perform.clj`, `timing.clj` and `events/core.clj` (stashed): 3 errors, each timing out.

**Mutants**: each against the fuzz's three seeds and the engine, handler and rotation tests (77 tests).

| # | Mutant | Fuzz | Unit tests failing |
|---|---|---|---|
| M1 | `advance` without the `:from` check | 0 of 3 | `stale-advance-is-ignored-test` (1), after its three-vibe case was added: with two vibes a stale FLASH advance lands on the vibe that plays, and the mutant survived |
| M2 | the gap ignores the keys down | 3 of 3 | 13 (`held-key-defers-the-switch-test`, the allocation test, two handler tests) |
| M3 | a FLASH release advances whatever is held | 3 of 3 (0 of 3 before the "FLASH over HIT" spell) | 2 |
| M4 | a switch doesn't start the vibe's time again | 3 of 3 | 6 tests or more |
| M5 | `advance` through `switch-to`, no pulse | 3 of 3 | 2 |
| M6 | the gap ignores the last HIT | 1 of 3 | 7 |
| M7 | a HIT that plays nothing isn't a hit (Phase 1's `:last-hit-at`) | 2 of 3 | 3 |
| M8 | AUTO off leaves an armed change | 0 of 3 | 1 |
| M9 | the FLASH release restores what it ducked before it advances (the code before the stress-run fix) | seed 180 | 1 |

And two in `due` against `due-allocates-nothing-when-idle-test`: a settings merge in `auto?` (6.6 MB per 10,000 calls) and a boxed subtraction (240 KB). All killed.

**Verification, 2026-11-19, macOS arm64, JDK 25**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test tap-tempo timing triggers show-time show-clock vibes rotation engine core` | 313 tests, 1,858 assertions, 0 failures |
| `VIBE_FUZZ_SEEDS=200 clj -M:mac-arm:test -n laser-show.vibes.engine-fuzz-test` | seeds 1–200, 2,000 steps each: 0 failures (71 s); 7 seeds failed before the FLASH release fix above |
| `clj -M:test-ui:mac-arm:test-ui-headless -n` perform-test, toolbar-test, vibes-test | 33 tests, 227 assertions, 0 failures |
| `clj -M:mac-arm:test` (full unit suite) | 1,088 tests, 34,168 assertions, 0 failures (baseline at `2d1f813`: 1,054, 33,972) |
| `clj -M:kondo --lint` on the 28 touched files and `testfx.clj` | 0 errors; 1 warning, there before (the redundant `let` in `triggers.clj` `update-runtime`, `:119`) |
| `git merge-tree --write-tree master 443e4b3` (master `3d82fe7`) | 2 conflicts, both in require lists. `services/show_clock.clj`: master adds `vibes.faders` (the faders derived each tick), this branch `vibes.rotation`; keep both, `tick!` merges cleanly (master's input read and `:faders`, this branch's `rotation-step`). `events/handlers/vibes_test.clj`: both add `state.persistent`, this branch also `vibes.rotation`; keep both |
| that tree exported, the two resolved by keeping both (no branch touched) | full unit suite 1,123 tests, 34,467 assertions; perform, toolbar and vibes UI 37 tests, 273 assertions; 0 failures |
| layout | headless screenshots at 1200×800 of the merged tree: the countdown and AUTO fit in the header's second row, with Phase 5's meters under the pads |
| full UI suite | not run here: it runs after the merge |
| after the merge, on `master` at `4fa9eee`: `clj -M:mac-arm:test`, then the full UI suite | 1,123 tests, 34,469 assertions, 0 failures; UI 136 tests, 693 assertions, 0 failures |
| manual (unverified) | a real pad's taps against a click track; one auto-advance during a real set (Phase 7 hardware check) |
