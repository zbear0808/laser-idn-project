# Detail panel height kept for the session (L3-A) + cell-switch viewport fix

**Written:** 2026-11-11   **Written for commit:** `716688d` (branch `timeline/effects-panel`)
**Status:** Proposed   **Related:** `07-effects-panel-followups.md` L3 (option A) and "Related bug"; corrects that section's diagnosis (see §2)

The bottom detail panel's height should survive closing/reopening the editor and switching cell. Zoom, scroll and "follow" should still reset per cell. Total ~2 h.

## 1. Current behaviour (file:line)

- **Height is local.** `detail-panel` (`timeline_editor.clj:697-722`): the 5 px strip `#timeline-detail-resize` (`:708-717`) writes `:detail-h` into the editor's `fx/ext-state` on every mouse move (`:716`), clamped to `detail-height-bounds [160 600]` (`:691`). The drag anchor is the `defonce` atom `detail-drag` (`:693-695`). It's read as `(:detail-h state 300.0)` (`:889`).
- **Local state dies on close.** The dialog is only in the scene graph while it's open (`root.clj:182-184`), so the `ext-state` (`timeline_editor.clj:910-912`) is rebuilt on reopen: height, zoom, scroll and follow all reset.
- **Prefs.** `[:ui :timeline-prefs]` defaults live in `domains.clj:169-173` and `default-prefs` (`handlers/timeline.clj:40-45`), with helpers `prefs-get`/`prefs-assoc` (`:61-65`) and the sub `subs/timeline-prefs` (`subs.clj:185-188`). The existing pref events are `:timeline/toggle-inspector` (`:101-104`), `:timeline/set-ruler-mode` (`:405-408`) and `:timeline/toggle-add-panel` (`:282-285`). They last for the session only; only project files are saved (`persistent.clj:33-61`).
- **Cell switch (checked by a scratch TestFX run, 2026-11-11).** Re-dispatching `:timeline-cue-editor/open` for another cell while the editor is open:
  - zoom **does** reset (slider 6.29 → 5.64);
  - the panel height also resets to 300;
  - **the lanes go blank** (`#timeline-canvas` width 0.0) unless the panel height changed during the switch.

## 2. Root cause

- **07's "Related bug" is wrong.** cljfx 1.10.6 `ext-state` throws its local state away whenever `:initial-state` changes (`cljfx/lifecycle.clj:828-831`; the `ext-state` docstring in `cljfx/api.clj:282-287` says the same). `:initial-state` contains `:cell [col row]` (`timeline_editor.clj:911`), so the viewport already resets per cell. Also, `:fx/key` on the `ext-state` desc would do nothing: keys are only read by `wrap-many` (children lists, `lifecycle.clj:325-376`), and the single-child `dynamic` lifecycle ignores them (`:41-55`).
- **The real bug is a stale size.** The reset puts back `initial-view`, whose `:width 0.0 :height 0.0` (`timeline_editor.clj:41-42`). The JavaFX nodes are kept, not recreated, and the size listeners (`:802-810`) only fire when the pane's size *changes*. So `:view` keeps 0×0 until something resizes the pane. Today, a dragged panel snapping back to 300 px happens to trigger that resize. **Once L3-A keeps the height, every cell switch will blank the lanes**, so this fix has to come first.
- **The height resets** because it lives in component-local state.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `ext-state` `:reset` option (`cljfx/api.clj:297-299`) | Keep the measured `:width`/`:height` when the state resets for a new cell |
| `prefs-get` / `prefs-assoc` (`handlers/timeline.clj:61-65`) | Read/write `:detail-height` |
| `handle-set-ruler-mode` (`:405-408`) | Shape of the new handler |
| `wrap-history`'s `document-roots-identical?` fast path (`handlers/history.clj:212-215`) and `next-dirty` (`:262-266`) | `[:ui …]` isn't a document root (`state/history.clj:48-50`), so there's no undo entry and the dirty flag stays as it is. No exclusion list entry needed. |
| `transport-events-create-no-undo-test` (`history_handlers_test.clj:439-447`) | Test to mirror |
| `ui/drag-by!` (`testfx.clj:310-327`), `open-timeline!`, `slider-value` (`timeline_editor_test.clj:61-69`), `:ui/close-dialog` as used at `:99-100` | UI tests |

