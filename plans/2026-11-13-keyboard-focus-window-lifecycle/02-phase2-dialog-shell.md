# Phase 2 — One dialog shell

**Written:** 2026-11-13   **Written for commit:** `cb32414` (`master`)
**Status:** Proposed. Needs D4 answered   **Last verified against:** `cb32414`
**Supersedes / Superseded by:** none   **Related findings:** K-12, K-14 (`00-overview.md` §3); review 01 F18, M5, F19 (dialog parts), Q9; the shared scene filter the 2026-10-07 plan wanted in every dialog

Six dialogs repeat the same stage skeleton, three repeat the same key handler, and each decides on its own whether and how it closes. This phase extracts the shell once, so Phase 3 (key handling) and Phase 5 (keyboard triggers) can be written in one place instead of six. About 1.5 days.

## 1. Current behaviour (file:line)

All six dialogs are `:modality :none` stages in `views/dialogs/`. `root.clj` mounts each one behind its own `dialog-open?` sub and `when` block (`root.clj:146-151`, `:174-196`).

| Concern | cue chain | effect chain | timeline | zone group | trigger | add projector |
|---|---|---|---|---|---|---|
| Stage skeleton (`:showing :title :modality :on-close-request`) | `cue_chain_editor.clj:679-683` | `effect_chain_editor.clj:251-255` | `timeline_cue_editor.clj:218-222` | `zone_group_editor.clj:177-181` | `cell_trigger_editor.clj:77-83` | `add_projector_manual.clj:99-103` |
| Stylesheets: `(css/dialog-stylesheet-urls)` called directly | `:673` | `:236` | `:212` | `:176` | `:76` | `:98` |
| Scene key handler | `:639-652` | `:201-214` (byte-identical to the cue one) | `:177-196` (same undo/redo + Space) | none | none | none |
| `"Cell " (char (+ 65 …))` | `:470`, `:674` | `:103`, `:238` | `:107`, `:213` | none | `[A, 1]` form at `:23`, `:75` | none |
| Size | content 800×700 | 800×550 | 1280×760, min 960×560 | content width 400 | stage 440×380 | content width 380 |
| Opened by | bespoke `:cue-chain/open-editor` (`cue_chain.clj:48-67`) | bespoke `:effect-chain/open-editor` (`effects.clj:111-124`) | `:timeline-cue-editor/open` → `:ui/open-dialog` (`timeline.clj:414-444`) | bespoke ×2 (`zone_groups.clj:17`, `:80`) | `:ui/open-dialog` direct (4 callers) | `:ui/open-dialog` direct (`projectors.clj:189`) |
| Has an entry in `domains.clj:141-162` | yes | yes | **no** | yes | **no** | yes |

Other duplicated code: `destination-zone-dropdown` (`cue_chain_editor.clj:95-126`, `timeline_cue_editor.clj:25-46`), `trigger-mode-options` (`:273-276`, `:52-54`) and `trigger-mode-dropdown` (`:278-312`, `:56-78`) differ only in label text and one comment. The window-title tail `(if (seq chain-name) (str chain-name " (" cell-id ")") cell-id)` appears three times (`cue :676-678`, `effect :240-242`, `timeline :215-217`). The grid's row header uses the same letter formula (`views/components/grid_tab.clj:99`).

Defects found while reading:
- **Add Projector never closes after Add.** The button dispatches `:projectors/add-manual` (`add_projector_manual.clj:85-88`); its handler (`projector.clj:238-252`) touches `:projectors`, `:chains` and `:projector-ui` only. The typed name/host/port also survive the next open, because `:ui/open-dialog` merges a nil `:data` (`handlers/ui.clj:29`).
- **`:editing-name?` is never reset on open.** Only the `set-name` handlers clear it (`cue_chain.clj:222`, `effects.clj:133`), so a dialog closed mid-rename reopens in rename mode.
- **Help > About does nothing.** `handle-help-about` sets `[:ui :dialogs :about :open?]` (`menu.clj:251`), but no view mounts an About dialog.
- A comment at `effect_chain_editor.clj:198` points at `laser-show.css.dialogs`, which does not exist.

Tests that constrain a refactor: windows are found by title prefix, `"Cue Chain Editor"` (`cue_chain_list_test.clj:18,44`) and `"Timeline Editor"` (`timeline_editor_test.clj:21,45,64`, `timeline_visual_test.clj:57`); `#trigger-editor-done` is used at `trigger_editor_test.clj:25,29`. Titles and ids must not change.

