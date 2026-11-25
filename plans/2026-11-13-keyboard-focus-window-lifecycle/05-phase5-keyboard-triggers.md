# Phase 5 — Keyboard as a cue-trigger source

**Written:** 2026-11-13   **Written for commit:** `cb32414` (`master`)   **Re-checked:** 2026-11-22 at `2227bce`, 275 commits later: every citation re-checked; K-15 found fixed by other work; the Settings placeholder found gone; the trigger actions added by vibes and zones (Z4, Z5), the new windows and their keys taken in (§0)
**Status:** Done and on `master` (`79bd080`); D10–D12 answered A. **Last verified against:** `79bd080`, with both full regression suites passing (§10). The manual macOS window-focus and held-key checks in §8 remain open.
**Size:** ~1–1.5 days   **Needs:** nothing open   **Depends on:** Phase 3 (`views.keys`), Phase 2 (the shell)
**Supersedes / Superseded by:** none (replaces the "keyboard trigger source" amendment in `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md`)   **Related findings:** K-13, K-15 (`00-overview.md` §4); review 05 F17, L3, F9 (learn also fires the old binding: fixed)

The README promises keyboard input (`README.md:3`, `:52`), the trigger editor already shows keyboard bindings (`trigger_bank.clj:46`), and every stage after the input router is source-agnostic. Only the producer is missing: nothing turns a JavaFX key press into a router event. About 1–1.5 days, mostly design.

## 0. What changed since `cb32414`

- **After Phase 2 (`90dbe87`):** the main window and the eight non-modal editors are still D11 A's proposed scope. All eight editors now use `views.dialogs.common/dialog-stage`. The three modal questions **and the new modal About** use the same shell; Phase 5 should not install a trigger filter in any of the four. The timeline's additional shortcut is `handle-space` in `views/dialogs/timeline_cue_editor.clj`.

- **K-15 is fixed.** Learning a key no longer also fires its old binding. The fix is in the mapper, as this doc proposed: `learning?` (`input_mapper.clj:59-66`) reads `[:backend :input :learning-target]`, and presses aren't dispatched as `:trigger/input` while a learn waits; releases still are (`:105-106`). It landed in `38f92ad` (vibes tab, 2026-11-19), with `mapper-ignores-discrete-events-while-learning-test` (`test/laser_show/events/handlers/input_mapper_test.clj:30-53`), the test §8 used to plan. The mapper change and that test are gone from §4 and §8; invariant 5 stays, now as a regression test.
- **A key can bind far more than a cell.** These action types exist now (`triggers.clj:89-138`), and all of them are source-agnostic:
  - vibe roles (`:vibe`, press and release; vibe Phases 1–8);
  - `:vibe-select`;
  - zone map pads (`:zone-map`, Z4);
  - pattern pads (`:pattern-pad`, Z5);
  - the Zone step key (`:zone-step`, Z5).

  Once keys reach the router, the laptop keyboard can play the Perform tab's five vibe keys, and the learn buttons all take a key without change:
  - the vibe pads (`vibe_pads.clj:91-92`);
  - the map pads (`zone_map_pads.clj:107`, `:215`);
  - the pattern pads and STEP (`pattern_pads.clj:86-87`);
  - Learn all (`events/handlers/vibes.clj:197`);
  - the Perform tab's "Press the key for …" (`perform.clj:145`).
