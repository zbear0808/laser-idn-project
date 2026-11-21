# Phase Z6: the step grid

**Written:** 2026-11-19   **Written for commit:** `f178f56` (`master`; first written at `739bd74`, then re-verified here: `src/` is as vibe Phase 5 left it in `9f5d78a`, after vibe Phase 4b (`f5a4f84`), 4a (`6715fb4`) and UX Phase 3 (`b9b9d55`), and `test/` as `43fa00d` left it; every line below is at that source). Z2–Z5 change several of these files first, so **re-check every path and line at HEAD before starting.**
**Status:** **Done in code, on `master`** (`e31d24b`..`372e00a`, 2026-11-20; as built in §13). Still owed: seeing it on a real projector.   **Needs:** Z5 (`07`): the zone stage (`routing/zone_remap.clj`), the `:zone-pattern` effect, a cue's `:zone-patterns`, the pattern window, and `:zone-steps` in the snapshot. Vibe **Phase 4 has landed** (4a `6715fb4`, 4b `f5a4f84`), so a grid steps on HIT, ACCENT, FLASH, a vibe change or any cue start from the start, and can restart on one, through Z5's `:step-on` and `:restart-on` (`07` §4.2). Vibe **Phase 5 has landed** (`9f5d78a`): its Fader modulator can scrub a grid (§8).
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

**Questions the build raised** (2026-11-20; each is built as its ★, and listed in §13)

> **Z6-Q6 · A pattern that was a grid, its style switched since** (from the Pattern chip, the pattern window or a modulator).
> - **A ★ (built)** Step grid… brings its steps back: they stay in the pattern when the style changes.
> - **B** It becomes a new grid of its current style's first 8 steps, as a pattern that never was one does.

> **Z6-Q7 · What Shift and Swap become as a grid.** A grid lights places; it can't trade what's in them.
> - **A ★ (built)** A new grid's start: Alternate over the pattern's zones.
> - **B** A Chase over its zones (where A's content goes under Shift).

> **Z6-Q8 · The show lock in the grid's window.**
> - **A ★ (built)** The editing part (the toolbar, the grid, Moves, Follows) is greyed out with a line that says why, as for the keyframes; opening it, its cursor and the playhead go on. Copy is greyed with the rest.
> - **B** Everything stays live and each click is refused with a notice, as in the pattern window (a drag would bring one refusal per mouse move).

> **Z6-Q9 · A new modulator on a pattern's Style spans Steps too.**
> - **A ★ (built)** 0 to 6, Steps last, so an old show's modulator from 0 to 5 keeps its six styles.
> - **B** A new one spans the six rule styles only; Steps is picked by hand.

## 13. As built (2026-11-20, `e31d24b`..`372e00a`, on `master`)

Built in worktree `zones-z6` off `ea4d153` (Z5 on `master`), while vibe Phase 8's zone reactions (`6544efb`..`cdf1d57`) landed on `master` (`734e411` at the end). The ★ answers are built: Z6-Q1 A (a new grid is 8 steps at 2 a beat), Z6-Q2 A (a grid doesn't follow the pattern pads until it is ticked), Z6-Q3 A (the first cell of a drag decides), Z6-Q4 A (1 to 64 steps, 8, 16 and 32 one click away) and Z6-Q5 A (its own window); and Z5's Z5-Q1 to Z5-Q10 A as Z5 built them (the pads switch a grid that follows them to their style; As set gives it its steps back). The build raised Z6-Q6 to Z6-Q9 (§12), each built as its ★. `legacy-shows` ran in every step. **Not done: seeing it on a real projector** (00 R-1): §8's grid on one laser's halves and on four lasers, and a grid stepped by HIT.

The worktree's folder was emptied once, before step 1's first edit, by something outside the work (the temporary folder, at about 20:28), so nothing was lost. The worktree was put back at the same path with `git worktree add -f … zones-z6`, and each step was committed as soon as its tests passed.

### Where it landed

