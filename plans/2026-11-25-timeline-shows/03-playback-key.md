# Timeline shows S1: the playback key

**Status:** Proposed   **Last verified against:** `63240576` (working tree; every file:line below was read for this doc)
**Supersedes / Superseded by:** none. It expands `02-phases.md` §S1   **Related findings:** `00` TS-4; reactivity 15 (`plans/2026-11-23-cljfx-reactivity/15-timecode-chase-lifetime.md`)

Paths are under `src/laser_show/` unless they start with `T/` (= `test/laser_show/`) or `plans/`. **NEW** marks anything that doesn't exist yet.

Notation used in the examples:

| Symbol | Means |
|---|---|
| `u`, `u1`, `u2` | show uuids, with `(str u1) < (str u2)` |
| `r…` | run ids |
| `k…` | chase episode ids |
| `D` | the document id |

**Goal:** after S1, a `[:show u]` key in `[:playback :active-cues]` behaves exactly like a `[col row]` key. It plays, ends, chases MTC, renders, is reported and is pruned, and its cue comes from `[:chains :shows u]`.
- No UI or event creates one yet; S2 does that.
- The grid behaves exactly as today.
- S1 only adds lookups and orderings. It adds no state paths, events or effects.

## 1. Current behaviour (file:line)

### 1.1 Who looks a playback key's cue up, and where

Every lookup is hard-coded to `[:chains :cue-chains key]`:

| # | Site | What it does today |
|---|---|---|
| L1 | `animation/show_time.clj:178,182` `reconcile-runs` | `(get chains cell)` → `chased?` (:147-150) decides whether the intent gets a run |
| L2 | `animation/show_time.clj:195,201-205` `advance` | `(get chains (:cell run))` → tempo (`playback/cue-bpm`), loop, timeline length |
| L3 | `animation/show_time.clj:242-245` `chase` | hands `apply-chase` the whole `[:chains :cue-chains]` map; it walks every entry (`timeline/playback.clj:98-125`) and keeps the followers |
| L4 | `animation/show_time.clj:247` `chase`'s `entry` | `playback/source-version state cell` for the new episode's `:chase-id` |
| L5 | `animation/show_time.clj:224-228` `current-episode?` | `playback/source-version state cell` |
| L6 | `animation/show_time.clj:275,280,287` `chase-reports` | `(get chains cell)` for `chases-mtc?`; `source-version` for the report |
| L7 | `animation/show_time.clj:397,400,406` `active-runs` | `(get chains cell)` for the render's `:cue-chain` and the follower check |
| L8 | `timeline/playback.clj:66-68` `source-version` | `(chases-mtc? (get-in state [:chains :cue-chains cell]))` |
| L9 | `events/handlers/timing.clj:175` cue-ended guard | `(playback/chases-mtc? (get-in state [:chains :cue-chains cell]))` |
| L10 | `events/handlers/timing.clj:244` `handle-show-clock-apply-chase` | `playback/source-version state cell` (fixed by L8) |
| L11 | `events/handlers/history.clj:117-127` `prune-active-cues` | removes every intent whose key isn't a key of `[:chains :cue-chains]` |

### 1.2 Who orders, destructures or formats a key

| # | Site | What it does today |
|---|---|---|
| O1 | `animation/show_time.clj:278` `chase-reports` | `(sort (distinct …cells))` |
| O2 | `animation/show_time.clj:412` `active-runs` | `(sort (keys chase))` for chases app state hasn't acknowledged |
| O3 | `subs.clj:365-369` `cue-cells-in-grid-order` | `(sort-by (fn [[col row]] [row col]) …)` over `active-cue-cells` (`subs.clj:359-363`, every active key) |
| O4 | `subs.clj:371-383` `playing-cues` | `(fn [[col row]] …)` → `cue-label-data col row` (`subs.clj:349-357`). The status bar formats it with `common/cell-label col row` (`views/status_bar.clj:92-102`, `views/dialogs/common.clj:15-19` → `common/util.clj:229-233`, which does `(long row)`) |
| O5 | `services/show_clock.clj:90` `render-cue` | `(get (:column-targets render-ctx) (first cell))` |
| O6 | `services/show_clock.clj:91,94` | `output-errors/ok!` / `report!` under `[:render cell]` |
| O7 | `services/show_clock.clj:106-115` `edited-cells` | `#{[col row]}` from `[:ui :dialogs :cue-chain-editor]` and `[:ui :dialogs :timeline-cue-editor]` |
| O8 | `events/handlers/menu.clj:394-401` `handle-edit-copy` | on the grid tab with no selection, takes `(first (keys active-cues))`, then `(let [[col row] …])` → `:grid/copy-cell` |

### 1.3 What a `[:show u]` key does to O1–O5 today (probe)

The investigation ran a probe with `clojure -M -i probe.clj`, with no app and no GUI. These results were measured on 2026-11-25; the probe wasn't committed, and S1's `key-order-test` repeats them.

```
sort mixed throws?              true   ; (sort [[0 1] [:show u]])
sort-by [row col] mixed throws? true   ; O3's keyfn on [[0 1] [:show u]]
edn round trip equal?           true   ; (= k (read-string (pr-str k)))
column target of show key:      nil    ; (get {0 [:zone 1]} :show)
```

- **O1 and O2 throw `ClassCastException`.** O1 throws as soon as a show key and a grid key are both chased; O2 throws when both are unacknowledged chases.
  - O1 runs inside `show-time/step` on the show-clock thread, so the whole tick throws.
  - The clock's error handling (`run-clock`, `services/show_clock.clj`) then reports `[:show-clock]` every tick, and **every cue stops rendering**.
