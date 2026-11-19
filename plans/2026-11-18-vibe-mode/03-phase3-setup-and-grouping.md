# Phase 3: setting up vibes (the Vibes tab, the grid, the wizard)

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** Proposed. Needs Phases 1 and 2; V6 (audience cells) answered in Phase 1   **Last verified against:** `b1da0f2`
**Supersedes / Superseded by:** none   **Related findings:** keyboard plan K-15 (learn also fires the old binding): fixed here if that plan hasn't, because the wizard learns five keys in a row; review 01 T5 (narrow subscriptions)

This is the part that makes vibes *easy to set up*. The aim is a working set in a few minutes: make vibes from the grid, fill their roles by clicking cells, learn the five controls with one button.

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| A cell has no idea what it belongs to | `views/components/grid_cell.clj:82-197` (`generic-grid-cell`), `:203-289` (`grid-cell`) | state classes (`:30-43`), a label, an active dot, a trigger bolt (`:186-189`). No place for membership |
| Cell context menu | `views/components/grid_cell.clj:233-278` (cue), `:326-360` (effects) | Copy, Paste, Edit, Clear, Learn Trigger, Configure Triggers |
| The grid is one component | `views/components/grid_tab.clj:43-60` (`grid`, takes `:cell-component`), `:85-105` | a picker can reuse it with another cell component |
| Single selection | `views/components/grid_tab.clj`, `subs.clj:69` (`selected-grid-cell`) | there is no multi-select on the grid |
| Inline rename | `views/components/inline_edit.clj` | exists |
| Undo labels | `events/handlers/history.clj:125-142` (`event-descriptions`); test `events/history_handlers_test.clj:252-257` | every key must be a routed event, or the test fails |
| Learn, one target at a time | `events/handlers/input.clj:223-278` | no queue; the captured press also reaches the mapper before the binding exists (K-15) |
| Mapper while learning | `events/handlers/input_mapper.clj:62-78` | dispatches every discrete input as `:trigger/input`, learning or not |
| UI state | `state/domains.clj:128-189` (`ui`) | no place for the Vibes tab's selection |
| Audience routing | `routing/zone_effects.clj:127-156` | an item's destination is its `:zone-group-id`, or a `:zone-selector` effect's `:target-zone`, or its track's, or the chain's `:destination-zone`, else `:all` |
| Zone groups and tags | `state/domains.clj:271, 314-333, 359-362` | default `:crowd` group; projectors carry `:tags` such as `:crowd-scanning` |

## 2. Root cause

Everything a vibe needs to be set up is possible only by editing EDN. There is no list of vibes, no way to see which cells belong where, no quick start, and learn can't be chained.

## 3. Grouping aids, and why these

| Aid | What it saves | Where |
|---|---|---|
| **Make vibes from rows** | the first set in one click: a row is already how people lay out a grid | Vibes tab button |
| **Pick on a grid** | no typing coordinates: click cells to put them in the active role | Vibes tab |
| **Dots on the Grid tab** | you can always see which cells are in which vibe, so you don't lose track while editing | `grid-cell` |
| **Add to vibe ▸** on a cell's menu | grouping while you build cues, without leaving the grid | `grid-cell`, `effects-cell` |
| **Starter vibes** | a new project already plays | Phase 1 |
| **Learn all** | the five controls in 20 seconds | Vibes tab |
| **A checklist** | what is left to do, from the data (cues, vibes, roles, controls) | Vibes tab header |
| **Lint** | catches the show-stoppers before the show (empty pool, missing cue, a pool of one) | Vibes tab, tab label |
| **Rules of thumb** | makes pools that look good under random picks (`00-overview.md` §4) | Vibes tab, a collapsible note |

## 4. Changes per namespace

