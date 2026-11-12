# Phase 5 — Chase incoming MIDI timecode (MTC)

**Written:** 2026-11-08   **Written for commit:** `d0a7dc2`
**Status:** Done (2026-11-11, branch `timeline/phase5-mtc-chase`), except the manual DAW check (§8)   **Last verified against:** branch head; `overtone/midi-clj` 0.5.0 (`deps.edn`)

> **How the implementation differs from this plan:**
> - **Position anchor.** `:tc` is the assembled value + 2 frames (what a display shows, as §7.1 says), but the position used for chasing is anchored at the value + 1.75 frames. Piece 7 arrives 1.75 frames after piece 0's frame started, so this removes a quarter-frame lead.
> - **Resync after a gap.** A quarter-frame arriving more than 500 ms after the previous one marks the decoder unsynced. It then reports `:stopped`, holding the position where timecode stopped, until a fresh 8-piece sequence arrives. Otherwise a restart would briefly extrapolate from the stale anchor. A full-frame locate also drops partly collected pieces.
> - **Names.** The test reset is `mtc/reset-decoder!` (not `reset!`, which would shadow `clojure.core`). NEW `mtc/full-frame?`, `mtc/status`, `mtc/mirror-status`, `midi/create-sysex-handler`.
> - **The chase step is pure and lives in `timeline.playback/apply-chase`**, with `chases-mtc?` and `chase-beat`. `update-timing-accumulators!` calls it after `advance-active-cues`, which skips chasing cues. Freewheeling outside `[0, length)` also deactivates. The loop is ignored while chasing. Chased cues also get `:chasing? true` and `:accumulated-ms` matching the beat.
> - **Status mirror.** `[:backend :input :mtc]` is `{:state :fps :text :at-ms}`. It's written when `:state` or `:fps` change, and at most every 100 ms when only the text changes. `subs/mtc-status` drops `:at-ms`.
> - **A chasing timeline ignores** `:timeline/play`, `:pause`, `:toggle-play`, `:stop` and grid triggers, because the timecode drives it. `:timeline/seek` still moves the cursor.
> - **Inspector.** "Follow timecode: Off | MTC" sits under the frame rate. The rate warning is a component that subscribes to the MTC status by itself. A second warning appears when a chasing timeline's tempo isn't locked.
> - **Tests.** The UI test is `timeline-follows-midi-timecode` in `laser-show.ui-test.timeline-editor-test`, and the screenshot baseline gained `07-mtc-chase`. The midi-test fixtures are `fixtures/mtc-quarter-frame` and `fixtures/sysex-msg`, built through midi-clj itself. The manual DAW check has **not** been done yet.
**Supersedes / Superseded by:** none   **Related findings:** TL-31; README "Timecode Integration" (`README.md:147,157`); 05 (input fixtures must match the library); overview T1, T4

This implements user decision D12 (`00-overview.md` §8): pre-recorded shows follow an external timecode source. A typical setup is a DAW or media server playing the audio, sending MTC, and the laser timeline following it. Phase 4 already provides the locked tempo, the timecode maths (`laser-show.timeline.timecode`) and the transport.

## 1. Current behaviour (file:line)

- **Quarter-frames are dropped.**
  - `overtone.midi/midi-msg` (midi-clj 0.5.0) maps a ShortMessage's `getCommand` (status & 0xF0) through `midi-shortmessage-keys`. For system messages that's `0xF0`, which isn't a key, so `:command nil`. `:status` is the full status keyword, e.g. `:midi-time-code` for `0xF1`, and `:data1` is the quarter-frame byte. `.getChannel` of `0xF1` is `1`.
  - The app's `message-command` (`input/midi.clj:43-62`) falls back to the keyword `:status`, so it returns `:midi-time-code`, and `midi-msg->event` (`:71-86`) then returns `nil` for it.
  - Even before that, `create-midi-handler` (`:92-113`) applies the channel filter using `(:channel msg)` (`:108-111`), which would drop MTC when channel 1 is filtered out.
