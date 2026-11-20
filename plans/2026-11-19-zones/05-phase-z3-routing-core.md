# Phase Z3: routing core (routing in the snapshot, each picture once, split lasers, fit guard, per-laser preview, low-fps warnings)

**Written:** 2026-11-19   **Written for commit:** `90e35c7` (`master`; the source cited is as at `e3d6d1b`, the last commit to touch `src/`)
**Status:** Proposed.   **Needs:** Z1 (`03-phase-z1-safety.md`: the safety limit and stage) and Z2 (`04-phase-z2-rig-strip-and-groups.md`: rig order, automatic groups, the rig card). Doc 04 landed while this was being written, and the Z2 names below are taken from it. Doc 03 hadn't been written yet, so the Z1 row of the table below is an assumption: re-check it when doc 03 lands. Doc 06 (Z4) calls this phase's accessor `rig/resolve-ref`, and this doc uses that name.
**Implements spec** (`01-model-and-routing-spec.md`): §1 (frame keys in normal form), §5, §6 with §6.1 and §6.2 a–h (i and j are Z4), §7, §8, invariants 4, 5, 6, 8 and 9, and needs C-10 (a per-laser preview).   **Size:** ~6 d

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. **NEW** marks anything that doesn't exist yet.

After this phase you can:
- **Split one laser.** With one laser, a cue whose track 1 goes to Left and track 2 to Right draws them side by side, each fitted into 75% of the laser's safe area (§6.2 b). With three lasers, the middle one is shared by Left and Right (f). On **Both**, it splits only while both sides are lit with different pictures (g, h).
- **Trust overlapping groups.** A laser in All and in Graphics draws a cue sent to both of them once, not twice (e). A picture placed under two keys is drawn once.
- **Keep the frame rate.** When a laser would draw below its minimum (20 fps by default), it draws fewer points. It never goes below 60% of them, and it keeps the blanking, the ends of each line and the corners. It dims too, if you allow it.
- **See it.** Each laser's card shows what it sent (after calibration and safety) and a load meter that says what the guard did ("split · 2 regions · −30% pts"). The card turns amber below the laser's minimum, and the toolbar says **LOW FPS · Laser R 14 fps** on every tab, with a click to the card. A playing cue that is too heavy shows **⚠** on its cell, and its editor names the laser and the heaviest item. A preview cell can show one laser as it draws it.
- **Edit live.** The show clock works out the routing once per tick, and every engine and the preview read that one copy. A group edit shows on the next tick and restarts nothing.

There is still one render per cue per tick (spec invariant 8). Zones (A, B…) and maps come in Z4. Until then, frame keys are groups (`:all`, `:auto/left`, `:auto.laser/<id>`, custom ids), and Z4 adds only the zone → lane lookup (§4, `rig/resolve-ref`).

