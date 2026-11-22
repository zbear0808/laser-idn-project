# Phase 4 — One clipboard

**Written:** 2026-11-13   **Written for commit:** `cb32414` (`master`)   **Updated:** 2026-11-20 at `3229c95`, re-checked at `cc9e000`: every citation re-checked, probes p5–p7 re-run and p9–p17 added, the phase split into Steps 4a–4c, D13 and D14 added. Re-checked 2026-11-21 at `a38ea43`, after the output drivers, zones Z4–Z6, vibe Phase 8, Decimate's follow-ups, the Projectors layout pass and the engine snapshot: every Step 4a citation re-checked, probes p5–p7 and p9–p17 re-run and p18 added, and Step 4a takes in the step grid's clipboard (zones Z6, §1 row 19)
**Status:** In progress. **Step 4a done** (2026-11-21, `02ae75c`..`1113d9a`; §9a As built), D8, D13 and D14 answered (2026-11-21, the ★ options). Steps 4b and 4c need D9   **Last verified against:** `a38ea43` (probes p5–p7, p9–p18, `probes/keyboard_probes_test.clj`, run at `3229c95`, `cc9e000` and `a38ea43`; headless JavaFX 28-ea+11, JDK 25.0.5, macOS arm64)
**Supersedes / Superseded by:** replaces the clipboard parts of `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md` (§3.6, §3.7, §5.A.2). Takes over review 02 #15, the clipboard bullets of 02 #2, M1 step 2 and M4, the "clipboard effect stubs" carry-over (`02-events-state.md:363`), review 01 F7 and Q6, and "Left: the clipboard helper" in the improvement review's overview (row 11). Zones Z0 already fixed the projector chain's part of K-8 (B-8b, `f044a31`), and `95a510d` removed `:clipboard/paste-projector-effects`   **Related findings:** K-6…K-11 (`00-overview.md` §4); review 01 F7, Q6; 02 #2, #15, M1, M4
**Size:** ~2–2.5 days (4a ~1.25, 4b ~0.75, 4c ~0.25)   **Needs:** none of Phases 2–3 (§0)

Copy and paste are written twice, with different type names and different read rules. The list half writes the clipboard behind the event pipeline's back, so the pipeline undoes the write (p9). Every copy and every list paste makes the event agent wait for the FX thread (p10). Two views read the OS clipboard while rendering (p11), and the Paste buttons disagree with ⌘V (p5, p11, p12). **Step 4a makes one clipboard and ships on its own.** 4b settles which list takes what and gives pasted things new ids; 4c makes Cut one step.

## 0. Steps and dependencies

| Step | What ships | Fixes | Needs | Size |
|---|---|---|---|---|
| **4a** One clipboard (**done**, §9a) | `[:ui :clipboard]` is written only in the `:state` handlers return. Two NEW effects write and read the OS clipboard on the FX thread, and the event agent never waits for them. Nothing reads the OS clipboard while rendering. Paste buttons and menu items follow the clipboard. The dialog-local clipboards, the step grid's own clipboard and the dead code go | the lost update (K-7, p9), the waits (p10), the OS reads in handlers and render (K-10, p11), Paste buttons that never light or light wrongly (p5, p11, p12), the data-loss half of K-6 (p16), K-11, the step grid's second clipboard (p18) | D8, D13, D14 (answered) | ~1.25 d |
| 4b Types and ids | each list's type comes from its component id; a copied cell pastes into a list; pasted things get new ids at every depth; an empty cell copies nothing | K-8 (p7, p13), K-9 (p6, p14, p15) | D9 | ~0.75 d |
| 4c Cut | Cut is one event and one undo step, with a Cut button | the rest of K-6 | D9 | ~0.25 d |

**4a ships first and alone.** It keeps today's rules for which target takes which clip (§7, first table), so nothing that pastes today stops pasting and nothing pastes where it couldn't before. 4b and 4c use 4a's helpers and can follow in either order.

**Keyboard Phases 2 and 3 have not landed.** `02-phase2-dialog-shell.md` and `03-phase3-key-handling.md` are Proposed with D4–D7 open, there is no `views/keys.clj`, and the list's key filter still asks `.isFocused` (`views/components/list.clj:747, 762`).

**Phase 4 no longer depends on Phase 3.** D7 was about how the list's keys learn its `:clipboard-type`. Since `f044a31` (2026-11-19), `list-editor` reads it from `:on-change-params` and gives it both to the props atom the keys read (`views/components/list.clj:875`) and to `list-sidebar`'s toolbar (`:916`). 4b then derives the type from the component id in the handler, so it needs no props at all. What is left is a shared file:
- Phase 3 rewrites the filter's focus test (`:747, 762`) and the props atom (`:58-78`).
- 4a removes props (`:870-876`); 4c changes the ⌘X branch (`:775-779`).

Whichever lands second rebases. Phase 2 isn't needed either: nothing here adds a stage listener (only D13 C would). `00-overview.md` §6 still lists Phase 3 as a dependency. That row is outside this update.

**Sibling plans** (`plans/2026-11-20-state-integrity/`, written alongside this update):
- 01 (engine snapshot) doesn't touch the clipboard.
- 02 (direct state writes) leaves `set-internal-clipboard!` (`state/clipboard.clj:31-34`) to this phase; 4a removes it with every other clipboard write outside the pipeline. Both plans edit the "Writers outside the pipeline" sentence (`events/core.clj:36-41`) and the `effects` map (`:584-645`). Whichever lands second edits what the other left. Neither waits for the other.

**Re-checked at `a38ea43` (2026-11-21).** Since `cc9e000`, `master` gained the output drivers, zones Z4–Z6, vibe Phase 8, Decimate's follow-ups, the Projectors layout pass and the engine snapshot. Of the files Step 4a edits, `state/clipboard.clj`, `events/helpers.clj`, `events/safety_guard.clj`, `views/components/list.clj`, `views/components/title_bar.clj` and `events/handlers/menu.clj` are unchanged; every other citation below is given at `a38ea43`. A search of `src/` for copy, paste and `Clipboard` found one new copy/paste path and no new OS clipboard reads or writes behind the pipeline:
- **The step grid (zones Z6) has a clipboard of its own**, `[:ui :step-grid :clipboard]` (§1 row 19, p18). It is written in its handler's `:state`, so it is no lost update, but it is a second clipboard: it never reaches the OS clipboard, its Paste ignores what the other lists copy, and a list copy doesn't replace it. Step 4a brings it under the one clipboard, with a clip of type `:step-grid` (§4, §7).
- Two changes to code 4a edits: grid paste now passes `migration/cue-z4` as its `transform-fn` (`events/handlers/grid.clj:171-174`, row 10), and a list paste of `:cue-chain-items` runs `migration/items-z4` (`events/handlers/list.clj:267-269`). 4a keeps both.
- Not clipboard paths: `:projectors/copy-safety` (a safety zone copied to other lasers, `events/handlers/projector.clj:760`), `:zone-maps/duplicate` (`events/handlers/zone_maps.clj:82-86`), the keyframe panel's "Copy Params" (a comment, `visual_editors/keyframe_modulator_panel.clj:18`), and the drag-and-drop `ClipboardContent`s (§10).

## 1. Current behaviour (file:line, at `a38ea43`)

"Run" means a probe in `probes/keyboard_probes_test.clj` printed it on 2026-11-20 at `3229c95` and again on 2026-11-21 at `a38ea43` (the numbers say which); "read" means it comes from reading the code.

