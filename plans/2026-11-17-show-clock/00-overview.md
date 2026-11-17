# Show clock — overview

**Written:** 2026-11-17   **Written for commit:** `f7fdd79` (`master`; `src/` last changed in `8edfe59`)
**Status:** Proposed. O10 answered 2026-11-17 (A: this plan); S1–S3 open (§5)   **Last verified against:** `f7fdd79` (7 probes on loopback, JDK 25.0.5, macOS arm64; see §1)
**Supersedes / Superseded by:** none. Takes over output runtime O10 / O-17 (`plans/2026-11-16-output-runtime/00-overview.md` §2, §5) and the review items in §6
**Related findings:** review 04 #11, #23, #24 (part), #25 (part), #27, LE1; 03 MR1 step 3; 02 M1 step 3; 01 F11 (stats part); overview T1, T4, T5; timeline TL-28 (clock part)

Show time covers the beats, every cue's position, the timeline playhead and the MIDI-timecode chase. Today it moves only when the 30 Hz preview timer runs. It is stored in app state, and every projector renders the whole show again from it.

This plan gives the show one clock: a thread at the output rate (60 Hz). It owns show time in a runtime atom and renders each tick once. Each projector then sends that tick from its own streaming thread, as decided in O6, and the preview draws the same tick.

## Start here

**Start with `01-phase1-clock-owns-time.md`.** It needs no decision and takes about 2 days. It moves show time out of app state into the clock, which runs at 60 Hz. It also fixes two bugs:
- a slow event no longer moves cues back in time (c5);
- a code reload no longer freezes the output (c6).

Phase 2 needs S1 and S3, and Phase 3 needs S2.

| Doc | Phase | Size | Needs |
|---|---|---|---|
| `01-phase1-clock-owns-time.md` | The clock owns show time | ~2 days | none |
| `02-phase2-render-once.md` | Render once per tick; every engine sends that tick | ~1 day | S1, S3 |
| `03-phase3-screen-reads-the-clock.md` | The screen reads the clock; app state stays quiet while a show plays | ~0.5 day (S2 A) or ~1–1.5 days (S2 B) | S2 |
| `04-phase4-one-timing-context.md` | One timing context down the render (review 03 MR1 step 3) | ~0.5–1 day | Phase 2 |

About 4–5.5 days in total. O10 estimated 3–5.

## 1. What the code does today (probes)

These results come from `probes/show_clock_probes_test.clj`. They ran against `f7fdd79` on loopback with the fake DAC from `test/` and no UI. The command is in the file header and in §8.

One grid cue plays at [0 0]: a circle rotated by a 1-beat sine, so every new position gives a different frame. c1–c6 ran at a load average of 3–4, and c7 at 7–10.

| Probe | Action | Result |
|---|---|---|
| c1 | One projector streams for 2 s, preview timer running | 120 frames sent but only **61 distinct cue positions**: 59 frames repeat the one before |
| c2 | Renders per second, by thread, with 0, 1, 2 and 4 projectors on zone `:all` | 30.5, 89.5, 110 and **138 renders a second**. The preview always does 30.5; the engines do 0, 59, 79.5 and 107.5 (the 16 ms cache catches only some). Every render shows one of the same 30 positions |
| c3 | Cost of one `generate-frames-by-zone` | 1 preset with 1 effect (65 points): median 28 µs, p95 99 µs. 4 presets × 3 modulated effects (346 points): median 179 µs, p95 543 µs |
| c4 | App-state writes per second while one cue plays (no UI, no engines) | **61**: 30.5 change the global clock and the cue timings, 30.5 the preview frame |
| c5 | An event is handled while the timer runs (its handler slowed to 300 ms) | **The cue's position went back once, by 0.65 beats**: the event wrote its older snapshot back. The dev-mode check stays silent, because it ignores the timer's paths |
| c6 | The preview timer stops, as it does during a dev code reload | 61 frames a second still go out, **all identical**: the output is frozen |
| c7 | Modulation contexts built per render (`mod/make-context`, `make-base-context`) | 1 per render for the simple cue (2 % of the render), **16 for the busy one, ≥ 6 % of the render**. The time is measured inside the two functions only, so this is a lower bound |