| Assumed from | What this phase uses |
|---|---|
| Z1 (doc 03, not written yet) | `[:projectors id :safety :limit]` `[x0 y0 x1 y1]`, default `[-1 -1 1 1]`. A safety stage after `apply-projector-effects` in `projector-frame` that fails closed and reports under `[:safety id]`. A test-pattern and Identify branch that replaces a laser's content before its projector effects |
| Z2 (doc 04 §4) | `[:rig]` with `:order`, `:middle` (`:split`) and `:split-width` (0.75), plus `:exclude-from-all?` on each projector. In `routing/rig.clj`: `enabled?` (`(:enabled? p true)`), `effective-order`, `rig-inputs [state]`, `auto-groups`, and `ref-entries [inputs]`, the whole `{ref entries}` map that "Z3's resolver puts under `:refs`". There's also `refs-for`, which Z2's `get-projector-zone-groups` returns: every ref that reaches a laser, with halves counted as the whole laser. `subs/target-groups`, which every picker reads (the preview's too), lists All, the automatic groups, each laser (`:auto.laser/<id>`) and the custom groups. The cards are built in NEW `views/components/rig_strip.clj` from a vector of sections, and Z2's thumbnail section returns nil for Z3 to fill. The card keeps `#projector-dot-<id>` and `#projector-status-<id>`, and the inspector stays in `views/tabs/projectors.clj` |

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| A tick | `services/show_clock.clj:71-92, 137-162` | It renders every playing cue once (`:83-85`) and publishes `{:cues [{:cell :run :zone-frames}] :projector-ctx :render-us :at-ns :playing? :positions :preview}` (`:153-157`). The snapshot has no routing |
| Points counted | `services/show_clock.clj:62-69` | One total for the JFR `FrameGeneration` event. Nothing per cue, per item or per laser |
| The clock's status | `services/show_clock.clj:108-132`; `animation/show_time.clj:52-62`; `events/handlers/timing.clj:156-162` | The beat, the frame stats and the MTC text. They go to app state at most every 250 ms, and only when app state differs |
| Who draws what | `backend/multi_engine.clj:47-88, 119-150` | Each engine, on its own thread, reads `[:projectors id :zone-groups]` from **live app state** (`:132-133`). It takes those keys of each cue, in list order, joins them (`:134-137`), then applies its projector effects (`:148-150`). A laser in two groups that both hold the same frame draws it twice: 68 points instead of 33 (Phase 8 probe v22). There are no regions |
| The join | `services/frame_service.clj:179-217` | `concatenate-frames` puts two dark points between frames |
| Effects-grid point effects | `services/frame_service.clj:397-405` | Run once per key of each cue |
| Frame keys | `routing/zone_effects.clj:127-156`; `services/frame_service.clj:239-249, 373` | One keyword per item: its own group, else its track's, its parent group's or the cue's, else `:all` |
| Projector effects | `backend/multi_engine.clj:94-111` | Read live. A failing one is skipped |
| The preview | `services/frame_service.clj:407-428`; `views/components/preview.clj:134-162, 295-335` | A cell names a zone group (`:zone-group-id`, `state/domains.clj:248-255`) and draws that exact key. There is no view of one laser |
| Its picker | `views/components/preview.clj:200-267`; `events/handlers/ui.clj:103-138` | Lists `[:zone-groups]`. Option ids use `(name id)`, so `:auto/left` and a custom `:left` would collide |
| Engine stats, status | `backend/streaming_engine.clj:51-63, 267-308, 365-384`; `backend/output_runtime.clj:172-207` | The fps a laser draws and whether its scan rate limits it, published at most 4 times a second. No point load |
| The laser's status line | `views/tabs/projectors.clj:283-294` | "Streaming · 24.9 fps (limited by scan rate)" is the only sign of too many points |
| Scan rate | `idn/stream.clj:547-578`; `backend/streaming_engine.clj:123, 170-183` | Clamped to 1,000–100,000 pts/s. It sets each frame's duration on the wire |
| Toolbar, cue cells | `views/toolbar.clj:228-297`; `views/components/grid_cell.clj:189-217, 224-317` | OUTPUT OFF and the streaming status. A cell shows LEARN, its label, the playing dot and ⚡. A cue cell has no tooltip |
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
| `concatenate-frames` (`services/frame_service.clj:179-217`) | Joins every bucket and every cue. It moves to NEW `animation/frames.clj` so `routing` can call it, and the frame service keeps the name |
| The snapshot and `publish!` (`animation/show_time.clj:306-326`) | Carry `:routing`. Engines and the preview read nothing else for routing |
| The runtime's `:status`, kept from tick to tick (`services/show_clock.clj:108-132`) | The same pattern keeps the resolver's cache, `:routing-cache` |
| `status-paths`, `:show-clock/status` (`animation/show_time.clj:52-62`) | One more path: `[:backend :streaming :cue-warnings]` |
| A frame's `{:tick :at-ns}` metadata, read at `backend/streaming_engine.clj:369` | Also carries the fit-guard record into the engine's stats |
| `engine-status`, `:output/status` (`backend/output_runtime.clj:172-219`) | Publish each laser's load, at most 4 times a second |
| `ext-clock-redraw`, `draw-preview` (`views/components/clock_view.clj:58-71`; `views/components/preview.clj:119-132`) | Draw the thumbnail and the laser cell. A NEW `:every-ms` caps thumbnails at 10 redraws a second |
| `output-held-chip` and its style (`views/toolbar.clj:228-240`; `css/layout.clj:64-71`) | The model for the LOW FPS chip |
| The fingerprint (`backend/output_runtime.clj:35-39`) | Unchanged: fit, safety and rig edits restart nothing |
| Z2's automatic groups (`routing/rig.clj`) and Z1's limit | The resolver's inputs |
| Phase 8's planned `routing/zone_remap.clj` | If Z3 lands first, it can join with `animation/frames` instead of taking a `:join` option. Its "copy to several zones" (its §9) also becomes safe, because a copy is now drawn once |

## 4. Changes per namespace

**`laser-show.animation.frames`** (NEW, pure). `blanking-jump [from to]` and `concatenate-frames [frames]` move here unchanged from `services/frame_service.clj:179-217`. `frame-service/concatenate-frames` stays as `(def concatenate-frames frames/concatenate-frames)`, so no caller or test changes. The reason: `routing` has to join frames, and the frame service already requires `routing`.

**`laser-show.routing.rig`** (from Z2; Z3 adds these, all pure):
- `normalize-target [t]` (spec §1): nil stays nil and a keyword stays itself. A collection becomes its distinct keywords sorted by `(juxt namespace name)`: one keyword if one is left, nil if none.
- `key-refs [k]`: `[k]` for a keyword, `k` itself for a vector. `zone-index [ref]`: `:zone/a` 0 … `:zone/z` 25, `:zone/aa` 26, nil for any other ref. It's used for key order now, and for lanes in Z4.
- `region-fits [limit w]` (spec §7): `{:L [cx cy s] :R [cx cy s]}` from the limit's bounding box. It returns nil when the box is under 0.05 wide or high (§12 S-9).
- `routing-inputs [state map-id]`: `{:rig :projectors :zone-groups :zone-maps :map}`, slices of the tick's app state. Their identity is the cache key (`:zone-maps` is nil until Z4).
- `routing [inputs]` → `{:map map-id :refs {ref [[pid region side] …]} :outputs {pid {…}}}`. It builds Z2's `rig-inputs` from the slices.
  - `:refs` is Z2's `ref-entries`: `:all` (every enabled laser that isn't excluded, whole), the `:auto/*` groups with their regions and sides, `:auto.laser/<id>`, and the custom groups (members in E, whole).
  - `:outputs` has an entry for each laser in E (`rig/enabled?`):
    - `:index`: its place in E;
    - `:scan-rate`: clamped as the engine clamps it (`idn/stream.clj:561-568`), 25,000 by default;
    - `:fit`: from `fit-guard/settings`;
    - `:regions`: from `region-fits`.
