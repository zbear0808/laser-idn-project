# Phase 1 — Don't lose work: unsaved-changes guard and an exit path

**Written:** 2026-11-13   **Written for commit:** `cb32414` (`master`)
**Status:** Done (2026-11-14, `93c638a` on `master`; CI fix `e87b272`). D1–D3 answered A; differences from the plan in §10   **Last verified against:** `93c638a`
**Supersedes / Superseded by:** none   **Related findings:** K-1, K-2, K-3 (`00-overview.md` §3); review 01 F20 / L3, 02 M3 follow-up; undo plan §10 ("the unsaved-changes guard … can now trust `[:project :dirty?]`")

Closing the window, File > Exit, File > New and File > Open all discard unsaved work without asking. The dirty flag has been exact since the undo plan landed (it is compared with the last saved version), so the guard can trust it. About 1 day.

## 1. Current behaviour (file:line)

| What | Where | Behaviour today |
|---|---|---|
| Window close | `views/root.clj:167-169` | `:on-close-request (fn [_] (System/exit 0))`, with `;; TODO: Check for unsaved changes` at `:168`. Runs in dev mode too, so closing the window kills the REPL JVM even though `app.clj:130-132` calls `Platform/setImplicitExit false` for exactly that case. |
| File > Exit | `title_bar.clj:99-101` → `menu.clj:129-134` → `events/core.clj:219-227` | Handler returns `{:system/exit true}` (TODO at `menu.clj:133`). `effect-system-exit` does nothing but log in dev mode, otherwise `Platform/exit` + `System/exit 0`. |
| File > New | `title_bar.clj:66-69` → `menu.clj:23-30` | Replaces the document at once (TODO at `:28`) and asks for `:multi-engine/refresh`. |
| File > Open | `title_bar.clj:71-74` → `menu.clj:32-42` (chooser) → `:44-53` → `:project/load` effect (`core.clj:117-125`) → `menu.clj:55-60` | Replaces the document when the zip has loaded. No dirty check anywhere on the path. |
| Save | `menu.clj:107-113`, `:87-105` (Save As), `:67-76` (`save-to`) | `save-to` marks the project saved **before** the write and requests `:project/save` (`core.clj:107-115`) with `:on-failed`; `:file/save-failed` (`menu.clj:78-85`) restores the old status. There is no "after a successful write" hook. |
| Dirty flag | `events/handlers/history.clj:282-305` (`wrap-dirty`), `subs.clj:106-107`, title `" *"` at `subs.clj:357` | Derived for every event from the saved baseline. UI-only changes (such as `[:ui :dialogs …]`) leave it untouched. |
| Confirm dialog infrastructure | grep for `Alert`, `confirm`, `showAndWait` in `src/` | None. The only native dialog is the `FileChooser` effect (`core.clj:272-329`). The two `TODO: Show confirmation dialog` comments are the only hits. |
| Exit cleanup | `app.clj:186-199` (`stop!`), `streaming_engine.clj:290-291` | `stop!` unmounts the UI and releases input devices only. It stops neither the streaming engines, the preview timer (`frame_service.clj:839-845`) nor the Carabiner link (`link/disconnect-carabiner!` has no callers). No JVM shutdown hook exists anywhere. Engine threads are daemons, so `System/exit` kills them without the IDN close packets (`streaming_engine.clj:301-333`); the DAC only notices after its 1 s link timeout. |
| Engine stop reads state wrongly | `multi_engine.clj:300` | `stop-engines!` reads `state/get-in-state`, which goes through `fx/sub-val` on a non-FX thread (`docs/CLJFX_STATE_MANAGEMENT.md` §Thread Safety says not to). Calling it from the exit path makes this matter. |

Existing tests that pin today's shapes: `menu_system_handlers_test.clj:22-28` (`exit-handler-test` expects `{:system/exit true}` for a clean state) and `menu_file_handlers_test.clj` (save/open pipeline tests).

## 2. Root cause

