# The Projectors tab: a layout pass

**Written:** 2026-11-20 at `cc9e000` (`master`: Z0–Z3, ZR and Decimate in).   **Status:** **Done, on `master`** (`77230f1`..`0a1c219`); the as-built note is §5.   **Asked for by the user** (the direction approved): setting up a laser at 1200 × 800 should need no hunting. A divider to drag between the rig strip and the inspector; the strip keeps its own scrolling; each inspector column scrolls on its own; defaults that show the whole safety canvas and its Add / Reset buttons at 1200 × 800 with a laser selected; the divider's place kept for the session (UI state: never saved with the show or the rig, not undoable). No behaviour changes beyond layout, and every node id kept.   **Built alongside** Z4 and a Decimate follow-up (the card's load meter): see §2's last list.

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

## 5. As built (2026-11-20, `77230f1`..`0a1c219`, on `master`)

Built in worktree `wt-projectors-layout` off `master` at `cc9e000`, while Z4 and the Decimate follow-up were built in theirs. Z4 has since landed (`master` at `4c5fcc8`); "For the merge" has the trial merges. A prototype of the whole design (not committed) was measured and screenshotted before §1–§4 were written; the commits below were then made from it, tests first.

### Where it landed

| # | Commit | Step (§4) |
|---|---|---|
| 1 | `e935ffd` | this plan, §1–§4 |
| 2 | `77230f1` | NEW `inspector-column`: the inspector is three scroll panes, NEW `#projector-column-left` (348 px), `#projector-column-middle` (316 px) and `#projector-column-right` (the rest), without the region that pushed the right column down; FIT GUARD's boxes and "%" keep their whole width; NEW `projectors-layout-test` with `each-column-scrolls-on-its-own-test` and `fit-guard-shows-whole-test` |
| 3 | `53589f1` | NEW `#projectors-split` holds the strip and the inspector; NEW `default-divider`, `follow-default-divider!`, `remember-divider`; NEW `rig-strip/cards-height`, the strip's `:min-height` (its cards) and `:max-height` (all its rows); TEST PATTERN at the top of the middle column; NEW `:rig/set-divider` and `[:rig-ui :divider]`; `setting-up-a-laser-needs-no-scrolling-test`, `dragged-divider-stays-for-the-session-test`, `divider-is-kept-for-the-session-test`; the four tests that click a strip row scroll the strip to it |
| 4 | `0a1c219` | the eleven `ui/scroll-into-view!` calls of §1 are gone (the helper stays) |
| 5 | this commit | this note |

