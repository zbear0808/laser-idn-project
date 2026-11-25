# Timeline shows: storage, undo, migration, triggers, paste, lock

**Status:** Proposed   **Last verified against:** `63240576`
**Supersedes / Superseded by:** none. It details S2 steps 1, 3, 4 and 5 of `02-phases.md`, and `01-spec.md` §1 and §6.   **Related findings:** see §10 (corrections to `00`–`02`)

Paths are under `src/laser_show/` unless they start with `test/` (`T/` = `test/laser_show/`) or `plans/`. **NEW** marks anything that doesn't exist yet.

## 1. Current behaviour (file:line)

### 1.1 Where chains live and how they are saved

- **The domain.** `chains` has three keys: `:effect-chains`, `:cue-chains` and `:projector-effects` (`state/domains.clj:504-523`). A timeline is a cue chain at `[:chains :cue-chains [col row]]` (`:520-521`).
- **What is saved.** `persistent-state-mapping` saves `[:chains :cue-chains]` and `[:chains :effect-chains]` (`:owner :show`) and `[:chains :projector-effects]` (`:owner :rig`), all in `content.edn` (`state/persistent.clj:84-89`).
- **Saving.** `collect-data-for-paths` writes each row with `assoc-in acc path` (`:172-191`). So `content.edn` is `{:chains {:cue-chains … :effect-chains … :projector-effects …} :zone-maps … :zones …}`.
- **Loading.**
  - `zip-contents->file-data` merges the three members with `merge-with merge` (`:308-314`). Only `content.edn` has a `:chains` key.
  - `replace-at-path` (`:227-242`) puts the domain default where a path is missing (`:240`).

### 1.2 Checks on Open and Save

**`checked-project-files`** (`:121-155`):
- Its generic loop (`:142-150`) checks that every mapping path is a map or absent. The errors name the member and the path (`format-error`, `:95-99`).
- `check-grid!` (`:104-119`) then checks both grids: keys with `grid-cell?` (`:101-102`), values as maps, and `:items` as a sequence of maps (`:153-154`).

**Reading.**
- `read-project` reads strictly (`:371-384`).
- `serialization/read-one-edn` uses `clojure.edn/read` with no `:readers` (`state/serialization.clj`). The standard `#inst` and `#uuid` tags read; any other tag throws.

**Writing.** `write-project!` writes the project, then reads the staged zip back with `read-project`. So everything it writes passes `checked-project-files`.

**Save also does a dry Open.**
- `save-to` (`events/handlers/menu.clj:220-245`) builds the files, runs `prepare-open` (`:164`) on them, and throws the result away.
- `prepare-open` calls `apply-loaded-project`.
- So every Save runs the whole load pipeline, migrations included, on what it is about to write.

### 1.3 The load pipeline

`apply-loaded-project` (`state/persistent.clj:316-348`) runs these steps in order:

| Line | Step |
|---|---|
| :335 | `checked-project-files` |
| :339 | `replace-document` (`:244-251`), for `:show` paths |
| :340 | `reset-session` (`:269-306`) |
| :342 | `normalize-timeline-cues` on `[:chains :cue-chains]` |
| :343 | `zones-z2-show` |
| :344 | `zones-z4` |
| :345 | `rig-file/rename-pins` |
| :346 | `with-group-copy` |
| :347 | `history/mark-clean` |

- **BPM.** `[:timing :bpm]` holds the file's BPM from :339 on. Its row has `:keys [:bpm]` (`:73`), and nothing after :339 writes it.
- **`reset-session`** resets:
  - `:playback`, `:vibe-runtime`, `:list-ui`, `:history` and `:project`;
  - `[:ui :dialogs]`, `[:ui :notice]`, `[:ui :vibes]` and `[:ui :input-learn]`;
  - `[:grid :selected-cell]`.

  **It doesn't reset `[:ui :timeline-editor]`** (`state/domains.clj:211-212`), so old cells' cursors and selections survive an Open today.
- **Callers.**
  - `prepare-open` (`menu.clj:164-190`), called by `handle-file-project-loaded` (`:192-212`, the real Open) and by `save-to` (the dry run).
  - Startup never opens a project: `app/start!` mounts `starter-state` (`app.clj:165-174`).
  - New uses `new-project-state` (`persistent.clj:350-365`), which applies the starter templates.
- **`state.migration`** is pure, driven by the data, and safe to run twice (`state/migration.clj:1-16`). It uses helpers that keep identity when nothing changes: `update-same` (`:33-40`) and `update-vals-same` (`:45-53`). `zones-z4` (`:295-312`) touches only `:cue-chains`, `:effect-chains` and `[:config :preview]`.

### 1.4 Undo and the unsaved flag

**What gets compared.**
- `document-roots` includes `:chains` (`state/history.clj:65-68`), and `extract-document-state` keeps `:chains` whole (`:85`).
- `show-content-changed?` (`:181-189`) calls `chains-changed?` (`:151-166`) with `(complement rig-chain-domains)`, where `rig-chain-domains` is `#{:projector-effects}` (`:177-179`).
- `chains-changed?` takes the domains from the keys of both chains maps (`:166`), so any new domain key is compared.
- Each entry is compared with `entry-equiv?` (`:141-149`). `:effect-chains` ignores `:active?`; every other domain uses `equiv-ignoring-live?` (`:132-139`).
- `map-equiv?` (`:116-130`) ignores `live-item-keys` (`:collapsed? :muted?`, from `live-item-paths` `:60-63`), but only on maps that contain `:id` (`chain-item?`, `:106-107`).
- `persisted-changed?` (`:207-214`) and `undoable-changed?` (`:200-205`) both go through `show-content-changed?`.

**Restoring.**
- `restore-document` replaces `:chains` with `(graft-live-chains (:chains state) chains)` (`:342`).
- `graft-live-chains` (`:306-320`) walks every restored domain and grafts each entry with `graft-live-entry` (`:259-271`).
- `graft-live-entry` indexes the current entry's maps that carry an `:id` (`live-item-index`, `:223-229`) and copies their live keys onto the restored maps (`graft-live-item`, `:238-257`). `:active?` is special-cased for `:effect-chains` only (`:314-316`).

**The pipeline** (`events/core.clj:866-878`) runs `wrap-show-lock`, then the step grid, `wrap-normalize-timelines`, `wrap-history` and `wrap-dirty`, and so on.
- `record` (`events/handlers/history.clj:378-424`) pushes a step when `undoable-changed?` holds. It applies `:history/backfill` to every snapshot and passes it on to `wrap-dirty` for the saved baseline (`:503-527`).
- `history-excluded-events` (`:190-205`) includes `:file/project-loaded`.

**`wrap-normalize-timelines`** (`events/handlers/timeline.clj:540-568`):
- It normalizes the cue chains an event changed.
- It calls `reconcile-chase-sources` (`:506-538`), which rebuilds `:chase-sources` from `[:chains :cue-chains]` when the document id changed, as on Open (`:522-525`).

### 1.5 Things that name a grid cell

