# Timeline shows: a library tab of their own

**Decision 2026-11-25 (user): no backward compatibility, so no migration.** Old files open as before: a timeline cell stays a grid cue, and is not editable as a show. `migration/timeline-shows`, the banner and `[:ui :shows :migrated]` were built and then removed (`354f98b1`). Wherever these docs describe the migration (`01` §6, `04` §4.3–§4.4, §7.1, TS-11's rewrite, TS-12, TS-15's column-target carry), read it as dropped.
**Written:** 2026-11-25   **Status:** In progress (S1 and S2 started on branch `shows/integration`)   **Last verified against:** `63240576`; implementation is against `385b6bac`
**Master moved after verification.** `385b6bac` merged 62 follow-up commits. Line numbers in these docs may be a few lines out in `show_time.clj`, `events/core.clj`, `grid.clj`, `history.clj`, `menu.clj`, `timing.clj`, `subs.clj`, `state/history.clj`, `vibes/refs.clj` and the timeline `device_chain.clj`, so implementers re-check each citation as they go.
- **One semantic change:** follow-up B7 is fixed (`1a75392a`). A grid press now stops a chased cue for its episode. It records the stop at NEW-to-us `[:playback :chase-stopped] {cell chase-id}` (`state/domains.clj:87`, `animation/show_time.clj:244-261`, `events/handlers/grid.clj:66-88`, `events/handlers/timing.clj:239`).
- **What S1 does with it:** `:chase-stopped` is a cell-keyed path, so S1 keys it by playback key like `:chase-sources`.
- **What S2 does with it:** `:shows/toggle-run` on a show that follows timecode reuses B7's stop and follow-again. A pad then stops a chased show for that episode, as a grid press stops a chased cue.
**Supersedes / Superseded by:** none
**Related findings:** reactivity follow-ups B7 (`plans/2026-11-24-reactivity-follow-ups.md:54`: a chased cue can't be toggled off from the grid; this plan moves timecode on and off to the Shows tab's TC column). Reactivity 15 (`plans/2026-11-23-cljfx-reactivity/15-timecode-chase-lifetime.md`): chase versions and episodes, which this plan re-keys and keeps. Live performance UX (`plans/2026-11-21-live-performance-ux/`): the shared design language, which the tab follows.

Paths are under `src/laser_show/` unless they start with `test/`, `plans/` or `docs/`. **NEW** marks anything that doesn't exist yet.

| Doc | What |
|---|---|
| `00-overview.md` | This doc: current behaviour, root cause, decisions, state, events, risks, questions |
| `01-spec.md` | **The contract**: the show document, the playback key, the table (columns, sort, search), the timecode rules, migration, the tab layout, data examples and invariants |
| `02-phases.md` | Phases S0–S5: changes per namespace, tests, and how to check each phase is done |
| `03-playback-key.md` | S1 in detail: every site that keys playback by grid cell (L1–L11, O1–O8), its class and change; the `shows` key functions; data traces with plan 15's chase; measured per-tick cost |
| `04-storage-and-migration.md` | S2 storage in detail: why undo and unsaved need no change (traced); saving, `check-shows!`, the load pipeline and Save's dry run; `migration/timeline-shows` with five full EDN cases; triggers, vibes, zone refs, paste, show lock and labels |
| `05-editor-rekey.md` | S2 editor re-key in detail: all 262 `col` lines by file and function, every `:timeline/*` event before → after, the footer decisions, a deleted show while its editor is open, every test that changes, a safe commit order |
| `06-table-and-search.md` | S0 and S3 in detail: the probe results, the four table extensions, selection, inline editing, the search field, keys, subscriptions, CSS, UI tests |
| `07-timecode-and-transport.md` | S4 and S5 in detail: adding a notice from middleware safely, where the turn-off sits, ranges in frames, loops and 24 h, the now-playing sub and bar, the seek slider, Space |
| `probes/` | `table_sort_probe.clj` (a headless TableView, 100 checks) and `filter_cost_probe.clj` (filter and sort cost at 1000 and 5000 shows). Results in `06` §1.3 |

Where `03`–`07` correct this doc or `01`/`02`, the correction is made here too; each detail doc ends with what it corrected.

## Start here

Your answers of 2026-11-25 set the scope:

- **Own library.** Timeline shows leave the grid. Old shows are moved out when the project is opened.
- **Timecode on/off is saved with the project.** It is the show's existing Follow Timecode setting (`:timeline/chase`), which is already saved.
- **Overlapping timecode is blocked.**
- **Columns:** Title, Artist, Album, BPM, Key, Genre, Duration, TC start and TC end.
- **Play from the list**, with a now-playing transport bar.

After this plan:

1. **A new main tab, Shows**, sits between Perform and Effects. It is a music-player table: one row per show. Click a column header to sort by it, and click it again to reverse the order. A search box filters the rows as you type, either across every text column or within one column you choose. BPM and Duration also accept ranges, such as `120-128` and `3:00-4:30`.
2. **Title, Artist, Album, Key, Genre, BPM and TC start are edited in the table.** A slow second click on a cell, or F2, opens one edit field. Enter commits, Tab moves to the next column, Escape cancels. BPM is the show's locked tempo, so changing it changes how the show plays. TC start is where beat 0 sits on the timecode. Duration and TC end are worked out from the show.
   - Only the cell being edited holds a field. Its draft lives in app state, so the edit survives the rows changing under it. The probe showed that a field in every cell loses its focus and commits half an edit whenever the rows change (`06` §1.3, checks d and d2).
