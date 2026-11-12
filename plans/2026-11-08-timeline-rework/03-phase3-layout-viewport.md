# Phase 3 — One layout, virtual viewport, header column, new look

**Written:** 2026-11-08   **Written for commit:** `d0a7dc2`
**Status:** Done (2026-11-11, branch `timeline/phase3-layout`, commits `26e0f84`, `ad19072`)   **Last verified against:** `ad19072`

> **How the implementation differs from this plan:**
> - **Viewport layout.** The lanes are a `:pane` holding two unmanaged canvases (lanes + overlay) sized from the view, not a StackPane. A StackPane's minimum size follows its children, so it couldn't shrink. The vertical ScrollBar sits right of the lanes and the horizontal one under them (offset by the header width).
> - **Per-cue state.** Selected track stays in `[:list-ui [:timeline-tracks col row]]` (Phase 2) and rename-in-progress is view-local (`:renaming-id` in `ext-state`), so neither is new app state. The viewport resets when the editor shows another cell, as §9 allows.
> - **Follow playhead** is an `fx/ext-effect` whose args change once per page; the swap goes through `Platform/runLater`. Zooming doesn't turn follow off; scrolling by hand does.
> - **Header rows** are a graphic-only `:label` wrapping an `:h-box`, because only Controls take a `:context-menu`.
> - **The inspector** lives in NEW `timeline.inspector`; effects can be removed through a NEW `:remove-effect` op. The Phase 4/5 rows (length, tempo, timecode) aren't there yet.
> - **Not done from `05` §3.4:** the hover trim-zone bands, the dashed original outline, snap guide and tooltip while dragging, and the narrow-clip tooltip. Labels are clipped without an "…".
> - **Performance is unmeasured.** The overlay-only redraw during playback is *unverified*; no JFR run yet (`docs/PROFILING.md`).
> - **Tests** live in `laser-show.ui-test.timeline-editor-test`, not `e2e-test`. `timeline-long-show-reachable` uses a clip at beat 300, not 600: Fit can't go below the 2 px/beat minimum zoom in the 780 px headless lane width. The screenshot baseline is `laser-show.ui-test.timeline-visual-test/timeline-screenshot-baseline`, run with `LASER_SHOW_VISUAL=1`; the test runner can't exclude a tag by default and still let you opt in.
**Supersedes / Superseded by:** none   **Related findings:** TL-15 (rest), TL-16, TL-18, TL-28 (render part), V-1, V-6…V-14 (`05-visual-design.md`); 01 F11, F15, F17, F22, M6, Q10, L1; 02 M4 "timeline scroll panes"

This phase implements the visual spec in `05-visual-design.md` §3. This file covers structure and data flow; `05` covers how it looks.

## 1. Current behaviour (file:line)

- **Two panes, two layouts.**
  - The left pane is the generic `list-editor` (`timeline_editor.clj:757-785`, `list.clj:789-892`) inside a vbar-less ScrollPane (`timeline_editor.clj:1121-1142`). Its rows are content-sized: CSS padding 6 px top/bottom (`css/list.clj:338-348`), 4 px gaps between items (`list.clj:605`), 2 px inside groups (`:528`), and taller folder headers with buttons (`list.clj:375-449`).
  - The right pane is a canvas with fixed 32 px lanes (`timeline_editor.clj:37,135-138`) inside another ScrollPane (`:1143-1178`).
  - Vertical position is synced by copying the normalized `vvalue` between them on the agent thread (`timeline.clj:93-102`).
  - In `img/before-02-content.png`, header row centres are at y≈138/169/202/235 and lane centres at 132/164/196/228, so the rows sit 5–7 px off and folder rows are a different height.