| # | What happens today | Evidence |
|---|---|---|
| 1 | **A list copy is undone.** ⌘C and Copy dispatch `:list/copy-selected` (`views/components/list.clj:136-143`, from `:694` and `:764-767`). `handle-copy-selected` (`events/handlers/list.clj:235-249`) calls `clipboard/copy-cue-chain-items!`, `-item-effects!` or `-timeline-tracks!` by `:clipboard-type` (`:244-247`). Each writes `[:ui :clipboard]` with `state/assoc-in-state!` (`state/clipboard.clj:31-34`, a `swap!` at `state/core.clj:152-162`) and writes the OS clipboard. The handler then returns `{:state state}` (`:248`), the snapshot from before the write, and `effect-state` (`events/core.clj:154-162`) writes that back | run p5: slot `nil`, OS text starts `{:type :cue-chain-items,`. Run p9: in dev mode the lost-update detector (`events/core.clj:128-137`, attached by `wrap-lost-update-check`, `:139-152`) reports `[[:ui :clipboard]]` for `:list/copy-selected` |
| 2 | **A cell copy writes twice.** `:grid/copy-cell` and `:effects/copy-cell` (`events/handlers/grid.clj:157-164`, `events/handlers/effects.clj:61-68`; from the cell menus `grid_cell.clj:330-331, 494-495` and Edit > Copy `events/handlers/menu.clj:283-303`) use `h/handle-copy-to-clipboard` (`events/helpers.clj:49-72`). It calls `clipboard/copy-cue-chain!` or `copy-effects-cell!`, which write the slot out of band (with `:copied-at`) and the OS clipboard (`:69-70`). Then it returns its own slot value, without `:copied-at` (`:72`), and that one wins | run p9: the detector reports `[[:ui :clipboard]]` for `:grid/copy-cell`; the slot ends with keys `[:data :type]` |
| 3 | **Hidden OS reads in a handler.** `handle-paste-items` (`events/handlers/list.clj:252-284`) calls `clipboard/get-clipboard` (`:258`; the OS clipboard first, then the slot, `state/clipboard.clj:104-109`). For an `:item-effects` list it also calls `get-effects-to-paste` (`:261`, `state/clipboard.clj:228-239`), which reads the OS clipboard again. Cell paste reads only the slot (`events/helpers.clj:91`) | run p5: ⌘V pastes (3 → 4 items) from the OS text while the slot is empty |
| 4 | **The event agent waits for the FX thread.** JavaFX's `Clipboard` works only on the FX thread: `getContent`, `hasContent` and `setContent`, which `getString`, `hasString` and `putString` go through, call `Toolkit.checkFxUserThread()` (javafx-graphics 28-ea+11 bytecode). The helpers wait from the agent with `@(fx/on-fx-thread …)` (`state/clipboard.clj:64, 82, 116`); cljfx's `on-fx-thread` is a `Platform/runLater` plus a promise (`cljfx/platform.clj:6-27`). Every event queued behind waits too: MIDI and OSC triggers, the vibe keys | run p10: `getString` off the FX thread throws `IllegalStateException: Not on FX application thread`. With the FX thread busy for 500 ms, a BPM change lands after 1–2 ms on its own, but after 514–535 ms behind a list copy and 503–579 ms behind a list paste (5 runs at `3229c95`); at `a38ea43`, 2 ms, 513–532 ms and 502–523 ms (3 runs, load average 6–13). With the FX thread idle, a copy plus a BPM change take a median of 4.7–18.5 ms (load average 5–23); 12.1–13.3 ms at `a38ea43` |
| 5 | **OS reads while rendering.** `projector-config-panel` (`views/tabs/projectors.clj:688`) and `effect-chain-editor-content` (`views/dialogs/effect_chain_editor.clj:96`) call `clipboard/get-effects-to-paste` inside the render function. The Paste button they light is stale, because nothing they subscribe to changes on a copy | run p11: the read happens on the JavaFX Application Thread inside `projector_config_panel`. After Copy, the same chain's Paste stays disabled, with no read, until selecting another effect re-renders the panel. Run p12: with effects (`:item-effects`) on the OS clipboard, the effects grid's chain editor enables Paste, and clicking it pastes nothing (1 → 1), because that list pastes as `:cue-chain-items` |
| 6 | **The Cue Chain Editor's Paste never lights.** `list-sidebar` enables Paste from its `:clipboard-items` prop (`views/components/list.clj:615`). The editor passes dialog-local slots (`cue_chain_editor.clj:326, 329-330`). Only `:cue-chain/set-clipboard` and `:cue-chain/set-item-effects-clipboard` write those (`events/handlers/cue_chain.clj:159-175`), and both are dispatched only from `:on-copy-fn` callbacks (`cue_chain_editor.clj:391-393, 418-422`). `list-sidebar` takes that prop as `on-copy` (`:598`) and never calls it. Both handlers also call `clipboard/copy-*!` (`cue_chain.clj:165, 174`), which would make a third copy of the clip | run p5: both Paste buttons disabled (`[true true]`). The dead prop: read (`on-copy` appears only at `:598, 846, 876, 917`) |
| 7 | **A view writes the clipboard.** The effects grid's chain editor gives its list `:on-copy-fn` calling `clipboard/copy-effect-chain!` (`effect_chain_editor.clj:145-146`): view code writing state outside the pipeline. It never runs (the same uncalled prop) | read |
| 8 | **Which list takes what.** Since `f044a31`, `list-editor` takes `:clipboard-type` from `:on-change-params` (`views/components/list.clj:875, 916`). Only the projector chain passes one (`views/tabs/projectors.clj:535`, `:item-effects`). The effects grid's chain (`effect_chain_editor.clj:144`) and an item's effects (`cue_chain_editor.clj:414`) default to `:cue-chain-items`: they take a cue's presets, and give their effects to the cue list | run p7: a `:preset` lands in effect chain [0 0]. Run p13: a preset lands in item 0's effects, and a `:scale` effect among cue [0 0]'s items |
| 9 | **Cut is two events.** ⌘X dispatches the copy, then the delete (`views/components/list.clj:775-779`). The copy's slot write is undone (row 1). If the OS write fails, `copy-to-system-clipboard!` logs and returns false (`state/clipboard.clj:71-73`), and the delete runs anyway | run p16, with the OS write failing: 3 → 2 items, slot `nil`, OS `""`, undo label "Delete Item(s)"; then ⌘V: 2 → 2 |
| 10 | **Ids.** List paste copies items with fresh ids (`chains/deep-copy-items`, `animation/chains.clj:217-239`), but a preset's `:effects` keep their ids, inside a group too. Cell paste keeps every id: grid paste passes `migration/cue-z4` as `handle-paste-from-clipboard`'s `transform-fn` (`events/helpers.clj:90-95`; `grid.clj:171-174`), which converts an older build's cue and keeps its ids, and effects paste passes none (`effects.clj:73-75`). Some state is keyed by these ids, for example an effect's errors under `[:effect <instance id>]` (`backend/output_errors.clj:12`) | run p15 (effect ids kept); run p14 (A1 and B2 share item ids) |
| 11 | **An empty cell's copy empties a cell.** Edit > Copy on an empty selected cell stores `{:type :cue-chain :data nil}` (`events/helpers.clj:66-72`; no OS write, `:69`). Edit > Paste of it sets the target cell to nil (`:95`) | run p14: the `:triangle` cue at [2 0] becomes `nil` (one undo step, labelled "Paste") |
| 12 | **Menu items.** Edit > Paste is enabled whenever the slot holds anything (`views/components/title_bar.clj:105-106, 135`). Once list copies land in the slot, it would be enabled where nothing can be pasted. The cell menus' Paste items are always enabled (`grid_cell.clj:333-334, 349-350, 497-498`) | read |
| 13 | **Dead code.** No handler emits `:clipboard/copy-effects` or `:clipboard/paste-effects` (`events/core.clj:216-233`, registered at `:642-643`, documented at `:613-614`); `test/laser_show/events/core_test.clj:53` lists them as registered. `:effects/insert-pasted` is a TODO stub (`events/handlers/effects.clj:170-177`, routed at `:208-209`), reached only from `:clipboard/paste-effects` (`events/core.clj:228`). `events/handlers/projector.clj` has no clipboard code left. Called only from tests: `clear-clipboard!`, `clipboard-has-type?`, `clipboard-empty?`, the cell-assignment trio (`state/clipboard.clj:111-165`), `get-clipboard-description` (`:326-347`) and `copy-timeline-tracks!` (`:294-304`). No view passes `:timeline-tracks`: the timeline's track sidebar is not a `list-editor`; only `list_handlers_test.clj:426-440` uses it | grep |
| 14 | **Types.** `clipboard-types` (`state/clipboard.clj:40-42`) names 11 types. Live code writes four: `:cue-chain-items`, `:item-effects` (projector chains only), `:cue-chain` and `:effects-cell`. Nothing writes `:cue`, `:zone`, `:projector`, `:effect`, `:effect-chain`, `:cell-assignment` or `:timeline-tracks` | grep |
| 15 | **Stale docs.** `state/clipboard.clj:21-24` ("uses database/dynamic.clj !ui atom", "state/!ui") and `:156` ("falls back to internal atom"). `events/core.clj:36-37` names "the clipboard helper (state/clipboard.clj)" among the writers outside the pipeline | read |
| 16 | **Unit tests write the developer's clipboard.** The unit suite runs without the headless platform. `test/laser_show/state/clipboard_test.clj` (`clear-clipboard!` at `:14, 24, 30, 40`), `grid_handlers_test.clj:187-196` and `menu_handlers_test.clj:38-63` run the real `copy-*!`, so they write the Mac's pasteboard | read (not run: it would overwrite the user's clipboard) |
| 17 | **The slot survives Open and New, and a copy isn't an edit.** `reset-session` (`state/persistent.clj:185-213`, called at `:239` and `:261`) leaves `[:ui :clipboard]` alone, and `:ui` is not a document root (`state/history.clj:57-59`) | read |
| 18 | **What the EDN costs.** `ser/serialize-for-clipboard` pretty-prints (`state/serialization.clj:204-211`, which calls `pprint` at `:38`), on the agent (`state/clipboard.clj:61`) | run p17 at `3229c95` (pprint and read-back from three runs, `pr-str` from two; load average 9–23): 3 presets (671 chars): pprint 2.8–4.5 ms, `pr-str` 0.04–0.08 ms, read 0.07–0.10 ms. 50 presets × 3 effects (34 KB): 58–66 ms, 0.86–0.87 ms, 1.5–1.9 ms. A timeline cell with 300 clips × 2 effects (197 KB): 283–313 ms, 3.2–3.6 ms, 7.1–7.4 ms. At `a38ea43` (one run, load average 6–13): 2.0, 0.03 and 0.06 ms; 56, 1.4 and 3.2 ms; 253, 1.9 and 7.4 ms |
| 19 | **The step grid has a clipboard of its own** (zones Z6, `08-phase-z6-step-grid.md` §5). `:step-grid/copy` (`events/handlers/step_grid.clj:286-291`, from Copy and ⌘C, `views/components/step_grid.clj:410-411, 52`) writes `[:ui :step-grid :clipboard]` `{:zones :steps}` (`routing/step_grid.clj:290-294`) in its handler's `:state`. `:step-grid/paste` (`step_grid.clj:323`, from Paste and ⌘V, `views/components/step_grid.clj:412-413, 53`) reads only that slot. Its Paste is disabled while that slot is empty (`subs/step-grid-clipboard`, `subs.clj:1341-1344`; `views/components/step_grid.clj:363, 413`). Nothing puts a grid on the OS clipboard, a list copy doesn't replace it, and its Paste ignores the one clipboard | run p18 (`a38ea43`): Paste disabled before a copy and after a cue list copy (OS text `{:type :cue-chain-items,`); after the grid's Copy, `[:ui :step-grid :clipboard]` has `[:steps :zones]`, `[:ui :clipboard]` is `nil`, the OS text is still the cue items', and Paste is enabled |

p5–p7 print the same at `3229c95` as at `cb32414`. Every probe, p5–p7 and p9–p17, printed the same findings at `cc9e000` and again at `a38ea43` (2026-11-21, load average 6–13); p18 was added at `a38ea43`.

## 2. Root cause

Two clipboards share one state slot. The state helper (`state/clipboard.clj`) writes the slot itself, from inside handlers, behind the pipeline's back, so the handler's own write-back undoes it. It also talks to the OS clipboard synchronously, so whoever calls it waits: a handler on the agent, or a render function on the FX thread. The views then each worked out "is there anything to paste?" for themselves: from dialog-local slots fed by callbacks nobody calls, or from an OS read while rendering. And a list's clipboard type is a prop that two of the four lists don't pass. The step grid, written later, avoided the first two by keeping a slot of its own, which made a third clipboard.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `[:ui :clipboard]` (`state/domains.clj:155-156`), `ex/clipboard` (`state/extractors.clj:141-142`), `subs/clipboard` (`subs.clj:96-97`) | The one clipboard |
| `h/current-time-ms` (`events/helpers.clj:11-18`) | `:copied-at` from the event's `:time` co-effect, so copy handlers stay pure |
| `effect-show-file-chooser` (`events/core.clj:432-489`): `Platform/runLater`, then `dispatch` | The shape of `:clipboard/read`. Its callback is queued on the agent, as every "call back later" effect's is since output-runtime Phase 3 |
| `safety-guard/parked` (`events/safety_guard.clj:34-37`) | Strips `:state`, `:time` and `:fx/event` from the paste the read continues with. Moves to `events/helpers.clj`; `safety-guard/ask` (`:53`) calls it there |
| `h/show-notice` (`events/helpers.clj:109-123`) | "Nothing to paste here" |
| `h/handle-copy-to-clipboard`, `h/handle-paste-from-clipboard` (`events/helpers.clj:49-96`), including the unused `transform-fn` (`:90`) | Cell copy and paste keep their shape; 4b passes `chains/fresh-ids` as `transform-fn` |
| The pure parts of `state/clipboard.clj`: the envelope check in `read-from-system-clipboard` (`:85-95`), the type rule of `get-effects-to-paste` (`:234-239`), `serialize-effect-chain` (`:174-206`), the clip shapes built by the `copy-*!` fns (`:252-254, 268-270, 283-285, 317-319`) | They become the pure helpers in §4 |
| `ser/serialize` (`state/serialization.clj:25-39`) and `ser/deserialize` (`:41-69`) | The clipboard text: one line of EDN (`pr-str`), read back silently |
| `chains/deep-copy-item(s)` (`animation/chains.clj:217-239`) | Rebuilt on NEW `fresh-ids` with the same contract; now nested `:effects` get new ids too |
| `cue-chains/timeline-cue?` (`animation/cue_chains.clj:59`) | 4b: a timeline cell isn't pasted into a list |
| `normalize-selected-ids`, `collect-by-ids`, `remove-by-ids`, `insert-after-id` (`events/handlers/list/helpers.clj:56, 34, 122, 218`) | Unchanged. Cut uses what copy and delete use |
| `:history/description` on the change event (`events/handlers/history.clj:288`; Delete sets it at `events/handlers/list.clj:228-231`) | "Cut Item(s)" |
| The dev-mode lost-update detector (`events/core.clj:128-162`) | Shows 4a works: p9 reports nothing afterwards |
| The headless platform's clipboard (`deps.edn:225-229`) | UI tests check the OS text without touching the Mac's pasteboard |
| `routing.step-grid/copy` and `paste` (`routing/step_grid.clj:290-306`), the step grid's `edit` (`events/handlers/step_grid.clj:107-122`) | The step grid's clip carries `copy`'s `{:zones :steps}`; its paste is `edit` with `paste`, as today |

## 4. Changes per namespace

Paths are under `src/laser_show/` unless they start with `test/`.

### Step 4a: one clipboard

- `laser-show.state.clipboard` (`state/clipboard.clj`) becomes pure: no `state.core`, no JavaFX, no `fx/on-fx-thread`. New ns doc: what a clip is, where it lives, and that only two effects in `events/core.clj` touch the OS clipboard.
  - Kept: `clipboard-types` (`:40-42`), `serialize-effect-chain` (`:174-206`).
  - NEW `list-clip [type items now]`: the shapes `copy-cue-chain-items!`, `copy-item-effects!` and `copy-timeline-tracks!` build today (§7), without writing anything.
  - NEW `cell-clip [type cell now]`: `{:type type :data cell :copied-at now}`.
  - NEW `grid-clip [grid now]`: `{:type :step-grid :grid grid :copied-at now}`, `grid` being `routing.step-grid/copy`'s `{:zones :steps}` (§1 row 19). `clipboard-types` gains `:step-grid`.
  - NEW `->system-text [clip]`: `(ser/serialize clip)`, one line of EDN (§10 says why not pretty-printed).
  - NEW `<-system-text [text]`: the clip, or nil. It returns nil unless the text is a map with a known `:type` (the checks at `:85-95`), and it doesn't parse a text over 5,000,000 characters (p17's largest clip is 196,761).
  - NEW `accepts? [clip target]` and `payload [clip target]`: today's rules, unchanged (§7, first table), and a step grid (`:step-grid`) takes a `:step-grid` clip's `:grid`.
  - NEW `adopt [state clip]`: puts a clip read from the OS clipboard in the slot, but only while the slot is still empty.
  - Removed: `get-internal-clipboard`, `set-internal-clipboard!`, `get-system-clipboard`, `copy-to-system-clipboard!`, `read-from-system-clipboard`, `get-clipboard`, `clear-clipboard!`, `clipboard-has-type?`, `clipboard-empty?`, `copy-cell-assignment!`, `paste-cell-assignment`, `can-paste-cell-assignment?`, `copy-effect-chain!`, `get-effects-to-paste`, `copy-cue-chain!`, `copy-effects-cell!`, `copy-cue-chain-items!`, `copy-timeline-tracks!`, `copy-item-effects!` and `get-clipboard-description`, with the stale comments (`:21-24`, `:156`).
