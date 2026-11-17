# Output runtime — overview

**Written:** 2026-11-16   **Written for commit:** `70e049e` (`master`)
**Status:** In progress. Phase 1 done (`2c23e63`), Phase 2 done (`645da70`), Phase 3 done (`47e7f8d`, `a37f690`) and Phase 4 done (`a8b2fd2`, `5dce132`, `20aeed9`), 2026-11-16; Phase 5 done (`eba8b2f`, `2a7e406`, `bfe12a5`), 2026-11-17, except its check on real hardware; O1–O9 answered (O1: A; O2: skip only the failing effect; O3: filter bad points; O4: C, no switch; O5–O8: A, with one streaming thread per projector kept; O9: A, 25 000 pts/s by default, and the status shows the fps each projector draws); O10 open (the show clock, its own plan)   **Last verified against:** `70e049e` (13 probes on loopback, JDK 25.0.5, macOS 27.2; see §1). Phases 1–2 changed `streaming_engine.clj`, `multi_engine.clj`, `frame_service.clj`, `effects.clj`, `calibration.clj`, `types.clj` and `generators.clj`, so citations into those files in docs 03–05 are against `70e049e` and get re-checked before each phase starts. After Phase 2, p2, p3, p9 and p11 show the new behaviour (02 §10). After Phase 3, callbacks from futures and the FX thread run on the event agent; p6 improves (one orphan sender after Stop instead of two), p7 is unchanged until Phase 4 takes the engine handles out of app state (03 §10). After Phase 4 one output runtime owns every engine (each still on its own streaming thread): p4–p7 leave 0 senders after Stop, and the dev-mode detector reports no lost update (04 §10). After Phase 5 each frame's duration follows its projector's scan rate (p10: 25 000 points/s), the engine holds 60 fps by deadline (p12: 46.0 → 60.5 fps), timestamps are monotonic microseconds, and the projector status shows the fps each projector draws (05 §10)
**Supersedes / Superseded by:** none. Takes over the output items of `plans/2026-11-07-improvement-review/` listed in §6
**Related findings:** review 04 findings 5, 7, 8, 11–16, 26 and LE3/LE4; 03 findings 6, 7, 9, 10 (MR4, the output half of MR5); 02 #23, M1 (output writers), M7, M8

This is the plan the keyboard plan deferred (`plans/2026-11-13-keyboard-focus-window-lifecycle/00-overview.md` §2, row 5). It covers what happens between "a frame exists" and "UDP packets reach the DAC": who owns the streaming engines, what an error does to the beam, and how frames are timed.

## Start here

**All five phases are done (2026-11-17).** What's left: the check on a real DAC in `05-phase5-frame-timing.md` §8 (none available yet), and the show clock (O10), which gets its own plan. The rest of this section is the original guidance.

**Start with `01-phase1-fake-dac.md`.** It adds a loopback fake DAC and tests of the wire behaviour that must survive every later change. There are no app changes, it takes about a day, and it needs one answer (O1). Every later phase writes its failing test against the fake DAC first.

**The most urgent fixes are in Phase 2** (`02-phase2-output-safety.md`, ~1.5 days):
- a NaN coordinate goes out as a full-brightness point at the centre;
- one failing effect takes its whole chain down, for example all of a projector's calibration;
- an engine thread or the preview timer can die without anyone being told.

None of that needs hardware to fix or to test.

| Doc | Phase | Size | Needs |
|---|---|---|---|
| `01-phase1-fake-dac.md` | A fake DAC and tests of today's correct behaviour | ~1 day | O1 (answered) |
| `02-phase2-output-safety.md` | Nothing unsafe reaches the wire | ~1.5 days | O2, O3, O4 (answered) |
| `03-phase3-event-reentry.md` | Background results come back through the event agent | ~1 day | O5 |
| `04-phase4-engine-ownership.md` | One owner for the engines; status the UI can trust | ~2–3 days | O6, O7, O8 (and Phase 3) |
| `05-phase5-frame-timing.md` | Scan rate, pacing, timestamps; the show clock for later | ~1–1.5 days (+ a hardware check; no IDN DAC available as of 2026-11-16) | O9 (answered), O10 |

