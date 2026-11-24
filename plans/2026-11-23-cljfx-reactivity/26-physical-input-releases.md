# 26 · Physical input releases

**Status:** Implemented in `a0cd589` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24).

No plan page was written before implementation; the design brief the implementer worked from and its hand-off report are recorded here. Paths are relative to `src/laser_show/` unless they say otherwise, and citations refer to the code at the time of writing.

## Design brief

Audit: "A held non-keyboard input can release the wrong action after rebinding" (section "Device and runtime boundaries").

Observed (external-bridges auditor, at a7e414e): only keyboard releases use the recorded activation (events/handlers/triggers.clj `handle-input`: `[:trigger-runtime :keyboard-activations id]`). A MIDI, OSC or gamepad press can activate cue A; clearing its binding while held (events/handlers/input.clj clear-binding handler) means the release clears bookkeeping but never sends A's `:grid/release-cell`; rebinding the same control to B releases B instead (triggers.clj `resolve-action`/`update-runtime`/`run-action`). test/laser_show/events/handlers/triggers_test.clj covers the keyboard case; the MIDI momentary test uses an unchanged mapping.

Required:
- Record the action actually fired per physical input for every source (keyboard keeps working as today): key by the canonical physical identity used for lookup (source + channel + note for MIDI notes; source + id for OSC/gamepad/keyboard triggers — reuse `lookup-key`/`learn-lookup-key` shapes, internal MIDI channels are zero-based). On release, consume that activation and run the release for the ORIGINAL action, regardless of the current binding. A repeated press while held doesn't create a second activation.
- Retire outstanding activations when they can never be released: the device/source is disconnected or disabled (existing `:vibe/release-all :only :input` paths and the keyboard blur release), and on New/Open (`state/persistent.clj` `reset-session` currently clears only keyboard holds via `clear-keyboard-holds`). Retiring must not fire actions into a newly opened show.
- Velocity-0 note-on is a release (pinned in test/laser_show/input/midi_test.clj); OSC press/release values and gamepad button up/down use their existing normalized shapes (test/laser_show/input/fixtures.clj, input/gamepad.clj `button-event`).

Tests (NEW, names adaptable) in laser-show.events.handlers.triggers-test: MIDI momentary press → clear binding → release releases A; rebind to B while held → release releases A, not B; OSC and gamepad equivalents; disconnect/disable retires without firing; New/Open retires holds. Keep the existing keyboard tests and laser-show.ui-test.keyboard-trigger-test passing.

Boundaries: 25 (device worker) landed earlier in this worktree — use its disconnect/disable completion events as the retirement signal where they exist. 24 (learn) comes later; learn-only presses must still never fire actions.

## As implemented

**Changes**
- `events/handlers/triggers.clj`: every source records what a press played, keyed by the physical key (MIDI channel+note, or source+id) — generalizes keyboard's `:keyboard-activations` → `[:trigger-runtime :activations]`. A release ends the original action regardless of the current binding; a release with no recorded press does nothing. A second press while the key still holds something (momentary cell / vibe role) is a repeat (plays nothing); a press after one that held nothing plays normally. NEW `:trigger/retire {:source}`: each hold lets go quietly (momentary cell gets its release; vibe role lets go without switching/locking/advancing); nothing else plays; later release does nothing.
- Retirement triggers (`events/handlers/input.clj`): applied `:input/midi-disconnected` (superseded → nothing), `:input/midi-disable`, `:input/osc-stop-server` (when it stops letting messages in), `:input/osc-disable`, failed OSC restart.
- Gamepad/keyboard: no change needed (gamepad poll sends releases on disable/disappear; keyboard filter on disable/blur/teardown).
- New/Open (`state/persistent.clj` `clear-input-holds`): every source's holds dropped; nothing fires; `end-learn`, `begin-document` kept.
- `events/core.clj`: NEW `:dispatch-n` effect (several follow-ups in order within the same turn).
- `vibes/engine.clj`: `release-all` takes optional `:roles` set.
- `state/domains.clj`: `:activations` doc.

**Deviations**: MIDI disconnect no longer uses blanket `:vibe/release-all :only :input` (cut keyboard/pad-held roles too); `midi-disconnect-releases-input-holds-only-test` presses a real MIDI key and checks a keyboard-held role stays. One retirement covers all MIDI inputs (key identity has no device name). Existing tests updated (release without press now does nothing; quiet presses like zone-map record an activation). A held momentary effect gets saved as switched on (existing behaviour).

**Not done**: All Notes Off (CC 120/123) unchanged (lets go of every role; doesn't retire MIDI activations). Successful OSC restart onto a new port doesn't retire the old server's holds.

**For 24**: learn-only presses never record an activation. MIDI disable, applied MIDI disconnected, OSC stop, OSC disable and failed OSC start handlers use their `:dispatch` slot for `:trigger/retire` — add other follow-ups via `:dispatch-n`, don't overwrite `:dispatch`. When a learn moves a role off a key the role is let go immediately (`release-unbound`); that key's later release does nothing.
**For 09-OSC**: keep the `:trigger/retire` dispatch in `handle-osc-stop-server`.
**For 27**: keep `diff-controller-states` sending releases when the gamepad is disabled or a controller disappears.
**For 10**: a release dispatches the recorded `:grid/release-cell` without re-checking content; a momentary press on an empty cell records an activation, its release does nothing.