**Trigger bindings** live at `[:config :input :trigger-map key]`.
- **Keys.** `{:source :midi :channel :note}`, or `{:source :keyboard|:osc|:gamepad :id}` (`events/handlers/triggers.clj:31-41`).
- **Action `:trigger-cue`.** `{:type :trigger-cue :target [col row]}`, built at `events/handlers/input.clj:346-350`, with an optional `:behavior` (`:follow` or `:momentary`).
- **Actions `:start-timeline` and `:stop-timeline`.** `{:type :start-timeline|:stop-timeline :target [col row]}` is read (`triggers.clj:171-176`), but no code in `src/` writes it.
- **Dispatch.**
  - A `:trigger-cue` press sends `:grid/trigger-cell` (`triggers.clj:160-163`).
  - With `:behavior :momentary`, the press sends `:grid/activate-cell` and the release sends `:grid/release-cell` (`:91-105`, `:154-156`).
- **Keeping them in step.** When a cell moves or clears, `refs/move-trigger-targets` and `refs/remove-trigger-targets` (`vibes/refs.clj:182-193`, `:195-204`) rewrite them. They are called at `grid.clj:123-124, :165`.

**Trigger definitions.** `[:triggers :definitions]` (`state/domains.clj:96-99`) is read by `definition-action` (`triggers.clj:47-55`), but no code writes it and it isn't saved.

**Vibes** live at `[:config :vibes :library id]`.
- The slots that name a cue cell are `:hit {:cells [[c r] …]}` and `:accent {:cells […]}`, both **vectors**, plus `:flash {:cue [c r]}` and `:bed [c r]|nil` (`state/templates.clj:73-82`).
- `refs/move-cue` and `remove-cue` (`vibes/refs.clj:76-115`) keep them in step.
- The engine's own cell references in `[:vibe-runtime]` (voices, duck, bags) are reset by `reset-session` (`persistent.clj:285`), before any migration runs.

**Column targets.** `[:grid :column-targets col]` maps a column to a target. A cue in that column with no `:target` of its own draws there (`services/show_clock.clj:90`; `routing/targets.clj:133` `cue-fallback`).

**Session state** resets on Open: `[:grid :selected-cell]`, `[:playback …]`, `[:list-ui …]` and `[:ui :dialogs …]`. **`[:ui :timeline-editor [col row]]` is the exception** (§1.3).

### 1.6 What reads zone references from cues only

Each of these walks `[:chains :cue-chains]` and nothing else:

- **`routing.refs/ref-places`, kind `:cue-chains`** (`routing/refs.clj:371-412`). This is the one table that `replace-refs` (`:414`) uses. Its callers:
  - `rig-file/rename-pins` (`state/rig_file.clj`), which runs on every Open (`persistent.clj:345`);
  - zone group delete (`events/handlers/zone_groups.clj:218`);
  - rig set-up, import and repin (`state/rig_file.clj`).
- **`refs/cues-using`** (`routing/refs.clj:311-319`).
- **`refs/chain-ref-usage`** (`:330-345`).
- **`subs/cue-ref-sets`**, **`zone-patterns-in-use?`**, and the rig report's chains.

### 1.7 The clipboard

- **Copying a cell.** A grid cell copies `{:type :cue-chain :data cue :copied-at ms}` (`state/clipboard.clj`). It goes on the OS clipboard as one line of EDN, and is read back with `<-system-text`.
- **Pasting onto a cell.** `handle-grid-paste-cell` (`events/handlers/grid.clj:146-156`) calls `h/handle-paste-from-clipboard` (`events/helpers.clj:171-184`) with `(comp chains/fresh-ids migration/cue-z4)`.
- **An empty clipboard slot.** `with-clipboard` (`helpers.clj:128-154`):
  1. emits `:clipboard/read`;
  2. runs again with `:clipboard/system`;
  3. `adopt`s that clip into the slot and calls `f`;
  4. shows "Nothing to paste here" when there is nothing.
- **Where a timeline clip can go.**
  - `payload` already refuses a timeline cell into a cue **list** (`clipboard.clj:163-164`).
  - It still accepts one onto a **cell**. `T/state/clipboard_test.clj` asserts both (in `accepts-strict-types-and-conversions-test`, `:81`).
- **A list paste.** It runs `migration/items-z4` on cue items and keeps `:timeline/start`, `:timeline/duration` and `:track-id` (`events/handlers/list.clj:284-316`).
- **The hidden route.** `timeline-cue?` counts a cue with no `:editor-type` as a timeline when it has `:tracks`, or when any item has one of those keys (`animation/cue_chains.clj:60-76`). So pasting timed items can turn a plain cue into one.

### 1.8 Starter content, show lock, labels

- **Starter content.** `starter-state` (`app.clj:165-174`) and `new-project-state` (`persistent.clj:350-365`) apply `apply-starter-cue-chains` (`state/templates.clj:43-58`, no timelines), `apply-starter-vibes` and `apply-starter-effects`.
- **Show lock.**
  - `blocked-events` is at `events/show_lock.clj:34-96`.
  - `T/events/show_lock_test.clj:302` (`blocked-events-name-real-events-test`) runs every blocked event type, with `{:state (starter) :file-path "x.zip"}`, and fails if a handler returns `{}`.
- **History labels.**
  - `event-descriptions` is at `events/handlers/history.clj:213-313`.
  - `T/events/history_handlers_test.clj:294` (`history-labels-name-real-events-test`) runs every labelled event type with `{:state (test-state)}`, and fails if a handler returns `{}`.
  - A handler's own `:history/description` wins over the table.

## 2. Root cause

Moving timelines into `[:chains :shows]` is a storage change with four parts:

1. **Persistence only knows about `[:chains :shows]` through one mapping row.** Without it, Save drops the library, and Open neither checks it nor resets it.
2. **The load pipeline and the zone-reference readers name `:cue-chains` explicitly** (§1.3, §1.6).
   - Shows loaded from a file wouldn't be normalized before `mark-clean`. The middleware would normalize them afterwards, so the file would open as unsaved.
   - A show's pins and zone-group targets wouldn't follow `rename-pins`, or a zone group's deletion.
3. **An old file's timelines are addressed by cell, and so is everything that points at them** (§1.5). Moving them needs one pure pass that rewrites those pointers. That pass also runs in Save's dry run, so it must be pure and safe to run twice.
4. **A timeline can still reach the grid from outside.** It can arrive as OS clipboard text from an older build, either pasted onto a cell or as list items that carry timing keys (§1.7).