- `laser-show.views.root` (existing): `main-tabs` gets `{:id :vibes :label "Vibes"}` between Grid and Perform; `tab-content` gets `:vibes`.
- `laser-show.views.tabs.vibes` (NEW): the tab. Left: `vibe-list`. Right: `vibe-editor` (name, colour, energy; one section per role). Under it: the **picker** (`views/components/vibe_picker.clj`, NEW): the cue grid for HIT, ACCENT, FLASH cue and BED, the effects grid for FX and flash FX. Above: the checklist and the **Controls** panel. Below: lint, and the rules of thumb.
- `laser-show.views.components.vibe-picker` (NEW): `vibe-pick-cell` and `fx-pick-cell`, both built on `generic-grid-cell` (`views/components/grid_cell.clj:82`), with `:on-click {:event/type :vibe/toggle-cell}` / `:vibe/toggle-fx`, drawn "in" or "out" of the active slot.
- `laser-show.views.components.vibe-controls` (NEW): the Controls panel: a row per control (HIT, ACCENT, FLASH, VIBE, BLACKOUT; the faders in Phase 5): label, binding (`trigger-bank/binding-label`), **Learn**, **Clear**; and **Learn all**.
- `laser-show.views.components.grid-cell` (existing): `grid-cell` and `effects-cell` read `(fx/sub-ctx context subs/cell-vibes col row)` and pass `:vibes` in `display-data`; `generic-grid-cell` draws up to three dots plus "+n" (`:children`, `:170-189`). The context menus (`:233-278`, `:326-360`) get **Add to vibe ▸** (a vibe, then Hit / Accent / Flash / Bed for a cue; FX set / Flash FX for an effect cell) and **Remove from vibe ▸**.
- `laser-show.subs` (existing): NEW `vibe-cell-index` (memoized on the library only: `{cell [{:vibe id :name n :color c :role r}]}`), `cell-vibes [col row]` (an identical vector for an unchanged cell), `vibe-editor` (selected vibe, slot, pick state), `vibe-lint`, `vibe-checklist`, `vibe-controls`.
- `laser-show.vibes.library` (existing from Phase 1): edit functions, all pure and returning the new library: `add-vibe` (takes the id from `:next-id`), `duplicate-vibe`, `remove-vibe`, `move-vibe`, `rename-vibe`, `set-color`, `set-energy`, `toggle-cell`, `set-single` (flash cue, bed), `toggle-fx`, `set-role-option`, `make-from-rows`. NEW `lint`.
- `laser-show.events.handlers.vibes` (existing): the edit events in §6. Each returns at least `{:state state}`, even for missing arguments (the history-label test, `events/history_handlers_test.clj:252-257`, calls every labelled event with none).
- `laser-show.events.handlers.input` (existing, `:258-270`): when the learned action is a vibe role and `[:ui :vibes :wizard :queue]` is not empty, the result also has `:dispatch` of the next `:input/start-learn`, and the wizard's `:done` grows.
- `laser-show.events.handlers.input-mapper` (existing, `:62-78`): discrete events are not dispatched as `:trigger/input` while `[:backend :input :learning-target]` is set (K-15). Skip if keyboard plan Phase 5 already did this.
- `laser-show.events.handlers.history` (existing, `:125-142`): `event-descriptions` gets one label per edit event.
- `laser-show.state.domains` (existing): `ui` gets `:vibes {:selected nil :slot :hit :wizard nil}`.
- `laser-show.css.vibes` (existing from Phase 2): adds `.vibe-card`, `.vibe-slot`, `.vibe-slot-active`, `.vibe-pick-in`, `.vibe-pick-out`, `.vibe-dot`, `.vibe-check-done`.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:config :vibes]` (order, library, settings) | document | the edit events | **yes / yes** |
| `[:config :input :trigger-map]` role entries | document | learn, Clear | yes / yes |
| `[:ui :vibes {:selected :slot :wizard}]` (NEW) | UI state, session | `:vibe/select-for-edit`, `:vibe/set-slot`, the wizard | no / no |

A selection is not part of the project, so clicking around the editor never marks it unsaved.

## 6. Events and effects

| Event | Exists? | Dispatched from | Undo label |
|---|---|---|---|
| `:vibe/add` | NEW | New button | "Add Vibe" |
| `:vibe/duplicate {:vibe}` | NEW | card menu | "Duplicate Vibe" |
| `:vibe/remove {:vibe}` | NEW | card menu | "Delete Vibe" |
| `:vibe/move {:vibe :to}` | NEW | card up/down | "Reorder Vibes" |
| `:vibe/rename {:vibe :name}` | NEW | inline edit | "Rename Vibe" |
| `:vibe/set-color`, `:vibe/set-energy` | NEW | swatches, dots | "Change Vibe" |
| `:vibe/toggle-cell {:vibe :slot :cell}` | NEW | picker, grid menu | "Edit Vibe Cues" |
| `:vibe/toggle-fx {:vibe :slot :cell}` | NEW | picker, effects menu | "Edit Vibe Effects" |
| `:vibe/set-option {:vibe :role :key :value}` | NEW | mode, beats, select, solo, allow-audience | "Change Vibe Option" |
| `:vibe/make-from-rows` | NEW | button | "Make Vibes From Rows" |
| `:vibe/select-for-edit`, `:vibe/set-slot` | NEW | the vibe list and slot headers (`views/tabs/vibes.clj`, NEW) | none (UI) |
| `:vibe/learn-all`, `:vibe/wizard-cancel` | NEW | the Controls panel (`views/components/vibe_controls.clj`, NEW) | none |
| `:input/start-learn`, `:input/learn-captured`, `:input/cancel-learn` | yes (`events/handlers/input.clj:223-278`) | the wizard chain | "Learn Trigger" |

No new effect key.

## 7. Data examples and invariants

### Make vibes from rows

```clojure
;; cue grid: row 0 has cues in cols 0-7, row 1 in cols 0-2, rows 2-3 are empty
;; the library already has :vibe-1 (HIT [[0 0] [1 0] [2 0] [3 0]]) and :vibe-2
(vibes/make-from-rows state)
;; => adds
{:vibe-3 {:id :vibe-3 :name "Row A" :color "#E67E22" :energy 2
          :hit {:cells [[0 0] [1 0] [2 0] [3 0] [4 0] [5 0] [6 0] [7 0]] :select :shuffle :mode :decay :beats 1.0}
          :accent {:cells []} :flash {:cue nil :fx [] :solo? true} :bed nil :fx []}
 :vibe-4 {:id :vibe-4 :name "Row B" :hit {:cells [[0 1] [1 1] [2 1]] …}}}
