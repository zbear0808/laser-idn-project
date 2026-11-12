# Drag presets and effects from the browser (07 L1, option A)

**Written:** 2026-11-11   **Written for commit:** `716688d` (branch `timeline/effects-panel`)
**Status:** Proposed   **Related:** `07-effects-panel-followups.md` L1-A; `05-visual-design.md` (tokens, drag visuals, §3 browser note "drag-to-lane is out of scope", now in scope); `00-overview.md` D4, D6

Presets can be dragged into the lanes to add a clip at the drop beat. Effects can be dragged onto a clip, an empty lane, a track or folder header, or a position in the device chain. Clicking works exactly as it does today.

## 1. Current behaviour (file:line)

- **Bank buttons only click.** `tabbed_bank.clj:35-52` `item-button` is a `:button` with `:on-action (merge event-template {:item-id :item})`. There is no drag source.
- **Browser** (`timeline/browser.clj:30-56`):
  - Presets dispatch `:timeline/add-preset-to-track` with `:track-id insert-track-id`.
  - Effects dispatch `:timeline/add-effect-to-item` with `:target-item-id` / `:target-track-id` taken from the selection.
  - The whole effect bank is `:disable (not effect-target?)` (`:45`). With nothing selected, effects can't even be pressed, so they couldn't be dragged either.
  - Zone effects are only listed when a clip is selected (`:51`).
- **Handlers** (`events/handlers/timeline.clj`):
  - `handle-add-preset-to-track` (`:293-309`) already takes `:start` and `:track-id`. It applies `{:op :add-clip}`, moves the cursor to the clip's end, selects the clip and its track, and labels the undo step "Add Clip".
  - `handle-add-effect-to-timeline-item` (`:311-324`) builds params from `(:parameters item)` and goes through `op-effects` (`:136-141`). The label is `model/op-label` → "Add Effect" (`model.clj:737`). It doesn't change the selection.
- **Model** (`timeline/model.clj`):
  - `:add-clip` (`:652-658`) resolves `:track-id` through `insert-track` (`:104-116`: a folder gives its first leaf), snaps `:start`, and uses `default-clip-length` 4.0 (`:189`).
  - `:add-effect` (`:660-663`) always appends (`conj`).
  - `:move-effect` (`:631-639`) clamps an index; it's the pattern to copy.
- **Undo.** `wrap-history` (`events/handlers/history.clj:184-236`) pushes one snapshot per event that changes undoable content, using `:history/description`. Selection and cursor changes in the same event ride along (`ui-context`).
- **Lane canvas.**
  - `ci/interactive-canvas` (`canvas_interaction.clj:95-305`) wires mouse, key and scroll only. `extract-event-info` (`:45-78`) knows no `DragEvent`.
  - The canvas is `:managed false` (`timeline_editor.clj:820`). Its interaction state is an atom in the canvas userData (`:35-38`), and `render-lanes!` gets it as `drag-info` (`:265-290`, `ci :86-93`).
  - `overlay-canvas` sits on top but is `:mouse-transparent true` (`timeline_editor.clj:474`). JavaFX leaves mouse-transparent nodes out of picking for drag events too, so the lane canvas (`#timeline-canvas`) is the drop target.
- **Hit testing.** `layout/hit-test` (`layout.clj:325-360`) returns `:loop`/`:end`/`:ruler`/`:clip`/`:lane`/`:empty`. On a folder row, `:clip` is a summary bar (`:357`). `row-at` is `:132-141`, `x->beat` is `:112`, `clip-rect` is `:286-295`, and `model/snap` is `model.clj:226-231`.
- **Header rows** (`timeline/header.clj:124-191`, ids `#timeline-track-header-<idx>`):
  - Rows get list_dnd source and target in `:on-created` (`:135-137`), and the pane gets a container target (`:221-225`).
  - `list_dnd` uses `.setOnDragOver`/`.setOnDragDropped` (single property slots, `list_dnd.clj:161-222`) and accepts any drag with `.hasString` (`:164-165`).
  - Its `onDragDropped` handlers (`:205-222`, `:240-249`) don't check the dragboard at all. They call `setDropCompleted true` and dispatch `:list/clear-drag`.
