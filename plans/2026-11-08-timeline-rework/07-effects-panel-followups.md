# Effects panel follow-ups: drag-and-drop, effect groups, panel size

**Written:** 2026-11-11   **Written for commit:** `6edef3a` (branch `timeline/effects-panel`)
**Status:** Proposed. Pick an option per limitation.   **Related:** `05-visual-design.md` (layout note, 2026-11-11)

The Ableton-style detail panel and browser shipped with three known limitations. This file explains each one and gives resolution options with rough effort. One related bug found while researching is listed at the end.

---

## L1. Effects and presets can only be added by clicking

### What happens today
- The browser buttons are plain buttons with `:on-action` (`tabbed_bank.clj:35-52`). A click adds a preset as a clip at the cursor on the insert track, or adds an effect to the selected clip, track or folder.
- You can't drag a preset to a place in time, or an effect onto a particular clip, track or position in the chain. You select first, then click.
- Effect cards are reordered with ◀ ▶ buttons, not by dragging.

### Why
- No drag source exists on bank buttons.
- The timeline canvas (`canvas_interaction.clj:159-305`) handles mouse gestures only, not JavaFX drag-and-drop (`:on-drag-over` / `:on-drag-dropped`).

### What can be reused
- **`drag_drop_cell.clj`** (`:31-123`): the app's DnD helper. It puts an EDN payload on the dragboard under an app-private `DataFormat`, which is safer than `list_dnd`'s plain string, because that one accepts text dragged in from other apps.
- **`layout/hit-test`** (`layout.clj:325`) already maps canvas x/y to `{:type :lane :row :beat}` or `{:type :clip ...}`. That's exactly the drop-target lookup needed.
- **`list_dnd.clj`** works out before/into/after from the y position. The same idea works for the x position between cards.

### Options
| | What | Effort | Notes |
|---|---|---|---|
| **A** (recommended) | **Full drag from the browser.** Drag a preset into the lanes to add a clip at the drop beat (snapped) on the drop track. Drag an effect onto a clip, a track header or the device chain to add it there. Show a ghost clip / insertion line while dragging. | ~1.5–2 days | NEW `:on-drag-*` pass-through props on `interactive-canvas`. NEW payload `{:laser-show/bank-drag true :kind :preset/:effect :id}`. Events gain `:start`/`:track-id`, plus an `:index` for effects. Clicking keeps working. |
| **B** | **Drag inside the panel only.** Drop effects onto the device chain at a position, and reorder cards by dragging (this replaces ◀ ▶). | ~1 day | `:move-effect` already exists; only the view changes. Doesn't help with placing presets. |
| **C** | **"Arm then place."** Click a preset, then click in a lane to place it (Esc cancels). | ~0.5 day | Not real drag. Cheap, and works well on trackpads. |

A covers B's effect drop. Card reordering by drag (from B) can be added to A later.

### Tests (any option)
- **Pure:** a drop-target function (hit → op) for lanes, clips, headers and chain positions.
- **UI:** TestFX `drag(...).dropTo(...)` from a bank button to `#timeline-canvas` and `#timeline-device-chain`. Headless DnD in TestFX can be unreliable; if it is, test the drop handlers directly.

---

## L2. Effect groups show as a plain card

### What happens today
- An effect group (`{:type :group :items [...]}` inside an `:effects` vector) shows as a card titled "Group (n)", with on/off, move and remove only. Its effects' parameters can't be edited in the timeline editor; the card says to use the Cue Chain Editor.

### Why
- The cards are one level deep: each card is one top-level effect with one `effect-parameter-editor`.
- Groups only reach timelines through **Convert to Timeline Cue**, because the Cue Chain Editor's effect list allows grouping (`list.clj`, `:allow-groups? true`). The timeline editor has no way to create them.
- Rendering is fine. `effects/apply-effect-chain` flattens enabled groups in order (`chains/flatten-chain`, `chains.clj:67-88`), and a disabled group switches off everything in it. Only editing is missing.

