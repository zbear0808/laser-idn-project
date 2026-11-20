# Phase Z6: the step grid

**Written:** 2026-11-19   **Written for commit:** `f178f56` (`master`; first written at `739bd74`, then re-verified here: `src/` is as vibe Phase 5 left it in `9f5d78a`, after vibe Phase 4b (`f5a4f84`), 4a (`6715fb4`) and UX Phase 3 (`b9b9d55`), and `test/` as `43fa00d` left it; every line below is at that source). Z2–Z5 change several of these files first, so **re-check every path and line at HEAD before starting.**
**Status:** Proposed. Not to be built yet (D-8).   **Needs:** Z5 (`07`): the zone stage (`routing/zone_remap.clj`), the `:zone-pattern` effect, a cue's `:zone-patterns`, the pattern window, and `:zone-steps` in the snapshot. Vibe **Phase 4 has landed** (4a `6715fb4`, 4b `f5a4f84`), so a grid steps on HIT, ACCENT, FLASH, a vibe change or any cue start from the start, and can restart on one, through Z5's `:step-on` and `:restart-on` (`07` §4.2). Vibe **Phase 5 has landed** (`9f5d78a`): its Fader modulator can scrub a grid (§8).
**Implements:** D-6; overview "Start here" 4 ("a drum-machine step grid comes later"); needs `06`'s "Custom step grid". Spec §6 steps 1–3, for a step that lights several zones (spec §6.1's pattern copies, from Z5's S-1).   **Size:** ~3 d

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. **NEW** marks what doesn't exist yet. Z5's names are the ones `07` proposes.

