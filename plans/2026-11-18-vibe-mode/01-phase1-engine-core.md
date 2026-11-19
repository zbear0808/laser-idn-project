# Phase 1: the vibe model and its engine (no UI)

**Written:** 2026-11-18   **Written for commit:** `b1da0f2` (`master`)
**Status:** Proposed. Needs V1, V2, V3, V6, V7 (`00-overview.md` §7)   **Last verified against:** `b1da0f2` (probes v1–v10, v12 in `00-overview.md` §3)
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