- `laser-show.events.core`:
  - NEW effect `:clipboard/write`. Its value is a clip. It serializes on the agent with `->system-text` (at most 3.6 ms for a 300-clip timeline cell, p17), then `Platform/runLater` puts the text on the OS clipboard through NEW private `put-system-clipboard-text!`. A failure logs a WARN. Nothing derefs it, and no event follows.
  - NEW effect `:clipboard/read`. Its value is `{:then event}`. `Platform/runLater` reads the text through NEW private `system-clipboard-text` (`hasString`, then `getString`) and parses it with `<-system-text`, then calls `(dispatch (assoc then :clipboard/system clip))`, with `clip` possibly nil. This is a "call back later" effect: in production, the follow-up waits its turn on the agent.
  - Remove `effect-clipboard-copy-effects` and `effect-clipboard-paste-effects` (`:216-233`), their rows (`:641-643`) and their docstring lines (`:613-614`). The `effects` docstring lists the two new effects with their class ("no follow-up"; "later, FX thread").
  - Ns doc (`:36-37`): drop "the clipboard helper (state/clipboard.clj)" from the writers outside the pipeline.
- `laser-show.events.helpers`:
  - NEW `parked [event]`, moved from `events/safety_guard.clj:34-37`.
  - NEW `with-clipboard [event f]`, the one place a paste looks at the clipboard:
    - If the slot is empty and the event has no `:clipboard/system` key, it returns `{:clipboard/read {:then (parked event)}}`.
    - Otherwise it calls `(f state' clip)`, where `state'` is `(clipboard/adopt state (:clipboard/system event))` and `clip` is `state'`'s slot.
    - If `f` returns nil after a read, it returns `(show-notice state' "Nothing to paste here" :info)`. If `f` returns nil without a read (the slot holds a clip this target doesn't take), it returns `{:state state'}`.
  - `handle-copy-to-clipboard` (`:49-72`) becomes `[state source-path clipboard-type now]`. It returns `{:state (assoc-in state [:ui :clipboard] clip) :clipboard/write clip}` with `clip` from `clipboard/cell-clip`. The `system-clipboard-fn` parameter goes.
  - `handle-paste-from-clipboard` (`:74-96`) becomes `[event target-path expected-type & [transform-fn]]`, built on `with-clipboard`.
- `laser-show.events.safety-guard`: `parked` (`:34-37`) goes; `ask` (`:53`) calls `h/parked`.
- `laser-show.events.handlers.list`:
  - `handle-copy-selected` (`:235-249`) returns `{:state (assoc-in state [:ui :clipboard] clip) :clipboard/write clip}`. `clip` comes from `clipboard/list-clip`, with the event's `:clipboard-type` (default `:cue-chain-items`, as today) and `(h/current-time-ms event)`.
  - `handle-paste-items` (`:252-284`) goes through `h/with-clipboard`, with the items from `clipboard/payload`. Lines `:265-283` are unchanged (including `migration/items-z4`, `:267-269`). Docstring `:253-256` updated.
- `laser-show.events.handlers.grid` (`:157-174`) and `laser-show.events.handlers.effects` (`:61-75`): use the helpers' new signatures (grid paste keeps `migration/cue-z4` as its `transform-fn`); the `clipboard` require goes where nothing uses it (`grid.clj:9`, `effects.clj:22`).
- `laser-show.events.handlers.menu`: `handle-edit-copy` and `handle-edit-paste` (`:298-299`, `:318-319`) pass the event's `:time` into the events they build, so `:copied-at` comes from the co-effect.
- `laser-show.events.handlers.cue-chain`: remove `handle-cue-chain-set-item-effects-clipboard` and `handle-cue-chain-set-clipboard` (`:159-175`), their routes (`:279-280`), `item-effects-ui-path` (`:38-43`, used only by the first), the `:clipboard nil` reset (`:62`), the require (`:26`) and the ns doc line (`:11`).
- `laser-show.events.handlers.effects`: remove `handle-effects-insert-pasted` (`:170-177`) and its route (`:208-209`), and the `log` require (`:19`), which only it uses.
- `laser-show.events.handlers.step-grid` (§1 row 19): `handle-copy` (`:286-291`) returns `{:state (assoc-in state [:ui :clipboard] clip) :clipboard/write clip}`, `clip` from `clipboard/grid-clip` and `(h/current-time-ms event)`. `:step-grid/paste` (`:323`) goes through `h/with-clipboard`, pasting `(clipboard/payload clip :step-grid)` with `edit` as today. The ns doc (`:17-18`) says Copy uses the one clipboard.
- `laser-show.subs`: NEW `can-paste? [context target]`. Per D13 B, it is true when the slot is empty or `(clipboard/accepts? clip target)`. `step-grid-clipboard` (`:1341-1344`) goes.
- `laser-show.views.components.list`:
  - `list-sidebar`'s Paste is disabled when `(not (fx/sub-ctx context subs/can-paste? clipboard-type))` (`:615`).
  - Remove `:clipboard-items`, `:on-copy` and `:on-copy-fn` (`:598, 771, 830-831, 846, 859, 874, 876, 907, 917`).
- `laser-show.views.dialogs.cue-chain-editor`: remove `get-item-effects-clipboard` (`:281-284`), `clipboard` from the destructuring (`:299`), `clipboard-items` and `item-effects-clipboard` (`:325-330`), and both lists' `:on-copy-fn` and `:clipboard-items` (`:391-394`, `:418-423`). Update the ns doc line (`:12`).
- `laser-show.views.dialogs.effect-chain-editor`: remove the render-time read (`:96`), `:on-copy-fn` (`:145-146`), `:clipboard-items` (`:147`) and the require (`:20`).
- `laser-show.views.tabs.projectors`: remove the render-time read (`:688`), the `:clipboard-items` prop and its doc (`:518-519, 536, 733`) and the require (`:20`); the comment at `:533-534` says what lights Paste.
- `laser-show.views.components.title-bar` (`:105-106, 132-136`): Edit > Paste is disabled unless `can-paste?` holds for the active tab's cell type (`:grid` → `:cue-chain`, `:effects` → `:effects-cell`). On other tabs it is disabled.
- `laser-show.views.components.grid-cell` (`:333-334, 349-350, 497-498`): the cell menus' Paste items get the same `:disable`, for `:cue-chain` or `:effects-cell`.
- `laser-show.views.components.step-grid` (`:363, 412-413`): the step grid's Paste is disabled when `(not (fx/sub-ctx context subs/can-paste? :step-grid))`.
- `laser-show.state.domains`: drop `:clipboard nil` from the Cue Chain Editor's default (`:171`). The `:clipboard` doc (`:155-156`) says what a clip is and that only handlers write it.

### Step 4b: types and ids

The citations in Steps 4b and 4c are at `cc9e000`, apart from those Step 4a's re-check fixed in passing; re-check them before 4b starts.

- `laser-show.state.clipboard`:
  - NEW `list-type [component-id]` (the table in §7).
  - `accepts?` and `payload` become strict, with D9 A's two conversions (§7, second table).
  - `clipboard-types` becomes the four written types; `<-system-text` returns nil for any other type.
  - `list-clip` drops `:timeline-tracks`.
- `laser-show.events.handlers.list`: copy and paste take the type from `(clipboard/list-type component-id)` and ignore an event's `:clipboard-type`.
- `laser-show.views.components.list`: remove the `:clipboard-type` plumbing (`:22, 139-143, 148-154, 875, 916`). Paste's target is `(clipboard/list-type component-id)`.
- `laser-show.views.tabs.projectors` (`:424-426`): `:on-change-params` loses `:clipboard-type`.
- `laser-show.animation.chains`:
  - NEW `fresh-ids [x]`: every uuid that is the `:id` of a map inside `x` is replaced by a new one, everywhere it appears in `x`. So `:track-id` follows its track, and a uuid not defined inside `x` stays.
  - `deep-copy-item` (`:217-229`) becomes `fresh-ids`: same contract, and now nested `:effects` and every other inner `:id` too. Callers don't change: `events/handlers/list.clj:242, 266` and `timeline/model.clj:576` (a duplicated clip's effects get new ids too).
- `laser-show.events.helpers`:
  - `handle-copy-to-clipboard`: a cell that is nil, or has no `:items` and no `:tracks`, copies nothing (`{:state state}`, no write).
  - Grid and effects paste pass `chains/fresh-ids` as `transform-fn`.

### Step 4c: Cut

- `laser-show.events.handlers.list`: NEW `handle-cut-selected` (`:list/cut-selected`). It normalizes the selection as copy and delete do (`:217-221`, `:237-241`), then returns:
  ```clojure
  {:state (-> state (assoc-in [:ui :clipboard] clip) (assoc-in [:list-ui component-id] cleared))
   :clipboard/write clip
   :dispatch (assoc on-change-params :event/type on-change-event :items new-items
                    :history/description "Cut Item(s)")}
  ```
  It is routed next to `:list/copy-selected` (`:439`), and the `handle` docstring (`:407-410`) lists it.
- `laser-show.views.components.list`:
  - NEW `dispatch-cut-selected!`; ⌘X (`:775-779`) dispatches it.
  - D9 A: a Cut button, `#chain-cut-selection`, between Copy and Paste (`:686-703`). It is disabled with nothing selected, and its tooltip reads "Cut the selected items (⌘X)" (`shortcuts/shortcut-text`, `views/components/shortcuts.clj:12-23`).

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:ui :clipboard]`: a clip (§7) or nil | app state, UI (`state/domains.clj:155-156`) | handlers only: copy and cut (in the `:state` they return), and a paste whose read found a clip while the slot was empty (`adopt`) | No and no: `:ui` is not a document root (`state/history.clj:57-59`). It isn't saved, and it survives Open and New (`state/persistent.clj:185-213`) |
| The OS clipboard's text (one line of EDN) | the OS | the `:clipboard/write` effect, on the FX thread | n/a |
| The paste a read continues with (`:then`) | the effect's closure, until the FX thread reads | the `:clipboard/read` effect | n/a |
| `[:ui :dialogs :cue-chain-editor :clipboard]`, `[:ui :dialogs :cue-chain-editor :item-effects-ui <path> :clipboard]` | removed | | |
| `[:ui :step-grid :clipboard]` (the step grid's own, `events/handlers/step_grid.clj:290`) | removed: a step grid's Copy is a `:step-grid` clip in `[:ui :clipboard]` | | |

After 4a nothing writes `[:ui :clipboard]` from outside the pipeline, so the direct-writes plan (02) has no clipboard row left.

## 6. Events and effects

| Event / effect | Exists? | Dispatched from (file:line) | Pure? |
|---|---|---|---|
| `:list/copy-selected` | changed. 4a: the slot in `:state` plus `:clipboard/write`. 4b: the type from the component id | Copy button `views/components/list.clj:694`, ⌘C `:764-767` (both through `dispatch-copy-selected!`, `:136-143`); ⌘X `:777` until 4c | yes |
| `:list/paste-items` | changed. 4a: through `with-clipboard`. 4b: strict types, fresh ids | Paste button `:703`, ⌘V `:769-773` (through `dispatch-paste-items!`, `:145-154`); NEW: the `:clipboard/read` callback | yes |
| `:list/cut-selected` | NEW (4c) | ⌘X `:775-779`; the Cut button (NEW) | yes |
| `:list/delete-selected` | unchanged | Delete button `:711`, Delete key `:791-794`; ⌘X `:779` until 4c | yes |
| `:grid/copy-cell`, `:effects/copy-cell` | changed (4a; in 4b an empty cell copies nothing) | the cell menus `grid_cell.clj:331, 495`; `:edit/copy` `events/handlers/menu.clj:298-299` | yes |
| `:grid/paste-cell`, `:effects/paste-cell` | changed (4a; fresh ids in 4b) | the cell menus `grid_cell.clj:334, 350, 498`; `:edit/paste` `menu.clj:318-319`; NEW: the `:clipboard/read` callback | yes |
| `:edit/copy`, `:edit/paste` | routing unchanged (`menu.clj:283-323`, routes `:463-464`); Paste's enablement changes (4a) | the Edit menu, ⌘C and ⌘V accelerators (`title_bar.clj:127-136`) | yes |
| `:clipboard/write` | NEW effect | the copy and cut handlers | effect: serialize on the agent, set on the FX thread |
| `:clipboard/read` | NEW effect | `h/with-clipboard` (a paste with an empty slot) | effect: read and parse on the FX thread, then dispatch |
| `:step-grid/copy` | changed (4a): the slot in `:state` plus `:clipboard/write` | Copy `views/components/step_grid.clj:410-411`, ⌘C `:52` (through `on-key`, `:70-77`) | yes |
| `:step-grid/paste` | changed (4a): through `with-clipboard` | Paste `views/components/step_grid.clj:412-413`, ⌘V `:53`; NEW: the `:clipboard/read` callback | yes |
| `:cue-chain/set-clipboard`, `:cue-chain/set-item-effects-clipboard` | removed | only the dead `:on-copy-fn`s (`cue_chain_editor.clj:391-393, 418-422`) | – |
| `:clipboard/copy-effects`, `:clipboard/paste-effects` (effects), `:effects/insert-pasted` | removed | nothing (`events/core.clj:642-643`; `:228`) | – |

The show lock refuses `:grid/paste-cell`, `:effects/paste-cell`, `:edit/paste` (`events/show_lock.clj:36-38`) and `:step-grid/paste` (`:93`) before the handler runs (`wrap-show-lock`, `:162-173`). So a locked paste asks nothing of the OS clipboard, and a paste that resumes after a read is refused as well if the lock came on in between.

## 7. Data examples and invariants

**Clips** (the slot's value; the OS text is the same map as one line of EDN):

```clojure
{:type :cue-chain-items :items [{:id #uuid "…" :type :preset :preset-id :circle :params {…} :effects []}] :copied-at 1763650000000}
{:type :item-effects :effects [{:id #uuid "…" :effect-id :axis-transform :enabled? true :params {…}}] :copied-at 1763650000000}
{:type :cue-chain :data {:items […] :destination-zone {:zone-group-id :all}} :copied-at 1763650000000}
{:type :effects-cell :data {:items […] :active? true} :copied-at 1763650000000}
{:type :step-grid :grid {:zones [:zone/a :zone/b] :steps [[:zone/a] [:zone/b] [] []]} :copied-at 1763650000000}
```

**What each target takes in 4a: today's rules, unchanged.**

| target ↓ \ clip type → | `:cue-chain-items` | `:item-effects` | `:effect-chain`, `:effect` | `:cue-chain` | `:effects-cell` | `:step-grid` |
|---|---|---|---|---|---|---|
| a list of type `:cue-chain-items` (every list but a projector chain) | its items | – | – | – | – | – |
| a list of type `:item-effects` (a projector chain) | – | its effects | its effects | – | – | – |
| a cue cell (`:cue-chain`) | – | – | – | the cell | – | – |
| an effects cell (`:effects-cell`) | – | – | – | – | the cell | – |
| a step grid (`:step-grid`) | – | – | – | – | – | its `:grid` (`routing.step-grid/paste`) |

**4b (D9 A): each list's type comes from its id.**

| `component-id` (built at) | List type |
|---|---|
| `[:cue-chain col row]` (`cue_chain_editor.clj:525`) | `:cue-chain-items` |
| `[:item-effects col row]` (`cue_chain_editor.clj:554`) | `:item-effects` |
| `[:effect-chain col row]` (`effect_chain_editor.clj:137`) | `:item-effects` |
| `[:projector-effects projector-id]` (`projectors.clj:418`) | `:item-effects` |
| anything else | nil: copy and paste do nothing |

| clip | into a cue list | into an effect list | onto a cue cell | onto an effects cell |
|---|---|---|---|---|
| `:cue-chain-items` | its items | – | – | – |
| `:item-effects` | – | its effects | – | – |
| `:cue-chain`, not a timeline cue | the cell's items (conversion) | – | the cell | – |
| `:cue-chain`, a timeline cue (`cue-chains/timeline-cue?`) | – | – | the cell | – |
| `:effects-cell` | – | the cell's effects (conversion) | – | the cell |

(Effect lists share one type because their items have the same shape: effects move between an item's effects, an effects cell's chain and a projector chain.)

**A paste in 4a** (D8 A, D13 B, D14 A):

| Slot | OS clipboard | Target takes it? | What happens | Paste enabled? |
|---|---|---|---|---|
| a clip | anything | yes | pasted, in one event | yes |
| a clip | anything | no | nothing | no; ⌘V does nothing |
| empty | a clip (another instance, an earlier run, EDN text) | yes | `:clipboard/read`; the slot gets the clip; pasted. Two events | yes |
| empty | a clip | no | read; the slot gets the clip; the notice "Nothing to paste here" | yes before; no after |
| empty | plain text, nothing, or an unknown type | – | read; the slot stays empty; the notice | yes |
| empty, then a copy before the read comes back | anything | – | `adopt` keeps the copy; the paste uses it | yes |

**The agent after 4a** (p10's setup: the FX thread busy for 500 ms):

| A BPM change queued behind | Today (p10; `3229c95`, then `a38ea43`) | After 4a |
|---|---|---|
| nothing | 1–2 ms; 2 ms | the same |
| a list copy | 514–535 ms; 513–532 ms | a few ms: the copy only queues a `runLater` |
| a list paste, slot holding a clip | 503–579 ms; 502–523 ms | a few ms: no read |
| a list paste, slot empty | 503–579 ms; 502–523 ms | a few ms. The paste itself lands once the FX thread is free; the agent doesn't wait for it |

**`chains/fresh-ids` (4b):**

```clojure
(chains/fresh-ids {:editor-type :timeline
                   :tracks [{:id t1 :type :track :name "Track 1"}]
                   :items  [{:id c1 :track-id t1 :preset-id :circle :effects [{:id e1 :effect-id :scale}]}]
                   :destination-zone {:zone-group-id :all}})
;; => {:editor-type :timeline
;;     :tracks [{:id T1 :type :track :name "Track 1"}]
;;     :items  [{:id C1 :track-id T1 :preset-id :circle :effects [{:id E1 :effect-id :scale}]}]
;;     :destination-zone {:zone-group-id :all}}       ; T1, C1, E1 new; :track-id follows its track

(chains/fresh-ids {:id g :type :group :items [{:id a :effects [{:id e}]}]})
;; => {:id G :type :group :items [{:id A :effects [{:id E}]}]}

(chains/fresh-ids [{:id c :track-id t}])   ; a duplicated clip: its track isn't inside
;; => [{:id C :track-id t}]
```

**Invariants**

1. `[:ui :clipboard]` is written only through the `:state` a handler returns. No code under `state/` or `views/` writes it. p9's detector reports nothing for a copy.
2. Only the `:clipboard/read` effect reads the OS clipboard, and only for a paste while the slot is empty. No render function reads it.
3. The event agent never waits for the FX thread for the clipboard: no `fx/on-fx-thread` deref is left in the clipboard code.
4. A copy never touches the document: no undo entry, and `[:project :dirty?]` is unchanged.
5. The Paste buttons, the Paste menu items and ⌘V agree. When Paste is enabled, a paste either pastes or says "Nothing to paste here"; when it's disabled, ⌘V does nothing.
6. With an empty slot, the slot takes a clip from the OS clipboard only if nothing was copied in the meantime (`adopt`).
7. 4b: a clip is pasted only where the second table says; anywhere else the paste is a no-op.
8. 4b: a paste gives every item, group, effect, track and clip a new id; references inside the pasted value follow, and references outside it stay.
9. 4b: an empty cell copies nothing, so a paste can never empty a cell.
10. 4a: the cut items are in the slot before the delete runs, so a failed OS write can't lose them. 4c: a cut is one event and one undo step, "Cut Item(s)".
11. The slot survives File > Open and File > New.

## 8. Tests (named test vars + fixtures)

**Unit tests**, pure: plain state maps or `domains/build-initial-state`, no FX toolkit and no OS clipboard. Run them with the effect maps their handlers return; nothing runs `:clipboard/write` or `:clipboard/read`.

4a:
- `laser-show.state.clipboard-test` (rewritten; no `init-state!`):
  - `list-clip-shapes-test`, `cell-clip-shapes-test` (`:copied-at` from the argument)
  - `system-text-round-trip-test`: `->system-text`, then `<-system-text`, gives the clip back, uuids included.
  - `system-text-ignores-foreign-text-test`: `"hello"`, `""`, `"{:a 1}"` (no `:type`), an unknown type, `"[1 2]"`, broken EDN `"{:type"`, and a text of 5,000,001 characters starting with `{`.
  - `accepts-keeps-todays-rules-test` (the first table, the step grid's column included). `grid-clip-shapes-test`.
  - `adopt-fills-only-an-empty-slot-test`.
- `laser-show.events.list-handlers-test` (extend):
  - `copy-selected-writes-the-slot-and-asks-for-an-os-write-test`: the slot in `:state` equals the `:clipboard/write` value, and `:copied-at` is the event's `:time`.
  - `copy-selected-with-nothing-selected-does-nothing-test`.
  - `paste-with-a-clip-reads-only-the-slot-test`: no `:clipboard/read` in the result.
  - `paste-with-an-empty-slot-asks-for-the-system-clipboard-test`: exactly `{:clipboard/read {:then e}}`, where `e` has no `:state`, `:time` or `:fx/event`.
  - `paste-after-the-read-adopts-and-pastes-test`.
  - `paste-after-a-read-that-found-nothing-says-so-test`.
  - `paste-keeps-a-clip-copied-during-the-read-test`.
  - Rewritten on state, without `with-redefs` of OS functions: `timeline-track-copy-uses-own-clipboard-type-test` (`:426-440`) and `paste-effects-into-an-item-effects-list-test` (`:446-468`).
- `laser-show.events.grid-handlers-test`: `handle-grid-copy-cell-test` (`:187-196`) also checks `:clipboard/write`. NEW: `paste-cell-with-an-empty-slot-asks-for-the-system-clipboard-test`.
- `laser-show.events.menu-handlers-test`: the Edit > Copy tests (`:38-63`) also check `:clipboard/write` and no longer touch the OS. NEW: `edit-paste-with-an-empty-slot-asks-for-the-system-clipboard-test`.
- `laser-show.events.show-lock-test`: NEW `locked-paste-asks-nothing-of-the-system-clipboard-test`. `:grid/paste-cell`, `:effects/paste-cell`, `:edit/paste` and `:step-grid/paste`, with an empty slot and the lock on, give only `#{:state :dispatch-later}` (the lock's notice), through `handle` (`:35-38`). The step grid's cases in the locked-edits test (`:403`) put a `:step-grid` clip in `[:ui :clipboard]`, and `locked-step-grid-opens-and-a-drag-lets-go-test` (`:429-431`) finds Copy's clip there.
- `laser-show.events.step-grid-handlers-test`: `paste-from-another-grid-test` (`:260-275`) finds Copy's clip in `[:ui :clipboard]` and in `:clipboard/write`. NEW `step-grid-paste-with-an-empty-slot-asks-for-the-system-clipboard-test`, `step-grid-paste-ignores-a-cue-clip-test`.
- `laser-show.events.core-test`: `effects-and-co-effects-are-shared-maps-test` (`:46-57`) finds `:clipboard/write` and `:clipboard/read`, and no `:clipboard/copy-effects` or `:clipboard/paste-effects`.
- `laser-show.subs-test` (a real cljfx context, `context` at `:35-36`): NEW `can-paste-test`. With an empty slot it is true for every target; with a clip, only for the targets that take it. The step grid test's clipboard part (`:1353-1356`) goes with `step-grid-clipboard`.

4b:
- `laser-show.state.clipboard-test`: `list-type-test` (the id table), `accepts-strict-types-and-conversions-test` (the second table, the timeline cell included).
- `laser-show.events.list-handlers-test`:
  - `paste-refuses-another-lists-kind-test`: cue items into `[:effect-chain 0 0]` and into `[:item-effects 0 0]` give no `:dispatch`.
  - `paste-a-copied-cell-into-a-cue-list-test`, `paste-a-copied-effects-cell-into-an-effect-list-test`, `paste-a-timeline-cell-into-a-list-does-nothing-test`.
  - `paste-regenerates-ids-at-every-depth-test`.
- `laser-show.animation.chains-test`: NEW `fresh-ids-renames-every-inner-id-test`, `fresh-ids-keeps-inner-references-test` (`:track-id`), `fresh-ids-leaves-outside-references-test`, `deep-copy-item-regenerates-nested-effect-ids-test`. `deep-copy-item-test` and `deep-copy-items-test` (`:67-116`) pass unchanged.
- `laser-show.events.grid-handlers-test`: `paste-cell-regenerates-ids-test`, `copy-an-empty-cell-copies-nothing-test`.
- `laser-show.events.menu-handlers-test`: `edit-copy-on-an-empty-cell-keeps-the-clipboard-test`.
- `laser-show.events.timeline-handlers-test`: the duplicate-clips tests pass unchanged.

4c:
- `laser-show.events.list-handlers-test`:
  - `cut-selected-is-one-state-update-test`: the clip and the cleared selection in one `:state`, `:clipboard/write`, and a `:dispatch` labelled "Cut Item(s)".
  - `cut-selected-is-one-undo-step-test`: through `events/handle-event-pipeline` and its `:dispatch`, as `history_handlers_test.clj:32-39` runs them. One undo entry, "Cut Item(s)"; the write effect isn't run.

**UI tests** (TestFX; `(use-fixtures :once fixtures/with-toolkit)` and `(use-fixtures :each fixtures/with-app)`; the headless platform's clipboard. `clipboard-test` also saves the OS clipboard's text before each test and puts it back after, so a visible run doesn't leave the Mac's pasteboard changed, and each test starts from a text it sets, as the probes do with `set-os-clip!`):
- NEW `laser-show.ui-test.clipboard-test`, because these behaviours span the main window and two dialogs. Each test notes the probe it comes from and what it gave today:
  - 4a:
    - `list-copy-lands-in-the-clipboard-test` (p5, p9; today the slot stays `nil`)
    - `copy-puts-edn-on-the-system-clipboard-test` (the headless clipboard's text reads back as the slot's clip)
    - `copy-and-paste-do-not-hold-up-other-events-test` (p10). With the FX thread busy for 500 ms, a BPM change behind a copy, and behind a paste with a full slot, lands within 250 ms. Today: 503–579 ms.
    - `nothing-reads-the-system-clipboard-while-rendering-test` (p11). `with-redefs` counts calls to `events/system-clipboard-text`. Mount the Projectors tab with a chain, open the effects grid's chain editor and change selections: 0 calls.
    - `copy-lights-the-same-chains-paste-test` (p11; red today)
    - `cue-editor-paste-lights-after-a-copy-test` (p5; red today: `[true true]`)
    - `paste-button-and-shortcut-agree-test` (p12). In the projector chain and in the effects grid's chain editor, with a given slot, Paste is enabled exactly when ⌘V changes the items. Red today.
    - `cut-with-a-failing-os-write-keeps-the-items-test` (p16). `with-redefs` makes `events/put-system-clipboard-text!` throw. ⌘X, then ⌘V, gets the items back. Today: 2 → 2.
    - `fresh-app-pastes-from-the-system-clipboard-test` (D8 A). Copy in the cue list, `app/stop!`, `app/start-test!`: the slot is empty and the OS clipboard holds the clip's EDN; ⌘V in the cue list pastes it, and the slot then holds it.
    - `paste-is-a-maybe-before-the-first-copy-test` (D13 B). With an empty slot and plain text on the OS clipboard, the Cue Chain Editor's two Paste buttons, the projector chain's, Edit > Paste and a cell menu's Paste are enabled; a Paste then changes nothing and the status bar says "Nothing to paste here".
    - `menu-paste-agrees-with-the-clipboard-test` (invariant 5). After a list copy, Edit > Paste and the cell menus' Paste are disabled and ⌘V on the grid changes nothing; after a cell copy, they are enabled and ⌘V pastes the cell.
    - `step-grid-paste-follows-the-one-clipboard-test` (p18). After a cue list copy, the step grid's Paste is disabled; after the grid's Copy, `[:ui :clipboard]` holds a `:step-grid` clip and the OS clipboard its EDN.
  - 4b: `lists-refuse-each-others-items-test` (p7, p13), `cell-copy-pastes-into-a-list-test` (p6).
  - 4c: `cut-then-paste-restores-the-items-test`, `cut-is-one-undo-step-test`.
- `laser-show.ui-test.setup-dialogs-test`: `copy-an-effect-to-another-projector-test` (`:277-292`) waits on `[:ui :clipboard :type]` instead of `clipboard/get-clipboard` (`:284`).
- `laser-show.ui-test.step-grid-test`: `copy-and-paste-between-grids-test` (`:347-372`): Paste is enabled before any copy (D13 B, `:358`), and Copy is waited on in `[:ui :clipboard]` (`:360`).
- `laser-show.ui-test.cue-chain-list-test` (4c): `list-buttons-say-what-they-do-test` (`:107-135`) adds `"#chain-cut-selection" "Cut the selected items (⌘X)"` and still checks Delete isn't cut short. The projector panel's chain column is 316 px wide (`projectors.clj:714`).
- `laser-show.ui-test.grid-test`: `cell-menus-name-the-platform-shortcut-test` (`:204-219`) passes unchanged.

Run, for each step:
1. `clj -M:mac-arm:test clipboard list-handlers grid-handlers menu-handlers show-lock core-test subs chains timeline-handlers step-grid-handlers`
2. `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.clipboard-test`, then `-n laser-show.ui-test.setup-dialogs-test`, `-n laser-show.ui-test.cue-chain-list-test`, `-n laser-show.ui-test.step-grid-test` and `-n laser-show.ui-test.grid-test`
3. both full suites.

Re-run the probes before and after each step (§1 has the before numbers). The unit suite stops writing the Mac's clipboard in 4a (§1 row 16).

## 9. Commit plan

Write the failing tests first in each step.

| # | Step | Commit | Tests |
|---|---|---|---|
| 1 | 4a | `feat(events): :clipboard/write and :clipboard/read put a clip's EDN on the system clipboard and read it back on the FX thread, without the event agent waiting` (pure helpers and the two effects, not used yet) | `clipboard-test` (the new tests), `core-test` |
| 2 | 4a | `fix(clipboard): a copy writes the clipboard in its own handler's state, so a list copy is no longer undone; a paste reads it, and looks at the system clipboard only while it is empty` | the 4a handler tests, `locked-paste-…`; UI `list-copy-lands-…`, `copy-puts-edn-…`, `copy-and-paste-do-not-hold-up-…`, `cut-with-a-failing-os-write-…`, `fresh-app-pastes-…` |
| 3 | 4a | `fix(ui): Paste buttons and menu items follow the clipboard and agree with ⌘V; nothing reads the system clipboard while rendering; the dialog-local clipboards and their callbacks go` | `can-paste-test`; UI `nothing-reads-…`, `copy-lights-…`, `cue-editor-paste-…`, `paste-button-and-shortcut-agree-test`, `paste-is-a-maybe-…`, `menu-paste-agrees-…`; the changed `copy-an-effect-to-another-projector-test` |
| 3b | 4a | `fix(step-grid): the step grid copies and pastes through the one clipboard, so a copied grid reaches the system clipboard and its Paste follows what was copied last` (added at the `a38ea43` re-check, §1 row 19) | the step grid's handler and show-lock tests; UI `step-grid-paste-follows-…`, the changed `copy-and-paste-between-grids-test` |
| 4 | 4a | `chore(clipboard): remove the unused clipboard effects, the insert-pasted stub and the helpers only tests used; the docs say where the clipboard lives` | `core-test` |
| 5 | 4b | `fix(lists): each list copies and pastes its own kind, from its id: a cue's presets never land among effects, nor effects among a cue's presets` | `list-type-test`, `paste-refuses-…`; UI `lists-refuse-…` |
| 6 | 4b | `feat(lists): a copied grid cell pastes into a cue list as its items, a copied effects cell into an effect list` | the conversion tests; UI `cell-copy-pastes-…` |
| 7 | 4b | `fix(paste): every pasted item, effect, track and clip gets a new id, references kept; copying an empty cell copies nothing` | the `fresh-ids` tests, `paste-…-regenerates-ids-…`, `copy-an-empty-cell-…` |
| 8 | 4c | `feat(lists): Cut is one step, "Cut Item(s)", with a Cut button next to Copy` | the cut tests; `list-buttons-say-what-they-do-test` |

## 9a. Step 4a as built (2026-11-21, branch `clipboard-4a`, from `a38ea43`)

| # | Commit | What |
|---|---|---|
| – | `f8a0d1f` | `plans:` the re-check at `a38ea43` (§0, §1 rows 1–19), D8/D13/D14 answered, probe p18 |
| 1 | `02ae75c` | `state.clipboard`'s pure helpers (`list-clip`, `cell-clip`, NEW `grid-clip`, `->system-text`, `<-system-text`, `accepts?`, `payload`, `adopt`) and the effects `:clipboard/write`, `:clipboard/read` (private `put-system-clipboard-text!`, `system-clipboard-text`) |
| 2 | `e0948d5` | list, grid and effects-cell copy write the slot in their `:state` plus `:clipboard/write`; paste through NEW `h/with-clipboard`; `parked` moved to `events/helpers.clj`; Edit > Copy and Paste pass `:time` |
| 3 | `2243f4f` | NEW `subs/can-paste?`; the list toolbar's Paste, Edit > Paste and the cells' Paste items follow it; the render-time reads, `:clipboard-items`, `:on-copy`/`:on-copy-fn`, the Cue Chain Editor's own clipboards and `:cue-chain/set-clipboard`, `:cue-chain/set-item-effects-clipboard` go |
| 3b | `d4e2c00` | the step grid's Copy and Paste through the one clipboard (§1 row 19); `[:ui :step-grid :clipboard]` and `subs/step-grid-clipboard` go |
| 4 | `1113d9a` | `state/clipboard.clj` pure (no `state.core`, JavaFX or `fx/on-fx-thread`); `:clipboard/copy-effects`, `:clipboard/paste-effects`, `:effects/insert-pasted` and the helpers only tests used go; the docs |

**Changes per namespace**, beyond §4 as written:
- `laser-show.state.clipboard`: `payload` and `accepts?` also know `:timeline-tracks` (a list type, today's rule) and `:step-grid`. `<-system-text` also catches a `StackOverflowError` from deeply nested EDN. `max-system-text-length` (5,000,000) is public.
- `laser-show.events.core`: `:clipboard/write` and `:clipboard/read` catch a `Platform/runLater` that throws (toolkit not running) as well; the read then continues with `:clipboard/system nil`.
- `laser-show.events.helpers`: `handle-copy-to-clipboard` keeps today's "an empty cell's clip goes in the slot only": no `:clipboard/write` for a nil cell (4b makes an empty cell copy nothing).
- `laser-show.events.handlers.step-grid` (3b): `handle-copy` and NEW `handle-paste` as §4 says.
- `laser-show.views.components.step-grid`: Paste from `subs/can-paste? :step-grid`.
- `laser-show.events.handlers.effects`: the unused `log` require went with the stub.

**Deviations**
- **3b is new**: the step grid's clipboard, found at the re-check, is folded into the one clipboard (the brief asked for any new copy/paste path to be). A copy in a list now replaces a copied grid and the reverse, and a grid's Paste is a "maybe" before the first copy (D13 B), where it was off.
- **The UI tests set up projectors through events** (`:projectors/add-manual`, `:chain/set-items`, `:projectors/select-projector`, `:list/select-item`), not `state/swap-state!`, so the new namespace writes no state outside the pipeline. A new projector comes with its own calibration chain, so the setup sets both chains with `:chain/set-items`.
- **`nothing-reads-the-system-clipboard-while-rendering-test`** counted the old helper's reads too (`clipboard/read-from-system-clipboard`) until commit 4 removed it, so it was red against commit 2 (4 reads) and is green with `events/system-clipboard-text` alone.
- **Extra tests**: `grid-clip-shapes-test`, `paste-of-a-clip-this-list-does-not-take-does-nothing-test`, `step-grid-paste-ignores-a-cue-clip-test`, and UI `paste-is-a-maybe-before-the-first-copy-test`, `menu-paste-agrees-with-the-clipboard-test`, `step-grid-paste-follows-the-one-clipboard-test`.
- **The old `clipboard-test`** (`cell-assignment-copy-paste-test`, `clipboard-description-test`, `item-effects-paste-as-effects-test`) was rewritten in commit 1, not commit 4: it wrote the Mac's pasteboard, and its last case is in `accepts-keeps-todays-rules-test`.
- **`handle-grid-paste-cell-empty-clipboard`** became `paste-cell-with-an-empty-slot-asks-for-the-system-clipboard-test` (an empty slot is now a read, not a no-op).
- **One red run wrote the Mac's pasteboard**: commit 2's handler tests, run red against commit 1, called the old `copy-*!` (§1 row 16). Since commit 2 the unit suite runs no clipboard effect.

**Tests changed**: `core-test/effects-and-co-effects-are-shared-maps-test`; `grid-handlers-test/handle-grid-copy-cell-test`; `menu-handlers-test`'s three Edit > Copy tests; `list-handlers-test/timeline-track-copy-uses-own-clipboard-type-test`, `paste-effects-into-an-item-effects-list-test` (on state, no `with-redefs`); `show-lock-test/locked-zone-pattern-edits-are-refused-test` (the step grid's paste case), `locked-step-grid-opens-and-a-drag-lets-go-test`; `step-grid-handlers-test/paste-from-another-grid-test`; `subs-test/step-grid-test` (its clipboard part gone); UI `setup-dialogs-test/copy-an-effect-to-another-projector-test`, `step-grid-test/copy-and-paste-between-grids-test`.

**Red first**
- Commit 1: `clipboard-test` didn't compile (`No such var: clipboard/list-clip`); `core-test` 1 failure.
- Commit 2: 37 failures in 15 handler tests (list, grid, menu, show-lock); UI `clipboard-test` 5 of 5 red: the slot stayed nil (`list-copy-…`, `cut-…`, `fresh-app-…` timed out), the OS text was pretty-printed and unlike the slot, and a paste behind a busy FX thread took 514 ms.
- Commit 3: `subs-test` didn't compile (`No such var: subs/can-paste?`); UI 6 new tests red: 4 render-time reads, the effects grid's chain lit Paste and pasted nothing (p12), the projector chain's Paste stayed off after Copy, the Cue Chain Editor's Paste stayed off, Edit > Paste and the cells' Paste ignored the clip.
- Commit 3b: 9 failures in 5 unit tests; UI `step-grid-paste-follows-…` and `copy-and-paste-between-grids-test` red.
- Commit 4: `core-test` 1 failure (the old effects still registered).

**Probes, before (`a38ea43`) and after (`1113d9a`)**, headless, 2026-11-21:

| Probe | Before | After |
|---|---|---|
| p5 | slot `nil`; Paste buttons `[true true]`; ⌘V 3 → 4 | slot holds the clip; `[false false]`; ⌘V 3 → 4 |
| p9 | lost update `[[:ui :clipboard]]` for the list copy and the cell copy | none for either; the cell's slot has `:copied-at` |
| p10, FX busy 500 ms | alone 2 ms; behind a copy 513–532 ms; behind a paste 502–523 ms (3 runs, load 6–13) | alone 10–33 ms; behind a copy 29–64 ms; behind a paste 33–48 ms (3 runs, load 4–6) |
| p10, FX idle | median 12.1–13.3 ms | median 25.8–57.5 ms |
| p11 | 1 OS read while mounting, inside `projector_config_panel`; Paste off after Copy until a re-render | 0 reads; Paste on before Copy (D13 B) and after it |
| p12 | Paste on, click pastes nothing (1 → 1), silently | Paste on (empty slot: a maybe), click pastes nothing (1 → 1), says "Nothing to paste here", the slot adopts the `:item-effects` clip and Paste goes off |
| p16 | ⌘X 3 → 2, slot `nil`; ⌘V 2 → 2 | ⌘X 3 → 2, slot holds the cut item, OS `""`; ⌘V 2 → 3 |
| p18 | grid's own slot; `[:ui :clipboard]` nil; OS keeps the cue items; Paste off before a copy | no own slot; `[:ui :clipboard]` `:step-grid`; OS `{:type :step-grid, :grid`; Paste on before a copy, off after a cue copy |
| p6, p7, p13, p14, p15 | as §1 | unchanged (Steps 4b–4c) |
| p17 | 2.0 / 0.03 / 0.06 ms; 56 / 1.4 / 3.2 ms; 253 / 1.9 / 7.4 ms | 18–21 / 0.13–0.27 / 0.24–0.37 ms; 236–260 / 3.6–3.8 / 9.7–10.8 ms; 1150–1239 / 9–15 / 35–48 ms |

The after-runs came on a slower machine: p17 measures code Step 4a didn't change, and it took about 5× as long, and p10's "alone" baseline went from 2 ms to 10–33 ms. A user process was using about 83 % of a core and the WindowServer 41 %. So the after numbers are slower across the board, but no wait near 500 ms is left: an event behind a copy or a paste lands with the baseline.

**Suites** (2026-11-21, headless JavaFX 28-ea+11, JDK 25.0.5, macOS arm64):
- Unit, full: 1869 tests, 570,483 assertions, 0 failures (20:31, load 4.1). An earlier run (19:48, load 5) had 17 timing failures in `engine-wire-test`, `streaming-engine-test`, `output-lifecycle-test`, `fake-dac-test` and `show-clock-test`. Run on their own, they failed again, and they failed the same way at `a38ea43` in a scratch worktree at the same time, so they came from the machine's load, not from Step 4a.
- UI, full: 285 tests, 1510 assertions, 0 failures (20:03–20:27).
- Targeted on the way: unit `clipboard list-handlers grid-handlers menu-handlers show-lock core-test subs chains timeline-handlers step-grid-handlers cue-chain-handlers effects-handlers migration projector-handlers` (319 tests, 2107 assertions); UI `clipboard-test` (12 tests, 49 assertions), `setup-dialogs-test`, `cue-chain-list-test`, `grid-test`, `menu-bar-test`, `projectors-layout-test`, `step-grid-test`.
- kondo (`--lint src test --parallel --fail-level error`): 0 errors, 17 warnings, none in a file Step 4a touched (the same count as before).

**Live check** (headless REPL, `start-test!` with a temp rig file and add-ons folder; the headless clipboard; screenshots in `test-results/clipboard-4a/`, gitignored):
- Before the first copy, with plain text on the OS clipboard: Edit > Paste and a cell's Paste item were on (`01-…`); ⌘V on an empty cell said "Nothing to paste here" and changed nothing (`13-…`).
- Effects grid: Copy Effects on D1 (cell [0 3]), then the Paste item was on and pasted onto C6 (cell [5 2]) (`02-…`, `03-…`); after Copy Cue on A1 the effects cells' Paste was off.
- Cue Chain Editor: with a copied cue both Paste buttons were off (`04-…`); after Copy both were on (`05-…`); ⌘V in A2's editor (cell [1 0]) pasted A1's circle into another chain (`06-…`).
- Projector chain: Paste off with cue items on the clipboard, on right after Copy in the same chain (`07-…`), and pasted into the other laser's chain (`08-…`).
- Step grid: A1's edited grid copied (OS text `{:type :step-grid, :grid`) and pasted into A2's (`09-…`, `10-…`).
- Copy, quit, start: after `stop!` and `start-test!` the slot was empty and the OS clipboard held the copied cue item; both Paste buttons were on (`11-…`) and ⌘V pasted it into A1 (`12-…`), after which the slot held it.
- Noticed, as planned for 4b: the item-effects list's Paste lights for copied cue items (both lists are `:cue-chain-items` until 4b).

Not done here, as planned: Steps 4b and 4c (D9).

## 10. Out of scope / alternatives considered

- **Pretty-printed EDN on the OS clipboard** (today: `pprint` on the agent, `state/clipboard.clj:61`). p17: up to 313 ms for a 300-clip timeline cell, against 3.2–3.6 ms for `pr-str`. The alternative is to pretty-print on a single writer thread, as the rig writer does (`state/rig_store.clj`), and then `runLater`. Not done: one line of EDN pastes just as well, and a text editor can reformat it. If it's wanted later, only the effect changes; no handler does.
- **A co-effect for the OS read, or a read in the view's callback:** D14 B and C.
- **Looking at the OS clipboard on the first paste after an app window lost focus.** It would extend D8 A, so a newer clip from another instance or edited EDN text wins once you come back. It needs one more predicate in `with-clipboard` and a focus event per stage. `:on-focused-changed` exists on `:stage`, and the main window already sends `:vibe/window-focus` (`views/root.clj:212`). Later, if cross-instance paste matters.
- **Telling our clips apart without reading them** (a custom `DataFormat`, or macOS's pasteboard "detect" calls). JavaFX doesn't expose the macOS calls, and a format alone wouldn't give the clip's type. Unverified; not pursued.
- **Undo for copy:** a copy is not an edit.
- **Several clipboard slots or a history:** not requested.
- **Drag-and-drop payloads** use their own `ClipboardContent` (`views/components/list_dnd.clj:22, 127`; `views/components/drag_drop_cell.clj:27, 79-81`) and are untouched.
- **Cut for grid cells** (the Edit menu): possible on the shared slot, not required.
- **Noticed, not changed:**
  - Edit > Paste's undo label is "Paste", not "Paste Cell" (p14): `:edit/paste` has no entry in `event-descriptions` (`events/handlers/history.clj:148-244`).
  - An incompatible ⌘V says nothing. A notice ("The clipboard holds cue items") could come later.

---

## Decisions

> **DECISION D8 — What is the clipboard's source of truth, and when is the OS clipboard read?** *(answered A, 2026-11-21 · needed before Step 4a · recommended: A ★)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★** | `[:ui :clipboard]` is the clipboard. Copy and cut write it in the handler's state and put its EDN on the OS clipboard (`:clipboard/write`, no wait). Paste reads the slot. Only while the slot is empty (this instance hasn't copied anything since it started) does a paste look at the OS clipboard, once, through `:clipboard/read` (D14), and take a clip that another instance, an earlier run or an EDN text left there | One source of truth. No OS read while you work in the app: no FX waits, and nothing for macOS's pasteboard-privacy alert to catch. The slot survives Open and New, so copying between shows doesn't need the OS clipboard. Copy, quit, start, paste still works | Once this instance has copied something, it doesn't pick up a newer clip from another instance, or EDN text you edited elsewhere (today's list paste prefers the OS clipboard). If that matters later, add the look after a focus loss (§10) |
> | B | The OS clipboard first, on every paste, through `:clipboard/read`; the slot only when the OS holds no clip. Today's list rule, without the wait | A clip from another instance or edited EDN text always wins, as in today's list paste | Every paste is two events and reads the OS clipboard. Paste buttons can't know what the OS holds, so a button and ⌘V can disagree. Two sources of truth with a precedence rule |
> | C | Internal only: write the OS clipboard, for other apps, but never read it | Simplest: no read effect, no import | Drops copy-quit-paste, pasting between instances, and pasting EDN text |
>
> **Your answer:** A (2026-11-21): `[:ui :clipboard]` is the clipboard. Copy and cut write it in the handler's state and put its EDN on the OS clipboard (`:clipboard/write`, no wait). Paste reads the slot, and looks at the OS clipboard (once, through `:clipboard/read`) only while the slot is empty.

> **DECISION D13 — How do the Paste buttons and menu items decide whether they are enabled, now that nothing reads the OS clipboard while rendering?** *(answered B, 2026-11-21 · needed before Step 4a · recommended: B ★ with D8 A; A with D8 C)*
>
> Today the list buttons look at dialog-local slots that nothing writes, or read the OS clipboard while rendering (rows 5–6); Edit > Paste is on for any clip, and the cell menus' Paste is always on (row 12).
>
> | | What | For | Against |
> |---|---|---|---|
> | A | From the slot alone: enabled when the slot holds a clip this target takes (`clipboard/accepts?`), disabled otherwise, an empty slot included | Exact; no read | With D8 A, a fresh instance's Paste buttons are off while ⌘V in a list would import from the OS clipboard. The main window's ⌘V is a menu accelerator, and a disabled item's accelerator doesn't fire, so a cell can't import at all |
> | **B ★** | A, but an empty slot counts as "maybe": until this instance copies something, Paste is enabled, and a paste that finds nothing usable on the OS clipboard says "Nothing to paste here" | Buttons, menu items and ⌘V always agree (invariant 5). Exact from the first copy on. No read just to decide enablement | A fresh instance shows Paste enabled until its first copy |
> | C | From the slot, refreshed from the OS clipboard whenever an app window gains focus | Lights for another instance's clip as soon as you come back | Reads the OS clipboard without a paste. That is what macOS's pasteboard-privacy work targets (an API since macOS 15.4; Apple calls the alert "upcoming"; web sources, November 2026, not checked with this app). It also means a read on every window switch and a focus listener on every stage (Phase 2's shell) |
> | D | Always enabled; a paste that can't do anything says "Nothing to paste here" | Simplest; no sub | The buttons tell you nothing about the clipboard |
>
> **Your answer:** B (2026-11-21): enablement comes from the slot via `clipboard/accepts?`. An empty slot counts as "maybe", so Paste is enabled, and a paste that finds nothing usable says "Nothing to paste here".

> **DECISION D14 — How is the OS clipboard read?** *(answered A, 2026-11-21 · needed before Step 4a if D8 is A or B · recommended: A ★)*
>
> JavaFX's `Clipboard` works only on the FX thread (p10: `getString` elsewhere throws "Not on FX application thread"). Handlers run on the event agent, and an agent that waits for the FX thread holds up every event behind it (p10: about 500 ms behind a busy FX thread; review 02 #15).
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★** | An effect, `:clipboard/read {:then event}`. `Platform/runLater` reads and parses on the FX thread, then `dispatch`es `(assoc then :clipboard/system clip)`, which the agent runs later | The agent never waits, and the handler stays pure: the clip arrives as event data. It has the file chooser effect's shape (`events/core.clj:353-410`) and is what review 02 M4 prescribed | A paste with an empty slot takes two events. Another event can run in between, and the second hop pastes at the selection as it is then |
> | B | A co-effect, `:clipboard/system` | One event | cljfx runs every co-effect for every event (`cljfx/event_handler.clj:8-13`), so every event would wait for the FX thread. Made lazy (a `delay` that only paste handlers force), it is still a wait on the agent, now hidden in the handler. It would also deadlock if the FX thread ever waited for the agent (`:fx/sync`, `cljfx/event_handler.clj:40-49`; unused in `src` today) |
> | C | Read on the FX thread where the paste starts (the key filter, the button's `:on-action`) and put the text in the event | One event, and the read happens inside the user's gesture | The reads spread over views. The Edit menu and the cell menus send map events, which would need a wrapper in the app's wiring. And the view decides when to read |
>
> **Your answer:** A (2026-11-21): `:clipboard/read {:then event}`. `Platform/runLater` reads and parses on the FX thread, then dispatches `(assoc then :clipboard/system clip)`.

> **DECISION D9 — Types, conversions and a Cut button** *(open · needed before Steps 4b and 4c · recommended: A ★)*
>
> Since `f044a31`, the projector chain copies and pastes as effects. The effects grid's chain and an item's effects still copy and paste as cue items (p7, p13).
>
> | | What | For | Against |
> |---|---|---|---|
> | **A ★** | Strict types per list, from the list's component id (§7). Two conversions: a copied grid cell (`:cue-chain`, not a timeline cue) pasted into a cue list adds the cell's items, and a copied effects cell (`:effects-cell`) pasted into an effect list adds its effects. Cut becomes one event, with a Cut button next to Copy | Fixes the visible confusion: today a cell copy does nothing in a list (p6). Cut is already on the keyboard | Two small conversion rules to test. A fourth toolbar button, and the projector panel's chain column is 280 px wide |
> | B | Strict types, no conversions, no Cut button (⌘X becomes one event either way) | Smallest | Cell copy and list paste stay unrelated |
> | C | A, plus the reverse: list items pasted onto a cell replace it | Most flexible | Overwrites a cell; needs a confirmation (Z0's confirm dialog) and an undo story |
>
> **Your answer:** _pending_
