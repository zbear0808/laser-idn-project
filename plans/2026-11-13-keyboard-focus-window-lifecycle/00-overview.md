# Keyboard, focus and window lifecycle — overview

**Written:** 2026-11-13   **Written for commit:** `cb32414` (`master`)
**Status:** In progress. Phase 1 done (`93c638a`, 2026-11-14); D0–D3 answered A; D4–D12 open   **Last verified against:** `cb32414` (live probes on JavaFX 28-ea+11, headless; see §3). Phase 1 moved line numbers in `root.clj`, `menu.clj`, `app.clj`, `domains.clj`, `multi_engine.clj` and `events/core.clj`, so citations into those files in docs 02–05 are against `cb32414` and get re-checked before each phase starts
**Supersedes / Superseded by:** supersedes `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md` (approved with D0 on 2026-11-14; that file is marked superseded and otherwise untouched)
**Related findings:** review 01 F3, F4, F7, F18, F20, M5, L3, Q4, Q6; 02 #15, M4 (clipboard part); 05 F17, F9, L3; carry-overs "menu stubs" and "unsaved-changes guard"; undo plan §10

## Start here

**Phase 1 (`01-phase1-unsaved-guard.md`) is done** (`93c638a`, 2026-11-14; results and differences from the plan in its §10). It was the only pending item that stopped real data loss: closing the window, File > Exit, File > New and File > Open discarded unsaved work without asking (`views/root.clj:167-169`, `events/handlers/menu.clj:28,133` at `cb32414`). **Next is Phase 2 (`02-phase2-dialog-shell.md`), which needs D4.**

The CI fix (§1) is in `e87b272`.

| Doc | Phase | Size | Needs |
|---|---|---|---|
| `01-phase1-unsaved-guard.md` | Unsaved-changes guard and an exit path | ~1 day | D1, D2, D3 (answered A). Done, `93c638a` |
| `02-phase2-dialog-shell.md` | One dialog shell | ~1.5 days | D4 |
| `03-phase3-key-handling.md` | Keys reach the right place | ~0.5–1 day | D5, D6, D7 |
| `04-phase4-clipboard.md` | One clipboard | ~2 days | D8, D9 |
| `05-phase5-keyboard-triggers.md` | Keyboard as a cue-trigger source | ~1–1.5 days | D10, D11, D12 (and Phases 2–3) |

About 6–7 days in total, against roughly two weeks for the 2026-10-07 plan as written. Each phase ships on its own.

## 1. Step 0 — CI is red because the workflow file is invalid

GitHub Actions run #3 on `cb32414` ended with **"Invalid workflow file: `.github/workflows/build-and-test.yml#L1` (Line: 94, Col: 14): Unrecognized named-value: 'matrix'"** and ran **zero jobs**. The offending line is `shell: ${{ matrix.shell }}` on the "Run headless UI tests" step (`build-and-test.yml:94`). GitHub's context-availability table lists no contexts for `steps.shell`, but allows `matrix` in `jobs.<job_id>.defaults.run`.

So none of the timeline rework, the TestFX CI matrix or anything since `d0a7dc2` has been checked by CI. Earlier runs also failed: #1 on `d0a7dc2` at "Setup Clojure", #2 on `36e4dec` at "Run unit tests" (13 s, cause unknown). Locally the unit suite is green: `clj -M:mac-arm:test` ran 700 tests, 13,560 assertions, 0 failures.

Fix (move the shell choice to the job, remove it from the step):

```yaml
  ui-tests:
    runs-on: ${{ matrix.os }}
    defaults:
      run:
        shell: ${{ matrix.shell }}
    ...
    - name: Run headless UI tests
      run: clojure '-M:${{ matrix.platform }}:test-ui:test-ui-headless'
```

**Applied 2026-11-14 in `e87b272`:** `shell` moved to `jobs.ui-tests.defaults.run` and removed from the step. After pushing, expect the Linux unit-test failure from run #2 to show up again if it was real; that needs its own look.

## 2. Why this plan first: all pending work, ranked