3. **A TC checkbox column** turns timecode on or off for each show. An enabled show plays by itself whenever incoming MIDI timecode is inside its range. Two enabled shows can never overlap. A show also needs a BPM before it can be enabled, because without one its timecode range moves with the global tempo. A refused tick says why: *"Overlaps 'Around the World' (01:04:10:00–01:09:12:05)"*.
4. **Double-click or Enter plays a show by hand.** A now-playing bar at the bottom of the tab shows the title and artist, elapsed and remaining time, and the timecode. It has a seek bar and ⏮ ⏯ ⏹ ⏭ buttons. Previous and Next follow the order the table shows. Playing a show stops the show you played before it; grid cues keep playing.
5. **Open in Editor** (row menu, or ⌘E) opens today's timeline editor on that show. The editor is unchanged apart from addressing a show instead of a grid cell.
6. **The grid only holds cues.** "Create Timeline Cue" is gone from the cell menu. The cue chain editor's "Open in Timeline Editor" becomes **New Show from Cue**, which copies the cue into a new show and leaves the cell as it is.
7. **~~Opening an older project moves its timeline cues into the library.~~** Dropped: no backward compatibility. An older project's timeline cells stay grid cues.

Build order: **S0** (probes) → **S1** (the playback key) → **S2** (library, migration, editor re-keyed, a bare Shows list) → **S3** (the full table: metadata, sort, search) → **S4** (timecode column and rules) → **S5** (now-playing bar). S3 and S4 can be built side by side after S2.

| Phase | What you get | Size |
|---|---|---|
| S0 | **Done.** Probes have run (`06` §1.3): the table mechanisms that work, and filter and sort cost | — |
| S1 | `[:show id]` keys play, chase and render beside `[col row]` keys. Mixed keys sort safely. Grid behaviour unchanged (`03`) | ~1.5 d |
| S2 | `[:chains :shows]` saved and undoable. Older projects migrate on Open, with triggers, column targets and zone refs. The timeline editor addresses `:show-id`. The grid has no timelines. New Show from Cue. A Shows tab with a plain list (title, Open, Play) (`04`, `05`) | ~4.5 d |
| S3 | The table: the four extensions, header sort, the search box with column scope and ranges, inline editing, the row menu, multi-select, New / Duplicate / Delete (`06`) | ~3 d |
| S4 | The TC column: enable and disable, the BPM and overlap rules at the tick and after every edit, the notice merge, a TC-range warning, MTC status in the header (`07`) | ~2 d |
| S5 | The now-playing bar: live position, seek, ⏮ ⏯ ⏹ ⏭, keyboard, the status bar's Playing line (`07`) | ~1.5 d |

S2 grew from ~3 d: the editor re-key is 262 lines, not ~190 (`05` §1), and the migration also re-homes triggers, column targets and zone refs (`04` §4.3, §4.6). Total ~12.5 d.

## 1. Current behaviour (file:line)

**A timeline show is a grid cue.** It is a cue chain at `[:chains :cue-chains [col row]]` (`state/domains.clj:520-521`) with `:editor-type :timeline`, tracks and clips (`timeline/model.clj:1-22`).
- `cue-chains/timeline-cue?` (`animation/cue_chains.clj:60-76`) tells timelines from plain cues.
- Its timecode settings are optional keys on the cue (`timeline/model.clj:462-467`): `:timeline/length`, `:timeline/tempo-bpm`, `:timeline/tc-start`, `:timeline/tc-fps` and `:timeline/chase`.
- They are edited through `model/apply-op` (`timeline/model.clj:629-707`): `:set-tempo` :674-676, `:set-tc-start` :677, `:set-tc-fps` :678-680, `:set-chase` :681-683.
- A timeline has `:name` and nothing else about the song.

**Making one.**
- The empty cell's menu has "Create Timeline Cue" (`views/components/grid_cell.clj:342-344`).
- The cue chain editor has "Open in Timeline Editor" (`views/dialogs/cue_chain_editor.clj:446-457`). Both dispatch `:timeline-cue-editor/open` (handled by `events/handlers/timeline.clj:439-471`), which converts the cell in place: "Convert to Timeline Cue", :466.

**Saving, undo and the unsaved flag.**
- `[:chains :cue-chains]` is saved in `content.edn` (`state/persistent.clj:84-89`). Open checks that its keys are `[col row]` cells (`check-grid!` :104-119, :153).
- `:chains` is a document root (`state/history.clj:65-68`). Every chain domain except `:projector-effects` counts as undoable show content, compared entry by entry while ignoring live item fields such as a track's `:muted?` (`chains-changed?` :151-166, `show-content-changed?` :181-189, `live-item-paths` :60-63).
- Undo grafts those live fields back on by item id (`graft-live-chains` :306-320, `restore-document` :322-342).
- On open, timelines are normalized before the saved baseline is taken (`state/persistent.clj:342`). Older files are converted by data-driven migrations (`state/migration.clj`, called at `state/persistent.clj:343-344`).

