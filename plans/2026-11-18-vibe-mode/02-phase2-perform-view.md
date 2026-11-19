# Phase 2: the Perform tab

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** Done in `94f905e` (2026-11-19); V8 answered B (2026-11-18: the vibe stuff gets its own tabs, nothing on the Grid tab). As built: §10   **Last verified against:** `94f905e` (§10)
**Supersedes / Superseded by:** none   **Related findings:** review 01 T5 (narrow subscriptions); keyboard plan K-15 (learn also fires the old binding) shows up when a role is re-learned

A screen you can read from two metres, five pads you can click, touch or press from any controller, and a way to teach a pad which key it is. With Phase 1 this is the first usable version.

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| Tabs | `views/root.clj:74-80` (`main-tabs`), `:57-68` (`tab-content`), `:82-89` (`tab-bar`) | Grid, Effects, Projectors, Zone Groups, Settings. The active tab is `[:ui :active-tab]`, set by `:ui/set-active-tab` (`events/handlers/ui.clj:13-16`) |
| Preview pane | `views/root.clj:95-117` | shown beside every tab except `#{:projectors :zones :settings}` (`:100`), so a new tab gets it for free |
| Toolbar readouts | `views/toolbar.clj:117-129` (TAP), `:142-157` (beat indicator, `subs/beat-index` `subs.clj:56`), `:218-245` (output status, `subs/connection-status` `subs.clj:364`) | the beat square changes on each beat only |
| Widgets drawn from the show clock | `views/components/clock_view.clj:58-71` (`ext-clock-redraw`) | a node redraws from the newest tick on each screen refresh, without app state |
| Cell labels and "playing" | `views/components/grid_cell.clj:49-63` (`cell-label`); `subs.clj:205-283` | `cell-display-data` is memoized per cell and ignores unrelated changes (`test/laser_show/subs_test.clj:62`) |
| Trigger map, learn state | `subs.clj:769-780` (`input-trigger-map`, `input-learning-target`) | learn badge on a grid cell: `views/components/grid_cell.clj:174-177, 226` |
| Learn | `events/handlers/input.clj:223-270` | binds the next note or trigger to a `target-action`. Learning an action that already has a key **adds** a second key; learning a key that is bound elsewhere **overwrites** that binding |
| Binding labels | `views/components/trigger_bank.clj:56-63` | `"MIDI Ch 1 Note 36"`, `"OSC /x"`, `"Key :space"` |
| CSS | `css/core.clj:16-78`; `css/grid_cells.clj:20-60`; `css/theme.clj` | one registered namespace per area, listed in both URL functions |
| Window focus | `views/root.clj:164-180` | the main stage has no focus listener |
| Press and release by robot | `test/laser_show/ui_test/testfx.clj:403, 497` (`release!`, `press!`) | a held pad can be tested |

## 2. Root cause

The engine (Phase 1) has no face. Nothing shows which vibe is current, which key is held, or what a press just played; there is no way to press a role without a bound key; and learn can't aim at a role.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `styled-tab-bar`, `main-tabs`, `tab-content` (`views/root.clj:57-89`) | one more tab |
| `ext-clock-redraw` (`views/components/clock_view.clj:58-71`) | the meters of Phase 5, and the pulse bar of Phase 4 |
| `cell-label` (`views/components/grid_cell.clj:49-63`) | the name of the cue under HIT and ACCENT |
| `input-trigger-map`, `input-learning-target` (`subs.clj:769-780`) | binding labels, the learn badge |
| `:input/start-learn`, `:input/cancel-learn`, `:input/learn-captured` (`events/handlers/input.clj:223-278`) | learn a role like a cell |
| `trigger-bank` label rules (`views/components/trigger_bank.clj:56-63`) | made one public `binding-label`, used by both |
| `subs/connection-status`, `midi-connected-devices` (`subs.clj:364, 705`) | the output and MIDI chips |
| `subs_test.clj` memoization pattern (`:62`) | tests that a HIT press leaves the other pads alone |

## 4. Changes per namespace