- **SysEx is never received.** `connect-device` (`input/midi.clj:115-140`) calls the 2-arity `m/midi-handle-events` (`:128`). midi-clj's 2-arity installs a no-op SysEx handler, so MTC full-frame (locate) messages `F0 7F 7F 01 01 hh mm ss ff F7` are discarded.
- **No consumer.** Timeline position only comes from the cue accumulators (`cue_timing.clj:120-166`), advanced by `update-timing-accumulators!` (`frame_service.clj:79-122`).

## 2. Root cause

MIDI input was built for triggers (notes, CC, program change), which go through the event queue. Timecode is a continuous 100+ messages-per-second stream that needs to *drive* playback, not trigger it.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `create-midi-handler`, `connect-device` (`input/midi.clj:92-140`) | MTC is tapped here, before the trigger path |
| midi-clj 3-arity `midi-handle-events` (`overtone/midi.clj`, 0.5.0) | SysEx full-frame |
| `laser-show.timeline.timecode` (Phase 4, `04` §4.6) | Rate codes, drop-frame, seconds↔beats |
| `cue-timing/activate-cue`, `deactivate-cue`, `seek` (Phase 4, `04` §4.1) | Applying the chased position |
| `update-timing-accumulators!` (`frame_service.clj:79-122`) | The single place playback runtime is advanced; chase is applied here |
| `docs/CLJFX_STATE_MANAGEMENT.md:650-663` ("Keep high-frequency updates separate") | Justifies the MTC runtime atom |

## 4. Changes per namespace

### 4.1 `laser-show.input.mtc` (NEW, `src/laser_show/input/mtc.clj`)
Justification: it's a self-contained decoder that's testable without MIDI hardware, plus a runtime atom for 120 Hz data that must not go through the cljfx context or the event agent.

- Pure:
  - `initial-decoder` → `{:pieces [nil ×8] :last-qf-ns nil :tc nil :fps nil :direction :forward :anchor-ns nil}`.
  - `step-quarter-frame [decoder qf-byte now-ns] → decoder`. Piece = high nibble (0-7), value = low nibble. When all 8 pieces of a frame are present it assembles `hh:mm:ss:ff` and the rate (bits 5-6 of the hours piece: 0=24, 1=25, 2=29.97 drop-frame, 3=30). MTC sends 2 frames per 8 quarter-frames, so the assembled time is the frame at the start of the sequence and `+2` frames applies once the sequence completes. Direction is detected from the piece order.
  - `step-full-frame [decoder sysex-data now-ns] → decoder`. `sysex-data` is midi-clj's `:data`, i.e. `(.getData SysexMessage)`, which excludes the leading `F0`: `7F 7F 01 01 hh mm ss ff F7`. It sets `:tc` and `:anchor-ns`, and marks a locate (seek).
  - `position [decoder now-ns {:keys [freewheel-ms]}] → {:seconds s :fps f :state (:running | :freewheel | :stopped)}`.
    - `:running` = last quarter-frame < 100 ms ago; `:seconds` = tc seconds + time since the anchor (extrapolated).
    - `:freewheel` = 100 ms to `freewheel-ms` ago (default 500); keeps extrapolating at nominal speed.
    - `:stopped` after that.
- Runtime: `defonce ^:private !state (atom initial-decoder)`. `on-quarter-frame! [byte]` and `on-sysex! [bytes]` `swap!` it with `System/nanoTime`; they're called on the MIDI receiver thread. `current [] → position map`. `reset!` is used for tests.

### 4.2 `laser-show.input.midi` (existing)
- `create-midi-handler` (`input/midi.clj:92-113`): before the channel filter, if `(= :midi-time-code (:status msg))`, call `mtc/on-quarter-frame! (:data1 msg)` and return (no trigger event). MTC ignores the channel filter, and quarter-frames never reach `dispatch-fn` or the event queue.
- `connect-device` (`:128`): use the 3-arity `m/midi-handle-events receiver handler sysex-handler`. `sysex-handler` passes `(:data sysex)` to `mtc/on-sysex!` when it's an MTC full-frame, and ignores everything else.
- Both still require `(:enabled? midi-state)` and a connected device (`:104-106`).

