# 20 · Document-bound completions

**Status:** Implemented in `3f82e19` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24).

No plan page was written before implementation; the design brief the implementer worked from and its hand-off report are recorded here. Paths are relative to `src/laser_show/` unless they say otherwise, and citations refer to the code at the time of writing.

## Design brief

Audit: "A previous show's work can change the new one" (section "Document, undo and asynchronous work"). Ownership contract in 00-overview.md ("Three ownership decisions that span pages"): **NEW `[:project :document-id]` / event `:document-id` belongs to 20**; transient, never saved, undone, or compared for dirty state. 24 (input learning, another agent, later) owns `:learn-id` and the full learn lifecycle; 15 (timecode chase) and 21 (rig writer/Exit, next in this worktree) will read your document identity.

Observed (persistence auditor, reproduced with in-memory handler probes at a7e414e):
1. Start Learn on a grid cell, choose File > New, then press a key: the NEW show acquired the old binding, became dirty and got a "Learn Trigger" undo entry. `reset-session` (state/persistent.clj) keeps `[:backend :input :learning-target]`; the capture handler (events/handlers/input.clj `handle-input-learn-captured`) only checks target equality.
2. An OS-clipboard paste parked before New (events/helpers.clj `with-clipboard` → `{:clipboard/read {:then parked-event}}`; read effect in events/core.clj) overwrote the new show's cell when its continuation arrived afterwards.
3. Converse loss: start File > Open on a clean show (the unsaved guard passes because it's clean); while the chooser is open, an external input (e.g. a pending learn capture or MIDI-triggered edit) dirties the show; choosing the file replaces the now-dirty show without asking (events/handlers/menu.clj `handle-file-open` → `:fx/show-file-chooser` → `:file/open-result` → `:project/load` → `:file/project-loaded`).

Required:
- Session identity: `[:project :document-id]` is a fresh UUID for the initial document (app start / start-test! starter state) and is replaced on every *successful* New and Open (not on cancelled/failed Open, Save, Save As, Undo). Handlers may call `random-uuid` directly (existing handlers do for request IDs). Not part of `persistent/project-files`, history snapshots or dirty comparison.
- Parked OS paste: the parked continuation carries the document-id at park time; on return it does nothing (no state change, no notice spam) if the document was replaced in between. Same-document paste keeps working (clipboard tests: test/laser_show/ui_test/clipboard_test.clj and unit tests).
- Open chooser: the chooser request records the document-id and whether discard was authorized for a clean or confirmed-dirty document; when the result arrives, if the document was replaced or has become dirty since authorization, don't replace it: re-enter the unsaved-changes guard (park the open with the chosen path so confirming continues to the same file) instead of discarding. Keep Save-then-Open/Exit flows and existing menu tests working.
- Learn across New/Open: at minimum the document replacement must end any in-progress document-bound learn (clear the learning target/capture in the session reset) so a late capture can't bind into the new show. Don't redesign learn (24 does: listener ownership, learn-id, Settings learn); keep the existing learn tests passing.
- Keep ZIP I/O inline on the event thread (explicitly deferred decision). 02 (strict reads, verified saves, `prepare-open`, `:project/mark-clean` with `:then`) has already landed in this worktree — build on its shapes (see `git log 7b320b3..HEAD`).

Tests (NEW, names adaptable): in laser-show.events.menu-file-handlers-test — `document-id-rotates-on-successful-new-and-open-test` (and not on failure/cancel/save/undo), `chooser-open-rechecks-a-document-changed-while-choosing-test`, `parked-paste-from-a-replaced-document-does-nothing-test`; a learn test (input handlers or history tests) that a capture after New doesn't bind or dirty the new show. Keep laser-show.events.menu-file-handlers-test, menu-system-handlers-test, clipboard tests, input handler tests and laser-show.ui-test.main-window-test passing.

Report the exact document-id contract (where it's created, rotated, and how a completion should check it) for 21, 24 and 15.

## As implemented

**Changes**
- Show identity: session-only UUID at `[:project :document-id]` (documented in `state/domains.clj`). `persistent/begin-document` creates it; `app/starter-state` calls it at start; `persistent/reset-session` calls it on every New/Open that goes ahead.
- Learn: `reset-session` (NEW private `end-learn`) clears `:learning-target` and `:learning-capture`.
- Helpers (`events/helpers.clj`): `document-id`, `for-document` (stamp an event when sent), `same-document?` (check it on return).
- Paste: `with-clipboard` stamps a paste waiting for the OS clipboard; if the show was replaced before the read returns → nothing (no paste, no clipboard change, no notice).
- Open (`handlers/menu.clj`): chooser result carries `:authorized {:document-id :dirty?}` (NEW `discard-authorization`); `:file/project-loaded` checks it with NEW `may-discard?` before replacing anything; if replaced-by-dirty or clean-became-dirty → re-asks unsaved-changes with the Open of that file parked; Don't Save / Save continue to the same file. `ask-first` keeps `:file-path`.
- Dialog text (`views/dialogs/unsaved_confirm.clj`): re-asked Open names the file ("Save changes to this project before opening B.zip?").
- Save As chooser result is stamped too: a result for a replaced show writes nothing; its continuation doesn't run (log line only).

**Deviations / gaps**
- Replaced show with no unsaved changes: guard passes; file opens without a question.
- Don't Save already chosen: only identity checked; further edits to the same show while choosing are discarded with the confirmed ones.
- `:file/open-result` without `:authorized` is applied unchecked (dialog's own re-dispatch, existing UI tests).
- Learn minimal: old listener not unregistered; after New/Open it can still catch one input which changes nothing but commits an identical state (→ 24).
- Show lock turned on between New/Open and a waiting paste's return: lock's refusal notice still appears for grid/effects/step-grid pastes (lock runs before the paste handler).
- If the unsaved-changes dialog is already open when an Open is re-checked, that Open is dropped (logged only).

**Contract**
- Created: `app/starter-state` (so `start!`/`start-test!` with no/nil state get one). A custom state given to `start-test!` keeps what it carries; bare unit-test states have nil.
- Changes only through `reset-session`: `new-project-state` (New going ahead) and `apply-loaded-project` (successful Open). Save, Save As, mark-clean, Undo/Redo, a New that asks first, and a cancelled/failed/refused Open keep it.
- Not part of: `project-files`, history snapshots, saved baseline, dirty comparison.
- Completion check: stamp when sending `(h/for-document event state)`; on return `(h/same-document? state event)` (`=` against `(h/document-id state)`); when false return `{}` (no commit, no notice). An unstamped event only matches a show with no id — hand-built test events against an app's show must be stamped.
- Discard guard: record `(discard-authorization state)` when the guard passes/is confirmed; on return check `(may-discard? state authorized)`; on failure `ask-first` with the parked event (both private in menu.clj).
- 21: for Exit after a rig retry, attach `:authorized` to the parked `{:event/type :file/exit :force? true :rig-force? true}`; check it in `handle-file-exit` before `:system/exit`, re-asking with `{:event/type :file/exit}`. Save-then-Exit is already marked clean → authorization `:dirty? false`.
- 24: stamp the `:on-captured` event of `:input/await-learn` with `for-document`, then check `same-document?` first in `handle-input-learn-captured` and `handle-fader-captured`. Removing the leftover listener (`::generalized-input-learn`, `!pending-learn`) is 24's.
- 15: a chase report can carry the document id of the state its tick read; the timing handler drops it with `same-document?`. `reset-session` already resets `:playback`.
- 19: only shared code is the one-line change in `app/starter-state`.
- Docs: `docs/CLJFX_STATE_MANAGEMENT.md` could document this contract (orchestrator to do).