The window's close hook is wired straight to `System/exit`, and the three document-replacing menu handlers never read `[:project :dirty?]`. The flag only became trustworthy on 2026-11-08, which is why the TODOs were left. Nothing in the app can ask the user a question yet.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `[:project :dirty?]`, `[:project :saved-document]` (`state/history.clj` `mark-clean`, `derive-dirty`) | The guard's only input. No new dirty logic. |
| `:file/save`, `:file/save-as`, `:file/save-as-result`, `save-to` (`menu.clj:67-120`) | Gain an optional `:then` event that is carried through to the write. |
| `:project/save` effect (`core.clj:107-115`) | Gains `:on-saved`, dispatched only after `write-project!` returned true. |
| `:fx/show-file-chooser` `:on-result` map (`core.clj:272-329`) | Already carries arbitrary keys back into the result event, so `:then` rides along. Cancel dispatches `:file-path nil`, which aborts the chain. |
| `:system/exit` effect (`core.clj:219-227`, registered `:446`) | Extended (see §4, D3). |
| `[:ui :dialogs …]` + `:ui/open-dialog` / `:ui/close-dialog` (`handlers/ui.clj:18-35`), `subs/dialog-open?` (`subs.clj:391-396`), root `ext-many` mounting (`root.clj:146-196`) | How the confirm dialog is shown (D1 option A). |
| `app/stop!` (`app.clj:186-199`) | Becomes the one shutdown path (D3). |
| TestFX: `fixtures/with-app` (`fixtures.clj:47-58`), `ui/click!`, `ui/wait-for!`, `ui/query` | UI tests. |

## 4. Changes per namespace (as planned; the differences are in §10)

- `laser-show.views.root` (`root.clj:160-172`): `:on-close-request` becomes a **function** that consumes the `WindowEvent` and dispatches `{:event/type :file/request-exit}`. It must be a function: a cljfx event map is dispatched asynchronously (`events/core.clj:468-490`), so the window would already have closed before a handler could cancel it. Mount the confirm dialog beside the other six (`:174-196`).
- `laser-show.views.dialogs.unsaved-confirm` (NEW): a small stage with three buttons (`#unsaved-save`, `#unsaved-discard`, `#unsaved-cancel`) and a message built from `[:project :current-file]` and the pending action ("Save changes to X before closing?"). `:modality :application-modal`; its own close request means Cancel. Built on the Phase 2 shell if that has landed, otherwise as a plain stage.
- `laser-show.events.handlers.menu` (`menu.clj`):
  - `handle-file-new-project` (`:23`), `handle-file-open` (`:32`) and `handle-file-exit` (`:129`) gain the guard: dirty and not `:force?` → open the confirm dialog with the pending action; otherwise behave exactly as today.
  - NEW `handle-file-request-exit` (window close) → same path as `handle-file-exit`.
  - NEW `:file/confirm-save`, `:file/confirm-discard`, `:file/confirm-cancel` handlers (§6).
  - `handle-file-save` (`:107`), `handle-file-save-as` (`:87`), `handle-file-save-as-result` (`:115`), `save-to` (`:67`) pass `:then` through.
- `laser-show.events.core`:
  - `effect-save-project` (`:107-115`) dispatches `:on-saved` after a successful write.
  - `effect-system-exit` (`:219-227`): per D3.
  - NEW `set-exit-hook!` (next to `set-dispatch-fn!`, `:460`): a test seam so TestFX tests can observe "exit requested" without killing the test JVM.
- `laser-show.app` (`app.clj:186-199`): `stop!` also stops the preview timer, the streaming engines and the Carabiner link (D3). This is the symmetric-lifecycle item from review 02 M8.
- `laser-show.backend.multi-engine` (`multi_engine.clj:297-317`, `:319-378`): read state with `state/get-raw-state` + `get-in`, not `get-in-state`.
- `laser-show.state.domains` (`domains.clj:141-162`): add `:unsaved-confirm {:open? false :pending nil}`.
- `laser-show.views.components.title-bar`: unchanged (the Exit/New/Open items already dispatch the right events).

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:ui :dialogs :unsaved-confirm :open?]` NEW | app state, UI | `:file/new-project`, `:file/open`, `:file/exit`, `:file/request-exit` (open); `:file/confirm-*` (close) | No / no (not a document root, so `wrap-dirty` leaves the flag alone) |
| `[:ui :dialogs :unsaved-confirm :pending]` NEW | app state, UI | same | No / no. Holds the original event **without** co-effects: `{:event/type :file/new-project}`, never the injected `:state` or `:time`. |

No runtime atoms, no threads, no new effect keys. `:on-saved` and `:then` are plain event maps inside existing effect/event payloads.

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:file/new-project`, `:file/open`, `:file/exit` + optional `:force? true` | changed | menu items (`title_bar.clj:66-101`); re-dispatched by confirm handlers | yes |
| `:file/request-exit` | NEW | window close request (`root.clj`) | yes |
| `:file/confirm-save` | NEW | `#unsaved-save` | yes. Returns the existing save path with `:then (assoc pending :force? true)`. |
| `:file/confirm-discard` | NEW | `#unsaved-discard` | yes. Closes the dialog and `:dispatch`es `(assoc pending :force? true)`. |
| `:file/confirm-cancel` | NEW | `#unsaved-cancel`, dialog close request | yes. Closes the dialog; document untouched. |
| `:file/save`, `:file/save-as`, `:file/save-as-result` + optional `:then` | changed | menu, `:file/confirm-save` | yes |
| `:project/save` effect + `:on-saved` | changed | `save-to` | effect |
| `:system/exit` effect | changed | `:file/exit` (unguarded or forced) | effect |

