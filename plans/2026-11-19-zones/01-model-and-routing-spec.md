# Zones: model and routing spec (the contract every phase follows)

**Written:** 2026-11-19   **Written for commit:** `f9fc352` (`master`)
**Status:** Proposed. It records D-1 to D-32 (`00-overview.md` §2). Each phase doc (`02`–`09`) implements part of this spec. If a phase finds the code disagrees with the spec, it says so in its "Spec questions" section. It doesn't quietly drift.

Paths are under `src/laser_show/` unless they start with `test/` or `plans/`. **NEW** marks anything that doesn't exist yet.

## 1. Words and ids

| Word | Meaning | Id / shape |
|---|---|---|
| **Projector** | One IDN output (host + service). From Z2 it is matched by its **stable id** (unit id + service id), not its IP (D-23) | `:projectors` key, as today |
| **Rig order** | The projectors from left to right | `[:rig :order]` `[projector-id …]` |
| **Ref** | Something a target can name. Every ref resolves to a set of `[projector-id region side]` entries (§5) | a keyword, see below |
| **Target** | Where content goes: inherit, one ref, or several refs (their union) | `nil` \| ref \| `[ref ref …]` |
| **Zone** | An abstract place a cue draws into. There is no limit on how many (D-16) | `:zone/a`, `:zone/b`, … `:zone/z`, `:zone/aa` … |
| **Zone map** | Says which group each zone means right now | `{:id :name :lanes [ref …] :overrides {zone ref}}` |
| **Region** | One part of one laser's safe area: `:L` (left), `:R` (right) or `nil` (the whole laser) | keyword or nil |

### Refs

| Ref | Resolves to | Stored? |
|---|---|---|
| `:all` | every **enabled** projector without `:exclude-from-all? true`, whole (D-20) | the id is kept for old files, but the meaning is now implicit |
| `:auto/left`, `:auto/right`, `:auto/center`, `:auto/odd`, `:auto/even` | folded from the rig order (§3) | derived, never saved |
| `:auto.laser/<name of projector-id>` | that one projector, whole | derived |
| a custom group id (`:graphics`, `:crowd`, `:left` after migration…) | the projectors that list it in `:zone-groups`, whole | as today |
| `:zone/<letters>` | that zone's lane in the effective map (§4), then the lane ref | ids only. Optional labels in `[:zones :labels]` |

**Normal form of a target.** `nil`, one keyword, or a vector of 2 or more **distinct** keywords sorted by `(juxt namespace name)`. Frame keys are targets in normal form, so two items with the same target merge (by concatenation, as today).

## 2. Inheriting a target (D-24)

This picks the first one that is set:
1. the item's `:target`
2. the track's `:target` (walking up its folders)
3. the parent group's resolved target
4. the cue's `:target`
5. the grid column's default, `[:grid :column-targets col]`
6. the show default, `[:config :default-target]`
7. `:all`

The order is today's (`routing/zone_effects.clj:142-152`, where a track beats an inherited parent group), with levels 5 and 6 added below it. The resolver returns `{:target t :from level}`, and the UI uses `:from` to say "Zone A → Left · from column 3".

The routing reads **resolved** parameter values. In Z4 the Zone Selector effect is converted into the item's `:target` (§11), so no routing decision reads a raw `:params` value.

## 3. Automatic groups (D-17, D-20, D-21)

Inputs:
- `E`: the **enabled** projectors in rig order, **without** the ones marked **`:exclude-from-all? true`**. Excluded projectors are left out of All *and* out of the folded groups below (Q-Z2-1 B, confirmed as D-33). They are still reachable through custom groups and through their own `:auto.laser/*` group, which exists for **every enabled projector**. An enabled projector that is **offline keeps its place**, so a laser that dies doesn't shift Odd/Even on the others (D-21).
- "Enabled" follows the engines' rule: a missing `:enabled?` key counts as on (`backend/multi_engine.clj:220`).
- `n = |E|`, with `pk` the k-th projector of `E`.
- `[:rig :middle]`: one of `:split` (the **default**), `:both`, `:left`, `:right`, `:none`.
- `[:rig :split-width]`: `w`, default 0.75, range 0.5–1.0.

