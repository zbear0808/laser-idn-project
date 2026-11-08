# Improvement Review — Overview (2026-11-07)

Six parallel reviews, one per area. Each area plan has findings with file:line references, tiered fixes, effort/risk, and dependencies.

| # | Area | Plan | Findings |
|---|------|------|----------|
| 01 | Views / UI / CSS | [01-views-ui.md](01-views-ui.md) | 26 |
| 02 | Events / state | [02-events-state.md](02-events-state.md) | 28 |
| 03 | Animation / effects | [03-animation-effects.md](03-animation-effects.md) | 25 |
| 04 | Output pipeline (IDN / backend / routing) | [04-output-pipeline.md](04-output-pipeline.md) | 28 |
| 05 | Input / triggers | [05-input-triggers.md](05-input-triggers.md) | 21 |
| 06 | Testing / build / tooling | [06-testing-build-tooling.md](06-testing-build-tooling.md) | 18 |
| 07 | Audit of old plans (2026-11-08) | [07-old-plans-audit.md](07-old-plans-audit.md) | 21 plans |

> 2026-11-08:
> - The old plans were audited. All were deleted except `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md` (amended, Phase 2). Their open items were added to the end of 01, 02, 04, 05 and 06.
> - `.agents/rules/planning-guide.md` was rewritten using the audit's lessons.
> - `/plans` is un-ignored on branch `improve/p0-tooling`.

---

## Cross-cutting themes

These came up in more than one review and are best fixed once, centrally.

### T1. Handlers return stale state, clobbering concurrent writes
- File > Save loses `:file-path`/clean flag (02); Reload Styles trigger overwritten (01).
- 30 Hz preview timer, engine threads, clipboard, and MIDI/OSC learn threads write to the app atom outside the event flow, and their writes get overwritten (02, 05, 04).
- **Fix:** handlers return pure state transitions only; side effects become registered effects. Add a dev-mode warning when the atom changed underneath a handler. Move high-frequency/runtime data (playback ticks, engine handles, device handles, sockets) into separate runtime atoms, as `docs/CLJFX_STATE_MANAGEMENT.md` already recommends.

### T2. Key-name mismatches silently disable features
- `:enabled` vs `:enabled?` breaks OSC enable, MIDI disable (02, 05) and the blackout effect (03).
- Decay modulator reads `:decay-type`, which nothing writes (03). Preview reads `:fps`, which frame stats never set (01). Presets reference the non-existent `:offset` effect (03).
- **Fix:** fix each one, then add specs/schemas for settings and effect params, plus a "every param changes output" test (03) so new mismatches fail CI.

### T3. Live performance actions flood undo/history and the event queue
- Tap tempo, Link BPM, MIDI effect toggles, keyframe selection, and collapse state all create undo entries (02, 05).
- Blocking I/O (zip, device open, clipboard) runs on the same queue as live triggers (02); trigger presses go through the queue twice (05).
- **Fix:** an explicit "document" boundary for undo/dirty tracking; events opt out of history; I/O moves into effects.

### T4. Timing: no single clock
- Cue timing mixes relative and absolute time, so once-mode modulators are stuck at 0 (03).
- Cue timing advances at 30 Hz (preview timer) while hardware streams at 60 Hz. `Thread/sleep` pacing jitters; `:scan-rate` is ignored; wall-clock ms timestamps are used (04).
- Input timestamps are overwritten by queue time, which adds tap-tempo jitter (02, 05). Link downbeat sync uses the wrong clock (05).
- **Fix:** one monotonic frame-clock thread (nanoTime) that renders once per tick, feeds every projector and the preview, and passes a single relative timing context down the effect chain.

### T5. Over-broad subscriptions and hot-path allocation
- Grid cells subscribe to whole `active-cues`/`chains` maps and recompute at 30 Hz; the timeline dialog re-renders whole each tick (01, 02).
- Preview draw loop allocates per point (01). Effect chains clamp per effect and rebuild lookup tables per frame (03). The output path has per-packet DNS lookups, exceptions used for control flow, and eager `format` strings in logging (04).
- **Fix:** narrow subscriptions first (cheap). Then a benchmark harness, then primitive loops / flat arrays shared between output and preview.