Threads: an effect's `dispatch` argument is the synchronous handler, so a nested event runs inline on whichever thread is executing the effect. The file chooser dispatches its result from the FX thread (`events/core.clj:286-329`). The chain "chooser result → `:project/save` write → `:on-saved` → forced New" therefore runs on the FX thread when it starts from Save As, exactly as the Save As write already does today, and on the agent thread otherwise. Both are fine for a small zip; it is worth knowing when reading logs.

## 7. Data examples and invariants

| Dirty? | Event | Result |
|---|---|---|
| no | `{:event/type :file/new-project}` | document replaced exactly as today; no dialog |
| yes | `{:event/type :file/new-project}` | document unchanged; `[:ui :dialogs :unsaved-confirm]` = `{:open? true :pending {:event/type :file/new-project}}` |
| yes | `{:event/type :file/new-project :force? true}` | document replaced |
| yes | `{:event/type :file/open}` | confirm opens **before** the file chooser (the user is not asked after picking a file) |
| yes | `{:event/type :file/request-exit}` | confirm with `:pending {:event/type :file/exit}` |
| no | `{:event/type :file/request-exit}` | `{:system/exit true}` |
| yes, pending new | `:file/confirm-discard` | dialog closed; `:dispatch {:event/type :file/new-project :force? true}` |
| yes, pending new, file known | `:file/confirm-save` | `:project/save` with `:on-saved {:event/type :file/new-project :force? true}`; the document is **not** replaced until the write succeeded |
| yes, pending new, no file | `:file/confirm-save` | file chooser whose result event carries `:then`; cancelling the chooser ends the chain with nothing changed |
| write fails | `:file/save-failed` | no `:on-saved`; project status restored (existing behaviour); pending action dropped |
| yes, dialog already open | any second guarded event | ignored (one dialog at a time) |

Invariants:
1. A dirty project is never replaced or exited without an explicit Discard or a **successful** write.
2. The confirm dialog never changes the document: no undo entry, `[:project :dirty?]` unchanged.
3. For a clean project every handler returns the same effect map as before (so `exit-handler-test` and the file tests stay green).
4. `:force?` is only ever set by confirm handlers.
5. The window close request is always consumed. The stage closes only through the exit effect (or the dev-mode path).

## 8. Tests (named test vars + fixtures)

Pure handler tests, in `laser-show.events.menu-file-handlers-test` (extend; fixtures `document-state` `:28`, `runtime-state` `:36` and `with-runtime` `:42` already exist):
- `dirty-new-project-asks-first-test`
- `dirty-open-asks-before-the-chooser-test`
- `dirty-exit-asks-first-test`
- `clean-project-skips-the-guard-test` (same effect maps as today)
- `force-new-project-replaces-document-test`
- `confirm-discard-continues-the-pending-action-test`
- `confirm-cancel-keeps-document-and-closes-dialog-test`
- `confirm-save-with-file-waits-for-the-write-test` (no document replacement in the returned `:state`)
- `confirm-save-without-file-routes-through-save-as-test`
- `save-failed-drops-the-pending-action-test`
- `second-guarded-event-while-open-is-ignored-test`

Pipeline test (like `save-as-pipeline-test`, `:111`): `save-then-continue-pipeline-test` runs a dirty `:file/new-project` + `:file/confirm-save` through `events/event-handler` with a temp file and asserts the document is replaced only after the zip exists.

`laser-show.events.menu-system-handlers-test` (extend): `request-exit-is-routed-to-the-menu-handler-test`; `exit-handler-test` (`:22`) stays as is.