## 4. Changes per namespace

### 4.1 `laser-show.views.components.visual-editors.timeline-editor` — step 1, cell-switch fix (~30 min)
- `timeline-editor` (`:910-912`): add `:reset (fn [old new] (update new :view merge (select-keys (:view old) [:width :height])))`.
- Update the docstring (`:907-908`): "Local state resets when `:cell` changes (it's part of `:initial-state`); the measured lane size carries over." Add a short comment on `:cell` so nobody removes it as unused.

### 4.2 `laser-show.timeline.layout` — step 2 (~15 min)
- Move `detail-height-bounds` here (public), add `default-detail-height 300.0` and `clamp-detail-height [h] → double` (non-numbers fall back to the default). It's pure and shared by the view and the handler. The handler ns gains a require of `layout`; there's no cycle (`layout` requires only `model`, `timecode`).

### 4.3 `laser-show.events.handlers.timeline` + `laser-show.state.domains` — step 2 (~30 min)
- `default-prefs` (`:40-45`) and `domains.clj:169-173`: add `:detail-height 300.0` (`layout/default-detail-height`). This matches the existing names `:inspector-open?` and `:ruler-mode`.
- NEW `handle-set-detail-height [{:keys [height state]}]` → `{:state (prefs-assoc state [:detail-height] (layout/clamp-detail-height height))}`. Route it as `:timeline/set-detail-height` under ";; View controls" in `handle` (`:486-488`).
- Update the `subs/timeline-prefs` docstring (`subs.clj:186`) to list the new key.

### 4.4 `timeline-editor` view — step 3, preview locally, commit on release (~45 min)
- `editor-body` (`:889`): `:height (or (:detail-h state) (:detail-height timeline-prefs) layout/default-detail-height)`. Local `:detail-h` is now only a drag preview.
- `detail-panel` (`:712-717`), with a pure helper `drag-height [[y0 h0] screen-y]` (clamped):
  - press: unchanged.
  - dragged: `(swap-state assoc :detail-h h)`; this stays local, no event.
  - released: compute `h` from the release event (not from the render prop, which can be stale), `swap-state assoc :detail-h h`, and, if `h ≠ h0`, `(events/dispatch! {:event/type :timeline/set-detail-height :height h})`. Then `reset! drag nil`.
  - The preview is **not** cleared on release. Clearing it would briefly show the old pref until the async event lands (`events/core.clj:468-473`). The value equals the committed pref, and it's dropped at the next cell switch or reopen.

### 4.5 Small windows — step 4, optional (~20 min)
The dialog's min height is 560 (`timeline_cue_editor.clj:115`), so a 600 px panel can squeeze the lanes to nothing. **Decision:** cap only during a drag: `hi = min(600, height + (:height view) − 120)`, which keeps ≥ 120 px of lanes (ruler + about 2 tracks). The stored pref is never shrunk by window resizes. Skip this step if it's not wanted.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:ui :timeline-prefs :detail-height]` NEW | app state (UI, session) | `:timeline/set-detail-height` on release | No / No |
| `:detail-h` in editor `ext-state` | view-local (drag preview) | strip `on-mouse-dragged`/`-released` | No |
| `:view :width/:height` | view-local | size listeners; now also carried over by `:reset` | No |
| `:view` zoom/scroll, `:follow?`, `:renaming-id` | view-local, reset per cell (unchanged) | — | No |

## 6. Events and effects

| Event | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:timeline/set-detail-height {:height h}` | NEW | `#timeline-detail-resize` mouse release, once per drag | yes |

