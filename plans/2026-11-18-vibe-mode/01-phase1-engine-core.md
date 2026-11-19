# Phase 1: the vibe model and its engine (no UI)

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** Done in `9177b1e` (2026-11-19); V1, V2, V3, V6, V7 answered A. As built: §10   **Last verified against:** `9177b1e` (§10)
**Supersedes / Superseded by:** supersedes the scaffolding named in V7 (`state/domains.clj:93-100, 106-111`, `events/handlers/triggers.clj:134-155`)   **Related findings:** review 05 F13, F15, L1, M4 step 1. K-15 (learn also fires the old binding) is *not* fixed here

After this phase the engine works and is tested, and you can drive it from the REPL or from any trigger-map entry. There is no screen for it yet (Phase 2), so land the two together.

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| Input to action | `events/handlers/triggers.clj:13-27` | `lookup-key` turns a note or trigger event into a trigger-map key; the action is `(get-in state [:config :input :trigger-map key])` |
| Action to event | `:59-89` | `action-event` handles `:trigger-cue :trigger-effect :stop-all :blackout :tap-tempo :start-timeline :stop-timeline :apply-preset`. `:apply-preset` dispatches `:preset/apply`, which no domain handles (`events/handlers.clj:36-69`) |
| Press and release | `:43-57, 59-89` | only `:momentary` cue and effect actions react to a release. Every other action ignores it |
| Bookkeeping | `:91-114` | every discrete input writes `[:trigger-runtime :last-input-time :tap-history :held-inputs :active-triggers]`. Nothing reads them, and unmapped presses pile up (probe v8) |
| Timestamps | `events/handlers/input_mapper.clj:73-76`; `events/core.clj:88-91, 462-467`; cljfx 1.10.6 `cljfx/event_handler.clj:8-13` | the mapper's `:time` is overwritten by the processing time (probe v2). The original stays in `[:input-event :timestamp]` (`input/events.clj:17-68`) |
| Start and stop a cue | `animation/cue_timing.clj:322-342`; `events/handlers/grid.clj:32-47, 95-107` | `activate-cue` writes the intent `{:run uuid}` and sets `:playing?` true; `deactivate-cue` drops it and sets `:playing?` to whether any cue remains. `trigger-cue` (the MTC-chase rule, timeline start beat) is private |
| A run ending | `animation/cue_timing.clj:176-184`; `animation/show_time.clj:187-202, 252-275`; `events/handlers/timing.clj:122-128` | only timeline runs end. The clock reports `:show-clock/cue-ended`, `active-runs` drops ended runs from the render at once, and the handler deactivates the cue if the run id still matches. A plain run counts beats for ever (probe v7) |
| Picking from a pool | `triggers/shuffle_bag.clj:5-36` | unused. The bag stores its `:shuffle-fn` in the data, uses `clojure.core/shuffle`, and repeats at refills (probe v1) |
| Group scaffolding | `state/domains.clj:89-115`; `events/handlers/triggers.clj:134-155` | `[:triggers :groups :settings]`, `[:trigger-runtime :active-groups :shuffle-bags]`. Not saved (probe v3). `:trigger/tick` is dispatched by nothing |
| Cell moves and clears | `events/handlers/grid.clj:49-71, 147-167, 185-193`; `events/handlers/effects.clj:47-76, 94-104` | trigger-map entries follow a moved cell and go when it is cleared. The two helper functions are written twice (`migrate-cell-triggers`, `remove-cell-triggers`: `events/handlers/grid.clj:49-71`, `events/handlers/effects.clj:47-69`) |
| Document policy | `state/history.clj:39-64, 153-159`; `state/persistent.clj:33-61, 112-127` | a key of `[:config]` that isn't in `prefs-config-keys` is undoable content and is saved; the load merges file over default shallowly (probe v3) |
| Starter content | `state/templates.clj:17-63`; used at `app.clj:144-146, 259-261` and `state/persistent.clj:174-182` | eight shape cues in row 0, applied only when the grid is empty |

## 2. Root cause

The parts exist, but nothing joins them. There is no object called a vibe. A press has no memory of what it started, so a release can't undo it. A cue can't end after N beats. The one picker repeats. Blackout is a transport stop that any cue start undoes.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `[:config]` document policy (`state/history.clj:39-64`; `state/persistent.clj:49`) | `[:config :vibes]` is saved, undoable and marks the project unsaved, with no change to either file |
| Trigger map and learn (`events/handlers/input.clj:223-270`) | role bindings are ordinary entries `{:type :vibe :role :hit}` |
| `cue-timing/activate-cue`, `deactivate-cue` (`animation/cue_timing.clj:322-342`) | start and stop a voice. `activate-cue` gains two optional intent keys |
| `grid/trigger-cue` (`events/handlers/grid.clj:32-42`) | made public as `start-cue`, so vibes follow the same MTC-chase and timeline rules |
| Runs and `:show-clock/cue-ended` (`animation/show_time.clj:126-158, 187-202`; `events/handlers/timing.clj:122-128`) | the decay is a run that ends; the report path is unchanged |
| `:timing/tap-tempo` (`events/handlers/timing.clj:42-58`) | HIT dispatches it |
| `triggers.shuffle-bag` | the picker, with a refill guard and a seed |
| Effect cells' `:active?` (`events/handlers/effects.clj:26-45`; live, `state/history.clj:19-21`) | FX sets and flash FX set it directly |
| `test/laser_show/animation/show_time_test.clj:23-57` pattern | engine tests build state with the real handlers and step the clock by hand |

## 4. Changes per namespace