## 2. Finding index

| ID | Finding | Sev | Evidence | Phase |
|---|---|---|---|---|
| S-1 | Show time advances only in the 30 Hz preview tick, so the 60 fps output sends each position twice | M | c1. `update-timing-accumulators!` (`frame_service.clj:121-158`) has one caller, `update-preview-frame!` (`:827`), on a `java.util.Timer` every 33 ms (`:863-883`). Engines read what it last wrote (`multi_engine.clj:141,156`) | 1 (60 Hz); 2 (every tick sent once) |
| S-2 | Every projector renders the whole show, and the preview renders it a third time. The 16 ms cache only helps by chance | M | c2. `generate-frames-by-zone-cached` (`frame_service.clj:623-659`, key `:64-74`) per engine (`multi_engine.clj:159`); the preview is uncached (`frame_service.clj:714`) | 2 |
| S-3 | Positions live in app state, and a thread outside the event flow writes them 61 times a second. A slow event writes its older snapshot back and moves cues back in time. The dev check can't see it | M-H | c4, c5. `swap-state!` at `frame_service.clj:141,843`; whole-state write-back at `events/core.clj:150-158`; the check ignores `preview-timer-paths` (`frame_service.clj:109-119`, `events/core.clj:119-133`) | 1 |
| S-4 | A code reload stops the timer, and the output freezes on one frame | L-M (dev) | c6. `dev/reload.clj:120,136` | 1 |
| S-5 | One frame mixes several reads: each engine reads the wall clock, the input store and app state on its own, and render steps read app state again | M | `multi_engine.clj:138,145,146`; `frame_service.clj:259,507,591`; review 04 #23 and the LE1 note (`04-output-pipeline.md:420`) | 2 (time), 4 (one snapshot per tick) |
| S-6 | The UI re-renders 30 times a second from those writes, and the preview draws at 30 fps | L-M | `beat-position` builds a new map every tick (`subs.clj:56-71`), and the toolbar uses only its index (`toolbar.clj:145`). `frame-stats` and `current-frame` share `ex/streaming-data` (`subs.clj:442-456`), read by `status_bar.clj:73` and `preview.clj:266,339`. Review 01 F11 | 3 |
| S-7 | `elapsed-ms`, `bpm` and `trigger-time` are threaded through about fifteen render functions and every effect, but the timing context's values win. Each modulated parameter builds its own context | L | `effects.clj:243-245,389,443-446,453`; c7 | 4 |
| S-8 | The preview joins different cues' frames in one zone without blanking, so it draws lines the laser doesn't | L | `frame_service.clj:741-743`; review 04 #27 | 2 |
| S-9 | Hardware frames are never timed: the frame profiler records only the preview's render | L | `frame_service.clj:757-767`; review 04 #24 | 2 |

**Root cause.** Show time is high-frequency runtime data, but it is kept in app state, against `docs/CLJFX_STATE_MANAGEMENT.md:650-663`. Two kinds of writer change it: events and the timer. Its only driver is the preview, and there is no "frame for tick k": each consumer renders its own.

## 3. Target architecture (recommended defaults)

```
UI / MIDI / OSC / Link ─▶ event agent ─▶ app state: what should play                                [Phase 1]
                             ▲            (cue intents, transport requests, BPM, the document)
                             │                          │ read once per tick
       :show-clock/* reports │ (only when app state     ▼
       and slow facts        │  and the clock disagree)
                       show clock thread, 60 Hz, deadline-paced on System/nanoTime                   [Phase 1]
                         1. reconcile intents, advance the global clock and every cue (runtime atom)
                         2. render each active cue's zone frames once                                [Phase 2]
                         3. publish the tick {tick at-ns cues zone-frames preview}, wake the engines
                                         │
            ┌────────────────────────────┼─────────────────────────────┐
     engine thread, projector A    engine thread, projector B    FX thread: the preview and the     [Phase 3, S2 B]
     its zones → its projector     its zones → its projector     timeline playhead draw the newest
     effects → sanitize → UDP      effects → sanitize → UDP      tick on each screen refresh
```

