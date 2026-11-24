# Open items after the cljfx reactivity repairs

**Status:** Open · **Last verified against:** `reactivity/integration` at `7d3c1060` (merged into `master` on 2026-11-24)
**Supersedes / Superseded by:** none · **Related findings:** [repair plan and implementation status](2026-11-23-cljfx-reactivity/00-overview.md#implementation-status-2026-11-24), [audit](2026-11-23-cljfx-reactivity-audit.md)

This list holds everything left over from the 38 repairs: decisions, checks that need hardware or a display, and bugs found but not fixed. Each row names its evidence (paths under `src/laser_show/` unless stated), the package that found it, a rough size (S ≈ hours, M ≈ a day, L ≈ days) and what it needs.

Priorities:
- **P1:** can lose work, aim light wrongly or mislead during a show.
- **P2:** wrong but recoverable.
- **P3:** cost, cleanup or wording.

Several rows are leads rather than reproduced bugs; each says which. Re-check the cited code before starting.

## Start here

1. **Check right-click on a real display (P1, S, needs a display).** If an unselected row's context menu opens without selecting the row, Delete from that menu deletes the old selection (§3, item B1).
2. **Make projector IDs readable for IPv6 hosts (P1, S).** Today such a show can't be saved at all (B2).
3. **Stop a stale safety-zone drag from landing on a newer edit (P1, M).** This is the one wrong-target path left in the safety editor (B3).
4. **Retire MIDI holds on All Notes Off (P2, S).** A key can otherwise stay "held", and its next press plays nothing (B5).
5. **Run the owed real-projector and DAC checks** before relying on the new output status and thumbnails at a show (§2, item H1).

## 1. Decisions needed

| Item | What it is | Options | What it does now |
|---|---|---|---|
| **D1 · Refused controls under Show Lock** (13) | With Show Lock on, many controls are still clickable, such as a projector's **On** box, a tag, a Vibes option or a pattern's Follows box. JavaFX flips the box the moment you click, before the app decides. The lock then refuses the edit. Before the fix, the box kept showing the change that was refused. | **(a)** Keep it clickable: send the edit once, show the "Show lock is on: … refused" notice, then snap the control back to the stored value within about 300 ms. **(b)** Grey out every control the lock would refuse while it's on. | (a). The adapter is `views/components/applied_value.clj`; the lock policy is `events/show_lock.clj`. Choosing (b) means a per-control "would the lock refuse this?" check. Edits already queued before the lock turned on would still need (a). |
| **D2 · Delete the finished plan** | Planning-guide rule 11 says to delete a completed plan, since git history keeps it. | Delete `plans/2026-11-23-cljfx-reactivity/` and the audit, or keep them while this list is open. | Kept. This list links to it. |

Confirmed on 2026-11-24, so no longer open:
- 02 fails Save on a filesystem that can't replace a file atomically.
- 09 replaces an out-of-range IDN port with 7255.
- 36 sets the cache to 4096 entries.

## 2. Checks that need hardware, a display or CI

| P | Item | What to run | Why it's owed |
|---|---|---|---|
| P1 | **H1 · Real-projector and DAC checks** | Output runtime Phase 5: a real-DAC run and the hidden-window pacing check (`plans/2026-11-16-output-runtime/00-overview.md:10–14`, its `05-phase5-frame-timing.md` §8). Zones R-1 and the Z1/Z3–Z6 projector checks (`plans/2026-11-19-zones/00-overview.md:127–133`, its `05-phase-z3-routing-core.md:430–433`). | 18 (thumbnails) and 30 (starting status) change what the operator is told about output. No test proves physical delivery. |
| P2 | **H2 · Windowed drag test** | `clj -M:test-ui:mac-arm -v laser-show.ui-test.grid-test/native-cell-drag-clears-on-drop-and-escape-test` on a machine where windows may open (`test/laser_show/ui_test/grid_test.clj:882`). | 38 can only start a real platform drag in a window; headless runs skip it. |
| P2 | **H3 · Flaky timing test** | `laser-show.backend.streaming-engine-test/device-paced-driver-waits-for-ready-test` (`test/laser_show/backend/streaming_engine_test.clj:493–515`) failed once in about ten full unit runs, then passed three re-runs. | Unverified cause. Its ready predicate checks `(:tick (show-time/latest))` (`:497–500`) when asked, not the tick the engine is handling, so it can miscount polls around `publish!`. Make the predicate key on the frame being readied, then re-run it 20 times. The engine's readiness loop (`backend/streaming_engine.clj:342–360`) is unchanged by the repairs. |
| P3 | **H4 · CI for dev-mode rendering** | Add `clojure -M:dev:linux:test:test-ui-headless -n laser-show.renderer-wiring-test` and `clojure -M:dev:linux:test-ui:test-ui-headless -v laser-show.ui-test.menu-bar-test/renderer-inspector-is-dev-only-test`, ideally plus `-n laser-show.ui-test.settings-test`, to `.github/workflows/build-and-test.yml`. | 31 made dev mode validate every render; only CI keeps those specs and corrections honest. The workflow file has uncommitted edits of yours, so it was left alone. |

## 3. Bugs and wrong-target leftovers

| P | Item | Evidence | Found by | Size | Direction |
|---|---|---|---|---|---|
| P1 | **B1 · Right-click may act on the old selection** (lead) | The row's click listener selects an unselected row on a secondary click (`views/components/list.clj:351–356`). With the headless robot, the context menu opened on the press and took the release, so that click never ran. | 32 | S | Check on a real display. If confirmed, select on `MOUSE_PRESSED`/`CONTEXT_MENU_REQUESTED` before the menu is built. |
| P1 | **B2 · IPv6 hosts make a show unsaveable** | `routing/rig.clj:617–621` builds `:projector-<host>-<n>`, replacing only `.` in the host, so a `:` gives a keyword the EDN reader can't read back. 02's verified Save now refuses the file instead of writing one that won't open. | 02 | S | Map every non `[A-Za-z0-9-]` character in the host. Decide how existing in-memory IDs move: a rename through `rig-file/rename-pins`, or new IDs on the next scan. |
| P1 | **B3 · A safety-zone drag still replaces the whole zone** | The drag sends `:projectors/set-safety` with the zone it started from (`views/components/safety_editor.clj:307–313` → `events/handlers/projector.clj:802`). Its confirmation is not scoped like 08's coordinate edits (`events/handlers/projector.clj:840`), so an Allow can land after a newer edit. Undo, Redo and Copy questions are also unchanged. | 08 | M | Route drags through 08's proposal (`events/safety_guard.clj` `zone-edit-proposal`, `ask-review`), with the gesture's press id from 16. |
| P2 | **B4 · Colour picker sends a JavaFX node** | `events/handlers/chain/params.clj:133–150` reads `.getValue` from the ColorPicker on the event thread; `events/handlers/keyframe.clj:499,537` does the same. Refusals don't restore it, and 13b left it unwrapped. | 01, 13b | M | Extract the colour on the FX thread and send plain RGB data, as 01 did for numbers. Wrap it with `applied-value/applied-control`. |
| P2 | **B5 · All Notes Off keeps MIDI activations** | `events/handlers/input_mapper.clj:60–68` lets go of roles on CC 120/123, but the activation records from 26 stay. A later press of that key counts as a repeat and plays nothing until its note-off arrives. A successful OSC restart on a new port also keeps the old server's holds. | 26 | S | Dispatch `:trigger/retire {:source :midi}` from the All-Notes-Off path, and `{:source :osc}` when a restart moves ports. |
| P2 | **B6 · Modulate toggle targets a slot, not an effect** | `events/handlers/modulator.clj:44–60` (`handle-toggle`) resolves `:effect-path`. A queued toggle after a reorder can hit the wrong effect, the class 05 and 07 fixed for other events. | 07 | S | Address it by effect instance via `chain-helpers/resolve-effect-instance-path`, like 07's field edits. |
| P2 | **B7 · A chased cue can't be toggled off** | A grid click still treats a Follow Timecode marker as playing, so turning off a cue that follows running timecode is undone by the clock's next On (`events/handlers/grid.clj:66`, 15's `:show-clock/apply-chase`). | 15 (existing) | S | Decide the UX first: refuse with a notice, or switch Follow off. |
| P2 | **B8 · Vibe ownership after Undo of a replacing move** | `vibes/refs.clj:163` (`restore-fx`) follows restore's by-key rule. After Undo, both the moved and the replaced cell are vibe-owned. Trigger-held switches, cue voices and `[:vibe-runtime :problem]` aren't remapped. | 22 | M | Keep the replaced cell's own pre-move owner in the snapshot. |
| P3 | **B9 · Rig Try again can lose its Exit** | `events/handlers/rig.clj:134–140` writes with `:then`. A newer autosave that supersedes it in `state/rig_store.clj:288` (`submit!`) drops that `:then`, so Exit has to be pressed again (existing). | 21 | S | Carry a pending `:then` onto the superseding write. |
| P3 | **B10 · Late error from a stopped engine** | `backend/streaming_engine.clj:314–320` reports under `[:engine id]`, so a replacement engine's status can show the old engine's error until its next send. | 18 | S | Tag reports with the run token 18 already checks. |
| P3 | **B11 · Generic chain writers and a missing projector** | `events/handlers/chain/helpers.clj:17–48` resolves `:projector-effects` for any id. A late write can recreate an orphan calibration chain. `:projectors/select-projector` also accepts a missing id (`events/handlers/projector.clj:441`); this is harmless now that `subs/active-projector` returns nil. | 14 | S | Guard the `:projector-effects` domain on the projector existing. |
| P3 | **B12 · Paste and Open edge cases across a new show** | A paste returning after New/Open while Show Lock is on still shows the lock's refusal notice. An Open re-checked while the unsaved-changes dialog is already open is dropped, logged only (`events/helpers.clj:128`, `events/handlers/menu.clj:63`). | 20 | S | Check `same-document?` before the lock, and queue rather than drop. |
| P3 | **B13 · OSC port label during a programmatic change** | `events/handlers/input.clj:235` accepts a port change while listening, so "Listening on port N" shows the new port before any restart. | 09 | S | Show the listening port from the server's reported state. |
| P3 | **B14 · Slider press across a same-label retarget** | `views/components/gesture.clj:120–135,182,205`: a slider re-rendered for another control under the same label and source id keeps its press. | 16 | S | Include the effect instance in `:source-id`. |
| P3 | **B15 · Canvas scroll handler never installed** (latent) | In `views/components/visual_editors/canvas_interaction.clj:354–372`, `:on-scroll` sits inside the `:on-key-pressed` function, a bracket mistake from before the repairs. Nothing passes `:on-scroll` today. | 33 | S | Move the bracket, then add a test with a scroll consumer. |
| P3 | **B16 · Small existing gaps** | IDs from `System/currentTimeMillis` collide for two programmatic adds in one millisecond (`events/handlers/projector.clj:357`). `:int` modulator subfields such as Steps store decimals (07). An effect already running when its app retires still finishes its own side effect (19; `events/core.clj:914` `guard-all` drops only what follows). | 09, 07, 19 | S each | Fix the first two; treat the third as documented behaviour. |

## 4. Rendering and state-ownership follow-ups

| P | Item | Evidence | Size | Direction |
|---|---|---|---|---|
| P3 | **R1 · Registry reads at render time** | `effects/get-effect` is called during render in `views/tabs/projectors.clj:472`, `views/components/visual_editors/timeline/device_chain.clj:41`, `views/dialogs/zone_pattern_editor.clj:42`, `views/dialogs/cue_chain_editor.clj:70,356` and `views/dialogs/effect_chain_editor.clj:54,134`. The modulator controls read `animation.modulator-registry` directly (`views/components/modulator_param_control.clj:35`). They only refresh on code reload. | M | Read 17's published `subs/effect-descriptors` / `modulator-descriptors`. |
| P3 | **R2 · Target menus address items by path** | `:cue-chain/set-item-target` carries `:item-path` (`views/dialogs/cue_chain_editor.clj:152,183`; handler `events/handlers/cue_chain.clj:250`), so moved items' menus and the selected item's editors re-render on reorder. | S | Take the item id and resolve the path from state (changes the event's contract). |
| P3 | **R3 · Whole-map subscriptions** | `views/components/rig_file_status.clj:61` subscribes to all of `:rig-file`, which updates more often since 23. `effects-grid-empty?`, `zone-patterns-in-use?` and `map-cycles?` (`subs.clj:441,1404,1412`) walk the whole effects grid on any effects-chain change. `views/components/vibe_pads.clj:48` reads `cell-display-data` only for a label. | S | Narrow to the fields shown; use 37's `cue-label-data` for the pad label. |
| P3 | **R4 · Memory to watch at the new cache size** | 36 measured stale view descriptions kept up to capacity: about 48 MB after 3000 renames at 4096. About 1–2 MB more heap stays in use after each app start/stop in one JVM; that isn't the cache and wasn't investigated (`state/core.clj:61–74`). | M | Profile a long UI-suite JVM; 2048 remains the measured fallback. |
| P3 | **R5 · Link listener in app state** | Carabiner's status listener is stored under `[:backend :link]` (`events/handlers/timing.clj:336–341`; `state/domains.clj:568`) and closed from a state read (`app.clj:317`). This is the last live object noted in the 2026-11-07 review (02 M4). | M | Give Link a runtime owner like 25's `input/devices.clj`. |

## 5. Wording and docs

| Item | Evidence |
|---|---|
| The pad chip tooltip still says "the zone map in use" (29) | `views/components/zone_map_pads.clj:90` |
| The docstring says the bank click "optionally accepts :item"; 17 removed it | `events/handlers/cue_chain.clj:67–71` |
| Says MIDI/OSC are "managed by state atoms"; 25 moved them to a worker | `dev/reload.clj:108` |
| The tooltip lists errors while Stopping; a retiring engine's rate can show during an unsettled error (30) | `subs.clj:641` (`connection-status`) |
| The probes are pre-repair baselines. The numeric section of the state-outcome probe no longer applies, and the renderer section of the render probe reproduces the old option map itself. | `plans/probes/` (retire them with the plan, or keep them as history) |

## 6. Cleanup

- **Worktrees and branches:** the 11 implementation worktrees (`~/.local/share/opencode/worktree/2e7f27/rx-*`) and the `reactivity/*` branches can be removed now that `master` has the work.
- **Old JVMs:** two idle JVMs from earlier sessions, days old, are still running (cwd `…/T/opencode/wt-vibe4` and `…/T/opencode/wt-zones-zr`). Stop them if nothing needs them.
