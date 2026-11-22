# Phase 3 — Keys reach the right place

**Written:** 2026-11-13   **Written for commit:** `cb32414` (`master`)   **Re-checked:** 2026-11-22 at `2227bce`, 275 commits later (the timeline rework, vibe Phases 1–8, zones Z0–Z6 and ZR, the output drivers, the Projectors layout pass, plan 02's write guard, clipboard Phase 4): every citation re-checked, probes p1–p4 and p8 re-run, p19 added, the new key handlers added to §1
**Status:** Proposed, ready to build. D5, D6 and D7 answered A (2026-11-22, the ★ options, taken under the user's standing choice to take the recommended options). Nothing in it has been built by other work   **Last verified against:** `2227bce` (probes p1–p4, p8, p19, `probes/keyboard_probes_test.clj`; headless JavaFX 28-ea+11, JDK 25.0.5, macOS arm64)
**Size:** ~0.5–1 day   **Needs:** nothing open   **Depends on:** nothing (see §0; it used to wait for Phase 2)
**Supersedes / Superseded by:** replaces §§2–5 of `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md`   **Related findings:** K-4, K-5 (`00-overview.md` §4); review 01 F3 (done), F4, F20, L3, Q4; 02 #15 (list part)

The 2026-10-07 plan proposed a focus context in app state, a `:command/*` router and a shortcut registry. Probing the running app showed that most of the failures it was built for don't happen, and that JavaFX's own event flow already gives the focused component priority. What's left is small. About 0.5–1 day.

## 0. What changed since `cb32414`, and the order

- **The defect is unchanged.** The list still asks `.isFocused` (`list.clj:769`), and p4 still prints `3 3`. None of `isFocusWithin`, a `typing-target?` helper or `laser-show.views.keys` exists in `src/` or `test/`.
- **More components handle keys of their own.** Every one of them consumes only the keys it maps, on a node that is itself focus-traversable, so the D5 A model fits them as they are. They are:
  - the step grid's body (zones Z6);
  - the rig strip (Projectors layout pass);
  - the commit field's undo filter;
  - the step-grid and zone-pattern windows' scene handlers;
  - the timeline header's rename field;
  - the confirm dialogs' default and cancel buttons.
- **The pads never take focus.** The Perform tab's vibe pads, Z4's map pads and Z5's pattern pads are `:focus-traversable false`, so they need nothing from this phase. They have no key handling at all: the "Zone step key" and the Perform tab's "Press the key for …" (`perform.clj:145`) are input-router bindings learned from MIDI, OSC or gamepad, which is Phase 5's ground.
- **A list now also lives in the main window.** This is the projector chain (`tabs/projectors.clj:522`). There, a focused list button lets Delete and ⌘C fall through to Edit > Clear Cell and Edit > Copy. Those do nothing on the Projectors tab (`menu.clj:327-345`, `:283-303` only act on `:grid` and `:effects`), so the only harm is the list ignoring the key, as in the dialogs.
- **The clipboard type no longer travels in the props.** Phase 4 derives it from the list's id (`state/clipboard.clj:89-99`, `list-type`). So D7 A's "(Phase 4) `:clipboard-type`" is no longer needed, and D7 A is the props atom with `:ordered-ids` only.
- **p19 (new) shows that tab buttons and the trigger-mode combo keep focus after a click and eat Space**, as TAP does (p8). D6 A's scope is the whole toolbar except the BPM field, and the tab bar.
- **Order.** Phase 3 touches `list.clj`, `toolbar.clj`, `tabs.clj` and one NEW namespace, and no dialog file. The doc used to depend on Phase 2 "for the shared scene handler", but nothing below uses that handler, so Phase 3 can ship first. Phase 2's shell then takes `keys/typing-target?` for the timeline's Space guard (02 §4).

## 1. Current behaviour (file:line)

**How a key press travels.** JavaFX delivers `KEY_PRESSED` to the focus owner in two passes:
1. *Filters* run outside-in: stage, scene, ancestors, target.
2. *Handlers* then run inside-out: target, ancestors, scene.

`MenuItem` accelerators are checked at the scene during the second pass, and only if nobody consumed the event. So a component that handles a key on its own node always beats a menu accelerator, and a control that consumes a key hides it from the scene. The main window's menu bar is in its scene (`title_bar.clj:330-331`, `:use-system-menu-bar false`), so the accelerators exist only in the main window. A dialog has none.

**Where keys are handled today** (`2227bce`):

| Where | File:line | Pass | Keys |
|---|---|---|---|
| Main-window menu accelerators | `title_bar.clj:68, 73, 80, 86, 117, 123, 131, 136, 144, 154, 162, 168, 177, 182, 223, 228` (the last two dev mode only, `:220`) | scene, second pass | Shortcut combos, plus single keys: Delete (Clear Cell, `:144`), Space (Play / Stop, `:154`), Escape (a hidden Stop item, enabled only while playing, `:158-164`), R (Retrigger, enabled only while playing, `:166-170`), F11 (Fullscreen, `:182`) |
| Dialog scene handlers | cue `cue_chain_editor.clj:507-520` (installed `:528`); effect `effect_chain_editor.clj:197-210` (`:218`); timeline `timeline_cue_editor.clj:162-181` (`:187`); **new:** step grid `step_grid_editor.clj:108-122` (`:142`), zone pattern `dialogs/zone_pattern_editor.clj:75-88` (`:105`) | scene, second pass | Shortcut+Z / Shift+Z / Y. The timeline also handles Space (`:170-173`), with a `TextInputControl` guard (`:171`, the only one in `src/`). The undo/redo bodies are the same in all five (02 §1) |
| List editor | `list.clj:752-824` (a filter), installed in `list-editor` at `:889-907` | node, first pass | Shortcut/Ctrl+C `:786`, +V `:791`, +X `:796` (one-step Cut, Phase 4), +A `:801`, +G `:806`; Delete `:811`; Escape `:816`. All only `when focused?` (`:784`, from `(.isFocused node)` at `:769`). It consumes only what it handled (`:822-824`) |
| List rename field | `list.clj:361-381` | node | Enter commits, Escape cancels (`:373-378`); losing focus cancels (`:379-381`) |
| Inline name edit | `inline_edit.clj:43-50` | node | Enter, Escape |
| Commit field (**new**: the BPM field, driver fields and others) | `commit_field.clj:12-34`, installed as a filter at `:86` | node, first pass | Shortcut+Z / Shift+Z become `:edit/undo` / `:edit/redo` while the field shows its applied text (`:32`) |
| Interactive canvas | `canvas_interaction.clj:296-313`; focusable only when it has `on-key` (`:202-205`); takes focus on press and consumes the press (`:212-216`) | node, second pass | per canvas: `spatial_canvas.clj:348-380` (Tab, arrows), `rotate_canvas.clj:264-275`, `scale_canvas.clj:330-354`, `hue_shift_canvas.clj:143-154`, `hue_canvas.clj:150-159` (arrows), `safety_editor.clj:298-300` (Escape while dragging) |
| Timeline canvas | `visual_editors/timeline_editor.clj:417-457`, wired at `:966` | via the canvas | Space `:425`, Esc `:428`, Shortcut+D `:433`, Shortcut+A `:439`, Delete/Backspace `:444-445`, ← `:447`, → `:452` |
| Timeline track rename (**new**) | `visual_editors/timeline/header.clj:98-101` | node | Escape |
| Step grid body (**new**, zones Z6) | `step_grid.clj:41-68` (`key-event`), `:70-77` (`on-key`), wired at `:156` on `Pane#step-grid-body` (`:143`, focus-traversable `:144`); takes focus when created (`:140`) and on press (`:148-153`) | node, second pass | arrows, Shift+←/→ (rotate), Space and Enter (toggle), Delete/Backspace, Home/End, Shortcut+C/V; consumes every key it maps (`:76`) |
| Rig strip (**new**, Projectors tab, main window) | `rig_strip.clj:472-484`, wired at `:583` on `GridPane#rig-strip` (focus-traversable `:579`); `focus-strip!` `:384-391` on a card click (`:433`) | node, second pass | ←/→ move the selection, Alt+←/→ move the card; always consumes ←/→ (`:484`). Because it is a handler on the strip, a focused child (card checkbox `:182`, move buttons `:490-500`, the group matrix's combo, slider and "+ Group" button) that doesn't consume ←/→ lets them bubble up to it, so it already behaves like D5 A (read from JavaFX semantics; not probed) |
| Confirm dialogs (**new**) | `confirm.clj:76-84`, `unsaved_confirm.clj:54, 65, 99, 110`, `rig_report.clj:71, 77, 236-237` | JavaFX default and cancel buttons | Enter and Escape, with no key handler |
| Pads (**new**) | `vibe_pads.clj:69`, `zone_map_pads.clj:140, 239`, `pattern_pads.clj:109` | – | none; `:focus-traversable false` |
| Main window scene | `root.clj:220-222` | – | no key handler or filter. The stage has `:on-focused-changed {:event/type :vibe/window-focus}` (`:212`), and the split pane a mouse-only filter (`:142`) |

`views/components/shortcuts.clj` (new) is not a key handler. Its `shortcut-text` (`:12-23`) writes "⌘C" or "Ctrl+C" for labels and tooltips.

**Probes** (headless TestFX, JavaFX 28-ea+11, JDK 25.0.5; `probes/keyboard_probes_test.clj`). Re-run 2026-11-22 at `2227bce` (`uptime` before and after: load averages 1.18 / 1.27). The first run at `2227bce` (load 1.33 / 1.62) gave the same p1–p4 results and failed p8 (see the p8 row).

| Probe | Action | `cb32414` | `2227bce` |
|---|---|---|---|
| p1 | focus `#main-layout`, Space | `playing? true` (the accelerator fires) | same |
| p2 | focus `#bpm-input` (a commit-field `TextField`); Space; R while playing; Delete with `[0 0]` selected | nothing fires | same: `playing? false`; no restart requested; items `1 1` |
| p2 (new step) | Escape in `#bpm-input` while playing; then Escape on `#main-layout` while playing | not probed | **Stop fires in both.** `playing? false` with the focus owner still `TextField#bpm-input`: the field doesn't consume Escape. See 05, D10 |
| p3 | click a row in the Cue Chain Editor list; Shortcut+A; Delete | focus owner `VBox#list-editor-cue-chain-0-0`; 3 of 3; `3 0` | same |
| p4 | select a row, focus the list's "New" toolbar button (`#chain-new-group`), Delete | **ignored** (`3 3`) | **same: ignored** (`3 3`, 1 selected) |
| p8 | click TAP, Space | the scene filter sees the press and the release (target `Button`); the second pass sees neither | same. The probe timed out until it was fixed: it waited for `[:timing :tap-times]`, and tap tempo now writes `[:timing :tap :taps]` (`domains.clj:40`). Headless, TAP's action doesn't run on Space (taps stay 1) |
| p19 (**new**) | click `#grid-tab`, `#perform-tab`, `#trigger-mode-selector`; Space after each | – | the clicked control keeps focus (`Button#grid-tab`, `Button#perform-tab`, `ComboBox#trigger-mode-selector`) and Space doesn't play (`playing? false` each time) |

The main window's focus owner at startup is still `Button#play-btn` (p3, p4).

**Claims in the 2026-10-07 plan that no longer hold:**

| Old claim | Status |
|---|---|
| §2.A "ScrollPane focus trap": `request-list-focus!` stops at the `ScrollPane`, so the list never has focus | Not reproducible. After a row click the focus owner is the list-editor `VBox` (p3). |
| §2.C canvas ignores mouse focus | Fixed: `canvas_interaction.clj:212-216`. |
| §1.2 / review 01 F20: single-key accelerators fire while typing in text fields | Not reproducible for Space, R and Delete in a `TextField` (p2): the field consumes them first. Escape is the exception, and it stops the show from a text field (p2). |
| `keyboard-handler-atoms` | Renamed `!component-handler-props` (`list.clj:60`). |
| Timeline Delete clears the selection instead of deleting | Fixed: `timeline_editor.clj:444-445`. |
| Ctrl vs Cmd on macOS | Fixed: `list.clj:765` uses `(or (.isShortcutDown e) (.isControlDown e))`. |
| §2.B child controls steal focus from the list | **True** (p4). This is the remaining defect, K-4. |
| §2.F out-of-band props atom | **True**, K-5. |

## 2. Root cause

The list asks "am I the keyboard target?" with `Node.isFocused` (`list.clj:769`). That is true only for the node itself, never for its descendants. Several children of the list are focus-traversable, so clicking any of them silently turns the list's shortcuts off:
- the group toolbar buttons (`#chain-new-group` `:580`, `#chain-group-selection` `:587`);
- the Copy / Cut / Paste / Delete row (`:700`, `:709`, `:718`, `:727`);
- the group header's collapse and ungroup buttons (`:415-422`, `:449-453`);
- the row enable checkboxes (`:425-428`, `:502-505`).

The list is used in four places:
- the effect chain editor (`effect_chain_editor.clj:132`);
- the Cue Chain Editor's two lists (`cue_chain_editor.clj:361`, `:384`);
- the projector chain in the main window (`tabs/projectors.clj:522`).

In the main window, the toolbar's buttons and combo and the tab buttons are focus-traversable. So after a click they eat Space (p8, p19): the second pass never sees it and Play doesn't fire. Nothing else in the old plan's problem list survives the probes.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| The list's capture-phase filter (`list.clj:752-824`) | Stays the one place list keys are handled; only the "is this mine?" test changes. |
| `request-list-focus!` (`list.clj:260-267`) and the click handlers that call it (`:348`, `:356`) | Unchanged. |
| The `:focus-traversable false` idiom: timeline toolbar (`timeline_editor.clj:636`, `:647`, `:710-777`), step grid (`step_grid.clj:347`, `:300`, `:433`), the pads (§1) | The idiom for the main-window toolbar and tab bar (D6 A). |
| `Node.isFocusWithin` (JavaFX 19+; present in 28-ea on this classpath) | The replacement for `.isFocused`. |
| `clipboard/list-type` (`state/clipboard.clj:89-99`) | Already gives each list its clipboard type from its id, so the props atom doesn't carry one (D7). |

## 4. Changes per namespace

- `laser-show.views.keys` (NEW, about 30 lines). It is justified because Phases 2, 3 and 5 need the same two definitions, and `timeline_cue_editor.clj:171` has the only copy today.
  - `typing-target? [^KeyEvent e]`: true when the event target is a `TextInputControl`. That covers a text field, a text area, and a spinner's or editable combo's editor.
  - `focus-within? [^Node node]`: `(.isFocusWithin node)`.
  - `list-key-target? [node e]`: `(and (focus-within? node) (not (typing-target? e)))`.
- `laser-show.views.components.list` (`list.clj`):
  - `setup-keyboard-handlers!` (`:752-824`) uses `list-key-target?` in place of `focused?` (`:769`, `:784`). The typing guard is mandatory: the rename `TextField` (`:361-381`) lives inside the list, so a bare `isFocusWithin` would let the list eat Delete and Ctrl+A meant for the text.
  - Drop the logging on every key press and on focus changes. This means the `log/info` at `:755-758` and `:891-893`, the `log/debug` at `:772-781` and on every handled key (`:787, 792, 797, 802, 807, 812, 817, 823`), and the debug focus listener (`:901-907`).
  - Props atom, per D7 A:
    - one writer, `list-sidebar` (`:617-619`). Its props already carry `:ordered-ids`, and the `list-editor` write (`:882-888`) goes;
    - the entry is removed when the editor's node is deleted (an `:on-deleted` beside the `:on-created` at `:889`).
- Main-window controls, per D6 A, get `:focus-traversable false`:
  - in `toolbar.clj`, the transport button (`:16-31`; `#play-btn`/`#stop-btn`), Retrigger (`:55-59`), the trigger-mode combo (`:88-98`), TAP (`:132-144`), Link (`:188-217`) and Stream (`:293-302`). `#bpm-input` (`:122-130`) stays focusable: it is a text field;
  - in `tabs.clj`, `styled-tab-button` (`:23-41`). That also covers the other users of `styled-tab-bar`: `tabbed_bank.clj:129` and `custom_param_renderers.clj:484`.
- No change to:
  - the `title_bar.clj` accelerators;
  - the canvases, the step grid and the rig strip (each already handles its keys on its own focusable node);
  - the dialogs' scene handlers (Phase 2 moves them).

## 5. State placement

None: no new keys, atoms, events or effects. Focus stays in the scene graph, where JavaFX already tracks it. The props atom is view-local and FX-thread-only, as today. It is not app state, so plan 02's write guard (`state/core.clj:102-128`) doesn't apply to it.

## 6. Events and effects

None new. The list keeps dispatching `:list/*` events (`list.clj:106-241`): `:list/select-all` `:115`, `:list/delete-selected` `:132`, `:list/copy-selected` `:142`, `:list/cut-selected` `:150`, `:list/paste-items` `:160` and the group and rename events.

## 7. Data examples and invariants

| Focus owner | Key | Today | After |
|---|---|---|---|
| list `VBox` | Delete | removes the selection | same |
| a toolbar button inside the list | Delete | **ignored** (p4) | removes the selection |
| a row's enable checkbox | Ctrl+A | ignored | selects all |
| the rename `TextField` inside the list | Delete / Backspace / Ctrl+A | the field edits its text | the field edits its text; the list does nothing |
| a button inside the projector chain list (main window, Projectors tab) | Delete | falls through to Clear Cell, which does nothing on this tab | removes the selection |
| `#tap-tempo-btn` after a click | Space | TAP keeps focus; Play doesn't fire (p8) | TAP never takes focus; Space plays |
| `#grid-tab` or `#trigger-mode-selector` after a click | Space | Play doesn't fire (p19) | Space plays |
| `#bpm-input` | Space / R / Delete | field only (p2) | same |
| `#bpm-input` | Escape while playing | Stop fires (p2) | same (Phase 5, D10) |
| the step grid body, the rig strip, the timeline canvas | their keys | the component handles them | same |

Invariants:
1. A key press is acted on by at most one component: the focused one (first pass, or its own handler), or the scene if nobody consumed it.
2. No list, component or scene handler acts on Space, R, Delete or a letter while the event target is a `TextInputControl`.
3. A dialog containing two lists never has two lists acting on one key press, because focus is within at most one.
4. Every `!component-handler-props` entry belongs to a list editor that is on screen, and it has `:ordered-ids`.

## 8. Tests (named test vars + fixtures)

All UI tests use `with-toolkit` once and `with-app` each (`ui_test/fixtures.clj:18-25`, `:48-59`). They wait with `events/flush!` (`events/core.clj:812-826`) and set state up with `events/write-state!` (`:828-842`), because plan 02 makes a direct write throw under `start-test!` (`app.clj:395`).

`laser-show.ui-test.cue-chain-list-test` (extend; helpers `open-cue-chain-editor!` `:16-22`, `add-cue-chain-items!` `:24-31`):
- `delete-after-focus-moves-to-a-toolbar-button-test` (p4; fails today)
- `select-all-after-clicking-a-row-checkbox-test`
- `list-shortcuts-do-not-act-while-renaming-test`
- `delete-after-clicking-a-row-still-works-test` (p3 as a regression)

`laser-show.ui-test.toolbar-test` (extend):
- `space-plays-after-clicking-tap-test` (p8)
- `space-plays-after-clicking-a-tab-or-the-trigger-mode-combo-test` (p19)

`laser-show.ui-test.keys-test` (NEW; needs the toolkit because it builds controls):
- `typing-target-detects-text-inputs-test`
- `focus-within-is-true-for-descendants-test`

`laser-show.ui-test.projectors-layout-test` (extend; its fixtures already set up projectors):
- `projector-chain-delete-after-clicking-its-copy-button-test`

These must stay green unchanged:
- `clipboard_test.clj`, whose list shortcut tests start at `:90`;
- `step_grid_test.clj:250-285`;
- `rig_strip_test.clj:55`;
- `menu_bar_test.clj:56`;
- `timeline_editor_test.clj:55`.

Run `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.cue-chain-list-test`, then `-n laser-show.ui-test.keys-test -n laser-show.ui-test.toolbar-test`, then both full suites.

## 9. Out of scope / alternatives considered

- **A focus context in app state** (the old plan): D5 option C.
- **A `:command/*` router and shortcut registry.** Not needed for correctness: the focused component already wins, and the Edit menu already routes by active tab and selected cell (`menu.clj:283-345`). Revisit only if user-configurable shortcuts become a goal.
- **Moving Ctrl/Cmd combos out of `MenuItem` accelerators.** They work (p1), and text fields consume their own (p2).
- **Atomic list operations** (review 02 M5; D7 option C). A larger refactor of `handlers/list.clj`; schedule separately. Phase 4 has already moved one prop, the clipboard type, from the props to the list's id.
- **Making the rig strip's Scan and Add Manual buttons (`rig_strip.clj:122`, `:128`) mouse-only.** They are on a setup tab, where Tab navigation is worth more than a global Space. Left as they are.
- **`list-ordered-ids` re-evaluates on every context change for non-`:chains` lists.** Its `:else` branch reads the whole state (`subs.clj:868-869`). This is review 01 F17 / M6, not keyboard work. Noted because this phase touches the same props atom.

---

## Decisions

> **DECISION D5 — Focus model** *(answered A, 2026-11-22: the ★ option, taken under the user's standing choice)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Let JavaFX's event flow route keys. The focused component handles its keys (list: `focusWithin` + typing guard); everything unconsumed reaches the scene. No new state | Smallest change that fixes the verified defect; nothing to keep in sync; works the same in every window; the step grid, the rig strip and the canvases added since `cb32414` already work this way | Components must each use the shared `typing-target?` guard |
> | B | Keep `.isFocused` and stop child controls taking focus (`:focus-traversable false` on the list's buttons and checkboxes) | One-line changes | Fragile: any new focusable child breaks the list again; loses Tab navigation |
> | C | The old plan: `[:ui :focus-context]` in app state, `:command/*` router, shortcut registry; components dispatch a context event on every click | Menu items could enable or disable by context; one place lists every shortcut | An event per click; stale-context bugs across 12 windows; a registry and router to build and keep in step; solves problems the probes did not find |
>
> **Your answer:** A (2026-11-22; the recommended ★ option, under the user's standing choice to take the recommended options. Re-checked at `2227bce`: still fits, see §0)

> **DECISION D6 — Main-window buttons and the single-key shortcuts** *(answered A, 2026-11-22: the ★ option, taken under the user's standing choice)*
>
> Space, Escape, R, Delete and F11 are `MenuItem` accelerators (`title_bar.clj:144`, `:154`, `:162`, `:168`, `:182`). They fire unless a focused control consumes the key, and buttons and combos do. After you click TAP, a tab or the trigger-mode combo, Space no longer toggles play (p8, p19).
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Make the main window's toolbar controls (except the BPM field) and the tab buttons not focus-traversable, so they are mouse-only, like the timeline's and the step grid's toolbars and the pads | Space/Esc/R/Delete stay global; tiny change | No Tab-key navigation across the toolbar and tab bar |
> | B | Leave them focusable | No change | The shortcut works until you click a button |
> | C | A, and also move the single-key accelerators into one registry-driven scene handler | One list of shortcuts; they could be shown in a help overlay | Not needed to make them work. Keyboard triggers (Phase 5) override them without it |
>
> **Your answer:** A (2026-11-22; the recommended ★ option, under the user's standing choice. Scope as re-checked: the toolbar's buttons and its trigger-mode combo, which p19 adds, and the tab buttons)

> **DECISION D7 — How do list keyboard commands get their props (items path, change event)?** *(answered A, 2026-11-22: the ★ option, taken under the user's standing choice)*
>
> Today a global atom of the latest props (`list.clj:60-80`) is written by two components: `list-sidebar` (`:619`, with `:ordered-ids`) and `list-editor` (`:887-888`, without). It is never cleaned for closed dialogs, and both the keyboard filter (`:768`) and the click handler (`:338`) read it. The clipboard type it was going to carry is now derived from the list's id (`state/clipboard.clj:89-99`).
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Keep the atom, make it correct: one writer (`list-sidebar`), which always includes `:ordered-ids`; the entry is removed when the list editor's node is deleted | Smallest, safe | Still a mutable side channel |
> | B | Store the props on the node (`getProperties`), refreshed each render | No global atom | Needs a per-render refresh hook; the same stale-closure risk (`docs/STALE_CLOSURES.md`) |
> | C | A pure registry keyed by `component-id` (review 02 M5): events carry only the id, and handlers derive the items path and change event, as they already derive the clipboard type | Removes the side channel, the two-step dispatch and stale reads | A larger refactor across `handlers/list.clj`, `chain/core.clj`, `cue_chain.clj`; best done as its own plan |
>
> **Your answer:** A (2026-11-22; the recommended ★ option, under the user's standing choice. Re-checked: A no longer needs to add `:clipboard-type`, which Phase 4 took from the props)
