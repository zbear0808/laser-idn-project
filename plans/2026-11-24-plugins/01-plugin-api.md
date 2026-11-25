# 01 · The plugin contract (plugin API 1)

This page is the contract a plugin author and the app share. `docs/PLUGINS.md` (NEW, written along with P1–P5) is its user-facing copy. Every phase in `02` cites the sections here.

## 1. The plugin file

A plugin is **one UTF-8 `.clj` file**: in the plugins folder (`<dir>/*.clj`; hidden and `._` files are ignored, as for add-ons, `output/addons.clj:63-74`), or a show script's `:source`. Its first form is an `ns` form with an attr-map, the **manifest**. The manifest is read with SCI's parser (`sci/reader`, `sci/parse-next`) and **is not evaluated** until it passes every check.

```clojure
(ns bar-flash
  "Flash cue [0 0] on the first beat of every bar."
  {:plugin/name "Bar flash"
   :plugin/api 1
   :plugin/version "0.1.0"}
  (:require [laser-show.plugin :as p]))

(p/on :bar (fn [_] (p/dispatch! {:event/type :grid/trigger-cell :col 0 :row 0})))
```

| Manifest key | Required? | Value |
|---|---|---|
| `:plugin/api` | yes | `1` |
| `:plugin/name` | no | Shown in Settings. Defaults to the file stem (or the script id) |
| `:plugin/version` | no | A string, shown in Settings |
| `:plugin/description` | no | Defaults to the ns docstring |

**Id:** `:machine/<stem>`, where the stem is the file name without `.clj`, lower-cased, with `_` → `-` (`bar_flash.clj` → `:machine/bar-flash`). A show script's id is `:show/<script :id>`.

| File | Result (status `:state`, `:error :message`) |
|---|---|
| The example above | `:running` |
| First form isn't `(ns …)` | `:failed` "The first form must be (ns …) with a {:plugin/api 1} map" |
| `{:plugin/api 2}` | `:failed` "Needs plugin API 2; this app has API 1" |
| `(:require [clojure.java.io :as io])` | `:failed` "Plugins can't use clojure.java.io. They can require laser-show.plugin, clojure.string, clojure.set, clojure.walk, clojure.edn" |
| Unbalanced paren on line 9 | `:failed` "Can't read the file: EOF while reading, starting at line 9" with `:line 9` |
| Larger than 256 KiB | `:failed` "The file is larger than 256 KiB" |
| `bar-flash.clj` next to `bar_flash.clj` | the one later by name: `:failed` "bar_flash.clj already uses the id :machine/bar-flash" |
| Top level throws on line 7 | `:failed` "Unable to resolve symbol: trigger" with `:line 7 :column 4` |
| Top level takes > 2000 ms | `:failed` "Loading took longer than 2 s" |

## 2. The `laser-show.plugin` namespace

These are the only host functions a plugin can reach. Each is a Clojure fn copied into the SCI ctx (`sci/copy-var`), and each knows its calling plugin through a SCI dynamic var bound by the runner.

| Fn | Phase | Returns / does |
|---|---|---|
| `(p/plugin)` | P1 | `{:id :machine/bar-flash :name "Bar flash" :version "0.1.0" :origin :machine}` |
| `(p/log & xs)`; `print`, `println`, `prn`, `pr` | P1 | Append a line to the plugin's console (`str` of the args, cut at 500 chars) and to the app log under logger `laser-show.plugin.<id>` |
| `(p/state)`, `(p/state path)` | P1 | The state view (§5), or `(get-in view path)` |
| `(p/clock)` | P1 | `{:playing? true :beats 128.004 :beat 128 :beat-in-bar 0 :bar 32 :phase 0.004 :bpm 124.0 :tick 51234}` from `show-time/latest` and `show-time/global-beats` (`animation/show_time.clj:396-399`, `:331-334`). `{:playing? false …}` before the first tick |
| `(p/dispatch! event)` | P2 | `:queued`, or `{:refused "reason"}` from the gate (§3.1). Refusals later in the pipeline go to the console |
| `(p/actions)` | P2 | The catalog as data: `{type {:keys {k "description"} :doc "…"}}` |
| `(p/on hook f)` → id; `(p/off id)` | P3 | Register `f` for a hook (§4.1). `f` gets one map, and its return value is ignored |
| `(p/watch path f)` → id | P3 | `f` gets `{:path :old :new}` when the view's value at `path` changes (§4.2) |
| `(p/every spec f)`, `(p/after spec f)` → id; `(p/cancel id)` | P3 | Timers. `spec` is `{:ms n}` (n ≥ 10) or `{:beats n}` (n > 0) (§4.5) |
| `(p/control! key value)` | P5 | A virtual fader at input-store key `[:plugin <id> key]`. `value` is clamped to 0–1. It is learnable (§3.7) |
| `(p/press! key)`, `(p/release! key)` | P5 | A virtual button, `:trigger` / `:trigger-release` with `:source :plugin :id [<id> key]`. It is learnable and bindable in Configure Triggers |

