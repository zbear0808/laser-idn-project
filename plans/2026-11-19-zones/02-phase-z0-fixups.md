# Phase Z0: fix-ups that every design needs

**Written:** 2026-11-19   **Written for commit:** `d298801` (`master`; UX Phases 1–3 and vibe Phases 1–3 and 4a have landed). **Re-check every path and line at HEAD before starting.**
**Status:** Proposed. Not to be built yet (D-8).   **Needs:** nothing.   **Size:** ~1.5 d.
**Implements:** the baseline bugs B-5 to B-12 (`plans/2026-11-19-zones-needs/05-baseline.md`) that don't depend on the design, plus the names Z2 and Z4 expect from Z0: the **reference walker** (`routing/refs.clj`), the **cue count**, **ids that can't collide**, and a **confirm dialog** that Z1 and ZR reuse.

## After this phase you can…

- **Delete a zone group safely.** You're asked first, and you're told which cues use the group. You pick where those cues go instead (All by default), and the whole delete is one undo step. All can't be deleted.
- **See "Used by 3 cues"** on a group, and have it be true.
- **Edit Axis Transform** (mirror X, mirror Y, swap axes) on a projector. Today its boxes do nothing.
- **Paste calibration effects** into a projector's chain.
- **Remove a projector** you no longer have.
- **Make two groups in the same millisecond** without one overwriting the other.

## 1. Current behaviour (file:line, at `d298801`)