- **Trigger definitions are matched before the trigger map** (`definition-action`, `triggers.clj:33-41`, used first at `:163-164`). So "is this key bound?" must ask both, through one NEW function the handler also uses (§4).
- **The Settings placeholder is gone** (`b9b9d55`), and `settings_test.clj:50-55` (`keyboard-card-is-gone-test`) checks that it stays gone. The Keyboard card is NEW, and that test changes (§8).
- **`binding-label` is shared** by five views: `trigger_bank.clj:117`, `vibe_pads.clj:137`, `zone_map_pads.clj:84`, `pattern_pads.clj:47` and `vibe_controls.clj:41`. So the key label changes everywhere at once.
- **The main window's focus change is taken.** `:on-focused-changed {:event/type :vibe/window-focus}` (`root.clj:212`) ends pad holds when the window loses focus (`events/handlers/vibes.clj:81-88`: "A key's hold stays: its release still comes"). That is true for MIDI. For the keyboard it is true only if the producer sends the releases itself, which §4 does. Its listener is added beside that prop, not in place of it.
- **More windows and more keys.** There are now eight editor windows and three question dialogs (02 §1). The new windows and components that use single keys are:
  - the step grid window's body (arrows, Space, Enter, Delete, Home/End; `step_grid.clj:41-68`);
  - the rig strip (←/→; `rig_strip.clj:472-484`);
  - the canvases (arrows).

  The main window adds F11 to the single-key accelerators (`title_bar.clj:182`). This is why D11 is re-opened.
- **Escape stops the show from a text field** (p2, 2026-11-22). In the main window `#bpm-input` doesn't consume Escape, so the hidden Stop accelerator fires (`title_bar.clj:158-164`). That settles the audit note's question: Escape already is a panic key there, whatever has focus. A dialog has no menu bar, so Escape stops nothing in a dialog.
- **Design work elsewhere uses the keyboard.** `plans/2026-11-21-live-performance-ux/` (not a plan, nothing built, `README.md:4`) proposes several keys:
  - 1–5 and 0 to queue zone maps (`designs/E-stage-view.md:62`, `:105`, `:121`; `designs/B-now-next.md:31`, `:70`);
  - Space and N for a blind preview (`designs/D-co-pilot.md:37`);
  - a held Shift or ? to show the pads' backs, and Esc to leave Tune (`designs/A-instrument.md:70-72`).

  All of them are bare keys that this phase's producer would carry (D12 A). QLab's unchangeable Esc panic (`research/04-hci-principles-for-live-performance.md:46`) supports D10 A.

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| Producer of keyboard events | grep `router/dispatch!`, `events/trigger`, `:keyboard` in `src/` | **Not found.** `router/dispatch!` is called for the gamepad (`app.clj:262`) and from the MIDI and OSC handlers (`handlers/input.clj:68`, `:132`, `:160`). `events/trigger` is used only by the gamepad and OSC (`input/gamepad.clj:62`, `input/osc.clj:104`, `:115`). No `KeyEvent` in `input/` or `events/`. |
| Event constructor | `input/events.clj:58-68` | `(trigger :keyboard :space :pressed)` → `{:type :trigger :source :keyboard :id :space :state :pressed :timestamp ms}`; `:released` gives `:type :trigger-release`. `keyboard-event?` exists (`:126-129`). |
| Router | `input/router.clj:103-128` | `dispatch!` runs synchronously on the caller's thread: all global handlers (a map, so in no set order, `:113-118`), then pattern handlers (`:121-127`). A handler can't consume an event. The router has its own on/off (`:109`, `:140-148`). |
| Mapper | `events/handlers/input_mapper.clj:81-112`, registered by `start-mapper!` (`:116-120`); started by `app.clj:240` (`set-dispatch-fn!`) and `:258`, stopped at `:329`; **not** started by `start-test!` (`app.clj:354-398`) | Discrete events become `{:event/type :trigger/input :input-event e :time ts}` (`:107-110`), except presses while a learn waits (`:105-106`). There is no source filter. |
| Resolution | `events/handlers/triggers.clj:17-27` (`lookup-key`), `:29-31` (trigger map), `:33-41` (definitions), `:89-138` (`action-event`), `:161-173` (`handle-input`) | The key is `{:source :keyboard :id :space}`. A press runs the bound action. `:behavior :momentary` runs `:grid/activate-cell` on press and `:grid/release-cell` on release (`:47-61`; `grid.clj:81-93`). A vibe role always gets both (`:73-87`). Unmapped input only updates `[:trigger-runtime …]`; "quiet" types skip that (`:67-71`). |
| Learn | `handlers/input.clj:284-313` (start; the global router handler `::generalized-input-learn` at `:295-303`), `:315-323` (`learn-lookup-key`), `:348-391` (captured), `:414-422` (cancel) | Captures the next `trigger` or `note-on` of **any** source (or a moving control for a fader learn, `fader-catcher` `:268-282`) and binds it through `:input/await-learn` (`events/core.clj:313-322`). One-key targets move their key (`:342-346`). Proven end to end with a synthetic keyboard event by `trigger_editor_test.clj:99-139` (`space-press!` `:43-48`). |
| Learn also fires the old binding (K-15) | – | **Fixed** (§0). |
| Display | `trigger_bank.clj:38-47` | `:keyboard` renders as `"Key " + (:id key)`, e.g. `Key :space`, in all five views that show bindings. |
| Placeholder state | `domains.clj:533-534` | `[:backend :input :keyboard {:enabled? true :attached-components #{}}]`. Nothing reads it. |
| Settings | `views/tabs/settings.clj:20-78` | No keyboard card (removed in `b9b9d55`). Sections: EXTERNAL SYNC (Link), INPUT DEVICES (MIDI `:61`, OSC `:64`), OUTPUT (add-ons `:74`). |
| Key repeat | JavaFX `KeyEvent` (28-ea+11) has `getCharacter getCode getEventType getText` and `isAltDown isControlDown isMetaDown isShiftDown isShortcutDown`; no repeat flag | A held key delivers repeated `KEY_PRESSED` without a release in between. The producer has to remember which keys are down. |
| Built-in single keys, main window | `title_bar.clj:144` (Delete, Clear Cell), `:154` (Space, Play/Stop), `:162` (Escape, hidden Stop, only while playing), `:168` (R, Retrigger, only while playing), `:182` (F11, Fullscreen) | Menu accelerators; they fire unless a focused control consumes the key (03 §1, p2, p8, p19). |
| Components that edit with single keys | step grid body `step_grid.clj:41-77`; timeline canvas `timeline_editor.clj:417-457` (Space, Esc, Delete, arrows); list `list.clj:811`, `:816` (Delete, Escape); rig strip `rig_strip.clj:472-484` (←/→); canvases (arrows, 03 §1); timeline window Space (`timeline_cue_editor.clj:170-173`) | Each consumes its keys on its own focused node (03 §1). |

