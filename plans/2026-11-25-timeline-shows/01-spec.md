# Timeline shows: the contract

**Status:** Proposed   **Last verified against:** `63240576`

This doc defines the show document, the playback key, the table, the timecode rules, migration and the tab layout. Each section ends with data examples and invariants; they are the acceptance tests in `02-phases.md`. Paths are under `src/laser_show/`. **NEW** marks anything that doesn't exist yet.

The detail docs `03`–`07` expand each section, function by function:

| Section | Detail doc |
|---|---|
| §2 | `03` |
| §1, §6 | `04` |
| the editor | `05` |
| §3, §7 | `06` |
| §4, §5, §7's bar | `07` |

They agree with this contract. Where an investigation changed the contract, it is changed here too.

## 1. The show document

A show is a timeline cue map, the same shape `timeline/model.clj:1-22` and `:462-467` define, stored under its id:

```clojure
;; [:chains :shows]
{#uuid "6f1c2a8e-…" {:name "One More Time"                 ; Title (existing key)
                     :show/info {:artist "Daft Punk"         ; NEW, every key optional
                                 :album "Discovery"
                                 :key "F#m"
                                 :genre "House"}
                     :editor-type :timeline
                     :timeline/tempo-bpm 122.7               ; BPM column
                     :timeline/tc-start 3600.0               ; TC start column, in seconds (01:00:00:00)
                     :timeline/tc-fps 25
                     :timeline/chase :mtc                    ; TC column ticked
                     :timeline/length nil                    ; nil: ends at the last clip
                     :tracks [{:id #uuid "…" :name "Track 1" :color "#…"}]
                     :items [{:id #uuid "…" :type :preset :preset-id :circle :track-id #uuid "…"
                              :timeline/start 0.0 :timeline/duration 16.0}]
                     :loop {:enabled? false :start 0.0 :duration 4.0}}}
```

- **The show id is only the map key.** It isn't repeated inside the show (one location), and a show map never has a top-level `:id` (`04` §4.1, SH-1). History treats maps that have an `:id` as chain items (`state/history.clj:106-107`), and a show's live fields belong to its tracks. `shows/rows` puts the id on the row.
- **`:show/info` values are trimmed strings.** An empty string removes the key, so a cleared field is absent rather than `""`.
- **`normalize-timeline-cue`** (`timeline/model.clj:392-418`) applies to every show, as it does to timeline cues today. That includes shows loaded from a file, before `mark-clean` (`04` §4.2).
- **`:editor-type` is always `:timeline`.** Open's `check-shows!` requires it.

### NEW `laser-show.timeline.shows`, pure

| Fn | Params → result |
|---|---|
| `play-key` | `[id]` → `[:show id]` |
| `play-key?` | `[k]` → true when `k` is a `[:show uuid]` vector |
| `grid-cell?` | `[k]` → true for an `[int int]` vector |
| `key-show-id` | `[k]` → the id, or nil |
| `cue-path` | `[k]` → `[:chains :shows id]` for a show key, else `[:chains :cue-chains k]`. `chain-config` builds its paths from it (`05` §4.9) |
| `cue-at` | `[state k]` → `(get-in state (cue-path k))` |
| `key-order` | comparator: grid cells first, by `compare` as today; then show keys, by `(str id)`; anything else last. It never throws |
| `playable-shows` | `[state]` → `{[:show id] show}` of the shows with `:timeline/chase :mtc`, the map `show_time/chase` hands `apply-chase`. In S1 only, grid followers are merged in (`03` §4) |
| `new-show` | `[{:keys [name]}]` → an empty show (`:editor-type :timeline`, one track via `normalize-timeline-cue`) |
| `duration-s` | `[show global-bpm]` → `{:seconds s :approx? bool}` (§3.2). Called by the duration cell and by the duration filter and sort, not by `rows` |
| `tc-range` | `[show]` → `{:start s :end e :fps f}`, or nil without a locked tempo or with length 0. It ignores `:loop` |
| `tc-overlap?` | `[a b]` → half-open overlap in seconds, with a 1 µs tolerance (§4.1) |
| `timecode-problem` | `[shows id]` → nil, `{:reason :no-bpm}`, `{:reason :empty}` or `{:reason :overlap :with id2 :range {…}}` for enabling `id` against the other enabled shows |
| `rows` | `[shows]` → a vector of row maps (§3.1). No global BPM: an unlocked show's duration is worked out where it's shown |
| `filter-rows` | `[rows query scope global-bpm]` → rows (§3.4); the BPM is for unlocked shows' durations |
| `sort-rows` | `[rows {:keys [column dir]} global-bpm]` → rows (§3.3). Columns are row keys (`:duration-s`, not `:duration`). TC: ascending puts ticked rows first, descending last |
| `visible-ids` | `[state]` → the ids in table order: `(->> (rows …) (filter-rows …) (sort-rows …) (mapv :id))`, the same functions as `subs/visible-show-rows` over memoized rows (`06` §4.4). Shared by the subscription and the next/previous handlers |
| `invalid-timecode-ids` | `[shows changed-ids]` → `[[id problem] …]` for the changed, enabled shows that break TS-6 (§4.3) |
| `invariant-violations` | `[shows]` → the pairs or shows that break TS-6. For tests |