**Playback is keyed by grid cell.**
- Intents live at `[:playback :active-cues cell]` (`state/domains.clj:74-84`). `cue-timing/activate-cue` and `deactivate-cue` write them (`animation/cue_timing.clj:331-362`).
- The show clock looks each cue up by its key, and only in `[:chains :cue-chains]`: `reconcile-runs` (`animation/show_time.clj:174-186`), `advance` :190-205, `chase` :230-257, `chase-reports` :262-295 and `active-runs` :390-413.
- Timecode versions: `playback/source-version` reads `[:chains :cue-chains cell]` (`timeline/playback.clj:60-68`), and `playback/apply-chase` walks the cue-chains map it is given (:88-125).
- **Two sorts would throw on a mixed key set.** `chase-reports` sorts its cells (`animation/show_time.clj:278`) and `active-runs` sorts the chase keys (:412). Clojure compares two-element vectors element by element, so `[:show id]` against `[0 1]` compares a keyword with a number and throws `ClassCastException`. `subs/cue-cells-in-grid-order` destructures `[col row]` (`subs.clj:365-369`) and has the same problem.
- The render takes the column default target with `(first cell)` (`services/show_clock.clj:90`). Errors are filed under `[:render cell]` (:91, :94). `edited-cells` builds `[col row]` from the editor dialogs (:106-115).
- The rest of the cell-keyed paths:
  - the cue-ended check reads `[:chains :cue-chains cell]` (`events/handlers/timing.clj:175`);
  - the chase report keys are listed at :180-182, and `install-chase` is at :203-222;
  - undo stops intents whose key isn't a cue-chains key (`prune-active-cues`, `events/handlers/history.clj:117-127`);
  - Edit > Copy falls back to the first active cue's key and splits it as `[col row]` (`events/handlers/menu.clj:394-401`).

**The timeline editor addresses `[col row]` everywhere.**
- The dialog reads `{:col :row}` from `[:ui :dialogs :timeline-cue-editor]` (`views/dialogs/timeline_cue_editor.clj:34-43`, window title :143-147).
- Its subscriptions take `col row`: `subs/cue-cell-chain` :249-253, `timeline-ui` :258-263, `timeline-transport` :271-279.
- Its handlers take `col row`, falling back to the open dialog's cell: `event-cell` (`events/handlers/timeline.clj:74-81`), `chain-path` :119, `view-path` :50, `tracks-list-id` :52-55, transport :356-428.
- Its playhead and readout draw `show-time/cue-beats snapshot [col row]` (`views/components/visual_editors/timeline_editor.clj:544, :602`).
- Its effect lists map `:timeline-items` and `:timeline-tracks` onto `[:chains :cue-chains entity-key]` (`events/handlers/chain/helpers.clj:17-48`; `state/extractors.clj:20-45` `:timeline-cue-chain`).
- 262 lines name a column (`grep -cE '\bcol\b'`), by file (`05` §1 has the full map):

  | File | Lines |
  |---|---|
  | `events/handlers/timeline.clj` | 115 |
  | `timeline_editor.clj` | 53 |
  | `timeline/header.clj` | 30 |
  | `timeline/inspector.clj` | 28 |
  | the dialog | 16 |
  | `device_chain.clj` | 12 |
  | `browser.clj` | 5 |
  | `drop.clj` | 3 |

  Nine more sites sit outside the editor (subs, chain helpers, extractors, show clock, load warning), plus the cue zone-pattern chain (`05` §4.11).

**Timecode.**
- An MTC-following timeline plays whenever timecode is inside `[0, effective-length)` of its own beats (`timeline/playback.clj:88-125`).
- Nothing stops two followers from overlapping: both play at once.
- A follower with no locked tempo maps timecode to beats at the global BPM. The inspector only warns about this (`views/components/visual_editors/timeline/inspector.clj:257-262`).
- Transport events are ignored for a follower (`unless-chasing`, `events/handlers/timeline.clj:360-363`).
- The grid can't stop a chased cue (B7).

**Triggers and vibes name cells.**
- `:start-timeline` and `:stop-timeline` need a `[col row]` target (`events/handlers/triggers.clj:171-176`).
- `:trigger-cue` bindings at a cell are rewritten when it moves or clears (`events/handlers/grid.clj:124, :165`; `vibes/refs.clj:182, :195`).
- Vibe pools name cells too (`vibes/refs.clj:103-114` `remove-cue`). A timeline in a vibe slot plays through `grid/start-cue` (`events/handlers/grid.clj:35-52`).

**The main window.**
- The tabs are a data vector (`views/root.clj:85-93`), routed by `tab-content` (:66-79). The preview is hidden on some tabs (:137-138).
- `[:ui :active-tab]` is written by `:ui/set-active-tab` (`events/handlers/ui.clj:16-19`).
- **Nothing in the app uses a `TableView` or `ListView`, and there is no search field.** The closest pieces:
  - `list-editor`, which is not virtualized (`views/components/list.clj:865-938`);
  - `commit-field`, which applies on Enter or blur (`views/components/commit_field.clj:57-97`);
  - live `:on-text-changed` fields writing dialog data (`views/dialogs/zone_group_editor.clj:77`).
- cljfx 1.10.6 has `:table-view`, with `:sort-policy`, `:on-sort` and `:sort-order` (`cljfx/fx/table_view.clj:48-66` in the jar), and `:table-column`, with `:sort-type`, `:sortable`, `:cell-value-factory` and `:cell-factory`. The S0 probe found that their plain forms lose the sort arrow, the selection and a cell's text field in common cases. Three small extensions fix each one (`06` §1.3).

**Also cell-only today, found by the detail docs:**
- Zone references: `routing.refs/ref-places` and the usage readers walk only cue chains (`routing/refs.clj:311-412`). So `rename-pins` on Open, and zone-group deletion, would miss shows (`04` §1.6).
- A cue with no target draws to its column's default target (`services/show_clock.clj:90`). A show has no column (`04` §4.3).
- **Save runs the whole Open pipeline as a dry run.** `save-to` calls `prepare-open` on what it is about to write (`events/handlers/menu.clj:220-245`). So any migration runs on every Save (`04` §1.2).
- `:dispatch-later` takes one map (`events/core.clj:268-274`), so middleware can't simply add a notice (`07` §1.1).
- `:timeline/seek` isn't wrapped in `unless-chasing` (`events/handlers/timeline.clj:607`).
- `org.clojure/test.check` isn't a dependency (`deps.edn`), so the plan's property tests use seeded loops.