Undo and unsaved tracking need no change (§4.1 proves it).

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `chains` defstate (`state/domains.clj:504-523`) | Gains `:shows` |
| `persistent-state-mapping` `:content` (`state/persistent.clj:84-89`); `replace-at-path` (`:227-242`); `collect-data-for-paths` (`:172-191`) | One more row. A missing path loads `{}` |
| The generic map check (`:142-150`); `format-error` (`:95-99`); `check-grid!` (`:104-119`) | `check-shows!` follows `check-grid!`. It only checks keys and values, because the generic loop already checks that the path is a map |
| `model/normalize-timeline-cues` (`timeline/model.clj:420-434`) | Runs on `[:chains :shows]` at load, as it does on cue chains at `:342` |
| `state/migration.clj`'s style (`update-same`, `:33-40`) | `timeline-shows` returns the same state, by identity, when there is nothing to move |
| `u/cell-label` (`common/util.clj:229-233`), which is **pure** | Gives the default name, e.g. "Timeline A3". No copy is needed |
| `cue-chains/timeline-cue?` (`animation/cue_chains.clj:60-76`) | Finds the cells to move, including inferred timelines |
| `model/effective-length` (`timeline/model.clj:475-478`), `playback/chases-mtc?` (`timeline/playback.clj:55-58`) | The follower and empty checks during migration |
| `refs/remove-cue` (`vibes/refs.clj:103-115`); `move-trigger-targets` (`:182-193`) | Vibe clean-up. The trigger rewrite is a sibling of `move-trigger-targets` |
| `routing.refs/rewrite-cue`, `ref-places` (`routing/refs.clj:347-412`) | A NEW `:shows` place reuses `rewrite-cue` unchanged |
| `h/with-clipboard` (`events/helpers.clj:128-154`), `h/show-notice` (`:73-87`) | The paste refusal |
| `clipboard/payload`, `accepts?` (`state/clipboard.clj`) | One more refusal rule, next to the cue-list one |
| `:history/backfill`; `handle-open-editor`'s backfill (`events/handlers/timeline.clj:467-471`) | `shows/handle-open-editor` keeps the same backfill, at `[:chains :shows id]` |

**NEW functions**, each justified:

| Function | Why |
|---|---|
| `persistent/check-shows!` | No checker exists for uuid-keyed chains |
| `migration/timeline-shows` | No migration moves data from one domain to another |
| `vibes.refs/rehome-trigger-targets` | `move-trigger-targets` can't change a binding's `:type` |
| `vibes.refs/cue-ref-count` | Nothing counts vibe references, and the banner reports them |
| `clipboard/timeline-clip?` | One test, shared by `accepts?` and the paste notice |
| `timeline.shows/tc-range`, `timeline.shows/overlaps?` (specified in `01` §1) | Needed in **S2**, not S3/S4, because the migration checks overlaps |

## 4. Changes per namespace

### 4.1 Undo and unsaved: no change, shown step by step

Take `[:chains :shows]` = `{u show}`, where a show has **no** `:id` key (`01` §1):

1. **Snapshots.** `extract-document-state` (`state/history.clj:85`) copies `:chains` whole, so `:shows` is in every snapshot and in the saved baseline.
2. **The fast path.** `document-roots-identical?` (`:89-92`) fails on any show edit, because the edit replaces `:chains`. So the full comparison runs.
3. **Which kind of content.** `chains-changed?` (`:151-166`) includes `:shows`, because it takes the union of the keys (`:166`). With `(complement rig-chain-domains)` that makes `:shows` **show content**: undoable, and counted as unsaved. `rig-content-changed?` excludes it.
4. **Comparing a show.** `entry-equiv? :shows a b` falls to `equiv-ignoring-live?`.
   - The show map itself has no `:id`, so nothing is ignored at the show level.
   - `:tracks` and `:items` hold maps that carry an `:id`, so those ignore `:muted?` and `:collapsed?`, and nested folders recurse.
   - This is exactly what cue cells get today (`T/events/history_handlers_test.clj:693-707`).
5. **Restoring.** `graft-live-chains` visits `:shows` and indexes the current show's tracks, items and effects by `:id`. The `:active?` branch applies to `:effect-chains` only.
6. **The unsaved flag.** `mark-clean` and `derive-dirty` (`:391-409`) go through `persisted-changed?`, which uses `show-content-changed?`. Covered.
7. **Gestures.** The transactions use `undoable-changed?`. Covered.
8. **Backfill.** `shows/handle-open-editor` (S2) returns `(fn [doc] (if (get-in doc [:chains :shows id]) (update-in doc [:chains :shows id] normalize) doc))`.

**Invariant SH-1: a show map never has a top-level `:id`.**
- With one, the show itself would become a chain item, and a show-level `:collapsed?` or `:muted?` would be ignored without anyone noticing.
- `shows/new-show` never adds one. `migration/timeline-shows` dissocs one if the cell's cue had it.

**One change is still needed, outside history.** `wrap-normalize-timelines` runs after the Open handler's `mark-clean` (`events/core.clj:869`, inside `wrap-dirty`). Once S2 re-points it at `[:chains :shows]`, a loaded show that needs normalizing would be normalized after the baseline was taken, so the file would open as unsaved. `apply-loaded-project` must normalize the shows itself (§4.2).

### 4.2 `laser-show.state.persistent` (existing)

**The mapping row** (`:84-89`). Add `{:path [:chains :shows] :owner :show}` after the `:cue-chains` row. As a result:
- `project-files` writes `{:chains {… :shows {uuid show}}}` into `content.edn`.
- `owned-paths :show` and `replace-document` pick the row up.
- An older file without it loads `{}`.
- The rig file is untouched: `rig-paths` lists only `[:chains :projector-effects]`.

**NEW `check-shows! [member data]`** (private), called in `checked-project-files` after `:154`. The generic loop already refuses a `[:chains :shows]` that isn't a map. For each `[k show]`:

| Rule | Error path | Message |
|---|---|---|
| `(uuid? k)` | `[:chains :shows k]` | "is not a show id (a uuid)" |
| `(map? show)` | `[:chains :shows k]` | "is not a map" |
| `(= :timeline (:editor-type show))` | `[:chains :shows k :editor-type]` | "is not :timeline" |
| `:items` is nil, or a sequence of maps | `[… k :items]` | "is not a sequence of maps" |
| `:tracks` is nil, or a sequence of maps | `[… k :tracks]` | "is not a sequence of maps" |
| `:show/info` is nil, or a map whose values are strings | `[… k :show/info]` | "is not a map of text" |

It doesn't check clip timing, tempo, timecode, or ids inside the show; `normalize-timeline-cue` and TS-6 own those. Item ids may be strings, as they are in `T/routing/legacy_shows/timeline.edn`.

**EDN and `#uuid` keys.**
- `pr-str` prints `#uuid "…"`, and `edn/read` reads it back with no extra readers.
- A UUID is `=` to its re-read value, so Save's read-back compares equal.
- Item `:id`s in saved cue chains are already UUIDs today. §8 pins this with a test anyway.

**`reset-session`** gains two lines, after `[:ui :vibes]`:
- `(assoc-in [:ui :shows] (merge (get-in initial [:ui :shows]) (select-keys (get-in state [:ui :shows]) [:query :scope :sort])))`. Search and sort are how the user browses, so they survive an Open. The selection, the edit in progress (`:editing`), `:manual` and `:migrated` go.
- `(assoc-in [:ui :timeline-editor] (get-in initial [:ui :timeline-editor]))`. The editor's per-show view state names ids from the show being closed. This also fixes today's leak (§1.3).

**`apply-loaded-project`**, the new order:

