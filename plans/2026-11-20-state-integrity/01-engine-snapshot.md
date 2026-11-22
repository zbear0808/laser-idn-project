# Every frame comes from one snapshot

**Written:** 2026-11-20   **Written for commit:** `3229c95` (`master`)
**Status:** **Done** (2026-11-21, `09b6db7`, `dba29bb`; §11 As built). E1–E3 answered A, the ★ options (2026-11-21, §Decisions)   **Last verified against:** `fe7d554` (2026-11-21: every citation and claim re-checked after the output drivers (`5a9a41c`..`4b22368`), zones Z4–Z6, vibe Phase 8, Decimate's follow-ups Q-D-1…Q-D-4 B and the Projectors layout pass, `cc9e000`..`fe7d554`; probes e1–e4 run three times there, with e1i added, JDK 25.0.5, macOS arm64, load average 1.6–2.5; §1.5). Earlier: `cc9e000` and `3229c95`, three runs each, 2026-11-20
**Supersedes / Superseded by:** supersedes the live reads Z1 put into `projector-frame` (`plans/2026-11-19-zones/03-phase-z1-safety.md` §4, built in `df65be4`, `fb801f2`, `1b14cc4`) and the "from the next frame" of its §8 invariant 10; Z3's "Only the projector effects and Z1's safety settings are still read live, as today" (`05-phase-z3-routing-core.md` §8 invariant 2). Takes over review 04 #23, the engines' part (`plans/2026-11-07-improvement-review/04-output-pipeline.md:174-179`), which the show clock's Phase 4 left as "Observed, not changed" (its §10, `git show 2d1f813~1:plans/2026-11-17-show-clock/04-phase4-one-timing-context.md`), and MR7's step "Pass `raw-state` into `apply-projector-effects`" (`04-output-pipeline.md:347`). Superseded by: none   **Related findings:** review 04 #23, MR7; show clock S-5 (its `00-overview.md` §2, `git show 2d1f813~1:plans/2026-11-17-show-clock/00-overview.md`); zones-needs T-12 ("use the same tick everywhere", `plans/2026-11-19-zones-needs/03-conflicts.md:118-126`); `docs/CLJFX_STATE_MANAGEMENT.md` Best Practice 1 (`:576-594`); output drivers C-6 ("Drivers get no access to app state", `plans/2026-11-19-output-drivers/01-driver-contract.md:81`)
**Needs:** nothing. E1–E3 are answered; no dependency on the sibling plans (§10).   **Size:** ~1 d (1–1.5 d with both full suites, the probes in three plan folders and the as-built note)

The show clock reads app state once per tick and publishes a snapshot. Each laser's engine then takes the tick's cues and routing from the snapshot, but reads its calibration, safety zone, test pattern, Identify and BLACKOUT from app state as it is when it gets to them, once or twice per frame. So one frame can join tick N's show with a later calibration or zone, one frame can even take its zone and its calibration from two different states, and two lasers can disagree about the same tick. Since Decimate's follow-ups the tick's routing also carries each laser's Decimate budget, from the tick's calibration chains, while the frame runs the chain it reads later: one more way a frame mixes two states (e1i). This plan puts those inputs in the snapshot, so a frame is a function of its snapshot alone and no engine thread reads app state, whatever driver it sends through.

Paths are under `src/laser_show/` unless they start with `test/`, `plans/` or `docs/`. **NEW** marks anything that doesn't exist yet.

## After this plan

- An edit to a laser's calibration, safety zone, test pattern, Identify or BLACKOUT lands on every laser at the same tick. No frame is one tick's show through a later tick's calibration or zone.
- The card's fps, load meter and LOW FPS, which since Decimate count what a laser's calibration left, describe the same tick as the frame. So does the meter's rule for a laser with Decimate in its chain (Q-D-3 B), which already comes from the tick.
- A laser's halves are always fitted to the zone they are clipped to.
- No engine thread reads app state, `idn-streaming-*` or `output-streaming-*`. The clock's one read per tick feeds the cues, the routing and every frame.
- A keepalive resend repeats the tick's frame instead of making a new one from newer state.
- The engine's frame can be unit-tested from a snapshot alone: `(#'me/projector-frame :p snapshot)`, with no app atom.

## 1. Current behaviour (file:line)

### 1.1 What one frame reads, and when

Since the output drivers (`5a9a41c`) every engine is the same loop over a driver map: `streaming-loop` (`backend/streaming_engine.clj:333-450`) waits for the clock's tick (`:388-391`), asks a device-paced driver's `:ready?` (`:397-399`, `await-ready!` `:267-287`; no built-in driver has one), then calls the frame provider (`:401`) and the driver's `:send-frame!` (`:409`). The provider (`backend/multi_engine.clj:223-255`) calls `(projector-frame projector-id (show-time/latest))` (`:245`). Both run on the engine's streaming thread: `idn-streaming-<id>` for IDN, `output-streaming-<id>` for any other driver (`thread-name`, `streaming_engine.clj:461-466`, started at `:571`).

| What the frame takes | From | Where it is read | When |
|---|---|---|---|
| The rendered cues, the routing, `:playing?`, `:projector-ctx`, `{:tick :at-ns}` | the snapshot | `multi_engine.clj:186`, `:201-202`, `:212`, `:216`, `stamp` `:104-107` | the tick's one read of app state, `services/show_clock.clj:263` |
| Whether the frame's record is metered as what the laser draws (`fit-guard/drawn`, Q-D-3 B), from the routing's `:decimate-budget` | the snapshot's routing, made from the tick's `[:chains :projector-effects]` (`routing/rig.clj:392`, `decimate-of` `:404-409`, `:443-450`) | `show-frame` `:212`, `:217-219` | the tick's read. **New since `cc9e000`** (`bfcaeab`) |
| Whether the test pattern shows: `[:projector-ui :identify id]`, `[:projector-ui :calibrating-projector-id]` | app state | `:177-178`, `test-pattern-mode` `:131-143` | read #1, when the engine starts the frame |
| BLACKOUT, `[:vibe-runtime :blackout?]` | app state | `:181` | read #1 |
| The pattern's brightness, `[:projector-ui :calibration-brightness]` | app state | `test-pattern-frame` `:150` | read #1 |
| The safety zone, `[:projectors id :safety]` | app state | `guard-safety` `:118`, given read #1 through `test-pattern-frame` (`:151`) or `show-frame` (`:215`) | read #1 |
| The calibration, `[:chains :projector-effects id :items]` | app state | `apply-projector-effects` `:85-86` | **read #2**, after composition and the fit guard (`:202-216`) |
| The frame's `:fit` record, which the card's fps, its load meter and LOW FPS read | the calibration's output | `show-frame` keeps it in a local `volatile!` and recounts the guard's record with its count (`:214-219`, `fit-guard/recount` `animation/fit_guard.clj:211-230`), since Decimate (`d6fbb15`); `recount` also sets `:effect-points` since Q-D-1 B (`9585cae`) | read #2's chain |
| Identify's wall clock | `System/currentTimeMillis` | `now-ms` `:126-129`, called at `:178` | at read #1 |
| The stale check | `System/nanoTime` | `:174-176` (`stalled` `:98-102`, `show-time/stale?` `animation/show_time.clj:412-415`) | at the provider's call |

**What the driver work adds, checked one by one.** Nothing that reads app state per frame:
- `driver/projector-config` and `driver/lookup` (`output/driver.clj:205-216`, `:143-146`) run once per engine, when `create-engine-for-projector` builds it (`multi_engine.clj:276-282`) on the output lifecycle thread, from the projector map the sync command carries (`events/core.clj:493-498`, `output_runtime.clj:160-175`). They read the driver registry, a runtime atom (`output/driver.clj:45-47`), not app state. The engine keeps the driver map and its config (`streaming_engine.clj:118-121`).
- Every driver call goes through `driver/with-loader` (`output/driver.clj:437-450`), which reads only the driver map's own classloader.
- The driver's `:status` is asked by the `output-status` thread (`poll-driver-status!`, `streaming_engine.clj:631-649`, from `publish-status!`, `output_runtime.clj:319-331`) and reads only the session (contract C-9, `plans/2026-11-19-output-drivers/01-driver-contract.md:84`). `engine-status` (`output_runtime.clj:235-262`) makes the card's fps, `:load` (`load-of` `:214-233`) and `:low?` from the engine's stats, whose `:fit` is the record of the last frame sent (`streaming_engine.clj:414-416`). It reads no app state. `publish-status!` reads app state once per 250 ms to compare (`output_runtime.clj:328`), on the `output-status` thread, and that is not a frame input.
- Drivers get no app state (C-6, `01-driver-contract.md:81`), and a driver's own threads only resend what was sent or blank (C-11, `:86`): they never call the provider.
- The add-on loader runs at start-up (`load-addons`, `app.clj:130-142`) and "Nothing here touches app state" (`output/addons.clj:14-15`).

So these two reads are still the only app-state reads on an engine thread. No other namespace on the frame path requires `state.core`: `routing/compose.clj`, `animation/fit_guard.clj`, `animation/safety.clj`, `animation/test_pattern.clj`, `animation/effects.clj` and `animation/effects/*.clj` (Decimate's `points.clj` included), `idn/frame_rules.clj`, `backend/streaming_engine.clj`, and since the drivers `output/driver.clj`, `output/lsps.clj`, `output/sample_format.clj`, `output/api.clj` and `idn/driver.clj` (checked with grep at `fe7d554`).

So up to three states can go into one frame: the tick's (cues, routing, the fitted halves, the meter rule), read #1's (pattern, BLACKOUT, zone) and read #2's (calibration, and with it the record's count).

### 1.2 What that does (probes e1, e4; run)

| Probe | Scene | Result today |
|---|---|---|
| e1a | Corner pin 1.0 → 0.5 after the tick, a line lit to x = 0.8 | the tick's frame is lit to **0.40**: it follows the edit, not the tick (0.80) |
| e1b | One laser, a 64-point circle on Left; safety limit ±0.9 → ±0.3 after the tick | the tick's routing fits Left to `[-0.225 0 0.675]` (the ±0.9 box), the stage clips to ±0.3: **0 of 64 points lit**. Both from the tick: 64 lit. The next tick fits `[-0.075 0 0.225]`: 64 lit |
| e1c | Test pattern on; BLACKOUT pressed after the tick | the tick's frame goes from 537 points to **0** |
| e1d | Test pattern turned on after the tick | the tick's frame goes from the cue (2 points) to **the pattern (537)** |
| e1e | Pin 1.0 → 0.5 and limit ±0.9 → ±0.3 while the engine composes (an edit inside `fit-guard/guard`) | lit to **0.40**: the new pin through the old limit. All old: 0.80; all new: 0.30. One frame, two states |
| e1f | Two lasers, one tick, both pins 1.0 → 0.5 between their frames | `:a` lit to **0.80**, `:b` to **0.40** |
| e1g | The same as e1a through `show-clock/tick!` with a circle cue | 0.50 → **0.25** |
| e1h | Decimate (25 kpps, 30 fps) added to the laser's calibration after the tick; a 3,000-point cue at 25 kpps | the tick's frame goes from the guard's 1,800 points to **833**, and its record's `:points` (the card, LOW FPS) from 1,800 to **833**. The next tick: 833 and 833 |
| e1i (**added at `fe7d554`**) | Decimate (25 kpps, 30 fps) removed from the calibration after the tick; the same cue | the tick's routing still has `:decimate-budget 833`. [points sent, `:points`, `:effect-points`] go from [833 833 833] to **[1800 1800 1800]**: the edit's chain, metered by the tick's rule (`fit-guard/drawn`). The next tick: [1800 1800 nil], the meter counting the 3,000 asked for |
| e4 | App-state reads per frame | a cue playing **2**; nothing playing 1; the test pattern 2; the pattern during BLACKOUT 1 |

e1a–e1h and e4 gave the same results at `fe7d554` as at `cc9e000` and `3229c95`: the driver work and the zones and Decimate work changed none of them.

### 1.3 Timing

| What | Where | Value |
|---|---|---|
| Tick period T | `TICK_HZ` 60 (`services/show_clock.clj:64`); `run-clock` parks to each deadline and skips missed ticks (`:311-326`) | 16.67 ms |
| An engine's frame period while the clock runs | `streaming-loop` sends each new tick's frame as soon as `publish!` wakes it (`streaming_engine.clj:388-391`, `show-time/frame-signal` `show_time.clj:451-458`); a device-paced driver first waits up to one interval for `:ready?` and skips the tick if it isn't (`:397-399`) | T |
| …with no tick for a while | the newest frame again after `KEEPALIVE_INTERVALS` (3) intervals (`streaming_engine.clj:84-89`, the wait's deadline `:389-390`); a new provider call (`:401`), so today it re-reads app state. A driver's own keepalives (C-11) resend what it sent and call no provider | 50 ms |
| …after `STALE_MS` without a tick | empty frames, "Show clock stalled" (`show_time.clj:345-348`, `multi_engine.clj:174-176`) | 250 ms |
| …while no clock runs (`start-test!`) | paced at `DEFAULT_FPS` (`streaming_engine.clj:40`, `:363`, `:392`, `:448`); `show-time/latest` is nil, so nothing is drawn (`multi_engine.clj:173`) | 16.67 ms |
| The DAC | finishes the frame it is drawing first (Stream §3.3); a frame lasts its samples at the scan rate. Since the drivers, IDN's `wire-duration-us` is in `idn/driver.clj:123-136`, returned as `:duration-us` (`:219`); another driver gives its own or the core's `default-duration-us` (`streaming_engine.clj:202-209`) | 16.7 ms at 418 samples, 40 ms at 1,000 points, at 25 kpps (`plans/2026-11-16-output-runtime/05-phase5-frame-timing.md` §7) |

Measured (e2, three runs at `fe7d554`, medians with p95 in brackets; the `cc9e000` runs after the arrow):

| | Idle | 1 cue, 1 laser | 4 cues + a global effect, 3 lasers |
|---|---|---|---|
| d: the tick's read (`show_clock.clj:263`) → its `:at-ns` (`:287`) | 22–23 µs (39–42); was 16–19 (29–95) | 111–120 µs (226–284); was 101–113 (263–468) | 180–192 µs (462–503), max 2.8–5.0 ms; was 167–188 (362–1,067), max 3.8–9.3 ms |

| 3 IDN engines on the fake DAC, the clock thread running, one cue, the default calibration | `fe7d554` | `cc9e000` |
|---|---|---|
| x₁: publish → read #1 (`:177`) | 82–90 µs (163–516), max 0.5–3.0 ms | 54–71 µs (138–897), max 0.5–8.1 ms |
| x₂: publish → read #2 (`:85`) | 184–247 µs (685–1,137), max 2.7–3.1 ms | 127–169 µs (485–1,637), max 1.8–8.2 ms |
| s: publish → the frame leaves the engine (`:tick-to-send-us`, `streaming_engine.clj:414-415`) | 1.21–1.53 ms | 0.45–0.60 ms |
| Reads on the engine threads | 720–726 in 2 s, 240–242 per engine: 2 per frame | the same |

The larger x and s are this machine today, not the driver loop: e2 run alternately on `cc9e000` (extracted to a temporary folder) and `fe7d554` at the same load (2.1–2.8) gave s = 1.42–1.44 and 0.73–0.79 ms on `cc9e000` against 1.21–1.24 and 1.31–1.37 ms on `fe7d554`, and x₁, x₂ of the same order. The tick-to-send varies by 2× from run to run on either commit. d grew by about 5 µs idle, within the noise for the busier scenes; the tick now also works out the zone map and the Map cycle before the render (Z4, Z5, `show_clock.clj:269-281`).

So today a frame's calibration is read up to 3 ms after its tick was published, and the engines of one tick read at different moments. e2 measures IDN engines only; an add-on driver with `:ready?` would read later still, up to one interval after the publish (*unverified*, no such driver ships).

### 1.4 The preview and the frame service

- **Preview cells** draw `show-time/latest` on each screen refresh (`views/components/clock_view.clj:1-11`, `start-timer!` `:31-56`). A group cell shows `:ref-frames` and a laser cell `:projector-frames` of the snapshot's `:preview` delay (`cell-points`, `views/components/preview.clj:142-155`; `redraw-cell!` `:157-175`), which is `frame-service/preview-data` of the tick's cues and routing (`services/show_clock.clj:294`, `services/frame_service.clj:562-590`). A laser cell runs `compose/projector-frame` and `fit-guard/guard` only (`frame_service.clj:582-584`): it stops before calibration and safety (Z3 §7). **Already one tick; no app state.** It doesn't share `multi-engine/projector-frame`. So it doesn't show a Decimate in the laser's calibration either, as for Corner Pin (`plans/2026-11-19-zones/12-decimate-effect.md` §6 "Found on the way").
- **The rig card's thumbnail** draws `show-time/output-frame` (`redraw-thumbnail!` and `output-thumbnail`, `preview.clj:201-232`; `show_time.clj:377-380`), what the provider produced, after calibration and safety (`multi_engine.clj:249`). It mixes whatever the engine mixed. Its grey outline is the card's `:limit`, from `subs/rig-cards` (`subs.clj:1362-1414`, `:limit` at `:1413`) on the FX thread.
- `frame-service/get-preview-zone-filter` (`frame_service.clj:71-77`) reads app state on the calling thread and has **no caller in `src`**; only `frame_service_test.clj:38-48` calls it. The UI reads that path through its sub (`preview-zone-filter`, `subs.clj:153-160`). `matches-preview-zone?` (`:79-91`) is private and test-only (`frame_service_test.clj:51-64`). Z3 left both (05 §11).
- The 2-arity `generate-frames-by-zone` (`frame_service.clj:469-473`) reads app state, the input store and `show-time/global-clock`. In `src` only the clock renders, with the 3-arity (`render-cue`, `show_clock.clj:83-84`); about 35 test call sites use the 2-arity. `one-state-and-input-read-per-tick-test` (`test/laser_show/services/show_clock_test.clj:626-654`) would fail if a tick called it.

### 1.5 What was run and what was read

- **Run**: `plans/2026-11-20-state-integrity/probes/engine_snapshot_probes_test.clj` (NEW, this plan), e1–e4, three times at `3229c95`, three at `cc9e000` (with e1h and an e3 line added there for Decimate) and three at `fe7d554` (2026-11-21, load average 1.6–2.5 by `uptime` before and after each run, 2.4–2.5 by e3's own reading; one more run before e1i was added gave the same results at 1.8–2.8). At `fe7d554` the probe compiled unchanged. e1i was added (it needs the routing's `:decimate-budget`, new since `bfcaeab`), and the citations in its comments and labels were updated; nothing else changed. e2 was also run twice on `cc9e000` and twice on `fe7d554`, alternately, to tell the machine from the code (§1.3). e1 and e4 are deterministic and gave the same results in every run. The timings vary with load and are quoted as ranges of the three `fe7d554` runs: every number in §1.3's measured tables, §4.2 and §4.3.
- **Read**: the call paths and line numbers, re-checked at `fe7d554`, the driver loop and what the drivers, their `:status`, the status thread and the add-on loader read (§1.1); the history in §2.2 (the plan docs, `git show 70f7db8:src/laser_show/backend/multi_engine.clj`, `git show 2d1f813~1:plans/2026-11-17-show-clock/…`); the latency formulas in §4.2 (from the code, with e2's numbers put in); the stall case in §4.2; the preview's data flow (§1.4). *Unverified*: anything on a real DAC, and any device-paced driver.

## 2. Root cause, and why the engines read live

### 2.1 Root cause

The snapshot was made to carry what the render produces (show clock Phase 2). The engines kept reading their per-laser settings from app state, because that is where they had always read them. Z3 moved the routing into the snapshot and stopped there. Decimate's follow-ups (Q-D-2 B `114e49e`, Q-D-3 B `bfcaeab`) put each laser's calibration chain into the routing's inputs too (`rig/routing-inputs`, `routing/rig.clj:381-393`: "the lasers' calibration chains, for their Decimate budgets"), so the clock now reads every chain per tick, while the engine still reads the chain it runs from app state. The output drivers rebuilt the engine around the provider and left the provider as it was. Nothing checks for app-state reads off the clock thread: `one-state-and-input-read-per-tick-test` counts only the reads on the clock's own thread (`show_clock_test.clj:637-642`). Reading the context from another thread is safe (`docs/CLJFX_STATE_MANAGEMENT.md:185-193`); the problem is consistency, as Best Practice 1 says of a worker that reads state itself: "State might have changed!" (`:587-593`).

### 2.2 Was reading live deliberate?

No plan gives a reason to read these settings live. The reads were inherited and kept to stay in scope:

| Where it came from | What it says |
|---|---|
| Before Z1 (`70f7db8`) | the engine already read `[:projectors id :zone-groups]` (`multi_engine.clj:132` there) and the projector effects (`:101` there) live |
| Output runtime Phase 4 (`plans/2026-11-16-output-runtime/04-phase4-engine-ownership.md:54`) | "Zone groups and names are read live by the provider, so changing them doesn't restart anything." The point is **no restart**. The same phase moved the lifecycle's reads to the handler, citing Best Practice 1 (`:58`) |
| Show clock Phase 2 (§9, at `2d1f813~1`) | "Moving projector effects to the clock thread… they stay on each projector's thread (O6)." That is about where the effects **run**; this plan keeps them there |
| Show clock Phase 4 (§10) | "Each projector's engine still reads app state on its own thread… Observed, not changed" |
| Z1 (`03-phase-z1-safety.md` §1 "Engines" row, §4 sketch, §5 `:187`) | follows the existing reads: "Projector settings other than the fingerprint… are read live, with no restart"; "Safety isn't part of the engine fingerprint, so an edit applies at the next frame with no restart". The sketch reads `(state/get-raw-state)` with no reason given |
| Z3 (`05-phase-z3-routing-core.md` S-2, §8 invariant 2) | moves the routing into the snapshot because "reading those live would let an engine and the preview disagree for a tick", and keeps "the projector effects and Z1's safety settings… read live, as today" |
| Decimate's follow-ups (Q-D-2 B, Q-D-3 B; `plans/2026-11-19-zones/10-questions.md:202`) | read each laser's chain **from the tick**, for the cue's ⚠ and the meter rule (`rig.clj:381-393`, `:443-450`), and leave the chain the frame runs where it was. No reason given for the difference |
| Output drivers (`plans/2026-11-19-output-drivers/01-driver-contract.md:81`, `:84`, `:86`) | give drivers no app state (C-6), keep `:status` to atoms (C-9) and keep driver threads off new content (C-11). The provider and its reads are untouched; no reason given to keep them live |
| `output-runtime/fingerprint` (`backend/output_runtime.clj:44-51`) | "names, groups, the rig's order, fit and safety settings restart nothing: the routing comes with each tick, and safety is read live": again about not restarting. The drivers added `:driver` and `:driver-config` to it (`:51`) |

The candidate reasons, one by one:

| Reason | Finding | This plan |
|---|---|---|
| A safety edit must apply at once on a live laser (D-28) | D-28 says a narrowing applies without asking ("at once", spec §10, `plans/2026-11-19-zones/01-model-and-routing-spec.md:227`); Z1 invariant 10 says "from the next frame, with no engine restart". Neither is a sub-tick latency | the edit applies from the first tick that reads it, still without a restart. The worst case grows by d + x ≈ 0.3–0.4 ms (§4.2) |
| Identify's flash timing | the flash is 250 ms lit, 250 ms dark (`animation/test_pattern.clj:16-18`, `:70-78`); the engine reads the wall clock per frame | the tick's wall clock goes in the snapshot (E1 A): the edges move by less than one tick (16.7 ms) on a 250 ms half |
| The test pattern shows with nothing playing | the clock publishes a snapshot every tick whether or not anything plays (`render` `show_clock.clj:122-123`, `tick!` `:283-294`; `snapshot-shape-test`, `show_clock_test.clj:233-239`) | the pattern's inputs ride on every snapshot, idle ones too |
| The stale check | it must compare the snapshot with the engine's clock: it is about delivery, not app state | it moves into the provider, unchanged (§4.1) |
| No restart for settings outside the fingerprint | the snapshot restarts nothing either | `fingerprint` and `wanted` are unchanged |
| A driver may need app state | C-6 forbids it; a driver gets only its config and session | nothing changes for drivers |

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| The tick's one read of app state (`services/show_clock.clj:263`) | NEW `engine-inputs` takes its slices from it, so the tick still reads app state once |
| `rig/routing-inputs` (`routing/rig.clj:381-393`) | the model: slices of the tick's state, by reference, made by the namespace that consumes them. Its `:projector-effects` is the very reference `engine-inputs` carries, so the Decimate budget and the chain come from one state |
| The snapshot and `publish!` (`animation/show_time.clj:355-359`, `:382-391`) | carry `:engine-inputs` beside `:routing` |
| `guard-safety` (`multi_engine.clj:109-124`), `test-pattern-mode` (`:131-143`), `test-pattern-frame` (`:145-156`), `projector-frame` (`:160-186`), `show-frame` (`:188-221`), `apply-projector-effects` (`:78-96`) | the same logic and the same failure handling, reading the snapshot instead of app state |
| `show-frame`'s recount and meter rule (`:214-219`; `fit-guard/recount` `animation/fit_guard.clj:211-230`, `fit-guard/drawn` `:232-238`) | kept as they are: they count what the tick's chain left, under the tick's rule |
| `stalled` (`:98-102`), `show-time/stale?` (`show_time.clj:412-415`) | the stale check, moved into the provider |
| `engine-routes-from-the-snapshot-not-app-state-test` (`test/laser_show/backend/multi_engine_test.clj:197-210`, its redefined readers at `:206-207`) | the pattern for the new tests: build a tick, edit app state, the frame follows the tick; make a reader throw |
| The `snapshot` fixture (`multi_engine_test.clj:34-40`) | gains `:engine-inputs`, so every existing engine test keeps setting state first and then building a snapshot |
| `with-engines` (`show_clock_test.clj:197-214`), `renders-once-per-tick-test`'s hand ticks (`:216-229`) | the test that counts app-state reads on the engine threads |
| `laser-show.output.recording-driver/recording-driver` (`test/laser_show/output/recording_driver.clj:25`), registered with `driver/register!` (`output/driver.clj:122`) | one non-IDN engine in that test, so the `output-streaming-*` thread is counted too |
| The provider's per-call `show-time/latest` (`multi_engine.clj:245`) | already the `docs/STALE_CLOSURES.md` pattern (deref at call time, `:85-125`); kept |

## 4. Changes per namespace

### 4.1 Namespaces

- `laser-show.backend.multi-engine` (`backend/multi_engine.clj`):
  - **NEW `engine-inputs [state wall-ms]`**, public and pure: what the engines take from app state for one tick, as references (§7). The show clock calls it once per tick with the tick's state.
  - `apply-projector-effects` (`:78-96`) takes the chain: `[frame projector-id items timing-ctx]`. It reads no state. The `try`, `ok!`, `report-skips!` and `report!` stay as they are.
  - `guard-safety` (`:109-124`) takes `[inputs projector-id snapshot f]` and reads `(get-in inputs [:projectors projector-id :safety])`. Normalizing stays inside its `try`, so an unreadable zone still fails closed and names itself under `[:safety id]` (E2 A).
  - `now-ms` (`:126-129`) goes (E1 A). `test-pattern-mode` (`:131-143`) takes `[inputs projector-id]` and uses `:identify`, `:calibrating` and `:wall-ms`.
  - `test-pattern-frame` (`:145-156`) takes `[inputs projector-id snapshot]`: `:brightness` and the laser's chain come from `inputs`.
  - `show-frame` (`:188-221`) takes `[inputs projector-id snapshot]` and gives the laser's chain to `apply-projector-effects`. Its recount and meter rule stay as they are: the chain's output still goes into the local `volatile!` (`:214`, `:216`), the guard's record is recounted with it (`:218`), and `:decimate-budget` from the snapshot's routing (`:212`) still decides `fit-guard/drawn` (`:219`). The budget and the chain now come from the same tick.
  - `projector-frame` (`:160-186`) keeps `[projector-id snapshot]`, so the 36 call sites in `multi_engine_test.clj` and the redefinitions in `output_lifecycle_test.clj` (`:390`, `:416`, `:449`, `:469`, `:489`, `:512`, `:594`, `:1053`) and `ui_test/streaming_status_test.clj:221` stay valid. It loses the stale check and the read:

    ```clojure
    (let [inputs (:engine-inputs snapshot)]
      (when (contains? (:projectors inputs) projector-id)   ; nil before the first tick, and for a laser the tick doesn't know
        (let [pattern (test-pattern-mode inputs projector-id)]
          (cond
            (and pattern (:blackout? inputs)) (stamp snapshot [])
            (= :identify-off pattern)          (stamp snapshot [])
            pattern                            (test-pattern-frame inputs projector-id snapshot)
            (:playing? snapshot)               (show-frame inputs projector-id snapshot)))))
    ```

    The `contains?` check is new. Today a projector missing from state reads its zone as nil, which `normalize` makes the full range (`animation/safety.clj:69-70`). Only other handlers keep such a laser dark: the routing doesn't reach it, and `:projectors/remove` clears its test pattern (`events/handlers/projector.clj:649-650`). Now it draws nothing by construction.
  - `create-projector-frame-provider` (`:223-255`): before `projector-frame`, the stale check on the engine's `System/nanoTime` (the code at `:173-176`, as it is). The same error reaches `[:provider id]`.
  - `create-engine-for-projector` (`:261-288`) and the driver path are unchanged: the provider is the only thing the engine calls, and a driver never sees the snapshot.
  - The namespace docstring (`:1-28`, "No engine reads the groups from app state" at `:23-25`): "No engine reads app state: each tick brings the routing and everything else a frame takes (`engine-inputs`)."
  - What "pure" means here: `projector-frame`'s value depends only on its arguments. It reads no app state, no clock and no atom that changes what it returns; `show-frame`'s `volatile!` is made fresh for each call. It still reports to `output-errors` and counts `routing-log-counter` (`:52`), as today.
  - **What Decimate and its follow-ups (`e7b93e4`..`b2bdb77`) change here**: nothing in the design, and two more reasons for it.
    - Decimate (`animation/effects/points.clj:46-56`, its transducer `:58-79`, registered `:108-114` with category `#{:shape :calibration}` at `:111`) can sit in a laser's calibration chain, so that chain sets the frame's point count. The chain's output also sets the `:fit` record (`:214-219`), which reaches the engine's stats (`streaming_engine.clj:414-416`), then `output-runtime/engine-status`'s `:load` and `:low?` (`backend/output_runtime.clj:235-262`): the card's fps, its load meter and LOW FPS. Today that chain comes from read #2, so a Decimate added, removed or retuned between the tick and the frame changes both the frame and the card's numbers for that tick (e1h). The count still agrees with the frame, because the record counts the frame that was made.
    - Since Q-D-3 B the meter rule comes from the tick's routing (`:decimate-budget`) while the chain comes from read #2, so a frame can be metered by one state's rule and drawn by another's chain (e1i). After the plan, the frame, its record, its rule and the card all follow the tick's chain.
    - Decimate's `:kpps` and `:fps` are resolved per frame from the chain's timing context (`effects/points.clj:65-67`), and a calibration may carry modulators other than presses. That is one more reason the chains stay per frame (§4.3).
    - Q-D-2 B is built (`114e49e`; `10-questions.md:202`): the clock's cue warnings, made from the snapshot (`cue-warnings`, `show_clock.clj:159-170`, `compose/cue-warnings` `routing/compose.clj:244`), read each laser's budget from the tick's routing, which reads the tick's `[:chains :projector-effects]`. They already agree with the tick; after the plan the frames do too.
- `laser-show.services.show-clock` (`services/show_clock.clj`):
  - `tick!` (`:254-302`): the snapshot (`:286-294`) gains `:engine-inputs (multi-engine/engine-inputs state (System/currentTimeMillis))`, from the tick's one read (`:263`), before `publish!`. `vibe-step`'s own wall-clock read after the publish (`:229`) stays as it is.
  - A NEW require of `laser-show.backend.multi-engine`. There is no cycle: `multi-engine` requires nothing that requires the clock (its new `output.driver` require pulls in `idn.driver`, `output.lsps` and `idn.stream`, none of which does), and the clock already loads it through `events.core` (`events/core.clj:68`) → `output-runtime` (`backend/output_runtime.clj:22`).
  - Docstring step 4 (`:23-30`): the snapshot carries what the engines take from app state.
- `laser-show.animation.show-time` (`animation/show_time.clj`): the `!snapshot` docstring (`:355-359`) lists `:engine-inputs`.
- `laser-show.backend.output-runtime` (`backend/output_runtime.clj`): the `fingerprint` docstring (`:44-49`) says "the routing, safety and calibration come with each tick" instead of "the routing comes with each tick, and safety is read live". `wanted`, `engine-status` and the status thread are unchanged: they read engine stats and driver status, not frames.
- `laser-show.backend.streaming-engine`: **not changed**. The generic loop still calls the provider for every frame it sends, a keepalive included (`:401`); after the plan the provider returns the tick's frame again.
- **Probes in other plans**, updated with the code, as Z3 updated `vibe_probes_test.clj` (05 §13). Without these changes they would measure nil frames, or throw on the new arity:
  - `plans/2026-11-19-zones/probes/zones_probes_test.clj:96-99` (`snapshot`) and `plans/2026-11-19-zones/probes/vibe_probes_test.clj:579-585` (`zone-snapshot`) gain `:engine-inputs`. That file's z3 and z4 run the real clock (`:163`, `:211`) and need nothing;
  - `plans/2026-11-16-output-runtime/probes/output_probes_test.clj:324-330` (p8) passes the chain to `apply-projector-effects`.
- **Not changed**: `routing/rig.clj` (E2 A), `routing/compose.clj`, `animation/safety.clj`, `animation/test_pattern.clj`, `animation/fit_guard.clj` (`recount` and `drawn` included), `animation/effects/points.clj`, `services/frame_service.clj`, `backend/streaming_engine.clj`, `output/*.clj`, `idn/driver.clj`, the views and every event handler. The projector effects still run on each engine's own thread (O6).

### 4.2 Latency

Symbols: T is the tick period (16.67 ms). d is the tick's read → its publish (§1.3). x₁ and x₂ are publish → read #1 and read #2 today. s is publish → the frame leaves the engine. D is the DAC finishing its current frame.

An edit is in the first frame whose read comes after the edit's write (the `:state` effect, `effect-state` `events/core.clj:154-162`). The reads come once per tick, so:

| | Today | After |
|---|---|---|
| Where the frame's settings are read | read #1 at publish + x₁, read #2 at publish + x₂ | the tick's read, at publish − d |
| Worst case, write → the frame with the edit leaves the engine | T − x + s (the edit lands just after an engine's read) | T + d + s (it lands just after the clock's read) |
| Increase | – | **d + x** |
| Then the DAC | + up to D | the same |

| Edit | Read today | Worst case today | Worst case after | Increase, d + x (medians) |
|---|---|---|---|---|
| Safety narrowing (`:projectors/set-safety`) | #1 | 16.67 − 0.09 + 1.37 ≈ 18.0 ms | 16.67 + 0.19 + 1.37 ≈ 18.2 ms | 0.27 ms |
| BLACKOUT on the test pattern | #1 | ≈ 18.0 ms | ≈ 18.2 ms | the same |
| Test pattern or Identify on/off | #1 | ≈ 18.0 ms | ≈ 18.2 ms | the same |
| Calibration edit (corner pin, curves, Decimate) | #2 | 16.67 − 0.22 + 1.37 ≈ 17.8 ms | ≈ 18.2 ms | 0.40 ms |

The numbers use the medians of the `fe7d554` runs (§1.3): the 4-cue scene's d (0.18–0.19 ms), x₁ (0.08–0.09), x₂ (0.18–0.25) and s (1.21–1.53), on this machine. At `cc9e000` the same table gave increases of 0.24 and 0.33 ms; the difference is the machine (§1.3). Then up to D more at the DAC (16.7–40 ms for 418–1,000 points at 25 kpps). Under load the tails are longer: each part's p95 was between 0.16 and 1.14 ms in these runs (§1.3), a fifteenth of a tick or less. From the code with e2's numbers put in; not measured end to end, and *unverified* on a real DAC.

- **Which edits get later.** Only those that land between the clock's read and the engines' reads. That window is d + x, about 0.3–0.4 ms of each 16.67 ms tick, so about 2 % of edits. They move to the next tick, the frame they belong with. Every other edit reaches the laser at the same moment as today. For a device-paced driver x can reach one interval, so more of its edits move to the next tick; the worst case after, T + d + s, doesn't depend on x (*unverified*, no such driver ships).
- **Device-paced add-on drivers** (they wait for `:ready?` before asking for a frame, `streaming_engine.clj:397-399`): accepted as *unverified* for now (the user, 2026-11-21). Their provider call takes the newest snapshot like any other engine's, so their frames are one tick's too; the latency above is still to be checked with a real device-paced add-on on hardware (§10).
- **BLACKOUT on the show itself is already per tick.** Pressing it returns `:dispatch {:event/type :transport/stop}` (`toggle-blackout`, `vibes/engine.clj:469-488`, the dispatch at `:488`), which the pipeline runs inline in the same turn (`effect-dispatch`, `events/core.clj:164-167`). `handle-transport-stop` sets `[:playback :playing?]` false (`events/handlers/timing.clj:92-101`, `:97`). The tick reads that (`show_clock.clj:283`). Only the pattern's BLACKOUT was live. After this plan both go through the snapshot.
- **Identify**: E1 A times the flash by the tick, so each 250 ms half starts on a tick boundary, at most one tick (16.7 ms) late.
- **The one case that gets slower: a stalled clock.** With no tick for 50–250 ms, today a keepalive resend (a new provider call, `streaming_engine.clj:389-390`, `:401`) re-reads app state, so a narrowing reaches the laser at the next resend (≤ 50 ms). After, the resend repeats the tick's frame, and the narrowing waits for the next tick. At 250 ms (`STALE_MS`) the engine sends empty frames anyway (S3 A). So the worst case during a stall grows from about 50 ms to 250 ms, and then the laser is dark. During a stall the show is frozen as well. From reading the code, not measured. Making resends re-read the zone is rejected (§10).

### 4.3 Cost

| Work | Thread, rate | Today | After |
|---|---|---|---|
| The frame's two app-state reads | each engine, per frame (60/s per laser) | 2 × 0.13 µs (e3) | none |
| Building `engine-inputs` | the clock thread (`show-clock`, `show_clock.clj:341`), per tick (60/s) | – | 0.7–0.9 µs for a map of seven entries, each a reference or a scalar (e3, a stand-in for the same map) |
| Normalizing the safety zone | each engine, per frame | twice (`guard-safety` `:118`, then inside `apply-safety`, `safety.clj:276`): 2 × 6–7 µs for a limit, 2 × 19–22 µs with two blocked areas, nothing without a zone (e3) | E2 A: the same |
| Building the calibration chain | each engine, per frame | 45–48 µs of the default chain's 123–128 µs on 1,000 points: rgb-curves builds three 256-entry LUTs per frame (`animation/effects/calibration.clj:26-35`) (e3) | the same (§10) |
| Decimate last in that chain | each engine, per frame | 395–411 µs on the guard's 1,800 points (cut to 833), against 183–188 µs for the chain without it (e3): Decimate buffers the frame and thins it (`effects/points.clj:58-79`) | the same |
| The routing | the clock thread, per change of `[:rig]`, `[:projectors]`, `[:zone-groups]`, `[:zone-maps]`, `[:chains :projector-effects]` or the effective map (`routing-keys`, `rig.clj:379`); otherwise an identity check | 28–30 µs for 1 laser, 66–72 µs for 3, 172–189 µs for 8, already normalizing each zone (`halves`, `rig.clj:395-402`); 0.4 µs per tick otherwise (e3). Since Q-D-2 B every calibration edit, a corner-pin drag step included, recomputes it | the same (E2 A) |
| The whole engine frame | each engine, per frame | 132–135 µs without a zone, 808–811 µs with two blocked areas (1,000 points; `apply-safety` alone 652–662 µs) (e3) | the same, minus the two reads |

The `fe7d554` costs are within 10–20 % of the `cc9e000` ones, mostly a little lower at the lower load; none changed in kind.

- **What goes into the snapshot**: references to maps that are already immutable, `(:projectors state)` and `(get-in state [:chains :projector-effects])` (the same two references the routing's inputs hold, `rig.clj:389`, `:392`), plus four scalars and the `{pid until}` map, also by reference. No copy and no new computation.
- **Per tick or per change**:
  - The references cost nothing, so they go in per tick.
  - Normalized safety stays per frame as today (E2 A). Normalizing it per tick on the clock thread would cost n × 6–22 µs per tick, up to ~0.2 ms with 8 lasers: as much as a whole render (d, §1.3). Rejected. Per change (E2 B, C, not chosen) would save one normalize per frame: 6–22 µs, about 0.04–0.13 % of a frame period on each engine thread.
  - Built calibration chains stay per frame. They take the frame's timing context and point count (`frame-context`, `animation/effects.clj:235-244`, made in `apply-effect-chain` at `:387`), and calibration may carry modulators other than presses (`modulator-choices`, `views/components/modulator_param_control.clj:47-53`). Since Decimate, a chain can also change the frame's point count, from parameters resolved per frame (`effects/points.clj:65-67`).
- **The memo patterns**:
  - `rig/cached-routing` (`rig.clj:465-473`, its cache in the clock's runtime as `:routing-cache`, `show_time.clj:59-65`) would have been the model for E2 B or C.
  - `!refs` (`rig.clj:494-498`) is a global atom that engine threads and the UI both wrote ("a race only recomputes"). After Z3 no engine calls it (`rig.clj:476-477`). It is the pattern to avoid on the output path.

### 4.4 The preview

The preview cells need nothing: they already draw one tick from the snapshot and never read app state for a frame (§1.4). The thumbnail's frame becomes one tick's through the engine. Its outline stays the card's current limit (E3 A).

The dead preview reader `get-preview-zone-filter` and `matches-preview-zone?`, and the 2-arity `generate-frames-by-zone`, are out of scope (§10). None is on a frame path, and deleting them changes no frame.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| snapshot `:engine-inputs` **NEW** | runtime: `show-time/!snapshot` (`show_time.clj:355-359`), replaced every tick | the show clock thread (`tick!` → `publish!`) | no / no |
| `[:chains :projector-effects]`, `[:projectors id :safety]` (existing) | the rig (document; the rig file since ZR, `state/rig_file.clj:30`) | their events, as today | as today |
| `[:projector-ui :calibrating-projector-id]`, `:calibration-brightness`, `:identify` (existing) | UI (`state/domains.clj:357-378`) | their events, as today | no / no |
| `[:vibe-runtime :blackout?]` (existing) | session (`state/domains.clj:107-136`, `:119`) | `:vibe/press :role :blackout` | no / no |
| `multi-engine/now-ms` (var, `multi_engine.clj:126-129`) | – | **removed** (E1 A) | – |

Nothing new goes into app state and nothing new is saved. This follows `docs/CLJFX_STATE_MANAGEMENT.md`:
- Best Practice 1: one thread extracts the data once, and the workers get immutable data.
- Best Practice 5 (`:650-663`): the per-tick data stays in the clock's runtime atom, out of the context.

## 6. Events and effects

No event, handler or effect changes or is hooked. The effect keys in `events/core.clj` are untouched. These events' results reach the lasers from the next tick, on every laser at once:

| Event | Dispatched from | Pure? |
|---|---|---|
| `:projectors/set-safety`, `:projectors/add-blocked-area`, `:projectors/remove-blocked-area`, `:projectors/copy-safety` | `views/components/safety_editor.clj:185`, `:289`, `:295`, `:332`; `:321`; `:212`; `:227`, `:232` | yes, unchanged |
| `:chain/update-param`, `:chain/update-curve-point`, `:chain/update-spatial-params`, `:chain/reset-params` with `:domain :projector-effects` | `views/tabs/projectors.clj:417-420`, `:470-473`, `:481-484`, `:485-489` | yes, unchanged |
| `:projectors/add-effect` (a calibration effect, Decimate among them since `d6fbb15`; handler `events/handlers/projector.clj:564`) | the laser's "+ Add Effect" menu (`projector-add-effect-menu`, `views/tabs/projectors.clj:540-560`, the event at `:552`, the item ids at `:550`) | yes, unchanged |
| `:projectors/toggle-calibration`, `:projectors/set-calibration-brightness` | `views/tabs/projectors.clj:609`, `:634` | yes, unchanged |
| `:projectors/identify` (and its `:dispatch-later` `:projectors/identify-ended`, `events/handlers/projector.clj:687-688`) | `views/tabs/projectors.clj:618`, `views/components/rig_strip.clj:358` | yes, unchanged (uses `:time`, `co-effect-time` `events/core.clj:95-98`, registered at `:582`) |
| `:vibe/press :role :blackout` | the Perform pad (`views/components/vibe_pads.clj:65`, `:83` → `:vibe/pointer`, `events/handlers/vibes.clj:75` → `engine/press`, `:212`, `vibes/engine.clj:504`); a bound trigger (`events/handlers/triggers.clj:81`) | yes, unchanged |
| `:edit/undo`, `:edit/redo` restoring a zone or calibration | the title bar menu (`views/components/title_bar.clj:116`, `:122`) and the editors' keys | yes, unchanged |

## 7. Data examples and invariants

```clojure
;; The tick's state (the clock's one read, show_clock.clj:263), in part
{:projectors   {:p1 {:host "10.0.0.5" :enabled? true
                     :safety {:limit [-0.9 -0.8 0.9 0.95] :blocked [[-0.2 -1.0 0.2 -0.4]]}}
                :p2 {:driver :lsps :driver-config {:host "10.0.0.6" :port 7266} :enabled? true}}
 :chains       {:projector-effects {:p1 {:items [rgb-curves corner-pin decimate]} :p2 {:items [rgb-curves corner-pin]}}}
 :projector-ui {:calibrating-projector-id :p2 :calibration-brightness 0.1 :identify {:p1 1763686500000}}
 :vibe-runtime {:blackout? false}}

(me/engine-inputs state 1763686498000)
;; => {:projectors        {:p1 {…} :p2 {…}}            ; (identical? … (:projectors state))
;;     :projector-effects {:p1 {:items […]} :p2 {…}}  ; (identical? … (get-in state [:chains :projector-effects]))
;;                                                     ; and to (:projector-effects (rig/routing-inputs state map-id))
;;     :calibrating       :p2
;;     :brightness        0.1                          ; 0.1 when state has none, as test-pattern-frame does today (:150)
;;     :identify          {:p1 1763686500000}          ; until-ms, on the :time co-effect's wall clock
;;     :blackout?         false
;;     :wall-ms           1763686498000}               ; the tick's wall clock, for Identify (E1 A)

(:engine-inputs (show-time/latest))   ; that map, beside :cues :routing :projector-ctx :playing? :tick :at-ns
                                       ; :positions :pulses :faders :zone-steps :preview :render-us
```

The driver doesn't matter to `engine-inputs`: `:p2` streams through LSPS on an `output-streaming-p2` thread and takes its frame from the same map.

Each row is an edit between the tick and its frame. "Today" is probe e1; "after" is the target:

| Edit after tick N | Today | After: tick N's frame | Tick N+1's frame |
|---|---|---|---|
| Corner pin 1.0 → 0.5; a circle cue (e1g) | reaches 0.25 | reaches **0.50** | 0.25 |
| Limit ±0.9 → ±0.3; a 64-point circle on Left of one laser (e1b) | 0 lit (halves for ±0.9, clipped to ±0.3) | **64 lit**, halves and clip both ±0.9 | 64 lit, halves `[-0.075 0 0.225]`, clipped to ±0.3 |
| BLACKOUT on; test pattern on (e1c) | 0 points | **537** (the pattern) | 0 |
| Test pattern on; a 2-point cue (e1d) | 537 | **2** (the cue) | 537 |
| Pin 0.5 and limit ±0.3 while the frame is composed (e1e) | reaches 0.40 | reaches **0.80** | 0.30 |
| Both pins 0.5 between laser `:a`'s and `:b`'s frames (e1f) | `:a` 0.80, `:b` 0.40 | **both 0.80** | both 0.40 |
| Decimate (25 kpps, 30 fps) added to the calibration; a 3,000-point cue at 25 kpps (e1h). [points sent, the record's `:points`] | [833 833] | **[1800 1800]**, the guard's | [833 833] |
| Decimate (25 kpps, 30 fps) removed from the calibration; the same cue (e1i). [points sent, `:points`, `:effect-points`] | [1800 1800 1800] | **[833 833 833]**, Decimate's | [1800 1800 nil] |

Identify, `{:p1 10000}` (`identify-on?`, `test_pattern.clj:70-78`):

| `:wall-ms` | Frame |
|---|---|
| 7000 | the pattern (lit half) |
| 7300 | `[]` (dark half) |
| 7500 | the pattern |
| 10000 | the show (its end time; `:identify-ended` may not have cleared it yet) |

**Invariants**
1. **A frame is a function of its snapshot.** `(projector-frame id s)` gives `=` frames for the same `s`, whatever app state does in between. So a keepalive resend repeats its tick's frame.
2. **No engine thread reads app state.** Zero `state/get-raw-state` calls on `idn-streaming-*` or `output-streaming-*` threads. Today there are 2 per frame (e2b, e4).
3. **One tick, one state.** Every laser's frame of tick N uses the calibration, zone, test pattern, Identify and BLACKOUT of the state the clock read for tick N, and so does its `:fit` record, which the card and LOW FPS read, and the meter rule (`:decimate-budget`). That is the read its cues and routing come from, so its halves are fitted to the box it is clipped to (Z1 S-Z1-7), unless the resolver failed and the tick kept an older routing (`routing-step`, `show_clock.clj:209-221`). Then the clip and the chain are the newer state's, the halves and the budget the older routing's.
4. **Still one read per tick.** `one-state-and-input-read-per-tick-test` stays `{:app-state 1 :input-store 1}`.
5. **References, not copies.** `engine-inputs` holds the state's own sub-maps (`identical?`) and computes nothing but `boolean` and a default.
6. **Safety is still last and fails closed.** An unreadable zone in the tick gives an empty frame and `[:safety id]`. A laser missing from the tick's `[:projectors]`, or a snapshot without `:engine-inputs`, gives nil and no error. No laser falls back to the full range because the tick didn't know it.
7. **The work stays where it runs.** Projector effects, the fit guard and safety run on each laser's own thread (O6). Only where their inputs come from changes. Drivers still see only frames (C-6).
8. **An edit is in force from the first tick that reads it**, with no restart (Z1 invariant 10, restated). That is at most T + d after its write, plus the engine's send and the DAC's frame.
9. **Nothing new in app state**, so no undo or dirty change.

## 8. Tests (named test vars + fixtures)

Write each new test first and watch it fail. The red results expected are the probe lines in §1.2.

- `laser-show.backend.multi-engine-test` (existing; fixture `setup-test-state` `:23-30`, installed at `:32`):
  - The `snapshot` fixture (`:34-40`) gains `:engine-inputs (me/engine-inputs (state/get-raw-state) (System/currentTimeMillis))`, and a `:wall-ms` option. Every other test there passes unchanged, because each sets state before it builds a snapshot.
  - NEW `engine-inputs-are-the-ticks-own-maps-test` (pure): the example in §7. `:projectors` and `:projector-effects` are `identical?` to state's, and `:projector-effects` to `rig/routing-inputs`'s. A state without `:projector-ui` or `:vibe-runtime` gives `:brightness 0.1`, `:calibrating nil`, `:identify nil`, `:blackout? false`.
  - NEW `calibration-edit-waits-for-the-next-tick-test`: e1g through `show-clock/tick!` (`events/dispatch!` stubbed, as `show_clock_test.clj:647`). A circle cue and a pin of 1.0, then a pin of 0.5 after the tick. The tick's frame is `=` to the frame made before the edit (reach 0.50), and the next tick's reaches 0.25.
  - NEW `decimate-edit-waits-for-the-next-tick-test`: e1h, with the file's `heavy` (`:627-630`) and `decimate` (`:713-714`) fixtures. Decimate goes into the laser's calibration after the tick. The tick's frame keeps the guard's 1,800 points, and its `:fit` record says `:points 1800`; the next tick's has 833 and 833. The provider's smoothed record follows the tick too.
  - NEW `decimate-removal-waits-for-the-next-tick-test`: e1i, with the same fixtures. Decimate is in the chain at the tick and removed after it. The tick's frame keeps [833 833 833] (points, `:points`, `:effect-points`); the next tick's is [1800 1800 nil].
  - NEW `safety-edit-waits-for-the-next-tick-test`: e1b. The tick's frame keeps 64 lit points and the halves of ±0.9. The next tick keeps 64 lit points inside ±0.3, with the halves `[-0.075 0 0.225]`.
  - NEW `blackout-and-test-pattern-wait-for-the-next-tick-test`: e1c and e1d. Pressing Identify after the tick leaves the tick's frame alone too.
  - NEW `edit-while-a-frame-is-made-leaves-it-alone-test`: e1e. `fit-guard/guard` is redefined to edit the pin and the limit, then call the original. The frame reaches 0.80.
  - NEW `lasers-of-one-tick-share-its-state-test`: e1f. Both reach 0.80.
  - NEW `engine-reads-no-app-state-test`: with `state/get-raw-state` redefined to throw, as `:206-207` does for the routing. Each of these is `=` to the frame made without the redefinition:
    - a show frame with calibration and a zone;
    - the test pattern;
    - Identify, lit and dark;
    - BLACKOUT on the pattern;
    - nothing playing (nil);
    - an unreadable zone (`[]`, with `[:safety :p]` reported).
  - NEW `laser-the-tick-doesnt-know-draws-nothing-test`: a snapshot whose `[:engine-inputs :projectors]` lacks `:p`, with the test pattern on for `:p`, gives nil and an empty `output-errors/snapshot`. A snapshot without `:engine-inputs` gives nil too.
  - NEW `same-tick-same-frame-test`: the provider runs twice on one published snapshot (a keepalive), with the pin edited and an Identify started in between. The two frames are `=`.
  - Changed: `failing-calibration-effect-skips-only-itself-test` (`:440-458`) and `skipped-calibration-effect-is-reported-test` (`:460-482`) pass the chain to `apply-projector-effects`, not app state. Their assertions don't change.
  - Changed: `identify-flashes-then-stops-test` (`:610-620`) sets the snapshot's `:wall-ms` instead of redefining `me/now-ms` (`:614`). It uses the table in §7.
  - Unchanged, and they pin the moved check: `stale-snapshot-sends-an-empty-frame-test` (`:385-397`), `no-snapshot-yet-reports-nothing-test` (`:399-403`), `idle-frame-provider-returns-nil-without-exceptions-test` (`:409-427`; its `:test-proj` isn't in state, and since the drivers its engine is built through `driver/projector-config`, so it gets nil before and after), `provider-records-what-it-produced-test` (`:678-707`), and every other Z1 safety and test-pattern test (`:499-608`).
  - Unchanged: Decimate's `decimate-in-a-calibration-chain-counts-on-the-card-test` (`:716-744`) and Q-D-3 B's `decimate-that-changes-nothing-still-meters-what-the-laser-draws-test` (`:752-768`). Each sets state before each snapshot it builds, so the fixture's `:engine-inputs` carries its chain and its safety limit, and the routing its budget.
- `laser-show.services.show-clock-test` (existing; fixture `:61-68`):
  - NEW `snapshot-carries-engine-inputs-test`, next to `snapshot-carries-routing-test` (`:262-273`). An idle tick and a playing tick both carry `(me/engine-inputs state wall-ms)` of the tick's state: the same maps (`identical?`), and a `:wall-ms` between the wall clock just before and just after the tick.
  - NEW `engines-read-no-app-state-test`. `with-engines` (`:197-214`) runs 3 IDN projectors with the default calibration (`events.handlers.projector/DEFAULT_PROJECTOR_EFFECTS`, `events/handlers/projector.clj:40-55`) and a zone, and one more projector on the recording driver (`recording-driver` registered under `:test/recording`, `:driver :test/recording`), so both thread names are covered. One cue plays, and 60 hand ticks run 5 ms apart, as in `renders-once-per-tick-test`. No `state/get-raw-state` call happens on an `idn-streaming-*` or `output-streaming-*` thread, and every engine sent frames (the recording driver's `:frames`). Today: 2 per frame (e2b).
  - Unchanged: `one-state-and-input-read-per-tick-test` (`:626-654`, invariant 4), `renders-once-per-tick-test`, `snapshot-shape-test` (`:231-253`), `routing-is-made-before-the-render-test` (`:352`), `decimate-put-in-a-calibration-chain-moves-the-warning-test` (`:601-614`, Q-D-2 B), `projector-effects-never-see-pulses-test` (`:694-703`).
- `laser-show.backend.output-lifecycle-test` (existing): the `stopping-every-engine` fixture (`:47-66`) also calls `show-time/reset-runtime!` in its `finally`. The `^:timing` `every-projector-sends-every-tick-once-test` (`:605-636`) leaves its last snapshot behind (`show-clock/stop!` doesn't reset it). With the stale check now in the provider, a later test that redefines `me/projector-frame` would otherwise get "Show clock stalled" instead of its frame. Today the redefinition replaces the check too. No assertion changes.
- `laser-show.ui-test.streaming-status-test` (`:221`) also redefines `me/projector-frame`; it runs with the app's clock, so its snapshots are fresh. No change.
- `laser-show.animation.show-time-test`: nothing. `publish!` doesn't look inside a snapshot (`show_time.clj:382-391`); only the docstring changes.
- `laser-show.animation.safety-test`, `laser-show.animation.test-pattern-test`, `laser-show.routing.rig-test`, Decimate's `laser-show.animation.effects.points-test` and `fit-guard-test/recount-counts-what-is-drawn-test`, `laser-show.output.driver-test` and `laser-show.backend.streaming-engine-test`: unchanged. They are pure or never see app state (E2 A).
- UI regression (nothing on screen changes, so no new UI test, per `.agents/skills/targeted-ui-testing/SKILL.md` §Test design guidance): `-n laser-show.ui-test.safety-editor-test`, `.setup-dialogs-test` (the brightness slider, and Decimate in the Add Effect menu), `.streaming-status-test`, `.rig-card-test` (the thumbnail and the load), `.preview-test`.

Run, in this order:
1. `clj -M:mac-arm:test multi-engine show-clock output-lifecycle engine-wire streaming-engine driver rig compose test-pattern safety fit-guard points`
2. `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.safety-editor-test`, then the same for each of the other four UI namespaces above
3. both full suites
4. the probes: `P=plans/2026-11-20-state-integrity; clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" -M:mac-arm:test:probe -d "$P/probes" -n probes.engine-snapshot-probes-test 2>&1 | grep PROBE`. After the change, e1 prints "follows the tick" in every case, e1e reaches 0.80, e1h keeps [1800 1800] and e1i [833 833 833] for the tick, e2b counts no engine reads and e4 counts 0. Then the zones probes (z1) and the output runtime probes (p8), which change in commit 2.

## 9. Commit plan

| # | Commit | Tests (red first) |
|---|---|---|
| 1 | `feat(output): each tick's snapshot carries what the engines take from app state, the lasers' calibration, safety zones, test pattern, Identify and BLACKOUT, and the tick's wall clock; nothing reads it yet` | `engine-inputs-are-the-ticks-own-maps-test`, `snapshot-carries-engine-inputs-test`; `one-state-and-input-read-per-tick-test` unchanged. Write a stub `engine-inputs` returning nil first, so they fail one by one rather than as a compile error |
| 2 | `feat(output): every frame a laser sends comes from one tick, its calibration, safety zone, test pattern and BLACKOUT the tick's, so an edit lands on every laser at the same tick, a frame never mixes two states, and no engine reads app state, whatever its driver` | the ten NEW engine tests and `engines-read-no-app-state-test`, red against the engine of commit 1 with the §1.2 numbers; then the two changed calibration tests, the changed Identify test, both fixtures, the docstrings, and the probe helpers of §4.1 |
| 3 | `plans: engine snapshot done (<sha>): as built, red-first results, probes e1–e4 before and after; Z1 §8 invariant 10 and Z3 §8 invariant 2 point here` | – |

## 10. Out of scope / alternatives considered

- **E1 B, E2 B and C, E3 B**: not chosen (2026-11-21); their rationale stays in the decisions.
- **Moving the projector effects to the clock thread**: rejected in show clock 02 §9 (O6), and still right. A heavy calibration would delay every laser. Only the inputs move.
- **Keepalive resends that re-read the zone**: rejected. A resend would again join a tick's content with a later zone, and a stall ends in dark frames at 250 ms anyway (§4.2).
- **Caching built calibration chains** (rgb-curves' LUTs, 45–48 µs per frame per laser, e3): later, as a performance item. It needs static effects told apart from modulated ones, and since Decimate it must keep a chain's per-frame count.
- **An `apply-safety` that trusts an already normalized zone**, which would save its second normalize (6–22 µs): rejected for the safety stage.
- **`apply-safety`'s cost with blocked areas** (652–662 µs per 1,000 points, e3) **and Decimate's in a calibration chain** (about 210–225 µs per frame on 1,800 points, e3; Q-Z3-7 is the same question for the guard): not this plan.
- **The routing recomputed on every calibration edit** since Q-D-2 B (28–189 µs on the clock thread per change, e3; 34–213 µs after this plan), because `[:chains :projector-effects]` is one of its keys, so every corner-pin drag step recomputes it: **follow-up work**, a later performance item (the user, 2026-11-21), not this plan; it changes no frame. A fix would key the routing on each laser's Decimate budget rather than on the whole chains map.
- **Device-paced add-on drivers on hardware** (follow-up): the frames are one tick's by construction (the provider is the only call), but the latency of §4.2 for a driver that waits on `:ready?` is *unverified* until a real device-paced add-on is run on hardware (the user, 2026-11-21; the output drivers' D5 is the natural place).
- **The dead preview readers** (`frame_service.clj:71-91`) **and the 2-arity `generate-frames-by-zone`** (`:469-473`): out of scope. No frame reads them, and deleting changes no frame. The reader and its private helper are a two-test cleanup for a later commit (Z3 §11 left them too). The 2-arity has about 35 test call sites, and the clock's read count already guards against it.
- **`multi-engine/create-engines`** (`:290-305`) reads app state, but no caller in `src` uses it (only `multi_engine_test.clj:116`, `:187`), and it builds engines rather than frames.
- **The output status thread's read** (`output_runtime.clj:328`): it compares the status with app state every 250 ms and dispatches only on a change; it is not a frame input.
- **A dev-mode warning when an engine thread reads app state**: the tests in §8 pin it. A runtime check would add a thread-name test to every read.
- **A laser just turned off, whose engine is still being stopped**, keeps drawing the test pattern until the lifecycle thread stops it, as today (the tick still lists it in `[:projectors]`).
- **Not here**: MIDI, OSC and Link callbacks that read live state; the direct `swap-state!` writers (`plans/2026-11-20-state-integrity/02-direct-state-writes.md`); the clipboard (`plans/2026-11-13-keyboard-focus-window-lifecycle/04-phase4-clipboard.md`).
- **The sibling plans**: no dependency either way. The engines write no app state and, after this plan, read none. A calibration pasted into a laser's chain lands in `[:chains :projector-effects]` like any other edit, so the next tick carries it, however the clipboard plan has the paste written. The new tests set state as every engine test does today (`state/assoc-in-state!`, `multi_engine_test.clj:200`). If plan 02 fences that off for tests too, they follow whatever 02 gives tests.

## 11. As built (2026-11-21, `09b6db7`, `dba29bb`)

Built on branch `engine-snapshot` from `979c6c8`, in §9's order: commit 1 `09b6db7` (the snapshot carries `:engine-inputs`), commit 2 `dba29bb` (the engines read only the snapshot), commit 3 this note. JDK 25.0.5, macOS arm64.

### What changed per namespace

- `laser-show.backend.multi-engine`: NEW public `engine-inputs [state wall-ms]` (§7's map, the state's own `:projectors` and `[:chains :projector-effects]`). `apply-projector-effects [frame projector-id items timing-ctx]` reads no state. `guard-safety`, `test-pattern-mode`, `test-pattern-frame` and `show-frame` take `inputs`; `now-ms` is gone (E1 A). `projector-frame` keeps `[projector-id snapshot]`, draws nothing for a laser missing from `[:engine-inputs :projectors]` or a snapshot without `:engine-inputs`, and has no stale check. That check moved, unchanged, into `create-projector-frame-provider`. The recount and meter rule are as they were. The namespace docstring says no engine reads app state. `state.core` is still required, for `create-engines` (§10).
- `laser-show.services.show-clock`: `tick!`'s snapshot gains `:engine-inputs (multi-engine/engine-inputs state (System/currentTimeMillis))` from the tick's one read; requires `multi-engine` (no cycle); docstring step 4.
- `laser-show.animation.show-time`: the `!snapshot` docstring lists `:engine-inputs`.
- `laser-show.backend.output-runtime`: the `fingerprint` docstring ("the routing, safety and calibration come with each tick").
- Probes: `zones_probes_test.clj` (`snapshot`) and `vibe_probes_test.clj` (`zone-snapshot`) carry `:engine-inputs`; `output_probes_test.clj` p8 passes the chain. z1, v19–v22 and p8 were run after the change and draw as before. This plan's probe labels for e1e and e1i now say what the before-value would have been, since the printed value is now the tick's.
- Not changed, as §4.1 says: `streaming_engine.clj`, `routing/*`, `animation/safety.clj`, `test_pattern.clj`, `fit_guard.clj`, `effects/points.clj`, `frame_service.clj`, `output/*`, `idn/driver.clj`, views and handlers.

### Deviations (the plan's decisions kept; its text fixed here)

1. Private helper `chain-of [inputs projector-id]` in `multi-engine`, to read a laser's chain from the inputs in one place (the pattern and the show frame).
2. `test-pattern-mode` also checks that `:wall-ms` is there before it compares it with `until`, so an inputs map without a wall clock shows no Identify instead of throwing. `engine-inputs` always sets it.
3. The multi-engine `snapshot` fixture builds the routing and `:engine-inputs` from **one** read of app state (§8 sketched two reads); it also takes a `:wall-ms` option.
4. The show-clock and show-time docstrings went into commit 1 with the field they describe; `multi-engine`'s and `fingerprint`'s are in commit 2.
5. The new engine tests sit in a section at the end of `multi_engine_test.clj`, because they use the file's `heavy`, `decimate` and `identity-curves`, which are defined late in the file. They add small fixtures of their own (`line-to-08`, `pin`, `box`, `reach`, `circle-points`, `laser`).
6. `show_clock_test/with-engines` gains a 3-arity, `(with-engines n {:idn {…} :others {id projector}} f)`, and returns the engines so a test can read their stats. Existing callers are unchanged.
7. In `engine-reads-no-app-state-test` the test pattern through a zone with a blocked area has 705 points, not 537 (the safety layer also outlines the blocked area), so the test checks for "more than 300".

### Tests

Added (all red first, against the engine of the commit before):

| Test | Red result |
|---|---|
| `multi-engine-test/engine-inputs-are-the-ticks-own-maps-test` | 6 failures against a stub returning nil |
| `show-clock-test/snapshot-carries-engine-inputs-test` | 8 failures (4 per tick, idle and playing): no `:engine-inputs` |
| `calibration-edit-waits-for-the-next-tick-test` (e1g) | the tick's frame followed the edit (circle 0.50 → 0.25) |
| `decimate-edit-waits-for-the-next-tick-test` (e1h) | 3: the tick's frame, its record and the provider's smoothed record were [833 833], not [1800 1800] |
| `decimate-removal-waits-for-the-next-tick-test` (e1i, a test of its own as the user asked) | [1800 1800 1800], not [833 833 833] |
| `safety-edit-waits-for-the-next-tick-test` (e1b) | 0 of 64 lit |
| `blackout-and-test-pattern-wait-for-the-next-tick-test` (e1c, e1d, Identify) | 3: BLACKOUT gave 0 points, not the pattern's 537; the pattern replaced the tick's cue; Identify at `:wall-ms` 7000 didn't flash (the engine read its own clock) |
| `edit-while-a-frame-is-made-leaves-it-alone-test` (e1e) | reach 0.40, not 0.80 |
| `lasers-of-one-tick-share-its-state-test` (e1f) | `:b` 0.40 |
| `engine-reads-no-app-state-test` | 7: all six frames threw "test: an engine read app state", and so `[:safety :p]` wasn't reported (plus one failure that was the test's own 537, fixed per deviation 7) |
| `laser-the-tick-doesnt-know-draws-nothing-test` | 2: both snapshots drew the test pattern from app state |
| `same-tick-same-frame-test` | the resend was the Identify pattern, not the tick's frame |
| `show-clock-test/engines-read-no-app-state-test` | `{"idn-streaming-p1" 54, "idn-streaming-p2" 54, "idn-streaming-p3" 54, "output-streaming-p4" 54}` reads in the 60 hand ticks: 2 per frame |

Commit 2's red run: 11 tests, 24 failures, 0 errors (`blackout-…` run on its own: 3 more). Commit 1's: 14 failures in its 2 tests.

Existing tests changed, with the reason:
- `multi-engine-test`'s `snapshot` fixture: carries `:engine-inputs` (deviation 3). No other test there changed.
- `failing-calibration-effect-skips-only-itself-test`, `skipped-calibration-effect-is-reported-test`: pass the chain to `apply-projector-effects` instead of writing it to app state. Same assertions.
- `identify-flashes-then-stops-test`: sets `:wall-ms` instead of redefining `me/now-ms`. Same table (7000, 7300, 7500, 10000).
- `output-lifecycle-test`'s `stopping-every-engine` fixture: also `show-time/reset-runtime!` in its `finally` (§8). No assertion changed.
- `show-clock-test/with-engines`: the 3-arity (deviation 6).

### Probes e1–e4, before and after

Before: one run at `979c6c8` (code identical to `fe7d554`), load 1.3–2.3 by `uptime`. e1a–e1i gave §1.2's results exactly. e2a d = 22.8 / 120.0 / 210.9 µs (idle / 1 cue / 4 cues). e2b: 720 reads in 2 s (240 per engine), x₁ 66.8 µs, x₂ 152.8 µs, s 1.27–1.32 ms. e4: 2 / 1 / 2 / 1. e3: the stand-in map 0.67 µs.

After: three runs at `dba29bb`, load 2.1–3.0 by `uptime` before and after the runs (e3's own reading 2.1, 2.2, 3.0). e1 and e4 were the same in all three runs:

| Probe | Before | After (every run) |
|---|---|---|
| e1a pin edited after the tick | 0.80 → 0.40 | 0.80 → **0.80**, follows the tick |
| e1b limit narrowed after the tick | 64 → 0 lit | 64 → **64** lit; the next tick fits `[-0.08 0.00 0.22]` and keeps 64 |
| e1c BLACKOUT after the tick | 537 → 0 | 537 → **537** |
| e1d pattern on after the tick | 2 → 537 | 2 → **2** |
| e1e edit inside the guard | 0.40 | **0.80** (all new: 0.30) |
| e1f two lasers | 0.80 / 0.40 | **0.80 / 0.80** |
| e1g through `tick!` | 0.50 → 0.25 | 0.50 → **0.50** |
| e1h Decimate added | [1800 1800] → [833 833] | [1800 1800] → **[1800 1800]**; the next tick [833 833] |
| e1i Decimate removed | [833 833 833] → [1800 1800 1800] | [833 833 833] → **[833 833 833]**; the next tick [1800 1800 nil] |
| e2b engine reads in 2 s | 720 | **0** in each run |
| e4 reads per frame (cue / idle / pattern / pattern in BLACKOUT) | 2 / 1 / 2 / 1 | **0 / 0 / 0 / 0** |

After, the timings: e2a d = 22.5–26.0 µs idle, 108.8–127.7 µs for 1 cue, 184.7–221.5 µs for 4 cues (medians; within the noise of §1.3). e2b s = 0.35–1.44 ms, which isn't comparable with before: before, the probe's own wrapper of `get-raw-state` ran on the engine threads twice per frame, and after it never does. e3: the real `engine-inputs` costs 0.46–0.50 µs per tick. The whole engine frame costs 803–846 µs with two blocked areas and 135–153 µs without a zone. The routing costs 34–213 µs per change for 1–8 lasers.

### Suites

- Targeted unit tests (`multi-engine show-clock output-lifecycle engine-wire streaming-engine driver rig compose test-pattern safety fit-guard points show-time decimate output-runtime`): 511 tests, 508,565 assertions, 0 failures. `:test-timing` for `output-lifecycle multi-engine show-clock streaming-engine`: 4 tests, 0 failures.
- UI namespaces, one at a time: `streaming-status-test` 10/53, `rig-card-test` 6/25, `rig-strip-test` 13/44, `projector-drivers-test` 18/99, `safety-editor-test` 8/13, `projectors-layout-test` 5/60, `setup-dialogs-test` 16/48, `perform-test` 23/154, `preview-test` 10/23 (tests/assertions), all 0 failures. `rig-card-test`'s log shows a "Show clock stalled" for laser `:a` after a `with-clock` block ends while its engine still runs. It goes through the same check and the same `[:provider id]` report as before, which now run in the provider instead of `projector-frame`.
- Full unit suite (`clj -M:mac-arm:test`): 1,852 tests, 570,377 assertions, 0 failures, 0 errors (load 2.3–3.2).
- Full headless UI suite: 273 tests, 1,461 assertions, 0 failures, 0 errors (load 2.2–2.7).
- kondo (`--lint src test --parallel --fail-level error`): 0 errors, 17 warnings, none in a file this plan touched.
- No timing failure in any run, so nothing had to be rerun.

### Live check (app-repl and plorer)

A headless REPL with `laser-show.rig-file` and `laser-show.addons-dir` set to temp folders, `start-test!`, the real show clock thread (`show-clock/start!`), and the test LSPS receiver on loopback. Nothing scanned the network. An LSPS laser was added by `:projectors/add-manual` and streamed with the Stream button. Cue A1 (a circle, 65 points on the wire) was given a rotation, so every tick's frame differs. Every published snapshot was recorded. Each frame the receiver reassembled was compared with the wire encoding of `(projector-frame pid s)` (after `sanitize-frame`) for every recorded snapshot `s`:

| Phase | Frames received | Matched one tick | Ticks in order |
|---|---|---|---|
| A: rotating circle | 92 (91 distinct) | 92 | yes |
| B: corner pin (`:chain/update-param`) and safety zone (`:projectors/set-safety`, ±1.0 ↔ ±0.5) edited every 7 ms, 140 edits | 113 (113 distinct; 96 of 65 points, 17 of 67 with the safety crossings) | 113 | yes |
| C: Identify (button, then its confirm dialog) | 235: 90 pattern (537 points), 90 dark, 55 show | 235 | yes |
| D: test pattern on, BLACKOUT on and off, pattern off | 156: 76 pattern, 40 dark, 40 show | 156 | yes |
| E: Decimate (1 kpps, 30 fps: 33 points) added, then removed (`:chain/set-items`, the list's delete) | 149: 63 of 33 points, 86 of 65 | 149 | yes |

So 0 frames out of 745 failed to match a single tick, while the pin and the zone were changing every few milliseconds. Identify's halves were exactly 15 frames each: the flash is timed by the tick. Engine stats after Decimate went in: `:points 33 :effect-points 33 :low? false`; after it came out, `:points 65` and no `:effect-points`. Over the whole session there were 11,749 packets and 11,251 complete frames, 0 LSPS rule violations (`lsps-receiver/violations`), 0 skipped frames, about 60.7 fps sent, and no output errors at the end. Screenshots are in `test-results/engine-snapshot/`: `es-1-streaming-pin-zone.png`, `es-2a-identify-confirm.png`, `es-2b-identify-flashing.png`, `es-3a-test-pattern.png`, `es-3b-blackout-on-pattern.png`, `es-4a-decimate-added.png` (the card's thumbnail and "−49% pts"), `es-4b-decimate-removed.png`. Not checked: a real DAC, and a device-paced driver (§10).

### The user's answers (2026-11-21)

1. Device-paced add-on drivers: accepted as *unverified*, to be checked on hardware (§4.2, §10).
2. `decimate-removal-waits-for-the-next-tick-test` stays a test of its own.
3. The routing recomputed on every corner-pin drag step since Q-D-2 B: a later performance item (§10, follow-up work).


---

## Decisions

> **DECISION E1 — What clock times Identify's flash?** *(answered A, 2026-11-21)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★ (chosen)** | The tick's wall clock, `:wall-ms` in `engine-inputs`, read when the snapshot is built. `me/now-ms` goes | A frame is a function of its snapshot alone; a keepalive repeats it; every laser of a tick decides on the same instant; `projector-frame` keeps `[projector-id snapshot]`, so no call site changes; the tests set `:wall-ms` instead of redefining a var | The flash's edges land on tick boundaries, at most 16.7 ms late on a 250 ms half |
> | B | The engine's wall clock per frame, as today, passed into `projector-frame` as an argument | Sub-tick edges | `projector-frame` needs a third argument at its 36 test call sites and the 9 redefinitions, or a 2-arity that reads the clock; a resend can differ from its tick |

> **DECISION E2 — Where a laser's safety zone is normalized** *(answered A, 2026-11-21)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★ (chosen)** | Per frame, as today, from the tick's `[:projectors]` (`engine-inputs`) | The smallest change. A narrowing never waits on the resolver. Fail-closed stays exactly where Z1 put it. `rig.clj` is untouched | Two normalizes per frame stay (2 × 6–22 µs with a zone, e3) |
> | B | Per change, in the routing: `halves` (`rig.clj:395-402`) already normalizes, so `:outputs pid` keeps `:safety` (or `:safety-error`, a message string, so the routing stays a value) | One normalize per frame fewer. The halves and the clip are one object | A resolver failure keeps the cached routing (`routing-step`, `show_clock.clj:209-221`), which would freeze every zone until it recovers, so `routing-step` must refresh the zones from the tick's state then; `rig-test/unreadable-safety-zone-draws-no-halves-test` (`rig_test.clj:536-549`) and `rig-test/outputs-carry-scan-rate-fit-and-region-fits-test` (`:489-503`) change |
> | C | Per change, in its own memo on `(:projectors state)`'s identity, kept in the clock's runtime like `:routing-cache` | One normalize per frame fewer, independent of the resolver | A second cache for 6–22 µs per frame, about 0.04–0.13 % of a frame period on each engine thread |

> **DECISION E3 — Should the preview get the same consistency?** *(answered A, 2026-11-21)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★ (chosen)** | Nothing more. The preview cells already draw one tick from the snapshot (§1.4). The thumbnail's frame becomes one tick's through the engine. Its grey outline stays the card's current limit (`subs/rig-cards`, `subs.clj:1362-1414`) | No UI change. While you drag the limit, the outline shows where you are dragging it | For up to a tick, the outline can lead the frame drawn inside it (display only, at most 10 redraws a second, `preview.clj:225`) |
> | B | The provider also records the zone it clipped with (`show-time/record-output!` gains `:limit`, `show_time.clj:371-375`), and the thumbnail draws that outline | Frame and outline are always the same tick | Touches the card and its tests (`rig-card-test`); the outline stops following a drag until the next tick reaches the engine |
