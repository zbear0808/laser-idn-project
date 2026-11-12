# Phase 1 — Correctness fixes

**Written:** 2026-11-08   **Written for commit:** `d0a7dc2`
**Status:** Done (2026-11-11, branch `timeline/phase1-correctness`, commits `c7a57ff`…`707c82a`)   **Last verified against:** `707c82a`
**Supersedes / Superseded by:** none   **Related findings:** TL-1…TL-5, TL-7…TL-12, TL-15, TL-17, TL-19 (reset), TL-20…TL-23, TL-26 (cursor), TL-29, TL-30, V-2…V-5, V-9, V-14 (label, size), V-15 (see `00-overview.md` §3, `05-visual-design.md` §2); 01 F9, F10; 02 #20 (partly)

> **How the implementation differs from this plan:**
> - **Conversion is an undoable edit** ("Convert to Timeline Cue"), not `:history/skip?` + `:document/rebase?`. The undo plan that landed replaced those keys with `:history/backfill` and made conversion undoable (`2026-11-08-undo-history-boundary.md` §decisions row 4). Opening a timeline cue that somehow skipped normalization normalizes it as a backfill.
> - **Undo/redo results aren't re-normalized** by `wrap-normalize-timelines`. They restore normalized snapshots, and re-normalizing could mint a new track id that differs from the saved baseline.
> - **`:muted?` is a live field** via `live-item-paths`, and `live-item-keys` in `state/history.clj` is now derived from that list. Before, it was a separate hard-coded `#{:collapsed?}`, so adding a path to `live-item-paths` alone had no effect.
> - **Loop window wrap (part of TL-25):** `generate-frames-by-zone` wraps the window position into an enabled loop. Without it, a converted cue went dark for up to a beat at each 16-beat wrap because of the phase offset. Removing the phase glide for timeline cues stays in Phase 4.
> - **Top-level tracks** get a "Cue Default Zone (X)" inherit option in the inspector, so a track with no zone doesn't show the first zone group as its value.
> - **Mute only filters top-level clips** in `generate-frames-by-zone`, the path both preview and hardware use. `in-timeline-window?` and the older `render-item-with-effects` path are unchanged.
> - **Visuals:** overlaps get a translucent band, not the 45° hatch. There's no 2 px top band or luminance-based label colour yet. Clips have a 1 px dark border, selected clips are drawn on top with a white outline, and labels are clipped. These visual items move to Phase 3 (`05` §3.4).
> - **TL-30** has no automated test; the drag callback is inside the component. The real fix is stable keyframe IDs (§9).

Each item below is independent and can ship alone. Every item starts with a failing test (§8) that shows the current behaviour.

## 1. Current behaviour (file:line)