- `laser-show.views.root` (existing): `main-tabs` gets `{:id :perform :label "Perform"}` after Grid; `tab-content` gets `:perform`. The main stage gets `:on-focused-changed {:event/type :vibe/window-focus}` (a window property in cljfx 1.10.6, `cljfx/fx/window.clj:29`; the event carries the new value as `:fx/event`).
- `laser-show.views.tabs.perform` (NEW): `perform-tab`: header, vibe strip, pad row, status line. In Phase 5 the meters go under the pads; Phase 4 adds a pulse bar and a reactions line to each pad.
- `laser-show.views.components.vibe-pads` (NEW): `pad` (props `:role`) and `pad-row`. Pointer **and touch**: press → `:vibe/press`, release → `:vibe/release`, both with `:source :pad`. Right-click menu: "Learn control…" (`:input/start-learn {:target-action {:type :vibe :role r}}`), "Cancel learning", "Clear control" (`:vibe/clear-control`). A pad has a stable `:id` (`vibe-pad-hit` …) for tests, and is **not focus-traversable**, so Space or Enter on the keyboard can never press it a second time.
- `laser-show.views.components.vibe-strip` (NEW): one chip per vibe: name, colour, energy dots, `now` and `next` marks. A click is `:vibe/select`.
- `laser-show.views.components.trigger-bank` (existing, `:56-63`): the label `case` becomes public `binding-label`.
- `laser-show.subs` (existing): NEW subs, all memoized and narrow: `vibe-config`, `vibe-order`, `vibe-current-id`, `vibe-strip`, `vibe-header`, `vibe-pad [role]`, `vibe-blackout?`. See §7.
- `laser-show.css.vibes` (NEW) registered in `css/core.clj` in both `all-stylesheet-urls` (`:35-55`) and `dialog-stylesheet-urls` (`:57-78`): `.vibe-header`, `.vibe-chip`, `.vibe-chip-current`, `.vibe-pad`, `.vibe-pad-held`, `.vibe-pad-learning`, `.vibe-pad-dim`, `.vibe-banner`. Colours come from `css/theme.clj`; a vibe's own colour is data and is applied inline, as the zone group colours are (`views/components/zone_chips.clj`).
- `laser-show.events.handlers.input` (existing, `:258-270`): when the learned action is `{:type :vibe :role r}`, first remove every other trigger-map entry with that same action, so re-learning **moves** a role. The undo label says which action the key replaced, if any.
- `laser-show.events.handlers.vibes` (existing from Phase 1): NEW `:vibe/clear-control` (removes a role's entries), `:vibe/window-focus` (on `false`, releases the pad-held roles: `:vibe/release-all {:only :pad}`).
- `laser-show.vibes.engine` (existing from Phase 1): nothing to add. `held` entries already carry their source, `{:at 1000 :src :pad}` or `:src :input`, so a focus loss releases a mouse hold but not a MIDI one.
- `laser-show.events.handlers.history` (existing, `:125-142`): `event-descriptions` gets `:vibe/clear-control "Clear Control"`.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:ui :active-tab]` `:perform` | UI state (existing) | `:ui/set-active-tab` | no / no |
| `[:config :input :trigger-map]` role entries | document (existing) | learn, `:vibe/clear-control` | yes / yes |
| `[:vibe-runtime :held role]` `{:at :src}` (Phase 1) | app state, session | the engine | no / no |
| pointer state of a pad | none: the pad holds nothing. Press and release are events | – | – |

No new key. The view never writes state and never closes over it (`docs/STALE_CLOSURES.md`).

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:vibe/press`, `:vibe/release` | Phase 1 | a pad's pointer and touch handlers (`views/components/vibe_pads.clj`) | yes |
| `:vibe/select` | Phase 1 | a strip chip (`views/components/vibe_strip.clj`, NEW) | yes |
| `:vibe/clear-control {:role}` | NEW | the pad menu (`views/components/vibe_pads.clj`, NEW) | yes |
| `:vibe/window-focus` | NEW | the main stage's `:on-focused-changed` (`views/root.clj:164-180`) | yes |
| `:vibe/release-all {:only :pad}` | Phase 1 | `:vibe/window-focus` (inline `:dispatch`) | yes |
| `:input/start-learn`, `:input/cancel-learn`, `:input/learn-captured` | yes (`events/handlers/input.clj:223-278`) | the pad menu, learn | yes |
| `:ui/set-active-tab` | yes (`events/handlers/ui.clj:13-16`) | the tab bar (`views/root.clj:82-89`) | yes |

