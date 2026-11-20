# Phase Z0: fix-ups that every design needs

**Written:** 2026-11-19 at `d298801`. **Reviewed and re-checked at `70f7db8`** (`master`, after vibe Phases 4b and 5 and the fader fix `0faa18c`): every line below is at `70f7db8`. **Re-check every path and line at HEAD before starting.**
**Status:** Proposed. Not to be built yet (D-8).   **Needs:** nothing.   **Size:** ~1.5 d.
**Implements:** the baseline bugs B-5 to B-12 (`plans/2026-11-19-zones-needs/05-baseline.md`) that don't depend on the design, plus the names Z2, Z4 and ZR expect from Z0: the **reference walker** (`routing/refs.clj`; Z2's migration and ZR's pin renaming call its `replace-refs`), the **cue count**, **ids that can't collide**, and a **confirm dialog** that Z1 and ZR reuse.

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. **NEW** marks anything that doesn't exist yet.

## After this phase you can…

- **Delete a zone group safely.** You're asked first, and you're told which cues use the group. You pick where those cues go instead (All by default), and the whole delete is one undo step. All can't be deleted.
- **See "Used by 3 cues"** on a group, and have it be true.
- **Edit Axis Transform** (mirror X, mirror Y, swap axes) on a projector. Today its boxes do nothing.
- **Copy calibration effects from one projector and paste them into another**, with the Paste button as well as ⌘V. Today the two disagree.
- **See when a calibration effect is skipped.** The projector's status line says so, not only the log (Q-Z0-3).
- **Remove a projector** you no longer have.
- **Make two groups in the same millisecond** without one overwriting the other.

## 1. Current behaviour (file:line, at `70f7db8`)