- **O3 throws** on the FX thread's subscription as soon as a show and a grid cue play together.
  - With only show keys it doesn't throw.
  - But O4 then calls `cue-label-data :show u`, and `common/cell-label` throws in `(long :show)`.
- **O5 is already harmless.** `:column-targets` keys are integers; only `handle-grid-set-column-target` writes them (`events/handlers/grid.clj:170-179`). So `(get … :show)` is nil, and `targets/cue-fallback` (`routing/targets.clj:133-148`) falls back in order to:
  1. the cue's `:target`;
  2. then `[:config :default-target]`;
  3. then `:all`.

### 1.4 Other sites that touch a playback key (checked, unaffected)

These use the key only as a map key, or take any key:
- `cue-timing/activate-cue`, `deactivate-cue`, `request-seek` and `position-ids` (`animation/cue_timing.clj:331-379`) take any key.
- In `show_time.clj`:
  - `run-for` (:152-172) stores `:cell` as given.
  - `pending-reports`' cue-ended loop (:316-319) does `(get-in intents [(:cell run) :run])`, which is fine for a vector key.
  - `positions` (:383-388), `cue-beats` (:425-429), `chase-id` (:431-435) and `free-to-start?` (:212-217).
- `effect-show-clock-read` `:chase-cell` (`events/core.clj:472-487`).
- `handle-show-clock-chase` (`events/handlers/timing.clj:184-196`) and `install-chase` (:203-222).
- `apply-chase`'s own body (`timeline/playback.clj:98-125`).

These build maps keyed by `:cell` without sorting them:
- `compose/cue-warnings` (`routing/compose.clj:244-276`).
- `preview-data`'s `:cue-destinations` (`services/frame_service.clj:611-615`), which nothing reads.

## 2. Root cause

The playback core treats "a playback key" and "a grid cell" as one type:
- **Eleven sites (L1–L11) resolve the key against one fixed path.**
- **Three sites (O1–O3) order keys with Clojure's default comparison.** That only works while every key is `[int int]`: `compare` on vectors compares element by element, and `(compare :show 0)` throws.
- **Nothing owns the key.** No single place says where a key's cue lives or how two keys compare.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `playback/apply-chase` (`timeline/playback.clj:88-125`) | Unchanged. It already reduces over any `{key cue}` map and writes `(:active-cues chase)` under the same key |
| `playback/chases-mtc?` (`timeline/playback.clj:55-58`), `cue-chains/timeline-cue?` (`animation/cue_chains.clj:60-76`) | `playable-shows` filters with the same test. `shows` requires `cue-chains`, not `playback`, to avoid a require cycle (§4) |
| `playback/source-version` default `0` (`timeline/playback.clj:68`) | A show follower that no edit has versioned is version 0, as a fresh grid follower is. S1 adds no versioning for shows |
| `cue-timing/*` (`animation/cue_timing.clj:331-379`) | Tests write show intents with `activate-cue [:show u]` |
| Test helpers in `T/animation/show_time_test.clj`: `init!` :32, `run-at` :50, `follower` :489, `qf-decoder` :495, `mtc-at` :501, `on-report` :520, `deliver!` :531, `drawn` :545 | Every NEW show-time test reuses them. Only `init!` gains a shows argument |
| `T/services/show_clock_test.clj`: `cue` :51, `one-clip-timeline` :55, `init!` :62, `ticks!` :80; the shape of `column-default-reaches-its-cues-test` :469 | The service test for O5 |
| `T/events/history_handlers_test.clj`: `test-state` :16, `run` :35, `edit-size` :46; `undo-prunes-active-cues-for-removed-cells-test` :252 | The undo tests |

**NEW namespace `laser-show.timeline.shows`** (`timeline/shows.clj`), justified in `00` §3. In S1 it holds only the key functions below. Nothing in the code today owns "where a key's cue lives".

## 4. Changes per namespace

### `laser-show.timeline.shows` (NEW, pure)

It requires only `laser-show.animation.cue-chains`. `timeline.playback` requires `shows` (for `cue-at`), so `shows` must not require `playback`.

**`play-key [id]`** → `[:show id]`. This is the only way code builds a show key.

| Input | Output |
|---|---|
| `u` | `[:show u]` |
| `nil` | `[:show nil]`. Not a valid key (`play-key?` is false); callers pass ids they read from `[:chains :shows]` |

**`play-key? [k]`** → true when `k` is a vector of exactly `:show` and a `java.util.UUID`.

| Input | Output |
|---|---|
| `[:show u]` | `true` |
| `[0 1]` | `false` |
| `[:show "6f1c…"]` (a string) | `false` |
| `[:show u :extra]`, `'(:show u)`, `nil`, `:show` | `false` |

**`grid-cell? [k]`** → true for a vector of two integers. This is the one test for "a grid key", shared by `menu.clj:397`, `subs.clj:369` and `show_clock.clj:90`.

**`key-show-id [k]`** → the uuid of a show key, else nil: `[:show u]` → `u`; `[3 2]` → nil; `nil` → nil.

**`cue-at [state k]`** → the cue map a playback key plays. A show key reads `(get-in state [:chains :shows id])`. Any other key reads `(get-in state [:chains :cue-chains k])`, which is today's lookup.

