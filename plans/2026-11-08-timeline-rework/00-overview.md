# Timeline rework — overview

**Written:** 2026-11-08 (user decisions D10–D12 added the same day)   **Written for commit:** `d0a7dc2` (`master`, "docs")
**Status:** In progress (Phase 1 done, see `01-phase1-correctness.md`)   **Last verified against:** `d0a7dc2`
**Supersedes / Superseded by:** none
**Related findings:** 01 F8 (done), F9, F10, F11, F12, F14, F15, F17, F18, F22, M1, M3, M5, M6, L1, carry-overs "tracks can't inherit" and "clip overlap"; 02 #8, #15, #16, #19, #20, #21, M4, M5; 03 #1, MR1; 04 #11, LE1; overview T1, T4, T5
**Depends on / interacts with:** `plans/2026-11-08-undo-history-boundary.md` (In progress), `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md`

> **Line numbers.** Every `file:line` in this folder refers to commit `d0a7dc2`. When this plan was written, the working tree also had **uncommitted** edits from the undo-history-boundary work (`events/handlers/timeline.clj`, `history.clj`, `state/history.clj`, `cue_chain.clj`, `grid.clj`, `list.clj` handlers, `keyframe.clj`, `persistent.clj`, `events/core.clj`, …). Those edits remove `h/mark-dirty` calls and add `:history/skip?` / `:document/rebase?` / `:history/coalesce-key` keys, so line numbers in those files will shift by a few lines once that lands. Where this plan depends on that work it says so.
>
> **Evidence.** All findings come from reading code at `d0a7dc2`. The visual findings were also checked against real screenshots of the dialog, taken with a throwaway TestFX harness (see `05-visual-design.md` §1 and `img/`). No other finding has been reproduced at runtime; each Phase 1 item starts by writing the failing test that proves it.

## Files in this plan

| File | Contents |
|---|---|
| `00-overview.md` | This file: root causes, finding index, target architecture, phase order, decisions |
| `01-phase1-correctness.md` | Small, independent fixes that stop data corruption and broken controls |
| `02-phase2-model-and-gestures.md` | Pure timeline model, edit ops as data, local gesture state, per-cue UI state |
| `03-phase3-layout-viewport.md` | One row layout for headers + lanes, virtual layered canvas, new header column |
| `04-phase4-transport-and-time.md` | Play/pause/seek/stop, stop at end, playhead = output, clip-local time, locked tempo, timecode display |
| `05-visual-design.md` | Screenshot review and the visual spec that Phases 1 and 3 implement |
| `06-phase5-timecode-chase.md` | Following incoming MIDI timecode (MTC) |
| `img/` | "Before" screenshots (1400×820 dialog, commit `d0a7dc2`) |

## 1. What the timeline is today (code map)

| Piece | Where | Role |
|---|---|---|
| Dialog | `views/dialogs/timeline_cue_editor.clj:86-245` | Stage, header name, footer (zone, trigger mode), mounts the editor; mounted from `views/root.clj:148,183` |
| Editor | `views/components/visual_editors/timeline_editor.clj` (1192 lines) | Toolbar `:618`, sidebar `:757`, inspector `:788`, add panel `:955`, canvas `:1038`, composition `:1072` |
| Canvas plumbing | `views/components/visual_editors/canvas_interaction.clj:92-274` | Shared `interactive-canvas`; drag/hover state in `[:ui :canvas-drag id]` via async events |
| Track helpers | `views/components/visual_editors/timeline/track_logic.clj` | Pure: tracks tree, leaf resolution, geometry, zoom math |
| Handlers | `events/handlers/timeline.clj` (≈680 lines) | 35 events (`:620-675`), plus a `defonce` ScrollPane registry (`:73`) |
| Generic list | `views/components/list.clj:789` + `events/handlers/list.clj` | Track sidebar (tree, DnD, rename, enable checkbox, clipboard) |
| Rendering | `services/frame_service.clj:266-535` | Window gating (`in-timeline-window?` `:266`), zone routing, effects |
| Output | `backend/multi_engine.clj:174` | Same `generate-frames-by-zone` path via the 16 ms cache |
| Timing | `animation/cue_timing.clj:120-245`, advanced by `frame_service.clj:79-122` at 30 Hz | Per-cue beats, phase-offset glide, loop wrap |
| Routing | `routing/zone_effects.clj:78-156` | Track → zone (with folder inheritance) |
| Keyframe timeline | `views/components/visual_editors/keyframe_timeline.clj` | Separate 0–100 % keyframe strip for modulators |

