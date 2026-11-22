# State integrity — overview

**Written:** 2026-11-20   **Written for commit:** `3229c95` (`master`)
**Status:** In progress. **01 done** (2026-11-21, `09b6db7`, `dba29bb`; its §11 As built), its E1–E3 answered A, the ★ options; 02 needs W1–W6; **the clipboard step (keyboard plan, Step 4a) done** (2026-11-21, `02ae75c`..`1113d9a`; keyboard 04 §9a), D8, D13 and D14 answered A, B, A   **Last verified against:** 01 at `fe7d554` (2026-11-21, re-checked after the output drivers, zones Z4–Z6, vibe Phase 8, Decimate's follow-ups and the Projectors layout pass; probes e1–e4 run three times there, e1i added); the clipboard step at `a38ea43` (2026-11-21, keyboard probes p5–p7 and p9–p17 re-run there, p18 added for the step grid's clipboard); 02 at `cc9e000` (probes w1–w6 run at `3229c95` and again at `cc9e000`), not re-checked since
**Supersedes / Superseded by:** none   **Related findings:** review 02 M1, #2, #15, #21, #23; review 04 #23, MR7; keyboard K-6…K-11; output runtime O-9

The 2026-11-20 cljfx state audit was done by six read-only auditors. It was reported in the session and isn't saved. It found three ways app state can be wrong for reasons unrelated to any handler's logic:
- **Engine frames read live state.** Each laser's engine reads app state partway through a frame, so a frame can mix two states.
- **Writes outside the event agent.** Some code writes app state from outside the event agent, and an event in flight then overwrites the write.
- **The clipboard does both.** It writes behind the pipeline, reads the OS clipboard while rendering, and makes the event agent wait for the FX thread.

This folder plans the first two. The third already had a plan, so it was updated in place (planning guide rule 11): `plans/2026-11-13-keyboard-focus-window-lifecycle/04-phase4-clipboard.md`.

## Start here

**`01-engine-snapshot.md` is done** (2026-11-21, `09b6db7`, `dba29bb`): every frame comes from one tick's snapshot, and no engine thread reads app state (01 §11). Its follow-ups are in 01 §10: the routing recomputed on every corner-pin drag step, and a device-paced add-on driver checked on hardware.

**Clipboard Step 4a is done** (2026-11-21, `02ae75c`..`1113d9a`; keyboard 04 §9a). A copy writes `[:ui :clipboard]` in its handler's `:state`, the OS clipboard is written and read only by `:clipboard/write` and `:clipboard/read` on the FX thread, nothing reads it while rendering, and Paste follows the clipboard. Keyboard p9 reports no lost update and p10 no wait for the FX thread. It also folded in the step grid's clipboard (zones Z6), found at its re-check.

**Next is `02-direct-state-writes.md`**, in its own commit order (02 §10). 4a landed first, so its guard (commit 9) needs no `[:ui :clipboard]` exemption, and its §1.2 row 13 (`set-internal-clipboard!`) is gone (02, "Dependencies on the sibling plans"). 02 now edits what 4a left in `events/core.clj`: the effects map, the "Writers outside the pipeline" sentence and `effects-and-co-effects-are-shared-maps-test`.

