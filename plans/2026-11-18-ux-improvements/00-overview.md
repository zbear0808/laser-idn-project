# UX improvements: controls that work, a screen that tells the truth, the Grid tab as the performer's screen

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** Approved 2026-11-18 ("your plan with the rest of the stuff seems fine"; then "with the rest of the plan I agree with you. Go ahead and implement"). All decisions answered (§5): U1 performers, U2–U5 A   **Last verified against:** `b1da0f2`, by driving the app in test mode (headless, no hardware) through its REPL with plorer, 2026-11-18. The screenshots are in `debug-output/ux-*.png` (gitignored, not committed)
**Supersedes / Superseded by:** none   **Related findings:** review 01 (views); keyboard plan Phase 2 (dialog shell, menu stubs D4, `cell-label`), Phase 4 (clipboard); vibe plan V8 B (the vibe stuff has its own tabs, nothing on the Grid tab), V2 and output runtime O4 (Stop is the blackout)

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. "Ran" means seen in the running app or a REPL check; "read" means from the code. Anything marked **NEW** does not exist yet.

## Start here

The look is coherent, and the timeline editor is the most polished part. The problems, in the order to fix them:

1. **Some controls silently do nothing**, or store junk. Phase 0, about 2 days.
2. **The screen contradicts the output** in places. Phase 1, about 3 days.
3. **The layout doesn't scale**, which matters most on the Grid tab, now that you've said it is the performer's tab. Phase 2, about 3 days.
4. **Guidance is stale or missing.** Phase 3, about 2 days.

| Phase | Goal | Size | Needs |
|---|---|---|---|
| 0 | Controls that work: one fix for the `:fx/event` placeholders, the cell colours, the single-output device, the trigger editor | ~2 d | none |
| 1 | An honest screen: Stop shows what it did, the status bar names what plays, effects cells show on and off, one notice for failures, the stream state says why | ~3 d | 0 (the colours) |
| 2 | The Grid tab as a performer's screen: pads that grow, larger text; a Cue Chain Editor that fits; dialogs that match | ~3 d | 1; the keyboard plan's Phase 2 for the dialog parts |
| 3 | Guidance: true hints, tooltips, platform shortcut text, Settings tidy-up, QUICKSTART | ~2 d | none |

## 1. What the app does today

### Controls that don't work (Phase 0)

| # | What happens | Where | How found |
|---|---|---|---|
| F-1 | **Add Projector** (manual): the button never enables. **New / Edit Zone Group**: the same. **Renaming a projector** stores the keyword `:fx/event` as its name, the panel header reads "CONFIGURE: :fx/event", and the tab stops updating. **Unticking** a discovered output never disables it. The calibration brightness slider would throw | ten call sites pass `:fx/event` as a *value*: `views/dialogs/add_projector_manual.clj:46, 61, 72`; `views/dialogs/zone_group_editor.clj:78, 123, 136`; `views/tabs/projectors.clj:121, 193, 392, 690`. cljfx puts the event's value under the `:fx/event` *key* only, so the handlers store the keyword: `events/handlers/ui.clj:37-58` merges `:updates` as given; `events/handlers/projector.clj:270-288` merges the name; `:211-221` coerces `:fx/event` with `boolean` (so always true); `:381-385` calls `double` on it | ran (dialog state `{:name :fx/event :host :fx/event}`; the renamed projector; tick then untick: still enabled). The slider: read |
| F-2 | **Every cue cell looks like an empty cell** (both `#3D3D3D`), and **hovering a cue cell turns it near black** (`#1E1E1E`), a playing one too | `css/theme.clj:96-97` takes `border` from `semantic-colors`, which has no `:border` (it is in `base-colors`), so `:cell-content` is nil (`:124`) and `(colors/lighten nil 0.12)` is `"#1E1E1E"` (`:125`). In `css/grid_cells.clj` the nil background falls back to `.grid-cell`'s (`:31-37`), and `.grid-cell-content:hover` (`:86-87`) comes after `.grid-cell-active:hover` (`:81-83`) with the same specificity, so it wins | ran (fills read from the nodes; a REPL check of the computed colours) |
| F-3 | **A discovered device with 0 or 1 outputs makes the device list fail to render**; the tab still shows "No devices found" | `views/tabs/projectors.clj:206` passes `:on-mouse-clicked nil` for single-output devices; cljfx 1.10.6 throws "Don't know how to coerce" on a nil handler | ran (a seeded device, and a two-line repro). *Unverified on real hardware* |
| F-4 | **Trigger editor**: Behavior lists raw keywords (":retrigger", ":quantized-decay"); a typed decay isn't kept (2.5 and Enter: the binding gets 1.0 while the field still shows 2.5); a behaviour picked before a key is learned is dropped; and only "momentary" changes anything | `views/components/trigger_bank.clj:96-111` (the combo shows keywords; the decay field re-sends the rendered value); `events/handlers/input.clj:297-315` updates existing bindings only; `events/handlers/triggers.clj:59-89` reads `:behavior` only for `:momentary`, so toggle, retrigger, quantized-decay and one-shot all do what the cue's own trigger mode says | ran (the decay); read (the rest) |

