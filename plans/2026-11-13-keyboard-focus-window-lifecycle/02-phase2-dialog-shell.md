# Phase 2 — One dialog shell

**Written:** 2026-11-13   **Written for commit:** `cb32414` (`master`)   **Re-checked:** 2026-11-22 at `2227bce`, 275 commits later: every citation re-checked; five dialogs added since (the confirm dialog, the unsaved-changes confirm, the rig report, the zone pattern editor and the step grid window); the parts other work has done marked (§0)
**Status:** In progress on `keyboard-phase2` from `c3de6b6` (2026-11-23). D4 answered A, with the menu-stub table (§9). §§0–9 describe the re-check at `2227bce`; the content sizing for Add Projector and the trigger editor from `04c0ffd` is kept.
**Size:** ~2 days (was ~1.5: eleven dialogs instead of six, three of them modal questions, against two defects and one duplicate already gone)   **Needs:** nothing open   **Depends on:** nothing. Phase 3 can go first and give the shell `keys/typing-target?` (03 §0)
**Supersedes / Superseded by:** none   **Related findings:** K-12, K-14 (`00-overview.md` §4); review 01 F18, M5, F19 (dialog parts), Q9; the shared scene filter the 2026-10-07 plan wanted in every dialog

Eleven dialogs repeat the same stage skeleton. Five repeat the same undo/redo key handler, and each decides on its own how it closes and where its stylesheets come from. This phase extracts the shell once, so Phase 5 (keyboard triggers) can install its filter in one place instead of eleven.

## 0. What other work has done since `cb32414`

| Item in the 2026-11-13 plan | State at `2227bce` |
|---|---|
| `cell-label` helper | **Done.** `views/dialogs/common.clj:4-8` (`92c191a`, 2026-11-19), tested in `test/laser_show/views/dialogs/common_test.clj:5-9`. Used by the trigger editor (`cell_trigger_editor.clj:39`, `:80`), the zone pattern and step grid windows (`dialogs/zone_pattern_editor.clj:22`, `step_grid_editor.clj:28`), `tabs/vibes.clj:252`, `status_bar.clj:99` and `events/handlers/zone_groups.clj:13`, `:103`. The cue, effect and timeline editors still spell the formula out |
| Add Projector never closes after Add | **Done** in the output drivers work. `handle-projectors-add-manual` (`projector.clj:312-350`) ends in `added` (`:302-310`), which sets `[:ui :dialogs :add-projector-manual]` to `{:open? false}`, closing the dialog and clearing the form. Tests: `projector_handlers_test.clj:273` `add-manual-closes-and-clears-its-dialog-test`, `setup_dialogs_test.clj:58` `add-projector-closes-after-add-test`. Left: Cancel or the window's close keeps what was typed for the next open (§1) |
| `destination-zone-dropdown` duplicate | **Gone.** The Target chip (`target_control/target-field`, `2a48f48`) replaced it in both editors (`cue_chain_editor.clj:453-461`, `timeline_cue_editor.clj:123-131`) |
| `trigger-mode-dropdown` labels differ | Both now say "Trigger:" (`cue_chain_editor.clj:177`, `timeline_cue_editor.clj:42`); the duplicate itself remains |
| Shared stage, key handler, registry, `window-title`, `undo-redo-event` | **Not built.** Five dialogs added since copied the skeleton again, and two of them the key handler |

## 1. Current behaviour (file:line)

All eleven dialogs are stages in `views/dialogs/`. `root.clj` mounts each one behind its own `dialog-open?` sub (`root.clj:184-194`) and its own `when` block inside `fx/ext-many` (`:223-266`), in a fixed order. The order matters once: the confirm dialog is last so it shows on top of the rig report (`:263`). No dialog sets an owner.

**Editor windows** (`:modality :none`):

