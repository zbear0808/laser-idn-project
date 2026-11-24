# 05 — Keep the Step Grid on the pattern that opened it

**Status:** Implemented in `b5e9866` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24). Planned as: Proposed · **Last verified against:** `7b320b3` (`master`, 2026-11-23).

**Supersedes / Superseded by:** none · **Related findings:** [wrong-pattern audit](../2026-11-23-cljfx-reactivity-audit.md#L61); [zones Z6 as built](../2026-11-19-zones/08-phase-z6-step-grid.md#L316-L338). Paths are relative to `src/laser_show/`; `T/` means `test/laser_show/`.

## 1. Current behaviour

**Reproduced in memory at HEAD:** Open P in effects cell `[2 1]`, change `[P Q]` to `[Q P]`: `subs/step-grid` now returned Q's ID and cells. Toggle at step 0 changed Q from `[:zone/b]` to `[:zone/a :zone/b]`; P was untouched. Deleting P left Copy returning Q's steps. Opening an empty cue created a UUID in the document but kept only `{:domain :cue-zone-patterns :entity-key [1 0] :effect-path [0]}` in the dialog (`subs.clj:1324–1356`; `events/handlers/step_grid.clj:46–81,111–131,145–180,290–298`).

## 2. Root cause

The opener captures a **position**. Readers and writers check its occupant's kind, `:zone-pattern`, not its identity. A replacement of that kind also hides the existing missing-pattern placeholder (`views/components/zone_pattern_editor.clj:293–297`; `subs.clj:1164–1172`; `views/dialogs/step_grid_editor.clj:45–50,69–103`). Shared parameter controls bypass the grid writer entirely (`views/components/step_grid.clj:364–373,395–404,420–438`).

## 3. Reuse / extend

| Existing contract | Use |
|---|---|
| `chain-config` (`events/handlers/chain/helpers.clj:26–39`); `chains/find-path-by-id`, `get-item-at-path` (`animation/chains.clj:95–136`) | Search **one** vector: `[:chains :cue-chains k :zone-patterns]` for a cue, `[:chains :effect-chains k :items]` for an effects cell. Group paths and misses already work (`T/animation/chains_test.clj:119–135`). |
| `preset-instance`, `ensure-item-fields`, `as-grid` (`routing/zone_patterns.clj:123–137`; `events/helpers.clj:22–32`; `routing/step_grid.clj:266–285`) | The preset lacks an ID; insertion assigns one. Converting an existing pattern changes only its params. |

## 4. Changes per namespace

1. **Open the displayed instance.** `T = {:domain d :entity-key [col row] :effect-instance-id P}` (`:effect-instance-id` **NEW on grid targets**; precedent: `events/handlers/chain/params.clj:73–79`). The chip uses `pattern` even when disabled (`views/components/zone_pattern_editor.clj:519–552`). Visual and Numeric **Edit grid…** carry `(:id effect)` from the instance supplying the displayed params, through the visual effect keys and numeric change template (`views/components/effect_parameter_editor.clj:93–100,138–154,169–179`; `views/components/effect_param_ui.clj:269–294`; dispatchers `views/components/zone_pattern_editor.clj:339–360,427–429`; `views/components/parameter_controls.clj:418–424`).
2. **Create only on explicit intent.** Only the chip displaying a cue without a pattern sends `{:event/type :step-grid/open :target {:domain :cue-zone-patterns :entity-key k} :create-first? true}` (**NEW**, event-only flag). `with-new-grid` / `handle-open` (`events/handlers/step_grid.clj:145–180`) must find no requested ID and the cue still present, unlocked and patternless. Insert once and take P from the **inserted item** in the same returned state. Otherwise do nothing. Ordinary Open requires a UUID; a stale/missing ID never creates or selects a replacement. Locked/keyframed patterns still open without conversion.
3. **Use one read/write lookup.** **NEW** `zone-pattern-items-path [domain entity-key]` beside `chain-config` (§3) restricts lookup to the two supported domains and a cell coordinate. **NEW** pure `locate-pattern [owner-items instance-id]` in `routing/step_grid.clj:1–14` uses `find-path-by-id`; return `{:effect-path p :instance fx}` only for a matching UUID and `:effect-id :zone-pattern`. `subs/step-grid` subscribes to the owner vector in its supplied context. `instance-path`, `pattern-at`, `grid-params` and `edit` use that same lookup on the current event state (`events/handlers/step_grid.clj:46–81,111–131`). Paths exist only for the current operation. A miss → selector `nil`, edits inert, dialog shows **“This pattern is no longer in this cell.”** and Close. Restoring P in the original owner lets the same window resolve it again.
4. **Guard shared controls too.** Send T's three fields plus `:dialog-id :step-grid-editor` (**NEW use** on grid-origin chain/modulator events; templates `views/components/step_grid.clj:367–373,424–438`). **NEW** pure `wrap-step-grid-control-target [handler]` in `events/handlers/step_grid.clj`, immediately outside Show Lock (`events/core.clj:645–653`), checks the tagged event matches the open T, resolves its pattern and rejects keyframed edits. Supply the current path **before** the lock classifies the effect (`events/show_lock.clj:100–116,162–173`). Preserve the address and dialog tag on today's inline parameter follow-ups (`events/handlers/modulator.clj:86–91,154–162,202–207`). [#07](07-modulator-field-patches.md#L30-L33) uses the same instance address; its field-event builder must retain the dialog tag when its direct writes replace those follow-ups.

## 5. State placement and undo

| Data | Writer / reader | Saved, undo, dirty? |
|---|---|---|
| `[:ui :dialogs :step-grid-editor]` — `:target T`, `:cursor`, `:every-laser`, `:open?` | Open/cursor/paint update it; Close hides it (`events/handlers/step_grid.clj:39,101–106,163–196,257–260`; `events/handlers/ui.clj:44–50`). Dialog subscribes to `subs/dialog-data` and `subs/step-grid` (`views/dialogs/step_grid_editor.clj:45–50,105–118`); preserve cursor/note only for the same T. | No. |
| `[:ui :step-grid :drag]`, transaction `:source-id [:step-grid T]`, `[:ui :clipboard]` (`events/handlers/step_grid.clj:248–285,290–307`) | Paint handlers own the drag; Copy and resumed Paste write the shared clipboard. Toolbar subscribes to `subs/can-paste?` (`subs.clj:100–107`; `views/components/step_grid.clj:365–366`); Z6's local clipboard was superseded by [keyboard 4a](../2026-11-13-keyboard-focus-window-lifecycle/04-phase4-clipboard.md#L412). | No. |
| Instance `:id`, `:params` in the two show-owned chains (§3; `state/persistent.clj:81–84`) | Open creates/converts; grid and tagged parameter handlers edit. The playhead uses the resolved ID for its existing snapshot lookup (`views/components/step_grid.clj:168–173,203–236`). | Yes for document changes; none for opening an existing grid, cursor, Copy or misses. One changed paint drag → one “Edit Step Grid” (`events/handlers/history.clj:212–229,272–324,369–383`; `state/history.clj:57–79`). |

## 6. Events and effects

All event types exist (`events/handlers.clj:55–57,69–74`). Post-open input must name the displayed T; remove the cursor's missing-target fallback (`events/handlers/step_grid.clj:182–197`). Release identifies its drag.

| Input → destination | Required behaviour |
|---|---|
| `:step-grid/open` from the chip and Visual/Numeric controls (§4.1) → `events/handlers/step_grid.clj:157–180,316` | Only the explicit first-pattern request may create one for the grid. |
| Keys, row/step menus, toolbar/length (`views/components/step_grid.clj:42–78,159,260–278,300–330,379–392,408–418`) → `events/handlers/step_grid.clj:317,322–339` | Arrows/Home/End, Space/Enter, Delete, Shift+arrows and menu edits all use T. Undo/Redo stay scene commands (`views/dialogs/common.clj:77–110,134–140`). |
| `:step-grid/pointer` (`views/components/step_grid.clj:137–159`) → `:step-grid/paint-start`, `/paint`, `/paint-end` (`events/handlers/step_grid.clj:217–238,240–285`) | Press and drag validate T before painting. Match the ordinary release to its recorded drag and transaction source, even if P vanished or the lock came on; a release for Q cannot commit P's gesture. |
| `:chain/update-param`, `/update-param-from-text` and `:modulator/*` (`views/components/step_grid.clj:367–372,424–438`; `views/components/modulator_param_control.clj:435–457,481–518`) → `events/handlers/chain/core.clj:63–71`, `events/handlers/modulator.clj:32–51,213–225` | Resolve the tagged event before delegating (§4.4); keep existing parameter/undo semantics. |
| `:step-grid/copy`, `/paste` from buttons/shortcuts (`views/components/step_grid.clj:52–55,413–416`) → `events/handlers/step_grid.clj:290–307`; `with-clipboard` (`events/helpers.clj:78–104`) | Copy reads P. Check T before requesting `:clipboard/read`; its `:then {:event/type :step-grid/paste :target T}` resumes with `:clipboard/system`. Check T again **before** adopting or applying the clip. |

No new effects: `:state` commits on the event thread; `:dispatch` continues inline. Only the existing `:clipboard/write` and `:clipboard/read` touch the OS, on the FX thread (`events/core.clj:24–34,165–178,242–274,719–720,750–751`). This follows the [state guide](../../docs/CLJFX_STATE_MANAGEMENT.md#reactive-state-in-this-app).

## 7. Five input → output examples

P and Q are distinct UUIDs; row 0 is `:zone/a`. Read/write invariant: **same domain, cell, UUID and pattern kind**. Never fall back to the old slot, current selection or another owner.

| Input | Expected result |
|---|---|
| Cue `[1 0]`, patterns `[]`, explicit create-first Open | One preset `{:id P …}`; dialog target names P. Undo → placeholder; Redo → P. A stale create request arriving after `[Q]` does nothing. |
| Effects `[2 1]`: Open P, `[P Q]` → `[Q {:type :group :items [P]}]`; P's steps `[[] []]`; Right, Space | Resolve `[1 :items 0]`; displayed ID P, cursor `[0 1]`, P's steps `[[] [:zone/a]]`. Copy contains those steps; a Rate change updates P only. Q is equal to its original value. |
| Open P with `[[] [] []]`; after a reorder, press row 0 step 0, drag to step 2, release | P gets `[[:zone/a] [:zone/a] [:zone/a]]`; Q unchanged, one more undo entry. Undo restores P's three rests and preserves the prior reorder. |
| Empty clipboard → Paste(T); reorder P and Q before reply `{:clipboard/system {:type :step-grid :grid {:zones [:zone/a] :steps [[:zone/a]]} :copied-at 5}}` | P takes the one-step grid; Q unchanged. If the window retargeted to Q instead, the same reply changes neither grid nor clipboard. |
| Original owner now `[Q]`; P exists in a different cell. Deliver old key, paste reply, paint or toolbar event for T | Selector `nil`, placeholder visible, Q and remote P unchanged. No clipboard effect, extra undo or dirty transition. Only restoring P in the original owner can make T resolve. |

## 8. Acceptance tests and fixtures

| Test var | Fixture / assertion |
|---|---|
| Extend `laser-show.events.step-grid-handlers-test/open-makes-a-grid-from-the-pattern-test` (`T/events/step_grid_handlers_test.clj:15–31,204–243`) | Marked-clean starter, explicitly empty cue. Assert document UUID = dialog UUID, one undo entry for creation/conversion, none for reopen; example 1 plus missing cue, locked empty cue and stale Open(P) over Q. |
| Extend `laser-show.subs-test/step-grid-sub-test` (`T/subs_test.clj:35–36,1299–1352`) | Advance a real cljfx context through examples 2 and 5; check `:id`, `:lit`, cursor and nil. Use UUIDs; for the existing style-change case keep the **same** ID. A different ID replacing it is a separate negative case. |
| **NEW** `laser-show.events.step-grid-handlers-test/edits-follow-the-open-pattern-test` | Extend `with-grids`, `run`, `mouse` and undo-depth fixtures (`T/events/step_grid_handlers_test.clj:15–68,95–121`). Open T before commands. Exercise both owner domains, a nested effect, examples 2–3, direct and delegated toolbar writes, deletion between press and drag, and wrong-kind/keyframed/locked targets. Assert whole sibling maps and normal release/undo. |
| **NEW** `laser-show.events.step-grid-handlers-test/parked-paste-rechecks-grid-target-test` | Use the real pipeline's returned `:then` and synthetic `:clipboard/system` clip from `T/events/step_grid_handlers_test.clj:281–296`; deliver it after reorder, deletion and retarget. Check examples 4–5, including unchanged undo depth on rejection. |
| **NEW** `laser-show.ui-test.step-grid-test/open-window-follows-pattern-through-reorder-and-removal-test` | `with-toolkit`/`with-app`, distinct lit cells, `key!` and `fire-grid!` (`T/ui_test/fixtures.clj:18–25,48–59`; `T/ui_test/step_grid_test.clj:31–54,87–131`). Open from Numeric; keep the chip/Visual opener cases at `T/ui_test/step_grid_test.clj:136–190`. Reorder via the effects list's `:chain/set-items` (`views/dialogs/effect_chain_editor.clj:127–139`; `events/handlers/chain/core.clj:28–35`). Wait for document **and** displayed cells after input. Delete P: assert placeholder, absent grid body and available Close. |

Update path-only fixtures to open T (use explicit creation for a patternless cue): `T/events/step_grid_handlers_test.clj:37–68`; `T/events/show_lock_test.clj:117,185–198,418–467`; `T/ui_test/step_grid_test.clj:31–54`; `T/ui_test/dialogs_test.clj:106–122,147–148,214–216`; `T/ui_test/clipboard_test.clj:594–605`; `T/ui_test/keyboard_trigger_test.clj:298–305`. Keep current lock, keyframe and one-drag undo assertions.

## 9. Scope

**#05 owns the pattern address. [#16](16-gesture-undo-boundaries.md) owns gesture identity and finishing on close, blur, disposal or missed release** (`events/handlers/ui.clj:44–50`). [#20](20-document-completions.md) owns rejecting a paste from an earlier document.