### The screen contradicts the output (Phase 1)

| # | What happens | Where | How found |
|---|---|---|---|
| F-5 | **Stop leaves the cells lit**: after Stop, four cues stayed blue, and Play brought them all back. The Transport menu calls the same event both "Pause" and "Stop" | `events/handlers/timing.clj:72-78` sets `:playing? false` and leaves the intents (by design: Stop is the blackout, output runtime O4); the cells read the intents (`subs.clj:205-210`); `views/components/title_bar.clj:146-165` | ran |
| F-6 | **The status bar names one arbitrary cue**: with four playing it said "Active: [0,0]". One cell has four names: "A1", "Cell A1", "[A, 1]", "[0,0]" | `subs.clj:53-54` and `state/extractors.clj:129-133` (the first key of the map); `views/status_bar.clj:36-56`; `views/dialogs/cell_trigger_editor.clj:23, 75` | ran |
| F-7 | **Effects cells**: a filled cell that is off has the same blue as one that is on, and "on" only adds a glow. The cell says "2 fx", not which | `css/grid_cells.clj:67-70`; `views/components/grid_cell.clj:66-76` | ran |
| F-8 | **Stream with nothing enabled** reads "Streaming to 0 of 0 (no enabled projectors)" beside a Stop button | `subs.clj:364-398` | ran |
| F-9 | **The status bar is for developers**: Base, Effects, Total, p95, max, IDN in µs, and again "µs/frame" in the preview header. Output health (fps, errors) shows only on the Projectors tab | `views/status_bar.clj:67-110`; `views/components/preview.clj:339-345, 367-370` | ran |
| F-10 | **Failures are silent**: a failed open or save only logs | `events/handlers/menu.clj:94-97, 113-120` | read |

### The layout (Phase 2)

| # | What happens | Where | How found |
|---|---|---|---|
| F-11 | **Nothing scales**: cells are 80×60 with 10 px labels, the preview 400×400; at 1680×1000 the grid fills about 40 % of its pane. The split position is fixed | `views/components/grid_cell.clj:119-120`; `views/components/grid_tab.clj:66-105`; `css/grid_cells.clj:98-102`; `views/components/preview.clj:316-337` (sizes from `[:config :preview]`, `state/domains.clj:227`); `views/root.clj:107` | ran |
| F-12 | **The Cue Chain Editor's footer doesn't fit**: "Close" reads "C…", "Trigger Mode" "Trigger M…"; on the right, four stacked scroll regions leave the preset parameters one visible row; the left list column is half empty | `views/dialogs/cue_chain_editor.clj:475-476` (800×700), `:113, 299` (long labels), `:503` (a 300 px list), `:597-633` (footer) | ran |
| F-13 | **Clipped and mismatched dialogs**: the projector panel's "Port:" shows "P…"; the Zone Group editor is hard-coded `#2D2D2D` and inline styles; the trigger editor says "Done" where the others say "Close" | `views/tabs/projectors.clj:411-413`; `views/dialogs/zone_group_editor.clj:101-136`; `views/dialogs/cell_trigger_editor.clj:63` | ran |

### Guidance (Phase 3)