- **One giant canvas.** Width = `max(32, last end + 8)` beats × zoom, clamped to `[800, 8192]` px (`track_logic.clj:308-325`). The ruler and grid always draw from beat 0 to the canvas width (`timeline_editor.clj:176-242`). Zoom-at-cursor needs content-width maths done against live ScrollPanes from the agent thread (`timeline.clj:138-178`, `track_logic.clj:342-381`).
- **Everything redraws on every render.** The `:draw` prop is a new closure each render (`canvas_interaction.clj:168-169`), so any re-render of `timeline-canvas` redraws ruler, grid, lanes and clips. After Phase 2 that's only on document/selection/preview/playhead changes, but the playhead still forces a full redraw at 30 Hz while playing.
- **The look is hard-coded per draw call.** About 50 literal colours and fonts in `timeline_editor.clj` (01 F22). `(Font. "Inter" 10)` is created per clip (`:290`) and per ruler draw (`:192`). `Color/web` is called per grid line (`:238`) and per track (`:65-79`) (01 F15). The details are in `05` §2.

## 2. Root cause

Root causes 3, 4 and 6 (`00-overview.md` §2). The header and lane positions aren't derived from one source, the scrollable area is modelled as a real node instead of a viewport, and there's no visual token layer.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `fx/ext-state` (cljfx 1.10.6 `cljfx/api.clj`, `lifecycle.clj:809-843`) | View-local viewport + hover state; `swap-state` is safe from any thread and re-renders on the FX thread with the latest value, so ScrollBars never get a stale value back |
| `fx/ext-effect` (cljfx `api.clj`) | Follow-playhead page flips without side effects in render |
| `fx/ext-on-instance-lifecycle` (already used at `timeline_editor.clj:1121,1143`) | Size listeners on the lane `StackPane` |
| `interactive-canvas` after Phase 2 (local interaction atom) | Base lane canvas with gestures |
| `tl/calculate-zoom-at-cursor` focal-beat maths (`track_logic.clj:342-381`) | Simplified into `layout/zoom-at` (no content width needed) |
| `list_dnd/setup-drag-source!`, `setup-drag-target!`, `setup-container-drop-target!` (`list_dnd.clj:108,146,225`) | Track header drag-reorder; still drops through `:list/perform-drop` → `:timeline/update-tracks` |
| `track-context-menu` (`timeline_editor.clj:722-754`) | Header context menu, extended |
| `clj-font-awesome` `fa/icon` (used in `list.clj:403`) | Toolbar and header icons |
| `css/theme.clj` `base-colors` / `semantic-colors` (`:32-92`) | Source of the timeline tokens (`05` §3.1) |
| 01 L1 `laser-show.css.canvas` (proposed there) | Created here, first user is the timeline (`00-overview.md` §6) |

## 4. Changes per namespace

### 4.1 `laser-show.timeline.layout` (NEW, `src/laser_show/timeline/layout.clj`)
Pure. It replaces the geometry left in `track_logic.clj` and `build-tracks` (`timeline_editor.clj:87-132`). Justification: one source of row positions for the header column, lanes and hit-testing (TL-16).

- `metrics` = `{:ruler-h 36 :track-h 36 :folder-h 26 :row-gap 1 :header-w 220 :edge-grab-px 6 :min-clip-px 6}`. Numbers from `05` §3.4.
- `rows [tracks clips metrics] → [{:id :kind (:track | :folder) :depth :y :h :color :muted? :zone-id :zone-inherited? :clip-ids :summary-clip-ids}]`.
  - `:y` is in content coordinates (0 = first row).
  - A collapsed folder carries its descendants' clips as `:summary-clip-ids`.
  - It replaces `build-tracks`; there's no "Unassigned" row, because the model guarantees valid tracks.
- `content-height [rows]`, `content-beats [cue]` = `max(16, last clip end, loop end) + 16`. Phase 4 swaps this to `:timeline/length` + 16.
- Viewport `view` = `{:zoom px-per-beat :scroll-beat b :scroll-y px :width px :height px}`.
  - `beat->x [view beat]`, `x->beat [view x]`, `visible-beat-range [view]`, `row-at [rows view y]`.
  - `clamp-view [view content-beats content-height]`.
  - `zoom-at [view focal-x factor {:keys [min max]}]`, with bounds min 2, max 400 px/beat.
  - `scroll-by [view dx-px dy-px bounds]`, `fit [view content-beats]`.
