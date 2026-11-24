# 22 · Vibe ownership on Undo

**Status:** Implemented in `f04c7ef` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24).

No plan page was written before implementation; the design brief the implementer worked from and its hand-off report are recorded here. Paths are relative to `src/laser_show/` unless they say otherwise, and citations refer to the code at the time of writing.

## Design brief

Audit: "Some restored identities have only half their state" (section "Document, undo and asynchronous work"). Persistence auditor finding 4 (reproduced with in-memory handler probes at a7e414e): "Select a vibe that turns on effects cell A, drag A to B, then Undo. The forward move relocates the vibe's ownership to B (events/handlers/effects.clj move-cell → vibes/refs.clj `move-fx`); Undo restores the active effect at A (state/history.clj `restore-document` / `graft-live-chains` / `restored-effect-active`) but leaves ownership at B. Switching vibes then left A on, and Save wrote A on although session-owned switches are supposed to save off (vibes/engine.clj ~167–176; state/persistent.clj `project-files` turns off vibe-owned switches)."

Required:
- When a document restore (Undo/Redo) moves effect cells back/forth, reconcile the live vibe ownership lists in `[:vibe-runtime …]` (and the held FLASH ownership, if separate) against the same cell identities that restore uses to decide which `:active?` flags to graft. Ownership must follow the cell content: after Undo of A→B, the vibe owns A again; after Redo, B.
- Genuinely manual switches stay manual (not vibe-owned); a cell whose content no longer exists drops its ownership without turning on something else.
- No new saved data; ownership stays session state; Undo/Redo don't add history entries for it.

Tests (NEW, names adaptable), using real handlers through the pipeline (see test/laser_show/events/history_handlers_test.clj fixtures and vibes engine tests): `undo-of-a-cell-move-returns-vibe-ownership-test` (vibe on A, move A→B, Undo: ownership at A; switch vibe: A off; `persistent/project-files` projection has A off), redo case, and manual-switch case. Keep history, vibes engine/handler and persistence tests passing.

Boundaries: 16 (gesture/undo transactions) landed earlier in this worktree; 23 (rig group definitions on undo) and 14 (projector selection after undo) follow you and also touch restore code — keep your change localized and describe the restore hook you used.

## As implemented

**Changes**
- `state/history.clj`: NEW private `effect-source` = the one rule for which current cell a restored effect cell takes its on/off from (same key, else the cell holding the same items (moved), else none); `restored-effect-active` uses it (restore unchanged). NEW public `effect-cell-sources [state document-state]` → `{restored cell -> current cell}`.
- `vibes/refs.clj`: NEW `restore-fx [state sources]` remaps `[:vibe-runtime :fx-on]`, `[:voices :flash :fx]`, `[:voices :flash :fx-on]`. Gone content loses ownership; nothing switched on/off; no other cell becomes vibe-owned; nothing saved; no history entries; dirty unchanged.
- `events/handlers/history.clj`: NEW private `restore [state snapshot]` = single restore path for Undo/Redo (`apply-restore`) and a press's abort (`handle-abort-transaction`).

**Remaining risks**: a move that replaced another cell — restore's by-key rule gives the replaced cell the moved cell's on/off; after Undo both cells vibe-owned (a hand-switched replaced cell would also go off). Not remapped on Undo: `[:vibe-runtime :problem]` cell, cue voices and active cues (`prune-active-cues` stops a moved cue's run), trigger-held effect switches.

**Hook for 23 and 14**: `laser-show.events.handlers.history/restore [state snapshot]` calls `history/restore-document` with `(:document-state snapshot)`, then `refs/restore-fx` with `(history/effect-cell-sources state document)` (state = before restore). Undo/Redo then apply `(assoc :history history)` and `prune-active-cues`; abort applies `(assoc :history history)`.
- 23: replace the `restore-document` call inside `restore` with `history/restore-snapshot state snapshot`; keep computing `effect-cell-sources` from the pre-restore state.
- 14: add the selection fix as one more step inside `restore` (Undo, Redo, abort all get it).
- An Undo that asks first (`ask-first-if-it-grows`) throws away the restored state; nothing in `restore` is installed until confirmed.
