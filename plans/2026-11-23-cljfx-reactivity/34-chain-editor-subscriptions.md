# 34 · Chain editor subscriptions

**Status:** Implemented in `e5e0096d` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24).

No plan page was written before implementation; the design brief the implementer worked from and its hand-off report are recorded here. Paths are relative to `src/laser_show/` unless they say otherwise, and citations refer to the code at the time of writing.

## Design brief

Audit: "An unrelated cell edit invalidates open chain editors" (section "Subscription hot spots that were measured"). Subscription auditor (cache finding 1) and editors auditor (finding 4), at a7e414e:
- The cue and effect chain editors subscribe to ALL of `:chains` (views/dialogs/cue_chain_editor.clj ~248–249, views/dialogs/effect_chain_editor.clj ~88–90) although per-cell selectors exist (`subs/cue-cell-chain`, `subs/effect-cell-chain`).
- The cue editor allocates four fresh `partial`s for label/menu functions on every render (cue_chain_editor.clj ~315–318) → row descriptions are unequal on every render, so every row re-runs.
- `subs/list-ordered-ids` (subs.clj ~862–875) subscribes to all of `:chains` and then walks the target path: an unrelated effect-cell toggle reran a cue list's ID traversal.
- list.clj `list-sidebar` gives every row the whole `:items` vector and the whole props bag, and keys rows by `[(:id item) idx]`, so an insert or reorder recreates every row below it.

Required:
- Both editors read their cell through the cell-addressed selectors; nothing in them depends on other cells' chains.
- Stable functions: replace per-render partials/closures passed as row props with top-level functions plus plain data arguments (or data specs resolved inside the row), so equal inputs produce equal descriptions.
- `list-ordered-ids` subscribes to just the resolved items path.
- Rows receive only row-relevant props; key rows by the stable `(:id item)` now that callbacks read current node data (04 landed earlier in this worktree) — verify DnD, rename and selection still work after reorders.
- 33 (removing explicit `:fx/context` props and context-carrying bags) landed earlier in this worktree; keep its invariants.

Tests (NEW, names adaptable): selector/view-count tests on a real context showing that, with an editor open on [0 0], editing cell [1 0] doesn't recompute the [0 0] editor's list rows or its ordered ids (count body calls by instrumenting like plans/probes/cljfx_reactivity_2026_11_23.clj `starter-grid`), while editing [0 0] does; a UI regression that reorders/inserts items and checks rows, selection and Delete still target the right items. Keep cue-chain-list, clipboard, dialogs and zone pattern UI tests passing.

Boundaries: 35 (grid fan-out) and 37 (derived costs) are other lanes; 36 (cache capacity) is measured after everything merges — don't change the LRU size.

## As implemented

Body calls per render, before → after (LRU 512; list components in brackets):
| Scenario | Effects editor, 6 rows | Cue Chain Editor, 5 rows |
|---|---|---|
| Rename `[1 0]` / add an item to it | 1 → 0 | 59 (13) → 0 |
| Turn one `[0 0]` item off | 17 (15) → 5 (4) | 60 (13) → 5 (4) |
| Change the preset tab | – | 64 (13) → 6 (0) |
| Select an item | 21 (9) → 16 (4) | 113 (16) → 58 (6) |
| Insert an item | 27 (17) → 14 (4) | 120 (18) → 114 (6) |
| Reorder | 27 (17) → 12 (2) | 120 (18) → 111 (3) |
(Cue insert/reorder totals barely drop: the selected item's editors and moved items' Target menus address their item by path.)

**Changes**: editors read their own cell via `subs/effect-cell-chain` / `subs/cue-cell-chain`; Effects param wrapper gets only the selected effect; cue middle section only the selected item. List label/menu props take a fn or `[f & args]` spec; cue editor's four partials → specs; each cue row's Target menu is its own component `item-target-menu`; projector sidebar label fn top-level; unused `:selected-ids` gone. Rows get only what they show and are keyed by `(:id item)`; row buttons, On box, rename field find their list by walking up at event time; On box uses `:on-action`; `make-registry-label` removed. Disabled-group children now look disabled (`:effectively-disabled?` vs `:parent-disabled?` fixed).

**Tests**: NEW `chain-editor-subscriptions-test` (4), NEW shared `test/laser_show/views/harness.clj` (33 helpers moved + per-render body counting), NEW UI `cue-chain-list-test/rows-keep-their-items-through-a-reorder-and-an-insert-test`, `a-reopened-list-keeps-its-rows-and-deletes-in-its-cell-test` (04/33/34→36 row), `zones-test/a-rows-own-target-menu-sets-its-item-test`.

**Left**: cue item Target menu event still sends `:item-path` (accepting item id would stop menu re-renders on reorder; event contract change). Target chips/menus depend on `zones-in-use` by design.

**Notes**: 36 — each cue row adds two cache entries; row descriptions smaller; reuse harness; measure without `:dev`. Integration — `list-editor` accepts `[f & args]` for label/menu props; `:get-context-menu` may return a fn-component description; 33's test uses the shared harness.