Data lives on the cue chain at `[:chains :cue-chains [col row]]`:
`:items` (clips, each with `:timeline/start`, `:timeline/duration`, `:track-id`), `:tracks` (tree of `{:type :track}` and `{:type :group :items [...]}`), `:loop {:enabled? :start :duration}`, `:editor-type :timeline`. UI state is global: `[:ui :timeline …]`, `[:list-ui :timeline-tracks]`, `[:ui :canvas-drag …]`.

## 2. Root causes

1. **No timeline model.** Timeline data is a cue chain with a few extra keys. About ten call sites read those keys, and each one supplies its own default. The defaults disagree: the editor treats a clip with no timing as beats 0–4, and the renderer treats it as always on. Nothing enforces an invariant, so every new writer (cue chain editor, list paste, grouping, load) can break the screen/output contract.
2. **Interaction state goes through the async event queue.** Drag, hover, scroll and zoom go from the FX thread to the agent, into state, back through a re-render, and only then onto the screen. Handlers also read live `ScrollPane`s from the agent thread and push values back into them. The result is jitter, snap-back, stale reads and a possibly stuck undo transaction.
3. **Two layouts expected to line up.** Track headers come from the generic `list-editor` (rows sized by their content, with gaps). Lanes are a canvas with fixed 32 px rows. The two panes are synced by a 0–1 scroll fraction even though their heights differ.
4. **A giant canvas inside a ScrollPane.** The width is capped at 8192 px, so long shows are cut off. It's fully redrawn on every re-render, and the dialog re-renders 30× a second while playing.
5. **No transport or time model.** Position is "beats since the grid cell was triggered", plus a phase glide meant for looping cues. There's no seek and no end, clips don't get their own local time, and the playhead is drawn from a different number than the renderer uses.
6. **No visual system.** Colours, sizes and fonts are hard-coded per draw call. Clip colour comes from the zone, and the default zone is grey, so in the common case every clip is the same grey (`05` V-2).

## 3. Finding index

Severity: **H** high, **M** medium, **L** low. "Phase" is the phase that fixes it.