Screenshots (headless, the scratch test of §1, with the app's rig file line; not committed):

- before: `/private/var/folders/19/1y1fw6cs2z3bt3f1rjy5d92m0000gn/T/opencode/projectors-layout-shots/before-1200x800.png`, `…/before-1440x900.png`
- after: `…/after-1200x800.png`, `…/after-1440x900.png`; the divider dragged 100 px down, `…/after-dragged-1200x800.png`; then Laser B and its test pattern on, `…/after-pattern-on-1200x800.png` and, the window enlarged, `…/after-pattern-on-1440x900.png`

What they show (scene y, px):

| | 1200 × 800 | 1440 × 900 |
|---|---|---|
| The strip | 228–360: **132 px, its cards** (the rows scroll, or the divider goes down) | 228–422: 194 px, the cards, the All, Left and Right rows and most of Center |
| The inspector | 366–758: **392 px** (414 in the tests, without the rig file line) | 428–858: 430 px |
| Left column | everything, FIT GUARD at 604–721 | everything |
| Middle column | Show test pattern / Identify 395–424, the chain, Remove… 638–667 | everything |
| Right column | **the canvas 395–655, Limit, Blocked 1, + Add blocked area / Reset 719–748**; Copy to… / Mirror to… (754–783) under the fold, their tops showing | everything, Copy to… at 816–845 |

Dragged 100 px down, the strip is 232 px and `[:rig-ui :divider]` is 0.443, with nothing to undo. It stays 232 px with Laser B selected, and after the Grid tab and back.

### Deviations from the plan

- **TEST PATTERN moved in step 3, not 2.** In step 2 the strip still took its 323 px, and TEST PATTERN at the top of the middle column pushed Remove… out of the window: `setup-dialogs-test/remove-projector-asks-first-test` failed ("bounds are not visible in Scene"). Step 2 left it under SAFETY ZONE; it moved with the divider.
- **FIT GUARD was cut short,** seen in the prototype's screenshots: its "Draw fewer points below it" row needs 314 px and its column had 300, so the box read "Draw fewer points belo..." and its "%" read "...". The left column is 348 px (the row, the padding, the border and the 6 px scroll bar), and the two boxes and the "%" labels have `:min-width :use-pref-size`, so a wider font squeezes the number fields instead. NEW `fit-guard-shows-whole-test`.
- **Widths:** the middle column is 316 px (280 before); the right column is the rest, 504 px at 1200 (its content 472; the Blocked row needs 402).
- **How the default is kept:** while nothing is stored, the split pane is made without a position and `follow-default-divider!` (added by `fx/ext-on-instance-lifecycle`'s `:on-created`) listens to its height. On its first layout and on each resize it sets `default-divider`, until the mouse lets go of the divider (`remember-divider` sets a flag in the split pane's properties and dispatches `:rig/set-divider`). With a stored position the split pane is made with it, and nothing listens.
- **After a drag, a resize** shares the change between the strip and the inspector (JavaFX's SplitPane does; the preview's divider does the same), within the strip's min and max. The stored position is what the tab is shown with again (Q-L-4).
- **The strip's sizes:** `cards-height` is 132 px, 8 more with more than 6 cards (the horizontal scroll bar is 6 px, 8 hovered; about 6 cards fit at 1200). `strip-height` lost its 400 px cap: the divider stops at the strip's rows instead. `inspector-room` is the canvas + 170 px, 430 px.
- **The inspector with no laser selected** ("Click a card to set it up") is a plain v-box, without its scroll pane.
- **`each-column-scrolls-on-its-own-test`** selects RGB Curves on the laser, so its editor makes the right column longer than the inspector, and checks that the settings' title and the chain's Add Effect menu stay put (in step 2 TEST PATTERN was still in the right column).
- **The row tests' scrolls went in with step 3,** with the change that needs them, so every commit is green; step 4 only removes calls.
- **New ids:** `#projectors-split`, `#projector-column-left`, `#projector-column-middle`, `#projector-column-right`. None removed or renamed.

### Tests added

- UI, `laser-show.ui-test.projectors-layout-test` (NEW, 4 tests):
  - `setting-up-a-laser-needs-no-scrolling-test`: two lasers, Laser A selected with one blocked area. At 1200 × 800, fifteen nodes (CONFIGURE, Name, Scan rate, the tags, Exclude from All, FIT GUARD, Show test pattern, Identify, Add Effect, Remove…, the canvas, the Limit and Blocked rows, + Add blocked area, Reset) and both cards are shown: inside the window above the status bar (`ui/scene-bounds`), inside every scroll pane's viewport around them, and none of those scroll panes scrolled. At 1440 × 900, also Copy to…, Mirror to…, and the All and Left rows;
  - `fit-guard-shows-whole-test`: both FIT GUARD floor rows show their box, "never below" and "%" whole (`ui/shown-text`);
  - `each-column-scrolls-on-its-own-test`;
  - `dragged-divider-stays-for-the-session-test`: 80 px down, nothing kept while the mouse holds it, then kept, no undo step, the show not unsaved; another laser; another tab and back.
- `laser-show.events.rig-handlers-test`: `divider-is-kept-for-the-session-test` (the position stored; −0.1, 1.5, nil and "0.4" ignored; no undo step, not unsaved, in neither the show's files nor the rig file, no rig file write; Open keeps it).
- Changed: `rig-strip-test`'s `custom-row-toggles-membership-test` (scrolls to the Graphics row and to + Group), `all-row-click-excludes-test` and `row-name-opens-the-group-test`, and `zone-groups-tab-test/both-tabs-show-the-same-membership-test`, scroll the strip to the row they click. The five tests of §1 lost their eleven scrolls.

### Red first

| Step | Before the change |
|---|---|
| 2 | Against `master`'s views (at `e935ffd`): `each-column-scrolls-on-its-own-test` 1 error (no `#projector-column-right`). `fit-guard-shows-whole-test`, written after the prototype's screenshots, against `master`'s `projectors.clj`: 2 failures ("Draw fewer points belo...", and "..." for "%"). That first run of the namespace also had step 3's two UI tests: 19 failures and 1 error |
| 3 | Against `77230f1`: `setting-up-a-laser-needs-no-scrolling-test` 16 failures (at 1200 × 800 FIT GUARD, Show test pattern, Identify, the canvas, the Limit and Blocked rows, Add and Reset; at 1440 × 900 the same but the canvas and the Limit row, and Copy to… and Mirror to…), `dragged-divider-stays-for-the-session-test` 1 error (no divider). `divider-is-kept-for-the-session-test` against the handlers without the event (the same at `e935ffd` and `77230f1`): 4 failures, its unknown event returning no state. The four row tests as they were on `master`, against step 3's views: 4 errors (the row is under the inspector) |
| 4 | none: it removes calls. The five tests pass without them against step 3's views |

### Verification

| Where | Unit suite (`clj -M:mac-arm:test`) | UI suite (`clj -M:test-ui:mac-arm:test-ui-headless`) |
|---|---|---|
| `cc9e000` (the base) | 1,447 tests, 541,789 assertions, 0 failures | 195 tests, 884 assertions, 0 failures: 12 §6's runs of the same `src/` and `test/` trees (`d6fbb15`), not run again |
| `0a1c219`, the branch's last code, on `cc9e000` | 1,448 tests, 541,801 assertions, 0 failures | 199 tests, 940 assertions, 0 failures |
| **rebased onto `bb1e4bc` (Z4 and the Decimate follow-up in, no conflicts), as landed** | **1,529 tests, 542,918 assertions, 0 failures, 0 errors** | **216 tests, 1,075 assertions, 0 failures, 0 errors** |

Each step also ran its targeted namespaces: step 2 `projectors-layout-test`, `setup-dialogs-test`, `safety-editor-test`, `rig-card-test` and `rig-strip-test` (44 tests, 139 assertions); step 3 `projectors-layout-test` (4 tests, 56 assertions), `rig-handlers-test` (15 tests, 131 assertions), and `rig-strip-test`, `zone-groups-tab-test`, `setup-dialogs-test`, `safety-editor-test` and `rig-card-test` (46 tests, 139 assertions); step 4 the four namespaces it changed (42 tests, 126 assertions). `streaming-engine-test/new-tick-goes-out-without-the-minimum-gap-test` passed in the full run. clj-kondo reports no errors or warnings on the touched files (one info, "Redundant nested call: +", in `cards-height`, as `strip-height` had on `master`). No test wrote under the home folder: `~/LaserShowProjects` doesn't exist.

### For the merge

Trial merges with `git merge-tree --write-tree`, which writes no branch and no worktree:

- **Into `master` at `4c5fcc8`** (Z4 landed): no conflicts, tree `64cef4e`. Unpacked with `git archive` into a temporary folder, its UI namespaces `projectors-layout-test`, `rig-strip-test`, `zone-groups-tab-test`, Z4's `zones-test`, `setup-dialogs-test`, `safety-editor-test` and `rig-card-test` pass (64 tests, 316 assertions), and so do its `rig-handlers` and `domains` unit tests (16 tests, 134 assertions); its full suites weren't run.
  - Files both sides changed since `cc9e000`: `state/domains.clj` (Z4's changes are elsewhere in the file; this branch adds `:divider` at the end of `rig-ui`) and `test/laser_show/ui_test/setup_dialogs_test.clj` (Z4's `delete-group-asks-and-retargets-test` reads `:target`; this branch takes the scrolls out of two other tests).
  - Z4 didn't change `events/handlers/rig.clj`, `views/tabs/projectors.clj` or `views/components/rig_strip.clj`; this branch doesn't touch `subs.clj` or the Zone Groups tab.
  - Z4's new UI tests (`zones_test.clj`) don't open the Projectors tab. On `master`, the UI tests that click a strip row or scrolled to the inspector are the ones changed here.
- **With `decimate-b` at `e15be0d`** (the Decimate follow-up): no conflicts, tree `a0fe97e`. The only shared file is `test/laser_show/ui_test/rig_card_test.clj`: it adds a test at the end that reads the card's texts and classes, and this branch takes two scrolls out of `fit-guard-settings-apply-test`. It doesn't touch `rig_strip.clj` (its meter change is in `load_warning.clj` and the subs).
- Files this branch touches: `views/tabs/projectors.clj`, `views/components/rig_strip.clj` (`cards-height`, `strip-height`, two lines and the docstring of `rig-strip`; `load-meter` untouched), `events/handlers/rig.clj` (`handle-set-divider`, its `case` row, the docstring), `state/domains.clj` (`:divider`); the tests above; this doc. Not touched: `subs.clj`, `safety_editor.clj`, `group_matrix.clj`, `testfx.clj`, `00-overview.md`, `10-questions.md`.

### Found on the way

- FIT GUARD's row has been cut since Z3 (above). Nobody saw it: it was below the fold.
- With no blocked area, the safety section's empty blocked-areas box still takes its 6 px gap: 12 px between Limit and the buttons. Unchanged.
- At 1200 × 800 the right column's Copy to… / Mirror to… row is under the fold, its buttons' tops showing (Q-L-3).

### Questions for the user (★ = built)

**Q-L-1 · Where TEST PATTERN sits.**
- **A ★** At the top of the middle column, above CALIBRATION EFFECTS: CONFIGURE, TEST PATTERN and SAFETY ZONE start side by side, always in view.
- **B** Back under SAFETY ZONE in the right column, as Z2 sketched it: at 1200 × 800 it then needs that column scrolled, since the zone alone takes the height there is.

**Q-L-2 · The strip at 1200 × 800 until the divider is moved.**
- **A ★** Its cards: the inspector gets the room its safety zone needs (430 px), and the group rows are a scroll or a drag away. From 1440 × 900 some rows show, all of them from a window about 1,030 px tall.
- **B** The cards and the All row, 17 px more: in the app + Add blocked area and Reset are then cut by about 8 px with one blocked area.
- **C** The whole strip, as before, and the inspector scrolls.

**Q-L-3 · Copy to… / Mirror to… at 1200 × 800.**
- **A ★** As now: under the fold of the right column, a short scroll.
- **B** On the Add / Reset row (wrapping when the column is too narrow), so all of SAFETY ZONE shows at 1200 × 800. That changes Z1's editor, which put them on a second row because one row didn't fit at 1200 px then (03 §13); the four buttons take 389 px, and the right column's content is 472 px now.

**Q-L-4 · A window resize after the divider was moved.**
- **A ★** The change is shared between the strip and the inspector, as JavaFX's split pane does and the preview's divider does.
- **B** The strip keeps its height and the inspector takes the change.