- `resolve-ref [routing ref]`: the only way to read a ref's entries, and the name doc 06 uses. In Z3 it's `(get (:refs routing) ref [])`. In Z4 it resolves a zone through the effective map's lanes, so nothing that calls it changes.
- `cached-routing [cache inputs]` → `[cache routing]`. While every input is `identical?` to the cached one (every tick without an edit), it returns the cached routing; otherwise it builds a new one.
- Z2's `refs-for` stays for the audience rule (`refs-reaching`), but no engine calls it any more.

**`laser-show.routing.compose`** (NEW, pure): what one laser draws (spec §6, §7).
- `key-order [k]`: a sortable tuple. Zone refs come first by index, then the other single refs by namespace and name, then vector keys.
- `contributions [zone-frames routing pid]` (steps 1–3). For each key with entries for `pid` it gives `{:keys [k] :frame f :region r :side s}`. `r` is nil (the whole laser) if any entry is whole or if the entries cover both `:L` and `:R`; otherwise it is that one region. `s` is kept only when every entry carries the same side.
- `merge-pictures [contribs]` (step 4): contributions whose frames are `identical?` become one, with their keys together and their regions merged by the same rule.
- `split-sides? [cues routing pid]` (step 5): true when some whole contribution tagged `:left` and some tagged `:right` reach `pid` anywhere in the **tick's** cues (§12 S-1). After `merge-pictures` they are different objects, so they are different pictures.
- `cue-plan [zone-frames routing pid split?]` → `{:L [f …] :whole [f …] :R [f …]}`, each in key order (steps 4–6). With `split?`, contributions tagged `:left` go to `:L` and those tagged `:right` go to `:R`.
- `plan-points [plan]`: how many points `build` will make, from counts only: every frame's count plus 2 per join.
- `fit-region [frame [cx cy s]]`: each point moves to `(cx + s·x, cy + s·y)`, colours unchanged.
- `build [plan fits]`: joins each bucket, fits `:L` and `:R`, then joins L, whole and R. A bucket whose fit is nil (a degenerate box) is dropped.
- `projector-frame [cues routing pid]` → `{:frame f :regions n}`, or nil when nothing reaches `pid`: every cue's built frame, joined in the cues' order (step 7).
- `cue-points [zone-frames routing pid]`: `plan-points` for one cue, 0 if it doesn't reach `pid`.
- `cue-warnings [cues routing]` → `{cell {:projector :points :fps :min-fps :scan-rate :item}}`: every cue whose `scan-rate / cue-points` is under some laser's `:min-fps`, at its worst laser (ties go to the lower `:index`). `:item {:id :points}` is the top-level item with the most points on that laser. It's present when the cue's frames carry `:item-points` metadata, which only edited cues do (below).

**`laser-show.animation.fit-guard`** (NEW, pure; spec §8):
- `defaults` is `{:min-fps 20 :reduce-points? true :point-floor 0.6 :dim? false :dim-floor 0.5}`. `settings [fit]` merges a laser's `:fit` over them and clamps to 5–60, 0.3–1.0 and 0.2–1.0.
- `budget [scan-rate min-fps]` is ⌊r / min-fps⌋ (S-5). `target-points [n budget settings]` is `max(budget, ⌈point-floor · n⌉)`.
- `keep-mask [frame]` marks the points thinning may never remove:
  - every blanked point;
  - the first and last point of each **lit run** (a maximal stretch of lit points);
  - dwell points (the same x, y as a neighbour, within 1e-6);
  - corners, where the directions in and out turn by more than 45° (`a·b < 0.7071·|a||b|`, against the original neighbours).

  A colour change is not protected.