- **App state says what should play.** Events write it, and only events: which cues are active and how they started, plus transport requests (restart, align, phase shift, seek). The clock reads it once per tick.
- **The clock says where everything is.** Positions live in a runtime atom that only the clock thread writes. Today's timing maths (`cue_timing.clj`) runs unchanged on those maps, at 60 Hz instead of 30.
- **Facts go back through events.** A timeline reaching its end and a timecode chase starting or stopping go back to app state as events. So do the slow facts the UI shows (the beat square, MTC text, stats), sent only when app state is out of date.
- **One render per tick.** Each projector's thread only takes its zones from the tick, applies its own projector effects and sends (S1). One projector's slow send or heavy calibration still can't delay another (O6).

## 4. Phases and dependencies

| Phase | Goal | Depends on |
|---|---|---|
| 1 | The clock owns show time: runtime timing, intents in app state, reports through events, a 30 Hz mirror in place of the preview timer | none (review 03 MR1 is done: `plans/2026-11-17-modulator-time-base.md`) |
| 2 | Render once per tick; engines and the preview take the tick | 1; S1, S3 |
| 3 | The screen reads the clock; app state stays quiet while a show plays | 2; S2 |
| 4 | One timing context through the render; dead arguments go | 2 |

Phases 3 and 4 are independent of each other. What each probe should show afterwards (expected; each phase measures it):

| Probe | After 1 | After 2 | After 3 (S2 B) | After 4 |
|---|---|---|---|---|
| c1 | about one new position per frame, with a few repeats where the clock's and the engine's 60 Hz drift past each other | every frame a new tick (S1 A) | – | – |
| c2 | unchanged | 60 renders a second, whatever the projector count | – | – |
| c4 | 30.5 writes a second (one mirror write per two ticks) | 30.5 | about 6 a second at 120 BPM (2 beat squares, 4 stats updates), up to 10 more while MIDI timecode runs | – |
| c5 | 0 times backwards | – | – | – |
| c6 | the clock keeps running through a reload | – | – | – |
| c7 | – | – | – | one context per effect chain (busy cue: 16 → 4) |

## 5. Decisions

Each decision is written out with options and a recommendation in the doc that needs it. Reply with letters (e.g. `S1: A, S2: B, S3: A`) or in your own words.

| ID | Question | Doc | Needed before | Recommended |
|---|---|---|---|---|
| O10 | Where show time advances | output runtime 05 | – | **A, answered 2026-11-17** ("Go write the plan"): one show clock, this plan |
| S1 | Who renders, and when each engine sends | 02 | Phase 2 | **A**: the clock renders once per tick; each engine wakes on the tick, on its own thread |
| S2 | How the screen gets the moving parts (preview, playhead, beat) | 03 | Phase 3 | **B**: the preview and playhead draw from the clock on each screen refresh; app state only gets slow facts |
| S3 | What the lasers show if the clock stops publishing | 02 | Phase 2 | **A**: blank after 0.5 s without a tick, and say so in the projector status |

## 6. Review items, re-checked 2026-11-17