About 7–9 days in total. The show clock (O10) would be its own plan after these.

## 1. What the code does today (probes)

These results come from `probes/output_probes_test.clj`. Everything runs on 127.0.0.1 against a small receiver that decodes 16/8 samples. The command is in the file header and in §8.

p5, p6 and p7 slow one function down with `with-redefs` (a 50–300 ms sleep) so that a race window is wide enough to reproduce. Without the sleep the same window is a few milliseconds wide; the code has no guard against it either way.

| Probe | Action | Result |
|---|---|---|
| p1 | Encode `[NaN NaN 1 1 1]`, `[+Inf -Inf 1 1 1]`, `[0.5 0.5 NaN 1 1]` and `[nil 0 1 1 1]` as 16/8 | `[0 0 255 255 255]`: **a full-brightness point at the centre**. `[32767 -32767 255 255 255]`: clamped to a corner. A NaN red becomes `0`. `nil` throws `NullPointerException`. `t/blanked?` of an all-NaN colour is `false`. |
| p2 | Provider returns 5 good frames, then frames with a `nil` x | 5 frame messages in 600 ms (about 28 frames asked for). The engine still says running. Nothing is sent, so the DAC keeps the last good frame until its link timeout (1 s by default, Hello §7.2). |
| p3 | Provider throws `AssertionError` on its 4th call | The engine thread is dead and `running?` is still `true`. 3 frames arrived, and nothing reports the failure. |
| p4 | `:idn/start-multi-streaming` twice, then Stop | 2 engines and 2 senders on channel 1. **After Stop, 1 sender keeps streaming**, while state says stopped with 0 engines. |
| p5 | Start, then Stop 50 ms later (`start!` slowed to 150 ms) | After Stop, 1 sender is streaming. `running?` and the toolbar's `connected?` are both false, so **the button offers "Stream", not "Stop"**. |
| p6 | Streaming to A, then enable B and C in quick succession (`start!` slowed to 100 ms) | 5 engines for 3 projectors: senders per channel `{1 1, 2 2, 3 2}`. After Stop, 2 senders are still streaming. |
| p7 | Start while another event is being handled (that handler slowed to 300 ms) | The engine handles are gone from state, which still says streaming. **After Stop, 1 sender is still streaming.** |
| p8 | A projector calibration effect that throws, per point or while it is built | Both times the projector receives the **uncalibrated** input frame. |
| p9 | `rgb-curves` with red raised to 0.1 at 0 (diode threshold compensation), applied to a blanked point | `[0.5 0.5 0.1 0.0 0.0]` with `blanked? false`, so travel moves become visible lines. |
| p10 | Engine streaming 10-point, then 3000-point frames | The duration is 16 666 µs both times: **600 and 180 007 points/s**, while the projector's stored `:scan-rate` is 30 000. Every timestamp is a whole millisecond. |
| p11 | The preview tick throws `AssertionError` on its 3rd call | 3 ticks in 500 ms instead of 15. The timer is cancelled, so beat timing stops advancing. |
| p12 | Engine pacing over 2 s, after a 1 s warm-up | 44–48.5 fps over three runs (target 60); median gap 19–24 ms |
| p13 | Same machine: today's sleep-the-remainder loop vs deadline pacing with `parkNanos` | 48.8 fps vs **59.8 fps**. On this Mac a 16 ms sleep often returns after 24 ms; the deadline loop absorbs that. |

## 2. Finding index

