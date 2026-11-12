# Timeline visual design

**Written:** 2026-11-08   **Written for commit:** `d0a7dc2`
**Status:** Mostly done (Phases 1 and 3; the exceptions are listed in `03` status notes)   **Last verified against:** `ad19072` (the `img/before-*` screenshots are from `d0a7dc2`; new ones come from `timeline-screenshot-baseline`)
**Related findings:** V-1…V-15 (below); 01 F15, F22, Q10, L1; `CSS_COLOR_SYSTEM.md`
**Implemented by:** Phase 1 quick wins (`01` §4.3) and Phase 3 (`03` §4.2-§4.7)

## 1. How the screenshots were taken

A throwaway TestFX test (kept outside the repo) used `app/start-test!` (`app.clj:199`). It opened the timeline editor for cell C3 and resized the stage to 1400×820. It then added 3 tracks and 7 clips via `:timeline/add-preset-to-track`, opened the add panel, enabled the loop and triggered the cell. Each step was snapshotted with `Node.snapshot`, the same call as `ui/screenshot!` (`testfx.clj:370`). The command was:

```powershell
clj -Sdeps '{:aliases {:shot {:extra-paths ["<tmp>/tlshot"]}}}' -M:test-ui:win:shot -d <tmp>/tlshot -n laser-show.ui-test.timeline-shot-test
```

`app/start-test!` doesn't start the preview timer, so playback position stays 0 in these shots. Phase 3 §8 turns this into an opt-in `^:visual` test (`timeline-screenshot-baseline`).

| File | Shows |
|---|---|
| `img/before-01-empty.png` | Fresh timeline cue: one folder, one track, nothing selected |
| `img/before-02-content.png` | 3 tracks, 7 clips (incl. an overlap), the last-added clip selected |
| `img/before-03-add-panel.png` | Same with "Add Content" open (presets, effects, clip properties) |

## 2. What's wrong visually (with evidence)

| ID | Problem | Evidence | Cause (file:line) |
|---|---|---|---|
| V-1 | **Track headers don't line up with lanes** | `before-02`: header row centres y≈138/169/202/235, lane centres 132/164/196/228; the folder header is taller than its lane | `03` §1 / TL-16 |
| V-2 | **Every clip is the same grey** | `before-02`: all seven clips are #808080-ish on dark grey; nothing tells tracks or content apart | Clip colour = zone colour at 0.6 alpha (`timeline_editor.clj:65-79`), and the default zone `:all` is `#808080` (`domains.clj:325`). Selection is just `.brighter` (`:282-284`) |
| V-3 | **Adjacent and overlapping clips merge** | `before-02`: "circle" (0–4) and "star" (2–6) read as one bar; there's no edge between touching clips | No inset, border or gap (`timeline_editor.clj:277-288`); overlap isn't drawn (TL-11) |
| V-4 | **Selection colours compete** | `before-02`: the same gold outlines the selected lane (full-width lines) and the selected clip; the saturated blue sidebar row is the loudest thing on screen | `selection-border-color` used for both (`timeline_editor.clj:61,264-268,298-302`); list `selection-bg` |
| V-5 | **Cursor looks like the playhead** | `before-02`: the red line at beat 10 is the edit cursor (stopped); during playback the cursor isn't shown | `timeline_editor.clj:325` (TL-26) |
| V-6 | **Ruler is cramped and ambiguous** | 24 px tall, 10 px labels, beats numbered from 0 with no bars; the loop brace (teal) draws over the numbers 0–4 | `timeline_editor.clj:38,159-215`; `(Font. "Inter" 10)` (`:192`), likely not installed, so JavaFX falls back |
| V-7 | **Folder lane is an empty row** | `before-02`: a full-height dark row above the tracks with nothing in it | `build-tracks` gives folders no clips (`timeline_editor.clj:97-104`) |
| V-8 | **Dead space and a detached scrollbar** | `before-02`: lanes end at y≈264; ~450 px of black below; the horizontal scrollbar sits at y≈725, far from the content | Canvas height = track count (`timeline_editor.clj:141-144`) inside a fit-to-height ScrollPane (`:1157`) |
| V-9 | **Labels are IDs, not names** | Clips say "beam-fan" while the inspector says "Beam Fan"; long names overflow into the next clip | `timeline_editor.clj:292-297` uses `(name preset-id)`; text isn't clipped |
| V-10 | **Header rows are noisy** | "[All]" on every row; enable checkboxes look like selection checkboxes; the tiny orange ⊗ at x≈398 reads as a stray pixel; the sidebar is ~420 px wide for short names | `track-label-renderer` (`timeline_editor.clj:691-707`); `list.clj:410-413,434-437,483-486`; split `:divider-positions [0.2]` (`:1119`) |
| V-11 | **Empty state gives no guidance** | `before-01`: a lane is highlighted with gold lines though nothing was selected; no hint what to do | TL-21; no empty-state drawing |
| V-12 | **Toolbar has no transport and weak toggles** | "Loop" is a plain toggle whose on-state is barely darker; the zoom slider has no value, no −/+, no fit; "Add Content" toggles a panel rather than adding | `timeline_editor.clj:618-689` |
| V-13 | **Add panel wastes space** | `before-03`: ~200 px tall for five preset buttons; "ADD CONTENT & PROPERTIES" mixes adding with inspecting; 10 px inspector labels | `timeline_editor.clj:955-1035` |
| V-14 | **Chrome duplicates information** | A 52 px header that only says "Cell C3" (already in the window title); footer label "Default Zone (Chain Fallback):"; dialog pref size 900×500 is too small for a timeline | `timeline_cue_editor.clj:35,122-123,126-141,235-238` |
| V-15 | **Blurry lines and per-draw allocation** | Grid lines look smudged: 0.3/0.5 px strokes at fractional x | `timeline_editor.clj:208,240`; `Color/web` per grid line (`:238`) and per track (`:65-79`); `Font.` per clip (`:290`) (01 F15) |