| Review item | Today | Here |
|---|---|---|
| Output runtime O10 / O-17 | Still true (c1, c6) | 1–3 |
| 04 #11 (timing only in the 30 Hz timer), LE1 (one frame producer, many senders) | Still true (c1, c2). The timeline end and MTC chase now also run in the timer (`frame_service.clj:84-107,157-158`) | 1, 2 |
| 04 #23 (repeated state reads, duplicate rendering, cache races) | Still true (c2; S-5) | 2 (one render), 4 (one snapshot) |
| 04 #24 (the profiler covers only the preview) | Partly: only `record-frame-timing!` and the JFR event are called from the preview path (`frame_service.clj:757-767`) | 2 (the clock's render is timed); the profiler's allocation stays out |
| 04 #25 (duplicated code) | `multi_engine/get-active-cues-data` (`:114-132`) duplicates `frame_service/get-all-active-cues-data` (`:183-199`); `combine-zone-frames` (`multi_engine.clj:76-89`) duplicates `combine-all-zone-frames` (`frame_service.clj:661-671`); the deprecated `get-timing-context` (`:233-252`) is kept for one test | 1 (active cues), 2 (combine), 4 (deprecated timing fns) |
| 04 #27 (preview zones joined without blanking) | Still true (`frame_service.clj:741-743`) | 2 |
| 03 MR1 step 3 (one modulation context per chain) | Open, deferred here (`plans/2026-11-17-modulator-time-base.md` §9); c7 measures it | 4 |
| 02 M1 step 3 (per-tick runtime in its own atom, mirrored at 15–30 Hz) | Still true | 1 (atom and mirror), 3 (S2 decides whether the mirror stays) |
| Overview T1 (writers outside the event flow) | The preview timer is the one left in this area (`frame_service.clj:109-119`) | 1 (only mirror paths left), 3 (none, with S2 B) |
| Overview T4 (one monotonic frame clock, rendering once per tick, one relative timing context) | Partly: output runtime Phase 5 and the modulator time base | 1, 2, 4. The input-timestamp part (02 #10, Q5) and Link's clock (05 F8) stay out (§7) |
| Overview T5 / 01 F11 (30 fps subscriptions) | Grid cells and the timeline dialog are done (`03314bf`, `afd2306`); the status bar, preview header and beat indicator still re-render at 30 Hz | 3 |
| Timeline TL-28 (clock part); timeline 04 §9 "smoothing the 30 Hz playhead" | Still true | 1 |

## 7. Out of scope

- **Input timestamps** (review 02 #10, Q5): handlers still see the time the agent handled an event (`input_mapper.clj:76` is overwritten by the `:time` co-effect, `events/core.clj:86-89`). A cue starts at the first tick after its event, at most 16.7 ms later.
- **Link's downbeat sync** uses the wrong clock (review 05 F8, `link.clj:207-219`). This plan only changes where it reads the show's beats (01 §4).
- **What Stop and blackout mean** (O4 kept them). Stop still rewinds every cue, and cues keep counting while stopped, unseen, as today (`handlers/timing.clj:68-77`; `update-cue-timing` doesn't look at `:playing?`).
- **Per-projector frame rates** (LE1's "highest projector FPS"). Every engine runs at `DEFAULT_FPS` 60 (`streaming_engine.clj:31`), so the clock does too.
- **How global effects apply.** They run per zone per cue at the cue's tempo (`frame_service.clj:614-619`). **Projector effects** take the first active cue's timing (`multi_engine.clj:171-175`). Both are unchanged, so frames stay identical: Phase 2 has a test for that.
- The preview draw loop's allocation (review 01 F13), except the map conversion that Phase 3 B removes.
- Dead timing defaults in app state (`domains.clj:43-52`, `:cue-queue` `:71`) and the unread `[:playback :active-cue]` (`handlers/timing.clj:73`).

## 8. Re-running the probes

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
P=plans/2026-11-17-show-clock
clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" \
    -M:mac-arm:test:probe -d "$P/probes" \
    -n probes.show-clock-probes-test 2>&1 | grep PROBE
```

Use `:win`, `:linux` or `:mac` instead of `:mac-arm` on other machines. The run takes about two minutes. To run one probe, use `-v probes.show-clock-probes-test/c7-modulation-contexts-per-render`. When a phase fixes a behaviour, turn its probe into a real test that asserts the new behaviour. Each phase doc names those tests.