```clojure
(-> state
    (replace-document …)                                   ; :339
    (reset-session initial)                                ; :340
    (update-in [:chains :cue-chains] model/normalize-timeline-cues)   ; :342
    (update-in [:chains :shows] model/normalize-timeline-cues)        ; NEW
    (migration/zones-z2-show hardware)                     ; :343
    (migration/zones-z4)                                   ; :344
    (migration/timeline-shows)                             ; NEW, §4.3
    (rig-file/rename-pins hardware)                        ; :345: now sees shows (routing.refs :shows place, §4.6)
    (rig-file/with-group-copy outgoing-copy)               ; :346
    history/mark-clean                                     ; :347
    (assoc-in [:project :current-file] zip-path))
```

- **The migration goes after `zones-z4`,** so the cues it moves are already in Z4 form. That matters for the column-target rule (§4.3 step 2).
- **It goes before `rename-pins`,** so a moved show's pins follow this rig.
- **It writes `[:ui :shows :migrated]`.** That is UI state, which `mark-clean` doesn't see.

**`new-project-state`** needs no change. `replace-document … {}` gives `[:chains :shows] {}`, and `reset-session` resets `[:ui :shows]`.

### 4.3 `laser-show.state.migration` (existing): NEW `timeline-shows`

`(timeline-shows state)` and `(timeline-shows state {:keys [id-fn]})`. It is pure, and it returns `state` **identical?** when no cell of `[:chains :cue-chains]` is `timeline-cue?`.

1. **Find.** `cells` are the keys of `[:chains :cue-chains]` whose cue is `timeline-cue?`, sorted by `[row col]` (reading order).
2. **Move.** For each cell `[c r]`, take `id` = `(id-fn)` and build the show from the cue:
   - `(assoc :editor-type :timeline)`. An inferred timeline becomes explicit, which `check-shows!` requires.
   - `(dissoc :id :trigger-mode)`. SH-1 forbids `:id`, and a show has no trigger mode.
   - **Column target.** When the cue has no `:target` and `[:grid :column-targets c]` is set, `(assoc :target that)`. The show then draws where it did before. Without this it would fall back to the show default, because a show has no column.
   - **Name.** When `(str/blank? (:name cue))`, `(assoc :name (str "Timeline " (u/cell-label c r)))`.
   - **Tempo.** When it follows timecode (`:timeline/chase :mtc`) and has no `:timeline/tempo-bpm`, `(assoc :timeline/tempo-bpm (double (get-in state [:timing :bpm])))`, which is the file's BPM.

   Put the show at `[:chains :shows id]`, and remove the cell from `:cue-chains`.
3. **Check timecode.**
   - `enabled` starts as the shows already in the library that have `:timeline/chase :mtc`, in `(str id)` order. They are left alone.
   - Each moved follower is then checked, in step 1's order. It loses `:timeline/chase` and is added to `:timecode-off` when `(shows/tc-range show)` is nil (`:empty`), or when its range overlaps an earlier member of `enabled` (`shows/overlaps?`, half-open, `01` §4.1).
   - A follower that passes joins `enabled`.
4. **Re-point.**
   - **Triggers.** `[:config :input :trigger-map]` becomes `(refs/rehome-trigger-targets tm [c r] [:show id] {:trigger-cue :toggle-show :start-timeline :start-timeline :stop-timeline :stop-timeline})`. That changes the `:type` according to the map and the `:target`, and keeps `:behavior`.
   - Bindings to other cells are untouched. So are effect triggers: a `:trigger-effect [c r]` names a cell of the effects grid.
   - **Vibes.** Count `(refs/cue-ref-count state [c r])`, then call `(refs/remove-cue state [c r])`.
5. **Record.** When at least one cell moved, `(assoc-in [:ui :shows :migrated] {:moved n :timecode-off [names…] :vibe-refs-removed k :triggers-rewritten t})`. The names are the shows' final `:name`s, in step 1's order.

**Invariants**

| # | Rule |
|---|---|
| M-1 | It never adds, removes or changes a cue that isn't a timeline, or any effect cell |
| M-2 | Afterwards, no cue in `:cue-chains` is `timeline-cue?` |
| M-3 | Running it a second time returns `identical?` state |
| M-4 | The library grows by exactly the number of cells moved, and no id collides |
| M-5 | Afterwards, no binding and no vibe slot names a moved cell |
| M-6 | The enabled shows satisfy TS-6 (`01` §4), with the existing ones counted first |
| M-7 | It reads `[:timing :bpm]` and never writes it |
| M-8 | It never throws, because Save re-runs it. A cue it can't read stays where it is |

### 4.4 `laser-show.vibes.refs` (existing)

- **NEW `rehome-trigger-targets [trigger-map from-pos to-pos type-map]`.** Every binding whose `:target` is `from-pos` and whose `:type` is a key of `type-map` gets `:target to-pos` and `:type (type-map type)`. Its other keys stay. When nothing matches, it returns `trigger-map` itself.
- **NEW `cue-ref-count [state cell]`.** It counts the slots in `[:config :vibes :library]` that name `cell`. Each membership of `:hit :cells` or `:accent :cells` counts 1, and so does a matching `:flash :cue` or `:bed`.

### 4.5 `laser-show.events.handlers.triggers` (existing)

**`:start-timeline` and `:stop-timeline`** (`:171-176`):
- A `(shows/play-key? target)` target dispatches `:timeline/play {:show-id id}` or `:timeline/stop {:show-id id}`.
- A `cell?` target is logged at debug level and dispatches nothing. None are left after the migration.

**NEW action `:toggle-show`, on a normal press, dispatches NEW `:shows/toggle-run {:show-id id}`.**
- It doesn't use `:timeline/toggle-play`: that pauses and resumes from the cursor (`events/handlers/timeline.clj:407-413`). A grid press on a timeline cell stops it, or starts it from beat 0.
- `action-event` can't read state, so the decision happens in `:shows/toggle-run`'s handler:
  - a show that follows timecode: no change;
  - a show with an intent at `[:show id]`: `cue-timing/deactivate-cue`;
  - otherwise: `cue-timing/activate-cue` at `[:show id]`, with `{:timeline? true :start-beat 0.0}`.
- This isn't TS-9's "stop the previous manual show". A pad starts its own show.

**`:toggle-show` with `:behavior :momentary`.** Add `:toggle-show` to the momentary types (`:154-155`), and a case to `momentary-event` (`:91-105`):
- the press sends `:timeline/play {:show-id id :from 0.0}`;
- the release sends `:timeline/stop {:show-id id}`.

Every trigger dispatch carries `:source-trigger`, so none of them adds an undo step.

### 4.6 `laser-show.routing.refs` (existing) and the other zone-reference readers

`00`–`02` miss these. They are needed in S2's storage step.

- **`ref-places`** (`:371-412`) gains a `:shows` place:
  ```clojure
  {:kind :shows
   :replace (fn [state smap]
              (update-in-same state [:chains :shows]
                              #(update-vals-same % (fn [show] (when show (rewrite-cue show smap))))))}
  ```
  `rename-pins` on Open, zone group delete, and rig set-up, import and repin then re-point shows too.
