# Timeline shows: the table and the search box

**Status:** Proposed   **Last verified against:** `63240576`
**Supersedes / Superseded by:** none. It details S3 of `02-phases.md` and settles TS-7 and TS-8 of `00-overview.md`.
**Related findings:** `00` TS-7, TS-8, TS-13, §8 "TableView is new" and "Filter cost"; `01` §3, §7; `02` S0, S3.

Paths are under `src/laser_show/` unless they start with `test/`, `plans/` or `docs/`. `JAR/` means the cljfx 1.10.6 jar (`~/.m2/repository/cljfx/cljfx/1.10.6/cljfx-1.10.6.jar`) unpacked, so `JAR/cljfx/fx/table_view.clj:49` is a line in that jar. **NEW** marks anything that doesn't exist yet. Probe results are quoted from the logs of the runs listed in §1.4.

## 1. Current behaviour (file:line)

### 1.1 The app

- **Nothing in `src/` uses `:table-view`, `:list-view` or a search field** (`00` §1, re-checked with grep). The tabs are routed in `views/root.clj:66-79` and listed in `main-tabs` :85-93. A tab button's id is `(str (name tab-id) "-tab")` (`views/components/tabs.clj:16`), so the Shows tab button will be `#shows-tab`.
- **Text fields that commit:** `commit-field` (`views/components/commit_field.clj:57-97`). It applies on Enter (`:on-action`, :94-95) or on focus loss (its focus listener, :79-85). Its props are `:id :text :on-commit :pref-width :disable :style-class :style :prompt-text` (:65-68). It passes `:pref-width` through unchanged (:90), and cljfx can't coerce `nil` (see §2, P8), so every caller passes a width.
- **Live text fields** write state on every keystroke with `:on-text-changed`, and bind `:text` to that state: `views/dialogs/zone_group_editor.clj:72-79`.
- **Refusable native controls** use `applied-value/applied-control` (`views/components/applied_value.clj:217-250`). It owns the control's value, `:user-data` and listener, and it puts the applied value back `settle-ms` (:44-47, 300 ms) after a refused change. Its `:address` prop is a cljfx ext prop made with `fx/make-ext-with-props` and a setter (`ext-with-address`, :210-214). That is the pattern this plan copies for its three table props.
- **A combo box inside `applied-control`**, with `:button-cell` and `:cell-factory` label functions: `views/components/zone_pattern_editor.clj:507-517`.
- **Context menus:** a `{:fx/type :context-menu :items […]}` on a wrapping `:label` with `:content-display :graphic-only`. The grid does this at `views/components/grid_cell.clj:232-239`, and list rows at `views/components/list.clj:541-549`.
- **Region keyboard handling:** the list editor claims `#{:delete :escape}` with `keys/claim-keys!` and installs a `KEY_PRESSED` event filter that runs only when `keys/list-key-target?` is true (`views/components/list.clj:818-860`).
  - The filter finds its list from the node's `:user-data` when the key arrives (`list-address`, :71-78).
  - `keys/typing-target?` (`views/keys.clj:9-17`) is true for text inputs and editable combos and spinners. `list-key-target?` (:24-28) means "focus inside, nobody typing". `claim-keys!` is at :30-36 and `claims?` at :54-62.
- **Scene keyboard filter:** `key-triggers/wrap-scene` (`views/key_triggers.clj:96-104`) installs `handle-key!` (:35-66) as a `KEY_PRESSED`/`KEY_RELEASED` filter on the scene. A key that is claimed (:44) or being typed (:42) never reaches the trigger router (`input/keyboard.clj:37-57` `decide`).
- **Menu accelerators:**

  | Key | Menu item | Where |
  |---|---|---|
  | Space | Transport Play / Stop | `views/components/title_bar.clj:144-147` |
  | Escape | Stop, a hidden item | :151-156 |
  | Delete | Edit > Clear Cell | :134-137 |
  | ⌘N | File > New | :67 |

  - The timeline dialog keeps Space for itself: `:claimed-keys #{:space}` plus a handler that consumes it when nobody is typing (`views/dialogs/timeline_cue_editor.clj:130-138, :148-149`).
  - **⌘E and ⌘F are free.** No `:accelerator` in `src/` uses them (grep over `:accelerator`).
- **Focus housekeeping in the main window:**
  - `leave-text-field-on-click` (`views/root.clj:163-179`): a press on something that doesn't take the focus moves the focus to the layout, so a field applies what was typed.
  - `take-focus-from-removed-owners!` (:181-194): when the focus owner leaves the scene, the layout takes the focus on the next `runLater`.
- **CSS modules:**
  - A module is `(css/register ::name {...})` built from `theme/base-colors` (`css/theme.clj:32-52`) and `theme/semantic-colors` (:59-86). `css/timeline.clj:8-11` is an example.
  - Every module is required and listed in `css/core.clj:16-30` and `all-stylesheet-urls` (:37-59).
  - The dark text field is `.text-field-dark` (`css/forms.clj:27-30`).
  - `.tl-mtc-chip` (`css/timeline.clj:34-47`) and `.tl-readout-approx` (:54-55) are the styles to copy for the TC chip and the "≈" duration.
- **Events and state:**
  - `events/dispatch!` is asynchronous: events are queued to the event agent (`events/core.clj:1122`). Tests wait with `events/flush!` (:1148) and seed state with `events/write-state!` (:1164).
  - A handler can name its undo step with `:history/description` (`events/handlers/history.clj:10`, read at :391). Otherwise the step is named from the event type (`event-descriptions` :213, `format-event-description` :315-321).
  - Notices come from `h/show-notice` (`events/helpers.clj:73-86`).
  - The shared confirm dialog is opened by writing `[:ui :dialogs :confirm]` with a `:confirm-event` (`events/handlers/zone_maps.clj:182-195`). Its button runs `:ui/confirm` (`events/handlers/ui.clj:97-120`).
  - Session UI state lives in `defstate ui` (`state/domains.clj:156`). For example, `[:ui :vibes]` is at :221-222.

### 1.2 cljfx 1.10.6 (from the jar)