| # | Item | State (checked) | Size | Verdict |
|---|---|---|---|---|
| 0 | CI workflow | invalid file, §1 | 10 min | first |
| 1 | **This plan** | Phase 1 is a data-loss fix; the rest is verified papercuts (§3, §4) | ~6–7 d | **start here** |
| 2 | Timeline plan 10: panel height + blank-lanes fix | current: the only commits since it was written are docs and the `plorer` dev alias | ~2 h | independent; do any time. Has a real bug (lanes blank after a cell switch) and is a prerequisite for 08 |
| 3 | Timeline plan 08: drag from browser | current; needs a headless drag-and-drop spike first | ~2–2.25 d | next timeline item |
| 4 | Timeline plan 09: effect racks | current; the 07 write-up itself suggests a cheaper option if groups are rare | ~2–2.5 d | last of the timeline items |
| 5 | **Output runtime** (review 02 M1/M7/M8, 03 MR1/MR4/MR5, 04 MR4/MR5/LE1/LE4): **no refined plan yet** | open, verified below | 2–3 weeks | the next big plan; can run in parallel with this one |
| 6 | Smaller output fixes that don't need a plan | open | ~0.5 d each | do any time: calibration failure falls back to the *uncalibrated* frame (`multi_engine.clj:111-116`) |
| 7 | Later: review 05 M3–M6, L1–L4; 01 M2, L1; 03 LE1; 06 leftovers | open | – | after the above |

Done, safe to mark in the review overview: undo/dirty boundary (undo plan), TestFX plan, timeline Phases 1–5, improvement review Phase 0–1 (merged `b384eae`).

**Why this plan beats the others:**
1. **It is the only data-loss fix** (Phase 1), and the dirty flag it needs has been exact since 2026-11-08.
2. **Its old plan document is the one pending plan whose premises are partly wrong.** Probing the running app (§3) shows the "ScrollPane focus trap", the canvas focus gap and "single-key accelerators fire while typing" do not happen. Executing it as written would build a focus context in app state and a `:command/*` router for problems that don't exist. The refined plan is about a third of the size.
3. **It is verifiable now.** The headless TestFX suite (just stabilized) can drive every phase, with no hardware.
4. **It closes a gap the README advertises.** Keyboard input is promised (`README.md:3`, `:51`) and everything after the input router already accepts it; only the producer is missing (Phase 5).
5. **It is disjoint from the output code**, so it can run in parallel with the output runtime plan, and it has little overlap with timeline plans 08–10. Shared files to watch: `domains.clj`, `events/core.clj` (effect map), `app.clj` (`stop!`), `list.clj`, `canvas_interaction.clj`.

**Why not the output runtime plan first.** Its findings are real (verified):
- cue and global timing advance only on the 30 Hz preview timer (`frame_service.clj:805-831`, started at `app.clj:141`), and the timer does two context swaps per tick;
- engine start, stop and refresh are separate unsynchronized futures (`events/core.clj:337,353,367`), and engine handles live in app state (`multi_engine.clj:289,312,371`) beside two different `:running?` flags;
- a failing calibration effect falls back to the uncalibrated frame (`multi_engine.clj:111-116`); `u/clamp` lets NaN through (`util.clj:13-27`) and a NaN coordinate encodes as the centre of the scan field (`output_config.clj:99-111`); the first point of a frame is never forced dark; a projector's `:scan-rate` is stored and never read; there is no master output-off;
- nothing stops the engines, the preview timer or the Carabiner link on exit (`app.clj:186-199`, no shutdown hook anywhere).

But the blast radius is smaller than it first reads. When the transport is stopped the frame provider returns nil and the engine streams empty frames (`multi_engine.clj:156-159`, `streaming_engine.clj:219`), so an orphaned engine cannot keep a beam on by itself. Almost none of it can be changed safely without a fake-DAC test harness first (only a 2-test loopback exists, `streaming_engine_test.clj:28-71`), and some of it needs hardware to confirm. It deserves its own plan with its own decisions (where runtime state lives, the executor policy, the failure policy for calibration, whether to build a single frame clock). I can write that next in the same format.

**What would change this recommendation:** if a show is imminent, do the calibration fix and the Stop/Start race first; if the timeline editor is the priority, do plan 10 (2 hours) now and 08 next, and still do Phase 1 of this plan in between.

## 3. What the running app does today (probes)

Run by `probes/keyboard_probes_test.clj` (headless TestFX, JavaFX 28-ea+11, master `cb32414`). The file's header has the command.

| Probe | Action | Result |
|---|---|---|
| p1 | focus `#main-layout`, Space | `playing? true` (the menu accelerator fires) |
| p2 | focus `#bpm-input`; Space, R (while playing), Delete (cell selected) | nothing fires; the text field consumes the key before the scene sees it |
| p3 | click a cue-list row; Shortcut+A; Delete | focus owner is the list-editor `VBox`; 3 of 3 selected; items 3 → 0 |
| p4 | focus the list's "New" toolbar button; Delete | **ignored** (3 → 3) |
| p5 | list Ctrl/Cmd+C, then Ctrl/Cmd+V | `[:ui :clipboard]` stays `nil`; the OS clipboard holds `{:type :cue-chain-items`; toolbar Paste buttons stay disabled (`[true true]`); the keyboard paste works (3 → 4) |
| p6 | `:grid/copy-cell`, then list paste | nothing pasted (1 → 1) |
| p7 | copy a cue item, `:list/paste-items` into effect chain `[0 0]` | the effect chain now holds `{:preset-id :circle :type :preset}` |
| p8 | click TAP, Space | the key is consumed by the focused button; the scene's second pass sees neither press nor release |