## 3. Visual spec

### 3.1 Tokens (`laser-show.css.canvas`, NEW in `03` §4.5)
They're derived from `css/theme.clj` (`base-colors` `:32-57`, `semantic-colors` `:59-92`). NEW values are marked; everything else reuses an existing theme value.

| Token | Value | Use |
|---|---|---|
| `:tl/bg` | `:bg-base` `#121212` | canvas background below the last lane |
| `:tl/lane` | NEW `#181818` | track lane |
| `:tl/lane-alt` | NEW `#1C1C1C` | every other lane |
| `:tl/lane-folder` | NEW `#222222` | folder lane |
| `:tl/lane-selected` | `:blue` `#4992db` @ 0.10 | selected track lane |
| `:tl/grid-bar` | NEW `#3A3A3A`, 1 px | bar lines |
| `:tl/grid-beat` | NEW `#2A2A2A`, 1 px | beat lines |
| `:tl/grid-sub` | NEW `#222222`, 1 px | subdivision lines (only when ≥ 12 px apart) |
| `:tl/ruler-bg` | NEW `#161616` | ruler |
| `:tl/ruler-text` | `:text-secondary` `#B0B0B0` | bar numbers |
| `:tl/ruler-text-dim` | `:text-muted` `#808080` | beat numbers |
| `:tl/playhead` | `:red` `#fd3636` | playhead line + head |
| `:tl/cursor` | `:orange` `#FF9800` | edit cursor (dashed) |
| `:tl/loop` | NEW `#2EAD8F` (teal) | loop band; 0.9 alpha on, 0.35 off |
| `:tl/loop-tint` | `#2EAD8F` @ 0.05 | lanes inside an enabled loop |
| `:tl/past-end` | `#000000` @ 0.35 | region after the timeline end (Phase 4) |
| `:tl/selection` | `:text-primary` `#FFFFFF` | selected clip outline (2 px) |
| `:tl/hover` | `#FFFFFF` @ 0.4 | hovered clip outline (1 px) |
| `:tl/snap-guide` | `:orange` @ 0.5 | guide line while dragging |
| `:tl/marquee` | `:blue` @ 0.15 fill, `:blue` 1 px stroke | marquee selection |
| `:tl/font-ruler` | `Font/font "System" FontWeight/BOLD 11` | bar numbers |
| `:tl/font-clip` | `Font/font "System" FontWeight/BOLD 11` | clip names |
| `:tl/font-small` | `Font/font "System" 10` | beat numbers, clip badges |