`shows/cue-at` is the only place outside `[:chains]` code that decides where a playback key's cue lives.

## 2. The playback key

A show plays under `[:show id]` in every map today keyed by cell:

```clojure
;; app state
[:playback :active-cues]   {[0 0] {:run #uuid "a…"}
                            [:show #uuid "6f1c…"] {:run #uuid "b…" :timeline? true :start-beat 0.0}}
[:playback :chase-sources] {:next 4 :cells {[:show #uuid "9d…"] 3}}
;; show-time runtime (animation/show_time.clj:64-72)
:runs   {#uuid "b…" {:cell [:show #uuid "6f1c…"] :timeline? true …}}
:chase  {[:show #uuid "9d…"] {:chase-id [doc [:show #uuid "9d…"] 3 t0 812] …}}
;; published snapshot
:positions {[0 0] 7.5, [:show #uuid "6f1c…"] 33.25}
```

### Where the key changes behaviour (S1)

| Site | Today | With show keys |
|---|---|---|
| `show_time/reconcile-runs` :178, `advance` :195, `chase-reports` :275, `active-runs` :397 | `(get chains cell)` | `(shows/cue-at state k)` |
| `show_time/chase` :243-245 | `apply-chase` over cue-chains | `apply-chase` over `(shows/playable-shows state)`, a `{[:show id] show}` map of the shows with `:timeline/chase :mtc`. In S1 it also holds grid followers, which S2 drops once no grid cell holds a timeline (`03` §4) |
| `show_time/chase-reports` :278, `active-runs` :412 | `sort` | `(sort shows/key-order …)` |
| `playback/source-version` (`timeline/playback.clj:66-68`) | `[:chains :cue-chains cell]` | `shows/cue-at` |
| `services/show_clock.clj:90` | `(get column-targets (first cell))` | the column target only for a grid cell. A show falls back to the cue's own target, then the show default (`targets/cue-fallback`, `routing/targets.clj:133-148`) |
| `services/show_clock.clj:106-115` `edited-cells` | `[col row]` from both editor dialogs | the cue chain editor's `[col row]`, plus `(shows/play-key show-id)` from the timeline editor |
| `events/handlers/timing.clj:175` | `(get-in state [:chains :cue-chains cell])` | `shows/cue-at` |
| `events/handlers/history.clj:117-127` `prune-active-cues` | gone when not a cue-chains key | gone when `(nil? (shows/cue-at state k))` |
| `events/handlers/menu.clj:394-401` Copy fallback | first active key | first active key that is a grid cell |
| `subs/cue-cells-in-grid-order` (`subs.clj:365-369`) | every active key | grid cells only; `playing-cues` is unchanged for the grid |

### Examples

