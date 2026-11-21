# The Projectors tab: a layout pass

**Written:** 2026-11-20 at `cc9e000` (`master`: Z0–Z3, ZR and Decimate in).   **Status:** planned.   **Asked for by the user** (the direction approved): setting up a laser at 1200 × 800 should need no hunting. A divider to drag between the rig strip and the inspector; the strip keeps its own scrolling; each inspector column scrolls on its own; defaults that show the whole safety canvas and its Add / Reset buttons at 1200 × 800 with a laser selected; the divider's place kept for the session (UI state: never saved with the show or the rig, not undoable). No behaviour changes beyond layout, and every node id kept.   **Built alongside** Z4 and a Decimate follow-up (the card's load meter): see §2's last list.

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. **NEW** marks anything that doesn't exist yet.

## 1. What's there

The tab (`views/tabs/projectors.clj` `projectors-tab`) is one v-box: the header, the rig file line (ZR), the rig header (`rig-strip/rig-header`), the strip (`rig-strip/rig-strip`, a scroll pane whose `:min-height` is `strip-height`, the cards and every group row, up to 400 px) and the inspector (`projector-config-panel`): one scroll pane around three columns, the settings (300 px), the calibration chain with Remove… (280 px) and the rest, where the selected effect's editor, SAFETY ZONE (`safety-editor/safety-section`, a 260 px canvas, its rows and buttons) and TEST PATTERN (`calibration-controls`) sit, pushed to the bottom by a growing region.

Screenshots before any change, from a scratch UI test (not committed) with two lasers, Laser A selected, its zone a limit and one blocked area, and the app's "Rig saved 16:32" line (a rig file is set, as in the app; the tests have none, which gives the strip and inspector 22 px more):

- `/private/var/folders/19/1y1fw6cs2z3bt3f1rjy5d92m0000gn/T/opencode/projectors-layout-shots/before-1200x800.png`
- `/private/var/folders/19/1y1fw6cs2z3bt3f1rjy5d92m0000gn/T/opencode/projectors-layout-shots/before-1440x900.png`

What they show (scene y, px):

| | 1200 × 800 | 1440 × 900 |
|---|---|---|
| The strip | 228–551: 323 px, its whole content (the cards 124 px, nine rows, + Group) | the same |
| The inspector's viewport | 558–757: **199 px** | 558–857: 299 px |
| Shown in it | CONFIGURE, Name, Host / Port, Scan rate, Output, Identity | also the tags, Exclude from All, Remove… |
| Below the fold | the tags (742), Exclude from All, FIT GUARD (803–920), Remove…, the safety canvas (610–870, cut), its rows, **+ Add blocked area / Reset (934–963)**, Copy to… (969–998), **TEST PATTERN's buttons (1034–1063)** | FIT GUARD (cut), the canvas (cut at 857), Add / Reset, Copy to…, TEST PATTERN |

The inspector scrolls as one: reaching TEST PATTERN scrolls the settings and the chain away too. The right column needs 389 px from its top to the bottom of Add / Reset (one blocked area) and 489 px to TEST PATTERN's buttons; with the strip at 323 px there are 199. Also, FIT GUARD's "Draw fewer points below it" row needs 314 px and its column has 300: its box's text and its "%" are cut to "…" when it is scrolled into view.

Five UI tests scroll to the inspector's sections first, with eleven `ui/scroll-into-view!` calls: `safety-editor-test/add-and-remove-a-blocked-area-test` (2), `rig-strip-test/audience-tag-offers-exclude-from-all-test` (4), `setup-dialogs-test/calibration-brightness-slider-test` (2) and `decimate-is-offered-test` (1), `rig-card-test/fit-guard-settings-apply-test` (2).

## 2. The design

**A split pane between the strip and the inspector.** The header, the rig file line and the rig header stay on top. Below them a vertical `:split-pane` (NEW `#projectors-split`) holds the strip and the inspector, with the app's divider (the one between a tab and the preview, `views/root.clj`).

- **The strip keeps its scroll pane** (`#rig-strip-scroll`, both bars as needed). Its `:min-height` becomes its cards' row (`rig-strip/cards-height`, NEW: the card with its thumbnail and note, plus room for the horizontal bar with more than 6 cards), so the divider can't cut a card, and its `:max-height` all its rows (`strip-height`, without the 400 px cap), so the divider stops below + Group.
- **The inspector's columns each scroll on their own**: three scroll panes in an h-box, NEW `#projector-column-left` (fixed, wide enough for FIT GUARD's rows), `#projector-column-middle` (fixed) and `#projector-column-right` (the rest).
- **TEST PATTERN moves to the top of the middle column**, above CALIBRATION EFFECTS: the three columns start with CONFIGURE, TEST PATTERN and SAFETY ZONE side by side. In the right column it can't share the height with the safety zone: the zone alone takes about 390 px, and 1200 × 800 has about 530 for the strip and the inspector together. It is the calibration controls it was before Z1 (`calibration-controls`, `:projectors/toggle-calibration`), and the test pattern shows the calibration and the safety outline, so it sits between the two. The right column is the selected effect's editor (when one is selected, as now) and SAFETY ZONE, without the region that pushed them down.