| Doc | What | Size | Needs | Depends on |
|---|---|---|---|---|
| `01-engine-snapshot.md` | Every frame comes from one snapshot | **done** (`09b6db7`, `dba29bb`) | – | nothing |
| `../2026-11-13-keyboard-focus-window-lifecycle/04-phase4-clipboard.md`, Step 4a | One clipboard (and, since the `a38ea43` re-check, the step grid's clipboard folded into it) | **done** (`02ae75c`..`1113d9a`) | – | nothing |
| `02-direct-state-writes.md` | No state writes outside the event pipeline | ~4½ d (~3½ with W2 B and W3 B) | W1–W6 | nothing; commit 9 is better after 4a |

That is about 6¾ days in total (about 5¾ with W2 B and W3 B). Steps 4b and 4c of the clipboard plan (~1 day, D9: types, new ids on paste, one-step Cut) are not part of this batch.

## 1. Finding index

Severities are this overview's own. Evidence is from probes that were run, unless it says "read".

| ID | Finding | Sev | Evidence | Plan |
|---|---|---|---|---|
| SI-1 (**fixed by 01**) | One frame can take its show from tick N and its calibration, safety zone, test pattern, Identify or BLACKOUT from a later state, or even its zone and its calibration from two different states. Two lasers can draw the same tick differently. Each engine reads app state once or twice per frame, besides the snapshot (`backend/multi_engine.clj:223-255`, the provider, which every output driver's engine calls) | H | e1a–e1i, e4 (01 §1.2). A safety narrowing just after a tick lights 0 of 64 points in that tick's frame (e1b); one frame takes its pin and its zone from two states (e1e); Decimate's recount follows the edit, not the tick (e1h), while its meter rule follows the tick (e1i) | 01 |
| SI-2 (**fixed by 4a**) | A list copy writes `[:ui :clipboard]` behind the pipeline (`state/clipboard.clj:31-34`), and the handler's `:state` then undoes it. The Cue Chain Editor's Paste buttons never light, and the projector chain's light only after a re-render | M | keyboard p5, p9, p11 | 04 Step 4a (K-7) |
| SI-3 (**fixed by 4a**) | Every copy and list paste makes the event agent wait for the FX thread (`state/clipboard.clj:64,82`) | M | keyboard p10: 514–535 ms behind a copy and 503–579 ms behind a paste, with the FX thread busy for 500 ms, against 1–2 ms on its own | 04 Step 4a (K-10) |
| SI-4 (**fixed by 4a**) | Two views read the OS clipboard while rendering, and one view copies behind the pipeline (`views/dialogs/effect_chain_editor.clj:95, 144-145`; `views/tabs/projectors.clj:560`) | M | keyboard p11, p12 | 04 Step 4a (K-10, K-11) |
| SI-5 | The Link connection written at start-up (`app.clj:213-220`) can be lost to an event in flight, leaving the app believing Carabiner is disconnected | M | w1 (02 §1.2 row 6) | 02 commit 5 |
| SI-6 | `stop!`'s teardown write is lost, so a second `stop!` disconnects again. Device closing runs inside a `swap!` fn and runs again when the swap retries (`app.clj:237-248`, `:260-267`) | L | w2a, w2b (02 §1.2 rows 7–8) | 02 commit 6 (W4) |
| SI-7 | An old app instance's late event can run alongside the new instance's and undo its write (tests and the REPL restart the app) | L | w6 | 02 commit 2 (W3) |
| SI-8 | A write inside an event's turn survives only if it runs after `:state`. Effects run in the effects map's order, which becomes hash order at nine keys. `:output/write-status` (`events/core.clj:421-425`) is exposed, though no event returns it together with `:state` today | L (latent) | w3 (02 §1.1, §1.5) | 02 commit 1 (W1) |
| SI-9 | UI tests write app state directly while the app's agent runs (69 lines), and four test namespaces copy a "set the BPM and wait" helper | L | read (02 §1.3) | 02 commits 3–4 (W5) |
| SI-10 | Nothing stops a new direct writer. The dev detector (`events/core.clj:124-148`) only logs, and only with `-Dlaser-show.dev=true`, which no test alias sets | M | read (02 §1.1) | 02 commit 9 (W2) |
| SI-11 | Dead or unused writers and helpers: `init-styles!` writes `[:styles :menu-theme]`, which nothing reads (`app.clj:111-119`); `save-project!`, `load-project!` and `new-project!` are used only by tests (`state/persistent.clj:282-308`); `update-in-state!` is documented but doesn't exist | L | read (02 §1.2 rows 5, 9; §1.2 "The primitives") | 02 commits 5, 8 (W6) and 10 (the docstrings) |

## 2. Shared files and merge order

None of the three depends on another, but they share some edits:
- **02 and 4a** both edit three things:
  - the "Writers outside the pipeline" sentence in `events/core.clj` (`:36-41`);
  - the `effects` map (`:505-555`) and its docstring;
  - `effects-and-co-effects-are-shared-maps-test`.

  Whichever lands second edits what the other left (02, "Dependencies on the sibling plans"; 04 §0).
- **02's guard (commit 9) before 4a** carries a `[:ui :clipboard]` exemption in the guard, the kondo config and `guard-lets-the-clipboard-through-test`. 4a then removes all three. With 4a first, there is no exemption.
- **01 and 02** share only `test/laser_show/services/show_clock_test.clj`, in different places. 01's new tests write state without an app, so 02's guard (which needs a running app) and its kondo rule (which exempts `test/`) don't affect them (02, "Dependencies on the sibling plans").
- **4a and keyboard Phase 3** both edit `views/components/list.clj`. Whichever lands second rebases (04 §0).

## 3. Decisions

Each is written out in full, with options, in its doc (★ = recommended). E1–E3, D8, D13 and D14 are answered; the rest are open.

| ID | Question | Doc | Needed before | ★ |
|---|---|---|---|---|
| E1 | What clock times Identify's flash | 01 | 01 | **Answered A** (2026-11-21): the tick's wall clock in the snapshot; each half starts on a tick boundary, at most 16.7 ms late |
| E2 | Where a laser's safety zone is normalized | 01 | 01 | **Answered A** (2026-11-21): per frame as today, from the snapshot |
| E3 | Does the preview get the same consistency | 01 | 01 | **Answered A** (2026-11-21): no change; the thumbnail's outline can lead its frame by up to a tick while you drag |
| D8 | The clipboard's source of truth, and when the OS clipboard is read | 04 | Step 4a | **Answered A** (2026-11-21): `[:ui :clipboard]`; the OS clipboard is read only while this instance hasn't copied anything |
| D13 | When Paste buttons and menu items are enabled | 04 | Step 4a | **Answered B** (2026-11-21): when the clipboard holds something the target takes, or while it is still empty ("Nothing to paste here" on a miss) |
| D14 | How the OS clipboard is read | 04 | Step 4a | **Answered A** (2026-11-21): an effect reads on the FX thread and dispatches the paste back; the agent never waits |
| W1 | How a write inside a turn survives the event's `:state` | 02 | commit 1 | A: `:state` runs first in every event |
| W2 | What keeps direct writers from coming back | 02 | commit 9 | A: a kondo `:discouraged-var` rule in CI, plus a runtime guard (throws in dev mode and tests, logs in production) |
| W3 | One event thread for every app instance | 02 | commit 2 | A: yes |
| W4 | What `stop!` does with device handles in app state | 02 | commit 6 | A: writes nothing; closes each device once |
| W5 | How UI tests and the REPL write state while the app runs | 02 | commits 3–4 | A: `events/write-state!` and `events/flush!` (NEW) |
| W6 | `save-project!`, `load-project!`, `new-project!` | 02 | commit 8 | A: delete them |
| D9 | Clipboard types, conversions, Cut button | 04 | Steps 4b–4c | A (not part of this batch) |

## 4. Probes

The probes print `PROBE` lines describing what the code does today. Each file's header says how to run it. When a plan's work lands, the matching probe becomes a real test that asserts the new behaviour.
- `probes/engine_snapshot_probes_test.clj`: e1–e4, for 01 (e1i added at `fe7d554`).
- `probes/state_write_probes_test.clj`: w1–w6, for 02.
- `plans/2026-11-13-keyboard-focus-window-lifecycle/probes/keyboard_probes_test.clj`: p5–p7 and p9–p17, for the clipboard.

## 5. Not planned here

These audit findings were left out on purpose, and none of them blocks this batch:
- MIDI, OSC and Link callbacks that read live state (01 §10);
- live MIDI and OSC objects and the Link listener held in app state (02 §9; review 02 M4 "Devices");
- the `get-state` / `get-in-state` read API (02 §9);
- subscription granularity;
- the component bugs and untested view and canvas logic.