| ID | What happens now |
|---|---|
| TL-1 | `handle-open-editor` (`events/handlers/timeline.clj:583-614`) runs on every open. For a cue without timing it sets `:timeline/start 0.0` / `:timeline/duration 4.0` on every item (`:576-581`, `:602`), auto-creates tracks (`track_logic.clj:240-300`) and sets `:editor-type :timeline` (`:607`). The loop defaults to off in playback (`frame_service.clj:113`), so the cue renders for 4 beats and then nothing, while staying "active". Nothing resets `:editor-type`, so `cue-chains/timeline-cue?` (`cue_chains.clj:58-74`) stays true and double-click (`grid_cell.clj:230-231`, `grid.clj:205-210`) keeps opening the timeline. Dispatchers: `cue_chain_editor.clj:626` ("Open in Timeline Editor"), `grid_cell.clj:252` ("Create Timeline Cue", empty cells), `grid.clj:210`. |
| TL-2 | The editor defaults missing timing to 0 / 4 in `draw-clip!` (`timeline_editor.clj:275-276`), `hit-test` (`:376-377`), `on-press` (`:431-432`) and `calculate-canvas-width` (`track_logic.clj:313-314`). The renderer treats no keys as always on and no duration as infinite (`frame_service.clj:266-283`). `timeline-ready?` (`timeline.clj:570-574`) is `some`, so once one item has `:timeline/start` the rest are never filled in (`:602`). `:cue-chain/add-preset` (`cue_chain.clj:74-92`) and `:chain/set-items` (`chain/core.clj:31-36`, used by list paste/group/drop) add items with no timing and no `:track-id`. |
| TL-3 | Track rows show an enable checkbox (`list.clj:410-413` folders, `:483-486` tracks) that writes `:enabled?` on the track via `:list/set-enabled` → `:timeline/update-tracks` (`timeline.clj:369-377`). Routing (`zone_effects.clj:102-125`) and rendering (`frame_service.clj:394-464`) never read it, and the toggle is undoable. Disabled clips are drawn like enabled ones (`timeline_editor.clj:271-302`), and the inspector looks clips up with `chains/flatten-chain` (`:793`), which skips disabled items, so they can't be inspected. |
| TL-4 | Deleting a track replaces `:tracks` wholesale (`timeline.clj:369-377`). Clips keep a dangling `:track-id`, fall into "Unassigned" (`timeline_editor.clj:105-111`), and route to the chain default zone (`zone_effects.clj:146-156`). |
| TL-5 | The sidebar's `handler-props` takes `:clipboard-type` from `:on-change-params` (`list.clj:853`). The timeline passes `{:col :row}` (`timeline_editor.clj:780`), so tracks use `:cue-chain-items` (`handlers/list.clj:241-243`, `:251-256`). |
| TL-7 | `handle-add-track` / `handle-add-folder` stamp the chain's default zone (`timeline.clj:440-442`, `:458-460`), so a track moved into a folder never inherits. |
| TL-8 | The zoom slider sends `{:event/type :timeline/set-zoom :zoom (or zoom-x default-zoom)}` (`timeline_editor.clj:687-688`). `handle-set-zoom` reads `:zoom` (`timeline.clj:60-64`), which is the render-time value, so dragging the slider changes nothing. |
| TL-9 | The snap checkbox sends `:enabled? (not snap-enabled?)` (`timeline_editor.clj:664-665`). |
| TL-10 | The left-edge drag sends `:start new-start :duration (original − delta)` (`timeline_editor.clj:494-501`). `handle-update-item-timing` clamps start ≥ 0 and duration ≥ 1/16 separately and snaps each separately (`timeline.clj:268-292`). Trimming past 0 lengthens the clip; trimming past the end leaves a 1/16-beat sliver that slides right. The loop brace does the same (`timeline_editor.clj:467-474`, `timeline.clj:235-252`). `handle-resize-selection` (`timeline.clj:317-354`) has correct math but no dispatcher. |
| TL-11 | `items-by-track` sorts by start (`track_logic.clj:104-111`). Clips draw in that order (`timeline_editor.clj:334-340`), but `hit-test` takes the first match (`:375`), i.e. the bottom-most. Overlaps are invisible (screenshot `img/before-02-content.png`, "circle" 0–4 and "star" 2–6). |
| TL-12 | `hit-test` treats the whole ruler (`y ≤ 24`) as the loop brace inside its x-range (`timeline_editor.clj:356-367`). The dialog always passes a loop config (`timeline_cue_editor.clj:154`), so the brace is always hit-testable. `handle-update-loop-timing` defaults a missing loop to `:enabled? true` (`timeline.clj:242`) while `handle-toggle-loop` defaults to `false` (`:258`). |
| TL-15 | `handle-set-hvalue` (`timeline.clj:104-111`) runs on the agent and, if the live ScrollPane differs, pushes the event's (now stale) value back with `run-on-fx!` (`:83-91`). That runs outside cljfx's `*in-progress?*` guard, so it fires `:on-hvalue-changed` again. The `:hvalue` and `:vvalue` props (`timeline_editor.clj:1134`, `:1160`, `:1162`) re-apply lagging state on every render. |
| TL-17 | `build-tracks` collects clips on tracks hidden by a collapsed folder into "Unassigned" (`timeline_editor.clj:105-111`). |
| TL-19 | `[:ui :timeline :selection]`, `:cursor-beat`, `:scroll-hvalue` and `[:list-ui :timeline-tracks]` survive across cells. |
| TL-20 | Dead: `:timeline/scroll` (`timeline.clj:66-71`), `:timeline/scroll-horizontal` (`:114-135`, test-only), `:timeline/resize-selection` (`:317-354`), `:timeline/toggle-track-expand` (`:356-367`) and the `expanded-tracks` branch it feeds (`track_logic.clj:89-91`), `:timeline/init-tracks` (`timeline.clj:392-403`), `:scroll-x` (`timeline_editor.clj:1087-1093`, `:1172`), `:list-props` (`timeline_cue_editor.clj:155-165`, `timeline_editor.clj:1086`), `tl/track-index`, `track-zone-id`, `add-track`, `remove-track`, `update-track`, `move-track` (`track_logic.clj:59-64,113-161`). |
| TL-21 | `selected-track-id` is `tl/resolve-active-track` of the sidebar selection (`timeline_editor.clj:1101-1105`). That resolves a folder to its first child and an empty selection to the first leaf (`track_logic.clj:183-219`). The inspector and the lane highlight both get the resolved id, so a folder can never be inspected, and the first track looks selected on open (`img/before-01-empty.png`). |
| TL-22 | The track inspector finds the parent with `(last (:path selected-track))` (`timeline_editor.clj:891`). Tracks have no `:path`, so there's no "Inherit" option and the value falls back to the first zone group (`:901`). |
| TL-23 | The header commits with `:cue-chain/set-name` (`timeline_cue_editor.clj:136`), which clears `[:ui :dialogs :cue-chain-editor :editing-name?]` (`cue_chain.clj:226`), not the timeline dialog's flag. |
| TL-26 | `playhead-beat (or beats-elapsed cursor-beat 0.0)` (`timeline_editor.clj:325`): the edit cursor is drawn as the red playhead when stopped and is invisible while playing. |
| TL-29 | `keyframe_timeline.clj:277` and `curve_canvas.clj:219` read `:hover-idx`; `interactive-canvas` stores `:hover-id` (`canvas_interaction.clj:225`). |
| TL-30 | The keyframe drag keeps `:drag-id hit-idx` (`keyframe_timeline.clj:302`) and moves `(:drag-id drag-info)` (`:318`). `handle-move-keyframe` re-sorts (`keyframe.clj:265-268`), so after crossing a neighbour the next drag event moves the neighbour. |

