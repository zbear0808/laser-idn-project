# Phase 4 — One clipboard

**Written:** 2026-11-13   **Written for commit:** `cb32414` (`master`)
**Status:** Proposed. Needs D8–D9 answered   **Last verified against:** `cb32414` (probes p5–p7, `probes/keyboard_probes_test.clj`)
**Supersedes / Superseded by:** replaces the clipboard parts of `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md` (§3.6, §3.7, §5.A.2)   **Related findings:** K-6…K-11 (`00-overview.md` §3); review 01 F7, Q6; 02 #15, M4 (clipboard part), "clipboard effect stubs" carry-over

Copy and paste are implemented twice, with different type names and different read rules, and the list half is broken in ways you can see: the toolbar Paste button never enables, cue items can be pasted into an effects list, and cut is two separate actions. About 2 days.

## 1. Current behaviour (file:line)

| Path | What happens | Evidence |
|---|---|---|
| **List copy** (Ctrl+C, Copy button) | `handlers/list.clj:234-248` deep-copies the selection and calls `clipboard/copy-cue-chain-items!` (or `-item-effects!`, `-timeline-tracks!`, chosen by `:clipboard-type`). That writes `[:ui :clipboard]` with `assoc-in-state!` (`state/clipboard.clj:31-34`) **and** the OS clipboard, **blocking the agent thread** until the FX thread has run it (`:57-73`, deref at `:64`). The handler then returns `{:state state}` (`:247-248`), the snapshot taken *before* the write, so the event's `reset-state!` (`events/core.clj:71-75`) **undoes the internal write**. | p5: `[:ui :clipboard]` is `nil` after a list copy; the OS clipboard starts with `{:type :cue-chain-items,` |
| **List paste** | `handle-paste-items` (`handlers/list.clj:251-278`) reads `clipboard/get-clipboard` (`state/clipboard.clj:104-109`: OS clipboard first, then the internal slot), compares only `:type` (`:255-259`), deep-copies and inserts. | p5: Shortcut+V pastes (3 → 4 items) |
| **Toolbar Paste button** | `list.clj:609` `can-paste? (boolean (seq clipboard-items))`. `clipboard-items` comes from dialog-local slots that only dead callbacks write (`cue_chain.clj:158-175`, `cue_chain_editor.clj:453-457`), or from an OS-clipboard read **during render** (`effect_chain_editor.clj:95`, `tabs/projectors.clj:613`) that only recognizes types nothing writes. | p5: Paste buttons stay disabled (`[true true]`) right after a copy |
| **Cut** (Ctrl+X) | `list.clj:761-765`: `dispatch-copy-selected!` then `dispatch-delete-selected!`, two events. A failed copy is swallowed (`state/clipboard.clj:71-73`) and the delete still runs. | by reading |
| **Which list pastes what** | `list-editor` puts `:clipboard-type` in its props (`list.clj:861`) but `list-sidebar` never receives it (`:892-901`) and no caller passes one, so **every** list copies and pastes `:cue-chain-items`. | p7: pasting cue items into effect chain `[0 0]` leaves a `{:preset-id :circle :type :preset}` item in the effect list |
| **Cell copy / paste** (grid and effects tabs, Edit menu) | `grid.clj:171-185`, `effects.clj:78-92` → `events/helpers.clj:49-96`. The copy puts `{:type :cue-chain …}` or `{:type :effects-cell …}` in the returned state (`:72`) and on the OS clipboard (`:69-70`). Paste reads the **internal slot only** (`:91`) and regenerates no ids (`:90-95`). | p6: a cell copy pastes nothing into a list (1 → 1) |
| **Type vocabulary** | `state/clipboard.clj:40-42` lists 11 types. Written by live code: `:cue-chain-items`, `:cue-chain`, `:effects-cell`. Reachable only if a caller passes it (none does): `:item-effects`, `:timeline-tracks`. Never written: `:effect-chain`, `:cue`, `:zone`, `:projector`, `:effect`, `:cell-assignment`. | survey |
| **Dead code** | `:on-copy-fn` is destructured and passed along but never called (`list.clj:592, 862, 901`). Effects `:clipboard/copy-effects`, `:clipboard/paste-effects`, `:clipboard/paste-projector-effects` are registered (`events/core.clj:450-452`) but no handler emits them; their targets are TODO stubs (`effects.clj:172-176`, `projector.clj:345-348`). The `dispatch!` fallback emulates them (`core.clj:505-513`). | grep |
| **Ids** | List copy/paste deep-copies items with fresh ids (`animation/chains.clj:217-239`), but a preset item's nested `:effects` keep their original ids. Cell paste regenerates nothing. | by reading |
| **Tests** | `state/clipboard_test.clj` calls `clear-clipboard!` (`:14, 24, 30`) and so touches the real OS clipboard; so do several grid/menu copy tests. | survey |

