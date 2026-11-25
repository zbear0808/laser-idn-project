# Open items after the cljfx reactivity repairs

**Status:** Open (only checks that need hardware, a display or CI) · **Last verified against:** `followups3/integration`, merged into `master` on 2026-11-25
**Supersedes / Superseded by:** none · **Related findings:** the repair plan and its audit are done and deleted. Git history keeps them, and they landed in merges `4c5eae64` (38 packages), `385b6bac` (follow-ups B1–B16, R1–R5, H3, H4, N1–N15) and the 2026-11-25 merge that added beat alignment, Carabiner reconnect and B12b.

Paths are under `src/laser_show/` unless stated otherwise. Priorities: **P1** can lose work, aim light wrongly or mislead during a show; **P2** is wrong but recoverable; **P3** is cost or cleanup.

## 1. Needs a display, a DAC or CI

| P | Item | What to run | Why it's owed |
|---|---|---|---|
| P1 | **H1 · Real-projector and DAC checks** | Output runtime Phase 5: a real-DAC run and the hidden-window pacing check (`plans/2026-11-16-output-runtime/00-overview.md:10–14`, its `05-phase5-frame-timing.md` §8). Zones R-1 and the Z1, Z3–Z6 projector checks (`plans/2026-11-19-zones/00-overview.md:127–133`). The IDN config must still arrive after an outage (N11, `idn/driver.clj` `send-frame!`). | The repairs changed what's reported or sent on the output path; no test proves physical delivery. |
| P1 | **H5 · Right-click selection on a real display** | On macOS and Windows: select row A, right-click unselected row B, use its menu. B must be the selection the menu acts on. | The fix (`views/components/list.clj`, context-menu selection) is defensive; the headless robot can't show how a real platform orders press, menu and click. |
| P2 | **H8 · Beat alignment with a real Link session** | With Carabiner and another Link peer (e.g. Live), turn Link on with BPM sync and "Align beats with Link". Check that the app's beat indicator lands on the peer's beats, that `:latency-ms` shifts it as expected, and that a tempo change re-aligns within about a second. | The alignment is tested against an in-process fake Carabiner only; real clock domains and jitter are unverified. |
| P2 | **H2 · Windowed drag test** | `clj -M:test-ui:mac-arm -v laser-show.ui-test.grid-test/native-cell-drag-clears-on-drop-and-escape-test` on a machine where windows may open. | Only a window can start a real platform drag. |
| P3 | **H6 · GPU memory after start/stop** | On a real display, repeat many app start/stop cycles with all tabs shown and watch video memory (Activity Monitor, or `-Dprism.verbose=true`). | About 15 MB per cycle of JavaFX ScrollPane viewport-cache images are held softly and freed under heap pressure. On the GPU pipelines they may be textures that heap pressure never frees (OpenJFX JDK-8395377). |
| P3 | **H7 · CI cache keys** | On the first CI run after these merges, the first Linux UI shard's cache step should save `Linux-clojure-test-ui-dev-…`, and the other jobs their own keys. | Per-job cache keys can only be confirmed on GitHub; the first run downloads every dependency once. |

## 2. Parked (not now)

- **Bar/downbeat sync with Link.** You asked to skip it for now; beat alignment covers what matters. Bar alignment would query `phase-at-time <µs> 4` on the same query connection and needs a rule for when to realign bars.

## 3. Accepted behaviour (no action)

- **Taps and typed BPMs while the app follows Link's tempo** (Link on with Sync BPM): they do nothing, from the TAP button and the BPM field (both disabled then), a tap key, a mapped MIDI/OSC control or HIT, as the tempo and the beats are Link's (you, 2026-11-25; `events/handlers/timing.clj`, `views/toolbar.clj`).
- **Undo of a cue move while the cue plays:** it stops playing, a rare edge case (you, 2026-11-25).
- **Exit queued behind the unsaved-changes question:** it does nothing if the answer replaced the show (New/Open); a queued Open still opens its file.
- **Whole-grid selectors left on purpose:** `vibe-meter`'s effects side, `vibe-reactions`, `checklist-world`, `effect-ref-inputs`, `zone-group-usage`, `vibe-fader-actions`. They were measured, and narrowing them would need a second copy of their rules.
- **Soft-held JavaFX images after start/stop:** reclaimable, not an app leak (see H6).
- **UI tests can't start a real Carabiner:** the shared `with-app` fixture refuses `bc/connect` (beat-carabiner can launch its embedded Carabiner). Tests that need Link use the fake in `test/laser_show/input/fixtures.clj`.