| # | Commit | Step (§10) |
|---|---|---|
| 1 | `e31d24b` | `:steps` a lit style of `routing.zone-remap` (NEW `grid-steps`, `grid-step`; `lit-set` is step k mod L of the steps, those in the lane; no steps is the identity); `routing.zone-patterns`: NEW `rule-styles` (the six), `styles` with `:steps` last, the `:steps` parameter (`:step-list`), the grid's preset, `describe`, `lane-text`, `warnings` (a grid needs one row), pads from the rules only; the pattern chip and New zone pattern ▸ list the rules; the numeric list's `:step-list` control; the coverage exemption |
| 2 | `879cca3` | NEW `routing/step_grid.clj` (the edits, `as-grid`, `from-style`, `center-out`, `grid-rows`, `copy`, `paste`, `cell-at`, `line-cells`, `normalize`); NEW `events/handlers/step_grid.clj` routed for `"step-grid"`; the labels; the show lock's refused events |
| 3 | `b5a3f7e` | NEW `views/components/step_grid.clj` (the body, the rows and numbers, + Row, the toolbar, Moves and Follows, `key-event`), NEW `views/dialogs/step_grid_editor.clj` (mounted in `root.clj`); `subs/step-grid`, `step-grid-clipboard`; Step grid… on the chip and in the pattern renderer, Edit grid… in the numeric list; `css/zones.clj`; `as-grid` brings a former grid's steps back (Z6-Q6) |
| 4 | `372e00a` | the playhead and the Now line (`ext-clock-redraw` on `:zone-steps`); the pattern window's small grid and Edit grid… for Steps, in place of Zones |
| 5 | this commit | this note and §12's new questions |

### Deviations from the plan