| # | What | Where | Behaviour |
|---|---|---|---|
| B-5 | Deleting a group | `events/handlers/zone_groups.clj:43-73`; the button at `views/tabs/zones.clj:295-299` | No confirmation, and `:all` can be deleted. The group leaves every projector and virtual projector, but **cues, items, tracks, clips, Zone Selector params and keyframes, effect chains, the preview cells and the preview filter keep naming it**. A dangling id routes nowhere (`backend/multi_engine.clj:61-73`) |
| B-6 | Double toggle in the Zone Groups tab | `views/tabs/zones.clj:166-181` | The row (`:166-172`) and its checkbox (`:173-181`) both dispatch the toggle, but **a click toggles once.** JavaFX's `SkinBase` consumes every mouse event that reaches a skinned control (its constructor calls `consumeMouseEvents(true)`), so a click on the box never reaches the row. And cljfx 1.10.6 calls no handler while it renders (`*in-progress?*`), so the re-render adds nothing. `click-a-projector-to-add-it-to-the-group-test` (`test/laser_show/ui_test/setup_dialogs_test.clj:106-132`) clicks the row, then the box, and checks one toggle each. **Nothing to fix.** The test stays as the guard |
| B-7 | "Cues targeting: 0" | `subs.clj:715-725` (`:cue-count 0 ;; TODO` at `:723`), shown at `views/tabs/zones.clj:271-273` | Always 0 |
| B-8 | Projector effect parameters | `views/tabs/projectors.clj:496-505` sends `:projectors/update-effect-param` and `…-from-text`; `events/handlers/projector.clj:354-365` only log "TODO" | No generic parameter of a calibration effect can be edited: the Axis Transform's three boxes (`animation/effects/calibration.clj:87-104`). The curves and corner pin work because their editors send `:chain/*` events (`views/tabs/projectors.clj:546-575`) |
| B-8, found | The edited effect's path | `views/tabs/projectors.clj:706-711` | Every editor gets `[effect-idx]`, the **top-level** index only. An effect inside a group of the chain would edit the group instead |
| B-8b | Copy and paste in a projector's chain | `views/tabs/projectors.clj:593-619, 712`; `views/components/list.clj:136-154, 687-703, 764-772, 875`; `events/handlers/list.clj:234-278`; `state/clipboard.clj:228-237` | The chain is a list editor, and every list copies and pastes as `:cue-chain-items`, the default type (the `:on-copy-fn` of this chain, `:615-616`, and of the effects grid's, `views/dialogs/effect_chain_editor.clj:144-145`, are never called). But the chain's Paste **button** is enabled from `clipboard/get-effects-to-paste`, which only knows `:effect` and `:effect-chain`, so it stays off after any copy. ⌘V pastes anyway, and pastes whatever a list copied last, presets from a cue included. The TODO stub `:projectors/insert-pasted-effects` (`events/handlers/projector.clj:347-351`) is never reached: nothing returns the `:clipboard/paste-projector-effects` effect (`events/core.clj:228-237, 555`). Read, not run |
| B-9 | "All" twice | `views/components/visual_editors/custom_param_renderers.clj:505-506`; also the keyframe value editor, `views/components/visual_editors/keyframe_modulator_panel.clj:401-402, 420-423` | A hard-coded `{:id :all :name "All"}` is put in front of the groups, and `:all` is already one of them (`state/domains.clj:364-368`) |
| B-10 | Projectors can't be removed | `events/handlers/projector.clj:12` ("cannot be deleted once discovered") | There is no remove event. A laser you sold stays in the list forever (and, after ZR, in your rig) |
| B-11 | Group ids from milliseconds | `events/handlers/zone_groups.clj:33` | Two creates in one millisecond (a script, a test, a fast double-click) give the same id, and the second overwrites the first |
| B-12 | Calibration failures | `backend/multi_engine.clj:94-111`; `animation/effects.clj:320-336, 349-385` | A failing calibration effect is skipped and the rest of the chain runs. If anything else throws, the frame goes out uncalibrated (`:108-110`). This was **decided** in the output runtime plan (O2, `plans/2026-11-16-output-runtime/02-phase2-output-safety.md:166-175`) and is tested (`test/laser_show/backend/multi_engine_test.clj:452-470`). **Not changed here.** Z1's safety stage runs after calibration, so a skipped corner pin can no longer move light outside the safe area (03 §9 tests it). **But the skip is invisible:** it is reported under `[:effect <instance id>]`, and the status reads only `[:engine]`, `[:provider]` and `[:projector-effects]` (`backend/output_runtime.clj:173`), so only the log says so (Q-Z0-3) |
| – | The subtitle promises safety zones | `views/tabs/projectors.clj:799-801` (no id) | "Configure color calibration and safety zones", but there are none until Z1 |

## 2. Why

- Deleting a group cleans up only the projector side, so every reference elsewhere goes dark without a word.
- The count was never written. The parameter stubs were left behind when the projector chain moved to the `:chain/*` events, and the chain's copy and paste were wired twice: once through the list editor, once through effects that nothing calls.
- These are small, but Z2 depends on three of them:
  - Z2's migration re-points references with the same walker (04 §4);
  - Z2's details panel shows "Used by n cues";
  - Z2's "+ Group" needs ids that can't collide.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `:chain/update-param`, `:chain/update-param-from-text` (`events/handlers/chain/params.clj:72-105`) with `(chain-helpers/chain-config :projector-effects id)` (`events/handlers/chain/helpers.clj:14-44`) | The generic projector parameter editor sends these instead of the stubs, as the curves and corner pin editors already do |
| `:list/paste-items` with a clipboard type (`events/handlers/list.clj:251-278`); `clipboard/copy-item-effects!` (`state/clipboard.clj:308-320`) | The projector chain's copy and paste, as effects |
| The dialog pattern: `[:ui :dialogs <id> {:open? …}]` (`state/domains.clj:165-189`), `:ui/open-dialog`, `:ui/close-dialog`, `:ui/update-dialog-data` (`events/handlers/ui.clj:20-60, 163-165`), mounted in `views/root.clj:178-240`, styled like the zone group editor (`views/dialogs/zone_group_editor.clj:90-169`, `container-primary`), not `dialog-content`, whose `-fx-spacing 0` (`css/cue_chain_editor.clj:33-35`) beats a v-box's own spacing (`views/dialogs/cell_trigger_editor.clj:29`) | A NEW generic confirm dialog |
| `chains/collect-all-ids` (`animation/chains.clj:138`), the track index (`routing/zone_effects.clj:78-100`) and the vibe rule's cue walk (`vibes/library.clj:89-105`, which skips keyframes) | The walker's traversal of items, groups, clips, tracks, folders and effects |
| `cell-label` (`views/dialogs/common.clj:4-8`) | "A1 Intro" in the delete dialog |
| `show-notice` (`events/helpers.clj:109-123`) | "Deleted Balcony · 3 cues now go to All" |
| `history` | Every change here is one event, so it is one undo step, and it marks the show unsaved (`state/history.clj:14-22`) |

