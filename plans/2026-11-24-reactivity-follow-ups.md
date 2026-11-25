# Open items after the cljfx reactivity repairs

**Status:** Mostly done. On 2026-11-25 every item that didn't need a physical screen or a DAC was fixed, plus fifteen new items found along the way (N1–N15). · **Last verified against:** `followups/integration` (merged into `master` on 2026-11-25)
**Supersedes / Superseded by:** none · **Related findings:** [repair plan and implementation status](2026-11-23-cljfx-reactivity/00-overview.md#implementation-status-2026-11-24), [audit](2026-11-23-cljfx-reactivity-audit.md)

What's still open comes first; what landed is in [Done](#done-2026-11-25) at the end. Paths are under `src/laser_show/` unless stated otherwise.

Priorities:
- **P1:** can lose work, aim light wrongly or mislead during a show.
- **P2:** wrong but recoverable.
- **P3:** cost, cleanup or wording.

## 1. Decisions needed

| Item | What it is | Options | Now |
|---|---|---|---|
| **D3 · Downbeat alignment checkbox** | Settings' "Sync downbeat alignment" (`[:backend :link :beat-sync?]`, on by default, shown while Link and BPM sync are on) promises an alignment that can't happen. beat-carabiner 8.0.0 offers no way to read Link's beat or phase (N4, `input/link.clj` `get-link-beat-position`). | Hide it until there's a phase source (L1), disable it with a tooltip that says why, or leave it. | Shown; does nothing. |
| **D4 · Reconnecting Carabiner** | After Carabiner drops (N5 now reports it), both Link buttons are disabled and auto-connect only runs at start-up, so nothing in a running app reconnects. `:timing/carabiner-auto-connect` reconnects correctly when sent. | Add a small Connect button in Settings, which sends a connect that ignores the Auto-connect setting (one new event), or leave a restart as the way back. | No way back without restarting. |
| **D2 · Delete the finished plan** | Planning-guide rule 11 says to delete a completed plan; git history keeps it. | Delete `plans/2026-11-23-cljfx-reactivity/`, the audit and `plans/probes/`, or keep them while this list is open. | Kept. |

Settled on 2026-11-24 and 2026-11-25:
- Refused controls stay clickable and snap back.
- Save fails on a filesystem that can't replace a file atomically.
- An out-of-range IDN port becomes 7255.
- The cache holds 4096 entries.
- A press on a chased cue stops it until timecode re-enters it (B7).

## 2. Needs a display, a DAC or CI

| P | Item | What to run | Why it's owed |
|---|---|---|---|
| P1 | **H1 · Real-projector and DAC checks** | Output runtime Phase 5: a real-DAC run and the hidden-window pacing check (`plans/2026-11-16-output-runtime/00-overview.md:10–14`, its `05-phase5-frame-timing.md` §8). Zones R-1 and the Z1, Z3–Z6 projector checks (`plans/2026-11-19-zones/00-overview.md:127–133`). Also N11's IDN change on a real DAC: the config must still arrive after an outage. | 18, 30 and N11 change what's reported or sent on the output path; no test proves physical delivery. |
| P1 | **H5 · Right-click selection on a real display** | On macOS and Windows: select row A, right-click unselected row B, use its menu. B must be the selection the menu acts on. | B1 (`8e8c2f38`) fixed this defensively; the headless robot can't show how a real platform orders press, menu and click. |
| P2 | **H2 · Windowed drag test** | `clj -M:test-ui:mac-arm -v laser-show.ui-test.grid-test/native-cell-drag-clears-on-drop-and-escape-test` on a machine where windows may open. | 38 can only start a real platform drag in a window. |
| P3 | **H6 · GPU memory after start/stop** | On a real display, repeat R4's start/stop cycles with all tabs shown and watch video memory (Activity Monitor, or `-Dprism.verbose=true`). | R4 found about 15 MB per cycle of JavaFX ScrollPane viewport-cache images. They're held softly and freed under heap pressure, so not an app leak. On the GPU pipelines they may be textures that heap pressure never frees (see OpenJFX JDK-8395377). |
| P3 | **H7 · CI cache keys** | On the first CI run after this merge, the first Linux UI shard's cache step should save `Linux-clojure-test-ui-dev-…`, and the other jobs their own keys. | N13 (`3e8845c0`) can only be confirmed on GitHub. The first run downloads every dependency once. |

## 3. Larger design item

| P | Item | Evidence | Direction |
|---|---|---|---|
| P2 | **L1 · A Link phase source, so downbeat sync can work** | `input/link.clj` `get-link-beat-position` returns nil because beat-carabiner 8.0.0's public API has no beat or phase read. Its `beat-at-time` shifts the whole Link session's grid and must not be used (N4, `0974fdfe`). `sync-to-downbeat!` keeps its alignment math for when a phase exists. | Choose a source: the app's own query connection to Carabiner sending `phase-at-time` (Carabiner accepts several clients), a newer or forked beat-carabiner, or a direct Link library. Then choose when to align (when sync turns on, on tempo changes, or regularly). Run it on the Link owner's thread, not the event thread, and use the stored latency setting, which nothing reads today. Decide D3 with it. |

## 4. Smaller leftovers

| P | Item | Evidence | Direction |
|---|---|---|---|
| P2 | **B8b · Cue playback after Undo of a cue move** | B8 (`e2ae1da7`) remaps effect-cell ownership, trigger-held effect switches and the vibe's problem cell on Undo. Cue voices, the playing cues they follow, and trigger keys holding cue cells are still not remapped. While FLASH is held, its list of asked-for cells may include the moved cell until release. | Decide whether Undo of a cue move keeps the cue playing at its restored cell. This touches 03's and 15's playback rules. |
| P3 | **B12b · An Exit re-asked while the unsaved-changes dialog is open is dropped** | B12 (`df0dd11c`) queues a re-checked Open in `events/handlers/menu.clj` `ask-again`; Exit's return path still drops (logged). | Queue it the same way. |

## 5. Left by design (no action)

N9 (`29333866`) measured these whole-grid reads and left them on purpose:
- `vibe-meter`'s effects side: whether a cell is switched on is a real input.
- `vibe-reactions` and `checklist-world`: plan 37 reads the whole grid for these deliberately.
- `effect-ref-inputs`: a cheap single pass whose result stays equal.
- `zone-group-usage`: no subscriber outside tests.
- `vibe-fader-actions`: two short walks, and keeping "first Master" stable needs the map's order.

## Done (2026-11-25)

Each row is one commit with its own regression tests; the tests failed on the base except where a row says otherwise.

Final gates on the merged tree (`7bceb739`):
- **Unit** (`clj -M:mac-arm:test`): 2191 tests, 576,103 assertions, 0 failures.
- **Headless UI** (`clj -M:test-ui:mac-arm:test-ui-headless`): 453 tests, 2,968 assertions, 0 failures.
- **clj-kondo** (`--lint src test --fail-level error`): 0 errors.

| Item | Commit | What changed |
|---|---|---|
| B1 | `8e8c2f38` | A list row selects itself when its context menu is requested; a click whose press opened the menu changes nothing more. |
| B2 | `5ccb2f34` | Projector ids map every character outside `[A-Za-z0-9-]`, so IPv6 hosts give readable ids. Unreadable ids already in memory are renamed at the next scan. |
| B3 | `05e76f6a` | A safety-zone drag sends `:projectors/drag-safety` through 08's proposal and scoped review. Undo/Redo, Copy/Mirror and remove-blocked-area questions are scoped too. |
| B4 | `03179410` | Colour pickers send the picked colour as plain data (`parameter-controls/color-picker`), wrapped in `applied-control`. |
| B5 | `380edfbd` | All Notes Off retires MIDI activations; an OSC restart on a new port retires the old server's holds. |
| B6 | `e3471c5c` | The Modulate toggle addresses its effect by instance. |
| B7 | `1a75392a` | A press on a chased cue stops it for the current episode (`[:playback :chase-stopped]`). A new episode or a second press resumes it. |
| B8 | `e2ae1da7` | Undo of a move onto an occupied cell restores the replaced cell's on/off and owner (snapshots keep `:effect-owners`). See B8b. |
| B9 | `3f48651f` | A superseding rig write carries a pending Try again `:then` (`rig-store/submit!`); the Exit only returns to the same app instance. |
| B10 | `6aad841f` | Engine errors are reported only while the run owns the engine; a new run clears the previous run's error (`output-errors/forget!`). |
| B11 | `0e9aa2a5` | `:chain/*` writes for a removed projector do nothing (`chain-helpers/missing-owner?`). |
| B12 | `df0dd11c` | A stale paste is silently nothing, even under Show Lock (`h/stale-paste?`). A re-checked Open is queued while the dialog is busy. See B12b. |
| B13 | `75419038` | "Listening on port N" shows the port the server reports (`[:backend :input :osc :listening-port]`). |
| B14 | `68a60b4f` | Slider presses carry the effect instance in `:source-id` (`parameter-controls/slider-source-id`). |
| B15 | `f8000c1a` | The canvas installs its scroll handler. |
| B16 | `31ab1a36`, `cea473f4` | Manual-add ids can't collide. `:int` modulator subfields store whole numbers. The third part (an effect running at app retirement finishes its own side effect) is documented behaviour. |
| R1 | `16f3a62c`, `8f950653` | Editors, the projector inspector and the modulator controls read the published registries. |
| R2 | `8afd230d` | `:cue-chain/set-item-target` carries `:item-id`. |
| R3 | `2af0c18f` | The grid content checks, rig-file line and pad label read only what they show. |
| R4 | (findings) | JavaFX viewport-cache images, held softly; no app object survives a start/stop. See H6. |
| R5 | `141b9d9a` | `input/link.clj` owns the Carabiner connection and listener. This also closes the 2026-11-07 review's 02 M4. |
| H3 | `09eaed07` | The device-paced ready test keys on the tick the engine woke for: 1000 runs, 0 failures. |
| H4 | `24e005bd` | The first Linux UI shard runs the dev-mode renderer tests. |
| Wording | `b4055f0c` | Map chip says "the manual map". Docstrings in `cue_chain.clj`, `preset_bank.clj` and `dev/reload.clj` are fixed. `connection-status` lists errors only while streaming, and the status bar hides a rate that could be a retiring engine's (`:retiring?`). |
| N1 | `91be0a23` | `:int` parameter sliders snap to whole numbers (`parameter-controls/int-slider`), and handlers round (`params/param-number`). |
| N2 | `d33e2e13` | Set Color edits address the effect by instance. |
| N3 | `c970d9ee` | The Set Color swatch never throws (`parameter-controls/color-channel`); a modulated channel shows "∿". |
| N4 | `0974fdfe` | The beat read no longer sends Carabiner a grid shift. Downbeat sync does nothing until L1. |
| N5 | `65d92e0c` | A Carabiner drop is reported. Link switches off and its tempo and peers clear. See D4. |
| N6 | `a3bec4c3` | The whole-zone `:projectors/set-safety` is retired; every zone write is scoped. |
| N7 | `d00eac04` | Keyframe and modulator writes for a removed projector do nothing. |
| N8 | `5462ae93` | "Set up rig from show" and "Use the show's" check their Allow with `guard/ask-review :zones`. |
| N9 | `29333866` | `zones-in-use`, `ref-usage`, the Map cycle checks, `vibe-meter` (cue side) and `projector-effect-chain` read per cell. See section 5. |
| N10 | `99d5e284` | Preset editor edits name the cue item: `:cue-chain/update-preset-param`, `…-from-text`, `…-color`. |
| N11 | `78650a43` | The IDN config counts as sent only after the whole frame went out, so it isn't lost in an outage. |
| N12 | `0b84fefe` | The skips-a-tick test is deterministic. |
| N13 | `3e8845c0` | Each CI job has its own dependency-cache key. See H7. |
| N14 | `59aa2f06` | The cue chain editor's item-effects list, bank and effect parameter editor name the item and effect by id. The new `wrap-effect-instance-target` pipeline step resolves the current path before the Show Lock. |
| N15 | `2e1ec8af` | The projector calibration editor's events name the effect by id, with the laser as owner (`projector-effect-address`). UI modes (visual/numeric, curves channel) are kept by effect id (`params/ui-mode-key`). No view sends an `:effect-path`-addressed edit any more. |
| Integration | `70dfa90b` | Marked the unreachable downbeat arithmetic so CI's linter stays green. |
