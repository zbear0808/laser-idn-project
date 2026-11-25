# Timeline shows: phases

**Status:** Proposed   **Last verified against:** `63240576`

This is the build sheet. Each phase lists its goal, the changes per namespace, the tests that prove it, and how to tell it's done. The detail docs hold the call-site maps, data examples and full test lists, so this file names them rather than repeating them. Paths are under `src/laser_show/`; `T/` means `test/laser_show/`. **NEW** marks anything that doesn't exist yet.

- **Unit tests:** `clojure -M:test -n <ns>`.
- **UI tests:** the `targeted-ui-testing` skill. Run the targeted TestFX vars first, then the full UI and unit suites last.
- **No `test.check`.** It isn't a dependency (`deps.edn`), so property tests use seeded loops on `java.util.Random` and print the seed when they fail.

| Phase | Detail doc | Size |
|---|---|---|
| S0 | `06` §1.3 (results) | done |
| S1 | `03` | ~1.5 d |
| S2 | `04`, `05` | ~4.5 d |
| S3 | `06` | ~3 d |
| S4 | `07` §4.1–§4.7, §4.10–§4.11 | ~2 d |
| S5 | `07` §4.7–§4.9, §4.12 | ~1.5 d |

## S0 · Probes: done

Both probes have run (`06` §1.3). They live in `plans/2026-11-25-timeline-shows/probes/`.

**`table_sort_probe.clj`** ran headless on macOS arm64. Its run command is in the file's header. Three recent runs gave `{:PASS 54–57 :FAIL 15–18 :INFO 28}`. The fixed failures are the variants that were tried and rejected, and the spread comes from the bound search field. It compared several ways of doing each job:

| Job | What passed | What failed |
|---|---|---|
| Sort arrow | a NEW `ext-with-sort` extension passed every check | plain `:sort-order` loses the arrow on the third click |
| Selection | NEW `ext-with-rows` plus `ext-with-selection` passed | `:selected-indices` loses the selection when its own row changes |
| In-cell editing | one edit field, with its draft in app state, taking focus once its skin exists: 3 of 3 runs (d2) | a field in every cell loses focus and commits half an edit whenever the rows change (d) |
| Search field | the NEW guarded field passed 15 of 15 runs | binding the field's `:text` to state garbled the text in 10 of 16 runs |
| Keys | — | the TableView swallows Space; Enter and Delete still fire the menu shortcuts unless the table consumes them |
| Header clicks in tests | plain mouse events at the header work (`ui/mouse-click!`) | the headless robot's header clicks are ignored |

**`filter_cost_probe.clj`** measured the cost of filtering and sorting:

| 1000 shows | Median | p95 |
|---|---|---|
| full recompute, empty query sorted | 1.7–1.8 ms | 4.7 ms |
| filter + sort, rows already cached | 1.25 ms | 2.3 ms |

At 5000 shows with cached rows it is 8–10 ms. So `show-rows` is its own subscription, and each keystroke only re-runs filter and sort (`06` §4.4).

These results are already folded into `00` (TS-7, TS-8) and into `01` §3 and §7.

## S1 · The playback key (~1.5 d, `03`)

**Goal:** `[:show id]` keys play, end, chase and render alongside grid cells. There is no UI yet, and the grid behaves exactly as it does today.

**Changes** (each site, with its line and its class, is in `03` §4–§5):
- **NEW `laser-show.timeline.shows`:** `play-key`, `play-key?`, `grid-cell?`, `key-show-id`, `cue-path`, `cue-at`, `key-order` (total; never throws) and `playable-shows`.
  - **Transitional rule:** in S1, `playable-shows` also merges in grid followers. S2 drops them (`03` §4).
- **`animation/show_time.clj`:**
  - seven lookups move to `shows/cue-at`;
  - two sorts move to `shows/key-order`;
  - `chase` takes the `playable-shows` map.