## 2. Root cause

Keyboard was designed as an input source (the README, the `:keyboard` placeholder, the trigger-bank label), but only the receiving half was built. The producer needs a scene-level key hook in every window that should take triggers. None exists: the main scene has no key filter (`root.clj:220-222`), and each dialog builds its own scene (02 §1).

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `input-events/trigger` (`events.clj:58-68`) | The only event constructor needed. |
| `router/dispatch!`, the mapper (with its learn suppression), `:trigger/input`, `triggers/handle-input`, the trigger map `[:config :input :trigger-map]` (`domains.clj:273-274`) | Unchanged. This is the point of the phase: cells, vibe roles, map pads, pattern pads and the Zone step key all work from keys at once. |
| Learn (`handlers/input.clj:284-422`), the trigger editor (`cell_trigger_editor.clj`, `trigger_bank.clj`) and the pads' learn buttons (§0) | Unchanged. |
| Phase 2 `dialog-stage` (02 §4) | Installs the filter in every editor window, once. |
| Phase 3 `laser-show.views.keys/typing-target?` (03 §4) | The one definition of "the user is typing". |
| `fx/ext-on-instance-lifecycle` with an `addEventFilter KEY_PRESSED`, as `commit_field.clj:76-86` does | How the filter is installed. |
| `handle-gamepad-set-enabled` (`handlers/input.clj:224-228`, table `:500-501`) | Pattern for `:input/keyboard-enable` / `-disable`. |
| The OSC card (`osc_settings.clj:80-96`) | Card style for the Keyboard card, placed after OSC (`settings.clj:64`). |
| UI fixture `with-input-mapper` (`ui_test/perform_test.clj:32-45`; the same in `vibes_test.clj:28-34`, `zones_test.clj:321-327`) | Starts the mapper that `start-test!` doesn't. |
| TestFX: `ui/press!` (`testfx.clj:611-616`), `ui/focus!` (`:248-267`), `ui/wait-for!`, `fixtures/with-app` | Tests. |