| Concern | cue chain | effect chain | timeline | zone group | trigger | add projector | zone pattern (Z5) | step grid (Z6) |
|---|---|---|---|---|---|---|---|---|
| Stage skeleton (`:showing :title :modality :on-close-request`) | `cue_chain_editor.clj:547-553` | `effect_chain_editor.clj:247-251` | `timeline_cue_editor.clj:203-207` | `zone_group_editor.clj:182-186` | `cell_trigger_editor.clj:78-84` | `add_projector_manual.clj:196-200` | `dialogs/zone_pattern_editor.clj:96-102` | `step_grid_editor.clj:133-139` |
| `(css/dialog-stylesheet-urls)` called directly | `:541` | `:232` | `:197` | `:181` | `:77` | `:195` | `:104` | `:141` |
| Scene key handler (undo/redo) | `:507-520`, on `:528` | `:197-210`, on `:218` | `:162-181`, on `:187`, + Space (`:170-173`) | none | none | none | `:75-88`, on `:105` | `:108-122`, on `:142` |
| Cell letter | `(char (+ 65 …))` `:327`, `:542` | same, `:102`, `:234` | same, `:80`, `:198` | – | `common/cell-label` | – | `common/cell-label` | `common/cell-label` |
| Size | content 1120×760 (`:332-333`), stage min 960×640 (`:551-552`) | content 800×550 (`:107-108`) | content 1280×760, min 960×560 (`:85-88`) | content width 400 (`:104`) | stage 440×434 (`:81-82`) | content width 400 (`:116`); the window follows its content (`fit-window-to-content!` `:85-100`, on `:183-185`) | content 760×620, stage min 640×480 | content 940×600, stage min 720×460 |
| Opened by | bespoke `:cue-chain/open-editor` (`cue_chain.clj:40-58`) | bespoke `:effect-chain/open-editor` (`effects.clj:114-127`) | `:timeline-cue-editor/open` → `:ui/open-dialog` (`timeline.clj:439-463`) | bespoke `:zone-groups/add` (`zone_groups.clj:35-45`), `:zone-groups/edit` (`:238-252`) | `:ui/open-dialog` direct (`cue_chain_editor.clj:477-481`, `effect_chain_editor.clj:178-182`, `grid_cell.clj:369-376`, `:524-530`) | `:ui/open-dialog` direct, no `:data` (`rig_strip.clj:128-133`) | `:ui/open-dialog` direct (`components/zone_pattern_editor.clj:556`) | bespoke `:step-grid/open` (`step_grid.clj:157-180` in `events/handlers/`) |
| Default in `domains.clj:166-189` | yes `:167-172` | yes `:173-176` | **no** | yes `:179-184` | **no** | yes `:177` (`{:open? false}` only) | **no** | **no** |

**Question dialogs** (`:modality :application-modal`, `:resizable false`, new since `cb32414`). These have no key handler: Enter and Escape work through JavaFX's `:default-button` and `:cancel-button`.

| Concern | confirm (`confirm.clj`) | unsaved changes (`unsaved_confirm.clj`) | rig report (`rig_report.clj`) |
|---|---|---|---|
| Stage | `:94-99`, title from the data or "Confirm" | `:120-125`, "Unsaved changes" or "Rig not saved"; close request → `:file/confirm-cancel` | `:249-254`, "Set up rig" or "<file> on this rig" |
| Stylesheets | `:101` | `:127` | `:256` |
| Default / cancel buttons | `#confirm-ok` `:84` / `#confirm-cancel` `:78` | Save `:110` / Cancel `:99`; rig variant Try again `:65` / Cancel `:54` | `#rig-setup-button` `:77` / `#rig-setup-not-now` `:71`; report `#rig-report-close`, both (`:236-237`) |
| Opened by | the map written whole by `ask` (`safety_guard.clj:44`) and by `zone_groups.clj:183`, `rig.clj:262`, `:295`, `zone_maps.clj:185`, `projector.clj:592` | `ask-first` / `ask-about-the-rig` (`menu.clj:46-66`) | `menu.clj:129-135` (project load), `:rig/open-report` (`rig.clj:198-202`) |
| Default in `domains.clj` | yes `:187` | yes `:185-186` | **no** |

