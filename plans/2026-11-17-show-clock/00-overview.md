# Show clock — overview

**Written:** 2026-11-17   **Written for commit:** `f7fdd79` (`master`; `src/` last changed in `8edfe59`)
**Status:** In progress. Phases 1 and 2 done 2026-11-17 (`dd106de`; as built in 01 §10 and 02 §10), Phase 3 done 2026-11-18 (`7d67aff`; as built in 03 §10). O10 answered 2026-11-17 (A: this plan); S1–S3 answered 2026-11-17 (A, B, A), with the audit's changes (§9) folded into the phase docs   **Last verified against:** `dd106de` for Phases 1–2 and `7d67aff` for Phase 3 (the probes again, 01–03 §10). §1–8 are as written for `f7fdd79` (7 probes on loopback, JDK 25.0.5, macOS arm64; see §1). The audit (§9) re-checked the citations it adds against `fc9d310`
**Supersedes / Superseded by:** none. Takes over output runtime O10 / O-17 (`plans/2026-11-16-output-runtime/00-overview.md` §2, §5) and the review items in §6
**Related findings:** review 04 #11, #23, #24 (part), #25 (part), #27, LE1; 03 MR1 step 3; 02 M1 step 3; 01 F11 (stats part); overview T1, T4, T5; timeline TL-28 (clock part)

Show time covers the beats, every cue's position, the timeline playhead and the MIDI-timecode chase. Today it moves only when the 30 Hz preview timer runs. It is stored in app state, and every projector renders the whole show again from it.

This plan gives the show one clock: a thread at the output rate (60 Hz). It owns show time in a runtime atom and renders each tick once. Each projector then sends that tick from its own streaming thread, as decided in O6, and the preview draws the same tick.

## Start here

**Phases 1 and 2 are done (2026-11-17, `dd106de`), and so is Phase 3 (2026-11-18, `7d67aff`).** Every frame sent is a new tick (c1: 120 distinct in 120 frames), there is one render per tick whatever the number of projectors (c2: 60 a second), a slow event no longer moves a cue back (c5), and a code reload no longer freezes the output (c6). The preview and the timeline's playhead draw the clock's newest tick on each screen refresh, app state changes 6 times a second while a cue plays (c4), and one redraw of the 3x2 preview costs 88 µs instead of 367 (c8). Next is Phase 4 (one timing context). It still cites the code before Phases 1–3, so re-check its citations against `7d67aff` first. The rest of this section is the original guidance.

**Start with `01-phase1-clock-owns-time.md`, and land Phase 2 right after it.** Phase 1 needs no decision and takes about 2 days. It moves show time out of app state into the clock, which runs at 60 Hz. It also fixes two bugs:
- a slow event no longer moves cues back in time (c5);
- a code reload no longer freezes the output (c6).

Until Phase 2 lands, a newly triggered cue reaches the laser up to one tick later than today (01 §9), so don't play a show between the two phases. Every decision is answered (§5).