| `state` | `k` | Output |
|---|---|---|
| `{:chains {:cue-chains {[0 0] c}}}` | `[0 0]` | `c` |
| `{:chains {:shows {u s}}}` | `[:show u]` | `s` |
| `{:chains {:shows {}}}` | `[:show u]` | `nil` (deleted show) |
| `{:chains {:cue-chains {[0 0] c}}}` | `nil` | `nil` |
| `{:chains {:cue-chains {[:show u] c}}}` | `[:show u]` | `nil`: a show key never reads cue-chains |

**`key-order`**: a comparator fn `[a b]` that returns a negative number, zero or a positive number.
- **Grid keys come first**, in today's `compare` order.
- **Show keys come next**, ordered by `(compare (str id-a) (str id-b))`.
- **Anything else (nil, malformed) goes last**, ordered by `(compare (pr-str a) (pr-str b))`.

So it is total and never throws, whatever the key. Ordering by the uuid string is arbitrary, but it is stable across runs, ticks and EDN round trips, which is what report order and `active-runs` need.

| Input to `(sort key-order …)` | Output |
|---|---|
| `[[:show u2] [1 0] [0 1] [:show u1]]` | `[[0 1] [1 0] [:show u1] [:show u2]]` |
| `[[1 0] [0 0]]` | `[[0 0] [1 0]]`, the same as `(sort …)` today |
| `[[:show u1] [:show u1]]` | `[[:show u1] [:show u1]]` (0: equal) |
| `[nil [:show u1] [0 0]]` | `[[0 0] [:show u1] nil]` |
| `[[:show u2] [:show u1]]` | `[[:show u1] [:show u2]]` |

Grid keys keep `compare`'s order, column first (`[0 1]` before `[1 0]`). That is today's O1 and O2 order, not the grid's reading order; O3 keeps its own `[row col]` order for grid cells.

**`playable-shows [state]`** → `{[:show id] show}` for every show in `[:chains :shows]` whose cue follows MTC. The test is `(and (cue-chains/timeline-cue? show) (= :mtc (:timeline/chase show)))`, the same as `playback/chases-mtc?`. It returns `{}` when there are none, or when there is no `[:chains :shows]` yet (in S1, before S2 declares the path).

| `[:chains :shows]` | Output |
|---|---|
| absent | `{}` |
| `{u1 {:editor-type :timeline :timeline/chase :mtc …}}` | `{[:show u1] <that show>}` |
| `{u1 <follower>, u2 <not following>}` | `{[:show u1] <u1>}` |
| `{u1 {:timeline/chase :mtc :editor-type :cue-chain}}` | `{}`: not a timeline |

It doesn't check TS-6 (BPM, length, overlap). S4's handlers keep that invariant in state, and the clock just follows state.

**Invariants**
- Outside the `[:chains]` editing code, `cue-at` is the only function that reads `[:chains :shows]` for playback.
- `(play-key? (play-key u))` holds for every uuid `u`.
- `key-order` is total and consistent with `=`.

**Name clash.** `routing.compose/key-order` (`routing/compose.clj:39`) already exists, and it is a *keyfn* over frame keys. `shows/key-order` is a *comparator*. Always require the new one as `[laser-show.timeline.shows :as shows]`.

### `laser-show.timeline.playback` (existing)

- `source-version` (:60-68) reads `(shows/cue-at state cell)` instead of `[:chains :cue-chains cell]`. Its version read, `[:playback :chase-sources :cells cell]`, is unchanged, so a show key gets its version from the same map. It is `0` until S2 versions shows.
- Docstrings only, no logic change:
  - `intent-owner` (:70-80): "a cell" becomes "a key".
  - `apply-chase` (:88-96): "every chasing timeline in cue-chains" becomes "every chasing timeline in cues, a `{key cue}` map".
  - Its parameter is renamed from `cue-chains` to `cues`.
- Adds the require of `laser-show.timeline.shows`.

### `laser-show.animation.show-time` (existing)

| Site | Change |
|---|---|
| `reconcile-runs` :178, :182 | Drop the `chains` local; use `(chased? intent (shows/cue-at state cell))`. |
| `advance` :195, :201 | Use `(shows/cue-at state (:cell run))`. There is one run per intent, so the per-run `get-in` costs nothing. |
| `chase` :243-245 | **S1 only, a transitional rule:** give `apply-chase` the map `(merge (get-in state [:chains :cue-chains]) (shows/playable-shows state))`. Grid keys and show keys can't collide, so the merge only adds keys; with no followers it returns the cue-chains map unchanged. **In S2**, once no grid cell holds a timeline, replace it with `(shows/playable-shows state)` alone. That also stops the clock scanning every grid cell each tick. |
| `chase` :247 | Unchanged text. `source-version` now resolves show keys (L8). |
| `current-episode?` :219-228 | Unchanged. `(= at cell)` compares `[:show u]` by value. |
| `chase-reports` :275, :280 | Use `(playback/chases-mtc? (shows/cue-at state cell))`; drop the `chains` local. |
| `chase-reports` :278 | Use `(sort shows/key-order (distinct …))`. |
| `active-runs` :397, :400, :406 | Use `(shows/cue-at state cell)` in `entry` and in the follower check. |
| `active-runs` :412 | Use `(sort shows/key-order (keys chase))`. |
| ns docstring :12-35; `!runtime` doc :64-70 | Replace "cell" with "key (a grid cell `[col row]` or a show `[:show id]`, timeline.shows)". |

