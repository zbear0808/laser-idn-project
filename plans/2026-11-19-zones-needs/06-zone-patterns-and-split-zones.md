# Wanted feature: zone patterns that fit any rig, and split zones

**Status:** Wanted, **not core**. This builds on C-1, C-5, C-6, C-8 and C-9 in `04-core-features.md`, and goes after them. The needs it serves are N-26 and N-27 in `02-needs.md`.
**Decided 2026-11-19 (see `07-plan.md` §1):**
- Q-f: at most 2 regions on a laser, plus a fit guard that reduces points, then brightness.
- Q-g: one laser splits Left/Right by default, into overlapping regions 75% of the safe width each.
- Q-h: the middle laser is not split by default, and splitting it is one toggle.
- Q-i: cues just see zones A–D.
- Q-j: patterns can live on cues and on effect cells; the performer switches zone maps live.
- Q-k: the step grid is its own phase.
- Q-l: the preview is an ordered strip for now (future work is in `08-rig-setup-ux-future.md`).

Where this doc's examples disagree with those decisions (for example "center split" by default, or 4 regions), `07-plan.md` wins.

## The idea

You should be able to design an animation that moves between zone groups (left ↔ right, odd ↔ even, a chase, center-out) **without knowing how many lasers will play it**. The same cue should look intentional on 1, 2, 3, 4 or 8 lasers.

Two pieces make this work:
1. **Zone patterns.** A cue or track is written against a small number of abstract **slots** (A/B, 1–4…) and a **pattern** that says which slots are lit when. It never names real groups or projectors.
2. **Split zones.** When there are fewer lasers than slots, one laser is **split into sub-zones**: its canvas is divided into regions, and each region behaves like a separate zone in the group. So a left/right alternation still reads as left/right on a single laser.

Spread (see `04-core-features.md`) is the opposite case: one image stretched across many lasers. A split zone is several images packed onto one laser.

## Why

- Designing per rig doesn't scale. Today a cue that alternates left and right needs `:left` and `:right` filled with projectors, and with one laser it shows nothing or everything.
- Small rigs are the common case: 1 or 2 lasers at most gigs. They should get the movement too, not a flat unison fallback.
- Designers want to build an animation once and trust it. That's N-12 and N-13 taken to the content level.

## How patterns fold onto the rig

Slots are mapped to **outputs ordered by stage position** (C-4/C-5). In these examples, `[A|B]` is one laser split into a left half for A and a right half for B.

### Alternate left/right (2 slots: A = left, B = right)

| Lasers | Result |
|---|---|
| 1 | `[A\|B]`: the left half and right half of the one laser alternate |
| 2 | L = A, R = B |
| 3 | L = A, R = B, and the center is split `[A\|B]`, so the movement stays continuous across the stage |
| 4 | L1, L2 = A; R1, R2 = B |
| 5 | L1, L2 = A; R1, R2 = B; center split `[A\|B]` |

### Odd/even (2 slots)

| Lasers | Result |
|---|---|
| 1 | `[A\|B]` |
| 2 | 1 = A, 2 = B |
| 3 | 1, 3 = A; 2 = B |
| N | By index parity |

### Chase over 4 slots

| Lasers | Result |
|---|---|
| 1 | One laser split into 4 regions (`[1\|2\|3\|4]`, or quadrants). This is heavy; see the point budget below |
| 2 | Each laser split into 2: `[1\|2] [3\|4]` |
| 4 | One slot per laser |
| 8 | Either repeat (1 2 3 4 1 2 3 4) or stretch (11 22 33 44). This is a per-pattern choice |

### Center-out (3 slots: outer, inner, center)

| Lasers | Result |
|---|---|
| 1 | Split into 3 vertical bands |
| 2 | Each laser `[outer\|inner]`, mirrored |
| 5 | One laser per slot position |

### Fold policies (one per pattern, with sensible defaults)

| Situation | Choices | Default |
|---|---|---|
| **Fewer** lasers than slots | **Split** (sub-zones); **Time-share** (the active slot uses the whole canvas, so motion becomes a change of content); **Merge** (unison) | Split for spatial patterns (L/R, chase, center-out); Time-share for "one slot at a time" patterns once each laser would need more than 2 regions |
| **More** lasers than slots | **Repeat**, **Stretch** (contiguous blocks), **Center split** for odd counts | Stretch for L/R, Repeat for chase |
| **Odd count with a middle laser** | Split it, give it to A, give it to B, or both get it | Split |

## Split zones

- A **sub-zone** is a region of one output's canvas: a half, a third, a quadrant, or a custom rectangle.
- Slot content is **fitted** into the region (scaled and moved), not clipped. Clipping would cut shapes in half.
- The split follows the stage: the left slot goes to the left half. On a laser that is mirrored or rotated, the split follows its geometry (corner pin, axis), so "left" stays left as the audience sees it.
- A gap between regions (5% by default) keeps the two images apart.
- Sub-zone images are joined into one frame with blanking between them, and the output's **safety zone still applies last to the whole frame** (N-8). If a sub-zone falls mostly inside a blocked region, it shrinks, or it is flagged in the preview.
- A sub-zone exists only while the pattern runs. It isn't a setup step. With C-1's "several outputs per projector", a user could also make a sub-zone permanent on purpose (a fixed graphics half, for example), but this feature doesn't need that.