### 4.3 `laser-show.timeline.model` (existing, Phase 2/4)
- NEW optional document key `:timeline/chase` ∈ `nil | :mtc`, with op `{:op :set-chase :source kw}`.
- `:timeline/tc-start` (Phase 4) is the timecode where beat 0 of this timeline sits, e.g. `3600.0` for `01:00:00:00`.

### 4.4 `laser-show.services.frame-service` (existing)
`update-timing-accumulators!` (`frame_service.clj:79-122`) reads `(mtc/current)` once per tick. For every timeline cue with `:timeline/chase :mtc`, within the same `swap-state!`:

| MTC state | Timeline position `b = seconds->beats(s − tc-start, cue-bpm)` | Action |
|---|---|---|
| `:running` or `:freewheel`, `0 ≤ b < length` | — | activate (`:timeline? true`) if inactive; set accumulated to `b` (`cue-timing/seek`); `:paused? false` |
| `:running`, `b < 0` or `b ≥ length` | — | deactivate if active |
| `:stopped` | — | deactivate if active |

- Only top-level grid cells that hold a chasing timeline are considered (`[:chains :cue-chains]` scan once per tick; *unverified* cost, ≤ 64 cells).
- Chasing cues skip their own accumulation, so their position is set, not integrated. The playhead and renderer still read the same `:accumulated-beats` (Phase 4 invariant 1).
- This is the same out-of-band writer as Phase 4 (overview T1, unchanged).

### 4.5 Views (Phase 3/4 namespaces)
- Toolbar timecode status chip (`05` §3.7, next to the readout):
  - `MTC 01:00:05:12` green when running
  - amber when freewheeling
  - grey `MTC —` when stopped
  - hidden when the timeline doesn't chase
- While chasing, the transport buttons are disabled, with the tooltip "Following MIDI timecode".
- Inspector Timeline section: "Follow timecode: Off | MTC", timecode start, frame rate. If the incoming MTC rate differs from `:timeline/tc-fps`, it warns: "Incoming 25 fps, timeline set to 30 fps".

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| Decoder state (`!state` in `laser-show.input.mtc`) NEW | **runtime atom** outside the cljfx context | MIDI receiver thread (`on-quarter-frame!`, `on-sysex!`) | No |
| `[:chains :cue-chains k :timeline/chase]` NEW (optional) | document | `apply-op :set-chase` | Yes |
| `[:playback :active-cues [c r]]` for chasing cues | runtime (in app state today) | `preview-update` timer (`update-timing-accumulators!`) | No |
| Status chip data | derived | NEW `subs/mtc-status`: reads `[:backend :input :mtc]`, mirrored at 10 Hz by the timer (only when it changes) | No |

The status mirror exists because the UI shouldn't read the runtime atom during render. The mirror is written in the same timer swap, and only when `:state`, `:fps` or the displayed frame text change.

## 6. Events and effects