- **NEW `refs/shows-using [shows ref]`** returns a sorted set of show ids. It sits beside `cues-using` (`:311-319`).
- **The zone group delete message** (`events/handlers/zone_groups.clj:218`) counts shows separately: "used by 2 cues and 1 show".
- **`chain-ref-usage`** (`:330-345`) gets a NEW 3-arity, `[cue-chains effect-chains shows]`, which adds `:shows #{id}`. Its callers pass `[:chains :shows]`.
- **`subs/cue-ref-sets`** adds each show's `targets/cue-refs`, with a nil column.
- **`zone-patterns-in-use?`** adds shows.

### 4.7 `laser-show.state.clipboard` (existing)

- **NEW `timeline-clip? [clip]`:** `(and (= :cue-chain (:type clip)) (cue-chains/timeline-cue? (:data clip)))`.
- **`accepts?`** is false for `:cue-chain` when `timeline-clip?` holds, and `payload` returns nil for it. Once a timeline clip is adopted into the slot, `can-paste? :cue-chain` is false, so Paste Cue is greyed out on the grid.
- **List paste.** `payload` for a `:cue-chain-items` clip strips `:timeline/start`, `:timeline/duration` and `:track-id` from each top-level item (NEW private `untimed`).
  - The pasted items then can't turn a plain cue into an inferred timeline.
  - After S2, no list of type `:cue-chain-items` shows a timeline's clips, so nothing that pastes into a timeline loses its timing.

### 4.8 `laser-show.events.handlers.grid` (existing)

`handle-grid-paste-cell` (`:146-156`) calls `h/with-clipboard` directly:

```clojure
(fn [state clip]
  (cond
    (clipboard/timeline-clip? clip) (h/show-notice state "Timeline shows live in the Shows tab. Paste it there with New Show from Cue." :info)
    (clipboard/accepts? clip :cue-chain) {:state (assoc-in state [:chains :cue-chains [col row]]
                                                   ((comp chains/fresh-ids migration/cue-z4) (clipboard/payload clip :cue-chain)))}
    :else nil))
```

- The notice's `:state` keeps `with-clipboard`'s adopt, because it receives that state.
- `[:chains]` doesn't change, so there is no undo step and the unsaved flag doesn't change.
- This covers in-app clips, OS clipboard text from an older build, and Edit > Paste.

### 4.9 `laser-show.events.handlers.menu` (existing)

- **`handle-file-project-loaded`** (`:192-212`). When `(get-in opened [:ui :shows :migrated])` is set, it also does `(assoc-in opened [:ui :active-tab] :shows)`.
  - This goes here, not in `prepare-open`, because Save's dry run mustn't switch tabs. Its result is discarded anyway.
- **`save-to`** (`:220-245`): no change.
  - The migration in the dry run is pure, and its result is discarded.
  - The files written come from `project-files state`.
  - A show that `check-shows!` would reject makes Save refuse: "this build couldn't open it again".

### 4.10 `laser-show.state.domains` (existing)

- **`chains`** (`:504-523`) gains:
  ```clojure
  :shows {:default {}
          :doc "{show-id (uuid) show}: the timeline show library (plans/2026-11-25-timeline-shows). A show is a timeline cue map with :editor-type :timeline and no :id. Show content: undoable, unsaved, saved in content.edn"}
  ```
- **`ui`** gains `:shows`, with `00` §5's default:
  ```clojure
  {:query "" :scope :all :sort {:column :title :dir :asc}
   :selected #{} :editing nil :manual nil :migrated nil}
  ```
  `:migrated` is `{:moved :timecode-off :vibe-refs-removed :triggers-rewritten}`. Open's migration writes it; `:shows/dismiss-migration` and `reset-session` clear it.
- **`:timeline-editor`** (`:211-212`): its doc now says it is keyed by show id and reset on New and Open.

### 4.11 Starter content: none (decision)

`state/templates.clj` and `app.clj:165-174` don't change, so a new project has an empty library and the tab's placeholder.

Why no example show:
- **It wouldn't demonstrate much.** It plays nothing useful without a song behind it, and its TC would have to be off.
- **It would be one more thing for New to keep in step,** with the templates and with `T/state/templates_test.clj`.
- **A demo is already one click away:** New Show from Cue makes one from any starter cue.

### 4.12 Show lock and history labels

`blocked-events` (`events/show_lock.clj:34-96`) gains one group, with this comment: "A show's timecode and its range: turning TC on, or moving the range of a show that is on (BPM, TC start), changes what lights when; deleting a show removes what the show is built from."

| Event | Blocked? | `event-descriptions` label | Why |
|---|---|---|---|
| `:shows/set-timecode` | **yes** | "Change Timecode". The handler returns its own `:history/description`, "Enable Timecode" or "Disable Timecode", which wins | starts or stops light |
| `:shows/set-bpm` | **yes** | "Set Timeline Tempo", as `model/op-label` gives | moves an enabled show's range |
| `:shows/set-tc-start` | **yes** | "Set Timecode" | the same |
| `:shows/request-delete`, `:shows/delete` | **yes**, like `:zone-maps/request-delete` and `:zone-maps/delete` | `:shows/delete`: "Delete Show(s)". `request-delete` has none, as it changes no document | removes content |
| `:shows/create`, `:shows/duplicate`, `:shows/new-from-cue` | no | "New Show", "Duplicate Show(s)", "New Show from Cue" | each adds a show that isn't triggered and has TC off, so nothing that plays changes. **This differs from `00` §6, which blocks `new-from-cue`** |
| `:shows/set-info` | no | "Edit Show Info" | metadata only |
| `:shows/open-editor`, `play`, `stop`, `toggle-run`, `toggle-play`, `seek`, `next`, `previous`, `set-query`, `set-scope`, `set-sort`, `select`, `dismiss-migration` | no | none | these belong to the performer or the UI |

- **The tests call handlers with a bare event.** So every `:shows/*` handler that is blocked or labelled must return something other than `{}` when `:show-id` or `:show-ids` is missing. It returns `{:state state}`.
- **`:timeline-cue-editor/open` is removed.** No label points at it.
- **Clip edits on a show stay open under the lock,** as they are for timeline cues today. TS-6's automatic turn-off can then change what plays mid-show. This is accepted, the same as today's clip edits.

## 5. State placement

| Key / path | Lives in | Written by | Undo/dirty? |
|---|---|---|---|
| NEW `[:chains :shows]` `{uuid show}` | document, `content.edn` | `:shows/*` edits; `:timeline/*` edits; `migration/timeline-shows`, inside the `:file/project-loaded` handler | yes / yes (§4.1). A track's `:muted?` and `:collapsed?` stay live |
| `[:config :input :trigger-map]`: `:toggle-show` actions and `[:show id]` targets | document (`[:config]`) | the migration; `:shows/delete` removes a deleted show's bindings (S3) | yes / yes. Never an undo step during Open: the event is excluded, then `mark-clean` runs |
| `[:config :vibes :library]` slots | document | the migration (`refs/remove-cue`) | the same as above |
| NEW `[:ui :shows]` | UI, session | the `:shows/*` UI events; `:migrated` by `apply-loaded-project` | no / no. `reset-session` keeps `:query`, `:scope` and `:sort` |
| `[:ui :timeline-editor]` | UI | the timeline view handlers | no / no. Reset on New and Open (NEW) |
| `[:ui :active-tab]` `:shows` | UI | `handle-file-project-loaded`, when anything was migrated | no / no |
| `[:ui :clipboard]` holding a timeline clip | UI | `with-clipboard`'s adopt | no / no. It pastes nowhere |