| # | What happens | Where | How found |
|---|---|---|---|
| F-14 | **Stale hints**: the Grid says "Right-click to select" (right-click opens a menu); the Effects grid says "Right-click to edit chain" (a menu too); QUICKSTART describes a preset palette and "Connect IDN", which don't exist; the timeline's empty lane points at a browser that starts closed | `views/tabs/grid.clj:23`; `views/tabs/effects.clj:21`; `QUICKSTART.md:42-47`; `views/components/visual_editors/timeline_editor.clj:265`, `state/domains.clj:173` | ran |
| F-15 | **Few tooltips** outside the toolbar and the timeline: the list buttons (New, Group, Copy, Paste, "Del") and the Cue Chain Editor's footer have none | `views/components/list.clj:568, 574, 680, 686, 693`; `views/dialogs/cue_chain_editor.clj:610-633` | read |
| F-16 | **Shortcut text**: cell menus say "Ctrl+C" on macOS too | `views/components/grid_cell.clj:235, 238, 254, 328, 331` | ran |
| F-17 | **Settings**: a "coming soon" Keyboard card; MIDI's "Disconnect All" is low-contrast text beside a button; the OSC card has an Enabled checkbox and a Start/Stop Server button side by side | `views/tabs/settings.clj:19-37`; `views/components/midi_settings.clj:211`; `views/components/osc_settings.clj:106-140` | ran (what each OSC control does: not checked yet) |
| F-18 | **Small things**: Save As is disabled until the project has a file; BPM commits only on Enter; the Zone Groups subtitle says projectors are assigned in the Projectors tab, though its own middle column assigns them too | `views/components/title_bar.clj:84-88`; `views/toolbar.clj:103-115`; `views/tabs/zones.clj:336`, `:163-176` | ran |

## 2. Root causes

- **A cljfx convention misread** (F-1): a map event's value arrives as the `:fx/event` key, and nothing substitutes a placeholder value inside the map. Ten call sites assumed it does.
- **A typo in the theme** (F-2), invisible because a nil background falls back silently, plus CSS rule order.
- **Labels that fell behind behaviour** (F-4, F-5, F-6, F-14): the trigger editor offers behaviours the trigger path never had; Stop became the blackout without the screen saying so; "active cell" kept a single-cue meaning after multi-cue playback.
- **Fixed pixel sizes** (F-11, F-12, F-13) from a smaller first screen.

## 3. Phases

### Phase 0: controls that work (~2 d)

**Changes**
- `laser-show.events.core` (existing, `handle-event-pipeline` `:444-460`): NEW private `with-fx-event`, the outermost step. When the event has an `:fx/event` key, a value that is exactly the keyword `:fx/event` at the top level, or one level inside `:updates`, becomes that value. `:state` is never looked at. The ten call sites stay as they are, and future ones work.
- `laser-show.events.handlers.projector` (existing, `:211-221`): drop the `boolean` coercion that hid the bug.
- `laser-show.css.theme` (existing, `:96-97`): take `border` from `base-colors`. `laser-show.css.grid-cells` (existing): `.grid-cell-active:hover` after `.grid-cell-content:hover` (`:81-87`), so a playing cell stays blue.
- `laser-show.views.tabs.projectors` (existing, `:204-207`): leave `:on-mouse-clicked` out of the row when there is no handler.
- `laser-show.views.components.trigger-bank` (existing, `:96-111`): see U5. With U5 A: two choices with a one-line description each, "Follow the cue's mode (toggle or retrigger)" and "While held (momentary)", and no decay field until the vibe plan's Phase 1 decay exists; the choice is kept in `[:ui :dialogs :trigger-editor :behavior]` and passed in `:input/start-learn`'s `:target-action`, so the learned binding carries it. `laser-show.events.handlers.input` (existing, `:223-270`): a learned binding keeps `:behavior` from its target action.

**State**: `[:ui :dialogs :trigger-editor :behavior]` (NEW), UI state, written by `:ui/update-dialog-data`, no undo, no dirty. Nothing else new.

**Data examples** (`with-fx-event`)

| Event as the view describes it | What the handler sees |
|---|---|
| `{:event/type :ui/update-dialog-data :dialog-id :zone-group-editor :updates {:name :fx/event} :fx/event "Balcony"}` | `:updates {:name "Balcony"}` |
| `{:event/type :projectors/set-service-enabled :host "10.0.0.5" :service-id 1 :enabled? :fx/event :fx/event false}` | `:enabled? false` |
| `{:event/type :projectors/set-calibration-brightness :brightness :fx/event :fx/event 0.4}` | `:brightness 0.4` |
| `{:event/type :timing/set-bpm :bpm 128.0}` | unchanged |
| `{:event/type :x :updates {:label ":fx/event"}}` (a string) | unchanged |
| a placeholder but no `:fx/event` key (dispatched by hand) | unchanged, and a warning in the log |

