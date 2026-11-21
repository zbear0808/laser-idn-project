# Phase Z4: zones, zone maps and one Target control

**Written:** 2026-11-19   **Written for commit:** `6aaad7d` (`master`; first written at `90e35c7`, then re-verified here: `src/` is as vibe Phase 6 left it in `4a4a10a`, after vibe Phases 3 (`38f92ad`), 4a (`6715fb4`), 4b (`f5a4f84`) and 5 (`9f5d78a`, with the fader fix `0faa18c`) and UX Phase 3 (`b9b9d55`); every line below is at that source). Z0, Z2 and Z3 edit several of these files first, so **re-check every path and line at HEAD before starting.**
**Status:** **Done in code, on `master`** (`64fab99`..`b44e388`, §13). Still owed: the checks on a real projector (§13).   **Needs:** Z3 (`05`), and through it Z2's automatic groups (`04`) and Z0's reference walker and confirm dialog (`02`). Vibe Phases 2–6 are in: the Perform tab (`94f905e`), the Vibes tab and its lint (Phase 3), pulses and tap reactions (4a, 4b), the faders with the Master (5), and auto-advance with one tap tempo (6)   **Size:** ~6 d
**Implements spec** (`01-model-and-routing-spec.md`): §1 (zone refs, the normal form of a target, zone labels), §2 (inheritance, with `:from`), §4 (built-in and custom maps, wrap, overrides, active, pending, effective, with the slot and the pause the Map cycle fills in Z5), §5's order within a tick (the effective map before the routing), §9 (switch timing), §11 (the rows for item and track zones, `:destination-zone`, the Zone Selector and preview cells), §12 (the show-side rows and `[:zone-map]`). Overview questions planned with ★: Q-Z1 A (wrap), Q-Z4 A (grid column and show default), Q-Z5 A (direct and through the current map), Q-Z6 A (letters, optional labels).
**Merge risk:** vibe Phase 6 landed while this was being re-verified (`4a4a10a`), and the lines below are after it. It dropped `:timing/calculate-bpm` from `events/core.clj`, gave tap tempo an `:input-time` and took `:tap-history` out of `update-runtime` (`events/handlers/triggers.clj`), made `show-time/due-reports` public with a sent map, added a `rotation-step` to `tick!`, and put an AUTO switch in the Perform header. None of it changes what Z4 relies on: the map-due report goes through `due-reports` with the step's `:sent`, and a map press dates itself by `:input-time` as tap tempo now does. Vibe Phase 7 (safety and hardening, proposed) edits `events/core.clj`, `events/handlers/input.clj`, `events/handlers/grid.clj`, `views/tabs/perform.clj`, `services/show_clock.clj`, `animation/show_time.clj`, `state/domains.clj` and `subs.clj`, which Z4 edits too, so whichever lands second merges those by hand (00 §6).

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. **NEW** marks what doesn't exist yet. "Z3's" marks a name the Z3 doc introduces: `rig/routing`, `rig/resolve-ref`, `compose/…`.

After this phase you can:
- set where content goes with **one Target chip**, the same everywhere: an item, a group, a track or folder, a clip, the cue, a grid column, the whole show. It says what the target ends up as and where that came from: "A → Left · from column 3";
- draw into **zones A, B, C…** (with optional labels, "A · Beams"), into All, **pin** a group, or combine several;
- pick a **zone map** (Left / Right, Odd / Even, Unison, or your own) and **switch it live** from pads on the Grid tab and on Perform: on the next beat, or on the next bar with Shift. A pad blinks while it waits, and any MIDI key can be taught to press it;
- see on each grid cell where its cue goes (A, A B, A+B, pinned L/R);
- open an old show: its routing becomes targets, silently, and **routes exactly as before**;
- trust that a vibe never picks a cue that **any** zone map can send to the crowd.

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| Four ways to route an item | `routing/zone_effects.clj:127-156` | the item's `:zone-group-id`, else its first enabled Zone Selector's `[:params :target-zone]`, else its track's (walking folders), else the parent group's resolved zone, else the cue's `[:destination-zone :zone-group-id]`, else `:all`. One keyword |
| The track walk | `routing/zone_effects.clj:78-125` | rebuilds the whole track index on every call (`:117`), so once per item per tick |
| The render | `services/frame_service.clj:230-297` (`render-item-tree-by-zone`), `:349-416` (`generate-frames-by-zone`; the cue default is read at `:384`) | frame keys are the resolved zone ids; items under one key are concatenated |
| The Zone Selector | `animation/effects/zone.clj:43-53`; its renderer `views/components/visual_editors/custom_param_renderers.clj:494-539`, `views/components/effect_param_ui.clj:120-129` | an identity transducer whose stored param routing reads raw. Offered by the Zone tab (`views/components/effect_bank.clj:26-31, 103-107`) in the cue editor (`views/dialogs/cue_chain_editor.clj:49`), the effects grid (`views/dialogs/effect_chain_editor.clj:44`, where it does nothing: vibe Phase 8, probe v20) and the timeline browser for clips (`views/components/visual_editors/timeline/browser.clj:61-63`) |
| Setting a zone | `events/handlers/cue_chain.clj:180-193` (cue), `:196-209` (item or group); `timeline/model.clj:615-619, 680-681` (`:set-clip-zone`, `:set-track-zone`), `:593-607` (`add-track` takes `:zone-group-id`) | one keyword or nothing |
| Cue Chain Editor | `views/dialogs/cue_chain_editor.clj:95-131` (footer, "Default zone:", with UX Phase 3's tooltip), `:133-143` (parent group's zone), `:145-188` (labels, "Circle [Left (Group)]"), `:190-231` (context menu, "Assign Zone"), `:233-272` ("Zone Destination:"), `:330-414` (inherit labels), `:608-653` (footer) | four widgets, each working out "inherit" its own way; the labels skip the track level. Every footer control has a tooltip, and a UI test counts its two combos (`test/laser_show/ui_test/main_window_test.clj:201-215`) |
| Timeline editor | `views/dialogs/timeline_cue_editor.clj:25-46, 147-151` (a copy of the footer dropdown); `views/components/visual_editors/timeline/header.clj:38-57, 121-128` (zone menu, chip); `.../timeline/inspector.clj:27-42, 90-131, 141-168` (zone combos) | the clip's "Inherit from Track (…)" ignores the cue default: `inspector.clj:93` passes `:all` |
| Timeline model | `timeline/model.clj:150-174` (`flatten-visible-tracks`, rooted at `:all`, `:174`), `:298-365` (`auto-initialize-tracks` reads Zone Selectors), `timeline/layout.clj:50-88` (rows carry `:zone-id`) | |
| Preview cells | `state/domains.clj:252-259` (`{:zone-group-id …}`), `events/handlers/ui.clj:103-138`, `views/components/preview.clj:134-142, 200-266, 295-335` | a cell shows the frames under one exact key |
| Grid | `views/components/grid_cell.clj:95-220` (no badge; the stack pane at `:135`, its children `:191-211`, the menu label and its `:tooltip-fn` `:212-219`), `views/components/grid_tab.clj:134-156` (column numbers), `:219-251` (title, hint and UX Phase 3's empty-grid line); `views/tabs/grid.clj:16-23` | the generic tab is shared with the effects grid. A cue cell has no tooltip yet; Z3 gives it one (05 §4) |
| Perform | `views/tabs/perform.clj:134-147`; `views/components/vibe_pads.clj:55-150`; `events/handlers/vibes.clj:34-54` (`pointer-action`) | banner, header (with Phase 6's AUTO switch), vibe strip, the five pads (with Phase 4a's pulse bars and reactions), Phase 5's fader meters (`slider-meters/meter-row`, `:146`), then the status line. A pad: primary press or touch, a menu to learn |
| Trigger map, learn | `events/handlers/triggers.clj:60-113` (vibe actions carry `:input-time`, and since Phase 6 tap tempo does too, `:102`), `:136-148` (`handle-input`, which skips the bookkeeping for vibe actions, `:142-144`); `events/handlers/input.clj:233-238` (`learn-target` keeps `:type :target :role :slot`), `:276-305` (a learn catches a press, or for a fader a control that moves, `fader-catcher`, `:260-274`), `:317-326` (`action-name`), `:328-367` (`handle-input-learn-captured`: re-learning a role moves it, `:341`, `:357-358`; Learn all refuses a key twice, `:342-352`), `:369-388` (`handle-fader-captured`: a fader binds `[:config :input :macro-map]`), `:481-483`; `events/handlers/input_mapper.clj:57-64, 90-91` (while a learn waits, presses are held back) | |
| The show clock | `services/show_clock.clj:57-72` (`render-cue`), `:83-106` (`render`), `:160-191` (`tick!`: `step` at `:172`, then Phase 6's `rotation-step` after `publish!`, and the reports and status dispatched at `:190`); `animation/show_time.clj:203-218` (`pending-reports`), `:220-233` (`due-reports`: again at most every 250 ms, public since Phase 6 and given the sent map, the step's `:sent` or the clock's `:vibe-sent`), `:314-317` (`global-beats`), `:413-421` (`frame-signal`); `animation/cue_timing.clj:78-122` (the global clock advances every tick, playing or not) | it writes app state only through events |
| Reading the clock in an event | `events/core.clj:267-271` | `:link/sync-to-downbeat` reads `show-time/global-beats` inline; no handler sees the beats |
| Stop, Retrigger | `events/handlers/timing.clj:73-84`; `animation/show_time.clj:108-110` | a `:restart` request: the global clock goes back to beat 0 (Phase 4a's pulse clock doesn't) |
| Live and saved | `state/history.clj:14-22, 48-64, 153-159, 262-278`; `state/persistent.clj:48-62, 128-143, 151-169, 179-191` | BPM and effect cells' `:active?` are live. `[:grid]` keeps only `:size`, in the document (`history.clj:60`) and in the file (`persistent.clj:51`) |
| Audience | `vibes/library.clj:72-140`, `:405-416`; `subs.clj:1145-1165` | group names: `:crowd` and every group of a crowd-scanning projector (`audience-groups`, `:82-87`); `zone-group-ids` (`:89-105`) reads the four places above. Vibe Phase 3 made `audience-cue?` (`:107-110`) the one test: `audience-cell?`, `unplayable` and so `playable-cells` (`:112-140`), the lint (`cell-problems`, `audience-text`) and the Vibes tab's picker (`subs/vibe-cell-blocked`, from `vibe-audience-groups`, "from the projectors alone") all go through it |
| Used by | `subs.clj:716-726` (`:cue-count 0 ;; TODO`), shown at `views/tabs/zones.clj:271-273` | Z0 makes the direct count real |
| Starter cues | `state/templates.clj:31-47` | each says `:destination-zone {:zone-group-id :all}` |
| Undo labels | `events/handlers/history.clj:125-163`; `timeline/model.clj:735-752` | |

## 2. Why / root cause

- **Routing names the venue.** All four places hold a group id, so a cue says "Left", never "zone A". Nothing between an item and a projector can change what a name means while the show runs, and that is what a map switch is.
- **Each place has its own widget and its own idea of "inherit"** (`cue_chain_editor.clj:133-188, 330-349`, `header.clj:38-57`, `inspector.clj:90-168`), so they drift: the clip inspector's label is wrong (`inspector.clj:93`), and the cue editor's skips tracks.
- **One of the four is an effect** whose parameter routing reads raw (`zone_effects.clj:143-145`): it can't be modulated, and it is offered where it does nothing.
- **Nothing above the cue.** A column of "left" cues is set cue by cue.
- **The audience rule matches group names**, so it can't see a zone that a map sends to the crowd.

## 3. Reuse / extend

| Existing (file:line) | Reused for |
|---|---|
| Z3's resolver (`routing/rig.clj`): `routing-inputs [state map-id]` with its `:zone-maps` slot (nil until Z4), `routing`, `resolve-ref [routing ref]` ("in Z4 it resolves a zone through the effective map's lanes"), `normalize-target`, `zone-index`, `key-refs`; the snapshot's `:routing {:map nil …}`, made before the render (05 §4, spec §5) | zone → lane → entries, behind the one reader every engine and the preview already use; `:routing :map` becomes the effective map |
| Z2's `state/migration.clj` ("Z4 adds its rows here"), whose `zones-z2` has already rewritten references to the removed built-ins, in the old keys, with Z0's `refs/replace-refs` (04 §4, 02 §4) | Z4's rows: `zones-z4` |
| Z0's reference walker, `laser-show.routing.refs` (02 §4): `ref-places`, `cue-refs`, `cues-using`, `replace-refs` | Z4's new places, so deleting a group re-targets them too (§4.4) |
| Z0's confirm dialog (`views/dialogs/confirm.clj`, `:ui/confirm`, 02 §4) | asking before a custom map is deleted (§6) |
| Z2's `subs/target-groups` (All, Left … Even, the lasers in order, then custom groups by name) and `rig/group-defs` (04 §4) | the Groups section of the Target menu and of the map lanes |
| Z2's `audience-groups` = `(into #{:crowd} (rig/refs-reaching state crowd?))` (04 §4) | the non-zone half of the audience rule |
| Z3's preview cells `{:target ref}` (a laser's own ref, `:auto.laser/<id>`, shows what that laser draws), its `:ref-frames` and `:preview/set-cell-zone` with a `:target` payload (05 §4) | a zone cell counts for its lane's ref |
| Z3's cue-cell `:tooltip-fn` (05 §4) | the badge's tooltip text |
| `render-context` (`services/frame_service.clj:103-125`), `render-cue` (`services/show_clock.clj:57-72`) | the column and show defaults reach each cue's render, read once per tick |
| The reports (`animation/show_time.clj:203-233`) | "the pending switch is due", sent at once, again every 250 ms while app state differs |
| `:link/sync-to-downbeat` (`events/core.clj:267-271`) | an inline effect that reads the beats and continues the event |
| BPM, ignored by both comparisons (`state/history.clj:153-173`) | the live policy of the active map: a domain outside the document roots |
| `persistent-state-mapping`'s `:keys` specs (`state/persistent.clj:48-62, 128-143`) | saving `[:zone-map :active]` and `[:grid :column-targets]`; a file without them loads the defaults |
| Z2's `migration/zones-z2` call in `apply-loaded-project` (after `normalize-timeline-cues`, `state/persistent.clj:188`) | where Z4's rows run: after Z2's, before `mark-clean` |
| The timeline header chip (`header.clj:121-128`, `.tl-zone-chip`) | the Target chip's shape |
| Vibe pads (`vibe_pads.clj:55-142`), `pointer-action` (`vibes.clj:34-54`), `binding-label` (`views/components/trigger_bank.clj:38`) | map pads: press, touch, the learn menu, the key text |
| `learn-target`, `handle-input-start-learn`, `handle-input-learn-captured` (`input.clj:233-238, 276-305, 328-367`) | learning a map pad (a press, never a moved control) and moving its key |
| `ext-clock-redraw` (`views/components/clock_view.clj:58-71`) | blinking a pending pad from the clock, without app state |
| The routing test cues (`test/laser_show/routing/zone_routing_integration_test.clj:26-116, 488-533`) and the probe v19 rig (vibe Phase 8 §7) | the legacy fixture shows |

## 4. Changes per namespace

### 4.1 The pure model

- `laser-show.routing.targets` (NEW, pure; it requires nothing else, so `rig`, `zone-maps`, the timeline model and the vibes can all use it):
  - Z3's `normalize-target`, `zone-index` and `key-refs` move here from `routing/rig.clj`, which keeps them as aliases so Z3's callers don't change. Normal form (spec §1): nil, a keyword, or a vector of 2+ distinct keywords sorted by `(juxt namespace name)`.
  - NEW: `zone-ref?`; `zone-ref` (index → keyword, the inverse of `zone-index`); `zone-letter` ("A", "AA"); `next-zone [used]`; `toggle [t ref]` (adds or removes one ref, normalised).
  - `cue-fallback [cue-target column-target show-target]` → `{:target :from}`, `:from` one of `:cue :column :show :default` (All). Made once per cue render.
  - `resolve-item [own track parent fallback]` → `{:target :from :via}`, spec §2: the item's own (`:item`), else its track's (`:track`, `:via` the track or folder that set it), else the parent group's resolution (a group's own target becomes `{:from :group :via group-id}`, anything else passes through), else the fallback. The one function routing uses.
  - `track-targets [tracks]` → `{track-id {:target :via}}` for every track with its own or a folder's `:target`. It replaces `index-tracks` and the per-item walk; built once per cue render and kept while `tracks` is `identical?` (a one-entry cache).
  - `explain-cue [cue column-target show-target]` → `{:cue res :items {id res} :tracks {id res}}` for the editors. `cue-refs [cue column-target show-target]` → every ref any item can draw to, what it inherits from its column and the show included, disabled and muted items too (for "used by" and the audience rule). Z0's `refs/cue-refs [cue]` (02 §4) stays what it is, the refs a cue's own data names, for the walker.
  - Text, from a names context `{:groups :labels :lasers}`: `ref-name` ("A · Beams", "Left", "Laser 2", "Front (missing)"), `target-text [names zone-map t]` ("A + B → Left + Right"), `from-text` ("from column 3"), `badge` (§8.5).
