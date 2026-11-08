---
trigger: model_decision
description: Instructions for writing a plan. Assume the user wants these whenever they ask you (or a subagent) to 'plan' a feature, refactor or fix. Subagents writing plans must be told to follow this file.
---

# Planning guide

A plan describes changes to *this* codebase. It must be grounded in the code as it is today.
**Don't write implementation bodies.** Do write data examples: EDN state shapes, event maps, and input → output tables. Those are what catch logic errors.

Before writing, grep `plans/` (especially the latest `*-improvement-review/`) for your area, so you don't duplicate an open item or prescribe a design that is already a finding.

## Rules

1. **Cite everything that exists.** Every existing namespace, function, event key, effect key and state path you mention gets a `file:line`. Anything new is marked **NEW**. If you can't find it, don't name it.
2. **Current behaviour first.** Describe what the code does now and the root cause before proposing any target design.
3. **Reuse before you create.** List what already exists that you'll reuse or extend. Justify every NEW namespace or function.
4. **State placement.** For each new or changed key, say:
   - **where it lives:** document (saved + undo), UI state, or a runtime atom (threads, devices, sockets, high-frequency data);
   - **who writes it:** a handler, an effect, or a background thread;
   - **whether it enters undo/dirty tracking.**

   Check against `docs/CLJFX_STATE_MANAGEMENT.md`. Never `swap!` app state from outside the event flow.
5. **Handlers are pure; side effects are registered effects.** List each side effect (file I/O, clipboard, dialogs, devices, network) as a named effect key, and say whether it already exists in `events/core.clj`.
6. **Trace the call path.** For every event you change or hook, name its dispatcher (`file:line`).
7. **Algorithms get examples and invariants.** Give 3–5 input → output examples as data, plus the invariants (e.g. "never removes items", "channels are 0-based internally"). These become the tests.
8. **One location per item.** No "A or B". Put alternatives in a separate *Alternatives considered* section.
9. **Performance claims need evidence.** Cite a benchmark or profiler run, or label the claim *unverified*. The output path is real-time, so name the thread and frame rate involved.
10. **Name the acceptance tests.** List them as test vars (`ns/test-name`) with their fixtures, not "verify it works". MIDI/OSC fixtures must match what the libraries actually emit.
11. **One copy, kept current.** No `v2/` or `v3/` copies. Edit in place and keep the status header accurate. When the work is done, mark it done and delete the plan; git history keeps it.

## Template

```markdown
# <Title>

**Status:** Proposed | In progress | Done   **Last verified against:** <commit>
**Supersedes / Superseded by:** <paths or none>   **Related findings:** <e.g. 02 #7, 05 F9>

## 1. Current behaviour (file:line)
## 2. Root cause
## 3. Reuse / extend
| Existing thing (file:line) | How it's reused |
## 4. Changes per namespace
- `laser-show.x.y` (existing, file:line): what changes
- `laser-show.x.z` (NEW): purpose; fns with their params
## 5. State placement
| Key / path | Lives in | Written by | Undo/dirty? |
## 6. Events and effects
| Event / effect key | Exists? | Dispatched from (file:line) | Pure? |
## 7. Data examples and invariants
## 8. Tests (named test vars + fixtures)
## 9. Out of scope / alternatives considered
```