- **`timeline/playback.clj`:** `source-version` uses `shows/cue-at`.
- **`services/show_clock.clj`:** the column target is used only when `grid-cell?`.
- **`events/handlers/timing.clj:175`, `events/handlers/history.clj:117-127`, `events/handlers/menu.clj:397`, `subs.clj:365-369`:** as `03` §4 says.
- **Docs only:** `state/domains.clj:74-86` and `state/extractors.clj:117-121`.

**Tests:** `03` §10.
- **New fixture arity:** `init!` in `T/animation/show_time_test.clj:32` and in `T/services/show_clock_test.clj:62` gains a `[chains shows]` arity.
- **NEW tests:**
  - in `laser-show.timeline.shows-test`: 4 vars;
  - in `laser-show.animation.show-time-test`: `show-key-runs-and-ends-test`, `show-key-chases-mtc-test`, `show-follower-takes-over-its-manual-run-test`, `mixed-keys-report-in-key-order-test` and `removed-show-retires-its-chase-test`;
  - elsewhere: `laser-show.timeline.playback-test/source-version-reads-show-keys-test`, `laser-show.events.timing-handlers-test/show-key-end-and-chase-guards-test`, `laser-show.events.history-handlers-test/undo-removing-show-stops-it-test` and `undo-keeps-a-show-that-stays-test`, `laser-show.services.show-clock-test/show-key-ignores-column-target-test` and `show-chase-ticks-with-fifty-shows-test`, `laser-show.subs-test/playing-cues-skips-show-keys-test`, and `laser-show.events.menu-handlers-test/copy-fallback-skips-show-keys-test`.
- **Must stay green:** every chase test reactivity 15 added. They are listed in `03` §10.

**Done when** the unit suite passes and `laser-show.ui-test.grid-test` passes unchanged.

## S2 · Library, migration, editor re-keyed (~4.5 d, `04`, `05`)

**Goal:** every timeline is a show, and the grid holds none. Older projects migrate on Open with their triggers, column targets and zone refs. A bare Shows tab lists the shows by title, with Open in Editor and Play.

**Commit order.** Each step ends green (`05` §8.3).

1. **Storage** (`04` §4.1–§4.2, §4.10)
   - `[:chains :shows]` in `chains`, plus `[:ui :shows]` in `ui`.
   - The `content.edn` mapping row.
   - NEW `check-shows!`.
   - `reset-session` keeps the query, scope and sort, and resets `[:ui :timeline-editor]`.
   - `apply-loaded-project` normalizes `[:chains :shows]` before `mark-clean`.
   - History needs no change, which `04` §4.1 traces through.
2. **Zone refs see shows** (`04` §4.6)
   - a `:shows` entry in `routing.refs/ref-places`;
   - `shows-using`;
   - a 3-arity `chain-ref-usage`;
   - shows also counted by `cue-ref-sets`, `zone-patterns-in-use?`, and the zone-group delete message.
3. **Migration** (`04` §4.3–§4.4, §4.9)
   - NEW `migration/timeline-shows`, run after `zones-z4` and before `rename-pins`. It must never throw (M-8).
   - NEW `refs/rehome-trigger-targets` and `refs/cue-ref-count`.
   - `handle-file-project-loaded` switches to the Shows tab when anything moved. Save's dry run doesn't.
   - In `01` §1, `tc-range` and `tc-overlap?` arrive here rather than in S4.
4. **The ref abstraction** (`05` §8.3 step 2)
   - `shows/cue-path` and `show-path`.
   - `chain-config` and `zone-pattern-items-path`.
   - The zone-pattern chain on a playback key `k`, covering its handler, its window and the step grid (`05` §4.11).
   - `key-cue-warning` and `cue-load-warning :cue-key`.
   - Delete the dead `:timeline-cue-chain` path.