| n | `:auto/left` | `:auto/right` | `:auto/center` | `:auto/odd` | `:auto/even` |
|---|---|---|---|---|---|
| 0 | – | – | – | – | – |
| 1 | p1·L | p1·R | p1 | p1·L | p1·R |
| 2 | p1 | p2 | p1, p2 | p1 | p2 |
| 3 | p1, **p2·L** | **p2·R**, p3 | p2 | p1, p3 | p2 |
| 4 | p1, p2 | p3, p4 | p2, p3 | p1, p3 | p2, p4 |
| 5 | p1, p2, **p3·L** | **p3·R**, p4, p5 | p3 | p1, p3, p5 | p2, p4 |
| 6 | p1–p3 | p4–p6 | p3, p4 | p1, p3, p5 | p2, p4, p6 |

The general rule:
- **Even n:** Left is the first half and Right the second half. Center is the middle two (both, when n = 2).
- **Odd n ≥ 3:** let `m = (n+1)/2`. Left is `p1…p(m−1)` plus the middle, Right is `p(m+1)…pn` plus the middle, and Center is `pm`. **How the middle joins** depends on the policy:

  | `:middle` | In Left | In Right |
  |---|---|---|
  | `:split` (**default**) | `pm·L` | `pm·R` |
  | `:both` | `pm` whole, tagged side `:left` | `pm` whole, tagged side `:right` |
  | `:left` | `pm` | – |
  | `:right` | – | `pm` |
  | `:none` | – | – |

- **Odd/Even** go by 1-based position in `E`. With n = 1 they split, as Left/Right do (D-13).
- **n = 1** always splits Left/Right and Odd/Even. Center and `:all` are the whole laser.

## 4. Zone maps (D-12, D-14, D-16, D-25)

- **Built-in maps** are derived, never saved, and can't be edited. They can be duplicated into a custom map.

  | Map id | Name | Lanes |
  |---|---|---|
  | `:map/left-right` (**default**) | Left / Right | `[:auto/left :auto/right]` |
  | `:map/odd-even` | Odd / Even | `[:auto/odd :auto/even]` |
  | `:map/unison` | Unison | `[:all]` |

- **Custom maps** are saved with the show in `[:zone-maps id]`. Their lanes can be any refs except zones (a zone never maps to a zone). Overrides send one zone somewhere else.
- **Resolving a zone.** Zone `i` (`a` is 0, `z` is 25, `aa` is 26, and so on) resolves to `(get overrides zone (nth lanes (mod i (count lanes))))`. So zones **wrap** over the lanes. Under Left / Right, A → Left, B → Right, C → Left, D → Right. Empty lanes resolve to nothing, and the map editor warns about it.
- **The active map** is `[:zone-map :active]` (default `:map/left-right`). It is **live**: it isn't undoable and doesn't mark the show unsaved, like an effect cell's `:active?` (`state/history.clj:14-22`). It is saved with the show, so the show reopens on the map last used (D-14).
- **A pending switch** is `[:zone-map :pending] {:map id :at-beat b}`, runtime only (§9).
- **The effective map** at a tick is chosen in this order:
  1. the pending map, if it is due;
  2. otherwise the map an active Map cycle effect picks (Z5), **unless the cycle is paused**;
  3. otherwise the active map.
- **A map switch while a Map cycle runs pauses the cycle on the chosen map** (`[:zone-map :cycle-paused?]`). It stays paused until the CYCLE pad is pressed or a cycle cell is switched on again (Z5 S-2, Z5-Q2 A). Without this, the cycle would win again on the very next tick.

## 5. Routing per tick (the snapshot)

- Each tick, the show clock computes **`:routing`** and puts it in the snapshot, next to `:cues` (`services/show_clock.clj:71-92`). It is reused from tick to tick while nothing it depends on changes. Its shape:

```clojure
{:map     effective-map-id                      ; nil until Z4
 :refs    {ref → [[projector-id region side] …]}  ; every non-zone ref the rig knows (Z2's ref-entries)
 :outputs {projector-id {:index :scan-rate :fit {…} :regions {:L box :R box}}}}  ; so nothing is read live (Z3 S-2)
```

- **Read it only through `rig/resolve-ref [routing ref]`.** In Z3 that is a plain lookup. In Z4 it also resolves a zone through the effective map's lanes and overrides, so nothing that calls it has to change (Z3 S-3, Z4 SQ-10).
- **Order within a tick (Z5 S-3):** step the clock → zone context → the Map cycle's pick → the effective map → the routing → render. The routing never depends on the frames, so it can come first, and the zone stage can use it (the lanes, the known refs, Every laser).
- It comes from a pure resolver (`routing/rig.clj`, **NEW**), memoised on its inputs: the rig order, the enabled set, the excluded set, `:middle`, `w`, custom group membership, the maps and the effective map id.
- **The engines and the preview read only the snapshot's `:routing`.** They never read zone state directly (today `backend/multi_engine.clj:47-59, 132-133` reads `[:projectors id :zone-groups]` live). So a tick's frames and its routing always agree, and editing a group still needs no engine restart.