## 2. Root cause

Two clipboard systems share one state slot and the OS clipboard but not a vocabulary or a rule set. The list half writes the slot from outside the event pipeline, which the pipeline then reverts, and never tells the list which type it is. So the slot is empty, the Paste button has nothing to look at, and any list accepts any list's items.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `[:ui :clipboard]` (`domains.clj:131-132`), `subs/clipboard` (`subs.clj:96-97`), `ex/clipboard` | The single source of truth. |
| `chains/deep-copy-items` (`chains.clj:217-239`) | Extended to regenerate nested ids. |
| `helpers/normalize-selected-ids`, `collect-by-ids`, `remove-by-ids`, `insert-after-id` (list handlers) | Unchanged. |
| `ser/serialize-for-clipboard`, `deserialize-from-clipboard` (`state/serialization.clj:204-238`) | The EDN text format for the OS clipboard. |
| `:history/description` on handler results (`handlers/history.clj`) | "Cut Item(s)" as one undo label. |
| The file-chooser effect's `Platform/runLater` pattern (`events/core.clj:272-329`) | Shape of `:clipboard/write` and `:clipboard/read`. |

## 4. Changes per namespace

- `laser-show.state.clipboard` (`clipboard.clj`) becomes pure helpers: `clipboard-types` (the five live types), `compatible? [clip target-type]`, `->system-text [clip]`, `<-system-text [text]` (nil unless the text is a valid envelope). Removed: `set-internal-clipboard!`, `get-internal-clipboard`, `get-clipboard`, every `copy-*!`, and the helpers that only tests used.
- `laser-show.events.core`:
  - NEW effect `:clipboard/write` (the clip map): serializes and writes on the FX thread with `runLater`, never derefs, never blocks the agent.
  - NEW effect `:clipboard/read` (`{:on-read event}`): reads and parses on the FX thread, then dispatches `(assoc on-read :system-clipboard parsed-or-nil)`.
  - Remove `:clipboard/copy-effects`, `:clipboard/paste-effects`, `:clipboard/paste-projector-effects` (`:129-153`, `:450-452`) and their fallback handling in `dispatch!` (`:505-513`).
- `laser-show.events.handlers.list`:
  - `handle-copy-selected` (`:234`) returns `{:state (assoc-in state [:ui :clipboard] clip) :clipboard/write clip}`.
  - NEW `handle-cut-selected` (`:list/cut-selected`): the clipboard write and the cleared selection are one `:state`; it then `:dispatch`es the list's change event with `:history/description "Cut Item(s)"`, so the document edit is one undo step and the copy cannot be lost.
  - `handle-paste-items` (`:251`) reads `[:ui :clipboard]`, falls back to `:clipboard/read` when the slot is empty (D8), and ignores a clip whose type is not compatible with the list.
  - NEW pure `list-clipboard-type [component-id]` (the table in §7). Views no longer pass a type.