5. **A minimal `events/handlers/shows.clj`**
   - `:shows/create`, `:shows/open-editor` (this takes over `handle-open-editor`'s normalize backfill) and `:shows/new-from-cue`.
   - The `"shows"` route in `events/handlers.clj:41-86`.
6. **The editor re-key, in one commit** (`05` §4.1–§4.10)
   - The 262 `col` lines in eight files.
   - NEW `subs/show-cue`, `show-target-explain`; `timeline-ui` and `timeline-transport` take an id.
   - `edited-cells`.
   - Remove `:timeline-cue-editor/open`, its four dispatchers and its route.
   - Re-point `wrap-normalize-timelines` and `reconcile-chase-sources` at `[:chains :shows]`.
   - Drop S1's grid half of `playable-shows`.
   - Re-key every test listed in `05` §8.1.
7. **The footer** (`05` §5.1)
   - NEW `:timeline/set-name` and `:timeline/set-cue-target`, plus model op `:set-cue-target`.
   - Remove the trigger mode and the cue-chain button.
   - The deleted-show placeholder.
8. **The grid loses timelines** (`04` §4.7–§4.8)
   - Drop "Create Timeline Cue" and the timeline label.
   - `start-cue` and `open-cell-editor` lose their timeline branches.
   - Grid paste refuses a timeline clip with a notice. `clipboard/timeline-clip?`. List paste strips timing keys.
   - The cue chain editor's button becomes **New Show from Cue**.
9. **Triggers** (`04` §4.5)
   - `:start-timeline` and `:stop-timeline` take `[:show id]`.
   - NEW `:toggle-show`, which calls NEW `:shows/toggle-run`, with momentary support.
10. **The bare tab**
    - NEW `views/tabs/shows.clj`, showing a plain list.
    - `views/root.clj:66-93` gets the tab.
    - The tab docs in `state/domains.clj:162-163`.
    - The banner.
    - Labels (`04` §4.12).
    - The show-lock entries: `:shows/delete`, `:shows/request-delete` and `:timeline/set-cue-target` are blocked. `:shows/new-from-cue` is not.

**Tests:** `04` §8 and `05` §8. Highlights:
- **Persistence:** `laser-show.state.persistent-test/shows-survive-save-and-load-test`, `old-timeline-show-opens-clean-test` and `session-reset-keeps-show-search-test`.
- **Migration:** `laser-show.state.migration-test/timeline-shows-examples-test` (cases A–E), `timeline-shows-is-idempotent-test` and `timeline-shows-invariants-test` (seeded).
- **Save's dry run:** `laser-show.events.menu-file-handlers-test/save-dry-run-ignores-migration-test`.
- **History:** `laser-show.state.history-test/shows-are-undoable-show-content-test` and `restore-grafts-show-track-mute-test`.
- **Paste:** `laser-show.events.grid-handlers-test/paste-refuses-timeline-clip-test`.
- **Triggers:** `laser-show.events.handlers.triggers-test/show-targets-play-and-toggle-test`.
- **Zone refs:** `laser-show.routing.refs-test/shows-are-a-ref-place-test`.
- **Timeline handlers:** `laser-show.events.timeline-handlers-test/edits-never-create-a-show-test` and `no-handler-touches-cue-chains-test`.
- **Extended:**
  - `T/state/persistent_test.clj` `checked-project-files-test` (:778) and `load-normalizes-timeline-cues-and-stays-clean-test` (:287);
  - `T/state/clipboard_test.clj` `accepts-strict-types-and-conversions-test` (:81), whose timeline assertion flips;
  - `laser-show.routing.legacy-shows-test`: its timeline fixture migrates, and the golden frames are unchanged.
- **Re-keyed:** all 28 vars in `T/ui_test/timeline_editor_test.clj` (:33–:768), opened with `:shows/new-from-cue {:col 0 :row 0}`. The timeline cases in `dialogs_test`, `zones_test`, `zone_patterns_test`, `keyboard_trigger_test`, `cue_load_test` and `timeline_visual_test` are re-keyed too.
- **Updated:**
  - the tab labels in `T/ui_test/vibes_test.clj:74-78`;
  - the tab list in `laser-show.ui-test.main-window-test/top-level-tabs-update-content-and-preview` (`T/ui_test/main_window_test.clj:44-57`);
  - NEW `laser-show.ui-test.shows-tab-test/bare-list-opens-and-plays-test`;
  - NEW `laser-show.ui-test.grid-test/empty-cell-menu-has-no-timeline-item-test`.

**Done when:**
1. the targeted tests pass, then the full unit and UI suites;
2. `grep -nE '\bcol\b'` over the editor's eight files returns nothing;
3. a manual check on the app REPL with plorer passes: an older show with a timeline cell opens with it in Shows, and its MIDI trigger still toggles it.

## S3 · The table (~3 d, `06`)

**Goal:** the music-player table, with its columns, sort, search, selection, inline editing, row menu, and New, Duplicate and Delete.

**Changes** (`06` §4):
- **`timeline/shows.clj`:** `rows` (takes no global BPM), `duration-s`, `filter-rows`, `sort-rows` and `visible-ids`. The duration filter and sort take the BPM as an argument.
- **NEW `views/components/show_table.clj`:**
  - **three extensions**, copied from the probe: `ext-with-sort`, `ext-with-rows` and `ext-with-selection`;
  - the columns, as refs;
  - a sort policy that accepts without sorting, plus `on-sort`;
  - **JavaFX's own selection gestures**, reported through `on-selected-rows`;
  - **`row-desc`:**
    - the row menu, whose events carry `:from-row`;
    - double-click to play;
    - the slow-click edit start;
  - **cells:**
    - status and approx-duration components;
    - the one `edit-field`, which takes the focus once its skin exists;
    - the TC checkbox, disabled until S4;
  - `install-table-keys!`.
- **`views/tabs/shows.clj`:**
  - the header: a search field in NEW `ext-with-query-text`, the scope (`applied-control`), the count and the MTC chip;
  - the placeholders and the banner, which has no "Show me";
  - the tab root's filter for ⌘F and Escape;
  - **no ⌘N** binding.
- **`subs.clj`:** `show-rows`, `visible-show-rows`, `show-table-ui`, `show-selection`, `show-editing`, `show-edit-draft`, `show-table-status` and `show-row-status`.
- **`events/handlers/shows.clj`:**
  - **edits:** `start-edit`, `edit-draft`, `commit-edit` and `cancel-edit`, built on `set-info`, `set-bpm` and `set-tc-start`;
  - **library:**
    - `duplicate`, which copies without TC;
    - `request-delete`;
    - `delete`, which also removes the show's intents, chase versions, editor view state and triggers, clears `:editing` and closes its editor;
  - **table state:** `set-query`, `set-scope`, `set-sort` and `select`.
  - `:from-row` and `:from-selection` resolve against the visible selected ids.
- **NEW `css/shows.clj`,** plus `.text-field-invalid` in `css/forms.clj`.
- **`events/show_lock.clj`:** the predicate that refuses `:shows/commit-edit` on BPM or TC start.

**Tests:** `06` §8.
- **Unit:**
  - `laser-show.timeline.shows-test`: rows shape (no global BPM), sort, filter, query problem;
  - `laser-show.events.shows-handlers-test`: `set-sort-maps-cleared-sort-to-ascending-test`, `select-replaces-with-gesture-test`, `from-row-uses-selection-test`, `edit-lifecycle-test`, and delete cleaning up;
  - `laser-show.subs-test/visible-show-rows-ignores-status-and-editing-test`;
  - `laser-show.views.components.show-table-test`: column ids, and one body per draft keystroke.
- **UI:** eleven vars in `laser-show.ui-test.shows-tab-test`, including:
  - `header-click-sorts-and-reverses-test`;
  - `search-keeps-typing-under-load-test`, the regression test for the race;
  - `inline-edit-commits-once-test`;
  - `edit-survives-row-change-test`;
  - `keys-in-table-test`, which checks that Delete doesn't run Clear Cell;
  - `row-menu-acts-on-selection-test`.
- **Cache:** after S3, re-run `laser-show.ui-test.cache-working-set-test` with 1000 shows.

**Done when** the targeted tests pass, then the full suites, and a 1000-show library on the REPL (`app-repl` skill) filters as you type with no visible lag.

## S4 · Timecode column and rules (~2 d, `07`)

**Goal:** the TC checkbox, with TS-6 enforced at the tick and after every edit.

**Changes** (`07` §4):
- **`events/core.clj:268-274`:** `:dispatch-later` also takes a vector of timers.
- **`events/helpers.clj`:** NEW `merge-notice`.
- **`timeline/timecode.clj`:** NEW `seconds->tc-ceil`.
- **`timeline/shows.clj`:** `tc-overlap?` with its 1 µs tolerance, `timecode-problem`, `invalid-timecode-ids` (returning pairs) and `invariant-violations`.
- **`events/handlers/timeline.clj`:**
  - `wrap-normalize-timelines` gets the turn-off step, between normalize and reconcile. It is skipped on history restores and on document replacement;
  - `:timeline/seek` gains `unless-chasing`.
- **`events/handlers/shows.clj`:** `:shows/set-timecode`, which is blocked by the show lock, as are `set-bpm` and `set-tc-start`.
- **`views/components/show_table.clj`:** the TC cell, in `applied-control`, and the ⚠ tooltip.
- **`views/components/visual_editors/timeline/inspector.clj:180-198, :257-262`:** the chase buttons in `applied-control`, sending `:shows/set-timecode`. The warning is replaced by `#timeline-tc-problem`.
- **`subs.clj`:** `show-timecode-problem`.

**Tests:** `07` §8.
- **The turn-off:** `laser-show.events.helpers-test/merge-notice-examples-test` (NEW file), `laser-show.events.core-test/dispatch-later-takes-a-vector-test`, and in `laser-show.events.shows-handlers-test`: `edit-that-overlaps-turns-timecode-off-test`, `turn-off-merges-with-handler-notice-test`, `open-never-turns-timecode-off-test` and `invariant-holds-after-random-edits-test` (seeded).
- **The rules:** `laser-show.timeline.shows-test/overlap-examples-test` (`07` §7.2) and `laser-show.timeline.timecode-test/seconds->tc-ceil-test`.
- **The clock:** `laser-show.animation.show-time-test/turned-off-show-stops-next-tick-test` and `looping-show-chases-without-looping-test`.
- **UI:** `refused-tick-snaps-back-with-notice-test`, `tc-problem-tooltip-test` and `inspector-chase-refused-snaps-back-test`.

**Done when** the targeted tests pass, then the full suites. On the REPL, synthetic MTC (`laser-show.input.mtc-test/feed`) run across two enabled shows plays one, then the other, never both.

## S5 · Now-playing bar (~1.5 d, `07`)

**Goal:** the transport bar, the keyboard, and the status bar's Playing line.

**Changes** (`07` §4.5, §4.7–§4.9, §4.12):
- **`events/handlers/shows.clj`:** `:shows/play` (with TS-9's refusal), `toggle-play`, `stop`, `seek`, `next` and `previous`.
- **`subs.clj`:** `now-playing`, with its order of choice and its shape in `07` §4.7, plus `playing-shows`.
- **NEW `views/components/now_playing.clj`:**
  - the bar's ids, for tests;
  - `redraw-now-playing!`;
  - `seek-slider`: it has no `:value` prop, it isn't redrawn while it is captured, and it seeks once, on release.
- **`views/status_bar.clj:90-111`:** `playing-list` takes both cues and shows.

**Tests:** `07` §8.
- **Unit:** `play-replaces-previous-manual-show-test`, `play-refused-while-following-timecode-test`, `next-previous-follow-visible-order-test`, `play-resumes-held-transport-test`, `laser-show.subs-test/now-playing-examples-test` and `laser-show.views.status-bar-test/playing-list-with-shows-test`, which sits beside `playing-list-test` (`T/views/status_bar_test.clj:98`).
- **UI:** `now-playing-follows-the-clock-test` (following `T/ui_test/timeline_editor_test.clj:292`), `seek-release-moves-the-show-test`, `space-in-table-toggles-show-not-transport-test`, `timecode-show-disables-transport-test` and `held-chip-while-stopped-test`.

**Done when:**
1. the targeted tests pass, then the full unit and UI suites;
2. a 5-minute live check with plorer passes, and a screenshot of the tab is saved in `test-results/`.

Then mark this plan Done and delete the plan (planning-guide rule 11).
