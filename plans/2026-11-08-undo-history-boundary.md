# Undo history and unsaved-changes boundary

**Status:** Done (2026-11-08, uncommitted on master)   **Last verified against:** master `b384eae` + working tree
**Supersedes / Superseded by:** none   **Related findings:** 02 #5, #6, #16, #18, Q12, M2; 05 F8 part 5, F12; overview T3

## Decisions (agreed with the user, 2026-11-08)

| Question | Decision |
|---|---|
| BPM (typed, tap tempo, Link) | **Live:** no undo step and no "unsaved" flag. Still saved with the project. |
| Effect cell on/off (click, MIDI, OSC, trigger) | **Live:** no undo step and no "unsaved" flag. Undoing another edit doesn't flip effect cells back. Still saved. |
| Project settings and layout (preview, window, IDN, OSC, MIDI, global trigger mode) | **Preference:** marks the project unsaved, but isn't undoable. |
| How the unsaved flag works | **Compared against the last saved/opened version.** Undoing back to the saved state makes the project clean again. |

## 1. Current behaviour (file:line)

- **One middleware decides what's undoable.** `wrap-history` (`events/handlers/history.clj:134-158`) runs after every handler. It compares `extract-document-state` (`state/history.clj:16-28`) of the old and new state, and pushes a pre-action snapshot whenever they differ. The document is all of `:config`, `[:timing :bpm]`, `[:grid :size]`, `:projectors`, `:virtual-projectors`, `:zone-groups` and `:chains`.
- **Every change to that document is undoable**, including live actions:
  - `:timing/set-bpm` (`timing.clj:24-27`), sent by the toolbar (`toolbar.clj:107`), tap tempo (`events/core.clj:104`) and Link (`input/link.clj:76`).
  - `:effects/toggle-cell` writes `[:chains :effect-chains k :active?]` (`effects.clj:26-41`). It's sent by grid clicks (`grid_cell.clj:366`), the chain editor checkbox (`effect_chain_editor.clj:177`) and triggers (`triggers.clj:49,69`).
  - `:keyframe/select` writes `:selected-keyframe` inside the item (`keyframe.clj:204-213`).
  - `:list/toggle-collapse` writes `:collapsed?` on items (`list.clj:337-345`).
  - `:preview/*` writes `[:config :preview]` (`ui.clj:60-117`).
  - `:projectors/scan-complete` auto-creates projectors (`projector.clj:159-174`).
  - Opening the timeline editor fills in timeline fields (`timeline.clj:583-614`), and `:timeline/init-tracks` does the same.
- **Each of those pushes a snapshot and clears redo.** A drifting Link session or a MIDI controller can push real edits out of the 50-entry stack (`history.clj:12-14`).
- **Undo restores the whole document** (`apply-document-state`, `state/history.clj:30-44`). Undoing a parameter edit therefore also reverts BPM, effect on/off, collapse state, the selected keyframe and preview layout, and it always sets `dirty? true` (`:44`).
- **Transactions are global** (`history.clj:89-120`, `wrap-history :143`):
  - While a slider or canvas drag is open, every other document change is folded into it.
  - `begin-transaction` ignores a second begin while one is open (`:93`), so a missed mouse-up disables undo until the next commit.
  - `commit` ignores `source-id`.
  - Begin is dispatched from `parameter_controls.clj:90`, `modulator_param_control.clj:99` and `canvas_interaction.clj:181`.