| Thing | Where | What it does |
|---|---|---|
| `:table-view` props | `JAR/cljfx/fx/table_view.clj:35-67` | `:columns [:list dynamics]` :46, `:fixed-cell-size` :48, `:items [:list scalar]` :49, `:on-sort` (event handler) :52, `:placeholder` (dynamic) :53, `:row-factory` (`{:fx/cell-type …}` or a Callback) :54-56, `:sort-order [:list dynamics]` :64, `:sort-policy` :65-66, `:table-menu-button-visible` :67 |
| `:sort-policy` coercion | `JAR/cljfx/fx/table_view.clj:26-30` | a `javafx.util.Callback` or `:default`. A plain fn is **not** accepted |
| `:table-column` props | `JAR/cljfx/fx/table_column.clj:31-51` | `:cell-factory` (`{:fx/cell-type :table-cell :describe f}`) :38-42, `:cell-value-factory` (any `ifn?`, wrapped as a constant observable) :14-21 and :43-44, `:sort-type` (`:ascending` / `:descending`) :49-51 |
| column base props | `JAR/cljfx/fx/table_column_base.clj:15-31` | `:comparator`, `:context-menu`, `:graphic`, `:id` :20, `:min-width`, `:pref-width`, `:resizable`, `:sort-node`, `:sortable` :26, `:style-class`, `:text`, `:visible` |
| selection ext | `JAR/cljfx/ext/table_view.clj:6-28`, `JAR/cljfx/ext/multiple_selection_model.clj:11-20, :61-74` | `with-selection-props`: `:selection-mode`, `:selected-indices` (clears the selection, then selects), `:on-selected-items-changed` (gets a vector of items) |
| cell factory | `JAR/cljfx/ext/cell_factory.clj:17-35` | A cell is described again only when its item or emptiness changes and isn't `identical?` (:25-28), or when the `:describe` fn is not `=` (:30-31). Its listeners are `bound-fn`s made during a render (:53-56) |
| prop setter | `JAR/cljfx/mutator.clj:25-34` | A setter runs only when the new value isn't `=` to the old one. A `:list` prop does `setAll` (:61-72) |
| `:text` of a text input | `JAR/cljfx/fx/text_input_control.clj:22-25` | `setText` whenever the description's text differs from what the field shows |
| muted listeners | `JAR/cljfx/lifecycle.clj:57-69`, :161, :169, :206 | While cljfx renders (`*in-progress?*`), cljfx's own event handlers don't fire. A plain JavaFX listener still does |
| ext props | `JAR/cljfx/api.clj:247-257`, `JAR/cljfx/lifecycle.clj:460-486` | `make-ext-with-props` advances the child first, then its own props |
| refs | `JAR/cljfx/api.clj:184-206` | `ext-let-refs` / `ext-get-ref`: one instance used at two places, such as a column in `:columns` and in `:sort-order` |

### 1.3 JavaFX 28-ea+11, measured by the S0 probe

The probe (`plans/2026-11-25-timeline-shows/probes/table_sort_probe.clj`) mounts a 4-column table:

- with `fx/create-app`, so events are async as in the app;
- using the app's real `commit-field` and `applied-control`, with `events/dispatch!` re-pointed at the probe's own app;
- driven by TestFX's headless glass robot.

