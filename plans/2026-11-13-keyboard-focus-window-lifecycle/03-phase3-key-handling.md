# Phase 3 — Keys reach the right place

**Written:** 2026-11-13   **Written for commit:** `cb32414` (`master`)
**Status:** Proposed. Needs D5–D7 answered   **Last verified against:** `cb32414` (probes p1–p4, `probes/keyboard_probes_test.clj`)
**Supersedes / Superseded by:** replaces §§2–5 of `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md`   **Related findings:** K-4, K-5 (`00-overview.md` §3); review 01 F3 (done), F4, F20, L3, Q4; 02 #15 (list part)

The 2026-10-07 plan proposed a focus context in app state, a `:command/*` router and a shortcut registry. Probing the running app shows that most of the failures it was built for do not happen, and that JavaFX's own event flow already gives the focused component priority. What is left is small. About 0.5–1 day.

## 1. Current behaviour (file:line)

**How a key press travels.** JavaFX delivers `KEY_PRESSED` to the focus owner in two passes: *filters* run outside-in (stage, scene, ancestors, target); *handlers* then run inside-out (target, ancestors, scene). `MenuItem` accelerators are checked at the scene during the second pass, and only if nobody consumed the event. So a component that handles a key on its own node always beats a menu accelerator, and a text field that consumes a key hides it from the scene.

**Where keys are handled today:**

| Where | File:line | Pass | Keys |
|---|---|---|---|
| Main-window menu accelerators | `title_bar.clj:68, 73, 80, 86, 116, 122, 130, 135, 143, 152, 157, 163, 172, 177, 218, 223` | scene, second pass | Ctrl/Cmd combos, plus single keys Delete (`:143`), Space (`:152`), Escape (`:157`), R (`:163`), F11 (`:177`) |
| Dialog scene handlers | cue `cue_chain_editor.clj:639-652`; effect `effect_chain_editor.clj:201-214`; timeline `timeline_cue_editor.clj:177-196` | scene, second pass | undo/redo; Space in the timeline (with a `TextInputControl` guard, `:185-188`) |
| List editor | `list.clj:716-790` (filter), installed in `list-editor` `:866-884` | node, first pass | Ctrl+C/V/X/A/G, Delete, Escape, but only `when focused?` (`:748`, from `(.isFocused node)` at `:733`) |
| List rename field | `list.clj:350-370` | node | Escape; losing focus cancels |
| Inline name edit | `inline_edit.clj:43-50` | node | Enter, Escape |
| Interactive canvas | `canvas_interaction.clj:272-289`; focusable only when it has `on-key` (`:178-181`); takes focus on press and consumes the press (`:185-192`) | node, second pass | per canvas |
| Timeline canvas | `timeline_editor.clj:374-414` | via the canvas | Space, Esc, Ctrl+D, Ctrl+A, Delete/Backspace, ←/→ |
| Main window scene | `root.clj:170-172` | – | no key handler or filter at all |

**Probes** (headless TestFX, JavaFX 28-ea+11; `probes/keyboard_probes_test.clj`):

| Probe | Action | Result |
|---|---|---|
| p1 | focus `#main-layout`, press Space | `playing? true`: the menu accelerator fires |
| p2 | focus `#bpm-input` (a `TextField`), press Space; press R while playing; press Delete with cell `[0 0]` selected | nothing fired (`playing? false`; clock not reset; cell kept its items). The scene's second pass never saw `KEY_PRESSED` |
| p3 | click a row in the Cue Chain Editor list | focus owner is `VBox#list-editor-cue-chain-0-0`; Shortcut+A selects 3 of 3; Delete removes them |
| p4 | after selecting a row, focus the list's own "New" toolbar button, press Delete | **no effect** (3 items before and after) |
| p8 | click TAP, press Space | the scene's filter sees `KEY_PRESSED` and `KEY_RELEASED` with a `Button` target; the scene's second pass sees neither, so a Space accelerator cannot fire |

The main window's focus owner at startup is `Button#play-btn` (it shows in the p3 and p4 output). Headless mode cannot show what a button then *does* with Space, so that part is read from JavaFX semantics only.

**Claims in the 2026-10-07 plan that no longer hold:**

