# UX improvements: controls that work, a screen that tells the truth, the Grid tab as the performer's screen

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** In progress: Phase 0 done 2026-11-18 (`e2bce92`, as built in §8), Phase 1 done 2026-11-19 (`92c191a`, as built in §9); Phases 2–3 to do. Approved 2026-11-18 ("your plan with the rest of the stuff seems fine"; then "with the rest of the plan I agree with you. Go ahead and implement"). All decisions answered (§5): U1 performers, U2–U5 A   **Last verified against:** `b1da0f2`, by driving the app in test mode (headless, no hardware) through its REPL with plorer, 2026-11-18. The screenshots are in `debug-output/ux-*.png` (gitignored, not committed); Phase 0 as built against `e2bce92` (§8), Phase 1 against `92c191a` (§9)
**Supersedes / Superseded by:** none   **Related findings:** review 01 (views); keyboard plan Phase 2 (dialog shell, menu stubs D4, `cell-label`), Phase 4 (clipboard); vibe plan V8 B (the vibe stuff has its own tabs, nothing on the Grid tab), V2 and output runtime O4 (Stop is the blackout)

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. "Ran" means seen in the running app or a REPL check; "read" means from the code. Anything marked **NEW** does not exist yet.

## Start here

The look is coherent, and the timeline editor is the most polished part. The problems, in the order to fix them:

1. **Some controls silently do nothing**, or store junk. Phase 0, about 2 days. **Done** (`e2bce92`, §8).
2. **The screen contradicts the output** in places. Phase 1, about 3 days. **Done** (`92c191a`, §9).
3. **The layout doesn't scale**, which matters most on the Grid tab, now that you've said it is the performer's tab. Phase 2, about 3 days.
4. **Guidance is stale or missing.** Phase 3, about 2 days.

| Phase | Goal | Size | Needs |
|---|---|---|---|
| 0 | **Done 2026-11-18** (`e2bce92`, §8). Controls that work: one fix for the `:fx/event` placeholders, the cell colours, the single-output device, the trigger editor | ~2 d | none |
| 1 | **Done 2026-11-19** (`92c191a`, §9). An honest screen: Stop shows what it did, the status bar names what plays, effects cells show on and off, one notice for failures, the stream state says why | ~3 d | 0 (the colours) |
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

## 8. As built: Phase 0 (2026-11-18)

Committed as `e2bce92` on top of `b54e0a5`. §1–7 cite `b1da0f2`; the lines below are at `e2bce92`.

**Where it is now**
- `events/core.clj`: NEW private `fx-event-placeholders` `:444` and `with-fx-event` `:459`, the outermost step of `handle-event-pipeline` (`:480-498`). A value `identical?` to `:fx/event` at the top level (not `:state`, not `:fx/event` itself) or one level inside a map `:updates` becomes the event's `:fx/event`; with no `:fx/event` key the event goes on unchanged after a WARN naming its type and the paths. The ten call sites are unchanged. It looks at the event's top-level entries and at most one `:updates` map, never inside `:state`.
- `events/handlers/projector.clj:211-218`: `set-service-enabled` stores `:enabled?` as given; the `boolean` is gone.
- `css/theme.clj:96-97`: `border` comes from `base-colors`, so `:cell-content` is `#505050` and `:cell-content-hover` `#656565`.
- `css/grid_cells.clj`: the empty colour moved from `.grid-cell` to NEW rule `.grid-cell-empty` (`:48`; `views/components/grid_cell.clj:41` already gives that class to every cell without content), and the playing hover rule is `.grid-cell-grid.grid-cell-active:hover` (`:89`). Why both: the first row of the differences below.
- `views/tabs/projectors.clj:207-216`: the device row gets `:on-mouse-clicked` only when the device has several outputs.
- `views/components/trigger_bank.clj`: `behavior-options` `:18` (two per kind of cell, each a `:name` and a one-line `:description`; U5's "For N beats" goes here once the vibe plan's decay exists), shown through the `option-name` converter `:9` (combo `:133-139`); NEW label `#trigger-behavior-description` `:141`; no decay field; Learn sends `(assoc current-target :behavior behavior)` `:165`. The combo shows the cell's bindings' behaviour when it has any, else the editor's choice; anything but `:momentary` shows as the first choice (`:65-69`). `views/dialogs/cell_trigger_editor.clj:54` passes the choice in; the window is 410 high (`:82`, was 380).
- `events/handlers/input.clj`: NEW private `learn-target` `:223` (`select-keys [:type :target]`). `[:backend :input :learning-target]` stays the cell, which three views compare with the cell (`views/components/grid_cell.clj:226, 317`, `trigger_bank.clj:52`); the binding learned is the whole target action, `:behavior` included (`:229-277`). `update-cell-trigger-action` `:304` still updates the cell's bindings and now also returns `:dispatch` (existing, `events/core.clj:157`) of `{:event/type :ui/update-dialog-data :dialog-id :trigger-editor :updates {:behavior b}}`.
- Stable ids for the tests: `#projectors-add-manual-button`, `#discovered-device-<address>`, `#discovered-output-<address>-<service-id>` (dots as dashes, `address-id` `:99`), `#projector-name-field`, `#projector-config-title`, `#projector-calibration-button`, `#calibration-brightness-slider`, `#calibration-brightness-label` (`views/tabs/projectors.clj`); `#add-projector-name-field`, `-host-field`, `-port-field`, `#add-projector-confirm-button` (`views/dialogs/add_projector_manual.clj`); `#zone-group-name-field`, `-description-field`, `-color-field`, `#zone-group-save-button` (`views/dialogs/zone_group_editor.clj`); `#zone-groups-add-button` (`views/tabs/zones.clj:106`).