| # | Check | Result |
|---|---|---|
| a | A header click with `:sort-policy` returning true and `:on-sort` reading `.getSortOrder`/`.getSortType` | **Pass.** `on-sort` fires once per click, before the policy. State gets `{:column :artist :dir :asc}`. The arrow shows on Artist. JavaFX never permutes the item list (0 permutations over the whole run) |
| b | `:items` replaced from state keeps the arrow | **Pass**, for both a re-sort and an unrelated row edit |
| c | The third click (JavaFX empties the sort order) mapped back to ascending | **Fails with `:sort-order`** (`ext-get-ref`) plus column `:sort-type`. The state becomes `:asc`, but the sort order stays `[]` and the arrow is gone. The `:sort-order` description `[ref :artist]` is unchanged, so cljfx never sets it again (`JAR/cljfx/mutator.clj:61-72` compares the same column instances). **Passes with a NEW ext prop** `:applied-sort {:column :dir}` whose setter sets the sort type and the sort order (probe `apply-sort!`): 9/9 checks in each of 5 runs |
| d | A cell holding a `commit-field`: focus and caret while typing, Enter commits once | **Fails.** Any `:items` change, even one row's `.set`, makes JavaFX rebuild the cells. The focused field leaves the scene ("old field still in a scene false"), the focus goes to the first traversable node (the search field), and the field's focus-loss commit sends the half-typed text (`set-artist "deadmau5Z"`). Clicking into a cell's field doesn't select the row, and a double-click on it doesn't reach the row's handler |
| d2 | An **edit mode** instead: the one edited cell's field is a context component showing a draft from app state. The field takes the focus once its skin exists, with the caret at the end | **Passes** in 3 of 3 runs: it refocuses after an items change and after a re-sort, the draft survives, and Enter commits once. One trap: a field focused before its skin exists gets all its text selected when the skin is made, so the first key replaced the draft (2 failures in 4 runs until the focus waited for the skin). The cause, found with javap: `TextFieldBehavior.<init>` calls `selectRange` when its field already owns the focus |
| e | Selection re-applied by id survives an items change | `with-selection-props :selected-indices`: **passes** a re-sort and an unrelated row edit, but **fails** when the selected row's own data changes. JavaFX drops the selection and the indices description is unchanged. **NEW `ext-with-selection`**, whose value carries the rows and whose setter selects by id when JavaFX differs: **passes all four** |
| f | Keys while the table has focus | The TableView **consumes Space** itself. Enter, Delete and E reach the scene's accelerators. Space typed in a cell's text field reaches no accelerator |
| g | Search field with `:text` bound to state, 80 ms per `:set-query` | **Corrupts typing in 10 of 16 runs.** A late render sets an older text, the caret jumps, and later keys land mid-field: `"one moredaft punk "`, `"ft punk one moreda"`. The **NEW guarded setter** (`ext-with-query-text`: set only when the field isn't focused or the query is `""`) **passed 15 of 15** |
| h | `applied-control` check box in a cell | Pass: a tick applies, a refused tick snaps back after 300 ms, and after a re-sort each reused cell shows its new row's value |
| i, j | Row menu and double-click | A right-click opens the row's `:context-menu` ("Play Strobe"). A double-click on a label cell dispatches play from the row factory's `:on-mouse-clicked` |
| — | Headless robot clicks | `FxRobot.clickOn` doesn't sort: the headless robot's clicks are popup triggers, which a `TableColumnHeader` ignores (0 `on-sort` calls). A not-synthesized, not-popup-trigger PRESSED/RELEASED/CLICKED at the header (probe `fire-click!`, the events of `ui/press-node!` and `release-node!`) does sort. A synthetic **shift**-click on a cell did *not* extend the selection (unexplained; 1 attempt) |
| — | Header id | A `TableColumnHeader`'s id is its column's `:id` (`:header-id "artist"`), so `#shows-col-artist` finds the header |

### 1.4 Probe commands and runs

**Table probe**, from the repo root, macOS arm64, JDK 25.0.5, JavaFX 28-ea+11, glass `headless`. `:mac-arm` and `:test-ui-headless` come from `deps.edn`. `:probe` adds TestFX and the `:test-ui` JVM options without its `-m` main.

```sh
clojure -Sdeps '{:aliases {:probe {:extra-deps {org.testfx/testfx-core {:mvn/version "4.0.18"}}
  :jvm-opts ["-Djavafx.enablePreview=true" "-Djavafx.suppressPreviewWarning=true" "--enable-native-access=ALL-UNNAMED"
             "--add-exports=javafx.graphics/com.sun.javafx.application=ALL-UNNAMED"
             "--add-exports=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED"
             "--add-opens=javafx.graphics/com.sun.glass.ui=ALL-UNNAMED"]}}}' \
  -M:mac-arm:test-ui-headless:probe plans/2026-11-25-timeline-shows/probes/table_sort_probe.clj
```

- It takes about 60 s. The last three runs gave: **PASS 57/56/54, FAIL 15/16/18**.
- The expected FAILs are the rejected variants: the robot-click run, `:sort-order` (c), `commit-field` cells (d), and `:selected-indices` on an own-row change.
- The rest of the spread is check g on the bound field.

**Filter probe:** `clojure -M plans/2026-11-25-timeline-shows/probes/filter_cost_probe.clj`. It runs a cut-down copy of `01` §3 (rows, filter, sort), checks the §3.3 and §3.4 examples first, then takes 500 timed runs after 300 warm-up runs, with `System/nanoTime`. criterium isn't in `deps.edn`.

| 1000 shows (same JVM, 8 cores) | median | p95 |
|---|---|---|
| `rows` only | 0.43 ms | 1.14 ms |
| rows + `""` + sort by title | 1.82 ms | 4.66 ms |
| rows + `"da"` in `:all` + sort by artist | 1.03 ms | 2.78 ms |
| rows + `"120-128"` in `:bpm` + sort by title | 0.59 ms | 1.67 ms |
| filter + sort on memoized rows, `""` | 1.25 ms | 2.31 ms |
| filter + sort on memoized rows, `"da"` | 0.45 ms | 1.20 ms |
| 5000 shows, memoized rows, `""` | 8.40 ms | 9.63 ms |

- **Under the 4 ms target at the median for 1000 rows.** The p95 of a full recompute is 4.66 ms. With `rows` as its own subscription (§4.4), a keystroke costs filter + sort: p95 2.31 ms.
- This is the probe's code on a quiet JVM, not the app on the FX thread, where cljfx computes subscriptions during render. The app figure is *unverified* until S3's REPL check.
- The comparator lower-cases on every compare. Pre-computed sort keys would cut this; the size of the gain is *unverified*.

## 2. Root cause

What 00, 01 and 02 assume and the probe disproves:

- **P1, the third click.** cljfx re-sets a `:list` prop only when the component instances differ. JavaFX's third click empties the sort order, the state maps it back to the same column, and `:sort-order` never changes, so the arrow is lost. The sort must be applied through a setter keyed on the *state value* (`{:column :dir}`), which does change from `:desc` to `:asc`.
- **P2, cells are rebuilt on every items change** (JavaFX 28-ea+11). An always-present text field in a cell loses the focus, and `commit-field` then commits half an edit (commit_field.clj:79-85). The rows also change more often than "when you edit a show":
  - 01 §3.1 puts the approximate duration at the *global* BPM into each row, so every tempo change rebuilds the cells: tap tempo, and Link (`events/handlers/timing.clj:114-119` follows Link's tempo).
  - Any edit in the timeline editor while the table is visible does the same.
- **P3, selection is lost on an own-row change.** JavaFX drops the selection of a row whose item is replaced. `:selected-indices` is unchanged, so cljfx doesn't put it back.
- **P4, a bound `:text` races async events.** `:text` from state lags the field by the event queue. The text setter (`JAR/cljfx/fx/text_input_control.clj:22-25`) then sets an older text and moves the caret. The live pattern 02 S3 names (`zone_group_editor.clj:72-79`) has this race too; it only shows when events lag behind typing.
- **P5, the TableView consumes Space.** "Outside the table Space is Transport Play/Stop as now" (`01` §3.5) holds, but *inside* it Space does nothing at all unless the table's own filter handles it, even before S5.
- **P6, Delete is not consumed.** With the table focused, Delete fires Edit > Clear Cell (`title_bar.clj:134-137`) unless the table's filter consumes it first.
- **P7, ⌘N is File > New** (`title_bar.clj:67`). `00` §6 binds ⌘N "inside the table" to `:shows/create`. That would hide File > New whenever the table has focus.
- **P8, `commit-field` without `:pref-width` throws** a cljfx coercion error (probe's first run: `NullPointerException … computed_size_double`). The props list at commit_field.clj:65 doesn't say a width is needed.
- **P9, the row factory sees only the row.** `:status` (playing) and editing aren't row data. A row-level `.shows-row-playing` class (02 S3 CSS list) would have to put status into the items, which rebuilds every cell on every play and stop (P2).

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `:table-view`, `:table-column`, `ext.table-view/with-selection-props` (`JAR/…`, §1.2) | The table, its columns, `:selection-mode :multiple` and the selection listener |
| `fx/ext-let-refs`, `fx/ext-get-ref` (`JAR/cljfx/api.clj:184-206`) | The columns are refs, so the sort setter finds them by id. Each column instance is made once |
| `fx/make-ext-with-props` (`JAR/cljfx/api.clj:247-257`), modelled on `ext-with-address` (`views/components/applied_value.clj:210-214`) | Four NEW ext props: `ext-with-sort`, `ext-with-rows`, `ext-with-selection` (in `show_table.clj`), `ext-with-query-text` (in `tabs/shows.clj`) |
| `applied-value/applied-control` (`applied_value.clj:217-250`) | The TC check box in its cell (probe h), and the scope combo with label cells as at `zone_pattern_editor.clj:507-517` |
| `keys/claim-keys!`, `keys/typing-target?`, `keys/list-key-target?` (`views/keys.clj:30-36, :9-17, :24-28`) and the filter pattern of `list.clj:818-860` | The table's key filter and claims (§4.2) |
| the `:space` claim pattern (`views/dialogs/timeline_cue_editor.clj:130-149`) | Space in the table |
| The context-menu description (`grid_cell.clj:236-237`) | The row factory's `:context-menu` |
| `h/show-notice` (`events/helpers.clj:73-86`) | A refused edit: a BPM or TC text that doesn't parse |
| `:history/description` (`events/handlers/history.clj:10, :391`) | `:shows/commit-edit`'s undo label depends on the column |
| Confirm dialog state (`events/handlers/zone_maps.clj:182-195`) | `:shows/request-delete` |
| `css/register` modules (`css/timeline.clj:8-11`), theme tokens (`css/theme.clj:32-86`), registration (`css/core.clj:16-59`) | NEW `css/shows.clj`, plus `.text-field-invalid` in `css/forms.clj` |
| `subs/bpm` (`subs.clj:59-60`) | The approximate-duration cell subscribes to it, so rows don't (P2) |
| TestFX helpers (`test/laser_show/ui_test/testfx.clj`, §8) and fixtures `with-toolkit` / `with-app` (`test/laser_show/ui_test/fixtures.clj:18, :48`) | The S3 UI tests |

**Not reused:**
- `commit-field` inside cells (probe d, P2).
- `:sort-order` (P1).
- `:selected-indices` (P3).
- A `:text`-bound search field (P4).

`commit-field` stays the right component everywhere outside a virtualized cell.

## 4. Changes per namespace

### 4.1 `laser-show.views.components.show-table` (NEW, `views/components/show_table.clj`)

**Purpose:** the first TableView in the app. It keeps the JavaFX sort, selection and edit plumbing in one place (`00` §3 justifies the NEW namespace). Public fn: `show-table [{:keys [fx/context]}]`. Everything else is private.

**Descriptor shape.** These are the real prop names from §1.2. The ext props are NEW and nest outermost-first, because an ext advances its child before its own props (`JAR/cljfx/lifecycle.clj:470-482`), so the rows are set before the selection:

```clojure
{:fx/type fx/ext-let-refs
 :refs {:status <col> :tc <col> :title <col> :artist <col> :album <col> :bpm <col>
        :key <col> :genre <col> :duration <col> :tc-start <col> :tc-end <col>}
 :desc {:fx/type fx.ext.table-view/with-selection-props
        :props {:selection-mode :multiple
                :on-selected-items-changed on-selected-rows}        ; top-level fn, §4.3
        :desc {:fx/type ext-with-selection                          ; NEW
               :props {:selection {:ids selected :rows items}}
               :desc {:fx/type ext-with-rows                        ; NEW
                      :props {:rows items}
                      :desc {:fx/type ext-with-sort                 ; NEW
                             :props {:applied-sort sort}            ; {:column :title :dir :asc}
                             :desc {:fx/type fx/ext-on-instance-lifecycle
                                    :on-created install-table-keys!  ; §4.2
                                    :desc {:fx/type :table-view
                                           :id "shows-table"
                                           :style-class ["table-view" "shows-table"]
                                           :columns [{:fx/type fx/ext-get-ref :ref :status} …]
                                           :sort-policy accept-sort       ; a reified Callback returning true
                                           :on-sort on-sort               ; top-level fn
                                           :fixed-cell-size 28
                                           :column-resize-policy :constrained
                                           :row-factory {:fx/cell-type :table-row :describe row-desc}
                                           :placeholder placeholder-desc}}}}}}}
```

- **`items`** is `visible-show-rows` (§4.4). The table fn changes only the edited row: `(assoc row ::editing column)`. It does this in the view, so the subscription stays pure and is memoized apart from editing (§5).
- **`ext-with-sort`** (NEW, private) has the prop `:applied-sort`, a `mutator/setter` over `lifecycle/scalar`. Its setter does:
  1. find the column whose `.getId` is `(str "shows-col-" (name column))`;
  2. `.setSortType` from `:dir`;
  3. `.setAll` the sort order to that column, unless it already is.

  This is the probe's `apply-sort!`, verified for checks a, b and c.
- **`ext-with-rows`** (NEW, private) has the prop `:rows`. When the ids are the same in the same order, it `.set`s only the rows that aren't `identical?`; otherwise it does `setAll` (probe `apply-rows!`).
  - Cells are rebuilt either way (P2). This only keeps the list changes small.
  - Plain `:items` together with `ext-with-selection` would probably be enough, but that pairing is *unverified* (§9).
- **`ext-with-selection`** (NEW, private) has the prop `:selection {:ids #{…} :rows items}`. The rows are part of the value, so the setter runs again after *every* rows change. It selects the indices of `ids` in the table's current items, and only when JavaFX's selected indices differ (probe `apply-selection!`). The `clearSelection` + `selectIndices` happen during a render, so the selection listener is muted (`JAR/cljfx/lifecycle.clj:57-69`) and nothing echoes back.
- **`accept-sort`** is a `def` holding a `reify Callback` that returns `true`. `:sort-policy` won't take a fn (`JAR/cljfx/fx/table_view.clj:26-30`).
- **`on-sort [^SortEvent e]`** (top-level). On the FX thread it reads `(first (.getSortOrder table))` and its `.getSortType`, then dispatches plain data: `{:event/type :shows/set-sort :column kw-or-nil :dir :asc|:desc|nil}`. JavaFX fires it before the policy (probe a: 1 `on-sort` per click). Sorts set by a render fire it muted.

**Columns.** These are pure top-level descriptions. Each `:id` is `"shows-col-<key>"`, which is also its header's id (§1.3). `:cell-value-factory identity` gives each cell the whole row (`JAR/cljfx/fx/table_column.clj:14-21`). Each `:describe` is a top-level fn, so it is `=` across renders (`JAR/cljfx/ext/cell_factory.clj:30-31`).

| Ref | Header | `:sortable` | width | `:describe` (NEW, private) | Cell graphic |
|---|---|---|---|---|---|
| `:status` | ▶ | false | 28 fixed | `status-cell-desc` | `{:fx/type status-cell :show-id id}`: a context component reading `subs/show-row-status` (S4/S5). In S3 it shows ▶ when `[:ui :shows :manual]` is this id and the key is in `[:playback :active-cues]` |
| `:tc` | TC | true | 48 | `tc-cell-desc` | `applied-control` `:property :selected :address [:show id :timeline/chase] :value (:timecode? row) :on-change #(… :shows/set-timecode …)` with `{:fx/type :check-box :id (str "shows-tc-" id)}`, plus a ⚠ `:label` `.shows-tc-problem` with a `:tooltip` from `:problem`. This is S4; S3 shows a disabled check box |
| `:title` | Title | true | 220, min 120 | `text-cell-desc :title` | label `#shows-cell-title-<id>`, or "Untitled" with `.shows-cell-muted`; the edit field while editing |
| `:artist`, `:album`, `:key`, `:genre` | … | true | 160 / 160 / 56 / 100 | `text-cell-desc <k>` | the same |
| `:bpm` | BPM | true | 64 | `text-cell-desc :bpm` | `"122.7"`, or "—" muted; the edit field while editing |
| `:duration` | Dur. | true | 64 | `duration-cell-desc` | locked: `"5:20"`. Unlocked: `{:fx/type approx-duration :beats (:length-beats row)}`, a context component on `subs/bpm` showing `"≈2:08"` with `.shows-cell-approx`. Rows don't hold the global BPM (P2, §7) |
| `:tc-start` | TC start | true | 110 | `text-cell-desc :tc-start` | `tc/format-tc` monospace; the edit field while editing |
| `:tc-end` | TC end | true | 110 | `plain-cell-desc :tc-end` | `tc/format-tc`, or "—" |

**The edit field** is NEW private `edit-field [{:keys [fx/context show-id column]}]`, a context component (the probe's `draft-field`, d2):

- It shows `(fx/sub-ctx context subs/show-edit-draft)` as `:text` and has `:id "shows-edit-field"`.
- Its classes are `["text-field-dark" "shows-edit-field"]`, plus `"text-field-invalid"` while `[:ui :shows :editing :invalid?]`.
- `:on-text-changed` is a top-level fn of `[show-id column]` data that dispatches `:shows/edit-draft`.
- An `:on-created` lifecycle does three things:
  - **Focus.** When the field already has its skin, or else when its `skinProperty` is first set, it runs `Platform/runLater` with `.requestFocus` then `.end`. That is the fix found in d2.
  - **Key filter.** Enter, Tab and Shift-Tab consume the key, `.requestFocus` the enclosing `TableView`, then dispatch `:shows/commit-edit` with `:then nil`, `:next` or `:previous`. Escape consumes, refocuses the table and dispatches `:shows/cancel-edit`.
  - **Focus loss.** It runs `Platform/runLater`, and **only if the field is still in a scene** and the focus went somewhere else in that scene, dispatches `:shows/commit-edit`. A field taken out by a cell rebuild commits nothing; its replacement takes the focus back.
- The focus-loss branch is *unverified*. The probe's d2 field had no focus-loss commit, and its replacement refocused 3 times out of 3.
- **One field at a time**, so there is never more than one draft in state.

**The row factory** is `row-desc [row]` (top-level). It returns `{:id (str "shows-row-" id) :context-menu … :on-mouse-clicked row-clicked}`.

- `row-clicked` is a top-level fn. It reads the click count and button on the FX thread.
  - A primary double-click dispatches `{:event/type :shows/play :show-id id}`.
  - A primary single click starts the slow-click edit (§4.2).
  - Probe j checks the double-click path through `:on-mouse-clicked`.
- The context menu:
  ```clojure
  {:fx/type :context-menu
   :items [{:fx/type :menu-item :text "Play" :on-action {:event/type :shows/play :show-id id}}
           {:fx/type :menu-item :text "Open in Editor" :accelerator [:shortcut :e]
            :on-action {:event/type :shows/open-editor :show-id id}}
           {:fx/type :menu-item :text (if (:timecode? row) "Disable Timecode" "Enable Timecode")
            :on-action {:event/type :shows/set-timecode :from-row id :enabled? (not (:timecode? row))}}
           {:fx/type :separator-menu-item}
           {:fx/type :menu-item :text "Duplicate" :on-action {:event/type :shows/duplicate :from-row id}}
           {:fx/type :menu-item :text "Delete…" :on-action {:event/type :shows/request-delete :from-row id}}]}
  ```
- **`:from-row id`** (NEW event key): the handler acts on the selected visible ids when `id` is selected, else on `#{id}`. The menu can't know the selection, because the row factory sees only the row (P9).
- The **accelerator on a context-menu item** only labels the menu: the table's filter handles ⌘E (§4.2).

**Placeholder** (`:placeholder`, `JAR/cljfx/fx/table_view.clj:53`). The probe showed it with empty items.

- An empty library shows a `:v-box` `#shows-empty` with *"No shows yet. + New Show, or New Show from Cue in the cue editor."*
- No match shows `#shows-no-match` with *"No shows match 'xyz'"* and a `:hyperlink` "Clear search" (`{:event/type :shows/set-query :text ""}`).
- The table picks between them from `show-table-status` (§4.4).

### 4.2 Keyboard and focus (in `show_table.clj` and `views/tabs/shows.clj`)

**NEW `install-table-keys! [^TableView table]`.** It follows the list editor (`list.clj:818-860`):

- `(keys/claim-keys! table #{:space :enter :delete :back_space :f2 :up :down})`. A user's keyboard trigger on one of these keys then doesn't fire while the table has focus (`key_triggers.clj:44`).
- A `KEY_PRESSED` **filter** on the table acts only when `(keys/list-key-target? table e)`. That is false while the edit field has the focus, because `typing-target?` is true there.

| Key, table focused | Does | Consumes |
|---|---|---|
| ↑ ↓, ⇧↑ ⇧↓, Home/End | JavaFX's own selection; its listener reports it (probe e: Down → `#{"r4"}`) | no (JavaFX does) |
| Space | S3: `:transport/play` or `:transport/stop`, read from `(state/get-raw-state)` as `key_triggers.clj:38` does, so Space keeps doing what it does elsewhere. S5: `:shows/toggle-play` | yes. The TableView would swallow it anyway (P5) |
| Enter | `:shows/play {:from-selection true}`: the first selected visible id | yes |
| Delete, Backspace | `:shows/request-delete {:from-selection true}` | yes, so Clear Cell doesn't run (P6) |
| F2 | `:shows/start-edit {:from-selection true :column :title}` | yes |
| ⌘E | `:shows/open-editor {:from-selection true}` | yes |
| Escape | not handled: Transport's hidden Stop (`title_bar.clj:151-156`) | no |

**Slow-click edit.**
1. A `MOUSE_PRESSED` filter on the table records `{:row-id :sole?}` in the table's properties. `:sole?` means the pressed row was the only selected row *before* the press. A filter runs before the behaviour selects, so it can tell.
2. In `row-clicked`, a single primary click with no modifiers on a text column, when `:sole?` was true, starts a 500 ms `PauseTransition` kept in the table's properties.
3. When it finishes, it dispatches `{:event/type :shows/start-edit :show-id id :column c}`.
4. A second click (a double-click) or any key stops it.

The column under the click is the `TableCell`'s `.getTableColumn` id, taken from the event's pick result. This gesture is *unverified* (no probe) and has its own UI test.

**Tab-level keys** go on `views/tabs/shows.clj`'s root `#shows-tab-root`, as a `KEY_PRESSED` filter:
- ⌘F focuses `#shows-search` and selects all of it.
- ⌘N is **not** bound (P7). **+ New Show** is the button.

**Focus rules:**
- Clicking a row while the search field has the focus moves the focus to the table. The table takes the focus, so `leave-text-field-on-click` (`root.clj:163-179`) leaves it alone. The search text stays, because the query is already applied.
- After Enter, Tab or Escape in the edit field, the field gives the focus to the table *before* it is removed. `take-focus-from-removed-owners!` (`root.clj:181-194`) then has nothing to do, and ↑ ↓ Enter keep working. *Unverified*; `inline-edit-commits-once-test` asserts it.
- Space typed in the edit field or the search field reaches neither the table filter (`typing-target?`) nor Transport (probe f).

### 4.3 Selection plumbing

- **JavaFX owns the gestures** in `:selection-mode :multiple`: click, ⌘-click, shift-click, arrows and shift-arrows. The NEW top-level `on-selected-rows [rows]` dispatches `{:event/type :shows/select :show-ids (mapv :id rows)}`.
- Re-sorts, filters, row edits and re-applies happen during a render, where the listener is muted, so **they never write the selection.** A row that a filter hides stays in `:selected`. JavaFX doesn't show it (probe e: filtered out, then back, still `["r2"]`).
- The next user gesture replaces `:selected` with what JavaFX shows, so a hidden selected row is dropped then. This **changes `01` §3.5**: there is no `:anchor`, and the handler doesn't compute ranges. JavaFX's anchor is the platform's; a handler copy could disagree with it.
- **"N selected"** counts `(count (filter selected visible-ids))`, from `show-table-status`.

### 4.4 `laser-show.subs` (existing `subs.clj`): NEW subscriptions

| Sub | Reads | Returns | Re-runs when |
|---|---|---|---|
| `show-rows [ctx]` | `[:chains :shows]` | `(shows/rows shows)`: rows **without** the global BPM (§7) | the library changes |
| `visible-show-rows [ctx]` | `show-rows`, `[:ui :shows :query]`, `:scope`, `:sort`; `subs/bpm` **only** when the sort column or the scope is `:duration` | `(-> rows (shows/filter-rows q scope bpm) (shows/sort-rows sort bpm))` | the library, query, scope or sort change, or the BPM while sorting or filtering by duration |
| `show-table-ui [ctx]` | `[:ui :shows]` `:query :scope :sort` | the header's values | those change |
| `show-selection [ctx]` | `[:ui :shows :selected]` | the id set | selection |
| `show-editing [ctx]` | `[:ui :shows :editing]` without `:draft` | `{:show-id :column :invalid?}` or nil | an edit starts, ends or is refused, **not on each keystroke** |
| `show-edit-draft [ctx]` | `[:ui :shows :editing :draft]` | the string | each keystroke (only `edit-field` reads it) |
| `show-table-status [ctx]` | `show-rows`, `visible-show-rows`, `show-selection` | `{:total n :visible m :on-tc k :selected-visible s :query-problem nil-or-:unparsed}` | any of those |
| `show-row-status [ctx]` | `[:playback :active-cues]`, `[:ui :shows :manual]` (S5 adds chase) | `{id :manual\|:timecode}` | playback starts or stops. Read by each `status-cell`: about one body per visible row, never an items change |

### 4.5 `laser-show.views.tabs.shows` (NEW in S2; S3 replaces its list)

`shows-tab [{:keys [fx/context]}]` is a `:border-pane` `#shows-tab-root`:

- **`:top`** is an `:h-box` `.shows-header`:
  - **+ New Show** `#shows-new`: `:shows/create`, then `:shows/start-edit` on the new show's Title, from the handler (`01` §7).
  - **The search field** is a NEW `ext-with-query-text` (private in this ns, the probe's guarded setter: set when not focused, or when the query is `""`) around `{:fx/type :text-field :id "shows-search" :prompt-text "Search shows…" :style-class ["text-field-dark" "shows-search"] (+ "text-field-invalid") :on-text-changed on-query-text}`. `on-query-text` is a top-level fn that dispatches `:shows/set-query {:text t}`.
    - Its own `KEY_PRESSED` handler turns Escape on a non-empty field into `:shows/set-query {:text ""}` and consumes it. An empty field lets Escape reach Stop as in every other field.
    - A ✕ `:button` `#shows-search-clear`, shown while the query isn't blank, sends `:shows/set-query {:text ""}`.
  - **The scope combo** `#shows-scope` is `applied-control :property :value :address [:ui :shows :scope] :value scope :on-change (fn [s] {:event/type :shows/set-scope :scope s})`, around a `:combo-box` with `:items [:all :name :artist :album :key :genre :bpm :duration]`, `:button-cell scope-cell` and `:cell-factory {:fx/cell-type :list-cell :describe scope-cell}` (pattern `zone_pattern_editor.clj:507-517`). Its labels are "All columns", "Title", "Artist", "Album", "Key", "Genre", "BPM", "Duration".
  - **The count** `#shows-count` is "12 shows", or "3 of 12 shows", then " · 2 on TC" and " · 2 selected" when s > 0.
- **The banner** sits under the header, while `[:ui :shows :migrated]` is set: an `:h-box` `.shows-banner` `#shows-migration-banner` with the `01` §6 text and a ✕ (`:shows/dismiss-migration`).
- **`:center`** is `{:fx/type show-table/show-table}`.
- **`:bottom`** is the now-playing bar (S5).

### 4.6 `laser-show.events.handlers.shows` (NEW in S2): the S3 UI events

See §6. Every handler is pure and returns `:state`. The edit events resolve the show from `:state` and drop a stale event by comparing `[:show-id :column]` with `[:ui :shows :editing]`, as the probe's `::draft` does.

### 4.7 CSS

**`laser-show.css.shows` (NEW, `css/shows.clj`):** `(def shows (css/register ::shows …))`, from `theme/base-colors` and `semantic-colors`. Require it in `css/core.clj:16-30` and add it to `all-stylesheet-urls` :37-59.

| Selector | Style |
|---|---|
| `.shows-table` | `bg-base`; `-fx-table-cell-border-color` `#262626` |
| `.shows-table .column-header`, `.shows-table .column-header-background` | `#161616`, `text-secondary`, as `.tl-toolbar` (`css/timeline.clj:16-23`) |
| `.shows-table .column-header .arrow` | `text-secondary` |
| `.shows-table .table-row-cell:selected` | `selection-bg` |
| `.shows-cell-muted` | `text-muted` |
| `.shows-cell-approx` | `-fx-opacity 0.6`, as `.tl-readout-approx` (:54-55) |
| `.shows-cell-mono` | monospace for the TC columns |
| `.shows-tc-problem` | `accent-warning` |
| `.shows-tc-chip` | copies `.tl-mtc-chip` (:34-47) |
| `.shows-header`, `.shows-search`, `.shows-banner` | `accent-info` border, `bg-elevated` |
| `.shows-edit-field` | `-fx-padding 0 4` so it fits the 28 px row |

**Not included:** `.shows-row-playing`, because of P9. The ▶ cell shows what is playing.

**`css/forms.clj` (existing):** `.text-field-invalid` (`-fx-border-color` `accent-danger`, `-fx-border-radius 3`) goes next to `.text-field-dark` (:27-30). It is generic, so it doesn't belong in `css/shows.clj` (02 S3 put it there).

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:ui :shows :query]` `""` | UI, session (`defstate ui`, `state/domains.clj:156`) | `:shows/set-query` | no / no |
| `[:ui :shows :scope]` `:all` | UI, session | `:shows/set-scope` | no / no |
| `[:ui :shows :sort]` `{:column :title :dir :asc}` | UI, session | `:shows/set-sort` (from `on-sort`) | no / no |
| `[:ui :shows :selected]` `#{}` | UI, session | `:shows/select` (from `on-selected-rows`); `:shows/delete` removes deleted ids; `:shows/create` and `:shows/duplicate` select what they make | no / no |
| ~~`[:ui :shows :anchor]`~~ | **removed** from `00` §5 / `01` §3.5 | — | — |
| NEW `[:ui :shows :editing]` `nil` or `{:show-id u :column :artist :draft "Daft Punk" :invalid? false}` | UI, session | `:shows/start-edit`, `:shows/edit-draft`, `:shows/commit-edit`, `:shows/cancel-edit`; `:shows/delete` clears it if it is on a deleted show | no / no. Reset by `reset-session` (`state/persistent.clj:269`) with `:selected` |
| Column widths, the slow-click timer, the press record | view-local: TableColumn widths, the table's `getProperties` | JavaFX, `install-table-keys!` | n/a |

- Readers: `show-table-ui` (header), `show-selection` and `visible-show-rows` (table), `show-editing` (table fn, for the `::editing` row), `show-edit-draft` (`edit-field`).
- None of these paths is a document root, so no undo step is pushed for them (`state/history.clj:65-68`; `00` TS-13).

## 6. Events and effects

No new effect keys.

| Event | Exists? | Dispatched from | Pure? / result |
|---|---|---|---|
| `:shows/set-query {:text}` | NEW | `on-query-text` (`#shows-search` `:on-text-changed`); ✕; Escape; "Clear search" | yes: assoc `:query` (raw text) |
| `:shows/set-scope {:scope}` | NEW | `#shows-scope` (`applied-control` `:on-change`) | yes: a scope not in the 8 gives `{}` |
| `:shows/set-sort {:column :dir}` | NEW | `on-sort` | yes. `:column nil` (the third click) becomes `{:column (:column old) :dir :asc}`. `:status` or an unknown column gives `{}` |
| `:shows/select {:show-ids}` | NEW | `on-selected-rows` | yes: `:selected (set show-ids)` |
| `:shows/start-edit {:show-id :column}` or `{:from-selection true :column}` | NEW | slow click; F2; `:shows/create` (`:dispatch`); Tab / Shift-Tab (via `commit-edit`) | yes. `:draft` starts as the cell's current text. Refused (`{}`) for a non-editable column or a missing show. Editing another cell commits that one first, in the same handler |
| `:shows/edit-draft {:show-id :column :text}` | NEW | `edit-field` `:on-text-changed` | yes: ignored unless `[:show-id :column]` is the current edit; clears `:invalid?` |
| `:shows/commit-edit {:show-id :column :then nil\|:next\|:previous}` | NEW | `edit-field`: Enter, Tab, Shift-Tab, focus loss | yes. It applies the draft as `:shows/set-info` / `set-bpm` / `set-tc-start` would (one shared pure fn each) and returns `:history/description` "Edit Show Info", "Set Timeline Tempo" or "Set Timecode". A draft equal to the shown value ends the edit with no change and no undo step. A draft that doesn't parse keeps `:editing` with `:invalid? true` and returns `h/show-notice`. `:then` starts the next or previous editable column of the same row |
| `:shows/cancel-edit` | NEW | `edit-field` Escape | yes: `:editing nil` |
| `:shows/play`, `:shows/open-editor`, `:shows/duplicate`, `:shows/request-delete`, `:shows/set-timecode` with `:from-row id` or `:from-selection true` | `00` §6 events; the **keys are NEW** | row menu (`row-desc`), table keys (§4.2) | yes: each resolves ids from `:state` (`:selected` ∩ `shows/visible-ids`), in visible order |
| `:transport/play` / `:transport/stop` | exist (Transport menu, `title_bar.clj:144-147`) | table Space in S3 (§4.2) | existing |

Show lock: none of the UI events is blocked. `:shows/commit-edit` is a metadata, BPM or TC-start edit, which `00` §6 leaves open.

## 7. Data examples and invariants

**Rows don't depend on the global BPM** (change to `01` §3.1 and §3.2, from P2). `shows/rows [shows]` drops its `global-bpm` param. `duration-s` and the duration filter and sort take the BPM as an argument:

```clojure
;; locked
{:id u1 :title "One More Time" :artist "Daft Punk" :album "Discovery" :bpm 122.7 :key "F#m" :genre "House"
 :length-beats 655.2 :duration-s 320.39 :approx? false
 :tc-start-s 3600.0 :tc-end-s 3920.39 :fps 25 :timecode? true :problem nil}
;; unlocked: no seconds in the row; the cell and the :duration sort/filter use subs/bpm
{:id u4 :title nil … :bpm nil :length-beats 256.0 :duration-s nil :approx? true :tc-end-s nil …}
```

**Sort** (`:shows/set-sort`, from JavaFX's sort order):

| State `:sort` before | Click | `on-sort` sends | State after | Header (probe) |
|---|---|---|---|---|
| `{:title :asc}` | Artist | `{:column :artist :dir :asc}` | `{:artist :asc}` | Artist ▲ (rotate 180) |
| `{:artist :asc}` | Artist | `{:column :artist :dir :desc}` | `{:artist :desc}` | Artist ▼ (rotate 0) |
| `{:artist :desc}` | Artist | `{:column nil :dir nil}` (JavaFX cleared it) | `{:artist :asc}` | Artist ▲, restored by `ext-with-sort` |
| `{:artist :asc}` | BPM | `{:column :bpm :dir :asc}` | `{:bpm :asc}` | BPM ▲, Artist none |
| `{:bpm :asc}` | `:shows/set-sort {:column :status}` | — | unchanged (`{}`) | — |

**Selection across changes** (`ext-with-selection`; probe e):

| `:selected` | Change | JavaFX shows |
|---|---|---|
| `#{r2}` | sort BPM ↓ (r2 moves from index 0 to 3) | r2 |
| `#{r2}` | another row's title edited, same order | r2 |
| `#{r2}` | r2's own BPM edited | r2 (with `:selected-indices`: nothing) |
| `#{r2}` | query hides r2, then clears | nothing, then r2; `:selected` stays `#{r2}` throughout |

**Edit:**

| Before | Input | After |
|---|---|---|
| `:editing nil`, r3 sole selected | slow click on r3's Artist | `{:show-id r3 :column :artist :draft "deadmau5"}`; `#shows-edit-field` focused, caret 8 |
| editing r3 Artist, draft `"deadmau5Z"` | another show renamed in the editor (items change) | same `:editing`; a new field instance, focused, `"deadmau5Z"`, caret 9 (probe d2) |
| the same | Enter | r3 `:show/info :artist "deadmau5Z"`; one undo step "Edit Show Info"; `:editing nil`; r3 still selected and re-sorted by artist; `#shows-table` focused |
| editing r3 BPM, draft `"12x"` | Enter | unchanged show; `:invalid? true`; notice *"'Strobe': BPM must be a number, e.g. 128"*; the field is still editing |
| editing r3 Title | Tab | the Title is committed, then `{:column :artist …}` |
| editing r3 Artist | Escape | `:editing nil`, show unchanged, no undo step |

**Search field** (`ext-with-query-text`):

| Field focused? | State query | Field shows | Setter |
|---|---|---|---|
| yes | `"daft p"` (late) | `"daft punk"` | skips |
| yes | `""` (Escape or ✕) | `"daft punk"` | sets `""` |
| no | `"strobe"` (set elsewhere) | `""` | sets `"strobe"`, caret at end |

**Invariants:**
- The table's items are exactly `visible-show-rows`, with at most one row carrying `::editing` (`01` §7).
- JavaFX never reorders the items: the item list gets 0 permutations (probe a).
- The header arrow is always on `(:column sort)` in `(:dir sort)` after a render (probe a, b, c and the state-driven check).
- No render writes `[:ui :shows :selected]`. Only a user gesture's listener call does.
- At most one edit field exists, and only `:shows/commit-edit` writes the show from it. A cell rebuild never commits (d2: 1 commit event).
- The search field never receives a text older than what it shows while focused.

## 8. Tests (named test vars and fixtures)

**Pure** (`clojure -M:test -n …`):
- NEW `laser-show.timeline.shows-test/rows-shape-test`: the two rows in §7, with no global BPM in either.
- NEW `laser-show.timeline.shows-test/sort-rows-examples-test`: `01` §3.3, plus unlocked durations sorted at a BPM argument.
- NEW `laser-show.timeline.shows-test/filter-rows-examples-test`: `01` §3.4 (the probe's `self-check` cases are its seed).
- NEW `laser-show.timeline.shows-test/query-problem-test`: `"5:"` in `:duration` and `"x"` in `:bpm` are `:unparsed`; `""` and `"daft"` in `:all` are nil.
- NEW `laser-show.events.shows-handlers-test/set-sort-maps-cleared-sort-to-ascending-test`: the §7 sort table, all rows.
- NEW `laser-show.events.shows-handlers-test/select-replaces-with-gesture-test`: `:selected #{a b}`, then `:shows/select {:show-ids [c]}` gives `#{c}`.
- NEW `laser-show.events.shows-handlers-test/from-row-uses-selection-test`: `:from-row a` with a selected gives the selected visible ids; with a not selected it gives `#{a}`.
- NEW `laser-show.events.shows-handlers-test/edit-lifecycle-test`: every row of the §7 edit table except the focus columns. It includes a stale `:shows/edit-draft` for another cell being ignored, and an unchanged draft adding no undo step (through the real pipeline: `events/flush!`, `events/core.clj:1148`).
- NEW `laser-show.subs-test/visible-show-rows-ignores-status-and-editing-test`: `visible-show-rows` is `identical?` across a `:shows/play`, a `:shows/edit-draft` and a `:timing/set-bpm` while sorted by title. Its value changes on a `:timing/set-bpm` while sorted by `:duration`.

**View, no app** (`test/laser_show/views/harness.clj:44-78` `mount!`, which records dispatches):
- NEW `laser-show.views.components.show-table-test/columns-and-ids-test`: the column ids `shows-col-*` and their `:sortable` flags.
- NEW `laser-show.views.components.show-table-test/draft-keystrokes-run-one-body-test`: with `harness/each-render` and `ran-types` (:205-208), a change to `:draft` runs the `edit-field` body only.

**UI**, NEW `laser-show.ui-test.shows-tab-test`. Fixtures are `fixtures/with-toolkit` (:once) and `fixtures/with-app` (:each). Setup is `events/write-state!` (`events/core.clj:1164`) with four shows, then `(ui/click! robot "#shows-tab")`.

| Test var | How (helpers in `test/laser_show/ui_test/testfx.clj`) |
|---|---|
| `header-click-sorts-and-reverses-test` | `ui/mouse-click!` (:382-387: `press-node!` :353 + `release-node!` :370 fire not-synthesized, non-popup events headless) on `"#shows-col-artist"` three times. After each, `ui/wait-for!` (:174) on `[:ui :shows :sort]`, then check on the FX thread (`ui/on-fx-thread` :728) that the table's sort order is `[artist]` with the expected sort type and that the item ids are in order. **Not** `ui/click!` (:221): a robot click is a popup trigger, and the header ignores it (§1.3) |
| `typing-filters-rows-test` | `ui/type!` (:572) `"daft"` into `"#shows-search"` → `ui/assert-text!` (:669) `"#shows-count"` "2 of 4 shows". Then `ui/click!` `"#shows-search-clear"` → "4 shows" |
| `search-keeps-typing-under-load-test` | slow the event thread, as probe g's 80 ms does: a `with-redefs` on `:shows/set-query`'s handler fn that sleeps 80 ms, then `ui/type!` `"daft punk one more"`. Field text, caret and state all equal it. This is the regression test for P4 |
| `scope-bpm-range-test` | `ui/select!` (:589) `"#shows-scope"` "BPM", `ui/type!` `"120-128"` → count; then `"5:"` with Duration → `ui/has-style-class?` (:113) `"#shows-search"` `"text-field-invalid"` |
| `inline-edit-commits-once-test` | `ui/click-node!` (:232) on `#shows-cell-artist-<u>` twice, 700 ms apart, to make the slow click → wait for `#shows-edit-field` → `.write` `" Live"` → `ui/press!` (:643) ENTER. Exactly one new undo label "Edit Show Info"; the row is still selected after re-sorting; the focus owner is `#shows-table` (`ui/focus!` :248 is not needed) |
| `edit-survives-row-change-test` | probe d2 through the app: start an edit with `:shows/start-edit`, write "Z", rename another show with `events/write-state!`; `#shows-edit-field` is focused and shows the draft, and Enter commits once |
| `escape-cancels-edit-test` | F2 → write → `ui/press!` ESCAPE: show unchanged, `:editing nil`, Transport not stopped |
| `keys-in-table-test` | `ui/focus!` `"#shows-table"`. Delete opens `#confirm-dialog` (`views/dialogs/confirm.clj:54`) and no cell is cleared; Enter plays (`[:playback :active-cues [:show u]]`); Space toggles Transport (S3) |
| `row-menu-acts-on-selection-test` | `ui/right-click-node!` (:240) on a row cell → click "Duplicate" → 2 new shows when 2 were selected (selection set beforehand with `ui/on-fx-thread` `.selectIndices`) |
| `delete-asks-then-removes-test` | as in `02` S3 |
| `migration-banner-dismiss-test` | state with `[:ui :shows :migrated]` → `ui/assert-visible!` `"#shows-migration-banner"` → ✕ → `ui/assert-no-node!` |

- **Multi-select gestures headless are unverified.** The probe's synthetic shift-click didn't extend the selection, and the headless robot drops held modifiers (`testfx.clj:285-310`).
- So the tests make multi-selections with the selection model on the FX thread. That covers the listener → `:shows/select` path. JavaFX's own range logic isn't the app's code.
- `ui/modifier-click-node!` (:312-328) gets one try in `row-menu-acts-on-selection-test`. If it fails, record it as a known headless gap.

**Cache:** after S3, re-run `laser-show.ui-test.cache-working-set-test/the-grid-and-both-editors-fit-in-half-the-cache-test` (`test/laser_show/ui_test/cache_working_set_test.clj:40`) with the Shows tab open on 1000 shows. The status and duration cell components add about one cache entry per visible row; the effect is *unverified*.

## 9. Out of scope / alternatives considered

- **TS-7, final.** The table is a cljfx `:table-view`.
  - JavaFX draws the sort arrows, from state, through NEW `ext-with-sort`.
  - `:sort-policy` is a Callback returning true, and `:on-sort` dispatches `:shows/set-sort`.
  - The third click is mapped to ascending by the handler and drawn back by `ext-with-sort`.
  - Our own header graphics (`:sortable false` plus a `:graphic`) **are not needed**: a, b and c pass with the ext prop.
- **TS-8, final.**
  - The search is live, through `ext-with-query-text`, never a bare `:text` binding.
  - Typing cost for 1000 shows: 1.25 ms median, 2.31 ms p95 for filter + sort on memoized rows (probe, not app; §1.4).
- **`:sort-order` with `ext-get-ref`.** Rejected: it loses the arrow on the third click (P1).
- **`commit-field` in every editable cell** (02 S3). Rejected by probe d:
  - an items change takes its focus and commits half an edit;
  - clicking into it doesn't select the row;
  - a double-click on it doesn't play.
- **JavaFX's own cell editing** (`:editable true`, `TableCell.startEdit`, `:on-edit-commit`). Not chosen: cljfx's `:describe` sees only the item, so the editing state would live in JavaFX and not the app. A cell rebuild during an edit is the same problem as P2, with nowhere to keep the draft. *Unverified.*
- **Freezing the items while editing.** Rejected: the table would show stale rows, and a commit would jump.
- **Plain `:items` with `ext-with-selection`, without `ext-with-rows`.** Probably enough, since cells are rebuilt either way, but not probed. `ext-with-rows` was in the verified pairing, so it stays until a test shows it adds nothing.
- **A handler-computed shift range with an `:anchor`** (`01` §3.5). Rejected: JavaFX already does it natively, and two anchors could disagree. Covered in §4.3.
- **⌘N for New Show inside the table.** Rejected (P7).
- **A row-level "playing" class.** Rejected (P9). The ▶ cell shows what is playing.
- **Out of scope:**
  - the TC column's rules (S4);
  - the now-playing bar and the `:shows/toggle-play` Space binding (S5);
  - column reordering and hiding (`:table-menu-button-visible`, `JAR/cljfx/fx/table_view.clj:67`, stays false);
  - saved column widths;
  - fixing the same race in `zone_group_editor.clj:72-79` and `add_projector_manual.clj:58, :72, :123` (P4). Those are dialogs with short fields; it gets its own follow-up item.
