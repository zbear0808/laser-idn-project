# Core features

> **Update 2026-11-19:** `07-plan.md` turns these into phases Z0 to Z6, with three changes:
> - C-4 becomes an ordered left-to-right strip (D-7).
> - Zones A–D and zone maps join the core in Z4 (D-5).
> - Split lasers and the fit guard join in Z3 (D-1, D-2).

This is the smallest set of features that solves the problem. Every Must need in `02-needs.md` is covered by at least one feature here. Anything not on this list is later work (see the end of this doc).

## Core

| ID | Feature | Serves | Today |
|---|---|---|---|
| C-1 | **Outputs as one unit.** Projector + geometry + safety + tags is the thing you place, group and target | N-2, N-7, T-6 | Projectors only. Virtual projectors are half built |
| C-2 | **Safety stage + editor.** A scan limit and blocked regions for each output, clipped and blanked, run last, fail closed, edited live on the real test pattern | N-7, N-8, N-9 | Missing. Calibration outputs nothing |
| C-3 | **Working Identify / test pattern** that respects safety and runs at a set low brightness | N-5, S-8 | Missing (the button exists) |
| C-4 | **One Rig screen.** Connection, placement (order and side, with templates), geometry, safety, colour and groups in one place | N-2, N-5 | Split across two tabs, and assignment happens in both |
| C-5 | **Implicit All + automatic groups.** All = every enabled output. Left / Right / Center / Odd / Even / by-tag come from the rig and update when it changes | N-1, N-3, N-4 | `:all` is a group you have to tick. The other built-in groups start empty |
| C-6 | **Targets as sets, at most once per output.** An output gets each cue at most once, whatever groups overlap | N-25, T-7 | Overlapping groups join the content and double the points |
| C-7 | **One target control, with inheritance and an explanation.** A single chip at the show, column, cue and track/item levels, showing the effective value and where it came from. Grid badges | N-14, N-15, N-20, T-9 | Four routing methods, set per cue, with no badges |
| C-8 | **Cheap distribution.** Unison, Mirror, Alternate and Chase, set at the show level and on cues/tracks; render once and change only gains or a flip per output | N-12, N-16, N-24 | Missing |
| C-9 | **Zone modulation that plays.** Keyframed and modulated targets, and chase on beat or pulse, actually route | N-18, T-10 | The editor offers it; playback ignores it |
| C-10 | **Per-output preview + load meter** after calibration and safety | N-17, N-25 | No view of a single projector |
| C-11 | **Safe references + migration.** Group delete asks first and re-targets; old fields are converted on load | N-22, N-23, T-13 | Deleting a group leaves dangling references |

## The core in one sentence

Place your outputs and draw their safety zones in one screen. Groups make themselves. Cues default to All and inherit their target from above. One show-level switch makes the lasers mirror, alternate or chase. Every output shows what it really draws, and safety always has the last word.

## Order the core features depend on each other

```
C-1 ──> C-2 ──> C-3
  │       └──> C-10
  └──> C-4 ──> C-5 ──> C-6 ──> C-7 ──> C-8 ──> C-9
                                  └──> C-11
```

C-2 and C-3 are needed whatever design we pick. C-6 changes how routing works inside the engine (sets and gains instead of joining group frames), so C-8 and C-9 depend on it.

## Left out on purpose (later, or only if needed)

| Feature | Why not core |
|---|---|
| Roles + layouts (option B) | Purpose- and position-based automatic groups may already make cues survive rig changes (T-3). Revisit after C-5 to C-8 are built |
| Rendering per output (output-index / output-x modulation sources, phase spread) | Costs CPU (T-4). C-8 covers most of the visual variety |
| Zone patterns + split zones (`06-zone-patterns-and-split-zones.md`) | Wanted, but it builds on C-5 to C-9. C-8's Alternate and Chase should be designed as the simple form of a zone pattern, so this can be added later without a rewrite |
| Spread over one wide canvas | Needs coverage angles (T-11) |
| Identify walk (click where each laser lit up) | Templates + drag first. Add only if placement is still slow |
| Live zone pad strip | Should, not Must (N-19). Easy once C-8 and C-9 exist |
| Audience limits beyond scan limit and blocked regions | Waits on Q5 |
| Latency offset per output | Only if T-12 turns out to be visible |
| Several outputs per projector in the UI | The model allows it (C-1). The UI waits on Q4 |
| Rig file separate from the show | Waits on Q8. C-1 to C-5 shouldn't depend on where the rig is stored |