### `laser-show.services.show-clock` (existing)

- **`render-cue` :90.** Use `:column-target (when (shows/grid-cell? cell) (get (:column-targets render-ctx) (first cell)))`.
  - This isn't a bug fix: §1.3 shows the lookup is already nil today.
  - It states that a show has no column, so a later change to how columns are keyed can't route a show through one.
- **`edited-cells` :106-115.**
  - The `:timeline-cue-editor` dialog yields `(shows/play-key show-id)` when its data has `:show-id`, and `[col row]` as now otherwise.
  - S1 has no dialog with `:show-id`. S2 removes the `[col row]` branch for that dialog.
- **`[:render cell]` keys (:91, :94)** are unchanged. A show logs as `[:render [:show #uuid "…"]]`.

### `laser-show.events.handlers.timing` (existing)

- **`handle-show-clock-cue-ended` :175.** Use `(playback/chases-mtc? (shows/cue-at state cell))`.
  - Today a show key reads nil here.
  - Take a show that started following timecode while its manual run was ending. The end report would stop it, instead of leaving it to the chase as the docstring's rule says (:166-167).
- **`chase-report-keys` :180-182, `install-chase` :203-222 and `handle-show-clock-apply-chase` :224-267** need no change. Their docstrings change "cell" to "key".

### `laser-show.events.handlers.history` (existing)

- **`prune-active-cues` :117-127.** A key is gone when `(nil? (shows/cue-at state k))`.
  - Today **every** undo or redo removes every show intent, because no show key is a cue-chains key.
  - With the change, grid keys behave exactly as now.

### `laser-show.events.handlers.menu` (existing)

- **`handle-edit-copy` :397.** Use `(first (filter shows/grid-cell? (keys (get-in state [:playback :active-cues]))))`.
  - Among grid keys it picks the same one as today.
  - Today, if a show key came first, Copy would send `:grid/copy-cell {:col :show :row u}` and silently copy nothing.

### `laser-show.subs` (existing)

- **`cue-cells-in-grid-order` :365-369.** Use `(->> active-cue-cells (filter shows/grid-cell?) (sort-by (fn [[col row]] [row col])) vec)`.
  - `active-cue-cells` (:359-363) stays the full key set, so the sort still re-runs only when membership changes (`T/subs_test.clj:1535`).
  - Shows join the status bar in S5 (`01` §7).
- **No change in S1** (see the §5 table):
  - `held-cue-count` (:332-341)
  - `output-held?` (:343-347)
  - `lit-cue-count` (:1955-1958)
  - `cue-cell-active?` (:292-300)
  - `timeline-transport` (:271-279)
  - `vibe-playing-cell` (:2018-2024)
  - `cue-warning` (:577-590)

### `laser-show.state.domains`, `laser-show.state.extractors` (existing, docs only)

- **`playback`**: the docstring (:57-65) and `:active-cues` :doc (:74-84) become "Map of key → intent: a grid cell `[col row]` or a show `[:show id]` (timeline.shows/play-key)".
- **`:chase-sources`** :doc (:85-86) becomes `{:next n :cells {key version}}`.
- **`ex/active-cues`** docstring (`state/extractors.clj:117-121`) becomes `{key intent}`.

## 5. Site classification