No new effect key.

## 7. Data examples and invariants

### The screen

```
┌ Perform ───────────────────────────────────────────────────────────────┐
│ ████ GROOVE ████████████████████████████    128.0 BPM  ■ □ □ □         │  header: vibe colour, name, tempo, beat
│ energy ●●○○     STREAMING 2/2        MIDI: Pad 4x4 ✓                    │  output and controller chips
├────────────────────────────────────────────────────────────────────────┤
│ [ Warm-up ] [ GROOVE  now ] [ Build  next ] [ Peak ]                    │  vibe strip: click to jump
├────────────────────────────────────────────────────────────────────────┤
│ ┌────────┐ ┌────────┐ ┌────────┐ ┌─────────┐ ┌──────────┐              │
│ │  HIT   │ │ ACCENT │ │ FLASH  │ │  VIBE   │ │ BLACKOUT │              │  five pads, 120 px or more
│ │ Circle │ │  –     │ │        │ │ → Build │ │          │              │  what plays now / what is next
│ │ C2     │ │ C#2    │ │ D2     │ │ D#2     │ │ E2       │              │  the key each one answers to
│ └────────┘ └────────┘ └────────┘ └─────────┘ └──────────┘              │
└────────────────────────────────────────────────────────────────────────┘
```

Blackout adds a red full-width banner "BLACKOUT" and dims the other four pads. Learning shows "Press the key for HIT" in the header and a `LEARN` badge on the pad.

### The subscriptions

```clojure
(fx/sub-ctx ctx subs/vibe-pad :hit)
;; => {:role :hit :label "HIT" :bindings ["MIDI Ch 1 Note 36"] :held? false
;;     :playing "Circle" :learning? false :problem nil :dim? false}
(fx/sub-ctx ctx subs/vibe-strip)
;; => [{:id :vibe-1 :name "Shapes" :color "#4A90D9" :energy 2 :current? true  :next? false}
;;     {:id :vibe-2 :name "Lines"  :color "#9B59B6" :energy 3 :current? false :next? true}]
(fx/sub-ctx ctx subs/vibe-header)
;; => {:name "Shapes" :color "#4A90D9" :energy 2 :rotation :active :blackout? false}
```

`:playing` is the cue name only while the voice's run is still the cell's run (`cell-label`), so a decayed hit clears itself.

### Invariants

