# Effect racks in the device chain (L2, option A)

**Written:** 2026-11-11   **Written for commit:** `716688d` (branch `timeline/effects-panel`)
**Status:** Proposed   **Related:** `07-effects-panel-followups.md` L2 (this is option A); `05-visual-design.md` (2026-11-11 layout note); L1-B (drag reordering, later)

An effect group (`{:type :group :items [...]}` inside an `:effects` vector) becomes a framed **rack** in the device chain, like Ableton's Audio Effect Rack. The rack holds its children's cards, nested up to 3 levels. Racks can be switched on and off, renamed, collapsed, ungrouped, moved and removed. Users can also create racks in the timeline: select cards, then press **Group** (Ctrl+G).

## 1. Current behaviour (file:line)

- **Cards are one level deep.** `device-chain` (`device_chain.clj:88-132`) maps over the top-level `(:effects entity)`. `effect-card` (`:31-86`) titles a group "Group (n)" (`:60-61`) and shows "Edit it in the Cue Chain Editor" (`:84-85`) instead of a parameter editor. The editor is given `:effect-path [idx]` (`:77`). `:fx/key` is `(:id fx i)` (`:129`). Element ids use the top-level index, e.g. `timeline-device-name-0`.
- **Model ops** (`model.clj`):
  - `update-effects` (`:621-629`) finds the clip (any depth) or track/folder that owns the effects.
  - `:remove-effect` uses `remove-ids` (`:524-530`). It already works at any depth, because it recurses into any `:items`.
  - `:set-effect-enabled` uses `update-items-by-id` (`:514-522`). It also works at any depth.
  - `:move-effect` → `move-effect` (`:631-639`) only searches the **top level**. For a nested id it does nothing.
  - There are no group, ungroup, rename or collapse ops for effects. `:set-collapsed` (`:680`) only handles tracks.
- **Parameter paths already work when nested.** `compute-full-effect-path` (`effect_parameter_editor.clj:42-51`) is `item-path ++ [:effects] ++ effect-path`. The `:chain/*` handlers build paths with generic `get-in`/`assoc-in`/`update-in` on `(:items-path (chain-config ...))`: `chain/params.clj:82-87`, `keyframe.clj:45-46`, `modulator.clj:33-35`. So `effect-path [1 :items 0]` addresses a nested effect. The UI-mode key is the full path (`effect_parameter_editor.clj:161`).
- **Rendering already handles groups.** `apply-item-effects` (`frame_service.clj:306-320`) keeps only top-level effects that pass `model/active-at?` (`:311-313`). It then calls `effects/apply-effect-chain`, which flattens via `chains/flatten-chain` (`effects.clj:277`, `chains.clj:67-88`). A disabled group skips all its children. Track and folder effects take the same path (`render-track-tree`, `frame_service.clj:521-540`).
  - Timeline windows (`:timeline/start`) **on nested effects are ignored**. No UI creates effect windows (verified: nothing in `src` writes `:timeline/start` on an effect), so this stays out of scope (§9).
- **Collapse is "live" state.** `live-item-paths` contains `[:collapsed?]` (`history.clj:43-46`). It applies to every map with an `:id` anywhere under `:chains` (`map-equiv?`, `:93-107`), so effect groups inside clips and tracks are included. When only live fields change, the history handler pushes no snapshot (`events/handlers/history.clj:222`). Collapsing is therefore not undoable and doesn't mark the project unsaved, the same as track folders.

## 2. Root cause

The device chain was built as a flat list, and the effect ops were written for flat chains. Groups only arrive through **Convert to Timeline Cue** (`handle-open-editor`, `events/handlers/timeline.clj:414-441`), from Cue Chain Editor lists created with `:allow-groups? true`.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `list-helpers/group-items-by-ids` (`list/helpers.clj:369-425`) | Body of `:group-effects`. It already refuses ids that aren't siblings (returns nil). |
| `list-helpers/ungroup-by-id` (`:428-454`) | Body of `:ungroup-effect`, after the disabled state has been pushed onto the children |
| `list-helpers/update-by-id` (`:148-168`) | Fix the new group's id or name; rename; collapse |
| `list-helpers/calculate-selection` (`:77-116`) | Card selection (click, Ctrl-click, Shift-click) |
| `chains/find-path-by-id`, `nesting-depth`, `max-nesting-depth`, `flatten-chain`, `create-group` (`chains.clj:106,43,39,67,198`) | Paths, the depth limit, invariants in tests |
| `model/update-effects`, `apply-op`, `op-label` (`model.clj:621,709,726`) | All rack edits are ops, so each is one undo entry with a label |
| `apply-op-to-state` (`events/handlers/timeline.clj:121-134`) | Used by the NEW group-selected handler |
| `effect-parameter-editor` (unchanged) | Gets a nested `:effect-path` |
| Inline rename pattern (`header.clj:87-113`) | Rack name: double-click → text field, Enter commits, Esc cancels |
| Ctrl+G in the list editor (`list.clj:772`) | Same shortcut and meaning |