- `laser-show.events.helpers` (`helpers.clj:49-96`): cell copy/paste use the same slot and `:clipboard/write`; paste regenerates ids.
- `laser-show.animation.chains` (`chains.clj:217-239`): `deep-copy-item` regenerates ids of nested `:effects` too.
- `laser-show.views.components.list`: remove the `:on-copy` / `:on-copy-fn` / `:clipboard-items` props and the `:clipboard-type` defaulting (`:142`, `:153`, `:861`); Paste's `:disable` comes from NEW `subs/clipboard-accepts?`; add a Cut button if D9 says so.
- `laser-show.subs`: NEW `clipboard-accepts? [context type]`.
- `laser-show.views.dialogs.effect-chain-editor` (`:95`, `:144-146`) and `laser-show.views.tabs.projectors` (`:520`, `:613`): drop the render-time OS read and the dead `:on-copy-fn`.
- `laser-show.events.handlers.cue-chain` (`:158-175`) and `cue_chain_editor.clj:453-457`: remove `:cue-chain/set-clipboard`, `:cue-chain/set-item-effects-clipboard` and the dialog-local clipboards; `domains.clj:145` loses `:clipboard nil`.
- `effects.clj:172-176` + route `:207`, `projector.clj:345-348` + route `:430`: delete the two stubs.
- Check before deleting: `copy-timeline-tracks!` and the `:timeline-tracks` type look dead since the timeline stopped using `list-editor` (only `list_handlers_test.clj:425-439` uses them).

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:ui :clipboard]` `{:type … :items/:data … :copied-at ms}` | app state, UI | copy, cut and cell-copy **handlers only** (returned in `:state`) | No / no: not a document root, not saved. `reset-session` leaves it, so it survives Open/New (`persistent.clj:135-150`) |
| OS clipboard text | the OS | `:clipboard/write` effect (FX thread) | n/a |
| `[:ui :dialogs :cue-chain-editor :clipboard]`, `[… :item-effects-ui … :clipboard]` | removed | | |

Nothing writes the context from outside the event pipeline any more (the writers survey counted the clipboard helper among the out-of-pipeline writers).

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:list/copy-selected` | changed (writes `[:ui :clipboard]`, requests the OS write) | Ctrl+C, Copy button | yes |
| `:list/cut-selected` | NEW | Ctrl+X, Cut button (D9) | yes |
| `:list/paste-items` (+ optional `:system-clipboard`) | changed | Ctrl+V, Paste button, `:clipboard/read` callback | yes |
| `:grid/copy-cell`, `:grid/paste-cell`, `:effects/copy-cell`, `:effects/paste-cell` | changed (same slot, ids regenerated) | Edit menu, cell context menus | yes |
| `:clipboard/write` | NEW effect | the copy/cut handlers | effect |
| `:clipboard/read` | NEW effect | `:list/paste-items` when the slot is empty | effect |
| `:clipboard/copy-effects`, `:clipboard/paste-effects`, `:clipboard/paste-projector-effects` | removed | – | – |

## 7. Data examples and invariants

Clipboard envelope: `{:type :cue-chain-items :items [{:id #uuid "…" :type :preset :preset-id :circle …}] :copied-at 1731500000000}`.

Which list uses which type (derived from the component id):

| `component-id` | Clipboard type |
|---|---|
| `[:cue-chain col row]` | `:cue-chain-items` |
| `[:item-effects col row]` | `:item-effects` |
| `[:effect-chain col row]` | `:item-effects` |
| `[:projector-effects projector-id]` | `:item-effects` |