| Input | Output |
|---|---|
| `(sort shows/key-order [[:show u2] [1 0] [0 1] [:show u1]])` with `(str u1) < (str u2)` | `[[0 1] [1 0] [:show u1] [:show u2]]`; plain `sort` throws `ClassCastException` |
| `active-cues {[:show u] {:run r}}`, show `u` with 8 beats, tempo 120 locked, one tick at 4 s | `active-runs` gives one entry `{:cell [:show u] :run r :cue-chain <show>}`; then `:show-clock/cue-ended {:cell [:show u] :run r …}`, and the intent leaves |
| Show `u` enabled, tc-start 3600, tempo 120, length 64; MTC `[:running 3605.55]` | chase installs `[:show u]` at beat 11.10, exactly as `T/animation/show_time_test.clj` example 1 does for a cell |
| Undo removes show `u` while it plays | `prune-active-cues` deactivates `[:show u]`; the last intent closes the transport |
| `[:show u]` renders with grid `:column-targets {0 [:zone 1]}` | `:column-target` nil; the cue's `:target` or the show default applies, never column 0's |

**Invariants**
- A grid cell key is a `[int int]` vector, a show key is `[:show uuid]`. Nothing else enters `:active-cues`.
- Every sort over playback keys uses `shows/key-order`.
- No playback logic outside `shows/cue-at` reads `[:chains :shows]`.

## 3. The table

### 3.1 Columns and row data

| Column | Row key | Source | Shown as | Edited by |
|---|---|---|---|---|
| ▶ | `:status` | `show-row-status`: `:manual`, `:timecode` or nil | ▶ playing by hand, `TC` chip playing from timecode | — |
| TC | `:timecode?` | `(= :mtc (:timeline/chase show))` | checkbox; ⚠ when `:problem` | `:shows/set-timecode` |
| Title | `:title` | `:name` | text; "Untitled" in muted text when nil | the edit field → `:shows/commit-edit` (`:shows/set-info :name`) |
| Artist | `:artist` | `[:show/info :artist]` | text | edit field (`set-info`) |
| Album | `:album` | `[:show/info :album]` | text | edit field (`set-info`) |
| BPM | `:bpm` | `:timeline/tempo-bpm` | `122.7`, or "—" when the tempo follows the global BPM | edit field (`set-bpm`); empty text unlocks |
| Key | `:key` | `[:show/info :key]` | text | edit field (`set-info`) |
| Genre | `:genre` | `[:show/info :genre]` | text | edit field (`set-info`) |
| Duration | `:length-beats`, `:duration-s`, `:approx?` | `shows/duration-s` for a locked show. An unlocked show's cell subscribes to `subs/bpm` itself | `5:20`, or `≈5:20` at the global BPM when unlocked | — |
| TC start | `:tc-start-s`, `:fps` | `:timeline/tc-start`, `tc/cue-fps` | `tc/format-tc` | edit field (`set-tc-start`) |
| TC end | `:tc-end-s` | `(:end (tc-range show))` | NEW `tc/seconds->tc-ceil`: the first frame at or after the end, so typing it as the next start never overlaps (`07` §4.3). "—" without a range | — |

**How cells are edited** (`06` §4.1–§4.2):
- **Only one field exists at a time.** It lives in the cell at `[:ui :shows :editing] {:show-id :column :draft :invalid?}`, and every other cell is a label. A slow second click on a selected row's cell starts it, and so do F2 and + New Show.
- **The draft is app state,** so the field survives the rows changing under it. It takes the focus only once its skin exists.
- **Enter commits and Tab moves to the next column.** Escape cancels.
- **Why not a field in every cell:** in the probe it lost its focus and committed half an edit on every rows change (`06` §1.3, d). The single field passed 3 of 3 runs (d2).

**Rows don't hold the global BPM.** `shows/rows [shows]` takes no BPM, so tap tempo and Link don't rebuild the table's cells. Where an unlocked show needs one, the BPM is an argument to the duration filter and sort, and a subscription for its duration cell (`06` §7).

```clojure
;; one row (shows/rows): a locked show
{:id #uuid "6f1c…" :title "One More Time" :artist "Daft Punk" :album "Discovery"
 :bpm 122.7 :key "F#m" :genre "House"
 :length-beats 655.2 :duration-s 320.39 :approx? false
 :tc-start-s 3600.0 :tc-end-s 3920.39 :fps 25
 :timecode? true :problem nil}
;; an unlocked show: no seconds in the row
{:id #uuid "9a0b…" :title nil … :bpm nil :length-beats 256.0 :duration-s nil :approx? true :tc-end-s nil …}
```

