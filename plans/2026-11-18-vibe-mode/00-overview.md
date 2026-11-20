# Vibe mode: five buttons, two faders, cues grouped by feel

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`; `src/` is unchanged since `378db47`)
**Status:** Phases 1–7 done: 1 `9177b1e`, 2 `94f905e`, 3 `38f92ad`, 4 (4a `6715fb4`, 4b `f5a4f84`), 5 `9f5d78a` (its fader slots fixed in `0faa18c`), 6 `4a4a10a`, 7 `5b59774`; each doc's §10 (and 04 §11, 05 §11) says what was built. Phase 8 (zone effects) is open: V10 isn't answered. V1–V9 answered 2026-11-18 (§7: V8 B, V9 A with a palette option, the rest A)   **Last verified against:** `b1da0f2` for §1–§3 and the probes. The unit tests whose names contain `triggers`, `input-mapper` or `show-time` pass (42 tests, 149 assertions, 0 failures). Probes v1–v24 (§3) ran on macOS arm64, JDK 25.0.5 (v13–v24, for Phases 4 and 8, on 2026-11-18; for v19–v24 the working tree also held Phase 1's uncommitted edits, in files those probes don't touch).
**Supersedes / Superseded by:** supersedes the unused trigger-group scaffolding from `23fc7cc` ("build out vibe tapping", see V7); answers the open "adopt or delete the `triggers/` engine" decision (review 05 L1). Superseded by: none
**Related findings:** review 05 F13, F14, F15, L1, M4 (step 1 only), M5, M6; review 02 #10, T3; keyboard plan K-13 (its Phase 5); output runtime O4 (blackout); show clock §7 (input timestamps, Stop and blackout, the agent's blocking I/O); modulator time base §9 ("once" on the effects grid counts from the clock's start); the UX plan (`plans/2026-11-18-ux-improvements/`: the Grid tab is the performer's tab)

Paths are under `src/laser_show/` unless they start with `test/`, `plans/` or `beyond-vibe/`. `beyond-vibe/` is the Rust project next door (commit `073ea89`), cited as `beyond-vibe/src/<file>:<line>`. Anything marked **NEW** does not exist yet. "Probe vN" is in `probes/vibe_probes_test.clj`.

## Start here

You asked for the `beyond-vibe` idea inside this app: busk with a handful of keys and one or two faders, with cues grouped so that you decide less. This folder is that plan.

**The idea.** Group cues into **vibes**, each a *feel* ("Calm", "Groove", "Peak"). A vibe holds a small **pool** of cues per fixed **role**: hit, accent, flash, plus an optional always-on bed and an **FX set** (effect cells that switch on with the vibe). Five buttons play whatever vibe is current, and they never change meaning:

| Button | One-line meaning |
|---|---|
| **HIT** | each press plays a different cue from the pool for about a beat, taps the tempo, and kicks the effects that react to HIT |
| **ACCENT** | the same from a second, bigger pool, layered over the hit, with its own reactions |
| **FLASH** | hold for the big moment; let go and the look returns |
| **VIBE** | tap = next feel; hold = lock the feel |
| **BLACKOUT** | kill everything; stays killed until pressed again |

Two faders: **Intensity** (a master dimmer that works out of the box) and **Morph** (whatever the current vibe wires to it).

**Presses move effects, not only cues** (Phase 4). Every press is also a **pulse** that effects can follow: a HIT can punch the size, pump the brightness, step the colour or the spin; an ACCENT can flip or scatter the picture; a held FLASH can swell or strobe; a VIBE switch can fade the new look in; the tapping rate itself can grow the look. Any effect parameter that takes a modulator can follow a press (brightness, size, spin, colour, position, lens distortion, a comet's flare), and an effect cell holds a chain, so one press can move several effects at once. A role may even have no cues at all and only move effects. These are ordinary modulators in the existing editors, three new ones (Envelope, Step, Energy) and a "Restart on" choice for the old ones, and a vibe gets them in one click as **tap reactions**.

**Looks that move between projectors** (Phase 8). Three effects on the effects grid move a finished picture between zones: **chase** (the picture runs across the zones, one at a time, on the beat), **swap** (two zones trade pictures) and **shift** (the pictures move along the zones). A FX set, a held FLASH or a Grid-tab cell switches them on, and a press can drive them: a HIT hops the picture to the next projector, an ACCENT swaps left and right. They only re-route what is drawn, and they obey the audience rule.

**What the app already has:** the cue grid, the trigger map and learn, momentary cues, effect cells, the lock-free input store and modulators, and the show clock that owns time. **What is missing:** the grouping model, a beat-length decay, a picker that never repeats, a blackout that stays on, a screen you can read at a glance, a way for a press to reach an effect, a way to move a look between zones, and a way to learn a fader.

| Phase | Doc | Goal | Size | Needs | Status |
|---|---|---|---|---|---|
| 1 | `01-phase1-engine-core.md` | Vibes, pools, the roles, beat decay, the no-repeat bag. No UI | ~3.5 d | none | Done, `9177b1e` |
| 2 | `02-phase2-perform-view.md` | A Perform tab: five pads, vibe strip, readouts. Playable by mouse, touch or learned keys | ~2.5 d | 1 | Done, `94f905e` |
| 3 | `03-phase3-setup-and-grouping.md` | A Vibes tab, badges on the grid, make-vibes-from-rows, learn-all wizard, starter vibes | ~3.5 d | 1, 2 | Done, `38f92ad` |
| 4 | `04-phase4-tap-reactions.md` | Presses move effects: pulses, Restart on, Envelope / Step / Energy, tap reactions, hue stepping | ~3.5 d | 1 (2 and 3 for the pad and Vibes tab parts) | Done, 4a `6715fb4`, 4b `f5a4f84` |
| 5 | `05-phase5-faders-and-macros.md` | The fader path: macro modulator, CC learn, master intensity, Morph | ~1.75 d | 1, 2 | Done, `9f5d78a` (fader slots `0faa18c`) |
| 6 | `06-phase6-rotation-and-tempo.md` | Auto-advance on phrase gaps, and one tap-tempo algorithm | ~2.5 d | 1, 2 | Done, `4a4a10a` |
| 7 | `07-phase7-safety-and-hardening.md` | Audience marks on Perform, show lock, stuck-key guards, hardware check, docs | ~2 d | 1–6 | Done, `5b59774` (the hardware check unverified) |
| 8 | `08-phase8-zone-effects.md` | Zone chase, swap and shift: three effects, a render stage, the pickers. With vibes: four reactions and the audience rule | ~3.5 d (2.5 d for the effects, 1 d with vibes) | none for the effects; 1 and 4 for the reactions | Open (V10 pending) |

About 23 days. Phase 8 stands apart: its effects need nothing from the other phases and can land any time, and its reactions follow Phases 1 and 4. Phase 1 alone is only drivable from the REPL, so **land 1 and 2 together** for first use. Each later phase ships on its own; Phase 4 is the one that makes tapping feel alive, so it comes before the faders.

---

## 1. The request, as requirements

| # | Requirement | Where it lands |
|---|---|---|
| R1 | At most 5 buttons and 2 sliders while performing | §4 controls, V1 |
| R2 | Groups of triggers that are easy to set up (minutes) and easy to use (no menus in the show) | §4 grouping, Phase 3 |
| R3 | Less mental load: fewer decisions per second | §4 table |
| R4 | Safe by default: one sure way to dark, no accidental audience beams | V2, V6, Phase 7 |
| R5 | Port the *model* of `beyond-vibe`, not its protocol (BEYOND OSC) | §2 |
| R6 | Fit this app: pure handlers, registered effects, the show clock owns time, high-frequency data stays out of app state (`.agents/rules/planning-guide.md` rules 4–5) | §5 |
| R7 | Tapping modulates effects, not only which cue shows: a press can punch, step, scatter or swell an effect, and the tapping rate can drive one | §4 controls, Phase 4 |
| R8 | A look can move between projectors: zone chase, swap and shift, on the beat or on a key, and never to the crowd by accident | §4, Phase 8, V10 |

## 2. What `beyond-vibe` does, and where each piece lands

| `beyond-vibe` | Where (`beyond-vibe/src/…`) | This app today | Verdict |
|---|---|---|---|
| Six keys and one slider with fixed roles | `config.rs:32-43` | trigger map `{input → action}` (`events/handlers/triggers.clj:25-27`), learn (`events/handlers/input.clj:223-270`) | **extend**: action `{:type :vibe :role r}`; five roles, no latch key (V1) |
| Vibe group: name, cue pool, selection mode, step modifier, flash, slider target | `config.rs:113-127` | none. `[:triggers :groups]` is declared and never used (`state/domains.clj:93-94`) | **NEW** `[:config :vibes]` (Phase 1) |
| Cues addressed by page/row/col | `config.rs:70-81` | `[col row]` keys of `[:chains :cue-chains]` (`state/domains.clj:391-392`) | exists. Cells move and clear, so references must follow (`vibes.refs`, Phase 1) |
| Shuffle bag, never the same cue twice | `engine/shuffle_bag.rs:23-56` | `triggers/shuffle_bag.clj:5-36`: unused, stores a fn in the bag, repeats at refills (probe v1) | **extend** (Phase 1) |
| Round robin | `config.rs:84-88` | none | **NEW** `:select :sequence` (Phase 1) |
| One exclusive rhythm voice: stop old, start new | `engine/janitor.rs:27-40` | `cue-timing/activate-cue`, `deactivate-cue` (`animation/cue_timing.clj:322-342`) | **reuse** the pure fns; one voice per role (Phase 1) |
| Tap = auto-off after N beats, hold = gate | `engine/mod.rs:173-181, 360-387` | none: a plain run never ends (probe v7). The trigger editor offers `:quantized-decay` and a decay field that nothing reads (`views/components/trigger_bank.clj:100-111`) | **NEW**: decay in the show clock (Phase 1) |
| Quick-tap threshold 150 ms | `config.rs:47-53` | `[:triggers :settings :quick-tap-threshold-ms]`, unused (`state/domains.clj:95-99`) | **move** to `[:config :vibes :settings]` |
| Flash: solo cue or FX overlay | `engine/janitor.rs:50-87` | momentary cue and momentary effect cell (`events/handlers/triggers.clj:43-57`) | **extend**: a flash role that ducks the others (Phase 1); the overlay's on-press / on-release scripts become flash FX (Phase 1) and an Envelope that holds while FLASH is down (Phase 4) |
| Latch key | `engine/mod.rs:264-270` | none | **drop**: a per-vibe hit mode instead (V1) |
| Vibe key: tap next, hold 600 ms locks | `engine/mod.rs:15, 183-189, 454-459` | none | **NEW** (Phase 1). Decided on release, so it needs no timer |
| Three-state rotation (active / armed / locked), phrase gap, flash-mask switch | `engine/mod.rs:22-27, 203-225, 403-407` | `:trigger/tick` and `:trigger-group/toggle-lock` handlers that nothing dispatches or reads (`events/handlers/triggers.clj:134-155`) | **replace** (Phase 6) |
| Blackout: sticky, blocks every other trigger | `engine/mod.rs:254-257, 461-475` | `:blackout` = `:transport/stop` and any later cue start resumes (probe v4). O4 refused a master output switch | **extend** in the vibe layer only (V2, Phase 1) |
| Slider, rate-limited, per-vibe PangoScript target | `engine/mod.rs:17, 191-201`, `beyond_osc.rs:91-103` | CCs go to the lock-free store, not the agent (probe v9); modulators read it (`animation/modulator_evaluators.clj:204-221`) | **extend**: a `:macro` modulator and per-vibe FX sets (Phase 5) |
| Step modifier: cycle the colour (`CycleColorPalette`) or a script (`CyclePangoScript`, e.g. an X-invert) per tap | `config.rs:89-94`, `engine/mod.rs:341-358` | `:set-hue`, `:hue-shift`, `:scale` effects (`animation/effects/color.clj:30-43, 133-148`, `animation/effects/shape.clj:22-46`); nothing can step them per press | **NEW**: the Step modulator, on any parameter and any role (Phase 4, V9): the colour step, and the X-invert as the Flip template |
| (none: only the colour or a script per tap) | – | a modulator counts from its cue's start or the global clock (probe v15); its Retrigger button restarts every clock (v14); a random can't vary per press (v16) | **NEW**: pulses, Restart on, Envelope and Energy, tap reactions (Phase 4) |
| Tap tempo: median of 5, debounce 100 ms, reset after 1.5 s | `engine/tap_tempo.rs:8-73` | three versions (`events/handlers/timing.clj:42-58`, `triggers/tap_tempo.clj`, `events/handlers/triggers.clj:96-99`) | **consolidate** (Phase 6, review 05 M6) |
| MIDI learn wizard | `midi_io.rs:133-235` | per-cell learn (`events/handlers/input.clj:223-270`); notes and triggers only (probe v6) | **extend**: roles (Phase 2), wizard (Phase 3), CC (Phase 5) |
| Release held keys on unplug, CC 120 and 123 | `engine/mod.rs:153-157, 291-293, 410-426` | none | **NEW** (Phase 7) |
| Terminal dashboard | `ui.rs:108-204` | none | **NEW** Perform tab (Phase 2) |
| 1 ms engine thread | `main.rs:189-222` | event agent plus the 60 Hz show clock (`services/show_clock.clj:137-188`) | **not ported**: time here is beats, and 16.7 ms is enough (§5) |
| BEYOND OSC and PangoScript | `beyond_osc.rs` | IDN output | **not ported** |

### The `beyond-vibe` tests are the acceptance spec

`beyond-vibe` pins its behaviour with tests. Each one becomes a named test here, so the port is checked against what the Rust version already proved.

| `beyond-vibe` test | What it pins | Test here |
|---|---|---|
| `engine/mod.rs:546` `blackout_blocks_triggers` | blackout ignores every other key until pressed again | `blackout-is-sticky-test` (Phase 1) |
| `:560` `solo_flash_released_after_group_change_to_overlay` | a flash is released by what the press started, even after the vibe changed | `flash-release-stops-its-own-voice-after-a-vibe-change-test` (Phase 1) |
| `:577` `phrase_gap_waits_for_flash_release` | an armed switch waits while FLASH is held, and happens on its release | `flash-release-while-armed-advances-test`, `held-key-defers-the-switch-test` (Phase 6) |
| `:590` `sub_key_hold_sustains_then_cuts_on_release` | a held key outlives its decay, and release cuts it | `long-hold-cuts-on-release-test` (Phase 1), `hold-suspends-decay-test` (Phase 1) |
| `:601` `releasing_main_does_not_cut_sub_cue` | one role's release leaves another role's cue alone | `releasing-hit-leaves-accent-playing-test` (Phase 1) |
| `:612` `force_next_keeps_lock_and_vibe_key_tap_vs_hold` | a tap goes next, a hold locks, and next keeps the lock | `vibe-tap-next-wraps-test`, `vibe-hold-locks-test`, `vibe-tap-next-keeps-the-lock-test` (Phase 1) |
| `:629` `disconnect_releases_held_keys` | an unplugged pad lets go | `midi-disconnect-releases-input-holds-only-test` (Phase 7) |
| `:640` `midi_channel_filter` | a channel filter ignores other channels | already there: `input/midi.clj:93-120` filters by `:channel-filter` |
| `engine/shuffle_bag.rs:64, 77, 88` | no back-to-back repeats, each item once per cycle, sizes 0 and 1 | `never-repeats-back-to-back-test`, `emits-each-item-once`, `edge-sizes-test` (Phase 1) |
| `engine/tap_tempo.rs:95, 103, 111` | steady taps, bounce, a pause keeps the tempo | `steady-taps-test`, `bounce-is-ignored-test`, `pause-keeps-the-last-tempo-test` (Phase 6) |
| `config.rs:188-334` `validate` (bad values, duplicate keys, empty pools) | a config is checked before it is used | the lint of Phase 3 (empty pool, missing cue, pool of one) |

## 3. What the app does today (probes)

Run with the command in §10. "Read" means from the code only; "ran" means a probe.

| Probe | Question | Result | So |
|---|---|---|---|
| v1 (ran) | Does `triggers.shuffle-bag` repeat a cue back to back? | Yes. Share of picks that repeat the previous one (20,000 picks, three runs within a point of each other): pool of 2 **about 25 %**, 3 **11 %**, 4 **6 %**, 5 4 %, 6 3 %, 8 1.5 % | a refill guard is a must (Phase 1). `beyond-vibe` has one (`engine/shuffle_bag.rs:33-37`) |
| v2 (ran) | Are input timestamps kept? | No. The mapper sends `:time 1000`; the handler sees the current time (cljfx 1.10.6 `cljfx/event_handler.clj:8-13`, `events/core.clj:462-467`) | vibe events read `[:input-event :timestamp]` |
| v12 (ran) | A tap whose release waits behind a busy agent | A finger down for about 100 ms measured **212–226 ms** in two runs and would count as a hold (threshold 150 ms) | same fix; and why the agent's I/O matters (§10 risks) |
| v10 (ran) | Cost of the agent hop | idle agent: median 42–84 µs, p99 0.2–2.1 ms (three runs). Behind a 200 ms effect: **about 180 ms** until the cue is in app state | the "take blocking I/O off the agent" item matters for live use (R-1) |
| v5 (ran) | Cost of one press or release through the whole pipeline | median 8–10 µs, p99 18–46 µs (10,000 events, no agent, three runs) | no faster path needed. Review 05 M4 beyond step 1 is not needed |
| v3 (ran) | What a project keeps | `[:config]`, `[:timing :bpm]`, `[:grid :size]`, hardware, chains. **Not** `[:triggers]`. A new key under `:config` round-trips; a file without it loads the default; editing it is undoable and unsaved. A change under a new top-level key leaves the document roots identical | vibes live at `[:config :vibes]`, runtime at `[:vibe-runtime]` |
| v4 (ran) | What blackout is | `:playing?` false, the cue intent stays, and the next hit sets `:playing?` true | blackout in the vibe layer must be sticky (V2) |
| v7 (ran) | Does a plain cue ever end? | No: 39.97 beats after 20 s, no `:ended?` | decay goes into the show clock (Phase 1) |
| v6 (ran) | Can learn capture a fader? | No. A CC leaves learn waiting; a note binds | CC learn (Phase 5) |
| v9 (ran) | Where do faders and notes go? | CC: input store only, 0 events to the app. Note: one `:trigger/input`. A fader nobody moved reads 0.0 | faders never wait on the agent; the macro needs a default |
| v8 (ran) | What an unmapped note leaves behind | 6 notes: 6 held inputs, 6 active triggers, 6 taps, all unread | vibe actions skip this bookkeeping |
| v11 (ran) | What a master effect costs | 1,000-point frame: static intensity 38–62 µs median, modulated 41–62 µs (p99 74–415 µs), 240–310 KB of garbage per zone frame. The high ends are from a run on a machine that was also running another JVM's tests (load about 3) | with 4 voices ≈ 0.15–0.25 ms of the 16.7 ms tick. *Unverified under show load*; re-measure in Phases 4 and 5 |
| v13 (ran) | Can a press reach a modulator? | No. A `:pulses` key put on a cue's timing context is dropped by `mod/make-context` and `make-base-context` (fixed key lists, `animation/modulation.clj:42-101`); `playback/clip-timing-ctx` keeps it; `:input-values` gets through only because it is on the list | Phase 4 adds `:pulses` to both lists |
| v14 (ran) | What the modulator editor's Retrigger does | Restarts **every** clock: two cues and the global clock from 3.97 beats to 0.03 in one tick | there is no per-effect restart; Phase 4's "Restart on" is one |
| v15 (ran) | Can a press re-fire an envelope? | A Decay (1.5 → 1.0 over a beat) on an effects-grid cell: 1.000 after 8 beats, still 1.000 after a press starts another cue. As a cue's own effect: 1.500 only on the cue just started | today a punch needs its own cue restarted (Phase 4) |
| v16 (ran) | Does a random vary per press? | A once-mode random gives 0.883 after each of five restarts (a hash of the step number) | Restart on mixes in the press count (Phase 4) |
| v17 (ran) | What does a tap reaction cost, and is it neutral at rest? A `:midi` modulator stands in for Envelope, Step and Energy: all resolve once per parameter (`animation/effects.clj:467-470`) | A chain of one effect on a 1,000-point frame: median 53–70 µs (p99 0.13–0.24 ms), 240–310 KB of garbage. Four effects (size, spin, colour, brightness): **216–228 µs**, about 1.5 MB, and **the same when they are all at rest**. Each effect alone at its rest value leaves the frame exactly as it was, except Hue Shift (5.6e-17 off: it goes through HSV). Three runs, all with another JVM running tests on the machine (load 2.5–4), so read the times as upper bounds | the cost is per effect, per zone frame of every playing cue (`services/frame_service.clj:399-403`), and an effect at rest is not skipped (R-5); identity tests compare to a tolerance (04 invariant 11) |
| v18 (ran) | What do the decay curves leave at a quarter, half and three quarters of a release? | linear 0.75 / 0.50 / 0.25; exp 0.28 / 0.08 / 0.02; log 0.98 / 0.92 / 0.72 (`animation/modulator_evaluators.clj:128-141`, k = 5) | an `:exp` release of ½ beat is over after about a quarter of a beat; 04 §7's Envelope numbers (1.091 at 0.1 beat, 1.019 at 0.25) match |
| v19 (ran) | What are a cue's zone frames, and who draws them? | One item routed to each of left, right and center: `{:left 21 :center 10 :right 49}` points, and each side projector sends only its own zone. A cue routed to `:all`: `{:all 33}`, which all three side projectors (each in `[:all …]`) send; the crowd and graphics projectors send nothing. The project's zone groups: `:all :center :crowd :graphics :left :right` | a zone frame is the unit a zone effect moves. A projector that loses its zone goes dark (`backend/streaming_engine.clj:373`) |
| v20 (ran) | Does a zone selector in an effects-grid cell do anything? | No: the zone frames are identical with and without an active cell holding one | the effects-grid editor offers an effect that does nothing; zone effects need a reader of their own (Phase 8) |
| v21 (ran) | What do chase, shift and swap do to real zone frames? (prototypes in the probe file) | A chase of the `:all` cue over left, center, right on beats 0 to 3: left, center, right, left, with one projector sending each time. Bounce: 0 1 2 1 0 1. A shift by 1 turns 21/10/49 points into 49/21/10, by 3 the same as by 0. A swap trades left and right. A Sawtooth 0→3 over 4 beats **falls** 3.00, 2.25, 1.50, 0.75 | the tables of 08 §7. Waves start at their peak, so the effects carry their own `:rate` |
| v22 (ran) | What if a picture were copied into two zones? | A projector in `[:all :left]`: the 33-point frame under `:left` alone sends 33 points, under `:all` and `:left` **68** | a remap moves and never copies (08 invariant 1) |
| v23 (ran) | What does a remap cost? | 1,000-point frames, two runs (load 1.8 and 2.5): shift 0.7–0.8 µs, swap 0.2 µs, a chase that only re-keys 0.3 µs (about 1 KB of garbage each); a chase that joins two frames 11–12 µs (p99 12–50 µs, about 8 KB) | nothing next to a point chain (v17); 08 invariant 12 |
| v24 (ran) | In what order do the effects grid's cells become one chain? | Six cells: the order they were put in. Twelve cells put in as 13 10 12 2 1 11 20 21 23 22 3 0 (row·10 + col) come out 22 0 1 11 3 20 2 13 12 21 23 10: hash order | zone effects don't commute, so they run in reading order (08 §7). The order of point effects is a separate finding (08 §9) |
| (read) | Is `:trigger/tick` ever dispatched? | No: only its handler and one test mention it (`grep`) | the rotation timer is new work (Phase 6) |
| (read) | Does anything read `:quantization` or `:cue-queue`? | No (`state/domains.clj:51, 71`; show clock §7) | no quantizer to build on; none needed (§9) |

Latency from a press to light, from the show clock audit (`plans/2026-11-17-show-clock/00-overview.md` §9): the next tick 8.3 ms typical and 16.7 ms worst, render and send 0.5–1 ms, and the DAC finishes its frame (up to 40 ms at 1,000 points and 25 kpps). Vibe mode adds one pure transition on the agent (about the cost of v5) and nothing on the tick except a comparison per run. Phase 4 adds a stamp per press and a small pulse view per tick; a reaction is stamped at the same tick its cue starts, so the punch and the cue land together.

## 4. Less mental load: what the performer stops deciding

| The performer used to decide | Vibe mode decides it by |
|---|---|
| Which of 32 cells | the pool, picked by a bag |
| Not showing the same thing twice | the bag's refill guard |
| How long it stays on | the vibe's decay in beats (or hold, or sustain) |
| How to make the look move with the music, not just change | the presses drive the effects: a punch, a step, a scatter, a swell (tap reactions, Phase 4) |
| How hard to push | tap faster: an Energy reaction grows the look and lets it settle when you stop |
| When to change the whole look | auto-advance on a phrase gap (Phase 6), or one tap |
| Where the panic button is | one sticky BLACKOUT key |
| What each key does in this look | it doesn't change: five fixed roles |
| Is the controller wired right | the pads show their binding and light while held |
| Is a cue going to hit the crowd | a rule (V6), not memory |
| How bright / how big | one master fader that works from the first second |
| Which projector shows it | a zone chase, swap or shift: on the beat, or on a key (Phase 8) |

### Design rules

1. **Fixed roles.** A key means the same in every vibe. Only the pool behind it changes.
2. **Curated choice.** Randomness only picks among cues the author already approved.
3. **No dead presses.** Every press shows something: the bag never repeats, a role with no cues still moves its reactions, and a role that does neither says so on screen.
4. **Time takes the slow decisions.** Beats end a hit; a phrase gap changes the feel; the performer only overrides.
5. **One sure kill.** BLACKOUT clears everything and ignores every other key until pressed again.
6. **Readable at a glance.** The Perform tab is made for a look from two metres: name, colour, five pads, two meters.
7. **Safe by default.** Audience-capable cues never enter a random pick unless the vibe says so (V6), and calibration never reacts to a press.
8. **Presses move the look, not only change it.** A press can start a cue, move effects, or both, and every effect that follows a press is an ordinary modulator you can open and edit.

### Grouping strategies considered

| # | Strategy | Setup | What the performer thinks | Verdict |
|---|---|---|---|---|
| G1 | **Explicit vibes with roles**: each vibe lists cells per role | pick cells per role | "which feel now?" then hit | **Recommended core** |
| G2 | **Row = vibe**: each grid row is a pool | none | the same | Not a model: a *generator* ("Make vibes from rows", Phase 3). Hidden conventions are the opposite of easy to use |
| G3 | **Energy ladder**: vibes tagged 1–4 and ordered low to high | one number per vibe | "more or less energy" | **Adopt as a tag** (`:energy`): it orders the vibe strip and the auto-advance. It does not become a slider (a slider that selects a vibe is two meanings on one control) |
| G4 | **Tag-driven vibes**: a vibe is a saved query over cue tags | tag every cue | the same | Later. Needs cue tags and an editor; G1 covers the need now |
| G5 | **Sets / acts** above vibes | a second layer | "which set?" | **No new layer**: one project per set already exists (save/open) |
| G6 | **Zone-scoped vibes**: a vibe overrides where cues go ("left only") | a zone per vibe | spatial scope as a third axis | Later; the render path would need a per-run zone override. Moving a look between zones, on the beat or on a key, is Phase 8's zone effects instead |

**G1 with rules of thumb for authors** (shown in the Vibes tab, Phase 3):

1. One *feel* per vibe: the same colours and energy; shapes may differ.
2. 3–6 cues in a HIT pool. Two repeat too visibly, and ten hides things you could cut.
3. ACCENT: one to three bigger or rarer cues.
4. FLASH: one dramatic cue **or** an FX set, not both.
5. Keep audience-capable cues out of pools.
6. 4–6 vibes per set, ordered low energy to high. Name them by feel, not by shape.
7. One or two tap reactions per role: a Punch or a Step on HIT, a Flip or a Scatter on ACCENT, a Swell or a Strobe on FLASH, a Fade in on VIBE. More blur together. To combine two on one parameter, stack two effects (two Scales multiply).

Worked example, a house-music set (data in `01-phase1-engine-core.md` §7; the reactions are `04-phase4-tap-reactions.md` §7's templates):

| Vibe | Energy | HIT pool (decay) | ACCENT | FLASH | BED / FX set | Tap reactions |
|---|---|---|---|---|---|---|
| Warm-up | 1 | slow waves, tunnels (sustain) | – | soft fan | slow haze cue | Fade in (VIBE), Energy: size |
| Groove | 2 | beams, fans, rings (1 beat) | tall beam (2 beats) | wall of beams | – | Punch: size (HIT), Flip (ACCENT) |
| Build | 3 | rotating shapes (1 beat) | zoom burst | strobe FX set | rising-speed FX set | Step: spin (HIT), Swell (FLASH) |
| Peak | 4 | big shapes (½ beat) | full fan (2 beats) | everything | – | Pump (HIT), Scatter (ACCENT), Strobe (FLASH) |

### The controls

| Button | On press | On release | Pool or target | Pulses (Phase 4) | Taps tempo |
|---|---|---|---|---|---|
| **HIT** | next cue from the HIT pool, never the previous one; stops the previous HIT cue | quick tap (< 150 ms): the decay runs. Held longer: stop now (`:mode :decay`). `:gate`: always stop. `:sustain`: stays until replaced | `:hit` | `:hit` (and `:cue` when it starts a cue) | yes (V3) |
| **ACCENT** | same, from the ACCENT pool; its own voice, so it layers over a HIT | same | `:accent` | `:accent` | no |
| **FLASH** | starts the flash cue and/or switches on the flash effect cells; with `:solo?` the other voices step aside | stops the flash, restores the voices that were ducked | `:flash` | `:flash`, held until the release | no |
| **VIBE** | records the press | under 600 ms: next vibe (wraps). 600 ms or more: lock or unlock auto-advance | `:order` | `:vibe`, on every switch (also `:vibe/select` and auto-advance); not on a lock (Fade in listens to it) | no |
| **BLACKOUT** | toggles. On: every voice stops, holds clear, other keys are ignored. Off: the bed returns | – | – | none, and no press pulses while it is on | no |

| Slider | Meaning | Where it acts |
|---|---|---|
| **1 Intensity** | master brightness. Full until moved | a starter effect cell "Master" whose `:intensity` amount follows the macro (Phase 5) |
| **2 Morph** | whatever the current vibe wires to it. Does nothing until wired | the current vibe's FX set (Phase 5) |

Any input source works the same way, because roles are ordinary trigger-map entries: a MIDI pad (works today), a gamepad (works today: its buttons are triggers and its two analog triggers are 0–1 axes that suit faders, `input/gamepad.clj:29-35, 53-82`), an OSC button from a phone (works today: unmapped addresses become triggers, `input/osc.clj:109-118`), the on-screen pads (Phase 2), and the computer keyboard once the keyboard plan's Phase 5 lands (no change needed here).

## 5. Target architecture

```
 MIDI note / gamepad / OSC / (keyboard later)
        │                                     on-screen pad (Phase 2)
        ▼                                            │
 input router ─▶ input mapper ─▶ :trigger/input      │
        │            │                │              │
        │            │      action {:type :vibe :role r}
        │            │                └───────────▶ :vibe/press | :vibe/release     (agent, pure, ~10 µs)
        │            │                                  │ writes: [:playback :active-cues] intents (+ :decay-beats :hold?)
        │            │                                  │         [:playback :pulses role] {:id n :held?}   (Phase 4)
        │            │                                  │         effect cells' :active?   [:vibe-runtime …]
        │            ▼                                  │ dispatches: :timing/tap-tempo (HIT)
        │   input store  [:midi 0 1] …                  ▼
        │            │  (+ [:macro :slider-1] via the macro map, Phase 5)
        │            ▼
        └───────▶ modulators read it ◀── show clock, 60 Hz ── reads intents ─▶ runs advance in beats
                  (:macro, :midi, …)         │                                  a decayed run ends
                  (:envelope, :step,         │                                  └▶ :show-clock/cue-ended (exists)
                   :energy, Restart on)      ├─ reads [:playback :pulses] ─▶ stamps each press on its pulse clock
                         ▲                   │     (Stop, Retrigger, align and shift never move it)   (Phase 4)
                         └── :pulses ────────┤─ pulse view (ages in beats) into every cue's and the effects grid's context
                                             │     (never into calibration)
                                             └─ rotation due? ─▶ :vibe/arm | :vibe/advance   (Phase 6)
                                             ▼
                                   render cues + active effect cells ─▶ engines ─▶ projectors