- `laser-show.routing.zone-maps` (NEW, pure; requires `targets` only):
  - `built-ins`: `:map/left-right` "Left / Right" `[:auto/left :auto/right]` (the default), `:map/odd-even` "Odd / Even" `[:auto/odd :auto/even]`, `:map/unison` "Unison" `[:all]`. `all-maps [state]` (built-ins, then `[:zone-maps]` by name); `map-by-id`.
  - `zone-lane [m zone]` = `(get overrides zone (nth lanes (mod i n)))`; nil with no lanes or a nil lane (spec §4). `pin [m t]` replaces each zone by its lane.
  - `effective-map-id [state beats cycle]`, spec §4's order in full: the pending map once `due?`; else `cycle`, the Map cycle's pick, unless `[:zone-map :cycle-paused?]`; else `[:zone-map :active]`. An id that is no map falls back to `:map/left-right`. In Z4 `cycle` is always nil and nothing sets the pause: Z5 passes the pick, adds `:cycle-paused?` to the domain and sets it (07 §4.3, Z5 S-2), so the order is tested once, here. `due? [at-beat beats]` is `beats + 1e-9 ≥ at-beat`.
  - `switch-at [{:keys [beats bpm bar? clock?]}]` → the beat a press applies at, or nil for at once (§8.4); `clock?` is false while the clock thread is off, the transport is stopped or the BPM isn't positive (SQ-2). `window-s` 0.05, `beats-per-bar` 4, bars counted from global beat 0 as the beat squares are (`services/show_clock.clj:135`).
  - `schedule [state map-id bar? clock]` (the whole press, pure; the BPM is `[:timing :bpm]`, the beats come in `clock`): commits a pending switch that is already due, then: the active map pressed clears the pending one; at once commits it; otherwise writes `:pending {:id :map :at-beat :bar?}`, replacing any other. `commit [state id]` lands a switch on `:active`, and every switch goes through it (a press at once, a due one, Stop and Retrigger, "Use now"), so Z5 pauses a running cycle in one place. `map-warnings [state m names]` (§7.5). `next-custom-id` (`:map/custom-1`, the first unused).