**State**: `[:ui :dialogs :trigger-editor :behavior]` is `:follow` or `:momentary`, UI state, no undo, no dirty. A binding's `:behavior` can now be NEW `:follow`; the trigger path treats it like every value but `:momentary` (`events/handlers/triggers.clj:62`), and old values stay as they are.

**Differences from the plan**

| Plan (§3) | As built | Why |
|---|---|---|
| `.grid-cell-active:hover` moves after `.grid-cell-content:hover`; the theme fix makes a cue cell `#505050` | The playing hover rule has one class more (`.grid-cell-grid.grid-cell-active:hover`), and the empty colour has its own rule (`.grid-cell-empty`) | `cljfx.css` writes the rules in the style map's order, and a 13-rule map is a hash map: moving a rule in the source changes nothing. In that order `.grid-cell` also came after `.grid-cell-content`, so with only the theme fixed a cue cell was still `#3D3D3D` (`filled-cell-differs-from-empty-test` failed). Two pairs still rely on that order (content hover after `.grid-cell:hover`, active after content), which only a change to those selectors can change; the grid tests pin all five rows of the table |
| — | The rule matches cue cells only | Effect cells have their own hover rule, which already outranked the old one; only an effect cell that is on with no effects left now hovers grey (`#606060`) instead of blue. Phase 1 redoes effect cells |
| Two choices: "Follow the cue's mode (toggle or retrigger)" and "While held (momentary)" | Those for a cue cell; an effect cell's first choice says "Toggle on each press" | An effect cell has no trigger mode: a press toggles it (`events/handlers/triggers.clj:72-75`) |
| The choice is written by `:ui/update-dialog-data` | It is, dispatched by `:input/update-cell-trigger-action` | One change of the combo has to keep the choice and update the cell's existing bindings |
| — | "Follow" is stored as NEW `:follow` | The combo's options need an id; storing it keeps `update-cell-trigger-action` a merge, as today |
| — | `:learning-target` stays `{:type :target}` | With `:behavior` in it, the grid's LEARN badge and the editor's "Waiting for input..." no longer matched the cell |
| A one-line description each | Under the combo, for the chosen one, in `label-secondary` | The bank's own `description-text` class is in no stylesheet (it renders dark grey) |
| — | A converter, not a button cell and a cell factory | With cells, the button showed the option map's text when one render changed both the items and the value (opening the editor on an effect cell after a cue cell); `behavior-shows-plain-names-test` caught it |
| — | The trigger editor is 410 high, not 380 | The description adds about 30 px; at 380 a cell with one binding no longer fit under a macOS title bar |