```

Rules: handlers stay pure and return state; no new effect key (with V9 A); the show clock stays the only writer of show time; faders never touch app state; a press reaches an effect only as a pulse the clock stamps, never by writing the effect's parameters (so a tap is never an undo step).

## 6. Phases and dependencies

| Phase | Depends on | Touches (existing files) | Risk |
|---|---|---|---|
| 1 | none | `animation/cue_timing.clj`, `animation/show_time.clj`, `events/handlers/triggers.clj`, `events/handlers/grid.clj`, `events/handlers/effects.clj`, `events/handlers.clj`, `state/domains.clj`, `state/persistent.clj`, `state/templates.clj`, `triggers/shuffle_bag.clj` | medium: the decay edits the clock's per-run update; pinned by `cue_timing_test.clj`, `show_time_test.clj` |
| 2 | 1 | `views/root.clj`, `subs.clj`, `css/core.clj`, `events/handlers/input.clj` (learn a role) | low |
| 3 | 1, 2 | `views/components/grid_cell.clj`, `events/handlers/history.clj` (labels), `state/domains.clj` (`[:ui :vibes]`) | low |
| 4 | 1 (2, 3 for the pad and Vibes tab parts) | `animation/show_time.clj`, `services/show_clock.clj`, `services/frame_service.clj`, `animation/modulation.clj`, `animation/modulator_evaluators.clj`, `animation/modulators.clj`, `animation/modulator_registry.clj`, `animation/keyframes.clj`, `views/components/modulator_param_control.clj`, `views/components/visual_editors/keyframe_modulator_panel.clj`, `views/components/grid_cell.clj`, `events/handlers/grid.clj`, `events/handlers/effects.clj`, `state/domains.clj` | medium: it touches every time-based modulator and the render's contexts; pinned by `modulation_test.clj`, `keyframes_test.clj`, `show_clock_test.clj` (`snapshot-shape-test`, `one-state-and-input-read-per-tick-test`) and "a saved modulator behaves as before" (04 invariant 3) |
| 5 | 1, 2 | `events/handlers/input_mapper.clj`, `events/handlers/input.clj`, `animation/modulators.clj`, `animation/modulator_evaluators.clj` | medium: CC learn, and the mapper reads app state per CC |
| 6 | 1, 2 | `services/show_clock.clj`, `animation/show_time.clj`, `triggers/tap_tempo.clj`, `events/handlers/timing.clj` | medium: the tap algorithm is shared with the TAP button |
| 7 | 1–6 | `events/core.clj` (middleware), `input_mapper.clj`, `README.md` | low |
| 8 | none for the effects; 1 and 4 for the reactions | `animation/effects.clj`, `animation/effects/zone.clj`, `services/frame_service.clj`, `services/show_clock.clj`, `views/components/effect_bank.clj`, `views/components/parameter_controls.clj`, `views/components/zone_chips.clj`, `views/dialogs/effect_chain_editor.clj`, `views/dialogs/cue_chain_editor.clj`, `views/components/visual_editors/timeline/browser.clj`; with vibes `vibes/library.clj`, `vibes/engine.clj`, `vibes/reactions.clj`, `subs.clj` | medium: one new stage in the render path and two new keys in the effect registry; pinned by `frame_service_test.clj`, `effects_test.clj`, `multi_engine_test.clj`, `show_clock_test.clj`, and "a project without zone effects renders exactly as before" (08 invariant 2) |

## 7. Decisions

Answer with letters (for example `V1: A, V2: A`) or in your own words. Anything unanswered is asked again before its phase starts. **V1–V9 answered 2026-11-18**: V8 B ("I think we should have a separate tab for the vibe stuff"), V9 A with a palette option on the Step modulator ("hue stepping could just be a step modulator. But also could have an option for a palette"), and V1–V7 A ("with the rest of the plan I agree with you"). **V10 is open** (Phase 8's reactions).

| ID | Question | Doc | Needed before | Answer |
|---|---|---|---|---|
| V1 | Which five buttons, and what happens to Latch | 00 §4, 01 | Phase 1 | **A**: HIT, ACCENT, FLASH, VIBE, BLACKOUT; latch becomes a per-vibe hit mode |
| V2 | What blackout means in vibe mode | 01 | Phase 1 | **A**: sticky in the vibe layer, on top of Stop (output runtime O4 stays as decided) |
| V3 | Does HIT tap the tempo | 06 | Phase 1 | **A**: yes, with a setting, and never while Link sync is on |
| V4 | Is auto-advance on by default | 06 | Phase 6 | **A**: off, one switch on the Perform tab |
| V5 | What the two sliders mean | 05 | Phase 5 | **A**: 1 Intensity (built in), 2 Morph (per vibe, unwired by default) |
| V6 | Audience-capable cells in random pools | 01 | Phase 1 | **A**: flagged in the editor and left out of picks unless the vibe allows them |
| V7 | The unused trigger-group scaffolding | 01 | Phase 1 | **A**: delete it in Phase 1 |
| V8 | Where the five pads live, now that the Grid tab is the performer's tab | 02 | Phase 2 | **B**: their own tabs (Perform to play, Vibes to set up); nothing on the Grid tab |
| V9 | How hue stepping works | 04, 05 | Phase 4 | **A**, with a palette: a Step modulator, whose values can be even steps or a list you type (a palette); no `:macro/set` effect |
| V10 | Can a vibe switch on a zone effect that sends a picture to the crowd | 08 | Phase 8, reactions | _pending_ |

> **DECISION V1: The five buttons** *(answered 2026-11-18: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | HIT, ACCENT, FLASH, VIBE (tap next, hold lock), BLACKOUT. Latch is a per-vibe `:mode` (`:decay`, `:gate`, `:sustain`) | five keys, nothing to remember; "stay on" is a property of the look (ambient looks sustain, punchy ones decay) | to keep one look on while you play the faders you pick a sustain vibe, not press a key |
> | B | HIT, FLASH, NEXT, PREV, BLACKOUT (no accent, a direction) | goes back a vibe | no second pool; the layered hit/accent feel is gone |
> | C | `beyond-vibe`'s six: A plus a LATCH key | exact parity | over your limit of five |
>
> **Your answer:** A (2026-11-18, "with the rest of the plan I agree with you")

> **DECISION V2: What blackout means** *(answered 2026-11-18: A)*
>
> Today `:blackout` is `:transport/stop` and the next cue start resumes (probe v4). Output runtime decision O4 was "no separate laser output button; blackout keeps stopping the transport" (`plans/2026-11-16-output-runtime/02-phase2-output-safety.md`, O4 C).
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | The vibe layer keeps a sticky flag. On: stop every voice, clear holds, call `:transport/stop`, ignore the other four keys. Off: `:transport/play`, bring the bed back | keeps O4 as you decided it; no change to the render path; the keys behave like `beyond-vibe`'s | a click on the grid during blackout still starts a cue (as it does today) |
> | B | Add an output-mute flag the show clock honours (`[:playback :blackout?]`) | blackout holds for every trigger source; clocks keep running | reverses O4; touches the render path and its tests |
> | C | Just `:transport/stop` | nothing new | not sticky: the next HIT lights the room again |
>
> **Your answer:** A (2026-11-18)

> **DECISION V3: Does HIT tap the tempo** *(answered 2026-11-18: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Every HIT press taps the tempo (`:hit-taps-tempo?`, default on). Ignored while Link sync is on | `beyond-vibe`'s behaviour: your hits set the BPM | syncopated hitting disturbs the tempo (median of 5 limits it, Phase 6) |
> | B | Never. Tempo from Link, the TAP button, or a bound `:tap-tempo` key | hits are free | a sixth control, or the toolbar |
> | C | An adaptive follower that ignores taps far from the predicted beat | resists off-beats | more to explain and to test; later |
>
> **Your answer:** A (2026-11-18)

> **DECISION V4: Auto-advance default** *(answered 2026-11-18: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Off. A switch on the Perform tab turns it on (default 45 s, then a 2-beat gap) | no surprise change mid-song | you opt in |
> | B | On at 45 s as in `beyond-vibe` | least to do while playing | a calm vibe can change under a ballad |
>
> **Your answer:** A (2026-11-18)

> **DECISION V5: The two sliders** *(answered 2026-11-18: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | 1 Intensity: a starter "Master" effect cell, full until moved. 2 Morph: wired per vibe through its FX set, does nothing until wired | slider 1 always means "how much"; slider 2 is yours | slider 2 needs one setup step per vibe |
> | B | 1 Energy, picking the tier of vibes; 2 Intensity | "energy" is how you think about a set | one slider doing two jobs |
> | C | Both free; you wire everything | most flexible | no out-of-the-box fader |
>
> **Your answer:** A (2026-11-18)

> **DECISION V6: Audience-capable cells in random pools** *(answered 2026-11-18: A)*
>
> A cell is audience-capable if its cue's destination zone group is `:crowd` or contains a projector tagged `:crowd-scanning` (`state/domains.clj:271, 333, 359-362`).
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | The Vibes tab flags them. A pick never chooses one unless that vibe has `:allow-audience? true` | a random pick can't scan the crowd by accident | one checkbox when you mean to |
> | B | Warn only | never blocks you | the warning is easy to miss at 1 am |
> | C | Never allow them in vibes | simplest | no audience effects in vibe mode |
>
> **Your answer:** A (2026-11-18)

> **DECISION V7: The unused trigger-group scaffolding** *(answered 2026-11-18: A)*
>
> `[:triggers :groups :settings]`, `[:trigger-runtime :active-groups :shuffle-bags]`, `:trigger/tick`, `:trigger-group/toggle-lock`, `subs/trigger-group-state`, and the generic `triggers.core` / `definitions` / `state-machine` engine have no reader or dispatcher. `[:triggers]` is not saved (probe v3), so deleting it breaks no project file.
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Delete them in Phase 1, keep `shuffle-bag`, `tap-tempo`, `janitor`, `matcher` | one place for grouped triggers; answers review 05 L1 | five tests go with them (three in `test/laser_show/triggers/`, two group tests in `events/handlers/triggers_test.clj:32-50`) |
> | B | Leave them | no churn | two group models in one app |
>
> **Your answer:** A (2026-11-18)

> **DECISION V8: Where the five pads live** *(answered 2026-11-18: B)*
>
> You said the Grid tab was made for performers (2026-11-18). Phase 2 as first written adds a separate Perform tab, so someone playing the grid would have to switch tabs to see the vibe.
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | A compact **vibe bar** above the cue grid (vibe chip, five small pads, "→ next"; hidden while there are no vibes), and the Perform tab as the big, two-metre view. Same pads, same subscriptions | the Grid tab stays the one place to play; the grid under the bar shows which cells HIT just picked; the big view is still there for busking from across the room | one more component (`views/components/vibe_bar.clj`, about half a day); the Grid tab's layout must leave room for it (the UX plan's Phase 2) |
> | B | Only the Perform tab, as first written | one purpose per tab | the performer's tab doesn't show the vibe |
> | C | Only the Grid tab: the pads above the grid, no Perform tab | one place, nothing to switch | the grid and the preview leave the pads too small to read from two metres |
>
> **Your answer:** B (2026-11-18): "I think we should have a separate tab for the vibe stuff." The pads live on the Perform tab, setup on the Vibes tab, and nothing is added to the Grid tab

> **DECISION V9: How hue stepping works** *(answered 2026-11-18: A, with a palette)*
>
> As first written, a vibe had a `:palette`, and each HIT wrote the next hue to a `:hue` macro slot through a new `:macro/set` effect (Phase 5). Phase 4's Step modulator does the same on any parameter.
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | A Step modulator ("Step: colour", a tap reaction) on a hue effect in the vibe's FX set. No `:palette`, no `[:vibe-runtime :palette-idx]`, no `:hue` slot, no `:macro/set` | one mechanism for every press-driven change; nothing in the engine; it can step any role and any parameter; editable in the modulator editor | a palette of uneven hues (200°, 215°, 320°) needs a list editor, later; even steps cover the examples |
> | B | Keep the palette and `:macro/set` as first written, beside the Step modulator | an exact port of `beyond-vibe`'s `CycleColorPalette` | two ways to step a colour; one new effect key and engine code for one case |
>
> **Your answer:** A with a palette (2026-11-18): "hue stepping could just be a step modulator. But also could have an option for a palette." The Step modulator takes either even steps or a typed list of values (`04-phase4-tap-reactions.md` §7); there is no `:macro/set` effect

> **DECISION V10: Zone effects and the audience** *(pending)*
>
> V6 keeps a random pick from choosing a cue that can scan the crowd. A zone effect can send a picture there too: a chase that lists the crowd zone sweeps every cue through it, and a vibe switches its FX set on by itself (`08-phase8-zone-effects.md` §7, "The audience rule"). The crowd zone is `:crowd`, or any zone of a projector tagged `:crowd-scanning`; a zone only counts where the picture *goes* (`:zones`, `:zone-a`, `:zone-b`), not where it comes from (`:from`).
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | A vibe doesn't switch such a cell on unless it has `:allow-audience? true`. The Vibes tab flags it (lint error), Perform says why. Clicking the cell on the effects grid is never blocked | the same rule as V6, so one thing to remember: a vibe never reaches the crowd by itself unless you said so | one checkbox when you do mean it |
> | B | Warn only (the lint row, no block) | never blocks a vibe | at 1 am a missed warning sweeps the audience |
> | C | The zone list can't hold an audience zone at all (the picker hides them) | nothing to remember | no audience chase, even by hand |
>
> **Your answer:** _pending_

## 8. Ownership with other plans

| Item | This plan does | Stays with |
|---|---|---|
| Input timestamps (review 02 #10, 05 M4) | vibe events read the input's own timestamp (v2, v12) | the rest of M4: composing dispatch, history short-cuts. Probe v5 says they are not needed |
| Tap tempo (05 F14, M6) | Phase 6, one algorithm | – |
| Blackout (05 M5, output runtime O4) | the vibe layer only (V2 A) | the master output switch stays refused |
| The `triggers/` engine (05 F15, L1) | keeps the bag, tap tempo, janitor, matcher; deletes the rest (V7 A) | – |
| Keyboard as a source (keyboard plan Phase 5, K-13, K-15) | nothing: roles are trigger-map entries and the wizard is source-agnostic | that plan, including "learn also fires the old binding" (K-15), which also affects re-learning a role |
| The agent's blocking I/O (review overview "Next") | nothing; it is the biggest risk to live feel (§10) | that plan |
| The show clock | extends it: per-run decay in `step`, vibe reports in `tick!`, the pulse clock and the stamps (Phase 4) | – |
| "Once" on the effects grid counts from the clock's start (modulator time base §9) | gives effects-grid modulators a press to count from (Restart on, Phase 4) | a start time per effect cell, if still wanted |
| Live actions and undo (review 02 T3) | a press never writes an effect parameter (§5 rules) | – |
| Velocity (05 F18) | nothing yet; an Envelope's depth from velocity is the natural next step | review 05 |
| The Grid tab as the performer's tab (`plans/2026-11-18-ux-improvements/`) | nothing on the Grid tab (V8 B): the vibe stuff has its own tabs | the Grid tab's layout and scaling (that plan's Phase 2) |
| Zone routing (`routing/zone_effects.clj`, the zone selector) | Phase 8 adds a remap stage **after** the item routing and leaves the selector's static routing alone; it hides the selector from the effects-grid editor, where it does nothing (probe v20) | per-item routing, tracks, the Zone Groups tab |
| Zones and projectors setup (`plans/2026-11-18-zones-ux/`, a draft: its §4.5 and question 20 ask for zone chase and for pulses to drive it) | Phase 8 builds three zone effects on today's model: a re-key of a cue's zone frames after routing, effects grid only. A HIT can step a chase, which answers its question 20 for today's model. It touches no zone setup, safety zones or routing | that plan: the Rig tab, safety zones, per-output weights and distribution, roles and layouts. If it adopts weights, the effects keep their parameters and are re-expressed as weights (08 §9) |
| The cue and effect chain editors (UX plan Phase 2 reshapes their layout and dialogs) | Phase 8 changes one prop in each (`:scope`, `:include-zone?`), nothing about layout | that plan's Phase 2; whichever lands second rebases the line numbers |
| The order of point effects across effects-grid cells (probe v24: insertion order up to 8 cells, hash order after) | nothing: only zone effects get a defined order (reading order) | a separate change, because it alters how existing projects with many cells look (08 §9) |

## 9. Out of scope, and alternatives considered

- **Keep `beyond-vibe` and drive this app over OSC.** Rejected: two apps and two configs; the engine needs this app's grid, effect cells, BPM and runs, so most of the engine would be rewritten around an OSC surface anyway. `beyond-vibe` stays as it is for BEYOND users, and its model is what this plan ports.
- **Run the vibes on `triggers.core` (state machines and trigger definitions).** Rejected: five fixed roles do not need a generic machine, its `:history` grows without bound, and its `fn?` matchers can't be saved (review 05 F15).
- **Decay with `:dispatch-later`.** Rejected: a thread per tap, and wall time instead of beats (it would not follow a tempo change).
- **Quantize a press to the next beat.** Rejected: up to a beat of added delay. Cues already glide their phase onto the beat grid (`animation/cue_timing.clj:23-56`).
- **A render-path blackout flag.** V2 B.
- **Row = vibe as the model.** G2.
- **Controller LED feedback (MIDI out).** The app has no MIDI output. Worth its own plan.
- **A pop-out Perform window**, **velocity → intensity** (review 05 F18), **zone-scoped vibes** (G6), **tag-driven vibes** (G4), **importing `vibes.toml`**: later.
- **User-visible quantization settings** (`:quantization`, `:cue-queue`): dead keys, left alone (show clock §7).
- **A press that writes an effect's parameters** (a `:chain/update-param` per tap): rejected, every tap would be an undo step and a document change, and it can't make an envelope (`04-phase4-tap-reactions.md` §9).
- **Uneven step lists, a "next keyframe per press" driver, a fader that scales a reaction's depth**: later (04 §9).
- **Zone effects on items, tracks and clips; a picture copied to several zones; a left-to-right order on zone groups; a reaction for one voice only**: later (08 §9).

## 10. Risks, and re-running the probes

| # | Risk | Mitigation |
|---|---|---|
| R-1 | A save, load or device connect blocks the event agent, and every key waits (probe v10: about 180 ms), and a release that waits makes a tap look like a hold (v12) | Phase 7's show lock refuses save, open and new while it is on; the real fix is the review's "Next" item |
| R-2 | A lost release leaves a key "held" (unplugged pad) | BLACKOUT clears holds; CC 120/123 and a device disconnect release all; a hold watchdog (Phase 7) |
| R-3 | A vibe points at a cell that moved or was cleared | `vibes.refs` rewrites every reference on move and clear; lint flags the rest (Phase 1, 3) |
| R-4 | Random picks scan the audience | V6 |
| R-5 | The master effect and every tap reaction cost time on every cue and zone (v11, v17): four effects are about 0.22 ms and 1.5 MB of garbage per zone frame, at rest or not | Four cues in one zone with a four-effect FX set is about 0.9 ms of the 16.7 ms tick and 6 MB of garbage (0.35 GB/s), twice that with two zones. *An estimate from v17, unverified under show load.* Keep a vibe's FX set to a few effects; watch frame time and GC pauses in hardware check 12 (Phase 7); measure with the real types in Phases 4 and 5. Applying global effects once per tick is a separate render-path change (the global effects run at each cue's own tempo, `services/frame_service.clj:398`) |
| R-6 | An unmoved fader reads 0.0 (v9) and darkens the show | the macro modulator has a `:default`, 1.0 for Intensity |
| R-7 | The shared tap-tempo change alters the TAP button | Phase 6 keeps its tests and adds the `beyond-vibe` ones |
| R-8 | A reaction that moves the picture (Scatter, Swell, Punch) pushes beams toward the audience | calibration (the corner pin) runs after every reaction and never sees a press (04 invariant 4); the templates use small ranges; V6 still decides which cues play; hardware check 12 (Phase 7) |
| R-9 | "Restart on" changes how existing modulators behave | its default is `:own`, so every saved modulator evaluates exactly as before (04 invariant 3, `restart-on-own-is-unchanged-test`) |
| R-10 | A zone effect sends a look to the crowd, and a vibe switches it on by itself | V10: a vibe doesn't switch on a zone cell that lists an audience zone unless it allows the audience; the lint says so; Perform says why a cell stayed off (08 §7) |
| R-11 | A chase, swap or shift leaves a projector dark or sends a picture to a zone nobody listens to (a zone group deleted, a projector in none of the listed zones) | zone ids that no longer exist are skipped at render; the lint names the projector and the zone (08 §7); hardware check 13 (Phase 7) |

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
P=plans/2026-11-18-vibe-mode
clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" \
    -M:mac-arm:test:probe -d "$P/probes" \
    -n probes.vibe-probes-test 2>&1 | grep PROBE
```

Use `:win`, `:linux` or `:mac` in place of `:mac-arm` on other machines. It takes about 60 seconds. To run only some, add their numbers as name patterns (for example `v19 v20 v21 v22 v23 v24` after `-n probes.vibe-probes-test`). When a phase fixes a behaviour, turn its probe into a real test that asserts the new behaviour; each phase doc names those tests. **A probe stops reproducing its number once the phase that fixes it lands**: with Phase 1's edits in the working tree, v1 ends in an error (the shuffle bag has a new signature) and v3's `[:triggers]` lines read nil (the scaffolding is gone). That is the point of them. To get the numbers in §3 again, run the probes in a checkout of `b1da0f2` (`git worktree add ../laser-idn-b1da0f2 b1da0f2`).