| # | What | Where | Behaviour |
|---|---|---|---|
| B-5 | Deleting a group | `events/handlers/zone_groups.clj:43-73`; the button at `views/tabs/zones.clj:295-299` | No confirmation, and `:all` can be deleted. The group is removed from every projector and virtual projector, but **cues, items, tracks, Zone Selector params and keyframes, effect chains, the preview cells and the preview filter keep naming it**. A dangling id routes nowhere (`backend/multi_engine.clj:61-73`) |
| B-6 | Double toggle in the Zone Groups tab | `views/tabs/zones.clj:166-181` | Both the row and its checkbox dispatch the toggle. **Checked: it does not double-toggle.** UX Phase 3's `click-a-projector-to-add-it-to-the-group-test` (`test/laser_show/ui_test/setup_dialogs_test.clj:106-132`) clicks the checkbox and checks that it toggles once. **Nothing to fix.** The test stays as the guard |
| B-7 | "Cues targeting: 0" | `subs.clj:711-719` (`:cue-count 0 ;; TODO`), shown at `views/tabs/zones.clj:271-273` | Always 0 |
| B-8 | Projector effect parameters | `views/tabs/projectors.clj:496-505` sends `:projectors/update-effect-param` and `…-from-text`; `events/handlers/projector.clj:354-365` only log "TODO" | Every generic parameter of a calibration effect (the Axis Transform's three boxes, `animation/effects/calibration.clj:87-104`) can't be edited. The curves and corner pin work because their editors send `:chain/*` events (`views/tabs/projectors.clj:546-575`) |
| B-8b | Pasting projector effects | `events/core.clj:228-237` continues with `:projectors/insert-pasted-effects`; `events/handlers/projector.clj:347-351` logs "TODO" | A paste into a projector chain does nothing |
| B-9 | "All" twice in the Zone Selector | `views/components/visual_editors/custom_param_renderers.clj:505-506` | A hard-coded `{:id :all :name "All"}` is added in front of the groups, and `:all` is already one of them (`state/domains.clj:362-366`) |
| B-10 | Projectors can't be removed | `events/handlers/projector.clj:12` ("cannot be deleted once discovered") | There is no remove event. A laser you sold stays in the list forever (and, after ZR, in your rig) |
| B-11 | Group ids from milliseconds | `events/handlers/zone_groups.clj:33` | Two creates in one millisecond (a script, a test, a fast double-click) give the same id, and the second overwrites the first |
| B-12 | Calibration failures | `backend/multi_engine.clj:94-111`; `animation/effects.clj:349-385` | A failing calibration effect is skipped, and the rest of the chain runs. This was **decided** in the output runtime plan (`plans/2026-11-16-output-runtime/02-phase2-output-safety.md`) and is tested (`test/laser_show/backend/multi_engine_test.clj:452-470`). **Not changed here.** Z1's safety stage runs after calibration, so a skipped corner pin can no longer move light outside the safe area. Z1 adds the test for that (03 §9) |
| – | The subtitle promises safety zones | `views/tabs/projectors.clj:800` | "Configure color calibration and safety zones", but there are none until Z1 |

## 2. Why

- Deleting a group cleans up only the projector side, so every reference elsewhere goes dark without a word.
- The count was never written. The parameter and paste handlers were left as stubs when the projector chain moved to the `:chain/*` events.
- These are small, but Z2 depends on three of them:
  - Z2's migration re-points references with the same walker (04 §4);
  - Z2's details panel shows "Used by n cues";
  - Z2's "+ Group" needs ids that can't collide.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `:chain/update-param`, `:chain/update-param-from-text` (`events/handlers/chain/params.clj:72-105`) with `(h/chain-config :projector-effects id)` (`events/handlers/chain/helpers.clj:14-44`) | The generic projector parameter editor sends these instead of the stubs, as the curves and corner pin editors already do |
| The insert-pasted logic of the effects grid (`:effects/insert-pasted`, dispatched from `events/core.clj:216-226`) | The same insert, with fresh ids, for a projector chain |
| The dialog pattern: `[:ui :dialogs <id> {:open? …}]` (`state/domains.clj:165-187`), `:ui/open-dialog`, `:ui/close-dialog`, `:ui/update-dialog-data` (`events/handlers/ui.clj:162-165`), mounted in `views/root.clj:178-240`, styled like `views/dialogs/unsaved_confirm.clj` | A NEW generic confirm dialog |
| `chains/collect-all-ids`, the recursive walks in `routing/zone_effects.clj:66-100` (the track index) | The walker's traversal of items, groups, tracks and folders |
| `history` | Every change here is one event, so it is one undo step, and it marks the show unsaved (`state/history.clj:14-22`) |

## 4. Changes per namespace

### NEW `laser-show.routing.refs` (pure)

This is the one place that knows **every place a zone-group id can be stored**. Z2's migration and Z4's new places extend it (04 §4, 06 §4).

| Function | Behaviour |
|---|---|
| `ref-places` | The table of places, each `{:kind :path-fn}`. In Z0: each cue's `[:destination-zone :zone-group-id]`; each item's and group's `:zone-group-id`, recursively (cue chains and timeline clips); each track's and folder's `:zone-group-id`; each Zone Selector's `[:params :target-zone]` **and** its keyframes' `[:keyframe-modulator :keyframes i :params :target-zone]`, in cue chains **and** effect chains; the preview's `[:config :preview :grid-cells i :zone-group-id]` and `[:config :preview :zone-group-filter]` |
| `cues-using [state ref]` | The sorted set of grid cells `[col row]` whose cue names `ref` in any of its places. The effects grid and the preview don't count as cues |
| `replace-refs [state {old new}]` | Rewrites every place. With `new` = nil: an item's, group's or track's `:zone-group-id` is removed (it inherits); a cue's `:destination-zone` is removed (it falls back to `:all`); a Zone Selector's value becomes `:all`; a preview cell becomes the master view (`nil`); the preview filter becomes `:all`. Membership lists (`:zone-groups` on projectors and virtual projectors) lose `old`, or get `new` in its place (once). Nothing else changes, and a state with no `old` is returned unchanged (`identical?`) |

### Existing namespaces

- `laser-show.events.handlers.zone-groups`:
  - **`:zone-groups/request-remove {:group-id}`** (NEW). It opens the confirm dialog (below) with the message built from `refs/cues-using` and the member count, and a **"Send its cues to"** choice. That choice offers All (the default), the other groups, and "Nothing (they inherit)". It refuses `:all`.
  - **`:zone-groups/remove {:group-id :retarget-to}`** (changed, `:43-73`). It refuses `:all`, then calls `refs/replace-refs` with `{group-id retarget-to}`, `dissoc`s the group, clears the selection if needed, and closes the dialog. Because it's one event, it's one undo step. `:retarget-to` defaults to `:all`.
  - **`:zone-groups/create-new`** (changed, `:29-41`): `group-id` is `(unique-group-id state name)`, a slug of the name (`"Balcony"` → `:group-balcony`). If that id is taken, it adds `-2`, `-3` and so on, and it falls back to `:group` for an empty name. It is never one of the reserved ids `:all :left :right :center :graphics :crowd` or anything Z2 derives (`:auto/*`, `:auto.laser/*`). It doesn't depend on time.
- `laser-show.events.handlers.projector`:
  - Remove the three stubs (`:347-365`) and their `case` rows (`:433-435`).
  - **`:projectors/insert-pasted-effects {:projector-id :insert-pos :effects}`** becomes real. It inserts the effects at `insert-pos` (at the end if nil) in `[:chains :projector-effects id :items]`, each with a fresh `:id` (`h/ensure-item-fields`, as `:340-345` does).
  - **`:projectors/request-remove {:projector-id}`** (NEW) opens the confirm dialog.
  - **`:projectors/remove {:projector-id}`** (NEW):
    - it removes `[:projectors id]`, `[:chains :projector-effects id]` and `[:ui :projector-effect-ui-state id]`, plus the virtual projectors whose `:parent-projector-id` is `id`;
    - it clears `[:projector-ui :active-projector]` and `:calibrating-projector-id` if they point at it.

    The output-sync middleware stops its engine by itself, because `output-runtime/wanted` changes (`events/handlers/output_sync.clj:13-34`, `backend/output_runtime.clj:41-50`). A later scan finds the device again, turned off, as a new laser. The docstring at `:12` changes to say so. ZR's "Forget this laser" (09) is this event.
- `laser-show.views.tabs.projectors`:
  - `make-projector-param-events` (`:496-505`) returns `:chain/update-param` and `:chain/update-param-from-text` templates with `:domain :projector-effects :entity-key projector-id :effect-path [effect-idx]`.
  - A **Remove…** button (`button-danger`, id `#projector-remove-button`) at the bottom of the config panel (`:684-787`).
  - The subtitle (`:800`) becomes "Scan for IDN devices • Configure color calibration". Z1 adds "and safety zones" back when it's true.
- `laser-show.views.tabs.zones`:
  - **Delete…** (`:295-299`) dispatches `:zone-groups/request-remove`. It is disabled for `:all`, with the tooltip "All can't be deleted".
  - The usage line (`:271-273`) reads "Used by 3 cues", "Used by 1 cue" or "Used by no cues".
- `laser-show.subs`: `zone-group-usage` (`:711-719`) gets `:cue-count (count (refs/cues-using state group-id))` and `:cues` (the cells). It reads `:chains`, `:config` and `:zone-groups` with `fx/sub-val`.
- `laser-show.views.components.visual-editors.custom-param-renderers` (`:505-506`): the hard-coded All is added only when the groups have no `:all`.
- NEW `laser-show.views.dialogs.confirm`: one generic confirm dialog.
  - State: `[:ui :dialogs :confirm {:open? :title :message :details :choice :confirm-label :danger? :confirm-event}]`. `:choice` is optional: `{:label :options [{:value :text}] :value}`.
  - Buttons: Cancel (`#confirm-cancel`), which closes it, and the confirm button (`#confirm-ok`), which dispatches `:confirm-event` merged with `{:choice value}` and closes it.
  - It is mounted in `views/root.clj` next to the unsaved dialog. **Z1** (growing a live safety zone) and **ZR** (the import and use-the-show's-setup questions) use it too.
- `laser-show.state.domains` (`:165-187`): `:confirm {:open? false}` is added to the dialogs.

## 5. State placement

| Path | Lives in | Written by | Undo? | Unsaved? |
|---|---|---|---|---|
| `[:ui :dialogs :confirm]` | UI, never saved | the request events, the dialog's buttons | no | no |
| `[:zone-groups]`, the references `replace-refs` rewrites, `[:projectors]`, `[:chains :projector-effects]` | document | `:zone-groups/remove`, `:projectors/remove`, `:projectors/insert-pasted-effects`, `:chain/update-param` | yes, one step per event | yes |

## 6. Events and effects

| Event | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:zone-groups/request-remove {:group-id}` | NEW | Delete… (Zone Groups tab) | yes |
| `:zone-groups/remove {:group-id :retarget-to}` | yes, changed | the confirm dialog | yes |
| `:zone-groups/create-new` | yes, changed (the id) | the zone group editor | yes |
| `:projectors/request-remove`, `:projectors/remove` | NEW | Remove… (Projectors tab), the confirm dialog | yes |
| `:projectors/insert-pasted-effects` | yes, implemented | `:clipboard/paste-projector-effects` (`events/core.clj:228-237`) | yes |
| `:chain/update-param`, `:chain/update-param-from-text` | yes | the projector's generic parameter editor (it no longer uses the stubs) | yes |

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
- The cue list shows up to 6 cells, then "and 4 more".
- After the delete, the status bar shows the existing notice: "Deleted Balcony · 3 cues now go to All".

**Removing a projector** (Projectors tab → Remove…): "Remove Laser L? Its calibration goes too. If it's still on the network, the next scan finds it again, turned off." with [Cancel] [Remove].

**Axis Transform**: its three checkboxes now stick, and each tick is one undo step.

## 8. Data examples and invariants

```clojure
;; before: Balcony (:group-balcony) is used by cue [0 0]'s item and by the preview's 3rd cell
(refs/cues-using state :group-balcony)            ;; => #{[0 0]}
(refs/replace-refs state {:group-balcony :all})
;; item :zone-group-id :group-balcony → :all; preview cell 2 → {:zone-group-id :all};
;; projector :p1 :zone-groups [:all :group-balcony] → [:all]
(refs/replace-refs state {:group-balcony nil})
;; the item's :zone-group-id is removed (it inherits); preview cell 2 → {:zone-group-id nil}

(unique-group-id {:zone-groups {:group-balcony {}}} "Balcony")   ;; => :group-balcony-2
(unique-group-id {} "")                                         ;; => :group
```

**Invariants**

1. After `:zone-groups/remove`, no place in `refs/ref-places` names the deleted id.
2. `replace-refs` with an id nothing names returns the same state (`identical?`).
3. `:all` can never be removed, by the button or by the event.
4. A delete, with its re-target, is **one** undo step. Undoing it brings back the group, its members and every reference.
5. Group ids are unique without depending on time, and never collide with a reserved or derived id.
6. A projector parameter edit changes only that effect's parameter (`[:chains :projector-effects id :items idx :params k]`).
7. Removing a projector stops its engine with no other step (the output-sync middleware), and leaves no chain, UI state or virtual projector behind.

## 9. Tests

- `laser-show.routing.refs-test` (NEW):
  - `cues-using-finds-every-place-test`: a fixture with one reference in each place (§4), including a keyframed Zone Selector and a timeline folder;
  - `replace-refs-rewrites-every-place-test`;
  - `replace-refs-to-nil-inherits-test`;
  - `replace-refs-unused-is-identical-test`;
  - `effects-grid-and-preview-dont-count-as-cues-test`.
- `laser-show.events.zone-groups-handlers-test` (existing; pattern `:100-138`):
  - `request-remove-opens-the-confirm-test`;
  - `remove-retargets-cues-test`;
  - `remove-to-nothing-inherits-test`;
  - `all-cannot-be-removed-test`;
  - `remove-is-one-undo-step-test` (patterned on `history/derive-dirty` at `:138`);
  - `create-new-ids-are-unique-test` (two creates with the same name and the same clock);
  - `create-new-never-takes-a-reserved-id-test`.
- `laser-show.events.projector-handlers-test` (existing):
  - `insert-pasted-effects-test` (fresh ids, position);
  - `remove-projector-cleans-up-test`;
  - `remove-projector-stops-its-engine-test` (via `output-sync/sync-needed?`, as in `test/laser_show/events/output_sync_test.clj:69`).
- `laser-show.subs-test`: `zone-group-usage-counts-cues-test`.
- UI, `laser-show.ui-test.setup-dialogs-test` (existing):
  - `axis-transform-boxes-stick-test`;
  - `delete-group-asks-and-retargets-test` (open, choose Left, delete, then check the cue's item);
  - `all-has-no-delete-test`;
  - `remove-projector-asks-first-test`.

  `click-a-projector-to-add-it-to-the-group-test` (`:106-132`) passes unchanged (B-6).
- UI, `laser-show.ui-test.main-window-test`: `confirm-dialog-uses-theme-classes-test`, patterned on `zone-group-editor-uses-theme-classes-test`.

Run: `clj -M:mac-arm:test refs zone-groups-handlers projector-handlers subs output-sync`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.setup-dialogs-test`, then the two full suites.

## 10. Commit plan

Write the failing tests first in each step.

| # | Commit | Tests |
|---|---|---|
| 1 | `feat(routing): one walker knows every place a zone group is named: which cues use a group, and re-pointing or clearing every reference at once` | `refs-test` |
| 2 | `fix(zones): a group's details say how many cues use it` | `zone-group-usage-counts-cues-test` |
| 3 | `feat(ui): a confirm dialog the setup screens share` | `confirm-dialog-uses-theme-classes-test` |
| 4 | `fix(zones): deleting a group asks first, names the cues that use it and sends them where you choose (All by default), in one undo step; All can't be deleted` | the handler tests, `delete-group-asks-and-retargets-test`, `all-has-no-delete-test` |
| 5 | `fix(zones): new groups get ids from their names that never collide` | `create-new-ids-…` |
| 6 | `fix(projectors): Axis Transform's boxes stick, and pasting effects into a projector's chain works` | `axis-transform-boxes-stick-test`, `insert-pasted-effects-test` |
| 7 | `feat(projectors): remove a projector you no longer have; its engine, chain and virtual projectors go with it` | the remove tests |
| 8 | `fix(ui): the Zone Selector lists All once, and the Projectors tab no longer promises safety zones it doesn't have` | a renderer test, and `assert-text!` on the subtitle |

## 11. Out of scope / alternatives considered

- **Failing closed on a calibration error (B-12):** rejected for Z0. The output runtime plan decided to skip only the failing effect, and Z1's safety stage after calibration covers what that risked.
- **Deleting a group without asking when nothing uses it:** rejected. Every delete asks, so there is one pattern to learn. The unused case is just shorter.
- **UUID group ids:** rejected. Slugs keep the saved files readable, and they're unique without the clock.
- **Removing the row handler in the Zone Groups tab (B-6):** not needed. The test shows one toggle per click.

## 12. Spec questions and open questions

**Spec questions**
- **S-Z0-1 · Where the walker lives.** The spec has no name for it. Z2 (04 §4) calls it "Z0's walker" with `replace-refs [state {old new}]`, and Z4 (06 §4) extends "its places". **Chosen:** `laser-show.routing.refs` with `ref-places`, `cues-using` and `replace-refs`, so both docs' calls work as written.
- **S-Z0-2 · What "nothing" means for each place.** The spec doesn't say what a cleared reference becomes. **Chosen:** items, groups and tracks inherit; a cue falls back to All; a Zone Selector says All; a preview cell shows the master view.

**Open questions** (★ = recommended)
- **Q-Z0-1 · The default "send its cues to".** ★ **A** All, so the cues keep playing. **B** Nothing, so they inherit, which is usually All too, but follows a column or show default after Z4.
- **Q-Z0-2 · Remove a projector while it's streaming?** ★ **A** Yes, after the confirm (its engine stops). **B** Only when it's turned off.
