# 07 — Audit of the old planning documents (2026-11-08)

Scope: everything in `plan/` and `plans/` except `plans/2026-11-07-improvement-review/`, plus a one-line verdict on each file in `prompts/`.
Method: every namespace, function, event key and state path a plan names was checked against `master` with grep or by reading the file. Claims made in 00–06 about these plans were re-checked, not copied.

Nothing was deleted or moved. `plans/` is gitignored (`.gitignore:51`), so deleting from it can't be undone. `plan/Modulation-Optimization-Plan.md` **is tracked** in git (commit `90b0edd`).

Legend: **Impl** = implemented, **Partial** = partially implemented, **Not started**, **Superseded**, **Obsolete** = the code it targets never existed or no longer exists.

## Summary table

| File | Status | Quality | Recommendation | One-line reason |
|---|---|---|---|---|
| `plan/Modulation-Optimization-Plan.md` | Impl | Good, with one false claim | DELETE (it's in git history) | Store, OSC path and normalisation are all done and tested. The "allocation-free" claim in §1 is wrong, and the open follow-ups are already in 03 #15 and #19. |
| `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md` | Not started (only the canvas `requestFocus` landed, via the canvas plan) | Good root-cause analysis, but one step would keep a bug | **KEEP**, Phase 2 (after 01 M5) | 01, 02 and 05 already depend on it as [FOCUS]. It needs four amendments (see evidence). |
| `plans/2026-10-07-timeline-track-drag-and-selection.md` | Impl | Good; best-grounded of the old plans | DELETE | Every item has landed with tests. What's left (stale drag state, the views-ns dependency) is already 01 F14 and 02 #20. |
| `plans/2026-10-08-canvas-draw-prop-unification.md` | Partial | Good diagnosis, but it kept global drag state | DELETE (already merged into 01 M3 step 5) | `:draw` is done. The `:on-created` draw and the imperative cursor are still open, and 01 M3 tracks both. |
| `plans/plan-menu-dialogs.md` (164 B) | Not started | Not a plan, just a 6-item list | MERGE → 01 + 02, then delete | The list matches exactly the 6 TODO stubs in `menu.clj`. Four of them aren't tracked anywhere in 01–06. |
| `plans/v3/plan-menu-dialogs.md` | Obsolete | Bad: invented `laser-idn.*` namespaces | DELETE | Wrong project namespace, and its imperative dialogs bypass the event system. |
| `plans/plan-project-persistence.md` | Superseded by the real code (`state/persistent.clj`) | Bad: generic, invented data model | DELETE | It specifies JSON/cheshire and `:components :laser :mirror`. The real code saves EDN in a zip and has no cheshire dependency. |
| `plans/v3/plan-project-persistence.md` | Superseded (as above) | Bad (same plan, more boilerplate) | DELETE | Duplicate of the one above. It adds `reset!`-the-app-atom loading and fake "pure" tests that use `with-redefs`. |
| `plans/v3/plan-unsaved-guard.md` | Not started | Bad: every namespace it names is fictional | DELETE (re-plan in 02 M3 / 01 L3) | Every namespace in its list (`events.handlers.window`, `ui.confirm-dialog`, `laser-show.state`, `!app-state`, `:ui-state :unsaved?`) is missing. The guard itself is still needed. |
| `plans/timeline_overlap_simple.md` | Not started | Bad: the code it gives would wipe all items | DELETE (product decision → 01/02 open decisions) | `adjust-overlap` always returns `[]`. It hooks handlers that mouse drags never call. |
| `plans/timeline_overlap_analysis.md` | Not started | Bad: it reviews a plan that doesn't exist | DELETE | Its stated goal (delete/truncate) contradicts `_simple` (shift later cues). It approves the broken algorithm and recommends `u/sortv`, which doesn't exist. |
| `plans/TODO/layered-effects-refactor.md` | Obsolete | Worthless (generic) | DELETE | There is no "layered effects" subsystem. It names `laser-show.effects.layered`. |
| `plans/v3/layered-effects-refactor.md` | Obsolete | Worthless (generic, wrong stack) | DELETE | It names `.cljs`/Reagent UI, `clojure.spec`, effects stored in an atom, and `:blur` effects. None of that matches this code. |
| `plans/v2/plan-effects-insertion.md` | Not started (stub) | Poor | DELETE | A self-declared copy of v3 that adds nothing. |
| `plans/v3/plan-effects-insertion.md` | Not started (stub; the path is dead) | Poor: wrong path, wrong key, side effect inside a handler | DELETE (decision is already in 02 "clean up clipboard effects") | Nothing emits `:clipboard/paste-effects`. Paste goes through `:list/paste-items`. |
| `plans/v3/plan-cue-count.md` | Not started | Worthless: a TypeScript plan for a Clojure app | DELETE, MERGE 1 item → 02 | The TODO it should have targeted is real (`subs.clj:621`, `:cue-count 0`). |
| `plans/v3/plan-grid-trigger.md` | Superseded by `plan-grid-trigger-v2.md` | Poor: root-level state, functions that don't exist | DELETE | v2 explicitly lists v1's mistakes and corrects them. |
| `plans/v3/plan-grid-trigger-v2.md` | Mostly impl. §1.4 has regressed; §1.5 works but is racy | Good grounding; its learn design caused 05 F9 | ARCHIVE until 05 F5/F9 land, then DELETE | 05 already cites it as the design reference for unmapped OSC triggers. |
| `plans/zone routing arch/00-…overview.md` | Impl | Good | DELETE (with the folder, after merging 01) | Its roadmap is done, but the header still says "Status: Planning". |
| `plans/zone routing arch/01-backend-zone-routing-engine.md` | Impl, with deviations | Good, but its precedence rules contradict each other and its cache design is now a finding | MERGE 3 items → 04, then DELETE | Cache key, precedence order and a missing test (see evidence). |
| `plans/zone routing arch/02-ui-timeline-tracks-and-clips.md` | Impl except §3.3 | Good | MERGE 1 item → 01, then DELETE | New tracks still hard-code the chain's default zone, which stops them inheriting the folder's zone. |
| `plans/zone routing arch/03-ui-cue-chain-and-group-routing.md` | Impl | Good | DELETE | Picker, badges, group panel and footer label are all present. |

### Duplicate pairs: which copy to keep

| Pair | Keep |
|---|---|
| `plans/plan-project-persistence.md` vs `plans/v3/plan-project-persistence.md` | Neither. Both are superseded by `state/persistent.clj`. |
| `plans/plan-menu-dialogs.md` vs `plans/v3/plan-menu-dialogs.md` | The 164-byte stub's *list* only, merged into 01/02. The v3 expansion is fiction. |
| `plans/v2/plan-effects-insertion.md` vs `plans/v3/plan-effects-insertion.md` | Neither. v2 says of itself that it "mirrors the V3 version". |
| `plans/TODO/layered-effects-refactor.md` vs `plans/v3/layered-effects-refactor.md` | Neither. |
| `plans/v3/plan-grid-trigger.md` vs `plans/v3/plan-grid-trigger-v2.md` | v2 (archive), delete v1. |
| `plans/timeline_overlap_analysis.md` vs `plans/timeline_overlap_simple.md` | Neither. Both carry the same broken algorithm. If the feature is wanted, write a new two-line requirement. |

### `prompts/` (tracked): one-line verdicts

| File | Verdict |
|---|---|
| `prompts/calva-repl-driven-dev.md` | Useful if you use Calva Backseat Driver. It's a generic REPL-first agent prompt; keep it. |
| `prompts/refactor-clojure-file.md` | Still useful as the style template for `scripts/refactor_clojure.py`. Its style rules duplicate CONTRIBUTING/copilot-instructions, so keep one source. |
| `prompts/review-code.md` | Marginal. It's a 10-line generic review prompt, and the 2026-11-07 review supersedes it. Keep only if you reuse it. |
| `prompts/start-work.md` | **Stale.** It points at `plans/TODO/layered-effects-refactor.md`, which is obsolete. Delete it or repoint it. |
| `prompts/scripts/refactor_clojure.py` + `requirements.txt` | Works as-is (Bedrock/boto3). The model ID is pinned (`anthropic.claude-opus-4-5-20251101-v1:0`). It's a dev tool, so keep it if you still use it. |

---

## Evidence per plan

### `plan/Modulation-Optimization-Plan.md`: Impl, DELETE
- Done:
  - `input/store.clj` exists with `set-continuous-value!`, `get-continuous-value` and `get-all-continuous-values`.
  - `input_mapper.clj:44-60` routes `:midi` → `[:midi ch control]` and `:osc` → `[:osc path]` + `[:osc ch control]`.
  - `eval-midi` no longer divides by 127 (`modulator_evaluators.clj:171-187`). `eval-osc` reads `[:osc path]` (`:193`).
  - `frame_service.clj:472,623` and `multi_engine.clj:166` read the fast store.
  - Tests for every item in its §4: `modulation_test.clj:817-897` and `events_test.clj:166-212`.
- Flaws:
  1. §1.4 and the checklist line "[X] Verify point data structure … (no changes needed)" mark the "allocation-free" claim as verified. It is false: every effect allocates a new 5-vector per point, and `u/clamp` boxes its arguments (03 finding 15). The plan's own §1.2 quote (`(into [] transducer points)`) avoids intermediate *seqs*, not per-point allocation. **Nothing was measured.**
  2. It hedged on where the store should live ("`laser-show.input.router` or … `laser-show.input.store`"), so both were built. `router.clj:13-30` is now a pass-through copy of the store API.
  3. It never fixed the MIDI channel base. Its tests use channel 0, while the UI default is 1 (`modulators.clj:66`). The implementation papered over the gap with a 0↔1 fallback (`modulator_evaluators.clj:177-184`), which is now 03 #19 and 05 line 156.
  4. Formatting artefacts: stray `* [ ]` lines at lines 1 and 7.

### `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md`: Not started, KEEP (Phase 2)
- Nothing from its §5 exists. grep finds no `focus-context`, no `:command/*` events, no `shortcuts` namespace and no registry.
- `list.clj:719-740` still uses `.isFocused`, and `request-list-focus!` (`:249`) is unchanged.
- The only overlap that landed is canvas `.requestFocus` (`canvas_interaction.clj:174-175`), and that came from the canvas plan.
- The dialogs have scene `handle-scene-key-pressed` handlers, but only for undo/redo, copy-pasted three times (01 F18).
- The diagnosis is accurate and the plan is grounded in real files. It follows the planning guide.
- Amendments needed before execution:
  1. **§C.2 keeps `Space` (and, implicitly, `R` and `Delete`) as `MenuItem` accelerators** "with a handler check". That is exactly the bug in 01 L3: single-key accelerators fire inside text fields (`title_bar.clj:143,152,163`). They should move to the scene filter instead.
  2. Rename `keyboard-handler-atoms` to the current `!component-handler-props` (`list.clj:57`), and fold in 01 Q4 (the missing `:ordered-ids` defect).
  3. Add the keyboard *trigger* source (05 F17 / L3). It shares keys with these shortcuts, and the plan doesn't mention it.
  4. Fix the timeline Delete mapping to `:timeline/clear-selection` (01, `timeline_editor.clj:541`).
- Sequencing: do 01 M5 (shared dialog scaffolding) first, so the per-dialog scene filter is written once.

### `plans/2026-10-07-timeline-track-drag-and-selection.md`: Impl, DELETE
- Done:
  - `track_logic.clj:196` `droppable-track?` and `:210` `resolve-active-track`.
  - The editor uses both (`timeline_editor.clj:426,444,484,1101`).
  - `[:ui :timeline :selected-track-id]` is gone. The handlers write `[:list-ui :timeline-tracks …]` (`timeline.clj:435,461,523`).
  - Tests: `track_logic_test.clj:114-143` and `timeline_handlers_test.clj:78-113`.
- Flaws (both minor, both already in the review):
  - It prescribed passing `:original-track-idx` through `:drag-updates`, which goes through the async global drag state. 01 F14 shows this can be read stale and re-dispatch `move-item-to-track`.
  - It added more pure logic to a views namespace that the events layer requires (02 #20).
- It is the best-grounded old plan: a state-path table with file:line, plus concrete test fixtures.

### `plans/2026-10-08-canvas-draw-prop-unification.md`: Partial, DELETE
- Done:
  - `:draw` (`canvas_interaction.clj:168-169`).
  - `:on-advanced` removed.
  - `.setFocusTraversable` is no longer called inside the draw helper.
  - `requestFocus` on press (`:174-175`).
  - It correctly counts 8 `ci/interactive-canvas` users.
- Open:
  - Item 3: `:on-created` still calls `draw-interactive-canvas!` (`:153`).
  - Item 2, partly: the cursor is still set imperatively with `.setStyle` in the hover handler (`:229`).
  - Both are 01 M3 step 5.
- Flaw: §5.2 re-affirms "Mouse events update `[:ui :canvas-drag <canvas-id>]` in global state". That async global drag state is the root of 01 F14. The plan fixed redraw but wrote the race-prone design into the target architecture.

### `plans/plan-menu-dialogs.md`: Not started, MERGE then delete
The six bullets correspond one-to-one with stubs in `events/handlers/menu.clj`:

| Bullet | Code | Already tracked? |
|---|---|---|
| Dirty project confirmation | `:27`, `:109` TODOs; `root.clj:167-168` | 01 L3, 02 M3 |
| Export dialog | `:98-103` no-op | **No → add to 02 (handlers/effects)** |
| Preview panel toggle | `:197-202` flips `[:ui :preview-visible?]`, which nothing reads | **No → add to 01** |
| Fullscreen toggle | `:204-209` flips `[:ui :fullscreen?]`, which nothing reads (no `setFullScreen` anywhere) | **No → add to 01** |
| Update check | `:229-234` no-op | **No → add to 06 (release) or remove the menu item** |
| Open URL | `:221` returns the unregistered `:system/open-url` with a placeholder URL `github.com/your-repo/docs` | 02 Q2 (effect); **the placeholder URL is untracked** |

02 #21 also lists `[:ui :preview-visible?]` and `[:ui :fullscreen?]` as undeclared keys, but nobody flags that these menu items do nothing.

### `plans/v3/plan-menu-dialogs.md`: Obsolete, DELETE
- Every namespace uses `laser-idn.*` (`laser-idn.ui.dialogs.dirty-project`, `laser-idn.state`, `laser-idn.ui.menu`). The project is `laser-show.*`, and grep finds none of them.
- It specifies side-effecting functions that `swap!` a global `app-state` and build JavaFX `Alert`s directly. That bypasses the effects-as-data pattern (`docs/CLJFX_STATE_MANAGEMENT.md` Pattern 6).
- Its "pure function" tests call `reset!` on global state.

### `plans/plan-project-persistence.md` and `plans/v3/plan-project-persistence.md`: Superseded, DELETE both
- Real implementation:
  - `state/persistent.clj` saves an EDN zip (`zip-filenames`, `:21-25`), using `state/serialization.clj`.
  - Menu handlers live in `handlers/menu.clj:23-96`.
  - Projects go to `~/LaserShowProjects`.
- The plans specify:
  - `src/laser_show/persistence.clj`, `src/laser_show/ui/persistence_dialog.clj` and `src/laser_show/state.clj` (none exist).
  - JSON via `cheshire` (not in `deps.edn`) and `clojure.java.time` (not a library).
  - A made-up data model: `:components [{:type :laser|:mirror|:sensor …}]`.
  - A hard-coded `C:\Users\zubair/.laser-idn`.
- v3 adds "pure" tests that `with-redefs` `spit`/`slurp`, plus loading by `reset!` of the app atom.
- The real persistence bugs (Save loses `:file-path`, blocking I/O) are already 02 M3.

### `plans/v3/plan-unsaved-guard.md`: Not started, DELETE (re-plan)
- None of these exist: `laser-show.events.handlers.window`, `laser-show.ui.confirm-dialog`, `laser-show.state`, `!app-state`, `[:ui-state :unsaved?]`, `:window/close-requested`, `src/laser_show/ui/root.clj`.
- The real flag is `[:project :dirty?]` (`domains.clj:201`, `helpers.clj:11`). The real close hook is `root.clj:167` (`System/exit`).
- Its code is wrong cljfx: `fx/sub-val context [:ui-state :unsaved?]` passes a vector where a function is expected.
- `set-unsaved!` `swap!`s a global atom from inside a handler.
- It claims to follow "`u/-&map` macros". No such macro exists; the real one is `u/->map&`.
- The need is real. Re-plan it against `[:project :dirty?]` once 02 Q1/M3 make dirty tracking trustworthy (02 line 343, 01 L3).

### `plans/timeline_overlap_simple.md` and `plans/timeline_overlap_analysis.md`: Not started, DELETE both
- **The algorithm is broken.**
  - In `adjust-overlap` (simple, lines 44-57), the outer `reduce` starts at `[]`, and the inner `reduce` iterates over that same `coll`, which is always `[]`. So the function returns `[]` for every input, and applying it would **delete every clip**.
  - In both files the docstrings come *after* the argument vector, where they are no-op expressions, not docstrings.
- **It hooks the wrong handlers.**
  - Mouse drag and resize dispatch `:timeline/update-item-timing` (`timeline_editor.clj:487-498`, handler `timeline.clj:254`).
  - `:timeline/nudge-selection` is keyboard-only (`timeline_editor.clj:543-547`).
  - `:timeline/resize-selection` has no dispatcher in `src/` at all.
- **The goals contradict each other.**
  - The analysis "Goal recap" says overlapping cues are *deleted or truncated*, and mentions `:timeline/delete-item` / `:timeline/truncate-item` events that appear in neither file. It is reviewing a third plan that isn't in the repo.
  - `_simple` instead *shifts later cues right*.
- The analysis makes false claims and still says "the plan is sound":
  - "helpers are prefixed with `-`" (they're `defn-`).
  - "respects defaults via `get-in` with fallback".
  - It recommends `u/sortv`, which doesn't exist in `common/util.clj`.
- Product decision needed (add to 01/02 open decisions): should clips on one track be allowed to overlap? If not, enforce it in `handle-update-item-timing`, with a test.

### `plans/TODO/layered-effects-refactor.md` and `plans/v3/layered-effects-refactor.md`: Obsolete, DELETE both
- grep finds no `layered`, no `laser-show.effects.*` and no `laser-show.ui.*` in `src/`. The only `laser-show.ui.drag-drop` mention is in a docstring at `serialization.clj:11`.
- v3 specifies:
  - `src/laser_show/ui/effect_panel.cljs` (Reagent; this is a JVM cljfx app).
  - `clojure.spec` schemas.
  - Effects with `:blur` / `:target :layer-1`.
  - An `effects-atom` mutated by `enable-effect!`.
- That last item contradicts the real model: effects are pure data `{:effect-id … :params …}` stored in cljfx state (03 intro).
- Its only concrete goal ("no lazy seqs in the hot path") is 03 #15.
- This confirms the 03 reviewer's claim.

### `plans/v2/plan-effects-insertion.md` and `plans/v3/plan-effects-insertion.md`: Not started, DELETE both
- The stub still exists (`effects.clj:172-176`).
- The path is dead: `effect-clipboard-paste-effects` (`events/core.clj:130-139`) runs only when a handler returns `:clipboard/paste-effects`, and grep finds no handler that does. Effect-chain paste actually goes through `:list/paste-items`.
- Plan defects:
  - State path `[:chains :effect-chains col row :items]`; the real one is `[… [col row] …]` (`effects.clj:122`).
  - Payload key `:insert-index`; the real event sends `:insert-pos` and already carries `:effects`.
  - The handler reads the clipboard via `clipboard/paste-effects!`, which doesn't exist (the real function is `get-effects-to-paste`). That puts a side effect inside a pure handler.
  - Clipboard tag `:effects-cell`; the paste reads `:effect-chain`/`:effect`.
  - UI file `views/components/effect_chain_editor.clj`; the real one is `views/dialogs/`.
  - "the `handle` multimethod"; it's a `case`, and there's no `defmulti` in `src/`.
- 02 already says: delete the `:clipboard/*-effects` stubs or implement them inside the keyboard-plan clipboard unification.

### `plans/v3/plan-cue-count.md`: Not started, DELETE, carry 1 item → 02
- Entirely TypeScript (`src/extractors/cueCountExtractor.ts`, `npm test`), with a Clojure test skeleton tacked on as "§8", placed before §7.
- The real target: `subs.clj:613-621` `zone-group-usage` returns `:cue-count 0 ;; TODO`, which is shown at `views/tabs/zones.clj:269` as "Cues targeting: 0".
- **Carry over to 02 (subs):** compute the real cue count by reusing `zone_effects/resolve-item-zone-destination` over cue chains. It needs a narrow subscription, because it walks every chain.

### `plans/v3/plan-grid-trigger.md`: Superseded, DELETE
- It models root-level `:trigger-map` / `:learning-target`, and puts hardware drivers in charge of dispatching cell-aware events.
- Its tests call `midi/process-midi-message` / `osc/process-osc-message`, which don't exist.
- It dispatches `:ui/show-trigger-bank`, which also doesn't exist.
- v2 §1.1–1.3 lists and corrects each of these.

### `plans/v3/plan-grid-trigger-v2.md`: Mostly impl, ARCHIVE then DELETE
- Done:
  - State at `[:config :input :trigger-map]` and `[:backend :input :learning-target]`.
  - Subs `input-trigger-map` / `input-learning-target` (`subs.clj:705-711`).
  - Events `:input/start-learn`, `cancel-learn`, `remove-trigger` and `clear-cell-triggers` (`input.clj:321-324`).
  - Trigger migration and cleanup on move and clear (`grid.clj:150,185`, `effects.clj:72,99`).
  - `trigger_bank.clj` with `:cell-mode`; `cell_trigger_editor.clj` mounted in `root.clj:151`.
  - Badges and CSS (`grid_cell.clj:42-43`, `css/grid_cells.clj:49,104`).
  - Tests `trigger_test.clj` and `grid_trigger_test.clj`.
- Regressed: **§1.4 is contradicted by `osc.clj:109-112`.** An unmapped message with a numeric argument becomes `control-change :osc 0 0` instead of a trigger. That fails `trigger_test.clj:37-56` (05 F5, confirmed).
- Design flaw the plan prescribed: `:input/start-learn` "registers temporary router handler … captures next note … writes mapping". Implemented literally (`input.clj:204-241`), that gives a `future` blocked on a promise plus `swap-state!` from outside the event flow. Cancel never delivers the promise. That is 05 F9 (leaked threads, lost update).
- Also: it says "Implement `:grid/trigger-cell`" without telling the implementer to reuse `:grid/cell-clicked`. They are now about 90% duplicates (02, `grid.clj:81-139`).
- The `{:source :keyboard}` key schema has no input source behind it (05 F17).
- Keep it until 05 F5/F9 land, because 05 cites §1.4 as the reference. Then delete.

### `plans/zone routing arch/00…03`: Impl, MERGE 01/02 leftovers, then delete the folder
- Done (04 line 38 confirmed):
  - `zone_effects.clj:89` `resolve-track-effective-zone` (folder traversal).
  - Recursive `render-item-tree-by-zone` (`frame_service.clj:394`).
  - Multi-entry, pruned cache (`:537-573`).
  - Per-cue `get-cue-timing-context` in `multi_engine.clj:175`.
  - No second global-effects pass in `generate-current-frame` (`:659-661`).
  - `:timeline/set-track-zone` / `set-clip-zone` (`timeline.clj:391-419`).
  - Timeline inspector with "Inherit from Track/Folder" (`timeline_editor.clj:742,809,896`).
  - `item-zone-destination-picker`, `[Zone (Group)]` labels, group panel text and the "Default Zone (Chain Fallback):" footer (`cue_chain_editor.clj:113,140-183,228,390`).
  - Integration tests (`zone_routing_integration_test.clj:393-538`).
- **01 flaws:**
  1. **The precedence rules contradict each other.** §2 says item → parent group → ancestors → track → folder → chain. §3.1 says item → track → inherited (group) → default. The code follows §3.1 (`zone_effects.clj:135-139`). Decide which is intended and write a test for it.
  2. **The cache design it prescribed is now a finding.** A wall-clock 16 ms bucket key leaves each projector thread hoping for a hit, about 50% with two projectors (04 line 177). 04's frame-clock work replaces it.
  3. The key uses `(:id cue-chain)` instead of the prescribed `[col row]`, and doesn't include `timing-ctx` (`frame_service.clj:63-69`).
  4. "Add Multi-Cue Concurrent Cache Test" (§5.5) was never written.
  5. It specified parameter maps, but the code is positional with an argument-swap hack (`zone_effects.clj:97-98`, `(string? tracks)`).
- **02 leftover (→ 01):** §3.3 is not done. `handle-add-track` stamps the chain default zone explicitly on every new track (`timeline.clj:427-429`). A track later moved into a folder therefore never inherits the folder's zone. Create tracks with no `:zone-group-id` (inherit) instead.
- 00 header still says "Status: Planning". 01–03 say "Implemented".
- **Carry to 04:** items 1, 3 and 4, plus a note that 2 is replaced by the frame clock.

---

## Lessons for future plans

Recurring weaknesses, written so they can be folded into `.agents/rules/planning-guide.md`.

1. **Every name must be checked against the code.** Seven of the 21 plans name namespaces that don't exist (`laser-idn.*`, `laser-show.persistence`, `laser-show.state`, `laser-show.effects.layered`, `ui/*.cljs`, TypeScript files). Rule: each existing namespace, function, event key and state path you cite gets a `file:line`. Anything new is marked **NEW**. Name nothing else.
2. **Generated plans need a provenance and grounding check.** Every bad plan ends with "*Prepared by the analysis sub-agent*" / "*Generated by planning subagent*". They were batch-rewritten into a template (the identical "Pure Function Unit Testing" section was added to all of v3, including the TypeScript one). Rule: a plan without a "current behaviour" section that cites real files gets rejected, whoever wrote it.
3. **Start with current behaviour and root cause, not target architecture.** The good plans (timeline-track, canvas, keyboard, zone 01, grid-trigger v2) all open with "what the code does now, at file:line". The bad ones open with an idealised architecture diagram.
4. **Name where state lives, and which thread writes it, for every new piece of state.** Check it against `docs/CLJFX_STATE_MANAGEMENT.md`. Three current findings started as plan designs:
   - global async drag state (canvas plan → 01 F14);
   - learn implemented as a router callback plus `future` plus `swap-state!` (grid-trigger v2 → 05 F9);
   - wall-clock cache buckets shared across threads (zone 01 → 04).

   Rule: for each new key say: document, UI or runtime atom; who writes it (handler, effect, or thread); and whether it enters undo/dirty tracking.
5. **Side effects go in registered effects, never in handlers.** Plans repeatedly put clipboard reads, dialogs, `swap!` and file I/O inside handler functions (effects-insertion, unsaved-guard, persistence, menu-dialogs). Rule: list each side effect as a named effect key, and say whether it already exists in `events/core.clj`.
6. **Performance claims need a measurement.** "Allocation-free" was ticked as verified without a benchmark. Rule: any hot-path claim cites a benchmark or profiler run, or is labelled *unverified*.
7. **Algorithms need a worked example and invariants.** The overlap helper returned `[]` and nobody noticed. Zone precedence was given in two different orders. The MIDI channel base was never fixed. Rule: for any algorithm, give 3–5 input → output examples as data, plus the invariants ("never removes items", "0-based internally"). These become the tests.
8. **Trace the real call path into what you hook.** The overlap plan hooked `nudge-selection`/`resize-selection`, but the mouse goes through `update-item-timing`, and `resize-selection` has no caller. Rule: name the dispatcher (`file:line`) for every event you change.
9. **Don't hedge on locations.** "`input.router` or `input.store`" produced both. Rule: one location per item; list alternatives in a separate "alternatives considered" section.
10. **Reuse before you create.** Plans invented new namespaces (`shortcuts.registry`, `persistence`, `window`, `layered`) and missed duplicates (`:grid/trigger-cell` vs `cell-clicked`). Rule: a "reuse/extend" table comes before any NEW item.
11. **Keep a status header up to date, and keep one copy.** There are v2/v3/root duplicates, `_analysis` vs `_simple`, and 00 still says "Planning" after implementation. Rule: a header with `Status` / `Last verified against <commit>` / `Supersedes` / `Superseded-by`. Edit in place; no `v2/`/`v3/` folders. When something is done, mark it and delete it, or move it to an archive.
12. **Write acceptance tests as named test vars, not prose.** The plans that listed concrete test names and fixtures (timeline-track, modulation) got those tests written. Plans with "verify that it works" did not.
13. **Check the plan against the open review.** Before writing, grep `plans/2026-11-07-improvement-review/` for the area, to avoid prescribing a design that's already a finding (e.g. keeping `Space` as a MenuItem accelerator).

### Did `.agents/rules/planning-guide.md` contribute?

Partly. The guide is 6 lines.
- **What it gets right:** "look through the codebase, see what we can reuse" and "fn names with their list of props/parameters". The plans that followed it are the good ones.
- **Most bad plans ignored it anyway.** All of v3, both persistence plans and both overlap plans contain full code, `deftest` skeletons, and even TypeScript. The guide's `trigger: model_decision` front-matter means sub-agents may never have loaded it.
- **"NO pseudocode even" removed the one thing that would have caught the logic errors.** With no concrete data examples allowed, the zone precedence was written two ways in prose, the MIDI channel base was never fixed, and the cache key stayed vague. Replace the rule with: *no implementation bodies, but data-shape examples (EDN state, event maps, input→output tables) are required.*
- **It is silent on everything that went wrong:**
  - no requirement to cite `file:line` or to verify that a namespace exists;
  - nothing on which atom state lives in or which thread writes it;
  - nothing about effects vs handlers;
  - nothing on status/supersession or on measuring performance claims.
- **Its wording biases toward new code.** "the new state management", "new namespaces we may need to create", "how we would represent any new objects" invite inventing structure. It needs an equal prompt to say what *already* exists and to justify every NEW item.

Suggested replacement skeleton for the guide:
1. Status header.
2. Current behaviour (file:line).
3. Root cause.
4. Reuse table.
5. Changes per namespace (existing vs NEW).
6. State placement and writer thread.
7. Events and effects.
8. Data examples / invariants.
9. Named tests.
10. Out of scope / alternatives.
11. Links to related review findings.