## 6. Events and effects

There are no new effect keys. File I/O stays `:project/load` and `:project/save`; the clipboard stays `:clipboard/read` and `:clipboard/write`.

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:file/project-loaded` → `apply-loaded-project`, now with the migration | yes (`menu.clj:192-212`) | `:project/load`'s `:on-loaded` | yes |
| Save's dry run (`save-to`) | yes (`menu.clj:220-245`) | `:file/save`, `:file/save-as-result` | yes |
| `:grid/paste-cell`, now refusing a timeline clip | yes (`grid.clj:146-156`) | the cell menu; Edit > Paste | yes |
| `:list/paste-items`, now pasting cue items without timing | yes (`list.clj:284-316`) | the list menu | yes |
| `:trigger/input` → NEW `:shows/toggle-run`, or `:timeline/play` / `:timeline/stop` with `:show-id` | `triggers.clj`, extended | the input router | yes |
| `:shows/toggle-run {:show-id}` | NEW (`events/handlers/shows.clj`) | `triggers.clj`'s `action-event` | yes. Writes only `[:playback]` |

## 7. Data examples and invariants

Ids come from a counter `id-fn`: `u1` = `#uuid "00000000-0000-0000-0000-000000000001"`, and so on. `…` stands for contents that don't change.

### 7.1 Migration cases (`migration/timeline-shows`)

**Case A: no timelines**
```clojure
;; in: {:chains {:cue-chains {[0 0] {:items [{:id i1 :type :preset :preset-id :circle}]}} :shows {}} …}
;; out: identical? to in; no [:ui :shows :migrated]
```

