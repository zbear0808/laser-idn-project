# Conflicts and challenges

Each entry covers two needs that pull against each other: why both matter, the ways to resolve it, and which way we lean. A leaning isn't a decision until its **Q** is answered.

## T-1: Automatic vs predictable
**Needs in tension:** N-3, N-4, N-12 vs N-11, N-20.
- Automatic groups and distribution save setup time.
- But "magic" routing frightens people running a programmed show (S-5). If content moves because someone dragged a projector card, that is a bug from where they sit.
- **Ways out:**
  - automatic only ever **adds** defaults, and anything set by hand wins;
  - automatic groups are locked and named after where they come from ("Left (auto)");
  - every target can explain itself (N-20);
  - a "freeze automatic groups" switch for a rehearsed show.
- **Lean:** hand-set wins, plus explain-why. Allow freezing, but don't make it the default.

## T-2: Zero-config defaults vs different images
**Needs in tension:** N-1 vs N-16.
- The safest default is "everything does the same thing".
- But that is exactly the boring result the user complains about.
- **Ways out:**
  - default to unison, and offer a one-click show-wide distribution (mirror or alternate);
  - default to a gentle variation such as mirror on 2 outputs;
  - cues carry a *suggested* distribution that only applies when the rig allows it.
- **Lean:** unison by default, plus a show-level distribution setting that is easy to find. **Q-a:** should a two-projector rig mirror by default?

## T-3: Cues that don't know the rig vs exact authoring
**Needs in tension:** N-12, N-13 vs N-11.
- For cues to survive any rig, they have to point at something abstract: "All", a role, "the graphics output".
- But a programmer wants "this text on *that* laser".
- Abstract targets need a mapping layer, which is one more concept. Concrete targets break when the rig changes.
- **Ways out:**
  - purpose-based groups (by tag, such as Graphics or Audience) are both abstract and exact;
  - position-based groups (Left, Center) survive rig changes;
  - roles mapped by a layout (option B);
  - allow pinning to one output by ID, and warn when the rig no longer has it.
- **Lean:** targets are **groups**, and the built-in groups are purpose-based and position-based, so they survive rig changes. Roles and layouts only if that turns out not to be enough. **Q-b:** do you think in places (left, center) or in layers (A/B, main + accent)?

## T-4: Expressiveness vs CPU and point budget
**Needs in tension:** N-16, N-18 vs N-24, N-25.
- Per-output phase offsets and colours mean rendering each cue once per output.
- With 8 outputs × 6 cues that is 48 renders per tick, against 6 today.
- **Ways out:**
  - three cost tiers (gains ≈ free, a transform after one render is cheap, a full render per output is expensive);
  - only render per output when a parameter actually reads an output source;
  - cache renders by output-equivalence class (mirrored pairs share one render).
- **Lean:** build the tiers, and make tier 3 opt-in and visible ("this cue renders per output"). **Q-c:** how much CPU headroom is there?

## T-5: Safety that can't be bypassed vs creative freedom
**Needs in tension:** N-7, N-8 vs N-16, N-18.
- Effects (corner pin, lens distortion, spread across a wide canvas) can push content out of where it was drawn.
- Clipping late keeps the audience safe, but it can cut the image apart in surprising ways.
- Clipping early means effects can undo the safety.
- **Ways out:**
  - safety is always the final stage, with no exceptions;
  - the editor previews the clip so the artist sees it;
  - optionally, "fit inside the safety zone" scaling at the geometry stage, which runs before the clip and doesn't replace it.
- **Lean:** final, can't be bypassed, preview the clipping. Fit-to-zone is optional. This one isn't up for negotiation.

## T-6: Several zones on one projector vs simplicity and point budget
**Needs in tension:** S-3 (graphics + audience from one unit) vs N-2, N-25.
- A projector that carries a graphics zone and an audience zone with its own safety is powerful.
- But two outputs share one galvo budget and one frame.
- **Ways out:**
  - one output per projector in v1, with the model allowing more later;
  - several outputs per projector, joined into one frame, with a shared budget meter.
- **Lean:** allow several outputs in the model (the virtual-projector work already heads that way), and the UI shows one until you ask for a second. **Q4.**