## 3. Actions

### 3.1 The gate (`laser-show.plugins.gate/admit`, NEW, pure, on `plugin-runner`)

`(admit catalog event {:id :generation} now-ms bucket)` returns either `[:admit event' bucket']` or `[:refuse reason bucket']`. Its steps, in order:

1. `event` must be a map whose `:event/type` is a qualified keyword. Otherwise: "An action is a map with :event/type".
2. The type must be in the catalog. Otherwise: "<type> isn't available to plugins" (`p/actions` lists those that are).
3. Every key other than `:event/type` must be in the entry's `:keys`. Otherwise: "Unknown key <k> for <type>".
4. Every required key must be present, and every value must pass its check. Otherwise the check's message, e.g. ":col must be a whole number ≥ 0".
5. The token bucket (100/s refill, capacity 200) must have a token. Otherwise: "Rate limited: more than 100 actions a second". This is counted, with one console line per second.
6. Stamp the event: `:plugin/source id`, `:plugin/generation n`, the entry's `:stamps` (`:input-time now-ms`), and for entries with `:edit? true`, `:history/coalesce-key [:plugin id]` (PL-12).

`p/dispatch!` then calls `events/dispatch!` (`events/core.clj:811-835`) with `event'`.

### 3.2 A catalog entry (`laser-show.plugins.catalog/catalog`, NEW)

```clojure
{:grid/trigger-cell
 {:doc "Play or stop the cue at a grid cell, as a trigger binding does"
  :keys {:col {:check :nat-int :doc "column, from 0"}
         :row {:check :nat-int :doc "row, from 0"}}
  :required #{:col :row}
  :lights? true                 ; refused while stopped, in BLACKOUT or paused (3.5)
  :audience :cue-cell}          ; refused if the cell can reach the audience (3.5)
 :timing/tap-tempo
 {:doc "Tap the tempo, as the TAP button does"
  :keys {} :stamps #{:input-time}}
 :vibe/release
 {:doc "Let go of a vibe key" :keys {:role {:check :vibe-role}} :required #{:role}
  :stamps #{:input-time} :releases? true}}   ; always allowed: making dark (3.5)
```