(Effect lists share a type because their items have the same shape, so effects can be copied between an item's effects, an effect cell and a projector chain.)

| Clipboard holds | Target | Result |
|---|---|---|
| `:cue-chain-items` | cue chain list | pasted after the last selected item, fresh ids for items, nested effects and group children |
| `:cue-chain-items` | an effect list | **rejected**, no state change (p7 today pastes it) |
| `:item-effects` | any effect list | pasted |
| `:cue-chain` (a copied grid cell) | cue chain list | rejected, or the cell's items appended (D9) |
| slot empty, OS text is a valid envelope | compatible list | `:clipboard/read`, then pasted |
| slot empty, OS text is anything else | any | nothing happens |

Invariants:
1. Copy never touches the document: no undo entry, `[:project :dirty?]` unchanged.
2. Cut is one undo step labelled "Cut Item(s)". Because the clip is in the same `:state` as the selection change, a failed OS write cannot lose the cut items.
3. Paste always regenerates ids at every depth.
4. A clip is pasted only into a compatible target; otherwise the paste is a no-op.
5. `[:ui :clipboard]` is written only by handlers.
6. No code path reads the OS clipboard during render.

## 8. Tests (named test vars + fixtures)

Pure, in `laser-show.events.list-handlers-test` (extend; plain state maps, no OS clipboard):
- `copy-selected-writes-the-internal-clipboard-test`, `copy-selected-requests-an-os-write-test`
- `cut-selected-is-one-state-update-test`, `cut-selected-has-one-undo-label-test` (through `events/handle-event-pipeline`, like `history_handlers_test.clj:30-39`)
- `paste-rejects-an-incompatible-type-test`, `paste-regenerates-ids-at-every-depth-test`
- `paste-with-empty-slot-asks-for-the-system-clipboard-test`, `paste-with-system-clipboard-payload-test`
- `list-clipboard-type-test` (the table above)

Elsewhere:
- `laser-show.animation.chains-test` (extend): `deep-copy-item-regenerates-nested-effect-ids-test`.
- `laser-show.events.grid-handlers-test` (extend): `paste-cell-regenerates-ids-test`, `cell-copy-writes-the-shared-clipboard-test`.
- `laser-show.state.clipboard-test` (rewrite): `round-trip-text-test`, `parse-rejects-foreign-text-test`. Pure; the unit suite no longer touches the OS clipboard.
- `laser-show.subs-test` (extend): `clipboard-accepts-test`.

UI, `laser-show.ui-test.cue-chain-list-test` (extend; uses the real OS clipboard through the headless platform):
- `copy-enables-the-paste-button-test` (p5; fails today)
- `cross-list-paste-is-rejected-test` (p7)
- `cut-then-paste-restores-the-items-test`

Run: `clj -M:mac-arm:test list-handlers clipboard chains grid-handlers subs`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.cue-chain-list-test`, then both full suites.

## 9. Out of scope / alternatives considered

- **Undo for copy.** Copy is not an edit.
- **Several clipboard slots or a history.** Not requested.
- **Drag-and-drop payloads** use their own `ClipboardContent` (`list_dnd.clj:22,126-127`, `drag_drop_cell.clj:24,78-79`); untouched.
- **Cut for grid cells** (Edit menu): possible once the shared slot exists, not required.
- **An unrelated line to watch:** a cell copy of an empty cell stores `{:type … :data nil}` (`helpers.clj:66-72`), and pasting it later does `assoc-in` with nil (`:93-95`). Fix it while touching `helpers.clj`.

---

## Decisions

> **DECISION D8 — What is the clipboard's source of truth, and when is the OS clipboard read?** *(open · needed before Phase 4 · recommended: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | `[:ui :clipboard]` is the truth. Copy and cut write it and push the text to the OS clipboard (effect, non-blocking). Paste reads the slot first and only asks the OS clipboard when the slot is empty (a fresh instance, or something copied from another app) | Paste never blocks; the Paste button can look at state; copying between two running instances still works | If you copy in instance B after instance A, A still pastes its own slot (today the OS clipboard wins) |
> | B | Keep "OS first", but as the two-step effect: every paste asks the OS clipboard, then pastes | Same rule as today | Every paste is two events; the button state can't know what the OS holds |
> | C | Internal only; write the OS clipboard for other apps but never read it | Simplest | Drops cross-instance paste |
>
> **Your answer:** _pending_

> **DECISION D9 — Types, conversions and a Cut button** *(open · needed before Phase 4 · recommended: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Strict types per list (table in §7). Add one conversion: pasting a copied grid cell (`:cue-chain`) into a cue chain list appends the cell's items; the same for `:effects-cell` into an effect list. Add a Cut button next to Copy | Fixes the visible confusion (a cell copy does nothing in a list today); Cut already exists on the keyboard | Two small conversion fns to write and test |
> | B | Strict types, no conversions, no Cut button | Smallest | Cell copy and list paste stay unrelated |
> | C | Strict types plus conversions in both directions (paste a list copy onto a cell) | Most flexible | Overwrites a cell; needs a confirmation story |
>
> **Your answer:** _pending_