## 6. What one projector draws: de-duplication and composition (D-16, D-26)

For projector P and one cue's frames `{k → f}`:

1. **Paths.** For each key k (a target), the entries for P are the union of `(:refs routing)[ref]` over every ref in k, filtered to P. If there are none, skip k.
2. **The region of k on P:**
   - if any entry is whole (region nil), the whole laser;
   - otherwise, if the entries include both `:L` and `:R`, the whole laser (both halves of one laser means all of it);
   - otherwise, that one region.

   A side tag (the `:both` middle) is kept only if every entry carries the same one.
3. **Each key once.** A key is one contribution, however many of its refs reach P. This is the "**same stream, many paths → once**" rule.
4. **Each picture once.** Contributions whose frames are `identical?` merge into one, and their regions merge as in step 2. This is "**same picture, many keys → once**". The frame service guarantees the identity: a render placed under several keys (a pattern copy, for example) is **the same object**, and the per-key global point effects run once per distinct input object, with the result shared (§6.1).
5. **Auto-split** (only for `:middle :both`). This is decided for P **across all of the tick's cues** (Z3 S-1, Q-Z3-1 A). If any of P's contributions is tagged `:left` and another is tagged `:right`, **and their frames differ**, the `:left` contributions use `:L` and the `:right` ones use `:R`. Otherwise they stay whole (D-27).
6. **Composition.**
   - Contributions go into buckets in the order **`:L`, whole, `:R`**.
   - Inside a bucket, keys are sorted: zone refs by index, then the other single refs by `(juxt namespace name)`, then vector keys.
   - Each bucket's frames are concatenated. The `:L` and `:R` buckets are fitted into their regions (§7).
   - The buckets are joined with blanking (`frame-service/concatenate-frames`, `services/frame_service.clj:195-217`).
7. **Cues** are joined in intent order, as today (`backend/multi_engine.clj:135-137`).

A projector therefore has at most **2 regions** (`:L` and `:R`) plus whole content (D-1). Different pictures are **all drawn**: they overlay inside their bucket, and the fit guard (§8) keeps the frame rate up.

### 6.1 Sharing in the frame service

- Today the global point effects run once per zone key (`services/frame_service.clj:399-405`).
- From Z3 they run once per **distinct input frame object** within a cue's render, and every key that holds that object gets the same output object.
- Nothing is shared when the frames are not `identical?`. There is no value hashing.
- **Pattern copies (Z5 S-1).** When a zone pattern lights one picture in several zones, it is stored as **one frame under the union of those zones** (a vector key in normal form), not as copies under several keys. Step 3 then draws it once on every laser, and that never depends on object identity, which a later join would break. Identity sharing stays as a second line of defence.

### 6.2 Worked examples

| # | Rig and setup | Cue | P draws |
|---|---|---|---|
| a | 1 laser, map Left / Right | the All picture, alternating A ↔ B (Z5) | the picture in `:L`, then in `:R`, then `:L`… |
| b | 1 laser, Left / Right | track 1 → A (circle), track 2 → B (wave), both lit | circle fitted in `:L`, wave fitted in `:R`: two regions |
| c | 1 laser, Left / Right | one item → `[:zone/a :zone/b]` | the item once, whole (both halves of one laser) |
| d | P is in All and in Graphics | item 1 → `:all`, item 2 → `:graphics` | both, overlaid (different pictures) |
| e | P is in All and in Graphics | one item → `[:all :graphics]` | once (one key) |
| f | 3 lasers, `:middle :split` | track 1 → A, track 2 → B | p1: A whole. p2: A in `:L`, B in `:R`. p3: B whole |
| g | 3 lasers, `:middle :both` | as f | p2: A in `:L`, B in `:R` (they differ, so it auto-splits) |
| h | 3 lasers, `:middle :both` | only track 1 → A | p2: A whole |
| i | any rig, map Unison | track 1 → A, track 2 → B | every laser: A and B overlaid, whole (as an old show on `:all` does today) |
| j | 2 lasers, Left / Right | 4 zones A–D, all lit | p1: A + C overlaid. p2: B + D overlaid (wrap) |

## 7. Fitting into a region (D-2)

