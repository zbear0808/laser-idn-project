# Phase 4 — Transport, time and timecode display

**Written:** 2026-11-08 (updated the same day with the user's decisions D11, D12)   **Written for commit:** `d0a7dc2`
**Status:** Done (2026-11-11, branch `timeline/phase4-transport`)   **Last verified against:** branch head

> **How the implementation differs from this plan:**
> - **Clip-local time also fixes once-mode inside timeline clips.** `playback/clip-timing-ctx` sets `:trigger-time 0` and `:time-ms` to the clip-local ms, so once-mode modulators measure progress from the clip start. 03 #1 stays open for plain cues. The context keeps the absolute position as `:window-beats`, because nested windows stay absolute (D5).
> - **Converted cues change behaviour.** Grid triggers start every timeline cue with `:timeline? true`, so cues converted in Phase 1 lose the phase glide toward the global beat. They still loop and still follow the global BPM.
> - **Extra events and subs.** NEW `:timeline/toggle-play` (play/pause button and Space) and NEW `subs/timeline-transport` (`:stopped`/`:playing`/`:paused`, which doesn't change on every tick). Transport events without `:col`/`:row` use the open editor's cell, so the dialog's scene-level Space works.
> - **`update-timing-accumulators!` is now public.** It already took the time as an argument, so no new arg was needed. The per-cue step is the pure `advance-active-cues`.
> - **Scrubbing.** A ruler press seeks immediately, a drag draws a local scrub line on the lane canvas (not the overlay), and release seeks again. The end marker (non-looping timelines only) drags to set `:timeline/length`.
> - **Snap.** The snap value is stored in beats and shared. In time mode the options are time values converted at the cue's tempo, so their beat values change with the tempo.
> - **Tests.** The Phase 4 UI tests live in `laser-show.ui-test.timeline-editor-test`. The screenshot baseline gained `05-end-marker` and `06-time-ruler`.
**Supersedes / Superseded by:** none   **Related findings:** TL-6, TL-24, TL-25, TL-26, TL-27, TL-28 (clock), TL-31 (tempo + display); 02 #8 / Q4; 03 #1 / MR1; 04 #11 / LE1; overview T1, T4; README "Timeline System" (`README.md:153-165`)
**Followed by:** `06-phase5-timecode-chase.md` (follow incoming MTC)

## 1. Current behaviour (file:line)

- **Start** = trigger the grid cell. `grid/trigger-cue` (`grid.clj:32-43`) stores `cue-timing/create-cue-timing-state` (`cue_timing.clj:227-245`): accumulated beats 0, phase offset 0, **phase target = global beat phase** (`:239`). Timeline cues are treated like every other cue.
- **Advance.** The `preview-update` timer (30 Hz, `frame_service.clj:776-795`) calls `update-timing-accumulators!` (`:79-122`). That runs `update-cue-timing` (`cue_timing.clj:120-166`) per active cue via `state/swap-state!`, outside the event flow (overview T1). The phase offset decays toward the target. The loop wraps **accumulated** beats (`:154-159`).
- **Tempo.** Every cue advances at the single global BPM:
  - `ex/bpm` at `frame_service.clj:92`, passed to every cue at `:113-120`
  - `get-bpm` at `:610`, used for the timing context at `:624`
  - `[:timing :bpm]` read at `multi_engine.clj:160`, used at `:171,189`

  Tap tempo (`timing.clj:33-46`), Link and typing a BPM all change it live, so a pre-recorded show has no fixed beat↔seconds mapping (TL-31).
- **Render.** Clip windows are tested on `:effective-beats` = accumulated + phase offset (`cue_timing.clj:188`, `frame_service.clj:410`). The editor's playhead draws `:accumulated-beats` (`timeline_cue_editor.clj:110`), so it disagrees with the output by the phase offset (up to ~1 beat). Tap tempo realigns every active cue's phase (`timing.clj:45`), and so does the Link phase correction (`timing.clj:115-134`). That shifts a running timeline.
- **Loop.** The wrap happens on accumulated beats, but the window test uses effective beats, so the loop region the clips see is shifted by the phase offset (TL-25).
- **End.** There's no end. After the last clip the cue stays in `[:playback :active-cues]`, dark.
- **Seek / pause.** There's neither. Clicking the ruler only sets `[:ui :timeline :cursor-beat]` (`timeline_editor.clj:437-446`). Global `:transport/stop` (`timing.clj:68-77`) resets every cue's timing and sets `:playing? false`; `:transport/retrigger` (`:79-86`) resets every cue.
- **Timecode.** None. The ruler shows beats only (`timeline_editor.clj:176-215`).
- **Triggers.** `:start-timeline` / `:stop-timeline` actions emit `:timeline/start` / `:timeline/stop` (`triggers.clj:75-78`). There's no handler (falls to `timeline.clj:673-675`), and the name `:timeline/start` collides with the clip data key. No UI creates these actions (grep at `d0a7dc2`).
- **Clip time.** Every clip and its effects get the cue's timing context and `elapsed-ms` (`frame_service.clj:620-627`). Once-mode modulators are measured from the cue trigger, and are additionally stuck at 0 by 03 #1.

## 2. Root cause

Root cause 5 (`00-overview.md` §2). Timeline cues reuse the live-cue clock, whose phase glide, global realignment and global BPM are right for looping VJ cues and wrong for a sequenced, pre-recorded show.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `update-cue-timing`, `get-cue-timing-context`, `create-cue-timing-state` (`cue_timing.clj:120-245`) | Extended with a `:timeline?` mode; no new clock. Each already takes `bpm` as an argument, so a per-cue tempo is a call-site change |
| `update-timing-accumulators!` (`frame_service.clj:79-122`) | Same writer; also stops timelines at their end |
| `grid/trigger-cue`, `untrigger-cue` (`grid.clj:32-55`) | Logic moves into `cue_timing` as `activate-cue` / `deactivate-cue` on the `:playback` map; grid keeps thin wrappers |
| `:loop {:enabled? :start :duration}` | Unchanged |
| `model/active-at?`, `muted-track-ids`, `clip-start` (Phase 2) | Active-clip set |
| `subs/timeline-playhead` (Phase 2) | Playhead now equals the output position |
| `layout/ruler-ticks` (Phase 3, `03` §4.1) | Gains a `:time` mode |
| `"timeline"` route (`events/handlers.clj:63`) | Transport events |

## 4. Changes per namespace

### 4.1 `laser-show.animation.cue-timing` (existing)
- NEW `activate-cue [playback cell now global-beats {:keys [timeline? start-beat]}] → playback`. It moves `grid.clj:32-43`, operating on the `:playback` submap. For `timeline? true` it sets `:timeline? true`, `:accumulated-beats start-beat`, `:phase-offset 0.0`, `:phase-offset-target 0.0`, `:paused? false`.
- NEW `deactivate-cue [playback cell] → playback` (moves `grid.clj:45-55`).
- `update-cue-timing` (`:120-166`): NEW optional 6th argument `timeline-cfg` = `{:length beats}`.
  - `:paused?` → only `:last-frame-time` advances.
  - `:timeline?` → the phase stays 0 (no decay), so accumulated = effective. The loop wrap is unchanged, and that fixes TL-25 by construction.
  - No enabled loop, and accumulated ≥ `:length` → accumulated = length, `:ended? true` (D11: timelines always stop at the end).
  - The `bpm` argument is now the **cue's** tempo (§4.5).
- `align-cue-timing-phase` (`:46-58`): returns its input unchanged for `:timeline?` cues (TL-24). The tap-tempo path (`timing.clj:45`) goes through it.
- NEW `seek [cue-timing beat] → cue-timing`: sets accumulated beats, keeps `:last-frame-time`.

### 4.2 `laser-show.events.handlers.timing` (existing)
- `handle-timing-set-phase-offset-target` (`timing.clj:115-134`): skip cues with `:timeline?` when updating `[:playback :active-cues]`.

### 4.3 `laser-show.events.handlers.grid` (existing)
- `trigger-cue` / `untrigger-cue` (`grid.clj:32-55`) become `(update state :playback cue-timing/activate-cue …)` / `deactivate-cue`. They pass `:timeline? (cue-chains/timeline-cue? chain)` and `:start-beat 0.0`. Grid triggers keep starting timelines from the top (live use).

### 4.4 `laser-show.timeline.model` (Phase 2, extended)
- NEW `effective-length [cue]` = `(:timeline/length cue)` when set, else the last clip end (0 for empty cues).
- NEW optional document keys and their ops:

| Key | Meaning | Op |
|---|---|---|
| `:timeline/length` | beats; `nil` = automatic | `{:op :set-length :beats b}` |
| `:timeline/tempo-bpm` | locked tempo; `nil` = follow global BPM | `{:op :set-tempo :bpm b}` |
| `:timeline/tc-start` | timecode of beat 0, in seconds; default `0.0` | `{:op :set-tc-start :seconds s}` |
| `:timeline/tc-fps` | `24`, `25`, `29.97` (drop-frame) or `30`; default `30` | `{:op :set-tc-fps :fps f}` |

- `convert-to-timeline` (Phase 1) sets none of these: converted cues loop and follow the global BPM, as before.

### 4.5 `laser-show.timeline.playback` (NEW, `src/laser_show/timeline/playback.clj`)
Pure, and used on the render threads. Justification: hot-path helpers shouldn't live in the document namespace, and `frame_service` and `multi_engine` both need them.

- `cue-bpm [cue global-bpm]` = `(or (:timeline/tempo-bpm cue) global-bpm)` for timeline cues, `global-bpm` otherwise.
- `timeline-cfg [cue] → {:length}`, read by `update-timing-accumulators!`.
- `clip-timing-ctx [timing-ctx clip bpm] → timing-ctx`. Its local beats are `beats − clip-start`; it sets `:accumulated-beats` and `:effective-beats` to that, `:phase-offset 0.0`, and `:accumulated-ms` / `:time-ms` = the local beats in ms at `bpm`.
- `clip-elapsed-ms [clip-ctx]`, the value passed as `elapsed-ms` to effects.

### 4.6 `laser-show.timeline.timecode` (NEW, `src/laser_show/timeline/timecode.clj`)
Pure. Justification: the ruler, the readout, the inspector and Phase 5 chase all need the same maths.

- `beats->seconds [beats bpm]`, `seconds->beats [s bpm]`.
- `seconds->tc [s fps] → {:h :m :s :f :drop? bool}`, `tc->seconds [tc fps]`. 29.97 uses SMPTE drop-frame (frame numbers 0 and 1 are skipped at the start of every minute except multiples of 10).
- `format-tc [tc]` → `"01:00:05:12"` (with `;` before frames for drop-frame).
- `show-seconds [cue beats global-bpm]` = `tc-start + beats->seconds(beats, cue-bpm)`. It returns `{:seconds s :approx? (nil? tempo-bpm)}`: without a locked tempo the time is only an estimate at the current BPM.

### 4.7 `laser-show.services.frame-service` and `laser-show.backend.multi-engine` (existing)
- `update-timing-accumulators!` (`frame_service.clj:79-122`):
  - passes `(playback/cue-bpm chain bpm)` instead of the global `bpm` (`:113-120`)
  - passes `(playback/timeline-cfg chain)` for `:timeline?` cues
  - after the per-cue update, still in the same `swap-state!`, applies `deactivate-cue` to every cue with `:ended?`

  That's the same writer as today, so T1 doesn't get worse.
- `generate-current-frame` (`:619-637`) and `create-projector-frame-provider` (`multi_engine.clj:165-191`): build the timing context and call `generate-frames-by-zone` with `(playback/cue-bpm chain bpm)` (`frame_service.clj:624,627`; `multi_engine.clj:171,174,189`).
- `render-item-tree-by-zone` (`frame_service.clj:394-464`): for timeline cues, each **top-level** item renders with `(playback/clip-timing-ctx timing-ctx item bpm)` and `clip-elapsed-ms`. Nested children and effects inherit it. Window gating still uses the absolute position (D5).
- **Dependency.** Once-mode modulators only progress correctly after 03 MR1 (relative `:elapsed-*` context). Loop-mode modulators and keyframes phase-align to the clip start immediately.

### 4.8 `laser-show.events.handlers.timeline` (existing)
NEW events. All of them only touch `[:playback …]` and `[:ui :timeline-editor [col row] :cursor]`, so they create no undo entry and don't mark the project unsaved.

- `:timeline/play {:col :row :from beat?}`. `from` defaults to the per-cue cursor.
  - Inactive cue → `activate-cue` with `:start-beat from`, plus `[:playback :playing?] true`.
  - Paused → unpause, and `seek` if `:from` was given.
  - Playing with `:from` → `seek`.
  - A cursor at or past the end starts from 0.
- `:timeline/pause {:col :row}`: toggles `:paused?`. No-op when inactive.
- `:timeline/stop {:col :row}`: `deactivate-cue`; the cursor is unchanged.
- `:timeline/seek {:col :row :beat}`: always sets the cursor; if the cue is active, it also `seek`s.
- `:timeline/set-ruler-mode {:mode (:bars | :time)}`: writes `[:ui :timeline-prefs :ruler-mode]` (UI pref).

### 4.9 `laser-show.events.handlers.triggers` (existing)
- `action-event` (`triggers.clj:75-78`): `:start-timeline` / `:stop-timeline` with a `[col row]` target emit `:timeline/play` / `:timeline/stop` with `:col`/`:row`. Other targets → `nil` plus a debug log. This closes 02 #8 for timelines.

### 4.10 Views (Phase 2/3 namespaces)
- Toolbar transport (`05` §3.7): ⏮ (seek 0), ▶/⏸ (`:timeline/play` / `:timeline/pause`), ■ (`:timeline/stop`). Space in the dialog = play/pause (coordinate with `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md`; the main window's Space accelerator stays global).
- **Readout** shows both `bar.beat.sub` and timecode, e.g. `3.2.1 · 00:00:05:12`. It comes from `subs/timeline-playhead`, or the cursor when stopped. Without a locked tempo the timecode is prefixed with `≈` and dimmed.
- **Tempo control** (toolbar, next to the readout): `128.0 BPM` plus a lock toggle. Lock sets `:timeline/tempo-bpm` to the current global BPM via `{:op :set-tempo}`; unlock clears it. While locked, tap tempo and Link don't change this timeline's speed.
- **Ruler mode** toggle `Bars | Time` (`:timeline/set-ruler-mode`). In `:time` mode, `layout/ruler-ticks` labels whole seconds / 10 s / minutes, depending on zoom, as `mm:ss` (or `hh:mm:ss` past an hour). Frame ticks appear at ≥ 400 px per second. Snap options switch to `1 frame / 1/4 s / 1 s / 5 s`, converted to beats through `cue-bpm`.
- **Ruler interaction:** press = `:timeline/seek`; during a drag the overlay draws the local scrub position, and release commits `:timeline/seek`. The end marker is draggable → `{:op :set-length}`; the area past the end is shaded (`05` §3.3).
- **Overlay:** playhead = `(:accumulated-beats timing)`, which now equals the output position.
- **Inspector**, Timeline section (nothing selected, `05` §3.8): length (auto/explicit), tempo (locked/follow), timecode start (`HH:MM:SS:FF` text field), frame rate.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:playback :active-cues [c r] :timeline?/:paused?/:ended?]` NEW | runtime (in app state today; moves with 02 M1.3 / 04 LE1) | timeline / grid handlers; `ended?` by the `preview-update` timer | No |
| `[:chains :cue-chains k :timeline/length]` NEW (optional) | document | `apply-op :set-length` | Yes |
| `[:chains :cue-chains k :timeline/tempo-bpm]` NEW (optional) | document | `apply-op :set-tempo` | Yes |
| `[:chains :cue-chains k :timeline/tc-start/:timeline/tc-fps]` NEW (optional) | document | `apply-op :set-tc-start/:set-tc-fps` | Yes |
| `[:ui :timeline-editor [c r] :cursor]` | UI state | `:timeline/seek`, `:timeline/set-cursor-beat` | No |
| `[:ui :timeline-prefs :ruler-mode]` NEW | UI state | `:timeline/set-ruler-mode` | No |

## 6. Events and effects

| Event / effect key | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:timeline/play`, `:timeline/pause`, `:timeline/stop`, `:timeline/seek` | NEW | toolbar, ruler gesture, Space key, `triggers.clj`, Phase 5 chase | yes |
| `:timeline/set-ruler-mode` | NEW | toolbar | yes |
| `:timeline/apply-op` `:set-length`, `:set-tempo`, `:set-tc-start`, `:set-tc-fps` | NEW ops | toolbar, inspector | yes |
| `:timeline/start` | emitted, never handled → **removed** | `triggers.clj:76` | — |
| `:transport/stop`, `:transport/retrigger` | yes (`timing.clj:224-226`) | unchanged (global) | yes |

No new side-effect keys.

## 7. Data examples and invariants

### 7.1 `update-cue-timing` for a timeline cue
`delta` is in beats for readability.

| Timing in | cfg | Out |
|---|---|---|
| acc 15.9, delta 0.2, loop off | length 16 | acc 16.0, `:ended? true` |
| acc 15.9, delta 0.2, loop {0, 16} on | length 32 | acc 0.1 |
| acc 7.0, `:paused? true`, delta 0.5 | — | acc 7.0 |
| acc 3.3, phase 0, target 0.4 (tap tempo) | — | phase 0, target 0 (`align` is a no-op) |
| locked tempo 120, global BPM tapped to 140, 500 ms tick | — | acc +1.0 (not +1.1667) |

### 7.2 `clip-timing-ctx`
BPM 120, timing ctx at absolute beat 18, clip start 16.

`→ {:accumulated-beats 2.0 :effective-beats 2.0 :phase-offset 0.0 :accumulated-ms 1000.0 :time-ms 1000.0 …}`

### 7.3 Timecode

| Call | Result |
|---|---|
| `beats->seconds 64.0 120.0` | `32.0` |
| `seconds->tc 3605.5 25` | `{:h 1 :m 0 :s 5 :f 12}` → `"01:00:05:12"` |
| `seconds->tc 60.06006 29.97` (frame 1800, drop-frame) | `"00:01:00;02"` (frames ;00 and ;01 are skipped) |
| `show-seconds` with `:tc-start 3600`, tempo locked 120, beat 10 | `{:seconds 3605.0 :approx? false}` |
| `show-seconds` with no locked tempo, global 128, beat 32 | `{:seconds 15.0 :approx? true}` |

**Invariants:**
1. For timeline cues, the playhead beat equals the beat used for window gating, at every tick.
2. A paused cue's frame is constant.
3. A non-looping timeline deactivates exactly once, when it reaches its effective length.
4. Tap tempo and Link phase correction never move a timeline cue, and never change a locked timeline's speed.
5. `tc->seconds ∘ seconds->tc` is the identity on frame boundaries for every supported fps.
6. Non-timeline cues behave exactly as before (every existing `frame_service_test` passes unchanged).

## 8. Tests (named test vars + fixtures)

- `laser-show.animation.cue-timing-test` (NEW, `test/laser_show/animation/cue_timing_test.clj`):
  - `timeline-update-examples-test` (§7.1)
  - `timeline-cue-ignores-phase-alignment-test`
  - `non-timeline-cue-unchanged-test`: today's behaviour as golden values
  - `activate-deactivate-match-grid-behaviour-test`
- `laser-show.timeline.playback-test` (NEW):
  - `clip-timing-ctx-test` (§7.2)
  - `cue-bpm-locked-vs-follow-test`
  - `editor-and-renderer-agree-test`: drives `create → update ×N` for random BPM and steps, and asserts the `timeline-playhead` value equals the `:effective-beats` used by `generate-frames-by-zone` (`frame_service_test.clj` fixture style)
- `laser-show.timeline.timecode-test` (NEW): `timecode-examples-test` (§7.3), `drop-frame-roundtrip-test` (every frame of 0-11 minutes at 29.97), `non-drop-roundtrip-test` (24/25/30).
- `laser-show.events.timeline-handlers-test` (extend): `play-from-cursor-test`, `pause-freezes-position-test`, `seek-inactive-sets-cursor-only-test`, `stop-keeps-cursor-test`, `transport-events-create-no-undo-test` (through `events/handle-event-pipeline`).
- `laser-show.services.frame-service-test` (extend):
  - `timeline-cue-stops-at-end-test`, which calls `update-timing-accumulators!` with a fixed clock via a NEW `current-time-ms` arg
  - `locked-tempo-ignores-global-bpm-test`
  - `clip-local-loop-modulator-starts-at-clip-test`
- `laser-show.events.handlers.triggers-test` (extend, `test/laser_show/events/handlers/triggers_test.clj`): `start-timeline-action-emits-play-test`.
- TestFX (`laser-show.ui-test.e2e-test`):
  - `timeline-play-button-starts-from-cursor` asserts `[:playback :active-cues [0 0] :accumulated-beats]` ≥ the cursor after a play click. The timer is not started in `app/start-test!` (`app.clj:199`), so the test calls `frame-service/update-preview-frame!` directly.
  - `timeline-ruler-time-mode-shows-timecode`

## 9. Out of scope / alternatives considered

- **Out of scope here:**
  - Chasing external timecode: Phase 5, `06-phase5-timecode-chase.md`.
  - LTC decoding.
  - Tempo maps with several tempo changes inside one timeline. A single locked tempo covers most pre-recorded shows; a tempo map is a later extension of `cue-bpm` and `show-seconds`.
  - Pre-rendering frames, and recording live triggers onto the timeline (README `:159-161`).
  - Smoothing the 30 Hz playhead / timing step (04 #11, fixed by T4 / 04 LE1).
  - Moving playback runtime out of the cljfx context (02 M1.3).
- **Alternatives considered:**
  - **Store clip positions in seconds** for pre-recorded shows. Rejected: every clip edit, snap and the renderer are beat-based. A locked tempo gives an exact seconds↔beats mapping with no data change.
  - **A separate timeline clock** (anchor time + BPM integration). Rejected for now: the existing accumulator already integrates BPM changes and is what T4 will replace.
  - **Quantize timeline starts to the next bar.** Deferred: start immediately (matches today), and add a per-cue `:timeline/quantize-start` later if needed.
  - **Clip-relative windows for nested items.** Rejected (D5): it needs a data migration, and moving a clip already shifts nested windows.
