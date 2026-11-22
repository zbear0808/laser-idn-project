# Phase Z3: routing core (routing in the snapshot, each picture once, split lasers, fit guard, per-laser preview, low-fps warnings)

**Written:** 2026-11-19   **Written for commit:** `6aaad7d` (`master`; first written at `90e35c7`, then re-verified here: `src/` is as vibe Phase 6 left it in `4a4a10a`, after vibe Phases 3 (`38f92ad`), 4a (`6715fb4`), 4b (`f5a4f84`) and 5 (`9f5d78a`, with the fader fix `0faa18c`) and UX Phase 3 (`b9b9d55`); every line below is at that source). Z1 and Z2 edit several of these files first, so **re-check every path and line at HEAD before starting.**
**Status:** **Done in code, on `master`** (`cb28966`..`298c253`). The as-built note is §13. **Still owed: the check on a real projector** (00 R-1, §9).   **Needs:** Z1 (`03-phase-z1-safety.md`: the safety limit and stage) and Z2 (`04-phase-z2-rig-strip-and-groups.md`: rig order, automatic groups, the rig card). The Z2 names below are taken from doc 04. Doc 03 now exists (re-checked at `70f7db8`), and the Z1 row of the table below follows it. Doc 06 (Z4) calls this phase's accessor `rig/resolve-ref`, and this doc uses that name.
**Implements spec** (`01-model-and-routing-spec.md`): §1 (frame keys in normal form), §5 with its order within a tick (the routing before the render, Z5 S-3), §6 with §6.1 and §6.2 a–h (i and j are Z4), §7, §8, invariants 4, 5, 6, 8 and 9, and needs C-10 (a per-laser preview).   **Size:** ~6 d
**Merge risk:** vibe Phase 6 (auto-advance and one tap tempo) landed while this was being re-verified (`4a4a10a`), and the lines below are after it. It added a `rotation-step` to `tick!`, after `publish!` and `status-step`, and made `show-time/due-reports` public, taking the sent map. Z3 leaves both where they are. Vibe Phase 7 (safety and hardening, proposed) also edits `services/show_clock.clj`, `animation/show_time.clj`, `views/toolbar.clj` (where the LOW FPS chip goes), `state/domains.clj` and `subs.clj`, so whichever of Phase 7 and Z3 lands second merges those by hand (00 §6).

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. **NEW** marks anything that doesn't exist yet.

After this phase you can:
- **Split one laser.** With one laser, a cue whose track 1 goes to Left and track 2 to Right draws them side by side, each fitted into 75% of the laser's safe area (§6.2 b). With three lasers, the middle one is shared by Left and Right (f). On **Both**, it splits only while both sides are lit with different pictures (g, h).
- **Trust overlapping groups.** A laser in All and in Graphics draws a cue sent to both of them once, not twice (e). A picture placed under two keys is drawn once.
- **Keep the frame rate.** When a laser would draw below its minimum (20 fps by default), it draws fewer points. It never goes below 60% of them, and it keeps the blanking, the ends of each line and the corners. It dims too, if you allow it.
- **See it.** Each laser's card shows what it sent (after calibration and safety) and a load meter that says what the guard did ("split · 2 regions · −30% pts"). The card turns amber below the laser's minimum, and the toolbar says **LOW FPS · Laser R 14 fps** on every tab, with a click to the card. A playing cue that is too heavy shows **⚠** on its cell, and its editor names the laser and the heaviest item. A preview cell can show one laser as it draws it.
- **Edit live.** The show clock works out the routing once per tick, before it renders, and every engine and the preview read that one copy. A group edit shows on the next tick and restarts nothing.

There is still one render per cue per tick (spec invariant 8). Zones (A, B…) and maps come in Z4. Until then, frame keys are groups (`:all`, `:auto/left`, `:auto.laser/<id>`, custom ids), and Z4 adds only the zone → lane lookup (§4, `rig/resolve-ref`).

| Assumed from | What this phase uses |
|---|---|
| Z1 (doc 03) | `[:projectors id :safety]` with `:limit [x0 y0 x1 y1]` (default `[-1 -1 1 1]`), **always read through `safety/normalize`** (03 S-Z1-7), so the safe box for regions is the same box the stage clips to. `normalize` throws on data it can't read, and Z3 catches that per laser (§4, S-9). A safety stage after `apply-projector-effects` in `projector-frame` that fails closed and reports under `[:safety id]`. Z1's `projector-frame` is a `cond` (03 §4): BLACKOUT on the test pattern, Identify's dark half and the test pattern come before the `:playing?` branch, which is the only one Z3 changes. The pattern replaces a laser's content, so it skips composition and the fit guard, and the effects grid (the vibe Master included) never touches it (03 S-Z1-8) |
| Z2 (doc 04 §4) | `[:rig]` with `:order`, `:middle` (`:split`) and `:split-width` (0.75), plus `:exclude-from-all?` on each projector. In `routing/rig.clj`: `enabled?` (`(:enabled? p true)`), `effective-order`, `rig-inputs [state]`, `auto-groups`, and `ref-entries [inputs]`, the whole `{ref entries}` map that "Z3's resolver puts under `:refs`". There's also `refs-for`, which Z2's `get-projector-zone-groups` returns: every ref that reaches a laser, with halves counted as the whole laser. `subs/target-groups`, which every picker reads (the preview's too), lists All, the automatic groups, each laser (`:auto.laser/<id>`) and the custom groups. The cards are built in NEW `views/components/rig_strip.clj` from a vector of sections, and Z2's thumbnail section returns nil for Z3 to fill. The card keeps `#projector-dot-<id>` and `#projector-status-<id>`, and the inspector stays in `views/tabs/projectors.clj` |

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| A tick | `services/show_clock.clj:83-106, 160-191` | It steps show time (`:172`), takes Phase 4a's pulse view (`:176`), renders every playing cue once (`:97-99`) and publishes `{:cues [{:cell :run :zone-frames}] :projector-ctx :render-us :at-ns :playing? :positions :pulses :faders :preview}` (`:179-185`; `publish!` adds `:tick`). `:pulses` (vibe Phase 4a) is also on every cue's timing context and the effects grid's (`:65`; `services/frame_service.clj:103-125`), but not on `:projector-ctx`. `:faders` (vibe Phase 5, as `0faa18c` derives them once a tick) is for the Perform meters. After `publish!` come the status and Phase 6's auto-advance reports (`:187-190`). The snapshot has no routing |
| Points counted | `services/show_clock.clj:74-81` (`record-render!`) | One total for the JFR `FrameGeneration` event. Nothing per cue, per item or per laser |
| The clock's status | `services/show_clock.clj:122-146`; `animation/show_time.clj:63-78`; `events/handlers/timing.clj:148-154` | The beat, the frame stats and the MTC text. They go to app state at most every 250 ms, and only when app state differs |
| Who draws what | `backend/multi_engine.clj:47-88, 119-150` | Each engine, on its own thread, reads `[:projectors id :zone-groups]` from **live app state** (`:132-133`). It takes those keys of each cue, in list order, joins them (`:134-137`), then applies its projector effects (`:148-150`). A laser in two groups that both hold the same frame draws it twice: 68 points instead of 33 (Phase 8 probe v22). There are no regions |
| The join | `services/frame_service.clj:190-228` | `concatenate-frames` puts two dark points between frames |
| Effects-grid point effects | `services/frame_service.clj:84-101, 299-311, 408-416` | Run once per key of each cue, at the cue's tempo, inside the render, so before anything per laser. Since vibe Phase 5 a new project has one: the **Master** at [0 3] (`state/templates.clj:105-110`, `vibes/faders.clj:56-62`), an Intensity on fader 1. It scales r, g and b (`animation/effects/intensity.clj:11-18`) and keeps every point |
| Frame keys | `routing/zone_effects.clj:127-156`; `services/frame_service.clj:250-254, 384` | One keyword per item: its own group, else its track's, its parent group's or the cue's, else `:all` |
| Projector effects | `backend/multi_engine.clj:94-111` | Read live. A failing one is skipped |
| The preview | `services/frame_service.clj:418-439`; `views/components/preview.clj:134-162, 295-335` | A cell names a zone group (`:zone-group-id`, `state/domains.clj:252-259`) and draws that exact key. There is no view of one laser |
| Its picker | `views/components/preview.clj:200-267`; `events/handlers/ui.clj:103-138` | Lists `[:zone-groups]`. Option ids use `(name id)`, so `:auto/left` and a custom `:left` would collide |
| Engine stats, status | `backend/streaming_engine.clj:51-63, 267-308, 365-384`; `backend/output_runtime.clj:172-207` | The fps a laser draws and whether its scan rate limits it, published at most 4 times a second. No point load |
| The laser's status line | `views/tabs/projectors.clj:255-266` (`output-status-text`) | "Streaming · 24.9 fps (limited by scan rate)" is the only sign of too many points |
| Scan rate | `idn/stream.clj:547-578`; `backend/streaming_engine.clj:123, 170-183` | Clamped to 1,000–100,000 pts/s. It sets each frame's duration on the wire |
| Toolbar, cue cells | `views/toolbar.clj:219-301`; `views/components/grid_cell.clj:95-220, 226-319` | OUTPUT OFF and the streaming status. A cell shows LEARN, its label, the playing dot and ⚡. A cue cell has no tooltip: `generic-grid-cell` takes a `:tooltip-fn`, but only effects cells pass one (`effects-tooltip`, `:85-89`) |
| Clock widgets | `views/components/clock_view.clj:30-71` | Redraw when the tick changes: up to 60 times a second |
| Engine fingerprint | `backend/output_runtime.clj:35-39` | Host, port, service, output config and scan rate. Group edits restart nothing |
| Engine timing | `backend/streaming_engine.clj:380-381`; `profiling/frame_profiler.clj:55-70` | The "IDN" time of a frame is the provider (routing, projector effects) plus the send |

## 2. Why / root cause

Routing is a list of keys per laser, read live on each engine's thread and applied key by key. It records no path (which group brought a frame), no region and no frame identity. So:
- a laser can't be split;
- a laser reached through two groups draws the same thing twice;
- an engine can join one tick's frames with a later edit's groups, and the preview can disagree with it;
- nothing counts points per laser, so a heavy cue goes unnoticed until the light flickers.

The fix has two parts. A resolver turns the rig into `ref → [laser region side]` once per tick. A pure composition then works out each laser's frame, and every consumer runs it on the same snapshot: the engines, the preview and the cue warnings.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `concatenate-frames` (`services/frame_service.clj:190-228`) | Joins every bucket and every cue. It moves to NEW `animation/frames.clj` so `routing` can call it, and the frame service keeps the name |
| The snapshot and `publish!` (`animation/show_time.clj:335-355`) | Carry `:routing`, beside Phase 4a's `:pulses` and Phase 5's `:faders`. Engines and the preview read nothing else for routing |
| The runtime's `:status`, kept from tick to tick (`services/show_clock.clj:122-146`) | The same pattern keeps the resolver's cache, `:routing-cache` |
| `status-paths`, `:show-clock/status` (`animation/show_time.clj:63-73`) | One more path: `[:backend :streaming :cue-warnings]` |
| A frame's `{:tick :at-ns}` metadata, read at `backend/streaming_engine.clj:369` | Also carries the fit-guard record into the engine's stats |
| `engine-status`, `:output/status` (`backend/output_runtime.clj:172-219`) | Publish each laser's load, at most 4 times a second |
| `ext-clock-redraw`, `draw-preview` (`views/components/clock_view.clj:58-71`; `views/components/preview.clj:119-132`) | Draw the thumbnail and the laser cell. A NEW `:every-ms` caps thumbnails at 10 redraws a second |
| `output-held-chip` and its style (`views/toolbar.clj:232-244`; `css/layout.clj:65-71`) | The model for the LOW FPS chip |
| The fingerprint (`backend/output_runtime.clj:35-39`) | Unchanged: fit, safety and rig edits restart nothing |
| Z2's automatic groups (`routing/rig.clj`) and Z1's limit | The resolver's inputs |
| Phase 8's planned `routing/zone_remap.clj` | If Z3 lands first, it can join with `animation/frames` instead of taking a `:join` option. Its "copy to several zones" (its §9) also becomes safe: Z5 stores a copy as one frame under the union of its zones (spec §6.1, Z5 S-1), and composition draws each key once |

## 4. Changes per namespace

**`laser-show.animation.frames`** (NEW, pure). `create-blanking-jump` and `concatenate-frames [frames]` move here unchanged from `services/frame_service.clj:190-228`. `frame-service/concatenate-frames` stays as `(def concatenate-frames frames/concatenate-frames)`, so no caller or test changes (Z1's test pattern joins its layers with it too). The reason: `routing` has to join frames, and the frame service already requires `routing`.

**`laser-show.routing.rig`** (from Z2; Z3 adds these, all pure):
- `normalize-target [t]` (spec §1): nil stays nil and a keyword stays itself. A collection becomes its distinct keywords sorted by `(juxt namespace name)`: one keyword if one is left, nil if none.
- `key-refs [k]`: `[k]` for a keyword, `k` itself for a vector. `zone-index [ref]`: `:zone/a` 0 … `:zone/z` 25, `:zone/aa` 26, nil for any other ref. It's used for key order now, and for lanes in Z4.
- `region-fits [limit w]` (spec §7): `{:L [cx cy s] :R [cx cy s]}` from the limit's bounding box. It returns nil when the box is under 0.05 wide or high (§12 S-9).
- `routing-inputs [state map-id]`: `{:rig :projectors :zone-groups :zone-maps :map}`, slices of the tick's app state (the clock's one read, so `one-state-and-input-read-per-tick-test` still holds). Their identity is the cache key (`:zone-maps` is nil until Z4).
- `routing [inputs]` → `{:map map-id :refs {ref [[pid region side] …]} :outputs {pid {…}}}` (the shape in spec §5). It builds Z2's `rig-inputs` from the slices.
  - `:refs` is Z2's `ref-entries`: `:all` (every enabled laser that isn't excluded, whole), the `:auto/*` groups with their regions and sides, `:auto.laser/<id>`, and the custom groups (members in E, whole).
  - `:outputs` has an entry for each enabled laser (`rig/enabled?`), excluded ones too, since their own group and custom groups still reach them (D-33):
    - `:index`: its place in E, or nil for a laser excluded from All;
    - `:scan-rate`: clamped as the engine clamps it (`idn/stream.clj:561-568`), 25,000 by default;
    - `:fit`: from `fit-guard/settings`;
    - `:regions`: `region-fits` of the limit as Z1's `safety/normalize` reads it (03 S-Z1-7). A zone `normalize` can't read throws there; the resolver catches that for the one laser, which gets `:regions nil`, as a box under 0.05 does. So one bad zone never stops the routing of the other lasers (D-29), and Z1's stage still sends that laser dark and says why.