## 2. Root cause

These are symptoms of root causes 1, 2 and 6 in `00-overview.md` §2. There's no single owner of the timeline invariants, controls read values captured at render time instead of `:fx/event`, handlers write back into JavaFX controls, and selection conflates "selected" with "where new content goes". Phase 1 patches each symptom with the smallest change that also points toward the Phase 2 model. The pure helpers added here are written so they can move unchanged into `laser-show.timeline.model` (`02`).

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `cue-chains/timeline-cue?` (`cue_chains.clj:58-74`) | Decides which cues are normalized and whether opening converts |
| `tl/auto-initialize-tracks` (`track_logic.clj:240-300`) | Track creation during conversion |
| `tl/ensure-leaf-track`, `tl/first-leaf-track` (`track_logic.clj:168-194`) | Insert target and normalization's default track |
| `tl/flatten-visible-tracks` (`track_logic.clj:73-96`) | Kept; returns hidden-track → visible-ancestor mapping (§4.11) |
| Math in `handle-resize-selection` (`timeline.clj:317-354`) | Moved into `tl/trim-start` / `tl/trim-end`, then the handler is deleted |
| `chains/find-path-by-id` (`chains.clj:106-135`) | Parent lookup for tracks (`tl/parent-track`) |
| `list-helpers/find-by-id` (`handlers/list/helpers.clj:42`) | Inspector clip lookup that includes disabled clips |
| `list-helpers/remove-by-ids` (`handlers/list/helpers.clj:122`) | Removing clips of deleted tracks |
| `h/first-some` (`events/helpers.clj:102-106`) | Reading `:fx/event` without turning `false` into `nil` |
| `ui/slide!`, `ui/press!`, `ui/click!`, `ui/screenshot!` (`test/laser_show/ui_test/testfx.clj:297,313,168,370`) | TestFX acceptance tests |
| `clipboard-types` (`state/clipboard.clj:40-42`) | Extended with `:timeline-tracks` |
| Undo plan meta keys `:history/skip?` / `:document/rebase?` (working tree, `events/handlers/history.clj:180-236`) | Conversion keeps them |

## 4. Changes per namespace

### 4.1 `laser-show.views.components.visual-editors.timeline.track-logic` (existing)
All NEW functions are pure and take plain data. Phase 2 moves them to `laser-show.timeline.model`.

- NEW constants: `default-clip-length` = `4.0`, `conversion-length` = `16.0`, `min-clip-length` = `0.0625` (moved from `timeline.clj:33`).
- NEW `clip-start [item]`, `clip-length [item]`, `clip-end [item]`: the only readers of `:timeline/start` / `:timeline/duration` in views and handlers. They return `nil` when missing (no defaults). After normalization they never see missing values in a timeline cue.
- NEW `normalize-timeline-cue [cue-chain] → cue-chain`: rules in §7.1. It returns the **identical** map when nothing changes.
- NEW `normalize-timeline-cues [cue-chains-map] → cue-chains-map`: maps the above over the timeline cues only; identical when nothing changes.
- NEW `convert-to-timeline [cue-chain zone-groups] → cue-chain`: rules in §7.2.
- NEW `snap [beats {:keys [enabled? value]}]`: replaces the private `snap-value` (`timeline.clj:39-46`).
- NEW `move-clip [item delta snap-opts]`, `trim-start [item new-start snap-opts]`, `trim-end [item new-end snap-opts]`: examples and invariants in §7.3. `move-clip` also shifts every nested `:timeline/start` by the same snapped delta (decision D5).
- NEW `trim-loop-start`, `trim-loop-end`, `move-loop [loop … snap-opts]`: same rules applied to `{:start :duration}`.
- NEW `parent-track [tracks track-id] → folder | nil`: uses `chains/find-path-by-id`, drops the last two path segments.
- NEW `muted-track-ids [tracks] → #{id}`: tracks with `:muted? true`, plus every descendant of a folder with `:muted? true` (D10).
- NEW `leaf-track-ids [tracks] → #{id}`.
- `flatten-visible-tracks` (`:73-96`): delete the `expanded-tracks` argument and branch (`:89-91`). Return rows with NEW key `:hidden-descendants` (set of track ids hidden under a collapsed folder).
- Delete unused `track-index`, `track-zone-id`, `add-track`, `remove-track`, `update-track`, `move-track` (`:59-64`, `:113-161`).
- `create-track` (`:26-44`): `:zone-group-id` is only set when passed (no `:all` default), and NEW `:color` defaults to the next palette colour (`05` §3.2). Delete the never-read `:visible?` (`:42`).