### Options
| | What | Effort | Notes |
|---|---|---|---|
| **A** | **Racks in the device chain.** A group becomes a framed card holding its children's cards (like Ableton's Audio Effect Rack), with group on/off, rename and ungroup. Nested up to the existing 3-level limit. | ~1.5 days | Card paths become nested (`[g :items i]`). `effect-parameter-editor` already takes any effect path. The model ops need to find effects at any depth (`update-items-by-id` is already deep; `move-effect` is top-level only). |
| **B** | **Structure list + cards.** A compact `list/list-editor` of the effects sits at the left of the device row, with nesting, drag reorder, group/ungroup (Ctrl+G), copy/paste and enable. The cards show the selected effects' parameters. | ~1 day | Reuses the mature list component, the same one the Cue Chain Editor uses. Clips can use `:cue-chain/set-item-effects` (`cue_chain.clj:145-152`); tracks need a small NEW handler. This also gives drag reordering for free, which overlaps with L1-B. |
| **C** (cheapest) | **Ungroup button.** The group card gets "Ungroup", which splices its children into the chain. If the group was off, its children are switched off. | ~0.5 day | Grouping is lost; fine if groups are only a conversion leftover. |

**Recommendation:** C now if groups are rare in your timelines. B if you want grouping as a timeline feature; it also covers drag reordering. A gives the most Ableton-like result but costs the most.

---

## L3. The panel height resets when the editor reopens

### What happens today
- Drag-resizing the bottom panel works, but closing and reopening the timeline editor puts it back to 300 px. Zoom, scroll and "follow playhead" reset as well.

### Why
- The height (`:detail-h`) lives in the editor's local `fx/ext-state` (`timeline_editor.clj:889, 911`). The dialog is removed from the scene graph when it closes (`root.clj:182-184`), so that state is discarded.
- Separately, **nothing in the app is saved across restarts except the project file.** There's no app-settings file. `[:ui :timeline-prefs]` (snap, ruler mode, browser and panel open) lasts only for the session (`persistent.clj:33-61`, `app.clj:135`).

### Options
| | What | Effort | Notes |
|---|---|---|---|
| **A** (recommended now) | **Keep it for the session.** Move `:detail-h` into `[:ui :timeline-prefs]`, next to the browser and panel toggles. | ~1 hour | Preview the drag locally and dispatch one event on release, not one per mouse move. Prefs are UI state, so there's no undo step and nothing becomes unsaved. |
| **B** | **Remember across restarts.** Option A plus a small app-settings file (e.g. `~/.laser-show/settings.edn`). It's loaded at startup and saved, debounced, when a pref changes. It could also hold snap, ruler mode and the panel layout. | ~1 day | New infrastructure. `serialization/save-to-file!` / `load-from-file` exist but aren't used yet. Decide which prefs belong in it. |
| **C** | **Save per project** under `:config`. | ~1 hour | It's saved automatically with the project, but a window size isn't really project data. Not recommended. |

---

## Related bug found: zoom and scroll don't reset when the editor shows another cell

- The `timeline-editor` docstring says the viewport resets when the editor switches cell, but nothing keys the local state on the cell: `:cell` is stored in `:initial-state` and never compared.
- If the open editor is pointed at another cell, it keeps the previous cue's zoom, scroll and panel height.
- **Fix:** put `:fx/key [col row]` on the `ext-state` (~15 min, plus a UI test).
- If L3-A/B is done, the panel height lives in prefs, so it correctly survives a cell switch while zoom and scroll reset.

---

## Suggested order
1. Cell-switch fix + L3-A (half a day together, low risk).
2. L1-A (drag from the browser): the biggest workflow gain.
3. L2: C if groups are rare; B if grouping should be a timeline feature (it also covers card reordering).
4. L3-B if you want layouts remembered across restarts.