## 2. Root cause

The library and timecode features don't fit inside the grid:

- **Timelines are songs, but they are stored and addressed as grid cells.** A song has metadata, a place on the timecode and a playing state. A grid cell has none of these, and its address is a screen position. No list can be sorted or searched while each show's identity is "column 3, row 2".
- **The playback core can't address anything else.** It needs a cue to be a `[col row]` key into `[:chains :cue-chains]`. Every lookup is hard-coded to that path, and two sorts assume the key shape. So a show stored anywhere else would never play, chase or end.
- **The timecode rules live on each cue.** Follow Timecode is per cue, and no code compares one follower with another, so overlaps go unnoticed. A follower without a locked tempo has a timecode range that moves with the BPM.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| The timeline cue map and `model/apply-op` (`timeline/model.clj:629-707`), `normalize-timeline-cue(s)` (:392-434), `effective-length` (:475-478), `convert-to-timeline` (:436-455) | **A show is that same map.** It keeps every op, undo label (`op-label` :712-743) and invariant. New Show from Cue reuses `convert-to-timeline` on a copy |
| `[:chains …]` as a document root (`state/history.clj:65-68`); `chains-changed?` (:151-166); `graft-live-chains` (:306-320) | **`[:chains :shows]` gets undo, unsaved tracking and live track mute and collapse for free.** These walk every chain domain that isn't the rig's |
| `persistent-state-mapping` (`state/persistent.clj:52-89`); `replace-at-path` (:227-242) | One new `:content` row. An older file without it loads as `{}` |
| `state/migration.clj` (data-driven, run twice safely); `apply-loaded-project` (`state/persistent.clj:316-348`) | The grid-to-library move is one more migration, before `mark-clean` (:347), so the file doesn't open as unsaved |
| `show-time` runs, chase and reports (`animation/show_time.clj:145-413`); chase versions (`events/handlers/timeline.clj:484-538`); `:show-clock/apply-chase` (`events/handlers/timing.clj:224-267`) | Unchanged logic. Only the cue lookup (one NEW resolver) and the two sorts change |
| `cue-timing/activate-cue`, `deactivate-cue`, `request-seek` (`animation/cue_timing.clj:331-368`) | They already take any key |
| `wrap-normalize-timelines` (`events/handlers/timeline.clj:540-568`, installed `events/core.clj:869`) | Re-pointed at `[:chains :shows]`. It also enforces the timecode invariants after each edit (`01` §4.3) |
| Timeline transport handlers (`events/handlers/timeline.clj:356-428`), `unless-chasing` (:360-363) | The now-playing bar sends the same `:timeline/*` events, with `:show-id` |
| `clock-view/ext-clock-redraw` (`views/components/clock_view.clj:86-104`); `redraw-readout!` (`timeline_editor.clj:597-610`) | The now-playing position, time and seek bar redraw from `show-time/latest` with no app-state writes |
| `tc/format-tc`, `seconds->tc`, `parse-tc`, `beats->seconds`, `format-clock` (`timeline/timecode.clj:20-102`) | Column text, TC parsing and ranges |
| `applied-value/applied-control` (`views/components/applied_value.clj:217`); `commit-field` (`views/components/commit_field.clj:57`) | The TC checkbox and the scope combo snap back when refused. `commit-field` is **not** used inside table cells (probe d); the one edit field is its own component (`06` §4.1) |
| `h/show-notice` (`events/helpers.clj:73-87`) | Refusals and automatic timecode turn-offs |
| `refs/remove-cue`, `remove-trigger-targets` (`vibes/refs.clj:103, :195`) | Migration drops vibe references to the moved cells and rewrites their triggers |
| Confirm dialog with `:confirm-event` (`events/handlers/ui.clj:97-119` `handle-ui-confirm`; its button `views/dialogs/confirm.clj:96`), as zone maps use it (`events/handlers/zone_maps.clj:182-211`) | Delete Show(s) asks first |
| `styled-tab-bar` (`views/components/tabs.clj:50-71`); CSS module pattern (`css/core.clj:16-58`, e.g. `css/timeline.clj:8-11`) | The tab button and a NEW `css/shows.clj` |

**NEW namespaces**, each justified:

- `laser-show.timeline.shows`: pure model of the library and the playback key: rows, sort, filter, timecode ranges and overlap. There is no home for these today. `timeline.model` is about one show's contents, and keeping library rules out of it keeps it single-purpose.
- `laser-show.events.handlers.shows`: the `:shows/*` domain. Library edits, table UI state and list transport don't belong in the 630-line editor handler.
- `laser-show.views.tabs.shows`: the tab.
- `laser-show.views.components.show_table`: the TableView wrapper. It is the first in the app and keeps the JavaFX sort plumbing in one place.
- `laser-show.views.components.now_playing`: the transport bar.
- `laser-show.css.shows`: the tab's styles.

## 4. Decisions