| Event / effect key | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:timeline/apply-op {:op :set-chase}` | NEW op | inspector | yes |
| (none for quarter-frames) | — | MIDI thread writes the runtime atom directly | — |

No new side-effect keys. MTC deliberately bypasses the event queue (live-path latency, 05).

## 7. Data examples and invariants

### 7.1 Quarter-frame decoding (25 fps, forward)
Bytes for `01:00:05:12` at 25 fps: frames 12 = `0x0C`, seconds 5, minutes 0, hours 1 with rate code 1 (25 fps) → hours byte `0x21`.

| Byte | Piece | Value |
|---|---|---|
| `0x0C` | 0 frames low | C |
| `0x10` | 1 frames high | 0 |
| `0x25` | 2 seconds low | 5 |
| `0x30` | 3 seconds high | 0 |
| `0x40` | 4 minutes low | 0 |
| `0x50` | 5 minutes high | 0 |
| `0x61` | 6 hours low | 1 |
| `0x72` | 7 hours high + rate | `0b0010` (hours bit 4 = 0, rate = 1) |

After the 8th byte → `{:tc {:h 1 :m 0 :s 5 :f 14} :fps 25}` (the sequence's start frame 12 + 2).

### 7.2 Chase
`:tc-start 3600.0`, locked tempo 120, length 64 beats (32 s).

| MTC | Timeline |
|---|---|
| running at `00:59:59:00` | inactive (b < 0) |
| running at `01:00:05:12` (3605.48 s) | active, accumulated = 10.96 beats |
| last quarter-frame 300 ms ago | freewheel: accumulated keeps advancing |
| last quarter-frame 600 ms ago | stopped: deactivated |
| full-frame locate to `01:00:20:00` while stopped, then running | activates at beat 40 |
| running at `01:00:32:00` | deactivated (b = 64 ≥ length, D11) |

**Invariants:**
1. Quarter-frames never create events or touch the cljfx context directly.
2. For a chasing timeline, `accumulated-beats = seconds->beats(mtc-seconds − tc-start, cue-bpm)` at every tick while running.
3. The decoder never reports a time until a full 8-piece sequence (or a full-frame) has been received.
4. Non-chasing timelines and non-timeline cues are unaffected by MTC.

## 8. Tests (named test vars + fixtures)

- `laser-show.input.mtc-test` (NEW, `test/laser_show/input/mtc_test.clj`):
  - `quarter-frame-sequence-decodes-test` (§7.1)
  - `all-rates-decode-test` (24/25/29.97df/30)
  - `reverse-direction-detected-test`
  - `full-frame-sysex-locates-test`
  - `incomplete-sequence-reports-nothing-test`
  - `freewheel-and-stop-thresholds-test` (fake `now-ns`)
- `laser-show.input.midi-test` (extend, `test/laser_show/input/midi_test.clj`):
  - `mtc-quarter-frame-bypasses-triggers-and-channel-filter-test`. Its fixture is the exact map shape midi-clj 0.5.0 `midi-msg` produces for `0xF1`: `{:channel 1 :command nil :status :midi-time-code :data1 <byte> :data2 0 :note <byte> :velocity 0}`. The shared fixture namespace is `test/laser_show/input/fixtures.clj`.
  - `sysex-handler-forwards-mtc-full-frame-test` uses midi-clj's SysEx map shape `{:data <bytes> :status 240 :length n}`.
- `laser-show.services.frame-service-test` (extend): `chasing-timeline-follows-mtc-test` (§7.2 table, with the decoder atom `reset!` to fixed states), `non-chasing-cues-ignore-mtc-test`.
- **Manual:** a DAW (e.g. Reaper or Ableton Live) sending MTC over a loopback MIDI port (loopMIDI on Windows). Check that start, stop, locate and the rate warning behave as in §7.2.

## 9. Out of scope / alternatives considered

- **Out of scope:**
  - LTC (audio) decoding. Use an LTC→MTC converter; many interfaces and tools do this.
  - Sending MTC (being a timecode master).
  - Art-Net / OSC timecode.
  - Per-device MTC selection (all connected, enabled MIDI inputs feed one decoder).
  - Chase smoothing / PLL. Positions are set directly each tick; 04 #11's 30 Hz stepping applies until T4.
- **Alternatives considered:**
  - **Dispatch an event per quarter-frame.** Rejected: 100–120 events per second through the agent queue that MIDI triggers share (05 live-path latency), plus a context swap each time.
  - **Chase by adjusting speed** (vari-speed) instead of setting the position. Rejected for now: it's more complex and only needed for audio-synchronous scrubbing; laser frames at 30–60 Hz don't need sub-frame phase lock.
  - **MIDI Clock / Song Position Pointer instead of MTC.** Not chosen: it's beat-based and follows the DAW's tempo, which suits live sets, while pre-recorded shows usually distribute timecode. It could be added later as `:chase :midi-clock` using the same apply step.