- **Undo labels are wrong for list operations.** List handlers (`list.clj:144-163, 213-368`) only change `:list-ui` and then `:dispatch` the change event, so the snapshot is labelled by `:chain/set-items` ("Modify Chain"). Six entries in `format-event-description` (`history.clj:122-128`) name events that don't exist.
- **The modulator toggle creates two undo entries.** It sends `:keyframe/toggle-enabled` and then `:chain/update-param` 10 ms later (`modulator.clj:84-97`). Arrow-key nudges (`:timeline/nudge-selection`) create one entry per key press.
- **Undo doesn't resync the output engines.** `handle-undo` / `handle-redo` (`history.clj:47-79`) restore `:projectors` without emitting `:multi-engine/refresh`, and can leave cues in `[:playback :active-cues]` whose cell no longer exists.
- **The dirty flag is set by hand** in about 60 places (`h/mark-dirty`, `events/helpers.clj:8-11`, plus `menu.clj:84` and `files.clj:16`), and many document edits miss it (02 #6). It is never cleared by undo.
- **Undo history costs time on every event.** `wrap-history` builds two document maps and compares them for every event, including each trigger press (05 F12).

## 2. Root cause

There is one boundary, "the document", and it is too wide. It mixes three kinds of data:

1. **Content:** cues, effects, projectors, zones and trigger bindings. These should be undoable and should mark the project unsaved.
2. **Preferences** saved in the project file (preview, window, connection settings). These should mark the project unsaved but not be undoable.
3. **Live and view state** that happens to sit in saved paths: BPM, effect on/off, collapse state, the selected keyframe. These should be neither, although they are still written when the project is saved.

The dirty flag isn't derived from anything, so it drifts.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `state/history.clj` snapshot/stack functions (`:46-182`) | Kept. Snapshots still store the full raw document (cheap thanks to structural sharing). Only *comparison* and *restore* change. |
| `wrap-history` (`handlers/history.clj:134`) | Extended: policy-based comparison, skip/coalesce flags, an identical-root fast path, stale transactions. |
| `:history/description` on events (`history.clj:149`) | Used for correct list-operation labels. |
| `persistent-state-mapping` (`persistent.clj:31-59`) | The source of which paths are saved; the dirty view is derived from the same set. |
| `persistent/mark-saved`, `apply-loaded-project`, `new-project-state` (`persistent.clj:101-175`) | Each also records the saved baseline. |
| `:multi-engine/refresh` effect (`events/core.clj:348`) | Emitted after undo/redo when hardware config changed. |

## 4. Changes per namespace

- **`laser-show.state.history`** (existing):
  - NEW `prefs-config-keys` = `#{:preview :window :idn :osc :midi :cue}`.
  - NEW `live-item-paths` = `[[:collapsed?] [:keyframe-modulator :selected-keyframe]]`.
  - NEW `undoable-changed? [old-doc new-doc]` and `persisted-changed? [old-doc new-doc]`. Each compares root by root, returns early on `identical?`, compares chains entry by entry, and strips live fields only from entries that changed.
  - NEW `restore-document [state snapshot-doc]` replaces `apply-document-state`:
    - restores content;
    - keeps the current BPM, the current config preferences, each effect cell's current `:active?`, and the current live item fields (matched by item `:id`);
    - no longer touches `:dirty?`.
  - NEW `mark-clean [state]` stores `[:project :saved-document]` (the raw document) and sets `dirty? false`.
  - NEW `derive-dirty [old-state new-state]` returns the new dirty flag.
  - `push-undo-snapshot` gets a NEW optional `coalesce-key` / `now` arity.
  - `begin-transaction` and `commit-transaction` become source-aware.
- **`laser-show.events.handlers.history`** (existing):
  - `wrap-history` honours `:history/skip?` (on the event or in the effects), `:history/coalesce-key` and `:source-trigger`. It auto-commits stale transactions and strips its meta keys before effects run.
  - NEW `wrap-dirty [handler]` (outermost in the pipeline) derives `[:project :dirty?]` for every event and handles `:document/rebase?`.
  - `handle-undo` / `handle-redo` use `restore-document`, prune dead active cues, and emit `:multi-engine/refresh` when hardware config changed.
  - `format-event-description` labels are fixed.
- **`laser-show.events.core`** (`:368-370`): pipeline = `(-> handlers/handle-event wrap-history wrap-dirty)`.
- **`laser-show.state.persistent`**: `mark-saved`, `apply-loaded-project` and `new-project-state` call `history/mark-clean`.
- **`laser-show.app`** (`:134`, `:206-210`): the initial state passes through `history/mark-clean`.
- **Handlers:** remove every `h/mark-dirty` call, then delete `h/mark-dirty`.
  - `menu.clj` `handle-file-save-failed` restores the previous `:saved-document` instead of forcing dirty.
  - `files.clj` `:project/mark-clean` calls `history/mark-clean`.
- **Rebase and skip:** `projector.clj` scan-complete, `timeline.clj` open-editor and init-tracks return `:history/skip? true :document/rebase? true`.
- **Coalescing:** the `modulator.clj` toggle pair and `:timeline/nudge-selection` carry `:history/coalesce-key`.
- **`list.clj`:** each dispatched change event carries `:history/description`.

## 5. State placement

| Key / path | Lives in | Written by | Undo? | Dirty? |
|---|---|---|---|---|
| `[:chains *]` content | document | handlers | yes | yes |
| `[:chains :effect-chains k :active?]` | document (live field) | `:effects/toggle-cell` | **no** | **no** |
| item `:collapsed?`, `[:keyframe-modulator :selected-keyframe]` | document (view field) | list/keyframe handlers | **no** | **no** |
| `[:timing :bpm]` | document (live) | `:timing/set-bpm` | **no** | **no** |
| `[:config :preview/:window/:idn/:osc/:midi/:cue]` | document (prefs) | ui/connection/timing handlers | **no** | yes |
| `[:config :grid/:input]`, `[:grid :size]`, `:projectors`, `:virtual-projectors`, `:zone-groups` | document | handlers | yes | yes |
| `[:project :saved-document]` NEW | project metadata (not saved, not undoable) | `history/mark-clean` (save/load/new/init) | — | — |
| `[:project :dirty?]` | project metadata | **only** `wrap-dirty` and `mark-clean` | — | — |
| `[:history :undo-stack]` entries | history | `wrap-history` / transactions | — | — |

## 6. Events and effects

| Key | Exists? | Dispatched from | Notes |
|---|---|---|---|
| `:history/skip?` (event or effect meta key) | NEW | `triggers.clj` sets it via `:source-trigger`; scan, timeline init | Consumed by `wrap-history`; never reaches `wrap-effects`. |
| `:document/rebase?` (effect meta key) | NEW | scan-complete, timeline open-editor and init-tracks | Consumed by `wrap-dirty`. If the project was clean, the change becomes part of the saved baseline. |
| `:history/coalesce-key` (event key) | NEW | `modulator.clj:86-97`, `timeline_editor.clj` nudge dispatch | Merges with the top undo entry if the key matches, redo is empty, and the previous push was < 1000 ms ago. |
| `:history/description` | exists (`history.clj:149`) | `list.clj` handlers (NEW uses) | Correct undo labels. |
| `:multi-engine/refresh` | exists (`core.clj:348`) | `handle-undo` / `handle-redo` (NEW) | Only when `:projectors`, `:virtual-projectors` or `[:chains :projector-effects]` changed. |

## 7. Data examples and invariants

**Comparison (`undoable-changed?`)**

| old → new | undoable-changed? | persisted-changed? |
|---|---|---|
| `bpm 120 → 128` | false | false |
| `effect-chains [0 0] :active? false → true` | false | false |
| item `:collapsed? false → true` | false | false |
| `[:config :preview :grid-layout] [2 2] → [3 3]` | false | true |
| `cue-chains [0 0] :items [a] → [a b]` | true | true |

**Restore (`restore-document`)**, with current state `{bpm 140, effect [0 0] active? true, preview [3 3], cue [0 0] items [a b]}` and snapshot `{bpm 120, effect [0 0] active? false, preview [2 2], cue [0 0] items [a]}`:
→ `{bpm 140, effect [0 0] active? true, preview [3 3], cue [0 0] items [a]}`.

**Dirty**

| Sequence | dirty? |
|---|---|
| open → edit → | true |
| open → edit → undo | false |
| open → change preview | true |
| open → tap tempo / Link BPM / toggle effect | false |
| open → network scan auto-adds projectors | false (rebase) |
| open → edit → scan | true (the baseline isn't rebased while dirty) |
| save → save fails | the previous baseline is restored; true if it differs |

**Invariants**
1. Live and trigger-originated events never change `:undo-stack` or `:redo-stack`.
2. Undo never changes BPM, effect `:active?`, prefs or live item fields.
3. `undo ∘ redo` is the identity on undoable content.
4. `dirty? = (persisted view ≠ saved baseline)` whenever a baseline exists. Without one (bare test states), it falls back to "changed during this event, or already dirty".
5. Nothing except `wrap-dirty` / `mark-clean` writes `[:project :dirty?]`.
6. Meta keys (`:history/skip?`, `:document/rebase?`) never reach `fx/wrap-effects`; unknown effect keys throw there.
7. Events that don't touch any document root pay only for `identical?` checks.

## 8. Tests (named test vars + fixtures)

**`laser-show.state.history-test` (extend):**
- `undoable-changed?-policy-test`
- `persisted-changed?-policy-test`
- `restore-document-keeps-live-and-prefs-test`
- `restore-document-grafts-live-item-fields-by-id-test`
- `coalesce-push-test`
- `transaction-source-scoping-test`
- `stale-transaction-auto-commit-test`

**`laser-show.events.history-handlers-test` (extend; uses `events/handle-event-pipeline`):**
- `bpm-changes-are-not-undoable-test`
- `effect-toggle-is-not-undoable-test`, which also checks that undoing a param edit keeps `:active?`
- `preview-change-is-dirty-but-not-undoable-test`
- `trigger-originated-events-skip-history-test`
- `undo-to-saved-state-is-clean-test`
- `scan-complete-rebases-clean-project-test`
- `list-operation-labels-test` (delete, reorder, group, ungroup, rename)
- `modulator-toggle-is-one-undo-step-test`
- `undo-emits-engine-refresh-when-projectors-change-test`
- `undo-prunes-active-cues-for-removed-cells-test`
- `meta-keys-do-not-reach-effects-test`
- `history-labels-name-real-events-test`

**Existing handler tests:** the dirty assertions in `cue_chain_handlers_test`, `grid_handlers_test`, `keyframe_handlers_test`, `projector_handlers_test`, `zone_groups_handlers_test` and `menu_handlers_test` move from "the handler set `:dirty?`" to "the pipeline derives `:dirty?`". `history_test` line 54, which expected undo to always mark dirty, is replaced by the dirty-derivation tests.

## 9. Out of scope / alternatives considered

- **Out of scope:**
  - Moving live and view fields out of the document entirely, e.g. effect on/off into `:playback` or collapse state into `:list-ui`. That's cleaner long term, but it changes the save format and many views. With this plan in place, it can happen later without changing behaviour.
  - Per-source transactions for concurrent *content* edits during a drag. The main culprits (BPM, effect toggles) are excluded by this plan; truly concurrent content edits during a drag stay folded into the drag, as before.
  - The unsaved-changes guard on close/open/new. It's now unblocked; see 01 L3.
- **Alternatives considered:**
  - **An event-name allowlist/denylist for history.** Rejected: the same event (`:effects/toggle-cell`) comes from clicks, MIDI and triggers, and new events would silently default to undoable. A path-based policy covers every source.
  - **A `:dirty?` flag set automatically on any change.** Rejected in favour of comparing with the saved baseline (user decision), so undoing back to the saved state is clean.

## 10. Implementation notes (2026-11-08)

- **Implemented as planned.** Unit 589/0, integration 17/0, UI 19/0, kondo 0 errors.
- **Tests:**
  - 11 pure policy tests in `laser-show.state.history-test`.
  - 17 pipeline tests in `laser-show.events.history-handlers-test`.
  - The old BPM-based transaction tests now use a content edit, since BPM is no longer undoable.
- **Where the code differs from the plan:**
  - `:history/coalesce-key` can come from the handler's effects as well as the event. `:timeline/nudge-selection` sets it in the handler, so no view change was needed.
  - Without a saved baseline (bare test states), `derive-dirty` respects a flag the handler set on the new state. This keeps `:file/save-failed` correct when the previous project had no baseline.
  - The 25 handler-test assertions that read `:dirty?` straight off a handler result now assert `history/derive-dirty` (old state → new state).
- **Known trade-off:** a change made by a skipped event (scan, timeline setup) isn't in any undo snapshot. Undoing an *earlier* edit restores the snapshot from before it, so that change is removed again (e.g. timeline fields are re-initialised the next time the editor opens; auto-created projectors are re-created by the next scan).
- **Follow-ups:**
  - The unsaved-changes guard on close/open/new (01 L3) can now trust `[:project :dirty?]`.
  - Consider moving live/view fields out of the document entirely (section 9).

## 11. Review fixes (2026-11-09)

An independent review (with probes run through the real pipeline) found 7 confirmed bugs and some slow paths. All are fixed:

| # | Problem | Fix |
|---|---|---|
| 1 | A drag ending at the saved value stayed "unsaved" after mouse-up | A closing transaction always triggers a full unsaved-check |
| 2 | Learned MIDI/OSC bindings were written outside the event pipeline, so they were lost on undo and never marked unsaved | NEW `:input/await-learn` effect plus a pure `:input/learn-captured` handler (undo label "Learn Trigger"); captures after cancel are ignored |
| 3 | A drag held for more than 60 s flooded the undo stack | Staleness is measured from the last drag activity (`touch-transaction`); any mouse-up commits the open transaction |
| 4 | Skipped/rebased changes (scan, timeline setup) were lost on undo/redo; converting a cue to a timeline cue wasn't undoable | `:history/skip?` + `:document/rebase?` replaced by `:history/backfill (fn [doc] doc)`, applied to every snapshot and to the saved baseline. Converting to a timeline cue is now an undoable "Convert to Timeline Cue" |
| 5 | Undoing a cell move could switch an effect off | A restored effect cell takes its on/off state from the current cell, or from the cell its items moved to; otherwise it comes back off |
| 6 | Toggling an empty effect cell created an undo step | It's a no-op now; an entry holding only `:active?` counts as no entry |
| 7 | `:selected-keyframe` could end up out of range after undo | The current selection is kept only if the keyframes are unchanged, then clamped |
| 8 | Duplicate item ids grafted live fields wrongly | Duplicated ids keep the snapshot's values |
| 9 | A mismatched commit left the transaction stuck open | Any mouse-up commits |
| perf | `postwalk` rebuilt whole cells for every comparison | A non-allocating comparison that ignores live fields and stops at identical subtrees; the full check against the saved version is limited to events where the answer can change |

**Performance:** measured with the reviewer's probe (64 cue cells × 8 items × 4 effects, 32 effect cells). Overhead per event:

| Event | Before | After |
|---|---|---|
| Effect toggle from a trigger | 110–140 µs | ~30 µs |
| Param edit, no transaction | 1.6–2.6 ms | ~110 µs |
| Param edit inside a transaction | — | 7–60 µs |
| BPM change | — | ~6 µs |

**Tests:** 15 new regression tests (unit total 604/0).

**Pre-existing, not from this change:** the UI test `cue-chain-list-test/grouped-list-right-click-keeps-multi-selection` times out on committed HEAD `d8a4693` too.

**Still open (reviewer, suspected, not fixed):**
- Clicking a slider track may produce a discrete step before the drag begins (JavaFX sets the value before `on-mouse-pressed`).
- The canvas commit is dispatched before the release result (`canvas_interaction.clj:211-214`).