- `resolve-ref [routing ref]`: the only way to read a ref's entries, and the name doc 06 uses. In Z3 it's `(get (:refs routing) ref [])`. In Z4 it resolves a zone through the effective map's lanes, so nothing that calls it changes.
- `cached-routing [cache inputs]` → `[cache routing]`. While every input is `identical?` to the cached one (every tick without an edit), it returns the cached routing; otherwise it builds a new one.
- Z2's `refs-for` stays for the audience rule (`refs-reaching`), but no engine calls it any more.

**`laser-show.routing.compose`** (NEW, pure): what one laser draws (spec §6, §7).
- `key-order [k]`: a sortable tuple. Zone refs come first by index, then the other single refs by namespace and name, then vector keys, compared ref by ref in that same order. So the order never depends on the order of the keys in the cue's map, even with several vector keys (since Z5, each pattern copy is one: spec §6.1).
- `contributions [zone-frames routing pid]` (steps 1–3). For each key with entries for `pid` it gives `{:keys [k] :frame f :region r :side s}`. `r` is nil (the whole laser) if any entry is whole or if the entries cover both `:L` and `:R`; otherwise it is that one region. `s` is kept only when every entry carries the same side. A vector key is one contribution however many of its refs reach `pid`, which is how Z5's copies are drawn once on each laser without relying on identity (spec §6.1, Z5 S-1).
- `merge-pictures [contribs]` (step 4): contributions whose frames are `identical?` become one, with their keys together and their regions merged by the same rule. This is the second line of defence for copies.
- `split-sides? [cues routing pid]` (step 5): true when some whole contribution tagged `:left` and some tagged `:right` reach `pid` anywhere in the **tick's** cues (§12 S-1). After `merge-pictures` they are different objects, so they are different pictures.
- `cue-plan [zone-frames routing pid split?]` → `{:L [f …] :whole [f …] :R [f …]}`, each in key order (steps 4–6). With `split?`, contributions tagged `:left` go to `:L` and those tagged `:right` go to `:R`.
- `plan-points [plan]`: how many points `build` will make, from counts only: every frame's count plus 2 per join.
- `fit-region [frame [cx cy s]]`: each point moves to `(cx + s·x, cy + s·y)`, colours unchanged.
- `build [plan fits]`: joins each bucket, fits `:L` and `:R`, then joins L, whole and R. A bucket whose fit is nil (a degenerate box) is dropped.
- `projector-frame [cues routing pid]` → `{:frame f :regions n}`, or nil when nothing reaches `pid`: every cue's built frame, joined in the cues' order (step 7).
- `cue-points [zone-frames routing pid]`: `plan-points` for one cue, 0 if it doesn't reach `pid`.
- `cue-warnings [cues routing]` → `{cell {:projector :points :fps :min-fps :scan-rate :item}}`: every cue whose `scan-rate / cue-points` is under some laser's `:min-fps`, at its worst laser (ties go to the lower `:index`, an excluded laser's nil last). `:item {:id :points}` is the top-level item with the most points on that laser. It's present when the cue's frames carry `:item-points` metadata, which only edited cues do (below).

**`laser-show.animation.fit-guard`** (NEW, pure; spec §8):
- `defaults` is `{:min-fps 20 :reduce-points? true :point-floor 0.6 :dim? false :dim-floor 0.5}`. `settings [fit]` merges a laser's `:fit` over them and clamps to 5–60, 0.3–1.0 and 0.2–1.0. A value of the wrong type takes its default, so a bad `:fit` can't stop the resolver either.
- `budget [scan-rate min-fps]` is ⌊r / min-fps⌋ (S-5). `target-points [n budget settings]` is `max(budget, ⌈point-floor · n⌉)`.
- `keep-mask [frame]` marks the points thinning may never remove:
  - every blanked point;
  - the first and last point of each **lit run** (a maximal stretch of lit points);
  - dwell points (the same x, y as a neighbour, within 1e-6);
  - corners, where the directions in and out turn by more than 45° (`a·b < 0.7071·|a||b|`, against the original neighbours).

  A colour change is not protected.
- `thin [frame k]` removes up to k of the other points. Each run gets a share of k in proportion to its removable points (largest remainder, ties in frame order). Inside a run with c removable points and share q, its j-th removable point goes when `⌊(j+1)·q/c⌋ > ⌊j·q/c⌋`, which spreads the removals evenly. If there are fewer than k removable points, all of them go. The result is always a subsequence of the frame.
- `dim [frame factor]`: multiplies r, g and b by factor. Dark points stay dark.
- `guard [frame scan-rate settings]` → `{:frame f :fit record}`. Under budget it returns the same frame object, and a record with no cut. Over budget it thins if `:reduce-points?`, then, if `:dim?` and still under the minimum, dims by `max(dim-floor, guard-fps / min-fps)`. The record is `{:points-in :points :budget :min-fps :scan-rate :est-fps :guard-fps :reduced-pct :dim-pct}` for every frame, since the meter needs it. Over budget, a frame with no lit point also says `:dark? true` (S-14): the mask pass sees that for free.
- `smooth [prev record]` averages `:est-fps` and `:guard-fps` over about 10 frames, the way `update-stats` smooths the draw rate (`backend/streaming_engine.clj:267-308`). It keeps the rest of the newest record and sets `:low?`: on when the averaged guard fps is under the minimum, off again only at 1 fps above it (S-6). A `:dark?` record leaves the averages and `:low?` as they were (none yet: not low), so a frame nobody sees never turns a laser amber (S-14). A nil record gives nil.
- **The vibe faders.** The Master and the Morphs (vibe Phase 5) are effects-grid cells, so they run inside the render, before composition and the guard, and the guard sees the frame they made. The Master scales colours and never changes the number of points, so the guard cuts the same at any fader level. If `:dim?` is on, the guard's dim multiplies the Master's.
- **Cost** (estimated): one pass to mark and one to copy, about 50–100 µs for 2,000 points. That's extrapolated from v23 (11–12 µs to join two 1,000-point frames), plus a few multiplications per point. It runs only for frames over budget; otherwise it costs a `count` and a compare.