| Site (file:line) | With a `[:show u]` key today | Class | Change |
|---|---|---|---|
| `show_time.clj:178,182` reconcile-runs | misbehaves: a show follower holding a run isn't seen as chased, so it keeps a run beside the chase | must change | `cue-at` |
| `show_time.clj:195,201` advance | misbehaves: nil chain → global BPM, no loop, no timeline length, so a show never ends | must change | `cue-at` |
| `show_time.clj:243-245` chase | misbehaves: show followers never chase | must change | merged map (S1), shows only (S2) |
| `show_time.clj:224-228,247` current-episode?, entry | fine once L8 changes | fine | — |
| `show_time.clj:275,280` chase-reports lookup | misbehaves: no Off for a manual run under a follower | must change | `cue-at` |
| `show_time.clj:278` sort | **throws** on mixed keys (§1.3) | must change | `key-order` |
| `show_time.clj:316-319` cue-ended reports | fine: `get-in` with a vector key | fine | — |
| `show_time.clj:383-388` positions, `:425-429` cue-beats, `:431-435` chase-id | fine: map keys | fine | — |
| `show_time.clj:397,400,406` active-runs lookup | misbehaves: nil chain → no `:items` → never rendered | must change | `cue-at` |
| `show_time.clj:412` sort | **throws** on mixed unacknowledged chases | must change | `key-order` |
| `timeline/playback.clj:66-68` source-version | misbehaves: always nil, so every show episode fails `current-episode?` and every report's version guard | must change | `cue-at` |
| `timeline/playback.clj:98-125` apply-chase | fine: walks any map | fine | docstring |
| `services/show_clock.clj:90` column target | fine (nil, §1.3) | fine; made explicit | `grid-cell?` |
| `services/show_clock.clj:91,94` `[:render cell]` | fine: any key | fine | — |
| `services/show_clock.clj:106-115` edited-cells | fine in S1 (no show editor yet) | must change for S2's dialog | read `:show-id` |
| `routing/compose.clj:244-276` cue-warnings | fine: `{key warning}`, no sort over keys | fine | — |
| `subs.clj:577-590` cue-warning `[col row]` | fine: no show reader in S1 | grid-only in S1. S2 adds a show-key reader for the timeline dialog's load warning (`views/dialogs/timeline_cue_editor.clj:82`) | — |
| `services/frame_service.clj:611-615` :cue-destinations | fine: no reader | fine | — |
| `events/handlers/timing.clj:175` cue-ended guard | misbehaves (§4) | must change | `cue-at` |
| `events/handlers/timing.clj:184-267` chase, apply-chase, install-chase | fine once L8 changes; `:active-cell` gets `[:show u]` | fine | docstrings |
| `events/core.clj:472-487` `:show-clock/read :chase-cell` | fine | fine | — |
| `events/handlers/history.clj:117-127` prune-active-cues | misbehaves: every undo stops every show | must change | `cue-at` |
| `events/handlers/menu.clj:394-401` Copy fallback | misbehaves: copies nothing when a show key comes first | must change | grid keys only |
| `animation/cue_timing.clj:331-379` | fine: any key. `deactivate-cue` may move `:active-cell` to a show key (:362) | fine | — |
| `:active-cell` readers | only `events/handlers/grid.clj:127-128` (a grid move compares it with `[from-col from-row]`) | fine | — |
| `subs.clj:332-347` held-cue-count, output-held? | fine: counts every intent. A held show is a held cue, so the toolbar's "N cues held" stays true | fine, intended | — |
| `subs.clj:1955-1958` lit-cue-count | fine for the count (a show lights the room); the banner text says "grid cue(s)" | fine in S1; S2 rewords it | — |
| `subs.clj:292-300` cue-cell-active?, `views/components/grid_cell.clj:295-306` held state | fine: asks about its own `[col row]` | grid-only | — |
| `subs.clj:271-279` timeline-transport | fine: its own `[col row]` (re-keyed in S2) | grid-only in S1 | — |
| `subs.clj:359-363` active-cue-cells | fine: a set | fine | — |
| `subs.clj:365-369` cue-cells-in-grid-order | **throws** on mixed keys; with shows alone, `cell-label` throws | grid-only (filter) | `grid-cell?` |
| `subs.clj:371-383` playing-cues, `views/status_bar.clj:92-112` | fine after O3's filter | grid-only until S5 | — |
| `subs.clj:2018-2024` vibe-playing-cell; `vibes/engine.clj:55, 219, 354, 373` | fine: only the voice's own cell, always a grid cell (TS-12: vibes never play shows) | grid-only | — |
| `vibes/library.clj:113-117` cue-refs `[col]` | fine: vibe pool cells only | grid-only | — |
| `events/handlers/grid.clj:35-189` | fine: every handler builds `[col row]` from its event | grid-only | — |
| `events/handlers/timeline.clj:356-428, 484-538` transport, reconcile-chase-sources | fine: `[col row]` from the event, cue-chains only. S2 re-keys them | grid-only in S1 | — |

## 6. State placement

S1 adds **no** state path. Show keys go into existing session maps:

| Key / path | Lives in | Written by | Undo/dirty? |
|---|---|---|---|
| `[:playback :active-cues [:show u]]` | app state, session | in S1 only tests (`cue-timing/activate-cue`) and `:show-clock/apply-chase` (`events/handlers/timing.clj:224-267`) | no / no (`state/history.clj:65-87` excludes `:playback`) |
| `[:playback :chase-sources :cells [:show u]]` | app state, session | nothing in S1; S2 extends `reconcile-chase-sources` | no / no |
| `[:playback :active-cell]` may be `[:show u]` | app state, session | `activate-cue`, `install-chase` | no / no |
| `!runtime` `:runs {r {:cell [:show u]}}`, `:chase {[:show u] …}`; snapshot `:positions`, `:cues [{:cell [:show u]}]` | runtime atom, show-clock thread | `show-time/step`, `publish!` | n/a |
| `[:backend :streaming :cue-warnings [:show u]]` | app state, via `:show-clock/status` | `handle-show-clock-status` (`events/handlers/timing.clj:287-293`) | no / no |
| `[:chains :shows u]` | document (declared in S2) | S1 tests only | S2 |

## 7. Events and effects

S1 adds no events or effects.

| Event / effect | Exists? | Dispatched from (file:line) | Pure? | S1 change |
|---|---|---|---|---|
| `:show-clock/cue-ended` | yes, `events/handlers/timing.clj:160-178` | `show_time.clj:316-319`, via the show clock's dispatch | yes | guard uses `cue-at` |
| `:show-clock/chase` | yes, :184-196 | `show_time.clj:262-295` | yes | none |
| `:show-clock/read` effect (`:chase-cell`) | yes, `events/core.clj:472-487` | `events/handlers/timing.clj:194-195` | effect, inline | none |
| `:show-clock/apply-chase` | yes, :224-267 | `:show-clock/read`'s `:on-read` | yes | none (L8 fixes its version) |
| `:edit/undo`, `:edit/redo` | yes, `events/handlers/history.clj` | Edit menu (`events/handlers/menu.clj`) | yes | `prune-active-cues` |
| `:edit/copy` → `:grid/copy-cell` | yes, `events/handlers/menu.clj:389-409` | Edit menu | yes | grid keys only |

## 8. Data examples, end to end (reactivity 15 with show keys)

**Fixture.** Show `u` is the test file's `follower` (`T/animation/show_time_test.clj:489`): tc-start 3600, tempo 120 locked, length 64. `C = [2 0]` is a grid follower.