Five dialogs have no default: `:timeline-cue-editor`, `:trigger-editor`, `:zone-pattern-editor`, `:step-grid-editor` and `:rig-report`. Three defaults have no dialog: `:projector-config` (`:166`), `:add-virtual-projector` (`:178`) and `:settings` (`:189`). A project load resets `[:ui :dialogs]` to the defaults (`persistent.clj:198`).

**Stylesheets.** The main window takes its stylesheets from `subs/stylesheet-urls` (`root.clj:183`, `subs.clj:1632-1647`), which follows `[:styles :reload-trigger]`. Every dialog calls `css/dialog-stylesheet-urls` (`css/core.clj:61-85`), a plain function. Today it lists the same 14 sheets as `css/all-stylesheet-urls` (`:37-59`), but it is not tied to the trigger, so Reload Styles (dev mode, `title_bar.clj:223`) doesn't re-apply a dialog's stylesheets until something else re-renders it.

**Other duplicated code:**
- `trigger-mode-options` (`cue_chain_editor.clj:150-153`, `timeline_cue_editor.clj:29-31`).
- `trigger-mode-dropdown` (`:155-196`, `:33-55`). The cue copy adds `:min-width :use-pref-size` and a tooltip.
- The window-title tail `(if (seq chain-name) (str chain-name " (" cell-id ")") cell-id)` appears three times: cue `:543-546`, effect `:235-238`, timeline `:199-202`. The two windows added since have their own `window-title` fns in another format ("Step Grid — Cue A1 \"Beams\" · Zone pattern", `step_grid_editor.clj:23-30`; "Zone Pattern — Cue A1 \"name\"", `dialogs/zone_pattern_editor.clj:18-23`). Tests pin both formats.
- The grid's row header (`grid_tab.clj:188`), `vibes/library.clj:430`, `:466`, `events/handlers/input.clj:331` and `subs.clj:2007` spell the same letter formula out.

**Defects found while reading:**
- **Add Projector keeps the typed form after Cancel or close.** Add clears it (§0). Cancel and the window's close only set `:open? false` (`ui.clj:44-47`), and the rig strip's opener passes no `:data`, which `:ui/open-dialog` merges (`ui.clj:41`).
- **`:editing-name?` is never reset on open.** Only the `set-name` handlers clear it (`cue_chain.clj:192`, which the timeline reuses with `:dialog-id :timeline-cue-editor`, `timeline_cue_editor.clj:106`; `effects.clj:136`). None of the open paths sets it (`cue_chain.clj:48-55`, `effects.clj:119-124`, `timeline.clj:461-463`). So a dialog closed mid-rename reopens in rename mode.
- **Help > About does nothing.** `handle-help-about` sets `[:ui :dialogs :about :open?]` (`menu.clj:378-382`), and no view mounts an About dialog.
- A comment at `effect_chain_editor.clj:194-195` points at `laser-show.css.dialogs`, which doesn't exist.
- `events/handlers/zone_groups.clj:13` requires `laser-show.views.dialogs.common` for `cell-label`: an events namespace depending on a view namespace. It is harmless today and noted here; see §9.

**Tests that constrain a refactor.** Windows are found by title prefix or by node id, so titles and ids must not change:

| Window | Tests |
|---|---|
| Cue Chain Editor | `"Cue Chain Editor"` in `cue_chain_list_test.clj:20`, `:37`, `main_window_test.clj:181`, `:203`, `:237`, `setup_dialogs_test.clj:359`, `clipboard_test.clj:63` |
| Timeline Editor | `"Timeline Editor"` in `timeline_editor_test.clj:36`, `:60`, `:79`, `:129`, `timeline_visual_test.clj:58` |
| Effects Chain | `"Effects Chain"` in `clipboard_test.clj:278` |
| Trigger editor | `"Triggers — Cue Cell A1"` (`trigger_editor_test.clj:66`) and `#trigger-editor-done` (`:61-85`) |
| Step grid and zone pattern | the step grid and zone pattern titles (`step_grid_test.clj:37`, `:55`, `:178`; `zone_patterns_test.clj:67`) |
| Zone group | "New/Edit Zone Group" (`main_window_test.clj:285-293`, `setup_dialogs_test.clj:83-99`) |
| Add Projector | "Add Projector" (`setup_dialogs_test.clj:43-72`, `projector_drivers_test.clj:70`, `:103`, `addons_settings_test.clj:307`) |
| Question dialogs | `#confirm-*` (`safety_editor_test.clj:135`, `:145`, `zones_test.clj:493`, `:499`, `setup_dialogs_test.clj:212`, `:297`, `rig_report_test.clj:240`), `#unsaved-*` (`main_window_test.clj:97-145`, `rig_report_test.clj:215-218`), `#rig-setup-*` / `#rig-report-*` (`rig_report_test.clj:146-177`) |