| # | Decision | Why |
|---|---|---|
| TS-1 | Shows live at NEW `[:chains :shows]`, a map `{show-id show}`, show-id a `random-uuid` | Inside `:chains`, the library gets undo, unsaved tracking and live fields with no history changes (§3). A map, because Open accepts only maps at saved paths (`state/persistent.clj:147-150`). UUIDs never collide across Duplicate, undo or migration |
| TS-2 | A show **is** a timeline cue map. Title is its existing `:name`. Artist, Album, Key and Genre go in NEW `:show/info`. **BPM is `:timeline/tempo-bpm`.** Duration, TC start and TC end are derived. TC on/off **is** `:timeline/chase :mtc` | One location per fact: the editor, the table and the playback read the same keys. The BPM a song is tagged with is the tempo it plays at |
| TS-3 | The grid can't gain a timeline: grid paste refuses one with a notice, "Create Timeline Cue" goes, and "Open in Timeline Editor" becomes **New Show from Cue** (a copy). No migration: a timeline cell in an older file stays a grid cue | Your answer "Own library", and no backward compatibility needed |
| TS-4 | A show plays under the NEW key `[:show id]` in `[:playback :active-cues]`, `:chase-sources :cells`, show-time's `:chase` and `:positions`. One NEW resolver, `shows/cue-at`, finds the cue for any key. One NEW comparator, `shows/key-order`, replaces the bare sorts | The intent, run, chase and report logic stays as it is; only lookups and sorts change. A tagged vector can never be mistaken for a cell |
| TS-5 | The timeline editor and every `:timeline/*` event address `:show-id` instead of `:col :row`. The dialog's data is `{:show-id}` | After TS-3 every timeline is a show. One address, not two |
| TS-6 | **Timecode invariants:** an enabled show has a locked BPM and a length above 0, and no two enabled shows' ranges `[tc-start, tc-end)` overlap. Ranges are plain seconds with a 1 µs tolerance. They ignore the loop, because a chased show never loops, and they don't wrap at 24 h, because the MTC decoder doesn't. TC end is shown rounded **up** to a frame, so typing it as the next start never overlaps (`07` §4.3–§4.4). Ticking TC is refused with a notice naming the reason. After any edit that breaks the invariant (a longer clip, a new BPM or TC start), the middleware turns TC off **for the shows that edit changed**, in the same undo step, with a notice merged safely into the handler's effects (`07` §4.1–§4.2). Undo, redo, New and Open never trigger it | Your answer "Block the overlap". Refusing every clip drag that lengthens an enabled show would make editing painful. Turning it off is visible, undoable together with the edit, and leaves the other show playing |
| TS-7 | **The table.**<br>• It is a cljfx `:table-view` of pre-sorted, pre-filtered rows from subscriptions.<br>• Sort, rows and selection reach JavaFX through three NEW `make-ext-with-props` extensions: `ext-with-sort`, `ext-with-rows` (`.set` only the changed rows) and `ext-with-selection` (by id, re-applied after every rows change).<br>• `:sort-policy` accepts without sorting, and `:on-sort` dispatches `:shows/set-sort`.<br>• **JavaFX owns the selection gestures** (click, ⌘, Shift, arrows), so there is no `:anchor`.<br>• **One edit field at a time**, with its draft in `[:ui :shows :editing]`.<br>• Rows don't hold the global BPM: an unlocked show's duration cell subscribes to it (`06` §4.1–§4.4, §7) | **One owner for the order:** prev and next, range select and tests read the same order.<br>**The probe showed the plain props fail:**<br>• the sort arrow is lost on the third click;<br>• the selection is lost when its own row changes;<br>• a field in every cell loses focus whenever the rows change.<br>The extensions and the single edit field pass (`06` §1.3) |
| TS-8 | **Search.**<br>• One text field plus a scope menu: All, Title, Artist, Album, Key, Genre, BPM, Duration.<br>• It filters on every keystroke. Matching is case-insensitive, and every whitespace token must match.<br>• The BPM and Duration scopes accept a number, `a-b` or `a+`.<br>• The field shows the state's text only while it is unfocused, or when the query is cleared (NEW `ext-with-query-text`) | Music-player muscle memory. **Measured at 1000 shows:** about 1–2 ms per key, so no debounce. **A field bound to state on every key garbled its text** in 10 of 16 probe runs (`06` §1.3, check g) |
| TS-9 | Playing a show by hand stops the show you played by hand before it. Grid cues and timecode-driven shows are untouched. An enabled (TC) show can't be played by hand: Play is refused with *"Follows timecode. Untick TC to play it by hand."* | A music player plays one song. The refusal keeps today's rule (`unless-chasing`) and the chase ownership plan 15 made safe |
| TS-10 | The now-playing bar shows the show playing by hand or, failing that, the one timecode is driving. Prev and Next follow the table's visible order | It answers "what's on now", with either source |
| TS-11 | Triggers: `:start-timeline` and `:stop-timeline` take a `[:show id]` target. NEW `:toggle-show` mirrors a cell press: it starts the show from beat 0, or stops it, through NEW `:shows/toggle-run` (not `:timeline/toggle-play`, which pauses). A momentary `:toggle-show` plays while held. Migration rewrites a moved cell's `:trigger-cue` to `:toggle-show`, keeping `:behavior`, and its timeline targets to `[:show id]` (`04` §4.5) | A MIDI pad that played a timeline cell keeps doing exactly what it did |
| TS-12 | Vibes don't play shows. Migration removes vibe references to moved cells (`refs/remove-cue`) and counts them in the banner | A show is minutes long. It isn't a hit, accent or bed |
| TS-13 | Table UI state (query, scope, sort, selection, the banner) is NEW `[:ui :shows]`, for the session. New and Open reset the selection and the banner | Matches `[:ui :vibes]` (`state/domains.clj:221-222`), which is also for the session only |
| TS-14 | The Shows tab shows the preview, like Grid | You run a show from it and need to see the output |
| TS-15 | A show map has no top-level `:id`; its id is only its key. Migration carries a cue's column default into the show's `:target`, and stamps `:editor-type :timeline` on inferred timelines. The migration never throws, because Save re-runs it (`04` §4.1, §4.3) | History treats maps that have an `:id` as chain items. A show-level id would duplicate the key, and could disagree with it in a hand-edited file. The column target keeps old shows drawing where they did |
| TS-16 | The timeline editor survives its show going: undo leaves the window open with a "no longer in the library" placeholder, and redo brings the show back. `:shows/delete` closes it. No `:timeline/*` handler ever creates a show (`05` §5.3) | Today a cleared cell can be recreated by a header drag or a rename (`events/handlers/timeline.clj:243-255`) |

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| NEW `[:chains :shows]` `{uuid show}` (`01` §1) | document: `content.edn` row `{:path [:chains :shows] :owner :show}` | `:shows/*` library edits; `:timeline/*` editor edits; `migration/timeline-shows` on Open | yes / yes, through `:chains` (`state/history.clj:151-189`). A track's `:muted?` and `:collapsed?` stay live (:60-63) |
| `[:chains :shows id :show/info]` `{:artist :album :key :genre}` NEW | document | `:shows/set-info` | yes / yes |
| `[:chains :shows id :timeline/chase]` (existing key, new place) | document | `:shows/set-timecode`; the middleware's automatic turn-off | yes / yes ("Enable Timecode", or the edit's own label) |
| `[:chains :cue-chains]` | document, unchanged | — | no timeline is ever there again (TS-3) |
| `[:playback :active-cues [:show id]]` | session | `:timeline/play`, `:shows/play`, `:shows/stop`, `:show-clock/apply-chase` (`events/handlers/timing.clj:203-222`) | no / no |
| `[:playback :chase-sources :cells [:show id]]` | session | `reconcile-chase-sources` (`events/handlers/timeline.clj:506-538`), re-pointed at shows | no / no |
| NEW `[:ui :shows]` `{:query "" :scope :all :sort {:column :title :dir :asc} :selected #{} :editing nil :manual nil :migrated nil}` | UI, session | `:shows/set-query`, `set-scope`, `set-sort`, `select`; `:editing` by `start-edit`, `edit-draft`, `commit-edit` and `cancel-edit`; `:manual` by `:shows/play` and `:shows/stop`; `:migrated` by the Open path, cleared by `:shows/dismiss-migration` | no / no. `reset-session` (`state/persistent.clj:269-306`) keeps `:query`, `:scope` and `:sort`, and resets the rest before the migration writes `:migrated` (`04` §4.2) |
| `[:ui :timeline-editor show-id]`, re-keyed from `[col row]` (`state/domains.clj:211-212`) | UI, session | timeline view handlers | no / no. NEW: reset on New and Open |
| `[:ui :dialogs :timeline-cue-editor]` `{:open? :show-id}` (was `{:col :row}`) | UI | `:shows/open-editor` → `:ui/open-dialog` (`events/handlers/ui.clj:31-43`) | no / no |
| `[:list-ui [:timeline-tracks show-id]]` (was `[… col row]`) | UI | list handlers | no / no |
| `[:config :input :trigger-map key]` actions with `[:show id]` targets | document (`[:config]`) | migration; future trigger learning (Q-4) | yes / yes |
| Live position of the show playing | runtime: show-time's published snapshot `:positions` (`animation/show_time.clj:383-388`) | the show clock thread | n/a, never app state |