- `ruler-ticks [view] → [{:beat :x :kind (:bar | :beat | :sub) :label}]`. This is the single subdivision table used by the ruler and the grid, replacing the duplicated tables at `timeline_editor.clj:185-190,223-228` (01 F15). Labels are bars `"5"` and beats `"5.3"` (`05` §3.3).
- `hit-test [rows cue view x y] → {:type (:clip | :loop | :ruler | :lane | :empty) …}`. Topmost first. The loop band is only its drawn strip. Edge zones are `edge-grab-px`, shrunk proportionally for clips narrower than 3 × that.

### 4.2 `laser-show.views.components.visual-editors.timeline-editor` (existing, largely rewritten)
New tree:

```
timeline-editor
└─ fx/ext-state  :initial-state {:cue [col row] :view {:zoom 50 :scroll-beat 0 :scroll-y 0 :width 0 :height 0} :hover nil :follow? true}
   └─ border-pane
      ├─ top:    timeline-toolbar                (05 §3.7)
      ├─ center: h-box
      │   ├─ header-column  (fixed width 220, clipped pane, rows translated by -scroll-y)
      │   ├─ stack-pane  (h-grow; size listener → swap-state :width/:height)
      │   │    ├─ lane-canvas    interactive-canvas: ruler + lanes + grid + clips + loop + drag preview
      │   │    └─ overlay-canvas :mouse-transparent true: playhead + cursor + follow
      │   └─ scroll-bar :vertical   (value = scroll-y, max = content-height − viewport)
      ├─ bottom: scroll-bar :horizontal (value = scroll-beat, max = content-beats − visible beats,
      │                                  visible-amount = visible beats)
      └─ right:  timeline-inspector (collapsible, 05 §3.8)
```

- **Viewport flow.** ScrollBar `:on-value-changed` → `swap-state` (no app event). Mouse wheel on the stack pane or header is handled in an `:on-scroll` handler that calls `swap-state` directly:
  - plain wheel → scroll-y
  - Shift+wheel or trackpad dx → scroll-beat
  - Ctrl+wheel → `layout/zoom-at` the mouse x

  Because cljfx applies the ScrollBar `:value` prop under `*in-progress?*`, re-renders never fire the listener, and the value always equals the latest local state. That removes TL-15 entirely.
- **Canvas sizing.** Both canvases take `:width`/`:height` from the local view. The stack pane listener (`widthProperty`/`heightProperty`) swaps them in.
- **Lane canvas.** Its `render!` draws in this order: lane backgrounds → loop region tint → grid → clips (preview cue while dragging) → ruler (fixed at the top, not scrolled vertically) → loop band in the ruler → marquee. Fonts and Colors come from `laser-show.css.canvas` (§4.5); no allocation per element.
- **Overlay canvas.** A context component subscribed to `subs/timeline-playhead` (Phase 2) and the cursor. It only draws two lines and the ruler heads, so playback ticks redraw ≈ 2 lines, not the timeline. Performance claim *unverified*; measure with `docs/PROFILING.md` (JFR) before and after.
- **Follow playhead.**
  - `fx/ext-effect` with `:args [page follow?]`, where `page = floor((playhead − scroll-beat) / visible-beats)`. When `page ≠ 0` and follow is on, it swaps `:scroll-beat` to put the playhead at 10 % of the width. It fires once per page, not per frame.
  - Any user scroll turns follow off. A toolbar toggle turns it back on.
- **Deleted from this namespace:**
  - `build-tracks`, `track-y`, `total-canvas-height`, `calculate-canvas-width` (`:87-150`)
  - `scroll-filter` (`:555-610`)
  - both ScrollPanes (`:1121-1178`)
  - `timeline-sidebar` and the label renderers (`:691-785`)
  - every hard-coded colour/font def (`:50-62,157-159,245-246`)

### 4.3 `laser-show.views.components.visual-editors.timeline.header` (NEW, `…/timeline/header.clj`)
Justification: the header must match `layout/rows` pixel for pixel and needs track-specific controls. The generic `list-editor` can't do either without growing timeline-specific options (01 M6 already warns about its prop churn).