- `thin [frame k]` removes up to k of the other points. Each run gets a share of k in proportion to its removable points (largest remainder, ties in frame order). Inside a run with c removable points and share q, its j-th removable point goes when `⌊(j+1)·q/c⌋ > ⌊j·q/c⌋`, which spreads the removals evenly. If there are fewer than k removable points, all of them go. The result is always a subsequence of the frame.
- `dim [frame factor]`: multiplies r, g and b by factor. Dark points stay dark.
- `guard [frame scan-rate settings]` → `{:frame f :fit record}`. Under budget it returns the same frame object, and a record with no cut. Over budget it thins if `:reduce-points?`, then, if `:dim?` and still under the minimum, dims by `max(dim-floor, guard-fps / min-fps)`. The record is `{:points-in :points :budget :min-fps :scan-rate :est-fps :guard-fps :reduced-pct :dim-pct}` for every frame, since the meter needs it.
- `smooth [prev record]` averages `:est-fps` and `:guard-fps` over about 10 frames, the way `update-stats` smooths the draw rate (`backend/streaming_engine.clj:267-308`). It keeps the rest of the newest record and sets `:low?`: on when the averaged guard fps is under the minimum, off again only at 1 fps above it (S-6). A nil record gives nil.
- **Cost** (estimated): one pass to mark and one to copy, about 50–100 µs for 2,000 points. That's extrapolated from v23 (11–12 µs to join two 1,000-point frames), plus a few multiplications per point. It runs only for frames over budget; otherwise it costs a `count` and a compare.

