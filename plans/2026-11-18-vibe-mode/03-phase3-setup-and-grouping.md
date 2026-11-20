# Phase 3: setting up vibes (the Vibes tab, the grid, the wizard)

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** Done in `38f92ad` (2026-11-19); V8 B: the vibe stuff has its own tabs, so nothing is added to the Grid or Effects tab. As built: §10   **Last verified against:** `38f92ad` (§10)
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

## 10. As built (2026-11-19)

Committed as `38f92ad` on top of `90e35c7` (built as `3f85bc0` on branch `vibe-phase3` from `f7ec338`, Phase 2 done, then cherry-picked past UX Phase 2 with no conflicts; see correction 7). §1–9 cite `b1da0f2`; the lines below are at `3f85bc0`: the same in `38f92ad` except in the three files UX Phase 2 also changed (`state/domains.clj`, `subs.clj`, `views/root.clj`), where they are shifted by its lines.

**Where it is now** (paths under `src/laser_show/`)
- `vibes/library.clj`: NEW `roles` `:28`; private `scanners` `:71` (who scans the crowd, for `audience-groups` `:81` and the lint); NEW `audience-cue?` `:106` and `unplayable` `:118` (`:empty`, `:timecode` or `:crowd`; `playable-cells` `:129` uses it). The slots: `cue-slots` `:144`, `fx-slots` `:149`, `slots` `:153`, `slot-cells` `:163`, `in-slot?` `:172`, `edited-id` `:177` (the vibe the tab edits). The edits, each taking and returning `[:config :vibes]`: `palette` `:188`, `new-vibe` `:192`, private `take-id` `:202`, `add-vibe` `:220`, `duplicate-vibe` `:228`, `remove-vibe` `:240`, `move-vibe` `:250`, `rename-vibe` `:266`, `set-color` `:275`, `set-energy` `:282`, `set-single` `:296`, `toggle-cell` `:304`, `toggle-fx` `:315`, `set-role-option` `:330`, `make-from-rows` `:341`. Then `cell-index` `:363`, `lint` `:444` (private `audience-text` `:399`, `cell-problems` `:412`, `vibe-problems` `:422`) and `checklist` `:470`.
- `vibes/engine.clj`: NEW public `reconcile` `:465` (private `refresh-fx` `:443`, `refresh-bed` `:453`). `leave-look` `:130` and `enter-look` `:156` now share NEW private `release-fx` `:106` and `take-fx` `:117` with it; the steps are the ones they took before (the engine tests and the fuzz pin them).
- `events/handlers/vibes.clj`: private `edit` `:81` (the edit, then `engine/reconcile`), `handle-add` `:91` (the first new vibe is selected), `handle-remove` `:99` (the neighbour is selected), `handle-toggle` `:115` (an empty cell isn't put in), `handle-edit` `:126`, `handle-select-for-edit` `:132`, `handle-set-slot` `:135`, `handle-learn-all` `:138`, `handle-wizard-cancel` `:145`; routed in `handle` `:152`, the Vibes tab's cases together at `:166-182`.
- `events/handlers/input.clj`: NEW private `wizard-target` `:232`, `end-other-wizard` `:239`. `handle-input-start-learn` `:247` ends Learn all when the learn is for anything else; `handle-input-learn-captured` `:295` starts the next role's learn, or refuses a key an earlier role of the session has (`:refused`, `:318`) and starts the same learn again; `handle-input-cancel-learn` `:336` ends Learn all.
- `events/handlers/input_mapper.clj`: NEW private `learning?` `:34`; no discrete input is dispatched while it is true (`:82`).
- `state/persistent.clj`: NEW private `vibe-switches-off` `:94`, used by `project-files` `:108`. `state/domains.clj:201` `[:ui :vibes]`. `events/handlers/history.clj:149-159`: eleven labels, after `:vibe/clear-control`.
- `subs.clj`: the section ";; The Vibes tab" `:1021-1181`, after Phase 2's: `vibe-edited-id` `:1029`, `vibe-edit-target` `:1035`, `vibe-editor` `:1042`, `vibe-list` `:1050`, `vibe-cell-index` `:1057`, `cell-vibes` `:1066`, `vibe-audience-groups` `:1073`, `vibe-cell-blocked` `:1088`, `vibe-pick-cell` `:1107`, `vibe-pick-fx` `:1116`, `vibe-pick-label` `:1126`, `vibe-pick-fx-label` `:1138`, `vibe-lint` `:1154`, `vibe-checklist` `:1159`, `vibe-control` `:1164`, `vibe-wizard` `:1173`. `cell-vibes`, `vibe-cell-blocked` and the two labels each sit over a private level of their own, so their value stays identical while the level under it is equal.
- `views/tabs/vibes.clj` (NEW): `vibes-tab` `:323`; private `checklist` `:31`, `vibe-list` `:60`, `name-field` `:118` (applies the name on Enter or when it loses the focus; its focus listener calls the latest render's fn from the field's user data, as `views/tabs/projectors.clj` `commit-field` does), `editor-header` `:172`, `slot-tabs` `:205`, `pool-options` `:234`, `slot-options` `:255`, `lint` `:276`, `rules-of-thumb` `:303`.
- `views/components/vibe_picker.clj` (NEW): `vibe-pick-cell` `:90`, `fx-pick-cell` `:108`, `picker` `:141`; private `pick-cell` `:72` (a stack pane: the grid cell, and the `marks` `:32` over it), `undraggable` `:65`. `views/components/vibe_controls.clj` (NEW): `controls-panel` `:89`, private `control-row` `:14`, `learn-all` `:56`.
- `css/vibes.clj:125-268`: the Vibes tab's rules. `views/root.clj`: the tab `:81`, `tab-content` `:65`, the preview's exclusion `:106`.

**The brief's corrections (1–8)**
1. V8 B, nothing on the Grid tab or the Effects tab. Dropped: §3's dots on the Grid tab and its "Add to vibe ▸" / "Remove from vibe ▸" menus, §4's `grid-cell` change, and §8's `grid-menu-adds-a-cell-to-a-vibe-test` and `grid-cell-shows-its-vibe-dots-test`; `views/components/grid_cell.clj` and `grid_tab.clj` are unchanged. Membership shows in the picker only: blue for the slot edited, a cell's place in a pool, its other roles in the vibe, a dot in each other vibe's colour, and "audience" or "timecode" when the vibe never plays it. `vibe-cell-index` and `cell-vibes` serve the picker, with a grid argument (`:cue` or `:fx`): §4's `cell-vibes [col row]` named only the cue grid. NEW UI test `grid-and-effects-tabs-show-no-vibe-marks-test`: with cells in a pool, a FLASH cue, a bed and an FX set, no `vibe-` style class anywhere in the window and no vibe word in the cells' labels or menus, on either tab. `grid-test` passes unchanged.
2. K-15 is fixed in the mapper (`input_mapper.clj:34, 82`): while `[:backend :input :learning-target]` is set, no discrete input becomes `:trigger/input`; continuous ones still reach the input store. Learn catches the press with its own router handler (`input.clj:247`), so capture is as it was: `learn-binds-a-role-to-a-note-test` and the trigger editor's learn pass. The test is `laser-show.events.handlers.input-mapper-test/mapper-ignores-discrete-events-while-learning-test`: the keyboard plan's Phase 5 names its namespace `laser-show.events.input-mapper-test`, with the same var, and that phase can skip its mapper change.
3. `project-files` writes the effect cells in `[:vibe-runtime :fx-on]` and the held flash's `:fx-on` as `:active? false` (`persistent.clj:94-117`); the live state keeps them on. `:active?` is live (`state/history.clj:20`), so neither the saved baseline nor the unsaved flag sees the difference. Test `vibe-switched-fx-cells-are-saved-off-test`: a cell switched on by hand is saved on, and a load of the file has the other two off.
4. An edit of the vibe that plays takes effect at once: `engine/reconcile [state was t]` (`engine.clj:465`), called after every edit (`handlers/vibes.clj:81`). Deleting it leaves its look and enters the first of `:order` (nothing, when it was the last); during blackout that one is current and its look waits for blackout to end. A cell its FX set gains switches on, and one it loses switches off if the engine switched it on; a cell the held flash switched on becomes the set's, and one the flash asked for stays on as the flash's. When the cue the bed should play changes (another cell, or **Allow audience cells** making the same one playable or not), the old bed stops and the new one starts, or waits for a solo flash to let go. Pools, the flash and the options take effect at the next press. Nothing happens before the first press or select (no look was entered). Undo runs no handler: an undone FX cell stays on, and an undone vibe that played keeps its look, until the next switch, or the next edit, which finds the vibe that plays gone (or never in the old library) and leaves its look; so an id an undo handed back isn't taken for the vibe that played. Tests: `removing-the-current-vibe-leaves-its-look-test`, `fx-set-edits-switch-the-playing-vibe-test`, `bed-edits-restart-the-bed-test`, `pool-edits-wait-for-the-next-press-test`, `edits-after-an-undo-converge-test`, and the handlers' `editing-the-playing-vibe-plays-the-edit-test` (with the undo).
5. Tabs: Grid, Vibes, Perform, then the rest. No preview beside the Vibes tab (`root.clj:106`): the list and the Controls panel take 300 px and the picker (8 cells of 80 px with the row letters and padding) about 730, about 1,080 px with the padding, and the preview's split leaves the tab about 830 at 1280×800. Without the preview the tab fits at 1280×800 and at the default 1200×800 without scrolling (headless screenshots).
6. Phase 2's pieces: `trigger-bank/binding-label` (the Controls panel), `library/binds-role?`, learn aimed at a role and its move. The picker cells are grid cells with a plain click handler, which takes the robot's clicks; the Perform pads use `ui/mouse-click!`. The tab has no tooltips, so `ui/query` never meets one.
7. UX Phase 2: `grid_tab.clj` and `grid_cell.clj` are used as they are; the `root.clj` edit is the tab list, `tab-content`, and `:vibes` in `main-content`'s set of tabs without the preview (with its docstring); the new subs are at the end of `subs.clj`. `git merge-tree --write-tree master 3f85bc0` (master `90e35c7`, UX Phase 2 verified there) is clean, and that tree, exported, passes the full unit suite and the vibes, perform, grid, trigger-editor and main-window UI tests (table below). UX Phase 2 makes grid cells fluid: `grid-tab/grid-area` measures the pane and passes NEW `:size` and `:font-size` to `grid-header`, `grid-with-labels` and the cells. The picker gives them a fixed `[80 60]` and 12 px (`vibe_picker.clj:134-139`), which this branch's components ignore (they lay out 80×60 anyway), so after the merge the picker's cells stay 80×60 while the Grid tab's grow: the picker shares the width with the editor.
8. Every edit handler returns at least `{:state state}`, with no arguments too (`edit-events-return-state-without-arguments-test`), and `history-labels-name-real-events-test` passes with the eleven labels. For Phase 4a, the Vibes tab's cases in `handle` and its labels in `history.clj` are each in one block.

**Other differences from the plan**

| Plan | As built | Why |
|---|---|---|
| §4: the library's edits "return the new library" | each takes and returns `[:config :vibes]`; `make-from-rows [vibes cue-chains size]` | ids come from `:next-id`, and `:order` changes with the library |
| §4: `set-role-option` | also the vibe's own `:allow-audience?` (role nil); a value it can't take changes nothing (`:mode`, `:select`, `:beats` 0.25 to 64, booleans) | one event for every option in the editor |
| §7: the toggle table | as the table, and an empty cue or effect cell isn't put in (it comes out) | it has nothing to play |
| §7 invariant 3 | `take-id` also skips an id a hand-edited file already uses | ids stay unique |
| §7: the lint table | every row, plus the audience row for the FLASH cue and the BED, and an error for a BED that can't be played; the pool-of-one row is HIT's only (an ACCENT of one is the rule of thumb); "3 vibes are called …" past two; rows come error, warn, info, each with its `:vibe`. Cells are named "A3"; the audience row names the zone group and the projector that scans it ("A2 reaches the audience through All (P3)") | the starter vibes have ACCENT pools of one |
| §3: lint on the tab label | on the Vibes tab only | the tab labels are static data (`root.clj` `main-tabs`), and the brief keeps the `root.clj` edit small |
| §4 subs `vibe-editor` (selection, slot, pick state), `vibe-controls` | `vibe-edit-target` (`{:vibe :slot}`, what a picker cell reads), `vibe-editor` (the vibe with `:slot` and `:current?`), `vibe-list`, a pick state per cell (`vibe-pick-cell`, `vibe-pick-fx`), `vibe-control [role]` and `vibe-wizard` | narrow subs: a click re-renders its own cell, a learn its own row (invariant 6) |
| §4: picker cells drawn "in" or "out", `.vibe-pick-in`/`-out` | the wrapper has the two classes; "in" is the grid cell's `:active?` look (blue); the marks are a layer over it that takes neither the mouse nor any room | `generic-grid-cell` takes no extra classes, and `grid_cell.clj` stays as it is |
| `generic-grid-cell` reused as it is | without `:drag-config` its three drag props are nil, which cljfx can't coerce ("Don't know how to coerce … EventHandler, :x nil"), so `undraggable` leaves them out: a picker cell is no drag source or target | the grid's cells always have one |
| §7: Learn all | as planned, plus `:refused` (whose the key is) until the next key; any other learn, or a pad's **Cancel learning**, ends it; the panel shows it only while its role is being learned | a stale wizard could otherwise resume when that role is learned again |
| §4: `inline_edit.clj` for the rename | a text field applied on Enter or focus loss | `inline-edit-text` sets its handlers in `:on-created`, which would keep the first vibe's id (`docs/STALE_CLOSURES.md`); the checkboxes are keyed by the vibe for the same reason |
| §3: the rules of thumb | the first six, collapsed | the seventh is Phase 4's tap reactions |
| §8: UI tests | the six left after correction 1, and `vibes-tab-shows-the-vibes-and-edits-the-first-test`, `fx-picker-switches-the-playing-vibes-effects-test`, `grid-and-effects-tabs-show-no-vibe-marks-test` | the effects picker and V8 B |

**Found, not done**
- Unticking **Allow audience cells** stops an audience bed at once (a bed change), but a HIT, ACCENT or FLASH cue already playing an audience cell plays on until its decay, its release or the next press; a `:sustain` one until it is replaced.
- Fixed in `f5a4f84` (Phase 4b: a learn holds back presses only): the release of a key held when a learn starts is dropped with the rest (correction 2): a vibe role it held waits for that role's next press, or BLACKOUT.
- `[:ui :vibes]` survives New and Open (`reset-session` resets the dialogs only); a selection naming a vibe that went falls back to the first.
- A double click on a picker cell is two clicks (in, then out), and a right-click is one: `generic-grid-cell` sends both to `:on-right-click`, which the picker sets to the same toggle (it has no menu).

**Tests**: 33 new unit tests (951 → 984) and 9 UI tests
- `laser-show.vibes.library-test` (+10): `add-vibe-makes-a-unique-id-test`, `ids-come-from-next-id-test`, `remove-vibe-drops-it-from-order-test`, `duplicate-vibe-test`, `reorder-test`, `toggle-cell-test` (§7's table, both grids), `set-single-replaces-test`, `editing-a-vibe-takes-only-good-values-test`, `make-from-rows-test` (§7's example, idempotence, invariant 4), `lint-test` (one assertion or more per row).
- `laser-show.vibes.engine-test` (+5): the five of correction 4. `vibes.engine-fuzz-test` (extended): 5 % of its steps are now the tab's edits through their handlers (FX sets and FLASH effects, beds, pools, the audience box, deletes), checked for invariants 1 and 4, that HIT and ACCENT play on, and that the cells the FX set gains or loses switch and no other does.
- `laser-show.events.handlers.vibes-test` (+6): `every-edit-event-is-one-undo-step-test` (the eleven, each with its label, undone exactly: invariant 1), `selection-events-are-not-undoable-test`, `edit-events-return-state-without-arguments-test`, `picker-clicks-leave-empty-cells-out-test`, `new-vibes-are-selected-test`, `editing-the-playing-vibe-plays-the-edit-test`.
- `laser-show.events.handlers.input-test` (+3): `learn-all-chains-the-five-roles-test` (invariant 8), `learn-all-refuses-a-key-twice-test` (invariant 7), `cancel-leaves-no-learning-target-test` (also a pad's Cancel, another learn, a late capture). NEW `laser-show.events.handlers.input-mapper-test` (1).
- `laser-show.state.persistent-test` (+1). `laser-show.subs-test` (+7): `cell-vibes-ignores-other-cells-test` (invariant 6: identical values after another cell's click, a press, the clock's status, the grid's selection), `vibe-cell-index-follows-a-move-test` (invariant 5, both grids), `vibe-pick-cell-sub-test`, `vibe-editor-sub-test`, `vibe-checklist-test`, `vibe-lint-sub-test`, `vibe-controls-sub-test`.
- NEW `laser-show.ui-test.vibes-test` (9): `vibes-tab-shows-the-vibes-and-edits-the-first-test` (the tab order, no preview, the checklist, the lint), `new-vibe-is-selected-test` (then renamed, coloured, rated), `clicking-picker-cells-fills-the-hit-pool-test` (the pool's order, an empty cell, the BED), `fx-picker-switches-the-playing-vibes-effects-test`, `make-from-rows-button-test` (again: no change, no undo step), `learn-all-binds-five-roles-in-order-test` (five note-ons through the mapper; then again with the same keys, and nothing plays: K-15), `deleting-the-current-vibe-keeps-perform-working-test`, `undo-restores-a-deleted-vibe-test`, `grid-and-effects-tabs-show-no-vibe-marks-test`.

**Red first**
- `library-test` against `f7ec338`'s library (stashed): doesn't compile, `No such var: library/add-vibe`. The engine tests: `No such var: engine/reconcile`. `subs-test`: `No such var: subs/cell-vibes`.
- `events.handlers.vibes-test`: 14 tests, 56 failures (the events unrouted). `input-test` with `input-mapper-test`: 12 tests, 15 failures (no chaining, no refusal, cancel left the wizard, the mapper sent the four discrete inputs while learning). `persistent-test`: 2 failures (saved `[true true true]`).
- `ui-test.vibes-test` before the views: 8 errors (timed out waiting for `#vibes-tab`) and 1 failure (the tab order); the V8 B guard passed, as it must before and after. The first run with the views: 8 errors, the drag props above.
- Mutant: `reconcile` returning state as it is: 27 failures in the engine and handler tests, and the fuzz on 1 of its 3 seeds.

**Verification, 2026-11-19, macOS arm64, JDK 25**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test vibes subs input persistent history` | 256 tests, 1,539 assertions, 0 failures |
| `VIBE_FUZZ_SEEDS=200 clj -M:mac-arm:test -n laser-show.vibes.engine-fuzz-test` | seeds 1–200, 2,000 steps each: 0 failures |
| `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.vibes-test` | 9 tests, 79 assertions, 0 failures |
| the same with `-n` perform-test, grid-test, trigger-editor-test, main-window-test | 36 tests, 227 assertions, 0 failures |
| `clj -M:mac-arm:test` (full unit suite) | 984 tests, 33,403 assertions, 0 failures (baseline at `f7ec338`: 951 tests, 33,040) |
| `clj -M:kondo --lint` on every touched file | 0 errors; 1 warning, there before (the redundant `let` in `input.clj` `handle-input-update-cell-trigger-action`, now `:369`) |
| `3f85bc0` merged with `master` `90e35c7` (`git merge-tree --write-tree`, tree exported, no branch touched) | no conflict; full unit suite 990 tests, 33,444 assertions; the five UI namespaces above 41 tests, 271 assertions; 0 failures |
| after the merge, on `master` at `2a7f3f2`: `clj -M:mac-arm:test`, then `clj -M:test-ui:mac-arm:test-ui-headless` | 990 tests, 33,443 assertions, 0 failures; UI 106 tests, 510 assertions, 0 failures and 1 error: `deleting-the-current-vibe-keeps-perform-working-test` timed out with two agents' test runs on the machine. Its HIT click had become a hold, which cuts the cue on release, so no voice was left to see. `b01cee9` raises the quick-tap threshold before the click, as `perform_test.clj:146` does; `vibes-test` then 9 tests, 79 assertions, 0 failures, and with `perform-test` 19 tests, 0 failures |
| full UI suite | not run here: it runs after the merge onto `master` |
| manual (unverified) | a visible (non-headless) run; the layout was checked on headless screenshots at 1280×800 and 1200×800 |