## 4. Changes per namespace

- `laser-show.input.keyboard` (NEW, pure, no JavaFX): the whole policy as data in, data out.
  - `key-id [key-code-name]` → keyword: `"SPACE"` → `:space`, `"DIGIT1"` → `:digit1`, `"F5"` → `:f5`.
  - `decide [ctx]` → `{:consume? bool :emit :pressed|:released|nil :cancel-learn? bool}`. `ctx` = `{:key-id :phase :text-target? :claimed? :modifiers :held? :bound? :learning :enabled? :reserved?}` (table in §7). `:learning` is nil, `:press` or `:continuous`, from `[:backend :input :learning-target]`.
  - `label [key-id]` → `"Space"`, `"1"`, `"F5"`.
  - `builtin-shortcut [key-id]` → `{:label "Play/Stop"}` for Space, "Clear Cell" for Delete, "Retrigger" for R and "Fullscreen" for F11, or nil. Used for the override note (D10 A).
- `laser-show.views.key-triggers` (NEW): the JavaFX glue. It is a scene-level key **filter** (first pass, so it sees the key before any control does).
  - On `KEY_PRESSED` / `KEY_RELEASED` it builds `ctx` from four things: the event; the window's held-key set; `keys/claims?` on the focus owner (D11); and `state/get-raw-state` (the enabled flag, `triggers/bound?`, the learning target). It calls `decide`, consumes if told to, and calls `router/dispatch!` with `(events/trigger :keyboard id state)`. For Escape while learning it dispatches `:input/cancel-learn`.
  - A held-key set per window (a view-local atom) swallows repeated presses. A listener on the window's `focusedProperty` releases every held key when focus goes, so a momentary cue or a vibe role can't stick. It sits beside the main stage's `:on-focused-changed` (`root.clj:212`), not in place of it.
  - Installed through `fx/ext-on-instance-lifecycle` around the main scene's root (`root.clj:220-222`) and from Phase 2's `dialog-stage` for the eight `:modality :none` windows. It is not installed in the three question dialogs; they are application-modal, so no other window takes keys while one is open.
- `laser-show.views.keys` (Phase 3's, extended; only if D11 = A): NEW `claim-keys! [node key-ids]` stores the key ids a component edits with on the node (`getProperties`). NEW `claims? [focus-owner key-id]` walks from the focus owner up to the root. Declared at:
  - the step grid body (`step_grid.clj:156`: arrows, Space, Enter, Delete, Backspace, Home, End);
  - the timeline canvas (`timeline_editor.clj:966`: Space, Escape, Delete, Backspace, ←, →);
  - the list editor (`list.clj:889`: Delete, Escape);
  - the rig strip (`rig_strip.clj:583`: ←, →);
  - `canvas_interaction.clj:296-313` (arrows, for a canvas with `on-key`);
  - the timeline window's Space (`timeline_cue_editor.clj:170-173`, through the Phase 2 shell's `:on-key-extra`).
- `laser-show.events.handlers.triggers` (`triggers.clj:29-41`): NEW public `bound? [state input-event]` = there is a definition or trigger-map action for the event. `handle-input` (`:161-173`) uses the same two lookups, so the filter and the handler can't disagree about which keys are bound.
- `laser-show.events.handlers.input` (`input.clj:224-228`, table `:500-501`): NEW `:input/keyboard-enable` / `-disable`, writing `[:backend :input :keyboard :enabled?]`.
- `laser-show.views.components.trigger-bank` (`trigger_bank.clj:38-47`): the `:keyboard` label goes through `keyboard/label` (`Key Space`), in all five views that show it. The trigger editor shows "Overrides built-in shortcut: Play/Stop" when `builtin-shortcut` is non-nil (D10 A).
- `laser-show.views.components.keyboard-settings` (NEW, about 40 lines, like `osc_settings.clj`): a "Keyboard" card with the enable checkbox and the built-in keys a binding overrides (Escape is reserved). It is mounted in `settings.clj` after the OSC card (`:64`).
- `laser-show.state.domains` (`domains.clj:533-534`): drop the unused `:attached-components`.
- K-15's generic mapper suppression was already present. See §10 for the keyboard learn/dispatch ordering case.