`:status` isn't on the row. It changes every time playback starts or stops, so the ▶ cells read it from `show-row-status`, a separate subscription. Starting a show then re-runs the visible ▶ cells, not the rows (`docs/CLJFX_STATE_MANAGEMENT.md` §"Reactive state in this app"). There is also no row-level "playing" style for the same reason (`06` §2, P9).

### 3.2 Duration and TC end

- Duration in seconds is `tc/beats->seconds (model/effective-length show) bpm` (`timeline/model.clj:475-478`; `timeline/timecode.clj:20`). `bpm` is `:timeline/tempo-bpm`, or the global BPM with `:approx? true`.
- TC end is `tc-start + duration`, only with a locked tempo.

| Show | global BPM | `duration-s` | `tc-range` |
|---|---|---|---|
| length 64 beats, tempo 128 locked, tc-start 0, fps 30 | any | `{:seconds 30.0 :approx? false}` | `{:start 0.0 :end 30.0 :fps 30}` |
| length 64, tempo unlocked | 120 | `{:seconds 32.0 :approx? true}` | nil |
| no clips, no `:timeline/length` | 120 | `{:seconds 0.0 :approx? false}` | nil (length 0) |
| `:timeline/length 32`, tempo 60 locked, tc-start 3600 | any | `{:seconds 32.0 …}` | `{:start 3600.0 :end 3632.0 …}` |

### 3.3 Sort

- **State:** `[:ui :shows :sort] {:column c :dir :asc|:desc}`. Initially `{:column :title :dir :asc}`. Column keys are the row keys; the column ids are `shows-col-<key>`.
- **Header clicks:** one click sorts by that column ascending, and a second click reverses it. JavaFX's own third click (no sort) is mapped back to ascending, and NEW `ext-with-sort` puts the arrow back (`06` §4.2).
- **Text columns** compare `(str/lower-case v)`.
- **Number columns** (BPM, Duration, TC start, TC end) compare numbers.
- **TC** sorts ticked rows first in ascending order. **▶** isn't sortable.
- **Empty values always go last**, whichever direction.
- **Ties** break by title ascending, then by `(str id)`, so the order is total and stable.

| Rows (title, artist, bpm) | Sort | Order |
|---|---|---|
| A ("b", "Zed", 120), B ("a", nil, 128), C ("c", "adam", nil) | `:artist :asc` | C (adam), A (Zed), B (nil) |
| same | `:artist :desc` | A, C, B: nil is still last |
| same | `:bpm :desc` | B 128, A 120, C (nil) |
| D ("x", "Same", 120), E ("w", "Same", 120) | `:artist :asc` | E, D (ties by title) |

### 3.4 Search

- **State:** `[:ui :shows :query]` (the raw text) and `:scope`, one of `:all :name :artist :album :key :genre :bpm :duration`. Scope `:name` is the Title.
- **Matching:** trim the query, lower-case it and split it on whitespace. A row matches when **every** token matches. An empty query matches everything.
- **Text scopes:** the token is a substring of that field. Under `:all` it may be a substring of any of title, artist, album, key or genre.
- **`:bpm`:**
  - `n` matches `|bpm - n| < 0.5`;
  - `a-b` matches `a <= bpm <= b`;
  - `a+` matches `bpm >= a`.
  - Rows with no BPM never match. A token that doesn't parse matches nothing, and the field shows the `text-field-invalid` style.
- **`:duration`:** the same three forms, with `m:ss` or plain seconds.

