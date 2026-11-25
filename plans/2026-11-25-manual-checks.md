# Manual checks owed: real hardware, a display, a Link peer

**Status:** Open (each check needs equipment the test suites don't have) · **Written:** 2026-11-25, against `f1f553d9` (`master`)
**Supersedes:** `plans/2026-11-24-reactivity-follow-ups.md`, deleted: everything else on it is done (merges `4c5eae64`, `385b6bac`, `9b0758d9`, `b875b628`) · **Related:** the output runtime's and the zones plan's own hardware checks (H1)

None of these can be proved by the unit or headless UI suites. Each needs a projector and an IDN DAC, a desktop where test windows may open, a GPU, or a second Link peer. Paths are under `src/laser_show/` unless stated otherwise. Priorities: **P1** can aim light wrongly or mislead during a show; **P2** is wrong but recoverable; **P3** is cost or cleanup.

## Checklist

| P | Check | Needs | Done |
|---|---|---|---|
| P1 | [H1 · Projector and DAC](#h1--projector-and-dac-p1) | An IDN DAC with a projector | ☐ |
| P1 | [H5 · Right-click selection](#h5--right-click-selection-on-a-real-display-p1) | macOS and Windows desktops | ☐ |
| P2 | [H8 · Beat alignment with Link](#h8--beat-alignment-with-a-real-link-session-p2) | Carabiner and a Link peer such as Live | ☐ |
| P2 | [H2 · Windowed cell drag](#h2--windowed-cell-drag-test-p2) | A desktop where windows may open | ☐ |
| P3 | [H6 · GPU memory](#h6--gpu-memory-after-startstop-p3) | A real display (a GPU pipeline) | ☐ |

The keyboard plan's macOS window-focus and held-key checks (`plans/2026-11-13-keyboard-focus-window-lifecycle/05` §8) need the same kind of desktop session as H2 and H5, so they're worth doing at the same sitting.

## H1 · Projector and DAC (P1)

**Why:** the output path is tested against a loopback fake DAC only. The repairs changed what is reported and sent on it, and nothing automated proves light reaches a projector.

1. **Frame timing on a DAC:** the "Hardware check, before release" in `plans/2026-11-16-output-runtime/05-phase5-frame-timing.md` §8.
   - Compare a 10-point square with a 3000-point frame: small frames repeat, big ones slow down rather than distort, and the status line's fps matches what is drawn.
   - Log the DAC's acknowledge replies (latency estimate, link quality, error flags).
   - 1000-point frames at 25 000 pts/s: a change shows within about 40 ms.
   - Quit the app, and separately kill the JVM, while streaming: the projector goes dark within 1 s.
   - Record the DAC model and firmware.
2. **Hidden window** (no DAC needed): the "Hidden-window check" in the same §8. Stream for a minute with the app hidden (minimized and covered by a full-screen app on Windows, behind a full-screen app on macOS) and watch each projector's fps on its status line.
3. **Zones on real projectors:** Z1 (nothing lights inside a blocked box; the test pattern and Identify work; it decides `edge-dwell`), Z3 (R-1: two images on one laser don't flicker or look dim), Z4, Z5, Z6 and 11. See `plans/2026-11-19-zones/00-overview.md` §4 and each phase doc.
4. **The IDN config after an outage** (follow-up N11): while streaming, unplug the DAC's network or power-cycle it, then reconnect. The projector shows the stream again, drawn right, without restarting output (`idn/driver.clj` `send-frame!`: a send that fails leaves the channel config due for the next frame).

**If not:** start from the plan section named in each step; for step 4, `send-frame!` and `should-resend-config?`.

## H5 · Right-click selection on a real display (P1)

**Why:** the fix (`views/components/list.clj`, `select-for-menu!`) is defensive. The headless robot can't show how a real platform orders the press, the menu and the click: on macOS and Linux the menu opens on the press and its window can take the release, and on Windows the menu comes after the click.

On macOS and on Windows, in the item list of a Cue Chain Editor (open one from a cue cell on the grid; each row has a Target ▸ menu), with at least two items:

1. Select row A, then right-click row B, which isn't selected. B becomes the selection (highlighted, with its settings in the middle section), and Target ▸ sets B's target, not A's.
2. On macOS, Control-click B instead: the same, and B stays selected rather than toggling off.
3. Select A and B together, then right-click B: both stay selected.

**If not:** `select-for-menu!` and `setup-click-handler!` in `views/components/list.clj`.

## H8 · Beat alignment with a real Link session (P2)

**Why:** "Align beats with Link" is tested against an in-process fake Carabiner only. Real clock domains, network jitter and Carabiner's own answers are unverified.

**Setup:** Carabiner (the app starts its embedded one if none is running) and a Link peer such as Ableton Live with Link on and its metronome playing.

1. **Connect:** Settings → Ableton Link → Connect Carabiner, or click the toolbar's Link button while it says "Carabiner not connected — click to connect".
2. **Follow:** click the toolbar's Link button once to enable Link and again for Sync BPM. Check that "Align beats with Link" is on (Settings → Sync Settings).
3. **Beats:** for a minute, watch the toolbar's beat indicator against Live's metronome. Each beat lands with Live's click.
4. **Latency:** set Settings → Advanced → Latency compensation to +100 ms. The app's beats move about 100 ms ahead of Live's. Set it back to 0.
5. **Tempo change:** change Live's tempo, for example 120 → 128. The app's BPM follows and its beats line up again within about a second.
6. **TAP and BPM locked:** while following, TAP and the BPM field are disabled, and a tap from a key or a mapped MIDI/OSC control changes nothing. Turn Sync BPM off: both work again.
7. **Reconnect** (with a Carabiner you started yourself): quit it. The toolbar shows "Carabiner not connected — click to connect", and Link turns off. Start Carabiner again and click the button: it connects, and steps 2–3 work again.

Bars don't need to line up, only beats (bar sync is parked, below).

**If not:** `input/link.clj` (`check-beat!`, `beat-correction`, `aligning?`), `:timing/align-to-link-beat` in `events/handlers/timing.clj`, and `show-time/global-beats-at` in `animation/show_time.clj`.

## H2 · Windowed cell drag test (P2)

**Why:** only a real window can start a platform drag, so the headless suite skips this test (it logs "Skipping native-cell-drag-clears-on-drop-and-escape-test").

Run, on a machine where windows may open (it moves the mouse, so leave it alone while it runs; `:win` on Windows):

```
clj -M:test-ui:mac-arm -v laser-show.ui-test.grid-test/native-cell-drag-clears-on-drop-and-escape-test
```

It passes with 0 failures.

## H6 · GPU memory after start/stop (P3)

**Why:** each app start/stop cycle leaves about 15 MB of JavaFX ScrollPane viewport-cache images held softly. On the heap they are freed under memory pressure, so they aren't an app leak. On the GPU pipelines they may be textures that heap pressure never frees (OpenJFX JDK-8395377).

On a real display, start and stop the app many times from its REPL (20 or more), showing every tab each time, and watch the app's GPU memory: on Windows, Task Manager → Details, the Dedicated GPU memory column; on macOS, the app's Memory in Activity Monitor. `-Dprism.verbose=true` prints which pipeline is in use. The figure levels off after a few cycles; it doesn't grow by about 15 MB each time.

**If not:** report it against JDK-8395377 with the pipeline and the figures. JavaFX holds those images, not the app.

## Done since the follow-ups list

- **H7 · CI cache keys:** confirmed on CI run 45714179659 (2026-11-25). Each job restored its own key, for example `Linux-clojure-test-ui-dev-…` and `Linux-clojure-test-build-…`.

## Parked (not now)

- **Bar/downbeat sync with Link.** Skipped for now: beat alignment covers what matters. Bar alignment would ask `phase-at-time <µs> 4` on the same query connection, and needs a rule for when to move the bars.

## Decided (no work)

- **Taps and typed BPMs while following Link's tempo** (Link on with Sync BPM): they do nothing, from the TAP button and the BPM field (both disabled then), a tap key, a mapped MIDI/OSC control or HIT. The tempo and the beats are Link's (`events/handlers/timing.clj`, `views/toolbar.clj`).
- **Undo of a cue move while the cue plays:** the cue stops playing. A rare edge case.
- **An Exit queued behind the unsaved-changes question** does nothing if the answer replaced the show (New or Open); a queued Open still opens its file.
- **Whole-grid selectors left on purpose:** `vibe-meter`'s effects side, `vibe-reactions`, `checklist-world`, `effect-ref-inputs`, `zone-group-usage`, `vibe-fader-actions`. They were measured, and narrowing them would need a second copy of their rules.
- **UI tests can't start a real Carabiner:** the shared `with-app` fixture refuses `bc/connect`, and a stand-in with no port opens no query connection. Tests that need Link use the fakes in `test/laser_show/input/fixtures.clj`.
- **`new-tick-goes-out-without-the-minimum-gap-test` is a timing test** (`^:timing`): the normal unit run skips it, `clj -M:mac-arm:test-timing new-tick-goes-out` runs it, and CI doesn't.