Rules:
- Every stroke is pixel-snapped: draw 1 px lines at `(Math/floor x) + 0.5`.
- No `Color/web` or `Font.` calls inside draw functions.

### 3.2 Track colours (clip colour = track colour, D8)
NEW `track-palette`, assigned round-robin by `create-track` (`01` §4.1) and changeable from the header context menu:

`#4A90D9` blue · `#E67E22` orange · `#9B59B6` purple · `#2EAD8F` teal · `#D9B84A` gold · `#D9577A` rose · `#6C8CD5` periwinkle · `#7CB342` green

Three of these (`#4A90D9`, `#E67E22`, `#9B59B6`) are already zone colours in `domains.clj:330-350`, so the look stays consistent with the rest of the app. Existing tracks without `:color` get a palette colour by their index at render time; this isn't saved until edited.

### 3.3 Ruler (36 px)
```
 ┌──────────────────────────────────────────────────────────────────────────┐
 │ 1           2           3           4           5                        │ ← bar numbers (bold 11, left of bar line +3 px)
 │ ·  2  3  4  ·  2  3  4  ·  2  3  4  ·  2  3  4  ·                        │ ← beat numbers (10, dim), only when ≥ 24 px/beat
 │▼(playhead)          ▿(cursor)                                            │
 │▕██████████████████████▏ loop band (6 px, bottom of ruler)                │
 └──────────────────────────────────────────────────────────────────────────┘
```
- Labels are **bars** (1-based, 4/4) with beats underneath, never raw beat numbers.
- **Time mode** (Phase 4, `Bars | Time` toggle): the top tier shows `mm:ss` (or `hh:mm:ss`) from `:timeline/tc-start`; the bottom tier shows sub-second ticks, with frame ticks at ≥ 400 px/s. Grid lines follow the time ticks. Without a locked tempo, the time labels are italic (approximate).
- Tick levels come from `layout/ruler-ticks` (`03` §4.1):

| Zoom | Labels and ticks |
|---|---|
| < 6 px/beat | every 4 bars |
| 6–24 px/beat | every bar, beat ticks |
| ≥ 24 px/beat | beat labels |
| ≥ 96 px/beat | 1/4 ticks |

- The loop band sits in its own 6 px strip at the bottom of the ruler, with 6 px end grips. It's the only loop hit area (TL-12). When the loop is enabled, lanes inside it get `:tl/loop-tint`.
- Playhead head: a 10 px downward triangle in the ruler, then a 2 px line through the lanes. Cursor: a 1 px dashed line, with a small hollow triangle in the ruler.
- End marker (Phase 4): a 1 px white @ 0.4 line, with `:tl/past-end` over everything to its right.

### 3.4 Lanes and clips
Metrics (also `layout/metrics`, `03` §4.1): track lane 36 px, folder lane 26 px, 1 px separator (`:tl/bg`) between rows, header column 220 px.

**Clip:**
```
 ┌─────────────────────────────┐   inset 3 px top/bottom, 1 px left/right → touching clips show a gap
 │▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀│   2 px top band: track colour, full alpha
 │ Beam Fan                fx 2│   body: track colour @ 0.75; name bold 11, clipped with "…"
 └─────────────────────────────┘   square corners (no radius); badge "fx N" when the clip has effects (small 10)
```