The checks are named, not fns, so `p/actions` can print them: `:nat-int`, `:bpm` (a number from 40 to 300, the tap tempo's range, `triggers/tap_tempo.clj:25-44`), `:vibe-role` (`#{:hit :accent :flash :vibe :blackout}`), `:some` (not nil), `:uuid`, `:string<=200`, `:unit` (0–1).

### 3.3 The P2 catalog (performance actions)

Each payload is the one the trigger bindings already send (`events/handlers/triggers.clj:106-155`), so the handlers need no changes.

| Event | Keys | Flags | Handler |
|---|---|---|---|
| `:grid/trigger-cell` | `:col :row` | lights, audience `:cue-cell` | `events/handlers/grid.clj:59-79` |
| `:grid/activate-cell` | `:col :row` | lights, audience `:cue-cell`, held (paired with release) | `:81-86` |
| `:grid/release-cell` | `:col :row` | releases | `:88-93` |
| `:effects/toggle-cell` | `:col :row` | lights, audience `:fx-cell` | `events/handlers/effects.clj:29-50` |
| `:vibe/press` | `:role` | lights, except `:blackout` (3.5); stamps `:input-time`; held | `vibes/engine.clj:496-512` |
| `:vibe/release` | `:role` | releases; stamps `:input-time` | `:538-562` |
| `:vibe/select` | `:vibe` (`:some`) | lights; stamps `:input-time` | `:564-570` |
| `:vibe/release-all` | none | releases | `:572-583` |
| `:transport/stop` | none | releases | `events/handlers/timing.clj:92-101` |
| `:transport/retrigger` | none | lights | `:103-110` |
| `:timing/tap-tempo` | none | stamps `:input-time` | `:45-61` |
| `:timing/set-bpm` | `:bpm` (`:bpm`) | — | `:40-43` |
| `:timeline/play` | `:col :row` | lights, audience `:cue-cell` | `events/handlers/timeline.clj:379-398` |
| `:timeline/pause` | `:col :row` | — | `:400-413` |
| `:timeline/stop` | `:col :row` | releases | `:415-418` |

**Left out of the P2 catalog:**
- `:zone-map/press` and `:pattern-pads/press`/`:step`: a map switch can move a picture into an audience group, and no predicate for "this map reaches the audience" is public yet (`vibes/library.clj:154-171` `cycle-audience` is private). They come in with one.
- `:transport/play`: PL-8, Q-1.

**Held presses.** The runtime tracks every held press (`:grid/activate-cell`, `:vibe/press`) a plugin hasn't released. On reload, stop, pause or failure it sends the matching release. Releases always pass the guard.

### 3.4 Never in the catalog (`catalog-test/never-list-test`)

The catalog includes no event:
- of the domains `:ui :dev :file :system :addons :plugins :history :edit :help :view :input :trigger`;
- among `show-lock/safety-edits` (`events/show_lock.clj:120-124`), `:projectors/identify`, `:rig/set-up-from-show`, `:rig/use-show-setup`, `:vibe/set-show-lock`, `:transport/play`.

No entry's `:keys` holds `:confirmed? :force? :rig-force? :state :time :fx/event :fx/sync :source-trigger :source-input :promise :f :args`, or any key in the `history` or `plugin` namespaces.

### 3.5 The guard (`laser-show.plugins.guard/wrap-plugin-guard`, NEW, pure middleware)

It runs only for an event with `:plugin/source`, or (P5) whose `:source-input` has `:source :plugin`. Otherwise it is the identity. It sits in `events/core.clj:645-653` straight after `show-lock/wrap-show-lock`, so it runs before the show lock. It reads state when the event is handled. The first matching row wins:

| # | When | Event | Result |
|---|---|---|---|
| G1 | the entry has `:releases?` | any | passes |
| G2 | `[:plugins :list id]` has `:generation` > the event's, or `:state` in `#{:stopped :failed :disabled :not-allowed}` with a generation ≥ the event's | any | dropped, console "Dropped: from before the plugin reloaded" |
| G3 | `[:plugins :paused?]` | any | refused "Plugins are paused" |
| G4 | `[:vibe-runtime :blackout?]` and it's `:vibe/press :role :blackout` | — | refused "Plugins can't end BLACKOUT" |
| G5 | `:vibe/press :role :blackout`, not in BLACKOUT | — | passes (turns BLACKOUT on) |
| G6 | `:lights?` and `(not [:playback :playing?])` | — | refused "The show is stopped: plugins can't start light" |
| G7 | `:lights?` and `[:vibe-runtime :blackout?]` | — | refused "BLACKOUT is on" |
| G8 | audience `:cue-cell` and `(library/audience-cell? state [col row])` (`vibes/library.clj:140-145`) | — | refused "Cue [c r] can reach the audience" |
| G9 | audience `:fx-cell`, the cell is off, and `(library/audience-fx? state [col row])` (`:173`) | — | refused "Effect cell [c r] can send pictures to the audience" |
| G10 | otherwise | — | the next handler (show lock, then the domain handler) |

A refusal returns `{:dispatch {:event/type :plugins/refused :id :event-type :reason}}` and no `:state`, so nothing else changes. G2 counts by generation and doesn't need the status to be current: a just-loaded plugin's first actions pass even before its status reaches state.

### 3.6 Data examples

| Plugin sends | State when handled | Outcome |
|---|---|---|
| `{:event/type :grid/trigger-cell :col 0 :row 0}` | playing, no BLACKOUT, `[0 0]` can't reach the audience | gate stamps `{… :plugin/source :machine/bar-flash :plugin/generation 3}`; the cue toggles as from a binding |
| same | `[:playback :playing?]` false | G6: refused; console "The show is stopped: plugins can't start light"; state unchanged |
| `{:event/type :grid/trigger-cell :col 0 :row 0 :confirmed? true}` | — | gate: `{:refused "Unknown key :confirmed? for :grid/trigger-cell"}`; nothing dispatched |
| `{:event/type :vibe/set-show-lock :locked? false}` | — | gate: `{:refused ":vibe/set-show-lock isn't available to plugins"}` |
| `{:event/type :dev/write-state :f inc}` | — | gate: refused, not in the catalog |
| `{:event/type :grid/trigger-cell :col -1 :row 0}` | — | gate: `{:refused ":col must be a whole number ≥ 0"}` |
| `{:event/type :timing/tap-tempo}` at 10:00:00.120 | any | admitted with `:input-time 1764000000120`; tap tempo as from TAP |
| `{:event/type :vibe/press :role :blackout}` | BLACKOUT off | G5: BLACKOUT on |
| same | BLACKOUT on | G4: refused |
| `{:event/type :vibe/release :role :hit}` | paused | G1: passes |
| 201st action within one second | — | gate: `{:refused "Rate limited: more than 100 actions a second"}` |
| `{:event/type :effects/clear-cell …}` (P5) | SHOW LOCK on | the guard passes it; the show lock refuses; the console gets "Show lock is on: Clear effect cell refused"; no status-bar notice (PL-11) |
| any, generation 3 | `[:plugins :list id :generation]` 4 | G2: dropped |

### 3.7 Virtual controls (P5)

`p/control!`, `p/press!` and `p/release!` build input events (`input/events.clj:17-29`, `:58-68`) with `:source :plugin :plugin/source id` and call `router/dispatch!` (`input/router.clj:103-128`) from `plugin-runner`. From there they are inputs like any other:
- the mapper stores a control at `[:plugin 0 key]` (`events/handlers/input_mapper.clj:40-44`, the generic `[source channel control]` branch);
- a press becomes `:trigger/input` and runs its learned binding;
- Learn catches them.

The binding's event carries `:source-input` with `:source :plugin` (`events/handlers/triggers.clj:178-185`), so the guard applies G1–G9 to it as well.

## 4. Hooks

### 4.1 Hooks and payloads

| Hook | Source | Payload example |
|---|---|---|
| `:beat` | `plugin-clock` (§4.3) | `{:beat 128 :beat-in-bar 0 :bar 32 :beats 128.004 :bpm 124.0 :late-ms 3.1 :skipped 0}` |
| `:bar` | `plugin-clock` | `{:bar 32 :beat 128 :bpm 124.0}` |
| `:input` | router global handler (§4.4) | `{:event {:type :note-on :source :midi :channel 0 :note 36 :velocity 0.787 :timestamp 1764000000000}}` |
| `:cue/started` | `:plugins/notify` (§4.2) | `{:cell [0 1] :run #uuid "6f1c…" :timeline? false}` |
| `:cue/stopped` | `:plugins/notify` | `{:cell [0 1] :run #uuid "6f1c…"}` |
| `:vibe/changed` | `:plugins/notify` | `{:from 3 :to 4}` |
| `:tempo/changed` | `:plugins/notify` | `{:from 120.0 :to 124.0}` |
| `:transport/changed` | `:plugins/notify` | `{:playing? false}` |
| `:blackout/changed` | `:plugins/notify` | `{:blackout? true}` |
| `:show-lock/changed` | `:plugins/notify` | `{:locked? true}` |
| `:show/loaded` | `:plugins/notify`, when the event type is `:file/project-loaded` or `:file/new-project` | `{:file "/Users/me/LaserShowProjects/friday.zip"}`; `{:file nil}` for New |
| `:plugin/stop` | the runtime, before an unload | `{:reason :reload}` (or `:disabled`, `:failed`, `:show-closed`, `:app-exit`) |

### 4.2 Derived hooks (`laser-show.plugins.hooks/derive`, NEW, pure)

`(derive before after event-type)` → a vector of `[hook payload]`. Each path is compared with `identical?` first, so an event that doesn't touch it costs one `get-in` on each side.

| `[:playback :active-cues]` before | after | Hooks |
|---|---|---|
| `{}` | `{[0 0] {:run a}}` | `[:cue/started {:cell [0 0] :run a :timeline? false}]` |
| `{[0 0] {:run a}}` | `{[0 0] {:run b}}` (retrigger) | `:cue/stopped` a, then `:cue/started` b |
| `{[0 0] {:run a}}` | `{}` | `:cue/stopped` a |
| `{[0 0] {:run a :paused? false}}` | `{[0 0] {:run a :paused? true}}` | none |
| `{[0 0] {:run a}}` | `{[1 0] {:run a}}` (`:grid/move-cell`, `events/handlers/grid.clj:150-153`) | `:cue/stopped [0 0] a`, `:cue/started [1 0] a` |

Stop keeps the cues' intents (`events/handlers/timing.clj:92-101`), so Stop fires `:transport/changed {:playing? false}` and no `:cue/stopped`.

`:vibe/changed` fires on `[:vibe-runtime :current]`, `:tempo/changed` on `[:timing :bpm]`, `:transport/changed` on `[:playback :playing?]`, `:blackout/changed` on `[:vibe-runtime :blackout?]` and `:show-lock/changed` on `[:ui :show-lock?]`; each only when the value differs (`not=`).

**Watches.** `p/watch` compares `(get-in view path)` before and after, `identical?` then `=`. While a delivery for the same watch is still queued, the queued one keeps its `:old` and takes the new `:new`.

### 4.3 Beat and bar (`laser-show.plugins.beats/step`, NEW, pure; run on `plugin-clock`)

`plugin-clock` waits for each new tick with `show-time/frame-signal` (`animation/show_time.clj:454-462`), then calls `(step last {:playing? :beats :bpm :at-ns})` → `[last' hooks]`. `:playing?` is from `show-time/latest`, `:beats` from `show-time/global-beats`. A bar is 4 beats from global beat 0 (`routing/zone_maps.clj:113-115`). Bars and beats are counted from 0.

| `last` | Tick | Hooks |
|---|---|---|
| `{:beat 7}` | playing, beats 7.98 | none |
| `{:beat 7}` | playing, beats 8.01 | `:beat {:beat 8 :beat-in-bar 0 :bar 2 :skipped 0}`, `:bar {:bar 2 :beat 8}` |
| `{:beat 7}` | playing, beats 10.02 | `:beat {:beat 10 :beat-in-bar 2 :bar 2 :skipped 2}` and `:bar {:bar 2 :beat 10}`: one of each, never a burst |
| `{:beat 9}` | playing, beats 0.03 (restart) | `:beat {:beat 0 :bar 0 …}`, `:bar {:bar 0 :beat 0}` |
| `{:beat 7}` | stopped, beats 8.5 | none; `last` stays `{:beat 7}` |
| `nil` (just loaded) | playing, beats 8.4 | none; `last` becomes `{:beat 8}` (a hook never fires late for a beat it didn't see cross) |

`:late-ms` = `(beats − beat) × 60000 / bpm`.

### 4.4 Input

One global router handler (`input/router.clj:63-70`), NEW `::plugin-input`, runs on each device's thread. It only offers `{:event e}` to every running plugin that has an `:input` hook. It skips:
- events with `:learn-only?` (`views/key_triggers.clj:59`);
- events from the plugin's own virtual controls (no feedback loops).

Control changes are coalesced per `(event-id e)` (`input/events.clj:149-161`): a queued one is replaced by the newer value.

### 4.5 Timers

The runner's own `ScheduledThreadPoolExecutor` runs `{:ms n}` timers. `plugin-clock` runs `{:beats n}` timers: each fires when `floor(beats / n)` rises while playing, with the same rules as §4.3. Up to 64 timers per plugin; the 65th throws "Too many timers (64)".

### 4.6 Delivery

- Each plugin has a queue of at most 256 pending calls. Each call is tagged with the plugin's generation and dropped at run time if that generation is old.
- When a queue is full, coalescing hooks (`:beat :bar :input` CCs, watches) replace their queued entry; other hooks are dropped and counted (`:counts :dropped`), with one console line a second.
- The runner takes one call from each plugin in turn, in load order. Each plugin's own calls run in FIFO order.
- While `[:plugins :paused?]`, nothing is queued, except `:plugin/stop`.

## 5. The state view (`laser-show.plugins.view/state-view`, NEW, pure)

`(state-view state)` gives back `(select-keys state roots)` plus a few single paths. It reads one snapshot through `state/get-raw-state` (`state/core.clj:84-96`), on `plugin-runner`, when `p/state` is called.

| Included | From |
|---|---|
| `:config :timing :grid :chains :projectors :virtual-projectors :zone-groups :zones :zone-maps :zone-map :rig :playback :vibe-runtime :triggers :scripts` | `state/domains.clj` defstates; `:scripts` NEW |
| `[:ui :show-lock?]` | `events/show_lock.clj:141-144` |
| `[:project :current-file]`, `[:project :dirty?]` | `state/domains.clj:253` |
| `[:plugins :paused?]` | NEW |

**Not included:**
- `:backend` (devices, handlers, promises, `state/domains.clj:529-543`) and `:history`;
- the rest of `:ui`, plus `:list-ui :projector-ui :rig-ui :rig-file :trigger-runtime :pattern-pads :zone-group-ui :styles`;
- the rest of `:plugins`.

**Stability.** These are the app's own state shapes, documented in `state/domains.clj`, and they can change between versions. API 1 guarantees the hook payloads (§4.1), `p/clock` (§2) and the catalog (§3), not the view.

## 6. Limits

All values are initial and *unverified*; P0, P2 and P3 measure them (`02`).

| What | Limit | Over it |
|---|---|---|
| Source | 256 KiB | `:failed` |
| Loading (top-level eval) | 2000 ms | interrupted; `:failed` |
| One hook or timer call | 50 ms | interrupted; counted as an error |
| Errors | 10 in 10 s | `:stopped` "Stopped after repeated errors" |
| A call stuck in host code | 1000 ms → `:not-responding`; 5000 ms → runner replaced, plugin `:stopped` for the session | PL-20 |
| Actions | 100/s, burst 200, per plugin | refused (§3.1) |
| Timers / watches / hooks | 64 / 64 / 64 per plugin | throws when registering |
| Queue | 256 calls per plugin | §4.6 |
| Console | 200 lines kept, last 50 in status, 500 chars a line | oldest dropped |
| Plugins | 32 machine + 16 show scripts | the rest `:failed` "Too many plugins" |

## 7. Status (`:plugins/status`, supervisor → `[:plugins :list id]`)

```clojure
{:id :machine/bar-flash :name "Bar flash" :version "0.1.0" :origin :machine
 :file "/Users/me/LaserShowProjects/plugins/bar_flash.clj" :hash "sha256:9f2c…"
 :state :running          ; :loading :running :failed :stopped :disabled :not-allowed :not-responding
 :generation 3
 :error nil               ; or {:message "Unable to resolve symbol: trigger" :line 7 :column 4}
 :counts {:actions 120 :refused 2 :hooks 480 :errors 0 :dropped 0}
 :console [{:at-ms 1764000000120 :level :info :text "Bar flash 0.1.0 loaded"}
           {:at-ms 1764000004000 :level :warn :text "Refused :grid/trigger-cell: The show is stopped: plugins can't start light"}]}
```

The event is `{:event/type :plugins/status :seq 912 :plugins {id status} :order [ids]}`. `:seq` increases with every publish. The handler ignores an event whose `:seq` is ≤ the last one stored (`[:plugins :seq]`).

## 8. Show scripts and trust (P4)

`scripts.edn` in the show zip:

```clojure
{:items [{:id "bar-flash" :name "Bar flash" :enabled? true
          :source "(ns bar-flash\n  {:plugin/name \"Bar flash\" :plugin/api 1}\n  (:require [laser-show.plugin :as p]))\n…"}]}
```

`plugin-settings.edn` in the plugins folder (read at start, written by `:plugins/write-settings`):

```clojure
{:plugin-settings/version 1
 :disabled #{:machine/old-thing}
 :trusted #{"sha256:9f2c…" "sha256:01ab…"}}
```

**The prompt.** `:plugins/check-trust` opens `[:ui :dialogs :confirm]` (`views/dialogs/confirm.clj`) for the enabled scripts whose hash is neither trusted nor allowed this session. It uses:
- title: "Run this show's scripts?"
- message: "This show has 2 scripts: Bar flash, Tap from pad 60."
- details: "While the show plays, scripts can trigger cues, press vibe keys and set the tempo. They can't widen a safety zone, reach the audience, end BLACKOUT or turn off SHOW LOCK."
- `:choice {:key :remember :label "Remember" :options [{:value :once :text "Just this time"} {:value :always :text "Always for these scripts"}]}`
- confirm label: "Run scripts"
- `:confirm-event {:event/type :plugins/allow-show-scripts :hashes #{…}}`

Cancel allows nothing. Settings then shows those scripts as "Not allowed yet", with an **Allow…** button that opens the same prompt.

A show script is **wanted** (`sync/wanted`) when it is `:enabled?` and its hash is in `[:plugins :settings :trusted]` ∪ `[:plugins :allowed-hashes]`.

| Show's scripts | Trusted | Allowed this session | After Open |
|---|---|---|---|
| none | — | — | no prompt |
| A, B | A, B | — | no prompt; both run |
| A, B | A | — | A runs; the prompt lists B |
| A (edited since) | old A | — | the prompt lists A (the hash changed) |
| A, B | — | — | the prompt lists both. Run with "Always" → both hashes trusted, `plugin-settings.edn` written, both run |
| A, then Undo brings back a removed script C | A | — | C doesn't run and shows "Not allowed yet" |

Copy into show adds the copied source's hash to `:trusted` (PL-18): you made it, so opening your own show doesn't ask.

## 9. The sandbox (`laser-show.plugins.sandbox`, NEW)

`(base-ctx)` is built once with `sci/init`:
- `:namespaces`: `clojure.core` with `sci.interrupt/clojure-core`'s interruptible fns, plus `clojure.string clojure.set clojure.walk`, `clojure.edn` (`read-string` only) and `laser-show.plugin`;
- `:classes {}`, `:load-fn (constantly nil)`, and an `:interrupt-fn` that checks the current call's deadline (a volatile read);
- `sci/out` and `sci/err` bound per call to the plugin's console writer.

`(plugin-ctx)` is `(sci/fork base-ctx)`.

P0's probes (`test/laser_show/plugins/sandbox_test.clj`). Each row is a test; "refused" means an exception with SCI's message, and the app keeps running:

| Plugin code | Expected |
|---|---|
| `(slurp "/etc/hosts")`, `(spit "x" 1)`, `(load-file "x")` | refused (unresolved or not allowed) |
| `(System/exit 0)`, `(java.io.File. "x")`, `(Thread/sleep 10)` | refused: no class |
| `(.getClass "x")` | refused: interop on a class not in `:classes` |
| `(future 1)`, `(pmap inc [1])`, `(agent 0)` | refused |
| `(require 'clojure.java.io)` | refused: namespace not found |
| `(alter-var-root #'clojure.core/inc (constantly dec))` | refused (built-in vars are read-only) |
| `#=(java.lang.System/exit 0)` in the file | read error; `:failed` |
| `(loop [] (recur))`, `(reduce + (range))`, `(count (repeat 1))` | interrupted at the budget |
| `(eval '(+ 1 2))` | `3` (SCI's own sandboxed eval) |
| plugin A `(def secret 1)`; plugin B `(resolve 'a/secret)` | `nil` |

## 10. Invariants (each is a test in `02`)

1. Plugin code runs only on `plugin-runner`.
2. Every event the runtime dispatches passed `gate/admit` and carries `:plugin/source`.
3. The catalog meets §3.4.
4. No `:lights?` event from a plugin is handled while stopped, in BLACKOUT or paused.
5. After a reload, nothing from an older generation runs or is handled.
6. `:plugins/notify`, the router handler and `plugin-clock` never block; a full queue drops or coalesces.
7. The state view round-trips through `pr-str` and `clojure.edn/read-string`.
8. Machine plugins and show scripts never share a SCI context.
9. A show script never loads unless its hash is trusted or allowed this session (§8).
10. Pausing, unloading or failing a plugin releases everything it holds (§3.3).