### 4.2 `laser-show.events.handlers.timeline` (existing)
- NEW public `wrap-normalize-timelines [handler]`. After `handler` returns, if `[:state :chains :cue-chains]` is not `identical?` to the event's, it replaces it with `tl/normalize-timeline-cues`. Cost for events that don't touch cue chains: one `identical?` check. Wired in `events/core.clj` (§4.6).
- `handle-open-editor` (`:583-614`), rewritten:
  - If `timeline-cue?`: no document change. Returns `:ui/open-dialog` and, when `[col row]` differs from `[:ui :dialogs :timeline-cue-editor :col/:row]`, resets `[:ui :timeline :selection]` to `#{}`, `:cursor-beat` to `0.0`, `:scroll-hvalue` to `0.0`, and `[:list-ui :timeline-tracks]` to `{}` (TL-19).
  - Otherwise: `tl/convert-to-timeline`, with `:history/skip? true :document/rebase? true` (as the in-progress undo plan already does in the working tree), then `:ui/open-dialog`.
  - Delete `timeline-ready?` and `ensure-item-timeline-fields` (`:570-581`).
- `handle-set-zoom` (`:60-64`): zoom = `(h/first-some zoom (:fx/event event))`.
- `handle-set-snap` (`:180-193`): enabled = `(h/first-some enabled? (when (boolean? (:fx/event event)) (:fx/event event)) current)`. The combo keeps sending `:value`.
- `handle-set-hvalue` (`:104-111`): only stores the clamped value. Delete the `.getHvalue` / `run-on-fx!` echo (`:108-110`).
- `handle-update-item-timing` (`:268-292`): NEW required `:edge` (`:left` | `:right` | `:center`). `:center` → `tl/move-clip` (delta = `start − current start`), `:left` → `tl/trim-start`, `:right` → `tl/trim-end` (given `:end`). Dispatchers: `timeline_editor.clj:487-501` (all three updated).
- `handle-update-loop-timing` (`:235-252`): same `:edge` contract; a missing loop defaults to `:enabled? false`. Dispatchers: `timeline_editor.clj:460-474`.
- `handle-nudge-selection` (`:294-315`): uses `tl/move-clip` (also shifts nested windows).
- `handle-update-tracks` (`:369-377`): computes `removed = (leaf-track-ids old) − (leaf-track-ids new)` and removes clips whose `:track-id ∈ removed`, in the same `:state` (D4).
- NEW `:timeline/set-track-muted {:col :row :track-id :muted?}`: sets or clears `:muted?` on a track or folder. The undo plan's policy (`live-item-paths`, §4.12) keeps this out of undo and out of the unsaved flag, so the handler needs no history keys.
- `handle-add-track` / `handle-add-folder` (`:435-469`): don't stamp a zone (TL-7). A new track inside nothing (top level) is created with no `:zone-group-id`, which routes to the chain default zone, i.e. the same output as today.
- Delete `handle-scroll`, `handle-scroll-horizontal`, `handle-resize-selection`, `handle-toggle-track-expand`, `handle-init-tracks` and their `case` entries (`:628-629,646,653,658`). Remove the `handle-scroll-horizontal-test` (`timeline_handlers_test.clj:140-146`).