| State | Look |
|---|---|
| Normal | as above |
| Hover | 1 px `:tl/hover` outline; 6 px trim zones at both ends shown as darker bands with two 1 px grip lines |
| Selected | 2 px `:tl/selection` outline, body +10 % lightness, drawn on top |
| Dragging | original position as a 1 px dashed outline; preview at full opacity; `:tl/snap-guide` vertical line at the snapped edge; tooltip `3.2 → 4.1 · 2 beats` near the pointer |
| Overlap | the overlapping span gets a 45° hatch (white @ 0.15, 6 px pitch) on the topmost clip; both clips' edges stay visible because of the inset |
| Muted track / disabled clip | body @ 0.30, desaturated; name in `:text-muted` |
| Very narrow (< 24 px) | no label; the full name shows in a tooltip on hover |

Label text colour: `#FFFFFF` if the body colour's relative luminance is < 0.5, else `#121212`, so contrast stays ≥ 4.5:1.

**Folder lane** (expanded or collapsed): summary bars, 8 px tall and vertically centred, in each child track's colour @ 0.45, for every descendant clip. No labels. Clicking a summary bar selects that clip.

**Selected track lane:** `:tl/lane-selected` background, nothing else (no gold lines). The **insert target** gets a 3 px left-edge marker in the track colour (TL-21).

**Empty states:**
- No tracks: centred text in the lane area, "Add a track to get started", with a `+ Track` button.
- Tracks but no clips: ghost text in the first lane, "Pick a preset below to add it at the cursor (bar 1)".

### 3.5 Header column (220 px)
```
 ┌ TRACKS ──────────────────── + ┐  ← 36 px corner over the ruler: section label + add-track button
 │▌▾ FRONT                 [Front] M│  folder row (26 px): bold 11 caps, tinted #222, chevron, zone chip
 │▌   Front 1              (Front) M│  track row (36 px): colour strip 4 px, indent 14 px/level
 │▌   Strobes                [All] M│  explicit zone = filled chip; inherited = outline chip "(Front)"
 │▌ Track 3                   [All] M│
 └───────────────────────────────────┘
```
- The name is 12 px `#E0E0E0`. Double-click renames in place.
- The zone chip is a pill (radius 9, 10 px text): zone colour @ 0.25 background with zone-colour text. Inherited zones use an outline, italic text and parentheses. Clicking the chip opens the zone menu.
- **M** (mute): 18×18. Off = `:interactive` `#3D3D3D` outline; on = `:orange` fill with dark text, and the row's name dims. This replaces the enable checkbox (V-10).
- Selected row: `:blue` @ 0.18 background + 2 px `:blue` left accent (instead of a full saturated blue fill). Hover: `#222222`.
- No "⊗" button. Ungroup / delete live in the context menu.
- Rows are exactly `layout/rows` heights with spacing 0 (V-1).

### 3.6 Playhead vs cursor
- **Playhead** (red, solid, 2 px): drawn only while the cue is active. In Phase 4 it's the output position.
- **Cursor** (orange, dashed, 1 px): always drawn. It's where Play starts and where content is inserted. Clicking the ruler moves it; while playing, a click also seeks (Phase 4).

