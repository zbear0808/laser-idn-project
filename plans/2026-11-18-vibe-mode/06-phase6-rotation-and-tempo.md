# Phase 6: auto-advance on phrase gaps, and one tap tempo

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** Proposed. Needs Phases 1 and 2; V3 (HIT taps the tempo) and V4 (auto-advance default)   **Last verified against:** `b1da0f2`
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