## 4. Changes per namespace

### 4.1 `laser-show.timeline.model` (`model.clj`)
- `move-effect` (`:631-639`) becomes depth-aware:
  - Find `path = (chains/find-path-by-id effects id)` and its parent `(pop path)`: `[]` at the top level, `[g :items]` inside a group.
  - Apply the existing index logic to that sibling vector only.
  - **Pitfall:** `(update-in v [] f)` doesn't call `f` on `v` (it updates the `nil` key). Branch on `(empty? parent)`.
  - The op shape stays `{:op :move-effect :effect-instance-id :to idx}`, with `idx` counted within the siblings. Effects never move into or out of a rack this way; ungroup and regroup do that (§9).
- NEW private `group-effects [effects ids group-id]`:
  1. Call `(list-helpers/group-items-by-ids effects ids)`. nil → return `effects` unchanged.
  2. If a `group-id` was given, swap it in with `update-by-id`, so tests and handlers get a known id.
  3. Refuse (return `effects`) when `(chains/nesting-depth result)` > `chains/max-nesting-depth`. This is more precise than the whole-chain check in `list.clj:607-608`.
  - The group is named "New Group" (the `create-group` default, as in the Cue Chain Editor).
- NEW public `can-group-effects? [effects ids]` → `(not= effects (group-effects effects ids nil))`. The view uses it to enable the Group button, so the rule lives in one place.
- NEW private `ungroup-effect [effects id]`:
  - If `id` isn't a group, return `effects` unchanged.
  - If the group is disabled (`(false? (:enabled? g))`), set `:enabled? false` on each **direct** child first. A disabled child group still switches off its own subtree.
  - Then call `list-helpers/ungroup-by-id`.
  - Timing keys are left alone.
- NEW ops in `apply-op*` (`:644-698`). Each one goes through `update-effects`, so it accepts `:clip-id` or `:track-id`. `rename` and `collapse` only touch groups (`chains/group?`).

| Op | Shape | Label |
|---|---|---|
| `:group-effects` | `{:effect-ids #{..} :group-id uuid?}` | "Group Effects" |
| `:ungroup-effect` | `{:effect-instance-id g}` | "Ungroup Effects" |
| `:rename-effect-group` | `{:effect-instance-id g :name s}`; a blank (trimmed) name changes nothing | "Rename Effect Group" |
| `:set-effect-collapsed` | `{:effect-instance-id g :collapsed? bool}` | "Collapse Effect Group" (it never reaches the undo menu, because the field is live) |

- Add all four to `op-types` (`:700-707`) and `op-label` (`:726-757`). The existing `:remove-effect` and `:set-effect-enabled` need no change; tests in §8 pin that they work at depth.

### 4.2 `laser-show.events.handlers.timeline`
- `default-view` (`:36-38`) gains `:effect-selection {:owner-id nil :ids #{} :last-id nil}`. `owner-id` is the clip or track id the selection belongs to. The view ignores a selection whose owner isn't the current target, so nothing needs to prune it when the clip/track selection changes.
- NEW `:timeline/select-effects {:col :row :owner-id :effect-id :mode (:single|:ctrl|:shift)}`:
  - Uses `calculate-selection`. For `:shift`, the order is the **sibling** ids of the clicked effect, so a range never crosses racks.
  - No document change.
- NEW `:timeline/group-selected-effects {:col :row :owner-kind (:clip|:track)}`:
  - Reads the selection and makes `group-id (random-uuid)`.
  - Applies `{:op :group-effects ...}` via `apply-op-to-state`, with the label from `op-label`.
  - Then selects `#{group-id}`, so pressing Ctrl+G again doesn't nest the same effects one level deeper.

### 4.3 `laser-show.views.components.visual-editors.timeline.device-chain`
- NEW `chain-node [props effect path]`: dispatches to `rack` or `effect-card`.
  - `path` is the effect's path in `:effects`, e.g. `[1 :items 0]`.
  - Paths are computed **from the data on every render** and never stored.
  - `:fx/key` is always `(:id effect)`. Ids are unique, and an index key would mix up cards when a sibling is moved.