### 4.3 `laser-show.views.components.visual-editors.timeline-editor` (existing)
- Toolbar (`:618-689`): zoom slider sends `{:event/type :timeline/set-zoom}` (`:687-688`), snap checkbox sends `{:event/type :timeline/set-snap}` (`:664-665`). Loop toggle reads `(:enabled? loop-config false)` (`:655`).
- ScrollPanes (`:1121-1178`): delete the `:hvalue` and both `:vvalue` props (`:1134`, `:1160`, `:1162`). The controls are then one-way (control → state). `scroll-filter` (`:555-610`) logs instead of swallowing throwables (01 F10).
- `build-tracks` (`:87-132`): clips on hidden tracks go to their visible folder row with `:summary? true` instead of "Unassigned" (TL-17). The legacy one-row-per-item branch (`:121-132`) stays for cues without tracks (only reachable before normalization).
- `draw-clip!` (`:271-302`): read timing via `tl/clip-*`. Dim (35 % alpha) clips that are disabled or on `muted-track-ids`. Clip label = preset display name (`presets/presets-by-id`), clipped to the clip rect. 1 px darker border so adjacent clips separate. Hatch the overlapping span of overlapping clips (V-3). Draw the selected clip last.
- Visual quick wins (`05` §5):
  - clip colour = track `:color`, falling back to the palette by row index (`05` §3.2)
  - clip inset, top band and label colour per `05` §3.4
  - selected clip = white outline (no gold); selected lane = tint only (V-4)
  - lift every `Color/web` / `Font.` out of draw functions into `def`s (`:65-79,192,238,290`)
  - pixel-snap 1 px lines (V-15)
- `render-timeline!` (`:315-342`): draw the playhead only when `beats-elapsed` is non-nil, and draw the cursor separately as a dashed amber line (`05` §3.6).
- `hit-test` (`:350-390`): iterate clips in reverse draw order, i.e. selected clip first, then by descending start (TL-11). The loop brace is only hit inside its drawn band `y ∈ [ruler-height − loop-brace-height − 2, ruler-height − 2]` (TL-12).
- `on-drag` (`:449-518`): send `:edge` and absolute targets: `:start` for center/left, `:end` for right.
- Inspector (`:788-952`): the clip lookup uses `list-helpers/find-by-id` (includes disabled clips). The track inspector receives the **raw** sidebar selection (`sidebar-selected-track-id`, `:1102`) and shows Folder Properties for folders. The parent comes from `tl/parent-track`; the "Inherit from Folder (X)" option shows when the parent exists, and the current value is `:inherit` when the track has no `:zone-group-id`.
- The lane highlight (`draw-track-backgrounds!`, `:248-268`) uses the raw selection only. The insert target is still `tl/resolve-active-track` (unchanged) and is drawn as a 3 px left-edge marker on that lane (`05` §3.5), not a full highlight.
- `timeline-sidebar` (`:757-785`): `:on-change-params {:col col :row row :clipboard-type :timeline-tracks}`, and NEW `:show-enable-toggle? false` (§4.11), so the generic checkbox no longer looks like a mute.
- `track-context-menu` (`:722-754`): NEW first item "Mute" / "Unmute" (from `:muted?`) → `:timeline/set-track-muted`. Phase 3 replaces this with the header "M" button (`05` §3.5).
- Delete `:scroll-x` plumbing (`:1087-1093`, `:1172`, `:319`, the `scroll-x` args of the draw fns) and the `:list-props` prop (`:1086`).

### 4.4 `laser-show.views.dialogs.timeline-cue-editor` (existing)
- `:on-commit` (`:136-138`) adds `:dialog-id :timeline-cue-editor`.
- Delete `:list-props` (`:155-165`) and the unused `clipboard-items` sub (`:113`).
- Footer label "Default Zone (Chain Fallback):" (`:35`) becomes "Default zone:".
- Stage size: pref 1280×760, min 960×560 (`:122-123`; `05` §3.10).

### 4.5 `laser-show.events.handlers.cue-chain` (existing)
- `handle-cue-chain-set-name` (`cue_chain.clj:219-227`): NEW optional `:dialog-id` (default `:cue-chain-editor`) selects which dialog's `:editing-name?` to clear.

### 4.6 `laser-show.events.core` (existing)
- `handle-event-pipeline` (`events/core.clj:368-370`): `(-> handlers/handle-event timeline/wrap-normalize-timelines history-handlers/wrap-history …)`. It's innermost, so undo snapshots and dirty derivation see normalized state. This keeps the working tree's `wrap-dirty` outermost.

### 4.7 `laser-show.state.persistent` (existing)
- `apply-loaded-project` (`persistent.clj:157-166`): applies `tl/normalize-timeline-cues` to `[:chains :cue-chains]` **before** the undo plan's `history/mark-clean`. That way, loading an old project isn't immediately "unsaved". The middleware then finds nothing to change.

### 4.8 `laser-show.services.frame-service` (existing)
- `generate-frames-by-zone` (`frame_service.clj:483-535`): compute `muted (tl/muted-track-ids tracks)` once per cue per call. `render-item-tree-by-zone` (`:394-464`) gets it as a new argument and returns `{}` for a top-level item whose `:track-id ∈ muted`. Because `multi_engine.clj:174` goes through `generate-frames-by-zone-cached` → `generate-frames-by-zone`, this covers both preview and hardware. The cost is one tree walk per cue per frame (*unverified*; tracks are typically < 50). It runs on the `preview-update` timer (30 Hz) and each engine thread (60 FPS).
- This namespace now depends on `track-logic`. That's acceptable for Phase 1; Phase 2 moves it to `laser-show.timeline.model` (02 #20).