## 2. Root cause

Each dialog was written by copying the previous one. `views/dialogs/common.clj` holds one helper, `cell-label`, and nothing else is shared. So every cross-cutting concern (key handling, close behaviour, stylesheets, defaults) is implemented per dialog or not at all, and each new dialog (five since `cb32414`) copies it again.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `views/dialogs/common.clj` (`cell-label`, `:4-8`) and its test | The home of the new helpers and of `dialog-stage`. |
| `views/components/inline_edit.clj` (`inline-edit-text`) | The name header, unchanged. |
| `subs/dialog-open?` (`subs.clj:519-524`), `subs/dialog-data` (`:526-533`), `subs/stylesheet-urls` (`:1632-1647`, used by the main window at `root.clj:183`) | The shell's `:showing` and stylesheets. One stylesheet source for every window, tied to Reload Styles. |
| `:ui/open-dialog`, `:ui/close-dialog`, `:ui/update-dialog-data` (`handlers/ui.clj:30-70`, table `:192-194`) | The one open/close path. |
| The Space + `TextInputControl` guard in `timeline_cue_editor.clj:170-173` | The pattern for a dialog's own key extras, through `keys/typing-target?` (Phase 3) once that exists. |
| `ui/window-titles` (`ui_test/testfx.clj:91-99`), `ui/wait-for!` | The dialog smoke test. |

## 4. Changes per namespace