**Case B: two timelines, a column target, triggers and vibes** (the file's BPM is 120)
```clojure
;; in
{:timing {:bpm 120.0}
 :grid {:column-targets {2 :graphics}}
 :chains {:cue-chains {[0 0] {:items [{:id i1 :type :preset :preset-id :circle}]}
                       [2 0] {:name "Intro" :editor-type :timeline :trigger-mode :toggle
                              :timeline/chase :mtc :timeline/tempo-bpm 128.0 :timeline/tc-start 0.0
                              :tracks [{:id t1 :type :track :name "T" :muted? true}]
                              :items [{:id c1 :type :preset :preset-id :circle :track-id t1
                                       :timeline/start 0.0 :timeline/duration 64.0}]}
                       [3 0] {:tracks [{:id t2 :type :track}] :items [{:id c2 :track-id t2 :timeline/start 0.0 :timeline/duration 8.0 …}]}}
          :shows {}}
 :config {:input {:trigger-map {{:source :midi :channel 0 :note 36} {:type :trigger-cue :target [2 0]}
                                {:source :keyboard :id :k} {:type :trigger-cue :target [3 0] :behavior :momentary}
                                {:source :midi :channel 0 :note 37} {:type :trigger-cue :target [0 0]}
                                {:source :midi :channel 0 :note 38} {:type :trigger-effect :target [2 0]}}}
          :vibes {:library {:vibe-1 {:hit {:cells [[2 0] [0 0]]} :accent {:cells []} :flash {:cue [3 0]} :bed nil}}}}}
;; out
{:chains {:cue-chains {[0 0] {:items [{:id i1 …}]}}
          :shows {u1 {:name "Intro" :editor-type :timeline :target :graphics
                      :timeline/chase :mtc :timeline/tempo-bpm 128.0 :timeline/tc-start 0.0
                      :tracks [{:id t1 … :muted? true}] :items [{:id c1 …}]}
                  u2 {:name "Timeline A4" :editor-type :timeline :tracks […] :items […]}}}
 :config {:input {:trigger-map {{… :note 36} {:type :toggle-show :target [:show u1]}
                                {:source :keyboard :id :k} {:type :toggle-show :target [:show u2] :behavior :momentary}
                                {… :note 37} {:type :trigger-cue :target [0 0]}
                                {… :note 38} {:type :trigger-effect :target [2 0]}}}  ; effects grid: untouched
          :vibes {:library {:vibe-1 {:hit {:cells [[0 0]]} :accent {:cells []} :flash {:cue nil} :bed nil}}}}
 :ui {:shows {… :migrated {:moved 2 :timecode-off [] :vibe-refs-removed 2 :triggers-rewritten 2}}}}
```
`u2` gets no `:target`, because column 3 has none. `:trigger-mode` is dropped.

**Case C: a follower without a tempo** (the file's BPM is 124)
```clojure
;; in  [:chains :cue-chains [0 1]] {:name "Drop" :editor-type :timeline :timeline/chase :mtc :items [{… :timeline/duration 32.0}] …}
;; out [:chains :shows u1] {:name "Drop" … :timeline/chase :mtc :timeline/tempo-bpm 124.0}
;;     :migrated {:moved 1 :timecode-off [] :vibe-refs-removed 0 :triggers-rewritten 0}
```

**Case D: an overlap, and an empty follower** (all at 120 BPM, so 64 beats is 32 s)
```clojure
;; in
[:chains :cue-chains]
{[0 0] {:editor-type :timeline :timeline/chase :mtc :timeline/tempo-bpm 120.0 :timeline/tc-start 0.0  :items [{… :timeline/duration 64.0}] …}
 [1 0] {:editor-type :timeline :timeline/chase :mtc :timeline/tempo-bpm 120.0 :timeline/tc-start 16.0 :items [{… :timeline/duration 64.0}] …}
 [2 0] {:editor-type :timeline :timeline/chase :mtc :timeline/tempo-bpm 120.0 :timeline/tc-start 32.0 :items [{… :timeline/duration 64.0}] …}
 [0 1] {:editor-type :timeline :timeline/chase :mtc :timeline/tempo-bpm 120.0 :items []}}
;; ranges: A1 [0,32)  A2 [16,48)  A3 [32,64)  B1 none (length 0)
;; out: u1 "Timeline A1" on; u2 "Timeline A2" chase removed (overlaps A1); u3 "Timeline A3" on (touches A1's end);
;;      u4 "Timeline B1" chase removed (empty)
;;      :migrated {:moved 4 :timecode-off ["Timeline A2" "Timeline B1"] :vibe-refs-removed 0 :triggers-rewritten 0}
```

**Case E: start and stop triggers, with shows already in the library**
```clojure
;; in  :shows {u9 {:name "Set" :editor-type :timeline :timeline/chase :mtc :timeline/tempo-bpm 120.0 :timeline/tc-start 0.0 :items [{… :timeline/duration 64.0}]}}
;;     :cue-chains {[4 2] {:items [{:id c :track-id "x" :timeline/start 0.0 :timeline/duration 8.0}] :timeline/chase :mtc :timeline/tempo-bpm 120.0}}
;;     trigger-map {k1 {:type :start-timeline :target [4 2]} k2 {:type :stop-timeline :target [4 2]}}
;; out :shows {u9 {… unchanged, on}
;;             u1 {:name "Timeline C5" :editor-type :timeline :timeline/tempo-bpm 120.0 :items […]}}  ; no :timeline/chase: [0,4) overlaps "Set" [0,32)
;;     trigger-map {k1 {:type :start-timeline :target [:show u1]} k2 {:type :stop-timeline :target [:show u1]}}
;;     :migrated {:moved 1 :timecode-off ["Timeline C5"] :vibe-refs-removed 0 :triggers-rewritten 2}
;; second run: identical?
```

### 7.2 `check-shows!`: input → result

| `[:chains :shows]` in `content.edn` | Result |
|---|---|
| absent | accepted; loads `{}` |
| `{}` | accepted |
| `[]` | throws `{:member "content.edn" :path [:chains :shows]}`, "is not a map" (the generic loop) |
| `{"abc" {…}}` | throws at `[:chains :shows "abc"]`, "is not a show id (a uuid)" |
| `{u1 [:x]}` | throws at `[:chains :shows u1]`, "is not a map" |
| `{u1 {:name "x"}}` (no `:editor-type`) | throws at `[… u1 :editor-type]` |
| `{u1 {:editor-type :timeline :items [:circle]}}` | throws at `[… u1 :items]` |
| `{u1 {:editor-type :timeline :show/info {:artist 3}}}` | throws at `[… u1 :show/info]` |
| `{u1 {:editor-type :timeline :items [{:id "c" :timeline/start -4}]}}` | accepted; `normalize-timeline-cue` fixes the start at load |

### 7.3 Undo and unsaved, from a clean state with show `u1`

| Edit | Undo step? | `dirty?` |
|---|---|---|
| `:shows/create` | yes, "New Show" | true |
| `:shows/set-info :artist "Daft Punk"` | yes, "Edit Show Info" | true |
| `:timeline/set-track-muted :show-id u1 :muted? true` | no | false |
| `:timeline/update-tracks` with a folder `:collapsed? true` | no | false |
| mute, then `:timeline/apply-op :move-clips`, then Undo | the move is undone | the mute stays on |
| `:shows/open-editor` on a show that needs normalizing | no (backfill) | false |
| Undo of `:shows/create` | — | false (back to the baseline) |
| `:shows/set-query "daft"` | no | false |
| Open of an old file with timeline cells (Case B) | the history is empty | false |
| Save, then Open of the saved file | — | false; `[:chains :shows]` is `=` |

### 7.4 Paste

| Slot / OS text | Target | Result |
|---|---|---|
| slot nil; OS text `{:type :cue-chain :data {:editor-type :timeline :tracks […] …}}` | `:grid/paste-cell [1 1]` | The cell is unchanged and the slot holds that clip. Shows the notice "Timeline shows live in the Shows tab. …". No undo step, and `can-paste? :cue-chain` is false afterwards |
| slot holds an inferred-timeline clip (`:items [{… :timeline/start 0.0}]`) | `:grid/paste-cell` | the same refusal |
| slot holds a plain cue clip | `:grid/paste-cell` | pasted as today, with fresh ids and `cue-z4` |
| slot `{:type :cue-chain-items :items [{:id i :preset-id :circle :track-id t :timeline/start 4.0}]}` | `:list/paste-items` on `[:cue-chain 0 0]` | The item is pasted without `:track-id` or `:timeline/*`, and `[0 0]` stays a plain cue |

## 8. Tests (named test vars + fixtures)

### Extend

- **`laser-show.state.persistent-test/checked-project-files-test`** (`T/state/persistent_test.clj:778`): add every throwing row of §7.2.
- **`laser-show.state.persistent-test/load-normalizes-timeline-cues-and-stays-clean-test`** (`:287`):
  - the untimed timeline is now expected at `[:chains :shows <id>]`, found by its `:name`;
  - add a second fixture, with an untimed **show** already in `[:chains :shows]`, which must open normalized and with `dirty? false`.
- **`laser-show.state.migration-test/migration-is-idempotent-test`** (`T/state/migration_test.clj:334`): also run `timeline-shows` twice.
- **`laser-show.state.clipboard-test/accepts-strict-types-and-conversions-test`** (`T/state/clipboard_test.clj:81`):
  - the "timeline cue's cell" block flips to `(not (clipboard/accepts? c :cue-chain))`;
  - add an assertion for the `untimed` payload.
- **`laser-show.events.history-handlers-test`:**
  - the "timeline convert" row of `meta-keys-do-not-reach-effects-test` becomes `:shows/new-from-cue`;
  - `timeline-editor-open-classification-test` becomes `shows-open-editor-classification-test`;
  - `pipeline-normalizes-items-added-by-cue-chain-editor-test` is **deleted**, because the cue chain editor never edits a show;
  - `timeline-state` builds `[:chains :shows u1]`;
  - `track-mute-is-not-undoable-test` (`:693`) and its siblings switch `:col 0 :row 0` to `:show-id u1`, with their assertions unchanged.
- **`laser-show.routing.legacy-shows-test`** (`T/routing/legacy_shows_test.clj`). `02` misses this one.
  - The fixture `legacy_shows/timeline.edn` holds timeline cues, one of them inferred, and all of them migrate on Open.
  - Its `cue-cells` and `play` helpers must also find shows by `:name` and play them with `:shows/toggle-run`.
  - `golden.edn` keeps its `[col row]` keys, mapped to shows by name. The expected frames don't change, which follows from M-1 plus the column-target rule.
  - `legacy-loads-are-clean-test` (`:197`) also compares `[:chains :shows]`.

### NEW

**Persistence**
- **`laser-show.state.persistent-test/shows-survive-save-and-load-test`.** The fixture is `(test-state)` with two shows that carry `:show/info`, `#uuid` keys and a muted track.
  - `write-project!` is true, and `read-project` gives back `=` data.
  - `apply-loaded-project` gives an `=` `[:chains :shows]`, with `dirty? false`.
  - A file written without the row loads `{}`.
- **`laser-show.state.persistent-test/old-timeline-show-opens-clean-test`.** The fixture is Case B as a zip. After Open:
  - one cell is left and there are two shows, found by name;
  - `[:ui :shows :migrated]` is as in §7.1;
  - `dirty? false`, and the undo stack is empty;
  - running `project-files` then `apply-loaded-project` again gives no `:migrated` and the same shows.
- **`laser-show.state.persistent-test/session-reset-keeps-show-search-test`.** The fixture's `[:ui :shows]` is `{:query "daft" :scope :artist :sort {:column :bpm :dir :desc} :selected #{u1} :manual u1 :migrated {…}}`, plus `[:ui :timeline-editor {u1 {…}}]`. After `new-project-state`:
  - the query, scope and sort are kept, and the rest is reset;
  - `:timeline-editor` is `{}`.
- **`laser-show.events.menu-file-handlers-test/save-dry-run-ignores-migration-test`** (`T/events/menu_file_handlers_test.clj`):
  - `menu/prepare-open` on the `project-files` of a state holding an inferred-timeline cell succeeds;
  - the files `save-to` writes still hold the cell;
  - the `:file/save` handler, run through the pipeline, leaves `[:ui :active-tab]` unchanged.

**Migration**
- **`laser-show.state.migration-test/timeline-shows-examples-test`:** Cases A–E (§7.1), with the `id-fn` `(let [n (atom 0)] #(java.util.UUID. 0 (swap! n inc)))`.
- **`laser-show.state.migration-test/timeline-shows-is-idempotent-test`:** the second run is `identical?` for every case.
- **`laser-show.state.migration-test/timeline-shows-invariants-test`:**
  - Seeds 0–199 on `java.util.Random`; `test.check` isn't a dependency (`deps.edn`).
  - Each seed generates random grids of timelines, explicit and inferred, including followers with random tc-start, tempo and length. It adds random trigger maps and vibes.
  - Checks M-1, M-2, M-4, M-5, M-6 and M-8.
  - A failure prints the seed.

**Vibes and triggers**
- **`laser-show.vibes.refs-test/rehome-trigger-targets-test`:** Case B's map. Bindings that don't match stay `identical?`, and an empty map comes back as itself.
- **`laser-show.vibes.refs-test/cue-ref-count-test`:** a hit pool `[[2 0] [0 0]]` plus a flash at `[2 0]` gives 2 for `[2 0]`; a cell nothing names gives 0.
- **`laser-show.events.handlers.triggers-test/show-targets-play-and-toggle-test`:**
  - `:start-timeline [:show u]` → `:timeline/play {:show-id u}`;
  - a `:toggle-show` press → `:shows/toggle-run {:show-id u}`;
  - a `:momentary` `:toggle-show` press and release → play from 0.0, then stop;
  - a `[col row]` `:start-timeline` → no `:dispatch`.
- **`laser-show.events.shows-handlers-test/toggle-run-mirrors-a-cell-press-test`:** an idle show starts at beat 0, a playing one stops, and a follower is unchanged.

**History**
- **`laser-show.state.history-test/shows-are-undoable-show-content-test`,** next to `zone-maps-and-labels-are-undoable-test` (`T/state/history_test.clj:466`). It calls `show-content-changed?`, `rig-content-changed?` and `persisted-changed?` directly:

  | Documents differ only in | Results |
  |---|---|
  | `[:chains :shows u :show/info]` | true, false, true |
  | a track's `:muted?` | false, false, false |

- **`laser-show.state.history-test/restore-grafts-show-track-mute-test`.**
- **`laser-show.events.history-handlers-test/show-edits-undo-and-dirty-test`:** every row of §7.3, run through the event pipeline.

**Paste**
- **`laser-show.events.grid-handlers-test/paste-refuses-timeline-clip-test`,** beside `paste-cell-with-an-empty-slot-asks-for-the-system-clipboard-test` (`T/events/grid_handlers_test.clj:132`): rows 1–3 of §7.4.
- **`laser-show.events.list-handlers-test/pasted-cue-items-lose-timeline-timing-test`:** row 4 of §7.4, and `(cue-chains/timeline-cue? cell)` is false afterwards.

**Zone references**
- **`laser-show.routing.refs-test/shows-are-a-ref-place-test`:** `replace-refs {:graphics :crowd}` re-points a show's `:target`, a track's and a clip's. When no ref is named, it returns `identical?` state.
- **`laser-show.events.zone-groups-handlers-test/delete-counts-shows-test`:** the delete request's message names the show.

**Starter content**
- **`laser-show.state.templates-test/new-project-has-no-shows-test`:** `new-project-state` gives `[:chains :shows]` `{}`.

## 9. Out of scope / alternatives considered

- **Running the migration before `zones-z4`.** The cues it moves would need Z4 too, so the work would be done twice. Running it after is simpler, and Z4 is safe to repeat.
- **Keeping a column's target implicit, with a "home column" per show.** That brings back cell identity. Copying the column target into `:target` once keeps what the user had.
- **Dropping `:behavior :momentary` in the migration.** A momentary pad would silently become a toggle. Supporting momentary in `momentary-event` costs one case.
- **`:toggle-show` → `:timeline/toggle-play`** (as `02` has it). That gives pause and resume from the cursor, not the cell's stop and start-from-the-top.
- **Refusing timeline clips in `with-clipboard` for every target.** Only the grid needs it; cue lists already refuse them in `payload`.
- **One example starter show.** See §4.11.
- **Migrating `[:triggers :definitions]`.** Nothing writes or saves it (§1.5).
- **Re-checking TS-6 for every show at load, not only the moved ones.** Existing shows were saved by a build that enforces TS-6. A hand-edited file is only checked by the middleware at its next edit. If that turns out to be wrong, step 3 can run over all followers with no other change.

## 10. Corrections to `00`–`02` (evidence)

| Doc and place | It says | The code says |
|---|---|---|
| `01` §6 step 2 | `common/cell-label` is a view helper, so the migration needs a pure copy | `laser-show.common.util/cell-label` is already pure (`common/util.clj:229-233`). No `migration/cell-name` |
| `01` §6 table rows 2 and 4 | `[3 0]` → "Timeline D1", `[1 0]` → "Timeline B1" | The letter is the **row**: `(char (+ 65 row)) (inc col)`. So they are "A4" and "A2" |
| `01` §6 table, last row | a vibe's `:hit :cells #{[2 0] [5 5]}` | Pools are vectors (`state/templates.clj:74`) |
| `01` §6 | cells move, triggers are rewritten, vibes are dropped | It also needs to carry the **column target**, stamp `:editor-type :timeline` on inferred timelines, and keep `:behavior` |
| `02` S2 step 1 | "Undo and unsaved tracking need no change" | True for history (§4.1). But `apply-loaded-project` needs a NEW `normalize-timeline-cues` on `[:chains :shows]` |
| `02` S2 | — (not mentioned) | `routing.refs/ref-places` and the usage readers (§1.6) walk only cue-chains. Without a `:shows` place, refs in shows go stale |
| `02` S2 step 5 | `:toggle-show` → `:timeline/toggle-play` | That pauses. Use NEW `:shows/toggle-run` |
| `02` S2 step 3 | `migration/timeline-shows` goes after :344 | It must also come before `rename-pins` (:345) |
| `02` S3/S4 | `tc-range` and the overlap check arrive in S3 and S4 | The migration, in S2, already needs them |
| `02` S2 tests | only the timeline handler tests are re-keyed | `history_handlers_test` and `legacy_shows_test` also use timeline cells |
| `00` §6 show lock | `:shows/new-from-cue` is blocked | It adds a show with TC off (§4.12). Block `set-bpm`, `set-tc-start` and `request-delete` instead |
| `02` S2 tests | `paste-refuses-timeline-clip-test` | `T/state/clipboard_test.clj` currently asserts that a timeline cell **is** accepted onto a cell, so that assertion flips |
