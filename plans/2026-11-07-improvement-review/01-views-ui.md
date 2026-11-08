# Improvement Plan: UI Views & CSS

**Date:** 2026-11-07
**Area:** `src/laser_show/views/**`, `src/laser_show/css/**`, repo-root `cljfx/` override dir, and how views consume subs
**Status:** Review only. No source changes were made.

Related existing plans (referenced, not duplicated):
- `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md`: list/canvas focus, `keyboard-handler-atoms`, global accelerators, non-atomic cut. Called **[FOCUS]** below.
- `plans/2026-10-08-canvas-draw-prop-unification.md`: `:draw` prop on `interactive-canvas`. Called **[CANVAS]** below. The `:draw` part is done; cleanup items 2 and 3 are still open (see F14).
- `plans/v3/plan-unsaved-guard.md`: unsaved-changes guard on close (see F20).

---

## 1. Summary

The view layer is organized well: almost every component is a context-aware function component, most handlers are cljfx event maps, and there is a shared `interactive-canvas`, a shared `list-editor` and a token-based CSS theme. The biggest problems are in **subscription granularity**. Grid cells, the timeline dialog and the status/preview header subscribe to raw domains that change 30 times a second (`[:playback :active-cues]`, `[:backend :streaming]`). The **preview canvas**, which the README says is the app's biggest CPU user, allocates heavily on every draw. There are also several **real functional bugs**: the tap-tempo button does nothing, the preview always shows "0 FPS", right-clicking a multi-selection collapses it, `read-string` runs on drag-and-drop text, and the main window never picks up CSS hot-reload. CSS consistency has slipped: there are about 220 hard-coded hex colours or `white` literals in views, about 70 CSS classes that look unused, and `docs/CSS_COLOR_SYSTEM.md` no longer matches `theme.clj`. Test coverage of views is thin: two pure-function unit tests plus a TestFX e2e suite that does not cover the bugs above.

---

## 2. Findings

### Correctness bugs

**F1. The tap-tempo button does nothing (High)**
`src/laser_show/views/toolbar.clj:123-125`: `:on-action (fn [_] {:event/type :timing/tap-tempo :time ...})`. A *function* handler's return value is thrown away by cljfx (`app.clj:88-94` uses `fx/create-app` with map-event dispatch only), so no event is ever dispatched. The handler (`events/handlers/timing.clj:28`) and its unit tests pass, but the toolbar button is dead. No UI test covers it.

**F2. The preview header always shows "0 FPS" (Med)**
`src/laser_show/views/components/preview.clj:349` reads `(:fps stats 0)`. The `frame-stats` map built in `services/frame_service.clj:745-755` has only `:avg-latency-us`, `:p95-latency-us`, `:max-latency-us`, `:avg-base-us`, `:avg-effects-us` and `:avg-*-idn-us`. There is no `:fps` key anywhere in `src/` except the streaming-engine config.

**F3. Right-clicking inside a multi-selection collapses it to one item (Med)**
`src/laser_show/views/components/list.clj:327-344`: the click handler reads `selected-ids` from `(:selected-ids dynamic-props)`. `dynamic-props` is `sidebar-props` (`list.clj:595-597`), which never contains `:selected-ids`; selection lives in `subs/list-ui-state`. So `selected-ids` is always nil, and every right-click re-selects just the clicked item before the context menu opens. A "Delete N items" action from the context menu is impossible.

**F4. `list.clj` writes the props atom twice per render (Med, latent)**
`list.clj:856-857` (`list-editor`) does `reset!` with `handler-props`, which lacks `:ordered-ids`. Then `list.clj:597` (`list-sidebar`) does `reset!` with `sidebar-props`. If `list-editor` re-renders but `list-sidebar` does not (equal props and unchanged subs), the atom keeps the version without `:ordered-ids`. Then Ctrl+A (`list.clj:763`) and Shift-click range (`list.clj:329-336`) receive `all-ids nil`. Today this is hidden only because callers pass a fresh `partial`/`make-registry-label` function on every render (see F11), so `list-sidebar` always re-renders. The atom map `!component-handler-props` (`list.clj:57`) is also never cleaned up for closed dialogs. **[FOCUS]** proposes deleting this mechanism. This finding adds the concrete defect and the `:selected-ids` gap.

**F5. `read-string` runs on arbitrary dragboard text (High: security and perf)**
`src/laser_show/views/components/drag_drop_cell.clj:60` and `:81` call `clojure.core/read-string` on whatever string is being dragged. `*read-eval*` defaults to true, so dragging text containing `#=(...)` from a browser onto a grid cell evaluates code. It also re-parses the dragboard on every `DRAG_OVER` event (mouse-move rate). `list_dnd.clj` does the safer thing: it ignores the payload and reads `:dragging-ids` from state.