- `laser-show.state.migration` (Z2's, existing by then): NEW `zones-z4 [state]` and `cue-z4 [cue]` (§8.3), run after Z2's `zones-z2`, so references to the removed built-ins are already `:auto/*`. They look at the data, not at a flag: a migrated show comes back `identical?`.

### 4.2 Render, routing, the clock

- `services/frame_service.clj` (existing): `render-context` (`:103-125`) adds `:column-targets` and `:show-target`, beside Phase 4a's `:pulses` and the faders' values. `generate-frames-by-zone` builds `cue-fallback` from `(:target cue)`, the context's `:column-target` and `:show-target`, and `track-targets`, once; `:384` goes. `render-item-tree-by-zone` takes the parent's resolution instead of `parent-zone-id` and `default-zone-id`, and keys each frame by `(:target (targets/resolve-item …))`. The keys never depend on the map (invariant 2).
- `services/show_clock.clj` (existing): `render-cue` (`:57-72`) puts the cell's `:column-target` on the render context it passes, as Z3 puts `:item-points?` there. `tick!` (`:160-191`) keeps spec §5's order (Z5 S-3): after `step`, `(zone-maps/effective-map-id state (show-time/global-beats runtime) nil)`, from the tick's one read of app state; then Z3's cached routing with it, where Z3 passes nil; then the render. So the snapshot carries `:routing {:map id :lanes … :overrides … :refs … :outputs …}`. Z5 puts its zone context and the Map cycle's pick between `step` and the effective map; Phase 6's `rotation-step` stays after `publish!`.
- `routing/rig.clj` (Z2's, extended by Z3): `routing-inputs [state map-id]` fills the `:zone-maps` slot Z3 left nil; `routing` adds the effective map's `:lanes` and `:overrides` beside `:map` (from `zone-maps/map-by-id`), and `resolve-ref [routing ref]` resolves `:zone/*` with `zone-maps/zone-lane` over them, then the lane's entries (`[]` for nil). Every caller (engines, preview, cue warnings) is unchanged. `rig` requires `zone-maps`, never the reverse.
- `animation/show_time.clj` (existing): `pending-reports` (`:203-218`) adds `{:event/type :show-clock/zone-map-due :id :map}` while `[:zone-map :pending]` is due at the global beats, so that tick's snapshot and the report agree. `step` dedupes it with the others, by `(due-reports (:sent rt) …)`.
- `frame-service/preview-data` (Z3's `:ref-frames`): a zone counts for its lane's ref, so a cell on Zone A shows what Zone A's lane draws (05 §11).
- `routing/zone_effects.clj` (existing): `:58-187` goes. The debug logging (`:16-53`) stays: `frame_service`, `events/handlers/menu.clj:356-357` and `timing.clj:70` use it.
- `animation/effects/zone.clj` (existing): `:hidden? true`. The id stays registered (old files load; `test/laser_show/animation/effects_coverage_test.clj:22, 34` passes) and nothing offers it. `register-effect!`'s docstring (`animation/effects.clj:77-92`) lists `:hidden?`. If vibe Phase 8 landed first, its `:scope #{:item}` on the selector is replaced by `:hidden? true`.

### 4.3 State, history, loading

- `state/domains.clj`: NEW domains `zones` (`:labels {}`), `zone-maps` (`{}`: custom maps), `zone-map` (`:active :map/left-right`, `:pending nil`). `grid` (`:138-147`) gains `:column-targets {}`; `config` (`:246-277`) gains `:default-target nil` and `:zone-map-pads {}`; `ui` gains `:zone-maps {:selected nil}`; the preview cells' default (`:254-257`) becomes `[{:target nil} {:target :auto/left} {:target :auto/right} {:target :auto/center}]`.
- `state/history.clj`: `document-roots` (`:48-50`) and `extract-document-state` (`:52-64`) gain `:zone-maps` and `:zones`, and `:grid` keeps `:column-targets` beside `:size`; `content-changed?` (`:153-159`) compares the two new roots; `restore-document` (`:262-278`) restores them and `[:grid :column-targets]`. `[:zone-map]` stays out of all four (§5).
- `state/persistent.clj`: `:project-metadata` gets `{:path [:grid] :keys [:size :column-targets]}` and `{:path [:zone-map] :keys [:active]}`; `:content` gets `{:path [:zone-maps]}` and `{:path [:zones]}`. `apply-loaded-project` runs `migration/zones-z4` right after Z2's `zones-z2`, before `mark-clean`; `reset-session` clears `[:zone-map :pending]`.
- `state/templates.clj` (`:31-47`): the starter cues lose `:destination-zone`; they inherit All.
- `timeline/model.clj`: `create-track` and `add-track` take `:target`; `set-zone` becomes `set-target` (normalised; nil clears); ops `:set-clip-target`, `:set-track-target` replace the zone ops (`:680-681`, `op-types` `:709-716`, `op-label` `:751-752`); `flatten-visible-tracks` carries `:target`, rooted at nil instead of `:all`; `auto-initialize-tracks` makes a folder per distinct item `:target` and a "Default" folder with none for the rest, so a converted cue still follows its cue, column and show default.
- `timeline/layout.clj` (`:50-88`): rows carry `:target` and `:target-inherited?` instead of `:zone-id` and `:zone-inherited?`.

### 4.4 Events

- `events/handlers/cue_chain.clj`: `:cue-chain/set-target` and `:cue-chain/set-item-target` replace `:180-209` (normalised; nil removes the key). `events/handlers/timeline.clj` (`:263-284`): `:timeline/set-track-target`, `:timeline/set-clip-target`; `:add-track` and `:add-folder` take `:target`.
- `events/handlers/grid.clj`: NEW `:grid/set-column-target`. `paste-cell` (`:164-169`) runs `migration/cue-z4`, so a cue copied from an older build routes the same.
- `events/handlers/zone_maps.clj` (NEW), routed by `events/handlers.clj:38-67` for `"zones"`, `"zone-maps"` and `"zone-map"`: the events of §6.
- `events/handlers/timing.clj`: NEW `:show-clock/zone-map-due`. `:transport/stop` and `:transport/retrigger` commit a pending switch at once: their restart puts the global clock back to 0, and the pending beat belongs to the old count.
- `events/handlers/triggers.clj`: action `{:type :zone-map :target map-id}`. `action-event` (`:80-113`) turns a press into `{:event/type :zone-map/press :map id :src :input :input-time ts}`, and a release into nothing. `handle-input` skips the bookkeeping for it, as for vibe actions (`:140-144`).
- `events/handlers/input.clj`: a map pad learns the way a vibe pad does, beside Phase 5's fader learn, with nothing new in the catching:
  - One learn at a time. `[:backend :input :learning-target]` holds its `learn-target` (`:233-238`), `{:type :zone-map :target id}` for a pad, and a new learn releases the old one's promise and ends Learn all (`end-other-wizard`, `:247-253`).
  - A pad's learn catches a press, a note or a button (`handle-input-start-learn`, `:290-295`), never a CC. A fader's learn (`{:type :macro}`) catches only a control that moves (`fader-catcher`) and binds `[:config :input :macro-map]`, not the trigger map, so the two never take each other's input.
  - While any learn waits, the mapper holds presses back (`input_mapper.clj:57-64, 90-91`), so learning a map pad on HIT's key doesn't also press HIT. The new binding replaces the key's old one, with the undo label "Learn Trigger (replaces HIT)" (`:363-365`), and Phase 3's lint then says HIT has no key.
  - One key per pad (Z4-Q2): `handle-input-learn-captured` drops a role's other bindings (`:341`, `:357-358`, through `library/binds-role?`). Z4 makes that rule "a target that keeps one key": the actions of type `:vibe` and `:zone-map`, compared by `learn-target`. Z5 adds `:pattern-pad` and `:zone-step` (07 §4.6).
  - `action-name` (`:317-326`) says "zone map Odd / Even", and takes the state for a custom map's name.
- `events/handlers/ui.clj`: Z3's `:preview/set-cell-zone` already writes `{:target …}`; Z4 only lets the picker offer zones.
- Z0's reference walker, `laser-show.routing.refs` (02 §4; its delete, Z2's migration and ZR's `rename-pins` all call `replace-refs`): `ref-places` gains Z4's places, so `cue-refs`, `cues-using` and `replace-refs` see them: every `:target` (inside a union too), `[:grid :column-targets]`, `[:config :default-target]`, map lanes and overrides. So deleting a group re-targets them all, and `:zone-groups/remove` needs nothing of its own.
  - Inside a union the ref is replaced, or taken out for "Nothing", and the target is normalised again (`targets/normalize-target`): `[:graphics :zone/a]` becomes `[:all :zone/a]`, or `:zone/a`.
  - **A map lane or override that named the deleted group takes the delete's re-target** (All by default, 02 Q-Z0-1; the dialog only offers groups, so a lane never becomes a zone). With "Nothing (they inherit)", the lane is left empty, which reaches nothing and is flagged by the map editor (spec §4), and the override is dropped, so its zone wraps again (SQ-13).
  - Z0's delete dialog also names a column or the show default that uses the group: "3 cues, column 3 and 2 projectors use it."
- `events/handlers/history.clj` (`:125-163`): the labels of §6; `:cue-chain/set-destination-zone-group` goes, because the labels test wants routed events only.
- `events/core.clj`: NEW inline effect `:show-clock/read {:on-read e}`, beside `:link/sync-to-downbeat` (`:267-271`): it continues `e` with `:clock {:beats (show-time/global-beats) :running? ((:running? show-time/frame-signal)) :now-ms (System/currentTimeMillis)}` through `run-inline`. `:now-ms` is on the same wall clock as an input's `:timestamp` (`input/events.clj`), so the input-time correction (§8.4) compares like with like.

### 4.5 Views

- `views/components/target_control.clj` (NEW): `menu-model [ctx]` (pure: the rows of §7.1 as data, unit-tested; groups from Z2's `subs/target-groups`); `menu-items` (the rows as `:menu-item`s, plus a "Combine" submenu of `:custom-menu-item`s holding a check box, `:hide-on-click false`); `target-chip` (a `:menu-button` shaped like `.tl-zone-chip`; props `:target :explain :on-set :scope`, and a tooltip, as every footer control has one since UX Phase 3: "Where the cue's content goes, unless an item, a group or a track sets its own"); `target-field` ("Target:", the chip, the dim "from …"). `:on-set` is an event template that gets `:target`. Scopes: `:item :group :track :clip :cue :column :show` (every row; `:show` has no Inherit), `:lane` (All and groups only), `:preview` ("Master (all content)" instead of Inherit; no Combine, no Pin). `column-target-header` for the grid.
- `views/dialogs/cue_chain_editor.clj`: the pickers (`:95-131`, `:233-272`) become `target-field`s; "Assign Zone" (`:190-231`) becomes "Target ▸" with `menu-items`; the labels (`:145-188`) come from `explain-cue`; `:133-143` goes; `:49` loses `:include-zone?`. `views/dialogs/timeline_cue_editor.clj`: its footer copy (`:25-46`) becomes a `target-field`.
- Timeline: `timeline_editor.clj` passes `:explain` (`subs/cue-target-explain`) instead of `:destination-zone-id` (`:870`, `:922`, `:1022-1032`). `header.clj` `zone-chip` and `zone-menu-items` become a compact `target-chip` ("A", "(A)" inherited) and "Target ▸". `inspector.clj` gets `target-field`s for clips and tracks, explained by the resolver. `browser.clj:61-63` loses `:include-zone?`.
- `views/components/grid_cell.clj`: the cue cell gets a badge (`subs/cell-target-badge`), top-right in the stack pane (`:135`, beside the v-box of `:191-211`), mouse-transparent, `#grid-cell-badge-<col>-<row>`. A mouse-transparent node shows no tooltip, so the badge's text joins Z3's cue-cell `:tooltip-fn` (after its ⚠ line, when there is one) on the menu label (`:212-219`). Only cells whose badge has a zone subscribe to the active map (their tooltip names lanes).
- `views/components/grid_tab.clj`: `generic-grid-tab` (`:219-251`) takes optional `:header-extra` (right of the title) and `:column-component` (replaces the number label in `grid-header`, same size and id). `views/tabs/grid.clj` passes the map strip and `column-target-header`; the effects grid passes neither (it keeps its `:empty-hint`).
- `views/components/zone_map_pads.clj` (NEW): `map-pad` (props `:map-id :size`), `map-strip` (Grid tab), `map-pad-row` (Perform), `redraw-blink!` for `ext-clock-redraw`. Pads aren't focus-traversable; ids `zone-map-pad-<n>`, `-state`, `-keys`. `views/tabs/perform.clj` (`:134-147`): a "ZONE MAP" row after Phase 5's fader meters (`slider-meters/meter-row`, `:146`) and before the status line; Z5's "ZONE PATTERN" goes under it.
- `views/components/zone_maps_panel.clj` (NEW): §7.5, under Z2's three columns of the Zone Groups tab (GROUPS, LASERS IN …, DETAILS; 04 §7). Z2's details line "Used by n cues" (Z0's count) gains the count through the active map (§8.7) from `subs/ref-usage`.
- `views/components/preview.clj`: Z3's picker (Master, Groups, Lasers) gains a Zones section, the zones the show uses, named with `ref-name`.
- `views/components/effect_bank.clj`: `build-effects-by-category` (`:37-55`) skips `:hidden?` effects, and the Zone tab shows only while its category has an effect left (`:103-107`).
- `css/zones.clj` (NEW), in both lists of `css/core.clj:36-82`: `.target-chip` (`-inherited`, `-missing`), `.target-from`, `.grid-cell-target-badge` (`-inherited`), `.grid-column-header`, `.zone-map-pad` (`-active`, `-pending`, `-learning`, pseudo-class `:blink`), `.zone-map-warning`.
- `subs.clj`: NEW `zone-map-state`, `zone-maps-list`, `zone-map [id]`, `zone-labels`, `column-target [col]`, `default-target`, `names-ctx` (Z2's `target-groups-by-id` and the labels), `cue-target-explain [col row]`, `cell-target-badge [col row]`, `zones-in-use`, `zones-in-use?`, `map-pad [id]` (booleans and bindings only), `map-pad-ids`, `ref-usage [ref]`, `map-warnings [id]`.
- `vibes/library.clj`: NEW `audience-ref? [state groups ref]`: `ref` is in Z2's `audience-groups`, or is a zone whose lane in some map is (§8.6). `audience-cue?` (`:107-110`), the one test vibe Phase 3 left (`audience-cell?`, `unplayable` and so `playable-cells`, the lint and the Vibes tab's picker all go through it), asks it of `targets/cue-refs`, so it takes the state and the cell (for the column's target and the show default), not the cue alone. `audience-text` (`:405-416`) names a zone and the map that sends it there: "Groove: B2 reaches the audience through Zone B (the Crowd sweep map sends it to Crowd Scanning), so this vibe never plays it …". `zone-group-ids` (`:89-105`) goes. `subs/vibe-audience-groups` (`subs.clj:1145-1150`, today "from the projectors alone", and reading `[:rig]` too after Z2, 04 §4) also reads `[:zone-maps]`, the column targets and the show default, so the picker's `vibe-cell-blocked` (`:1155-1165`) re-runs when a map or a default changes, and only then.

## 5. State placement

| Path | Lives in | Written by | Undo / unsaved? |
|---|---|---|---|
| `:target` on items, groups, tracks, folders, clips and cues | `[:chains :cue-chains]` (document) | the Target events, timeline ops, the migration (before `mark-clean`) | yes / yes |
| `[:grid :column-targets] {col target}` | `grid` domain, document | `:grid/set-column-target` | yes / yes |
| `[:config :default-target]` | `config`, document | `:zones/set-default-target` | yes / yes |
| `[:zones :labels] {zone "text"}` | NEW `zones` domain, NEW document root | `:zones/set-label` | yes / yes |
| `[:zone-maps id] {:id :name :lanes [ref …] :overrides {zone ref}}` | NEW `zone-maps` domain, NEW document root | `:zone-maps/*` | yes / yes |
| `[:config :zone-map-pads id] {:bar? true}` (a pad's setting) | `config`, document | `:zone-map/set-pad-bar` | yes / yes |
| trigger-map `{:type :zone-map :target id}` | `[:config :input :trigger-map]` | learn, `:zone-map/clear-control` | yes / yes |
| `[:zone-map :active]` | NEW `zone-map` domain, **not a document root**; saved | `zone-maps/schedule` (at once), `:show-clock/zone-map-due`, Stop, Retrigger, "Use now", deleting the active map | **no / no**: live, saved with the show (D-14) |
| `[:zone-map :pending] {:id :map :at-beat :bar?}` | the same domain; not saved | `zone-maps/schedule`, the commit, `reset-session` | no / no |
| `[:config :preview :grid-cells i :target]` | a pref (as today) | Z3's `:preview/set-cell-zone`; the migration | no / yes |
| `[:ui :zone-maps :selected]` | UI | `:zone-maps/select` | no / no |
| snapshot `:routing :map` | per tick | the show clock | – |

**The live policy of `[:zone-map :active]`, exactly.** An effect cell's `:active?` sits inside a document root, so it needs `entry-equiv?` to ignore it (`state/history.clj:118-126`) and `restore-document` to graft it back (`:235-260`). The active map gets the same three properties more simply, as BPM does by being ignored:
1. `state/domains.clj`: `(defstate zone-map … {:active {:default :map/left-right} :pending {:default nil}})`.
2. `state/history.clj`: **not** added to `document-roots` (`:48-50`), `extract-document-state` (`:52-64`) or `restore-document` (`:262-278`). So a switch is never an undo step (`wrap-history` takes the `document-roots-identical?` fast path, `events/handlers/history.clj:218-221`), never unsaved (`next-dirty`, `:271-272`), and an undo never moves it. The policy table at `:14-22` gains the row "live, own domain: `[:zone-map :active]` (saved by `persistent-state-mapping`)".
3. `state/persistent.clj:49-52`: `{:path [:zone-map] :keys [:active]}` in `:project-metadata`, so it is saved, and a file without it loads `:map/left-right` (`replace-at-path`, `:128-143`). New Project gets the default.
4. `reset-session` (`:151-169`): `(assoc-in [:zone-map :pending] nil)`.
5. Tests: `zone-map-active-is-live-test` beside `undoable-changed?-policy-test` and `persisted-changed?-policy-test` (`test/laser_show/state/history_test.clj:54-80`).

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? / undo label |
|---|---|---|---|
| `:cue-chain/set-item-target {:col :row :item-path :target}` | NEW (replaces `:cue-chain/set-item-zone`) | item and group chips, "Target ▸" | yes / "Change Target" |
| `:cue-chain/set-target {:col :row :target}` | NEW (replaces `:set-destination-zone-group`) | both editors' footers | yes / "Change Cue Target" |
| timeline ops `:set-clip-target`, `:set-track-target {:id :target}`; `:add-track`/`:add-folder` `:target` | NEW (replace `:set-clip-zone`, `:set-track-zone`) | header chip, inspector | yes / "Change Clip Target", "Change Track Target" |
| `:grid/set-column-target {:col :target}` | NEW | column header | yes / "Change Column Target" |
| `:zones/set-default-target {:target}`, `:zones/set-label {:zone :label}` | NEW | Zones and maps | yes / "Change Show Target", "Rename Zone" |
| `:zone-maps/create`, `/duplicate {:map}`, `/rename`, `/set-lane {:map :index :ref}`, `/add-lane`, `/remove-lane`, `/set-override {:map :zone :ref}` (nil removes), `/delete {:map}` | NEW | Zones and maps; `/delete` from the confirm dialog | yes / "New Zone Map", "Duplicate Zone Map", "Edit Zone Map", "Delete Zone Map". Delete also drops the map's bindings, its pad setting and a pending switch to it, and if it was active, `:active` becomes Left / Right. Undo brings the map, its keys and its setting back, but not the switch: `:active` is live (D-14) |
| `:zone-maps/request-delete {:map}` | NEW | the map editor's [Delete] | yes: opens Z0's confirm dialog (02 §4), as every delete asks first (02 §11). Its message says what goes with the map: "It's the zone map in use: the show switches to Left / Right." · "Its 2 learned keys and its pad setting go too." · or "Nothing else uses it." Its `:confirm-event` is `{:event/type :zone-maps/delete :map id}`. A built-in map has no Delete |
| `:zone-maps/select`, `:zone-maps/use-now {:map}` | NEW | the map list, "Use now" (an edit, not a performance: at once, spec §9) | yes / live, no label |
| `:zone-map/pointer {:map :fx/event}` | NEW | a pad's mouse and touch press | yes: `vibes/pointer-action` `:press` → `:zone-map/press` with `:bar?` from Shift |
| `:zone-map/press {:map :bar? :src :input-time}` | NEW | `:zone-map/pointer`, the trigger action | yes: returns `{:show-clock/read {:on-read {:event/type :zone-map/schedule …}}}` |
| `:show-clock/read` | NEW effect (inline) | `:zone-map/press` (Z5's pattern pads too) | effect |
| `:zone-map/schedule {:map :bar? :input-time :clock}` | NEW | `:show-clock/read` | yes: `zone-maps/schedule`; `:bar?` is Shift or the pad's setting |
| `:show-clock/zone-map-due {:id :map}` | NEW | the clock's reports | yes: commits only if `:id` is still the pending one |
| `:transport/stop`, `:transport/retrigger` | yes (`timing.clj:73-84`) | toolbar, menu, keys | yes: also commit a pending switch |
| `:zone-map/set-pad-bar {:map :bar?}`, `:zone-map/clear-control {:map}` | NEW | the pad menu | yes / "Change Pad Timing", "Clear Control" |
| `:input/start-learn {:target-action {:type :zone-map :target id}}`, `:input/cancel-learn`, `:input/learn-captured` | yes (`input.clj:276-305, 328-367, 390-398`, routed at `:480-484`) | the pad menu, learn | yes |
| `:preview/set-cell-zone {:cell-index :target}` | Z3's (`events/handlers/ui.clj:131-138` today) | the preview picker, now with zones | yes / pref |
| `:ui/set-active-tab :zones` | yes | the pad menu's "Edit maps…" | yes |

No handler reads the clock: `:show-clock/read` hands it the beats. The clock writes no app state: the commit is `:show-clock/zone-map-due`.

## 7. UX

### 7.1 The Target chip and its menu

```
 Target: [ (A · Beams → Left) ▾ ]  from column 3            ← dim and in brackets: inherited
          ┌──────────────────────────────────────────────┐
          │ ✓ Inherit (A · Beams → Left · from column 3)  │
          │   All                                         │
          │ ── ZONES · map: Left / Right ──────────────── │
          │   A · Beams → Left                            │
          │   B → Right                                   │
          │   New zone (C → Left)                         │
          │ ── GROUPS ─────────────────────────────────── │
          │   📌 Left  📌 Right  📌 Center  📌 Odd  📌 Even  │
          │   📌 Laser 1 · 10.0.0.5     📌 Laser 2 · …      │
          │   📌 Graphics   📌 Crowd Scanning · audience    │
          │ ──────────────────────────────────────────── │
          │   Combine ▸        ☑ A → Left  ☐ B → Right  … │
          │   Pin: A → 📌 Left                             │
          └──────────────────────────────────────────────┘
```
- A click on a row sets that one target; **Inherit** removes the level's own. **Combine ▸** ticks several (it stays open): they draw as one key.
- "→ Left" is the zone's lane in the **active** map (SQ-7). A lane that reaches nothing reads "→ nothing ⚠".
- Zones listed: A and B, every zone the show uses, then "New zone" with the next letter (Z4-Q3).
- Groups: the automatic ones in Z2's order, one per laser in rig order, then custom groups by name; "· audience" in orange marks a group that reaches the crowd. A missing group the target still names comes first, ticked: "⚠ Front (missing)".
- **Pin** (only when the target has a zone) swaps each zone for its lane now, so the content stops following the map.
- 📌 is FontAwesome `:thumbtack` in chips and badges, never an emoji; plain-text places say "pinned".

### 7.2 Cue Chain Editor and timeline

```
 CUE CHAIN                                   │ PRESET   Target: [ B → Right ▾ ]
   Circle  [A · Beams → Left · column 3]     │ GROUP    Target: [ A + B → Left + Right ▾ ]
   Wave    [B → Right]                       │          Items inside inherit this target unless they have their own.
 ▾ Pair    [A + B → Left + Right]            │
     Square [A + B → Left + Right · group Pair]
   Star    [Graphics, pinned]
 Footer:  Target: [ (A · Beams → Left) ▾ ] from column 3   Trigger: [Toggle ▾]   ⚡ Triggers...   …   Close

 Timeline header rows:   ▌ BEAT   (A)  M        ▌  Kick   B   M        (inherited targets in brackets, dim)
 Inspector:              TRACK PROPERTIES  Target [ B → Right ▾ ]      CLIP  Target [ (C → Left) ▾ ] from folder Beat
```
Both footers say "Target:" where they said "Default zone:", and their two dropdown copies become one `target-field`. Its chip keeps a tooltip, as UX Phase 3 gave every footer control one (§4.5).

### 7.3 The grid: badges and column targets

```
 Cue Grid                                 Zone map: [● Left / Right] [Odd / Even ◌] [Unison] [Crowd sweep]
 Click: play or stop · Double-click or right-click: edit · Drag: move
        1 · A          2            3 · 📌 L         4
  A  ┌────────── A┐ ┌──────────┐ ┌──────── 📌L/R┐ ┌─────── A+B┐
     │  circle    │ │  square  │ │  wave        │ │  star      │
```
- The badge sits top-right, dim when everything in the cue is inherited; its tooltip (the cell's, §4.5) names the lanes ("A → Left · B → Right (map: Left / Right)", "Pinned: ignores the zone map").
- A column number opens the menu in the `:column` scope; its tooltip says "Column 3: cues here without a target of their own use A → Left", or "Column 3: no default (cues use the show default: All)". The default is positional: a cue moved to another column follows the new column.

### 7.4 Map pads

```
 Perform:  ZONE MAP
           ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
           │ LEFT / RIGHT │ │ ODD / EVEN   │ │ UNISON       │ │ CROWD SWEEP  │
           │ now          │ │ next beat    │ │              │ │              │
           │ C2           │ │ C#2          │ │ Right-click to learn a control │
           └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘
```
- A press (mouse down or touch) switches on the next beat, at once within 50 ms of a beat; Shift, or the pad's "Switch on the bar", waits for the bar. With the clock thread off or the transport stopped it switches at once. Pressing the active pad cancels a pending switch; another pad replaces it.
- The active pad is lit blue; the pending one blinks amber at 4 Hz (`ext-clock-redraw`, from the snapshot's time) and says "next beat" or "next bar". Without a clock it stays amber.
- The row (Perform, under the fader meters) and the strip (Grid tab) show while the show uses a zone anywhere (Z4-Q1).
- Right-click: "Switch on the bar" (tick), "Learn control…", "Cancel learning", "Clear control", "Edit maps…". A learned key presses the pad, with the pad's bar setting; learning it again moves the key (Z4-Q2). A pad learns a note or a button, never a CC: moving a control is how the Vibes tab learns a fader (§4.4).
- A map switch isn't a press the vibes hear: it pulses no source, and BLACKOUT doesn't hold it back, so the map can be set before the lights come back (SQ-12).

### 7.5 Zones and maps (Zone Groups tab)

```
┌ ZONES AND MAPS ───────────────────────────────────────────────────────────────────┐
│ Show default target: [ All ▾ ]                                                     │
│ Zone labels:  A [Beams      ]   B [           ]   C [           ]   (label)        │
│ MAPS                         │ Crowd sweep                [Use now] [Duplicate] [Delete] │
│ ● Left / Right   built-in    │ Name   [Crowd sweep       ]                        │
│   Odd / Even     built-in    │ Lanes  1 [ 📌 Left ▾ ] ×   2 [ 📌 Crowd Scanning ▾ ] ×   [+ Lane] │
│   Unison         built-in    │ Zones  A → Left · B → Crowd Scanning · C → Center (override)  │
│   Crowd sweep                │ Overrides  [ C ▾ ] → [ 📌 Center ▾ ] ×   [+ Override] │
│ [New map]                    │ ⚠ Lane 2 (Crowd Scanning) reaches the audience.   │
└───────────────────────────────────────────────────────────────────────────────────┘
```
- A built-in map shows its lanes read-only: "Built-in maps follow the rig. Duplicate one to change it."
- **Delete** (custom maps only) asks first, with Z0's confirm dialog: "Delete "Crowd sweep"?", and what goes with it (§6).
- Lanes use the chip in the `:lane` scope: All or a group, never a zone. The zones line lists the show's zones, "(wraps)" where the lane index repeats.
- Warnings (`map-warnings`): "Lane 2 is empty: zones B, D go nowhere." · "Lane 2 (Graphics) has no projector: zones B, D go nowhere." · "Lane 2 (Crowd Scanning) reaches the audience." · "No lanes: every zone goes nowhere." · "Override C → Front: Front no longer exists."
- A group's details extend Z0's line: "Used by 3 cues", "Used by 3 cues · 9 more through Zone A", "Used by 3 cues · 11 more through Zones A, C", "Used by 9 cues through Zone A", or "Used by no cues".

## 8. Data examples and invariants

### 8.1 Shapes

```clojure
[:chains :cue-chains [2 0]]                      ; column 3
{:name "Beams"                                   ; no :target: inherits the column's
 :items [{:type :preset :id #uuid "…01" :preset-id :beam-fan}
         {:type :preset :id #uuid "…02" :preset-id :wave :target :zone/b}
         {:type :group  :id #uuid "…03" :name "Pair" :target [:zone/a :zone/b]
          :items [{:type :preset :id #uuid "…04" :preset-id :square}]}
         {:type :preset :id #uuid "…05" :preset-id :star :target :graphics}]}
[:grid :column-targets]   {2 :zone/a}
[:config :default-target] nil
[:zones :labels]          {:zone/a "Beams"}
[:zone-maps]              {:map/custom-1 {:id :map/custom-1 :name "Crowd sweep"
                                          :lanes [:auto/left :crowd] :overrides {:zone/c :auto/center}}}
[:config :zone-map-pads]  {:map/unison {:bar? true}}
[:config :input :trigger-map {:source :midi :channel 0 :note 37}] {:type :zone-map :target :map/odd-even}
[:zone-map]               {:active :map/left-right                                  ; live, saved
                           :pending {:id #uuid "…" :map :map/odd-even :at-beat 17.0 :bar? false}} ; runtime
```
The cue's frame keys: `{:zone/a beam-fan, :zone/b wave, [:zone/a :zone/b] square, :graphics star}`. With p1–p4 in rig order and p5 only in Graphics (out of All and, by D-33, of the automatic groups), under Left / Right the snapshot has `:routing {:map :map/left-right :lanes [:auto/left :auto/right] :overrides {} :refs {:auto/left [[:p1 nil nil] [:p2 nil nil]] :auto/right [[:p3 nil nil] [:p4 nil nil]] :graphics [[:p5 nil nil]] …} :outputs {…}}`, so `(rig/resolve-ref routing :zone/a)` is p1 and p2. From the tick where beat 17 is reached, `:map :map/odd-even`, `:lanes [:auto/odd :auto/even]`, and `:zone/a` is p1 and p3: **the same frame objects**, re-routed, no new render. That tick makes the effective map and its routing before it renders (spec §5).

### 8.2 Resolving a target

| Item | Own | Track | Parent | Fallback | `{:target :from}` | Shown |
|---|---|---|---|---|---|---|
| beam-fan | – | – | – | column 3: `:zone/a` | `:zone/a`, `:column` | A · Beams → Left · from column 3 |
| wave | `:zone/b` | | | | `:zone/b`, `:item` | B → Right |
| Pair (group) | `[:zone/a :zone/b]` | | | | union, `:item` | A + B → Left + Right |
| square, in Pair | – | – | Pair's | | union, `:group` `:via` Pair | … · from group Pair |
| star | `:graphics` | | | | `:graphics`, `:item` | 📌 Graphics |
| a clip on track Kick in folder Beat (folder `:zone/c`) | – | `:zone/c` `:via` Beat | | | `:zone/c`, `:track` | C → Left · from folder Beat |
| anything, with nothing set anywhere | | | | | `:all`, `:default` | All |

### 8.3 Migration (silent, idempotent)

| Old | New | Routes the same because |
|---|---|---|
| a cue's `[:destination-zone :zone-group-id] g` | the cue's `:target g`; `:all` and nil → no `:target`; `:destination-zone` goes | on an old show levels 5 and 6 are empty, so nil still ends at All |
| an item's, group's, track's or folder's `:zone-group-id g` | `:target g` | levels 1–3 are as before |
| the first enabled top-level Zone Selector with `:target-zone g`, on an item without `:zone-group-id` | `:target g` | that one is all routing read (`zone_effects.clj:66-76, 143-145`) |
| every other Zone Selector: disabled, without `:target-zone`, a second one, inside an effect group, on a track, in an effects-grid cell | removed | routing never read them (probe v20 for the grid) |
| preview cell `{:zone-group-id x}` | `{:target x}` | the same key |
| a reference to a built-in group Z2 removed | already `:auto/left` etc. when `zones-z4` runs (Z2's `zones-z2`) | not the same, by Z2's choice: content aimed at an empty built-in now reaches the automatic group (04 §12 S-2); its fixture checks the new routing |

```clojure
;; before: an old file
{:destination-zone {:zone-group-id :all}
 :items [{:type :preset :id 1
          :effects [{:effect-id :zone-selector :enabled? true :params {:target-zone :left}}
                    {:effect-id :scale :enabled? true}]}
         {:type :group :id 2 :zone-group-id :center
          :items [{:type :preset :id 3 :zone-group-id :right
                   :effects [{:effect-id :zone-selector :enabled? true :params {:target-zone :crowd}}]}]}
         {:type :preset :id 4
          :effects [{:effect-id :zone-selector :enabled? false :params {:target-zone :crowd}}]}]}
;; after: loaded, not unsaved
{:items [{:type :preset :id 1 :target :left :effects [{:effect-id :scale :enabled? true}]}
         {:type :group :id 2 :target :center
          :items [{:type :preset :id 3 :target :right :effects []}]}   ; its own zone beat its selector, as in routing
         {:type :preset :id 4 :effects []}]}                           ; a disabled selector never routed
```

### 8.4 When a switch lands (spec §9)

At 120 BPM the window is 0.050 s × 120 / 60 = 0.1 beat; in bar mode 0.025 bar.

| Global beats at the press | Mode | Fraction | Applies at | Wait |
|---|---|---|---|---|
| 16.04 | beat | 0.04 ≤ 0.1 | 16: now | 0 |
| 16.50 | beat | 0.50 | 17 | 250 ms |
| 16.93 | beat | 1 − 0.93 = 0.07 ≤ 0.1 | 17 | 35 ms |
| 16.04 | bar | 4.01 bars: 0.01 ≤ 0.025 | 16: now | 0 |
| 17.00 | bar | 4.25 bars | 20 | 1.5 s |
| 19.95 | bar | 4.9875 bars: 0.0125 left ≤ 0.025 | 20 | 25 ms |
| any | the clock thread off, the transport stopped, or no tempo | – | at once | 0 |

A press from a learned key is dated by its input: the beats are backed off by `bpm × (now − input-time) / 60000`, at most 250 ms, so a press the event agent took late (vibe probe v10: up to 180 ms) still counts from when it was played. A pad press has no input time and counts from when it is handled.

### 8.5 Grid badges

| The cue's resolved targets (enabled items) | Badge | Tooltip |
|---|---|---|
| All only | none | – |
| `:zone/a` | A | A · Beams → Left |
| `:zone/a`, `:zone/b` | A B | A → Left · B → Right |
| `[:zone/a :zone/b]` | A+B | A + B → Left + Right |
| `:zone/a` … `:zone/d` | A–D | A → Left · B → Right · C → Left · D → Right |
| `:auto/left`, `:auto/right` | 📌 L/R | Pinned: ignores the zone map |
| `:zone/a`, `:graphics` | A 📌 Graph… | A → Left · Graphics (pinned) |
| all inherited from column 3 | the same, dim | … · from column 3 |

Short names: Left "L", Right "R", Center "Ctr", "Odd", "Even", a laser by its place ("#2"), a custom group by its name cut to 5 letters.

### 8.6 The audience rule

A cue is audience-capable when one of its refs (`targets/cue-refs`, column and show default included) passes `audience-ref?`:
- a group, All or a laser's own group: it is in Z2's `audience-groups`, `(into #{:crowd} (rig/refs-reaching state crowd?))`, every ref that reaches a projector tagged `:crowd-scanning` (or a virtual projector of one). For a disabled laser that is All unless excluded, its own groups and its laser ref, as carefully as today (`library.clj:72-87`); automatic groups follow the rig as it is;
- a zone: its lane, in **every map that exists in the show** (the three built-ins and every custom map, overrides included), is one of the above.

**Why every map, not the active one.** A vibe checks a cue once, when it picks it (`playable-cells`, `library.clj:130-140`), but the map changes while the cue plays: a pad, a learned key, a pending switch landing a beat later, Z5's Map cycle. Only a check over every map holds for the whole time the cue plays. It costs maps × the zones a cue uses. The Vibes tab's picker and lint (vibe Phase 3) ask the same question through `audience-cue?`, so they agree with what the vibe will play.

Rig: p1–p4; p5 tagged `:crowd-scanning`, with `:exclude-from-all?` (and so, by D-33, in no automatic group); custom `:crowd` = p5; custom map "Crowd sweep" `[:auto/left :crowd]` with `{:zone/c :auto/center}`.

| Cue | Why | Audience-capable? |
|---|---|---|
| A only | Left / Right: Left; Odd / Even: Odd; Unison: All (p5 excluded); Crowd sweep: Left | no |
| B only | Crowd sweep sends B to Crowd | **yes** |
| C only | it would wrap to Left, but the override sends it to Center | no |
| pinned `:auto.laser/p5` | p5 | **yes** |
| All, with p5 not excluded from All | All reaches p5 (as `test/laser_show/vibes/library_test.clj:64-65` pins today), and every zone does too, through Unison | **yes** |
| B, once "Crowd sweep" is deleted | no map sends B to p5 | no |

This is also the predicate vibe Phase 8b's `audience-fx?` and Z5's zone lists use for refs.

### 8.7 "Used by" (Q-Z5 ★ A)

Twelve cues: 3 name Left directly, 9 draw into A, 2 into C. Under Left / Right, A and C go to Left: "Used by 3 cues · 11 more through Zones A, C". Under Odd / Even: "Used by 3 cues". A cue that does both counts once, as direct, so the two numbers add up to every cue that reaches the group. Counted from `cue-refs` (column and show defaults included), through the **active** map only (Q-Z5 A: not every map).

### 8.8 Invariants

1. Every stored `:target`, column and show default, and every frame key is in normal form; `normalize-target` is idempotent.
2. **One render per cue per tick**, and frame keys never depend on the zone map: a switch re-routes the same frame objects.
3. **Old shows route as before**: every fixture of §9 sends each projector the same points at each beat as before the phase, except content aimed at an empty built-in group (04 S-2).
4. The migration is idempotent and silent: twice is once (`identical?`), no undo step, not unsaved, no notice.
5. The resolver is pure and total: one target per item, and `:from` names the level that set it.
6. The show clock never writes `[:zone-map]`; a switch reaches `:active` through `:zone-map/schedule`, `:show-clock/zone-map-due`, Stop, Retrigger or "Use now", each through `zone-maps/commit`.
7. A pending switch is effective from the first tick whose global beats reach `:at-beat` until the commit lands, so the output switches on the beat however late the agent is. The tick makes the effective map before its routing, and the routing before its render (spec §5).
8. A switch is live: no undo step, not unsaved; saved, and the show reopens on it (D-14); New Project is Left / Right.
9. A zone never resolves to a zone; an unknown map id is Left / Right; an empty lane resolves to nothing.
10. Unison sends every zone to All (spec invariant 10).
11. A vibe never picks a cue that any zone map in the show can send to an audience projector, unless the vibe allows the audience (V6), and the Vibes tab's picker and lint say the same of it.
12. Every place that sets a target uses `target-control`: one menu, one copy.
13. The Zone Selector is offered nowhere and read by no routing; its id stays registered.
14. A switch re-renders the pads, the badges with zones and open Target chips; no other cell or pad.
15. Deleting a group never moves a zone that didn't name it to another lane (SQ-13), and deleting a map always asks first.

## 9. Tests (named test vars, fixtures)

Pure first. Fixtures: the cues of `test/laser_show/routing/zone_routing_integration_test.clj:26-116, 488-533` and its setup (`:124-135`); the probe v19 rig (vibe Phase 8 §7); Z3's `projector-frame` tests (`test/laser_show/backend/multi_engine_test.clj:272-309` today); `show-clock/tick!` by hand, with `init!` and `ticks!` (`test/laser_show/services/show_clock_test.clj:52-74`, as `snapshot-shape-test`, `:228-250`, does).

- **Legacy shows, first** (NEW `laser-show.routing.legacy-shows-test`; EDN under `test/laser_show/routing/legacy_shows/`: `destination.edn`, `item-zones.edn`, `selectors.edn`, `timeline.edn`, `preview.edn`, `starter.edn`, and `golden.edn` recorded by `legacy-shows/record-golden!` before any Z4 change): `old-shows-route-as-before-test` (load through `apply-loaded-project`, tick at beats 0, 1.5 and 7.25, compare every projector's frame), `legacy-loads-are-clean-test`.
- `laser-show.routing.targets-test` (NEW): `normal-form-test`, `zone-index-round-trip-test` (a, z, aa, az, ba), `resolve-levels-test` (§8.2, a row each), `group-passes-through-what-it-inherits-test`, `track-targets-walk-folders-test` (a cycle stops), `toggle-builds-unions-test`, `cue-refs-include-disabled-items-test`, `badge-text-test` (§8.5), `ref-name-test`.
- `laser-show.routing.zone-maps-test` (NEW): `built-in-maps-test`, `zones-wrap-over-lanes-test`, `overrides-beat-the-wrap-test`, `empty-lanes-go-nowhere-test`, `effective-map-order-test` (pending due, the cycle's pick, a paused cycle, active, unknown id), `switch-at-table-test` (§8.4), `schedule-test` (now, pending, replace, cancel, due-first commit), `pin-test`, `map-warnings-test`.
- `laser-show.routing.refs-test` (Z0's): `z4-places-are-re-targeted-test` (a union, a column, the show default, a lane and an override take the delete's re-target), `nothing-empties-a-lane-and-drops-an-override-test` (SQ-13: the other zones keep their lanes).
- `laser-show.state.migration-test` (Z2's): `destination-becomes-the-cue-target-test`, `zone-groups-become-targets-test`, `enabled-selector-becomes-the-target-test`, `own-zone-beats-the-selector-test`, `first-enabled-selector-wins-test`, `every-selector-goes-test`, `preview-cells-take-targets-test`, `z4-runs-after-z2-test` (an empty built-in Left ends as `:target :auto/left`), `migration-is-idempotent-test`, `pasted-old-cue-is-migrated-test`.
- `laser-show.services.frame-service-test` (existing): `frame-keys-are-normal-targets-test`, `union-target-is-one-key-test`, `column-and-show-defaults-reach-items-test`, `frame-keys-ignore-the-map-test`. `test-generate-frames-by-zone-inherits-default` (`:69`) moves to `:target`.
- `laser-show.routing.rig-test` (Z2's file, Z3's tests): `zone-resolves-through-the-effective-map-test`, `unison-sends-every-zone-to-all-test`, `wrapped-zones-share-a-lane-test`, `resolve-ref-callers-see-zones-test`. `laser-show.routing.compose-test` (Z3's): the two tests Z3 names for Z4, `example-i-unison-draws-every-zone-everywhere-test` and `example-j-zones-wrap-over-two-lanes-test` (spec §6.2 i and j).
- `laser-show.services.show-clock-test` (existing): `snapshot-names-the-effective-map-test`, `pending-map-is-effective-once-due-test` (the routing of the tick that reaches the beat already uses it), `due-switch-is-reported-test` (once, again after 250 ms), `clock-writes-no-zone-map-test`. `one-state-and-input-read-per-tick-test` (`:381-409`), Z3's `routing-is-made-before-the-render-test` and Phase 6's `vibe-report-is-sent-once-per-interval-test` (`:146-174`) still pass.
- `laser-show.events.zone-maps-handlers-test` (NEW): `press-reads-the-clock-then-schedules-test`, `press-within-50-ms-switches-now-test`, `shift-and-pad-setting-wait-for-the-bar-test`, `no-clock-switches-at-once-test`, `input-time-backs-the-beats-off-test`, `due-commit-matches-the-id-test`, `stop-and-retrigger-commit-the-pending-switch-test`, `switch-is-live-test`, `delete-map-asks-first-test`, `delete-active-map-falls-back-test` (its keys, its pad setting and a pending switch to it go too), `map-editing-is-undoable-test`.
- `laser-show.events.handlers.triggers-test` (existing, beside the vibe and tap-tempo action tests, `:109-155`): `zone-map-action-presses-the-map-test`, `zone-map-action-carries-the-input-time-test`. `laser-show.events.handlers.input-test` (beside `relearning-a-role-moves-its-binding-test`, `:147`): `relearning-a-map-pad-moves-its-key-test`, and `map-pad-learn-ignores-a-cc-test` as `learn-a-role-ignores-cc-test` (`:406`) does for a role.
- `laser-show.events.cue-chain-handlers-test` (existing, the four zone tests `:385-456` rewritten): `set-item-target-test`, `inherit-removes-the-target-test`, `set-cue-target-test`. `laser-show.events.timeline-handlers-test`: `add-track-does-not-stamp-zone-test` (`:122-129`) becomes `…-target-test`; `open-editor-converts-without-changing-output-test` (`:81-100`) uses `:target`. `laser-show.timeline.model-test`: `auto-initialize-tracks-test` (`:82`) reads targets; NEW `target-ops-test`.
- `laser-show.state.history-test`: `zone-map-active-is-live-test`, `zone-maps-and-labels-are-undoable-test`, `column-targets-are-undoable-test`. `laser-show.state.persistent-test` (after the vibe and fader tests, `:241-330`): `zone-data-survives-save-and-load-test`, `show-reopens-on-its-map-test`, `pending-switch-is-not-saved-test`, `session-reset-clears-the-pending-switch-test`. `laser-show.state.templates-test`: `starter-cues-inherit-test`.
- `laser-show.vibes.library-test` (existing; `audience-cell-test`, `:52`, moves to targets, and `audience-cells-never-play-unless-allowed-test`, `:89`, still passes): `zone-in-any-map-reaches-the-crowd-test` (§8.6), `override-counts-test`, `unison-counts-when-all-reaches-the-crowd-test`, `lint-names-the-zone-and-its-map-test`.
- `laser-show.subs-test`: `ref-usage-direct-and-through-test` (§8.7), `cell-badge-sub-test`, `map-switch-rerenders-only-zone-badges-test` (invariant 14, the pattern of `cell-display-data-ignores-unrelated-changes-test`, `:68`), `map-pad-sub-test`, `picker-blocks-a-zone-cue-that-reaches-the-crowd-test` (`vibe-cell-blocked`). NEW `laser-show.views.components.target-control-test`: `menu-model-sections-test`, `menu-model-scopes-test`, `combine-rows-toggle-test`.
- UI, NEW `laser-show.ui-test.zones-test` (`with-toolkit` once, `with-app` each; `with-input-mapper` as `test/laser_show/ui_test/perform_test.clj:29-39`; `with-clock`, which Z3 moves to `test/laser_show/ui_test/fixtures.clj` from `preview_test.clj:70`): `item-chip-sets-a-zone-test`, `chip-says-where-an-inherited-target-comes-from-test`, `combine-makes-a-union-test`, `new-zone-takes-the-next-letter-test`, `pin-freezes-the-lane-test`, `footer-says-target-test`, `timeline-chip-and-inspector-set-targets-test`, `grid-badge-and-column-target-test`, `map-pad-switches-the-map-test` (no clock thread in `start-test!`: at once), `pending-pad-blinks-test`, `map-pad-learns-a-note-test`, `zone-maps-section-edits-a-map-test`, `deleting-a-map-asks-first-test`, `bank-offers-no-zone-selector-test`. Updated: `main-window-test/cue-chain-editor-footer-fits-test` (`:179-199`, "Target:" for "Default zone:") and `cue-chain-editor-footer-says-what-it-does-test` (`:201-215`: one combo left, Trigger, and the Target chip says what it chooses), `preview-test` (`:17-28`, `:105-116`, `:target`), `perform-test` NEW `map-row-shows-when-zones-are-used-test` (under Phase 5's meter row).

Run: `clj -M:mac-arm:test legacy-shows targets zone-maps refs migration frame-service routing.rig-test compose show-clock triggers handlers.input cue-chain-handlers timeline-handlers model-test history persistent templates library subs target-control`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.zones-test`, then `-n` main-window-test, preview-test, grid-test, perform-test, timeline-editor-test, then both full suites (the `targeted-ui-testing` skill).

## 10. Commit plan

Each step is one commit: its failing tests first (run them red), then the code, then the named tests green. The `legacy-shows` test runs in every step from 1 on.

| # | Commit (message) | Tests | ~d |
|---|---|---|---|
| 1 | `test(zones): old shows' routing pinned before targets arrive: six fixture shows and the frames every projector got from them` | `legacy-shows` (green: it records today) | 0.25 |
| 2 | `feat(zones): one normal form for targets, and the inheritance that says where each one came from` | `targets`, `target-control` (`menu-model` only) | 0.5 |
| 3 | `feat(zones): zone maps: Left / Right, Odd / Even and Unison, custom lanes that wrap and overrides, and the beat a switch lands on` | `zone-maps` | 0.25 |
| 4 | `feat(zones): cues, items, tracks and clips carry one :target; old shows convert on load, silently, and route as before` (render, handlers, timeline ops, `zones-z4`, paste, templates, Z0's walker learning `:target` and the defaults; the old dropdowns send the new events) | `migration`, `frame-service`, `cue-chain-handlers`, `timeline-handlers`, `model-test`, `templates`, `refs`, `legacy-shows` | 1 |
| 5 | `feat(zones): a grid column and the show can give a default target` | `history`, `persistent`, `frame-service` | 0.25 |
| 6 | `feat(zones): zones reach lasers through the map the snapshot names, worked out before each tick's render; the active map is live and saved with the show` | `routing.rig-test`, `compose` (i, j), `show-clock`, `history`, `persistent` | 0.5 |
| 7 | `feat(zones): a map switch lands on the beat, or the bar, from a pad press or any learned key` | `zone-maps-handlers`, `triggers`, `handlers.input`, `show-clock` | 0.5 |
| 8 | `feat(ui): one Target chip in the Cue Chain Editor (items, groups, the cue) that says where an inherited target comes from` | UI `zones-test` (chip tests), `main-window-test` | 0.75 |
| 9 | `feat(ui): the timeline's tracks and clips use the Target chip` | UI `timeline-chip…`, `timeline-editor-test` | 0.25 |
| 10 | `feat(ui): grid cells show where their cues go, and a column's number sets its default` | `subs`, UI `grid-badge…`, `grid-test` | 0.5 |
| 11 | `feat(ui): zone map pads on the Grid tab and on Perform, blinking while a switch waits` | UI pads tests, `perform-test` | 0.5 |
| 12 | `feat(ui): zones and maps on the Zone Groups tab: labels, the show default, custom maps with warnings (deleted only once you say yes, and a deleted group leaves their other lanes alone), and who uses each group` | `subs`, `refs`, `zone-maps-handlers` (`delete-map-asks-first-test`), UI `zone-maps-section…`, `deleting-a-map-asks-first-test`, `preview-test` | 0.5 |
| 13 | `feat(vibes): a cue that any zone map can send to the crowd is audience-capable, and the Vibes tab's picker and lint say which zone and map` | `library`, `subs` | 0.25 |
| 14 | `refactor(zones): the Zone Selector leaves the bank, and the old zone readers go` | UI `bank-offers…`, `effects_coverage`, both full suites | 0.25 |

About 6.25 days; if it runs long, map overrides (UI only) and the through-the-map count can move to Z5. Then write the "as built" note into this doc, as the vibe and UX plans do.

## 11. Out of scope / alternatives considered

- **Zone patterns, the Map cycle, pattern pads, Phase 8's pickers taking refs**: Z5 (`07`). Z4 leaves the `cycle` argument of `effective-map-id` nil and never sets `[:zone-map :cycle-paused?]`. **The step grid**: Z6. **The rig file**: ZR.
- **A target per vibe** (Q-Z4 B): no; a vibe uses the cues' targets.
- **Show data under `:config`**, as `[:config :vibes]` is: fewer edits to `state/history.clj`, but the spec names `[:zone-maps]` and `[:zones :labels]` and other phases cite them (SQ-1).
- **Keeping `:zone-group-id` or the Zone Selector as aliases**: rejected. Two keys meaning one thing is the bug; routing reads no raw parameter (spec §2).
- **Letting the clock compute `:at-beat`** (pending without a beat): rejected. The clock would keep scheduling state in its runtime and the UI couldn't say "next bar".
- **Blinking from `[:timing :beat-index]`**: rejected; it changes once a beat, so a pending switch would never blink.
- **Shift-click to combine**: rejected for the Combine submenu. A menu item's action carries no modifier, and touch has none.
- **Checking only the active map for the audience**: rejected (§8.6).
- **Setting several items' targets at once, a "Map: …" chip in the main toolbar, a colour per zone, renaming the Zone Groups tab**: later.
- **Fades between maps**: a switch is a cut (overview §8).

## 12. Spec questions and open questions

**Spec questions** (the code against `01`, or `01` silent; what this doc chose)
- **SQ-1 · Paths.** §12 names `[:zone-maps id]`, `[:zones :labels]` and `[:grid :column-targets]`; the history policy only sees `[:grid :size]` (`state/history.clj:60`) and the file only saves it (`persistent.clj:51`). *Chose:* the spec's paths, with the edits of §4.3 and §5.
- **SQ-2 · "No clock running".** The clock thread always runs in the app and the global clock advances whether anything plays (`cue_timing.clj:78-122`), and Stop and Retrigger restart it at 0 (`show_time.clj:108-110`), which §9 doesn't cover. *Chose:* "no clock" is the thread not running, the transport stopped or a BPM ≤ 0: at once. Stop and Retrigger commit a pending switch at once (so does BLACKOUT, which stops the transport).
- **SQ-3 · The beats at the press.** Handlers are pure and none sees the beats. *Chose:* a NEW inline effect `:show-clock/read`, as `:link/sync-to-downbeat` does, plus the input-time correction of §8.4.
- **SQ-4 · "An event the clock sends with its status".** `:show-clock/status` is only sent when the beat, stats or MTC text change (`show_clock.clj:122-146`). *Chose:* a report in `pending-reports`, deduplicated by `due-reports`, dispatched with the status at `show_clock.clj:190`.
- **SQ-5 · Which Zone Selectors convert.** §11 names enabled ones only. *Chose:* the first enabled top-level one with a `:target-zone` becomes the target (only it routed); every selector is removed, because one left behind would do nothing when switched on.
- **SQ-6 · `:from` through a group.** §2 says the parent group's resolved target is level 3. *Chose:* a child shows `:group` only when the group's own target set it; otherwise the group's `:from` passes through ("from column 3", not "from group Pair").
- **SQ-7 · "→ Left from the effective map".** The effective map is per tick and only the snapshot has it. *Chose:* the UI shows the active map; a pending switch shows on its pad; Z5 marks a cycling map.
- **SQ-8 · Beats per bar.** "From the clock, 4 by default", but there is no setting. *Chose:* 4, bars counted from global beat 0, as the beat squares (`show_clock.clj:135`) and the timeline (`timeline/layout.clj:224`) count.
- **SQ-9 · Bar mode from MIDI.** "Shift or the pad's setting", with no path for the setting. *Chose:* `[:config :zone-map-pads id :bar?]`, read for a pad press and for its learned keys alike.
- **SQ-10 · Where a zone resolves.** §5 puts zones in the resolver; Z3 left `routing-inputs`' `:zone-maps` nil and made `rig/resolve-ref` the only reader (05 §4, S-3; now spec §5). *Chose:* the routing carries the effective map's `:lanes` and `:overrides`, `resolve-ref` resolves zones from them, and `rig` requires `zone-maps` (never the reverse); Z3's target helpers move to `routing.targets` so nothing needs `rig` to read a target.
- **SQ-11 · Two choices made elsewhere that Z4 relies on.** Content aimed at an empty built-in now reaches the automatic group (04 S-2, chosen there): Z4's fixtures expect that. And Exclude from All also takes a laser out of the automatic groups (D-33, Q-Z2-1 B, confirmed): with A, every zone under Left / Right could reach an audience scanner placed on the strip, and §8.6 would mark nearly every zone cue as audience-capable.
- **SQ-12 (NEW) · A map switch as a pulse.** Vibe Phase 4a made every HIT, ACCENT and FLASH press, vibe change and cue start a pulse that effects can follow (`animation/pulses.clj:24-27`); the spec doesn't say whether a map switch is one. *Chose:* not in Z4. Nothing in Z4 or Z5 reacts to one, and a pulse written by the commit would be stamped a tick or more after the clock had already switched on the beat. If a reaction to a switch is wanted later, the clock can stamp a `:map` source itself, in its runtime, when the snapshot's `:routing :map` changes, with no write to app state; `pulses/sources` and `source-labels` gain it as Z5 adds `:zone-step`. A map pad isn't a vibe press either, so BLACKOUT, which holds back the vibe pads (`vibes/engine.clj:466-475`), doesn't hold it back; BLACKOUT stops the transport, so a pad pressed then switches at once (SQ-2).
- **SQ-13 (NEW) · What "Nothing" does to a map lane.** Z0's delete re-targets every place that names the group (02 §4), and Z4 adds map lanes and overrides to those places. For a cue, "Nothing (they inherit)" means the level above decides, which a lane has no equivalent of. *Chose:* a lane or override takes the re-target like any other place (All by default). With "Nothing", the lane is left empty, which spec §4 already allows: it reaches nothing, and the map editor says "Lane 2 is empty: zones B, D go nowhere". The override is dropped, so its zone wraps again. Taking the lane out instead would shift every later lane down one, so zones that never named the group would move to another group mid-show.

**Open questions for you** (★ = recommendation)

> **Z4-Q1 · When the map pads show.** Overview R-5 says "only when a playing cue uses zones".
> - **A ★** Whenever the show uses a zone anywhere (a cue, a column, the show default): you can pick the map before the drop, and a show without zones never sees them.
> - **B** Only while a playing cue uses zones (R-5 as written): nothing to pick until it plays.
> - **C** Always.

> **Z4-Q2 · Learning a key for a map pad again.**
> - **A ★** It moves: one key per pad, as the vibe pads (vibe Phase 2, invariant 6).
> - **B** It adds a second key, as a grid cell does.

> **Z4-Q3 · Which zones the Target menu lists.**
> - **A ★** A and B, every zone the show uses, and "New zone" with the next letter.
> - **B** Always A to D.
> - **C** As many as the active map has lanes (2 for Left / Right), plus "New zone".

**Open questions found while building** (2026-11-20; ★ = as built)

> **Z4-Q4 · The show lock and targets.** The lock refuses what the show is built from (vibe Phase 7). Z4 counts where content goes as that.
> - **A ★** Refuse every target edit (a cue's, an item's, a track's, a clip's, a column's, the show's), the zones' labels and every map edit; let a pad, its key, a due switch, Use now and selecting a map through. A cue's other edits go through, as before.
> - **B** Refuse the column's and the show's defaults and the maps only (they re-route many cues at once); a cue's own target is a cue edit, and goes through like the rest of the Cue Chain Editor.

> **Z4-Q5 · Where ZONES AND MAPS sits on the Zone Groups tab.**
> - **A ★** Under the lasers and the details, so the groups' list keeps the tab's height (at 1200 × 800 it shows six automatic groups, three lasers and two of yours whole).
> - **B** Under all three columns, as §4.5 planned: the list scrolls sooner.
> - **C** A tab of its own, "Zones".

> **Z4-Q6 · How loud the Vibes tab is about a zone cue that can reach the crowd.**
> - **A ★** A warning, as V6's audience rows: the cue is left out of the pool, and the row says which zone and which map.
> - **B** An error, as V10's table does for a zone effect: the checklist isn't done until you allow the audience or take the cue out.

> **Z4-Q7 · Z0's walker still reads the old keys** (`:zone-group-id`, `:destination-zone`, a Zone Selector's `:target-zone`) when a group is deleted or renamed. Loading and pasting convert them, so only data written by hand or a test still has them.
> - **A ★** Keep them until Z5, then take them out with the fixtures that use them (`zone_groups_handlers_test`).
> - **B** Take them out now.

## 13. As built (2026-11-20, `64fab99`..`b44e388`, on `master`)

Built in worktree `zones-z4` off `c067030` (Z3 and ZR on `master`), while the output drivers were built in worktrees of their own. The ★ answers are built: Z4-Q1 A (the pads show while the show uses a zone anywhere), Z4-Q2 A (learning a pad's key again moves it), Z4-Q3 A (the menu lists A, B, the show's zones and New zone), and from `10-questions.md` Q-Z1 A (zones wrap over the lanes), Q-Z4 A (a column and the show can give a default), Q-Z5 A (Used by counts direct and through the map in use, apart) and Q-Z6 A (letters, with a label per show). SQ-13 is built as chosen: a deleted group's map lane is left empty, never taken out. Step 13 follows V10 A. Z5's Map cycle argument of `effective-map-id` stays nil. `legacy-shows` ran in every step, and `golden.edn` was recorded once, at `c067030`, before any Z4 change. **Not done: seeing it on a real projector** (00 R-1): a map switch on the beat, and a zone cue under each built-in map, at 25 and 40 kpps.

### Where it landed

| # | Commit | Step (§10) |
|---|---|---|
| 1 | `64fab99` | NEW `routing/legacy_shows_test.clj` and six fixture shows (`destination`, `item-zones`, `selectors`, `timeline`, `preview`, `starter`), with `golden.edn` recorded at `c067030` by `record-golden!` |
| 2 | `f16c001` | NEW `routing/targets.clj`: normal form, zones and their letters, `cue-fallback`, `resolve-item`, `inherited`, `track-targets` (cached by identity), `explain-cue`, `cue-refs`, `toggle`, `zone-lane`, `pin`, names and texts, badges; NEW `views/components/target_control.clj` with `menu-model` only |
| 3 | `2133372` | NEW `routing/zone_maps.clj`: the built-ins, `all-maps`, `map-by-id`, `effective-map-id`, `switch-at`, `press-beats`, `schedule`, `commit`, `map-warnings`, `next-custom-id` |
| 4 | `76b7790` | `:target` everywhere: the render (`targets/resolve-item`), the cue chain and timeline handlers and ops, `state.migration/cue-z4`, `zones-z4`, `items-z4`, `effects-z4` on load and paste, Z0's walker (`routing/refs.clj`) learning targets, unions and the new places, the templates; the old views send the new events |
| 5 | `ef2aae9` | `[:grid :column-targets]` and `[:config :default-target]` through `render-context` and `render-cue`; `:grid/set-column-target`, `:zones/set-default-target`; NEW `events/handlers/zone_maps.clj`; Z0's delete question names columns and the show default |
| 6 | `408dbc1` | NEW `zones`, `zone-maps` and `zone-map` domains, their history and saving; `effective-map-id` in `tick!` before the routing; `rig/routing` carries the map's `:lanes` and `:overrides`, and `resolve-ref` resolves a zone; the preview counts a zone for its lane |
| 7 | `ae1e39b` | NEW inline effect `:show-clock/read`; `:zone-map/pointer`, `press`, `schedule`, `set-pad-bar`, `clear-control`, `:zone-maps/use-now`, `:show-clock/zone-map-due`; Stop and Retrigger commit a pending switch; the trigger action `{:type :zone-map}`; learning moves a pad's key |
| 8 | `2a48f48` | `target-control`'s `menu-items`, `target-chip`, `target-field`; NEW `css/zones.clj`; the zone subs; the Cue Chain Editor's item, group and cue chips, Target ▸ and its labels; the timeline cue editor's footer |
| 9 | `79f9509` | the timeline's header chips (compact) and Target ▸, the inspector's clip and track fields; `explain-cue`'s `:item-inherits` and `:track-inherits` |
| 10 | `ca0f08b` | the cells' badges and their tooltip line (`subs/cell-target-badge`, `cell-target-tooltip`); `grid-tab`'s `:column-component`, `target-control/column-target-header` |
| 11 | `d51b309` | NEW `views/components/zone_map_pads.clj`: the Grid tab's strip (`grid-tab`'s `:header-extra`) and Perform's ZONE MAP row; `subs/map-pad`, `map-pad-ids`, `map-pad-bindings` |
| 12 | `23639b8` | NEW `views/components/zone_maps_panel.clj` on the Zone Groups tab; the `:zones/set-label` and `:zone-maps/*` edits, delete asking first; Used by through the map (`zone-maps/usage`, `subs/ref-usage`); the preview picker's Zones; a group's delete names the maps that use it |
| 13 | `41640d1` | `vibes.library/audience-ref?`; `audience-cue?`, `unplayable` and `playable-cells` over `targets/cue-refs` and every map; the lint names the zone and its map |
| 14 | `b44e388` | the Zone Selector hidden (its id registered), the bank's Zone tab only while it has an effect; `routing/zone_effects.clj:58-187` gone; `frame-keys-ignore-the-map-test` |
| 15 | this commit | this note |

### Deviations from the plan

- **Where the pure pieces live.** `zone-lane` and `pin` are in `routing.targets` (the target texts need a lane, and `targets` requires nothing); `routing.zone-maps` names them. `rig` requires `zone-maps`, never the reverse, and Z3's `rig/normalize-target`, `key-refs` and `zone-index` are aliases of `targets`'. So `map-warnings` takes `[m {:names :zones :reach :audience}]`, not `[state m names]`: `zone-maps` can't require `rig` for the reach.
- **Small rules the plan left open:**
  - `schedule` commits at once when the beat `switch-at` gives is already reached (a learned key's press, backed off to its input time, can land behind now).
  - `targets/names` is `{:groups :order :labels :places}`: Z2's order for the menu, and a laser's place for its badge ("#2").
  - `target-text` names a zone inside a union by its letters only, zones first, as frame keys are ordered: "A + B → Left + Right".
  - The badge's tooltip line ends the zones with "(map: Left / Right)", before the pinned refs: "A · Beams → Left (map: Left / Right) · Graphics (pinned)". §8.5's table has no map name; §7.3's has it.
  - `explain-cue` also gives `:item-inherits` and `:track-inherits`, what each level would inherit with its own target cleared: the Inherit row and the "from …" of every chip. The Cue Chain Editor reads them too since step 9, so a group's child says "from group Pair".
  - A lane or an override is All or one group, never a zone or a union (the `:lane` scope has no Combine). New map starts with Left / Right's lanes, + Lane adds All, + Override takes the first zone listed without one, to All, and Duplicate names the copy "Odd / Even copy" (then "… copy 2").
- **The migration mirrors the routing it replaces.** The first enabled top-level Zone Selector gives the target; when that one has no `:target-zone`, there is none (the old routing read only the first). Every selector goes, the effects grid's too. `auto-initialize-tracks` makes a "Default" folder with no target first, then a folder per distinct item target; the items keep their own targets. `convert-to-timeline`'s second argument is names, `{ref {:name}}` (the timeline handler passes `rig/target-groups` by id).
- **Step 4 did a little more than §10 says,** so nothing went blind between steps: `vibes.library`'s audience rule read `:target` from step 4 on (step 13 replaced it); a list paste of `:cue-chain-items` runs `migration/items-z4` and a grid paste `migration/cue-z4`; `refs/cue-refs` names `:all` only for a cue with no target of its own. The old views kept their combos and menus until steps 8 and 9, sending the new events.
- **Z0's delete question names more places:** "2 cues, column 3, the show default, the map Crowd sweep and 1 projector use it.", and it offers where to send them when only a column, the show default or a map uses the group (Nothing leaves a map's lane empty, SQ-13). `zone-groups/uses-text`, `columns-using` and `maps-using` are public.
- **Routing:** with a nil or unknown map, `rig/routing` uses Left / Right's lanes, and its `:map` stays as given. The preview's `:ref-frames` counts a key once for each lane its zones go to. `[:zones]` loads with `:merge-defaults?`.
- **The UI, where it differs from §4.5 and §7:**
  - The Grid tab's column number is a menu button drawing "3 · 📌 L"; its text is "3 · pinned L".
  - The pads' ids are the map's id, not a number: `#zone-map-pad-odd-even` on Perform, `#zone-map-chip-odd-even` in the Grid tab's strip. An idle pad with Switch on the bar says "on the bar".
  - The pending pad blinks from the snapshot's `:at-ns` (when it was published): the snapshot has no tick time. With no tick, or a stale one, it stays amber.
  - **ZONES AND MAPS sits under the lasers and the details, not under all three columns.** Under all three, the groups' list got too short at 1200 × 800, and Z2's `zone-groups-tab-test` could no longer click the last group. The tab fits 1200 × 800 with three lasers and two groups (checked on a screenshot), and the Projectors tab is unchanged. With five maps or more the section scrolls (it is 290 px).
  - The show's default chip says "All" when nothing is set, not "(All)", and a lane that names nothing says "Empty", in orange.
  - Used by takes both of its numbers from `subs/ref-usage` (`targets/cue-refs`, through the map in use). Z0's count was `refs/cues-using`; they differ for a cue with no preset (it now counts nowhere) and for a column's or the show's default (they now count).
  - The preview picker draws `menu-model`'s `:preview` rows, so it is the one menu too (invariant 12): Master, All, ZONES (only while the show uses one, by `ref-name`, "A · Beams"), GROUPS. Its Master row keeps the app's "Master (everything)", not §4.5's "Master (all content)"; `menu-model` says the same.
- **The audience rule (step 13):**
  - `audience-cue?` takes the state and the cell, and reads the cue's refs where its content goes (`targets/cue-refs`), as §4.5 says. So a track's or folder's target counts for the clips on it only, and a cue's own target that every item overrides counts for nothing. Before, the cue counted for every target it named anywhere. `audience-cell-test` puts its clip on the track.
  - NEW `subs/vibe-audience-routing` (the maps, the column defaults and the show's) feeds the picker's `cell-blocked`, so it re-runs when one of them changes, and only then. §4.5 has `vibe-audience-groups` read them; it stays the groups, which the Target menu's "· audience" and the map warnings read. `reaction-inputs` reads the maps and both defaults too.
  - The lint row stays a warning, as V6's audience rows are: the cue is left out of the pool and the row says why. V10's table makes it an error for a zone *effect*, which is Z5's and 8b's. Asked as Z4-Q6.
- **The show lock** (the choices; each blocked event has an example in `show-lock-test`'s `structure-edits`):
  - refused, as structure: `:grid/set-column-target`, `:zones/set-default-target`, `:zones/set-label`, `:zone-map/set-pad-bar`, `:zone-map/clear-control`, the map edits `:zone-maps/create`, `duplicate`, `rename`, `set-lane`, `add-lane`, `remove-lane`, `set-override`, `request-delete` and `delete`, and the target edits `:cue-chain/set-target`, `:cue-chain/set-item-target`, `:timeline/set-track-target` and `:timeline/set-clip-target`. A cue's other edits go through, as before. Asked as Z4-Q4.
  - let through, as a performer's: `:zone-map/pointer`, `:zone-map/press`, `:zone-map/schedule`, `:show-clock/zone-map-due`, `:zone-maps/use-now`, and `:zone-maps/select`.
  - `:zone-maps/request-delete` changes no document, so its example is its own check: locked, no dialog opens and the notice names it. `show-lock-test`'s starter gained a pad's key, a map of yours and a timeline cue.
  - `:timeline/apply-op` isn't refused (it carries every timeline edit), so its `:set-clip-target` and `:set-track-target` ops would go through; nothing sends them since step 9.
- **Step 14:** `routing/refs.clj` still reads `:zone-group-id`, `:destination-zone` and a selector's `:target-zone`: Z0's walker re-points them in data that wasn't migrated (Z0's handler tests use them). No routing reads them. Asked as Z4-Q7.
- **Beyond §9:** `explain-says-what-each-level-would-inherit-test`, `set-track-and-clip-target-test`, `set-column-target-test`, `pad-pointer-presses-with-shift-for-the-bar-test`, `pad-setting-and-control-test`, `column-default-reaches-its-cues-test`, `preview-zones-count-for-their-lane-test`, `usage-text-test`, `zone-selector-stays-registered-but-hidden-test`, and `picker-offers-the-zones-the-show-uses-test` (UI).

### Tests added

- `laser-show.routing.legacy-shows-test` (NEW): `old-shows-route-as-before-test` (loaded through `apply-loaded-project`, ticked at beats 0, 1.5 and 7.25, every projector's frame against `golden.edn`) and `legacy-loads-are-clean-test`; six fixture shows under `test/laser_show/routing/legacy_shows/`.
- `laser-show.routing.targets-test` (NEW): the nine §9 tests and `explain-says-what-each-level-would-inherit-test`.
- `laser-show.routing.zone-maps-test` (NEW): the nine §9 tests.
- `laser-show.routing.refs-test`: the two §9 tests.
- `laser-show.state.migration-test`: the ten §9 tests.
- `laser-show.services.frame-service-test`: `union-target-is-one-key-test`, `column-and-show-defaults-reach-items-test`, `frame-keys-ignore-the-map-test` and `preview-zones-count-for-their-lane-test`; Z3's `frame-keys-are-normal-targets-test` and `test-generate-frames-by-zone-inherits-default` read `:target`.
- `laser-show.routing.rig-test`: the four §9 tests. `laser-show.routing.compose-test`: examples i and j.
- `laser-show.services.show-clock-test`: the four §9 tests and `column-default-reaches-its-cues-test`. `one-state-and-input-read-per-tick-test`, Z3's `routing-is-made-before-the-render-test` and Phase 6's `vibe-report-is-sent-once-per-interval-test` pass unchanged.
- `laser-show.events.zone-maps-handlers-test` (NEW): the eleven §9 tests, `set-default-target-test`, `pad-pointer-presses-with-shift-for-the-bar-test` and `pad-setting-and-control-test`.
- `laser-show.events.handlers.triggers-test` and `laser-show.events.handlers.input-test`: the two §9 tests each.
- `laser-show.events.cue-chain-handlers-test`: the three §9 tests, for the four zone tests. `laser-show.events.timeline-handlers-test`: `add-track-does-not-stamp-target-test` (renamed) and `set-track-and-clip-target-test`; `open-editor-converts-without-changing-output-test` uses `:target`. `laser-show.timeline.model-test`: `target-ops-test`; `auto-initialize-tracks-test` reads targets. `laser-show.events.grid-handlers-test`: `set-column-target-test`.
- `laser-show.state.history-test`: the three §9 tests. `laser-show.state.persistent-test`: the four. `laser-show.state.templates-test`: `starter-cues-inherit-test`.
- `laser-show.vibes.library-test`: the four §9 tests; `audience-cell-test` moved to targets (its clips sit on their tracks, and two cases more); `audience-cells-never-play-unless-allowed-test` passes unchanged.
- `laser-show.subs-test`: the five §9 tests.
- `laser-show.views.components.target-control-test` (NEW): the three §9 tests. `laser-show.views.tabs.zones-test`: `usage-text-test`.
- `laser-show.events.show-lock-test`: the new refused events in `structure-edits`, and the request-delete check. `laser-show.events.zone-groups-handlers-test`: the maps in the delete question.
- `laser-show.animation.effects-coverage-test`: `zone-selector-stays-registered-but-hidden-test`.
- Went with their functions (step 14): five of `zone-effects-test`'s tests and ten of `zone-routing-integration-test`'s, all on `routing.zone-effects`' old readers. `evaluate-zone-test`, `log-once-is-lazy-test` and the integration tests that render through the migration stay.
- Moved to `:target` with no change of meaning: fixtures in `multi-engine-test`, `rig-file-test` (the owned paths gain `[:zone-map]`, `[:zone-maps]`, `[:zones]`), `layout-test`, the vibe engine and handler tests, and the UI tests `rig-report-test`, `streaming-status-test`, `setup-dialogs-test` and `preview-test`.
- UI:
  - `laser-show.ui-test.zones-test` (NEW): the fourteen §9 tests.
  - `laser-show.ui-test.main-window-test`: the two footer tests ("Target:", one combo, the chip's tooltip).
  - `laser-show.ui-test.preview-test`: `:target`, and `picker-offers-the-zones-the-show-uses-test`.
  - `laser-show.ui-test.perform-test`: `map-row-shows-when-zones-are-used-test`.

### Red first

Where the new code didn't exist yet, the run was first made to compile with stubs (nil, `{}`, or a value no test expects), so the tests below were seen failing unless it says otherwise.

| Step | Before the change |
|---|---|
| 1 | green, as it records today. A check that it can fail: one fixture's destination changed, 3 failures |
| 2 | `targets-test` against stubs: 9 tests, 96 failures, 30 errors. `target-control-test` against a stub `menu-model`: 3 tests, 43 failures |
| 3 | `zone-maps-test` against stubs: 9 tests, 67 failures, 2 errors |
| 4 | the step's namespaces (migration, frame-service, cue-chain-handlers, timeline-handlers, model, layout, templates, refs) against stub `cue-z4` and `zones-z4` and the old handlers: 155 tests, 103 failures. **Not seen red:** `inherit-removes-the-target-test` (the old handler returned `{}`, so no `:target` either way) and `open-editor-converts-without-changing-output-test` (the old render ignored `:target`: All both ways). With the code, 77 assertions in other files failed on the old keys and moved to `:target`, and `zone-routing-integration-test` renders through `migration/cue-z4` |
| 5 | 8 namespaces, 149 tests, 35 failures (the new handlers stubbed) |
| 6 | 7 namespaces, 187 tests, 73 failures. **Not seen red:** `zone-map-active-is-live-test` (nothing of `[:zone-map]` was in the document before either) and `clock-writes-no-zone-map-test` (the clock never wrote it). `preview-zones-count-for-their-lane-test`'s expectations (the key order, a union counted once) were corrected after the first run with the code |
| 7 | 4 namespaces, 77 tests, 41 failures, after a compile error (a test placed before the helper it used). **Not seen red:** `map-pad-learn-ignores-a-cc-test` (every learn but a fader's already ignored a CC). `press-reads-the-clock-then-schedules-test` runs through `events/event-handler`, as the pipeline-only `run` runs no effect |
| 8 | the views were written **before** their UI tests. With the step's subs and `target-control` and `HEAD`'s two dialogs: 8 tests (zones-test's six, the two footer tests), 4 failures, 6 errors |
| 9 | `targets-test`: 10 tests, 6 errors (no `:item-inherits`). UI `timeline-chip-and-inspector-set-targets-test`: 1 error |
| 10 | against nil subs: `subs-test` 39 tests, 12 failures. UI `grid-badge-and-column-target-test`: 1 error |
| 11 | against stub subs: `subs-test` 40 tests, 7 failures. UI, the three pad tests and `map-row-shows-when-zones-are-used-test`: 4 errors |
| 12 | against stubs and events not routed yet: 6 namespaces, 89 tests, 114 failures, 1 error. UI, the two map tests and the preview's: 3 errors |
| 13 | `library-test` and `subs-test`: 66 tests, 14 failures. **Not seen red:** `unison-counts-when-all-reaches-the-crowd-test` (the old rule seeded `:all` for a cue with no target of its own, so a zone cue already counted as All) |
| 14 | `effects-coverage-test`: 5 tests, 1 failure. UI `bank-offers-no-zone-selector-test`: 1 failure (the effects grid's editor had a Zone tab; its Cue Chain Editor and timeline parts passed, as those went in steps 8 and 9). **Not seen red:** `frame-keys-ignore-the-map-test`, written at step 14, which pins invariant 2 as the code already keeps it |

### Verification

| Where | Unit suite (`clj -M:mac-arm:test`) | UI suite (`clj -M:test-ui:mac-arm:test-ui-headless`) |
|---|---|---|
| `c067030` (the base) | 1,437 tests, 541,737 assertions, 0 failures (05 §13's run of the same `src/` and `test/`) | 194 tests, 879 assertions, 0 failures (the same) |
| step 4, `76b7790` | 1,475 tests, 542,412 assertions, 0 failures | 10 namespaces (rig-report, setup-dialogs, perform, preview, streaming-status, cue-load, timeline-editor, main-window, cue-chain-list, rig-card): 114 tests, 503 assertions, 0 failures |
| step 5, `ef2aae9` | 1,481 tests, 542,464 assertions, 0 failures | – |
| step 6, `408dbc1` | 1,496 tests, 542,557 assertions, 0 failures | – |
| step 7, `ae1e39b` | 1,511 tests, 542,633 assertions, 0 failures | – |
| step 14's code, before its last unit test (`frame-keys-ignore-the-map-test`) | 1,511 tests, 542,799 assertions, 0 failures, 0 errors | 210 tests, 1,010 assertions, 0 failures, 0 errors |
| **`b44e388`, the branch's last code** | **1,512 tests, 542,801 assertions, 0 failures, 0 errors** | **the row above: the one test added since is a unit test, which the UI suite doesn't load** |
| the trial merge onto `master` `cc9e000` (tree `b536aaa`, below) | 1,522 tests, 542,856 assertions, 0 failures, 0 errors | 211 tests, 1,015 assertions, 0 failures, 0 errors |
| **rebased onto `cc9e000`, as landed** | **the same `src/`, `test/`, `deps.edn` and QUICKSTART trees as the trial merge (`b536aaa`), so its run above stands** | **the same** |

Each step also ran its targeted namespaces (§9's run list) and the UI namespaces whose views it changed, and `legacy-shows` every time. The full UI runs had no failure, so no screenshot to look at. Screenshots were checked for the Cue Chain Editor (step 8) and the timeline editor (9), and at 1200 × 800 for the grid's badges and column numbers (10), the Grid tab's strip and Perform's row (11), and the Zone Groups tab with and without lasers and the Projectors tab (12). `~/LaserShowProjects` didn't exist before the work and doesn't after it: no test or app start wrote under the home folder.

### For the merge

Trial merges with `git merge-tree`, which writes no branch and no worktree. The merged tree was unpacked with `git archive` into a temporary directory, its suites run there, and the directory deleted.

- **Into `master` at `cc9e000`** (Decimate in, `e7b93e4`..`d6fbb15`): no conflicts, in code or plans (`master` hasn't edited this doc). Merged tree `b536aaa`; its suites are in the table above. Files both sides edit, all merging cleanly:
  - `services/frame_service.clj`: `master`'s `effects.points` require beside the zone effect's;
  - `test/laser_show/animation/effects_coverage_test.clj`: `master`'s Decimate exemptions and id, Z4's hidden selector exemption and `zone-selector-stays-registered-but-hidden-test`;
  - `test/laser_show/backend/multi_engine_test.clj` and `test/laser_show/ui_test/setup_dialogs_test.clj`.
- **With the output drivers** (`output-drivers` at `392a84d`, D2 with D3 merged in, off `c067030` as Z4 is), read only: 13 files both edit. `git merge-tree output-drivers zones-z4` merges 11 of them cleanly and conflicts in two test files, both where each side appended tests at the end: keep both blocks.
  - `test/laser_show/subs_test.clj`: the drivers' rig-card and add-on subs tests, and Z4's badge, pad, Used by and picker tests;
  - `test/laser_show/state/persistent_test.clj`: OD-10's unknown-driver round trip, and Z4's zone data tests.
  - Merging cleanly: `events/core.clj`, `events/handlers.clj`, `events/handlers/history.clj`, `events/show_lock.clj` (both add refused events, and both add examples to `show-lock-test`'s `structure-edits`), `routing/rig.clj`, `state/domains.clj`, `subs.clj`, and the tests `multi_engine_test`, `show_lock_test`, `rig_file_test` (Z4 adds `[:zone-map]`, `[:zone-maps]` and `[:zones]` to the show's owned paths) and `streaming_status_test`.
  - That pair's merged suites weren't run: run both after whichever lands second. `show-lock-test/locked-blocks-structure-edits-test` then runs both sides' examples on one `s0`, which Z4 gives a map of yours and a timeline cue.
- The older output branches (`output-d1-seam`, `output-lsps`, `output-addons-loader`, `output-contract-additions`, off `cd10149`) already conflict with `master` in `app.clj`, the backend and `state/domains.clj`. Z4 adds no conflict of its own to theirs.
- Files Z4 edits that other work often touches: `events/core.clj` (the `:show-clock/read` effect), `events/handlers.clj` (the `zones`, `zone-maps` and `zone-map` routes), `events/handlers/history.clj` (the labels), `events/show_lock.clj`, `state/domains.clj`, `state/history.clj`, `state/persistent.clj`, `state/migration.clj`, `routing/refs.clj`, `routing/rig.clj`, `services/frame_service.clj`, `services/show_clock.clj`, `animation/show_time.clj`, `subs.clj`, the grid, Perform and Zone Groups tabs, both cue editors and the timeline's header and inspector.

### Found on the way

- **Z3's track index lost tracks.** `routing/zone_effects.clj`'s `index-tracks` used `assoc!` for its side effect on transients, so in a cue with more than 8 tracks and folders the later ones (and their folders) were dropped, and their clips lost their track's zone. `targets/track-targets` builds the index with `reduce`; `track-targets-walk-folders-test` has 11 tracks. The legacy fixtures have 8 or fewer, so `golden.edn` isn't affected, and the old function is gone (step 14).
- **cue-timing skips a tick gap over 1000 ms,** so `legacy-shows` ticks every quarter beat to reach beat 7.25.
- **A layout constraint inside `fx/ext-on-instance-lifecycle` throws** "No such prop: :h-box/hgrow". It goes on the desc in the parent's children, as the vibe pads do (`map-pad-row`).
- **No clock thread in `start-test!`:** `:show-clock/read`'s `:running?` is false there, so a UI press switches at once, as §9 says for `map-pad-switches-the-map-test`. `pending-pad-blinks-test` blinks under `fixtures/with-clock`.
- **Layout to look at:** with many maps of yours, the Grid tab's strip can crowd its title row, and the ZONES AND MAPS section scrolls.