| Cell | Today | After |
|---|---|---|
| empty | `#3D3D3D` | `#3D3D3D` |
| with a cue | `#3D3D3D` | `#505050` |
| with a cue, hovered | `#1E1E1E` | `#656565` |
| playing | `#4992DB`, glow | the same |
| playing, hovered | `#1E1E1E` | `#4992DB` at 0.9 opacity |

**Tests**: `laser-show.events.core-test` (existing): `fx-event-placeholder-test` (the table). `laser-show.css.theme-test` (NEW): `computed-colors-have-no-nil-test`. UI, `laser-show.ui-test.setup-dialogs-test` (NEW; `with-toolkit` once, `with-app` each): `add-projector-by-hand-test`, `create-zone-group-test`, `rename-projector-test`, `untick-a-discovered-output-disables-it-test`, `calibration-brightness-slider-test`, `single-output-device-renders-test`. `laser-show.ui-test.grid-test` (existing): `filled-cell-differs-from-empty-test`, `playing-cell-stays-blue-on-hover-test` (both read the node's background fill). `laser-show.ui-test.trigger-editor-test` (existing): `behavior-shows-plain-names-test`, `behavior-chosen-before-learning-sticks-test`; the existing `trigger-editor-learns-input-and-configures-behavior` picks ":momentary" by its text (`:62`) and moves to the new label.

### Phase 1: an honest screen (~3 d)

**Changes**
- Stop (U2 A: Stop stays the blackout, O4): NEW sub `subs/output-held?` (`:playing?` false while intents remain). The cells of held cues get NEW class `grid-cell-held` (a blue outline, no fill) instead of `grid-cell-active` (`views/components/grid_cell.clj:30-43`); the toolbar shows a chip "OUTPUT OFF · 4 cues held" (`views/toolbar.clj:218-245`), and Play's tooltip says "Resume 4 cues". The Transport menu (`views/components/title_bar.clj:146-165`) gets one item, "Play" or "Stop (blackout)", instead of "Pause" and "Stop" doing the same thing.
- Status bar (`views/status_bar.clj:36-56`): NEW sub `subs/playing-cues`, the playing cells in grid order with their names: "Playing: A1 circle · A2 square · +2". The µs stats (`:67-110`) and the preview's "µs/frame" (`views/components/preview.clj:367-370`) only in dev mode (`subs/dev-mode?`, `subs.clj:859`); in their place, output health from `subs/connection-status` (`subs.clj:364`). One cell name everywhere ("A1"), from the keyboard plan Phase 2's `cell-label` (`plans/2026-11-13-keyboard-focus-window-lifecycle/02-phase2-dialog-shell.md` §4); `cell_trigger_editor.clj:23, 75` stop saying "[A, 1]".
- Effects cells (`css/grid_cells.clj:67-70`, `views/components/grid_cell.clj:66-76`): off and filled is the neutral content colour, on is the blue fill; the label is the first effect's name plus "+n", like a cue cell; the tooltip lists the effects.
- Notices: NEW `[:ui :notice] {:text :level}`, set by the failure handlers (`events/handlers/menu.clj:94-97, 113-120`) and shown at the left of the status bar; `:ui/clear-notice` (NEW) through the existing `:dispatch-later` effect (`events/core.clj:162-168`) after 6 s, or a click.
- Stream with nothing enabled (`subs.clj:398`): "No projectors enabled", with a "Projectors…" link that dispatches `:ui/set-active-tab :projectors` (`events/handlers/ui.clj:13-16`).

**State**: `[:ui :notice]` (NEW), UI state, written by handlers, no undo, no dirty. The rest are subs.

**Tests**: `laser-show.subs-test` (existing): `playing-cues-sub-test` (grid order, names, "+n"), `output-held-sub-test`. `laser-show.views.status-bar-test` (existing): `playing-list-test`, `dev-stats-only-in-dev-mode-test`. `laser-show.events.menu-file-handlers-test` (existing): `failed-save-sets-a-notice-test`, `failed-open-sets-a-notice-test`. UI: `laser-show.ui-test.toolbar-test` (existing) `stop-shows-output-off-and-held-cells-test`; `laser-show.ui-test.grid-test` (existing) `effects-cell-on-and-off-look-different-test`; `laser-show.ui-test.menu-bar-test` (existing) `transport-menu-has-one-play-stop-item-test`.

### Phase 2: the Grid tab as a performer's screen (~3 d)

U1 says the Grid tab is for performers, so it is laid out for playing first: big targets, text readable from a step back, the state of the show in view, and no edit you can trigger by accident.

**Changes**
- `laser-show.views.components.grid-tab` (existing, `:22-105`) and `grid-cell` (`:119-120`): cells grow with the pane (minimum 64×48), and the cell label grows with them (12–16 px instead of 10, `css/grid_cells.clj:98-102`). The row letters and column numbers follow the cell size.
- The preview (`views/components/preview.clj:316-337`) sizes from its pane instead of `[:config :preview :width/:height]`; the split position (`views/root.clj:107`) is remembered in `[:config :window :grid-split]`, a pref (`state/history.clj:39-41`: saved, not undoable).
- The status bar text goes to 12–13 px, because the performer reads it.
- The Cue Chain Editor (`views/dialogs/cue_chain_editor.clj:475-476`): a minimum stage size (960×640) and a larger default; the footer uses the keyboard plan Phase 2's shared dropdowns with the timeline's short labels ("Default zone:", "Trigger:"); the preset parameters get the free height instead of the fixed 300 px list (`:503`).
- `views/tabs/projectors.clj:411-413`: "Port:" gets a minimum width. `views/dialogs/zone_group_editor.clj:101-136`: theme classes (`dialog-content`, `text-field`) instead of inline colours, as `views/dialogs/add_projector_manual.clj:22, 43` does. `views/dialogs/cell_trigger_editor.clj:63`: "Close" (the id `#trigger-editor-done` stays: `test/laser_show/ui_test/trigger_editor_test.clj:25, 29`).
- Accidental edits during a show: the vibe plan's show lock (its Phase 7) covers the grid's drag-to-move and clear; nothing more here.

**State**: `[:config :window :grid-split]` (NEW), a pref: saved, not undoable.

**Tests**: UI, `laser-show.ui-test.grid-test` (existing): `cells-grow-with-the-window-test` (at 1200×800 and 1680×1000, the cell width grows and the 8 columns still fit), `split-position-is-remembered-test`; `laser-show.ui-test.main-window-test` (existing): `cue-chain-editor-footer-fits-test` (the Close button's text is not truncated: its width covers its text), `zone-group-editor-uses-theme-classes-test`. The existing `grid-renders-stable-cell-locators` must pass unchanged.

### Phase 3: guidance (~2 d)

**Changes**
- Hints: the Grid (`views/tabs/grid.clj:23`) "Click: play or stop · Double-click or right-click: edit · Drag: move"; the Effects grid (`views/tabs/effects.clj:21`) "Click: on or off · Double-click or right-click: edit · Drag: move", and an empty-grid line "Effect cells change every playing cue. Double-click one to start".
- The timeline (`views/components/visual_editors/timeline_editor.clj:265`): opening a timeline with no clips opens the preset browser (`[:ui :timeline-prefs :add-panel :open?]`, `state/domains.clj:173`).
- Tooltips on every icon-only or abbreviated control (F-15); "Del" becomes "Delete".
- NEW `laser-show.views.components.shortcuts`: `shortcut-text [key]` → "⌘C" on macOS, "Ctrl+C" elsewhere; used by the cell menus (F-16).
- Settings: the Keyboard card (`views/tabs/settings.clj:19-37`) goes until the keyboard plan's Phase 5 gives it something to show; "Disconnect All" (`views/components/midi_settings.clj:211`) becomes a secondary button; the OSC card's Enabled and Start/Stop Server (`views/components/osc_settings.clj:106-140`): read what `:input/osc-*` each does before this phase, and merge them into one switch if they overlap.
- Save As always enabled (`views/components/title_bar.clj:84-88`); BPM also commits when the field loses focus (`views/toolbar.clj:103-115`); the Zone Groups subtitle (`views/tabs/zones.clj:336`) "Click a projector to add it to the group, or use the Projectors tab".
- `QUICKSTART.md:42-47`: rewritten for today's app (the Grid tab, double-click to edit, Projectors → Scan Network or Add Manual, Stream).
- The menu stubs (Export, Toggle Preview, Fullscreen, About, Check for Updates): the keyboard plan's Phase 2 table and D4, not here.

**Tests**: `laser-show.views.components.shortcuts-test` (NEW): `mac-and-other-text-test`. UI: `laser-show.ui-test.grid-test` (existing) `hints-describe-the-clicks-test`; `laser-show.ui-test.timeline-editor-test` (existing) `empty-timeline-opens-the-browser-test`; `laser-show.ui-test.toolbar-test` (existing) `bpm-commits-on-focus-loss-test`.

Every phase: the focused namespaces first, then `clj -M:test-ui:mac-arm:test-ui-headless` and `clj -M:mac-arm:test` (`.agents/skills/targeted-ui-testing/SKILL.md`).

## 4. Invariants

1. A view that describes an event never has to know that cljfx puts the value under `:fx/event`: a placeholder value works at the top level and inside `:updates`.
2. A cell's look tells its state apart: empty, filled, playing, held (output off), selected, learning; and hovering never makes a playing cell look stopped.
3. The screen never says more is playing, or less, than the clock renders: the status bar lists what plays, and held cues look held.
4. Stop is still the blackout (O4, vibe plan V2), and the Grid tab and the toolbar say so.
5. Every failure a user caused (open, save, device) is on screen for at least 6 s.
6. The Grid tab's 8×4 cells stay visible without scrolling from 1024×700 up.

## 5. Decisions

| ID | Question | Needed before | Answer |
|---|---|---|---|
| U1 | Who is the Grid tab for | Phase 2 | **Answered 2026-11-18: performers** ("grid tab originally for performers") |
| U2 | What Stop means on screen | Phase 1 | **Taken as recommended (A)**: Stop stays the blackout, and the screen shows it (held cells, "OUTPUT OFF") |
| U3 | Where the `:fx/event` fix goes | Phase 0 | **Taken as recommended (A)**: once, in the event pipeline |
| U4 | Which batch first | now | **A (2026-11-18)**: Phase 0 alone, then Phase 1 |
| U5 | What the trigger editor offers | Phase 0 | **A (2026-11-18)**: two choices that do what they say |

> **DECISION U4: Which batch first** *(answered 2026-11-18: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Phase 0 alone, then Phase 1 | two days that fix things that are broken today, each with a test | the screen stays misleading a little longer |
> | B | Phases 0 and 1 together | one review | about 5 days before anything lands |
>
> **Your answer:** A (2026-11-18, "with the rest of the plan I agree with you")

> **DECISION U5: What the trigger editor offers** *(answered 2026-11-18: A)*
>
> The editor lists five behaviours, but the trigger path reads only "momentary" (`events/handlers/triggers.clj:59-89`): the other four do whatever the cue's own trigger mode says.
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Two choices that do what they say: "Follow the cue's mode" and "While held". Add "For N beats" when the vibe plan's Phase 1 decay exists (its `start-cue` takes `:decay-beats`) | the editor stops promising things | one-shot is gone until someone defines it |
> | B | Make all five work now | full set | toggle and retrigger per binding would override the cue's own mode, a second place deciding the same thing; decay needs the vibe plan's Phase 1 first anyway |
>
> **Your answer:** A (2026-11-18)

## 6. Ownership with other plans

| Item | This plan | Stays with |
|---|---|---|
| Dialog shell, shared dropdowns, `cell-label`, the menu stubs | uses them (Phases 1–2) | keyboard plan Phase 2 (D4) |
| Paste stays disabled after a list copy (K-7) | nothing | keyboard plan Phase 4 |
| Vibe mode's screens | nothing on the Grid tab | vibe plan V8 B: its own Perform and Vibes tabs |
| Accidental edits during a show | nothing | vibe plan's show lock (its Phase 7) |
| Stop as the blackout | shows it (U2) | output runtime O4, vibe plan V2 |

## 7. Out of scope / alternatives considered

- **A thumbnail or colour per cue**, so pads can be scanned without reading: later; the preview already shows the output, and a thumbnail per cell costs a render.
- **Assigning a preset from the grid** without opening the editor (a palette): later; it needs the drag-and-drop work the timeline browser already has.
- **A UI scale setting**: Phase 2's growing cells cover most of it.
- **A distinct project file extension** instead of `.zip`: later; it touches the file chooser filters and existing files.
- **Fixing the ten call sites one by one** (U3 B): rejected; the next one would bring the bug back.
- **Making Stop clear the cells** (U2 B): rejected; it reverses O4.
