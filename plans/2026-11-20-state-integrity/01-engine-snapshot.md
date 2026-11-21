# Every frame comes from one snapshot

**Written:** 2026-11-20   **Written for commit:** `3229c95` (`master`)
**Status:** Proposed. E1–E3 open (§Decisions, ★ = recommended)   **Last verified against:** `cc9e000` (every citation re-checked after Decimate landed, `e7b93e4`..`cc9e000`; probes e1–e4 run three times there, as at `3229c95` before it, 2026-11-20, JDK 25.0.5, macOS arm64, load average 4–7; §1.5)
**Supersedes / Superseded by:** supersedes the live reads Z1 put into `projector-frame` (`plans/2026-11-19-zones/03-phase-z1-safety.md` §4, built in `df65be4`, `fb801f2`, `1b14cc4`) and the "from the next frame" of its §8 invariant 10; Z3's "Only the projector effects and Z1's safety settings are still read live, as today" (`05-phase-z3-routing-core.md` §8 invariant 2). Takes over review 04 #23, the engines' part (`plans/2026-11-07-improvement-review/04-output-pipeline.md:174-179`), which the show clock's Phase 4 left as "Observed, not changed" (its §10, `git show 2d1f813~1:plans/2026-11-17-show-clock/04-phase4-one-timing-context.md`), and MR7's step "Pass `raw-state` into `apply-projector-effects`" (`04-output-pipeline.md:347`). Superseded by: none   **Related findings:** review 04 #23, MR7; show clock S-5 (its `00-overview.md` §2, `git show 2d1f813~1:plans/2026-11-17-show-clock/00-overview.md`); zones-needs T-12 ("use the same tick everywhere", `plans/2026-11-19-zones-needs/03-conflicts.md:118-126`); `docs/CLJFX_STATE_MANAGEMENT.md` Best Practice 1 (`:576-594`)
**Needs:** nothing. No dependency on the sibling plans (§10).   **Size:** ~1 d (1–1.5 d with both full suites, the probes in three plan folders and the as-built note)

The show clock reads app state once per tick and publishes a snapshot. Each laser's engine then takes the tick's cues and routing from the snapshot, but reads its calibration, safety zone, test pattern, Identify and BLACKOUT from app state as it is when it gets to them, once or twice per frame. So one frame can join tick N's show with a later calibration or zone, one frame can even take its zone and its calibration from two different states, and two lasers can disagree about the same tick. This plan puts those inputs in the snapshot, so a frame is a function of its snapshot alone and no engine thread reads app state.

Paths are under `src/laser_show/` unless they start with `test/`, `plans/` or `docs/`. **NEW** marks anything that doesn't exist yet.

## After this plan