- **Device chain.** `timeline/device_chain.clj:88-132` shows cards (320 px wide, `h-box :spacing 6`) in a scroll pane. There is no DnD.
- **Existing DnD helper.** `drag_drop_cell.clj:27-127` puts an EDN payload under an app-private `DataFormat` per drag type (`data-format :52-59`), and `parse-payload` (`:36-48`) never evaluates input. It's hard-wired to `{:col :row}` and `TransferMode/MOVE`.

## 2. Root cause

The bank was built for click-to-add at the selection and cursor. No component carries "what is being dragged" (the bank) to "where it lands" (lanes, headers, chain), and the canvas has no DnD entry point.

## 3. Reuse / extend

| Existing (file:line) | Use |
|---|---|
| `drag_drop_cell` `data-format`, EDN marker, size cap (`:31-59`) | **Generalize**: NEW `start-drag!`, `read-payload`, `has-payload?` for any drag type and transfer mode. The cell functions are rebuilt on top of them, with the same behaviour and tests. Chosen over `list_dnd` because foreign text can't match an app-private format. |
| `list_dnd` (`:146-257`) | Not reused for bank drags. Gets a small guard so it ignores drags without a string (§4.6). Its before/after-from-position idea is reused for the chain insertion index. |
| `layout/hit-test`, `model/snap`, `model/insert-track`, `layout/clip-rect`, `layout/track-colors` | Drop resolution and ghost geometry |
| `handle-add-preset-to-track` (`:293`) | Preset drops, unchanged (`:start`/`:track-id` already supported) |
| `handle-add-effect-to-timeline-item` (`:311`) | Effect drops, plus `:index` and `:select-target?` |
| `select-track-in-sidebar` (`timeline.clj:66`) | Selecting the drop target |
| `gesture.clj` style (pure, `layout` hits in, data out) | Template for NEW `timeline/drop.clj` |

## 4. Changes per namespace