| Old claim | Status |
|---|---|
| §2.A "ScrollPane focus trap": `request-list-focus!` stops at the `ScrollPane`, so the list never has focus | Not reproducible. After clicking a row the focus owner is the list-editor `VBox` (p3). |
| §2.C canvas ignores mouse focus | Fixed: `canvas_interaction.clj:185-192`. |
| §1.2 / review 01 F20: single-key accelerators fire while typing in text fields | Not reproducible for a `TextField` (p2). The field consumes the key first. |
| `keyboard-handler-atoms` | Renamed `!component-handler-props` (`list.clj:57`). |
| Timeline Delete clears the selection instead of deleting | Fixed: `timeline_editor.clj:401`. |
| Ctrl vs Cmd on macOS | Fixed: `list.clj:729` uses `(or (.isShortcutDown e) (.isControlDown e))`. |
| §2.B child controls steal focus from the list | **True** (p4). This is the remaining defect, K-4. |
| §2.F out-of-band props atom | **True**, K-5. |

## 2. Root cause

The list asks "am I the keyboard target?" with `Node.isFocused`, which is true only for the node itself, never for its descendants. The list's own toolbar buttons (`list.clj:566-578`, `:679-697`) and the row enable checkboxes (`:413`, `:489`) are focus-traversable, so clicking any of them silently turns the list's shortcuts off. Nothing else in the old plan's problem list survives the probes.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| The list's capture-phase filter (`list.clj:716-790`) | Stays the one place list keys are handled; only the "is this mine?" test changes. |
| `request-list-focus!` (`list.clj:249-256`) and the click handlers that call it (`:337`, `:345`) | Unchanged. |
| The timeline editor's idiom of `:focus-traversable false` on toolbar buttons (`timeline_editor.clj:525-536`) | The option for the main-window and list toolbars (D6). |
| `Node.isFocusWithin` (JavaFX 19+; present in 28-ea, checked on this classpath) | The replacement for `.isFocused`. |
| Dialog shell from Phase 2 | Carries the one shared scene handler. |

## 4. Changes per namespace

- `laser-show.views.keys` (NEW, about 30 lines; justified because three phases need the same two definitions):
  - `typing-target? [^KeyEvent e]`: true when the event target is a `TextInputControl` (text field, text area, a spinner's or editable combo's editor).
  - `focus-within? [^Node node]`: `(.isFocusWithin node)`.
  - `list-key-target? [node e]`: `(and (focus-within? node) (not (typing-target? e)))`.
- `laser-show.views.components.list` (`list.clj`):
  - `setup-keyboard-handlers!` (`:716-790`) uses `list-key-target?` in place of `focused?` (`:733`, `:748`). The typing guard is mandatory: the rename `TextField` lives inside the list, so a bare `isFocusWithin` would let the list eat Delete and Ctrl+A meant for the text.
  - Drop the noisy `log/info` / `log/debug` blocks on every key press (`:719-722`, `:736-745`).
  - Props atom: per D7.
- Main-window buttons: per D6 (`toolbar.clj:14-29, 116-128, 169-198, 235-243`; `tabs.clj:23-45`).
- No change to `title_bar.clj` accelerators, the canvas, or the dialogs' handlers beyond what Phase 2 already moved.

## 5. State placement

None. No new keys, atoms, events or effects. Focus stays in the scene graph, where JavaFX already tracks it.

## 6. Events and effects

None new. The list continues to dispatch `:list/*` events (`list.clj:125-153`).

## 7. Data examples and invariants

| Focus owner | Key | Today | After |
|---|---|---|---|
| list `VBox` | Delete | removes the selection | same |
| a toolbar button inside the list | Delete | **ignored** (p4) | removes the selection |
| a row's enable checkbox | Ctrl+A | ignored | selects all |
| the rename `TextField` inside the list | Delete / Backspace / Ctrl+A | the field edits its text | the field edits its text; the list does nothing |
| a button outside any list (main window) | Delete | Clear Cell via the accelerator | same |
| `#bpm-input` | Space / R / Delete | field only (p2) | same |

Invariants:
1. A key press is acted on by at most one component: the focused one (first pass) or, if it is not consumed, the scene (second pass).
2. No component or scene handler acts while the event target is a `TextInputControl`.
3. A dialog containing two lists never has two lists acting on one key press (focus is within at most one).

## 8. Tests (named test vars + fixtures)

All UI tests use `with-toolkit` once and `with-app` each, in `laser-show.ui-test.cue-chain-list-test` (extend; helpers `open-cue-chain-editor!`, `add-cue-chain-items!`, `flush-event-queue!` exist at `:14-36`):
- `delete-after-focus-moves-to-a-toolbar-button-test` (p4; fails today)
- `select-all-after-clicking-a-row-checkbox-test`
- `list-shortcuts-do-not-act-while-renaming-test`
- `delete-after-clicking-a-row-still-works-test` (p3 as a regression)