**Where the divider starts (the default): the inspector first.** Until the divider is moved, the inspector gets the height its right column needs down to SAFETY ZONE's Copy to… row with one blocked area (`inspector-room`, the canvas + 170 px: 430 px), and the strip the rest, no shorter than its cards and no taller than its rows. It follows the window while it hasn't been moved. At 1200 × 800 that is the strip's cards (132 px) and 392 px for the inspector (414 without a rig file line): the canvas and Add / Reset show, with TEST PATTERN, all of the settings and the chain; Copy to… is under the fold. At 1440 × 900 the strip also shows four or five group rows and the inspector everything. From a window about 1,030 px tall (1,010 without the rig file line) the strip shows all of its rows. Why: the inspector is where a laser is set up, the cards are how one is picked, and the rows (which groups a laser is in) are the strip's to scroll, or to drag the divider down for. A fixed fraction can't promise the safety zone at 1200 × 800 (the rig file line and the number of rows change the room), and a strip that always keeps only its cards wastes the room on larger windows.

**Kept for the session.** The mouse letting go of the divider stores the split pane's position (0–1) in NEW `[:rig-ui :divider]`, through NEW `:rig/set-divider {:position}` (`events/handlers/rig.clj`), as `views/root.clj` `remember-split` does for the preview's divider. A layout pass that moves it, as a resize does, stores nothing. While it is set, the split pane takes it back when the tab is shown again; another laser selected, or any other render, leaves it alone. `:rig-ui` is UI state: no undo step, never unsaved, not in a show or the rig file, kept by New and Open.

**Unchanged:** every node id; the strip's cards, rows and keys; the sections' contents; the events, apart from the NEW one.

**Files:**

- `views/tabs/projectors.clj`: `projectors-tab`, `projector-config-panel`, NEW `inspector-column`, the divider's default and its event filter.
- `views/components/rig_strip.clj`: `strip-height` and NEW `cards-height`, and two lines of `rig-strip` (`:min-height`, `:max-height`). The Decimate follow-up edits `load-meter`, higher up; no other line here is shared.
- `events/handlers/rig.clj`: NEW `handle-set-divider` and its `case` row. `state/domains.clj`: `:divider` in `rig-ui`.
- Not touched: `subs.clj` (the tab reads `[:rig-ui :divider]` with `fx/sub-val`, as the strip reads `[:rig-ui :drop]`), the Zone Groups tab, the safety editor, the card.

## 3. Tests

- UI, NEW `laser-show.ui-test.projectors-layout-test`:
  - `setting-up-a-laser-needs-no-scrolling-test`: at 1200 × 800, two lasers, one selected, its zone with one blocked area. With no scroll pane scrolled, the safety canvas, `#safety-add-blocked-button`, `#safety-reset-button`, `#projector-calibration-button`, `#projector-identify-button`, FIT GUARD, the tags and Remove… are inside the window (`ui/scene-bounds`, above the status bar) and inside the viewport of every scroll pane around them. At 1440 × 900, Copy to… and the All row show too.
  - `dragged-divider-stays-for-the-session-test`: dragging the divider 80 px down makes the strip 80 px taller, and stores it (no undo step, the show not unsaved); selecting another laser, then another tab and back, leaves it there.
  - `each-column-scrolls-on-its-own-test`: scrolling the right column to its end leaves the left and middle columns where they were.
- `laser-show.events.rig-handlers-test`: `divider-is-kept-for-the-session-test`: the event stores the position, ignores one outside 0–1, makes no undo step and no unsaved change, and the position is in neither a show's files nor the rig file.
- Changed: the eleven `ui/scroll-into-view!` calls go (the helper stays). The rows start under the cards' row at 1200 × 800, so the tests that click a row scroll the strip to it first: `rig-strip-test`'s `custom-row-toggles-membership-test`, `all-row-click-excludes-test` and `row-name-opens-the-group-test`, and `zone-groups-tab-test/both-tabs-show-the-same-membership-test`.
- Run while building: those namespaces, and every UI namespace that opens the Projectors tab (`rig-card-test`, `rig-report-test`, `streaming-status-test`, `preview-test`, `main-window-test`, `grid-test`). Both full suites once at the end.

## 4. Commits

Failing tests first in each step, run red against the step before.

| # | Commit | Tests |
|---|---|---|
| 1 | `plans: the Projectors tab's layout pass (13)` | – |
| 2 | `feat(projectors): each inspector column scrolls on its own, TEST PATTERN at the top of the middle one, the left column wide enough for FIT GUARD` | `each-column-scrolls-on-its-own-test` |
| 3 | `feat(projectors): a divider between the rig strip and the inspector: until it is moved the inspector gets the room its safety zone needs, and where it is left is kept for the session` | `setting-up-a-laser-needs-no-scrolling-test`, `dragged-divider-stays-for-the-session-test`, `divider-is-kept-for-the-session-test`; the row tests scroll the strip |
| 4 | `test(ui): no scrolling to the inspector's sections` | the five tests that scrolled |
| 5 | `plans: the Projectors tab's layout pass, as built` | – |