**E1. A manual show runs and ends** (`01` §2 row 2).
```clojure
;; app state
{:chains {:shows {u (timeline {:timeline/tempo-bpm 120.0 :timeline/length 8.0})}}
 :playback {:active-cues {[:show u] {:run r :timeline? true :start-beat 0.0}} :playing? true}}
;; step at 0 ms, then 4000 ms (8 beats at 120)
:runs {r {:cell [:show u] :timeline? true :accumulated-beats 8.0 :ended? true …}}
:reports [{:event/type :show-clock/cue-ended :cell [:show u] :run r
           :position-ids {:restart nil :seek nil}}]
;; after the report: [:playback :active-cues] {}, :playing? false
```

**E2. A show chases MTC: On** (as `T/animation/show_time_test.clj:550`, with `[:show u]` in place of `C`).
```clojure
;; step at 70 ms (mtc-at 70)   ; 3605.55 s
:chase {[:show u] {:accumulated-beats 11.10 :chasing? true :timeline? true
                   :chase-id [D [:show u] 0 t0 2]          ; version 0: no edit versioned it
                   :start {:restart nil :allowed? true}}}
:reports [{:event/type :show-clock/chase :cell [:show u] :active? true :document-id D
           :source-version 0 :expected-owner :none
           :chase-id [D [:show u] 0 t0 2] :start {:restart nil :allowed? true}}]
;; :show-clock/chase → :show-clock/read {:chase-cell [:show u]} → :clock {:chase-id [D [:show u] 0 t0 2]}
;; → :show-clock/apply-chase installs:
[:playback :active-cues [:show u]] {:chasing? true :timeline? true :chase-id [D [:show u] 0 t0 2]}
[:playback :active-cell] [:show u], :playing? true
```

**E3. Timecode stops: Off.** The step at 670 ms (stopped) leaves `:chase {}` and reports:
```clojure
[{:event/type :show-clock/chase :cell [:show u] :active? false :document-id D
  :source-version 0 :expected-owner [:chase [D [:show u] 0 t0 2]]}]
```
Once delivered, the intent goes, `:playing?` becomes false and `:active-cell` becomes nil.

**E4. Mixed keys in one tick.** `C`, `u2` and `u1` all follow, timecode is at 3605.55 s, and there are no markers yet:
```clojure
(map :cell (:reports …))           ;=> ([2 0] [:show u1] [:show u2])     ; key-order, no throw
(map :cell (active-runs state rt)) ;=> ([2 0] [:show u1] [:show u2])     ; chased-later in key-order
```
After delivering only `[:show u2]`'s On, `active-runs` gives `[[:show u2]]` from the intents, followed by `[[2 0] [:show u1]]` from chased-later.

**E5. A show removed while its episode runs.** `[:chains :shows]` loses `u` (an undo, say).
- `source-version` becomes nil.
- On the next tick the seed drops `[:show u]` (`current-episode?`).
- `chase-reports` emits Off with `:source-version nil :expected-owner [:chase k]`.
- `apply-chase` accepts it, because a chase marker goes whether or not its cue is still there (`events/handlers/timing.clj:224-267`).
- If `prune-active-cues` already removed the marker in the undo, no report is sent.

**E6. The report retry key.** `due-reports` (`show_time.clj:322-335`) keys `:sent` by the whole report map. `[:show u]` holds a `java.util.UUID`, which has value equality and hashing. So a retried report equals the previous one, and the 250 ms limit holds.

**Invariants (S1)**
- Every key in `:active-cues`, `:chase-sources :cells`, `!runtime :chase` and `:positions` is `grid-cell?` or `play-key?`.
- No sort over playback keys throws, for any mix of keys.
- Grid-only state behaves exactly as today, and every existing test passes unchanged.
- A show key never reads `[:chains :cue-chains]`, and a grid key never reads `[:chains :shows]`.
- A show's render never uses a column target.

## 9. Per-tick cost (show-clock thread, 60 Hz, 16.7 ms budget)

**What `playable-shows` does.** It runs once per tick inside `chase`:
- one `reduce-kv` over every show;
- the `timeline-cue?` and `:timeline/chase` checks on each;
- one `assoc` and one new `[:show id]` vector per follower.

**Measured by the investigation probe** (not committed). The JIT was warmed by 20 000 calls, times are the mean of 20 000 calls, and each show has 20 clips:

| Shows | Followers | µs per call |
|---|---|---|
| 50 | 5 | 1.58 |
| 500 | 10 | 13.26 |
| 1000 | 20 | 37.08 |

- **At 1000 shows that is 0.2 % of a tick.**
- **The S1 merge with cue-chains adds nothing when there are no show followers.** Otherwise it adds one `assoc` per follower. Today's `apply-chase` already walks every grid cell each tick.

**Decision: no cache in S1.** If S3's 1000-show REPL check shows a higher cost in the running app, S3 adds a one-slot cache in `!runtime` that holds the shows map it last saw (compared with `identical?`) and its result, `{:shows-in … :playable …}`. That keeps `step` pure.

`cue-at` per run or intent is one `get-in`, the same cost as today's `get`. Re-hashing the fresh show-key vectors each tick is negligible (*unverified*, not measured separately).

## 10. Tests (named test vars + fixtures)

Run with `clojure -M:test -n <ns>`.

### Existing, must stay green unchanged