**Found, not done**
- The Add Projector dialog stays open after Add (§1 couldn't see it: the button never enabled), and a second click adds a second projector. For Phase 2 or the keyboard plan's dialog shell.
- F-3 was worse than §1 says: with the device already discovered, clicking the Projectors tab selected the tab but left the Grid tab's content on screen.
- The editor's choice lasts the session: on a cell with no bindings it shows the last choice made on any cell (dialog data is kept between opens, `events/handlers/ui.clj:18-30`). It is what Learn will use, so the screen tells the truth.

**Tests** (UI: `with-toolkit` once, `with-app` each)
- `laser-show.events.core-test/fx-event-placeholder-test`: the table above through the private step with `identity` as the handler, plus a placeholder two levels down (unchanged) and `:state` untouched; then through `handle-event-pipeline` the three handlers store the value, and a hand-dispatched placeholder is stored as is, with the WARN.
- NEW `laser-show.css.theme-test/computed-colors-have-no-nil-test`: every computed colour is a hex string; `:cell-content` `#505050`, `:cell-content-hover` `#656565`.
- NEW `laser-show.ui-test.setup-dialogs-test`: `add-projector-by-hand-test` (disabled, then enabled by a host; name, host and port reach the projector), `create-zone-group-test` (name, description and hex colour; editing renames it), `rename-projector-test` (the panel header follows two edits), `untick-a-discovered-output-disables-it-test` (a two-output device from `:projectors/scan-complete`: expand, tick, untick; the other output untouched), `calibration-brightness-slider-test` (0.4 and "40%"), `single-output-device-renders-test` (a one-output and a no-output device show; the inline checkbox ticks and unticks).
- `laser-show.ui-test.grid-test`: `filled-cell-differs-from-empty-test` (`#3D3D3D`, `#505050`, hovered `#656565`), `playing-cell-stays-blue-on-hover-test` (clicked and hovered `#4992DB` at 0.9; mouse away `#4992DB` at 1.0). Both read the node's `BackgroundFill` after `applyCss`; the hover is a real mouse move, which works headless.
- `laser-show.ui-test.trigger-editor-test`: `behavior-shows-plain-names-test` (the two names, the one shown, the description, no decay field, an old `:quantized-decay` binding shows as following the cue, an effect cell's names), `behavior-chosen-before-learning-sticks-test`; `trigger-editor-learns-input-and-configures-behavior` selects "While held (momentary)".

**Red first** (at `b54e0a5` with only the ids added)
- Unit (`fx-event-placeholder computed-colors-have-no-nil`): 2 tests, 6 failures, 1 error. The name stored as `:fx/event`; unticking left `:enabled? true`; the brightness threw `ClassCastException` (Keyword to Number); no WARN; `:cell-content` nil, `:cell-content-hover` `#1E1E1E`.
- UI (the three namespaces): 13 tests, 4 failures, 9 errors. Each setup test timed out where the value should have arrived (host, Create Group enabling, the name, the untick, 0.4); the device row never showed (cljfx: "Don't know how to coerce {:target EventHandler :x nil}"); a cue cell `#3D3D3D`, hovered `#1E1E1E`, playing and hovered `#1E1E1E`; the behaviour choices `[nil nil nil nil nil]` and no "While held (momentary)" to select.

**Verification, 2026-11-18, macOS arm64, JDK 25**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test fx-event-placeholder computed-colors-have-no-nil` | 2 tests, 30 assertions, 0 failures |
| `clj -M:mac-arm:test -n` events.core, css.theme, input.trigger, handlers.input, handlers.triggers, history-handlers, projector-handlers, ui-handlers, zone-groups-handlers | 98 tests, 398 assertions, 0 failures |
| `clj -M:test-ui:mac-arm:test-ui-headless -n` setup-dialogs, grid, trigger-editor | 13 tests, 54 assertions, 0 failures |
| `clj -M:mac-arm:test` (full unit suite) | 868 tests, 32,494 assertions, 0 failures |
| `clj -M:test-ui:mac-arm:test-ui-headless` (full UI suite) | 71 tests, 264 assertions, 0 failures (it ran before a last test-only change, a `wait-for!` in `behavior-shows-plain-names-test`, and a comment in `events/core.clj`; after them `trigger-editor-test` ran again: 4 tests, 19 assertions, 0 failures; and `events.core-test` with `css.theme-test`: 10 tests, 54 assertions, 0 failures) |
| `clj -M:kondo --lint` on every touched file | 0 errors; 2 warnings, both there before (a redundant `let` in `input.clj`, an unused require in `projector.clj`) |

## 9. As built: Phase 1 (2026-11-19)

Committed as `92c191a` on top of `6e26ee4` (built as `e9df4bf` on branch `ux-phase1` from `67735d0`, then cherry-picked past vibe Phase 1 with no conflicts). §1–7 cite `b1da0f2`; the lines below are at `92c191a`.

**Where it is now**
- Stop (U2 A):
  - `subs.clj`: NEW `held-cue-count` `:259` (from private `held-count` `:256`): the number of intents while `:playing?` is false, else 0. NEW `output-held?` `:267` is `held-cue-count` above 0. Both come from the state Stop leaves, so a vibe blackout that dispatches `:transport/stop` reads the same.
  - `subs.clj`: NEW `playing-cues` `:278` (cells from private `cue-cells-in-grid-order` `:273`): the cues that play, row by row (A1, A2 … B1), each `{:col :row :name :first-preset-id :preset-count :timeline?}` from `cell-display-data`. It is `[]` while `:playing?` is false, and stays identical when a pause or seek changes an intent.
  - `views/components/grid_cell.clj:243-246`: a cue cell with an intent while the output is held gets `:held? true` and `:active? false`, so `build-style-classes` `:31` gives it NEW `grid-cell-held` instead of `grid-cell-active`. Only cells with an intent subscribe to `output-held?`.
  - `css/grid_cells.clj:74`: NEW `.grid-cell.grid-cell-content.grid-cell-held`: a 2 px dashed blue outline, with the content colour inside.
  - `views/toolbar.clj:228`: NEW `output-held-chip` (`#output-held-chip`, NEW `.output-held-chip`, amber, `css/layout.clj:65`) shows "OUTPUT OFF · 4 cues held". It sits before the streaming status (`:296`) and is unmanaged when nothing is held.
  - `views/toolbar.clj:48-51` (`transport-controls` `:37`, NEW private `cues-text` `:32`): the button's tooltip is "Stop (blackout)" while playing, "Resume 4 cues" while cues are held, otherwise "Play".
  - `views/components/title_bar.clj:146-169`: the Transport menu shows "Play" or "Stop (blackout)" (Space), then Retrigger. The old Stop item stays, hidden (`:visible false`, `:156-163`), for its Escape shortcut.
- Status bar (`views/status_bar.clj`):
  - Active and Preset are gone. In their place NEW `playing-list` `:75` and `playing-status` `:87` show "Playing: A1 circle · A2 square · +2" (`#status-playing`): two cells at most by name (`max-named-cues` `:73`), then "+n". Each cell is named by `views.dialogs.common/cell-label` and `grid-cell/cell-label`, the cell's own text.
  - NEW `notice-status` `:47`, at the left (`#status-notice`, `#status-notice-text`): an icon and the text in the level's colour (`notice-looks` `:42`). A click dispatches `:ui/clear-notice` with the notice's id.
  - NEW `output-health-text` `:155` and `output-health` `:178` (`#status-output-health`). They read the state and errors of `subs/connection-status` and each projector's `:fps` in `subs/streaming-status`, and show "59.8 fps", "25.0–59.8 fps", "59.8 fps · 1 error" (red, with the errors as the tooltip) or "Starting…". Nothing shows while streaming is off or has nothing enabled: the toolbar says that.
  - `status-bar` `:219`: `frame-stats-status` only in dev mode (`:234`). `status-item` `:15` takes NEW optional `:id`, `:tooltip` and `:value-color`.
  - `views/components/preview.clj:347-371`: the "µs/frame" label (`#preview-frame-stats`) only in dev mode. Outside it the header no longer subscribes to the frame stats.
- One cell name:
  - NEW `laser-show.views.dialogs.common` with `cell-label [col row]` (`views/dialogs/common.clj:4`), to the keyboard plan's Phase 2 §4.
  - `views/dialogs/cell_trigger_editor.clj:38, 79` use it: "Cue Cell A1", "Triggers — Cue Cell A1".
- Effects cells:
  - `css/grid_cells.clj`: the two `.grid-cell-effects.grid-cell-content` rules (blue at 0.7, hovered 0.85) are gone, so an off cell with effects is `#505050`, hovered `#656565`. The playing rule `.grid-cell-content.grid-cell-active` `:68` and its hover rule `.grid-cell.grid-cell-content.grid-cell-active:hover` `:88` cover both kinds of cell; the hover rule was `.grid-cell-grid…`, cue cells only.
  - `css/grid_cells.clj`: content hovered is `.grid-cell.grid-cell-content:hover` `:93`. The background rules now rank by specificity alone (`:38-46`): empty and content 1 class; playing and hovered 2; content hovered and held 3; playing hovered 4. Neither of the two pairs §8 left on the hash order relies on it now.
  - `views/components/grid_cell.clj`: `effects-label` `:73` names the first effect as a cue cell names its first preset ("scale +1", "hue shift"; a custom name first). NEW `effects-tooltip` `:83` lists the effects, one per line (NEW private `effect-text` `:68`).
  - `views/components/grid_cell.clj`: `generic-grid-cell` takes NEW optional `:tooltip-fn` (`:104`), set on the label that carries the cell's menu (`:205-211`). `subs/effect-cell-display-data` gives NEW `:effect-ids` (`subs.clj:329`).
- Notices:
  - `state/domains.clj:136`: NEW `[:ui :notice]`, nil or `{:id :text :level}`.
  - `events/helpers.clj:109`: NEW `show-notice [state text level]` returns `{:state … :dispatch-later {:event {:event/type :ui/clear-notice :id id} :delay-ms notice-ms}}` (NEW `notice-ms` 6000, `:105`), with a `random-uuid` id per notice, like the transport requests' ids.
  - `events/handlers/ui.clj:61`: NEW `:ui/clear-notice` (routed `:155`) clears the notice only if its `:id` is the one shown; otherwise it returns `{}`, so a stray timer writes nothing back.
  - `events/handlers/menu.clj`: `handle-file-project-load-failed` `:99` shows "Could not open friday.zip", and `handle-file-save-failed` `:119` "Could not save friday.zip", after the same project-status restore as before. NEW private `file-name` `:94`.
- Stream with nothing enabled:
  - `subs.clj:415`: `:idle` reads "No projectors enabled".
  - `views/toolbar.clj:263-269`: while `:idle`, NEW `#streaming-projectors-link` "Projectors…" (a `:hyperlink`, NEW `.toolbar-link`, `css/layout.clj:74`) dispatches `:ui/set-active-tab :projectors`.
- Removed: `subs/active-cell`, `active-cell-preset`, `active-preset` and the deprecated `ex/active-cell` (`state/extractors.clj`). The status bar was their only reader; handlers still write `[:playback :active-cell]`.

**State**: `[:ui :notice]` (NEW) is UI state, written by the two failure handlers (through `show-notice`) and by `:ui/clear-notice`. No undo, no dirty. Everything else is subs.

**Differences from the plan**

| Plan (§3) | As built | Why |
|---|---|---|
| The Transport menu gets one item, "Play" or "Stop (blackout)" | One item shows; the old Stop item stays hidden, so Escape still stops while playing | Without it Escape would stop nothing, and the keyboard plan's Phase 5 reserves it as the stop key (`escape-always-stops-test`, D10 A). A scene `:accelerators` map can't carry it: cljfx clears and refills the scene's accelerators, which would remove the menu's. JavaFX runs an enabled item's accelerator whether it shows or not (`ControlAcceleratorSupport` checks `isDisable` only) |
| Play's tooltip says "Resume 4 cues" | The Stop button's tooltip is "Stop (blackout)" too (it said "Stop") | The menu item's name |
| NEW subs `output-held?` and `playing-cues` | Also NEW `held-cue-count`, and `notice` (one `sub-val`) | The chip and the tooltip need the number; `output-held?` is `held-cue-count` above 0 |
| `playing-cues`: the cells "with their names" | The sub gives what each cell's name is made from; the status bar names the cells with `grid-cell/cell-label` | `subs` can't require a view namespace: `grid-cell`, and the dialog shell to come, require `subs` |
| "Playing: A1 circle · A2 square · +2" | At most two named, then "+n" (three cues: "… · +1") | The example's four cues, as one rule |
| The µs stats only in dev mode; "in their place, output health" | Output health shows in both modes; dev mode adds the stats before it | A dev session, the usual way to run the app, sees it too |
| Output health from `subs/connection-status` | Its state and errors, and each projector's `:fps` from `subs/streaming-status` (which `connection-status` reads), shown as a range | F-9 asks for the fps. With fps inside `connection-status`, the toolbar's streaming status would re-render up to 4 times a second |
| `[:ui :notice] {:text :level}` | `{:id :text :level}`; `:ui/clear-notice` clears only its own id | An earlier notice's timer must never clear a later notice |
| "One cell name everywhere ('A1')", from `cell-label` | `cell-label` is used by the status bar and the trigger editor only | The cue chain, effect chain and timeline editors already say "Cell A1", with their own formula (`views/dialogs/cue_chain_editor.clj:470, 674`, `effect_chain_editor.clj:103, 238`, `timeline_cue_editor.clj:107, 213`). Moving them onto `common` (`default-name`, `window-title`) is the keyboard plan's Phase 2 |
| Held cells: "a blue outline, no fill" | A dashed 2 px blue outline; inside, the content colour | A solid blue outline is the selected cell's |
| — | An effects cell that is on with no enabled effects looks empty (`#3D3D3D`, hovered `#606060`) | The playing rule needs `grid-cell-content`; such a cell changes nothing on output. §8 noted it hovered grey |
| The single-cue status items go (F-6) | Their subs and `ex/active-cell` go too | Nothing else read them; "the first key of the map" is F-6 itself |
| A "Projectors…" link | Its own class `.toolbar-link`, and not focus-traversable | cljfx's `:style-class` replaces `hyperlink`, as for every control here. Not traversable, so a click doesn't give it the focus, and Space still reaches the Play/Stop shortcut |

**Found, not done**
- A click during a blackout plays. `cue-timing/deactivate-cue` (`animation/cue_timing.clj:335`) sets `:playing?` to "any cue left", so toggling one held cue off resumes the others. A click on any cue cell (`activate-cue` `:322`) starts it with all the held ones. The screen shows it truthfully now (the cells turn blue, the chip goes). Whether a click should end a blackout is the output's question (O4, the vibe plan's sticky blackout).
- A notice survives File > New and Open: `persistent/reset-session` (`state/persistent.clj:135-150`) resets the dialogs but not `[:ui :notice]`. It goes after its own 6 s.
- A failed network scan (`events/handlers/projector.clj:189-193`) still only logs, though invariant 5 names devices. §3 named the open and save handlers only.
- `ui/query` (`test/laser_show/ui_test/testfx.clj:65-74`) looks in the topmost window only. A tooltip that comes up hides the main window from it: an effects cell's, or a toolbar button's after about a second under a resting mouse. The new tests read through `query-all` or move the mouse off the button. An older test that rests on a button with a tooltip could time out on a slow machine.
- `.grid-cell-selected` and `.grid-cell-learning` still beat `.grid-cell`'s border by hash order, one class each; they do today.
- " (no enabled projectors)" (`subs.clj:417`) can still show, for an error while nothing is wanted (an engine failing as it is stopped).

**Tests** (UI: `with-toolkit` once, `with-app` each)
- NEW `laser-show.views.dialogs.common-test/cell-label-test`: A1; D8 for col 7, row 3; B3; nil, nil is A1.
- `laser-show.subs-test`:
  - `output-held-sub-test`, through `:transport/stop` and `:transport/play`: 3 cues held, count 3; none after Play; with no cue left, never held.
  - `playing-cues-sub-test`: cues started at B1, A2, A1 come back A1, A2, B1, each with its name fields. The list stays identical after a pause inside an intent and a show-clock status; a stopped cue leaves it; it is empty while held.
  - `connection-status-says-why-nothing-streams-test`.
  - `effect-cell-display-data-test` now expects `:effect-ids`.
- `laser-show.views.status-bar-test`:
  - `playing-list-test`: nil; one cue; a custom name and "star +2"; two named, then "+2" or "+1".
  - `output-health-text-test`: nil while off, stopping or idle; "Starting…"; "59.8 fps"; the range; "· 1 error"; "2 errors".
  - `dev-stats-only-in-dev-mode-test`: the stats only in dev mode; the health in both; the notice first, then the playing list.
- `laser-show.views.components.preview-test/frame-stats-only-in-dev-mode-test`.
- `laser-show.events.menu-file-handlers-test`:
  - `failed-save-sets-a-notice-test`.
  - `failed-open-sets-a-notice-test`, also through the pipeline from `:file/open-result`.
  - `open-missing-file-keeps-state-test` now checks that only the notice changed.
- `laser-show.events.ui-handlers-test`:
  - `notice-goes-after-6-s-or-on-a-click-test`.
  - `earlier-notice-timer-keeps-a-later-notice-test`: the first notice's timer leaves the second, also when a click took the first away; the second's own timer clears it.
- UI `laser-show.ui-test.toolbar-test/stop-shows-output-off-and-held-cells-test`:
  - two cells clicked, then Stop: "OUTPUT OFF · 2 cues held"; both cells `grid-cell-held` and not `-active`; Play's tooltip "Resume 2 cues".
  - Play: the cells are active, the chip is gone, "Stop (blackout)".
  - both cells toggled off: "Play".
- UI `laser-show.ui-test.grid-test`:
  - `held-cell-has-an-outline-and-no-fill-test`: `#505050` with a dashed `#4992DB` outline; hovered `#656565`, still dashed. A selected empty cell's outline is solid, and selecting it resumes nothing.
  - `effects-cell-on-and-off-look-different-test`: off `#505050` at opacity 1.0, "scale +1", tooltip "scale\nhue shift"; on `#4992DB`, hovered or not; off again `#656565` hovered, then `#505050`.
  - The helpers read through `query-all`, because the cell's tooltip window hides it from `ui/query`.
- UI `laser-show.ui-test.menu-bar-test/transport-menu-has-one-play-stop-item-test`: ["Play" "Retrigger"], and the open menu shows two items. While playing ["Stop (blackout)" "Retrigger"]. An Escape key event in the main window stops, and the cue is held.
- UI `laser-show.ui-test.streaming-status-test/no-projectors-enabled-links-to-the-projectors-tab-test`: Stream with only a disabled projector says "No projectors enabled" beside Stop, and the link opens the Projectors tab. Once the projector is enabled, "Streaming to 1 of 1" and no link.
- NEW UI `laser-show.ui-test.status-bar-test`:
  - `status-bar-names-what-plays-test`: "A2 square", then "A1 circle · A2 square"; gone while held, back after Play.
  - `failed-open-shows-a-notice-until-clicked-test`.
  - `notice-goes-by-itself-after-6-s-test`: a second notice, 3 s after the first, is still up 7 s after the first; its own timer takes it. About 10 s.
- UI `laser-show.ui-test.trigger-editor-test/cell-trigger-editor-opens-and-closes-modal`: the title is "Triggers — Cue Cell A1".

**Red first** (at `67735d0`, with the tests and no source change)
- Unit, one namespace at a time:
  - `common-test` could not load `laser-show.views.dialogs.common`.
  - `subs-test`: "No such var: subs/output-held?". `views.status-bar-test`: "No such var: status-bar/playing-list". `ui-handlers-test`: "No such var: h/show-notice".
  - `views.components.preview-test`: 3 tests, 1 failure (the µs label outside dev mode).
  - `menu-file-handlers-test`: 29 tests, 8 failures. No notice after a failed save or open, so no id and no `:dispatch-later`; and `open-missing-file-keeps-state-test`, because `[:ui :notice]` wasn't a declared key.
- UI, the 9 new or changed tests in one run: 9 tests, 5 failures, 8 errors.
  - An off effects cell was `#4992DB` at opacity 0.7, and "scale +1" never came; no `grid-cell-held`.
  - The menu read ["Play" "Stop" "Retrigger"]; the Stop tooltip was "Stop", and no chip came.
  - No `#status-playing` and no `#status-notice-text`; the toolbar never said "No projectors enabled".
  - The title was "Triggers — Cue Cell [A, 1]".

**Verification, 2026-11-19, macOS arm64, JDK 25**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test -n` views.dialogs.common-test, subs-test, views.status-bar-test, views.components.preview-test, menu-file-handlers-test, ui-handlers-test | 57 tests, 243 assertions, 0 failures |
| `clj -M:test-ui:mac-arm:test-ui-headless -n` toolbar, grid, menu-bar, streaming-status, status-bar, trigger-editor, preview, main-window, in one run | 41 tests, 158 assertions, 0 failures |
| `clj -M:mac-arm:test` (full unit suite) | 880 tests, 32,552 assertions, 0 failures (868 before, 12 new) |
| `clj -M:test-ui:mac-arm:test-ui-headless` (full UI suite) | Not run here: it runs after the merge onto master. 79 tests now, 71 before |
| After the merge, on `master` at `c556d71` (with vibe Phase 1): `clj -M:mac-arm:test`, then the full UI suite | 937 tests, 32,925 assertions, 0 failures; UI 79 tests, 311 assertions, 0 failures |
| Again at `f7ec338` (vibe Phase 2 on top) | 951 tests, 33,040 assertions, 0 failures; UI 89 tests, 376 assertions, 0 failures |
| `clj -M:kondo --lint` on every touched file | 0 errors, 0 warnings |