`laser-show.ui-test.keys-test` (NEW; needs the toolkit because it builds controls): `typing-target-detects-text-inputs-test`, `focus-within-is-true-for-descendants-test`.

Run: `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.cue-chain-list-test`, then `-n laser-show.ui-test.keys-test`, then both full suites.

## 9. Out of scope / alternatives considered

- **A focus context in app state** (the old plan): D5 option C.
- **A `:command/*` router and shortcut registry.** Not needed for correctness: the focused component already wins, and the Edit menu already routes by active tab and selected cell (`menu.clj:154-214`). Revisit only if user-configurable shortcuts become a goal.
- **Moving Ctrl/Cmd combos out of `MenuItem` accelerators.** They work (p1) and text fields consume their own (p2).
- **Atomic list operations** (review 02 M5; D7 option C). A larger refactor of `handlers/list.clj` that also fixes the stale two-step dispatch; schedule separately.
- **`list-ordered-ids` re-evaluates on every context change for non-`:chains` lists.** Its `:else` branch reads the whole state (`subs.clj:603-604`), and the preview timer swaps the context about 60 times a second. This is review 01 F17 / M6, not keyboard work. Noted because this phase touches the same props atom.

---

## Decisions

> **DECISION D5 — Focus model** *(open · needed before Phase 3 · recommended: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Let JavaFX's event flow route keys. The focused component handles its keys (list: `focusWithin` + typing guard); everything unconsumed reaches one shared scene handler. No new state | Smallest change that fixes the verified defect; nothing to keep in sync; works the same in every window | Components must each use the shared `typing-target?` guard |
> | B | Keep `.isFocused` and stop child controls taking focus (`:focus-traversable false` on list toolbar buttons and checkboxes, as the timeline editor does) | One-line changes | Fragile: any new focusable child breaks the list again; loses Tab navigation |
> | C | The old plan: `[:ui :focus-context]` in app state, `:command/*` router, shortcut registry; components dispatch a context event on every click | Menu items could enable or disable by context; one place lists every shortcut | An event per click; stale-context bugs across windows; a registry and router to build and keep in step; solves problems the probes did not find |
>
> **Your answer:** _pending_

> **DECISION D6 — Main-window buttons and the single-key shortcuts** *(open · needed before Phase 3 · recommended: A)*
>
> Space, Escape, R and Delete are `MenuItem` accelerators (`title_bar.clj:143-163`). They fire unless a focused control consumes the key, and buttons do: after you click TAP, Space presses TAP again instead of toggling play.
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Make the main window's toolbar and tab buttons not focus-traversable (mouse-only), like the timeline editor's toolbar (`timeline_editor.clj:525-536`) | Space/Esc/R/Delete stay global; tiny change | No Tab-key navigation across the toolbar |
> | B | Leave buttons focusable | No change | The shortcut works until you click a button |
> | C | A, and also move the four single-key accelerators into one registry-driven scene handler | One list of shortcuts; they could be shown in a help overlay | Not needed to make them work. Keyboard triggers (Phase 5) override them without it |
>
> **Your answer:** _pending_

> **DECISION D7 — How do list keyboard commands get their props (items path, change event, clipboard type)?** *(open · needed before Phase 3 · recommended: A)*
>
> Today a global atom of latest props (`list.clj:57-77`) is written by two components (`:603`, `:865`), never cleaned for closed dialogs, and the keyboard filter reads it. The click handler reads it too (`:327`).
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Keep the atom, make it correct: one writer (`list-sidebar`), always includes `:ordered-ids` and (Phase 4) `:clipboard-type`, entry removed when the component is deleted | Smallest, safe | Still a mutable side channel |
> | B | Store the props on the node (`getProperties`), refreshed each render | No global atom | Needs a per-render refresh hook; the same stale-closure risk (`docs/STALE_CLOSURES.md`) |
> | C | A pure registry keyed by `component-id` (review 02 M5): events carry only the id, and handlers derive items path, change event and clipboard type | Removes the side channel, the two-step dispatch and stale reads | A larger refactor across `handlers/list.clj`, `chain/core.clj`, `cue_chain.clj`; best done as its own plan |
>
> **Your answer:** _pending_