- `effect-card`:
  - Takes `:path` and `:n` (number of siblings) instead of `:idx`.
  - Passes `:effect-path path`.
  - ◀ ▶ are disabled at the sibling ends.
  - Element ids use `path-id`: the integers of the path joined by `-`, so `[0]` → `"0"` and `[1 :items 0]` → `"1-0"`. Top-level ids (`timeline-device-name-0`) are unchanged, so the existing UI tests keep working.
  - Clicking the header background or the name selects the card (`:timeline/select-effects`). Ctrl or Cmd toggles, Shift selects a range. Clicks whose target is a button or check box are ignored.
  - A selected card gets the style class `tl-device-selected`.
  - The "Group (n)" branch and the "Edit it in the Cue Chain Editor" text are removed.
- NEW `rack` (`.tl-rack`, plus `tl-rack-off` when disabled):
  - **Header** (`.tl-rack-header`, 26 px): on/off `#timeline-rack-enabled-<pid>`; collapse chevron `#timeline-rack-collapse-<pid>`; name label `#timeline-rack-name-<pid>` (double-click to rename inline); a "(n)" count; ◀ ▶; Ungroup `#timeline-rack-ungroup-<pid>` (icon `object-ungroup`); remove ×.
  - **Expanded:** below the header is an `h-box` (`.tl-rack-body`, spacing 6) of `chain-node`s at `(conj path :items i)`.
  - **Collapsed:** header only, 200 px wide, plus one muted line listing the child names ("Scale, Rotation, Hue Shift").
  - Racks contain **no scroll panes**. Only the existing outer horizontal `scroll-pane` (`:119-131`) scrolls.
  - Rename state is a local `fx/ext-state` `:renaming-id` at the `device-chain` root, as in `header.clj:87-113`.
- **Title row:** "EFFECTS · name", a spacer, then the **Group** button `#timeline-device-group` (tooltip "Group selected effects (Ctrl+G)"). It's disabled unless `can-group-effects?`. Ctrl+G is handled by `:on-key-pressed` on `#timeline-device-chain`; clicking a card header requests focus for it.
- **Converted groups keep their stored `:collapsed?`**: the Cue Chain Editor's collapse state carries over. They aren't collapsed by default, because conversion must not change the document beyond timing.

### 4.4 `laser-show.css.timeline` (next to `.tl-device`, `:185-199`)
| Class | Style |
|---|---|
| `.tl-rack` | background `#181818`, border `#3A3A3A`, a 3 px left border in `blue` (theme), padding 4, spacing 4, no fixed width |
| `.tl-rack-off` | opacity 0.55 (as `.tl-device-off`) |
| `.tl-rack-header` | like `.tl-device-header`; the name is 11 px bold, `text-secondary` (§3.1 section header style), upper-cased in code as in `header.clj:106-107` |
| `.tl-rack-collapsed` | min/pref/max width 200 |
| `.tl-device-selected` | border colour `blue` |

Width: an expanded rack of n cards is about `n·320 + (n−1)·6 + 12` px (3 cards ≈ 990 px). Height: each level adds about 34 px (header + padding), so at depth 3 the leaf cards lose about 100 px. That's fine at the default 300 px; at the 160 px minimum, collapse racks.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `:effects` vectors with groups (clip `[... :items p :effects]`, track `[... :tracks p :effects]`) | document | `apply-op` (`:group-effects`, `:ungroup-effect`, `:rename-effect-group`, `:move-effect`, `:remove-effect`, `:set-effect-enabled`) | Yes / yes |
| group `:collapsed?` | document, **live field** (`history.clj:46`) | `apply-op :set-effect-collapsed` | No / no; kept across undo (grafted by id) |
| `[:ui :timeline-editor [col row] :effect-selection]` NEW | UI view state | `:timeline/select-effects`, `:timeline/group-selected-effects` | No |
| `:renaming-id` | local `fx/ext-state` in `device-chain` | double-click / Enter / Esc | No |
| UI modes, at `[:ui :dialogs :timeline-cue-editor :effect-ui ...]` keyed by full path | UI | `:chain/set-ui-mode` (unchanged) | No |

## 6. Events and effects