### 4.1 `laser-show.views.components.drag-drop-cell` (existing)
- NEW `start-drag! [^Node source drag-type payload modes]` puts `(pr-str (assoc payload :laser-show/drag drag-type))` under `(data-format drag-type)`. NEW `read-payload [^Dragboard db drag-type valid?]` returns the map or nil (same EDN rules and 1024-char cap as `parse-payload`). NEW `has-payload? [db drag-type]`.
- `make-drag-*-handler` are reimplemented on these. The `:grid-cell`/`:effects-cell` payload shapes stay the same.
- Bank drags use drag type `:bank-item` and `TransferMode/COPY` (they add, they don't move). Targets accept `COPY` only.

### 4.2 `laser-show.views.components.tabbed-bank` / `preset-bank` / `effect-bank`
- NEW optional data prop `:drag-kind` (`:preset` | `:effect`), passed through by both banks. When it's set, `item-button` adds:
  - `:on-drag-detected` → `dd/start-drag!` with `{:kind k :id item-id :category (:category item)}`.
  - `:on-drag-done` → `(.disarm button)`, so the button never stays armed after the gesture (risk R2).
- Click isn't affected: `DRAG_DETECTED` only fires after the mouse has moved past the system drag threshold with the button down, and a plain click still gives `:on-action`. Banks without `:drag-kind` (cue chain editor) are unchanged.

### 4.3 `laser-show.views.components.visual-editors.timeline.browser`
- Pass `:drag-kind :preset` / `:effect`.
- Replace `:disable (not effect-target?)` with a style class `tl-browser-bank-idle` (opacity 0.55), so effects can be dragged with nothing selected. A click with no target is already a no-op (`timeline.clj:316-317`).
- Hints:
  - Presets: "Click: adds at 1.1.1 on Track 1 · or drag into a lane".
  - Effects: "Adds to Square · or drag onto a clip or track". With no target: "Drag onto a clip, track or folder, or select one first".

### 4.4 `laser-show.views.components.visual-editors.timeline.drop` (NEW, `timeline/drop.clj`)
This is pure. It's separate from `gesture.clj`, which is the press→move→release state machine; a drop is one stateless decision.
- `valid-payload? [m]`: `:kind` ∈ #{:preset :effect}, `:id` is a keyword, `:category` is a keyword, a set or nil.
- `zone-effect? [payload]`: `:zone` is the category or in the category set.
- `drop-target [payload hit {:keys [col row tracks snap]}] → {:event e :preview p} | nil`. `hit` is a `layout/hit-test` result, or `{:type :header :track-id id}`, or `{:type :chain :target sel-target :index i}`. Rules:

| payload | hit | event | preview |
|---|---|---|---|
| preset | `:lane` / `:clip` on a track row | `:timeline/add-preset-to-track` `:track-id` row, `:start (max 0 (snap beat))` | `{:ghost {:row-id leaf :start s :length 4.0}}` |
| preset | `:lane` / `:clip` on a folder row | same, `:track-id` = the folder (the handler resolves it via `insert-track`, as a click with a folder selected does) | ghost on `(model/insert-track tracks folder)` |
| preset | `:header` | `:track-id` id, **no `:start`** (handler uses the cursor) | none (header highlight only) |
| effect | `:clip` on a track row | `:add-effect-to-item` `:target-item-id` clip, `:select-target? true` | `{:highlight-clip id}` |
| effect | `:lane`, `:header`, or `:clip` on a folder row | `:target-track-id` row id, `:select-target? true` | `{:highlight-row id}` |
| effect | `:chain` with target kind `:clip`/`:track` | target id + `:index i` (no select: it's already the shown target) | none (insertion line) |
| zone effect | anything but a clip (lane or chain) | nil | — |
| any | `:ruler` `:loop` `:end` `:empty`, chain with `:clips`/`:timeline` target, nil payload | nil | — |

  Summary bars on a folder row target the folder: the bar stands for many clips and is only 8 px tall.
- `chain-insert-index [spans x]`: `spans` = `[[min-x width] …]` of the cards in h-box coordinates. Returns the first i whose centre is > x, else `(count spans)`.

### 4.5 `canvas_interaction.clj` and `timeline_editor.clj`
- `interactive-canvas`: NEW optional pass-through opts, attached only when given (`cond->`, like `style-class`):
  - `:on-drag-over (fn [x y ^Dragboard db value drag-info]) → {:accept? :drag-updates}`. When `:accept?`, the wrapper calls `acceptTransferModes COPY`. It merges `:drag-updates` and calls `render!` **only when the merged state differs**, like hover at `:249`. It always consumes.
  - `:on-drag-exited (fn [value drag-info]) → {:drag-updates}`.
  - `:on-drag-dropped (fn [x y db value drag-info]) → {:dispatch :drag-updates}`. The wrapper dispatches, calls `setDropCompleted (some? dispatch)`, merges, renders and consumes.
  - `extract-event-info` gains a `DragEvent` branch (`:x :y`).
- Timeline editor:
  - NEW private `drop-at [db value x y]`: `dd/read-payload db :bank-item drop/valid-payload?`, then `hit`, then `drop/drop-target`.
  - `on-drop-over` / `on-drop-exit` / `on-drop` set or clear `:drop-preview` and return the event.
- `render-lanes!`: NEW `draw-drop-preview!` after `draw-clips!`, reading `(:drop-preview drag-info)`:
  - **Ghost**: `clip-rect` of `{:timeline/start s :timeline/duration 4}` on the ghost row, filled with the track colour @ 0.35, with a 1 px dashed outline (`[4 3]`) in the track colour.
  - **Snap guide**: a full-height line at x(start) in NEW `cc/tl-snap-guide` (orange @ 0.5, the `05` token `:tl/snap-guide`).
  - **Highlight clip**: a 2 px `cc/tl-drop-target` stroke (NEW, `blue`).
  - **Highlight row**: the lane filled with NEW `cc/tl-drop-lane` (`blue` @ 0.18).
- Empty-state text (`:261`): "Click or drag a preset from the browser to add it (cursor at bar …)".

### 4.6 `header.clj` and `list_dnd.clj`
- `header-row :on-created` additionally calls NEW `bank-drop-target! node col row track-id`, using **`.addEventHandler`** for `DRAG_OVER`/`DRAG_ENTERED`/`DRAG_EXITED`/`DRAG_DROPPED`. It doesn't use the `setOnDrag*` slots, which list_dnd owns. Handlers on one node all run, even after `consume`.
  - It acts only when `(dd/has-payload? db :bank-item)`: it resolves through `drop/drop-target` with `{:type :header :track-id}` and toggles pseudo-class `drop-target` on the row.
  - CSS: `.label:drop-target .tl-header-row` gets the `.tl-header-row-selected` look plus a 1 px blue border.
  - All values it needs (track-id, col, row) are fixed for the row's `:fx/key`, so the `on-created` closure doesn't go stale.
- `list_dnd`: both `onDragDropped` handlers (`:205`, `:240`) return early, without consuming or calling `setDropCompleted`, when `(not (.hasString db))`. This keeps the two handlers on a header row from completing the same drop twice. Track-reorder drags carry a string and no bank format, so the new targets never accept them, and the reverse holds too.

### 4.7 `device_chain.clj`
- Give the cards' h-box `:id "timeline-device-cards"`.
- On the `#timeline-device-chain` v-box, add cljfx `:on-drag-over` / `:on-drag-exited` / `:on-drag-dropped` (no slot conflict here):
  - Read the payload.
  - Spans = the h-box children's `boundsInParent`; x = `(.sceneToLocal hbox sceneX sceneY)`.
  - `drop/chain-insert-index`, then `drop/drop-target`.
- The insertion line is pseudo-class `drop-before` on card i, or `drop-after` on the last card: a 3 px blue left or right border. Pseudo-classes aren't managed by cljfx, so re-renders can't strip them. They're cleared on exit and drop.
- With no cards, the drop index is 0 and the hint label highlights.
- Hint text: "Click or drag an effect from the browser…".

### 4.8 Model and handlers
- `model/apply-op* :add-effect`: optional `:index`, clamped to `[0, n]`, inserts with the `subvec` pattern from `move-effect`. Without it, the op appends as today. `op-label` is unchanged ("Add Effect").
- `handle-add-effect-to-timeline-item`:
  - Passes `:index`.
  - `:item` falls back to `(effects/get-effect item-id)`, so drop events stay pure data.
  - With `:select-target? true`: for a clip, selection `#{id}` + `select-track-in-sidebar` with its track (what pressing a clip does, `timeline_editor.clj:335-337`); for a track or folder, selection `#{}` + `select-track-in-sidebar` (what pressing a lane does, `:340-344`).
  - Applied only when the op changed the cue.

## 5. State placement

| What | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| Drag payload `{:laser-show/drag :bank-item :kind :id :category}` | JavaFX Dragboard, app-private DataFormat | `item-button` drag-detected | No |
| `:drop-preview` (ghost / highlight) NEW | lane canvas interaction atom (FX thread, `ci :35`) | `on-drop-over`/`-exit`/`-drop` | No |
| Header / chain highlight | node pseudo-classes | header and chain DnD handlers | No |
| New clip / effect | document `[:chains :cue-chains [c r]]` | `apply-op :add-clip` / `:add-effect` | **Yes**, one step |
| Selection, cursor, sidebar track | `[:ui :timeline-editor [c r]]`, list-ui | same event as the edit | No (in snapshot `ui-context`) |

## 6. Events and effects

| Event | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:timeline/add-preset-to-track` (`:start`, `:track-id`) | yes, unchanged | bank click; canvas drop; header drop | yes |
| `:timeline/add-effect-to-item` + `:index`, `:select-target?`, optional `:item` | extended | bank click (unchanged keys); canvas, header, chain drop | yes |
| op `:add-effect` + `:index` | extended | handler above | yes |

There are no new event types and no new effect keys. Each accepted drop dispatches exactly one event, so one `wrap-history` snapshot labelled "Add Clip" (`timeline.clj:309`) or "Add Effect" (`op-label`). The canvas doesn't open a transaction for DnD; mouse-press never fires during the gesture.

## 7. Data examples and invariants

Geometry from `gesture_test.clj` (zoom 50, no scroll, snap 0.25, col/row 0 0):
- Folder `:f` row: canvas y 36–62, summary bar y 45–53.
- `:t1` (inside `:f`): y 63–99, clip `:a` [2, 6) at x 101–299.
- `:t2`: y 100–136.

Payloads: preset `{:laser-show/drag :bank-item :kind :preset :id :square :category :geometric}`; effect `{… :kind :effect :id :scale :category :shape}`.

| Payload | Drop at | Result |
|---|---|---|
| `:square` | (413, 118) lane `:t2` | `{:event/type :timeline/add-preset-to-track :col 0 :row 0 :item-id :square :track-id :t2 :start 8.25}`, ghost `:t2` 8.25–12.25 |
| `:square` | (150, 81) over clip `:a` | `:track-id :t1 :start 3.0` (overlaps `:a`, D6) |
| `:square` | (150, 49) folder `:f` | `:track-id :f :start 3.0`; ghost on `:t1`; handler puts it on `:t1` |
| `:square` | header `:t2` | `:track-id :t2`, no `:start` → at the cursor |
| `:scale` | (150, 81) clip `:a` | `{:event/type :timeline/add-effect-to-item :col 0 :row 0 :item-id :scale :target-item-id :a :select-target? true}` |
| `:scale` | (400, 81) empty `:t1` lane | `:target-track-id :t1 :select-target? true`, row highlight |
| `:scale` | (150, 49) summary bar on `:f` | `:target-track-id :f` |
| zone effect `{:category :zone}` | (400, 81) lane / header | nil (no accept) |
| `:scale` | chain of clip `:a` with 2 cards, spans `[[0 320] [326 320]]`, x 400 | index 1 → `:target-item-id :a :index 1` |
| any | (150, 20) ruler; (150, 200) empty; foreign or malformed payload | nil |

`chain-insert-index` with those spans: x 100 → 0, x 400 → 1, x 700 → 2.

**Invariants:**
1. Click events are byte-identical to today's: no `:index`, no `:select-target?`.
2. An accepted drop dispatches exactly one event, so one undo entry. A rejected drop dispatches nothing and the OS shows "no drop".
3. The ghost's row and start equal the committed clip's `:track-id` and `:timeline/start`. The view snaps first, and the handler's snap is idempotent on the same grid; with prefs `:snap` nil it doesn't snap at all.
4. Bank targets accept only `:bank-item` payloads, and list_dnd never completes a bank drop.
5. `drop-target` is total: it never throws on odd hits or payloads.
6. Zone effects only land on clips.

## 8. Tests (named test vars + fixtures)

- `laser-show.views.components.visual-editors.timeline.drop-test` (NEW, next to `gesture_test.clj`; fixture = gesture_test's cue/rows/view, plus a 2-effect clip):
  - `preset-on-lane-snaps-start-test`
  - `preset-on-folder-row-targets-folder-test`
  - `preset-on-clip-adds-overlapping-clip-test`
  - `preset-on-header-uses-cursor-test`
  - `effect-on-clip-targets-clip-test`
  - `effect-on-lane-header-and-summary-targets-track-test`
  - `zone-effect-only-on-clips-test`
  - `rejected-hits-and-payloads-test`
  - `chain-insert-index-test`
  - `chain-drop-needs-single-target-test`
- `laser-show.views.components.drag-drop-cell-test`: `bank-payload-round-trips-test`, `bank-payload-rejects-foreign-input-test`. The existing two tests stay green.
- `laser-show.timeline.model-test`: `add-effect-at-index-test` (0, middle, past end clamps, absent appends, on a folder).
- `laser-show.events.timeline-handlers-test`:
  - `add-effect-with-index-and-select-target-test`
  - `add-effect-looks-up-definition-test`
  - `drop-is-one-undo-step-test`: runs through `events/handle-event-pipeline` like `history_handlers_test.clj:30-39`, and checks `undo-stack` +1 with label "Add Clip" / "Add Effect" and that one undo removes it.
  - `ghost-matches-committed-clip-test`: drop event → handler → clip start/track = preview.
- UI (`timeline_editor_test.clj`):
  - `timeline-drag-preset-into-lane` and `timeline-drag-effect-onto-header-and-chain` use `ui/drag-node!` (`testfx.clj:300`).
  - First check (step 1 spike) whether the headless glass platform delivers DragEvents. If it doesn't, skip them under `(ui/headless?)` with a log line and run them in windowed mode.
  - Also assert that a click on a bank button after a drag adds exactly one clip (R2).
- Manual: macOS trackpad drag inside the timeline dialog (`experiments/dnd_repro.clj` exists for dialog-window DnD).

## 9. Out of scope / alternatives considered
- **Out of scope:**
  - Reordering cards by drag (07 L1-B; it can reuse `chain-insert-index` and `:move-effect` later).
  - Auto-scroll at canvas or chain edges while dragging.
  - Dropping below the last row to create a track.
  - Shift to drop unsnapped: `DragEvent` has no modifier state.
  - A drag image (`setDragView`): optional polish.
- **Alternatives considered:**
  - **Reuse `list_dnd` for bank drags.** Rejected: it accepts any string, including text from other apps, and its state lives in `[:list-ui …]` through events.
  - **Ghost in `fx/ext-state`.** Rejected: every drag-over would re-render the whole editor body, not just one canvas.
  - **Draw the ghost on the overlay canvas.** Possible, but the overlay is subscribed to the playhead and has no hit-test context; the lane canvas already owns the preview.
  - **Reject presets on folder rows.** Rejected for consistency with click and `insert-track`.

## 10. Risks, steps and effort

**Risks**
- **R1. DnD takes over the mouse.** While dragging, there are no `MOUSE_MOVED`/`RELEASED` events, so the canvas hover state can be stale. Clear `:hover-id` on drop and exit.
- **R2. The button can stay armed** after `startDragAndDrop` (a known JavaFX quirk). The fix is `disarm` in drag-done, and a UI test covers it.
- **R3. The canvas must accept in every `DRAG_OVER`**, or there's no drop. Accept only COPY; the source offers only COPY.
- **R4. Redraw cost.** A full `render-lanes!` per changed preview: with snap on, that's one per grid step. With snap off it's per pixel, which is the same cost as today's clip drag.
- **R5. Header conflict.** Handled by format checks, `addEventHandler` and the list_dnd guard (§4.6).
- **R6. Payload read during drag-over.** If a platform hides content before the drop, fall back to `has-payload?` for accepting and keep the last parsed payload in the drag-detected closure's atom. *Unverified*; the step 1 spike checks it.

**Steps** (each one can be committed on its own):

| # | Step | Effort |
|---|---|---|
| 1 | Generic payload helpers + list_dnd guard + headless DnD spike | 0.25 d |
| 2 | `:add-effect :index`; handler `:index`/`:select-target?`/lookup + tests | 0.25 d |
| 3 | `timeline/drop.clj` + `drop-test` | 0.25 d |
| 4 | Bank drag source (`:drag-kind`), browser enable + hints | 0.25 d |
| 5 | `interactive-canvas` DnD opts, editor wiring, `draw-drop-preview!`, tokens | 0.5 d |
| 6 | Header drop + CSS | 0.25 d |
| 7 | Device chain drop + insertion line | 0.25–0.5 d |
| 8 | UI tests, manual check, docstrings (`browser.clj`, `device_chain.clj` ns) | 0.25 d |

Total: about 2–2.25 days, slightly above 07's estimate because of the list_dnd guard and the spike.