- `header-column [{:keys [rows view col row selected-track renaming-id zone-groups]}]` returns a `:pane` with `:clip` set to its bounds, containing an absolutely positioned `:v-box` with `:translate-y (- scroll-y)`, spacing 0.
- `track-header-row` is fixed height (`:min-height = :pref-height = :max-height = (:h row)`). Contents (`05` §3.5):
  - 4 px colour strip
  - indent `(* depth 14)`
  - collapse chevron (folders)
  - name: double-click → inline `text-field`, Enter commits `:timeline/apply-op {:op :rename-track}`
  - zone chip: shows the zone name, dimmed and italic when inherited; click opens the zone context menu
  - mute toggle "M"
- Click → `:timeline/select-track` (raw id, Phase 2 per-cue state). Right-click → context menu: Rename, Colour ▸ palette, Zone ▸, Add track inside (folders), Delete.
- Drag reorder via `list_dnd` (`:108,146,225`) with component-id `[:timeline-tracks col row]` and items-path `[:chains :cue-chains [col row] :tracks]`. The drop goes through `:list/perform-drop` → `:timeline/update-tracks` (Phase 1 behaviour).
- Each row gets a stable JavaFX `:id` (`"timeline-track-header-<index>"`) for TestFX.

### 4.4 `laser-show.events.handlers.timeline` (existing)
- Delete:
  - `scroll-panes`, `run-on-fx!`, `handle-register-scroll-pane`, `handle-sync-scroll` (`timeline.clj:73-102`)
  - `handle-set-hvalue`, `handle-zoom-at-cursor`, `handle-set-zoom` (`:60-64,104-178`) and their `case` entries
  - the `:zoom`/`:scroll-hvalue` keys from `[:ui :timeline-editor …]`

  Viewport state is view-local now. This closes 02 #19 / M4 for the timeline: no JavaFX in timeline handlers.
- `:timeline/set-cursor-beat` stays (cursor is per-cue UI state).

### 4.5 `laser-show.css.canvas` (NEW, `src/laser_show/css/canvas.clj`; proposed by 01 L1)
- Pre-built `javafx.scene.paint.Color` and `Font` constants derived from `theme/base-colors` / `semantic-colors` (`css/theme.clj:32-92`). The timeline keys and values are in `05` §3.1.
- `with-alpha [Color a]`, memoized in a `ConcurrentHashMap` for per-track colours (01 Q10).

### 4.6 `laser-show.css.timeline` (NEW, `src/laser_show/css/timeline.clj`) and `laser-show.css.core` (existing)
- Style classes for the toolbar, header rows, chips, inspector and content browser (`05` §3.5-§3.9).
- Registered with the other component stylesheets in `css/core.clj` so dialogs pick them up through `css/dialog-stylesheet-urls`.

### 4.7 `laser-show.views.dialogs.timeline-cue-editor` (existing)
- The header strip (`:126-141`) merges into the toolbar row: the name sits at the left of the toolbar (`05` §3.7).
- The footer keeps the zone and trigger-mode pickers and Close (`05` §3.10).

### 4.8 `laser-show.views.components.visual-editors.timeline.track-logic` (existing) → **deleted**
Its tests move to `test/laser_show/timeline/layout_test.clj`.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| Viewport `{:zoom :scroll-beat :scroll-y :width :height}`, `:hover`, `:follow?` | **view-local** `fx/ext-state` (FX thread) | ScrollBar / wheel / size listeners | No |
| `[:ui :timeline-editor [col row] :zoom/:scroll-hvalue]` | **deleted** | — | — |
| `[:ui :timeline-editor [col row] :selected-track/:cursor/:selection/:renaming-track]` | UI state | timeline handlers | No |
| `[:chains :cue-chains k :tracks * :color]` | document | `:timeline/apply-op :set-track-color`, `create-track` | Yes |
| `[:chains :cue-chains k :tracks * :muted?/:collapsed?]` | document, live fields (D10) | header "M" button / chevron → `:timeline/apply-op` | **No / No** (still saved) |

Remembering the viewport per cue across dialog opens is out of scope (§9).

## 6. Events and effects