**`laser-show.services.frame-service`** (existing):
- `generate-frames-by-zone` (`:338-405`): the effects-grid loop (`:397-405`) becomes NEW `apply-global-effects-once [frames-by-key global-effects global-ctx]` (spec §6.1). An `IdentityHashMap` maps each input frame to its output, so keys that hold one object get one result object. There is no value hashing.
- A NEW optional 4th argument, `{:item-points? true}`, puts `{:item-points [[item-id {key n}] …]}` on the result's metadata: each top-level item's (or clip's) point counts by key, taken from `item-zone-maps` (`:379-386`), before track, group and global effects.
- Keys become targets in normal form: `resolve-item-zone-destination` (`routing/zone_effects.clj:148-152`) returns `(rig/normalize-target result)`. With today's keyword keys this changes nothing; in Z4, two vector targets written in different orders become the same key and merge by concatenation.
- `preview-data [cues routing]` (`:407-428`; the 1-arity stays, with routing nil):
  - `:zone-frames` becomes `:ref-frames {ref frame}`: for every ref named in the tick's keys, the frames of each key that names it, joined in key order and then across cues. A vector key counts for each of its refs. With keyword keys this is exactly today's `:zone-frames`.
  - NEW `:projector-frames {pid (delay …)}`: for each laser in `:outputs`, `compose/projector-frame` and then `fit-guard/guard`. A delay is forced only when a cell shows that laser, and a cell on `:auto.laser/<id>` reads it through Z2's `rig/laser-of`.

**`laser-show.services.show-clock`** (existing):
- `render` (`:71-92`): `render-cue` (`:48-60`) passes `{:item-points? true}` for the cells whose editor is open, read from the tick's state: `[:ui :dialogs :cue-chain-editor]` or `[:ui :dialogs :timeline-cue-editor]`, with `:open?`, `:col` and `:row`.
- `tick!` (`:137-162`): after the render, `(rig/cached-routing (:routing-cache runtime) (rig/routing-inputs state nil))`, kept in the runtime as `:routing-cache`. The snapshot gains `:routing`, and `:preview` becomes `(delay (frame-service/preview-data cues routing))`. If the resolver throws, the tick reports `[:routing]` and keeps the cached routing (or an empty one), so a bad edit can't stop the clock. Z4 passes the effective map id instead of nil.
- `status-step` (`:108-132`) also takes the snapshot. When the stats are due (every 250 ms), its value gains `:cue-warnings (not-empty (compose/cue-warnings cues routing))`, rounded (whole fps, points to the nearest 10). So the warnings reach app state only when one starts, ends or changes.

**`laser-show.animation.show-time`** (existing): `status-paths` (`:52-57`) gains `:cue-warnings [:backend :streaming :cue-warnings]`, and the snapshot's docstring (`:306-311`) lists `:routing`. NEW `record-output! [pid frame]` and `output-frame [pid]` → `{:frame :at-ns}` keep what each laser's provider produced last, in a `ConcurrentHashMap`, for the thumbnails. `reset-runtime!` (`:333-341`) clears them.

**`laser-show.backend.multi-engine`** (existing):
- `get-projector-zone-groups` (Z2's version returns `rig/refs-for`), `extract-frames-for-zones` and `combine-zone-frames` (`:47-88`) go.
- `projector-frame` (`:119-150`) reads no app state for routing. It runs `compose/projector-frame` on the snapshot's `:cues` and `:routing`, then `fit-guard/guard` with the laser's `:outputs` entry, then `apply-projector-effects` (`:94-111`, unchanged and still live), then Z1's safety stage. The result carries `{:tick :at-ns :fit (assoc record :regions n)}` as metadata. When the laser is showing Z1's test pattern, composition and the guard are skipped.
- `create-projector-frame-provider` (`:152-171`) smooths the record with `fit-guard/smooth` in a `volatile!` (there's one provider per engine, called only on that engine's thread), puts the smoothed record on the frame's `:fit`, and calls `show-time/record-output!` with the frame (nil after an error).

**`laser-show.backend.streaming-engine`** (existing): the loop reads `:fit` together with `:tick` and `:at-ns` (`:369`) and stores it as the stats' `:fit` (`:382-383`). `initial-stats` (`:51-63`) gets `:fit nil`, and a frame without one (nil: nothing playing) clears it. The engine doesn't look inside the record. The numbers live in the engine's stats rather than in an atom in `multi_engine` for three reasons: they start and end with the engine (`start!` resets them), the output runtime already publishes those stats, and there's nothing to clean up when a laser is turned off.

**`laser-show.backend.output-runtime`** (existing): `engine-status` (`:172-181`) adds `:low?` and `:load {:points-in :points :budget :min-fps :est-fps :guard-fps :reduced-pct :dim-pct :regions}`, rounded (whole fps and percent, points to the nearest 10). `output-errors`' docstring lists `[:routing]`. `fingerprint` is unchanged.

**State, events and subs** (existing namespaces):
- `state/domains.clj`: the projectors docstring (`:274-299`) lists `:fit`. `[:backend :streaming]` (`:428-430`) gains `:cue-warnings nil`. The preview docstring says a cell is `{:target ref}`, that a laser's ref shows what the laser draws, and that an old `{:zone-group-id x}` reads as `{:target x}`. Z2 has already made the default cells `nil :auto/left :auto/right :auto/center`.
- `events/handlers/projector.clj`: NEW `:projectors/set-fit`.
- `events/handlers/ui.clj`:
  - NEW `:ui/reveal-projector`.
  - `:preview/set-cell-zone` (`:131-138`) also takes `:target` (`:zone-group-id` still works) and writes `{:target …}`. Z4 renames it `:preview/set-cell-target` (doc 06 §4).
  - `:preview/set-grid-layout` (`:103-129`) fills new cells with `{:target …}`.
- `subs.clj`:
  - NEW `projector-load [ctx pid]` (from `projector-output-status`, `:382-386`);
  - NEW `low-fps-projectors [ctx]` (named, lowest first);
  - NEW `cue-warning [ctx col row]`;
  - `preview-cell-config` (`:477-480`) normalises old cells;
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
- `views/toolbar.clj`: NEW `low-fps-chip`, between `output-held-chip` and `connection-status` (`:283-297`).
- `views/components/grid_cell.clj`: an amber ⚠ next to ⚡ (`:206-209`) on a cue that has a warning, and a `:tooltip-fn` for cue cells (the tooltip goes on the menu label, `:210-217`).
- NEW `views/components/load_warning.clj`:
  - `cue-warning-text`, `low-fps-text` and `load-note-text`, pure functions shared by the tooltip, the chip and the card;
  - `cue-load-warning`, a line above the footers of `views/dialogs/cue_chain_editor.clj` (`:602-643`) and `views/dialogs/timeline_cue_editor.clj` (`:142-171`).
- CSS:
  - in `css/layout.clj`: `.low-fps-chip` (like `.output-held-chip`), `.rig-card-low`, `.load-meter` and `.load-meter-over`;
  - in `css/grid_cells.clj`: `.grid-cell-warning-indicator`, after `:113-115`;
  - for the editors: `.dialog-warning-line`.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:projectors id :fit]` `{:min-fps :reduce-points? :point-floor :dim? :dim-floor}` NEW | the rig: `hardware.edn` until ZR (`state/persistent.clj:53-56`) | `:projectors/set-fit` | yes / yes |
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
| `:show-clock/status` | yes (`events/handlers/timing.clj:156-162`) | the show clock. Its value gains `:cue-warnings` | yes |
| `:output/status` | yes (`events/handlers/connection.clj:28-34`) | the output runtime. Each laser gains `:load` and `:low?` | yes |

There's no new effect key. The path of one frame: `show-clock/tick!` → `rig/cached-routing` → `show-time/publish!` → the engine's `projector-frame` → `compose/projector-frame` → `fit-guard/guard` → projector effects → Z1 safety → `sanitize-frame` → `send-frame!`.

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
;;     :positions {[0 0] 6.5} :projector-ctx {…} :render-us 180.0 :preview #<Delay>}
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
| the spec's ⌈r/min⌉ (S-5) | 30,000, 23 | 2,000 | 1,305 | 1,200 | 1,305 | → 22.99 | | **yes**, wrongly |
| this plan's ⌊r/min⌋ | 30,000, 23 | 2,000 | 1,304 | 1,200 | 1,304 | → 23.01 | | no |

**Invariants**:
1. One render per cue per tick (spec 8). Routing, composition, the guard, the laser cells and the cue warnings never render: they transform frames that were already rendered.
2. The engines, the laser cells and the cue warnings compose from the snapshot alone. No composition step reads app state (spec 6). Only the projector effects and Z1's safety settings are still read live, as today.
3. A laser draws each (cue, key) at most once, and each identical frame at most once per cue (spec 4). It has at most two regions, `:L` and `:R`, plus whole content (spec 5).
4. Composition is deterministic: the same cue frames and routing give the same frame, whatever the order of the keys in the cue's map.
5. Composition keeps every point. The frame has exactly the points of the distinct contributions (fitted ones moved, no colour changed), plus 2 per join, and `plan-points` equals its count.
6. Under budget the guard returns the same object. Over budget its output is a subsequence of its input: every blanked point, every run's ends, every dwell point and every corner over 45° stay, and there are never fewer than ⌈floor·N⌉ points. Colours change only when dimming is on.
7. No zone setup means today's behaviour: every enabled laser draws every `:all` cue once (spec 2). An old show draws the same points (spec 3). It draws the same frame whenever each cue reaches a laser through one key (S-4).
8. A group, order, middle, split-width, fit or limit edit shows on the next tick and restarts no engine.
9. The resolver is pure, and its output is reused (`identical?`) while its inputs are identical (spec 9).
10. Safety stays last. Composition and the guard run before the projector effects, and Z1's stage runs after them (spec 1).
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
- `with-engines` (`test/laser_show/services/show_clock_test.clj:156-173`);
- `play-square!`, `lit-in` and `with-clock` (`test/laser_show/ui_test/preview_test.clj:43-86`), with `with-clock` moved to `ui_test/fixtures.clj`;
- `with-fake-dac` and `projector` (`test/laser_show/ui_test/streaming_status_test.clj:23-36`).

- `laser-show.routing.rig-test` (Z2's file; Z3 adds):
  - `normalize-target-test`, `zone-index-test`;
  - `routing-has-every-ref-the-rig-knows-test`: `:all` without excluded or disabled lasers; automatic groups with regions and sides for n = 1, 3 `:split` and 3 `:both`; `:auto.laser/*`; custom groups; a stray `:all` in a laser's list never overrides All;
  - `outputs-carry-scan-rate-fit-and-region-fits-test`;
  - `region-fits-follow-the-safety-limit-test`: the two boxes in §8, and a box 0.04 wide gives nil;
  - `cached-routing-is-reused-while-inputs-are-identical-test`, `cached-routing-follows-an-edit-test`;
  - `routing-of-an-invented-rig-test` (invariant 9).
- `laser-show.routing.compose-test` (NEW):
  - the §6.2 examples, one test each: `example-a-a-picture-moves-between-halves-test`, `example-b-one-laser-draws-two-fitted-halves-test`, `example-c-both-halves-of-one-laser-is-the-whole-laser-test`, `example-d-two-keys-two-pictures-overlay-test`, `example-e-one-key-through-two-groups-is-drawn-once-test`, `example-f-split-middle-laser-test`, `example-g-both-middle-splits-when-both-sides-differ-test`, `example-h-both-middle-with-one-side-stays-whole-test`;
  - the other rules: `both-middle-splits-across-two-cues-test` (S-1), `same-picture-under-two-keys-is-drawn-once-test`, `side-is-kept-only-when-every-entry-agrees-test`, `buckets-go-left-whole-right-in-key-order-test`, `key-order-test`, `fit-region-test`, `degenerate-safe-area-draws-no-halves-test`, `cues-join-in-their-order-test`, `nothing-reaches-the-laser-is-nil-test` (an unknown ref too), `plan-points-equal-the-built-frame-test`;
  - `old-show-draws-the-same-points-test` (invariant 7): with one key per cue, the result equals today's extract-and-combine frame; with two keys, the same points in another order;
  - `composition-invariants-test`, seeded: each key once; at most 2 regions; the count is the distinct frames' points plus 2 per join; the frame stays the same when the keys are reordered;
  - `cue-warnings-name-the-worst-laser-test`, `cue-warnings-name-the-heaviest-item-test`;
  - written in Z4, named here: `example-i-unison-draws-every-zone-everywhere-test`, `example-j-zones-wrap-over-two-lanes-test`.
- `laser-show.animation.fit-guard-test` (NEW): `under-budget-is-the-same-frame-test`, `budget-and-target-points-test` (the table in §8, S-5 included), `thinning-keeps-blanking-ends-dwell-and-corners-test`, `thinning-spreads-evenly-along-a-run-test`, `thinning-shares-the-cut-across-runs-test`, `floor-stops-the-thinning-test`, `dim-only-when-allowed-and-still-low-test`, `reduce-off-keeps-every-point-test`, `settings-are-clamped-test`, `smooth-averages-and-low-has-hysteresis-test`, and the seeded `guard-invariants-test` (invariant 6).
- `laser-show.services.frame-service-test` (existing):
  - NEW: `global-effects-run-once-per-distinct-frame-test` (it counts `effects/apply-effect-chain` calls and checks that both keys hold one result object), `distinct-frames-are-not-shared-test`, `frame-keys-are-normal-targets-test`, `item-points-only-when-asked-test`, `preview-ref-frames-follow-ref-membership-test`, `preview-projector-frames-are-what-the-engine-composes-test`;
  - the preview tests at `:157-185` read `:ref-frames`;
  - `test/laser_show/services/frame_concatenation_test.clj` passes unchanged, through the alias.
- `laser-show.backend.multi-engine-test` (existing):
  - `extract-frames-for-zones-test`, `combine-zone-frames-test` and `get-projector-zone-groups-test` (`:194-270`) go, since the functions they test are removed. The compose tests cover what they checked.
  - Z2's `one-laser-draws-left-and-right-whole-test` was written to pin Z2's behaviour, so it becomes `one-laser-draws-left-and-right-halves-test` (example b, through the clock).
  - Z2's `projector-draws-all-without-listing-it-test`, `excluded-projector-skips-all-test` and `reorder-reroutes-left-test` keep their assertions on routed snapshots.
  - `projector-receives-only-zone-specific-frames-test` (`:272-309`) and `engine-frame-equals-direct-render-test` (`:355-395`) keep their assertions. Their snapshots carry routing, and `direct-frame` composes.
  - NEW: `engine-routes-from-the-snapshot-not-app-state-test`, `fit-guard-runs-before-projector-effects-test`, `frame-carries-its-fit-record-test`, `provider-records-what-it-produced-test`.
- `laser-show.routing.zone-routing-integration-test` (existing): `projector-multi-zone-concatenation-test` (`:601-627`) composes instead of calling the removed helpers. Its assertions don't change. Z2's `laser-show.state.migration-test/old-show-routes-as-before-test` compares through `compose/projector-frame` instead of `extract-frames-for-zones`, and compares the same points (S-4).
- Engines and status (existing):
  - `laser-show.backend.streaming-engine-test`: NEW `stats-keep-the-frames-fit-record-test`, patterned on `stats-report-the-fps-the-projector-draws-test` (`:138`).
  - `laser-show.backend.output-lifecycle-test`: NEW `status-carries-each-lasers-load-test`, patterned on `:371`. `zone-group-edit-restarts-nothing-test` (`:289`) also edits `:fit`, the limit and the rig order.
  - `laser-show.events.output-sync-test`: `name-or-zone-change-requests-nothing-test` (`:69`) also changes `:fit`, `[:safety :limit]` and `:exclude-from-all?`.
- `laser-show.services.show-clock-test` (existing):
  - NEW: `snapshot-carries-routing-test` (next to `snapshot-shape-test`, `:190-210`), `routing-is-reused-while-nothing-changes-test`, `routing-follows-a-group-edit-next-tick-test`, `a-throwing-resolver-keeps-the-last-routing-test`, `cue-warnings-reach-app-state-test`, `edited-cue-names-its-heaviest-item-test`;
  - unchanged and still passing: `renders-once-per-tick-test` (`:175-188`, invariant 1), `one-state-and-input-read-per-tick-test` (`:341-369`), `preview-is-built-after-the-engines-wake-test` (`:250-264`), and `app-state-is-quiet-while-a-cue-plays-test` (`:288-300`), whose path list (`:269-270`) gains the new path;
  - `preview-zones-have-blanking-between-cues-test` (`:226-238`) reads `:ref-frames`.
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

  The render time is also checked against JFR `FrameGeneration` `baseTimeUs` (`services/show_clock.clj:62-69`), which should be unchanged. No new JFR event is added (§11).
- **Hardware check** (00 R-1), on a real projector at 25 and 40 kpps:
  - example b, looking for flicker and for the halves sitting inside the limit;
  - a 3,000-point cue with reduce on and off;
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
| 2 | `feat(routing): the rig resolver gives every group its lasers, halves and sides, and each laser its scan rate, fit settings and the boxes its halves fit into; it works them out again only when the rig or a group changes` | `rig-test` (above) |
| 3 | `feat(routing): what one laser draws from a tick: each key once and each picture once, a split laser's Left and Right fitted into its safe area, joined Left, whole, Right in a fixed order` | `compose-test` (examples a–h, the property test) |
| 4 | `feat(output): each tick's snapshot carries its routing and every engine composes its laser's frame from it, so a group edit shows on the next tick and restarts nothing, and no engine reads zone state` | `engine-routes-from-the-snapshot-not-app-state-test`, `one-laser-draws-left-and-right-halves-test`, `snapshot-carries-routing-test`, `routing-is-reused-while-nothing-changes-test`; then the tests that used the removed helpers move over |
| 5 | `feat(render): an effects-grid point effect runs once per distinct picture in a cue, so a picture under two keys stays one picture and is drawn once; frame keys are targets in normal form` | `global-effects-run-once-per-distinct-frame-test`, `frame-keys-are-normal-targets-test` |
| 6 | `feat(output): the fit guard: a laser that would draw below its minimum fps draws fewer points (never under its floor, keeping blanking, line ends and corners), then dims if allowed, before its calibration` | `fit-guard-test`, `fit-guard-runs-before-projector-effects-test` |
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
- **Left as they are**: virtual projectors (no engine routes them today), and `get-preview-zone-filter` and `matches-preview-zone?` (`services/frame_service.clj:61-81`), which only tests use. The default preview cells are Z2's.
- **Later**: inverting the corner pin for regions (00 R-3), fades between regions (00 §8), a large preview and a Wall of thumbnails (needs/08 R-14), protecting colour changes from thinning.

## 12. Spec questions and open questions

**Spec questions**: places where 01 is unclear or the code disagrees with it, and what this plan chose.

| # | Spec | The problem | Chosen |
|---|---|---|---|
| S-1 | §6 step 5 (auto-split) is written under "one cue's frames" | D-27 says Both splits "while both sides are lit with different content". With one cue per side, a per-cue rule would never split | The test runs over all of the tick's cues for that laser (`split-sides?`). Asked as Q-Z3-1 |
| S-2 | §5: `:routing` is `{:map :refs}` | Composing also needs each laser's scan rate, fit settings and safe box. Reading those live would let an engine and the preview disagree for a tick | `:routing :outputs {pid {:index :scan-rate :fit :regions}}`, from the same memoised resolver |
| S-3 | §5: `:refs` covers the refs in the tick's keys | That ties the routing to each tick's frames, so it couldn't be reused from tick to tick | `:refs` holds every non-zone ref the rig knows (a superset: Z2's `ref-entries`). Everything reads it through `rig/resolve-ref`, where Z4 resolves zones by the effective map |
| S-4 | §6 step 6 sorts the keys; invariant 3 says old shows route exactly as before | Today a laser joins a cue's keys in its `:zone-groups` order (`backend/multi_engine.clj:61-73`), and Z2 joins in `refs-for` order | The spec's order, as Z2's S-1 expects. The frames are identical whenever each cue reaches a laser through one key (nearly always); otherwise the same points come in another order. The fixture tests say which case they check |
| S-5 | §8.1: `N' = max(⌈r/min-fps⌉, …)` | The ceiling leaves the frame just under the minimum (30,000 pts/s, min 23: 1,305 points, 22.99 fps), so a laser the guard fixed would be flagged LOW | `⌊r/min-fps⌋` |
| S-6 | §8: P is low when `guard-fps < :min-fps` | Checked frame by frame, a cue near the limit flips the badge on and off | Averaged over about 10 frames, and cleared only at 1 fps above the minimum |
| S-7 | §8.1: points are removed "evenly along each run, in proportion to its length" | Length in points or in distance? | In removable points, so each run loses the same share, spread evenly by index |
| S-8 | §8: N is the frame's points | Z1's safety (blanking at crossings) and `sanitize-frame` (a dark first point, `idn/frame_rules.clj:19-31`) add points after the guard | Accepted: the estimate is a few points optimistic. The measured fps on the card (`backend/streaming_engine.clj:267-308`) stays the ground truth |
| S-9 | §7: fit into `min(w·W, H)/2` | A tiny or zero safe box squeezes a half into a near-static point: a burn and audience hazard | Under 0.05 wide or high, the laser draws no halves (its whole content still goes through safety), and its card says "Safe area too small to split" |
| S-10 | §8: the per-item counts "come from the tick's render" | The render counts only one total (`services/show_clock.clj:62-69`) | Top-level item counts are recorded only for the cues whose editor is open (`{:item-points? true}`). Cue-level counts come from the snapshot's frames |
| S-11 | §8.3: the stats "are not part of state" | The UI reads app state | They reach `[:backend :streaming :status]`: runtime state, never saved or undone, the same as `:fps` |
| S-12 | §11: preview cells `{:zone-group-id x}` become `{:target x}` | It doesn't say what a cell shows, and it has no laser view | A group cell shows what is sent to the group. A cell on a laser's ref (`:auto.laser/<id>`, which Z2's picker already lists) shows what that laser draws. Z3 reads `:zone-group-id` as `:target`, so no load-time step is needed before Z4 |
| S-13 | §10: the test pattern goes through safety | Composition and the guard aren't mentioned | The test pattern and Identify skip both, since they replace the content |

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
