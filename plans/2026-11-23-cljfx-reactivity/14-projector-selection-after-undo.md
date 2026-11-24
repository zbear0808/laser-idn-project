# 14 · Projector selection after Undo

**Status:** Implemented in `a15687b` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24).

No plan page was written before implementation; the design brief the implementer worked from and its hand-off report are recorded here. Paths are relative to `src/laser_show/` unless they say otherwise, and citations refer to the code at the time of writing.

## Design brief

Audit row "Undo Add Projector can leave an editable phantom". Shell/settings auditor finding 3 (reproduced at a7e414e): "Add selects the new ID (events/handlers/projector.clj `added`); Undo restores :projectors but preserves UI selection (state/history.clj `restore-document`). `subs/active-projector` then does `(assoc nil :id active-id)`, so the inspector takes its configured branch (views/tabs/projectors.clj). The probe got zero projectors, selected `{:id …}`; editing its name recreated a projector containing only `:name` (events/handlers/projector.clj `handle-projectors-update-settings` merges into a missing record)."

Required:
- `subs/active-projector` (and the virtual-projector equivalent if it has the same shape) returns nil when the selected id has no record; the inspector shows its no-selection state.
- Writers addressed by projector id never create a record for a missing id: `update-settings`, `update-driver-config`, tag/exclude/enabled setters, calibration/safety writers — check each; most already guard with `contains?`, make the rest consistent (return `{:state state}` / `{}` per local convention).
- Reconcile UI selection after any document restore (Undo/Redo, and anywhere else restore-document is used): clear `[:projector-ui :active-projector]` / `:active-virtual-projector` / calibrating / identify ids that no longer exist. This is session UI state: not saved, not an undo step, not dirty. Put it where restores happen (events/handlers/history.clj undo/redo or a small helper applied after `restore-document`) — not inside the document snapshot.
- Redo of the add: either re-select the restored projector or leave no selection; choose and document.

Tests (NEW, names adaptable): history/projector handler tests — Add → Undo → selection nil, stale `:projectors/update-settings` for the removed id leaves `:projectors` empty and no dirty change; Redo; subs test for active-projector with a dangling id; a UI test (projectors tab UI namespace, e.g. laser-show.ui-test.projectors-layout-test or setup_dialogs_test) Add → Undo → inspector shows no projector. Keep existing projector/history tests passing.

Boundaries: 22 (vibe ownership on undo) and 23 (rig group definitions on undo) landed earlier in this worktree and also touch restore paths — integrate with them, don't undo their changes. 08/09/13 (other lane) edit other projector handlers/views.

## As implemented

**Changes**
- `subs/active-projector` → nil when the selected id has no projector (inspector shows "Click a card to set it up"). No sub/view reads `:active-virtual-projector`.
- NEW `events.helpers/prune-projector-ui`: clears selected projector, selected virtual projector, test-pattern projector and Identify flashes whose projector no longer exists; never selects; session UI only.
- `events/handlers/history.clj`: `(h/prune-projector-ui)` is the third step of `restore` (after `restore-snapshot` and `refs/restore-fx`) → Undo, Redo, abort. Redo of an Add restores the projector with nothing selected (docstring + test).
- `events/handlers/projector.clj` hunks (each + a docstring sentence): (1) `handle-projectors-update-settings` final write only when the projector exists; (2) `handle-projectors-toggle-enabled` `cond->` guard; (3) `handle-projectors-toggle-zone-group` refuses unknown projector; (4) `handle-vp-toggle-zone-group` refuses unknown VP; (5) `handle-projectors-add-effect` no calibration chain for a missing projector; (6) `handle-projectors-remove`: `clear-if` binding + three clears → `(h/prune-projector-ui)` (also lets go of a selected VP of the removed projector); (7) `handle-projectors-toggle-calibration`: a missing projector's test pattern can only be turned off. No `views/tabs/projectors.clj` changes.

**Remaining risk**: generic `:chain/*` writers for calibration chains (`:projector-effects`) unguarded — a late write for a missing projector could create an orphan calibration chain (fix belongs in chain handlers). `[:ui :projector-effect-ui-state id]` / `[:list-ui [:projector-effects id]]` not cleared on Undo (harmless). `select-projector` still accepts a missing id (sub returns nil; next restore clears it).