| Namespace | Vars (line) |
|---|---|
| `laser-show.animation.show-time-test` | all; especially `chase-activates-and-reports-test` :550, `chasing-timeline-follows-mtc-test` :630, `chase-takes-over-a-playing-cue-test` :669, `active-runs-keep-the-intents-order-test` :704, `follow-off-clear-and-replace-prune-chase-test` :786, `stale-chase-off-cannot-retire-a-restart-test` :905, `chase-reports-respect-explicit-stop-test` :968 |
| `laser-show.timeline.playback-test` (`T/timeline/playback_test.clj`) | `apply-chase-examples-test` :56, `apply-chase-ignores-other-cues-test` :77, `intent-owner-and-source-version-test` :84 |
| `laser-show.events.timing-handlers-test` (`T/events/timing_handlers_test.clj`) | `end-requires-a-current-run-and-position-test` :145, `chase-transition-requires-show-version-owner-and-clock-test` :209 |
| `laser-show.events.history-handlers-test` | `undo-prunes-active-cues-for-removed-cells-test` :252 |
| `laser-show.subs-test` | `blackout-banner-is-honest-test` :533, `output-held-sub-test` :848, `playing-cues-sub-test` :879, `playing-cues-sorts-only-on-membership-change-test` :1535 |
| `laser-show.services.show-clock-test` | `reports-reach-app-state-test` :148, `column-default-reaches-its-cues-test` :469, `failing-cue-renders-empty-test` :501, `edited-cue-names-its-heaviest-item-test` :649, `new-show-rejects-old-chase-reports-test` :1136 |

### Fixture change

`init!` in both `T/animation/show_time_test.clj` (:32) and `T/services/show_clock_test.clj` (:62) gains an arity `[chains shows]`. The new arity also does `(assoc-in [:chains :shows] shows)`.

### NEW `laser-show.timeline.shows-test` (`T/timeline/shows_test.clj`, pure, no fixtures)

- **`play-key-test`**: the `play-key`, `play-key?`, `grid-cell?` and `key-show-id` tables in §4, plus `(= (play-key u) (read-string (pr-str (play-key u))))`.
- **`cue-at-test`**: the five `cue-at` rows.
- **`key-order-test`**:
  - the five `key-order` rows;
  - `(sort [[:show u1] [0 1]])` throws `ClassCastException`, which is why the comparator exists;
  - totality: 200 shuffles of a mixed vector all sort to the same result.
- **`playable-shows-test`**: the four `playable-shows` rows.

### NEW in `laser-show.animation.show-time-test` (unmounted step)

**`show-key-runs-and-ends-test`** (E1)
- Setup: `(init! {} {u (timeline {:timeline/tempo-bpm 120.0 :timeline/length 8.0})})`. Write the intent with `(state/swap-state! update :playback cue-timing/activate-cue [:show u] r {:timeline? true :start-beat 0.0})`. Step at 0 ms and 4000 ms (`run-at`).
- Assert:
  - the run's `:cell` is `[:show u]`;
  - the run has ended at 8.0;
  - the report is the cue-ended map above.
- After delivering the reports, the transport is `[false {} nil]`.
- Second case: with `:timeline/tempo-bpm 60.0` and a global BPM of 120, the beats after 1 s are 1.0. This proves `advance` read the show's tempo.

**`show-key-chases-mtc-test`** (E2, E3)
- Setup: `(init! {} {u follower})`, using `qf-decoder` and `mtc-at`.
- At 70 ms, assert:
  - the chase beat is close to 11.10;
  - `(show-time/chase-id rt1 [:show u])` starts `[doc-id [:show u] 0 …]`;
  - the reports are `[(on-report [:show u] 0 :none k1 {:restart nil :allowed? true})]`.
- After `deliver!`, the intent is the marker, and the transport is `[true {[:show u] nil} [:show u]]`.
- Stopped at 670 ms, the reports are `[(off-report [:show u] 0 [:chase k1])]`. Once delivered, the transport is `[false {} nil]`.

**`show-follower-takes-over-its-manual-run-test`**
- Like the first case of `chase-takes-over-a-playing-cue-test` (:669): the show is written as a follower while `activate-cue` holds run `r1`.
- A step with timecode stopped leaves `(:runs rt')` empty, and the reports are `[(off-report [:show u] 0 [:run r1])]`.
- This proves L1 and L6.

**`mixed-keys-report-in-key-order-test`** (E4)
- Setup: `(init! {C follower} {u1 follower u2 follower})`, step at 70 ms.
- Assert:
  - `(map :cell reports)` is `[C [:show u1] [:show u2]]`;
  - `active-runs` gives the same order;
  - after delivering only `u2`'s On, `active-runs` is `[[:show u2] C [:show u1]]`;
  - `(drawn rt)` gives each key 1.

**`removed-show-retires-its-chase-test`** (E5)
- After E2's install, run `(state/swap-state! update-in [:chains :shows] dissoc u)`.
- The next step has `(:chase rt)` `{}` and exactly one Off, with `:source-version nil`.
- Once delivered, there is no intent.

### NEW elsewhere

**`laser-show.timeline.playback-test/source-version-reads-show-keys-test`.** State `{:chains {:shows {u chasing-cue}} :playback {:chase-sources {:cells {[:show u] 5}}}}`, where `chasing-cue` is the def at `T/timeline/playback_test.clj:47`. Then `(source-version state [:show u])`:

| Case | Result |
|---|---|
| as above | 5 |
| without the `:cells` entry | 0 |
| with `u` not following | nil |
| `[:show u2]` absent | nil |