- `laser-show.views.dialogs.common` (existing, `common.clj:1-8`). Signatures only:
  - `cell-label` stays. NEW `default-name [col row]` → `"Cell A1"`.
  - NEW `window-title [prefix chain-name col row]` → `"Cue Chain Editor - Intro (Cell A1)"`, or `"Cue Chain Editor - Cell A1"` without a name. For the three editors that use this format; the zone pattern and step grid windows keep their own (§1).
  - NEW `undo-redo-event [key-data]` → `:edit/undo`, `:edit/redo` or nil. Pure; `key-data` is `{:code :z :shortcut? bool :shift? bool}`.
  - NEW `scene-key-handler [extra-fn]` → the `:on-key-pressed` fn: undo/redo first, then `extra-fn` (the timeline's Space).
  - `trigger-mode-options`, `trigger-mode-dropdown`: moved, keeping the cue copy's tooltip and `:min-width` (both labels are "Trigger:" already).
  - NEW `dialog-stage` (D4 A). Required props are `:dialog-id`, `:title` and `:content`. Optional props:
    - `:modality` (default `:none`) and `:resizable`;
    - `:width :height :min-width :min-height`;
    - `:on-close-request`, which defaults to `{:event/type :ui/close-dialog :dialog-id id}` (the unsaved confirm passes `:file/confirm-cancel`);
    - `:undo-keys?` (default true for `:none`, false for the modal questions);
    - `:on-key-extra`.

    It renders the `:stage` with `:showing` from `dialog-open?` and a scene that takes its stylesheets from `subs/stylesheet-urls` and installs `scene-key-handler` when `:undo-keys?` is set. It is the one place Phase 5 adds its key filter.
- The eleven dialog namespaces use the shell and helpers and delete their local copies. Sizes, content, titles and node ids stay per dialog. The add projector keeps `fit-window-to-content!` inside its `:content`, as today (`add_projector_manual.clj:180-185`).
- `laser-show.views.root` (`:184-194`, `:223-266`): replace the eleven subs and `when` blocks with one registry. It must be an ordered vector of `[dialog-id component]`, not a map, so the confirm dialog stays last (D4 A). Render it with a loop over the open ones, `:fx/key` = the dialog id.
- `laser-show.events.handlers.cue-chain` (`cue_chain.clj:40-58`), `effects` (`effects.clj:114-127`), `timeline` (`timeline.clj:439-463`): the open handlers set `:editing-name? false`.
- `laser-show.views.components.rig-strip` (`rig_strip.clj:128-133`): Add Manual opens with `:data {:driver nil :name nil :host nil :port nil :driver-config nil}`, so a cancelled form doesn't come back.
- `laser-show.state.domains` (`domains.clj:166-189`):
  - add defaults for `:timeline-cue-editor`, `:trigger-editor`, `:zone-pattern-editor`, `:step-grid-editor` and `:rig-report`;
  - add the add-projector form keys;
  - drop `:projector-config`, `:add-virtual-projector` and `:settings`, which nothing mounts. Grep first: nothing in `src/` or `test/` reads them at `2227bce`.
- Menu stubs, per the table in §9 (D4 A):
  - `laser-show.views.dialogs.about` (NEW, mounted through the shell);
  - `title_bar.clj:91-94` and `:202-204` go;
  - `root.clj:133` also reads `[:ui :preview-visible?]`;
  - NEW `:window/set-fullscreen` effect for `:view/fullscreen` (`menu.clj:358-363`).
- Cleanup: the stale comment at `effect_chain_editor.clj:194-195`.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:ui :dialogs <id> …]` (existing; five new defaults, the add-projector form keys, three dead defaults removed, `:about` used) | app state, UI | `:ui/*` handlers, the bespoke open handlers, `:projectors/add-manual` | No / no (not a document root) |
| `[:ui :preview-visible?]`, `[:ui :fullscreen?]` (existing, written by `menu.clj:351-363`, read by nothing today) | app state, UI | the View menu handlers | No / no |

No runtime atoms and no new threads. One new effect, `:window/set-fullscreen` (§6).

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:ui/open-dialog`, `:ui/close-dialog`, `:ui/update-dialog-data` | yes (`handlers/ui.clj:30-70`, table `:192-194`) | shell, buttons, callers listed in §1 | yes |
| `:ui/confirm` | yes (`ui.clj:81-93`) | `#confirm-ok` | yes |
| `:edit/undo`, `:edit/redo` | yes (`menu.clj:271-281`, table `:462-463`) | the shared key handler | yes |
| `:help/about` | yes (`menu.clj:378-382`, table `:474`) | Help > About; now mounts the About dialog | yes |
| `:view/fullscreen` | yes (`menu.clj:358-363`, table `:470`) | View > Fullscreen (F11) | yes; now also returns `:window/set-fullscreen` |
| `:window/set-fullscreen` | **NEW** effect in `events/core.clj`, beside `:fx/show-file-chooser` (`:486`, owner from `get-primary-stage` `:475-484`) | `:view/fullscreen` | effect (FX thread) |

## 7. Data examples and invariants

| Call | Result |
|---|---|
| `(cell-label 7 3)` | `"D8"` (exists) |
| `(default-name nil nil)` | `"Cell A1"` |
| `(window-title "Cue Chain Editor" "Intro" 0 0)` | `"Cue Chain Editor - Intro (Cell A1)"` |
| `(window-title "Cue Chain Editor" nil 0 0)` | `"Cue Chain Editor - Cell A1"` |
| `(undo-redo-event {:code :z :shortcut? true})` | `:edit/undo` |
| `(undo-redo-event {:code :z :shortcut? true :shift? true})` | `:edit/redo` |
| `(undo-redo-event {:code :y :shortcut? true})` | `:edit/redo` |
| `(undo-redo-event {:code :z})` | `nil`. It never fires without the shortcut modifier, so typing `z` in a field is untouched |

Registry order (as `root.clj:223-266` today): effect chain, cue chain, timeline, zone pattern, step grid, add projector, zone group, trigger, unsaved confirm, rig report, confirm, then About.

Invariants:
1. Every dialog id in the root registry has a default entry in `domains.clj`, and every `:dialogs` default has a dialog in the registry.
2. Showing or closing a window is UI-only. An editor command that deliberately edits the document before showing its window keeps its own undo behavior (converting a cue to a timeline; creating or converting a step grid).
3. Window titles and the node ids listed in §1 are unchanged.
4. The shared handler consumes a key only when it matched an undo/redo chord (or the dialog's own extra). The question dialogs keep Enter and Escape on their default and cancel buttons.
5. Every dialog's stylesheets come from `subs/stylesheet-urls`.

## 8. Tests (named test vars + fixtures)

- `laser-show.views.dialogs.common-test` (extend; `cell-label-test` exists): `default-name-test`, `window-title-test`, `undo-redo-event-test`.
- `laser-show.events.ui-handlers-test` (extend): `every-mounted-dialog-has-a-default-test`, `every-dialog-default-is-mounted-test`, `open-dialog-clears-editing-name-test`.
- `laser-show.events.menu-handlers-test` (extend): `fullscreen-returns-the-window-effect-test`.
- `laser-show.ui-test.setup-dialogs-test` (extend; `add-projector-closes-after-add-test` exists at `:58`): `add-projector-cancel-clears-the-form-test`.
- `laser-show.ui-test.dialogs-test` (NEW; `with-toolkit` once, `with-app` each):
  - `each-dialog-opens-with-its-expected-title-test` (walks the registry through each dialog's open event);
  - `undo-shortcut-works-in-every-editor-dialog-test` (edit, then Shortcut+Z, in all five that have it today);
  - `timeline-space-still-toggles-play-test` (the existing behaviour, now through the shell);
  - `question-dialogs-keep-enter-and-escape-test`;
  - `help-about-opens-the-about-dialog-test`;
  - `reload-styles-reaches-an-open-dialog-test`.
- These must stay green unchanged: `cue_chain_list_test`, `timeline_editor_test`, `trigger_editor_test`, `step_grid_test`, `zone_patterns_test`, `setup_dialogs_test`, `rig_report_test`, `main_window_test` and `safety_editor_test`.

Run `clj -M:mac-arm:test -n laser-show.views.dialogs.common-test -n laser-show.events.ui-handlers-test -n laser-show.events.menu-handlers-test`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.dialogs-test -n laser-show.ui-test.setup-dialogs-test`, then both full suites.

## 9. Out of scope / alternatives considered

- **A `defdialog` macro.** Rejected: it would hide which props and subs a dialog depends on.
- **Moving dialog CSS** (review 01 L1). Separate, large, low-risk. `css/dialog-stylesheet-urls` can go once no dialog calls it.
- **Resetting all dialog state on open.** Rejected:
  - `:timeline-cue-editor` keeps per-effect UI modes in `[:ui :dialogs :timeline-cue-editor :effect-ui …]` (`chain/helpers.clj:46-47`), and the zone pattern editor keeps its own there (`:48`). Dropping them on every open would be a visible regression.
  - The show clock reads the cue and timeline editors' `:open? :col :row` (`services/show_clock.clj:111`).
- **Moving `cell-label` out of `views/`**, so `events/handlers/zone_groups.clj` stops requiring a view namespace. A one-line move to a shared util; do it in passing if the namespace is touched anyway.
- **Unifying the two window-title formats.** Tests pin both; not worth the churn.
- **Adjacent menu stubs (K-14).** Accepted with D4 A on 2026-11-22. These items do nothing today:

| Menu item | Today | Change (accepted with D4 A) |
|---|---|---|
| Help > About | flag set (`menu.clj:378-382`), nothing mounted | mount a small About dialog on the new shell |
| File > Export… | no-op (`menu.clj:206-211`; the item at `title_bar.clj:91-94` is disabled without a project) | remove the item until an exporter exists |
| View > Toggle Preview (Shortcut+P) | flips `[:ui :preview-visible?]` (`menu.clj:351-356`), nothing reads it | `root.clj`'s `show-preview?` (`:133`) also requires it |
| View > Fullscreen (F11) | flips `[:ui :fullscreen?]` (`:358-363`), nothing reads it | a small effect that calls `Stage.setFullScreen` on the main stage |
| Help > Check for Updates | no-op (`:384-389`) | remove the item until releases carry binaries |

## 10. As built on `keyboard-phase2` (from `c3de6b6`)

- `views.root/dialog-registry` mounts open windows in order, keyed by dialog id. The confirm window stays above the rig report. Components are looked up through vars so an individually reloaded view is used on its next render (`772c6da`, `9b6e935`).
- `views.dialogs.common/dialog-stage` subscribes to the same stylesheets as the main window. All eight non-modal editors now use its undo/redo handler; modal windows leave Enter and Escape to their own buttons. Timeline Space is the only extra scene shortcut and uses `views.keys/typing-target?`. The existing titles and trigger-mode control are shared by the cue and timeline editors; the effect editor shares the title formatter, and step-grid and zone-pattern titles retain their own formats. The coordinate label moved into `common.util` so event handlers no longer require the shell (`61deae2`, `6c01171`, `0ab7ade`).
- Add Projector and the trigger editor keep the content-driven sizing from `04c0ffd`. The three modal questions keep their default/cancel buttons, and closing the unsaved-changes window still cancels the pending operation.
- The new closed default for the rig report exposed two assumptions. The report view calculated mismatches on a fresh document, and `:rig/open-report` could open it without a loaded file. The view and handler now check the filename recorded by `:file/project-loaded`; a loaded show can still reopen its report after closing it. Before the fix, a fresh cue pinned to a missing projector produced a warning count of 1; the bare open event changed `:open?` to true (`19078cb`).
- Help > About opens a small modal window. View > Show Preview is a check item (Shortcut+P); the tabs that never have a preview still don't. View > Fullscreen (F11) changes the main stage, and its check follows the stage even if the OS changes it. JavaFX's fullscreen exit key is disabled on that stage: Escape keeps its existing application meaning; F11 exits fullscreen. File > Export and Help > Check for Updates and their no-op handlers were removed (`8154313`, `c1defc8`, `446f2a6`).
- Baseline checks on `c3de6b6`: the Add Projector, trigger and zone group editors ignored Undo from their buttons (the other five editors handled it); the timeline's scene handled Space from the readout but left a typed space in the name field. About had no window, and only the main window took a changed stylesheet. The new two-modal regression also passed before the refactor: Escape cancels the confirmation above the rig report without closing the report.

---

## Decisions

> **DECISION D4 — How much of the shell is extracted?** *(answered A, 2026-11-22: the ★ option, taken under the user's standing choice)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | `common.clj` helpers **plus** a `dialog-stage` component **plus** a root registry (one ordered loop instead of eleven `when` blocks); the `:editing-name?`, add-projector form and defaults fixes | Every dialog gets the shared key handler, the reloadable stylesheets and Phase 5's filter by construction; the twelfth dialog (About) is a few lines; five dialogs were added since `cb32414` without any of it | A larger diff in eleven files; the root registry is a new indirection |
> | B | Helpers only (`window-title`, `undo-redo-event`, the dropdowns). Each dialog keeps its own stage and its own `when` block in `root.clj` | Smaller, easier to review | The shared key handler has to be wired into eight scenes by hand, Phase 5's filter into eleven, and a new dialog can forget either |
> | C | Fix only the bugs (`:editing-name?`, the add-projector form after Cancel, the missing defaults) | Smallest | Keeps the duplication this phase exists to remove |
>
> Menu stubs: the table in §9.
>
> **Your answer:** A, with the §9 menu-stub table as written (2026-11-22; the recommended ★ option, under the user's standing choice to take the recommended options. Re-checked at `2227bce`: still fits. The shell takes `:modality`, `:resizable`, size and `:on-close-request` props for the three question dialogs, and the registry is ordered)