;; and :order [:vibe-1 :vibe-2 :vibe-3 :vibe-4]
;; a row whose non-empty cells are already exactly some vibe's HIT pool is skipped, so the button is safe to press twice
```

### Toggle a cell in a role

| Slot | Click on a cell not in it | Click on a cell in it |
|---|---|---|
| `:hit`, `:accent` | appended at the end (pool order = click order) | removed |
| `:flash-cue`, `:bed` | replaces the single cell | cleared |
| `:fx`, `:flash-fx` | appended | removed |

A cell may be in several roles and several vibes. The picker draws the active slot's cells "in" and shows other roles of the same vibe as a dimmer dot.

### Learn all

```clojure
;; :vibe/learn-all
[:ui :vibes :wizard] ;; => {:queue [:hit :accent :flash :vibe :blackout] :done []}
;; starts :input/start-learn {:target-action {:type :vibe :role :hit}}
;; a note-on arrives -> learn-captured binds it, :done [:hit], and dispatches the next start-learn (:accent)
;; after :blackout: :wizard nil
;; :vibe/wizard-cancel -> :input/cancel-learn, :wizard nil
```

### Lint (a table; each row is a test)

| Level | Condition | Message |
|---|---|---|
| error | a vibe's HIT pool has no playable cell | "Shapes: HIT has no cues to play" (Phase 4 splits this row: an error only when HIT has no tap reaction either, otherwise info "moves effects only") |
| error | a pool cell has no cue, or chases MTC | "Shapes: A3 can't be played (empty / follows timecode)" |
| error | the flash cue can't be played (empty, or follows timecode) | "Shapes: FLASH cue G1 can't be played" |
| warn | a vibe with neither a flash cue nor flash FX | "Shapes: FLASH does nothing in this vibe" |
| error | an id in `:order` is not in the library, or the reverse | "Order and library disagree" |
| warn | a pool of one | "Lines: one cue in HIT repeats on every press" |
| warn | an audience-capable cell in a vibe without `:allow-audience?` | "Shapes: C2 reaches the crowd zone, so it will never be picked. Allow it in this vibe, or remove it" |
| warn | the same cell in HIT and ACCENT | "Shapes: B1 is in both HIT and ACCENT" |
| warn | two vibes with the same name | "Two vibes are called Groove" |
| info | fewer than 5 controls bound | "3 of 5 controls learned" |

### The audience rule

`audience-cell?` and `playable-cells` are defined in Phase 1 (`01-phase1-engine-core.md` §7), so a pick can never choose an audience-capable cell unless the vibe has `:allow-audience? true`. This phase only *shows* it: the lint row above names the zone group or projector that made a cell audience-capable, and the vibe editor has an **Allow audience cells** checkbox (`:vibe/set-option {:key :allow-audience?}`).

### Invariants

1. Every edit event is one undo step, and undoing it restores the library exactly. Clicking around the editor creates none.
2. Removing a vibe removes its id from `:order`, and if it was current the runtime falls back to the first id (Phase 1 invariant 9).
3. A vibe id is unique and comes from `[:config :vibes :next-id]`, which only grows (undo restores it with the rest of the library), so deleting the newest vibe and adding another never hands out the same id while the old one can still be referenced by the runtime.
4. `make-from-rows` never changes an existing vibe and is idempotent.
5. After a cell moves (`:grid/move-cell`), the grid dots, the picker and every pool all show the new place (Phase 1 `vibes.refs`).
6. A grid cell re-renders when its own memberships change and not when another cell's do (`cell-vibes` returns an identical vector).
7. Learn-all binds each role to a different key. A key learned for a second role is refused for the wizard's session ("already HIT") and the step waits for another key.
8. The wizard never leaves `:learning-target` set after it ends or is cancelled.
9. Lint never blocks saving, editing or performing.

## 8. Tests (named test vars + fixtures)

Unit:
- `laser-show.vibes.library-test` (existing): NEW `add-vibe-makes-a-unique-id-test`, `remove-vibe-drops-it-from-order-test`, `toggle-cell-test` (the table above), `set-single-replaces-test`, `reorder-test`, `make-from-rows-test` (the data example, and idempotence), `lint-test` (one assertion per row), `ids-come-from-next-id-test` (delete the newest vibe and add one: a new id).
- `laser-show.events.handlers.vibes-test` (existing): NEW `every-edit-event-is-one-undo-step-test`, `selection-events-are-not-undoable-test`, `edit-events-return-state-without-arguments-test`; the existing `history-labels-name-real-events-test` (`:252`) must pass with the new labels.
- `laser-show.events.handlers.input-test` (existing): NEW `learn-all-chains-the-five-roles-test`, `learn-all-refuses-a-key-twice-test`, `cancel-leaves-no-learning-target-test`.
- `laser-show.events.handlers.input-mapper-test` (NEW, named as keyboard plan Phase 5 names it): `mapper-ignores-discrete-events-while-learning-test` (skip if that plan landed it).
- `laser-show.subs-test` (existing): NEW `cell-vibes-ignores-other-cells-test`, `vibe-cell-index-follows-a-move-test`, `vibe-checklist-test`.

UI, `laser-show.ui-test.vibes-test` (NEW; fixtures as in Phase 2):
- `new-vibe-is-selected-test`
- `clicking-picker-cells-fills-the-hit-pool-test`
- `make-from-rows-button-test` (then again: no change)
- `grid-menu-adds-a-cell-to-a-vibe-test`, `grid-cell-shows-its-vibe-dots-test`
- `learn-all-binds-five-roles-in-order-test` (five `router/dispatch!` note-ons)
- `deleting-the-current-vibe-keeps-perform-working-test`
- `undo-restores-a-deleted-vibe-test`
- existing `laser-show.ui-test.grid-test` must pass unchanged (the grid cell gained dots)

Run: `clj -M:mac-arm:test vibes subs input`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.vibes-test`, then `-n laser-show.ui-test.grid-test`, then both full suites.

## 9. Out of scope / alternatives considered

- **Multi-select on the grid** ("new vibe from the selected cells"): the grid has single selection (`subs.clj:69`). The picker does the same job without changing the grid.
- **Drag a cell onto a vibe**: nice, not needed; the picker and the menu cover it. The grid already uses drag to move cells (`views/components/grid_cell.clj:152-168`), so a second meaning would clash.
- **Tag-driven vibes** (G4), **zone-scoped vibes** (G6): `00-overview.md` §4.
- **Copy a vibe to another project**: use Duplicate plus the project's cue clipboard; a real vibe clipboard is later.
- **Importing `beyond-vibe/vibes.toml`**: the cue addresses (page/row/col) have no meaning here.
- **A separate controller profile** (bindings that travel with the machine, not the project): bindings live in the project today (`[:config :input :trigger-map]`), the same as cell triggers.