Step 0 (a 15-minute spike before the rest): a TestFX test that a filter installed through `ext-on-instance-lifecycle` sees `KEY_PRESSED` first, and that consuming it stops Space from reaching the `MenuItem` accelerator (`title_bar.clj:154`). Everything below assumes that.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| held keys per window | view-local atom, FX thread only | the filter | n/a. Transient input state, never read elsewhere |
| claimed keys per node (D11 A) | the node's `getProperties`, set once when the component is created | the components (§4) | n/a |
| `[:backend :input :keyboard :enabled?]` (existing placeholder) | app state, session only (like the gamepad flag; `[:backend …]` is not saved, `persistent.clj:68-86`) | `:input/keyboard-enable` / `-disable` | No / no |
| `[:config :input :trigger-map {:source :keyboard :id …}]` (existing) | document (saved per project, like MIDI and OSC bindings) | learn (`:input/learn-captured`, undo label "Learn Trigger") | Yes / yes |
| `[:backend :input :learning-target]` (existing), `:learning-capture` (added) | app state, session only | learn handlers | No / no |
| `[:trigger-runtime :keyboard-activations]` (added; id → action) | app state, session only | `:trigger/input`, also cleared by New/Open | No / no |

The filter reads app state with `get-raw-state`. It never writes app state and never subscribes, so plan 02's write guard (`state/core.clj:102-128`) isn't involved. The router call runs the mapper and learn handlers on the FX thread, as the gamepad's poll thread does today. They deliver a promise or `events/dispatch!`, and write nothing.

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| router event `(trigger :keyboard id :pressed|:released)` | constructor exists | the key filter, on the FX thread | n/a (router) |
| `:trigger/input` | yes (`triggers.clj:161-173`) | the mapper (`input_mapper.clj:107-110`) | yes |
| `:input/start-learn`, `:input/cancel-learn`, `:input/learn-captured` | yes (`input.clj:284`, `:414`, `:348`; table `:504-508`) | trigger editor and pads; Escape while learning (filter) | yes |
| `:input/keyboard-enable`, `:input/keyboard-disable` | NEW | Settings > Keyboard checkbox | yes |

No new effect keys.

## 7. Data examples and invariants

Key ids: `SPACE` → `:space`, `A` → `:a`, `DIGIT1` → `:digit1`, `NUMPAD3` → `:numpad3`, `F5` → `:f5`, `LEFT` → `:left`.

Trigger-map entries:
- `{:source :keyboard :id :space} → {:type :trigger-cue :target [0 0] :behavior :momentary}`
- `{:source :keyboard :id :j} → {:type :vibe :role :hit}`
- `{:source :keyboard :id :digit1} → {:type :zone-map :target :map-1}`

Decision table for one key event, with D10 = A and D12 = A. The rows marked *D11 A* hold if D11 is answered A; under D11 B the "claims the key" row goes.