| ID | Finding | Sev | Evidence | Phase |
|---|---|---|---|---|
| O-1 | **Stop can leave lasers running.** Orphan engines come from a double start (p4), Stop during start (p5), overlapping refreshes (p6), or a concurrent event dropping the handles (p7) | H | futures with no ordering `events/core.clj:343,359,373`; handles written into app state `multi_engine.clj:289,312,371`; whole-state reset `events/core.clj:70-74`, `state/core.clj:115-126` | 3, 4 |
| O-2 | **Effects fail open, a whole chain at a time.** One effect that throws takes its whole chain with it, so the frame goes out without, for example, any of a projector's calibration | H | p8; `animation/effects.clj:252-258`, `frame_service.clj:315-319,497-501`, `multi_engine.clj:111-116` | 2 (O2: only the failing effect is skipped) |
| O-3 | A NaN coordinate goes out as a lit point at the centre. Nothing enforces the dark first sample or the 2-sample minimum (Stream §6.2) | H | p1; `util.clj:13-27`, `output_config.clj:99-111`, `types.clj:94-101` | 2 |
| O-4 | RGB-curve calibration lights blanked points | M-H | p9; `calibration.clj:18-37` (no `blanked?` check, unlike `color.clj:20,51,126`) | 2 |
| O-5 | An `Error` ends an engine thread or the preview timer silently. The UI keeps saying "Streaming", and beat timing stops | M-H | p3, p11; only `Exception` is caught at `streaming_engine.clj:256` and `frame_service.clj:861` | 2 |
| O-6 | A frame that fails to encode sends nothing (the DAC holds the last frame), while a provider error sends a blank frame | M | p2; `streaming_engine.clj:256-259` vs `multi_engine.clj:207-212` + `streaming_engine.clj:219` | 2 |
| O-7 | Output errors log at ERROR on every frame from the real-time threads (60 lines/s per engine) | M | `streaming_engine.clj:259`, `multi_engine.clj:115,208,211`, `animation/effects.clj:255`, `frame_service.clj:318,500` | 2 |
| O-8 | There's no master output switch. The blackout trigger action stops the transport, which also rewinds every cue and the global clock; output is live as soon as Stream is pressed and something plays | M | `triggers.clj:76-77`, `handlers/timing.clj:68-77`; no output-enable flag in `src/` | won't fix (O4: C) |
| O-9 | Callbacks from futures and the FX thread run the whole event pipeline off the agent and write the whole state back | H (enables O-1) | `app.clj:95` + `cljfx/event_handler.clj:22-25`; callbacks at `events/core.clj:88,191,196,215,329,335,349,353,364,380`; the namespace doc says otherwise (`events/core.clj:20-24`) | 3 |
| O-10 | Engine handles live in app state; there are two "running" flags, and "Streaming to N" counts enabled projectors, not engines | M | `multi_engine.clj:289-292`; `connection.clj:40,54` vs `multi_engine.clj:291,314`; `toolbar.clj:221-233` | 4 |
| O-11 | Refresh compares projector ids only, so host, port, service and bit-depth edits never reach a running engine. Add Manual and host edits don't refresh at all | M | `multi_engine.clj:338-343,367-368`; `projector.clj:238-253,268-281` | 4 |
| O-12 | No link health: each projector's status is set once, to disconnected; engine stats are never read | M | `projector.clj:103`, `projectors.clj:224`; `get-stats` has no caller (`streaming_engine.clj:340-343`) | 4 |
| O-13 | Exit stops only the engines found in state, and there's no shutdown hook. Since `93c638a`, closing the window in dev mode no longer kills the JVM, so orphans from O-1 keep streaming in the REPL | M | `app.clj:190-198,230-237`; no `addShutdownHook` in `src/` | 4 |
| O-14 | Frame duration is 1/fps, so the scan speed follows the point count (p10). `:scan-rate` is stored and never read | H (galvos, exposure) | `streaming_engine.clj:147`, `stream.clj:543-546`; `projector.clj:89,100` | 5 |
| O-15 | Pacing sleeps the rest of each frame on the wall clock | M | p12, p13; `streaming_engine.clj:261-264` | 5 |
| O-16 | Timestamps and the config-resend timer use the wall clock in ms; Stream §2.1 requires timestamps that increase monotonically | L-M | p10; `streaming_engine.clj:109-122` | 5 |
| O-17 | Cue and beat timing advance only in the 30 Hz preview tick. The 60 fps output sees each beat position twice and freezes when the timer stops; elapsed time comes from the wall clock instead | M | `frame_service.clj:108-145,805-831,847-866`; `multi_engine.clj:171-174`; `dev/reload.clj:120,136` | later (O10) |