No new effects. Not in `history-excluded-events` (`handlers/history.clj:119-133`); like the other prefs, the document-roots fast path covers it.

## 7. Data examples and invariants

- `{:event/type :timeline/set-detail-height :height 420.5}` → `[:ui :timeline-prefs :detail-height] = 420.5`. `:height 50` → 160.0, `9999` → 600.0, `nil` → 300.0.
- **Invariants:**
  1. One event per completed drag, none per mouse move and none for a click without movement.
  2. Prefs events never add undo snapshots or change `[:project :dirty?]`.
  3. After a cell switch, `#timeline-canvas` width equals the lanes pane width (> 0).
  4. The panel height is the same before and after close/reopen and cell switch. Zoom resets per cell.

## 8. Tests

- `laser-show.timeline.layout-test` (extend): `clamp-detail-height-test` (bounds, nil → default).
- `laser-show.events.timeline-handlers-test` (extend, `base-state` `:9-10`): `set-detail-height-clamps-test` (the §7 values; other prefs keys untouched).
- `laser-show.events.history-handlers-test` (extend, fixtures `timeline-state` `:394-402`, `run`, `undo-count`): `timeline-pref-events-create-no-undo-test`. Run `:timeline/set-detail-height`, `:timeline/toggle-inspector`, `:timeline/toggle-add-panel` on a clean state; the undo count is unchanged and `dirty?` stays false. A second case starts dirty and stays dirty.
- `laser-show.ui-test.timeline-editor-test` (extend). Helper `detail-height []` reads `.getHeight` of `#timeline-detail`.
  - `timeline-cell-switch-resets-zoom-keeps-lanes` (step 1, written first; it fails today):
    1. Open [0 0] and zoom in.
    2. Dispatch `:timeline-cue-editor/open :col 1 :row 0`, wait for the dialog's `:col` to be 1.
    3. Check that `slider-value` is back to the initial value and that `#timeline-canvas` width is > 0.
  - `timeline-detail-height-survives-reopen`:
    1. `drag-by! "#timeline-detail-resize" 300 2 0 -80 {:steps 80}` → height ≈ 380, and the pref is 380.
    2. Close with `:ui/close-dialog` (wait for `:open?` false like `:99-100`), then reopen → height ≈ 380.
  - `timeline-cell-switch-keeps-detail-height`: drag, switch cell → height kept, zoom reset.
  - **Headless note:** use 1 px steps (`:steps` = |dy|). In the scratch run, a 4-step drag of 80 px moved the panel by only 1–3 px: the headless robot stops delivering drag events once the pointer jumps off the 5 px strip.
- Run: `clj -M:test-ui:mac-arm:test-ui-headless timeline-cell-switch timeline-detail-height`, then `clj -M:mac-arm:test detail-height pref-events`, then the full suites.

## 9. Out of scope / alternatives considered

- **Browser width as a pref:** out of scope. It's fixed at 280 px in CSS (`css/timeline.clj:201-204`) with no resize handle. Add it alongside a handle if asked; the same pattern applies.
- **`:fx/key [col row]`** (07's fix): not needed, since the reset already happens. On the `ext-state` it would be a no-op (§2). On the dialog's child (`timeline_cue_editor.clj:117`) it would recreate the whole editor's JavaFX tree per switch, which is heavier than `:reset`.
- **Per-cell remembered zoom/scroll** (state keyed by cell): not requested; the docstring says reset.
- **L3-B, app settings file across restarts (~1 day):** new infrastructure. It needs a load at startup in `app.clj` (before or around `state/init-state!`, `:135`), a debounced save on pref changes, and a choice of which prefs go in it. `serialization/save-to-file!`/`load-from-file` (`serialization.clj:141,168`) exist but are unused. Worth doing for all prefs at once, not just this one.
- **L3-C, per-project `:config`:** a window size isn't project data, and changing it would mark the project unsaved (`[:config k]` is persisted; `state/history.clj:39-41`). Rejected.