### 4.9 `laser-show.events.handlers.list` (existing) and `laser-show.state.clipboard` (existing)
- `clipboard-types` (`clipboard.clj:40-42`): add `:timeline-tracks`. NEW `copy-timeline-tracks!` mirrors `copy-cue-chain-items!` (`:274-290`).
- `handle-copy-selected` / `handle-paste-items` (`handlers/list.clj:232-274`): add the `:timeline-tracks` case. Pasting tracks never carries clips.

### 4.10 Keyframe strip (existing)
- `keyframe_timeline.clj:277` and `curve_canvas.clj:219`: read `:hover-id`.
- `keyframe_timeline.clj:316-324` (`on-drag`): compute the moved keyframe's index after the same `sort-by :position` that `keyframe.clj:70-73` uses, and return it as `:drag-id`. This removes the swap in the normal case. Until Phase 2 makes drag state synchronous, a lagging render can still mis-index; the real fix is stable keyframe IDs (out of scope, §9).

### 4.11 `laser-show.views.components.list` (existing)
- `list-editor` (`list.clj:789-892`): NEW prop `:show-enable-toggle?` (default `true`, so other lists are unchanged). It's passed through `list-sidebar` (`:577`) to `group-header` (`:375`) and `list-item-card` (`:456`), which omit their `:check-box` (`:410-413`, `:483-486`) when it's false.

### 4.12 `laser-show.state.history` (working tree, from the undo plan; not in `d0a7dc2`)
- `live-item-paths` (working tree `state/history.clj:43-46`): add `[:muted?]`, next to `[:collapsed?]`. The postwalk in `strip-chain-entry` (working tree `:94-100`) covers every map with an `:id`, so track maps in `:tracks` are covered. Only tracks carry `:muted?`, so no other data becomes live.
- If the undo plan hasn't landed when Phase 1 starts, mute ships as an ordinary (undoable) edit, and this one-line change goes in with the undo plan.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:chains :cue-chains k :items * :timeline/start/:timeline/duration/:track-id]` | document | timeline handlers; `wrap-normalize-timelines`; `apply-loaded-project` | Yes, as part of the edit that triggered it. On load: no (baseline) |
| `[:chains :cue-chains k :loop]` (set by conversion) | document | `handle-open-editor` | No (skip + rebase, D2) |
| `[:chains :cue-chains k :tracks * :muted?]` NEW (mute, D10); legacy track `:enabled? false` is migrated to it by normalization | document, **live field** (in `live-item-paths`) | NEW `:timeline/set-track-muted` | **No / No** (still saved) |
| `[:chains :cue-chains k :tracks * :collapsed?]` (folder collapse) | document, live field (already in `live-item-paths`) | `:list/toggle-collapse` → `:timeline/update-tracks` | **No / No** (still saved) |
| `[:chains :cue-chains k :tracks * :color]` NEW | document | `tl/create-track` | Yes (with add-track) |
| `[:ui :timeline :selection/:cursor-beat/:scroll-hvalue]` | UI state | timeline handlers; reset by `handle-open-editor` | No |
| `[:list-ui :timeline-tracks]` | UI state | list handlers; reset by `handle-open-editor` | No |
| Clipboard `{:type :timeline-tracks …}` NEW | clipboard atom (`state/clipboard.clj`) | list copy handler | No |

## 6. Events and effects

| Event / effect key | Exists? | Dispatched from (file:line) | Pure? |
|---|---|---|---|
| `:timeline-cue-editor/open` | yes (`timeline.clj:671`) | `grid.clj:210`, `grid_cell.clj:231,252`, `cue_chain_editor.clj:626`, tests | yes |
| `:timeline/set-zoom` | yes (`:627`) | `timeline_editor.clj:687` | yes |
| `:timeline/set-snap` | yes (`:632`) | `timeline_editor.clj:664,674` | yes |
| `:timeline/set-hvalue` | yes (`:631`) | `timeline_editor.clj:607,1163` | **becomes** pure (no JavaFX) |
| `:timeline/update-item-timing` + NEW `:edge`, `:end` | yes (`:644`) | `timeline_editor.clj:487,491,498` | yes |
| `:timeline/update-loop-timing` + NEW `:edge`, `:end` | yes (`:649`) | `timeline_editor.clj:460,464,471` | yes |
| `:timeline/update-tracks` | yes (`:656`) | list on-change (`handlers/list.clj` via `timeline_editor.clj:779`) | yes |
| `:cue-chain/set-name` + NEW `:dialog-id` | yes | `timeline_cue_editor.clj:136`, `cue_chain_editor.clj` | yes |
| `:timeline/scroll`, `scroll-horizontal`, `resize-selection`, `toggle-track-expand`, `init-tracks` | yes → **deleted** | none in `src` | — |
| `:history/skip?`, `:document/rebase?` | working tree (undo plan) | `handle-open-editor` (conversion only) | meta keys |
| `:timeline/set-track-muted` | NEW | `track-context-menu` (`timeline_editor.clj:722-754`); Phase 3 header "M" | yes |

