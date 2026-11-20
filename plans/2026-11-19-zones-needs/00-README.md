# Zones and projectors: needs, conflicts and core features

**Written:** 2026-11-19   **Written for commit:** `67735d0` (`master`)
**Status:** Draft. This folder covers requirements only. It says what the zone system has to do, where those goals pull against each other, and which features are essential. It deliberately does not pick a design. The design options are in `plans/2026-11-18-zones-ux/00-overview.md`, and they should be re-checked against this folder once it settles.
**Related:** `plans/2026-11-18-zones-ux/` (options A/B/C), review 02 #17 / M9, review 04 (calibration pattern, zone precedence), vibe plan G6 / R-5

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`.

## Why this folder exists

Setting up more than one laser is one of the worst parts of running a show. Right now it means:
- making groups by hand;
- ticking projectors into those groups, in two different places;
- picking a group inside every single cue.

If you skip all of that, every laser draws the same picture. There are no safety zones at all. Before choosing a design, we need to agree on what "good" means and which trade-offs we are willing to make.

## Docs

| Doc | What it holds |
|---|---|
| `01-scenarios.md` | The real situations the system has to handle, from one laser at home to a touring rig with audience scanning |
| `02-needs.md` | Numbered needs (N-*), each with a priority and a "done when" test |
| `03-conflicts.md` | Places where needs pull against each other (T-*): both sides, the ways out, and which way we lean |
| `04-core-features.md` | The smallest set of features (C-*) that solves the problem, what each one serves, and what is left out on purpose |
| `05-baseline.md` | What the code does today, and the bugs to fix whatever design we choose |
| `06-zone-patterns-and-split-zones.md` | **Wanted, not core.** Cues that switch between left/right, odd/even or a chase on any number of lasers, splitting one laser into sub-zones when there are fewer lasers than zones |
| `07-plan.md` | **The plan.** Decisions D-1 to D-8, the model (rig order, automatic groups, zones A–D, zone maps, split lasers, fit guard), the pipeline, the UX, phases Z0 to Z6, migration, how it fits with vibe Phase 8, and the remaining questions |
| `08-rig-setup-ux-future.md` | Future improvements to rig setup beyond the v1 ordered strip (written by a parallel agent) |
| `09-questions.md` | The open questions on the plan, each with context, options and a recommendation |

## Words used in these docs

Some of these words are new, and some mean something different from what they mean in the code today.

| Word | Meaning here |
|---|---|
| **Projector** | One physical IDN service: a host, a service ID, an engine |
| **Output** (or *projection zone*) | Where content actually lands. It is a projector plus its geometry, safety zone and tags. One projector may carry more than one output (see T-6) |
| **Safety zone** | The limits on an output that content and effects can't override: the scan limit, blocked regions and caps |
| **Group** | A named set of outputs. It can be **automatic** (generated from the rig) or **custom** |
| **Target** | Where a cue, track or item sends its content: a group, or something more abstract (see T-3) |
| **Distribution** | How one piece of content spreads over the outputs in its target: unison, mirror, alternate, chase, spread, offset |
| **Rig** | The physical setup: projectors, outputs, positions, safety, calibration |
| **Show** | The creative content: cues, effects, vibes, targets |

## How to use this

1. Read `02-needs.md` and `03-conflicts.md`, then mark any priority or leaning you disagree with.
2. Answer the open questions. They are tagged **Q** inline and collected at the end of `03-conflicts.md`.
3. Freeze `04-core-features.md`. That becomes the scope that any option in `plans/2026-11-18-zones-ux/` has to deliver.

**Next step (2026-11-19):** the implementation plan is in **`plans/2026-11-19-zones/`**: an overview, the model and routing spec, and one doc per phase (Z0 to Z6, plus ZR).