| ID | Finding | Where (`d0a7dc2`) | Sev | Phase |
|---|---|---|---|---|
| TL-1 | Opening a non-timeline cue in the timeline editor rewrites it (start 0 / duration 4, `:editor-type :timeline`); with loop off by default it goes dark after 4 beats, permanently | `timeline.clj:583-614`; `frame_service.clj:113`; dispatchers `cue_chain_editor.clj:626`, `grid_cell.clj:252` | H | 1 |
| TL-2 | Missing timing = beats 0–4 in the editor, "always on" in the renderer; `timeline-ready?` uses `some`, so other items are never filled in; presets added from the cue chain editor get no timing or track | `timeline_editor.clj:275-276,376-377,431-432`; `track_logic.clj:313-314`; `frame_service.clj:266-283`; `timeline.clj:570-574,602`; `cue_chain.clj:84-87` | H | 1 |
| TL-3 | Track enable checkbox doesn't mute; disabled clips are drawn normally and can't be inspected | `list.clj:412,485`; `zone_effects.clj:102-125`; `frame_service.clj:394-464`; `timeline_editor.clj:271-302,793` | M | 1 |
| TL-4 | Deleting a track orphans its clips: they jump to "Unassigned" and re-route to the chain default zone | `timeline.clj:369-377`; `zone_effects.clj:127-156` | M | 1 |
| TL-5 | Track sidebar copy/paste uses the `:cue-chain-items` clipboard, so tracks can be pasted as cue items and vice versa | `list.clj:853`; `handlers/list.clj:232-274` | M | 1 |
| TL-6 | `:timeline/start` is both the clip-start data key and an (unhandled) trigger event | `triggers.clj:75-78`; `timeline.clj:673-675` | M | 4 |
| TL-7 | New tracks stamp the chain default zone, so folder inheritance and the footer "Default Zone" rarely apply | `timeline.clj:442`; `timeline_cue_editor.clj:35` | L | 1 |
| TL-8 | Zoom slider is a no-op: the view sends the render-time zoom and the handler reads `:zoom`, not `:fx/event` | `timeline_editor.clj:687-688`; `timeline.clj:60-64` | M | 1 |
| TL-9 | Snap checkbox sends `(not snap-enabled?)` captured at render | `timeline_editor.clj:664-665`; `timeline.clj:180-193` | L | 1 |
| TL-10 | Left-edge trim moves the right edge (start and duration clamped separately); same for the loop brace; correct math exists in the undispatched `handle-resize-selection` | `timeline_editor.clj:467-474,494-501`; `timeline.clj:268-292,317` | M | 1 |
| TL-11 | Overlapping clips: drawn last-on-top, hit-tested first-on-top; overlap itself is invisible | `timeline_editor.clj:334-340,375`; `track_logic.clj:111` | M | 1 |
| TL-12 | Loop brace hit area is the full ruler height and the brace is always drawn; dragging a missing loop creates an *enabled* one (default `true` vs `false`) | `timeline_editor.clj:356`; `timeline.clj:242,258` | L | 1 |
| TL-13 | Canvas undo transaction can stay open after a quick click (commit only if `:dragging?` already reached state). The undo plan's 60 s stale auto-commit bounds the damage | `canvas_interaction.clj:180-213`; `history.clj:143` | M | 2 |
| TL-14 | Drag/hover state round-trips through async events; every drag pixel writes the document and re-renders the dialog | `canvas_interaction.clj:39-80,171-229` (01 F14) | M | 2 |
| TL-15 | Scrolling fights the user: `set-hvalue` echoes the stale value back via `runLater`, and the `:hvalue`/`:vvalue` props re-apply lagging state | `timeline.clj:104-111`; `timeline_editor.clj:1134,1160-1163,606` | H | 1 |
| TL-16 | Track headers don't line up with lanes (content-sized rows + gaps vs fixed 32 px; normalized scroll sync between panes of different height) | `timeline_editor.clj:37,135-138`; `list.clj:528,605`; `css/list.clj:338-348`; `timeline.clj:93-102` | H | 3 |
| TL-17 | Collapsing a folder moves its clips into "Unassigned" | `timeline_editor.clj:105-111` | M | 1 |
| TL-18 | Canvas width capped at 8192 px: about 164 beats at default zoom, 41 beats at max zoom; anything later can't be seen or reached | `track_logic.clj:308-325` | H | 3 |
| TL-19 | View state is global, not per cue (zoom, scroll, selection, cursor, track selection) | `timeline_cue_editor.clj:105`; `timeline.clj:195-216,471-476` (02 #15) | M | 1 (reset) / 2 (keyed) |
| TL-20 | Dead code: `:timeline/scroll`, `scroll-horizontal`, `init-tracks`, `toggle-track-expand`, `resize-selection` have no dispatcher in `src`; `:scroll-x`, `expanded-tracks`, `list-props` unused; `tl/track-index`, `track-zone-id`, `add-track`, `remove-track`, `update-track`, `move-track` unused | `timeline.clj:66-71,114-135,317-367,392-403,628-658`; `track_logic.clj:59-64,113-161`; `timeline_cue_editor.clj:155-165`; `timeline_editor.clj:1086` | L | 1 |
| TL-21 | Folder Properties are unreachable: selection is resolved to a leaf before the inspector; with nothing selected, the first leaf is highlighted as "selected" | `timeline_editor.clj:1099-1105`; `track_logic.clj:183-219` | M | 1 |
| TL-22 | Inspector shows the wrong zone for tracks that inherit (looks up `(:path track)`, which doesn't exist) | `timeline_editor.clj:891,895-901` | L | 1 |
| TL-23 | Rename in the dialog header stays open after Enter (`:cue-chain/set-name` clears the cue chain editor's flag) | `cue_chain.clj:226`; `timeline_cue_editor.clj:133-141` | L | 1 |
| TL-24 | Playhead ≠ output: the playhead draws accumulated beats, clips are gated on accumulated + phase offset, which glides up to ~1 beat and moves on tap tempo | `timeline_cue_editor.clj:110`; `frame_service.clj:410`; `cue_timing.clj:188,239`; `timing.clj:45` | M | 4 |
| TL-25 | Loop wraps accumulated beats, but clips are tested on effective beats, so the loop region the clips see is shifted | `cue_timing.clj:154-159` | M | 4 |
| TL-26 | No transport: start = trigger the cell at beat 0; ruler click moves an edit cursor drawn as the playhead; no seek/pause/end/follow | `timeline_editor.clj:325,437-446`; `grid.clj:32-55` | M | 4 (cursor drawn separately in 1) |
| TL-27 | No clip-local time: every clip gets the cue's timing context | `frame_service.clj:620-627`; 03 #1 | M | 4 |
| TL-28 | Timing advances at 30 Hz from the preview timer; the whole dialog re-renders on each tick and subscribes to all of `:chains` | `frame_service.clj:780-785`; `timeline_cue_editor.clj:96,108-110,230`; 04 #11, 01 F11/F12 | M | 2 (subs) / 4 (clock) |
| TL-29 | Keyframe timeline hover never draws (`:hover-idx` vs `:hover-id`); same in `curve_canvas` | `keyframe_timeline.clj:277`; `curve_canvas.clj:219`; `canvas_interaction.clj:225` | L | 1 |
| TL-30 | Dragging a keyframe past a neighbour swaps them (index identity, handler re-sorts) | `keyframe_timeline.clj:302,318`; `keyframe.clj:265-268` | M | 1 |
| TL-31 | No timecode. Timeline speed follows the live global BPM, which tap tempo and Link change (`timing.clj:24-46`), so a pre-recorded show can't keep a fixed beat↔time mapping. Incoming MTC is dropped by the MIDI decoder (`midi.clj:71-86`), and full-frame SysEx is never received (`midi.clj:128` uses the 2-arity `midi-handle-events`) | `frame_service.clj:113-120,610,624`; `multi_engine.clj:160`; README `:157` | M | 4 (tempo, display) / 5 (chase) |
| V-1 … V-15 | Visual findings | `05-visual-design.md` §2 | — | 1 / 3 |

## 4. Target architecture

```
                 ┌─────────────────────── views (FX thread) ───────────────────────┐
 dialog ──▶ timeline-editor (fx/ext-state: viewport, hover, gesture)               │
                 │  toolbar   header-column   lane canvases (grid | clips | overlay) │
                 │      ▲            ▲               ▲   gesture controller (pure step fn)
                 │      └── laser-show.timeline.layout (rows, beat↔px, hit-test) ───┘
                 │                     │ one :timeline/apply-op per committed edit
                 ▼                     ▼
       events/handlers/timeline ──▶ laser-show.timeline.model (normalize, accessors, apply-op)
                 │                                   ▲
   pipeline: wrap-normalize-timelines → wrap-history → wrap-dirty
                 │                                   │
 frame_service / multi_engine ──▶ laser-show.timeline.playback (active clips, clip-local ctx)
                 ▲
   cue_timing (per-cue :timeline? flag: no phase glide, own tempo, pause, seek, stop at end)
                 ▲
   input.mtc runtime atom (Phase 5: MIDI timecode chase)
```

Principles:
1. **One model.** `laser-show.timeline.model` (NEW, pure) owns the document shape, the accessors (`clip-start`, `clip-length`, `clip-end`), normalization and every edit. The editor, handlers, frame service and load path all go through it. Readers never supply defaults.
2. **Edits are data.** A committed edit is one `{:op …}` map, applied by one pure function. That gives one undo entry with a meaningful label, and something to test without JavaFX.
3. **Gestures and viewport are local and synchronous.** Hover, drag preview, zoom and scroll live in view-local state on the FX thread. The event queue only sees committed edits.
4. **One layout.** `laser-show.timeline.layout` (NEW, pure) produces the rows, and the header column and the lanes both render from them.
5. **One position.** Timeline cues don't use the phase glide, so the playhead and the renderer read the same beat. Clips get clip-local time.
6. **Tokens, not literals.** Canvas colours and sizes come from theme tokens (`05` §3), and clip colour comes from the track.

## 5. Phases

| Phase | Goal | Size | Depends on |
|---|---|---|---|
| 1 — Correctness (`01`) — **Done** 2026-11-11 | Stop data changes on open, make editor = output, fix broken controls, scroll echo, trims, inspector, mute, keyframe strip; cheap visual wins | ~3–4 days | none (coordinate with the undo plan for TL-1 history flags) |
| 2 — Model and gestures (`02`) | `timeline.model`, `apply-op`, local canvas state (01 M3 for all canvases), per-cue UI state, narrowed subs (01 M1 steps 2, 3, 5) | ~1 week | Phase 1 |
| 3 — Layout, viewport, visuals (`03` + `05`) | Shared row layout, header column, virtual layered canvas, ScrollBars, new look | ~1–1.5 weeks | Phase 2 |
| 4 — Transport and time (`04`) | Per-cue transport, seek, stop at end, playhead = output, clip-local time, locked tempo, timecode display, trigger actions | ~1–1.5 weeks | Phase 2; clip-local once-mode needs 03 MR1 |
| 5 — Timecode chase (`06`) | Follow incoming MTC: position, start/stop, jumps, freewheel | ~1 week | Phase 4 |

Each phase leaves the app shippable. Phases 3 and 4 are independent of each other.

## 6. Ownership with other plans

To keep one location per item, this plan **takes over** the timeline-specific parts of these items. The other plans keep the non-timeline parts.

| Item | This plan does | Stays with |
|---|---|---|
| 01 F9 (toolbar payloads) | All of it (TL-8, TL-9) | — |
| 01 M1 steps 2, 3, 5 | Timeline dialog subs only (`02` §4) | 01 for grid cells and other dialogs |
| 01 M3 (local canvas state) | Steps 1, 2, 4 for **all** canvases, since the timeline needs the shared fix (`02` §4) | 01 keeps steps 3, 5, 6 |
| 01 M6 (list row props) | Removes the timeline's use of `list-editor` (`03`), so no timeline work is needed there | 01 |
| 01 Q10 / L1 (colour tokens) | Defines the timeline tokens (`05` §3) as the first user of `laser-show.css.canvas` | 01 for other canvases |
| 02 M4 "Timeline scroll panes", #20 | All of it (`01` §4.3, `03`) | — |
| 02 M5 (atomic list ops) | Not needed for tracks once `03` replaces the sidebar | 02 for other lists |
| 02 #8 / Q4 (`:timeline/start` / `:stop`) | Implemented in `04` | 02 for `:preset/apply` |
| 01 carry-over "tracks can't inherit" | `01` §4.12 | — |
| 01 carry-over "clip overlap" decision | Decision D6 below | — |
| Undo plan: open-editor `:history/skip?` + `:document/rebase?` | Keeps that classification for conversion (D2) | Undo plan |

## 7. Decisions

| # | Decision | Why |
|---|---|---|
| D1 | Keep the existing keys (`:timeline/start`, `:timeline/duration`, `:track-id`, `:tracks` with `:type :group` + `:items`, `:loop {:enabled? :start :duration}`). No save-format migration. | Reuse before create; existing projects load unchanged |
| D2 | Opening never rewrites a timeline cue. Opening a **non**-timeline cue converts it **without changing its output**: every item becomes a clip spanning `[0, 16)` beats (16 confirmed by the user), and a loop over `[0, 16)` is enabled unless the cue already has `:loop`. Conversion keeps the undo plan's classification (`:history/skip? true`, `:document/rebase? true`). | Fixes TL-1. Caveat: loop-mode modulators whose period doesn't divide 16 beats get a phase jump at each wrap |
| D3 | Clip timing is required on every **top-level** item of a timeline cue, enforced by normalization in one pipeline middleware and on load. Nested children of a group clip may omit timing (= always on inside the parent). | Fixes TL-2 at the root; covers future writers |
| D4 | Deleting a track deletes its clips (one undo entry). | DAW convention; no hidden re-routing (TL-4) |
| D5 | Timeline windows stay **absolute** beats at every level (current renderer semantics). Moving a clip shifts every nested `:timeline/start` by the same delta. Clip-local time is only for modulation (Phase 4). | No migration; moving a clip moves its contents |
| D6 | Clips on one track **may** overlap; overlap is drawn explicitly (`05` V-3), and hit-testing picks the topmost clip. | Matches the renderer, which already plays overlaps; avoids the deleted overlap plans' broken algorithms |
| D7 | *Superseded by D10:* track mute uses a NEW `:muted?` key, not `:enabled?`. Solo is out of scope. | See D10 |
| D8 | Clip colour = track colour. New tracks get a colour from a palette; the zone is shown as a chip in the header. | Fixes the all-grey look (V-2) |
| D9 | Transport events are `:timeline/play`, `:timeline/pause`, `:timeline/stop`, `:timeline/seek`. Nothing is named `:timeline/start`. | Removes the TL-6 collision; reuses the existing `"timeline"` route (`events/handlers.clj:63`) |

## 8. Decisions confirmed with the user (2026-11-08)

| Question | Answer | Recorded as |
|---|---|---|
| Conversion length | 16 beats is fine | D2 |
| Should folder collapse and track mute be undoable? | **No** | D10 |
| What happens at the end of a timeline? | **It stops** (cue deactivates); there's no "hold" option | D11 |
| Timecode for pre-recorded shows? | **Yes**, pre-recorded shows need timecode | D12 |

| # | Decision | Why |
|---|---|---|
| D10 | Track mute moves to a NEW track key `:muted?` (this changes D7, which reused `:enabled?`). `[:muted?]` is added to the undo plan's `live-item-paths` (working tree `state/history.clj:43-46`), next to the existing `[:collapsed?]`. Both are then **not undoable and don't mark the project unsaved**, but are still saved. Undo restores content around them: the undo plan's `restore-document` grafts live fields by item `:id`, so undoing an edit never flips mute or collapse back. Normalization migrates existing tracks with `:enabled? false` to `:muted? true`. | A separate key lets the path-based undo policy cover mute without making every item's `:enabled?` (clips, effects) non-undoable |
| D11 | A timeline without an enabled loop **stops at its end**: the cue is deactivated. There's no `:timeline/end-behavior` key and no `:hold` mode. | User decision; simpler model |
| D12 | Timecode, in two parts. **Phase 4:** a per-timeline locked tempo (`:timeline/tempo-bpm`) plus a timecode display (ruler mode, readout, `:timeline/tc-start` offset). **Phase 5 (NEW, `06`):** chasing incoming MIDI timecode (MTC). LTC stays out of scope; use an external LTC→MTC converter. | Timecode only means something if beats↔seconds is fixed, so the locked tempo comes first. MTC can reuse the existing MIDI input; LTC needs audio decoding |

The interpretation of "a timecode thing" behind D12 is: show timecode in the editor, and let a show follow an external timecode source. If only the display was meant, Phase 5 can be dropped without affecting the other phases.