| Situation | Consume? | Router event |
|---|---|---|
| keyboard triggers disabled | no | none |
| target is a text input | no | none |
| Ctrl/Cmd/Alt/Meta held | no | none (reserved for commands) |
| `:escape` | no | none; while learning it also cancels the learn. Stop still fires through the accelerator in the main window while playing (p2) |
| a fader learn waits (`:capture :continuous`, `{:type :macro}`) | no | none (a key can't be a fader; the mapper would swallow the press, `input_mapper.clj:46-49`, `:105-106`) |
| a press learn waits, any other key | yes | `:pressed` (learn captures it; the mapper doesn't dispatch it) |
| *D11 A:* the focus owner claims the key (step grid, timeline canvas, list, rig strip, a canvas) | no | none: the component edits with it |
| key already held (auto-repeat press) | yes if the key is bound, else no | none |
| bound key, press | yes | `:pressed` |
| bound key, release | yes | `:released` |
| unbound key | no | none |
| window loses focus with keys held | n/a | `:released` for each held key |

Invariants:
1. Outside discrete Learn, a new unbound press is not consumed; controls and menu accelerators keep it. A key the app already claimed keeps its matching release even if the binding has changed.
2. Typing in a text field never triggers a cue.
3. A momentary cue and a vibe role always get their release, including when the window loses focus.
4. Escape keeps its built-in meaning and can't be bound.
5. Learning a key never fires that key's previous binding (holds today; kept by a regression test).
6. A bound key produces exactly one `:pressed` per physical press, whatever the OS auto-repeat does.
7. *D11 A:* a focused component that edits with a key keeps that key.

## 8. Tests (named test vars + fixtures)

Pure, `laser-show.input.keyboard-test` (NEW):
- `key-id-test`
- `label-test`
- `decide-table-test` (one assertion per row of §7)
- `builtin-shortcut-test`

Handlers:
- `laser-show.events.handlers.triggers-test` (extend): `bound-agrees-with-handle-input-test` (a definition, a trigger-map entry, an unbound key).
- `laser-show.events.handlers.input-test` (extend; the gamepad test is at `:118-124`): `keyboard-enable-disable-test`.
- `laser-show.events.handlers.input-mapper-test` already has `mapper-ignores-discrete-events-while-learning-test` (`:30-53`). Nothing new.

UI, `laser-show.ui-test.keyboard-trigger-test` (NEW). Fixtures: `with-toolkit` once, then `with-app` and a `with-input-mapper` each, as `perform_test.clj:32-45` does, because `start-test!` doesn't start the mapper. Setup goes through `events/write-state!`, waits through `events/flush!`.
- `bound-key-triggers-its-cue-test` (bind `:digit1` → cell `[0 0]`; press it with focus on `#main-layout`; wait for `[:playback :active-cues [0 0]]`)
- `unbound-key-passes-through-test`
- `text-field-focus-blocks-triggers-test` (`#bpm-input` focused)
- `momentary-binding-releases-on-key-up-test`
- `bound-key-holds-and-releases-a-vibe-role-test`
- `repeated-press-emits-one-trigger-test` (fires two `KEY_PRESSED` events without a release; the headless robot can't auto-repeat)
- `focus-loss-releases-held-keys-test`
- `learn-captures-a-key-without-firing-the-old-binding-test`
- `escape-cancels-learn-test`
- `fader-learn-leaves-keys-alone-test`
- `bound-space-overrides-play-stop-test` (D10 A)
- `escape-always-stops-test` (including from `#bpm-input`, p2)
- `bound-key-works-in-the-timeline-window-test`
- *D11 A:* `focused-step-grid-keeps-its-space-test`

`laser-show.ui-test.settings-test`: `keyboard-card-is-gone-test` (`:50-55`) becomes `keyboard-card-has-its-switch-test`. The point it checked (no card that only says "coming soon") stays true.

Manual (unverified): focus-loss release on a real OS window, and a held key on macOS.

Run `clj -M:mac-arm:test -n laser-show.input.keyboard-test -n laser-show.events.handlers.triggers-test -n laser-show.events.handlers.input-test`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.keyboard-trigger-test -n laser-show.ui-test.settings-test`, then both full suites.

## 9. Out of scope / alternatives considered

- **Key combinations and chords** as bindings. Not requested; D12 B is the hook.
- **Physical key positions** (a grid of keys that matches the grid of cells whatever the layout). JavaFX exposes the logical key code only, so bindings follow the keyboard layout. Documented, not solved.
- **Global hotkeys** while another application has focus. Needs a native hook; out of scope. Keyboard triggers work only while a Laser Show window has focus.
- **Per-binding `Accelerator`s** (`Scene.getAccelerators`). Rejected: they never see key releases (momentary and vibe roles need them), and they are skipped when a control consumes the key.
- **User-configurable built-in shortcuts** (rebinding Play/Stop itself). The registry idea from the old plan; revisit separately.
- **Escape as Stop in dialogs too.** Today Escape stops only in the main window (no menu bar in a dialog). The filter could dispatch `:transport/stop` for Escape in every window. Not proposed: Escape already closes the question dialogs, ends a timeline drag and clears a list's selection there. Revisit with the live-performance designs.
- **A preset keyboard layout** (1–5 for zone maps, Space for a blind preview, as `plans/2026-11-21-live-performance-ux/` sketches). That is the user's bindings or a later plan's defaults; this phase only carries the keys.

## 10. As built on `keyboard-phase5` (from `eb25043`)

- **Ownership:** the main scene and eight non-modal editor scenes use the capture filter in `views/key_triggers.clj`; the four modal dialogs do not. The original `:on-focused-changed` for pointer pads stays. Each scene installs its own filters and window-focus listener, and removes them on disposal or replacement. The step-0 headless check (`07cf026`) demonstrated that a consuming scene filter stops Space reaching the Play menu accelerator; removing it restores Play.
- **Press and release:** the FX thread keeps only the keys the scene claimed, distinguishing a learn from a performing press. Key-up, window blur and scene disposal release performing keys once. `:trigger-runtime :keyboard-activations` records the action on the event thread, so a momentary cue, effect or vibe releases its original target even if its binding was removed/reassigned or its definition matches only a press. New/Open clears the old show's keyboard activations; they are never saved or undone (`83f747c`, `2395551`).
- **Learning:** `:learning-capture` records discrete vs. continuous independently of the learn target. A fader learn leaves the keyboard alone. A key used to learn is consumed through key-up without playing a cue. Its router press is tagged `:learn-only?`, so even if the learn finishes before the mapper sees that same press, it cannot fire the new or old binding (`cb8fbbd`, `5436a6f`).
- **Settings and display:** the session switch leaves saved bindings intact. The card lists overridden menu shortcuts from the trigger map and enabled definitions; the trigger editor names a collision alongside its binding. Key names are shared by all binding displays (`46ed151`).
- **D11-A editor keys:** claims follow the focus owner and its ancestors. A timeline window keeps Space; the step grid body keeps its edit keys but its surrounding window can play bindings. Reusable canvases follow the current editing or dragging state, so read-only hue controls do not intercept arrow bindings (`8f877a8`, `c6be56a`).

**Verification on macOS arm64 (`79bd080`):** the integrated `keyboard-trigger-test`, `key-filter-test`, `keys-test`, `settings-test` and `trigger-editor-test` run passed 39 tests / 153 assertions. Coverage includes keyboard bindings in a second window, no binding in any of the four modal windows, release on blur/close, a mid-hold rebind, typed input, discrete and continuous Learn, and editor key precedence. The first acceptance run on `eb25043` had 14 failures/timeouts for missing behavior. In a headless `start-test!` session, the trigger editor learned J from keyboard input without starting the cue; J then played and stopped A1 in the main window. The platform-native held-key and external-application-focus checks in §8 were not performed. Kondo: 0 errors, 17 warnings.

| Full regression suite at `79bd080` | Tests | Assertions | Failures | Errors |
|---|---:|---:|---:|---:|
| Unit (`clojure -M:mac-arm:test:test-ui-headless`) | 1,925 | 570,841 | 0 | 0 |
| Headless UI (`clojure -M:test-ui:mac-arm:test-ui-headless`) | 354 | 1,875 | 0 | 0 |

---

## Decisions

> **DECISION D10 — When a keyboard binding collides with a built-in shortcut** *(answered A, 2026-11-22: the ★ option, taken under the user's standing choice)*
>
> Space (Play/Stop), Delete (Clear Cell), R (Retrigger), F11 (Fullscreen) and Escape (Stop) are built in (`title_bar.clj:144`, `:154`, `:162`, `:168`, `:182`).
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | The user's binding wins, except Escape, which is reserved and can't be bound (a stop key that can't be hijacked). The learn panel notes "Overrides built-in shortcut: Play/Stop" | A learned key always does what the user taught it | A bound Space no longer plays or stops |
> | B | Built-ins win; learning a colliding key is refused with a message | Nothing built-in ever changes | The most natural keys (Space, R) can't be used |
> | C | A "performance mode" toggle: on = bindings win and built-ins (except Escape) are off; off = built-ins win | Explicit and visible | A new mode to remember and to show in the UI |
>
> *Audit note (2026-11-17), resolved 2026-11-22:* the note asked to add Escape to p2 before deciding. p2 now shows that Escape in `#bpm-input` stops the show (`playing? false`, the focus owner still the text field): the field doesn't consume it. So in the main window Escape already is a panic key whatever has focus, and A keeps it so. Stop still goes through the event agent (`:transport/stop`).
>
> **Your answer:** A (2026-11-22; the recommended ★ option, under the user's standing choice to take the recommended options. Re-checked at `2227bce`: still fits; F11 added to the built-ins it overrides)

> **DECISION D11 — Where do keyboard triggers work, and does a focused editor keep its keys?** *(answered A, 2026-11-23)*
>
> *Why it was re-opened.* The 2026-11-13 recommendation was "main window and every editor dialog, never inside a text input". It made a bound key win over every component's own keys, which was acceptable when only the timeline canvas and the list had single keys. Since then:
> - the step grid window (zones Z6) edits with Space, Enter, Delete, the arrows and Home/End on its focused body (`step_grid.clj:41-68`);
> - the rig strip uses ←/→ in the main window (`rig_strip.clj:472-484`);
> - the projector chain list sits in the main window (`tabs/projectors.clj:522`).
>
> Under the old option, binding Space to a cue (the most natural key; the live-performance designs use it) would turn the step grid's Space-to-toggle off for as long as the binding exists, with no way back but the mouse. That no longer looks like the best choice, so it isn't recorded. The options below are rewritten for today's code. The old A is now B.
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Main window and the eight editor windows, never the three question dialogs. A bound key fires unless the focus owner is a text input **or a component that edits with that key**: the step grid body, the timeline canvas, a focused list (Delete, Escape), the rig strip (←/→), a canvas (arrows). That component keeps the key. Components declare their keys on their node (`keys/claim-keys!`, §4), so the filter stays generic. A master switch in Settings > Keyboard (default on; with no bindings nothing is intercepted anyway) | The same rule as D5 A and as text fields: the focused editor keeps its keys. Triggers work everywhere else, including in the editor windows. Nothing built since `cb32414` loses a key | A bound key does nothing to the cue while such a component has focus (click elsewhere first, or bind a key the editor doesn't use). Six places declare their keys, and a new keyboard-driven component must too |
> | B | The 2026-11-13 option: main window and every editor window; a bound key wins everywhere except in a text input | A bound key always fires | A bound Space, Delete or arrow stops working in the step grid, the timeline canvas, the lists and the rig strip while the binding exists |
> | C | Main window only, with the switch | Simplest; no overlap with the editor windows' keys | Can't fire cues while an editor window is focused; in the main window the rig strip and the projector chain list still need A's or B's rule |
> | D | A or B, but each editor window opts in | Fine control | More configuration surface |
>
> **Your answer:** A. The main window and eight non-modal editors accept keyboard triggers. Text inputs and focused editors retain their editing keys; the three modal questions and About do not trigger cues. The Settings switch defaults on.

> **DECISION D12 — What is a key's identity?** *(answered A, 2026-11-22: the ★ option, taken under the user's standing choice)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | The `KeyCode` name only (`:space`, `:a`, `:digit1`, `:f5`). Ctrl/Cmd/Alt/Meta held means "not a trigger" (reserved for commands, and for Alt+←/→ in the rig strip). Shift is ignored. Follows the logical keyboard layout | Simple labels and learn; no collision with command shortcuts; the live-performance sketches use bare keys only | No chords; layout-dependent |
> | B | Include modifiers in the identity (`:shift+a`, `:ctrl+1`) | Many more bindings | Collides with command shortcuts; more labelling and learn work |
> | C | Use the typed character instead of the key code | Layout-independent for printable keys | Unstable for non-printable keys; case and dead-key problems |
>
> **Your answer:** A (2026-11-22; the recommended ★ option, under the user's standing choice to take the recommended options. Re-checked at `2227bce`: still fits)