**Claims of the 2026-10-07 plan that no longer hold** (full table in `03-phase3-key-handling.md` §1): ScrollPane focus trap (p3); canvas focus gap (fixed, `canvas_interaction.clj:185-192`); single-key accelerators firing while typing (p2); timeline Delete (fixed); Ctrl vs Cmd (fixed, `list.clj:729`). **Still true:** child controls steal focus from the list (p4); the global props atom (`list.clj:57`); fragmented clipboard types and non-atomic cut (p5–p7).

## 4. Finding index

| ID | Finding | Sev | Evidence | Phase |
|---|---|---|---|---|
| K-1 | Window close, File > Exit, New and Open discard unsaved work without asking | H | `root.clj:167-169`, `menu.clj:28,133` | 1 (done, `93c638a`) |
| K-2 | In dev mode, closing the main window kills the REPL JVM | M | `root.clj:169` vs `app.clj:130-132` | 1 (done, `93c638a`) |
| K-3 | Exit stops no engines, no preview timer, no Carabiner; no IDN close is sent | L | `app.clj:186-199`; `link/disconnect-carabiner!` has no callers | 1 (done, `93c638a`) |
| K-4 | List shortcuts die when focus moves to a toolbar button or checkbox inside the list | M | `list.clj:733,748`; p4 | 3 |
| K-5 | Global props atom: two writers, never cleaned, `:ordered-ids` can be missing | L | `list.clj:57,603,865` | 3 |
| K-6 | Cut is two events; a failed copy is swallowed and the delete still runs | M | `list.clj:761-765`, `clipboard.clj:71-73` | 4 |
| K-7 | List copy never reaches `[:ui :clipboard]`; toolbar Paste is permanently disabled | M | p5; `clipboard.clj:34` vs `list.clj:247-248` | 4 |
| K-8 | Any list pastes any list's items (cue items into an effect list) | H (data) | p7; `list.clj:861` never reaches the sidebar | 4 |
| K-9 | Cell copy and list paste don't interoperate; cell paste keeps ids | L | p6; `helpers.clj:90-95` | 4 |
| K-10 | Two views read the OS clipboard during render | M | `effect_chain_editor.clj:95`, `projectors.clj:613` | 4 |
| K-11 | Dead clipboard code (callbacks, effects, two TODO stubs, dialog-local slots) | L | `list.clj:592,862,901`; `core.clj:450-452` | 4 |
| K-12 | Six dialogs copy their shell; add-projector never closes; `:editing-name?` never reset; two dialogs lack defaults | M | `02-…` §1 | 2 |
| K-13 | Keyboard is advertised as an input source but nothing produces keyboard events | M | `README.md:3,51`; survey | 5 |
| K-14 | Menu items that do nothing: About, Export, Toggle Preview, Fullscreen, Check for Updates | L | `menu.clj:122,220,227,253`; `251` | 2 |
| K-15 | Learning a key (or note) also fires its previous binding | L | `input.clj:232-238`, `input_mapper.clj:85` | 5 |

## 5. Target architecture (recommended defaults)

```
close / File>Exit / New / Open ─▶ guard (pure) ─▶ confirm dialog (app state) ─▶ Save→continue | Discard | Cancel   [Phase 1]

key press ─▶ scene FILTER: keyboard triggers, bound keys only  [Phase 5]
          ─▶ focused component (list, canvas, text field) — JavaFX gives it priority  [Phase 3]
          ─▶ scene HANDLER: undo/redo, dialog extras  [Phase 2]
          ─▶ MenuItem accelerators (main window)

copy / cut ─▶ handler writes [:ui :clipboard] ─▶ :clipboard/write effect (FX thread)     [Phase 4]
paste      ─▶ slot, else :clipboard/read effect ─▶ compatible-type check ─▶ fresh ids

every dialog ─▶ dialog-stage shell (stylesheets, close, key handler, trigger filter) + one registry in root.clj   [Phase 2]
```

No focus context in app state, no `:command/*` router, no shortcut registry. Everything new in app state is `[:ui :dialogs :unsaved-confirm]` and `[:backend :input :keyboard :enabled?]`.

## 6. Phases and dependencies