## T-7: Overlapping groups vs load
**Needs in tension:** N-3 (many automatic groups) vs N-25.
- Today a projector in two groups draws both groups' content, joined together, so its points double (`backend/multi_engine.clj:119-150`).
- With automatic groups, almost every output sits in several groups.
- **Ways out:**
  - de-duplicate: content sent to "All" and to "Left" for the same cue renders once per output;
  - a budget policy when it overflows: warn, scale the point count down, or drop the lowest-priority content;
  - routing as gains per output (an output either receives a cue or it doesn't; it never receives the cue twice).
- **Lean:** treat a target as a *set of outputs*, never as a list of streams to join. An output gets each cue at most once. Show the load.

## T-8: Where the rig lives: show file vs separate
**Needs in tension:** N-21 vs "one file is the whole show".
- Today projectors and groups are in the project's `hardware.edn`, and New Project wipes them.
- A separate venue file survives new shows and touring, but opening a show can then look different on another machine.
- **Ways out:**
  - the rig is its own file, and the show remembers the last rig it ran on;
  - the rig is inside the show, with import and export;
  - split it: physical setup (hosts, safety, calibration) per machine or venue, and creative grouping per show.
- **Lean:** split it. Safety and calibration belong to the venue and must never be overwritten by opening a show. **Q8.**

## T-9: Inheritance vs discoverability
**Needs in tension:** N-14 vs N-15, N-20.
- A cascade (show → column → cue → track → item) saves clicks.
- But it makes "why is this on the left?" hard to answer.
- **Ways out:**
  - one target chip at every level that always shows the effective value and where it came from;
  - grid badges;
  - a "reset to inherit" action.
- **Lean:** cascade, but with no more than 4 levels, and always explain where the value came from.

## T-10: Modulating the grouping vs stable routing semantics
**Needs in tension:** N-18 vs N-11, N-15.
- Modulating routing (keyframes, LFOs, pulses) turns a target from one value into something that changes over time, with blends in between.
- A pinned target must stay put.
- **Ways out:**
  - routing is a gain from 0 to 1 per output; hard routing is the case where every gain is 0 or 1;
  - zone effects (chase, sweep, solo) change the gains *inside* the current target set and never widen it;
  - the order is pin > modulation > inheritance.
- **Lean:** zone effects can only narrow or shape the gains inside the target, never send content outside it. Safety stays on top.

## T-11: Positions accurate enough vs low setup effort
**Needs in tension:** N-5 vs spread, mirror and chase quality.
- Left-to-right order is enough for chase, odd/even and left/right.
- Spread (one wide canvas) and real mirroring need angles and overlap, which take a long time to measure.
- **Ways out:**
  - v1 uses order and side only;
  - templates fill in rough angles;
  - an optional "fine-tune coverage" step.
- **Lean:** order and side are required; angles are optional. **Q-d.**

## T-12: Live timing across projectors
**Needs in tension:** N-18 vs physical reality.
- Projectors differ in scan rate, network latency and engine wake time.
- A chase or an alternate on the beat across several projectors can look ragged.
- **Ways out:**
  - every output takes its frame from the same show-clock tick (that's how it works today);
  - an optional latency offset per output;
  - measure it with Identify.
- **Lean:** use the same tick everywhere, and add a latency offset only if it turns out to be needed.

## T-13: Old data vs cleanup
**Needs in tension:** N-23 vs N-15.
- Today there are four ways to set routing: the item's `:zone-group-id`, the Zone Selector effect, the track zone and the cue default.
- Merging them into one breaks old files, unless we convert.
- **Ways out:**
  - convert on load (fold the Zone Selector into the item target);
  - keep reading the old fields but stop writing them;
  - hide the Zone Selector from the effect bank.
- **Lean:** convert on load, and keep a reader for the old fields. **Q17.**

## T-14: Undo vs hardware state
**Needs in tension:** a consistent undo vs "discovery isn't an edit".
- Discovered projectors are kept out of undo (`events/handlers/projector.clj:159-186`).
- Positions, safety and groups are user edits and should be undoable.
- But undoing a safety edit during a live show is a safety event of its own.
- **Lean:** rig edits are undoable. Safety edits are flagged as safety changes in the history, and need confirming while the output is live. **Q-e.**

## Open questions collected here

| Q | Question | Conflicts it decides |
|---|---|---|
| Q1 | How many projectors do you usually run, and in what layout? | T-4, T-11, scenarios |
| Q4 | Several outputs (zones) on one projector? | T-6 |
| Q5 | Which audience limits: brightness cap, minimum scan speed, no static beams? | N-10, T-5 |
| Q6 | If safety fails: go dark, or hold the last good frame? | N-9 |
| Q8 | Rig inside the show, separate, or split? | T-8 |
| Q10 | Where to set default targets: column, page, vibe, show? | T-9 |
| Q17 | Must old shows load unchanged, or is a one-time conversion fine? | T-13 |
| Q-a | Should two projectors mirror by default? | T-2 |
| Q-b | Do you think in places or in layers? | T-3 |
| Q-c | CPU headroom for rendering per output? | T-4 |
| Q-d | Are order and side enough, or do real coverage angles matter? | T-11 |
| Q-e | Should a safety edit on a live output need confirming? | T-14 |

The Q numbers match `plans/2026-11-18-zones-ux/00-overview.md` §8 where they overlap.

**Update 2026-11-19:** the decisions in `07-plan.md` §1 change three leanings:
- **T-3:** cues target zones A–D, and the performer switches zone maps live, so the roles idea is now part of the core.
- **T-6:** a laser can be split into at most 2 regions.
- **T-11:** v1 uses the left-to-right order only.

The questions still open are collected in `07-plan.md` §9.