- An edit to a laser's calibration, safety zone, test pattern, Identify or BLACKOUT lands on every laser at the same tick. No frame is one tick's show through a later tick's calibration or zone.
- The card's fps and note and LOW FPS, which since Decimate count what a laser's calibration left, describe the same tick as the frame.
- A laser's halves are always fitted to the zone they are clipped to.
- No engine thread reads app state. The clock's one read per tick feeds the cues, the routing and every frame.
- A keepalive resend repeats the tick's frame instead of making a new one from newer state.
- The engine's frame can be unit-tested from a snapshot alone: `(#'me/projector-frame :p snapshot)`, with no app atom.

## 1. Current behaviour (file:line)

### 1.1 What one frame reads, and when

The provider (`backend/multi_engine.clj:218-250`) calls `(projector-frame projector-id (show-time/latest))` (`:240`) on the engine's own thread (`idn-streaming-<id>`, `backend/streaming_engine.clj:412-416`).

| What the frame takes | From | Where it is read | When |
|---|---|---|---|
| The rendered cues, the routing, `:playing?`, `:projector-ctx`, `{:tick :at-ns}` | the snapshot | `multi_engine.clj:186`, `:198-199`, `:209`, `:213`, `stamp` `:104-107` | the tick's one read of app state, `services/show_clock.clj:235` |
| Whether the test pattern shows: `[:projector-ui :identify id]`, `[:projector-ui :calibrating-projector-id]` | app state | `:177-178`, `test-pattern-mode` `:131-143` | read #1, when the engine starts the frame |
| BLACKOUT, `[:vibe-runtime :blackout?]` | app state | `:181` | read #1 |
| The pattern's brightness, `[:projector-ui :calibration-brightness]` | app state | `test-pattern-frame` `:150` | read #1 |
| The safety zone, `[:projectors id :safety]` | app state | `guard-safety` `:118`, given read #1 through `test-pattern-frame` (`:151`) or `show-frame` (`:212`) | read #1 |
| The calibration, `[:chains :projector-effects id :items]` | app state | `apply-projector-effects` `:85-86` | **read #2**, after composition and the fit guard (`:199-213`) |
| The frame's `:fit` record, which the card's fps, its note and LOW FPS read | the calibration's output | `show-frame` keeps it in a local `volatile!` and recounts the guard's record with its count (`:211-214`, `fit-guard/recount` `animation/fit_guard.clj:211-227`), since Decimate (`d6fbb15`) | read #2's chain |
| Identify's wall clock | `System/currentTimeMillis` | `now-ms` `:126-129`, called at `:178` | at read #1 |
| The stale check | `System/nanoTime` | `:174-176` (`stalled` `:98-102`, `show-time/stale?` `animation/show_time.clj:401-404`) | at the provider's call |

These two reads are the only app-state reads on an engine thread: no other namespace on the frame path requires `state.core` (`routing/compose.clj`, `animation/fit_guard.clj`, `animation/safety.clj`, `animation/test_pattern.clj`, `animation/effects.clj` and `effects/*.clj`, Decimate's `effects/points.clj` included, `idn/frame_rules.clj`, `backend/streaming_engine.clj`; checked with grep).

So up to three states can go into one frame: the tick's (cues, routing, the fitted halves), read #1's (pattern, BLACKOUT, zone) and read #2's (calibration, and with it the record's count).

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
| e4 | App-state reads per frame | a cue playing **2**; nothing playing 1; the test pattern 2; the pattern during BLACKOUT 1 |

### 1.3 Timing

| What | Where | Value |
|---|---|---|
| Tick period T | `TICK_HZ` 60 (`services/show_clock.clj:57`); `run-clock` parks to each deadline and skips missed ticks (`:273-288`) | 16.67 ms |
| An engine's frame period while the clock runs | it sends each new tick's frame as soon as `publish!` wakes it (`streaming_engine.clj:361-367`, `show-time/frame-signal` `show_time.clj:440-448`) | T |
| …with no tick for a while | the newest frame again after `KEEPALIVE_INTERVALS` (3) intervals (`streaming_engine.clj:68-73`, `:337`); a new provider call, so today it re-reads app state | 50 ms |
| …after `STALE_MS` without a tick | empty frames, "Show clock stalled" (`show_time.clj:334-337`, `multi_engine.clj:174-176`) | 250 ms |
| …while no clock runs (`start-test!`) | paced at `DEFAULT_FPS` (`streaming_engine.clj:31`, `:335`, `:367`); `show-time/latest` is nil, so nothing is drawn (`multi_engine.clj:173`) | 16.67 ms |
| The DAC | finishes the frame it is drawing first (Stream §3.3); a frame lasts its samples at the scan rate (`wire-duration-us`, `streaming_engine.clj:174-187`) | 16.7 ms at 418 samples, 40 ms at 1,000 points, at 25 kpps (`plans/2026-11-16-output-runtime/05-phase5-frame-timing.md` §7) |

Measured (e2, three runs at `cc9e000`, medians with p95 in brackets):

| | Idle | 1 cue, 1 laser | 4 cues + a global effect, 3 lasers |
|---|---|---|---|
| d: the tick's read (`show_clock.clj:235`) → its `:at-ns` (`:250`) | 16–19 µs (29–95) | 101–113 µs (263–468) | 167–188 µs (362–1,067), max 3.8–9.3 ms |

| 3 engines on the fake DAC, the clock thread running, one cue, the default calibration | |
|---|---|
| x₁: publish → read #1 (`:177`) | 54–71 µs (138–897), max 0.5–8.1 ms |
| x₂: publish → read #2 (`:85`) | 127–169 µs (485–1,637), max 1.8–8.2 ms |
| s: publish → the frame leaves the engine (`:tick-to-send-us`, `streaming_engine.clj:386-388`) | 0.45–0.60 ms |
| Reads on the engine threads | 720–726 in 2 s, 240–242 per engine: 2 per frame |

So today a frame's calibration is read up to 8 ms after its tick was published, and the engines of one tick read at different moments.

### 1.4 The preview and the frame service

- **Preview cells** draw `show-time/latest` on each screen refresh (`views/components/clock_view.clj:1-11`, `:31-51`). A group cell shows `:ref-frames` and a laser cell `:projector-frames` of the snapshot's `:preview` delay (`views/components/preview.clj:140-173`), which is `frame-service/preview-data` of the tick's cues and routing (`services/show_clock.clj:256`, `services/frame_service.clj:434-461`). A laser cell runs `compose/projector-frame` and `fit-guard/guard` only (`frame_service.clj:452-456`): it stops before calibration and safety (Z3 §7). **Already one tick; no app state.** It doesn't share `multi-engine/projector-frame`. So it doesn't show a Decimate in the laser's calibration either, as for Corner Pin (`plans/2026-11-19-zones/12-decimate-effect.md` §6 "Found on the way").
- **The rig card's thumbnail** draws `show-time/output-frame` (`preview.clj:199-229`, `show_time.clj:366-369`), what the provider produced, after calibration and safety (`multi_engine.clj:244`). It mixes whatever the engine mixed. Its grey outline is the card's `:limit`, from `subs/rig-cards` (`subs.clj:841-872`) on the FX thread.
- `frame-service/get-preview-zone-filter` (`frame_service.clj:67-73`) reads app state on the calling thread and has **no caller in `src`**; only `frame_service_test.clj:36-46` calls it. The UI reads that path through its sub (`subs.clj:155`). `matches-preview-zone?` (`:75-87`) is private and test-only. Z3 left both (05 §11).
- The 2-arity `generate-frames-by-zone` (`frame_service.clj:365-369`) reads app state, the input store and `show-time/global-clock`. In `src` only the clock renders, with the 3-arity (`show_clock.clj:76`); about 30 test call sites use the 2-arity. `one-state-and-input-read-per-tick-test` (`test/laser_show/services/show_clock_test.clj:519-547`) would fail if a tick called it.

### 1.5 What was run and what was read

- **Run**: `plans/2026-11-20-state-integrity/probes/engine_snapshot_probes_test.clj` (NEW, this plan), e1–e4, three times at `3229c95` and three times at `cc9e000`, with e1h and an e3 line added there for Decimate. Every result in §1.2, every number in §1.3's measured tables and in §4.3, which quote the `cc9e000` runs. e1 and e4 are deterministic and gave the same results in all six runs (e1h in the three at `cc9e000`); the timings vary with load (4–7 here) and are quoted as ranges. The `3229c95` runs gave timings of the same order, with the same spread from run to run (the tick-to-send, for one, 0.68–0.79 ms there against 0.45–0.60 here).
- **Read**: the call paths and line numbers, re-checked at `cc9e000`; the history in §2.2 (the plan docs, `git show 70f7db8:src/laser_show/backend/multi_engine.clj`, `git show 2d1f813~1:plans/2026-11-17-show-clock/…`); the latency formulas in §4.2 (from the code, with e2's numbers put in); the stall case in §4.2; the preview's data flow (§1.4). *Unverified*: anything on a real DAC.

## 2. Root cause, and why the engines read live

### 2.1 Root cause

The snapshot was made to carry what the render produces (show clock Phase 2). The engines kept reading their per-laser settings from app state, because that is where they had always read them. Z3 moved the routing into the snapshot and stopped there. Nothing checks for app-state reads off the clock thread: `one-state-and-input-read-per-tick-test` counts only the reads on the clock's own thread (`show_clock_test.clj:530-535`). Reading the context from another thread is safe (`docs/CLJFX_STATE_MANAGEMENT.md:185-193`); the problem is consistency, as Best Practice 1 says of a worker that reads state itself: "State might have changed!" (`:587-593`).

### 2.2 Was reading live deliberate?

No plan gives a reason to read these settings live. The reads were inherited and kept to stay in scope:

| Where it came from | What it says |
|---|---|
| Before Z1 (`70f7db8`) | the engine already read `[:projectors id :zone-groups]` (`multi_engine.clj:132` there) and the projector effects (`:101` there) live |
| Output runtime Phase 4 (`04-phase4-engine-ownership.md:54`) | "Zone groups and names are read live by the provider, so changing them doesn't restart anything." The point is **no restart**. The same phase moved the lifecycle's reads to the handler, citing Best Practice 1 (`:58`) |
| Show clock Phase 2 (§9, at `2d1f813~1`) | "Moving projector effects to the clock thread… they stay on each projector's thread (O6)." That is about where the effects **run**; this plan keeps them there |
| Show clock Phase 4 (§10) | "Each projector's engine still reads app state on its own thread… Observed, not changed" |
| Z1 (`03-phase-z1-safety.md` §1 "Engines" row, §4 sketch, §5 `:187`) | follows the existing reads: "Projector settings other than the fingerprint… are read live, with no restart"; "Safety isn't part of the engine fingerprint, so an edit applies at the next frame with no restart". The sketch reads `(state/get-raw-state)` with no reason given |
| Z3 (`05-phase-z3-routing-core.md` S-2, §8 invariant 2) | moves the routing into the snapshot because "reading those live would let an engine and the preview disagree for a tick", and keeps "the projector effects and Z1's safety settings… read live, as today" |
| `output-runtime/fingerprint` (`backend/output_runtime.clj:35-40`) | "safety is read live": again about not restarting |

The candidate reasons, one by one:

| Reason | Finding | This plan |
|---|---|---|
| A safety edit must apply at once on a live laser (D-28) | D-28 says a narrowing applies without asking ("at once", spec §10, `01-model-and-routing-spec.md:227`); Z1 invariant 10 says "from the next frame, with no engine restart". Neither is a sub-tick latency | the edit applies from the first tick that reads it, still without a restart. The worst case grows by d + x ≈ 0.2–0.3 ms (§4.2) |
| Identify's flash timing | the flash is 250 ms lit, 250 ms dark (`animation/test_pattern.clj:16-18`, `:70-78`); the engine reads the wall clock per frame | the tick's wall clock goes in the snapshot (E1 A): the edges move by less than one tick (16.7 ms) on a 250 ms half |
| The test pattern shows with nothing playing | the clock publishes a snapshot every tick whether or not anything plays (`show_clock.clj:113-114`, `:246-256`; `snapshot-shape-test`, `show_clock_test.clj:230-238`) | the pattern's inputs ride on every snapshot, idle ones too |
| The stale check | it must compare the snapshot with the engine's clock: it is about delivery, not app state | it moves into the provider, unchanged (§4.1) |
| No restart for settings outside the fingerprint | the snapshot restarts nothing either | `fingerprint` and `wanted` are unchanged |

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| The tick's one read of app state (`services/show_clock.clj:235`) | NEW `engine-inputs` takes its slices from it, so the tick still reads app state once |
| `rig/routing-inputs` (`routing/rig.clj:368-377`) | the model: slices of the tick's state, by reference, made by the namespace that consumes them |
| The snapshot and `publish!` (`animation/show_time.clj:341-348`, `:371-380`) | carry `:engine-inputs` beside `:routing` |
| `guard-safety` (`multi_engine.clj:109-124`), `test-pattern-mode` (`:131-143`), `test-pattern-frame` (`:145-156`), `projector-frame` (`:160-186`), `show-frame` (`:188-216`), `apply-projector-effects` (`:78-96`) | the same logic and the same failure handling, reading the snapshot instead of app state |
| `show-frame`'s recount of the guard's record (`:211-214`, `fit-guard/recount` `animation/fit_guard.clj:211-227`, Decimate) | kept as it is: it counts what the tick's chain left |
| `stalled` (`:98-102`), `show-time/stale?` (`show_time.clj:401-404`) | the stale check, moved into the provider |
| `engine-routes-from-the-snapshot-not-app-state-test` (`test/laser_show/backend/multi_engine_test.clj:197-210`, its redefined readers at `:205-208`) | the pattern for the new tests: build a tick, edit app state, the frame follows the tick; make a reader throw |
| The `snapshot` fixture (`multi_engine_test.clj:34-40`) | gains `:engine-inputs`, so every existing engine test keeps setting state first and then building a snapshot |
| `with-engines` (`show_clock_test.clj:196-213`), `renders-once-per-tick-test`'s hand ticks (`:215-228`) | the test that counts app-state reads on the engine threads |
| The provider's per-call `show-time/latest` (`multi_engine.clj:240`) | already the `docs/STALE_CLOSURES.md` pattern (deref at call time, `:85-125`); kept |

## 4. Changes per namespace

### 4.1 Namespaces

- `laser-show.backend.multi-engine` (`backend/multi_engine.clj`):
  - **NEW `engine-inputs [state wall-ms]`**, public and pure: what the engines take from app state for one tick, as references (§7). The show clock calls it once per tick with the tick's state.
  - `apply-projector-effects` (`:78-96`) takes the chain: `[frame projector-id items timing-ctx]`. It reads no state. The `try`, `ok!`, `report-skips!` and `report!` stay as they are.
  - `guard-safety` (`:109-124`) takes `[inputs projector-id snapshot f]` and reads `(get-in inputs [:projectors projector-id :safety])`. Normalizing stays inside its `try`, so an unreadable zone still fails closed and names itself under `[:safety id]` (E2 A).
  - `now-ms` (`:126-129`) goes (E1 A). `test-pattern-mode` (`:131-143`) takes `[inputs projector-id]` and uses `:identify`, `:calibrating` and `:wall-ms`.
  - `test-pattern-frame` (`:145-156`) takes `[inputs projector-id snapshot]`: `:brightness` and the laser's chain come from `inputs`.
  - `show-frame` (`:188-216`) takes `[inputs projector-id snapshot]` and gives the laser's chain to `apply-projector-effects`. Its Decimate recount stays as it is: the chain's output still goes into the local `volatile!` (`:211`, `:213`), and the guard's record is recounted with it (`:214`).
  - `projector-frame` (`:160-186`) keeps `[projector-id snapshot]`, so the ~35 test call sites and the redefinitions in `output_lifecycle_test.clj` (`:382`, `:408`, `:434`, `:454`, `:477`, `:559`) stay valid. It loses the stale check and the read:

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

    The `contains?` check is new. Today a projector missing from state reads its zone as nil, which `normalize` makes the full range (`animation/safety.clj:69-70`). Only other handlers keep such a laser dark: the routing doesn't reach it, and `:projectors/remove` clears its test pattern (`events/handlers/projector.clj:494-495`). Now it draws nothing by construction.
  - `create-projector-frame-provider` (`:218-250`): before `projector-frame`, the stale check on the engine's `System/nanoTime` (the code at `:173-176`, as it is). The same error reaches `[:provider id]`.
  - The namespace docstring (`:1-28`): "No engine reads app state: each tick brings the routing and everything else a frame takes (`engine-inputs`)."
  - What "pure" means here: `projector-frame`'s value depends only on its arguments. It reads no app state, no clock and no atom that changes what it returns; `show-frame`'s `volatile!` is made fresh for each call. It still reports to `output-errors` and counts `routing-log-counter` (`:52`), as today.
  - **What Decimate (`e7b93e4`..`cc9e000`) changes here**: nothing in the design, and one more reason for it.
    - Decimate (`animation/effects/points.clj:41-82`, category `#{:shape :calibration}` at `:79`) can sit in a laser's calibration chain, so that chain now sets the frame's point count. The chain's output also sets the `:fit` record (`:211-214`), which reaches the engine's stats (`streaming_engine.clj:386-388`), then `output-runtime/engine-status`'s `:load` and `:low?` (`backend/output_runtime.clj:194-209`): the card's fps, its note and LOW FPS. Today that chain comes from read #2, so a Decimate added, removed or retuned between the tick and the frame changes both the frame and the card's numbers for that tick (e1h). The two still agree with each other, because the record counts the frame that was made. After the plan, the frame, its record and the card all follow the tick's chain.
    - Decimate's `:kpps` and `:fps` are resolved per frame from the chain's timing context (`effects/points.clj:53-74`), and a calibration may carry modulators other than presses. That is one more reason the chains stay per frame (§4.3).
    - If Q-D-2 B is ever built (`plans/2026-11-19-zones/10-questions.md:162-164`: count each laser's calibration chain in a cue's ⚠), the clock's cue warnings, made from the snapshot (`show_clock.clj:150-161`), can read each laser's chain from the same tick's `:engine-inputs`, with no second read.
- `laser-show.services.show-clock` (`services/show_clock.clj`):
  - `tick!` (`:226-262`): the snapshot (`:249-256`) gains `:engine-inputs (multi-engine/engine-inputs state (System/currentTimeMillis))`, from the tick's one read (`:235`), before `publish!`.
  - A NEW require of `laser-show.backend.multi-engine`. There is no cycle: `multi-engine` requires nothing that requires the clock, and the clock already loads it through `events.core` → `output-runtime` (`backend/output_runtime.clj:21`).
  - Docstring step 4 (`:19-26`): the snapshot carries what the engines take from app state.
- `laser-show.animation.show-time` (`animation/show_time.clj`): the `!snapshot` docstring (`:341-348`) lists `:engine-inputs`.
- `laser-show.backend.output-runtime` (`backend/output_runtime.clj`): the `fingerprint` docstring (`:35-40`) says "safety and calibration come with each tick" instead of "safety is read live".
- **Probes in other plans**, updated with the code, as Z3 updated `vibe_probes_test.clj` (05 §13). Without these changes they would measure nil frames, or throw on the new arity:
  - `plans/2026-11-19-zones/probes/zones_probes_test.clj:90-93` (`snapshot`) and `plans/2026-11-19-zones/probes/vibe_probes_test.clj:579-585` (`zone-snapshot`) gain `:engine-inputs`;
  - `plans/2026-11-16-output-runtime/probes/output_probes_test.clj:324-330` (p8) passes the chain to `apply-projector-effects`.
- **Not changed**: `routing/rig.clj` (E2 A), `routing/compose.clj`, `animation/safety.clj`, `animation/test_pattern.clj`, `animation/fit_guard.clj` (its `recount` included), `animation/effects/points.clj`, `services/frame_service.clj`, `backend/streaming_engine.clj`, the views and every event handler. The projector effects still run on each engine's own thread (O6).

### 4.2 Latency

Symbols: T is the tick period (16.67 ms). d is the tick's read → its publish (§1.3). x₁ and x₂ are publish → read #1 and read #2 today. s is publish → the frame leaves the engine. D is the DAC finishing its current frame.

An edit is in the first frame whose read comes after the edit's write (the `:state` effect, `events/core.clj:150-158`). The reads come once per tick, so:

| | Today | After |
|---|---|---|
| Where the frame's settings are read | read #1 at publish + x₁, read #2 at publish + x₂ | the tick's read, at publish − d |
| Worst case, write → the frame with the edit leaves the engine | T − x + s (the edit lands just after an engine's read) | T + d + s (it lands just after the clock's read) |
| Increase | – | **d + x** |
| Then the DAC | + up to D | the same |

| Edit | Read today | Worst case today | Worst case after | Increase, d + x (medians) |
|---|---|---|---|---|
| Safety narrowing (`:projectors/set-safety`) | #1 | 16.67 − 0.06 + 0.52 ≈ 17.1 ms | 16.67 + 0.18 + 0.52 ≈ 17.4 ms | 0.24 ms |
| BLACKOUT on the test pattern | #1 | ≈ 17.1 ms | ≈ 17.4 ms | the same |
| Test pattern or Identify on/off | #1 | ≈ 17.1 ms | ≈ 17.4 ms | the same |
| Calibration edit (corner pin, curves, Decimate) | #2 | 16.67 − 0.15 + 0.52 ≈ 17.0 ms | ≈ 17.4 ms | 0.33 ms |

The numbers use the medians of the `cc9e000` runs (§1.3): the 4-cue scene's d (0.17–0.19 ms), x₁ (0.05–0.07), x₂ (0.13–0.17) and s (0.45–0.60), on this machine. Then up to D more at the DAC (16.7–40 ms for 418–1,000 points at 25 kpps). Under load the tails are longer: each part's p95 was between 0.14 and 1.64 ms in these runs (§1.3), a tenth of a tick or less. From the code with e2's numbers put in; not measured end to end, and *unverified* on a real DAC.

- **Which edits get later.** Only those that land between the clock's read and the engines' reads. That window is d + x, about 0.2–0.3 ms of each 16.67 ms tick, so 1–2 % of edits. They move to the next tick, the frame they belong with. Every other edit reaches the laser at the same moment as today.
- **BLACKOUT on the show itself is already per tick.** Pressing it dispatches `:transport/stop` inline (`vibes/engine.clj:459-464`), which sets `[:playback :playing?]` false (`events/handlers/timing.clj:73-79`). The tick reads that (`show_clock.clj:246`). Only the pattern's BLACKOUT was live. After this plan both go through the snapshot.
- **Identify**: E1 A times the flash by the tick, so each 250 ms half starts on a tick boundary, at most one tick (16.7 ms) late.
- **The one case that gets slower: a stalled clock.** With no tick for 50–250 ms, today a keepalive resend re-reads app state, so a narrowing reaches the laser at the next resend (≤ 50 ms). After, the resend repeats the tick's frame, and the narrowing waits for the next tick. At 250 ms (`STALE_MS`) the engine sends empty frames anyway (S3 A). So the worst case during a stall grows from about 50 ms to 250 ms, and then the laser is dark. During a stall the show is frozen as well. From reading the code, not measured. Making resends re-read the zone is rejected (§10).

### 4.3 Cost

| Work | Thread, rate | Today | After |
|---|---|---|---|
| The frame's two app-state reads | each engine, per frame (60/s per laser) | 2 × 0.13–0.42 µs (e3) | none |
| Building `engine-inputs` | the clock thread (`show-clock`, `show_clock.clj:303`), per tick (60/s) | – | 0.9–1.0 µs for a map of seven entries, each a reference or a scalar (e3, a stand-in for the same map) |
| Normalizing the safety zone | each engine, per frame | twice (`guard-safety` `:118`, then inside `apply-safety`, `safety.clj:276`): 2 × 8–11 µs for a limit, 2 × 23–28 µs with two blocked areas, nothing without a zone (e3) | E2 A: the same |
| Building the calibration chain | each engine, per frame | 45–46 µs of the default chain's 123–125 µs on 1,000 points: rgb-curves builds three 256-entry LUTs per frame (`animation/effects/calibration.clj:25-33`) (e3) | the same (§10) |
| Decimate last in that chain | each engine, per frame | 398–432 µs on the guard's 1,800 points (cut to 833), against 184 µs for the chain without it (e3): Decimate buffers the frame and thins it (`effects/points.clj:41-74`) | the same |
| The routing | the clock thread, per change of `[:rig]`, `[:projectors]`, `[:zone-groups]`; otherwise an identity check | 30–33 µs for 1 laser, 73–85 µs for 3, 197–220 µs for 8, already normalizing each zone (`rig.clj:379-386`); 0.4–0.6 µs per tick otherwise (e3) | the same (E2 A) |
| The whole engine frame | each engine, per frame | 135–142 µs without a zone, 810–858 µs with two blocked areas (1,000 points; `apply-safety` alone 663–667 µs) (e3) | the same, minus the two reads |

- **What goes into the snapshot**: references to maps that are already immutable, `(:projectors state)` and `(get-in state [:chains :projector-effects])`, plus four scalars and the `{pid until}` map, also by reference. No copy and no new computation.
- **Per tick or per change**:
  - The references cost nothing, so they go in per tick.
  - Normalized safety stays per frame as today (E2 A). Normalizing it per tick on the clock thread would cost n × 8–28 µs per tick, up to ~0.2 ms with 8 lasers: as much as a whole render (d, §1.3). Rejected. Per change (E2 B, C) saves one normalize per frame: 8–28 µs, about 0.05–0.2 % of a frame period on each engine thread.
  - Built calibration chains stay per frame. They take the frame's timing context and point count (`animation/effects.clj:222-231`, `:371-376`), and calibration may carry modulators other than presses (`views/components/modulator_param_control.clj:50-53`). Since Decimate, a chain can also change the frame's point count, from parameters resolved per frame (`effects/points.clj:53-74`).
- **The memo patterns**:
  - `rig/cached-routing` (`rig.clj:417-425`, its cache in the clock's runtime as `:routing-cache`, `show_time.clj:58-65`) is the model to copy if E2 B or C is chosen.
  - `!refs` (`rig.clj:446-450`) is a global atom that engine threads and the UI both wrote ("a race only recomputes"). After Z3 no engine calls it (`rig.clj:428-430`). It is the pattern to avoid on the output path.

### 4.4 The preview

The preview cells need nothing: they already draw one tick from the snapshot and never read app state for a frame (§1.4). The thumbnail's frame becomes one tick's through the engine. Its outline stays the card's current limit (E3 A).

The dead preview reader `get-preview-zone-filter` and `matches-preview-zone?`, and the 2-arity `generate-frames-by-zone`, are out of scope (§10). None is on a frame path, and deleting them changes no frame.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| snapshot `:engine-inputs` **NEW** | runtime: `show-time/!snapshot` (`show_time.clj:341-348`), replaced every tick | the show clock thread (`tick!` → `publish!`) | no / no |
| `[:chains :projector-effects]`, `[:projectors id :safety]` (existing) | the rig (document; the rig file since ZR, `state/rig_file.clj:30`) | their events, as today | as today |
| `[:projector-ui :calibrating-projector-id]`, `:calibration-brightness`, `:identify` (existing) | UI (`state/domains.clj:338-359`) | their events, as today | no / no |
| `[:vibe-runtime :blackout?]` (existing) | session (`state/domains.clj:107-120`) | `:vibe/press :role :blackout` | no / no |
| `multi-engine/now-ms` (var, `multi_engine.clj:126-129`) | – | **removed** (E1 A) | – |

Nothing new goes into app state and nothing new is saved. This follows `docs/CLJFX_STATE_MANAGEMENT.md`:
- Best Practice 1: one thread extracts the data once, and the workers get immutable data.
- Best Practice 5 (`:650-663`): the per-tick data stays in the clock's runtime atom, out of the context.

## 6. Events and effects

No event, handler or effect changes or is hooked. The effect keys in `events/core.clj` are untouched. These events' results reach the lasers from the next tick, on every laser at once:

| Event | Dispatched from | Pure? |
|---|---|---|
| `:projectors/set-safety`, `:projectors/add-blocked-area`, `:projectors/remove-blocked-area`, `:projectors/copy-safety` | `views/components/safety_editor.clj:185`, `:289`, `:295`, `:332`; `:321`; `:212`; `:227`, `:232` | yes, unchanged |
| `:chain/update-param`, `:chain/update-curve-point`, `:chain/update-spatial-params`, `:chain/reset-params` with `:domain :projector-effects` | `views/tabs/projectors.clj:308-315`, `:361-364`, `:372-379` | yes, unchanged |
| `:projectors/add-effect` (a calibration effect, Decimate among them since `d6fbb15`; handler `events/handlers/projector.clj:416`) | the laser's "+ Add Effect" menu (`views/tabs/projectors.clj:443`, the ids Decimate added at `:441`, `:448`) | yes, unchanged |
| `:projectors/toggle-calibration`, `:projectors/set-calibration-brightness` | `views/tabs/projectors.clj:500`, `:525` | yes, unchanged |
| `:projectors/identify` (and its `:dispatch-later` `:projectors/identify-ended`, `events/handlers/projector.clj:532`) | `views/tabs/projectors.clj:509`, `views/components/rig_strip.clj:288` | yes, unchanged (uses `:time`, `events/core.clj:91-94`) |
| `:vibe/press :role :blackout` | the Perform pad (`views/components/vibe_pads.clj:65`, `:83` → `events/handlers/vibes.clj:75`); a bound trigger (`events/handlers/triggers.clj:72`) | yes, unchanged |
| `:edit/undo`, `:edit/redo` restoring a zone or calibration | the title bar menu (`views/components/title_bar.clj:117`) and the editors' keys | yes, unchanged |

## 7. Data examples and invariants

```clojure
;; The tick's state (the clock's one read, show_clock.clj:235), in part
{:projectors   {:p1 {:host "10.0.0.5" :enabled? true
                     :safety {:limit [-0.9 -0.8 0.9 0.95] :blocked [[-0.2 -1.0 0.2 -0.4]]}}
                :p2 {:host "10.0.0.6" :enabled? true}}
 :chains       {:projector-effects {:p1 {:items [rgb-curves corner-pin]} :p2 {:items [rgb-curves corner-pin]}}}
 :projector-ui {:calibrating-projector-id :p2 :calibration-brightness 0.1 :identify {:p1 1763686500000}}
 :vibe-runtime {:blackout? false}}

(me/engine-inputs state 1763686498000)
;; => {:projectors        {:p1 {…} :p2 {…}}            ; (identical? … (:projectors state))
;;     :projector-effects {:p1 {:items […]} :p2 {…}}  ; (identical? … (get-in state [:chains :projector-effects]))
;;     :calibrating       :p2
;;     :brightness        0.1                          ; 0.1 when state has none, as test-pattern-frame does today (:150)
;;     :identify          {:p1 1763686500000}          ; until-ms, on the :time co-effect's wall clock
;;     :blackout?         false
;;     :wall-ms           1763686498000}               ; the tick's wall clock, for Identify (E1 A)

(:engine-inputs (show-time/latest))   ; that map, beside :cues :routing :projector-ctx :playing? :tick :at-ns
```

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

Identify, `{:p1 10000}` (`identify-on?`, `test_pattern.clj:70-78`):

| `:wall-ms` | Frame |
|---|---|
| 7000 | the pattern (lit half) |
| 7300 | `[]` (dark half) |
| 7500 | the pattern |
| 10000 | the show (its end time; `:identify-ended` may not have cleared it yet) |

**Invariants**
1. **A frame is a function of its snapshot.** `(projector-frame id s)` gives `=` frames for the same `s`, whatever app state does in between. So a keepalive resend repeats its tick's frame.
2. **No engine thread reads app state.** Zero `state/get-raw-state` calls on `idn-streaming-*` threads. Today there are 2 per frame (e2b, e4).
3. **One tick, one state.** Every laser's frame of tick N uses the calibration, zone, test pattern, Identify and BLACKOUT of the state the clock read for tick N, and so does its `:fit` record, which the card and LOW FPS read. That is the read its cues and routing come from, so its halves are fitted to the box it is clipped to (Z1 S-Z1-7), unless the resolver failed and the tick kept an older routing (`show_clock.clj:200-212`). Then the clip is the newer zone.
4. **Still one read per tick.** `one-state-and-input-read-per-tick-test` stays `{:app-state 1 :input-store 1}`.
5. **References, not copies.** `engine-inputs` holds the state's own sub-maps (`identical?`) and computes nothing but `boolean` and a default.
6. **Safety is still last and fails closed.** An unreadable zone in the tick gives an empty frame and `[:safety id]`. A laser missing from the tick's `[:projectors]`, or a snapshot without `:engine-inputs`, gives nil and no error. No laser falls back to the full range because the tick didn't know it.
7. **The work stays where it runs.** Projector effects, the fit guard and safety run on each laser's own thread (O6). Only where their inputs come from changes.
8. **An edit is in force from the first tick that reads it**, with no restart (Z1 invariant 10, restated). That is at most T + d after its write, plus the engine's send and the DAC's frame.
9. **Nothing new in app state**, so no undo or dirty change.

## 8. Tests (named test vars + fixtures)

Write each new test first and watch it fail. The red results expected are the probe lines in §1.2.

- `laser-show.backend.multi-engine-test` (existing; fixture `setup-test-state` `:23-32`):
  - The `snapshot` fixture (`:34-40`) gains `:engine-inputs (me/engine-inputs (state/get-raw-state) (System/currentTimeMillis))`, and a `:wall-ms` option. Every other test there passes unchanged, because each sets state before it builds a snapshot.
  - NEW `engine-inputs-are-the-ticks-own-maps-test` (pure): the example in §7. `:projectors` and `:projector-effects` are `identical?` to state's. A state without `:projector-ui` or `:vibe-runtime` gives `:brightness 0.1`, `:calibrating nil`, `:identify nil`, `:blackout? false`.
  - NEW `calibration-edit-waits-for-the-next-tick-test`: e1g through `show-clock/tick!` (`events/dispatch!` stubbed, as `show_clock_test.clj:540`). A circle cue and a pin of 1.0, then a pin of 0.5 after the tick. The tick's frame is `=` to the frame made before the edit (reach 0.50), and the next tick's reaches 0.25.
  - NEW `decimate-edit-waits-for-the-next-tick-test`: e1h, with the file's `heavy` (`:627-630`) and `decimate` (`:711-712`) fixtures. Decimate goes into the laser's calibration after the tick. The tick's frame keeps the guard's 1,800 points, and its `:fit` record says `:points 1800`; the next tick's has 833 and 833. The provider's smoothed record follows the tick too.
  - NEW `safety-edit-waits-for-the-next-tick-test`: e1b. The tick's frame keeps 64 lit points and the halves of ±0.9. The next tick keeps 64 lit points inside ±0.3, with the halves `[-0.075 0 0.225]`.
  - NEW `blackout-and-test-pattern-wait-for-the-next-tick-test`: e1c and e1d. Pressing Identify after the tick leaves the tick's frame alone too.
  - NEW `edit-while-a-frame-is-made-leaves-it-alone-test`: e1e. `fit-guard/guard` is redefined to edit the pin and the limit, then call the original. The frame reaches 0.80.
  - NEW `lasers-of-one-tick-share-its-state-test`: e1f. Both reach 0.80.
  - NEW `engine-reads-no-app-state-test`: with `state/get-raw-state` redefined to throw, as `:205-208` does for the routing. Each of these is `=` to the frame made without the redefinition:
    - a show frame with calibration and a zone;
    - the test pattern;
    - Identify, lit and dark;
    - BLACKOUT on the pattern;
    - nothing playing (nil);
    - an unreadable zone (`[]`, with `[:safety :p]` reported).
  - NEW `laser-the-tick-doesnt-know-draws-nothing-test`: a snapshot whose `[:engine-inputs :projectors]` lacks `:p`, with the test pattern on for `:p`, gives nil and an empty `output-errors/snapshot`. A snapshot without `:engine-inputs` gives nil too.
  - NEW `same-tick-same-frame-test`: the provider runs twice on one published snapshot (a keepalive), with the pin edited and an Identify started in between. The two frames are `=`.
  - Changed: `failing-calibration-effect-skips-only-itself-test` (`:440-458`) and `skipped-calibration-effect-is-reported-test` (`:460-482`) pass the chain to `apply-projector-effects`, not app state. Their assertions don't change.
  - Changed: `identify-flashes-then-stops-test` (`:610-620`) sets the snapshot's `:wall-ms` instead of redefining `me/now-ms`. It uses the table in §7.
  - Unchanged, and they pin the moved check: `stale-snapshot-sends-an-empty-frame-test` (`:385-397`), `no-snapshot-yet-reports-nothing-test` (`:399-403`), `idle-frame-provider-returns-nil-without-exceptions-test` (`:409-427`; its `:test-proj` isn't in state, so it gets nil before and after), `provider-records-what-it-produced-test` (`:676-705`), and every other Z1 safety and test-pattern test (`:499-608`).
  - Unchanged: Decimate's `decimate-in-a-calibration-chain-counts-on-the-card-test` (`:714-736`). It sets state before each snapshot it builds, so the fixture's `:engine-inputs` carries its chain and its safety limit.
- `laser-show.services.show-clock-test` (existing; fixture `:60-67`):
  - NEW `snapshot-carries-engine-inputs-test`, next to `snapshot-carries-routing-test` (`:261-273`). An idle tick and a playing tick both carry `(me/engine-inputs state wall-ms)` of the tick's state: the same maps (`identical?`), and a `:wall-ms` between the wall clock just before and just after the tick.
  - NEW `engines-read-no-app-state-test`. `with-engines` (`:196-213`) runs 3 projectors with the default calibration (`events.handlers.projector/DEFAULT_PROJECTOR_EFFECTS`, `events/handlers/projector.clj:40-55`) and a zone. One cue plays, and 60 hand ticks run 5 ms apart, as in `renders-once-per-tick-test`. No `state/get-raw-state` call happens on an `idn-streaming-*` thread, and every engine sent frames. Today: 2 per frame (e2b).
  - Unchanged: `one-state-and-input-read-per-tick-test` (`:519-547`, invariant 4), `renders-once-per-tick-test`, `snapshot-shape-test`, `routing-is-made-before-the-render-test`, `projector-effects-never-see-pulses-test` (`:587-596`).
- `laser-show.backend.output-lifecycle-test` (existing): the `stopping-every-engine` fixture (`:39-58`) also calls `show-time/reset-runtime!` in its `finally`. The `^:timing` `every-projector-sends-every-tick-once-test` (`:570-601`) leaves its last snapshot behind. With the stale check now in the provider, a later test that redefines `me/projector-frame` would otherwise get "Show clock stalled" instead of its frame. Today the redefinition replaces the check too. No assertion changes.
- `laser-show.animation.show-time-test`: nothing. `publish!` doesn't look inside a snapshot (`show_time.clj:371-380`); only the docstring changes.
- `laser-show.animation.safety-test`, `laser-show.animation.test-pattern-test`, `laser-show.routing.rig-test`, and Decimate's `laser-show.animation.effects.points-test` and `fit-guard-test/recount-counts-what-is-drawn-test`: unchanged. They are pure and already free of app state (E2 A).
- UI regression (nothing on screen changes, so no new UI test, per `.agents/skills/targeted-ui-testing/SKILL.md` §Test design): `-n laser-show.ui-test.safety-editor-test`, `.setup-dialogs-test` (the brightness slider, and Decimate in the Add Effect menu), `.streaming-status-test`, `.rig-card-test` (the thumbnail and the load), `.preview-test`.

Run, in this order:
1. `clj -M:mac-arm:test multi-engine show-clock output-lifecycle engine-wire streaming-engine rig compose test-pattern safety fit-guard points`
2. `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.safety-editor-test`, then the same for each of the other four UI namespaces above
3. both full suites
4. the probes: `P=plans/2026-11-20-state-integrity; clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" -M:mac-arm:test:probe -d "$P/probes" -n probes.engine-snapshot-probes-test 2>&1 | grep PROBE`. After the change, e1 prints "follows the tick" in every case, e1e reaches 0.80, e1h keeps [1800 1800] for the tick, e2b counts no engine reads and e4 counts 0. Then the zones probes (z1) and the output runtime probes (p8), which change in commit 2.

## 9. Commit plan

| # | Commit | Tests (red first) |
|---|---|---|
| 1 | `feat(output): each tick's snapshot carries what the engines take from app state, the lasers' calibration, safety zones, test pattern, Identify and BLACKOUT, and the tick's wall clock; nothing reads it yet` | `engine-inputs-are-the-ticks-own-maps-test`, `snapshot-carries-engine-inputs-test`; `one-state-and-input-read-per-tick-test` unchanged. Write a stub `engine-inputs` returning nil first, so they fail one by one rather than as a compile error |
| 2 | `feat(output): every frame a laser sends comes from one tick, its calibration, safety zone, test pattern and BLACKOUT the tick's, so an edit lands on every laser at the same tick, a frame never mixes two states, and no engine reads app state` | the nine NEW engine tests and `engines-read-no-app-state-test`, red against the engine of commit 1 with the §1.2 numbers; then the two changed calibration tests, the changed Identify test, both fixtures, the docstrings, and the probe helpers of §4.1 |
| 3 | `plans: engine snapshot done (<sha>): as built, red-first results, probes e1–e4 before and after; Z1 §8 invariant 10 and Z3 §8 invariant 2 point here` | – |

## 10. Out of scope / alternatives considered

- **E1 B, E2 B and C, E3 B**: see the decisions.
- **Moving the projector effects to the clock thread**: rejected in show clock 02 §9 (O6), and still right. A heavy calibration would delay every laser. Only the inputs move.
- **Keepalive resends that re-read the zone**: rejected. A resend would again join a tick's content with a later zone, and a stall ends in dark frames at 250 ms anyway (§4.2).
- **Caching built calibration chains** (rgb-curves' LUTs, 45–46 µs per frame per laser, e3): later, as a performance item. It needs static effects told apart from modulated ones, and since Decimate it must keep a chain's per-frame count.
- **An `apply-safety` that trusts an already normalized zone**, which would save its second normalize (8–28 µs): rejected for the safety stage.
- **`apply-safety`'s cost with blocked areas** (663–667 µs per 1,000 points, e3) **and Decimate's in a calibration chain** (about 215–250 µs per frame on 1,800 points, e3; Q-Z3-7 is the same question for the guard): not this plan.
- **The dead preview readers** (`frame_service.clj:67-87`) **and the 2-arity `generate-frames-by-zone`** (`:365-369`): out of scope. No frame reads them, and deleting changes no frame. The reader and its private helper are a two-test cleanup for a later commit (Z3 §11 left them too). The 2-arity has about 30 test call sites, and the clock's read count already guards against it.
- **`multi-engine/create-engines`** (`:291-306`) reads app state, but no caller in `src` uses it (only `multi_engine_test.clj:116`, `:187`), and it builds engines rather than frames.
- **A dev-mode warning when an engine thread reads app state**: the tests in §8 pin it. A runtime check would add a thread-name test to every read.
- **A laser just turned off, whose engine is still being stopped**, keeps drawing the test pattern until the lifecycle thread stops it, as today (the tick still lists it in `[:projectors]`).
- **Not here**: MIDI, OSC and Link callbacks that read live state; the direct `swap-state!` writers (`plans/2026-11-20-state-integrity/02-direct-state-writes.md`); the clipboard (`plans/2026-11-13-keyboard-focus-window-lifecycle/04-phase4-clipboard.md`).
- **The sibling plans**: no dependency either way. The engines write no app state and, after this plan, read none. A calibration pasted into a laser's chain lands in `[:chains :projector-effects]` like any other edit, so the next tick carries it, however the clipboard plan has the paste written. The new tests set state as every engine test does today (`state/swap-state!`, `multi_engine_test.clj:200`). If plan 02 fences that off for tests too, they follow whatever 02 gives tests.

---

## Decisions

> **DECISION E1 — What clock times Identify's flash?** *(open)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★** | The tick's wall clock, `:wall-ms` in `engine-inputs`, read when the snapshot is built. `me/now-ms` goes | A frame is a function of its snapshot alone; a keepalive repeats it; every laser of a tick decides on the same instant; `projector-frame` keeps `[projector-id snapshot]`, so no call site changes; the tests set `:wall-ms` instead of redefining a var | The flash's edges land on tick boundaries, at most 16.7 ms late on a 250 ms half |
> | B | The engine's wall clock per frame, as today, passed into `projector-frame` as an argument | Sub-tick edges | `projector-frame` needs a third argument at ~35 call sites, or a 2-arity that reads the clock; a resend can differ from its tick |

> **DECISION E2 — Where a laser's safety zone is normalized** *(open)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★** | Per frame, as today, from the tick's `[:projectors]` (`engine-inputs`) | The smallest change. A narrowing never waits on the resolver. Fail-closed stays exactly where Z1 put it. `rig.clj` is untouched | Two normalizes per frame stay (2 × 8–28 µs with a zone, e3) |
> | B | Per change, in the routing: `halves` (`rig.clj:379-386`) already normalizes, so `:outputs pid` keeps `:safety` (or `:safety-error`, a message string, so the routing stays a value) | One normalize per frame fewer. The halves and the clip are one object | A resolver failure keeps the cached routing (`show_clock.clj:200-212`), which would freeze every zone until it recovers, so `routing-step` must refresh the zones from the tick's state then; `rig-test/unreadable-safety-zone-draws-no-halves-test` (`rig_test.clj:463-476`) and `rig-test/outputs-carry-scan-rate-fit-and-region-fits-test` (`:433-447`) change |
> | C | Per change, in its own memo on `(:projectors state)`'s identity, kept in the clock's runtime like `:routing-cache` | One normalize per frame fewer, independent of the resolver | A second cache for 8–28 µs per frame, about 0.05–0.2 % of a frame period on each engine thread |

> **DECISION E3 — Should the preview get the same consistency?** *(open)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★** | Nothing more. The preview cells already draw one tick from the snapshot (§1.4). The thumbnail's frame becomes one tick's through the engine. Its grey outline stays the card's current limit (`subs/rig-cards`, `subs.clj:841-872`) | No UI change. While you drag the limit, the outline shows where you are dragging it | For up to a tick, the outline can lead the frame drawn inside it (display only, at most 10 redraws a second) |
> | B | The provider also records the zone it clipped with (`show-time/record-output!` gains `:limit`, `show_time.clj:360-364`), and the thumbnail draws that outline | Frame and outline are always the same tick | Touches the card and its tests (`rig-card-test`); the outline stops following a drag until the next tick reaches the engine |