| Event / op | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:timeline/apply-op {:op :move-effect}` | changed (sibling-aware) | card or rack ◀ ▶ | yes |
| `:timeline/apply-op {:op :remove-effect}` / `:set-effect-enabled` | unchanged (already deep) | card or rack × / on-off | yes |
| `:timeline/apply-op {:op :ungroup-effect}` | NEW op | rack Ungroup | yes |
| `:timeline/apply-op {:op :rename-effect-group}` | NEW op | rack name field (Enter) | yes |
| `:timeline/apply-op {:op :set-effect-collapsed}` | NEW op | rack chevron | yes |
| `:timeline/apply-op {:op :group-effects}` | NEW op | via the handler below; tests | yes |
| `:timeline/select-effects` | NEW event | card or rack header click | yes |
| `:timeline/group-selected-effects` | NEW event | Group button, Ctrl+G | yes (uses `random-uuid`) |
| `:chain/update-param` etc. with a nested `:effect-path` | unchanged | `effect-parameter-editor` | yes |

No new side-effect keys.

## 7. Data examples and invariants

Fixture (a clip's `:effects`):
```clojure
[{:id :a :effect-id :scale :enabled? true}
 {:id :g :type :group :name "Wobble" :enabled? false :collapsed? false
  :items [{:id :b :effect-id :rotation :enabled? true}
          {:id :c :effect-id :hue-shift :enabled? false}]}
 {:id :d :effect-id :translate :enabled? true}]