### Point budget (the hard part)

Two images on one laser share one scan budget, so each image gets about half the points, and four images get about a quarter. Expect more flicker and dimmer shapes.
- At most 2 regions per laser by default, and 4 at most. Past the limit, fall back to time-share.
- Lower the point count per region so the frame stays within the scan rate, preferring the simpler content.
- The per-output load meter (C-10) shows when a split pushes a laser near its limit.
- **Q-f:** what number of regions per laser still looks good on your projectors?

## What it looks like while designing

- In the Cue Chain or Timeline editor, a **Zone Pattern** section with:
  - a pattern (Alternate, Odd/Even, Chase, Ping-pong, Center-out, Random, Custom step grid);
  - a slot count;
  - a step rate in beats, or driven by a HIT/ACCENT pulse (vibe plan Phase 4);
  - the fold policies.
- **Slot content.** Either:
  - the same content in every slot, which renders once and is moved into each place (cheap, tier 2), or
  - a different track per slot (track → slot A, track → slot B), so two different animations alternate.
- **Custom step grid.** Rows are slots and columns are steps, the way a drum machine works. Each cell is on or off, or a level from 0 to 1, so weighted chases are possible.
- **Rig simulator in the preview.** A dropdown with "Preview on: this rig / 1 / 2 / 3 / 4 / 6 / 8 lasers". This is the key part for "complete flexibility": you watch how the cue folds before you have that rig. It uses an idealised line of lasers when the count differs from the real rig.
- **Warnings.** For example, "On 1 laser this cue splits into 4 regions (heavy)". "On 3 lasers the center is split."
- **Badge.** A grid badge shows the pattern (`⇄ L/R`, `◐ odd/even`, `→ chase 4`).

## How it fits the rest

- **Targets (C-7).** A pattern plays *inside* the cue's target set (T-10). It shapes and narrows, and never widens. For example, a pattern on target "Graphics" with 1 graphics laser splits that laser, and the other lasers are left alone.
- **Distribution (C-8).** Alternate and Chase in C-8 are the simple, rig-level form: one piece of content, gains per output. Zone patterns are the authored form, with slots, different content per slot, split zones and fold rules. They should share one engine, so that a C-8 "Alternate" *is* a two-slot zone pattern with default content.
- **Modulation (C-9).** The pattern step and the slot gains are ordinary parameters, so keyframes, LFOs and pulses can drive them. A **slot index** can be a modulation source. Different content per slot means one render per slot (tier 3 per slot, not per output), so the cost grows with the slot count, not with the number of lasers.
- **Pinned targets (N-11).** A cue pinned to a group can still use a pattern inside that group. A pin to one output plus a pattern means that output is always split.

## Sketch of the data (not a decision)

```clojure
{:zone-pattern {:slots 2
                :layout :left-right          ; :odd-even :sequence :center-out :custom
                :steps [[1 0] [0 1]]         ; per step: a gain for each slot
                :rate {:value 1 :unit :beats}  ; or {:pulse :hit}
                :fold {:fewer :split :more :stretch :middle :split}
                :split {:axis :x :gap 0.05 :max-regions 2}}
 :tracks [{:slot 0 ...} {:slot 1 ...}]}      ; per-slot content, optional
```

At play time, a resolver turns `(pattern, target outputs in stage order)` into `{slot → [[output-id region] ...]}`. This is pure and easy to test, so the rig simulator is the same function called with made-up rigs.

## Risks

- **Point budget and flicker** on split lasers (above). This decides whether split zones are usable at all.
- **Legibility.** On a small laser, two halves can look like one wide shape. The gap and the fitting need tuning on real hardware.
- **Galvo travel.** Jumping between regions adds blanking time. Order the regions to keep the travel short.
- **Safety.** The fitted content has to pass the same safety stage. Split zones must never be a way to aim content somewhere the zone forbids (the fit happens before safety, never after).
- **Too much choice.** The fold policies need good defaults so most users never open them.

## Open questions

| Q | Question |
|---|---|
| Q-f | What is the most regions per laser that still looks good (2? 3? 4?) on your projectors and scan rates? |
| Q-g | With 1 laser, should Alternate L/R split by default, or time-share (show A and B full-size in turn)? |
| Q-h | With an odd count, should the middle laser split, or belong to one side? |
| Q-i | Do you want different content per slot (two different animations), or mostly the same content moving between slots? |
| Q-j | Should zone patterns live on cues/tracks, on effect cells (so a global effect can make *any* cue alternate), or both? |
| Q-k | Is the step-grid editor (drum-machine style) worth building, or are the preset patterns plus a rate enough? |
| Q-l | Should the rig simulator use an idealised line of lasers, or let you save "test rigs" (for example "club 2 sides", "festival 6 + 2 audience")? |