Subscriptions (all NEW, in `subs.clj`):
- `show-rows`: every row's data.
- `visible-show-rows`: filtered and sorted.
- `show-table-ui`.
- `show-row-status`: playing by hand, by timecode or not, by id.
- `now-playing`.
- `show-cue`: replaces `cue-cell-chain` for the editor.
- `timeline-ui show-id`, `timeline-transport show-id`: re-keyed.

## 6. Events and effects

Every handler is pure and returns `:state`. A handler's notice uses the existing `:dispatch-later` effect (`events/core.clj:987`), as `h/show-notice` returns it (`events/helpers.clj:73-87`). The middleware's notice uses NEW `h/merge-notice`, and `:dispatch-later` is extended to take a vector of timers (`07` §4.1). **There are no new effect keys.**

| Event | Exists? | Dispatched from | Undo label |
|---|---|---|---|
| `"shows"` domain → `events/handlers/shows.clj` `handle` | NEW route in `events/handlers.clj:45-86` | — | — |
| `:shows/create {:title?}` | NEW. Selects the new show and starts editing its Title | the tab's **+ New Show** button. Not ⌘N, which is File > New (`views/components/title_bar.clj:67`) | "New Show" |
| `:shows/duplicate {:show-ids}` or `{:from-row id}` | NEW. The copy has TC off | row menu | "Duplicate Show(s)" |
| `:shows/request-delete {:show-ids}` or `{:from-row id}` / `{:from-selection true}`, then `:shows/delete` | NEW | row menu and Delete key (consumed by the table, so Edit > Clear Cell doesn't run) → the confirm dialog's `:confirm-event` (`events/handlers/ui.clj:97-119`) | "Delete Show(s)" |
| `:shows/start-edit {:show-id :column}`, `:shows/edit-draft {:show-id :column :text}`, `:shows/commit-edit {:show-id :column :then}`, `:shows/cancel-edit` | NEW (`06` §6). `commit-edit` applies the draft with the same pure functions as `set-info`, `set-bpm` and `set-tc-start`. A draft that doesn't parse stays open, marked invalid, with a notice | slow click and F2; the edit field's keys | "Edit Show Info", "Set Timeline Tempo" or "Set Timecode", by column |
| `:shows/set-info {:show-id :field :value}`, field one of `:name :artist :album :key :genre` | NEW. The pure core of `commit-edit`, also used by tests | — | "Edit Show Info" |
| `:shows/set-bpm {:show-id :text}` | NEW. Parses the text, then applies model op `:set-tempo` | — (through `commit-edit`) | "Set Timeline Tempo" (`model/op-label`) |
| `:shows/set-tc-start {:show-id :text}` | NEW. `tc/parse-tc` at the show's fps, then op `:set-tc-start` | — (through `commit-edit`) | "Set Timecode" |
| `:shows/set-timecode {:show-ids :enabled?}` or `{:from-row id}` | NEW. Checks TS-6, then op `:set-chase` | TC checkbox in an `applied-control`; row menu (several rows) | "Enable Timecode" / "Disable Timecode" |
| `:shows/open-editor {:show-id}` or `{:from-selection true}` | NEW. Chains `:ui/open-dialog` | row menu; ⌘E in the table | — |
| `:shows/new-from-cue {:col :row}` | NEW. Copies the cue, converts it (`model/convert-to-timeline`), adds it, opens the Shows tab and the editor | cue chain editor button (replaces `views/dialogs/cue_chain_editor.clj:446-457`) | "New Show from Cue" |
| `:shows/set-query {:text}`, `:shows/set-scope {:scope}`, `:shows/set-sort {:column :dir}`, `:shows/select {:show-ids}`, `:shows/dismiss-migration` | NEW | search field `:on-text-changed`, scope combo, table `:on-sort`, the table's selection listener (JavaFX's own gestures), banner ✕ | — (UI state) |
| `:shows/toggle-run {:show-id}` | NEW. Starts from beat 0 or stops, as a grid press did; a follower is left alone (`04` §4.5) | the `:toggle-show` trigger | — |
| `:shows/play {:show-id}` or `{:from-selection true}` | NEW. Checks TS-9, stops `[:ui :shows :manual]`'s run, then does `:timeline/play`'s work from beat 0 | double-click on a row; Enter in the table; ⏯ with nothing playing | — |
| `:shows/toggle-play`, `:shows/stop`, `:shows/seek {:beat}` | NEW thin wrappers over `:timeline/toggle-play`, `stop`, `seek` (`events/handlers/timeline.clj:407-428`) for the bar's show | now-playing bar; Space in the table from S5 (in S3 the table's Space runs Transport Play/Stop, because the TableView would otherwise swallow it) | — |
| `:shows/next`, `:shows/previous` | NEW. Use `shows/visible-ids` on the handler's own state | ⏮ ⏭ | — |
| `:timeline/*` (every one in `events/handlers/timeline.clj:574-630`) | exist; `:col :row` → `:show-id`. NEW `:timeline/set-name` and `:timeline/set-cue-target` for the dialog's footer. `:timeline/seek` gains `unless-chasing` (`05` §6, `07` §4.6) | timeline editor views (S2) | unchanged; "Rename Show", "Change Show Target" |
| `:timeline-cue-editor/open` | exists (`events/handlers/timeline.clj:625`). **Removed**; `:shows/open-editor` replaces it | was `grid_cell.clj:321, :344`; `cue_chain_editor.clj:455`; `grid.clj:188`; UI tests (`T/ui_test/` timeline_editor, dialogs, zones, keyboard_trigger, timeline_visual, zone_patterns) | — |
| `:grid/paste-cell` | exists (`events/handlers/grid.clj:146-156`). Refuses a timeline clip with a notice | — | — |
| `:trigger/input` actions `:start-timeline`, `:stop-timeline` with `[:show id]`; NEW `:toggle-show` | extend `events/handlers/triggers.clj:171-176` | the input router, unchanged | — (`:source-trigger` adds no step) |
| `:show-clock/chase`, `apply-chase`, `cue-ended` | exist (`events/handlers/timing.clj:160-267`); `:cell` may now be `[:show id]` | show clock (`services/show_clock.clj`) | — |
| `:file/project-loaded` | exists (`handle-file-project-loaded`, `events/handlers/menu.clj:192-212`, through `prepare-open` :164-190); `apply-loaded-project` now runs `migration/timeline-shows` | — | — (history excluded) |