## 4. Changes per namespace

### NEW `laser-show.routing.refs` (pure)

This is the one place that knows **every place a zone-group id can be stored**. Z2's migration (04 §4) and ZR's `rename-pins` (09 §4) call `replace-refs`, and Z4 adds its places to `ref-places` (06 §4).

| Function | Behaviour |
|---|---|
| `ref-places` | The table of places, each `{:kind :path-fn}`. In Z0:<br>• each cue's `[:destination-zone :zone-group-id]`;<br>• the `:zone-group-id` of every item and group, recursively (cue chains, timeline clips and group clips);<br>• the `:zone-group-id` of every timeline track and folder;<br>• every Zone Selector's `[:params :target-zone]` **and** its keyframes' `[:keyframe-modulator :keyframes i :params :target-zone]`, on or off, in the `:effects` of items, groups, clips, tracks and folders, inside effect groups too, in cue chains **and** effect chains;<br>• the preview's `[:config :preview :grid-cells i :zone-group-id]` and `[:config :preview :zone-group-filter]`.<br>Membership lists (a projector's `:zone-groups`) are **not** places: they say which lasers are in a group, not where content goes. Not stored anywhere else today: vibes name cells, not groups (their audience rule only reads `:crowd`, `vibes/library.clj:82-87`), and the starter cues name `:all` (`state/templates.clj:31-47`). If vibe Phase 8a lands first, its `:zones`, `:from`, `:zone-a` and `:zone-b` params join the table |
| `cue-refs [cue]` | The set of ids one cue names in any place. A cue with no destination names `:all`, as the routing (`services/frame_service.clj:384`) and the vibe rule (`vibes/library.clj:98`) treat it |
| `cues-using [cue-chains ref]` | The sorted set of grid cells `[col row]` whose cue names `ref`. It takes only `[:chains :cue-chains]`, so the sub that counts doesn't run on every state change. Effect cells and the preview don't count as cues |
| `replace-refs [state {old new}]` | Rewrites every place. With `new` = nil: an item's, group's, clip's or track's `:zone-group-id` is removed (it inherits); a cue's `:destination-zone` is removed (it falls back to `:all`); a Zone Selector's value becomes `:all`; a preview cell becomes the master view (`nil`); the preview filter becomes `:all`. **Membership lists are left alone.** A cue or cell it doesn't touch keeps its identity, and a state where nothing names `old` comes back `identical?`. The clipboard isn't state, so a cue copied before a delete still names the old id |

### Existing namespaces

- `laser-show.events.handlers.zone-groups`:
  - **`:zone-groups/request-remove {:group-id}`** (NEW). It opens the confirm dialog (below) with the message built from `refs/cues-using` and the member count, and a **"Send its cues to"** choice: All (the default), the other groups, and "Nothing (they inherit)". It refuses `:all`.
  - **`:zone-groups/remove {:group-id :retarget-to}`** (changed, `:43-73`). It refuses `:all`, then:
    - strips the group from every projector's and virtual projector's `:zone-groups`, as it does today (`:46-65`);
    - calls `refs/replace-refs` with `{group-id retarget-to}`. `:retarget-to` defaults to `:all` when absent; nil means "Nothing"; the deleted id or a group that doesn't exist becomes `:all`;
    - `dissoc`s the group, clears the selection if needed, closes the dialog, and shows the notice.

    Because it's one event, it's one undo step ("Delete Zone Group", already labelled at `events/handlers/history.clj:147`).
  - **`:zone-groups/create-new`** (changed, `:29-41`): the id is `(unique-group-id state name)`, a slug of the name (`"Balcony"` → `:group-balcony`: lower case, anything but a–z and 0–9 becomes `-`). If that id is taken, it adds `-2`, `-3` and so on, and an empty slug gives `:group`. The `group-` prefix keeps it clear of the built-in ids (`:all :left :right :center :graphics :crowd`), and a plain keyword can never be one of Z2's derived `:auto/*` or `:auto.laser/*`. It doesn't depend on time.
- `laser-show.events.handlers.projector`:
  - Remove the three stubs (`:347-365`) and their `case` rows (`:433-435`).
  - **`:projectors/request-remove {:projector-id}`** (NEW) opens the confirm dialog.
  - **`:projectors/remove {:projector-id}`** (NEW):
    - it removes `[:projectors id]`, `[:chains :projector-effects id]`, `[:ui :projector-effect-ui-state id]` and `[:list-ui [:projector-effects id]]`, plus the virtual projectors whose `:parent-projector-id` is `id`;
    - it clears `[:projector-ui :active-projector]` and `:calibrating-projector-id` if they point at it;
    - it drops that output from `[:projector-ui :discovered-devices]`, so the Discover list doesn't keep a box that can no longer do anything;
    - an unknown id gives `{:state state}` (the undo-labels test calls every labelled event bare).

    The output-sync middleware stops its engine by itself while streaming, because `output-runtime/wanted` changes (`events/handlers/output_sync.clj:13-34`, `backend/output_runtime.clj:41-50`). A later scan finds the device again, turned off, with default calibration, as a new laser. The docstring at `:12` changes to say so. ZR's "Forget this laser" (09 §6) can be this event behind its menu item (S-Z0-4). Undo label: "Remove Projector".
- `laser-show.events.core`: the unused `:clipboard/paste-projector-effects` effect goes (`:228-237`, its docstring line `:530` and its row `:555`).
- `laser-show.state.clipboard`: `get-effects-to-paste` (`:228-237`) also returns the effects of an `:item-effects` clipboard.
- `laser-show.events.handlers.list`: `:list/paste-items` (`:251-278`) with the type `:item-effects` takes its effects from `clipboard/get-effects-to-paste`, so whatever turns the Paste button on is what gets pasted, and a preset never lands in a calibration chain. No list uses that type today, so nothing else changes.
- `laser-show.views.tabs.projectors`:
  - `make-projector-param-events` (`:496-505`) returns `:chain/update-param` and `:chain/update-param-from-text` templates with `:domain :projector-effects :entity-key projector-id`, and every editor (`:546-575` too) gets the **full** `:effect-path` (`selected-effect-path`, `:706-707`), not `[effect-idx]`.
  - The chain sidebar (`:593-619`) passes `:clipboard-type :item-effects` in its `:on-change-params`, and drops the unused `:on-copy-fn`. Its Paste button and ⌘V then agree.
  - A **Remove…** button (`button-danger`, id `#projector-remove-button`) at the bottom of the config panel (`:684-783`).
  - The subtitle (`:799-801`) gets the id `#projectors-subtitle` and becomes "Scan for IDN devices • Configure color calibration". Z1 adds "and safety zones" back when it's true.
  - The status line (`output-status-text`, `:255-266`) shows Q-Z0-3's warning in amber: "Streaming · 59.8 fps · Corner Pin skipped".
- `laser-show.backend.multi-engine` (Q-Z0-3): after the chain, `apply-projector-effects` (`:94-111`) checks, only while `output-errors/failing?`, whether any of its effects' `[:effect <id>]` keys is failing. If so it reports `[:calibration id]` with their names; otherwise it calls `ok!`. The frame is the same either way (O2).
- `laser-show.backend.output-runtime` (Q-Z0-3): `engine-status` (`:172-181`) adds `:warning "Corner Pin skipped"` while `[:calibration id]` fails. The state stays `:streaming`.
- `laser-show.views.tabs.zones`:
  - **Delete…** (`:295-299`) dispatches `:zone-groups/request-remove`. For `:all` it's left out, and the panel says "All can't be deleted." (a disabled button shows no tooltip in JavaFX).
  - The usage line (`:271-273`) reads "Used by 3 cues", "Used by 1 cue" or "Used by no cues".
- `laser-show.subs`: `zone-group-usage` (`:715-725`) gets `:cue-count` and `:cues` from `(refs/cues-using (fx/sub-val context get-in [:chains :cue-chains]) group-id)`.
- `laser-show.views.components.visual-editors.custom-param-renderers` (`:505-506`) and `keyframe-modulator-panel` (`:420-423`): the hard-coded All is added only when the groups have no `:all`.
- NEW `laser-show.views.dialogs.confirm`: one generic confirm dialog, application-modal like `views/dialogs/unsaved_confirm.clj`.
  - State: `[:ui :dialogs :confirm {:open? :title :message :details :choice :choice-value :confirm-label :danger? :confirm-event}]`. `:choice` is optional: `{:key :label :options [{:value :text}]}`, and its combo keeps the picked option in `:choice-value` (`:ui/update-dialog-data`).
  - `:confirm-event` is plain data: a request builds it fresh, and anything that parks the event it is handling first drops `:state`, `:time` and `:fx/event`, as the unsaved guard keeps only `:event/type` (`events/handlers/menu.clj:38-45`).
  - Buttons: Cancel (`#confirm-cancel`) closes it. The confirm button (`#confirm-ok`) dispatches NEW `:ui/confirm`, which closes it and continues with `:confirm-event`, plus `{(:key choice) chosen-value}` when there's a choice.
  - It is mounted in `views/root.clj` next to the unsaved dialog. **Z1** (growing a live safety zone, Identify while a cue plays) and **ZR** (the import and use-the-show's-setup questions) use it too.
- `laser-show.events.handlers.ui`: NEW `:ui/confirm`.
- `laser-show.state.domains` (`:165-189`): `:confirm {:open? false}` is added to the dialogs.
- `laser-show.events.handlers.history` (`:125-163`): "Remove Projector".

## 5. State placement

| Path | Lives in | Written by | Undo? | Unsaved? |
|---|---|---|---|---|
| `[:ui :dialogs :confirm]` | UI, never saved | the request events, the dialog's buttons | no | no |
| `[:zone-groups]`, membership lists, the cue and effect chain references `replace-refs` rewrites, `[:projectors]`, `[:chains :projector-effects]` | document | `:zone-groups/remove`, `:projectors/remove`, `:chain/update-param`, `:chain/set-items` (a paste) | yes, one step per event | yes |
| `[:config :preview :grid-cells]`, `:zone-group-filter` | document, a preference (`state/history.clj:39-41`) | `:zone-groups/remove` | **no**: an undone delete leaves the cells on their new group | yes |
| `[:projector-ui :discovered-devices]` | runtime | the scan, `:projectors/remove` | no | no |
| `[:calibration id]` | `output-errors`, runtime | the engine thread | – | – |

## 6. Events and effects

| Event | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:zone-groups/request-remove {:group-id}` | NEW | Delete… (Zone Groups tab) | yes |
| `:zone-groups/remove {:group-id :retarget-to}` | yes, changed | the confirm dialog | yes |
| `:zone-groups/create-new` | yes, changed (the id) | the zone group editor | yes |
| `:projectors/request-remove`, `:projectors/remove` | NEW | Remove… (Projectors tab), the confirm dialog | yes |
| `:ui/confirm` | NEW | the confirm dialog's button | yes |
| `:chain/update-param`, `:chain/update-param-from-text` | yes | the projector's generic parameter editor (no more stubs) | yes |
| `:list/paste-items` | yes, changed (`:item-effects`) | the projector chain's Paste and ⌘V | yes |
| `:projectors/insert-pasted-effects`, effect `:clipboard/paste-projector-effects` | yes, **removed** (never reached) | – | – |

## 7. UX

**Deleting a group** (Zone Groups tab → select Balcony → Delete…):

```
┌ Delete "Balcony"? ─────────────────────────────────────┐
│ 3 cues and 2 projectors use it.                        │
│ A1 Intro · B4 Tunnel · C2 Drop                         │
│                                                        │
│ Send its cues to:  [All ▾]                             │
│                                                        │
│                               [Cancel]  [Delete group] │
└────────────────────────────────────────────────────────┘
```

- When nothing uses it, the dialog says "Nothing uses it." and there is no choice to make.
- The cue list shows up to 6 cells, named with `cell-label` and the cue's name, then "and 4 more".
- After the delete, the status bar shows a notice: "Deleted Balcony · 3 cues now go to All".
- All has no Delete… button. The panel says "All can't be deleted."

**Removing a projector** (Projectors tab → Remove…): "Remove Laser L? Its calibration goes too. If it's still on the network, the next scan finds it again, turned off." with [Cancel] [Remove].

**Axis Transform**: its three checkboxes now stick, and each tick is one undo step.

**Copy and paste**: in a projector's chain, Copy then (on another projector) Paste, or ⌘C then ⌘V, gives the same result. A copy from a cue no longer pastes presets there.

**A skipped calibration effect** (Q-Z0-3): the projector's status line says "Streaming · 59.8 fps · Corner Pin skipped" in amber, with the error as its tooltip. It goes away when the effect works again.

## 8. Data examples and invariants

```clojure
;; before: Balcony (:group-balcony) is named by cue [0 0]'s item and by the preview's 3rd cell;
;; projector :p1 is in it
(refs/cues-using (get-in state [:chains :cue-chains]) :group-balcony)   ;; => #{[0 0]}

{:event/type :zone-groups/remove :group-id :group-balcony :retarget-to :all}
;; item :zone-group-id :group-balcony → :all; preview cell 2 → {:zone-group-id :all};
;; :p1 :zone-groups [:all :group-balcony] → [:all]   (remove strips it; replace-refs never touches lists)

(refs/replace-refs state {:group-balcony nil})
;; the item's :zone-group-id is removed (it inherits); preview cell 2 → {:zone-group-id nil}

(unique-group-id {:zone-groups {:group-balcony {}}} "Balcony")   ;; => :group-balcony-2
(unique-group-id {} "")                                         ;; => :group
```

**Invariants**

1. After `:zone-groups/remove`, no place in `refs/ref-places` and no membership list names the deleted id.
2. `replace-refs` with an id nothing names returns the same state (`identical?`), and it never writes into a membership list, so it can never put a derived ref (Z2's `:auto/*`) there.
3. `:all` can never be removed, by the button or by the event.
4. A delete, with its re-target, is **one** undo step. Undoing it brings back the group, its members and every cue and effect reference. The preview cells and filter are preferences, so they stay on the group they were sent to.
5. Group ids are unique without depending on time, and never collide with a built-in or derived id.
6. A projector parameter edit changes only that effect's parameter, at its full path in `[:chains :projector-effects id :items]`.
7. Removing a projector stops its engine with no other step (the output-sync middleware), and leaves no chain, UI state or virtual projector behind.
8. In a projector's chain, the Paste button is enabled exactly when ⌘V would paste something.

## 9. Tests

- `laser-show.routing.refs-test` (NEW; the cues of `test/laser_show/routing/zone_routing_integration_test.clj:26-116` as fixtures):
  - `cues-using-finds-every-place-test`: one reference in each place (§4), including a disabled and a keyframed Zone Selector, one inside an effect group, a timeline folder and a group clip;
  - `cue-without-destination-names-all-test`;
  - `replace-refs-rewrites-every-place-test`;
  - `replace-refs-to-nil-inherits-test`;
  - `replace-refs-leaves-membership-alone-test`;
  - `replace-refs-unused-is-identical-test`;
  - `effects-grid-and-preview-dont-count-as-cues-test`.
- `laser-show.events.zone-groups-handlers-test` (existing; pattern `handle-zone-groups-remove-test`, `:104-138`):
  - `request-remove-opens-the-confirm-test`;
  - `remove-retargets-cues-test`, `remove-to-nothing-inherits-test`, `retarget-to-a-missing-group-is-all-test`;
  - `remove-strips-membership-test`;
  - `all-cannot-be-removed-test`;
  - `remove-is-one-undo-step-test` (through `events/handle-event-pipeline`);
  - `create-new-ids-are-unique-test` (two creates with the same name in one millisecond);
  - `create-new-never-takes-a-built-in-id-test`.
- `laser-show.events.projector-handlers-test` (existing):
  - `remove-projector-cleans-up-test`, `remove-unknown-projector-changes-nothing-test`;
  - `remove-projector-stops-its-engine-test` (through the pipeline, as `test/laser_show/events/output_sync_test.clj:69` does).
- `laser-show.events.chain-params-handlers-test` (existing, `:25`): `projector-effect-bool-param-test`, `nested-projector-effect-param-test`.
- `laser-show.state.clipboard-test` (existing): `item-effects-paste-as-effects-test`. `laser-show.events.list-handlers-test` (existing): `paste-effects-into-an-item-effects-list-test`.
- `laser-show.backend.multi-engine-test` (existing, next to `:452-470`): `skipped-calibration-effect-is-reported-test` (the frame is unchanged, `[:calibration :proj-a]` names the effect, and clears when it works).
- `laser-show.backend.output-lifecycle-test` (existing): `skipped-calibration-shows-as-a-warning-test`. `laser-show.views.tabs.projectors-test` (existing, `:10-17`): `output-status-text-test` gains the warning.
- `laser-show.subs-test` (existing; `context` at `:33-34`): `zone-group-usage-counts-cues-test`.
- UI, `laser-show.ui-test.setup-dialogs-test` (existing):
  - `axis-transform-boxes-stick-test`;
  - `delete-group-asks-and-retargets-test` (open, choose Left, delete, then check the cue's item);
  - `all-has-no-delete-test`;
  - `remove-projector-asks-first-test`;
  - `copy-an-effect-to-another-projector-test` (Copy, select the other projector, the Paste button is on and pastes it);
  - `subtitle-promises-no-safety-zones-test` (`#projectors-subtitle`).

  `click-a-projector-to-add-it-to-the-group-test` (`:106-132`) passes unchanged (B-6).
- UI, `laser-show.ui-test.cue-chain-list-test` (existing; `open-cue-chain-editor!` at `:16-22`): `zone-selector-lists-all-once-test`.
- UI, `laser-show.ui-test.main-window-test`: `confirm-dialog-uses-theme-classes-test`, patterned on `zone-group-editor-uses-theme-classes-test` (`:274`).

Run: `clj -M:mac-arm:test refs zone-groups-handlers projector-handlers chain-params clipboard list-handlers multi-engine output-lifecycle projectors-test subs output-sync history-handlers`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.setup-dialogs-test`, then `-n laser-show.ui-test.cue-chain-list-test` and `-n laser-show.ui-test.main-window-test`, then the two full suites.

## 10. Commit plan

Write the failing tests first in each step.

| # | Commit | Tests |
|---|---|---|
| 1 | `feat(routing): one walker knows every place a zone group is named: which cues use a group, and re-pointing or clearing every reference at once` | `refs-test` |
| 2 | `fix(zones): a group's details say how many cues use it` | `zone-group-usage-counts-cues-test` |
| 3 | `feat(ui): a confirm dialog the setup screens share` | `confirm-dialog-uses-theme-classes-test` |
| 4 | `fix(zones): deleting a group asks first, names the cues that use it and sends them where you choose (All by default), in one undo step; All can't be deleted` | the handler tests, `delete-group-asks-and-retargets-test`, `all-has-no-delete-test` |
| 5 | `fix(zones): new groups get ids from their names that never collide` | `create-new-ids-…` |
| 6 | `fix(projectors): Axis Transform's boxes stick, an effect inside a group of the chain is the one edited, and the chain's Paste button pastes what ⌘V pastes` | `axis-transform-boxes-stick-test`, `nested-projector-effect-param-test`, the paste tests |
| 7 | `feat(projectors): the status line says when a calibration effect fails and is skipped` | `skipped-calibration-…` |
| 8 | `feat(projectors): remove a projector you no longer have; its engine, chain and virtual projectors go with it` | the remove tests |
| 9 | `fix(ui): the Zone Selector and its keyframes list All once, and the Projectors tab no longer promises safety zones it doesn't have` | `zone-selector-lists-all-once-test`, `subtitle-promises-no-safety-zones-test` |

## 11. Out of scope / alternatives considered

- **Failing closed on a calibration error (B-12):** not in Z0. The output runtime plan decided to skip only the failing effect (O2), and Z1's safety stage after calibration covers where that could send light. Z0 only makes the skip visible (Q-Z0-3 asks).
- **Deleting a group without asking when nothing uses it:** rejected. Every delete asks, so there is one pattern to learn. The unused case is just shorter.
- **UUID group ids:** rejected. Slugs keep the saved files readable, and they're unique without the clock.
- **Removing the row handler in the Zone Groups tab (B-6):** not needed. The test shows one toggle per click.
- **Noticed, not done:** the effects grid's chain and the cue editor's item effects have the same clipboard mix-up (`views/dialogs/effect_chain_editor.clj:134-147` and `views/dialogs/cue_chain_editor.clj:543-563` pass no `:clipboard-type`, so their copies are `:cue-chain-items` too). They are left for a clipboard clean-up, since they aren't zones or projectors bugs. Once they copy as `:item-effects`, effects paste between all three.

## 12. Spec questions and open questions

**Spec questions**
- **S-Z0-1 · Where the walker lives.** The spec has no name for it. Z2 (04 §4) calls it "Z0's walker" with `replace-refs [state {old new}]`, ZR (09 §4) calls `migration/replace-refs`, and Z4 (06 §4) extends "its places". **Chosen:** `laser-show.routing.refs` with `ref-places`, `cue-refs`, `cues-using` and `replace-refs`. Z2's `migration/replace-refs` is an alias of it, so 04 and 09 work as written.
- **S-Z0-2 · What "nothing" means for each place.** The spec doesn't say what a cleared reference becomes. **Chosen:** items, groups, clips and tracks inherit; a cue falls back to All; a Zone Selector says All; a preview cell shows the master view.
- **S-Z0-3 · B-6.** 04 needs Z0 for "one toggle per click" and reuses the toggle "with Z0's one-toggle fix". There is nothing to fix (§1), so Z2 can use `:projectors/toggle-zone-group` as it is.
- **S-Z0-4 · ZR's Forget.** 09 §6 names a NEW `:rig/forget-projector`, on a disabled card. **Chosen:** Z0's `:projectors/remove` does the removing, and ZR's menu item can dispatch it (or wrap it with its own label).
- **S-Z0-5 · A reused id.** After deleting Balcony, a new "Balcony" gets `:group-balcony` again. The delete has re-targeted every reference, so nothing in the show points at the old group. A cue still on the clipboard does, and pastes into the new one. **Chosen:** accept it, since the name is the same.

**Open questions** (★ = recommended)
- **Q-Z0-1 · The default "send its cues to".** ★ **A** All, so the cues keep playing. **B** Nothing, so they inherit, which is usually All too, but follows a column or show default after Z4.
- **Q-Z0-2 · Remove a projector while it's streaming?** ★ **A** Yes, after the confirm (its engine stops). **B** Only when it's turned off.
- **Q-Z0-3 · A calibration effect that fails.** Today it is skipped (O2), and only the log says so (B-12). ★ **A** Keep the skip, and say it on the projector's status line: "Corner Pin skipped". **B** Fail closed for calibration: that laser goes dark with "Calibration error: output off" (the original B-12 fix, overriding O2 for projector chains). **C** Leave it to the log.
