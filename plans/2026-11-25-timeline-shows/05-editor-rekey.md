# Timeline shows: the editor re-key, call site by call site

**Status:** Proposed   **Last verified against:** `63240576`
**Supersedes / Superseded by:** none. It expands `02-phases.md` S2 step 2 ("Editor re-key") and corrects it where noted (§9.2).   **Related findings:** `00` TS-5, TS-4; `01` §2

Paths are under `src/laser_show/` unless they start with `test/` (`T/` = `test/laser_show/`) or `plans/`. **NEW** marks anything that doesn't exist yet. Below, `u` is a show id (a uuid) and `k` is a playback key (`[col row]` or `[:show u]`, `01` §2).

## 1. Current behaviour (file:line)

The editor uses a grid cell as its address in nine kinds of place. §4 lists every one; here is the summary.

| Kind | Where |
|---|---|
| Dialog data `{:col :row :editing-name?}` | written by `handle-open-editor` (`events/handlers/timeline.clj:461-463`); read at `views/dialogs/timeline_cue_editor.clj:34-35, :143` and by `event-cell` (`timeline.clj:80`) |
| Document path `[:chains :cue-chains [col row]]` | `chain-path` `timeline.clj:119`; written out again in `handle-update-tracks` :244 and `handle-open-editor` :449; `subs/cue-cell-chain` `subs.clj:249-253`; `chain-config` `events/handlers/chain/helpers.clj:38`; `header.clj:143` |
| UI paths | `[:ui :timeline-editor [col row]]` (`view-path` `timeline.clj:50`, `subs/timeline-ui` `subs.clj:258-263`); `[:list-ui [:timeline-tracks col row]]` (`tracks-list-id` `timeline.clj:52-55`, `timeline_editor.clj:900`, `header.clj:142`) |
| Playback key `[col row]` | `active-timing` `timeline.clj:365-366`; play, pause, stop and seek at :391, :395, :404, :418, :428; `subs/timeline-transport` `subs.clj:275`; `show-time/cue-beats snapshot [col row]` `timeline_editor.clj:544, :602`; `edited-cells` `services/show_clock.clj:106-115`; cue warnings `subs.clj:586` |
| Event maps `{:col :row}` | every `:timeline/*` dispatch in the views (§6) |
| Props `:col :row` | passed from `timeline-editor` to `editor-body`, then on to `toolbar`, `header-column`, `overlay-canvas`, `readout`, `detail-panel`, `inspector`, `device-chain`, `effect-card`, `browser` and the canvas `:value` (§4.3–4.8) |
| JavaFX user-data and list addresses | user-data `{:col :row …}` at `header.clj:189, :276`, read by `header-drop` :133 and `track-list` :141-145 (the list's drag-and-drop address); clock-view user-data at `timeline_editor.clj:564, :624` |
| The key that resets local state | `:initial-state {:cell [col row] …}` at `timeline_editor.clj:1044` |
| Chain domain entity-key `[col row]` | `device_chain.clj:81` (`:timeline-items`, `:timeline-tracks`); the footer's pattern chip, `views/components/zone_pattern_editor.clj:567-570` (`:cue-zone-patterns`) |

Two things need no change:
- **Node ids.** No JavaFX node `:id` in the editor contains a cell. They are indexed instead, for example `timeline-track-header-0` (`header.clj:188`).
- **History.** The history `ui-context` (`events/handlers/history.clj:113-115`) records only the tab and the grid's selected cell.

**Measured size.** These counts come from `grep -cE '\bcol\b'`: lines that name a column. `row` is also used for layout rows, so counting `col` gives the honest figure.

| File | Lines with `col` | `00` §1 said |
|---|---|---|
| `events/handlers/timeline.clj` | 115 | 110 |
| `views/components/visual_editors/timeline_editor.clj` | 53 | 17 |
| `timeline/header.clj` | 30 | 21 |
| `timeline/inspector.clj` | 28 | 23 |
| `views/dialogs/timeline_cue_editor.clj` | 16 | 7 |
| `timeline/device_chain.clj` | 12 | 8 |
| `timeline/browser.clj` | 5 | 2 |
| `timeline/drop.clj` | 3 | 1 |
| `timeline/gesture.clj` | 0 (its `row` is a layout row) | — |
| **Total** | **262** | ~190 |

Outside the editor there are 9 more sites:

| File | Sites |
|---|---|
| `subs.clj` | 4 |
| `chain/helpers.clj` | 2 |
| `state/extractors.clj` | 1 |
| `services/show_clock.clj` | 1 |
| `views/components/load_warning.clj` | 1 |

The zone pattern chain adds more (§4.11).

**What happens today when the cell goes while the editor is open:**
- `:grid/clear-cell` (`events/handlers/grid.clj:158-168`) neither closes the dialog nor touches it.
- The dialog then reads `subs/cue-cell-chain`'s default `{:items []}` (`subs.clj:253`) and draws an empty timeline ("Add a track to get started", `timeline_editor.clj:258-260`).
- Most edits do nothing, because `apply-op-to-state` returns the state unchanged for a nil cue (`timeline.clj:136-137`).
- Two edits **write a new, partial cue into the cleared cell**:
  - `handle-update-tracks` (`timeline.clj:243-255`) uses `assoc-in` with no nil check, and a header drag can reach it;
  - the name field's `:cue-chain/set-name` (`events/handlers/cue_chain.clj:185-192`).
- Undo of a "Convert to Timeline Cue" behaves the same way.
- New and Open close every dialog (`reset-session`, `state/persistent.clj:292`).

## 2. Root cause

The editor has no address of its own. It borrows the grid's `[col row]`, and each layer builds its own path from it: the document, the UI, the track list, playback, render warnings and the chain domains.

A show breaks this. It has a uuid in the library and a different playback key, `[:show u]`, so one value can no longer serve every layer. The document path, the UI keys and the playback key must each be derived from the show id, each in one place.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `event-cell`'s fallback to the dialog (`timeline.clj:74-81`) | Becomes `event-show`, with the same rule and the same callers |
| `chain-path`, `view-path`, `tracks-list-id` (`timeline.clj:119, :50, :52-55`) | Kept as the only places these paths are derived; their argument becomes `u` |
| `op-effects`, `apply-op-to-state` (`timeline.clj:130-150`) | Logic unchanged. `apply-op-to-state` is public, but only tests call it (`grep` finds no other caller in `src/`) |
| `targets/cue-fallback` 3-arity (`routing/targets.clj:139-140`) and `explain-cue` (:258) | A show has no column, so they are called with `column-target nil`. That gives `{:from :show}` or `{:from :default}`. No new routing code |
| `model/apply-op` (`timeline/model.clj:695-707`), `op-types` :686-693, `op-label` :712-743 | NEW op `:set-cue-target` joins them, so the footer's Target is just one more op |
| `chain-config` (`chain/helpers.clj:17-53`) | The base path for `:timeline-items`, `:timeline-tracks` and `:cue-zone-patterns` comes from NEW `shows/cue-path`. The chain, modulator and keyframe handlers then work unchanged, because they treat entity-key as opaque |
| `effect-parameter-editor`, the keyframe panel | Pass `:domain` and `:entity-key` through untouched. Only the value the device chain gives them changes |
| `compose/cue-warnings` (`routing/compose.clj:244-276`) | Already keyed by each run's `:cell`, so once S1 plays shows, a show's warning sits at `[:show u]` |
| `inline-edit` (`views/components/inline_edit.clj`) | The name field commits NEW `:timeline/set-name` |

**NEW functions**, each justified:
- **`laser-show.timeline.shows/cue-path [k]`** returns `[:chains :shows u]` for `[:show u]`, and `[:chains :cue-chains k]` for any other key. `01` §1's `cue-at` becomes `(get-in state (cue-path k))`. It's needed because `chain-config` builds a *path*, not a value; without it the path rule would be written twice.
- **`shows/show-path [u]`** returns `[:chains :shows u]`. `chain-path` and `subs/show-cue` use it.

## 4. Changes per namespace

Notation: **cur** → **new**. "Fallback" means the handler uses `event-show`.

### 4.1 `laser-show.events.handlers.timeline` (existing)

**Helpers**

| Line | Function | cur | new |
|---|---|---|---|
| 2-17, 33-36 | ns doc, comment | `[col row]` paths | `[:ui :timeline-editor u]`, `[:chains :shows u …]`, `[:list-ui [:timeline-tracks u]]` |
| 50 | `view-path` | `[col row]` → `[:ui :timeline-editor [col row]]` | `[u]` → `[:ui :timeline-editor u]` |
| 52-55 | `tracks-list-id` | `[:timeline-tracks col row]` | `[:timeline-tracks u]` |
| 57-61 | `view-get`, `view-assoc` | `[state col row k …]` | `[state u k …]` |
| 69-72 | `select-track-in-sidebar` | `[state col row track-id]` | `[state u track-id]` |
| 74-81 | `event-cell` | `[col row]` or the dialog's `[dcol drow]` | **renamed `event-show`**: `(or show-id (get-in state [:ui :dialogs :timeline-cue-editor :show-id]))` |
| 119 | `chain-path` | `[:chains :cue-chains [col row]]` | `(shows/show-path u)` |
| 121-128 | `prune-selection` | `[state col row cue]` | `[state u cue]` |
| 130-143 | `apply-op-to-state` | `[state col row op]` | `[state u op]` |
| 145-150 | `op-effects` | `[state col row op]` | `[state u op]` |
| 356-358 | `with-cell` | assocs `:col :row` from `event-cell` | **renamed `with-show`**: assocs `:show-id (event-show event)` |
| 360-363 | `unless-chasing` | `(chain-path col row)` | `(chain-path show-id)` |
| 365-366 | `active-timing` | `[:playback :active-cues [col row]]` | `[:playback :active-cues (shows/play-key u)]` |
| 368-377 | `start-beat` | `[state col row from]` | `[state u from]` |

**Edit handlers**

| Line | Function | cur | new |
|---|---|---|---|
| 152-160 | `handle-apply-op` | `{:keys [col row op state]}` | `{:keys [show-id op state]}` |
| 166-211 | `handle-select-items`, `-clear-selection`, `-delete-selection`, `-select-track`, `-set-cursor-beat` | `col row`, falling back to the dialog's cell | `show-id`, falling back to the dialog's show |
| 217-221 | `handle-toggle-loop` | `col row` | `show-id` |
| 227-232 | `handle-nudge-selection` | coalesce key `[:timeline/nudge-selection col row]` | `[:timeline/nudge-selection u]` |
| 238-255 | `handle-update-tracks` | its own `chain-path [:chains :cue-chains [col row]]` at :244 | `(chain-path show-id)`, and it **returns `{:state state}` when the show is absent**. Today it recreates a cleared cell (§1) |
| 257-286 | `handle-set-track-muted`, `-set-track-target`, `-set-clip-target`, `-add-track`, `-add-folder` | `col row` | `show-id` |
| 303-319 | `handle-add-preset-to-track` | `col row`, 7 uses | `show-id` |
| 321-348 | `handle-add-effect-to-timeline-item` | `col row`, 6 uses | `show-id` |

**Transport handlers**

| Line | Function | cur | new |
|---|---|---|---|
| 379-398 | `handle-play` | key `[col row]` at :391, :395 | `(shows/play-key show-id)` |
| 400-405 | `handle-pause` | `[… [col row] :paused?]` at :404 | the play key |
| 407-413 | `handle-toggle-play` | `col row` | `show-id` |
| 415-418 | `handle-stop` | `deactivate-cue [col row]` | the play key |
| 420-428 | `handle-seek` | its own fallback at :424; keys at :427-428 | `event-show`; the play key |

**Open, chase and routing**

| Line | Function | cur | new |
|---|---|---|---|
| 439-471 | `handle-open-editor` | converts or normalizes a cell, then opens the dialog | **deleted**, together with `:timeline-cue-editor/open` (:625). Two parts move to NEW `shows/handle-open-editor`: its normalize-on-open backfill (:467-471) and opening the browser on an empty timeline (:460) |
| 484-538, 540-568 | `versioned`, `unversioned`, `reconcile-chase-sources`, `wrap-normalize-timelines` | walk `[:chains :cue-chains]` with cells as keys | S2 re-points them at `[:chains :shows]` with keys `(shows/play-key id)`. This isn't editor addressing; it's listed here so the same commit carries it |
| NEW | `handle-set-name {:show-id :name}` | — | Sets `:name`, or removes it for `""`, **only if the show exists**. Sets `[:ui :dialogs :timeline-cue-editor :editing-name?]` to false, as `cue_chain.clj:192` does |
| NEW | `handle-set-cue-target {:show-id :target}` | — | `op-effects` with NEW op `{:op :set-cue-target :target t}`. `:inherit` or `nil` clears it |
| 574-630 | `handle` | routes `:timeline-cue-editor/open` | routes `:timeline/set-name` and `:timeline/set-cue-target`, and drops :625. The transport lines :603-606 call `with-show` |

### 4.2 `laser-show.timeline.model` (existing)
- **`apply-op*`** (`timeline/model.clj:629-684`): NEW `:set-cue-target` does `(if t (assoc cue :target t) (dissoc cue :target))`. The handler has already normalized `t` with `targets/normalize-target`.
- **`op-types`** (:686-693) gains the new op.
- **`op-label`** (:712-743) gains `:set-cue-target "Change Show Target"`.

### 4.3 `laser-show.views.dialogs.timeline-cue-editor` (existing)

| Line | cur | new |
|---|---|---|
| 1-11 | ns doc: "cue chain editor", "Trigger mode picker" | "the editor of one library show"; no trigger mode |
| 34-35 | `{:keys [col row]}` from the dialog data | `{:keys [show-id]}` |
| 39 | `subs/cue-cell-chain col row` | NEW `subs/show-cue show-id` (nil when the show is gone) |
| 42 | `subs/timeline-ui col row` | `subs/timeline-ui show-id` |
| 48 | `common/default-name col row` ("Cell A1") | the literal placeholder `"Untitled"` (`01` §3.1) |
| 60-61 | `:col col :row row` | `:show-id show-id` |
| 73-76 | `:cue-chain/set-name {:dialog-id :col :row}` | NEW `:timeline/set-name {:show-id}` |
| 82-84 | `load-warning/cue-load-warning :col :row` | `:cue-key (shows/play-key show-id)` (§4.10) |
| 91-99 | Target field: `:inherited (targets/cue-fallback nil (column-target col) default col)`, `:on-set :cue-chain/set-target {:col :row}` | `:inherited (targets/cue-fallback nil nil default)`, `:on-set {:event/type :timeline/set-cue-target :show-id show-id}` |
| 101-104 | `pattern-field :col :row` | `pattern-field :cue-key (shows/play-key show-id)` (§4.11) |
| 105-108 | `common/trigger-mode-dropdown` | **removed** (§5.1) |
| 111-119 | "Open in Cue Chain Editor" | **removed** (§5.1) |
| 130-138 | `handle-space` → `:timeline/toggle-play`, with no address | unchanged: `event-show` supplies the dialog's show |
| 143-147 | title `(common/window-title "Timeline Editor" chain-name col row)`, e.g. "Timeline Editor - Intro (Cell A1)" | `(str "Timeline Editor - " (or (not-empty name) "Untitled"))`; `"Timeline Editor - (deleted)"` when `show-cue` is nil |
| NEW | — | when `show-cue` is nil, the content is the placeholder label `#timeline-show-gone` "This show is no longer in the library." plus the Close button, instead of the editor (§5.3) |

### 4.4 `laser-show.views.components.visual-editors.timeline-editor` (existing)

**Canvas gestures**

| Line | Fn | cur | new |
|---|---|---|---|
| 370-386 | `on-press` | `{:keys [col row view]} value`; events `{… :col col :row row}` (select-track, select-items, seek, clear-selection, set-cursor-beat) | `{:keys [show-id view]}`; `:show-id show-id` |
| 400-406 | `on-release` | seek, apply-op, select-items and select-track with `:col :row` | `:show-id` |
| 422-453 | `on-key` | toggle-play :426, clear-selection :431, apply-op :435, select-items :440, delete-selection :445, nudge :448 and :453 | `:show-id` |
| 465-474 | `drop-at` | opts `{:col (:col value) :row (:row value) …}` at :472 | `{:show-id (:show-id value) …}` |

**Clock-driven redraws**

| Line | Fn | cur | new |
|---|---|---|---|
| 543-544 | `redraw-overlay!` | `(show-time/cue-beats snapshot [col row])` | `(show-time/cue-beats snapshot (shows/play-key show-id))` |
| 555, 564 | `overlay-canvas` | props, `:user-data {:col :row …}` | `:show-id` |
| 601-602 | `redraw-readout!` | as :544 | the play key |
| 616, 624 | `readout` | props, user-data | `:show-id` |

**Toolbar and panels**

| Line | Fn | cur | new |
|---|---|---|---|
| 673-796 | `toolbar` | seek, toggle-play, stop, readout props, apply-op (tempo lock), add-track, add-folder and toggle-loop, all with `:col :row` | `:show-id` |
| 814, 846 | `detail-panel` | props; `device-chain :col :row` | `:show-id` |

**`editor-body` and `timeline-editor`**

| Line | Fn | cur | new |
|---|---|---|---|
| 871 | `editor-body` keys | `col row` | `show-id` |
| 877 | `editor-body` explain | `subs/cue-target-explain col row` | NEW `subs/show-target-explain show-id` |
| 880 | `editor-body` transport | `subs/timeline-transport col row` | `subs/timeline-transport show-id` |
| 900 | `editor-body` tracks-ui | `subs/list-ui-state [:timeline-tracks col row]` | `[:timeline-tracks show-id]` |
| 911-1024 | `editor-body` | props to toolbar, header-column, canvas `:value`, overlay, browser, detail-panel and inspector | `:show-id show-id` |
| 1027-1050 | `timeline-editor` | `{:keys [col row]}`; `:initial-state {:cell [col row] …}` (:1044) | `{:keys [show-id]}`; `:initial-state {:show-id show-id :view initial-view :follow? true :renaming-id nil}`. Opening another show resets zoom and scroll, as switching cells does today |

### 4.5 `…timeline.header` (existing)

| Line | Fn | cur | new |
|---|---|---|---|
| 32-33 | `apply-op!` | `[col row op]` | `[show-id op]` |
| 35-38 | `track-on-set` | `[col row r]` | `[show-id r]` |
| 42-120 | `target-menu-items`, `context-menu`, `name-node`, `target-chip` | `col row` | `show-id` |
| 131-134 | `header-drop` | user-data `{:keys [col row track-id]}`; opts `{:col :row}` | `{:keys [show-id track-id]}`; `{:show-id show-id}` |
| 136-145 | `track-list` | `{:component-id [:timeline-tracks col row] :items-path [:chains :cue-chains [col row] :tracks] :on-change-event :timeline/update-tracks :on-change-params {:col col :row row}}` | `{:component-id [:timeline-tracks u] :items-path (conj (shows/show-path u) :tracks) :on-change-event :timeline/update-tracks :on-change-params {:show-id u}}`. `:list/perform-drop` (`events/handlers/list.clj:145-165`) merges `:on-change-params` into the event it sends, so `:timeline/update-tracks` receives `:show-id` |
| 174-242 | `header-row` | keys; `:user-data {:col col :row row :track-id track-id}`; select-track event; collapse and mute ops | `show-id` |
| 245-276 | `header-column` | docstring; add-track event; pane `:user-data {:col col :row row}` | `show-id`; `{:show-id show-id}` |

### 4.6 `…timeline.inspector` (existing)

| Line | Fn | cur → new |
|---|---|---|
| 18-19 | `apply-op!` | `[col row op]` → `[show-id op]` |
| 70-112 | `clip-section` | the move, trim-end and set-clip-track ops, and `:timeline/set-clip-target {:col :row :item-id}` → `:show-id` |
| 115-121 | `clips-section` | `:timeline/delete-selection {:col :row}` → `:show-id` |
| 123-149 | `track-section` | the set-track-target event, and the mute and colour ops → `:show-id` |
| 180-198 | `chase-toggle` | `[col row chase]` → `[show-id chase]`. S4 then replaces its ops with `:shows/set-timecode` (`07` §4.11) |
| 200-279 | `timeline-section` | 10 ops → `show-id` |
| 281-283 | `inspector` docstring | ":col :row" → ":show-id" |

### 4.7 `…timeline.device-chain` (existing)

| Line | Fn | cur → new |
|---|---|---|
| 22-26 | `op-event` | `[col row target op]` → `[show-id target op]` |
| 39-46 | `effect-card` | keys and `ev` → `show-id`. The `:timeline-items` and `:timeline-tracks` domains are unchanged. The dialog's effect UI, `[:ui :dialogs :timeline-cue-editor :effect-ui ui-key]`, stays as it is: per dialog, not per show, just as it is per dialog and not per cell today |
| 81 | `:entity-key [col row]` | `:entity-key (shows/play-key show-id)`, which `chain-config` resolves (§4.9) |
| 105-116 | `chain-drop` | `[e target col row]`, opts `{:col :row}` → `show-id` |
| 131-187 | `device-chain` | props, the two `chain-drop` calls, card props → `show-id` |

### 4.8 `…timeline.browser`, `…timeline.drop` (existing)

**`browser`** (`browser.clj:22-25, :39, :44, :65`):
- Its props take `:show-id`.
- `:cell [col row]` at :39 is **removed**. `preset-bank` reads `:cell` only to build a default template when it isn't given one (`views/components/preset_bank.clj:41`), and the browser always gives one.
- Its templates are `{:event/type :timeline/add-preset-to-track :show-id … :track-id …}` and `{:event/type :timeline/add-effect-to-item :show-id …}`.

**`drop-target`** (`drop.clj:67-81`):
- Opts become `{:keys [show-id] :as opts}`, and `base` becomes `{:show-id show-id :item-id …}` (:77).
- `preset-target`'s `row` (:27) is the hit's layout row, so it stays unchanged.

**`gesture.clj`** needs no change.

### 4.9 `laser-show.events.handlers.chain.helpers`, `laser-show.state.extractors` (existing)
- **`chain-config`** (`chain/helpers.clj:38`): the base path becomes `(case domain (:timeline-items :timeline-tracks :cue-zone-patterns) (shows/cue-path entity-key) [:chains domain entity-key])`. A grid cell key gives today's path, so the cue chain editor and the pattern window on a cue are unchanged.
- **`zone-pattern-items-path`** (:55-65) also accepts `(shows/play-key? entity-key)` for `:cue-zone-patterns`.
- **`resolve-items-path`'s `:timeline-cue-chain`** (`state/extractors.clj:38-40`) is **deleted, not renamed**. Nothing produces that component id: `grep -rn timeline-cue-chain` finds only this line and the plans. The `[:timeline-show id]` replacement in `02` S2 isn't needed.

### 4.10 `laser-show.subs`, `views.components.load-warning`, `services.show-clock` (existing)

| Line | cur | new |
|---|---|---|
| `subs.clj:249-253` `cue-cell-chain` | unchanged for the grid | NEW `show-cue [context u]` → `(fx/sub-val context get-in [:chains :shows u])`. It returns **nil** when the show is absent, with no `{:items []}` default, so the dialog can tell that the show is gone |
| `subs.clj:258-263` `timeline-ui` | `[context col row]`, `[:ui :timeline-editor [col row]]` | `[context u]`, `[:ui :timeline-editor u]` |
| `subs.clj:271-279` `timeline-transport` | `(get active-cues [col row])` | `(get active-cues (shows/play-key u))` |
| `subs.clj:1308-1315` `cue-target-explain` | grid only; the cue chain editor uses it too | unchanged. NEW `show-target-explain [context u]` → `(targets/explain-cue (show-cue u) nil (default-target))` |
| `subs.clj:577-590` `cue-warning [context col row]` | reads `[:backend :streaming :cue-warnings [col row]]` and `[:chains :cue-chains [col row]]` | NEW `key-cue-warning [context k]`, using `(shows/cue-path k)`. `cue-warning` becomes `(key-cue-warning context [col row])` for the grid cell |
| `load_warning.clj` `cue-load-warning` | props `:col :row` | prop `:cue-key k`. The cue chain editor passes `[col row]` |
| `subs.clj` `cue-zone-pattern`, `zone-pattern-at` | `[:chains :cue-chains [col row] :zone-patterns]` | take `k`; the path is `(conj (shows/cue-path k) :zone-patterns)` (§4.11) |
| `subs.clj` `zone-patterns-in-use?` | scans `[:chains :cue-chains]` | also scans `[:chains :shows]`, so a show's pattern makes the pattern pads show |
| `services/show_clock.clj:106-115` `edited-cells` | `[col row]` from both dialogs | the cue chain editor's `[col row]`, plus the timeline editor's `(shows/play-key show-id)` (`03` §4) |

### 4.11 Zone patterns of a show

This is the path from the footer's Pattern chip to the pattern window and the step grid. The entity-key for `:cue-zone-patterns` becomes a playback key `k` at every site:

| Site | cur | new |
|---|---|---|
| `views/components/zone_pattern_editor.clj` `pattern-field` (:531-575) | props `:col :row`; `subs/cue-zone-pattern col row`; `:zone-patterns/set-cue-pattern {:col :row}`; step grid target `:entity-key [col row]` (:567, :570); Edit pattern… `:data {:col :row}` | prop `:cue-key k`; `cue-zone-pattern k`; `{:cue-key k}`; `:entity-key k`; `:data {:cue-key k}`. The cue chain editor passes `[col row]` |
| `events/handlers/zone_patterns.clj` `handle-set-cue-pattern` | `[col row]`; path `[:chains :cue-chains [col row]]` | `cue-key`; path `(shows/cue-path cue-key)` |
| `views/dialogs/zone_pattern_editor.clj` | dialog data `{:col :row}`; title "Zone Pattern — Cue A1 \"Beams\"" | `{:cue-key k}`; a show's title is "Zone Pattern — Show \"<name or Untitled>\""; `:entity-key k`; Remove sends `{:cue-key k}` |
| `events/handlers/step_grid.clj` `with-first-pattern` | `(get-in state [:chains :cue-chains entity-key])` (:219) | `(get-in state (shows/cue-path entity-key))` |
| `views/dialogs/step_grid_editor.clj` `window-title` | `[col row] entity-key`, `cue-cell-chain col row` | a show key gives the title "Step Grid — Show \"<name>\" · Zone pattern", from `show-cue` |
| `events/show_lock.clj` `pattern-edit?` | `:cue-zone-patterns true` | unchanged: same domain |
| the render, `services/frame_service.clj` `stage-patterns` | `(:zone-patterns cue-chain)` | unchanged: a show is a cue map |

## 5. State placement and decisions

| Key / path | Lives in | Written by | Undo/dirty? |
|---|---|---|---|
| `[:ui :dialogs :timeline-cue-editor]` `{:open? :show-id :editing-name? :effect-ui}` (was `:col :row`) | UI | `:ui/open-dialog`, via `shows/handle-open-editor`; `:timeline/set-name`; `:ui/update-dialog-data` (`:editing-name?`) | no / no. `:ui/open-dialog` **merges** (`events/handlers/ui.clj:31-43`), so `:effect-ui` survives a switch, as it does today |
| `[:ui :timeline-editor u]` | UI, session | `view-assoc` | no / no. Reset on New and Open (`04` §4.3) |
| `[:list-ui [:timeline-tracks u]]` | UI | `select-track-in-sidebar`, the list handlers | no / no; reset on Open (`persistent.clj:286`) |
| `[:chains :shows u :name]` | document | `:timeline/set-name`, and S3's `:shows/set-info` | yes / yes, "Rename Show" |
| `[:chains :shows u :target]` | document | `:timeline/set-cue-target` | yes / yes, "Change Show Target" |
| `[:chains :shows u :zone-patterns]` | document | `:zone-patterns/set-cue-pattern {:cue-key [:show u]}`, and chain, modulator and keyframe events | yes / yes, as for cues |
| View-local `{:show-id :view :follow? :renaming-id}` | `fx/ext-state` (`timeline_editor.clj:1042-1049`) | the editor | never app state |

### 5.1 Footer decisions

| Control | Decision | Why |
|---|---|---|
| **Target** | Keep, with no column level | `cue-fallback` and `explain-cue` already work without a column, and after S1 a show's render passes no column target (`03` §4). The field's Inherit shows the show default, or "All" when there is none. Writes go through NEW `:timeline/set-cue-target`, which joins the show lock's `blocked-events` next to `:cue-chain/set-target` |
| **Pattern** | Keep, keyed by `[:show u]` (§4.11) | Migrated shows can hold `:zone-patterns`, which render. Hiding the chip would leave them playing but impossible to edit. Re-keying the domain to a playback key keeps one domain, so `pattern-edit?` and the step grid's rules hold unchanged |
| **Trigger mode** | Remove | Only the grid reads `:trigger-mode` (`events/handlers/grid.clj:19-33`). A show is started from the tab, the editor or a trigger (`:toggle-show`, TS-11), and none of those reads it |
| **Open in Cue Chain Editor** | Remove | It opens `:cue-chain/open-editor` on the cell (:117-119), and a show has no cell |
| **Load warning** | Keep, keyed by `[:show u]` (§4.10) | `00`–`02` miss it. Warnings are keyed by the run's key |
| **Name** | Keep, with NEW `:timeline/set-name` | `:cue-chain/set-name` writes to a cell. `02` S2 names `:shows/set-info`, but that event is only built in S3 |

### 5.2 Dialog data, title, initial state

```clojure
;; [:ui :dialogs :timeline-cue-editor]
{:open? true :show-id #uuid "6f1c…" :editing-name? false
 :effect-ui {:items {:ui-modes {[0] :numeric}}}}
;; title
"Timeline Editor - One More Time"   ; :name "One More Time"
"Timeline Editor - Untitled"        ; no :name
"Timeline Editor - (deleted)"       ; show-cue nil
;; ext-state initial state
{:show-id #uuid "6f1c…" :view {:zoom 50.0 :scroll-beat 0.0 :scroll-y 0.0 :width 0.0 :height 0.0}
 :follow? true :renaming-id nil}
```

### 5.3 A show that goes while its editor is open

| Cause | Behaviour |
|---|---|
| `:shows/delete` (S3) | The dialog closes in the same state (`02` S3) |
| Undo or redo removes it | The dialog stays open. `show-cue` is nil, so it shows the placeholder (§4.3) and the "(deleted)" title, and `prune-active-cues` stops its run (`03` §4). An undo or redo that brings the show back restores the editor with the same `:show-id`, selection and cursor |
| New, Open | `reset-session` closes every dialog (`state/persistent.clj:292`) |
| Any `:timeline/*` edit that names a missing show | Nothing changes: `{:state state}`. Every handler guards on `(get-in state (chain-path u))`, `handle-update-tracks` and `handle-set-name` included |

## 6. Events and effects

All the handlers are pure and return `:state`. There are **no new effect keys**. Undo labels are unchanged except for the two NEW events.

**Editor preferences (unchanged, no address)**

| Event | Shape | Dispatched from (file:line) |
|---|---|---|
| `:timeline/set-snap` | `{:enabled? :toggle? :value}` | `timeline_editor.clj` toolbar |
| `:timeline/toggle-inspector` | `{}` | toolbar |
| `:timeline/set-detail-height` | `{:height 420.0}` | detail panel |
| `:timeline/set-ruler-mode` | `{:mode :time}` | toolbar |
| `:timeline/toggle-add-panel` | `{}` | toolbar |
| `:timeline/set-add-panel-tab` | `{:panel :preset :tab-id …}` | `browser.clj` |

**Selection, editing and tracks**

| Event | Before | After | Dispatched from (file:line) |
|---|---|---|---|
| `:timeline/select-items` | `{:col 0 :row 0 :ids [c] :mode :replace}` | `{:show-id u :ids [c] :mode :replace}`; fallback | `timeline_editor.clj` press, release, keys |
| `:timeline/clear-selection` | `{:col 0 :row 0}` | `{:show-id u}`; fallback | `timeline_editor.clj` |
| `:timeline/delete-selection` | `{:col 0 :row 0}` | `{:show-id u}` | `timeline_editor.clj`; `inspector.clj:121` |
| `:timeline/select-track` | `{:col 0 :row 0 :track-id t}` | `{:show-id u :track-id t}`; fallback | `timeline_editor.clj`; `header.clj` |
| `:timeline/set-cursor-beat` | `{:col 0 :row 0 :beat 4.0}` | `{:show-id u :beat 4.0}`; fallback | `timeline_editor.clj` |
| `:timeline/nudge-selection` | `{:col 0 :row 0 :delta-beats 1.0}` | `{:show-id u :delta-beats 1.0}`; coalesce key `[:timeline/nudge-selection u]` | `timeline_editor.clj` keys |
| `:timeline/toggle-loop` | `{:col 0 :row 0}` | `{:show-id u}` | toolbar |
| `:timeline/apply-op` | `{:col 0 :row 0 :op {:op :set-tempo :bpm 128.0}}` | `{:show-id u :op …}` | `timeline_editor.clj`; `header.clj:33`; `inspector.clj:19`; `device_chain.clj:22-26` |
| `:timeline/update-tracks` | `{:col 0 :row 0 :items [...] :history/description "Reorder Items"}` | `{:show-id u :items [...] …}` | the list handlers (`events/handlers/list.clj`), using `header.clj`'s `:on-change-params` |
| `:timeline/set-track-muted` | `{:col :row :track-id :muted?}` | `{:show-id …}` | no view (the header and inspector use the `:set-track-muted` op); tests |
| `:timeline/add-track` | `{:col 0 :row 0 :target?}` | `{:show-id u :target?}` | toolbar; `header.clj` |
| `:timeline/add-folder` | `{:col 0 :row 0}` | `{:show-id u}` | toolbar |
| `:timeline/set-track-target` | `{:col 6 :row 3 :track-id :t1 :target :zone/a}` | `{:show-id u :track-id :t1 :target :zone/a}` | `header.clj:38`; `inspector.clj` |
| `:timeline/set-clip-target` | `{:col 6 :row 3 :item-id :c1 :target :graphics}` | `{:show-id u …}` | `inspector.clj` |
| `:timeline/add-preset-to-track` | `{:col 0 :row 0 :item-id :square :track-id t :start 8.25}` | `{:show-id u …}` | `browser.clj`; `drop.clj` |
| `:timeline/add-effect-to-item` | `{:col 0 :row 0 :item-id :scale :target-item-id c :index 1 :select-target? true}` | `{:show-id u …}` | `browser.clj`; `drop.clj`; `device_chain.clj` |

**Transport**

| Event | Before | After | Dispatched from (file:line) |
|---|---|---|---|
| `:timeline/play` | `{:col 2 :row 1 :from 3.0}` | `{:show-id u :from 3.0}`; fallback; writes `[:playback :active-cues [:show u]]` | `events/handlers/triggers.clj:172` (S2 step 5: `[:show id]` targets) |
| `:timeline/pause` | `{:col 0 :row 0}` | `{:show-id u}`; fallback | nothing in `src/` (it goes through toggle-play) |
| `:timeline/toggle-play` | `{:col 0 :row 0}` or `{}` | `{:show-id u}` or `{}` | `timeline_editor.clj`; `timeline_cue_editor.clj:138` |
| `:timeline/stop` | `{:col 2 :row 1}` | `{:show-id u}`; fallback | `timeline_editor.clj`; `triggers.clj:175` |
| `:timeline/seek` | `{:col 0 :row 0 :beat 6.0}` | `{:show-id u :beat 6.0}`; fallback | `timeline_editor.clj` |

**Removed and new**

| Event | Before | After | Dispatched from (file:line) |
|---|---|---|---|
| `:timeline-cue-editor/open` | `{:col 1 :row 1}` | **removed**, along with its route (`events/handlers.clj:80`) | `grid_cell.clj:321, :344`; `cue_chain_editor.clj:455`; `grid.clj:188` (all removed in S2 step 4) |
| `:timeline/set-name` | — | **NEW** `{:show-id u :name "Intro"}`; "Rename Show" in `event-descriptions` (`events/handlers/history.clj:213`) | `timeline_cue_editor.clj:73` |
| `:timeline/set-cue-target` | — | **NEW** `{:show-id u :target :zone/a}`; label from `op-label`; joins `blocked-events` | `timeline_cue_editor.clj` Target field |

**Zone patterns and effect chains**

| Event | Before | After | Dispatched from (file:line) |
|---|---|---|---|
| `:zone-patterns/set-cue-pattern` | `{:col 0 :row 0 :style :chase}` | `{:cue-key [0 0] :style :chase}` or `{:cue-key [:show u] …}` | the pattern-field component; the pattern window |
| `:chain/*`, `:modulator/*`, `:keyframe/*` on `:timeline-items` or `:timeline-tracks` | `{:domain :timeline-tracks :entity-key [0 0] …}` | `{:domain :timeline-tracks :entity-key [:show u] …}` | `device_chain.clj:78-87` → `effect_parameter_editor.clj` |

## 7. Data examples and invariants

**`event-show`**

| Event | Dialog | Result |
|---|---|---|
| `{:show-id u1}` | `{:open? true :show-id u2}` | `u1` |
| `{}` | `{:open? true :show-id u2}` | `u2` |
| `{}` | `{:open? false}` | nil, so the handler changes nothing |
| `{:col 0 :row 0}` (an old caller) | `{:show-id u2}` | `u2`. `:col` and `:row` are ignored and never become a path |

**`shows/cue-path` and `chain-config`**

| Call | `:items-path` |
|---|---|
| `(chain-config :timeline-items [:show u])` | `[:chains :shows u :items]` |
| `(chain-config :timeline-tracks [:show u])` | `[:chains :shows u :tracks]` |
| `(chain-config :cue-zone-patterns [:show u])` | `[:chains :shows u :zone-patterns]` |
| `(chain-config :cue-zone-patterns [2 1])` | `[:chains :cue-chains [2 1] :zone-patterns]`, as today |
| `(chain-config :effect-chains [0 0])` | `[:chains :effect-chains [0 0] :items]`, as today |

**Handlers**

| State | Event | After |
|---|---|---|
| show `u` with clip `c`; `[:ui :timeline-editor u :selection] #{c}` | `{:event/type :timeline/delete-selection :show-id u}` | `c` is gone from `[:chains :shows u :items]`; the selection is `#{}`; label "Delete Clip" |
| `u` stopped, cursor at 6.0 | `{:event/type :timeline/play :show-id u}` | `[:playback :active-cues [:show u]]` is `{:run r :timeline? true :start-beat 6.0}`; no `[col row]` key is written |
| show `u` absent | `{:event/type :timeline/update-tracks :show-id u :items [t]}` | `{:state state}`, identical; no `[:chains :shows u]` is created |
| default target `:zone/a` | footer Target for a show `u` without `:target` | `:inherited {:target :zone/a :from :show}`; with no default, `{:target :all :from :default}` |
| `u` playing, with a cue warning | a tick | `[:backend :streaming :cue-warnings [:show u]]` is set, and `#cue-load-warning` is visible in `u`'s editor |

**Invariants**
- No `:timeline/*` handler reads or writes `[:chains :cue-chains]`, and none reads `:col` or `:row` from an event.
- No `:timeline/*` handler creates a show. Every write goes to a `[:chains :shows u]` that already exists.
- The editor's playback key is always `(shows/play-key u)`. It is built only in `active-timing`, `subs/timeline-transport`, the two clock redraws and `edited-cells`.
- The only `[:ui :timeline-editor]` and `[:list-ui]` keys the editor builds are `u` and `[:timeline-tracks u]`.
- Grid behaviour is unchanged. Every grid call site passes `[col row]` to the generalized functions and gets today's paths back.

## 8. Tests (named test vars + fixtures)

### 8.1 Existing tests that change

**The fixture.** `show-state` is `(assoc-in base-state [:chains :shows u] cue)`, with `u` a fixed `#uuid`. It replaces `(assoc-in base-state [:chains :cue-chains [0 0]] cue)`. Assertions read these paths instead of the cell ones:
- `[:chains :shows u …]`
- `[:ui :timeline-editor u …]`
- `[:list-ui [:timeline-tracks u]]`
- `[:playback :active-cues [:show u]]`

**Handler tests, `T/events/timeline_handlers_test.clj`**

| Test (line) | Change |
|---|---|
| `handle-open-editor-test` :19, and the open-editor tests after it | Move to NEW `laser-show.events.shows-handlers-test` as `open-editor-*` and `new-from-cue-*`, because the handler moves (§4.1). Conversion becomes `:shows/new-from-cue` |
| `ui-state-is-per-cell-test` :105 | Rename to `ui-state-is-per-show-test`. Use two shows, `u1` and `u2`; the fallback case uses the dialog `{:open? true :show-id u2}` |
| the other tests and helpers in the file | `:col 0 :row 0` → `:show-id u`, on the `show-state` fixture. Tests that created the cue with `:timeline-cue-editor/open` use `show-state` with `(model/normalize-timeline-cue {:editor-type :timeline :items []})` instead |
| `reconcile-chase-sources-test` :455 | Keys become `[:show u]` and cues move under `[:chains :shows]` (the S2 re-point) |

**Other unit tests**

| Test | Change |
|---|---|
| `T/events/history_handlers_test.clj`: the tests that open or edit a timeline | `:timeline-cue-editor/open` → `:shows/new-from-cue {:col 0 :row 0}` or `show-state`; `:col :row` → `:show-id`. The nudge coalesce assertion uses `[:timeline/nudge-selection u]` |
| `T/animation/show_time_test.clj`: the timeline cases | Unchanged in S1, because grid timelines still play then (`03` §10). In S2 their cues move to `[:chains :shows u]`, events take `:show-id u`, and `positions` is keyed by `[:show u]`. The grid-cue cases in the same file stay |
| `T/services/show_clock_test.clj` `edited-cue-names-its-heaviest-item-test` (:649) | The heavy cue becomes show `u`, played under `[:show u]`, with dialog `{:open? true :show-id u}`. It asserts `[[:show u] :item]`, and the "another show's editor" case uses `u2` |
| `T/events/grid_handlers_test.clj` `handle-grid-open-cell-editor-test` | Expects `:cue-chain/open-editor` every time (S2 step 4) |
| `T/events/handlers/triggers_test.clj` `start-timeline-action-emits-play-test` | A `[:show u]` target → `{:event/type :timeline/play :show-id u}`; a `[2 1]` target → nil (`02` S2 step 5) |
| `T/events/show_lock_test.clj` structure edits | `:show-id u`; also add `{:event/type :timeline/set-cue-target :show-id u :target :zone/a}` |
| `T/subs_test.clj` `timeline-dialog-subs-ignore-playback-and-other-cells-test` | Rename to `…-other-shows-test`. Use `subs/show-cue u1` and `subs/timeline-ui u1`; the other edit goes to `[:chains :shows u2 :name]` and `[:ui :timeline-editor u2]` |
| `T/views/child_context_retention_test.clj`, the timeline state | Dialog `{:open? true :show-id u}`, the show at `[:chains :shows u]`, and `[:ui :timeline-editor u]` |
| `T/views/components/visual_editors/timeline/drop_test.clj` | `drop-target` opts `{:show-id u}` |

**UI tests**

| Test | Change |
|---|---|
| **All 28 vars** in `T/ui_test/timeline_editor_test.clj` (:33 to :768), with the helpers `open-timeline!` :81 and `switch-cell!` :406 | Open with `:shows/new-from-cue {:col 0 :row 0}`. This copies and converts the starter cue at `[0 0]`, so the zone folder, clips and content width the tests rely on are all there. `switch-cell!` becomes `switch-show!`, which opens a second show made the same way from `[1 0]`. Paths become `[:chains :shows u …]`, `[:ui :timeline-editor u]`, `[:list-ui [:timeline-tracks u]]` and `[:playback :active-cues [:show u]]`. The "empty cell" case becomes `:shows/create` then `:shows/open-editor` |
| `T/ui_test/timeline_visual_test.clj` `timeline-screenshot-baseline` | `:shows/create` + `:shows/open-editor`; events use `:show-id`. The baseline image only changes in its title |
| `T/ui_test/dialogs_test.clj`: the timeline dialog entries (opening, `editors` for undo, `timeline-space-still-toggles-play-test`) | Open with `:shows/new-from-cue`. The edit becomes `{:event/type :timeline/set-name :show-id u}`, and the value is read from `[:chains :shows u :name]` |
| `T/ui_test/keyboard_trigger_test.clj`, `cue_load_test.clj`, `zones_test.clj`, `zone_patterns_test.clj`: their timeline cases | Open from a show; use `:show-id`; timing paths use `[:show u]` |

### 8.2 NEW tests

**Unit**
- `laser-show.timeline.shows-test/cue-path-test`: the five rows of §7's `chain-config` table through `cue-path`, plus nil.
- `laser-show.events.chain-helpers-test/chain-config-show-keys-test`: §7's table, and `zone-pattern-items-path :cue-zone-patterns [:show u]` is non-nil.
- `laser-show.events.timeline-handlers-test/event-show-fallback-test`: §7's `event-show` table, through `:timeline/clear-selection`.
- `laser-show.events.timeline-handlers-test/edits-never-create-a-show-test`: for every addressed `:timeline/*` event in §6, run on `show-state` without `u`, `:state` is `identical?` to the input. The fixture is §6's event maps as data.
- `laser-show.events.timeline-handlers-test/no-handler-touches-cue-chains-test`: the same event list on `show-state` plus a grid cue at `[0 0]`. `[:chains :cue-chains]` stays `identical?`.
- `laser-show.events.timeline-handlers-test/set-cue-target-test`: `:zone/a` sets the target, `:inherit` clears it, the label is "Change Show Target", and the show lock refuses it.
- `laser-show.events.timeline-handlers-test/set-name-clears-editing-flag-test`: `""` removes `:name`, the flag goes false, and a missing show is unchanged.
- `laser-show.events.timeline-handlers-test/show-transport-uses-show-key-test` (`02` S2): play, pause, seek and stop write only `[:playback :active-cues [:show u]]`.
- `laser-show.subs-test/show-target-explain-has-no-column-test`: with column target `{0 :zone/b}` set, `:cue` is `{:from :show}`, never `:column`.
- `laser-show.events.zone-patterns-test/set-cue-pattern-on-a-show-test`: `{:cue-key [:show u] :style :chase}` adds the preset under `[:chains :shows u :zone-patterns]`; with `[:show u2]` absent, nothing changes.

**UI**
- `laser-show.ui-test.timeline-editor-test/deleted-show-shows-placeholder-test`:
  1. Open `u`, then undo its creation (`:edit/undo`).
  2. `#timeline-show-gone` is visible and the title is "Timeline Editor - (deleted)".
  3. Redo. `#timeline-lanes` is visible again, with the same cursor.
- `laser-show.ui-test.timeline-editor-test/footer-target-and-pattern-on-a-show-test`:
  - `#target-chip-cue`'s Inherit reads the show default;
  - there is no "Trigger:" label and no "Open in Cue Chain Editor" button in `.dialog-footer`.

### 8.3 A safe commit order (each step ends green)

1. **Prerequisites** (from `02`): S1, S2 step 1 (storage) and S2 step 3 (migration). Then no timeline cell is left by the time the editor stops addressing cells.
2. **The ref abstraction, with no behaviour change** (about 12 sites):
   - NEW `shows/cue-path` and `show-path`.
   - `chain-config` and `zone-pattern-items-path` (§4.9).
   - `key-cue-warning`, and `cue-load-warning :cue-key`.
   - `cue-zone-pattern`, `zone-pattern-at`, `set-cue-pattern`, the pattern window and the step grid all take `k` (§4.11). Every grid caller passes `[col row]`.
   - Delete `:timeline-cue-chain`.

   The existing suites pass unchanged, except for the zone pattern event map (`:col :row` → `:cue-key`).
3. **A minimal `events/handlers/shows.clj`** with `:shows/create`, `:shows/open-editor`, `:shows/new-from-cue` and the `"shows"` route. This is pulled forward from `02` S2 step 6, so tests and the cue chain editor can open the re-keyed editor.
4. **The editor re-key, in one commit** (about 262 lines, §1's table):
   - §4.1–4.8;
   - from §4.10: `show-cue`, `timeline-ui`, `timeline-transport`, `show-target-explain` and `edited-cells`;
   - remove `:timeline-cue-editor/open`, its four dispatchers and its route;
   - re-key every test in §8.1.

   Handlers and views can't land separately: in between, no view would address a handler.

   Within this step, edit in this order:
   1. `timeline.clj`. This is mechanical: change `col row` to `u` in the helper signatures at :50-81, :119-150 and :356-377, then in each handler.
   2. The leaf views: drop, browser, device_chain, inspector, header.
   3. `timeline_editor.clj`.
   4. The dialog.

   `grep -nE '\bcol\b'` over the eight files must end at 0.
5. **The footer:**
   - `:timeline/set-name` and `:timeline/set-cue-target`, with the model op, label and show lock entry;
   - remove the trigger mode and the cue chain button;
   - add the deleted-show placeholder;
   - add §8.2's footer, name and placeholder tests.

## 9. Out of scope / alternatives considered

### 9.1 Alternatives
- **A bare uuid as the chain entity-key, with a NEW `:show-zone-patterns` domain.** Rejected. `pattern-edit?`, the step grid and `zone-pattern-at` all dispatch on the domain, so each would need a second branch. Using a playback key as the entity-key needs only one path rule (`cue-path`).
- **Events carry `[:show u]` instead of `:show-id`.** Rejected. TS-5 and `01` use `:show-id`, and the triggers and the tab already think in ids. The key is derived only where it's needed (§7 invariants).
- **Close the dialog when undo removes its show.** Rejected: a redo would leave it closed. The placeholder keeps the window and brings it back with the show.
- **`:shows/set-info :name` from the editor.** Rejected for S2, because that event is S3's. S3's handler and `:timeline/set-name` write the same key, so S3 may make one call the other.

### 9.2 Where `00`–`02` are wrong or incomplete
- The call-site table in `00` §1 undercounts: it is 262 lines, not about 190. `timeline_editor.clj` alone has 53, not 17 (§1).
- `02` S2 sets the editor tests up with `:shows/create` and `:shows/open-editor`. An empty show has no zone folder, clips or scrollable content, and several tests need those. Use `:shows/new-from-cue {:col 0 :row 0}`.
- `02` S2 renames `:timeline-cue-chain` to `[:timeline-show id]`. It is dead code, so delete it (§4.9).
- `02` S2 says `cue-target-explain` "gets a show variant". No routing change is needed: `explain-cue` already works without a column (§3).
- `02` S2 has the name field commit `:shows/set-info :name`. That event is S3's, so use NEW `:timeline/set-name` (§5.1).
- `02` S2 keeps the pattern field but doesn't re-key the zone pattern chain. It misses 7 sites in 6 files (§4.11), plus `zone-patterns-in-use?`.
- Missing everywhere:
  - the load warning (§4.10);
  - the target write event (`:cue-chain/set-target` writes to a cell);
  - `handle-update-tracks`' own hard-coded path (`timeline.clj:244`), which also recreates a deleted cue.
- The commit order in `02` S2 doesn't work. The editor re-key (step 2) can't be reached or tested before step 6 creates `:shows/open-editor`. And timeline cells that a not-yet-written migration (step 3) would have moved lose their editor. §8.3 reorders the steps.

### 9.3 Out of scope
- **`:trigger-mode` left on migrated shows.** Nothing reads it; the migration (`01` §6) may strip it.
- **`[:ui :timeline-editor]` entries for deleted shows.** They are per session and a few bytes each. S3's `:shows/delete` removes them.