**Why this order.**
- O-1 is the worst failure: the operator presses Stop and the lasers stay on. But fixing it properly needs the event re-entry change (Phase 3) and an engine owner (Phase 4).
- Phase 2 needs neither, and it fixes the other high-severity findings (O-3, O-5, O-6). Until Phase 4, quitting the app is the only sure way to stop an orphaned engine: production exits the process, and the DAC goes dark after its 1 s link timeout.

## 3. Target architecture (recommended defaults)

```
UI / MIDI / OSC / Link ─▶ event agent ──────────────▶ app state  (one writer: the agent)       [Phase 3]
                              │  ▲
           :multi-engine/*    │  │ :output/status (pure data, ≤ 4 Hz)
                              ▼  │
                     output executor (one thread) ──owns──▶ engine registry (runtime atom)    [Phase 4]
                     runs start, stop, sync only              {projector-id {:engine :fingerprint}}

engine thread, per projector, deadline-paced at 60 fps                                         [Phase 5]
  frame provider (reads app state) ─ a failing effect is skipped; any other error ─▶ empty frame
     │                                       (every error logged once, in output-errors)       [Phase 2]
     ▼
  sanitize-frame (bad points dropped, dark first sample, ≥ 2 samples) ─▶ encode (duration from scan rate) ─▶ UDP
```

- No `Thread`, socket or engine object in app state. The registry is the only place an engine can live, and stopping drains it.
- A failing effect is skipped on its own; any other failure while producing a frame gives an empty frame, never a stale one.
- Every background result re-enters through the agent, so a whole-state reset can't undo another thread's write.

## 4. Phases and dependencies

| Phase | Goal | Depends on |
|---|---|---|
| 1 | Fake DAC; today's correct wire behaviour pinned in tests | none |
| 2 | Nothing unsafe reaches the wire | 1 |
| 3 | Background results re-enter through the agent | 1 (for tests only) |
| 4 | One owner for the engines; truthful status | 3 |
| 5 | Frame timing (scan rate, pacing, timestamps) | 1; a hardware check before release |
| later | One show clock (O10 option A) | 4, 5 and review 03 MR1 |

Phases 2, 3 and 5 are independent once Phase 1 is in. Phase 4 follows 3.

## 5. Decisions

Each decision is written out with options and a recommendation in the doc that needs it. Reply with letters (for example `O1: A, O2: A`) or in your own words. Anything left unanswered I'll ask again before that phase starts.

| ID | Question | Doc | Needed before | Recommended |
|---|---|---|---|---|
| O1 | Where the fake-DAC tests run | 01 | Phase 1 | **A, kept 2026-11-16**: in the unit suite; rate assertions tagged `^:timing` |
| O2 | What an effect error does to the output | 02 | Phase 2 | **Answered 2026-11-16**: skip only the effect that throws; log once |
| O3 | What the last-line sanitiser changes | 02 | Phase 2 | **Answered 2026-11-16**: drop bad points; dark first sample; at least 2 samples; clamp as today |
| O4 | Master output switch | 02 | Phase 2 | **C, answered 2026-11-16**: no switch; blackout unchanged |
| O5 | How background results re-enter | 03 | Phase 3 | **A, answered 2026-11-16**: callbacks from other threads go through the agent; follow-ups within a turn stay inline; tests keep the synchronous interpreter |
| O6 | Who owns the engines | 04 | Phase 4 | **A, answered 2026-11-16**: runtime registry plus one output executor for start, stop and sync; each projector keeps its own streaming thread |
| O7 | What a refresh restarts | 04 | Phase 4 | **A, answered 2026-11-16**: diff by config fingerprint; requests coalesced |
| O8 | How link health is shown | 04 | Phase 4 | **A, answered 2026-11-16**: engine stats at ≤ 4 Hz → toolbar and the projector dot; no ping yet |
| O9 | Frame duration | 05 | Phase 5 | **A, answered 2026-11-17**: from the point count and the projector's scan rate (default 25 000 pts/s), editable; the status shows the fps the projector draws; verify on hardware |
| O10 | Where show time advances | 05 | after Phase 5 | A: one show clock, as its own plan after Phases 1–5 |