**`laser-show.events.timing-handlers-test/show-key-end-and-chase-guards-test`**
- The cue-ended handler with `:cell [:show u]`, an intent naming run `r` and matching position ids, deactivates the intent.
- The same report while show `u` now follows timecode leaves state unchanged (L9).
- `:show-clock/apply-chase` On for `[:show u]` with `:clock {:chase-id k}` installs the marker. The state shape is the one at :209, with `[:chains :shows u]` as the cue.

**`laser-show.events.history-handlers-test/undo-removing-show-stops-it-test`** (`01` §2 row 4)
- Start from `(test-state)` with `[0 0]` playing, then `(run s0 {:event/type :history/record-snapshot :description "Add Show"})` (routed at `events/handlers/history.clj:536`).
- Assoc `[:chains :shows u]`, `activate-cue [:show u]`, then `(run s1 {:event/type :edit/undo})`.
- Assert:
  - `[:chains :shows]` has no `u`;
  - the active keys are `#{[0 0]}`;
  - `:playing?` is still true.
- A second case, with only `[:show u]` playing, ends with `:playing? false`.

**`laser-show.events.history-handlers-test/undo-keeps-a-show-that-stays-test`**
- An undo of an unrelated grid edit (`edit-size`, :46) leaves `[:playback :active-cues [:show u]]` unchanged.
- Today's code fails this: it removes every show intent.

**`laser-show.services.show-clock-test/show-key-ignores-column-target-test`** (`01` §2 row 5)
- Setup: `(init! {[0 0] (cue :circle)} {u one-clip-timeline})`, `[:grid :column-targets] {0 :zone/a}` and `[:config :default-target] :graphics`. Start `[0 0]` and `activate-cue [:show u]`, then `(ticks! 2)`.
- The destinations are `{[0 0] [:zone/a] [:show u] [:graphics]}`, the shape asserted at :469.
- `(output-errors/snapshot)` has no `[:render [:show u]]` entry.

**`laser-show.services.show-clock-test/show-chase-ticks-with-fifty-shows-test`**
- Setup: 50 shows, 5 of them following with disjoint timecode ranges. `mtc/current` is rebound to return a running position inside show 3.
- One `tick!` publishes `:positions` with exactly one `[:show …]` key.
- `(output-errors/snapshot)` has no `[:show-clock]` entry.
- §9 holds the timing; this test checks the behaviour.

**`laser-show.subs-test/playing-cues-skips-show-keys-test`**
- Use `playing-state` (:835) plus `[:playback :active-cues [:show u]]`.
- `playing-cues` still returns the three grid cues in grid order, and doesn't throw.
- With only a show key, it returns `[]`.
- `held-cue-count` after `:transport/stop` counts 4.

**`laser-show.events.menu-handlers-test/copy-fallback-skips-show-keys-test`** (in `T/events/menu_handlers_test.clj`)
- Grid tab, no selection, and `active-cues` as an array-map `{[:show u] … [1 0] …}`, with `[1 0]` holding a cue.
- The resulting clipboard holds `[1 0]`'s cue.

**Done when** these pass, then the full unit suite, then `laser-show.ui-test.grid-test` unchanged (`targeted-ui-testing` skill).

## 11. Risks and edge cases

- **Nil or malformed keys.**
  - `play-key?` is strict, and `key-order` puts anything else last without throwing.
  - `cue-at` of nil is nil, so `prune-active-cues` removes a nil key on the next undo, as it would today.
- **UUID equality.** `java.util.UUID` equality and hashing are by value, and EDN `#uuid` reads back equal (§1.3).
  - Playback keys are never saved.
  - S2 saves `[:show id]` trigger targets in `[:config]`, and the same property holds there.
- **A string id.** In a hand-edited file, a string show key would make `(play-key "…")` fail `play-key?`. S2's `check-shows!` rejects non-uuid keys on Open, so this can't reach playback.
- **Sort stability.** `sort` with a comparator is stable, and `key-order` is total.
- **Version 0 for every show in S1.** No edit versions a show until S2 extends `reconcile-chase-sources` (`events/handlers/timeline.clj:506-538`) and its new-document branch (:522-525) to `[:chains :shows]`.
  - In S1, replacing a show at the same id keeps version 0. So plan 15's "replace → fresh episode" rule doesn't hold for shows until S2.
  - S1's tests must not assert it.
- **The transitional merge is S1-only.** If S2 forgets to drop cue-chains from `chase`'s map, behaviour is still correct, since no grid timelines are left. But the clock keeps scanning the grid every tick.
- **A require cycle.** `playback` requires `shows` for `cue-at`, so `shows` must not require `playback`. It uses `cue-chains/timeline-cue?` directly.

## 12. Out of scope / alternatives considered

**Out of scope for S1:**
- any event or UI that starts a show (S2);
- `reconcile-chase-sources` over shows (S2);
- the timeline editor's keys (S2);
- TS-6 checks in the clock (S4);
- shows in the status bar (S5);
- the blackout banner's wording (S2).

**Alternatives considered:**
- **S1 passes only `playable-shows` to `apply-chase`.** Rejected: S1 lands before S2's migration, so grid followers would stop chasing for the length of S1.
- **`cue-at` dispatches on `(vector? k)` and `(keyword? (first k))`.** Rejected: `play-key?` is the one test, and a show key must hold a uuid.
- **Grid cells get a tag too (`[:cell col row]`).** Rejected: it touches every grid handler, vibe ref and test for no gain.
- **Show keys are ordered by title.** Rejected: titles change and repeat, so report order would shift on a rename. The uuid string is stable.