| Phase | Goal | Depends on |
|---|---|---|
| 1 | Never lose work; one exit path | CI fixed (§1) |
| 2 | One dialog shell; fix the dialog bugs | none. Phase 1's confirm dialog can use it, or a plain stage first |
| 3 | List shortcuts survive focus moves; one typing guard; main-window focus hygiene | Phase 2 for the shared scene handler |
| 4 | One clipboard; atomic cut; Paste button works; type safety | Phase 3 (D7 decides how `:clipboard-type` reaches the list) |
| 5 | Keyboard cue triggers | Phases 2 and 3 |

Phases 1 and 2 are independent and can run in parallel; 3 follows 2; 4 and 5 can run in parallel after 3.

## 7. Decisions

Each decision is written out in full, with options and a recommendation, in the doc that needs it. Reply with letters (for example `D0: A, D1: A, D2: A, D3: A`) or with your own wording. Anything you don't answer, I'll ask again before that phase starts.

| ID | Question | Doc | Needed before | Recommended |
|---|---|---|---|---|
| D0 | Which plan goes first | this file | now | **A, answered 2026-11-14**: this plan, with CI fixed first |
| D1 | How is the confirm dialog shown | 01 | Phase 1 | **A, answered 2026-11-14**: data-driven stage in app state |
| D2 | What "Save" in the dialog does | 01 | Phase 1 | **A, answered 2026-11-14**: save, then continue once the write succeeded |
| D3 | Dev-mode close/exit and exit cleanup | 01 | Phase 1 | **A, answered 2026-11-14**: one `app/stop!` path; dev keeps the JVM |
| D4 | How much of the dialog shell to extract (+ menu stubs) | 02 | Phase 2 | A: helpers + stage shell + root registry |
| D5 | Focus model | 03 | Phase 3 | A: JavaFX event flow, no new state |
| D6 | Main-window buttons vs single-key shortcuts | 03 | Phase 3 | A: toolbar and tab buttons not focus-traversable |
| D7 | Where list keyboard commands get their props | 03 | Phase 3 | A: keep the atom, make it correct |
| D8 | Clipboard source of truth and OS reads | 04 | Phase 4 | A: internal slot first, OS read only when empty |
| D9 | Clipboard types, conversions, Cut button | 04 | Phase 4 | A: strict types + cell→list conversion + Cut |
| D10 | Trigger vs built-in shortcut collisions | 05 | Phase 5 | A: user binding wins, Escape reserved |
| D11 | Where keyboard triggers work; master switch | 05 | Phase 5 | A: main window + editor dialogs, switch in Settings |
| D12 | Key identity | 05 | Phase 5 | A: bare `KeyCode` name, Shift ignored |

> **DECISION D0 — Which plan goes first** *(answered A, 2026-11-14)*
>
> | | What |
> |---|---|
> | **A (recommended)** | This plan, starting with Phase 1; fix CI first; plan 10 (2 h) whenever convenient |
> | B | Timeline follow-ups first (10, then 08, then 09); this plan afterwards |
> | C | Output runtime first. I would write its plan docs next, in this same format |
>
> **Your answer:** A (approved 2026-11-14)

## 8. Ownership with other plans

| Item | This plan does | Stays with |
|---|---|---|
| Review 01 F18 / M5 (dialog scaffolding), F19 dialog dead code | Phase 2 | – |
| Review 01 L3 (unsaved guard, window lifecycle), F20 | Phases 1 and 3 (F20's text-field claim is retired by p2) | – |
| Review 01 F4 / Q4 (props atom) | Phase 3, D7 A | 01 M6 / 02 M5 (list memoization, atomic list ops) if D7 C |
| Review 01 F7 / Q6, 02 #15 (clipboard) | Phase 4 | – |
| Review 05 F17 / L3 (keyboard source), F9 (learn fires old binding) | Phase 5 | the rest of 05 F9 (learn threads), already reworked by the undo plan |
| Menu stubs (carry-overs in 01, 02, 06) | listed in Phase 2 with defaults | – |
| Output lifecycle, timing, safety (02 M1/M8, 03, 04) | nothing | the output runtime plan |

## 9. Out of scope

Autosave and crash recovery; global (application-unfocused) hotkeys; user-configurable built-in shortcuts; chords; the output runtime; list rendering memoization (review 01 F17 / M6); CSS tokenization (01 L1).

## 10. Re-running the probes

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
P=plans/2026-11-13-keyboard-focus-window-lifecycle
clj -Sdeps "{:aliases {:probe {:extra-paths [\"$P\"]}}}" \
    -M:test-ui:mac-arm:test-ui-headless:probe -d "$P/probes" \
    -n probes.keyboard-probes-test 2>&1 | grep PROBE
```

Use `:win`, `:linux` or `:mac` in place of `:mac-arm` on other platforms. When a phase fixes a behaviour, turn the matching probe into a real test with an assertion on the new behaviour.