- `laser-show.vibes.library` (NEW, pure): the data. `settings`, `vibe`, `current-id`, `current-vibe`, `next-id`, `empty-library`; `audience-cell?` and `audience-groups` (§7); `playable-cells` (cells whose cue has items, does not chase MTC, and is not audience-capable unless the vibe has `:allow-audience? true`). Edits come in Phase 3.
- `laser-show.vibes.refs` (NEW, pure): `move-cue`, `remove-cue`, `move-fx`, `remove-fx` (every place a vibe names a cell, and the voices that follow it), and `move-trigger-targets`, `remove-trigger-targets` (the two copies in `events/handlers/grid.clj:49-71` and `events/handlers/effects.clj:47-69`, now one).
- `laser-show.vibes.engine` (NEW, pure): `press`, `release`, `select`, `release-all`; each `(state event) → {:state s' :dispatch ev-or-nil}`. Private: `pick`, `switch-to`, `stop-voice`, `restore-ducked`.
- `laser-show.events.handlers.vibes` (NEW): the `"vibe"` domain: routes `:vibe/press :vibe/release :vibe/select :vibe/release-all` to the engine. One line per event, no logic.
- `laser-show.events.handlers` (existing, `events/handlers.clj:36-69`): add `"vibe"`; remove `"trigger-group"` (`:63`) (V7 A).
- `laser-show.events.handlers.triggers` (existing, `events/handlers/triggers.clj:59-114`): `action-event` gets a first clause for `{:type :vibe :role r}`, producing `{:event/type :vibe/press|:vibe/release :role r :input-time (:timestamp input-event)}` for **both** press and release (an optional `{:type :vibe-select :vibe id}` gives `:vibe/select` on press). `handle-input` skips `update-runtime` for vibe actions (probe v8). Remove `handle-group-toggle-lock`, `handle-tick`, `:trigger/tick`, `:trigger-group/toggle-lock` (V7 A).
- `laser-show.events.handlers.grid` (existing): `trigger-cue` becomes public `start-cue [state col row opts]` (opts: `:decay-beats`, `:hold?`); `handle-grid-move-cell` and `handle-grid-clear-cell` call `refs/move-cue` and `refs/remove-cue` and the shared trigger helpers. `laser-show.events.handlers.effects` (existing): `handle-effects-move-cell`, `handle-effects-clear-cell` call `refs/move-fx`, `refs/remove-fx`.
- `laser-show.animation.cue-timing` (existing): `activate-cue` (`:322-333`) takes `:decay-beats` and `:hold?` and writes them on the intent. `update-cue-timing` (`:140-184`) reads `decay-beats` and `hold?` from the run and ends a run whose accumulated beats reach `decay-beats` while `hold?` is false (`:ended? true`, as a timeline does).
- `laser-show.animation.show-time` (existing): `run-for` (`:126-144`) copies `:decay-beats` and `:hold?` from the intent to the run, as it does `:paused?`.
- `laser-show.triggers.shuffle-bag` (existing): see §7. `make [items]`; `next [bag shuffle-fn] → [item bag']`; `seeded [seed] → shuffle-fn` (Fisher–Yates over `java.util.SplittableRandom`); `sync [bag items]`. The `:shuffle-fn` leaves the data. The refill swaps its first item if it equals `:last`. The engine keeps `:seed` (the event's `:time` when the bag is made) and `:n` (refills so far) beside the bag's own keys, and passes `(bag/seeded (+ seed n))` when a refill is due, so a bag's whole future is a function of its state.
- `laser-show.state.domains` (existing): `[:config :vibes]` default (`config` domain, `:221-246`): `{:order [] :next-id 1 :library {} :settings {:quick-tap-ms 150 :lock-hold-ms 600 :hit-taps-tempo? true}}`. NEW `defstate vibe-runtime`. Remove `:groups`, `:settings` from `triggers` and `:active-groups`, `:shuffle-bags` from `trigger-runtime` (V7 A).
- `laser-show.state.persistent` (existing): `reset-session` (`:135-150`) also resets `:vibe-runtime`.
- `laser-show.state.templates` (existing): `create-starter-vibes`, `apply-starter-vibes` (only when the library is empty), called wherever `apply-starter-cue-chains` is (`app.clj:144-146, 259-261`; `state/persistent.clj:174-182`).
- `laser-show.subs` (existing): remove `trigger-group-state` (`:796-799`) (V7 A).
- Delete (V7 A): `triggers/core.clj`, `triggers/definitions.clj`, `triggers/state_machine.clj` and their tests.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:config :vibes]` (NEW) | document | Phase 3 edit events; the starter template | yes / yes |
| `[:vibe-runtime]` (NEW domain) | app state, session only. Not a document root, so history and dirty take the fast path (probe v3) | `:vibe/*` handlers | no / no |
| intent `:decay-beats`, `:hold?` in `[:playback :active-cues cell]` | app state (existing) | `activate-cue`; the release handler | no / no |
| effect cell `:active?` | document, but live (`state/history.clj:19-21, 119-125`) | vibe switch, flash | no / no |
| run `:decay-beats`, `:hold?` in `show-time/!runtime` | the clock's runtime atom (only the clock writes) | `run-for` | – |

`[:vibe-runtime]`:

```clojure
{:current :vibe-1                  ; nil until the first press or :vibe/select
 :entered-at 1000                  ; ms (event time), when the vibe began
 :rotation :active                 ; :active | :locked   (:armed arrives in Phase 6)
 :blackout? false
 :held   {:hit {:at 1000 :src :input} :accent nil :flash nil :vibe nil}   ; :src is :pad when the event came from an on-screen pad
 :voices {:hit {:cell [1 0] :run #uuid "…"} :accent nil :bed nil
          :flash {:cell [6 0] :run #uuid "…" :fx-on [[0 3]]}}
 :duck   {:hit {:cell [0 0]}}      ; what a solo flash stepped aside, to restore
 :fx-on  [[1 3]]                   ; effect cells the vibe's FX set switched on (so only those are switched off)
 :bags   {[:vibe-1 :hit] {:items [[0 0] [1 0] [2 0] [3 0]] :remaining [[3 0] [0 0] [2 0]] :last [1 0] :seed 1002 :n 1}}
 :last-hit-at 1090
 :problem nil}                     ; {:role :hit :why :empty-pool} until the next good press
```

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:vibe/press {:role :input-time}` (`:input-time` falls back to `:time` for events with no input behind them, such as a pad) | NEW | `events/handlers/triggers.clj` `action-event` (inline `:dispatch`, `events/core.clj:157-160`); the Perform pads (Phase 2) | yes |
| `:vibe/release {:role :input-time}` | NEW | the same | yes |
| `:vibe/select {:vibe id}` | NEW | an optional binding; the vibe strip (Phase 2) | yes |
| `:vibe/release-all {:only}` | NEW | blackout, CC 120/123 and disconnect (Phase 7); focus loss (Phase 2). `:only` is `nil` (everything), `:pad` or `:input`, matching a hold's `:src` | yes |
| `:show-clock/cue-ended` | yes (`events/handlers/timing.clj:122-128`) | the clock's `pending-reports` (`animation/show_time.clj:187-202`) | yes |
| `:timing/tap-tempo` | yes (`events/handlers/timing.clj:42-58`) | `:vibe/press :hit` | yes |
| `:transport/stop`, `:transport/play` | yes (`:65-78`) | blackout on and off | yes |

No new effect key. Events dispatched by a trigger carry `:source-trigger`, so `wrap-history` skips them (`events/handlers/history.clj:189`).

## 7. Data examples and invariants

### The starter vibes (for the eight starter cues, `state/templates.clj:29-45`)

```clojure
{:order [:vibe-1 :vibe-2]
 :next-id 3
 :library
 {:vibe-1 {:id :vibe-1 :name "Shapes" :color "#4A90D9" :energy 2 :allow-audience? false
           :hit    {:cells [[0 0] [1 0] [2 0] [3 0]] :select :shuffle :mode :decay :beats 1.0}
           :accent {:cells [[4 0]] :select :shuffle :mode :decay :beats 2.0}
           :flash  {:cue [6 0] :fx [] :solo? true}
           :bed nil :fx []}
  :vibe-2 {:id :vibe-2 :name "Lines" :color "#9B59B6" :energy 3 :allow-audience? false
           :hit    {:cells [[5 0] [7 0]] :select :shuffle :mode :decay :beats 1.0}
           :accent {:cells [[6 0]] :select :shuffle :mode :decay :beats 2.0}
           :flash  {:cue [4 0] :fx [] :solo? true}
           :bed nil :fx []}}
 :settings {:quick-tap-ms 150 :lock-hold-ms 600 :hit-taps-tempo? true}}
```

`:mode` is `:decay` (ends after `:beats`, or when let go after a hold), `:gate` (only while held) or `:sustain` (until replaced). `:select` is `:shuffle` or `:sequence`. There is no `:palette`: stepping the colour on each press is a tap reaction (Phase 4, V9).

### The audience rule (V6)

`audience-cell?` is true when any of these names an audience group: the cue's `:destination-zone :zone-group-id`; any item's or track's `:zone-group-id`; any `:zone-selector` effect's `:target-zone` on an item, group or track (the resolution order in `routing/zone_effects.clj:127-156`). An **audience group** is `:crowd` (`state/domains.clj:359-362`), or any zone group listed by a projector or virtual projector tagged `:crowd-scanning` (`state/domains.clj:271`). `playable-cells` leaves such a cell out of every pick unless the vibe has `:allow-audience? true`; Phase 3 shows why in the editor.

| Cue | Projectors | `audience-cell?` |
|---|---|---|
| `:destination-zone {:zone-group-id :all}`, no items rerouted | none tagged | false |
| `:destination-zone {:zone-group-id :crowd}` | any | **true** |
| destination `:all`; one item has a `:zone-selector` effect with `:target-zone :front`, and projector `p3` (zone groups `[:front]`) is tagged `:crowd-scanning` | | **true** |
| destination `:all`, and `p3` (zone groups `[:all]`) is tagged `:crowd-scanning` | | **true**: it does reach the crowd projector. The lint names `p3` and `:all` |

### Press and release (state before, event, what changes)

| # | Before | Event (time in ms) | After (only what changes) | Dispatch |
|---|---|---|---|---|
| 1 | no vibe current | HIT press 1000 | `:current :vibe-1`, `:entered-at 1000`. Bag made from the 4 playable cells. Say it picks `[1 0]`: intent `{:run r1 :decay-beats 1.0 :hold? true}`, `:playing? true`. `:held {:hit {:at 1000 :src :input}}`, `:voices {:hit {:cell [1 0] :run r1}}`, `:last-hit-at 1000` | `:timing/tap-tempo` (only when `:hit-taps-tempo?`) |
| 2 | after 1 | HIT release 1090 (90 ms) | intent `:hold? false`; `:held :hit` nil; voice kept. The clock ends the run at 1 beat | – |
| 3 | after 1 | HIT release 1400 (400 ms) | `deactivate-cue [1 0]`; `:voices :hit` nil; `:playing?` false if no other cue plays | – |
| 4 | voice `[1 0]` r1 still decaying | HIT press 1500 | `[1 0]` deactivated (r1 is still its run); next from the bag, `[3 0]`, started as r2 | `:timing/tap-tempo` |
| 5 | HIT voice playing | ACCENT press 1520 | own voice `[4 0]`, intent `{:run r3 :decay-beats 2.0 :hold? true}`; HIT untouched | none |
| 6 | HIT mode `:sustain` voice `[0 0]`, accent decay voice `[4 0]` | FLASH press (`:solo? true`, cue `[6 0]`) | `[4 0]` dropped (a decaying voice is not restored); `[0 0]` stopped and remembered in `:duck`; `[6 0]` started with `:hold? true` | none |
| 7 | after 6 | FLASH release | `[6 0]` stopped; `[0 0]` started again as a new run; FX it switched on are switched off | none |
| 8 | any | VIBE press 1000, release 1200 | `:current` is the next id in `:order` (wraps); old bed and sustain voices stopped; old FX set off, new FX set on; new bed started; `:entered-at 1200`; bags kept | none |
| 9 | any | VIBE press 1000, release 1700 | `:rotation` toggles `:locked`; the vibe doesn't change | none |
| 10 | any | BLACKOUT press | every voice stopped, `:held` cleared, FX sets off, `:blackout? true`. HIT, ACCENT, FLASH, VIBE presses afterwards change nothing | `:transport/stop` |
| 11 | blackout on | BLACKOUT press | `:blackout? false`; the bed starts again | `:transport/play` |
| 12 | pool of 3 where 2 cells are empty | HIT press | the bag is over the one playable cell; with none: nothing plays and `:problem {:role :hit :why :empty-pool}` | none |

### The clock's side (the decay)

| Run (120 BPM) | At | Result |
|---|---|---|
| `{:decay-beats 1.0 :hold? false}` | 30 ticks of 1/60 s (1.0 beat) | `:ended? true`; the report `{:event/type :show-clock/cue-ended :cell c :run r}` goes out |
| `{:decay-beats 1.0 :hold? true}` | any time | never ends |
| `:hold?` set false at beat 3.2 | the next tick | ends |
| `{:decay-beats 1.0}` and BPM 60 from beat 0.5 on | | needs 0.5 more beats at the slower tempo, so it ends later in wall time |
| a run without `:decay-beats` | any time | unchanged from today |

### The bag (`triggers.shuffle-bag`)

| Items | Shuffles the refills return | Picks |
|---|---|---|
| `[:a :b :c]` | `[:a :b :c]`, then `[:c :b :a]` | `a b c` then the second refill starts with `:c`, which equals the last pick, so first and last swap: `a b c a b c`. No `c c` |
| `[:a :b]` | any | alternates; never `a a` |
| `[:a]` | any | `a a a …` (nothing to avoid) |
| `[]` | any | `nil` |
| `[:a :b :c]`, then the pool changes to `[:b :c :d]` after `:a` was picked | `sync` | `:last` kept, `:remaining` loses what is gone, gains `:d` |

### The references (`vibes.refs`)

```clojure
(refs/move-cue state [0 0] [2 3])
;; hit and accent cells, :flash :cue and :bed that were [0 0] are now [2 3];
;; order kept; a pool that already had [2 3] doesn't get it twice
(refs/remove-cue state [0 0])
;; removed from every pool; :flash :cue and :bed that were [0 0] become nil
;; a cue cell [0 0] and an effect cell [0 0] are different things: move-cue
;; never touches :fx, move-fx never touches :hit
```

### Invariants

1. A press plays only cells that have content, do not chase MTC, and are not audience-capable (unless the vibe allows them). With none, nothing changes except `:problem`.
2. For a pool of two or more, a role never plays the same cell twice in a row: 10,000 presses, pools of 2 to 8.
3. Inside one bag cycle every playable cell plays exactly once.
4. A role has at most one voice. A press stops the voice it started only if the intent's run is still that voice's run, so it never stops a cue someone else started.
5. A release for a role that isn't held changes nothing.
6. During blackout every press is ignored, releases still clear holds, and nothing plays.
7. HIT and ACCENT leave every document root identical. FLASH, BLACKOUT and a vibe switch change at most effect cells' `:active?` (the FX sets and the flash FX), which is neither undoable nor unsaved.
8. A run with `:hold? true` never ends by decay. A run with `:hold? false` ends at the first tick where its beats reach `:decay-beats`. A timeline still ends at its length.
9. If `:current` names a vibe that is gone (undo, delete), the engine uses the first id of `:order`; with none, `:problem {:why :no-vibes}`. No exception.
10. After a move or a clear, no vibe names the old cell, and the pools keep their order.
11. The same state, event and `:time` give the same result: randomness comes from the bag's `:seed` and `:n`, never from a global.
12. A release undoes what its press started, whatever the current vibe is by then: a flash pressed in one vibe and released after a switch still stops its own cue and effect cells.
13. A press costs about what `:grid/activate-cell` costs (probe v5, 8–10 µs median). *Unverified for the new code*: re-run v5 against `:vibe/press` when the phase lands.

## 8. Tests (named test vars + fixtures)

States come from `domains/build-initial-state`, `templates/apply-starter-cue-chains` and `apply-starter-vibes`, with the real handlers through `events/event-handler`, as `test/laser_show/animation/show_time_test.clj:23-57` does. Clock steps use `show-time/step` by hand.

- `laser-show.triggers.shuffle-bag-test` (existing, migrated): `emits-each-item-once` (new signature, same assertions); NEW `never-repeats-back-to-back-test` (fails today: probe v1), `refill-guard-swaps-a-repeat-test` (the table above), `same-seed-same-order-test`, `edge-sizes-test`, `sync-keeps-last-and-drops-gone-test`.
- `laser-show.vibes.refs-test` (NEW): `move-cue-rewrites-every-role-test`, `move-dedupes-test`, `remove-cue-clears-every-role-test`, `cue-and-effect-grids-are-separate-test`, `trigger-targets-follow-a-move-test`.
- `laser-show.vibes.library-test` (NEW): `playable-cells-skips-empty-and-chasing-test`, `current-vibe-falls-back-to-first-test`, `audience-cell-test` (the table above: destination, item `:zone-group-id`, `:zone-selector`, tagged projector), `audience-cells-never-play-unless-allowed-test`.
- `laser-show.vibes.engine-test` (NEW): one test per row 1–12 above (`hit-press-starts-a-pool-cue-test`, `quick-tap-keeps-decaying-test`, `long-hold-cuts-on-release-test`, `new-press-stops-the-old-cue-test`, `accent-has-its-own-voice-test`, `solo-flash-ducks-and-restores-test`, `flash-fx-switches-and-restores-test`, `vibe-tap-next-wraps-test`, `vibe-hold-locks-test`, `blackout-is-sticky-test`, `blackout-off-restores-the-bed-test`, `empty-pool-reports-a-problem-test`), plus `gate-mode-stops-on-any-release-test`, `sustain-mode-stays-until-replaced-test`, `releasing-hit-leaves-accent-playing-test`, `flash-release-stops-its-own-voice-after-a-vibe-change-test`, `vibe-tap-next-keeps-the-lock-test`, `sequence-select-walks-in-order-test`, `never-stops-someone-elses-cue-test`, `vanished-vibe-falls-back-test`, `same-input-same-output-test`, `stale-voices-are-forgotten-test`.
- `laser-show.vibes.engine-fuzz-test` (NEW): a seeded generator of 2,000 events (presses and releases of every role, vibe switches, blackouts, deletes and moves of cells and vibes) against the starter state; after every step it checks invariants 1–12. A failure prints the seed and the shortest failing prefix. Phase 7 widens the generator and adds a long soak.
- `laser-show.events.handlers.vibes-test` (NEW): `vibe-domain-is-routed-test`, `document-roots-stay-identical-test` (invariant 7, with `history/document-roots-identical?`), `fx-set-switch-is-live-not-undoable-test` (`undoable-changed?` and `persisted-changed?` both false).
- `laser-show.events.handlers.triggers-test` (existing): NEW `vibe-action-maps-press-and-release-test`, `vibe-action-skips-runtime-bookkeeping-test` (probe v8), `vibe-action-carries-the-input-timestamp-test` (probe v2); remove `trigger-group-lock-and-expiry`, `trigger-group-events-are-routed` (V7 A).
- `laser-show.animation.cue-timing-test` (existing): NEW `decay-ends-a-plain-run-test` (probe v7), `hold-suspends-decay-test`, `decay-follows-tempo-test`, `activate-cue-writes-decay-keys-test`. Existing `non-timeline-cue-unchanged-test` (`:91`) must still pass.
- `laser-show.animation.show-time-test` (existing): NEW `decayed-run-reports-cue-ended-test`, `release-before-decay-lets-the-run-finish-test`. Existing `report-for-an-old-run-changes-nothing-test` (`:249`) covers a stale report.
- `laser-show.events.handlers.grid-trigger-test` (existing, `:41-66`) and the effects test (`:68`): NEW `grid-cell-move-follows-vibe-pools-test`, `grid-cell-clear-leaves-vibe-pools-test`, `effects-cell-move-and-clear-follow-fx-FX sets-test`. The three existing trigger-map tests must pass unchanged.
- `laser-show.state.persistent-test` (existing): NEW `vibes-survive-save-and-load-test`, `old-project-gets-the-default-vibes-test`, `new-project-has-starter-vibes-test`, `session-reset-clears-vibe-runtime-test` (probe v3).
- `laser-show.state.history-test` (existing): NEW `config-vibes-is-undoable-content-test`.
- `laser-show.state.templates-test` (NEW): `starter-vibes-name-starter-cues-test`.

Run: `clj -M:mac-arm:test shuffle-bag vibes triggers cue-timing show-time grid-trigger effects-cell persistent history templates`, then the full unit suite (`clj -M:mac-arm:test`). No UI change, so the UI suite isn't needed until Phase 2.

## 9. Out of scope / alternatives considered

- **Any screen**: Phase 2. **Editing vibes**: Phase 3. **Tap reactions** (presses that move effects; hue stepping is one of them): Phase 4. **Faders**: Phase 5. **Auto-advance, one tap tempo**: Phase 6. **Audience marks on the screen, show lock, stuck-key guards**: Phase 7.
- **Decay in `:dispatch-later`**: rejected: a thread per press, and wall time, not beats.
- **The engine outside app state** (a runtime atom like show time): rejected: handlers would stop being pure, and a vibe changes a few times a second, not 60 (`docs/CLJFX_STATE_MANAGEMENT.md`). Fast data (faders) already bypasses app state.
- **References by a stable cue id instead of `[col row]`**: rejected for now: cue chains have no id of their own, and adding one touches saving, the clipboard and the templates. `vibes.refs` does what the trigger map already does for moves.
- **Making `:apply-preset` work** (`events/handlers/triggers.clj:85-86`): unrelated; noted as a dead branch.

## 10. As built (2026-11-18)

Committed as `9177b1e` on top of `67735d0` (UX Phase 0, `e2bce92`, is in it), after review. §1–9 cite `b1da0f2`; the lines below are at `9177b1e`.

**Where it is now** (paths under `src/laser_show/`)
- `vibes/library.clj` (NEW): `empty-library` `:21` (the domain default), `settings` `:25` (a key a file lacks gets its default), `vibe` `:30`, `current-id` `:35` (the first of `:order` when `:current` names no vibe), `current-vibe` `:45`, `next-id` `:48` (wraps), `audience-groups` `:59`, private `zone-group-ids` `:72`, `audience-cell?` `:90` (also `[state cell groups]`), `playable-cells` `:97`.
- `vibes/refs.clj` (NEW): `move-cue` `:74`, `remove-cue` `:101`, `move-fx` `:118`, `remove-fx` `:136`, `move-trigger-targets` `:154`, `remove-trigger-targets` `:167`; the two copies in `grid.clj` and `effects.clj` are gone. A move renames in order and names a cell once; it also moves the voices, what a solo flash keeps waiting, the bags (their cycle kept) and `:fx-on`. A move onto the same cell changes nothing.
- `vibes/engine.clj` (NEW): `press` `:373`, `release` `:387`, `select` `:399`, `release-all` `:407`. Private: `pick` `:171`, `switch-to` `:150`, `stop-voice` `:48`, `restore-ducked` `:255`, and `start` `:38`, `forget-stale` `:55`, `leave-look` `:105`, `start-bed` `:124`, `enter-look` `:135`, `ensure-current` `:159`, `play-next` `:182`, `press-pool` `:193`, `release-pool` `:228`, `release-flash` `:286`, `duck` `:295`, `press-flash` `:308`, `release-vibe` `:337`, `toggle-blackout` `:349`.
- `events/handlers/vibes.clj` (NEW): `handle` `:10`, routed at `events/handlers.clj:64`; `"trigger-group"` is gone.
- `events/handlers/triggers.clj`: `vibe-action?` `:60`, `vibe-event` `:64`, the first clause of `action-event` `:80-84`; `handle-input` `:140` leaves `[:trigger-runtime]` as it is for a vibe binding. `handle-group-toggle-lock`, `handle-tick`, `:trigger/tick` and `:trigger-group/toggle-lock` are gone.
- `events/handlers/grid.clj`: public `start-cue` `:33` (was private `trigger-cue`); move `:139-140` and clear `:173-174` call `vibes.refs`. `events/handlers/effects.clj`: clear `:53-54`, move `:82-83`.
- `events/handlers/timing.clj`: `handle-timing-tap-tempo` `:48` keeps the last `tap-history` (8, `:42`) taps, all that `:timing/calculate-bpm` reads (`events/core.clj:178`). HIT taps on every press, and the history used to grow for as long as the presses came under 2 s apart; the BPM a tap gives is unchanged. The report path is unchanged (`:122`, docstring only).
- `animation/cue_timing.clj`: `decay-epsilon` `:23`; `update-cue-timing` `:124` ends a run with `:decay-beats` once its beats reach them while `:hold?` is false (`:185-186, 193`); `activate-cue` `:331` writes both keys (`:343-344`). `animation/show_time.clj`: `run-for` `:127` copies them (`:140-141`).
- `state/domains.clj`: `[:config :vibes]` default `:266`; NEW `vibe-runtime` `:107`; `triggers` `:91` and `trigger-runtime` `:96` without the group keys (the `triggers` docstring no longer says "Persisted": probe v3); the intent's doc `:79`. `state/persistent.clj`: `reset-session` `:135` resets `:vibe-runtime` (`:143`); `new-project-state` `:176` applies the starter vibes (`:183`). `state/templates.clj`: `create-starter-vibes` `:70` (§7's data), `apply-starter-vibes` `:91` (only with no vibes; keeps the settings), called at `app.clj:146, 262`.
- `subs.clj`: `trigger-group-state` is gone (it followed `active-trigger-count` `:791`). Deleted: `triggers/core.clj`, `definitions.clj`, `state_machine.clj` and their three tests.
- `triggers/shuffle_bag.clj`: `make` `:13`, `seeded` `:29`, private `refill` `:45` (the guard), `next` `:55` (takes the shuffle fn), `sync` `:68`.

**State** as §5, plus: a HIT or ACCENT voice keeps the `:mode` it was pressed with; the flash's keeps `:fx` (asked for), `:fx-on` (switched on by it) and `:solo?`, and keeps them without `:cell` and `:run` once its cue is gone, until its release. `:duck` holds `{:cell :mode}` (stepped aside) or `{:mode}` (a HIT or ACCENT pressed during the solo flash). NEW `:started` counts the cues the engine started this session.

**Differences from the plan**

| Plan | As built | Why |
|---|---|---|
| `start-cue [state col row opts]`, opts `:decay-beats`, `:hold?` | also `:run`, the run's id; and a 3-arity without opts | the engine names its runs (next row) |
| run ids: not said (the grid uses `random-uuid`) | `(UUID. t n)`: the event's ms and NEW `[:vibe-runtime :started]` (`engine.clj:38-46`) | invariant 11 with run ids included. The counter resets with the session; a grid start keeps a random id, which can't equal one of these (its variant bits) |
| a run ends when its beats reach `:decay-beats` | within `decay-epsilon`, 1e-9 beats (`cue_timing.clj:23, 185-186`); the run keeps its beats (a timeline's end is clamped to its length) | 30 ticks of 1/60 s at 120 BPM add up to 0.9999999999999996 beats: without it a 1-beat decay ends a tick late |
| `run-for` copies `:decay-beats` and `:hold?` | only from an intent with `:decay-beats` | a held flash (`{:run r :hold? true}`) has no decay; its run is as before |
| — (not defined) | HIT or ACCENT pressed while a solo flash is held: nothing is picked and the bag doesn't move; `:held` is recorded and HIT still taps. A `:decay` press plays nothing; a `:gate` or `:sustain` one waits in `:duck` and picks when the flash lets go, as the vibe current then has the role (a `:gate` one let go of first is dropped) | a pick that never plays costs a cell its turn (invariant 3; review). beyond-vibe starts its newest rhythm cue at the release (`janitor.rs:27-40`); a decay would have ended by then |
| row 7: the ducked voices start again | only on a cell nothing plays now. FLASH pressed again without a release lets the first go and keeps what it ducked for the last release; a non-solo flash press starts what an earlier solo one ducked | fuzz seed 4242: a re-press restored a ducked HIT onto a cell the grid had taken, then ducked it, stopping the grid's cue (invariant 4) |
| row 8: the new bed starts | not on a cell that already plays | stress seeds 38 and 54: the bed took over the grid's cue, and a solo flash then stopped it (invariant 4) |
| row 11: the bed starts again | and the FX set comes back on | blackout switched it off (row 10) |
| `:vibe/select` | during blackout the vibe becomes current, and its look starts when blackout ends | nothing plays (invariant 6); the strip (Phase 2) can show what comes back |
| `:vibe/release-all {:only}` | a held HIT or ACCENT is cut as a long hold (`:sustain` stays), FLASH lets go after them, a held VIBE neither switches nor locks | a stuck key mustn't change the look |
| audience groups: `:crowd`, and those of a tagged projector or virtual projector | also those of a virtual projector whose parent is tagged; a zone selector counts on or off; a destination with no group, missing or `nil`, is `:all` | the parent is the hardware that scans; a selector can be switched on mid-show; the routing sends a cue with no group to `:all` (`routing/zone_effects.clj:148-152`), and `cue_chain.clj:190` can write `nil` (review) |
| first VIBE tap with nothing current | the second vibe | the first one is current by default (invariant 9) |
| tap tempo: Phase 6 | the tap history is capped at 8 now (`events/handlers/timing.clj:42-63`) | with HIT tapping it grew by one entry per press for a whole set (review) |
| `effects-cell-move-and-clear-follow-fx-FX sets-test` | `effects-cell-move-and-clear-follow-fx-sets-test` | a typo in §8 |
| the fuzz: presses, releases, switches, blackouts, deletes and moves | also ticks and their reports, grid clicks (half of them restart a cell a voice plays, half of those followed by BLACKOUT), effect-cell moves and clears, vibes put back and reordered. Invariant 1 comes from its own `may-play?`, not `playable-cells`. `VIBE_FUZZ_SEED=<seed>` runs one seed, `VIBE_FUZZ_SEEDS=<n>` seeds 1 to n | M1 and M4 survived the first mutation run (below) |

**Found, not done**
- Phase 3: effect cells a vibe switched on are saved with `:active? true`, as the whole of `[:chains :effect-chains]` is (`state/persistent.clj:60`). The field is live, so neither undoable nor unsaved, but it is in the file, and after a load or New `[:vibe-runtime :fx-on]` is empty (`:143`): leaving that vibe never switches them off. The starter vibes have no FX sets, so nothing shows this until Phase 3 can edit them.
- Phase 2: a grid click during blackout starts a cue and sets `:playing? true` (`animation/cue_timing.clj:345`). Light returns while `[:vibe-runtime :blackout?]` stays true and the vibe keys stay dead; the next BLACKOUT press "turns it off" and dispatches `:transport/play`. That is V2 A ("a click on the grid during blackout still starts a cue"), but the Perform screen must not show "dark" from `:blackout?` alone.
- Phase 6: the tap HIT sends is timed when it is handled (the co-effect `:time`, probe v2), as the TAP button's is; the press itself is timed by the input.
- Probe v1 (`probes/vibe_probes_test.clj:92-95`) calls the one-argument `bag/next` and now throws an `ArityException`. That file is another session's and is left alone; v1's "after" is `never-repeats-back-to-back-test`.
- Invariant 13, measured (verification): a vibe press costs about 2.5 times v5's momentary cue, 4 times with the tap (two more events), all under 0.11 ms p99.
- K-15 (learn also fires the old binding): not fixed, as the header says.

**Tests**: 62 new, 5 deleted (868 → 925)
- NEW `laser-show.vibes.engine-test` (25): rows 1–12 of §7, the ten more named in §8, and `hit-during-a-solo-flash-waits-for-its-release-test` (the bag doesn't move, `:held` and the tap do), `flash-and-fx-set-share-an-effect-cell-test`, `release-all-lets-go-of-one-source-test`. `never-stops-someone-elses-cue-test` also covers blackout and leaving a vanished vibe's look, the two paths that stop voices without forgetting stale ones first.
- NEW `laser-show.vibes.library-test` (4), `vibes.refs-test` (5), `events.handlers.vibes-test` (3), `state.templates-test` (1), as named in §8. `audience-cell-test` has §7's four rows, items inside groups, folders, a track's zone selector, virtual projectors, and a destination with no group.
- NEW `laser-show.vibes.engine-fuzz-test/engine-invariants-hold-for-random-events-test`: seeds 20261118, 7 and 4242, 2,000 steps each through `events/handle-event-pipeline` and `show-time/step`, checking invariants 1–12 after every step, and that every pick starts its cell. Its world: the starter cues (sent to `:center`), four vibes with every mode, beds, FX sets, a solo and a non-solo flash, a `:crowd` cue, a cue with no zone group, a timeline, one that follows timecode, and a `:crowd-scanning` projector in `:all`. A failure prints the seed and the steps up to it.
- Extended: `triggers.shuffle-bag-test` (+5), `animation.cue-timing-test` (+4), `animation.show-time-test` (+2), `events.handlers.grid-trigger-test` (+3), `events.handlers.triggers-test` (+3, and the 2 group tests gone), `state.persistent-test` (+4), `state.history-test` (+1), `events.timing-handlers-test` (+1: `tap-history-keeps-the-last-8-test`, 20 taps 500 ms apart keep the last 8, and the real `:timing/calculate-bpm` effect gives 120.0 from them as from all 20, and the same BPM for a drifting tempo). Deleted with their namespaces: `triggers.core-test`, `definitions-test`, `state-machine-test`.

**Red first**
- The new tests against `67735d0`'s `src/`: `shuffle-bag-test` doesn't compile (no `bag/seeded`); `cue-timing-test` with `history-test`: 31 tests, 150 assertions, 5 failures (the four decay tests, `activate-cue-writes-decay-keys-test` twice). `config-vibes-is-undoable-content-test` passed, as §3 says: the document policy needs no change. Every other new or extended namespace failed to load (`templates/apply-starter-vibes`, `laser-show.vibes.*` missing).
- The probes at `67735d0`: v1, a pool of 2 repeats 25.0 % of picks (3: 10.9 %, 4: 6.2 %, 8: 1.5 %); v7, a plain cue at 39.97 beats after 20 s, never ended; v8, six unmapped notes leave 6 held inputs, 6 active triggers and 6 taps.
- Review fixes: the no-group row of `audience-cell-test` failed (1 failure) before `zone-group-ids` used `(or … :all)`; `hit-during-a-solo-flash-waits-for-its-release-test` failed 5 times against the engine before the change, and the fuzz caught that engine on all three seeds (M7); `tap-history-keeps-the-last-8-test` failed twice before the cap (all 20 taps kept), while its BPM checks passed, as they should: the effect reads the last 8 either way.
- The fuzz's own first runs found the two engine bugs in the table above, and four mistakes in its checks: invariant 11 also compared what other handlers did with the events it dispatched (Stop's restart has a random id); a move onto the same cell; a pool that changes mid-cycle; two roles waiting on one solo flash picking the same cell, which starts once for both.

**Mutants**: each run is the fuzz's three seeds and the engine, library, refs and bag tests (41 tests), with the mutated file ahead of `src/` on the classpath.

| # | Mutant | Fuzz | Unit tests failing |
|---|---|---|---|
| M1 | `stop-voice` stops without checking the run is still its own (`engine.clj:51`) | 3 of 3 seeds, invariant 4 | `never-stops-someone-elses-cue-test` (2) |
| M2 | no refill guard (`shuffle_bag.clj:45-53`) | 3 of 3, invariant 2 | `never-repeats-back-to-back-test` (7), `refill-guard-swaps-a-repeat-test` (2) |
| M3 | every HIT or ACCENT release is a hold | 3 of 3, invariant 12 | 9 engine tests (13 failures) |
| M4 | `playable-cells` without the audience rule | 3 of 3, invariant 1 | `audience-cells-never-play-unless-allowed-test` (2) |
| M5 | blackout keeps the holds | 3 of 3, invariant 6 | `blackout-is-sticky-test` (2) |
| M6 | a destination with no group isn't `:all` (review 1) | 2 of 3, invariant 1 | `audience-cell-test` (1) |
| M7 | the engine before review 2: a press during a solo flash picks | 3 of 3, invariant 3 | `hit-during-a-solo-flash-waits-for-its-release-test` (5) |

In the first run M1 and M4 survived the fuzz. M1: every path but blackout (and switching out of a vanished vibe) forgets stale voices before it stops one, and the walk never had a stale voice at a blackout; now it restarts cells that voices play from the grid, half the time followed by BLACKOUT. M4: the check took "playable" from `playable-cells`, the function under test; now it has its own `may-play?` and compares `playable-cells` with it at every press.

**Verification, 2026-11-19, macOS arm64, JDK 25**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test shuffle-bag vibes triggers cue-timing show-time grid-trigger effects persistent history templates` | 210 tests, 1,165 assertions, 0 failures |
| `clj -M:mac-arm:test -n` vibes.engine-fuzz, vibes.engine, vibes.library, vibes.refs, triggers.shuffle-bag | 41 tests, 267 assertions, 0 failures (7 s) |
| `VIBE_FUZZ_SEEDS=200 clj -M:mac-arm:test -n laser-show.vibes.engine-fuzz-test` | seeds 1–200, 2,000 steps each: 0 failures (22 s) |
| `clj -M:mac-arm:test` (full unit suite) | 925 tests, 32,864 assertions, 0 failures (31 s), after the tap cap. Before it: 924 tests, 32,859 and 32,861 assertions in two runs, 0 failures; the count moves between runs outside the new tests (the vibe and bag namespaces count 267 every run) |
| `clj -M:mac-arm:test timing tap-tempo` | 57 tests, 162 assertions, 0 failures |
| `clj -M:test-ui:mac-arm:test-ui-headless` (full UI suite) | 71 tests, 264 assertions, 0 failures (201 s), before a `:doc` string change in `state/domains.clj` and the tap cap; after the cap, `tap-tempo-button-records-taps`: 1 test, 2 assertions, 0 failures |
| `clj -M:kondo --lint` on every touched file | 0 errors; 5 warnings, all there before (a redundant `let` in `triggers.clj` `update-runtime`, an unused require in `triggers/matcher.clj`, three unknown keys in `history_test.clj`) |
| Invariant 13: probe v5's loop (10,000 events, press and release) in one JVM, warm runs 2 and 3, machine load 5–15 | momentary cue 4.9 µs median (p99 20 µs); vibe HIT 12 µs (p99 52–55 µs); vibe HIT with the tap 19–20 µs (p99 104–108 µs) |