**Show lock** (`events/show_lock.clj:34-96`, `04` §4.12):
- `:shows/set-timecode`, `:shows/set-bpm`, `:shows/set-tc-start`, `:shows/request-delete`, `:shows/delete` and `:timeline/set-cue-target` join `blocked-events`. Each one can start or stop light, or move what lights when, mid-show. They are checked by `blocked-events-name-real-events-test` (`T/events/show_lock_test.clj:302`).
- `:shows/commit-edit` is refused only for the `:bpm` and `:tc-start` columns. That uses a NEW predicate next to `pattern-edit?` (`events/show_lock.clj:109`, applied at :207), because the lock otherwise decides by event type alone.
- `:shows/create`, `:shows/duplicate` and `:shows/new-from-cue` stay open. Each adds a show with TC off and no trigger, so nothing that plays changes.
- Metadata edits, sorting, search and Play stay open, as grid triggers do.

## 7. Fit with other plans

| Plan | Overlap | How it fits |
|---|---|---|
| Reactivity 15 (chase lifetime) | Chase versions, episode ids `[doc cell version origin tick]`, reports with `:expected-owner` | The same logic with `[:show id]` in the `cell` slot. Its tests (`test/laser_show/animation/show_time_test.clj`) gain show-key copies in S1 |
| Reactivity follow-up B7 | The grid could not stop a chased cue | Fixed on master (`1a75392a`, `[:playback :chase-stopped]`). S1 keys `:chase-stopped` by playback key; S2's `:shows/toggle-run` reuses its stop and follow-again for a chased show |
| Plugins (`plans/2026-11-24-plugins/`) | Action catalog | When plugins land, `:shows/play`, `:shows/stop` and `:shows/set-timecode` are candidates for the catalog (`:lights? true`). No change now |
| Live performance UX | The design language | The status chip in the TC column uses the shared states: ready, on, and AUTO for a timecode-driven show |