**`laser-show.services.frame-service`** (existing):
- `generate-frames-by-zone` (`:349-416`): the effects-grid loop (`:408-416`) becomes NEW `apply-global-effects-once [frames-by-key global-effects global-ctx]` (spec §6.1). An `IdentityHashMap` maps each input frame to its output, so keys that hold one object get one result object. There is no value hashing. The global context is `render-context`'s (`:103-125`) as today: the tick's pulse view and the faders' values are on it, so the Master and the tap reactions run exactly as they do now, once per picture instead of once per key.
- A NEW key on the render context, `:item-points? true`, puts `{:item-points [[item-id {key n}] …]}` on the result's metadata: each top-level item's (or clip's) point counts by key, taken from `item-zone-maps` (`:390-397`), before track, group and global effects. It's a key, not a 4th argument, so the clock keeps calling the 3-arity, which `render-context-carries-pulses-test` (`test/laser_show/services/show_clock_test.clj:421-447`) redefines with 3 arguments.
- Keys become targets in normal form: `resolve-item-zone-destination` (`routing/zone_effects.clj:148-152`) returns `(rig/normalize-target result)`. With today's keyword keys this changes nothing; in Z4, two vector targets written in different orders become the same key and merge by concatenation.
- `preview-data [cues routing]` (`:418-439`; the 1-arity stays, with routing nil):
  - `:zone-frames` becomes `:ref-frames {ref frame}`: for every ref named in the tick's keys, the frames of each key that names it, joined in key order and then across cues. A vector key counts for each of its refs. With keyword keys this is exactly today's `:zone-frames`.
  - NEW `:projector-frames {pid (delay …)}`: for each laser in `:outputs`, `compose/projector-frame` and then `fit-guard/guard`. A delay is forced only when a cell shows that laser, and a cell on `:auto.laser/<id>` reads it through Z2's `rig/laser-of`.

**`laser-show.services.show-clock`** (existing):
- `render` (`:83-106`): `render-cue` (`:57-72`) puts `:item-points? true` on the render context it passes for the cells whose editor is open, read from the tick's state: `[:ui :dialogs :cue-chain-editor]` or `[:ui :dialogs :timeline-cue-editor]`, with `:open?`, `:col` and `:row`.
- `tick!` (`:160-191`), in the spec's order (§5, Z5 S-3): `step` (`:172`); Phase 4a's pulse view (`:176`); NEW the routing, `(rig/cached-routing (:routing-cache runtime) (rig/routing-inputs state nil))`, kept in the runtime as `:routing-cache`; then `render` (`:177-178`). The routing never depends on the frames, so it can come first, and Z5's zone stage, inside the render, needs it there (Z3's render doesn't read it). The snapshot gains `:routing`, beside `:pulses` and `:faders` (`:183-184`), and `:preview` becomes `(delay (frame-service/preview-data cues routing))`. If the resolver throws, the tick reports `[:routing]` and keeps the cached routing (or an empty one), so a bad edit can't stop the clock. `status-step` and Phase 6's `rotation-step` stay after `publish!` (`:187-188`). Z4 passes the effective map id instead of nil, and Z5 puts its zone context and the Map cycle's pick before it.
- `status-step` (`:122-146`) also takes the snapshot. When the stats are due (every 250 ms), its value gains `:cue-warnings (not-empty (compose/cue-warnings cues routing))`, rounded (whole fps, points to the nearest 10). So the warnings reach app state only when one starts, ends or changes.

**`laser-show.animation.show-time`** (existing): `status-paths` (`:63-68`) gains `:cue-warnings [:backend :streaming :cue-warnings]`, and the docstrings of `!runtime` (`:56-59`) and the snapshot (`:336-338`) list `:routing-cache` and `:routing`. NEW `record-output! [pid frame]` and `output-frame [pid]` → `{:frame :at-ns}` keep what each laser's provider produced last, in a `ConcurrentHashMap`, for the thumbnails. `reset-runtime!` (`:362-372`) clears them.

**`laser-show.backend.multi-engine`** (existing):
- `get-projector-zone-groups` (Z2's version returns `rig/refs-for`), `extract-frames-for-zones` and `combine-zone-frames` (`:47-88`) go.
- `projector-frame` (`:119-150`, as Z1 leaves it: a `cond`, 03 §4) reads no app state for routing. Z3 changes only its `(:playing? snapshot)` branch: `compose/projector-frame` on the snapshot's `:cues` and `:routing`, then `fit-guard/guard` with the laser's `:outputs` entry, then, inside Z1's `guard-safety`, `apply-projector-effects` (`:94-111`, unchanged and still live) and the safety stage. The result carries Z1's `{:tick :at-ns}` and Z3's `:fit (assoc record :regions n)` as metadata. The branches before it stay Z1's: BLACKOUT on the test pattern, Identify's dark half, and the test pattern itself. The pattern replaces the content, so it skips composition and the guard (S-13), and its frame carries no `:fit`, so the card shows no load while it's on.
- `create-projector-frame-provider` (`:152-171`) smooths the record with `fit-guard/smooth` in a `volatile!` (there's one provider per engine, called only on that engine's thread), puts the smoothed record on the frame's `:fit`, and calls `show-time/record-output!` with the frame (nil after an error).

**`laser-show.backend.streaming-engine`** (existing): the loop reads `:fit` together with `:tick` and `:at-ns` (`:369`) and stores it as the stats' `:fit` (`:382-383`). `initial-stats` (`:51-63`) gets `:fit nil`, and a frame without one (nil: nothing playing) clears it. The engine doesn't look inside the record. The numbers live in the engine's stats rather than in an atom in `multi_engine` for three reasons: they start and end with the engine (`start!` resets them), the output runtime already publishes those stats, and there's nothing to clean up when a laser is turned off.

**`laser-show.backend.output-runtime`** (existing): `engine-status` (`:172-181`) adds `:low?` and `:load {:points-in :points :budget :min-fps :est-fps :guard-fps :reduced-pct :dim-pct :regions}`, rounded (whole fps and percent, points to the nearest 10). `output-errors`' docstring lists `[:routing]`. `fingerprint` is unchanged.

**State, events and subs** (existing namespaces):
- `state/domains.clj`: the projectors docstring (`:280-303`, where Z1 adds `:safety`) lists `:fit`. `[:backend :streaming]` (`:433-436`) gains `:cue-warnings nil`. The preview docstring (`:259`) says a cell is `{:target ref}`, that a laser's ref shows what the laser draws, and that an old `{:zone-group-id x}` reads as `{:target x}`. Z2 has already made the default cells `nil :auto/left :auto/right :auto/center`.
- `events/handlers/projector.clj`: NEW `:projectors/set-fit`.
- `events/handlers/ui.clj`:
  - NEW `:ui/reveal-projector`.
  - `:preview/set-cell-zone` (`:131-138`) also takes `:target` (`:zone-group-id` still works) and writes `{:target …}`. Z4 renames it `:preview/set-cell-target` (doc 06 §4).
  - `:preview/set-grid-layout` (`:103-129`) fills new cells with `{:target …}`.
- `subs.clj`:
  - NEW `projector-load [ctx pid]` (from `projector-output-status`, `:395-399`);
  - NEW `low-fps-projectors [ctx]` (named, lowest first);
  - NEW `cue-warning [ctx col row]`;
  - `preview-cell-config` (`:490-493`) normalises old cells;
  - the preview picker keeps Z2's `target-groups`.

**Screen:**
- `views/components/clock_view.clj`: `ext-clock-redraw` (`:58-71`) takes an optional `:every-ms`, and `start-timer!` (`:30-51`) skips pulses until that long after its last draw.
- `views/components/preview.clj`:
  - `cell-points [preview cell]` replaces `get-zone-filtered-points` (`:134-142`):
    - the master cell shows `:points`;
    - a laser's ref shows the `:frame` of its forced `:projector-frames` delay;
    - any other `{:target r}` shows `(get-in preview [:ref-frames r])`.
  - The picker (`:200-267`) lists Z2's entries. Its option ids are built from the namespace and the name (`preview-zone-option-1-auto-left`), so a custom `:left` no longer collides with `:auto/left` (unless Z2 has already fixed that).
  - NEW `output-thumbnail`: a canvas in `ext-clock-redraw` with `:every-ms 100`. Its `redraw-thumbnail!` draws `show-time/output-frame` while it's newer than `STALE_MS`, else "Not streaming", with the laser's limit as a grey outline.
- `views/components/rig_strip.clj` (Z2's cards): `output-thumbnail` fills Z2's thumbnail section, and the NEW load section adds `load-meter`, `load-note` and the amber `rig-card-low` state.
- `views/tabs/projectors.clj` (Z2's inspector): NEW `fit-guard-section`.
- `views/toolbar.clj`: NEW `low-fps-chip`, between `output-held-chip` and `connection-status` (the toolbar's last two children, `:300-301`).
- `views/components/grid_cell.clj`: an amber ⚠ next to ⚡ (`:208-211`) on a cue that has a warning. `grid-cell` (`:226-319`) passes a `:tooltip-fn`, which `generic-grid-cell` already takes for effects cells; the tooltip goes on the menu label (`:212-219`). Z4's badge text joins it there (06 §4.5).
- NEW `views/components/load_warning.clj`:
  - `cue-warning-text`, `low-fps-text` and `load-note-text`, pure functions shared by the tooltip, the chip and the card;
  - `cue-load-warning`, a line above the footers of `views/dialogs/cue_chain_editor.clj` (`:608-653`) and `views/dialogs/timeline_cue_editor.clj` (`:142-171`).
- CSS:
  - in `css/layout.clj`: `.low-fps-chip` (like `.output-held-chip`), `.rig-card-low`, `.load-meter` and `.load-meter-over`;
  - in `css/grid_cells.clj`: `.grid-cell-warning-indicator`, after `:113-115`;
  - for the editors: `.dialog-warning-line`.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:projectors id :fit]` `{:min-fps :reduce-points? :point-floor :dim? :dim-floor}` NEW | the rig: `hardware.edn` until ZR (`state/persistent.clj:54-57`) | `:projectors/set-fit` | yes / yes |
| `[:config :preview :grid-cells i]` `{:target ref}` | prefs (`state/history.clj:39-41`) | `:preview/set-cell-zone` | no / yes |
| snapshot `:routing`; `:routing-cache` in `show-time/!runtime` NEW | runtime, per tick | the show clock | – |
| the preview's `:ref-frames`, `:projector-frames` | runtime, inside the snapshot's `:preview` delay | forced by the screen | – |
| the engine stats' `:fit` NEW; `show-time`'s output frames NEW | runtime, per engine | its provider and streaming loop | – |
| `[:backend :streaming :status :projectors id]` `:load`, `:low?` NEW | app state, runtime | `:output/status`, at most 4 a second | no / no |
| `[:backend :streaming :cue-warnings]` NEW | app state, runtime | `:show-clock/status`, at most 4 a second and only on change | no / no |

Nothing new is saved apart from `:fit` and the preview cells.

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:projectors/set-fit {:projector-id :key :value}` | NEW (`events/handlers/projector.clj`) | the inspector's Fit guard controls | yes. Clamps to the ranges; one undo step per commit |
| `:ui/reveal-projector {:projector-id}` | NEW (`events/handlers/ui.clj`) | the LOW FPS chip | yes. Sets `[:ui :active-tab] :projectors` and selects the laser |
| `:preview/set-cell-zone {:cell-index :target}` | yes (`events/handlers/ui.clj:131-138`), with a new payload key (Z4 renames the event) | the preview picker | yes |
| `:show-clock/status` | yes (`events/handlers/timing.clj:148-154`) | the show clock. Its value gains `:cue-warnings` | yes |
| `:output/status` | yes (`events/handlers/connection.clj:28-34`) | the output runtime. Each laser gains `:load` and `:low?` | yes |

There's no new effect key. The path of one frame: `show-clock/tick!` → `rig/cached-routing` → `render` (where the effects grid, the Master included, has run) → `show-time/publish!` → the engine's `projector-frame` → `compose/projector-frame` → `fit-guard/guard` → projector effects → Z1 safety → `sanitize-frame` → `send-frame!`.

## 7. UX

**The rig card.** Z3 fills Z2's thumbnail section and adds a load section (`views/components/rig_strip.clj`). The example has three lasers, and the right one is heavy: a 3,000-point frame, thinned to its 60% floor:

```
┌ 1 Laser L ───────────┐ ┌ 2 Laser C ───────────┐ ┏ 3 Laser R ━━━━━━━━━━━┓
│ ● Streaming · 60 fps │ │ ● Streaming · 41 fps │ ┃ ● Streaming · 14 fps ┃  Z2's status line, bold amber while low
│ ┌──────────────────┐ │ │ ┌──────────────────┐ │ ┃ ┌──────────────────┐ ┃
│ │        ○         │ │ │ │   ○         ∿∿   │ │ ┃ │ ✱✱✱✱✱✱✱✱✱✱✱✱✱✱✱✱ │ ┃  what it sent, after calibration
│ └──────────────────┘ │ │ └──────────────────┘ │ ┃ └──────────────────┘ ┃  and safety, with the limit outlined
│ ▓▓▓░░░░░░░░░░   31%  │ │ ▓▓▓▓▓▓▓▓▓▓░░░   83%  │ ┃ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓  240% ┃  red past 100%
│                      │ │ split · 2 regions    │ ┃ min 20 fps · −40% pts┃
│ [✓] On               │ │ [✓] On               │ ┃ [✓] On               ┃
└──────────────────────┘ └──────────────────────┘ ┗━━━━━━━━━━━━━━━━━━━━━━┛
```

**The meter** is the points a laser is asked for (`:points-in`), divided by the points that still reach its minimum: `budget = ⌊scan-rate / min-fps⌋` (Q-Z3-4). It is green under 80%, amber up to 100%, and red past it, where the guard acts. Its tooltip reads "3,000 points a frame. 1,250 fit 20 fps at 25,000 pts/s". While a laser isn't streaming, the thumbnail says "Not streaming" and there's no meter (Q-Z3-2). Z2's half-mark tooltip drops its "For now the whole laser draws it." sentence.

**The toolbar**: an amber chip appears only while some laser is still low after the guard. Clicking it opens that laser's card.

```
[▶][⟳] Trigger: [Default ▾] │ BPM: [120.0] ■□□□ [TAP] [Link] │        [LOW FPS · Laser R 14 fps]  ● Streaming to 3 of 3  [Stop]
```

**A heavy cue**: an amber ⚠ on its cell while it plays, and a line above its editor's footer (in both editors):

```
┌──────────┐
│  circle  │   ⚠: "Too heavy for Laser C: about 12 fps (minimum 20)"
│   ●   ⚠  │
└──────────┘
⚠ ~2,010 points: about 12 fps on Laser C (25 kpps). Most points: Circle (1,999). Simplify it, or lower Laser C's minimum.
```

**The preview picker** is Z2's: Master, then `subs/target-groups` (All, the automatic groups, each laser, then your groups):

```
Master (everything)
● All  ● Left  ● Right  ● Center  ● Odd  ● Even        groups
▣ Laser L  ▣ Laser C  ▣ Laser R                        lasers (:auto.laser/<id>)
● Graphics  ● Crowd Scanning                           your groups
```

A **group cell** shows what is sent to that group, as drawn content, unfitted. A **laser cell** (`{:target :auto.laser/<id>}`) shows what that laser draws, before calibration: the same composition and guard its engine runs, so a split laser shows its two fitted halves. That includes what is sent to the laser's own ref. Here's why the cells work that way:
- A group can span several lasers, and overlaying their frames would draw something no laser draws.
- A group cell reads the same on any rig, which is what an author checks.
- One laser's two halves are a per-laser question, and Z2 already lists one entry per laser.
- It's cheap: a group cell is a lookup, and a laser cell composes only while it's shown.

The card's thumbnail is the third view: what the laser really sent, after calibration and safety (C-10).

**The inspector** gets a Fit guard section:

```
FIT GUARD
Minimum fps   [ 20 ]                        5–60
[✓] Draw fewer points below it   never below [ 60 ] %
[ ] Then dim                     never below [ 50 ] %
Below its minimum fps a laser draws fewer points, then dims if you allow it. Its card shows what it did.
```

| Where | Exact text |
|---|---|
| card, low | Z2's status line (`#projector-status-<id>`) turns bold amber, and the note starts "min 20 fps". The card gets `.rig-card-low` |
| card note | the parts that apply, joined by " · ": "min 20 fps" (only while low), "split · 2 regions" (or "split · 1 region"), "−40% pts", "−12% bright". Also "Safe area too small to split" (S-9) |
| thumbnail | "Not streaming" |
| toolbar chip | "LOW FPS · Laser R 14 fps", plus " +1" for each other low laser. Tooltip: one line per laser, "Laser R: 14 fps (minimum 20) · −40% pts", then "Click to see it." |
| cell tooltip | "Too heavy for Laser C: about 12 fps (minimum 20)" |
| editor line | "⚠ ~2,010 points: about 12 fps on Laser C (25 kpps). Most points: Circle (1,999). Simplify it, or lower Laser C's minimum." Until the item counts arrive, it leaves out "Most points: …" |
| picker | "Master (everything)", then Z2's entries |

## 8. Data examples and invariants

One laser `:p1` at 25 kpps, example b: track 1 (circle, 33 points) → `:auto/left`, track 2 (wave, 64) → `:auto/right`.

```clojure
(show-time/latest)
;; => {:tick 5120 :at-ns 9212345678901 :playing? true
;;     :cues [{:cell [0 0] :run 3 :zone-frames {:auto/left [… 33 points] :auto/right [… 64 points]}}]
;;     :routing {:map nil                                    ; Z4: the effective map id
;;               :refs {:all [[:p1 nil nil]] :auto/center [[:p1 nil nil]] :auto.laser/p1 [[:p1 nil nil]]
;;                      :auto/left [[:p1 :L nil]] :auto/right [[:p1 :R nil]]
;;                      :auto/odd [[:p1 :L nil]] :auto/even [[:p1 :R nil]]}
;;               :outputs {:p1 {:index 0 :scan-rate 25000
;;                              :fit {:min-fps 20 :reduce-points? true :point-floor 0.6 :dim? false :dim-floor 0.5}
;;                              :regions {:L [-0.25 0.0 0.75] :R [0.25 0.0 0.75]}}}}
;;     :positions {[0 0] 6.5} :pulses {} :faders {}         ; vibe Phases 4a and 5, as today
;;     :projector-ctx {…} :render-us 180.0 :preview #<Delay>}
;; :routing was made before the render, from the tick's state; the render didn't read it.
;; :p1 sends the circle fitted in :L, 2 dark points, the wave fitted in :R: 99 points, est-fps 252.5, no guard.

;; three lasers on :middle :both (spec §3): the middle laser is whole in both sides, tagged
{:auto/left [[:p1 nil nil] [:p2 nil :left]] :auto/right [[:p2 nil :right] [:p3 nil nil]] :auto/center [[:p2 nil nil]] …}

(meta (#'me/projector-frame :p3 snapshot))   ; a 1,750-point frame on :p3, before its projector effects
;; => {:tick 5120 :at-ns … :fit {:points-in 1750 :points 1250 :budget 1250 :min-fps 20 :scan-rate 25000
;;                               :est-fps 14.29 :guard-fps 20.0 :reduced-pct 28.6 :dim-pct 0.0 :regions 0}}
(get-in state [:backend :streaming :status :projectors :p3])
;; => {:state :streaming :fps 19.9 :scan-limited? true :low? false
;;     :load {:points-in 1750 :points 1250 :budget 1250 :min-fps 20 :est-fps 14 :guard-fps 20
;;            :reduced-pct 29 :dim-pct 0 :regions 0}}
(get-in state [:backend :streaming :cue-warnings])
;; => {[2 0] {:projector :p3 :points 1750 :fps 14 :min-fps 20 :scan-rate 25000
;;            :item {:id #uuid "…" :points 1200}}}          ; :item only while [2 0]'s editor is open
(get-in state [:config :preview :grid-cells])
;; => [{:target nil} {:target :auto/left} {:zone-group-id :graphics} {:target :auto.laser/p1}]
;;     master         a group              an old cell: reads as {:target :graphics}   a laser: what :p1 draws
```

**§6.2 on real refs** (circle A = 33 points, wave B = 64; a join adds 2). Z3 tests every row except i and j, which need zones and maps. Their Z3 forms are shown, and their own tests come in Z4:

| # | Z3 form of the example | p1 | p2 | p3 |
|---|---|---|---|---|
| a | 1 laser. A under `:auto/left`, next tick under `:auto/right` (the A ↔ B pattern itself is Z5) | A fitted in L, then in R: 33 | | |
| b | 1 laser. Track 1 → `:auto/left` (A), track 2 → `:auto/right` (B) | A in L, B in R: 99 | | |
| c | 1 laser. One key `[:auto/left :auto/right]` (A) | A whole, once: 33 | | |
| d | p1 in All and Graphics. Item 1 → `:all` (A), item 2 → `:graphics` (B) | A then B, whole: 99 | | |
| e | as d. One key `[:all :graphics]` (A) | A once: 33 (v22: the same frame under two keys sent 68) | | |
| – | 1 laser. The same frame object under `:auto/left` and `:auto/right` (step 4) | A once, whole: 33 | | |
| f | 3 lasers, `:split`. Track 1 → `:auto/left` (A), track 2 → `:auto/right` (B) | A: 33 | A in L, B in R: 99 | B: 64 |
| g | 3 lasers, `:both`. As f | A: 33 | A in L, B in R (auto-split): 99 | B: 64 |
| h | 3 lasers, `:both`. Only track 1 lit | A: 33 | A whole: 33 | nothing |
| i (Z4) | Z3 form: both tracks → `:all` | A + B, whole: 99 | 99 | 99 |
| j (Z4) | Z3 form, 2 lasers: tracks 1 and 3 → `:auto/left`, 2 and 4 → `:auto/right` | tracks 1 + 3 (one merged key) | tracks 2 + 4 | |

**Region fit** (spec §7). For the box `[-1 -1 1 1]` with w 0.75: L is −1…0.5 and R is −0.5…1, with s = min(1.5, 2)/2 = 0.75 and centres (−0.25, 0) and (0.25, 0). For a Z1 limit of `[-0.8 -0.5 0.8 0.5]`: s = min(1.2, 1.0)/2 = 0.5, with centres (−0.2, 0) and (0.2, 0).

**The fit guard**:

| Frame | r, min | N | budget ⌊r/min⌋ | ⌈0.6·N⌉ | N' | fps before → after | Cut | Low? |
|---|---|---|---|---|---|---|---|---|
| 1 dark + 1,999 lit (a circle, no corners) | 25,000, 20 | 2,000 | 1,250 | 1,200 | 1,250 | 12.5 → 20.0 | −37.5% (750 of 1,997 removable points, about 3 in 8) | no |
| 3,000 lit | 25,000, 20 | 3,000 | 1,250 | 1,800 | 1,800 | 8.3 → 13.9 | −40%. If dim is on: × max(0.5, 0.69), so −31% bright | **yes** |
| 1,000 | 25,000, 20 | 1,000 | 1,250 | – | the same object | 25 | none | no |
| 3,000, all dark (the Master at 0, a Strobe's dark half) | 25,000, 20 | 3,000 | 1,250 | 1,800 | 3,000: every point is kept | 8.3 | none. `:dark? true` (S-14) | as it was |
| the ceiling, the spec's first wording (S-5) | 30,000, 23 | 2,000 | 1,305 | 1,200 | 1,305 | → 22.99 | | **yes**, wrongly |
| the floor, now spec §8.1 | 30,000, 23 | 2,000 | 1,304 | 1,200 | 1,304 | → 23.01 | | no |

**Invariants**:
1. One render per cue per tick (spec 8). Routing, composition, the guard, the laser cells and the cue warnings never render: they transform frames that were already rendered. The routing is made before the render, from the tick's one read of app state, and never from its frames (spec §5).
2. The engines, the laser cells and the cue warnings compose from the snapshot alone. No composition step reads app state (spec 6). Only the projector effects and Z1's safety settings are still read live, as today. *Since `plans/2026-11-20-state-integrity/01-engine-snapshot.md` (`dba29bb`) they come with the snapshot too (`multi-engine/engine-inputs`): no engine reads app state.*
3. A laser draws each (cue, key) at most once, and each identical frame at most once per cue (spec 4). It has at most two regions, `:L` and `:R`, plus whole content (spec 5).
4. Composition is deterministic: the same cue frames and routing give the same frame, whatever the order of the keys in the cue's map.
5. Composition keeps every point. The frame has exactly the points of the distinct contributions (fitted ones moved, no colour changed), plus 2 per join, and `plan-points` equals its count.
6. Under budget the guard returns the same object. Over budget its output is a subsequence of its input: every blanked point, every run's ends, every dwell point and every corner over 45° stay, and there are never fewer than ⌈floor·N⌉ points. Colours change only when dimming is on. A frame with no lit point never sets or clears `:low?`.
7. No zone setup means today's behaviour: every enabled laser draws every `:all` cue once (spec 2). An old show draws the same points (spec 3). It draws the same frame whenever each cue reaches a laser through one key (S-4).
8. A group, order, middle, split-width, fit or limit edit shows on the next tick and restarts no engine. A zone `safety/normalize` can't read takes the halves away from that laser only.
9. The resolver is pure, and its output is reused (`identical?`) while its inputs are identical (spec 9).
10. Safety stays last. The effects grid (the vibe Master and Morphs included) runs inside the render, so composition and the guard see what it did. Composition and the guard run before the projector effects, and Z1's stage runs after them (spec 1). The test pattern skips composition and the guard, and the effects grid never touches it.
11. Load numbers and cue warnings reach app state at most 4 times a second, and only when they change.
12. Cost, estimated and *unverified*: probes z1–z4 measure it (§9).
    - Composition per laser costs about what today's join does (v23: 11–12 µs for two 1,000-point frames), plus 10–30 µs per 1,000 fitted points, and runs on the laser's own thread.
    - The guard costs 50–100 µs per 2,000 points, and only over budget.
    - The resolver costs one `identical?` check per tick, and tens of µs after an edit.
    - The cue warnings cost about cues × lasers × 1 µs, 4 times a second.
    - Thumbnails cost 15–200 µs each on the FX thread, at most 10 times a second each. Probe c8 measured 88 µs for a 3x2 preview grid.

## 9. Tests (named test vars + fixtures)

Pure tests come first. The fixtures to reuse:
- `snapshot` (`test/laser_show/backend/multi_engine_test.clj:31-35`), plus `:routing (rig/routing (rig/routing-inputs (state/get-raw-state) nil))`;
- `direct-frame` and `tick-and-compare` (`:323-353`);
- the cue fixtures and setup in `test/laser_show/routing/zone_routing_integration_test.clj:26-135`;
- `random-frame` and `sanitize-invariants-test` (`test/laser_show/idn/frame_rules_test.clj:23-57`): a seeded `java.util.Random`, 2,000 cases, the pattern for the property tests;
- `with-engines` (`test/laser_show/services/show_clock_test.clj:194-211`);
- `play-square!`, `lit-in` and `with-clock` (`test/laser_show/ui_test/preview_test.clj:43-86`), with `with-clock` moved to `ui_test/fixtures.clj`;
- `with-fake-dac` and `projector` (`test/laser_show/ui_test/streaming_status_test.clj:23-36`).

- `laser-show.routing.rig-test` (Z2's file; Z3 adds):
  - `normalize-target-test`, `zone-index-test`;
  - `routing-has-every-ref-the-rig-knows-test`: `:all` without excluded or disabled lasers; automatic groups with regions and sides for n = 1, 3 `:split` and 3 `:both`; `:auto.laser/*`; custom groups; a stray `:all` in a laser's list never overrides All;
  - `outputs-carry-scan-rate-fit-and-region-fits-test` (an excluded laser has one too, with no index);
  - `region-fits-follow-the-safety-limit-test`: the two boxes in §8, a box 0.04 wide gives nil, and a limit with its corners swapped gives the box `safety/normalize` makes of it;
  - `unreadable-safety-zone-draws-no-halves-test` (S-9): a zone `normalize` throws on gives that laser `:regions nil`, and the other lasers' routing is what it would be without it;
  - `cached-routing-is-reused-while-inputs-are-identical-test`, `cached-routing-follows-an-edit-test`;
  - `routing-of-an-invented-rig-test` (invariant 9).
- `laser-show.routing.compose-test` (NEW):
  - the §6.2 examples, one test each: `example-a-a-picture-moves-between-halves-test`, `example-b-one-laser-draws-two-fitted-halves-test`, `example-c-both-halves-of-one-laser-is-the-whole-laser-test`, `example-d-two-keys-two-pictures-overlay-test`, `example-e-one-key-through-two-groups-is-drawn-once-test`, `example-f-split-middle-laser-test`, `example-g-both-middle-splits-when-both-sides-differ-test`, `example-h-both-middle-with-one-side-stays-whole-test`;
  - the other rules: `both-middle-splits-across-two-cues-test` (S-1), `same-picture-under-two-keys-is-drawn-once-test`, `side-is-kept-only-when-every-entry-agrees-test`, `buckets-go-left-whole-right-in-key-order-test`, `key-order-test` (vector keys too), `fit-region-test`, `degenerate-safe-area-draws-no-halves-test`, `cues-join-in-their-order-test`, `nothing-reaches-the-laser-is-nil-test` (an unknown ref too), `plan-points-equal-the-built-frame-test`;
  - `old-show-draws-the-same-points-test` (invariant 7): with one key per cue, the result equals today's extract-and-combine frame; with two keys, the same points in another order;
  - `composition-invariants-test`, seeded: each key once; at most 2 regions; the count is the distinct frames' points plus 2 per join; the frame stays the same when the keys are reordered;
  - `cue-warnings-name-the-worst-laser-test`, `cue-warnings-name-the-heaviest-item-test`;
  - written in Z4, named here: `example-i-unison-draws-every-zone-everywhere-test`, `example-j-zones-wrap-over-two-lanes-test`.
- `laser-show.animation.fit-guard-test` (NEW): `under-budget-is-the-same-frame-test`, `budget-and-target-points-test` (the table in §8, S-5 included), `thinning-keeps-blanking-ends-dwell-and-corners-test`, `thinning-spreads-evenly-along-a-run-test`, `thinning-shares-the-cut-across-runs-test`, `floor-stops-the-thinning-test`, `dim-only-when-allowed-and-still-low-test`, `reduce-off-keeps-every-point-test`, `settings-are-clamped-test` (a value of the wrong type too), `smooth-averages-and-low-has-hysteresis-test`, `dark-frames-leave-low-as-it-was-test` (S-14: lit frames at the minimum with every other one dark, as a Strobe gives, never turn it on), and the seeded `guard-invariants-test` (invariant 6).
- `laser-show.services.frame-service-test` (existing):
  - NEW: `global-effects-run-once-per-distinct-frame-test` (it counts `effects/apply-effect-chain` calls and checks that both keys hold one result object), `distinct-frames-are-not-shared-test`, `frame-keys-are-normal-targets-test`, `item-points-only-when-asked-test`, `preview-ref-frames-follow-ref-membership-test`, `preview-projector-frames-are-what-the-engine-composes-test`;
  - the preview tests at `:161-186` read `:ref-frames`;
  - `timeline-clip-effects-see-pulses-test` (`:234`, vibe Phase 4a) passes unchanged;
  - `test/laser_show/services/frame_concatenation_test.clj` passes unchanged, through the alias.
- `laser-show.backend.multi-engine-test` (existing):
  - `extract-frames-for-zones-test`, `combine-zone-frames-test` and `get-projector-zone-groups-test` (`:194-270`) go, since the functions they test are removed. The compose tests cover what they checked.
  - Z2's `one-laser-draws-left-and-right-whole-test` was written to pin Z2's behaviour, so it becomes `one-laser-draws-left-and-right-halves-test` (example b, through the clock).
  - Z2's `projector-draws-all-without-listing-it-test`, `excluded-projector-skips-all-test` and `reorder-reroutes-left-test` keep their assertions on routed snapshots.
  - `projector-receives-only-zone-specific-frames-test` (`:272-309`) and `engine-frame-equals-direct-render-test` (`:355-395`) keep their assertions. Their snapshots carry routing, and `direct-frame` composes.
  - Z1's test-pattern tests (`test-pattern-shows-with-nothing-playing-test`, `master-doesnt-dim-the-test-pattern-test`, `blackout-hides-the-test-pattern-test`) pass unchanged.
  - NEW: `engine-routes-from-the-snapshot-not-app-state-test`, `fit-guard-runs-before-projector-effects-test`, `frame-carries-its-fit-record-test`, `test-pattern-frame-carries-no-load-test`, `provider-records-what-it-produced-test`.
- `laser-show.routing.zone-routing-integration-test` (existing): `projector-multi-zone-concatenation-test` (`:601-627`) composes instead of calling the removed helpers. Its assertions don't change. Z2's `laser-show.state.migration-test/old-show-routes-as-before-test` compares through `compose/projector-frame` instead of `extract-frames-for-zones`, and compares the same points (S-4).
- Engines and status (existing):
  - `laser-show.backend.streaming-engine-test`: NEW `stats-keep-the-frames-fit-record-test`, patterned on `stats-report-the-fps-the-projector-draws-test` (`:138`).
  - `laser-show.backend.output-lifecycle-test`: NEW `status-carries-each-lasers-load-test`, patterned on `:371`. `zone-group-edit-restarts-nothing-test` (`:289`) also edits `:fit`, the limit and the rig order.
  - `laser-show.events.output-sync-test`: `name-or-zone-change-requests-nothing-test` (`:69`) also changes `:fit`, `[:safety :limit]` and `:exclude-from-all?`.
- `laser-show.services.show-clock-test` (existing):
  - NEW: `snapshot-carries-routing-test` (next to `snapshot-shape-test`, `:228-250`), `routing-is-made-before-the-render-test` (spec §5's order: a redefined `generate-frames-by-zone` finds the tick's routing already made), `routing-is-reused-while-nothing-changes-test`, `routing-follows-a-group-edit-next-tick-test`, `a-throwing-resolver-keeps-the-last-routing-test`, `cue-warnings-reach-app-state-test`, `edited-cue-names-its-heaviest-item-test`;
  - unchanged and still passing: `renders-once-per-tick-test` (`:213-226`, invariant 1), `one-state-and-input-read-per-tick-test` (`:381-409`: the routing reads the tick's state, not its own), `preview-is-built-after-the-engines-wake-test` (`:290-304`), `app-state-is-quiet-while-a-cue-plays-test` (`:328-340`), whose path list (`:309-310`) gains the new path, vibe Phase 4a's `render-context-carries-pulses-test` (`:421-447`) and `projector-effects-never-see-pulses-test` (`:449-458`), Phase 5's fader tests (`:512-605`) and Phase 6's `vibe-report-is-sent-once-per-interval-test` (`:146-174`);
  - `preview-zones-have-blanking-between-cues-test` (`:266-278`) reads `:ref-frames`.
- Events, subs and views (existing, unless marked NEW):
  - `laser-show.events.projector-handlers-test`: `set-fit-clamps-and-is-undoable-test`;
  - `laser-show.events.ui-handlers-test`: `old-preview-cell-reads-as-a-target-test`, `set-cell-zone-writes-a-target-test`, `reveal-projector-opens-its-card-test`;
  - `laser-show.subs-test`: `low-fps-projectors-test`, `cue-warning-sub-test`;
  - `laser-show.views.components.preview-test`: `get-zone-filtered-points-test` (`:14`) becomes `cell-points-test`, which covers the master cell, a group, a laser and an old cell;
  - NEW `laser-show.views.components.load-warning-test`: `cue-warning-text-test`, `low-fps-text-test`, `load-note-text-test`.
- UI tests. Each namespace uses `with-toolkit` once and `with-app` (plus the fake DAC where it streams) for each test:
  - NEW `laser-show.ui-test.rig-card-test`: `thumbnail-draws-what-the-laser-sent-test`, `thumbnail-says-not-streaming-test`, `card-turns-amber-below-its-minimum-test` (a 3,000-point cue at 25 kpps), `thinned-laser-shows-its-cut-test`, `fit-guard-settings-apply-test`;
  - `laser-show.ui-test.streaming-status-test`: `low-fps-chip-names-the-laser-and-opens-its-card-test`, `light-laser-shows-no-chip-test`;
  - NEW `laser-show.ui-test.cue-load-test`: `heavy-cue-shows-a-warning-on-its-cell-test`, `warning-goes-when-the-cue-stops-test`, `cue-editor-names-the-laser-and-the-heaviest-item-test`, `timeline-editor-shows-the-same-line-test`;
  - `laser-show.ui-test.preview-test`: `laser-cell-shows-both-halves-of-a-split-laser-test` (pixels lit left and right of centre), `group-cell-shows-what-is-sent-to-it-test`, `old-cell-still-shows-its-group-test`. `preview-cell-shows-only-its-zone-test` (`:105-116`) passes unchanged.
- **Probes** (NEW `plans/2026-11-19-zones/probes/zones_probes_test.clj`, kept off the classpath like the other plans' probes):
  - z1: composition per laser with 1, 3 and 8 lasers, whole and split, on 1,000-point frames;
  - z2: the guard on 1,000, 2,000 and 4,000 points;
  - z3: each engine's IDN time with 3 lasers on the fake DAC, before (at Z2's commit) and after;
  - z4: renders per tick with 8 engines (show-clock probe c2).

  The render time is also checked against JFR `FrameGeneration` `baseTimeUs` (`services/show_clock.clj:74-81`), which should be unchanged. No new JFR event is added (§11).
- **Hardware check** (00 R-1), on a real projector at 25 and 40 kpps:
  - example b, looking for flicker and for the halves sitting inside the limit;
  - a 3,000-point cue with reduce on and off, and with the Master pulled down and up again (the card stays as it was while the show is dark, S-14);
  - Identify (Z1) still works.

Run:
- unit tests: `clj -M:mac-arm:test rig compose fit-guard concatenat frame-service multi-engine zone-routing streaming-engine output-lifecycle output-sync show-clock projector-handlers ui-handlers subs preview load-warning`;
- UI tests, one namespace at a time: `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.rig-card-test`, then the same with `-n laser-show.ui-test.streaming-status-test`, `.cue-load-test`, `.preview-test` and `.grid-test`;
- then both full suites;
- probes: `P=plans/2026-11-19-zones; clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" -M:mac-arm:test:probe -d "$P/probes" -n probes.zones-probes-test 2>&1 | grep PROBE`.

## 10. Commit plan

Each step is one commit. Write the named tests first and watch them fail, then the code, then run the tests named in §9.

| # | Commit message | Red first |
|---|---|---|
| 1 | `refactor(render): the blanking join moves to animation/frames.clj, so routing can join frames without requiring the frame service; frame-service/concatenate-frames stays as its alias` | none: it's a move. `frame-concatenation-test` and every caller pass unchanged |
| 2 | `feat(routing): the rig resolver gives every group its lasers, halves and sides, and each laser its scan rate, fit settings and the boxes its halves fit into; it works them out again only when the rig or a group changes, and a safety zone it can't read takes the halves away from that laser only` | `rig-test` (above) |
| 3 | `feat(routing): what one laser draws from a tick: each key once and each picture once, a split laser's Left and Right fitted into its safe area, joined Left, whole, Right in a fixed order` | `compose-test` (examples a–h, the property test) |
| 4 | `feat(output): each tick works out its routing before it renders and the snapshot carries it; every engine composes its laser's frame from it, so a group edit shows on the next tick and restarts nothing, and no engine reads zone state` | `engine-routes-from-the-snapshot-not-app-state-test`, `one-laser-draws-left-and-right-halves-test`, `snapshot-carries-routing-test`, `routing-is-made-before-the-render-test`, `routing-is-reused-while-nothing-changes-test`; then the tests that used the removed helpers move over |
| 5 | `feat(render): an effects-grid point effect runs once per distinct picture in a cue, so a picture under two keys stays one picture and is drawn once; frame keys are targets in normal form` | `global-effects-run-once-per-distinct-frame-test`, `frame-keys-are-normal-targets-test` |
| 6 | `feat(output): the fit guard: a laser that would draw below its minimum fps draws fewer points (never under its floor, keeping blanking, line ends and corners), then dims if allowed, before its calibration; a frame nobody sees, all dark, never makes it low` | `fit-guard-test`, `fit-guard-runs-before-projector-effects-test`, `test-pattern-frame-carries-no-load-test` |
| 7 | `feat(output): each laser's load (points, the fps it would draw, what the guard cut, low or not) reaches the output status, and what it produced is kept for its thumbnail` | `stats-keep-the-frames-fit-record-test`, `status-carries-each-lasers-load-test`, `provider-records-what-it-produced-test` |
| 8 | `feat(output): a playing cue too heavy for a laser is found from the tick's frames four times a second, and the item with the most points is named while its editor is open` | `cue-warnings-*` (compose), `cue-warnings-reach-app-state-test`, `item-points-only-when-asked-test` |
| 9 | `feat(ui): each rig card shows what its laser sent, a load meter and what the fit guard did, and turns amber below its minimum; the toolbar says LOW FPS · Laser R 14 fps and opens that card; the Fit guard settings` | `rig-card-test`, `low-fps-chip-*`, `set-fit-*`, `reveal-projector-*` |
| 10 | `feat(ui): a cue too heavy for a laser shows ⚠ on its cell and a line in its editor naming the laser and its heaviest item, gone when the cue stops or gets lighter` | `cue-load-test`, `cue-warning-sub-test`, `cue-warning-text-test` |
| 11 | `feat(preview): a preview cell on a laser shows what that laser draws (a split laser's two halves), and a cell on a group shows what is sent to it; old cells keep their group` | `laser-cell-shows-both-halves-of-a-split-laser-test`, `cell-points-test`, `old-preview-cell-reads-as-a-target-test` |
| 12 | `plans: zones Z3 done (<sha>): as built, deviations, tests, red-first results, probes z1-z4 before and after, and the check on a real projector` | – |

Steps 1–3 and 5–6 need nothing from the UI. Steps 9–11 can be reordered among themselves.

## 11. Out of scope / alternatives considered

- **Zones, maps, inheritance, vector targets in the editors**: Z4. They change only `rig/resolve-ref`, the preview's ref matching (a zone counts for its lane's ref) and the map id passed to the resolver (doc 06 §4).
- **The guard in the clock**, as one estimate for all lasers that would also give meters offline: rejected. The engine's numbers are what was really sent, and the clock already gives the cue warnings.
- **Thumbnails composed on the FX thread through the projector effects**: rejected. That doubles the engines' work and still can't show safety or a failure.
- **Fitting whole content into the safe box**: no. Old shows must look the same, and safety clips.
- **Keeping `:zone-frames` next to `:ref-frames`**: rejected; two near-identical maps would only confuse.
- **Value hashing for "each picture once"**: rejected (spec §6.1). It costs time on every tick, and two cues that draw the same shape on purpose would merge.
- **Per-item counts for every cue on every tick**: rejected. Only an open editor needs them.
- **A JFR event for composition**: not added. The IDN time already covers the provider (`backend/streaming_engine.clj:380-381`), and an event would need a Java class and a build step (`java/laser_show/profiling/`, `build.clj:35-44`).
- **Left as they are**: virtual projectors (no engine routes them today), and `get-preview-zone-filter` and `matches-preview-zone?` (`services/frame_service.clj:62-82`), which only tests use. The default preview cells are Z2's.
- **Later**: inverting the corner pin for regions (00 R-3), fades between regions (00 §8), a large preview and a Wall of thumbnails (needs/08 R-14), protecting colour changes from thinning.

## 12. Spec questions and open questions

**Spec questions**: places where 01 is unclear or the code disagrees with it, and what this plan chose. S-1, S-2, S-3, S-5, S-6, S-7 and S-11 are now in the spec, as chosen here.

| # | Spec | The problem | Chosen |
|---|---|---|---|
| S-1 | §6 step 5 (auto-split) was written under "one cue's frames". **Now spec §6 step 5** | D-27 says Both splits "while both sides are lit with different content". With one cue per side, a per-cue rule would never split | The test runs over all of the tick's cues for that laser (`split-sides?`). Asked as Q-Z3-1 |
| S-2 | §5 said `:routing` is `{:map :refs}`. **Now spec §5's shape** | Composing also needs each laser's scan rate, fit settings and safe box. Reading those live would let an engine and the preview disagree for a tick | `:routing :outputs {pid {:index :scan-rate :fit :regions}}`, from the same memoised resolver |
| S-3 | §5 said `:refs` covers the refs in the tick's keys. **Now spec §5** | That ties the routing to each tick's frames, so it couldn't be reused from tick to tick, nor made before the render (spec §5's order, Z5 S-3) | `:refs` holds every non-zone ref the rig knows (a superset: Z2's `ref-entries`). Everything reads it through `rig/resolve-ref`, where Z4 resolves zones by the effective map |
| S-4 | §6 step 6 sorts the keys; invariant 3 says old shows route exactly as before | Today a laser joins a cue's keys in its `:zone-groups` order (`backend/multi_engine.clj:61-73`), and Z2 joins in `refs-for` order | The spec's order, as Z2's S-1 expects. The frames are identical whenever each cue reaches a laser through one key (nearly always); otherwise the same points come in another order. The fixture tests say which case they check |
| S-5 | §8.1 said `N' = max(⌈r/min-fps⌉, …)`. **Now spec §8.1** | The ceiling leaves the frame just under the minimum (30,000 pts/s, min 23: 1,305 points, 22.99 fps), so a laser the guard fixed would be flagged LOW | `⌊r/min-fps⌋` |
| S-6 | §8 said P is low when `guard-fps < :min-fps`. **Now spec §8** | Checked frame by frame, a cue near the limit flips the badge on and off | Averaged over about 10 frames, and cleared only at 1 fps above the minimum |
| S-7 | §8.1: points are removed "evenly along each run, in proportion to its length". **Now spec §8.1** | Length in points or in distance? | In removable points, so each run loses the same share, spread evenly by index |
| S-8 | §8: N is the frame's points | Z1's safety (blanking at crossings) and `sanitize-frame` (a dark first point, `idn/frame_rules.clj:19-31`) add points after the guard | Accepted: the estimate is a few points optimistic. The measured fps on the card (`backend/streaming_engine.clj:267-308`) stays the ground truth |
| S-9 | §7: fit into `min(w·W, H)/2`; §10 fails closed per laser | A tiny or zero safe box squeezes a half into a near-static point: a burn and audience hazard. And Z1's `normalize` throws on a zone it can't read (03 S-Z1-7), inside the one resolver every laser shares | Under 0.05 wide or high, the laser draws no halves (its whole content still goes through safety), and its card says "Safe area too small to split". A zone `normalize` can't read gives the same `:regions nil` for that laser only: the others route on (D-29), and Z1's stage sends it dark with "Safety error: output off" |
| S-10 | §8: the per-item counts "come from the tick's render" | The render counts only one total (`services/show_clock.clj:74-81`) | Top-level item counts are recorded only for the cues whose editor is open (`:item-points? true` on the render context). Cue-level counts come from the snapshot's frames |
| S-11 | §8.3 said the stats "are not part of state". **Now spec §8.3** | The UI reads app state | They reach `[:backend :streaming :status]`: runtime state, never saved or undone, the same as `:fps` |
| S-12 | §11: preview cells `{:zone-group-id x}` become `{:target x}` | It doesn't say what a cell shows, and it has no laser view | A group cell shows what is sent to the group. A cell on a laser's ref (`:auto.laser/<id>`, which Z2's picker already lists) shows what that laser draws. Z3 reads `:zone-group-id` as `:target`, so no load-time step is needed before Z4 |
| S-13 | §10: the test pattern goes through safety | Composition and the guard aren't mentioned | The test pattern and Identify skip both, since they replace the content. 03 §4 puts them in Z1's `cond` before the `:playing?` branch, the only one Z3 changes |
| S-14 (NEW) | §8.1: only lit runs are thinned; §8's low flag | Since vibe Phase 5 the Master at 0 darkens every point, and so do a Strobe reaction's dark half and a Fade in's start (Phase 4a). The guard can't thin a frame with no lit point, so its estimate stays under the minimum, and averaged with the lit frames it would turn a laser amber while it strobes, or while the show is faded out, though nothing is seen | Over budget, a frame with no lit point says `:dark? true`, and `smooth` leaves the averages and `:low?` as they were for it. Its meter still shows its load, and the cue warnings still count its points (a heavy cue is still heavy) |

**Open questions for you** (★ = recommended):

**Q-Z3-1 · The Both middle laser with two cues.** The middle laser is on Both. Cue 1 lights Left (a circle) and cue 2 lights Right (a wave).
- **A ★ It splits:** the circle in its left half, the wave in its right. This reads D-27 as "both sides lit with different pictures, by any cues".
- **B It draws both whole, overlaid,** and splits only when one cue lights both sides.

**Q-Z3-2 · The card when a laser isn't streaming.**
- **A ★ "Not streaming", and no meter.** The card only ever shows what was really sent, and a preview cell set to that laser shows what it would draw at any time.
- **B The would-be frame (before calibration) in grey,** with an estimated meter.
- **C The would-be frame through its calibration,** computed on the screen thread. That repeats the engines' work.

**Q-Z3-3 · How long a cue's ⚠ stays.**
- **A ★ While it plays.** Always current, as spec §8 says.
- **B Until it plays light again,** for the rest of the session.
- **C Saved with the show.**

**Q-Z3-4 · What 100% means on the load meter.**
- **A ★ The points that still reach the laser's minimum fps,** so past 100% the guard acts.
- **B The points that reach 60 fps:** 416 at 25,000 pts/s, so most shows would read over 100%.

**Q-Z3-5 · A laser the guard thinned back up to its minimum.**
- **A ★ Its card says "−28% pts", and there's no toolbar chip.** The chip is only for lasers still below the minimum.
- **B The toolbar also shows a THINNED chip.**

Raised while building (§13), each built as A:

**Q-Z3-6 · The show lock and a laser's fit guard settings.**
- **A ★ The lock lets them through.** A fit edit restarts and re-routes nothing, none of them lets light anywhere new or makes it brighter than the show made it, and lowering a laser's minimum is what a cue's ⚠ asks you to do mid-show.
- **B The lock refuses them,** as it refuses the rig's order, middle laser and split width.

**Q-Z3-7 · The fit guard costs twice its estimate** (probe z2: about 190 µs per 2,000 points, against invariant 12's 50–100).
- **A ★ Leave it.** It runs on the laser's own thread and only for a frame over budget, which then takes at least 50 ms to draw (25 kpps, a minimum of 20).
- **B Make it faster now,** with primitive arrays in `keep-mask` and `thin-with` (2–3× is a guess), before a rig of many heavy lasers needs it.

## 13. As built (2026-11-20, `cb28966`..`298c253`, on `master`)

Built in worktree `zones-z3` off `6bedf25` (Z0, Z1 and Z2 on `master`), while ZR was built in its own worktree. ZR has since landed on `master` (`e38576f`), and "For the merge" below is a trial merge onto it. Rebased onto `e38576f` with no conflicts and fast-forwarded onto `master`. The ★ answers are built: Q-Z3-1 A to Q-Z3-5 A. **The check on a real projector (00 R-1, §9) is not done,** so by 00 R-1 Z3 isn't done yet.

### Where it landed

| # | Commit | Step (§10) |
|---|---|---|
| – | `cb28966` | NEW `plans/2026-11-19-zones/probes/zones_probes_test.clj` (z1–z4), run at `6bedf25` before any change |
| 1 | `cf5ddf6` | NEW `animation/frames.clj`, the blanking join; `frame-service/concatenate-frames` is its alias |
| 2 | `84e6518` | `routing/rig.clj`: `normalize-target`, `key-refs`, `zone-index`, `region-fits`, `routing-inputs`, `routing`, `resolve-ref`, `cached-routing`; NEW `animation/fit_guard.clj` with `defaults` and `settings` |
| 3 | `5ac7fcd` | NEW `routing/compose.clj`: `key-order`, `contributions`, `merge-pictures`, `split-sides?`, `cue-plan`, `plan-points`, `fit-region`, `build`, `projector-frame`, `cue-points` |
| 4 | `7efb841` | the clock's `routing-step`, before the render (a resolver that throws keeps the last routing and is reported under `[:routing]`); `:routing` in the snapshot; `multi-engine` composes from it, and `extract-frames-for-zones`, `combine-zone-frames` and `get-projector-zone-groups` are gone |
| 5 | `b7f1c0b` | `frame-service/apply-global-effects-once` (an `IdentityHashMap`); `zone-effects` makes frame keys in normal form |
| 6 | `8a0fcc9` | `fit-guard`: `budget`, `target-points`, `keep-mask`, `thin`, `dim`, `guard`, `smooth`; the engine guards each composed frame before the projector effects, and the frame's meta carries `:fit` |
| 7 | `b9c008d` | the engine's stats keep the smoothed `:fit`; `output-runtime/engine-status` adds `:low?` and the rounded `:load`; `show-time/record-output!` and `output-frame` |
| 8 | `a82dc83` | `compose/cue-warnings`; the clock's status writes `[:backend :streaming :cue-warnings]`; `:item-points?` on the render of a cue whose editor is open |
| 9 | `fa9239c` | the card's thumbnail (`preview/output-thumbnail`), meter, note and amber state; the toolbar's `low-fps-chip`; the inspector's `fit-guard-section`; `:projectors/set-fit`, `:ui/reveal-projector`; subs `projector-load`, `low-fps-projectors`; NEW `views/components/load_warning.clj`; `clock-view`'s `:every-ms` |
| + | `9d2fdcb` | fix: a card keeps one height, with the guard's note on one line under the thumbnail; tooltips on the note and the status line; the cards top-aligned |
| 10 | `3b25127` | `subs/cue-warning`; `load-warning/cue-warning-text`, `cue-line-text` and `cue-load-warning`; ⚠ and its tooltip on the cell; the line above both editors' footers |
| 11 | `f1a52ba` | `frame-service/preview-data [cues routing]` with `:ref-frames` and `:projector-frames`; `preview/cell-points`; cells are `{:target …}`, and `:preview/set-cell-zone` takes `:target`; `subs/preview-cell-config` reads an old cell |
| + | `298c253` | fix: every ref a preview cell holds is re-pointed; the fit test's unsaved check holds with ZR too (both found by the trial merge, below) |
| 12 | this commit | this note |

### Deviations from the plan

- **The show lock lets `:projectors/set-fit` through.** `events/show_lock.clj` is unchanged. A fit edit restarts and re-routes nothing, like a calibration edit, and lowering a laser's minimum is what a cue's ⚠ tells you to do, mid-show too. Asked as Q-Z3-6.
- **Any frame with no lit point is `:dark? true`, under budget too** (§8 and S-14 say over budget). So invariant 6's "a frame with no lit point never sets or clears `:low?`" holds for every dark frame. The scan stops at the first lit point. An empty frame is dark too, with nil fps.
- **A cue's warning names the worst laser:** the lowest fps, then the lower `:index`, then a laser excluded from All (no index), then the id.
- **The editor line rounds the item's points to 10,** as it does the cue's, so §7's example reads "Most points: Circle (2,000)", not "(1,999)". The warnings in app state change less often that way.
- **More in the load and its record:** `:load` also carries `:scan-rate` (the meter's tooltip) and `:no-room?` (S-9's "Safe area too small to split", from compose). `smooth` adds `:samples`, the frames averaged.
- **Small rules the plan left open:** an item or group with an empty target inherits (nil) instead of making a `[]` key (`zone-effects`, step 5), and `item-points` skips an item that rendered nothing (step 8).
- **The card:** §7's full-width thumbnail is a 44 px square with the meter beside it, so the card grows less. Under them is the guard's note, on one line of fixed height, there even when empty, with a tooltip that says all of it. The status line has a tooltip too, and the cards are top-aligned, so they never change height.
- **The strip is 60 px taller.** `strip-height` was `(min 400 (+ 72 (* 17 rows) 38))` and is `(min 400 (+ 72 (+ thumbnail-size 2) 14 (* 17 rows) 38))`, so it is 60 px taller up to 13 rows, then less, under the same 400 px cap. The inspector below it starts 60 px lower. Z2's `audience-tag-offers-exclude-from-all-test` scrolls to the tags, which the taller strip pushed below the fold.
- **Small UI additions:** CSS `.load-meter-track` and `.load-meter-near` (amber, 80% to 100%) beside §4's `.load-meter` and `.load-meter-over`; `commit-field` takes `:disable`; the `rig-cards` sub gives each card its `:limit`, for the thumbnail's grey outline.
- **The preview (step 11):**
  - A new project's default cells are written `{:target …}`, with Z2's refs, so the docstring holds for them.
  - When the show has no groups, `set-grid-layout` fills new cells from `:all :auto/left :auto/right :auto/center`. It used `:left :right :center`, ids that Z0's migration renames.
  - The picker's Master option is `#preview-zone-option-<i>-master`. It was `…-all`, the same id as All's. `preview-layout-selector-and-zone-picker-are-clickable` checks "Master (everything)" and clicks Master.
  - Z0's ref table (`routing/refs.clj`) re-points a cell's `:target`. Without it, a `{:target …}` cell kept a deleted group. Since `298c253` it re-points both `:target` and `:zone-group-id` when a cell has both.
  - The projectors docstring's `:fit` (§4) went in with step 11, not step 6.
- **Tests:**
  - `projector-receives-only-zone-specific-frames-test` checks the first and last points in the spec's key order (Center before Left, S-4), with one more assertion.
  - `old-show-routes-as-before-test` finds the frames a laser draws as runs of points in `compose/projector-frame`'s frame, each drawn once.
  - `with-clock` moved to `ui_test/fixtures.clj` (§9). Called without a start time, it now ticks on the wall clock, so the clock's status keeps coming, 4 times a second.
  - `plans/2026-11-19-zones/probes/vibe_probes_test.clj`'s `zone-snapshot` carries `:routing`, or v19, v21 and v22 print nil after Z3.
  - Beyond §9: `load-meter-test` and `replace-refs-rewrites-a-cells-target-test`.

### Tests added

- `laser-show.routing.rig-test`: the nine §9 tests.
- `laser-show.routing.compose-test` (NEW): the 22 §9 tests, examples a–h, the rules, `old-show-draws-the-same-points-test`, the seeded `composition-invariants-test` and the two cue-warning tests.
- `laser-show.animation.fit-guard-test` (NEW): the twelve §9 tests, the seeded `guard-invariants-test` among them.
- `laser-show.services.frame-service-test`: the six §9 tests. The three `test-zone-frames-*` tests read `:ref-frames`.
- `laser-show.services.show-clock-test`: the seven §9 tests. `preview-zones-have-blanking-between-cues-test` reads `:ref-frames`.
- `laser-show.backend.multi-engine-test`: `engine-routes-from-the-snapshot-not-app-state-test`, `fit-guard-runs-before-projector-effects-test`, `frame-carries-its-fit-record-test`, `test-pattern-frame-carries-no-load-test` and `provider-records-what-it-produced-test`. Z2's `one-laser-draws-left-and-right-whole-test` is `one-laser-draws-left-and-right-halves-test`. `extract-frames-for-zones-test`, `combine-zone-frames-test` and `get-projector-zone-groups-test` went with their functions.
- `laser-show.backend.streaming-engine-test`: `stats-keep-the-frames-fit-record-test`.
- `laser-show.backend.output-lifecycle-test`: `status-carries-each-lasers-load-test`. `zone-group-edit-restarts-nothing-test` also sets a fit, a safety limit and the rig's order, with two lasers.
- `laser-show.events.output-sync-test`: `name-or-zone-change-requests-nothing-test` also changes a fit, the safety limit and Exclude from All.
- `laser-show.events.projector-handlers-test`: `set-fit-clamps-and-is-undoable-test`.
- `laser-show.events.ui-handlers-test`: `reveal-projector-opens-its-card-test`, `set-cell-zone-writes-a-target-test` and `old-preview-cell-reads-as-a-target-test`.
- `laser-show.subs-test`: `low-fps-projectors-test` and `cue-warning-sub-test`.
- `laser-show.views.components.load-warning-test` (NEW): `low-fps-text-test`, `load-note-text-test`, `load-meter-test` and `cue-warning-text-test`.
- `laser-show.views.components.preview-test`: `cell-points-test` replaces `get-zone-filtered-points-test`.
- `laser-show.routing.refs-test`: `replace-refs-rewrites-a-cells-target-test`.
- Changed fixtures: `zone-routing-integration-test/projector-multi-zone-concatenation-test` composes, and `migration-test/old-show-routes-as-before-test` compares through `compose/projector-frame`.
- UI:
  - `laser-show.ui-test.rig-card-test` (NEW): the five §9 tests.
  - `laser-show.ui-test.streaming-status-test`: `low-fps-chip-names-the-laser-and-opens-its-card-test` and `light-laser-shows-no-chip-test`.
  - `laser-show.ui-test.cue-load-test` (NEW): the four §9 tests.
  - `laser-show.ui-test.preview-test`: the three §9 tests. `preview-cell-shows-only-its-zone-test` passes unchanged.

### Red first

Where the new code didn't exist yet, the run was first made to compile with stubs (nil, or a value no test expects), so every test below was seen failing one by one unless it says otherwise.

| Step | Before the change |
|---|---|
| 1 | none: it's a move. The namespaces that use the join passed unchanged (102 tests, 396 assertions) |
| 2 | `rig-test` and `fit-guard-test` couldn't load (no `animation.fit-guard`). Against stubs, 9 of the 10 new tests: 58 failures. `cached-routing-is-reused-while-inputs-are-identical-test` passed against the stubs (nil is identical to nil); against a `cached-routing` with no cache it failed 3 |
| 3 | `compose-test` couldn't load. Against stubs, all 20 tests: 10,329 failures, 2 errors |
| 4 | the seven new engine and clock tests, against the old clock and engine: 12 failures, 5 errors |
| 5 | couldn't compile. With today's per-key loop pulled out as `apply-global-effects-once`: 7 failures (`frame-keys-are-normal-targets-test` 5, `global-effects-run-once-per-distinct-frame-test` 2). `distinct-frames-are-not-shared-test` passes against that, as it should; against a version that hashes values (a `HashMap`) it failed 4 |
| 6 | `fit-guard-test` against stubs: the 11 new tests, 51 failures, 24 errors. The engine's three: 7 failures, 1 error, but `test-pattern-frame-carries-no-load-test` passed against the old engine (the pattern never had a `:fit`), so it was **not seen red** |
| 7 | couldn't compile (no `show-time/output-frame`). Against stubs, all 3: 13 failures |
| 8 | against a nil `compose/cue-warnings`, all 5 new tests: 18 failures |
| 9 | unit: couldn't compile. Against stubs, 7 tests: 36 failures. `name-or-zone-change-requests-nothing-test` passes either way (an unknown event requests nothing too): **not seen red**. UI: the views were written **before** their tests. Run with the step's handlers and subs and `HEAD`'s views, 6 of the 7 failed (6 errors). `light-laser-shows-no-chip-test` passed (there was no chip at all): **not seen red** |
| + `9d2fdcb` | a layout fix with no new test. Z2's `audience-tag-offers-exclude-from-all-test` failed after the strip grew, and passes scrolling to the tags |
| 10 | couldn't compile. Against stubs: 8 failures (`cue-warning-text-test` 6, `cue-warning-sub-test` 2). `cue-load-test` against the old views: all 4, 4 errors. The first run with the code found a bug in step 9's fixture: called without a start time, `with-clock`'s show time never moved unless a test moved it, so the status (every 250 ms of show time) stopped coming (3 errors) |
| 11 | couldn't compile. Against a stub `preview/cell-points`: 12 tests, 20 failures and 3 errors (`cell-points-test` 9, `set-cell-zone-writes-a-target-test` 4, `old-preview-cell-reads-as-a-target-test` 2, the three changed `test-zone-frames-*` 5; errors in `preview-ref-frames-follow-ref-membership-test`, `preview-projector-frames-are-what-the-engine-composes-test` and `preview-zones-have-blanking-between-cues-test`). `replace-refs-rewrites-a-cells-target-test`: 2 failures. UI: `laser-cell-shows-both-halves-of-a-split-laser-test` 1 failure, 1 error. `group-cell-shows-what-is-sent-to-it-test` and `old-cell-still-shows-its-group-test` passed against the old code (a group cell and an old cell already worked that way, and they keep it so): **not seen red** |
| + `298c253` | the refs test's cell with both keys: 2 failures. The changed fit test can't fail on this branch (both edits mark the show unsaved here); it was checked on the trial merge, where the old assertion failed |

### Verification

| Where | Unit suite (`clj -M:mac-arm:test`) | UI suite (`clj -M:test-ui:mac-arm:test-ui-headless`) |
|---|---|---|
| `6bedf25` (the base) | 1,306 tests, 463,805 assertions, 0 failures | 173 tests, 806 assertions, 0 failures (04 §13's run at `8c30108`; `6bedf25` changes only plans) |
| step 8, `a82dc83` | 1,364 tests, 541,190 assertions, 0 failures | – |
| step 11, `f1a52ba` | 1,377 tests, 541,274 assertions, 0 failures | 187 tests, 848 assertions, 0 failures |
| **`298c253`, the branch's last code** | **1,377 tests, 541,277 assertions, 0 failures** | **187 tests, 848 assertions, 0 failures** |
| the trial merge onto `master` `e38576f` (tree `e83a727`, below) | 1,437 tests, 541,737 assertions, 0 failures | 194 tests, 879 assertions, 0 failures |
| **rebased onto `e38576f`, as landed** | **the same `src/`, `test/`, `deps.edn` and QUICKSTART trees as the trial merge (`e83a727`), so its run above stands** | **the same** |

Each step also ran its targeted namespaces (§9's run list) and the UI namespaces whose views it changed. `streaming-engine-test/new-tick-goes-out-without-the-minimum-gap-test` passed in every full run.

**Probes z1–z4** (`plans/2026-11-19-zones/probes/zones_probes_test.clj`). Other agents' test suites ran on this machine at the same time, and the first run after was slower everywhere. So "before" was run twice more from `git archive cb28966` (the base with the probes), alternating with "after" (`f1a52ba`). The ranges are the three runs of each. Medians, except where it says avg:

| Probe | Before | After |
|---|---|---|
| z1, a whole laser of 8 (1,000 points), the engine's frame | 8.3–9.4 µs | 9.6–11.9 µs (one run had three at 15.6–16.1). Composing alone: 1.5–2.5 µs |
| z1, 1 laser whole, the first case measured | 12.0–12.4 µs | 17.4–18.7 µs. Composing alone: 2.1–3.4 µs; the rest isn't explained |
| z1, a split laser (1 laser, and the middle of 3) | 2,002 points (both pictures whole, overlaid, 12.5 fps), 19.5–21.8 µs | 1,250 points (two fitted halves, then the guard, 20 fps), 253–262 µs. Composing alone: 58–62 µs |
| z2, the guard at 25 kpps, minimum 20 | no guard | 1,000 points: 0.3–0.4 µs, the same object. 2,000 → 1,250 (12.5 → 20 fps): 188–206 µs. 4,000 → 2,400 (the 60% floor; 6.3 → 10.4 fps): 377–432 µs |
| z3, 3 engines on the fake DAC for 3 s, one cue on All: IDN time avg; the clock's render avg | 332–443 µs; 229–484 µs | 308–508 µs; 306–455 µs |
| z3, the same, Left and Right: IDN time avg; render avg | 224–441 µs; 272–612 µs | 214–451 µs; 250–585 µs |
| z3, frames each engine sent | 180–181 | 180–181 |
| z4, renders in 60 ticks with 8 engines | 60 | 60 |

Against invariant 12's estimates: composing is within them (11–12 µs plus 10–30 µs per 1,000 fitted points: 58–62 µs for 2,002 points); the guard is about twice its 50–100 µs per 2,000 points (Q-Z3-7); one render per cue per tick holds (z4). The render time is unchanged within the noise. z3 prints the clock's render time, the same measurement JFR's `FrameGeneration` `baseTimeUs` carries (`record-render!` sends both), so no separate JFR recording was made.

**Not done: the check on a real projector (00 R-1, §9).** Nothing here has been seen on a laser. Before calling Z3 done, at 25 and 40 kpps:
- example b (one laser, Left and Right): look for flicker, and for the halves sitting inside the safety limit;
- a 3,000-point cue with Draw fewer points on and off, then with the Master pulled down and up again (the card should stay as it was while the show is dark, S-14);
- Identify (Z1) still works.

### For the merge

Trial merges with `git merge-tree`, which writes no branch and no worktree. Each merged tree was unpacked with `git archive` into a temporary directory, and its suites were run there.

- **Into `master` at `e38576f`** (Q-Z1-5 and ZR in): no conflicts, in code or plans (`master` hasn't edited this doc).
- At `f1a52ba`, the merged unit suite had 2 failures, both fixed on the branch by `298c253`:
  - `set-fit-clamps-and-is-undoable-test` expected a fit edit to mark the show unsaved. ZR saves the projectors with the rig, and a rig edit no longer marks the show unsaved (`d55f24a`). The test now asks that a fit edit leaves the flag as a tag edit does, which holds on both sides.
  - ZR's `rig-file-test/rename-pins-by-stable-id-test` writes `:zone-group-id` into a default cell, which Z3 makes `{:target :auto/left}`. Z3's ref table re-pointed only that cell's `:target`. It now re-points both keys.
- At `298c253` (merged tree `e83a727`): unit 1,437 tests, 541,737 assertions, 0 failures; UI 194 tests, 879 assertions, 0 failures. The merged UI suite at `f1a52ba` was the same.
- Files both sides edit, all merging cleanly:
  - `events/handlers/history.clj`: Z3's `:projectors/set-fit "Change Fit Guard"` after `:projectors/set-tag`, ZR's seven `:rig/*` labels after `:rig/set-split-width`;
  - `state/domains.clj`: Z3's `:fit` in the projectors docstring, the preview's `{:target …}` cells and docstring, and `[:backend :streaming :cue-warnings]`; ZR's NEW `rig-file` defstate;
  - `views/components/rig_strip.clj`: Z3's card sections and strip height; ZR's "Forget this laser…" on the card menu;
  - `views/tabs/projectors.clj`: Z3's Fit guard section; ZR's rig file status line above the strip;
  - `test/laser_show/events/output_sync_test.clj` and `test/laser_show/events/projector_handlers_test.clj`.
- Of the files ZR was expected to share: Z3 edits `routing/rig.clj` (ZR as landed doesn't), and leaves `events/show_lock.clj` (Q-Z3-6), `state/persistent.clj`, `events/core.clj` and `events/handlers/rig.clj` alone.
- How Z3 and ZR meet, as intended:
  - `:fit` is in `[:projectors id]`, one of ZR's rig paths (`rig-file/rig-paths`). So it's saved in the rig file, and ZR's autosave writes it after a fit edit. The edit is undoable (`:projectors` is a document root) and doesn't mark the show unsaved.
  - ZR's set-up from a show (`bring-lasers`) takes a laser's settings from the show, `:fit` included. A show saved before Z3 has none, so its lasers get the defaults.
  - ZR's re-pinning (`rename-pins`, `set-up-from-show`) goes through `refs/replace-refs`, so `{:target …}` cells follow a laser too.
  - ZR's rig file status line ("Rig saved 14:02") sits above the strip whenever there is a rig file, so in the app the inspector starts one line lower again. The tests have no rig file, and it takes no room, so no UI test needed a change.

### Found on the way

- **The guard costs about twice its estimate,** 188–206 µs per 2,000 points. It wasn't profiled; `keep-mask` and `thin-with` read each point through `nth` with boxed arithmetic, the likely cost. It runs only for a frame over budget, on that laser's thread: Q-Z3-7.
- **A split laser draws less than before, on purpose.** z1's split laser drew both 1,000-point pictures whole, overlaid: 2,002 points at 12.5 fps. It now draws them fitted into its halves, and the guard thins them to 1,250 points, which reach 20 fps.
- **Z0's ref table didn't know `{:target …}` cells** (fixed in step 11 and `298c253`, above).
- **Layout to look at:** at the default 1200 × 800 the inspector starts about 60 px lower than in Z2, and one line lower again with ZR's status line. Its Fit guard section is at the bottom of the left column, so it needs a scroll, as Z1's safety editor already did.