1. A pad lights while its role is held, whatever pressed it (a key, an OSC button, a pointer).
2. A pointer hold ends on release, and also when the window loses focus (`:vibe/window-focus`). A MIDI hold does not end on focus loss.
3. Pressing a pad and pressing its learned key give the same `:vibe/press` (differing only in `:src` and the time source).
4. A HIT press re-renders the HIT pad (and the ACCENT pad only if its text changes). The strip, header and other pads' subscriptions return identical values.
5. A vibe change re-renders the strip, header and the VIBE pad's "→ next" text. Nothing else.
6. Re-learning HIT leaves exactly one key bound to HIT. Learning a key that was bound to another action replaces it, and the undo label names it.
7. No modal and no dialog. Pads are not focus-traversable, so the scene keeps the keyboard (the keyboard plan's filter sees every key first).
8. With no vibes: the tab says "No vibes yet. Open the Vibes tab" (Phase 3) and every pad is dim.

## 8. Tests (named test vars + fixtures)

Unit, `laser-show.subs-test` (existing, pattern `:62`): NEW `vibe-pad-sub-test`, `vibe-strip-sub-test`, `hit-press-leaves-the-strip-and-other-pads-alone-test` (invariant 4), `playing-name-clears-when-the-run-ends-test`.

Handlers:
- `laser-show.events.handlers.input-test` (existing): NEW `relearning-a-role-moves-its-binding-test`, `learning-over-another-action-names-it-in-the-undo-label-test`.
- `laser-show.events.handlers.vibes-test` (existing from Phase 1): NEW `clear-control-removes-only-that-role-test`, `window-focus-lost-releases-pad-holds-only-test`.

UI, `laser-show.ui-test.perform-test` (NEW; `with-toolkit` once, `with-app` each; a fixture also calls `input-mapper/set-dispatch-fn!` with `events/dispatch!` and `start-mapper!`, and `stop-mapper!` after, because `start-test!` doesn't start it, `app.clj:251-271`):
- `perform-tab-shows-five-pads-test`
- `hit-pad-hold-plays-then-release-cuts-test` (`ui/press!`, wait for the intent, `ui/release!`; held > 150 ms, so the cue is cut)
- `hit-pad-quick-click-leaves-a-decay-test` (the intent has `:decay-beats` and `:hold? false`; no clock runs in `start-test!`)
- `vibe-pad-tap-switches-and-long-press-locks-test`
- `blackout-pad-shows-banner-and-dims-the-rest-test`
- `learn-binds-a-role-to-a-note-test` (`router/dispatch!` a note-on, as `test/laser_show/ui_test/trigger_editor_test.clj:48-52`), `a-learned-note-plays-through-the-mapper-test`
- `strip-chip-click-selects-test`
- `pad-hold-ends-when-the-window-loses-focus-test`

Manual (unverified): multi-touch on a real touchscreen, and the legibility of the screen at 2 m (Phase 7 hardware check).

Run: `clj -M:mac-arm:test subs input vibes`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.perform-test`, then both full suites (`.github/copilot-instructions.md`, "UI Regression Requirement").

## 9. Out of scope / alternatives considered

- **The pulse bar and the reactions line**: Phase 4. **The faders' meters**: Phase 5. **The auto-advance countdown**: Phase 6. **Show lock, audience marks**: Phase 7.
- **One tab with an Edit / Perform switch**: rejected: it hides the preview rules and doubles the state. Two tabs, one purpose each.
- **A compact vibe bar on the Grid tab** (V8 A) and **the pads only on the Grid tab** (V8 C): not chosen (V8 B, 2026-11-18). The vibe stuff has its own tabs, and the Grid tab stays the grid.
- **A full-screen or pop-out Perform window**: later. The dialog stage shell (keyboard plan Phase 2) is the right base for it.
- **Keeping pointer state in the view** (a local atom of held pads): rejected: stale closures, and the focus-loss release needs the engine's `:src` anyway.
- **Showing the cue thumbnail** on the pad: the preview pane already shows the output; names are enough and cheap.

## 10. As built (2026-11-19)

Committed as `94f905e` on top of `c556d71` (built as `3164717` on branch `vibe-phase2` from `6e26ee4`, then cherry-picked past UX Phase 1 with no conflicts; Phase 1 is `9177b1e`). §1–9 cite `b1da0f2`; the lines below are at `3164717`.

**Where it is now** (paths under `src/laser_show/`)
- `views/tabs/perform.clj` (NEW): `perform-tab` `:116` (banner, header, strip, pads, status line), private `banner` `:18`, `tempo` `:31` (BPM and four beat squares, the only part a beat re-renders), `header` `:55` (name in the vibe's colour, energy, LOCKED, "Press the key for HIT"), `status-line` `:96` (`subs/connection-status`'s text, `subs/midi-connected-devices`). The banner is always in the tree, hidden while it has nothing to say, so showing it never makes new nodes of the pads under it (a held pad keeps getting its release).
- `views/components/vibe_pads.clj` (NEW): `pad` `:37`, `pad-row` `:107`. A pad is a `:label` with a mouse-transparent graphic (as a grid cell is, for its context menu), so a press and its release always reach the pad node; `:focus-traversable false`; ids `vibe-pad-hit` … and `-now`, `-keys`, `-badge`; the menu's items `-learn`, `-cancel-learn` (disabled unless this pad is learning), `-clear` (disabled without a binding).
- `views/components/vibe_strip.clj` (NEW): `vibe-strip` `:26`, `energy-dots` `:9`; a chip is a non-focus-traversable button (`vibe-chip-vibe-1` …) whose `:on-action` is `:vibe/select`.
- `css/vibes.clj` (NEW), in both URL lists (`css/core.clj:57, 82`). State rules have one class more than the base rule, and no two state rules that can apply together set the same property (cljfx.css writes a map of more than eight rules in hash order).
- `events/handlers/vibes.clj`: public `pointer-action` `:18` (a raw JavaFX event → `:press`, `:release` or nil), private `handle-pointer` `:40` (`:vibe/pointer`), `handle-window-focus` `:53`, `handle-clear-control` `:62`, routed in `handle` `:69`.
- `subs.clj`: one require (`vibes.library`, `:29`) and the section ";; Vibe mode" at the end, `:862-999`: `vibe-config` `:869`, `vibe-current-id` `:874`, `vibe-next-id` `:883`, `vibe-blackout?` `:890`, `vibe-strip` `:895`, `vibe-header` `:918`, `vibe-role-bindings` `:942`, `vibe-pad` `:977`, and private per-role `sub-val` fns (`vibe-held?`, `vibe-playing-cell`, `vibe-learning?`, `vibe-problem`, `vibes?`, `vibe-locked?`) so a pad's sub depends on booleans and one cell, not on whole maps.
- `events/handlers/input.clj`: `learn-target` `:225` keeps `:role`; NEW private `action-name` `:267`; `handle-input-learn-captured` `:278` removes the role's other bindings first (`:291-292`) and names what it replaced (`:295-297`). `events/handlers/history.clj:148` `:vibe/clear-control "Clear Control"`. `vibes/library.clj:56` NEW `binds-role?`.
- `views/components/trigger_bank.clj:37` public `binding-label`, used by the trigger editor (`:104`) and the pads. `views/root.clj`: the tab `:79`, `tab-content` `:64`, the main stage's `:on-focused-changed` `:176`.

**The brief's corrections (a–j)**
- a. The pads send `:src :pad`, which the engine reads (`engine.clj` `press`); §4's `:source` was wrong.
- b. Only the primary button presses. A right-click opens the menu and never presses or releases; so does a macOS Ctrl-click, whose press is a popup trigger. And a pad's release is sent only while the pad's own press still holds the role (`[:vibe-runtime :held role :src]` is `:pad`), so neither a Ctrl-click's release nor a release that comes after a focus loss can cut a key's hold.
- c. Touch: the mouse events JavaFX synthesizes from a touch are left out and a touch counts through its touch events, `TOUCH_PRESSED`/`TOUCH_RELEASED` of the event's own point (`getTouchPoint`). No id bookkeeping is needed: JavaFX fires one event per touch point, at the node that point went down on, typed by that point's state (`Scene.processTouchEvent`), so a second finger on another pad sends that pad its own events and only `TOUCH_STATIONARY` here. The edge is a pure handler, unit-tested with constructed `MouseEvent`, `TouchPoint` and `TouchEvent` objects (no toolkit needed), including a two-point event set (ids 1 and 2). Found on the way: the headless glass robot marks **every** mouse event synthesized and a popup trigger (`HeadlessRobot` calls `notifyMouse(..., true, true)`), so in headless runs a robot click on a pad is (rightly) ignored. NEW `ui/press-node!`, `release-node!`, `mouse-click!` (`test/laser_show/ui_test/testfx.clj:321, 338, 350`) fire a real mouse's events at the node headless, as `fire-modified-click!` already does for modifier clicks, and use the robot in visible runs. Multi-touch on a real touchscreen stays manual and unverified.
- d. `vibe-header`'s `:banner` (`subs.clj:918-937`) is "BLACKOUT" only while nothing lights the room; with `:blackout?` on and cues playing (`:playing?` true and intents left, as a grid click makes) it is "BLACKOUT · vibe keys off · a grid cue is playing" (or "n grid cues are playing"). It reads the playback only during blackout, so a HIT press leaves the header identical. Tests: `blackout-banner-is-honest-test` and the UI test below.
- e. `vibe-pad`'s `:playing` is the cell, not a name; the pad names it with `grid-cell/cell-label` of `subs/cell-display-data` (`vibe_pads.clj:28-29`): subs.clj can't call a view namespace's fn (grid_cell requires subs).
- f. `binding-label` is public; the trigger editor's UI tests pass.
- g. K-15 stays: re-learning HIT by pressing its current key also fires HIT. The learn then changes nothing, so it makes no undo step (asserted).
- h. Nothing depends on UX Phase 1. The subs are in their own section at the end of `subs.clj`, with one require between two that both branches keep. UX Phase 1 also appended to `test/laser_show/subs_test.clj` and put a require after `clojure.test`, so the new subs tests sit between `beat-index-sub-test` and `timeline-dialog-subs-ignore-playback-and-other-cells-test`, with their requires after `subs`. `git merge-tree` of this branch with `master` (`c556d71`, UX Phase 1 done) is clean, and that tree, exported, passes the full unit suite and the perform, trigger editor and main window UI tests (table below). An undo label names cells "A1", as UX Phase 1's `views.dialogs.common/cell-label` does; a handler can't use a view fn, so `action-name` has its own line.
- i. `ui-test.perform-test`'s `with-input-mapper` fixture (`:25`) sets the mapper's dispatch fn to `events/dispatch!`, starts it, and stops it and clears the fn after.
- j. Headless glass can't move the focus to another window: `HeadlessWindow._requestFocus` only notifies the window that asks, and glass `Window.requestFocus` refuses `FOCUS_LOST` for a top-level window. So `notify-focus!` (`perform_test.clj:92`) calls the main window's glass `notifyFocus(FOCUS_LOST)` by reflection, as the OS would; from there JavaFX's own path runs (`GlassWindowEventHandler` → `WindowStage` → the Stage's `focused` → cljfx's listener → `:vibe/window-focus`), and the test checks `Stage.isFocused` went false.

**Other differences from the plan**

| Plan | As built | Why |
|---|---|---|
| — (not said) | `learn-target` keeps `:role` (`input.clj:225-230`) | with `select-keys [:type :target]` every role's learn was `{:type :vibe}`: a capture for any role matched any role's learn, and the LEARN badge couldn't tell the pads apart |
| the undo label "names" the replaced action | "Learn Trigger (replaces cue A1)", "(replaces effect B3)", "(replaces HIT)", "(replaces tap tempo)"; plain "Learn Trigger" when the key was free or did the same | the existing "Learn Trigger" stays the base |
| `vibe-pad` `:bindings ["MIDI Ch 1 Note 36"]`, `:playing "Circle"` | `:bindings` are the trigger-map keys, named in the pad by `binding-label`; `:playing` is the cell (e) | subs don't require view namespaces |
| subs `vibe-order` | not added; NEW `vibe-next-id`, `vibe-role-bindings`; the header also has `:banner` and `:learning`, VIBE's pad `:next` and `:locked?`, BLACKOUT's `:on?` | nothing read `vibe-order` (the pads read a boolean, so reordering vibes doesn't re-render them; the strip reads `vibe-config`) |
| "No vibes yet. Open the Vibes tab" | "No vibes yet" in the header's name, every pad dim | there is no Vibes tab until Phase 3 |
| tempo and beat in the header | in their own component inside it | a beat re-renders the tempo only |
| §1: `press!` and `release!` hold a pad | `press!` pushes a key; NEW `press-node!`/`release-node!` (c) | |
| `hit-pad-quick-click-leaves-a-decay-test` | sets `:quick-tap-ms` to 2000 first | a busy machine can't stretch the click into a hold; the 149/150 ms edge is pinned by Phase 1's `long-hold-cuts-on-release-test` |
| pad presses timed by `:time` (01 §6) | as planned | a busy agent can still stretch a pad tap into a hold (probe v12); stamping at the FX edge would need a fn handler instead of an event map |

**Found, not done**
- A Windows touch press-and-hold (the OS's right-click) presses the pad while it opens the menu: manual, with multi-touch.

**Tests**: 24 new (unit 925 → 939, and 10 UI)
- `laser-show.subs-test` (+6): `vibe-pad-sub-test`, `vibe-strip-sub-test` (and the header), `hit-press-leaves-the-strip-and-other-pads-alone-test` (invariant 4, also a HIT that restarts ACCENT's cell), `vibe-change-leaves-the-pads-but-vibe-alone-test` (invariant 5), `playing-name-clears-when-the-run-ends-test` (the clock's `:show-clock/cue-ended`, and the grid restarting the cell), `blackout-banner-is-honest-test` (d).
- `laser-show.events.handlers.vibes-test` (+5): `pointer-presses-with-the-primary-button-only-test`, `one-touch-gives-one-press-and-one-release-test` (one finger with its synthesized mouse events; two fingers on two pads), `pad-release-lets-go-only-of-a-pad-hold-test`, `window-focus-lost-releases-pad-holds-only-test`, `clear-control-removes-only-that-role-test` (undoable, "Clear Control", unsaved).
- `laser-show.events.handlers.input-test` (+2): `relearning-a-role-moves-its-binding-test` (invariant 6; a capture for HIT while ACCENT learns is ignored; a cell still gets a second key), `learning-over-another-action-names-it-in-the-undo-label-test`.
- NEW `laser-show.views.components.trigger-bank-test/binding-label-test`.
- NEW `laser-show.ui-test.perform-test` (10): the nine of §8, and `hit-pad-touch-presses-once-test` (a constructed touch and its synthesized mouse events fired at the pad: one press, one release, one cue picked). `learn-binds-a-role-to-a-note-test` also fires a non-synthesized secondary press and release first (nothing pressed), then learns through the real context menu; `pad-hold-ends-when-the-window-loses-focus-test` also checks that the mouse's late release leaves a key's hold of HIT alone.

**Red first**
- Before the handlers, subs and `binding-label` (only `binds-role?` written): `subs-test` and `trigger-bank-test` didn't compile (`No such var: subs/vibe-pad`, `trigger-bank/binding-label`); `input-test` with `vibes-test`: 16 tests, 90 assertions, 20 failures (input 7: the role as learning target, the move, a capture while another role learns, the four "replaces" labels; vibes 13: every pointer, focus and clear-control assertion).
- `perform-test` with the views stashed (handlers and subs in): 10 tests, 10 errors, each timing out waiting for `#perform-tab`. The first run with the views: 10 errors, "No such prop: :v-box/vgrow": a layout constraint goes on the component's desc in the parent (`{:fx/type pad :h-box/hgrow :always}`), not on the node the component returns.

**Verification, 2026-11-19, macOS arm64, JDK 25**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test subs input vibes trigger-bank` | 162 tests, 863 assertions, 0 failures |
| `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.perform-test` | 10 tests, 65 assertions, 0 failures |
| the same, `-n` trigger-editor-test, main-window-test, grid-test, setup-dialogs-test, toolbar-test | 27 tests, 102 assertions, 0 failures |
| `clj -M:mac-arm:test` (full unit suite) | 939 tests, 32,981 assertions, 0 failures (30 s; baseline 925) |
| `clj -M:kondo --lint` on every touched file | 0 errors; 1 warning, there before (a redundant `let` in `input.clj` `handle-input-update-cell-trigger-action`, `:310` before, `:332` now) |
| this branch merged with `master` `c556d71` (`git merge-tree`, tree exported, no branch touched) | no conflict; full unit suite 951 tests, 33,041 assertions, 0 failures; perform, trigger editor and main window UI 22 tests, 119 assertions, 0 failures |
| full UI suite | not run here: it runs after the merge onto `master` |
| manual (unverified) | multi-touch on a real touchscreen, a visible (non-headless) run, the screen at 2 m (Phase 7 hardware check) |