- **Styles.** `zone-patterns/styles` is the six rules then `:steps`, last, so a modulator's indices for the rules are unchanged (Z6-Q9). NEW `rule-styles` is the six: the pattern pads (`pad-ids`), the Pattern chip's style items and New zone pattern ▸ list them only; a grid starts from Step grid… (the chip, the pattern window, an effects cell's editor). The `:steps` parameter comes right after `:zones` in the parameter list.
- **`routing.step-grid` has more than §4 lists:** `rows`, `length`, `grid?`, `lit?`, `next-row` (+ Row's letter), `clear-row`, `fill-step` (a step number's Light every row), `grid-rows` and `became-zones` (Every laser as zones, S-4), `as-grid` (any pattern as a grid), `line-cells` (a quick drag paints every cell between two mouse events, so "every cell crossed" holds however fast it moves) and `start-styles`. Every edit returns normalized params, and one that can't apply returns them unchanged (no undo step).
- **What a pattern becomes as a grid** (`as-grid`, used by `:step-grid/open`, the window's view and every edit): its rows from its zones, Every laser as that many zones (at least 2, the first letters it doesn't use), and 8 steps of its own style with its own Width, Path and Seed, at its own rate, Step on and clock, not following the pads (Z6-Q2 A). Shift, Swap, a style a modulator picks and Steps with no steps start as Alternate (Z6-Q7). A pattern that was a grid gets its steps back (Z6-Q6). An edit on a pattern that isn't a grid yet (an undo of the conversion with the window still open) makes it one in the same undo step. Start from uses the pattern's own Width, Path and Seed too (a new grid's are 1, Loop and 0: §8's table).
- **The mouse.** `:step-grid/pointer` (the body's press, drag and release, with `:cell-w :cell-h`) only reads the JavaFX event and dispatches NEW `:step-grid/paint-start` (begins the history transaction and paints, `:history/skip?` so the press isn't a step of its own), `:step-grid/paint` (each cell from the last one) and `:step-grid/paint-end` (commits), so the lock can refuse the painting and still let a held drag go (below). A press that is a popup trigger paints too: the body has no menu, and the headless robot's presses all are. The press is consumed, so the scroll pane around the grid can't take the focus from it.
- **The cursor and the keys.** `:step-grid/cursor` takes `:row :step` (a click), `:move [rows steps]` (the arrows, against the cursor in app state, so two quick keys never read an old one) and `:to :first` or `:last` (Home, End); `:toggle`, `:clear-step` and `:fill-step` without a cell take the cursor's. A row in an event is a ref or an index. `views.components.step-grid/key-event` (pure) maps a key to its event; the body's handler consumes the keys it uses; undo and redo are the scene's, as in the chain editors. The body takes the focus when the window opens and on a press. A shorter grid takes the cursor with it.
- **The window** (`views/dialogs/step_grid_editor.clj`, 940 × 600):
  - Rate is Z5's control (a slider with ∿ and its words), not a "[2 ▾]" combo, and so are Step on, Restart on (while Step on is the beat) and Offset; Moves and Follows the pattern pads are Z5's too (`zone-pattern-editor/control` and `moves-radios` went public). The length field is the app's commit field.
  - + Row is a split menu button: a click adds the next zone letter, its menu the Target menu's `:pattern` rows without Every laser, a row already in the grid ticked and greyed.
  - Cells are 22 × 26 px, so 32 steps fit; more scroll sideways. Beat and bar lines are drawn under the cells (every `rate` steps, every 4 × `rate`, from the offset) while the grid steps on the beat at a whole number of steps a beat and a whole offset; a bar's number is bold.
  - Lines under the grid: the guard (keyframes, or the show lock: NEW text "The show lock is on. Unlock it on the Perform tab to edit this grid."), the Every laser note after a conversion (two zones read "A and B", a run "A–D", else a list), the wrap note for each lane two or more zone rows share ("A, C and E all go to Left; B and D both go to Right under Left / Right.", none under Unison, whose fold line says it), and Z5's fold line.
  - The Now line in the footer says "Step 5 of 8" (NEW text) while the pattern plays and "Not playing" while it doesn't.
  - A target that holds no zone pattern (the Master's cell, a pattern since removed) opens no window, or says "There's no zone pattern here to draw on a grid."
- **The pattern window**, with the style Steps: a "Grid" row in place of Zones (the grid's rows are edited in its window), its small read-only grid (9 px cells), "8 steps over A B C" and Edit grid…; any other style a "Grid  [Step grid…]" row. The numeric list's Steps says "8 steps" and has Edit grid…. The chip's menu has Step grid… between the separator and Edit pattern…, and a cue without a pattern gets the new grid from it.
- **Words.** The chip says a grid as `describe` does, "Steps · 8 · A B"; its tooltip "Zone pattern: Steps. Moves the All picture on 8 steps over A B, 2 steps a beat, from the cue's start."; an effects cell's tooltip "Zone pattern: Steps on 8 steps over A B, 2 steps a beat (show beats)". The Start from tooltips are §7's ("Replaces the grid with the first 8 steps of Chase over these rows", the grid's own length), and All on's is "Lights every row on every step".
- **Warnings.** A grid needs one row, not two: one row is a rhythm in one zone. With none: "This pattern needs a zone (it has none)" (the lint names its cell, as for the others).

### The show lock (Z6-Q8)

- Refused as structure, each in `blocked-events` with the label "Edit Step Grid" ("Edit Step Grid refused"): `:step-grid/toggle`, `paint-start`, `paint`, `set-length`, `add-row`, `remove-row`, `set-row`, `move-row`, `fill-row`, `clear-row`, `clear`, `clear-step`, `fill-step`, `rotate`, `start-from`, `paste`. Its example in `show-lock-test`'s `structure-edits` is `:step-grid/toggle` on B1's grid; `locked-zone-pattern-edits-are-refused-test` adds the steps edited as any parameter (`:chain/update-param :steps`, refused by Z5's `pattern-edit?` as "Zone pattern edit refused") and the grid's own edits on an effects cell's grid, with their label. The toolbar's Rate, Offset, Step on, Restart on, Moves and Follows are `:chain/update-param` on the pattern, so `pattern-edit?` refuses them as for Z5.
- Let through: `:step-grid/open` (it opens the window, and while locked makes no grid: the window shows the grid the pattern would become, greyed), `:step-grid/cursor`, `:step-grid/copy`, `:step-grid/pointer` (it only reads the mouse) and `:step-grid/paint-end`: a drag held when the lock goes on still lets go, its transaction committed as one undo step, as a held key's release goes through (NEW `locked-step-grid-opens-and-a-drag-lets-go-test`). The playhead reads the snapshot only.
- The window greys the editing part while the lock is on, with the line above, so a drag can't bring a refusal per mouse move; Copy is greyed with it (Z6-Q8).

### Beyond §9

- `routing.step-grid-test`: `lit-test`, `clear-row-step-and-grid-test`, `as-grid-test` (a pattern plays the same for 8 steps; Every laser; Shift and Swap; a former grid's steps), `line-cells-test`.
- `events.step-grid-handlers-test`: `row-and-step-menus-test`, `cursor-moves-and-stays-in-the-grid-test`.
- `events.show-lock-test`: `locked-step-grid-opens-and-a-drag-lets-go-test`.
- UI `step-grid-test`: `pattern-window-shows-a-small-grid-test`; the keyframe guard in `rows-say-where-they-go-test`.
- `routing.zone-patterns-test`: the grid's preset, `describe` and warnings. `views.components.zone-pattern-editor-test`: the chip's words and the rows for Steps. `zone-remap-test/remap-invariants-test` generates grids too (their steps from a generator of their own, so the other styles' cases keep their draws).

### §9 said these pass unchanged; they don't

- UI `zone-patterns-test/offset-takes-a-modulator-test`: a new modulator on Style spans 0 to 6 ("… · 5 Swap · 6 Steps", Z6-Q9).
- UI `zone-patterns-test/footer-chip-adds-a-pattern-test`: the chip's items gain "Step grid…".
- `zone-patterns-test/presets-are-valid-test`: `styles` gains `:steps`, the default lanes a seventh, and a parameter of type `:step-list`.

### Tests added

- `laser-show.routing.step-grid-test` (NEW): the sixteen §9 tests and the four above.
- `laser-show.routing.zone-remap-test`: the nine §9 tests; the invariants' generator. `laser-show.routing.compose-test`: `step-grid-on-one-laser-test` (§8's table on 1 and 4 lasers, both halves whole once, and What's already in the zones). `laser-show.animation.effects-coverage-test`: the `:steps` exemption.
- `laser-show.events.step-grid-handlers-test` (NEW): the seven §9 tests and the two above. `laser-show.events.show-lock-test`: as above.
- `laser-show.services.show-clock-test`: `zone-steps-follow-a-grid-test` (with a grid edited while it plays, invariant 8). `laser-show.subs-test`: `step-grid-sub-test`.
- UI `laser-show.ui-test.step-grid-test` (NEW): the twelve §9 tests and `pattern-window-shows-a-small-grid-test`.

### Red first

Where the new code didn't exist yet, the run was first made to compile with stubs (an inert namespace, nil subs), so the tests below were seen failing unless it says otherwise.

| Step | Before the change |
|---|---|
| 1 | `zone-remap-test`, `compose-test`, `effects-coverage-test` and `zone-patterns-test` with `rule-styles` stubbed as `styles`: 79 tests, 72 failures, 0 errors. **Not seen red:** `no-steps-is-the-identity-test` (an unknown style was the identity already) and `remap-invariants-test`'s grids (identity on the old stage). The first green run had 1 failure, the test's own (normal form puts `:graphics` before `:auto.laser/p2`) |
| 2 | `step-grid-test`, `step-grid-handlers-test`, `show-lock-test` and `history-handlers-test` (the pattern ran `compose-test` and `subs-test` too) against an inert `routing.step-grid` and no `"step-grid"` route: 79 tests, 177 failures, 0 errors. **Not seen red:** the "nothing changes" parts of `keyframed-pattern-is-not-edited-test` (an unrouted event changes nothing either). The first green run had 1 failure and 1 error: a test's `'(B)` (a symbol, not the zone) and a handler bug (a cue's new grid put at index 0 of no vector made a map) |
| 3 | `step-grid-sub-test` against nil subs, and `as-grid-test`'s former-grid case against the step-2 `as-grid`: 2 tests, 18 failures. UI, before any view code (a stub namespace with the cell size only): `step-grid-test` 11 tests, 1 failure, 11 errors (each waited for the window); Z5's chip test with Step grid… expected: 1 failure. The first green UI run had 5 errors: four were the test's own (a message passed to `wait-for!` as its timeout) and one a robot click on a window behind another (now fired at the node); then two real bugs: the grid ignored a press that is a popup trigger (every headless robot press is) and the scroll pane took the focus on a click |
| 4 | `zone-steps-follow-a-grid-test` and `zone-pattern-editor-test`: 4 tests, 2 failures (the rows for Steps). **Not seen red:** `zone-steps-follow-a-grid-test` (the clock's `:zone-steps` is Z5's and the grid's stage step 1's) and the chip's words for Steps (step 3 had built them). UI `playhead-follows-the-clock-test` and `pattern-window-shows-a-small-grid-test`: 2 tests, 2 errors. The first green run of the playhead test failed on its own clock jump of 1.25 s: the clock skips a jump of more than a second (below); it moves 0.75 s now |

### Verification

| Where | Unit suite (`clj -M:mac-arm:test`) | UI suite (`clj -M:test-ui:mac-arm:test-ui-headless`) |
|---|---|---|
| `ea4d153` (the base: Z5 as landed; 07 §13's rebase row, not re-run) | 1,766 tests, 569,486 assertions, 0 failures | 254 tests, 1,370 assertions, 0 failures |
| **`372e00a`, the branch's last code** | **1,808 tests, 569,949 assertions, 0 failures, 0 errors** | **267 tests, 1,424 assertions, 0 failures, 0 errors** |
| the trial merge onto `master` `734e411` (vibe Phase 8's zone reactions in), tree `8aaabe8` | 1,819 tests, 570,197 assertions, 0 failures, 0 errors | 268 tests, 1,436 assertions, 0 failures, 0 errors |
| **rebased onto `734e411`, as landed** | **the same `src/`, `test/`, `deps.edn`, README and QUICKSTART trees as the trial merge (`8aaabe8`), so its run above stands** | **the same** |

Each step also ran its targeted namespaces (§9's run list) and the UI namespaces whose views it changed (`step-grid-test`, Z5's `zone-patterns-test`), and `legacy-shows` every time. Screenshots were checked with the window at 940 × 640: 8 steps over three rows, 32 steps (they fit), the show lock's greyed grid, the playhead while the cue plays, and the pattern window at 800 × 640 with a grid's small grid. `~/LaserShowProjects` didn't exist before the work and doesn't after it: no test wrote under the home folder.

### For the merge

- **Into `master` at `734e411`** (vibe Phase 8's zone reactions `6544efb`..`cdf1d57` and their plans in): `git merge-tree` merges cleanly, tree `8aaabe8`. The merged tree was unpacked with `git archive` into a folder of the worktree's (git-ignored) `test-results/`, its suites run there (the table above), and the folder deleted. Both sides edit `src/laser_show/subs.clj` (the step grid's block after the Map cycle's; vibe 8b's beside its vibe subs) and `test/laser_show/subs_test.clj` (each appends a test): both merge cleanly.
- **With the zone reactions** (`vibes/reactions.clj`): they make their patterns with `zone-patterns/preset-instance`, whose params now carry `:steps []`; their test compares with `preset-instance` itself, so it passes on the merge. A zone reaction's cell opens in the grid window like any cell pattern: it keeps its Step on HIT or ACCENT, and its Every laser becomes zones. Z6 doesn't edit `reactions.clj`, `events/handlers/vibes.clj` or `views/tabs/vibes.clj`.
- **Every file the branch edits** (28 in `src/` and `test/`, and this doc):
  - NEW: `routing/step_grid.clj`, `events/handlers/step_grid.clj`, `views/components/step_grid.clj`, `views/dialogs/step_grid_editor.clj`; the tests `routing/step_grid_test.clj`, `events/step_grid_handlers_test.clj`, `ui_test/step_grid_test.clj`.
  - `routing/zone_remap.clj`, `routing/zone_patterns.clj`, `animation/effects/zone.clj` (its doc), `events/handlers.clj` (the route), `events/handlers/history.clj` (the labels), `events/show_lock.clj`, `subs.clj`, `css/zones.clj`, `views/root.clj`, `views/components/grid_cell.clj` (one line: the rules), `views/components/parameter_controls.clj`, `views/components/zone_pattern_editor.clj`.
  - tests: `animation/effects_coverage_test.clj`, `events/show_lock_test.clj`, `routing/compose_test.clj`, `routing/zone_patterns_test.clj`, `routing/zone_remap_test.clj`, `services/show_clock_test.clj`, `subs_test.clj`, `ui_test/zone_patterns_test.clj`, `views/components/zone_pattern_editor_test.clj`.
- Files other work often touches, where Z6's edits are small and local: `subs.clj` (one new block), `events/handlers.clj` (one route), `events/handlers/history.clj` and `events/show_lock.clj` (one block each), `views/root.clj` (one dialog), `css/zones.clj` (rules appended). `00-overview.md` and `10-questions.md` are `master`'s alone: Z6-Q6 to Z6-Q9 are in §12 here, for the questions file.

### Found on the way

- **A jump of more than a second doesn't move the clock:** `cue-timing/update-global-clock` and the cue timing skip a tick that comes more than 1,000 ms after the last one (a stall), so a test that moves `fixtures/with-clock`'s fake time must step under a second.
- **Every zone pattern now has the `:steps` parameter,** so its Numeric mode needed a `:step-list` control: `param-control` falls back to a slider, which a parameter with no Min or Max breaks.
- **Test helpers:** `ui/query` looks in the topmost window only (a window behind another needs `query-all`), and a robot click lands on the window on top at that place; the headless robot's presses are popup triggers, so a control that ignores those can't be clicked by it.