After this phase you can:
- **Write a zone pattern on a grid, like a drum machine**: a row per zone, a column per step. Click a cell to light that zone on that step.
- **Choose its length** (8, 16 or 32 steps, or any length from 1 to 64) and **its speed** (steps a beat), or step it on a press: HIT, ACCENT or FLASH, a vibe change or any cue start (vibe Phase 4's pulses), or Z5's Zone step key. It can also start again from its first step on a press.
- **Start from** Alternate, Chase, Ping-pong, Random, Center-out or All on, then change it.
- **Light two zones on one step.** The picture is drawn in both, and a laser that both reach draws it once.
- **Watch the playhead** run while the cue plays, and edit as it plays.
- **Edit quickly**: drag to paint, arrows and Space, copy and paste between grids, rotate, clear, undo.

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| Z5's styles | `07` §4.1, §8.2 | Each style works out a step's lit zones from a rule. None holds a free set of zones per step |
| Z5's pattern window | `07` §7.1 | Shows the rows a style uses. Nothing edits steps |
| Z5's snapshot | `07` §4.4 | `:zone-steps {pattern-id step}`: the step each playing pattern is on |
| Presses (vibe Phase 4a) | `animation/pulses.clj:24-27`, `:105-122`, `:132-150`; `services/show_clock.clj:58`, `:158-164`; `services/frame_service.clj:115-121` | Every render's timing context carries the tick's pulse view: each source's press `:count` and age. Z5 steps a pattern on a source's `:count` and restarts it with `pulses/rebase` (`07` §4.2) |
| A Step modulator (vibe Phase 4a) | `animation/modulators.clj:96-105`; `animation/pulses.clj:183-200`, `:230-247` | "Next value on each press": `:steps` even positions from Min to Max, or a palette of `:values`, in order up, down, ping-pong or scatter |
| Storing a param | `events/handlers/chain/params.clj:72-87` | `:chain/update-param` stores any value, a vector of vectors included |
| One undo step per drag | `views/components/parameter_controls.clj:90-94`; `events/handlers/history.clj:19-35` | A slider begins a history transaction on mouse down and commits it on mouse up |
| Keyframes | `animation/interpolation.clj:47-49` | A non-numeric param holds the earlier keyframe's value, so a keyframed `:steps` would switch grids at each keyframe |
| Drawing from the clock | `views/components/clock_view.clj:58-71` | `ext-clock-redraw`: redraw on each screen pulse from the newest tick, with no app state |
| Raw pointer events in a pure handler | `events/handlers/vibes.clj:34-75` | `pointer-action` (`:34-54`) reads the JavaFX event inside the handler (`handle-pointer`, `:64-75`) |
| A drag in a headless test | `test/laser_show/ui_test/testfx.clj:457-484` | `fire-drag!` fires press, drag and release at one node, so a grid body that takes its own drag can be tested headless |
| An editor window | `views/dialogs/effect_chain_editor.clj:201-257` | A non-modal stage whose scene handles undo and redo keys: ⌘ or Ctrl with Z, Shift+Z or Y (`:201-214`) |
| Shortcut text (UX Phase 3) | `views/components/shortcuts.clj:12-23`; `views/components/grid_cell.clj:387-390` | Labels and tooltips that name a shortcut write it as the platform does: "Copy Effects (⌘C)" on macOS, "(Ctrl+C)" elsewhere |

## 2. Why / root cause

Z5's styles are rules. A rhythm like "A on 1 and 3, B on 2, both on the last eighth" can't be written as one: it needs a set of lit zones per step, chosen by hand. The stage already lights any set (`07` §4.1: move, gate, a copy as one key), so Z6 adds a style whose lit sets are data, and an editor for that data.

## 3. Reuse / extend

| Existing or planned (file:line) | How Z6 uses it |
|---|---|
| Z5's `lit-set` and its move and gate (`07` §4.1), and copies as one union key (`07` §8.4) | The grid is one more lit-set style, `:steps`. Nothing else in the stage changes |
| Z5's `:zone-pattern` params (`07` §8.2): `:zones`, `:rate`, `:offset`, `:step-on`, `:restart-on`, `:clock`, `:source`, `:from` | Rows are `:zones`. Rate, Step on, Restart on, Offset and Moves are the same controls, with the same words (Phase 4a's "HIT", "Vibe change", "Any cue start", and Z5's "Zone step key") |
| Z5's `lit-set` for every other style | "Start from": a grid of the first L steps of any style |
| `chain-config` (`events/handlers/chain/helpers.clj:31-45`) with Z5's `:cue-zone-patterns` domain | One address for a cue's pattern and a cell's: `{:domain :entity-key :effect-path}` |
| `history/begin-transaction`, `commit-transaction` (`events/handlers/history.clj:19-35`) | A drag is one undo step |
| `ext-clock-redraw`, Z5's `:zone-steps` | The playhead |
| Z4's `target_control` with Z5's `:pattern` scope, `routing.targets/next-zone`, `ref-name` | The row pickers, "+ Row" with the next zone letter, row labels |
| `with-clock` (`test/laser_show/ui_test/preview_test.clj:70`, moved to `ui_test/fixtures.clj` by Z3), `fire-drag!` | The UI tests |

## 4. Changes per namespace

- `routing/zone_remap.clj` (Z5's): `lit-set` for `:steps` is `steps[k mod L] ∩ lane`, where `L = (count steps)`. With no steps, the pattern is the identity.
- `routing/zone_patterns.clj` (Z5's): `:steps` joins `styles`, but not the pads (a pad can't make up steps). Its preset is 8 steps at 2 a beat over rows A and B, filled as Alternate (A, B, A, B…), with `:follow-pads? false` (Z6-Q2). `describe` says "Steps · 8 · A B".
- `animation/effects/zone.clj` (Z5's `:zone-pattern`): `:style`'s choices gain `:steps`. NEW param `{:key :steps :label "Steps" :type :step-list :default []}`. The exemptions in `test/laser_show/animation/effects_coverage_test.clj` (`:19-26`) gain it.
- NEW `laser-show.routing.step-grid` (pure): every edit takes the pattern's params and returns them.
  - `toggle [params ref step]`, `paint [params ref step on?]`.
  - `set-length [params n]`: 1–64. Longer pads with empty steps; shorter cuts the end.
  - `add-row [params ref]` refuses a ref that is already a row. `remove-row [params ref]` also takes it out of every step. `replace-row [params old new]` rewrites every step, and refuses a `new` that is already a row. `move-row [params ref delta]` moves it in `:zones`, and every step is re-sorted into row order.
  - `fill-row [params ref every]` (every 1, 2 or 4 steps), `clear [params]`, `clear-step [params step]`, `rotate [params by]` (every row, wrapping).
  - `from-style [params style n]`: Z5's `lit-set` for steps 0 … n−1. `center-out [rows n]`: the middle row (or the middle two), then one further out on each step. A lane holding `:lane/lasers` can't be rows (its length changes with the rig), so it becomes zones A, B, C… (as many as the rig has lasers now, at least 2), and the window says so (S-4).
  - `copy [params]` → `{:zones :steps}`. `paste [params clip]` matches rows by ref, and adds the rows it lacks.
  - `cell-at [x y cell-w cell-h rows len]` → `[row step]` or nil. `normalize [params]`: each step's refs are distinct, rows only, in row order.
- NEW `events/handlers/step_grid.clj`, routed for `"step-grid"` (`events/handlers.clj:38-67`): the events of §6. Each edit reads and writes the instance's `:params` at `chain-config`'s items-path plus the effect path, so it works for a cue's pattern and a cell's alike. While the pattern's keyframes are on, an edit does nothing (invariant 9).
- `events/handlers/history.clj` (`:125-163`): "Edit Step Grid".
- NEW `views/dialogs/step_grid_editor.clj`: the "Step Grid — Cue A1 · Zone pattern" window. A non-modal stage, with the scene key handler of `views/dialogs/effect_chain_editor.clj:201-214` plus the grid's keys (§7).
- NEW `views/components/step_grid.clj`:
  - `grid-body`: one pane, which takes the mouse itself (so a drag paints across cells), with a label per row and a region per cell, `#step-cell-<row>-<step>`;
  - `playhead`: a canvas over the body, in `ext-clock-redraw`;
  - `toolbar`: length, rate, Step on, Restart on (while Step on is the beat, as in Z5's window), offset, Start from, Copy, Paste, Clear. The Copy and Paste tooltips name their keys with `shortcuts/shortcut-text` (`views/components/shortcuts.clj:12-23`).
- `views/components/zone_pattern_editor.clj` (Z5's): with the style Steps, the pattern window shows a small read-only grid and "Edit grid…". The Pattern chip's menu gains "Step grid…" (a cue without a grid gets one made from its pattern by `from-style`, or the preset). A cell's pattern renderer has the same button.
- `subs.clj`: NEW `step-grid [target]` (rows with their names and lanes, the steps, the length, the cursor, whether keyframes are on, the wrap note) and `step-grid-clipboard`.
- `css/zones.clj` (Z4's): `.step-cell` (`-on`, `-cursor`, `-beat`, `-bar`), `.step-row-label`, `.step-playhead`.

## 5. State placement

| Path | Lives in | Written by | Undo / unsaved? |
|---|---|---|---|
| the pattern's `[:params :steps]` and `[:params :zones]` | document: a cue's `:zone-patterns`, or a cell's items | the step-grid events | yes / yes |
| `[:ui :dialogs :step-grid-editor] {:open? :target {:domain :entity-key :effect-path} :cursor [row step]}` | UI | open, close, the cursor keys | no / no |
| `[:ui :step-grid :clipboard] {:zones :steps}` | UI, session | Copy | no / no |
| snapshot `:zone-steps` (Z5's) | per tick | the show clock | – |

## 6. Events and effects

| Event | Exists? | Dispatched from | Pure? / undo label |
|---|---|---|---|
| `:step-grid/open {:target}` | NEW | "Edit grid…", "Step grid…" | yes. If the pattern isn't a grid yet, it becomes one: `from-style` over its lane for 8 steps, at its own rate, so it plays the same for those 8 steps. "Edit Step Grid" |
| `:step-grid/toggle {:target :row :step}` | NEW | Space or Enter on the cursor | yes / "Edit Step Grid" |
| `:step-grid/pointer {:target :cell-w :cell-h :fx/event}` | NEW | the grid body's mouse press, drag and release | yes. Press: begins a history transaction (as `:history/begin-transaction` does) and paints the cell with the opposite of its state. Drag: paints each cell crossed with that value. Release: commits. One drag, one step |
| `:step-grid/set-length {:target :n}` | NEW | 8, 16, 32, the field | yes / "Edit Step Grid" |
| `:step-grid/add-row`, `/remove-row`, `/set-row`, `/move-row`, `/fill-row`, `/clear-row` | NEW | "+ Row", a row's menu | yes / "Edit Step Grid" |
| `:step-grid/clear`, `/clear-step`, `/rotate {:by}`, `/start-from {:style}` | NEW | toolbar, keys, a step number's menu | yes / "Edit Step Grid" |
| `:step-grid/copy`, `:step-grid/paste` | NEW | Copy, Paste, ⌘C and ⌘V (Ctrl+C and Ctrl+V elsewhere) | copy: UI only. Paste: "Edit Step Grid" |
| `:step-grid/cursor {:row :step}` | NEW | arrows, Home, End, a click | yes, UI only |
| `:chain/update-param` (rate, offset, Step on, Restart on, Moves) | yes (`events/handlers/chain/params.clj:72-87`) | the toolbar | yes / its label |
| `:modulator/*` on `:offset` and `:rate` | yes | ∿ | yes |

No new effect key. A grid edit reaches the lasers on the next tick, as any edit does.

## 7. UX

```
┌ Step Grid — Cue A1 "Beams" · Zone pattern ─────────────────────────────────────────────────────┐
│ Steps [8] [16] [32] [ 8 ]   Rate [2 ▾] steps a beat   Step on [The beat ▾]   Offset [ 0.00 ] ∿   │
│                                                    Restart on [No ▾]                             │
│ Start from [Alternate] [Chase] [Ping-pong] [Random] [Center-out] [All on]   [Copy] [Paste] [Clear] │
│                  1    2  ┊  3    4  ┊  5    6  ┊  7    8  ║                 ┊ a beat  ║ a bar │
│ A → Left       [■]  [ ] ┊ [ ]  [ ] ┊ [■]  [ ] ┊ [ ]  [■] ║                                     │
│ B → Right      [ ]  [ ] ┊ [■]  [ ] ┊ [■]  [ ] ┊ [ ]  [■] ║                                     │
│ C → Left       [ ]  [ ] ┊ [ ]  [ ] ┊ [ ]  [ ] ┊ [■]  [■] ║                                     │
│ [+ Row ▾]                           ▲ playhead (step 5)                                         │
│ Moves  (•) Automatic  ( ) The All picture  ( ) What's already in the zones                       │
│ ☐ Follows the pattern pads                                                                       │
│ ⓘ A and C both go to Left under Left / Right. This rig (1 laser): Left is its left half.          │
│                                                                                       [Close]  │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Rows** are the pattern's zones, top to bottom, each labelled with its lane in the active map ("A → Left"). There can be any number of rows. "+ Row" adds the next zone letter (A, B, C…), and its menu offers any zone, group or laser (Z4's Target menu in Z5's `:pattern` scope). A row's right-click menu: "Change ▸", "Move up", "Move down", "Fill every step", "Every 2nd", "Every 4th", "Clear row", "Remove row".
- **Columns** are steps. A thin line marks each beat (every `rate` steps) and a thick one each bar (every `4 × rate` steps, Z4's SQ-8). More than 32 steps scroll sideways. A step number's right-click menu: "Clear step", "Light every row".
- **The playhead** lights the column of the step the pattern is on (`:zone-steps` from the snapshot, `step mod L`). While the cue doesn't play, or the cell is off, there's no playhead and the window says "Not playing".
- **The keyframe guard.** While the pattern's keyframes are on, the grid is greyed out: "This pattern's keyframes are on. Switch them off to edit its grid."

| Mouse | Does |
|---|---|
| press on an unlit cell | lights it. Dragging lights every cell the pointer crosses (Z6-Q3) |
| press on a lit cell | clears it. Dragging clears every cell crossed |
| right-click a row label or a step number | its menu (above) |

| Key (the grid has the focus) | Does |
|---|---|
| ← → ↑ ↓ | move the cursor (a blue outline) |
| Space, Enter | toggle the cell under the cursor |
| Delete, Backspace | clear the cursor's step, in every row |
| Shift+← / Shift+→ | rotate every row one step earlier or later |
| Home, End | first step, last step |
| ⌘C, ⌘V (Ctrl+C, Ctrl+V elsewhere) | copy the grid, paste a grid |
| ⌘Z, ⌘⇧Z, ⌘Y (Ctrl elsewhere, and Ctrl on macOS too) | undo, redo, as the chain editors do (`views/dialogs/effect_chain_editor.clj:201-214`) |

Wherever a label or tooltip names one of these keys, it is written with `shortcuts/shortcut-text`, as the grid cells' Copy and Paste items are (UX Phase 3).

| Where | Exact text |
|---|---|
| title | "Step Grid — Cue A1 "Beams" · Zone pattern", or "Step Grid — Effects B3 "Run" · Zone pattern" |
| Start from tooltip | "Replaces the grid with the first 8 steps of Chase over these rows" |
| Every laser note | "Every laser can't be a row, because the number of lasers changes. It became zones A–D: pick a zone map that sends them where you want." |
| wrap note | "A and C both go to Left under Left / Right." |
| keyframes on | "This pattern's keyframes are on. Switch them off to edit its grid." |
| not playing | "Not playing" |

## 8. Data examples and invariants

```clojure
{:id #uuid "…p3" :effect-id :zone-pattern :enabled? true
 :params {:style :steps
          :zones [:zone/a :zone/b :zone/c]                                        ; the rows, top to bottom
          :steps [[:zone/a] [] [:zone/b] [] [:zone/a :zone/b] [] [:zone/c] [:zone/a :zone/b :zone/c]]
          :rate 2.0 :offset 0.0 :step-on :clock :clock :global
          :source :auto :from :all :follow-pads? false}}
```

A step is a vector of the refs lit on it, in row order (`[]` is a rest). The length is `(count steps)`. With the show's beats at 2 steps a beat, the cue draws only its All picture P:

| Global beats | Step | Lit | Frame keys | 1 laser, Left / Right | 4 lasers, Left / Right |
|---|---|---|---|---|---|
| 0.0 | 0 | A | `{:zone/a P}` | P in the left half | p1, p2 |
| 0.5 | 1 | – | `{}` | dark | dark |
| 1.0 | 2 | B | `{:zone/b P}` | P in the right half | p3, p4 |
| 2.0 | 4 | A B | `{[:zone/a :zone/b] P}` | P whole, once (both halves of one laser are the whole laser: spec §6 step 2) | all four, once each |
| 3.0 | 6 | C | `{:zone/c P}` | the left half (C wraps to Left, D-25) | p1, p2 |
| 3.5 | 7 | A B C | `{[:zone/a :zone/b :zone/c] P}` | whole, once | all four |
| 4.0 | 8, so 0 again | A | `{:zone/a P}` | the left half | p1, p2 |

With **What's already in the zones** instead, and a cue that draws a circle into A and a wave into B: step 0 shows the circle (the wave is hidden), step 2 the wave, step 4 both, and step 6 is dark, because C has nothing of its own.

**On a press.** With **Step on: HIT** (or ACCENT, FLASH, a vibe change, any cue start, or Z5's Zone step key), each press moves one step, and the rate is steps per press: the step is the source's `:count` in the tick's pulse view (`07` §4.2). With Step on the beat and **Restart on: FLASH**, the grid starts again from its first step at each FLASH (`pulses/rebase`, `animation/pulses.clj:132-150`). A modulator on Offset shifts the playhead, and Phase 8 §7's driving table applies:
- Phase 4a's **Step** (`:step`, `animation/modulators.clj:96-105`) on Offset, following HIT with the palette `[0 4]`: each HIT jumps an 8-step grid half way round.
- Phase 5's **Fader** (`:macro`, `animation/modulators.clj:133-141`) on Offset, `:slot :slider-2`, Min 0, Max 7.9, with Rate 0: the Morph fader scrubs through an 8-step grid by hand.

**How the grid compiles to Z5's stage.** A step is the lit set `S` of `07` §4.1. In move mode the picture goes under the union of `S` (one key), so "copied to each lit zone" is one frame that every laser reached by any of those zones draws once (`07` §8.4). In gate mode each lit zone keeps its own content.

**Start from**, 8 steps, rows A–D (Z5's `lit-set`, `07` §8.2):

| Start from | Steps 1 to 8 |
|---|---|
| Alternate | A C · B D · A C · B D · A C · B D · A C · B D |
| Chase | A · B · C · D · A · B · C · D |
| Ping-pong | A · B · C · D · C · B · A · B |
| Random (seed 0) | C · A · C · B · D · C · A · D |
| Center-out | B C · A D · B C · A D · … (rows A–C: B · A C · B · A C …) |
| All on | A B C D on every step |

**Invariants**
1. One click, one key or one drag is one undo step, "Edit Step Grid".
2. A step names only rows. Its refs are distinct and in row order. Removing or changing a row rewrites every step, so no step names a ref that isn't a row.
3. Step `k` lights `steps[k mod L]`, with `L = (count steps)` from 1 to 64. With no steps, the pattern is the identity.
4. Two zones lit on one step are one frame under their union (Z5 invariant 3), so no laser draws the picture twice.
5. An empty step hides the picture (move mode) or the lane's zones' own content (gate mode), and nothing else.
6. "Start from" a style gives exactly that style's first L steps, for a lane of refs.
7. The playhead reads the snapshot only. No tick writes app state.
8. A grid edited while it plays shows from the next tick.
9. A pattern whose keyframes are on isn't edited by the grid, so a grid edit never lands in a keyframe nobody sees.
10. The grid adds nothing to the render: it is Z5's stage with a lit set read from data (Z5's invariants 1–9 hold, and 16: a press steps it at the first tick after the press).

## 9. Tests (named test vars, fixtures)

Fixtures: Z5's `zone-remap-test` frames and `ctx`; Phase 4a's `press` (`test/laser_show/vibes/reactions_test.clj:31-32`: one press of a source, for the ctx's `:pulses`); `test-timing-ctx` (`test/laser_show/services/frame_service_test.clj:60-67`); `init!` and `ticks!` (`test/laser_show/services/show_clock_test.clj:44-66`); `with-toolkit` and `with-app` (`test/laser_show/ui_test/fixtures.clj:17-58`), `with-clock` (Z3's, from `test/laser_show/ui_test/preview_test.clj:70`), `fire-drag!` (`test/laser_show/ui_test/testfx.clj:457-484`). A new project's effects grid has the Master at `[0 3]` (vibe Phase 5), so `opens-from-an-effect-cell-test` puts its pattern in another cell.

- `laser-show.routing.step-grid-test` (NEW, pure): `toggle-adds-and-removes-test`, `steps-keep-row-order-test`, `paint-sets-not-toggles-test`, `set-length-pads-and-cuts-test` (1 and 64 are the bounds), `add-row-refuses-a-row-twice-test`, `remove-row-clears-its-steps-test`, `replace-row-rewrites-steps-test`, `move-row-keeps-steps-test`, `rotate-test`, `fill-row-test`, `from-style-test` (the Start from table), `center-out-test` (3, 4 and 5 rows), `every-laser-becomes-zones-test`, `paste-matches-rows-by-ref-test`, `cell-at-test` (edges, outside the grid), `normalize-test`.
- `laser-show.routing.zone-remap-test` (Z5's): `steps-light-their-zones-test`, `steps-wrap-at-their-length-test`, `rest-step-hides-test`, `two-zone-step-is-one-union-key-test`, `steps-gate-in-zones-mode-test`, `no-steps-is-the-identity-test`, `steps-on-a-press-test` (a HIT `:count` in the ctx's pulse view), `steps-restart-on-a-press-test`, `offset-moves-the-playhead-test`.
- `laser-show.routing.compose-test` (Z3's): `step-grid-on-one-laser-test` (§8's table: halves, dark, whole once).
- `laser-show.animation.effects-coverage-test`: the `:steps` exemption.
- `laser-show.events.step-grid-handlers-test` (NEW): `toggle-is-one-undo-step-test`, `drag-paint-is-one-undo-step-test`, `edits-reach-a-cue-pattern-and-a-cell-pattern-test`, `keyframed-pattern-is-not-edited-test`, `open-makes-a-grid-from-the-pattern-test`, `start-from-is-one-undo-step-test`, `paste-from-another-grid-test`.
- `laser-show.services.show-clock-test`: `zone-steps-follow-a-grid-test`.
- `laser-show.subs-test`: `step-grid-sub-test` (rows with lanes, the wrap note, the keyframe guard).
- UI, NEW `laser-show.ui-test.step-grid-test` (`with-toolkit` once, `with-app` each): `opens-from-a-cue-pattern-test`, `opens-from-an-effect-cell-test`, `click-toggles-a-cell-test`, `drag-paints-a-row-test` (`fire-drag!` on the grid body), `keyboard-moves-and-toggles-test`, `length-buttons-test`, `add-and-remove-a-row-test`, `start-from-chase-test`, `copy-and-paste-between-grids-test` (the tooltips name the keys as `shortcut-text` writes them), `undo-reverts-a-drag-test`, `rows-say-where-they-go-test`, `playhead-follows-the-clock-test` (`with-clock`).

Run: `clj -M:mac-arm:test step-grid zone-remap compose effects-coverage show-clock subs`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.step-grid-test`, then `-n laser-show.ui-test.zone-patterns-test` (Z5's), then both full suites (the `targeted-ui-testing` skill).

## 10. Commit plan

Each step is one commit: its tests first (run them red), then the code, then its tests green.

| # | Commit message | Red first | ~d |
|---|---|---|---|
| 1 | `feat(zones): a zone pattern can be a step grid: each step lights its own zones, a rest step lights none, and a step that lights two draws the picture in both, once per laser` | `zone-remap-test` (steps), `compose-test`, `effects-coverage-test` | 0.5 |
| 2 | `feat(zones): step grid edits: toggle, paint, length, rows, rotate, clear, copy and paste, and a grid started from Alternate, Chase, Ping-pong, Random or Center-out` | `step-grid-test`, `step-grid-handlers-test` | 0.75 |
| 3 | `feat(ui): a step grid window, opened from a cue's or an effects cell's pattern, to click, drag or type a pattern, with each row's zone and where it goes` | UI `step-grid-test` (all but the playhead), `step-grid-sub-test` | 1 |
| 4 | `feat(ui): the step grid's playhead follows the playing pattern, and the pattern window shows a small grid` | `playhead-follows-the-clock-test`, `zone-steps-follow-a-grid-test` | 0.5 |
| 5 | `plans: zones Z6 done (<sha>): as built, deviations, tests, red-first results` | – | – |

About 2.75 days.

## 11. Out of scope / alternatives considered

- **Levels per cell** (a weighted chase): later. It needs gains per output (overview §8). A step is a vector of refs, so a later levelled step can be a map `{ref level}`, told apart from a vector: old grids load unchanged (S-3).
- **Swing, a rate per step, probability, conditional steps**: later.
- **Recording a grid live** (tapping zones in time while it loops): later. It needs each press placed on the pattern's own clock. Phase 4a's pulse view gives every press's age in beats (`:beats`, `animation/pulses.clj:105-122`, stamped by `pulses/stamp`, `:69-84`), so a recorder can place a press at `t − beats`.
- **A different picture per row**: that's gate mode with the cue's own zones (§8). A picture per row from one source would need a render per row (needs `06`), not a re-key.
- **Rows by index instead of by ref** (`[[0] [1] [] [0 1]]`): rejected (S-1).
- **The grid inside the pattern window**: rejected for v1 (Z6-Q5). 32 steps want the width.
- **Keyframes that switch grids**: they already work (non-numeric params hold, `animation/interpolation.clj:47-49`). The grid just doesn't edit a keyframe (invariant 9).

## 12. Spec questions and open questions

**Spec questions** (where `01` or the brief is unclear, and what this doc chose)

| # | Spec | The problem | Chosen |
|---|---|---|---|
| S-1 | The brief's `:steps [[:zone/a] [:zone/b] [] [:zone/a :zone/b]]` | Steps by ref must be rewritten when a row changes. Steps by index must be rewritten when a row is removed or moved | By ref, as the brief writes it: a step reads on its own, and moving a row changes nothing. The row edits rewrite the steps (invariant 2) |
| S-2 | "Source picture copied to each lit zone — dedup makes it safe" | Spec §6.1 used to make a copy one object under several keys | Z5's S-1, now spec §6.1 ("Pattern copies (Z5 S-1)"): one frame under the union of the lit zones. Each laser draws it once either way, and the union survives a join |
| S-3 | Levels later "need weights" (overview §8) | v1 has no gains per output | On and off only. The step's shape leaves room for `{ref level}` |
| S-4 | Rows are "the zones the pattern uses" | Z5's `:lane/lasers` can't be a fixed row, and pinning the rig's lasers would name the venue | Rows are refs. A pattern over Every laser becomes zones A, B, C… when it turns into a grid, and the window says so |
| S-5 | D-25: zones wrap over a map's lanes | Rows A and C can both mean Left | Allowed. Each row shows its lane, and a note names the rows that share one |
| S-6 | Spec §9: beats per bar "from the clock, 4 by default" | There's no setting (Z4's SQ-8) | Bar lines every `4 × rate` steps, counted from global beat 0 with the show's beats, or from the cue's start with the cue's |

**Open questions for you** (★ = recommended)

> **Z6-Q1 · A new grid's length and speed.**
> - **A ★** 8 steps at 2 a beat: one bar of eighths, readable at a glance.
> - **B** 16 steps at 4 a beat: one bar of sixteenths, as drum machines do.
> - **C** 16 steps at 2 a beat: two bars of eighths.

> **Z6-Q2 · Does a grid follow the pattern pads?**
> - **A ★** Not by default: a grid is a rhythm you wrote. Tick "Follows the pattern pads" to let the pads switch it.
> - **B** Yes, like every other pattern.

> **Z6-Q3 · Painting with a drag.**
> - **A ★** The first cell decides: pressing an unlit cell lights every cell you cross, and pressing a lit one clears them.
> - **B** Each cell crossed flips.

> **Z6-Q4 · How long a grid can be.**
> - **A ★** Any length from 1 to 64, with 8, 16 and 32 one click away (odd lengths make patterns that drift against the bar).
> - **B** Only 8, 16 or 32.

> **Z6-Q5 · Where the grid opens.**
> - **A ★** Its own window, from the pattern window, the Pattern chip and an effects cell's editor.
> - **B** Inside the pattern window, which grows to fit it.