### 3.7 Toolbar (one 40 px row; the dialog's name header merges into it)
```
 [Cell C3 ✎] │ ⏮  ▶  ■   3.2.1 │                  │ +Track ▾ │ 🧲 1/4 ▾  🔁  ⇥ │ − ━━●━━ +  ⤢ │ ☰
   name        transport  bar.beat.sub (mono 13)    add menu   snap  loop follow   zoom   fit  inspector
```
- Icons via `fa/icon`, as already used in `list.clj:403`: `:backward-step`, `:play`/`:pause`, `:stop`, `:magnet`, `:repeat`, `:arrows-left-right-to-line`, `:magnifying-glass-minus`/`-plus`, `:expand`, `:sliders`.
- Toggle buttons (snap, loop, follow, inspector): off = transparent background with `:text-muted` icon; on = `:blue` @ 0.2 background with `:blue` icon and a tooltip saying the state (V-12).
- The snap value combo shows musical names: "1 bar", "1/2", "1/4", "1/8", "1/16", "1/32".
- "+Track ▾" is a split button: click adds a track; the arrow offers "Folder".
- Transport is disabled (greyed) until Phase 4; the readout shows the cursor until then.
- **Timecode (Phase 4/5):**
  - The readout shows both `3.2.1 · 00:00:05:12` (mono 13; the timecode in `:text-secondary`, prefixed `≈` and dimmed when the tempo isn't locked).
  - Next to it: tempo `128.0 BPM` with a lock icon (`:lock`/`:lock-open`), and a `Bars | Time` segmented toggle for the ruler.
  - When the timeline follows MTC, a status chip `MTC 01:00:05:12` appears: green `#4CAF50` text on 0.15 background when running, `:orange` when freewheeling, `:text-muted` when stopped. The transport buttons grey out with the tooltip "Following MIDI timecode".

> **Layout changed 2026-11-11 (user request, Ableton-style):** §3.8, §3.9 and the §3.11 diagram describe the earlier layout. Now:
> - **Bottom: detail panel** (toggled by the sliders button; default 300 px, drag the top strip to resize between 160 and 600 px). On the left is a 300 px column with the §3.8 properties. To its right is a **device chain**: the selected clip's, track's or folder's effects as 320 px cards, each with an on/off switch, move left/right, remove, and the shared `effect-parameter-editor`.
> - **Right: browser** (toggled by the layers button; 280 px). Presets add a clip at the cursor on the insert track, and effects add to the selected clip, track or folder. A hint line under each heading says what a click will do. The Zone effect tab is only offered for clips.
> - **Tracks and folders can have effects** (`:effects` on the track). They apply to every clip on the track, or in the folder, at timeline time. NEW ops `:set-effect-enabled` and `:move-effect`; `:add-effect` and `:remove-effect` take `:clip-id` or `:track-id`.

### 3.8 Inspector (right side, 280 px, collapsible)
It replaces the "properties" third of the bottom panel. Section headers are 11 px bold caps `:text-secondary`, and labels are 11 px (not 10).

| Selection | Sections |
|---|---|
| Clip | **Clip**: name, preset; start / length (text fields in beats; commit → `{:op :move-clips}` / `{:op :trim-end}`); track combo; zone combo (Inherit from Track (X) / zones). **Effects**: list of attached effects with remove ×, and a "+ Add effect" button that opens the effect bank in a popover |
| Track / folder | name, colour swatches (palette), zone (Inherit from Folder (X) / zones), mute |
| Several clips | "N clips selected", shared track/zone combos, Delete |
| Nothing | **Timeline**: length (auto/explicit; the timeline stops at its end unless the loop is on), loop start/length, tempo (locked / follow global), timecode start + frame rate, follow timecode (Off / MTC), default zone |

### 3.9 Content browser (bottom drawer, 132 px, toggled from the toolbar)
- Presets only, as the existing `preset-bank` (`timeline_editor.clj:991-1001`) with its tabs. Effects move to the inspector (§3.8).
- A hint line on the right says what a click will do: "Adds at bar 3.2 on Front 1". It's computed from the cursor and the insert target.
- Clicking adds the preset (`:timeline/add-preset-to-track`, unchanged). Drag-to-lane is out of scope (`03` §9).

### 3.10 Dialog chrome
- Remove the separate 52 px header (`timeline_cue_editor.clj:126-141`); the name moves into the toolbar. The window title already says "Timeline Editor - Cell C3" (`:235-238`).
- Footer (unchanged controls, clearer text): "Default zone:" [combo] · "Trigger:" [Toggle/Retrigger] · spacer · [Open in Cue Chain Editor] (secondary) · [Close] (primary).
- Default stage size 1280×760, minimum 960×560 (today `:pref-width 900 :pref-height 500`, `timeline_cue_editor.clj:122-123`).

### 3.11 Full layout
```
┌──────────────────────────────────────────────────────────────────────────────────────────────┐
│ [Cell C3 ✎] │ ⏮ ▶ ■  1.1.1 │                 │ +Track ▾ │ 🧲 1/4 ▾  🔁  ⇥ │ − ━●━ + ⤢ │ ☰     │ toolbar 40
├───────────────────────┬──────────────────────────────────────────────────┬─┬────────────────┤
│ TRACKS             +  │ 1        2        3        4        5        6   │ │ CLIP           │ ruler 36
│                       │▼            ▿                                    │ │ Beam Fan       │
│                       │▕███████████████▏                                 │ │ Start  6.0     │
├───────────────────────┼──────────────────────────────────────────────────┤ │ Length 4.0     │
│▌▾ ALL           [All] │  ▬▬▬▬  ▬▬▬▬▬▬▬▬    ▬▬▬▬▬▬▬▬▬▬▬▬                   │▲│ Track [All 1▾] │ folder 26
│▌  All 1         (All)M│ ┌Circle─┐┌Star──┐  ┌Beam Fan──────┐              │█│ Zone  [Inherit]│ track 36
│▌  Track 2       (All)M│        ┌Square──────┐    ┌Spiral─────────┐       │ │                │
│▌  Track 3       (All)M│ ┌Wave──────────┐     ┌Triangle─────┐             │ │ EFFECTS        │
│                       │  Add more content from the browser below          │▼│ + Add effect   │
├───────────────────────┴──────────────────────────────────────────────────┴─┤                │
│ ◀━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━▶ │                │ h-scroll
├─────────────────────────────────────────────────────────────────────────────┴────────────────┤
│ PRESETS  Geometric | Wave | Beam      [Circle] [Square] [Triangle] [Spiral] [Star]  Adds at 2.3 on All 1 │ drawer 132
├──────────────────────────────────────────────────────────────────────────────────────────────┤
│ Default zone: [All ▾]   Trigger: [Toggle ▾]                      [Open in Cue Chain Editor] [Close] │ footer
└──────────────────────────────────────────────────────────────────────────────────────────────┘
```

## 4. Acceptance checklist (screenshot review)

Run `timeline-screenshot-baseline` (`03` §8) with the same scenario as §1 and check:

1. Header row *i* and lane *i* share top and bottom edges within 1 px, also after vertical scroll (V-1).
2. Clips on different tracks have different colours; with everything on zone "All", there are still ≥ 3 distinct colours (V-2).
3. The "circle"/"star" overlap shows a hatched span and both clip edges (V-3).
4. Exactly one white outline (the selected clip); no gold anywhere; the selected header row is a tinted row, not a saturated fill (V-4).
5. When stopped, the orange dashed cursor is visible and there's no red line; when playing, both are visible (V-5).
6. Ruler labels read 1, 2, 3 … at bar lines; the loop band doesn't overlap any label (V-6).
7. The folder lane shows summary bars (V-7).
8. No more than one lane-height of empty canvas below the last lane before the area is filled by the empty-state or background; the horizontal scrollbar sits directly under the lanes (V-8).
9. Clip labels are preset display names and are clipped with an ellipsis (V-9).
10. No checkboxes or "⊗" in headers; zone chips only; header column 220 px (V-10).
11. The empty cue shows the empty-state text and no highlighted lane (V-11).
12. Toolbar toggles clearly show on/off; the zoom has −/+/fit (V-12).
13. The browser drawer is ≤ 132 px and the inspector is on the right (V-13).
14. No 52 px name header; the footer says "Default zone:" (V-14).
15. Grid lines are crisp 1 px (zoom the PNG to 400 %) (V-15).

## 5. Phase mapping

| Item | Phase 1 (quick win, current canvas) | Phase 3 (new layout) |
|---|---|---|
| V-2 track colours + clip inset/border, V-3 overlap hatch, V-4 selection colours, V-5 cursor, V-9 names + clipping, V-15 pixel snapping and cached Colors/Fonts, V-14 footer label and stage size | yes (`01` §4.3, §4.4) | carried over |
| V-1, V-6, V-7, V-8, V-10, V-11, V-12, V-13, V-14 header merge | — | yes (`03` §4.2-§4.7) |