| Query | Scope | Matches |
|---|---|---|
| `""` | any | every row |
| `daft one` | `:all` | rows where "daft" and "one" each appear in some text field ("One More Time" by "Daft Punk") |
| `DAFT` | `:artist` | artist contains "daft" |
| `discovery` | `:artist` | none (it's the album) |
| `120-128` | `:bpm` | 120 ≤ bpm ≤ 128; unlocked rows never match |
| `122` | `:bpm` | 121.5 < bpm < 122.5 |
| `3:00-4:30` | `:duration` | 180 ≤ duration-s ≤ 270 |
| `5:` | `:duration` | nothing; the field is marked invalid |

### 3.5 Selection and keyboard

- **State:** `[:ui :shows :selected]` is a set of ids. There is no `:anchor`.
- **Clicks:** JavaFX's own gestures in `:selection-mode :multiple` (click, ⌘-click, Shift-click, arrows, Shift-arrows). Its listener dispatches `:shows/select {:show-ids …}`. NEW `ext-with-selection` puts the selection back by id after every rows change, and the listener stays quiet while it does (`06` §4.3). Shift and ⌘ headless are *unverified*.
- **Sorting, filtering and edits keep the selection by id.** Selected rows that are filtered out stay selected but hidden, until the next click replaces the selection. The "N selected" label counts only visible ones.
- **Row-menu events carry `:from-row id`.** The handler acts on the visible selected ids when `id` is one of them, else on `#{id}`. The row factory can't see the selection.
- **Keys while the table has focus.** The table's `KEY_PRESSED` filter consumes them; it runs only while nobody is typing. The probe found that the TableView swallows Space itself, while Enter and Delete would otherwise reach the menu shortcuts: Delete is Edit > Clear Cell (`views/components/title_bar.clj:134-137`).

  | Key | Does |
  |---|---|
  | ↑ ↓ | move the selection (JavaFX) |
  | Enter | play the first selected show (`:shows/play`) |
  | F2 | edit the Title |
  | ⌘E | open in editor |
  | Space | S3: Transport Play/Stop, as everywhere else. S5: ⏯ (`:shows/toggle-play`) |
  | Delete, Backspace | request delete |
  | ⌘F (on the tab) | focus the search field |
  | Escape | in the search field, clears it; in the edit field, cancels |

  ⌘N stays File > New. + New Show is a button.
  | ⌘F | focus the search field |
  | Escape | in the search field, clears it |

  The table claims Space with `keys/claim-keys!` (`views/keys.clj:30`) and consumes it. Outside the table, Space is Transport Play/Stop as now (`views/components/title_bar.clj:146`).

## 4. Timecode rules (TS-6)

### 4.1 Ranges and overlap

- A show's range is `[tc-start, tc-end)` in seconds (§3.2). Two ranges overlap when `a.start < b.end − ε` and `b.start < a.end − ε`, with ε = 1 µs. Touching ends, where one show's end equals the next one's start, don't overlap.
- Ranges are compared in seconds, so shows with different fps compare correctly. Today's chase plays a show inside `[0, length)` of its beats (`timeline/playback.clj:103-105`), which is the same half-open range.
- **Loops are ignored.** A chased show never loops: the chase sets its position from timecode, and only manual runs wrap (`animation/cue_timing.clj:174-190`). So a looping show's range is still `[tc-start, tc-start + length)` (`07` §1.4).
- **There is no 24 h wrap.** The MTC decoder and `tc/seconds->tc` don't wrap hours, so ranges are compared as plain seconds (`07` §7.2).

### 4.2 Enabling (`:shows/set-timecode {:show-ids #{…} :enabled? true}`)

- Each id is checked with `timecode-problem`, against the shows already enabled **and the ones before it in this request**. Ids are taken in `visible-ids` order.
- The ones that pass are enabled in one edit, labelled "Enable Timecode".
- Each refusal gets a notice: *"'X' needs a BPM to follow timecode"*, *"'X' is empty"* or *"'X' overlaps 'Y' (01:04:10:00–01:09:12:05)"*. With several refusals the notice is *"3 shows not enabled: …"*.
- The checkbox is an `applied-control`, so a refused tick unticks itself.

| Enabled before | Request | Result |
|---|---|---|
| A `[0, 300)` | enable B `[300, 600)` | B on (the ends touch) |
| A `[0, 300)` | enable B `[299, 600)` | refused: overlaps A |
| — | enable A `[0, 300)` and B `[100, 400)` | A on; B refused, overlaps A |
| — | enable C, tempo unlocked | refused: needs a BPM |
| A on | disable A | A off; never refused |

### 4.3 After any edit (middleware)

- `wrap-normalize-timelines` (`events/handlers/timeline.clj:540-568`) already sees every changed show.
- After normalizing, and before `reconcile-chase-sources`, it calls `shows/invalid-timecode-ids` for the **changed** enabled shows. A show turns invalid when it lost its BPM or length, or now overlaps another enabled show.
- Those shows get `:timeline/chase` removed **in the same state**, so the same undo step holds both the edit and the turn-off. Reconcile then drops their chase versions and markers in that event (`07` §4.2).
- It adds a notice, *"Timecode off for 'X': it now overlaps 'Y' (range)"*, through NEW `h/merge-notice`. That keeps the handler's own `:dispatch-later` and notice, because `:dispatch-later` holds one map (`07` §4.1).
- History restores (undo and redo) are skipped, as today (`restores-history?`, :477-482), because they restore a state that held the invariant. A document replacement (New, Open) is skipped too: Open's migration already enforces the invariant before `mark-clean`.

| Before | Edit | After |
|---|---|---|
| A on `[0, 300)`, B on `[300, 600)` | move A's last clip 4 beats later at 120 BPM (A becomes `[0, 302)`) | A off, B on, notice "Timecode off for 'A': it now overlaps 'B'"; one undo step "Move Clip" restores both |
| A on, tempo 120 | BPM cleared in its cell (unlock) | A off, notice "needs a BPM" |
| A on `[0, 300)`, B off `[100, 400)` | edit B | nothing changes; B isn't enabled |
| A on | edit A's Artist | no range change, nothing turned off |

**Invariant, after every event:** the enabled shows all have a locked tempo and a length above 0, and their ranges are pairwise disjoint.

## 5. Playing by hand (TS-9)

- **State:** `[:ui :shows :manual]` is the id of the show last played from the tab, or nil.
- **`:shows/play {:show-id u}`:**
  1. Refused with a notice if `u` is enabled for TC.
  2. If `:manual` is another show `m` with an intent, its intent is deactivated (`cue-timing/deactivate-cue`, `animation/cue_timing.clj:348-362`).
  3. `u` is activated from beat 0, as `handle-play` does (`events/handlers/timeline.clj:379-398`), and `:manual` becomes `u`.
- **`:shows/stop`** deactivates `:manual` and leaves `:manual` set, so ⏯ replays it.
- **When a show ends**, `:show-clock/cue-ended` removes its intent and `:manual` stays. The bar shows it stopped at 0:00.
- **Stop holds output.** Transport Stop keeps the intents and restarts every run at beat 0 (`events/handlers/timing.clj:92-101`). The bar shows HELD. Like any cue start, `:shows/play` resumes a held transport (`07` §1.5).
- **Previous and Next** take the neighbour of `:manual` in `visible-ids` and play it. If `:manual` is filtered out, they use the first or last visible row. At either end of the list they do nothing.
- **Seeking** an enabled show is refused: `:timeline/seek` gains `unless-chasing` (`07` §4.6).
- **A trigger's `:toggle-show`** goes through `:shows/toggle-run`, not this rule. A pad starts its own show and leaves `:manual` alone (`04` §4.5).

| State | Event | Result |
|---|---|---|
| `:manual nil`, grid `[0 0]` playing | `:shows/play u` | `[0 0]` and `[:show u]` playing; `:manual u` |
| `:manual u` playing | `:shows/play v` | `[:show u]` gone, `[:show v]` from beat 0; `:manual v` |
| `v` enabled | `:shows/play v` | unchanged; notice "Follows timecode. Untick TC to play it by hand." |
| visible `[a b c]`, `:manual b` | `:shows/next` | plays `c` |
| visible `[a b c]`, `:manual c` | `:shows/next` | unchanged |

## 6. Migration on Open: DROPPED

**Dropped 2026-11-25: no backward compatibility.** Kept below for the record only; nothing in this section is built.

NEW `migration/timeline-shows [state {:keys [id-fn]}]` runs in `apply-loaded-project` (`state/persistent.clj:316-348`):
- after `normalize-timeline-cues` (:342), the NEW normalize of `[:chains :shows]`, and the Z2 and Z4 migrations (:343-344);
- **before `rename-pins`** (:345), so moved shows' pins follow this rig;
- and before `mark-clean` (:347).

`id-fn` defaults to `random-uuid`; tests pass a counter. **Save runs the same pipeline as a dry run** (`events/handlers/menu.clj:220-245`), so the migration is pure and never throws (`04` §4.3, M-8).

Step by step (`04` §4.3 has the full rules and five EDN cases):

1. **Find** every cell whose cue is `timeline-cue?`, explicit or inferred. Take them in reading order, `[row col]`.
2. **Move** each one to `[:chains :shows (id-fn)]`:
   - stamp `:editor-type :timeline`;
   - drop `:id` and `:trigger-mode`;
   - copy the cell's column default target into `:target` when the cue has none;
   - default `:name` to `"Timeline " (u/cell-label col row)`. `u/cell-label` (`common/util.clj:229-233`) is pure; its letter is the **row** and its number is col + 1, so `[3 0]` is "A4".
   - A follower (`:timeline/chase :mtc`) without `:timeline/tempo-bpm` gets the file's saved `[:timing :bpm]` as its tempo. It then plays as it did at that tempo.
3. **Check the invariant.** Shows already in the library come first. Then each moved follower is checked in order; one that is empty, or overlaps an earlier one, loses `:timeline/chase`.
4. **Clean up the grid.**
   - Remove the cell.
   - `refs/remove-cue` drops vibe references to it (`vibes/refs.clj:103-114`).
   - In `[:config :input :trigger-map]`, NEW `refs/rehome-trigger-targets` turns a `:trigger-cue` at the cell into `{:type :toggle-show :target [:show id]}`, keeping `:behavior`. `:start-timeline` / `:stop-timeline` targets become `[:show id]`. Effect triggers (`:trigger-effect`) name the effects grid, so they are untouched.
5. **Record what happened** at `[:ui :shows :migrated]`: `{:moved n :timecode-off [names] :vibe-refs-removed k :triggers-rewritten t}`. This only happens when n > 0.

It is safe to run twice: the second run finds no timeline cells and returns `identical?` state.

| File | After Open |
|---|---|
| no timeline cells | identical state; no `:migrated` |
| `[2 0]` "Intro" (chase, tempo 128) and `[3 0]` unnamed (no chase); column 2's default target `:graphics` | two shows: "Intro" (TC on, `:target :graphics`) and "Timeline A4"; grid cells gone; `:migrated {:moved 2 :timecode-off [] …}`; `dirty? false` |
| `[0 1]` follower without tempo, file BPM 124 | show with `:timeline/tempo-bpm 124.0`, TC on |
| `[0 0]` follower `[0, 60)` and `[1 0]` follower `[30, 90)` | `[0 0]`'s show on; `[1 0]`'s show off; `:timecode-off ["Timeline A2"]` |
| MIDI note 36 → `{:type :trigger-cue :target [2 0] :behavior :momentary}`, `[2 0]` a timeline | note 36 → `{:type :toggle-show :target [:show id] :behavior :momentary}` |
| a vibe's `:hit :cells [[2 0] [5 5]]` (pools are vectors), `[2 0]` a timeline | `[[5 5]]`; `:vibe-refs-removed 1` |

The banner on the Shows tab reads: *"Moved 2 timeline cues from the grid into Shows. Timecode turned off for 'Timeline A2' (it overlapped). Saving writes the new layout; older versions won't see these shows."*
- It has a ✕ (`:shows/dismiss-migration`).
- The real Open, `handle-file-project-loaded` (`events/handlers/menu.clj:192-212`), also switches to the Shows tab once when anything moved. Save's dry run never does.

## 7. The tab

```
┌ Shows ────────────────────────────────────────────────────────────────────────────────┐
│ [+ New Show]   [🔍 search shows…          ] [All columns ▾]     12 shows · 2 on TC · MTC 01:02:11:04 ● │
│ ┌──┬────┬──────────────────┬────────────┬────────────┬───────┬─────┬────────┬───────┬─────────────┬─────────────┐ │
│ │▶ │ TC │ Title ▲          │ Artist     │ Album      │ BPM   │ Key │ Genre  │ Dur.  │ TC start    │ TC end      │ │
│ ├──┼────┼──────────────────┼────────────┼────────────┼───────┼─────┼────────┼───────┼─────────────┼─────────────┤ │
│ │  │ ☑  │ Around the World │ Daft Punk  │ Homework   │ 121.0 │ Am  │ House  │ 7:09  │ 01:04:10:00 │ 01:11:19:00 │ │
│ │▶ │ ☐  │ One More Time    │ Daft Punk  │ Discovery  │ 122.7 │ F#m │ House  │ 5:20  │ 01:00:00:00 │ 01:05:20:10 │ │
│ │TC│ ☑  │ Strobe           │ deadmau5   │ For Lack…  │ 128.0 │ Bm  │ Prog   │ 10:37 │ 01:12:00:00 │ 01:22:37:00 │ │
│ │  │ ☐⚠ │ Untitled         │            │            │ —     │     │        │ ≈2:08 │ 00:00:00:00 │ —           │ │
│ └──┴────┴──────────────────┴────────────┴────────────┴───────┴─────┴────────┴───────┴─────────────┴─────────────┘ │
├───────────────────────────────────────────────────────────────────────────────────────┤
│ ⏮ ⏯ ⏹ ⏭   One More Time — Daft Punk    1:12 ━━━━━●──────────── -4:08    01:01:12:05  ✎ │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

**Header:**
- **+ New Show** creates "Untitled", selects it, and starts editing its Title cell.
- The search field shows the prompt "Search shows…" and is cleared by its ✕. It shows the state's text only while unfocused (NEW `ext-with-query-text`, `06` §4.5). The scope menu sits beside it.
- A count: "N shows" or "N of M shows" while filtering, plus "K on TC" and "S selected".
- The MTC chip uses `subs/mtc-status` (`subs.clj:281-285`): `state` and `text`, at most 10 per second.

**Table:**
- Column widths are view-local.
- The edit field is red-bordered (`.text-field-invalid`, NEW in `css/forms.clj`) while its draft doesn't parse.
- The ⚠ on a TC cell has a tooltip with the reason: "needs a BPM", "empty", or "would overlap 'Y'".
- **Row menu:**
  - Play
  - Open in Editor (⌘E)
  - Enable or Disable Timecode
  - Duplicate (the copy has TC off)
  - Delete…
- An empty library shows the placeholder *"No shows yet. + New Show, or New Show from Cue in the cue editor."*
- No match shows *"No shows match 'xyz'"* with a Clear search link.

**Now-playing bar** (NEW `views/components/now_playing.clj`) uses `subs/now-playing`: `{:show-id :title :artist :source (:manual | :timecode) :state (:playing | :paused | :stopped) :held? …}`. Which show it picks, and its full shape, are in `07` §4.7.
- Elapsed, remaining and the timecode are drawn by `clock-view/ext-clock-redraw` from `show-time/cue-beats snapshot [:show id]` (`animation/show_time.clj:425-429`), the same way `redraw-readout!` does (`views/components/visual_editors/timeline_editor.clj:597-610`).
- **The seek bar** is a slider with no `:value` prop. The clock redraws its value except while it is pressed (`gesture/capture`). A release sends `:shows/seek {:beat b}`; losing focus mid-drag sends nothing (`07` §4.8).
- **While timecode drives the show**, ⏮ ⏯ ⏹ ⏭ and the seek bar are disabled, and a `TC` chip shows instead of ⏯.
- **While Stop holds the output**, a HELD chip shows.
- ✎ opens the editor.
- With nothing playing, it reads "Nothing playing" in muted text.

**Status bar:** `playing-list` (`views/status_bar.clj:90-104`) adds the show titles after the grid cues: "A1 circle · ♪ One More Time".

**Invariants for the view:**
- No view writes app state outside events.
- Positions never enter app state.
- The table's item list is exactly `visible-show-rows`.