| Doc | Phase | Size | Needs |
|---|---|---|---|
| `01-phase1-clock-owns-time.md` | The clock owns show time (**done**, `dd106de`) | ~2 days | none |
| `02-phase2-render-once.md` | Render once per tick; every engine sends that tick (**done**, `dd106de`) | ~1.5 days (with the fake-DAC checker, §9 A9) | S1, S3 (answered) |
| `03-phase3-screen-reads-the-clock.md` | The screen reads the clock; app state stays quiet while a show plays (**done**, `7d67aff`) | ~1.5–2 days (S2 B, with the preview's draw loop, §9 A12) | S2 (answered) |
| `04-phase4-one-timing-context.md` | One timing context down the render (review 03 MR1 step 3) | ~0.5–1 day | Phase 2 |

About 5.5–6.5 days in total with the audit's additions. O10 estimated 3–5.

## 1. What the code does today (probes)

These results come from `probes/show_clock_probes_test.clj`. They ran against `f7fdd79` on loopback with the fake DAC from `test/` and no UI. The command is in the file header and in §8. They describe the code before Phases 1 and 2; the probes now drive the show clock, and 01 §10 and 02 §10 compare a run before (`e52e865`) with one after (`dd106de`).

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

Measured after Phases 1 and 2 together (`dd106de`; 01 §10, 02 §10): c1 120 distinct ticks in 120 frames, no repeats; c2 60 a second with 0, 1, 2 and 4 projectors; c4 30; c5 0 times backwards; c6 61 distinct frames in the second after a reload. After Phase 3 (`7d67aff`, 03 §10): c4 6 (2 beat squares, 4 stats updates); NEW c8 (`probes/preview_draw_probes_test.clj`), one redraw of the 3x2 preview grid on the FX thread, 367 µs and 795 KB before, 88 µs and 1.1 KB after.

## 5. Decisions

Each decision is written out with options and a recommendation in the doc that needs it. Reply with letters (e.g. `S1: A, S2: B, S3: A`) or in your own words.

| ID | Question | Doc | Needed before | Recommended |
|---|---|---|---|---|
| O10 | Where show time advances | output runtime 05 | – | **A, answered 2026-11-17** ("Go write the plan"): one show clock, this plan |
| S1 | Who renders, and when each engine sends | 02 | Phase 2 | **A, answered 2026-11-17** ("i trust your recommendations"): the clock renders once per tick; each engine wakes on the tick, on its own thread. With the audit's changes A1–A5, A7–A9 (§9) |
| S2 | How the screen gets the moving parts (preview, playhead, beat) | 03 | Phase 3 | **B, answered 2026-11-17**: the preview and playhead draw from the clock on each screen refresh; app state only gets slow facts. The preview's draw loop is rewritten first (A12) |
| S3 | What the lasers show if the clock stops publishing | 02 | Phase 2 | **A, answered 2026-11-17**: blank after **250 ms** without a tick (the audit's change from 0.5 s, A6), and say so in the projector status |

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
- The preview draw loop's allocation (review 01 F13), except the map conversion that Phase 3 B removes and the draw loop Phase 3 rewrites (A12).
- Dead timing defaults in app state (`domains.clj:43-52`, `:cue-queue` `:71`) and the unread `[:playback :active-cue]` (`handlers/timing.clj:73`).
- **The event agent's blocking I/O.** Project save and load do their zip I/O inline on the agent (`events/core.clj:189-210`), and connecting a MIDI device runs inside a handler (`events/handlers/input.clj:53-63`). While one runs, every trigger waits, blackout included (`events/handlers/triggers.clj:77`); the clock keeps the output moving meanwhile. Review 02 #19, M3 step 4 and M4 describe the fix; it's the next item after this plan (`plans/2026-11-07-improvement-review/00-overview.md`).
- **OS timer throttling while the window is hidden** (Windows 11 with the window covered or minimized, macOS App Nap). All pacing numbers so far are from macOS with the app in front. Checked later, before a show: output runtime `05-phase5-frame-timing.md` §8.

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

## 9. Audit (2026-11-17)

The pending plans were audited for live use and frame latency, against IDN-Stream rev 002 and IDN-Hello (draft 2022-03-27) in `docs/`. This plan doesn't change the bytes on the wire: encoding stays in `idn/stream.clj` and `idn/hello.clj`, so the stream is exactly as conformant as it is today. S1–S3 were answered on the audit's recommendations, and its changes (A1–A12) are folded into the phase docs.

Measured with `src/` as at `8edfe59`, before `fc9d310` (macOS arm64, JDK 25, ZGC). The machine was heavily loaded by other work, so the tails are pessimistic.

| What | Result |
|---|---|
| Encoding one 1,000-point frame at 16-bit XY, 8-bit colour (`sanitize-frame`, `frame->packet`, `wrap-channel-message`) | 252 µs median, 1.1 ms p99; 3,000 points 0.74 ms. The same points cost about half their render (c3: the busy cue's 346 points render in 179 µs), so after Phase 2 encoding is one of the main jobs left on each projector's thread |
| `wrap-channel-message` | A reflective `alength` cost about 60 µs and 90 KB of garbage per packet. Fixed in `fc9d310`: one engine at 60 fps went from 228 to 99 KB of garbage per frame, at the same frame rate |
| Waking 4 waiting threads every 16.7 ms (a shared monitor's `notifyAll`, or `LockSupport/unpark` per thread) | 20–80 µs median for both; tails of 0.1–30 ms depending on load |
| `profiler/record-idn-streaming!`, always on | 12.5 µs and 10 KB per frame: one shared `swap!` copies a 1,000-entry vector (`frame_profiler.clj:57-61`). Four threads at once: p99 119 µs |

Latency from a trigger to light, after Phase 2:

| Stage | Typical | Worst |
|---|---|---|
| The event waits for the agent | short when idle | as long as a save, load or device connect on the agent (§7) |
| The next tick | 8.3 ms | 16.7 ms |
| Render, wake, projector effects, encode, send | 0.5–1 ms (1,000 points) | a few ms |
| The DAC finishes the frame it is drawing (Stream §3.3) | half a frame | one frame: 40 ms for 1,000 points at 25 000 pts/s |
| OS timers while the window is hidden | ~1 ms | 15.6 ms steps (Windows 11), ~100 ms (macOS App Nap) (§7) |

The changes, and where they went:

| # | Change | Why | Doc |
|---|---|---|---|
| A1 | An engine resends after 3 intervals (50 ms) without a tick, not 1.5 | In discrete mode the DAC repeats the last frame anyway (Stream §3.3). With the half-interval gap, a tick 8–17 ms late became a repeated frame plus a skipped one. 50 ms still keeps the config within 250 ms during a stall (Stream §2.2) and the link alive (Hello §7.2) | 02 §4, §7 |
| A2 | A new tick's frame goes out at once; the half-interval gap stays only on the error path | The clock never publishes ticks closer than an interval unless one was late (`next-deadline`), and the DAC keeps only the newest frame | 02 §4 |
| A3 | The snapshot's `:preview` is a `delay`, forced after `publish!` | Every projector waited while the preview joined every cue and zone again | 02 §4, §5 |
| A4 | Timestamps stay the send time | IDN-Hello §6: a message may go out after its timestamp, never before it | 02 §4, §7 |
| A5 | While no clock runs, engines pace themselves as today; with no snapshot yet they send empty frames and report nothing. Only a snapshot older than `STALE_MS` is "stalled" | `start-test!` starts no clock. Tests that stream through real engines would otherwise see "Show clock stalled" (`streaming-status-test/projector-dot-shows-status-test`) or a 20 fps keepalive instead of 60 fps (`…/projector-status-says-when-the-scan-rate-limits-the-fps-test`) | 02 §4, §8 |
| A6 | `STALE_MS` 250 instead of 500 | Half a second of frozen frame is long for audience scanning, and ZGC pauses are well under 1 ms (the release build uses ZGC, `.github/workflows/release.yml:202`). Revisit after the hidden-window check | 02 §4, S3 |
| A7 | The frame signal checks and waits under one monitor, kept in a `defonce`; each engine records its tick-to-send time | No lost wake-ups; a code reload can't leave the clock and the engines on different monitors; tick-to-send is the new critical path | 02 §4, §8 |
| A8 | IDN profiler samples go into a ring buffer instead of a copied vector | Under S1 A every engine records in the same instant | 02 §4 |
| A9 | NEW `fake-dac/violations`: every message an engine test receives is checked against the stream rules | Phase 2 rewrites the engine loop, and today each wire test checks a single rule | 02 §4, §8 |
| A10 | `now-ms` is fractional | Whole milliseconds step 16, 17, 17, 16 ms from tick to tick, about 6 % step jitter | 01 §4 |
| A11 | Land Phases 1 and 2 together | With Phase 1 alone a new cue starts up to a tick later than today | 01 §9 |
| A12 | Phase 3 rewrites the preview's draw loop first (review 01 M2) | S2 B doubles the redraw rate, and the loop allocates per point (review 01 F13) | 03 §4 |

Found in the same audit, outside this plan:
- The event agent's blocking I/O and OS timer throttling (§7).
- Fragmentation stays off. `fragmentation.clj:282` writes a chunk header in every sequel fragment, which Stream §6 doesn't (review 04 #3). A live show never needs it: a full 0xFF00 message holds about 8,150 points, which take 82 ms to draw even at 100 000 pts/s.
- Each socket is its own IDN session (Hello §7.1), so a multi-head DAC gets one session per projector (review 04 LE2). Phase 2 keeps a merge possible (02 §9).