```
| Op | Result |
|---|---|
| `{:op :move-effect :effect-instance-id :c :to 0}` | `:g :items` → `[:c :b]`; top level unchanged |
| `{:op :move-effect :effect-instance-id :g :to 2}` | `[:a :d :g]` |
| `{:op :set-effect-enabled :effect-instance-id :b :enabled? false}` | `:b` disabled inside `:g` |
| `{:op :remove-effect :effect-instance-id :b}` | `:g :items` → `[:c]` |
| `{:op :ungroup-effect :effect-instance-id :g}` | `[a b c d]` with `:b` and `:c` `:enabled? false` (`:g` was off) |
| `{:op :group-effects :effect-ids #{:a :g} :group-id :n}` | `[{:id :n :type :group :name "New Group" :enabled? true :collapsed? false :items [:a :g]} :d]` |
| `{:op :group-effects :effect-ids #{:a :d} :group-id :n}` | `[{:id :n :items [:a :d]} :g]`. Siblings don't have to be next to each other (as in the Cue Chain Editor): the group goes at the first selected index, so `:d` now runs before `:g` |
| `{:op :group-effects :effect-ids #{:a :b}}` | unchanged (not siblings) |
| `{:op :rename-effect-group :effect-instance-id :g :name "  "}` | unchanged |
| `{:op :set-effect-collapsed :effect-instance-id :g :collapsed? true}` | `:g :collapsed? true`; no undo entry |
| `:group-effects` on an effect already at depth 3 | unchanged (would be depth 4) |

**Invariants** (checked after every op in `effect-rack-ops-keep-invariants-test`):
1. Effect ids are unique within each `:effects` vector, at every depth.
2. `(chains/nesting-depth effects)` ≤ `chains/max-nesting-depth` (3).
3. Ungroup preserves order (the children take the group's index) **and rendering**: `(chains/flatten-chain before) = (chains/flatten-chain after)`.
4. Group keeps the relative order of the grouped effects. For a contiguous selection it also preserves rendering (the new group is enabled, so `flatten-chain` is unchanged).
5. Every card's path is `(chains/find-path-by-id effects id)`, and `(get-in effects path)` is that effect (paths are never cached).
6. Unknown ids, non-group targets and refused groupings return the cue `identical?`, so there's no undo entry (`apply-op`, `:719-721`).

## 8. Tests (named test vars + fixtures)

- `laser-show.timeline.model-test` (extend; the `op-cue`, `clip`, `q` helpers are at `:310-321`, and the §7 fixture becomes `rack-fx`):
  - `move-effect-within-rack-test`
  - `effect-ops-reach-nested-effects-test` (enable and remove at depth 2 and 3, on a clip and on a folder)
  - `group-effects-test` (siblings at the top level and inside a rack, fixed `:group-id`, non-siblings, depth limit, `can-group-effects?`)
  - `ungroup-effect-test` (order, disabled propagation, `flatten-chain` unchanged, a non-group id)
  - `rename-and-collapse-effect-group-test`
  - `effect-rack-ops-keep-invariants-test` (a fixed sequence of about 20 ops; §7 invariants after each, like `apply-op-output-is-normalized-test`)
  - `op-label-test` (`:412-420`) gains the four labels. Its `doseq` over `op-types` already covers the new ops.
- `laser-show.events.timeline-handlers-test`:
  - `nested-effect-param-edit-via-chain-events-test`: like `:410-415`, with `:effect-path [0 :effects 1 :items 0]` for both `:timeline-tracks` and `:timeline-items`.
  - `select-effects-shift-stays-in-siblings-test`
  - `group-selected-effects-selects-new-group-test`
  - `effect-group-collapse-is-not-undoable-test`: through `handlers/handle-event` with the history wrapper; the undo stack length is unchanged.
- `laser-show.services.frame-service-test` (extend; `render-at`, `scale-fx`, `clip-at` at `:643-652`):
  - `grouped-track-effects-render-like-flat-test`: a track with `[{:type :group :items [scale-fx]}]` renders the same as `[scale-fx]`. A disabled group renders the same as no effects. The same holds in a folder.
  - `grouped-clip-effects-render-like-flat-test`
- UI `laser-show.ui-test.timeline-editor-test`:
  - `timeline-converted-group-shows-rack`: put a plain cue in `[0 0]` (one `:square` preset whose `:effects` hold the §7 group), call `open-timeline!` (which converts it) and select the clip. Then: `#timeline-rack-name-1` is "WOBBLE"; `#timeline-device-name-1-0` is "Rotation"; clicking `#timeline-rack-enabled-1` enables `:g`; clicking `#timeline-rack-ungroup-1` gives 4 top-level effects with `:c` still off.
  - `timeline-group-cards-with-ctrl-g`: add Scale and Rotation from the browser (as `:309-328`), click card 0, Ctrl/Cmd-click card 1, press Ctrl+G, then `#timeline-rack-name-0` is visible. Undo restores 2 flat effects.
- Visual `timeline-visual-test`: after `09-track-effects` (`:114`), put a group (Scale + Rotation, plus a nested collapsed group) on the circle clip with `:timeline/apply-op`, select the clip, and `(shot! "10-effect-rack")`.

## 9. Implementation steps, risks, out of scope

**Steps** (each one a separate commit):
1. Model ops, `can-group-effects?`, model tests (~0.5 day).
2. Handler tests for nested parameter paths, plus frame-service tests (~1 h; no source change expected. If one fails, fix it here).
3. Recursive racks in `device_chain.clj` and CSS: render, on/off, collapse, ungroup, move, remove, rename. UI test `timeline-converted-group-shows-rack` (~0.5–0.75 day).
4. Card selection, the Group button, the two new events and handler tests, UI test `timeline-group-cards-with-ctrl-g` (~0.5 day). *Ctrl+G is optional within this step; the button alone is enough.*
5. Screenshot `10-effect-rack`, plus a line in the `05` layout note (~1 h).

**Risks:**
- **Nested scroll panes.** Each parameter editor already has a vertical scroll pane inside the horizontal row. Racks add none, but scroll-wheel events over a card scroll the inner pane, not the row (existing behaviour).
- **Key stability.** Keys by `:id` keep cards when siblings move. Group and ungroup change a card's parent, so cljfx recreates it, and the editor's local state (spatial canvas atoms) resets. That's acceptable.
- **UI modes follow the path, not the effect.** After a move, group or ungroup, a card's visual/numeric tab can swap with another card's. This already happens with `:move-effect`. Fixing it (keying by id) is out of scope.
- **Performance.** Every expanded leaf builds a full `effect-parameter-editor` (keyframe panel and controls). Racks make 10+ cards likely. Collapsed racks render no children. If profiling shows lag, render parameters only for selected cards (optional).
- **Header clicks vs buttons.** Clicks on buttons can still bubble up to the header. Filter by event target (§4.3) and test it in the UI test.

**Out of scope / alternatives considered:**
- Moving effects into or out of a rack except by ungroup and regroup. A future `:move-effects` op wrapping `list-helpers/move-items` (`:273`) would serve drag-and-drop (L1-B).
- Adding from the browser into a selected rack (effects still append to the top level).
- Copy/paste of effects and racks; Delete key for selected cards.
- Honouring `:timeline/start` windows on nested effects (no UI creates them).
- The Cue Chain Editor's own ungroup (`list.clj` `handle-ungroup`) doesn't push "disabled" onto the children. It could reuse `model`'s rule later.
- Rack macros (Ableton's mapped knobs).
- **"Group with next" button**, rejected: three effects need two clicks and produce nested racks.
- **Option B** (a structure list beside the cards), not chosen: it's less Ableton-like, though cheaper (`07` L2).