## 2. Root cause

Each dialog was written by copying the previous one. There is no shared dialogs namespace (`views/dialogs/` holds exactly the six files), so every cross-cutting concern, such as key handling, close behaviour and stylesheets, is implemented per dialog or not at all.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `views/components/inline_edit.clj` (`inline-edit-text`) | The name header, unchanged. |
| `subs/dialog-open?` (`subs.clj:391-396`), `subs/dialog-data` (`:398-405`), `subs/stylesheet-urls` (used by the main window at `root.clj:145`) | The shell's `:showing` and stylesheets. One stylesheet source for every window. |
| `:ui/open-dialog`, `:ui/close-dialog`, `:ui/update-dialog-data` (`handlers/ui.clj:18-58`) | The one open/close path. |
| The Space + `TextInputControl` guard in `timeline_cue_editor.clj:185-188` | The pattern for dialog-specific key extras. |
| `ui/window-titles`, `ui/wait-for!` (TestFX DSL) | The dialog smoke test. |

## 4. Changes per namespace

- `laser-show.views.dialogs.common` (NEW). Signatures only:
  - `cell-label [col row]` → `"A1"` (defaults `0`/`0` for nil, as today's `(or row 0)`); `default-name [col row]` → `"Cell A1"`.
  - `window-title [prefix chain-name col row]` → `"Cue Chain Editor - Intro (Cell A1)"`, or without the name part. Strings are identical to today's.
  - `undo-redo-event [key-data]` → `:edit/undo`, `:edit/redo` or nil. Pure; `key-data` is `{:code :z :shortcut? bool :shift? bool}`.
  - `scene-key-handler [extra-fn]` → the `:on-key-pressed` fn: undo/redo first, then `extra-fn` (the timeline's Space).
  - `destination-zone-dropdown`, `trigger-mode-options`, `trigger-mode-dropdown`: moved unchanged; the label becomes a `:label` prop ("Default Zone (Chain Fallback):" vs "Default zone:", "Trigger Mode:" vs "Trigger:").
  - `dialog-stage` (D4 option A): props `:dialog-id :title :content`, optional `:on-key-extra`. Renders the `:stage` (`:showing` from `dialog-open?`, `:modality :none`, `:on-close-request {:event/type :ui/close-dialog :dialog-id id}`) with a scene that takes its stylesheets from `subs/stylesheet-urls` and installs `scene-key-handler`.
- The six dialog namespaces: use the shell/helpers and delete their local copies. Sizes and content stay per dialog.
- `laser-show.views.root` (`:146-151`, `:174-196`): replace the six subs and `when` blocks with one registry map `{dialog-id → dialog component}` and a loop over the open ones (D4 option A).
- `laser-show.events.handlers.ui` (`ui.clj:18-35`): no new events. Open handlers set `:editing-name? false`.
- `laser-show.events.handlers.projector` (`projector.clj:238-252`): `handle-projectors-add-manual` also sets `[:ui :dialogs :add-projector-manual :open?] false` and resets the form keys.
- `laser-show.state.domains` (`domains.clj:141-162`): add defaults for `:timeline-cue-editor` and `:trigger-editor`; add the add-projector form defaults.
- Cleanup: the stale comment at `effect_chain_editor.clj:198`.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:ui :dialogs <id> …]` (existing; two new defaults, add-projector form keys) | app state, UI | `:ui/*` handlers, bespoke open handlers, `:projectors/add-manual` | No / no (not a document root) |

No runtime atoms, no new threads, no effects.

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:ui/open-dialog`, `:ui/close-dialog`, `:ui/update-dialog-data` | yes (`handlers/ui.clj:142-144`) | shell, buttons, callers listed in §1 | yes |
| `:projectors/add-manual` | yes (`projector.clj:238`, table `:415`) | Add button | yes. Now also closes the dialog. |
| `:edit/undo`, `:edit/redo` | yes (`menu.clj:328-329`) | shared key handler | yes |

## 7. Data examples and invariants

| Call | Result |
|---|---|
| `(cell-label 0 0)` | `"A1"` |
| `(cell-label 7 3)` | `"D8"` |
| `(cell-label nil nil)` | `"A1"` |
| `(window-title "Cue Chain Editor" "Intro" 0 0)` | `"Cue Chain Editor - Intro (Cell A1)"` |
| `(window-title "Cue Chain Editor" nil 0 0)` | `"Cue Chain Editor - Cell A1"` |
| `(undo-redo-event {:code :z :shortcut? true})` | `:edit/undo` |
| `(undo-redo-event {:code :z :shortcut? true :shift? true})` | `:edit/redo` |
| `(undo-redo-event {:code :y :shortcut? true})` | `:edit/redo` |
| `(undo-redo-event {:code :z})` | `nil` (never fires without the shortcut modifier, so typing `z` in a field is untouched) |

Invariants:
1. Every dialog id in the root registry has a default entry in `domains.clj`.
2. Opening and closing any dialog never changes the document, so there is no undo entry and the dirty flag is untouched.
3. Window titles and the node ids listed in §1 are unchanged.
4. The shared handler consumes a key only when it matched an undo/redo chord (or the dialog's own extra).

## 8. Tests (named test vars + fixtures)

- `laser-show.views.dialogs.common-test` (NEW, pure): `cell-label-test`, `window-title-test`, `undo-redo-event-test`.
- `laser-show.events.ui-handlers-test` (extend): `every-mounted-dialog-has-a-default-test`, `open-dialog-clears-editing-name-test`.
- `laser-show.events.projector-handlers-test` (extend): `add-manual-closes-the-dialog-and-resets-the-form-test`.
- `laser-show.ui-test.dialogs-test` (NEW; `with-toolkit` once, `with-app` each):
  - `each-dialog-opens-with-its-expected-title-test` (walks the registry through each dialog's open event);
  - `undo-shortcut-works-in-every-chain-dialog-test` (edit, then Shortcut+Z);
  - `add-projector-dialog-closes-after-add-test`;
  - `timeline-space-still-toggles-play-test` (the existing behaviour, now through the shell).
- `cue_chain_list_test` and `timeline_editor_test` must stay green unchanged.

Run: `clj -M:mac-arm:test common ui-handlers projector-handlers`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.dialogs-test`, then both full suites.

## 9. Out of scope / alternatives considered

- **A `defdialog` macro.** Rejected: it would hide which props and subs a dialog depends on.
- **Moving dialog CSS** (review 01 L1). Separate, large, low-risk.
- **Resetting all dialog state on open.** Rejected: `:timeline-cue-editor` keeps per-effect UI modes in `[:ui :dialogs :timeline-cue-editor :effect-ui …]` (`chain/helpers.clj:41-42`), and dropping them on every open would be a visible regression.
- **Adjacent menu stubs (K-14).** These items do nothing today. Listed here because they sit next to the dialog work; answer with D4 or leave them for later.

| Menu item | Today | Recommended default |
|---|---|---|
| Help > About | flag set (`menu.clj:251`), nothing mounted | mount a small About dialog on the new shell |
| File > Export… | no-op (`menu.clj:122-127`) | remove the item until an exporter exists |
| View > Toggle Preview | flips `[:ui :preview-visible?]`, nothing reads it (`menu.clj:220-225`) | wire it into `root.clj`'s `show-preview?` (`:97`) |
| View > Fullscreen | flips `[:ui :fullscreen?]`, nothing reads it (`:227-232`) | small effect that calls `Stage.setFullScreen` |
| Help > Check for Updates | no-op (`:253-258`) | remove the item until releases carry binaries |

---

## Decisions

> **DECISION D4 — How much of the shell is extracted?** *(open · needed before Phase 2 · recommended: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | `common.clj` helpers **plus** a `dialog-stage` component **plus** a root registry (one loop instead of six `when` blocks); add-projector, `:editing-name?` and defaults fixes | Every dialog gets the shared key handler and stylesheets by construction; adding the seventh dialog (About, the unsaved confirm) is a few lines; Phase 5 installs its filter once | A larger diff in the six files; the root registry is a new indirection |
> | B | Helpers only (`cell-label`, `window-title`, `undo-redo-event`, the dropdowns). Each dialog keeps its own stage and its own `when` block in `root.clj` | Smaller, easier to review | The shared key handler has to be wired into six scenes by hand, and a new dialog can forget it |
> | C | Fix only the three bugs (add-projector close/reset, `:editing-name?`, missing defaults) | Smallest | Keeps the duplication this phase exists to remove |
>
> Menu stubs: accept the table above, or say which rows you want differently (for example `D4: A, keep Export`).
>
> **Your answer:** _pending_