## 8. Risks

- **Re-keying the editor touches 262 lines.** It is mechanical but wide. Mitigation: `05` §8.3 gives a commit order where each step ends green. The 28 existing timeline-editor UI tests (`test/laser_show/ui_test/timeline_editor_test.clj:33-768`) keep their assertions and change only their setup, opening a show made with `:shows/new-from-cue`.
- **TableView is new to the app.** S0 has run (`06` §1.3). The plain cljfx props fail on the third sort click, on selection when its own row changes, and on a field in every cell. The four extensions and the single edit field pass their checks. Remaining risks:
  - the extensions are new imperative code;
  - **the edit field must take focus only once its skin exists**, or its first key replaces the draft (probe d2);
  - the slow-click start, the commit on focus loss, and headless Shift/⌘ clicks are *unverified*, and each has a UI test (`06` §8);
  - TestFX's headless robot can't click a column header, so UI tests fire plain mouse events instead (`ui/mouse-click!`).
- **The same search race exists today** in `views/dialogs/zone_group_editor.clj:72-79` and `add_projector_manual.clj:58, :72, :123`: `:text` is bound to state on every key. This plan doesn't fix them; add them to the follow-ups list.
- **The turn-off can surprise.** A clip drag can turn TC off. The notice names the show, Undo brings TC back with the drag, and the TC column flags it (`07` §7.1).
- ~~**Migration on open is one-way.**~~ (dropped with the migration) Saving writes `[:chains :shows]`, so an older build opening the file sees no timelines. This is unverified for older builds. The banner says so, and the file is only rewritten when you save.
- ~~**Every Save re-runs the migration**~~ (dropped with the migration) in its dry run (`04` §1.2). It is pure and its result is discarded, but it must never throw, or Save would refuse (M-8).
- **Filter cost** was measured, in the probe rather than the app, at about 1–2 ms median per keystroke for 1000 shows (p95 2.3 ms with rows cached) and about 8–10 ms for 5000 (`06` §1.4). Rows are their own subscription and don't depend on the global BPM, so a tempo change doesn't rebuild them. Libraries above about 5000 shows may need a re-check.

## 9. Questions

| # | Question | Proposed answer |
|---|---|---|
| Q-1 | TS-6: when an edit makes an enabled show overlap or lose its BPM, should TC turn off for the edited show (with a notice), or should the edit be refused? | Turn off. Refusing breaks clip editing |
| Q-2 | TS-9: may an enabled show be played by hand while timecode is stopped, e.g. for rehearsal? | No in this plan. Untick TC first. A "rehearse" mode can come later |
| Q-3 | Playing a show by hand: should grid cues keep playing? | Yes |
| Q-4 | Learning a MIDI trigger for a show from its row menu? | Later. Migration keeps the existing bindings working |
| Q-5 | Copy and paste shows between projects? | Later. Duplicate covers within a project |
| Q-6 | Auto-advance, so the next show starts when one ends, like a music player? | Not in this plan. Timecode is the way to run a set unattended |
| Q-7 | Extra columns later (Rating, Tags, Notes, custom)? | `:show/info` takes new keys without a format change. Add them on request |
| Q-8 | Edit metadata in the table cells (one edit field at a time), or in a separate Info panel? | In the cells, as a music player does. The probe's single-field edit mode passed 3 of 3 runs (`06` §1.3, d2). An Info panel is the fallback if the slow-click start or the commit on focus loss can't be made reliable |
| Q-9 | Should a show that ends past 24:00:00:00 be refused for timecode? | Not in this plan: the decoder doesn't wrap, and a sender that wraps stops the show (`07` §7.2). Revisit if you run 24 h timecode |

## 10. Alternatives considered

- **Keep timelines in the grid and make the tab a view over them.** This is the least code, but every row's identity would be a cell. Moving a cell would reorder the library, and the grid would keep all its timeline special cases. You chose a library.
- **Grid link cells that trigger a library show.** These could come later on top of `[:show id]` triggers (TS-11). Not asked for.
- **A separate document root `[:shows]`** outside `:chains`. That would need its own rows in `document-roots`, `extract-document-state`, `show-content-changed?` (with live-field comparison) and `restore-document` (`state/history.clj:65-342`). Living under `:chains` gets all of these from code that already walks chain domains.
- **A session-only TC set**, not saved. You chose "saved with project", and `:timeline/chase` is already saved.
- **A separate "enabled" flag beside `:timeline/chase`.** That would be two keys for one fact. The chase key already means "plays from timecode".
- **Allowing overlaps with a warning.** Two shows lighting at once is what you wanted blocked.
- **Approximate ranges for shows without a BPM**, using the global BPM. The range would move when someone taps tempo, so an overlap check made at enable time wouldn't hold.
- **`list-editor` rows instead of a TableView.** That component isn't virtualized and has no column headers. A 500-song library needs both.
- **Our own header buttons instead of JavaFX sorting.** Not needed: `ext-with-sort` passed every probe check. Kept as the fallback.
- **A text field in every editable cell** (`commit-field`). It failed the probe: whenever the rows change, the focus is lost and half an edit is committed (`06` §1.3, d).
- **An Info panel beside the table.** It is more robust than in-cell editing, but less like a music player. It is kept as the fallback (Q-8).