No new side-effect keys.

## 7. Data examples and invariants

### 7.1 `normalize-timeline-cue`
Tracks in all examples: `[{:id :f :type :group :items [{:id :t1 :type :track}]}]`.

| Top-level item in | Out |
|---|---|
| `{:id :a :type :preset}` | `{:id :a :type :preset :timeline/start 0.0 :timeline/duration 4.0 :track-id :t1}` |
| `{:id :a :type :preset :timeline/start 8.0 :track-id :t1}` | adds `:timeline/duration 4.0` only |
| `{:id :a … :track-id :gone :timeline/start 2.0 :timeline/duration 1.0}` | `:track-id :t1` (dangling → first leaf) |
| `{:id :g :type :group :items [{:id :c1 :timeline/start 4.0 :timeline/duration 2.0} {:id :c2 :timeline/start 8.0 :timeline/duration 4.0}]}` | group gets `:timeline/start 4.0 :timeline/duration 8.0` (bounding span of timed children); children unchanged |
| `{:id :g :type :group :items [{:id :c1}]}` | group gets `0.0` / `4.0` |
| complete item | `identical?` input |
| cue with no leaf track: `:tracks []`, one item | appends `{:type :track :name "Track 1"}` (no zone); item gets its id |
| track `{:id :t1 :type :track :enabled? false}` (written by today's checkbox) | `{:id :t1 :type :track :muted? true}` (D10 migration); `:enabled? true` is just removed |

**Invariants:**
1. `(normalize (normalize c))` = `(normalize c)`.
2. Returns the identical map when every top-level item is complete.
3. Never changes an existing `:timeline/start` or `:timeline/duration`.
4. Never touches non-timeline cues.
5. After it runs, every top-level item has a numeric start ≥ 0, a duration ≥ `min-clip-length`, and a `:track-id` that is a leaf in `:tracks`.

### 7.2 `convert-to-timeline`

| In | Out |
|---|---|
| `nil` (empty cell) | `{:items [] :tracks <auto-init for default zone> :editor-type :timeline}`, no `:loop` |
| `{:items [{:id :a :type :preset}] :destination-zone {:zone-group-id :all}}` | items `[{… :timeline/start 0.0 :timeline/duration 16.0 :track-id <child of :all folder>}]`, `:loop {:enabled? true :start 0.0 :duration 16.0}`, `:editor-type :timeline` |
| a cue with `:loop {:enabled? false …}` | keeps its `:loop` |
| a cue where `timeline-cue?` is already true | `identical?` input |

**Invariant:** for any converted non-empty cue with no prior `:loop`, the set of items `in-timeline-window?` is the full enabled item set at every playback position. In other words, conversion doesn't change what renders. The caveat is modulator phase continuity at the 16-beat wrap (D2).

### 7.3 Clip edits (snap 0.25 enabled, min length 0.0625)

| Call | Before | After |
|---|---|---|
| `trim-start` to −3 | start 1, len 4 | start 0, len 5 |
| `trim-start` to 9 | start 1, len 4 | start 4.9375, len 0.0625 (the clamp is applied after the snap; min length wins) |
| `trim-start` to 1.6 | start 1, len 4 | start 1.5, len 3.5 |
| `trim-end` to 0.5 | start 1, len 4 | start 1, len 0.0625 |
| `move-clip` +2.1 | start 1, len 4; child effect at 1 | start 3.0, len 4; child at 3.0 |
| `move-clip` −5 | start 1, len 4 | start 0, len 4 (child shifted −1) |

**Invariants:**
- trim-start never changes `clip-end` unless that's required to keep `start ≥ 0`, and it never does here because end is fixed and start is clamped to `[0, end − min]`.
- trim-end never changes start.
- move keeps length, and every nested start shifts by the same applied delta.
- No result has start < 0 or length < min.

## 8. Tests (named test vars + fixtures)

Unit (`clj -M:win:test -n <ns>`). Fixture `base-state` = `(domains/build-initial-state)`, as in `timeline_handlers_test.clj:6-7`.

- `laser-show.views.components.visual-editors.timeline.track-logic-test` (extend):
  - `normalize-timeline-cue-examples-test` (§7.1 table)
  - `normalize-timeline-cue-idempotent-test`: a seeded `java.util.Random` loop over 200 generated cues (no new dependency)
  - `convert-to-timeline-examples-test`
  - `trim-start-keeps-end-test`, `trim-end-keeps-start-test`, `move-clip-shifts-children-test` (§7.3)
  - `muted-track-ids-includes-folder-descendants-test`, `parent-track-test`
  - `flatten-visible-tracks-hidden-descendants-test` (replaces the `expanded-tracks` case at `track_logic_test.clj:11-41`)
- `laser-show.events.timeline-handlers-test` (extend; update `handle-open-editor-test` `:9-39`, which currently asserts duration `4.0`):
  - `open-editor-on-timeline-cue-does-not-change-document-test`
  - `open-editor-converts-without-changing-output-test`: compares `frame_service/generate-frames-by-zone` keys and point counts at beats 0, 5, 15 and 31 before and after (`frame_service_test.clj` style fixture)
  - `open-editor-resets-ui-for-other-cell-test`
  - `set-zoom-uses-fx-event-test`, `set-snap-uses-fx-event-false-test`
  - `set-hvalue-has-no-side-effects-test`
  - `update-item-timing-left-edge-keeps-end-test`, `update-loop-timing-missing-loop-stays-disabled-test`
  - `update-tracks-deleting-track-deletes-its-clips-test`
  - `add-track-does-not-stamp-zone-test`
- `laser-show.events.history-handlers-test` (extend): `pipeline-normalizes-items-added-by-cue-chain-editor-test`. It runs `:cue-chain/add-preset` on a timeline cue through `events/handle-event-pipeline` and asserts timing, a track, and a single undo entry.
- `laser-show.events.history-handlers-test` (extend, D10), all through `events/handle-event-pipeline`:
  - `track-mute-is-not-undoable-test`: mute leaves `:undo-stack` unchanged and the project clean.
  - `folder-collapse-is-not-undoable-test`
  - `undo-does-not-unmute-test`: mute a track, then move a clip, then undo; the clip moves back and the track stays muted.
- `laser-show.views.components.visual-editors.timeline.track-logic-test` (extend): `normalize-migrates-disabled-tracks-to-muted-test`.
- `laser-show.state.persistent-test` (extend): `load-normalizes-timeline-cues-and-stays-clean-test` (fixture: a zip written by `persistent/write-project!` from a state containing an un-normalized timeline cue).
- `laser-show.services.frame-service-test` (extend): `muted-track-clips-do-not-render-test`, `muted-folder-mutes-children-test`.
- `laser-show.events.cue-chain-handlers-test` (extend): `set-name-clears-given-dialog-flag-test`.
- `laser-show.events.list-handlers-test` (extend): `timeline-track-copy-uses-own-clipboard-type-test`.

TestFX (`clj -M:test-ui:win -v laser-show.ui-test.e2e-test/<var>`, per `.agents/skills/targeted-ui-testing/SKILL.md`):
- `timeline-zoom-slider-changes-zoom`: `ui/slide!` on `#timeline-zoom-slider` to 120, then wait for `[:ui :timeline :zoom-x]` = 120.
- `timeline-inspector-shows-folder-properties`: click the folder row and assert the label "FOLDER PROPERTIES" is visible (give it a NEW `:id "timeline-inspector-title"`).
- `timeline-open-does-not-convert-twice`: open twice and assert the second open doesn't change `[:chains :cue-chains [0 0]]`.

## 9. Out of scope / alternatives considered

- **Out of scope:**
  - Stable IDs on keyframes (the real TL-30 fix; it touches every index-based keyframe handler in `keyframe.clj`).
  - Converting a timeline cue back to a plain cue chain.
  - Fixing grid paste IDs (02 #15).
  - Solo.
- **Alternatives considered:**
  - **Normalize in each writer** (`cue_chain.clj:74`, `chain/core.clj:31`, list handlers, load) instead of middleware. Rejected: the root cause is "many writers, no invariant", and a middleware enforces it for future writers too. It follows the existing `wrap-history` / `wrap-dirty` pattern.
  - **Open-ended clips for conversion** (no duration, drawn to the end of the timeline). Rejected: every geometry function would then need an "infinite" case.
  - **Reassign orphaned clips to another track** on track delete. Rejected: it silently re-routes output (D4).
  - **Keep using `:enabled?` for mute** (the key today's checkbox writes). Rejected (D10): the user wants mute to be non-undoable. A path-based live policy on `:enabled?` would also make clip and effect enable toggles non-undoable.