| Event / effect key | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:timeline/register-scroll-pane`, `sync-scroll`, `set-hvalue`, `zoom-at-cursor`, `set-zoom` | yes → **deleted** | — | — |
| `:timeline/select-track` | yes (`timeline.clj:640`) | `header.clj` row click (NEW dispatcher) | yes |
| `:timeline/apply-op` (`:rename-track`, `:set-track-color`, `:set-track-muted`, `:set-collapsed`, `:set-track-zone`) | Phase 2 | `header.clj` | yes |
| `:list/perform-drop` | yes | `list_dnd.clj` from header rows | yes |

## 7. Data examples and invariants

### 7.1 `layout/rows`
Metrics as above. Tracks: `f` (folder, expanded) > `t1`, `t2`; `t3` top level.

| Input | `[:id :kind :y :h]` |
|---|---|
| expanded | `[:f :folder 0 26] [:t1 :track 27 36] [:t2 :track 64 36] [:t3 :track 101 36]` |
| `f` collapsed | `[:f :folder 0 26 + summary clips of t1, t2] [:t3 :track 27 36]` |

### 7.2 Viewport maths (width 1000 px)

| Call | View before | View after |
|---|---|---|
| `zoom-at` focal-x 500, factor 2 | zoom 50, scroll-beat 10 (beat at x500 = 20) | zoom 100, scroll-beat 15 (beat 20 still at x500) |
| `zoom-at` factor 1000 | zoom 50 | zoom 400 (clamped) |
| `scroll-by` dx −300 | zoom 50, scroll-beat 2 | scroll-beat 0 (clamped) |
| `visible-beat-range` | zoom 2, scroll-beat 0 | [0, 500] beats (long shows fit; no width cap) |

**Invariants:**
1. For every row, header y = lane y = `(:y row) − scroll-y` (+ ruler height for lanes).
2. `x->beat ∘ beat->x` = identity.
3. `zoom-at` keeps the focal beat at the focal x unless it's clamped by scroll bounds.
4. Drawing cost depends on the visible range, not the content length.

## 8. Tests (named test vars + fixtures)

- `laser-show.timeline.layout-test` (NEW): `rows-examples-test` (§7.1), `collapsed-folder-summarizes-children-test`, `zoom-at-keeps-focal-beat-test`, `scroll-clamp-test`, `ruler-ticks-levels-test` (bar/beat/sub per zoom band), `hit-test-topmost-first-test`, `hit-test-loop-band-only-test`, `beat-x-roundtrip-test` (seeded random), plus the moved `track_logic_test` geometry cases.
- TestFX (`laser-show.ui-test.e2e-test`, extend):
  - `timeline-headers-align-with-lanes`: after adding 6 tracks and scrolling by 100 px, the `boundsInScene` minY of `#timeline-track-header-<i>` equals the lane y computed from `layout/rows` + canvas `localToScene`, within 1 px.
  - `timeline-scrollbar-does-not-snap-back`: drag the horizontal ScrollBar thumb, `ui/sync-fx!` ×3, assert the value is unchanged.
  - `timeline-long-show-reachable`: add a clip at beat 600, use `fit`, assert the clip is hit-testable (`ui/click!` at its computed position selects it).
  - `timeline-screenshot-baseline` (NEW, opt-in via `^:visual` metadata, excluded by default): writes `ui/screenshot!` PNGs to `test-output/timeline/` for manual or LLM review against `05` §4. Uses the existing `.env` LLM vision setup described in `deps.edn:189-191`.

## 9. Out of scope / alternatives considered

- **Out of scope:** remembering the viewport per cue, a minimap/overview strip, vertical zoom (row height), dragging presets from the browser onto lanes (click-to-add stays; see `05` §3.9).
- **Alternatives considered:**
  - **Keep the ScrollPanes and fix the sync.** Rejected: the 8192 px cap (TL-18) needs a virtual canvas anyway, and two ScrollPanes can't share a pixel-exact vertical offset without JavaFX reads on every scroll.
  - **Viewport in app state through events.** Rejected: that reintroduces the async round trip that causes TL-15. `ext-state` is synchronous to the control and keeps handlers JavaFX-free.
  - **Draw the header column on the canvas too.** Rejected: rename text fields, context menus, DnD and accessibility are much easier as real nodes. Alignment comes from the shared `layout/rows`, not from sharing a canvas.
  - **Three canvas layers (grid / clips / overlay).** Rejected for now: two layers already move the 30 Hz work to the overlay. Splitting the grid out only helps when clips change often without view changes. Revisit if JFR shows lane redraw cost.
