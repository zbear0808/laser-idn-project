# cljfx reactivity repairs

**Status:** Implemented on branch `reactivity/integration` (2026-11-24); not yet merged to `master`. See [Implementation status](#implementation-status-2026-11-24). · **Planned against:** `7b320b3` · **Audit baseline:** `a7e414e` · **Related findings:** [reactivity audit](../2026-11-23-cljfx-reactivity-audit.md), [state-outcome probes](../probes/cljfx_state_outcomes_2026_11_23.clj), [render/cache probes](../probes/cljfx_reactivity_2026_11_23.clj).

**Supersedes / superseded by:** implements the audit's recommendations; it does not supersede the completed keyboard, zone or single-writer work. Page numbers refer to this folder unless another plan is named.

The plan preserves the one context atom, the single event writer and the separate frame/input runtime. [The app-specific cljfx guide](../../docs/CLJFX_STATE_MANAGEMENT.md#reactive-state-in-this-app) is the design reference. The audit documents what was observed. The numbered pages below were written as implementation work before the repairs. Pages 01, 04, 09, 14, 17, 19–22, 25, 26, 34 and 36 were never written; their implementation followed design briefs derived from the audit.

## Implementation status (2026-11-24)

Each package landed as one commit (09 and 13 as two). Each has regression tests that fail on the base behaviour, except where a row says otherwise. The lanes were merged into `reactivity/integration`; two integration fixes followed: `c08ca5a` (a dropped effect registration and a duplicated test helper) and `8ea4862` (10's and 26's momentary-press tests reconciled).

**Final gates on the merged tree:**
- **Unit** (`clj -M:mac-arm:test`): 2107 tests and 574,898 assertions. One timing failure appeared once in about ten full runs, `streaming-engine-test/device-paced-driver-waits-for-ready-test`. Three targeted re-runs passed. The engine's readiness code is unchanged; the test's ready predicate reads the global latest tick.
- **Headless UI** (`clj -M:test-ui:mac-arm:test-ui-headless`): 426 tests, 2,789 assertions, 0 failures.

| Pkg | Commit | Remaining risk or follow-up |
|---|---|---|
| 01 | `225daa5` | The colour picker still sends a JavaFX event. `:int` modulator subfields (e.g. Steps) store decimals (existing). |
| 02 | `0c28982` | Projector keywords built from an IPv6 host aren't readable EDN, so Save refuses that show (existing). An atomic replace turns a symlinked show into a regular file. On a filesystem that can't rename atomically, Save fails (the decision below). |
| 03 | `b699583` | — |
| 04 | `517b96e` | — |
| 05 | `b5e9866` | — |
| 06 | `9c1e1f1` | — |
| 07 | `2e5703a` | The Modulate toggle still addresses its effect by `:effect-path`. |
| 08 | `26350ca` | Canvas drags still use the whole-zone question. |
| 09 | `9551660` (Add), `3cbe86d` (OSC) | An out-of-range IDN port becomes 7255 rather than being refused. A programmatic port change while OSC is listening shows the new port before any restart. |
| 10 | `6bbc308` | Double-click, right-click and drag still use the rendered content flag. |
| 11 | `b222a39` | — |
| 12 | `50567aa` | — |
| 13 | `6629d64` (13a), `1f65567` (13b) | The colour picker and the transform-clamp box aren't wrapped. |
| 14 | `a15687b` | Generic `:chain/*` writers can still create an orphan calibration chain for a missing projector. |
| 15 | `6bfb56e` | Toggling off a cue that follows running timecode is undone by the clock's next On (existing). |
| 16 | `b2d41fd` | A slider re-rendered for another control under the same label keeps its press. |
| 17 | `3e2bd4a5` | Modulator controls, the calibration list and the chain editors' `effects/get-effect` calls still read the registries at render time; they refresh on code reload. |
| 18 | `ef8393e` | Real-hardware checks are still owed: Z3 R-1, the Phase 5 real-DAC run and the hidden-window pacing check. |
| 19 | `39349c9` | An effect already running when its app retires still finishes its own side effect. Only the continuation is dropped. |
| 20 | `3f82e19` | Under Show Lock, a returning paste still shows the lock's refusal notice. An Open re-checked while the unsaved-changes dialog is already open is dropped (logged). |
| 21 | `fab89cb` | A Try again write superseded by autosave loses its `:then` (existing). With a stuck write, quitting takes up to about 4 s. |
| 22 | `f04c7ef` | A move that replaced a cell leaves both cells vibe-owned after Undo. Trigger-held switches and cue voices aren't remapped. |
| 23 | `1367ecc` | The rig-file status line subscribes to the whole `:rig-file` map. |
| 24 | `0fe3f454` | — |
| 25 | `797d44e` | The Link listener is still in app state, and Carabiner is still closed from a state read. |
| 26 | `a0cd589` | All Notes Off doesn't retire MIDI activations. An OSC restart on a new port doesn't retire the old server's holds. |
| 27 | `d1fd0813` | — |
| 28 | `0c962ed` | — |
| 29 | `153b648` | The pad chip tooltip still says "in use". |
| 30 | `5d36428` | Errors still show in the tooltip while Stopping. |
| 31 | `db90de8` | A CI step running the `:dev` renderer tests isn't added yet. |
| 32 | `c1cfa68` | A possible right-click selection bug in lists, unverified on a real display. |
| 33 | `b923ddd` | Existing bug: in `canvas_interaction.clj`, `:on-scroll` sits inside `:on-key-pressed` and is never installed. It is latent because nothing passes `:on-scroll`. |
| 34 | `e5e0096d` | The cue item Target menu still sends `:item-path`, so moved items' menus re-render on reorder. |
| 35 | `780f41a` | — |
| 36 | `3b9f6921` | The LRU is 4096 entries. Stale descriptions are retained up to that size, about 16 KB per grid cell: 48 MB after 3000 renames, against 19 MB at 2048. 2048 also gave zero unrelated view runs, with only 1.98× headroom. |
| 37 | `8315b073` | — |
| 38 | `7f3849ca` | The windowed real-drag test (`grid-test/native-cell-drag-clears-on-drop-and-escape-test`) needs a display and wasn't run. |

The decisions below were implemented with their proposed defaults: 02 fails instead of risking the last good file, 13 keeps controls interactive and restores the applied value, and 36's capacity came from measurements. Each is still open for confirmation.

## Start here

**Start [01: numeric commits](01-numeric-commits.md) and [02: readable show files](02-readable-show-files.md) together.** 01 prevents a normal Enter key from putting a JavaFX object in the document; 02 prevents an unreadable show from being treated as a successful Open. Either fix alone leaves a path to data loss. [03: Stop](03-stop-and-cue-completion.md) and [04: list retargeting](04-list-target-lifetimes.md) are independent and can start at the same time. [05: Step Grid identity](05-step-grid-target.md), [12: audience indication](12-audience-indicators.md) and [13: refused rig controls](13-refused-control-feedback.md) are the next operator-facing fixes.

**After the first tranche:** prioritize late work that can discard or mutate a show ([19](19-app-and-scan-generations.md)–[21](21-rig-write-drain.md)) and changes that can go to the wrong undo step ([16](16-gesture-undo-boundaries.md), [22](22-vibe-ownership-undo.md), [23](23-rig-group-definitions.md)). Take the cache **baseline** early, but don't tune it ahead of the correctness and retention fixes. The [implementation lanes](#implementation-lanes-and-merge-order) below identify the shared files and interfaces when splitting the remaining work.

**Rechecked while writing this plan:** both saved diagnostics ran in fresh headless JVMs on macOS arm64 at `7b320b3`. The newer commit makes *toggling* Modulate one undoable edit; its [focused headless regression](../../test/laser_show/ui_test/modulator_editor_test.clj#L63-L82) passed (one test, two assertions). It does not fix typed Enter. The [state-outcome probe](../probes/cljfx_state_outcomes_2026_11_23.clj#L43-L90) still produced all three results below. The [cache probe](../probes/cljfx_reactivity_2026_11_23.clj#L73-L103) also reproduced the Grid counts. Other evidence is labeled separately in the individual pages.

| Interaction | Observed now | Required outcome |
|---|---|---|
| Enter in an inactive numeric modulator → save → open | Stored `ActionEvent`; save returned true; one cue reopened as zero, marked clean | A numeric edit round-trips; a bad required archive entry leaves the current show intact |
| Two cue intents → Stop → one completion | `:playing?` went `false → true` without Play | Ignore an end from before the restart. A current end can remove its run; neither can restart transport |
| Open list A, retarget to B, press Delete with B focused | Item counts `[A B]` went `[1 1] → [0 1]` | `[1 0]`; B's action cannot delete A's item |
| Starter Grid, five unrelated database updates | 512 cache entries: `144,149,149,149,149` view calls; comparison at 4,096: `91,0,0,0,0` | Repeatable selective rendering after warm-up; choose capacity *after* fixing retention. These are function calls, not frame rate |

## Finding index

IDs are stable work packages, **not** a mandatory 38-step sequence. Each page records current behavior, the intended change and its acceptance tests. Closely related manifestations with the same fix are named together; each finding has one implementation owner.

### Protect documents, controls and transport

| Page | Finding covered | Acceptance in one line |
|---|---|---|
| [01 · Numeric commits](01-numeric-commits.md) | Inactive-modulator and keyframed numeric Enter write a native event | A real Enter stores a validated value; a slider still works |
| [02 · Readable show files](02-readable-show-files.md) | Save accepts unreadable data; failed content parses as an empty clean show | Preserve the last good file and the current document on failure |
| [03 · Stop and completion](03-stop-and-cue-completion.md) | A cue ending restarts a stopped transport; stale end after seek/restart | Only the matching completed run is removed; Stop remains authoritative |
| [04 · List callback lifetime](04-list-target-lifetimes.md) | Delete/shortcuts or a nested background drop target the old list; reopening can lose handlers | Retarget and reopen with the actual native callbacks installed |
| [05 · Step Grid target](05-step-grid-target.md) | Non-modal editor follows a vector slot to the wrong pattern | Reorder follows the original instance; deletion cannot edit its replacement |
| [06 · Keyframe controls](06-keyframe-control-dispatch.md) | Zone/value and interpolation listeners return a map but dispatch nothing | A native selection updates the intended keyframe once |
| [07 · Modulator field patches](07-modulator-field-patches.md) | Each subfield replaces other changes from a stale `:current-value` | Two edits from one render both survive |
| [08 · Zone coordinate edits](08-zone-coordinate-edits.md) | Editing a coordinate replaces the rendered zone | Update only the intended coordinate against the current validated zone; existing guards remain effective |
| [09 · Form submission](09-manual-form-submit.md) | Manual Add/OSC Start use outdated form values | Submit the current open form once; a closed form cannot add a second projector |
| [10 · Grid click intent](10-grid-click-intent.md) | Queued cue click trusts the last render's content flag | Clearing/filling a cell before the click is handled changes the decision correctly |
| [11 · Stored effect occupancy](11-stored-effect-occupancy.md) | Disabled items/groups look absent; drag, creation menu and pattern pads disagree | Distinguish stored, enabled and eligible-for-creation without changing playback |
| [12 · Audience indication](12-audience-indicators.md) | Pattern through a custom group loses its AUDIENCE badge/quiet calculation | Pad, picker and full-state classification agree on the same data |
| [13 · Refused controls](13-refused-control-feedback.md) | Native checkbox can say off while the locked model remains on | After refusal the control shows the applied value |
| [14 · Projector selection after Undo](14-projector-selection-after-undo.md) | Missing selected projector becomes an editable phantom | Add → Undo → stale edit leaves no record |
| [15 · Follow Timecode cleanup](15-timecode-chase-lifetime.md) | Disabling chase or replacing its cue leaves an old render run | No orphaned or duplicate run; an old cleanup cannot remove a newer manual run |
| [16 · Gesture Undo](16-gesture-undo-boundaries.md) | Closing during paint leaves a transaction; background beats keep it alive | Close/blur/abandonment settles only that gesture; the next edit has its own undo step |

### Publish the right state and retire old work

| Page | Finding covered | Acceptance in one line |
|---|---|---|
| [17 · Registry descriptors](17-registry-descriptors.md) | Count-only effect bank and untracked modulator registry use obsolete definitions | Re-registering the same ID updates presentation and the default actually applied |
| [18 · Output thumbnails](18-output-thumbnail.md) | Attempted frames are labeled sent; separate output freshness is hidden by the tick gate | Rejected, empty, stale and fresh driver results are distinguishable without claiming physical delivery |
| [19 · App and scan generations](19-app-and-scan-generations.md) | Retired app can commit across restart; old/superseded scan can rewrite current rig | Delayed old commits and reversed scan results cannot alter the successor |
| [20 · Document-bound work](20-document-completions.md) | Learn/paste crosses New/Open; delayed chooser discards intervening edits | Reject an old document's result and re-evaluate discard authorization |
| [21 · Rig write and Exit](21-rig-write-drain.md) | Flush can succeed during I/O; zero-delay scheduling and retry/Exit can race | Hold the lock through the actual write outcome; don't discard newer work |
| [22 · Vibe ownership on Undo](22-vibe-ownership-undo.md) | Effect activation moves back on Undo but its live owner doesn't | Move/Undo/Redo, switch vibes, then save: live-owned and manual switches stay distinct |
| [23 · Rig group definitions](23-rig-group-definitions.md) | Undo restores the last member of a rig-only group without restoring its definition | Restored membership survives autosave and New with its definition |
| [24 · Input learning](24-input-learning.md) | MIDI/OSC Settings stay in Learn; old listener can remove the next wizard step; changed behavior or same-target restarts accept stale results | Only the active attempt can capture, complete, or unregister; displayed status matches the result |
| [25 · Input device worker](25-input-device-worker.md) | Opening devices blocks the sole event writer; repeated connect duplicates receivers | Delayed fake I/O doesn't block events; one connection delivers one input |
| [26 · Physical input releases](26-physical-input-releases.md) | Non-keyboard release follows a changed binding, not the action it started | Release, disable and teardown retire the original activation |
| [27 · Fast input publication](27-fast-input-publication.md) | Slow gamepad movement never emits; Map cycle ignores the fader that moves its meter | Compare with last emitted value and reuse the tick's sampled inputs |
| [28 · Pattern references](28-pattern-reference-traversal.md) | Rig remapping misses pins in persisted patterns; pattern-only zones are absent from the preview catalogue | The same persisted references are enumerated and remapped correctly |
| [29 · Effective map](29-effective-map-selection.md) | Manual selection is labeled “in use” and disables a valid Use now during cycling | Manual choice and actual controlling map have distinct semantics |
| [30 · Starting output status](30-starting-stream-status.md) | A drained lifecycle queue is reported as streaming with zero engines streaming | All-starting and mixed/error fixtures produce honest counts and status |

### Restore selective rendering and development feedback

| Page | Finding covered | Acceptance in one line |
|---|---|---|
| [31 · Renderer wiring](31-renderer-wiring.md) | Wrong app options, a broken local dev-help override and two validator mismatches | A real menu event still updates the model/view, F12 opens the dev inspector, and the custom renderer error handler receives errors |
| [32 · Read APIs](32-state-read-api.md) | Global-read helpers claim render tracking; raw snapshots depend on library internals | Views use their bound context; one tested adapter owns the raw snapshot contract |
| [33 · Context retention](33-child-context-retention.md) | Passing a bound context as a child prop retains generations and changes cache keys | Children receive the injected context and no prop/handler bag keeps the old context alive |
| [34 · Chain editor subscriptions](34-chain-editor-subscriptions.md) | Whole chains, list traversal and fresh row props invalidate unrelated editors | Another cell's edit doesn't recompute the untouched list or its ID traversal |
| [35 · Grid fan-out](35-grid-subscription-fanout.md) | Selecting one cell or learning an unrelated key rebuilds both grids' cells | Only affected selection/learning values change; index the bindings once |
| [36 · Cache working set](36-cache-working-set.md) | Current LRU loses the starter Grid and Vibes working sets | Measure warm Grid, Vibes and an editor; pick a bounded capacity from results |
| [37 · Derived input costs](37-derived-input-costs.md) | Full lint for a tab badge, sorting in a `sub-val`, whole params as fold key | No redundant lint/sort/fold for unrelated live edits; real changes still show |
| [38 · Drag payload lifetime](38-drag-payload-lifetime.md) | Grid drag leaves a fallback payload after completion/cancellation | No stale payload in the next gesture; distinguish a bounded stale entry from a leak |

## Implementation lanes and merge order

The findings were **planned** in parallel. Implement by outcome rather than opening 38 competing changes to the same few namespaces. The ordering below distinguishes an interface one task needs from merely sharing a file.

| Lane | What can start together | Integration point |
|---|---|---|
| Document integrity | **01 and 02** use independent typed-input and file-validation boundaries | Join on a real Enter → Save → Open regression and a failed Open that preserves the current show. Sequence **07 and 06** against 01 where they share controls. Later, 20 owns *when* an Open continuation applies; 02 owns *whether the archive is readable*. |
| Transport | **03** is independent of the document and editor fixes. The clock-side chase pruning in **15** can start in isolation. | Integrate **15** after **03**'s removal semantics and **20**'s document stamp. A stale chase/end report must neither restart playback nor remove a newer run. |
| Editor identity and gestures | **04 and 05** can start in parallel. 38's drag fallback is scoped to a different path. | Coordinate **05 and 16** on one step-grid target identity. Land **04 before 33/34**: the list must still work if memoization skips the sidebar body. |
| Operator state | **08, 12, 13 and 14** can be tested separately using in-memory configuration | 08/09/14 touch the projector handlers; split their commits by event. Preserve backend guard behavior when adopting 13's feedback. For the grids, land the functional rules **10/11** before narrowing their subscriptions in **35**. |
| Async ownership | **19, 20 and 21** address different lifetimes (app, show, file write); design in parallel | Reconcile boundaries before merging: 19 fences a retired app; 20 stamps document-bound operations; 21 waits for the actual rig write and rechecks whether Exit still applies. **24** consumes 20's document contract and owns capture attempts; **25** owns device connections; **26** owns which press a release ends. Don't conflate a device connection with a document edit. |
| Undo, routing and status | **22/23** are separate restorations. **27**, **28**, **29** and **30** concern separate input, reference, map and status projections. | Serialize changes to the document restore function in 14/22/23. Share pattern traversal in **28**, not an additional per-view walker. Keep the UI-only audience fix in 12 independently deliverable. 18's record of a successful driver call and 30's aggregate status describe different facts. |
| Rendering and developer tools | **31, 32** and the *baseline* part of **36** can start now; **17** can establish its registry inputs independently | Record counts before changing anything. Integrate **33 → 34**, **35** and **37**, then repeat **36** on the same scenarios. Choose the production LRU only after those comparisons. 31's middleware should preserve the single production dispatcher from 19. |

### Three ownership decisions that span pages

1. **Which operation owns a completion?** A mounted app, an open show, a device connection, and an individual Learn attempt have different lifetimes. **NEW** `[:project :document-id]` / `:document-id` belongs to 20; it is transient, not saved, undone or compared for dirty state. **NEW** `:learn-id` belongs to 24, not to the device connection in 25. A late result can have a valid device and still be invalid for the current show. 19 similarly checks app lifetime *before* any write-back; a queue flush alone proves neither worker completion nor instance ownership.
2. **What is a stable target?** For an open list/pattern editor the UI owns the selected identity; the handler resolves it in its current state. For a grid click the cell coordinate *is* the desired address; whether it holds a playable cue is a fact to read, not part of the intent. For a physical key release, use the activation recorded on press. Those three cases deliberately have different answers.
3. **What does the UI claim?** An immutable reported result, the most recent requested setting, and a JavaFX control's editable value are not interchangeable. A suppressed write must not leave a false On/Off indication (13); one successful driver return is evidence about that driver call, not proof of physical output (18); a drained lifecycle queue does not establish that an engine is streaming (30).

**Preserve the working paths.** [Effect ordering](../../src/laser_show/events/core.clj#L655-L672) already puts the app write first. [The clock](../../src/laser_show/services/show_clock.clj#L258-L304) reads app state and continuous input once per tick. [Scene-owned key filters](../../src/laser_show/views/key_triggers.clj#L65-L100) already remove hooks and release held keys on teardown. The [per-cell regression](../../test/laser_show/subs_test.clj#L70-L87) already proves that an unrelated cue or beat doesn't change one isolated selector result. The new coverage is about *other* inputs and full trees. The [empty-path accessor fallback](../../src/laser_show/state/extractors.clj#L49-L71) is a latent edge, not a demonstrated production whole-DB subscription; cover it if 34 changes that API rather than treating it as the measured fan-out.

**Be precise about “no-op.”** Returning `{:state state}` can preserve the document yet still call [the state effect](../../src/laser_show/events/core.clj#L165-L173) and create [a successor context](../../src/laser_show/state/core.clj#L148-L160). Where a work item promises **no commit** for duplicate pointer motion or a stale completion, check the effect result as well as state equality. Suppression of all no-op writes globally is not a prerequisite for these repairs.

**Keep planning evidence separate from validation of the fix.** A fixture confirming today's bug is a baseline. A named **NEW** test in a page is an acceptance criterion for implementation, not a test that already passes. For example, [07](07-modulator-field-patches.md) reproduced Min `0.3` reverting to `0.2` after editing Max from the same render, whereas the already-landed Modulate toggle regression above genuinely passed. [30](30-starting-stream-status.md) reproduced the aggregate status using published data; it makes no observation about a device receiving a frame.

### Decisions to confirm before release

- **02 — Save on a filesystem that cannot replace a file atomically.** The proposed default is to report failure and keep the previous file, rather than risk truncating someone's last good show. Its reader accepts documented legacy omissions, but an unreadable required content member is never an empty show. Confirm the destination/failure behavior for supported removable or network storage using a temporary-file fixture.
- **13 — What a locked control displays.** The proposed default keeps otherwise available controls interactive: dispatch the edit once, explain a refusal, then restore the applied value without sending a second edit. Already-disabled controls stay disabled. The settled native control **and** model must agree; the notice alone is insufficient.
- **36 — Production cache capacity.** This is an implementation measurement, not a value to approve in advance. A comparison cache fixed call counts but says nothing by itself about acceptable retention. Ship the new value only after the cross-view measurements and retention checks in page 36.

## How to verify and retire an item

Keep one end-to-end check for each interface shared by two pages:

| Hand-off | Integrated acceptance |
|---|---|
| 01 inputs → 07 field patches → 02 persistence | Commit number `0.42`. Make two active modulator subfield edits from one render and verify Undo/Redo before saving. Reopen: the number, both edits and an unrelated cue round-trip; the freshly opened show starts clean with fresh history. |
| 03 transport → 15 chase | Stop with two intents, then deliver an earlier completion and a matching later cleanup. Neither changes the gate to playing; a newer manual run at the same address is untouched. |
| 05 editor → 16 history | Open A, reorder it, paint, close before mouse-up, receive clock beats, then edit B. Only A was painted, and undoing the edit to B does not undo A. |
| 19 app → 20 document → 24 learn | Start capture, replace its show and start another capture for the same target before the old callback returns. The new binding and listener survive; a callback from a retired app cannot commit anything. |
| 19 shutdown → 21 rig writer | Gate a write after the worker claims it; request Exit and then release the gate. The old file lock stays held through the outcome; a newer unsaved edit is checked before any continuation. |
| 04/33/34 list → 36 cache | Repeat Delete after retarget/reopen with a warm cache, then change an unrelated cell while the editor stays open. Correct commands and dependency reuse must both hold; merely forcing a remount is not a fix. |

For the memory and CPU checks, record the workload, warm-up, LRU capacity, total view-body calls and changed results separately. Use the same workload before/after; keep the safety of data and target identity above as acceptance gates even if a change improves the counters. A cold cache, a deliberately stale fixture, or a diagnostic that still prints today's bad outcome is not evidence that the repair has landed.

1. Recheck its cited functions/tests against the implementation branch; plan pages name both the existing test to protect and the **NEW** regression where needed. Use the failing example in that page, not a hand-built event that bypasses the suspected listener.
2. Use the [focused-test workflow](../../.agents/skills/targeted-ui-testing/SKILL.md#choosing-the-regression-for-a-reactive-change). A pure handler gets state plus an event, a selector gets a real context and a derived successor, and a native-control failure gets a headless control plus the real listener. For workers use gated fakes and observable completions; for UI assertions re-query both the model and visible control after the relevant outcome. Keep device and file fixtures isolated.
3. While implementing, run the named test/namespace first. For code affecting the UI, the final regression gate is `clj -M:test-ui:mac-arm:test-ui-headless` and `clj -M:mac-arm:test` ([aliases](../../deps.edn#L125-L146), [headless UI](../../deps.edn#L205-L229)); substitute the platform alias as appropriate. The planning probes above are **baselines**, not passing regression tests for the desired behavior.
4. Mark each landed item with commit, tests run, and residual risk. Keep one owner for open work. The prior [improvement review](../2026-11-07-improvement-review/00-overview.md#L24-L45) explicitly defers responsiveness during ZIP I/O; this plan changes file integrity and stale continuations, not that scheduling decision. The [keyboard phases](../2026-11-13-keyboard-focus-window-lifecycle/00-overview.md#L3-L22), [output runtime phases](../2026-11-16-output-runtime/00-overview.md#L9-L16), and [zones phases](../2026-11-19-zones/00-overview.md#L3-L5) have already landed; these pages own the narrower failures that remain. The follow-up aligning skills and prompts is already present; 31/32 cover the still-live renderer/read-API code problems.