## 6. Review items, re-checked 2026-11-16

| Review item | Today | Here |
|---|---|---|
| 04 findings 1, 2, 6, 20, 22; QW1, QW2, QW4, QW5 | Fixed: `88cd72e`, `30eea6a`, `026b50d`, `7d10d01`, `49388bf` | – |
| 04 finding 17, QW7 (address per packet) | Fixed by `0ed784b`; a `DatagramPacket` is still allocated per send (`hello.clj:71-75`) | out of scope (performance) |
| 04 findings 3, 4, MR1 (fragment layout) | Latent: fragmentation is off (`streaming_engine.clj:79`) | out of scope until a reference receiver or hardware is available |
| 04 finding 5, QW3 (timestamps) | Still true (p10) | 5 |
| 04 findings 7, 8, MR2 | Still true (p1, p10) | 2 (sanitiser), 5 (duration) |
| 04 finding 11, LE1 | Still true; the timeline end and MTC chase now also run in the preview tick (`frame_service.clj:83-106,142-145`) | later (O10) |
| 04 findings 12, 13, 15, MR4 | Still true (p4–p7) | 4 |
| 04 finding 14, MR3 | Still true (p12, p13) | 5 |
| 04 finding 16, MR5, QW6 | Still true | 2 (logging), 4 (health) |
| 04 finding 26, LE3 (calibration mode) | The calibrating id is read only by the UI (`subs.clj:524-529`, `projectors.clj:549`) | out of scope (failing calibration effects: O2 skips only the failing one) |
| 04 LE2 (one IDN link per DAC) | Needs multi-head hardware | out of scope |
| 04 LE4 (fake DAC) | Partly: loopback tests only (`test/laser_show/backend/streaming_engine_test.clj:10-71`) | 1 |
| 03 findings 9, 10, MR4 (effect error policy) | Still true (p8) | 2 (skip the failing effect, log once) |
| 03 findings 6, 7, MR5 (clamp and blank at the end of the chain) | Still true | 2 owns the output-side failsafe; effect-side clamping stays with 03 |
| 03 MR1 (one time base) | Still true | later (O10) |
| 02 #23, M8 (symmetric lifecycle) | Mostly done by `93c638a`: `stop!` stops the engines, the preview timer and Carabiner | 4 finishes it (registry drain, shutdown hook) |
| 02 M1 (lost updates) | Still true for the output writers | 3 and 4; the other writers (link init, clipboard) stay with 02 |
| 02 M7 (one effect interpreter) | Still true | 3 |

## 7. Out of scope

- The fragment layout and turning fragmentation on (04 MR1): they need a reference receiver.
- One IDN-RT link per DAC (04 LE2): it needs multi-head hardware.
- Performance work: zero-copy packets (04 MR6), routing cost (MR7), streaming profiler (MR8), per-point allocation (03 LE1).
- The calibration test-pattern mode (04 LE3 except fail-closed).
- Whether projectors belong to the project file or to the venue. Today File > New while streaming closes every engine, because the new document has no projectors (`menu.clj:60`, `multi_engine.clj:337-355`). Phase 4 makes the status say so; the product question stays open.

## 8. Re-running the probes

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
P=plans/2026-11-16-output-runtime
clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" \
    -M:mac-arm:test:probe -d "$P/probes" \
    -n probes.output-probes-test 2>&1 | grep PROBE
```

Use `:win`, `:linux` or `:mac` instead of `:mac-arm` on other machines. The run takes about a minute and needs no network beyond loopback. When a phase fixes a behaviour, turn its probe into a real test on the Phase 1 fake DAC, asserting the new behaviour.