**F6. CSS hot-reload never reaches the main window (Med)**
`src/laser_show/subs.clj:774-788`: `stylesheet-urls` takes `_context` and calls no `sub-val`/`sub-ctx`. cljfx therefore memoizes it with **zero dependencies** and never invalidates it, so bumping `[:styles :reload-trigger]` (`css/reload.clj:67`, `dev/user.clj:150`) has no effect on `root.clj:145`. Dialogs call `css/dialog-stylesheet-urls` directly while rendering, so they *do* refresh. That inconsistency hides the bug. Two more problems:
- `events/handlers/menu.clj:257-265` runs `reload-all-styles!` (which `swap!`s state) inside a pure handler and then returns `{:state state}`, the snapshot from *before* the swap. That clobbers the trigger increment. (This is events-area code; noted here because the views symptom shows up here.)
- `css/reload.clj:15` requires `clojure.tools.namespace.find`, which exists only in the `:dev` alias (`deps.edn`). It also scans `(io/file "src")`, which does not exist in an uberjar. So **Help → Reload Styles (Ctrl+Shift+R) throws in release builds.**

**F7. Effect-chain and projector views read the system clipboard while rendering (Med)**
`dialogs/effect_chain_editor.clj:95` and `tabs/projectors.clj:613` call `clipboard/get-effects-to-paste` → `get-clipboard` → `read-from-system-clipboard`. That function does `@(fx/on-fx-thread ...)`, reads the OS clipboard and EDN-deserializes it (`state/clipboard.clj:75-97`), all inside a render function. Two consequences:
- Render becomes impure and slow.
- The Paste button's enabled state is stale: it does not update after a copy until something unrelated re-renders.

Other lists in the codebase use `(fx/sub-val context get-in [:ui :clipboard ...])`.

**F8. The timeline's Delete key clears the selection instead of deleting clips (Med)**
`visual_editors/timeline_editor.clj:541`: `"Delete" {:dispatch {:event/type :timeline/clear-selection}}`. Users expect Delete to remove the selected clips. At minimum it is mislabelled. **[FOCUS]** covers routing the key but not this mapping.

**F9. Timeline toolbar controls compute their payload at render time (Low-Med)**
- `timeline_editor.clj:663-664`: the snap checkbox sends `:enabled? (not snap-enabled?)`, captured at render.
- `timeline_editor.clj:686-687`: the zoom slider sends `:zoom (or zoom-x default-zoom)`, also captured at render, alongside `:fx/event`.

Two quick toggles before a re-render send the same value twice. The new value is already in `:fx/event`; the extra key is misleading and risks the handler reading the stale one.