- **The safe box** `[sx0 sy0 sx1 sy1]` is the bounding box of the projector's scan limit (§10). Its width is `W = sx1 − sx0` and its height `H = sy1 − sy0`.
- **Region bounds in x:** `:L` is `[sx0, sx0 + w·W]` and `:R` is `[sx1 − w·W, sx1]`. With w = 0.75 the middle 50% of the safe width is shared.
- **Fit:**
  - scale `s = min(w·W, H) / 2`, the same in both directions so shapes keep their aspect;
  - centre `cx` = the middle of the region, `cy = (sy0 + sy1) / 2`;
  - each point moves to `(cx + s·x, cy + s·y)`, and its colour is unchanged.
- The fit runs **before** the projector effects, in content space. v1 treats the scan limit as if it were in content space; that is exact when the corner pin is identity. Safety still clips the final frame (00 R-3).

## 8. Fit guard and the low-fps warnings (D-1, D-10, D-18)

Settings per projector, `[:projectors id :fit]`:

| Setting | Default | Range / meaning |
|---|---|---|
| `:min-fps` | **20** | 5–60 |
| `:reduce-points?` | **true** | |
| `:point-floor` | 0.6 | 0.3–1.0 |
| `:dim?` | **false** | |
| `:dim-floor` | 0.5 | 0.2–1.0 |

It runs **after composition and before the projector effects**. With `N` the frame's points (including blanking), `r` the scan rate, and fps estimated as `r / N`:

1. If `r/N < :min-fps` and `:reduce-points?`, reduce the points to `N' = max(⌊r / min-fps⌋, ⌈point-floor · N⌉)`. This uses the floor, not the ceiling, so a thinned frame reaches the minimum exactly (Z3 S-5).
   - Only lit runs are thinned. Blanking points and dwell points, and each run's first and last points, are kept. Corners (a turn of more than 45°) are kept.
   - Points are removed evenly along each run, in proportion to the number of removable points the run has (Z3 S-7).
2. If it is still `r/N' < :min-fps` and `:dim?`, scale the colours by `max(dim-floor, (r/N') / min-fps)`.
3. Record `{:est-fps :guard-fps :reduced-pct :dim-pct}` for P. It goes into the runtime streaming status in app state, beside the fps that is already there. It is never saved and never undone (Z3 S-11).

**Warnings:**
- **Low-fps highlight.** P is *low* when its guard fps, averaged over about 10 frames, is below `:min-fps`. It is cleared only once it is 1 fps above the minimum, so it doesn't flicker (Z3 S-6). Its rig card turns amber, and the toolbar gets an amber badge, "LOW FPS · Laser R 18 fps", that links to the card.
- **Cue warnings.** For each playing cue c and each projector P it reaches, the estimate is `r / points_c(P)`. If that is below P's `:min-fps`, c's grid cell shows ⚠, and its editor names P and the item that adds the most points. The counts come from the tick's render (`services/show_clock.clj:62-69` already counts points). There is no extra render.

## 9. Timing of a switch (D-11, D-19)

This applies to a zone map switch and to a pattern-pad switch (Z5). It doesn't apply to edits in an editor.

- Let `bpm` be the global tempo and `t` the global beats at the press.
- The window is `win = 0.050 s × bpm / 60` beats, and `fr = t − ⌊t⌋`.