### T6. Tests don't reflect real inputs, and the suite may not run
- `clj -M:test` likely fails at load (UI test namespaces on the classpath without TestFX) (06). 5 OSC tests already fail (05).
- MIDI/OSC test fixtures are hand-built and shaped differently from what the libraries actually send (05). TestFX tests assert little (06). There are no tests for `subs.clj` or the UDP send loop (06, 04).
- **Fix:** repair the aliases first, then fixtures captured from real input, a fake-DAC UDP harness, and an effect-param coverage test.

### T7. Dev-only code reachable in release builds
- Help > Reload Styles / Reload App Code require `tools.namespace` (dev-only) and throw in packaged builds (01, 06).
- Unused code: the `cljfx/` override dir (01), the unused `triggers/` engine (05), and `legacy/` and experiments (06).

---

## Top priorities (ranked)

### Phase 0 — Make the safety net trustworthy (≈1–2 days)
1. Repair test aliases and CI; confirm with `clj -M:win:test`. **(06)**
2. Un-ignore `/plans` (or move the plans elsewhere). **(06)**

### Phase 1 — Quick correctness wins (mostly one-liners, ≈2–4 days)
3. **Output:** `unchecked-short` + 0xFF00 cap for packet size (frames > ~3–4k points currently never send); signed 8-bit XY. **(04)**
4. **Input:** MIDI read `:command` (channels 2–16 are dropped); unmapped OSC addresses become triggers again; momentary release; gamepad lookup/release/router. **(05)**
5. **Key mismatches (T2):** `:enabled?`, blackout, decay, `:fps`, preset `:offset` → `:translate`, boolean params store `false` instead of `nil`. **(01, 02, 03, 05)**
6. **Events:** File > Save/Open/New; register missing effects (Exit, Docs, learn); route `:trigger-group/*`. **(02, 05)**
7. **UI:** tap-tempo button, right-click multi-select, timeline Delete. **(01)**
8. **Security:** `read-string` → `clojure.edn` on drag payloads. **(01)**
9. **Release:** hide dev-only reload menu items outside dev. **(01, 06)**
10. **Effects:** random modulator hash, `:seconds` in loop mode. **(03)**

### Phase 2 — Structural fixes (≈1–3 weeks)
11. **T1:** state-write discipline plus separate runtime atoms for engines, devices and playback. **(02, 04, 05)**
12. **T4:** unified frame clock and relative timing context. **(03, 04, 05)**
13. Serialise the engine lifecycle on one executor; report health to the UI. **(04)**
14. **T3:** undo/dirty-tracking boundary; rebuild learn as an event flow. **(02, 05)**
15. **T5 (cheap part):** narrow subscriptions for grid cells, the timeline and chain dialogs. **(01, 02)**
16. Clamp once at the end of the effect chain, NaN → blank; calibration effects fail closed. **(03, 04)**

### Phase 3 — Performance and polish (ongoing)
17. Benchmark harness (1,000-point, 6-effect chain), then a primitive preview loop and flat point arrays. **(01, 03, 04)**
18. Test coverage: `subs.clj`, effect params, real-shape input fixtures, a fake-DAC UDP harness. **(03, 04, 05, 06)**
19. CSS cleanup: hard-coded colours, unused classes, re-sync `CSS_COLOR_SYSTEM.md`. **(01)**
20. Release packaging: jpackage input, consistent version, re-enable the release upload step. **(06)**
21. Delete or adopt: the `cljfx/` overrides, the `triggers/` engine, `legacy/`, and stale plans (layered-effects v3/TODO, old persistence/unsaved-guard). **(01, 03, 05, 06)**

---

## Dependency notes
- Phase 0 comes first so every later fix lands with a test.
- T1 (runtime atoms) unblocks T4 (frame clock) and the engine lifecycle work (#13), and makes learn (#14) straightforward.
- T4 must agree on one timing-context shape between 03 (effects) and 04 (output) before either side changes.
- Flat point arrays (#17) change the contract between output (04) and preview (01); do them together.
- Ownership: effects (03) own NaN handling and clamping; output (04) owns the NaN failsafe and frame structure; calibration fail-closed is shared.

## Open decisions
- Keep `/plans` in git?
- `triggers/` engine: adopt or delete?
- Keyboard input source: fold into `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md`?
- Sequel-fragment chunk header (04): confirm against a reference receiver before changing.