**F10. Duplicate node IDs and weak defensive code in widgets (Low)**
- `toolbar.clj:19`: the retrigger button gets `:id "play-btn"` (anything that isn't `:stop`), which collides with the real play button. TestFX lookups by `#play-btn` are ambiguous.
- `canvas_interaction.clj:266-271`: `on-scroll` catches `ArityException` thrown *from inside* the callback and calls it again with a different arity. That hides real arity bugs deeper in the call stack and can invoke the handler twice.
- `timeline_editor.clj:609`: `scroll-filter` swallows every `Throwable` silently.

### Render performance and subscription consumption

**F11. 30 fps subscriptions fan out to grid cells and the timeline dialog (High)**
`frame_service.clj:95-115` rewrites `[:playback :active-cues]` (the `:accumulated-beats` of every active cue) on every 30 fps preview tick. Three consumers:
- `subs.clj:191`: `cell-display-data` does `(fx/sub-val context ex/active-cues)`. **Every cue cell's** display-data sub is recomputed 30 times a second while anything plays (64+ cells, each running `tree-seq` + `filterv` over its chain). The cells only need `contains?`.
- `dialogs/timeline_cue_editor.clj:108-110`: subscribes to the whole `active-cues` map. The **entire timeline dialog** re-renders at 30 fps during playback: `build-tracks`, the `list-editor` sidebar (whose `:get-item-label (partial ...)` at `timeline_editor.clj:773-776` is a new function each render, so every list row re-renders), the inspector, the add-panel, and `(into {} ...)` of zone-groups at `timeline_cue_editor.clj:150`. On top of that, the full canvas (up to 8192 px wide, per `track_logic.clj:308-310`) is redrawn by `:draw`.
- `status_bar.clj:73` and `preview.clj:331` subscribe to `frame-stats`, which shares `ex/streaming-data` with `current-frame` (`subs.clj:358,365`). They re-render at 30 fps; that is fine for the latency readout but wasteful for the static header.

**F12. Dialogs and editors subscribe to the whole `:chains` domain (Med)**
`cue_chain_editor.clj:437,670`, `effect_chain_editor.clj:91,232` and `timeline_cue_editor.clj:96,230` all do `(fx/sub-val context :chains)` and then `get-in`. Any edit to *any* cue/effect chain, including changes made from MIDI/OSC, re-renders every open editor, including the *stage* component that only needs the title. There are no per-entity subs such as `(subs/cue-chain col row)`.

**F13. The preview draw loop allocates heavily (High, hot path)**
`preview.clj:70-96`:
- `draw-frame-lines` uses `(partition 2 1 points)`, which builds a lazy seq of 2-element seqs per segment.
- It destructures maps per point twice: once for lines, once for dots.
- Each segment allocates a new `Color` through `color-from-rgb` → `u/clamp` ×3, and calls `.setLineWidth` again.
- `draw-frame-points` calls `fillOval` per point.
- `draw-no-content` creates `(Font. "System" 14)` on every draw (`preview.clj:102`).
- With a 3x2 layout that is 6 canvases × N points × 30 fps, all on the FX thread.

Upstream, `frame_service.clj:697-711` converts every point (and every zone's points) to a `{:x :y :r :g :b :blanked?}` map each tick just for the preview. That upstream conversion belongs to the output-pipeline area.

**F14. Interactive canvases have race-prone drag state and too many events (Med)**
In `canvas_interaction.clj`, drag/hover state lives in global state and is written through `events/dispatch!`, which is **asynchronous** (agent; `events/core.clj:385-407`). The next mouse event reads it back synchronously via `get-raw-state` (`:42`).
- On fast drags, `on-mouse-dragged` (`:188-200`) can see the state from before the press (`:dragging? false`) and drop events. Values set on press, such as `:original-track-idx` in the timeline (`timeline_editor.clj:517-518`), can be read stale, which re-dispatches `move-item-to-track`.
- Each mouse move dispatches 2-4 separate events (`update-drag-state!` + `dispatch-result!` via `run!`, `:31`). Each is a separate context swap and render pass.
- The timeline's `on-drag` returns no `:preview-value`, so every pixel of drag commits to domain state and re-renders the whole dialog.
- `on-press` ignores `:preview-value` returned by callers (for example `hue_canvas.clj:140`), because only `:drag-updates` is merged (`:177-179`).
- **[CANVAS]** cleanup is still open: `:on-created` still calls `draw-interactive-canvas!` (`:153`), and the cursor is set imperatively with `.setStyle` (`:229`).

**F15. Allocation inside canvas render functions (Low-Med)**
- `timeline_editor.clj:290` creates `(Font. "Inter" 10)` **per clip** and `:192` per ruler draw.
- `:238` calls `Color/web "#444444"` per grid line.
- `:65-79` calls `Color/web` per track per draw.
- The same pattern repeats across `rotate_canvas.clj:76-178`, `curve_canvas.clj:68-164`, `scale_canvas.clj:102-182`, `spatial_canvas.clj:54-133`, `keyframe_timeline.clj:63-205` and `hue_canvas.clj:43,83`. They parse hex strings into `Color`s every frame during drags.
- `draw-ruler!` and `draw-grid!` (`timeline_editor.clj:176-242`) duplicate the subdivision table and loop.

**F16. Grid cells do O(cells × triggers) work (Med)**
`grid_cell.clj:218-225` and `:310-316`: every cell subscribes to the whole `input-trigger-map` and does a linear `some` scan to compute `has-trigger?`. Any trigger edit (MIDI learn) re-renders every cell. Each cell also rebuilds a 6-8 item context-menu description and three drag-handler closures on every render (`:152-168`, `:233-278`), even though the context menu is only materialized on right-click.

**F17. List rows re-render on any list change (Low-Med)**
`list.clj:520,611-612`: every row receives `:items` (the whole list, unused by `list-item-card`/`group-header`) and `:props` (which includes `:items` and `:ordered-ids`). Any change to the list, such as toggling one checkbox, re-renders every row. Keys are `[(:id item) idx]` (`:542,609`), so an insert or reorder **re-creates** every row below it and re-installs four imperative drag/click handlers per row (`:394-398`, `:476-480`). The index in the key is probably there to avoid stale `on-created` closures; with an atom/sub indirection it would not be needed.

### Structure, reuse and naming

**F18. The three chain-editor dialogs copy-paste their scaffolding (Med)**
These pieces are duplicated across `cue_chain_editor.clj`, `effect_chain_editor.clj` and `timeline_cue_editor.clj`:
- `handle-scene-key-pressed` undo/redo: identical in all three (`effect_chain_editor.clj:201-214`, `timeline_cue_editor.clj:202-215`, `cue_chain_editor.clj:639-652`).
- `destination-zone-dropdown` and `trigger-mode-dropdown`: their docstring says "shared with cue-chain-editor" but they are copies (`timeline_cue_editor.clj:24-80`).
- The `"Cell " (char (+ 65 row)) (inc col)` label: six copies.
- The stage + scene + stylesheet shell and the inline-edit header block.

The `context-menu`-via-`:label :graphic` wrapper is also repeated three times (`list.clj:441-449`, `:500-507`, `grid_cell.clj:190-196`).

**F19. Dead or misleading code (Low)**
- `preview.clj:141-167`: `zone-filter-converter`, `build-zone-filter-items` and `find-selected-item` are unused. `color-from-rgb` (`:37-40`) wraps `color-from-normalized` with the same nil-defaulting.
- `root.clj:43-48,153`: `layout-config` is put into env and never read (`ext-get-env` has no uses).
- `title_bar.clj:39-43`: the `:leading`/`:trailing` aliases are unused.
- `list.clj:586,854,892`: `:on-copy`/`:on-copy-fn` are passed by all five callers and never invoked.
- `list.clj:869-876`: a focus listener that only logs.
- `css/core.clj:34-75`: `all-stylesheet-urls` and `dialog-stylesheet-urls` are identical.
- `grid_cell.clj:93`: the docstring says the context menu is "Displayed only when cell is empty", which is false.
- `inline_edit.clj:72-91`: the comment says "setup click handling on every render", but `on-created` runs once (stale-closure pattern). A plain `:on-mouse-clicked` prop would do.
- `effect_bank.clj:61-72`: it caches on registry *count*, not identity, so a re-registered effect with the same count is never picked up.

**F20. Window lifecycle and global accelerators (Med)**
- `root.clj:167-169`: `:on-close-request (fn [_] (System/exit 0))`. This ignores unsaved work (see `plans/v3/plan-unsaved-guard.md`) and kills the REPL JVM in dev mode, defeating `Platform/setImplicitExit false` (`app.clj:126-128`).
- `title_bar.clj:152,164,143`: single-key accelerators `[:space]`, `[:r]` and `[:delete]` on main-window menu items. These also fire while typing in main-window text fields such as BPM and projector names, because `TextField` consumes `KEY_TYPED`, not `KEY_PRESSED`. **[FOCUS]** §4 covers the architecture; this adds `[:r]` and `[:space]` as concrete risks.
- `title_bar.clj:216-219`: "Reload App Code" (`Ctrl+Shift+A`) ships in production menus.

**F21. Parameter controls can show up in random order (Med)**
`parameter_controls.clj:32-41`: `params-vector->map` uses `u/map-into` with `{}`, so any effect with **more than 8 parameters** becomes a hash-map. `param-controls-list` (`:248-259`) then renders the controls in hash order instead of registry order. The same helper feeds `effect_param_ui.clj:27` and the modulator lists.

**F22. Unsafe alpha suffixing on colour strings (Low)**
`preview.clj:228`, `zone_chips.clj:26` and `spatial_canvas.clj:109` build alpha colours with `(str color "40")`, `(str color "80")` and `"#4A6FA520"`. These break for `#RGB`, for `#RRGGBBAA`, or for named colours coming from user zone-group config. `css/colors.clj:155` already provides `with-alpha`.

### CSS consistency

**F23. The theme system is bypassed in views (Med)**
About 220 hard-coded colours (`#hex`, `white`, `Color/web "#..."`) appear in `src/laser_show/views`. The heaviest offenders:
- `timeline_editor.clj` (≈50)
- `projectors.clj` (≈17)
- `preview.clj` (≈15)
- `zone_group_editor.clj`
- `modulator_param_control.clj`
- every `visual_editors/*_canvas.clj`

Some literals don't match the theme at all: `#2D2D2D`, `#404040`, `#1A1A1A`, `#1E1E1E` and `#909090` are not tokens in `theme.clj:32-52`. Others duplicate tokens: `#121212` = `:bg-base`, `#4A6FA5` = the old blue. In addition:
- `css/core.clj` exposes only 14 accessors, and the docstring examples at `:13-15` show stale values (`#1E1E1E`, `#4CAF50`).
- Status colours are re-declared per component (`link_settings.clj:29-33`, `osc_settings.clj:23-26`, `midi_settings.clj:23-26`, `zones.clj:25-47`), even though `.status-indicator-*` classes exist in CSS.
- `keyframe_timeline.clj:63` reaches into `theme/base-colors` directly.
- `tabbed_bank.clj:102,115,120` inlines `::theme/bg-primary` even though `panel-primary`/`scroll-pane-base` classes exist.

**F24. Dead CSS classes and documentation drift (Low)**
- A scan of `"\.class"` selectors in `css/*.clj` against the view sources finds about 70-80 classes with no reference. After removing false positives (dynamically built `group-name-depth-*`, the built-in `scroll-bar`), the bulk is in `css/cue_chain_editor.clj`: 32 `preset-item*`/`preset-effect*`/`preset-param*`/`preset-bank-*`/`mini-effect*` selectors. There are also `badge-*`, `status-dot-*`, `visual-editor-info-*` and `inline-edit-*`.
- `docs/CSS_COLOR_SYSTEM.md` describes 7 grays (`gray-900` = `#1E1E1E`) and `green-500` = `#4CAF50`. `theme.clj` has 5 grays (`#121212` …) and green `#3D8B40`. `bg-surface`/`bg-elevated` are now all `bg-base`.

### cljfx override directory

**F25. The repo-root `cljfx/` overrides are byte-identical to upstream and not on the classpath (Low, cleanup)**
`cljfx/mutator.clj` and `cljfx/fx/v_box.clj` were added in `935df2f` ("override some vbox stuff"). Diffing them against `cljfx-1.10.6.jar` shows the only difference is a trailing newline. `deps.edn :paths` is `["src" "resources" "classes"]`, so they are never loaded either way. The real fix in that commit was moving `:v-box/vgrow` onto the `ext-on-instance-lifecycle` wrapper in `list.clj:626,641`. Leaving the directory in place suggests to readers that cljfx is patched when it isn't.

### Tests

**F26. View tests are thin (Med)**
Only `test/laser_show/views/components/preview_test.clj` (one private fn), `status_bar_test.clj` and `visual_editors/timeline/track_logic_test.clj` exist. The TestFX e2e suite (`ui_test/e2e_test.clj`) has no case for:
- tap tempo
- the FPS label
- right-click on a multi-selection
- paste-button enablement after copy
- CSS reload
- timeline Delete key

Pure helpers that are easy to unit-test have no tests: `grid_cell/cell-label`, `effects-label`, `build-style-classes`, `list` style-class builders, `timeline_editor/build-tracks` and `hit-test`, `params-vector->map` ordering, and `css/colors`.

---

## 3. Proposed improvements

### Quick wins (each S effort)

**Q1. Fix tap tempo (F1)**
- Steps: change `toolbar.clj:123-125` to `(fn [_] (events/dispatch! {:event/type :timing/tap-tempo}))`. The handler already falls back to `h/current-time-ms`. Add a TestFX case that clicks TAP 3 times and asserts `[:timing :tap-times]` has 3 entries.
- Files: `views/toolbar.clj`, `test/laser_show/ui_test/e2e_test.clj`
- Risk: none.

**Q2. Fix or remove the FPS label (F2)**
- Steps: either compute FPS in `frame_service` (`(/ 1e6 avg-frame-interval-us)`), which is a dependency on the output pipeline, or drop the label and show `avg-latency-us`. Add a unit test on the header description.
- Files: `views/components/preview.clj`, possibly `services/frame_service.clj`
- Risk: low.

**Q3. Make DnD payload parsing safe (F5)**
- Steps: in `drag_drop_cell.clj`, replace `read-string` with `clojure.edn/read-string` and wrap it in a `try`. Better: put a sentinel prefix in the payload, or use a custom `DataFormat`, and check that before parsing, so non-app drags are rejected for free. Only parse in `DRAG_DROPPED`; in `DRAG_OVER`, check `(.hasContent db app-format)`.
- Files: `views/components/drag_drop_cell.clj`
- Risk: low.

**Q4. Fix right-click selection and the props-atom double write (F3, F4)**
- Steps: in `setup-click-handler!`, read selection from `get-selected-ids` (already defined at `list.clj:49`; it reads state). Remove the `update-props-atom!` call in `list-editor` (`:856-857`) so only `list-sidebar` writes the atom. Add a `:on-deleted` on the `list-editor` lifecycle that `dissoc`s the component's atom. If **[FOCUS]** lands first, this folds into its atom removal.
- Files: `views/components/list.clj`, plus an e2e case (right-click inside a 3-item selection, then Delete)
- Risk: low.

**Q5. Fix CSS hot-reload (F6)**
- Steps:
  1. Make `subs/stylesheet-urls` depend on the trigger: `(fx/sub-val context get-in [:styles :reload-trigger])`, then return `(css/all-stylesheet-urls)`.
  2. Make dialogs use the same sub instead of calling `css/dialog-stylesheet-urls`, and delete the duplicate function.
  3. Change `handle-help-reload-styles` to return an effect (for example `{:css/reload true}`) instead of `swap!`ing inside a pure handler. This touches the events/state area.
  4. Guard `css/reload.clj` with `requiring-resolve` and a friendly log message when tools.namespace or `src/` is missing, or hide the menu item when `(dev-config/dev-mode?)` is false.
- Files: `subs.clj`, `css/core.clj`, `css/reload.clj`, `views/dialogs/*.clj`, `events/handlers/menu.clj`
- Risk: low.

**Q6. Use a subscription for clipboard state in render (F7)**
- Steps: add `subs/effects-clipboard-items`, which reads the internal clipboard from state with no OS clipboard access, and use it in `effect_chain_editor.clj:95` and `projectors.clj:613`. Keep OS-clipboard parsing inside the paste *handler* only.
- Files: `subs.clj`, `views/dialogs/effect_chain_editor.clj`, `views/tabs/projectors.clj`
- Risk: low. Cross-app paste still works because the handler reads the OS clipboard.

**Q7. Fix small widget bugs (F8, F9, F10)**
- Steps:
  - Map timeline Delete to `:timeline/delete-selection` (add the handler if it is missing).
  - Drop the `:enabled?` and `:zoom` render-time payloads and read `:fx/event`.
  - Give the retrigger button a unique `:id`.
  - Remove the `ArityException` fallback in `on-scroll`; all callers use the map arity, so verify with grep.
  - Log in `scroll-filter`'s catch.
- Files: `timeline_editor.clj`, `toolbar.clj`, `canvas_interaction.clj`, `events/handlers/timeline.clj` (for Delete)
- Risk: low.

**Q8. Keep parameter order (F21)**
- Steps: make `params-vector->map` return an order-preserving structure. Either keep the vector and look up by key with a small index map, or build the controls list from `(:parameters effect-def)` directly. Add a unit test with a 10-parameter spec.
- Files: `views/components/parameter_controls.clj`, `effect_param_ui.clj`, `modulator_param_control.clj`
- Risk: low.

**Q9. Delete dead code and the cljfx override dir (F19, F25)**
- Steps: remove the items listed in F19. Delete `cljfx/` at the repo root, after confirming `clj -Spath` does not include it and that no `:extra-paths` alias adds it. Fix the misleading docstrings.
- Files: `preview.clj`, `root.clj`, `title_bar.clj`, `list.clj`, `css/core.clj`, `grid_cell.clj`, `inline_edit.clj`, `cljfx/**`
- Risk: very low.

**Q10. Cache `Font` and `Color` constants in canvas renderers (F13 partial, F15)**
- Steps: lift every `(Font. ...)` and `(Color/web "#literal" ...)` used inside draw functions to `def ^:private` constants, as `timeline_editor.clj:52-62` already does for some. For per-zone colours, memoize `hex+alpha → Color` in a small `ConcurrentHashMap`.
- Files: all `visual_editors/*_canvas.clj`, `timeline_editor.clj`, `keyframe_timeline.clj`, `preview.clj`
- Risk: none.

### Medium refactors (M effort)

**M1. Narrow subscriptions off the 30 fps paths (F11, F12)**
- Steps:
  1. Add `subs/active-cue-ids`: `(set (keys (fx/sub-val context ex/active-cues)))`. cljfx stops propagating when a sub's value is unchanged, so beat ticks no longer invalidate dependants. Switch `cell-display-data` (`subs.clj:191`) to `(contains? (fx/sub-ctx context active-cue-ids) [col row])`.
  2. Add `subs/cue-beats-elapsed [col row]` and subscribe to it **only in `timeline-canvas`**, not in `timeline-cue-editor-content`. Make `timeline-canvas` a context component that subscribes itself, so the playhead redraw no longer re-renders the sidebar, inspector or add-panel.
  3. Add per-entity subs `(subs/cue-chain col row)`, `(subs/effect-chain col row)` and `(subs/cue-chain-name col row)`, and replace the six `(fx/sub-val context :chains)` uses in dialogs.
  4. Split `subs/frame-stats` from `current-frame`, ideally storing them under separate state keys (events/state dependency). Have `preview-grid-header` subscribe only to what it shows.
  5. Add `subs/zone-groups-by-id` (memoized) to replace the four `(into {} (map (juxt :id identity)) zone-groups)` calls made during render (`timeline_cue_editor.clj:150`, `cue_chain_editor.clj:465`, …).
- Files: `subs.clj`, `views/dialogs/{cue_chain_editor,effect_chain_editor,timeline_cue_editor}.clj`, `timeline_editor.clj`, `preview.clj`, `status_bar.clj`
- Risk: medium. These are behaviour-preserving, but verify with cljfx dev tools or a render counter that cells and dialogs stop re-rendering during playback. `docs/PROFILING.md` applies.

**M2. Rewrite the preview draw loop as a primitive loop (F13)**
- Steps:
  1. Iterate with `loop` / `dotimes` over an indexed vector, with no `partition`/`doseq` destructuring.
  2. Hoist `.setLineWidth` out of the loop.
  3. Only call `setStroke` when the colour changes (compare the three doubles).
  4. Draw dots with `fillRect` 2x2 or skip them when `count > threshold`.
  5. Cache the `Font`.
  6. Coordinate with the output-pipeline area so the preview payload becomes a `double[]` (x, y, r, g, b interleaved) or the raw `LaserPoint` frame instead of a vector of maps.
  7. Skip drawing when the canvas is not visible (`(.isVisible canvas)` / when the tab hides the preview). Ask output-pipeline to pause `start-preview-updates!` on hidden tabs.
- Before/after: measure draw time with JFR (`deps.edn :jfr`).
- Files: `views/components/preview.clj`; output-pipeline: `services/frame_service.clj`
- Risk: medium. It must keep the blanking semantics of `point-blanked?`.

**M3. Make interactive canvases hold interaction state locally (F14, [CANVAS] remainder)**
- Steps:
  1. Store drag/hover state in an atom in the Canvas `userData`, read and written **synchronously** by the mouse handlers. Only mirror it into `[:ui :canvas-drag]` if other components need it; currently only the canvas reads it.
  2. Use `:preview-value` for timeline drags, and dispatch the domain update only on release, or throttle it. Keep `history/begin-transaction`/`commit` as is.
  3. Batch multiple results into one event (`{:event/type :batch :events [...]}` or the `:dispatch-n` effect) so each mouse move is one swap.
  4. Honour `:preview-value` in `on-press`.
  5. Finish **[CANVAS]** items 2 and 3: drop the `on-created` draw, and set the cursor through state or the `:style` prop.
  6. Make every canvas call site use `{:fx/type ci/interactive-canvas ...}` instead of a direct function call (7 files call `(ci/interactive-canvas {...})`).
- Files: `visual_editors/canvas_interaction.clj`, `timeline_editor.clj`, `hue_canvas.clj`, `hue_shift_canvas.clj`, `rotate_canvas.clj`, `scale_canvas.clj`, `spatial_canvas.clj`, `curve_canvas.clj`, `keyframe_timeline.clj`; events: `handlers/ui.clj` (`:canvas/*`)
- Risk: medium. Drag UX regressions are possible; the TestFX `timeline-editor-controls-and-canvas-respond-to-clicks` test plus manual drag checks are required.

**M4. Make grid cells cheap (F16)**
- Steps: add `subs/cell-trigger-targets`, a memoized set of `[type target]` from `input-trigger-map`, and `subs/cell-has-trigger? type col row`. Build the context menu lazily via `:on-context-menu-requested`, or at least move the static menu parts to a function of `[col row has-content? learning? has-trigger?]` so the description stays equal between renders. Pass drag-handler factories stable inputs; equal closures still compare unequal, so consider an event-map-based `:on-drag-dropped` plus a single shared handler that reads `col`/`row` from node `userData`.
- Files: `subs.clj`, `views/components/grid_cell.clj`, `views/components/drag_drop_cell.clj`
- Risk: low-medium.

**M5. Extract shared dialog scaffolding (F18)**
- Steps: create `views/dialogs/common.clj` with:
  - `cell-label [col row]`
  - `undo-redo-key-handler`
  - `destination-zone-dropdown`
  - `trigger-mode-dropdown`
  - `editable-name-header` (wrapping `inline-edit-text` + dialog-id)
  - `chain-dialog-stage` (stage + scene + stylesheet sub + `:on-close-request`)

  Also add `views/components/with-context-menu.clj` for the label-graphic wrapper. Migrate the three chain dialogs, then `zone_group_editor`, `cell_trigger_editor` and `add_projector_manual`. Coordinate with **[FOCUS]**, which wants a scene-level key filter on each dialog; the shared stage component is the natural place for it.
- Files: `views/dialogs/*.clj`, `views/components/list.clj`, `grid_cell.clj`
- Risk: low.

**M6. List rendering: stable props and keys (F17)**
- Steps: stop passing `:items` and whole `props` to rows. Pass only row-relevant keys plus a stable `component-id`; handlers already read current data through atoms or `items-path`. Change keys to `(:id item)` alone once the `on-created` closures read through the component atom (Q4 / **[FOCUS]**). Require callers to pass stable label functions, or turn `get-item-label` into a data spec (`{:registry :presets :id-key :preset-id}`) resolved inside the row, so `partial`s stop defeating memoization (`cue_chain_editor.clj:512-515`, `timeline_editor.clj:773-776`).
- Files: `views/components/list.clj`, the callers in dialogs, `projectors.clj` and `timeline_editor.clj`
- Risk: medium. DnD handlers attach in `on-created`, so key changes must be checked against the e2e DnD tests.

### Larger efforts (L effort)

**L1. Colour and style tokenization pass (F22, F23, F24)**
- Steps:
  1. Extend `theme.clj` with the missing tokens that views actually use: canvas tokens (`canvas-bg`, `canvas-grid`, `canvas-grid-major`, `canvas-text`, `playhead`, `selection-outline`, `handle-x/y/both`, corner colours), surface tones (`bg-panel-dark` for `#1A1A1A`/`#1E1E1E`), `text-faint`, and status colours (`status-connected/disconnected/learning/partial`).
  2. Add a `laser-show.css.canvas` namespace exporting pre-built `javafx.scene.paint.Color` constants derived from the tokens, so canvases do `(:grid canvas-colors)` with no string parsing (subsumes Q10).
  3. Replace inline `:style` strings with CSS classes, starting with the repeated header-label (`-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;` appears in five files) and the `#808080` 11px hint label.
  4. Replace `(str color "40")` with `colors/with-alpha`.
  5. Delete the dead selectors from F24 after a grep that also covers `str`-built class names.
  6. Rewrite `docs/CSS_COLOR_SYSTEM.md` from `theme.clj`, or generate the tables from the theme maps.
  7. Optional: add a test that fails if `src/laser_show/views/**` contains `#[0-9A-Fa-f]{6}` outside an allow-list (zone-group palette and preset colours are data, not theme).
- Files: `css/theme.clj`, `css/core.clj`, new `css/canvas.clj`, `css/cue_chain_editor.clj`, `css/components.clj`, most `views/**`, `docs/CSS_COLOR_SYSTEM.md`
- Risk: low functional risk, high diff size. Do it per file with screenshot comparison (the TestFX/LLM-vision harness in `test/laser_show/ui_test/` can help).

**L2. View-layer test harness (F26)**
- Steps:
  1. Add pure-description unit tests for `grid_cell` (`cell-label`, `effects-label`, `build-style-classes`), `list` style builders, `timeline_editor/build-tracks` and `hit-test` (make them public or test via var), `parameter_controls` ordering, `css/colors` round-trips, and `preview/draw-preview` against a mock `GraphicsContext`, or by checking that a real `Canvas` snapshot is non-black where points exist.
  2. Add a "render count" dev helper (wrap `fn->lifecycle-with-context` to count renders per component) and a test asserting that grid cells render 0 times during 1 second of playback after M1.
  3. Add e2e cases for Q1, Q4, Q6 and Q7.
- Files: `test/laser_show/views/**`, `test/laser_show/ui_test/e2e_test.clj`, maybe `dev/`
- Risk: low.

**L3. Window and shortcut lifecycle (F20), joint with [FOCUS] and the unsaved-guard plan**
- Steps: route `:on-close-request` to an event (`:file/request-exit`) that checks dirty state, and only calls `Platform/exit` / `System/exit` outside dev mode. Remove the single-key accelerators (`[:r]`, `[:space]`, `[:delete]`) from `MenuItem`s in favour of **[FOCUS]**'s scene filter, which skips text inputs. Hide dev-only menu items in production.
- Files: `views/root.clj`, `views/components/title_bar.clj`, events `file`/`menu` handlers
- Risk: medium. It is user-facing shortcut behaviour.

---

## 4. Suggested order of work

1. **Q1, Q2, Q3, Q7**: user-visible bugs and a security fix, each under an hour, no dependencies.
2. **Q4, Q6**: list and clipboard correctness. Q4 overlaps **[FOCUS]**: if **[FOCUS]** is scheduled within about two weeks, fold Q4 into it; otherwise do Q4 now, since it is small.
3. **Q5**: CSS reload. It needs a one-line change in the **events/state** area (menu handler effect).
4. **Q8, Q9, Q10**: cheap cleanups that shrink the code surface before the refactors.
5. **M1**: subscription narrowing. This is the biggest UI CPU win. It needs agreement from the **events/state** area on the new subs and on splitting `[:backend :streaming]` keys. Verify with a render counter (L2 step 2, which can be done first).
6. **M2**: preview loop. The view half is independent. The payload-format half depends on the **output pipeline (IDN/backend/routing)** area changing `frame_service/get-preview-frame`. The **animation/effects** area is unaffected.
7. **M3**: canvas interaction. It depends on **events/state** for a batch-dispatch effect and for the `:canvas/*` handler cleanup. Finish **[CANVAS]** in the same PR.
8. **M4, M6**: grid cell and list row memoization. M6 should come after Q4 / **[FOCUS]**, because stable keys require closures that are not stale.
9. **M5**: dialog scaffolding. Do it before or together with **[FOCUS]** phase 3 (scene-level filters per dialog) so the filter is written once.
10. **L1**: tokenization, file by file, after M5 so dialogs are tokenized once.
11. **L2**: tests can be written incrementally alongside each item above. The render-count helper should land before M1. This depends on the **testing/build/tooling** area for CI wiring (Xvfb TestFX job).
12. **L3**: with **[FOCUS]** and `plans/v3/plan-unsaved-guard.md`. It needs **input/triggers** sign-off because keyboard triggers share keys with the menu accelerators.

### Cross-area dependencies at a glance

| Item | Needs from |
|------|-----------|
| Q2 FPS value | output pipeline (`frame_service` stats) |
| Q5 reload effect | events/state (`menu.clj` handler), testing/build (prod build without tools.namespace) |
| M1 split streaming keys, new subs | events/state |
| M2 preview payload format, pause when hidden | output pipeline |
| M3 batch dispatch, `:canvas/*` handlers | events/state |
| L2 CI for TestFX | testing/build/tooling |
| L3 accelerators vs keyboard triggers | input/triggers, events/state |


---

## Carried over from old plans (2026-11-08 audit, see 07-old-plans-audit.md)
- **Menu stubs that do nothing** (from `plans/plan-menu-dialogs.md`):
  - Preview panel toggle flips `[:ui :preview-visible?]`, which nothing reads (`events/handlers/menu.clj:197-202`).
  - Fullscreen toggle flips `[:ui :fullscreen?]`, but nothing calls `setFullScreen` (`menu.clj:204-209`).
  - For each one, either wire it to the view or remove the menu item.
- **Tracks can't inherit their folder's zone** (from zone routing arch/02 §3.3): `handle-add-track` stamps the chain's default zone on every new track (`events/handlers/timeline.clj:427-429`), so a track moved into a folder never inherits the folder's zone. Create tracks without `:zone-group-id` (inherit) and add a test.
- **Product decision:** should clips on the same track be allowed to overlap? If not, enforce it in `handle-update-item-timing` (the mouse path, `timeline_editor.clj:487-498`), with a test. Both old overlap plans had a broken algorithm and were deleted.
- **Unsaved-changes guard on close/open/new:** re-plan it against `[:project :dirty?]` (`root.clj:167`) once dirty tracking is reliable (02 M3). The old `v3/plan-unsaved-guard.md` named namespaces that don't exist and was deleted.