UI, `laser-show.ui-test.main-window-test` (extend; fixtures `with-toolkit` / `with-app`), with `events/set-exit-hook!` recording instead of exiting:
- `closing-a-dirty-project-asks-first-test`: dispatch `:grid/clear-cell` on `[0 0]` (dirty), fire `WindowEvent/WINDOW_CLOSE_REQUEST` on the main stage, wait for `#unsaved-cancel`; the main stage is still showing and the hook was not called.
- `cancel-keeps-the-window-test`, `discard-requests-exit-test`.
- `save-then-exit-test`: set `[:project :current-file]` to a temp path first (a native chooser can't be driven headless), click `#unsaved-save`, wait for the zip, then the hook is called.
- `closing-a-clean-project-requests-exit-test`.

Manual: Cmd+Q on macOS goes through the same close request (traced through the JavaFX jar in §10); confirm by hand once.

Run: `clj -M:mac-arm:test menu-file menu-system`, then `clj -M:test-ui:mac-arm:test-ui-headless main-window`, then both full suites.

## 9. Out of scope / alternatives considered

- **Autosave / crash recovery.** Valuable, but a separate feature; this phase only stops the avoidable loss.
- **Native `Alert` through an effect** is D1 option B.
- **Guarding the secondary dialogs.** Their edits apply to the document immediately, so they hold nothing unsaved.
- **Re-planning `:project/set-folder`** (`files.clj:21-24`, a TODO stub). Unrelated.

---

## Decisions

> **DECISION D1 — How is the confirm dialog shown?** *(answered A, 2026-11-14)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | A small data-driven stage mounted from `root.clj` like the other dialogs; state at `[:ui :dialogs :unsaved-confirm]` | Same pattern as every other dialog; pure handlers; testable by node id; can be reused for later confirmations by making the message data | Four touch points (default state, sub, view, root mount) |
> | B | Native JavaFX `Alert` shown by a generic `:ui/confirm` effect (`showAndWait` inside `runLater`) | Least code; native look | Not data-driven; button results come back through an effect, not state; headless tests have to drive a native dialog window |
>
> **Your answer:** A (approved 2026-11-14)

> **DECISION D2 — What does "Save" in the dialog do?** *(answered A, 2026-11-14)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Save, then continue the original action once the write has succeeded; a cancelled Save As or a failed write aborts everything | What users expect; nothing is lost if the write fails | Adds `:on-saved` / `:then` plumbing (small) |
> | B | Save only; the user repeats the action | Trivial | Every guarded action needs two tries; easy to misread as "it didn't work" |
>
> **Your answer:** A (approved 2026-11-14)

> **DECISION D3 — What does closing/exiting do in the dev (REPL) JVM, and what is cleaned up on exit?** *(answered A, 2026-11-14)*
>
> Today the close button kills the REPL JVM (`root.clj:169`), `File > Exit` is ignored in dev (`core.clj:223-224`), and neither path stops the engines or the preview timer.
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | One shutdown path, `app/stop!`, made symmetric with `start!`: stops the preview timer, the engines (graceful IDN close), the Carabiner link and input devices, then unmounts. Production exit = `stop!` then `Platform/exit` + `System/exit`. Dev "exit" = `stop!` only, so the JVM and REPL survive | DACs get a clean close; the REPL is never killed by a window click; `stop!` becomes safe to call twice (the TestFX fixtures already do) | `stop!` grows; engines must be stoppable from a non-FX thread (the `get-raw-state` fix in §4) |
> | B | Only guard the close/exit; leave cleanup and the dev-mode inconsistency as they are | Smallest diff | Engines keep their 1 s DAC timeout on exit; closing the window in dev still kills the REPL (unless the close handler is also gated on dev mode) |
>
> **Your answer:** A (approved 2026-11-14)

---

## 10. As built (2026-11-14)

Committed as `93c638a` on top of `cb32414`. Like the timeline-rework phase docs, this one stays until the whole folder is done (`.agents/rules/planning-guide.md` rule 11).

**Differences from the plan above**

| Plan | As built | Why |
|---|---|---|
| `events/set-exit-hook!` as a test seam; `effect-system-exit` branches on dev mode (§4, D3) | `events/set-exit-fn!` registers the function the `:system/exit` effect calls. `app/start!` registers `exit!` (`stop!`, then `Platform/exit` and `System/exit` outside dev mode); `stop!` clears it. With none registered the effect logs a warning and does nothing | `events/core.clj` can't require `app.clj`, and one seam serves production and tests. The dev-mode decision now lives in `app/exit!`, so `events/core.clj` no longer requires `dev-config` |
| NEW `:file/request-exit` for the window close (§4, §6, §7) | The close request dispatches `:file/exit`, which carries the guard | One handler instead of two that do the same |
| `:pending` holds the original event without co-effects (§5) | `ask-first` stores `(select-keys event [:event/type])` | The three guarded events have no payload. A future guarded event that has one must change `ask-first` |
| Dialog "built on the Phase 2 shell if that has landed" (§4) | A plain stage in `views/dialogs/unsaved_confirm.clj`, mounted by a seventh `when` block in `root.clj` | Phase 2 hasn't started. It moves this dialog onto the shell with the other six |
| Test names in §8 | Listed below | Renamed while writing |

**Behaviour worth knowing**
- Enter is Save (default button), Esc is Cancel (cancel button), and closing the dialog's own window is Cancel. Save and Cancel keep the document; only Don't Save can lose it.
- The message reads "Save changes to *file name* (or *this project*) before *creating a new project* / *opening another project* / *closing*?". A long file name wraps (checked in a headless screenshot).
- In a `start-test!` session (UI tests, REPL experiments) no exit function is registered. The close button still asks about unsaved changes, but a confirmed exit only logs a warning: the window never closes by itself and the JVM never ends. With `start!` in dev mode a confirmed exit stops the app and leaves the JVM and REPL alive.
- `stop!` now also stops the preview timer, the streaming engines (only when some exist) and the Carabiner link (only when connected). `engine/stop!` joins its thread for up to 1 s (`streaming_engine.clj:311`), so exiting with several engines running can take about that long per engine. The window is unmounted first, so it disappears at once.
- Not done from review 02 M8: clearing `events/*dispatch-fn` and `input-mapper/*dispatch-fn` in `stop!`. MIDI, OSC and gamepad release already existed (`stop-input-devices!`).
- A failed write after Save is only logged (`persistent/write-project!` returns false). The project goes back to unsaved and the pending action is dropped, but the user sees no message. That was already so for a plain Save; a status-bar message is a separate item.

**Tests** (all new unless noted)
- `laser-show.events.menu-file-handlers-test`: `dirty-new-project-asks-first-test`, `dirty-open-asks-before-the-chooser-test`, `dirty-exit-asks-first-test`, `clean-project-skips-the-guard-test`, `forced-events-skip-the-guard-test` (planned as `force-new-project-replaces-document-test`), `second-guarded-event-while-open-is-ignored-test`, `confirm-cancel-keeps-the-document-and-closes-the-dialog-test`, `confirm-discard-continues-the-pending-action-test`, `confirm-save-with-a-file-waits-for-the-write-test`, `confirm-save-without-a-file-routes-through-save-as-test`, `save-as-result-carries-then-to-the-write-test`, `plain-save-has-no-continuation-test`, `save-then-continue-pipeline-test`, `failed-write-does-not-continue-test` (planned as `save-failed-drops-the-pending-action-test`), `discard-pipeline-test`. The existing `new-project-resets-document-only-test` now sends `:force? true`.
- `laser-show.events.menu-system-handlers-test`: `exit-effect-calls-the-registered-exit-fn-test`; the fixture clears the exit fn and `exit-effect-is-registered-test` expects the effect to be ignored when none is registered. `request-exit-is-routed-to-the-menu-handler-test` was dropped with `:file/request-exit`.
- `laser-show.ui-test.main-window-test` (the three existing tests are kept): `closing-a-clean-project-exits-without-asking-test`, `closing-a-dirty-project-asks-first-test`, `cancel-keeps-the-window-and-the-project-test`, `discard-exits-test`, `save-then-exit-test`. They count exit requests through `events/set-exit-fn!`.

**Verification, 2026-11-14, macOS arm64, headless**

| Check | Result |
|---|---|
| `clj -M:mac-arm:test` (full unit suite) | 716 tests, 11,452 assertions, 0 failures (700 tests before this phase) |
| `clj -M:test-ui:mac-arm:test-ui-headless` (full UI suite) | 40 tests, 131 assertions, 0 failures |
| `main-window-test` run 8 times in one JVM | 8 of 8 clean (34 assertions each) |
| `clj -M:kondo --lint src test --parallel --fail-level error` | 0 errors. 22 warnings, none new: the only one in a touched file is the `redundant do` at `events/core.clj:500`, from 2026-01-10 |

Not run locally: the Linux unit suite and the Windows UI suite. They run in CI.

**Cmd+Q on macOS** (replaces the "unverified" note in §8). Read from the JavaFX 28-ea+11 `mac-aarch64` jar, not tried with a real keypress: native `-[GlassApplication applicationShouldTerminate:]` calls `Application.notifyWillQuit`, then `EventHandler.handleQuitAction`, then `GlassStage.requestClosingAllWindows()`, which calls `closing()` on every visible stage. `WindowPeerListener.closing()` fires `WindowEvent.WINDOW_CLOSE_REQUEST` through `Event.fireEvent`, the same call `main-window-test` makes. The native method then returns 0 (`NSTerminateCancel`), so macOS doesn't end the process on its own. Still worth one Cmd+Q by hand.
