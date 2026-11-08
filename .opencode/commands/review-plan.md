---
description: Run parallel per-area review agents and produce an improvement plan
---

Run a parallel, read-only improvement review of this codebase (Clojure + cljfx/JavaFX laser show app that outputs over ILDA IDN).

Optional focus or area filter from the user: $ARGUMENTS
(If empty, review all six areas below. If it names areas, only run those. Any other text is an extra focus that every agent should weigh.)

## Setup

1. Let `DATE` be today's date (YYYY-MM-DD) and `OUT` be `plans/DATE-improvement-review/` at the repo root.
2. Skim `plans/` for earlier `*-improvement-review/00-overview.md` files. If one exists, tell each agent its path so it can mark earlier findings as fixed, still open, or regressed, instead of rediscovering them.

## Launch agents

Launch one `general` subagent per area **in a single message with `background: true`**, so they all run in parallel. Do not do their work yourself while they run.

| # | Area | File | Scope |
|---|------|------|-------|
| 01 | Views / UI / CSS | `01-views-ui.md` | `src/laser_show/views/**`, `src/laser_show/css/**`, how views consume subs. Component structure, reuse, render performance, stale closures, CSS consistency. |
| 02 | Events / state | `02-events-state.md` | `src/laser_show/events/**`, `src/laser_show/state/**`, `src/laser_show/subs.clj`, `src/laser_show/app.clj`, `src/laser_show/common/**`. Handler design, state shape, subscriptions, side-effect isolation, undo/persistence. |
| 03 | Animation / effects | `03-animation-effects.md` | `src/laser_show/animation/**`, `resources/effect-presets/**`. Effect/modulation pipeline, per-frame performance, composability, preset format. |
| 04 | Output pipeline | `04-output-pipeline.md` | `src/laser_show/idn/**`, `backend/**`, `routing/**`, `services/**`, `profiling/**`, `java/**`, IDN spec PDFs in `docs/`. Protocol correctness, timing/threading, network errors, zone routing, hot-path performance. |
| 05 | Input / triggers | `05-input-triggers.md` | `src/laser_show/input/**`, `src/laser_show/triggers/**` and their tests. MIDI/OSC/keyboard/gamepad handling, trigger semantics, threading, latency, integration with events/state. |
| 06 | Testing / build / tooling | `06-testing-build-tooling.md` | `test/**`, `deps.edn`, `build.clj`, `.github/**`, `dev/**`, `src/laser_show/dev/**`, `dev_config.clj`, `experiments/**`, `legacy/`, `.clj-kondo`, install scripts, docs for running things. Coverage gaps, test quality, CI, packaging, lint, dead code, DX. |

Each agent's prompt must include its area, its scope, its output path (`OUT/<file>`), any prior overview path, any extra focus from the user, and these shared instructions:

> Produce an IMPROVEMENT PLAN for your assigned area only. DO NOT modify any source, test, or config files; the only file you may create is your plan file. You may run read-only commands and small REPL or `jshell` probes to confirm behaviour, but no long builds.
> First skim README.md, CONTRIBUTING.md and docs/ (especially CLJFX_STATE_MANAGEMENT.md and STALE_CLOSURES.md) for conventions. Check plans/ for existing plans in your area; reference them rather than duplicating them, and flag stale ones.
> Read the code in your area thoroughly. Look for bugs and correctness risks, performance issues (real-time output, so watch allocation and latency in hot paths), duplication and missing abstractions, unclear naming and structure, error-handling gaps, missing tests, and violations of the project's own conventions.
> Write Markdown with these sections: 1. Summary (3–5 sentences). 2. Findings: a numbered list, each with file:line refs, the problem, why it matters, and a severity (High/Med/Low). 3. Proposed improvements: Quick wins / Medium refactors / Larger efforts, each with concrete steps, files touched, effort (S/M/L) and risk. 4. Suggested order of work and dependencies on the other areas (views/UI, events/state, animation/effects, output pipeline, input/triggers, testing/build/tooling).
> Be specific and evidence-based; no generic advice. Say clearly which findings you verified by running something and which come from reading the code. Finish by replying with your top 5 recommendations and the plan path.

## After all agents finish

Write `OUT/00-overview.md` containing:
- A table of area plans with finding counts.
- **Cross-cutting themes**: issues reported by two or more areas, with one central fix each.
- **Ranked priorities** grouped into phases: Phase 0 safety net (tests/CI), Phase 1 quick correctness wins, Phase 2 structural fixes, Phase 3 performance and polish. Tag each item with the area numbers.
- Dependency notes and open decisions for the user.
- If a prior overview existed: a short "Since last review" section (fixed / still open / new).

Then give the user a concise summary of the top themes and phases, and ask which phase to start.