| Case | Applies at |
|---|---|
| `fr ≤ win` (just after a beat) | `⌊t⌋`, which is **now**. The switch counts from that beat |
| `1 − fr ≤ win` (just before a beat) | `⌈t⌉`, a wait of 50 ms or less |
| anything else | `⌈t⌉` (the next beat) |
| **Bar mode** (Shift held, or the pad's setting) | the same rule over bars (beats per bar from the clock, 4 by default) |
| no clock running, or no tempo | **at once** |

- A pending switch shows on its pad, which blinks.
- A new press replaces the pending one. Pressing the active map clears it.
- The show clock reads `[:zone-map :pending]` and treats it as effective once `beats ≥ :at-beat`. The UI then commits it to `:active` (an event the clock sends with its status), so the clock never writes app state itself.

## 10. Safety (D-28, D-29)

- **Data:** `[:projectors id :safety] {:limit [x0 y0 x1 y1] :blocked [[x0 y0 x1 y1] …]}`.
  - Output coordinates are −1 to 1, **after** the projector effects.
  - The default is `{:limit [-1 -1 1 1] :blocked []}`, which changes nothing.
  - There is no off switch.
- **The stage** runs **last**, after the projector effects (`backend/multi_engine.clj:94-111, 148-150`) and before the encoder:
  - a lit segment that crosses the limit or a blocked rectangle gets a point at the crossing, and the part outside is dark;
  - points outside the limit are moved onto it and made dark;
  - points inside a blocked rectangle are made dark;
  - each lit → dark change gets the blanking points the frame rules need.
- **Fail closed.** If the stage throws, P sends an empty frame, the error is reported under `[:safety id]`, and the card shows "Safety error: output off".
- **Editing a live laser (D-28).** A change that **shrinks** the limit or **adds or grows** a blocked rectangle applies at once. A change that **grows** the limit or **shrinks or removes** a blocked rectangle, while P is outputting, asks "Allow light in a larger area?" first.
- **The test pattern and Identify** go through the same stage.

## 11. Migration on load (D-22, silent)

Old data is converted on load in `state/persistent.clj` (`apply-loaded-project`, `:179-191` at `70f7db8`, beside `model/normalize-timeline-cues`). The conversion is idempotent and silent, and it routes exactly as before.

| Old data | New data |
|---|---|
| a projector whose `:zone-groups` lacks `:all` | `:exclude-from-all? true`, and `:all` is removed from every list |
| the built-in `:left`, `:right` or `:center` with **no** members | removed. References to them become `:auto/left`, `:auto/right`, `:auto/center` |
| the same with members | kept as custom groups with the same id, named "Left (manual)" and so on. References are unchanged |
| `:graphics`, `:crowd` and custom groups | unchanged |
| an item's or track's `:zone-group-id` | `:target` |
| the cue's `[:destination-zone :zone-group-id]` | the cue's `:target` (`:all` becomes `nil`, which inherits down to `:all`) |
| an enabled Zone Selector with `:target-zone` | the item's `:target` if the item has none, and the effect is removed |
| preview cells `{:zone-group-id x}` | `{:target x}` |
| Phase 8 zone params (`:zones`, `:from`, `:zone-a`, `:zone-b`) | accept any ref. Old ids keep working |
| no `[:rig :order]` | the order comes from IP (as numbers), then name |

## 12. Where state lives

"Rig" means `hardware.edn` until ZR, and the rig file after it (`09-phase-zr-rig-file.md`).

| Path | Phase | Where | Undo? | Unsaved? |
|---|---|---|---|---|
| `[:projectors id :safety]`, `:fit`, `:exclude-from-all?`, `:stable-id` | Z1–Z3 | rig | yes | until ZR: yes. After ZR: the rig file is saved automatically |
| `[:rig :order]`, `[:rig :middle]`, `[:rig :split-width]` | Z2 | rig | yes | as above |
| custom group membership (a projector's `:zone-groups`) | as today | rig | yes | as above |
| `[:zone-groups id]` definitions | as today | show (the rig keeps a copy after ZR) | yes | yes |
| `[:zone-maps id]`, `[:zones :labels]`, `[:grid :column-targets]`, `[:config :default-target]`, `:target` on items, tracks and cues | Z4 | show | yes | yes |
| `[:zone-map :active]` | Z4 | show, in its own `zone-map` domain, which is **not** a document root (Z4 §5) | **no** | **no** (live, saved with the show) |
| `[:zone-map :pending]` | Z4 | runtime | no | no |
| `[:zone-map :cycle-paused?]` | Z5 | runtime | no | no |
| snapshot `:routing` | Z3 | runtime, per tick | – | – |
| the fit-guard stats, cue warnings | Z3 | runtime streaming status in app state | no | no |

## 13. Invariants (every phase keeps them)

1. **Safety is last.** Only `sanitize-frame` and the encoder run after it. `sanitize-frame` only drops bad points, adds a dark first point and doubles a lone point, so it can't light anything (Z1 S-Z1-5). Safety fails closed. No effect, map, pattern or override can skip it.
2. **No zone setup means everything plays everywhere.** On the default rig with no targets, every enabled projector draws every cue, once. That's today's default.
3. **Old shows look the same.** A project with no zones, maps or patterns routes and renders exactly as before, after migration. A fixture test checks each case in §11. **The one deliberate exception:** a reference to an *empty* built-in Left, Right or Center used to reach nothing, and now reaches the automatic group of that name (Z2 S-2).
4. **Each key once, each picture once.** A projector draws each (cue, key) at most once, and each identical frame at most once per cue.
5. **At most two regions** (`:L`, `:R`) per projector, plus whole content.
6. **Routing comes from the snapshot,** so the engines and the preview agree on every tick.
7. **Automatic groups are derived.** They are never saved and always match the rig.
8. **One render per cue per tick.** There is no render per projector. Fitting, guarding and safety are per-projector transforms of frames already rendered.
9. **The resolver is pure.** The same inputs give the same output. The preview simulator (later) is this function with an invented rig.
10. **Unison is every zone → All.**
