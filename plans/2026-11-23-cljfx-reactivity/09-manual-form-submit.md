# 09 · Form submission

**Status:** Implemented in `9551660` (Add) and `3cbe86d` (OSC` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24).

No plan page was written before implementation; the design brief the implementer worked from and its hand-off report are recorded here. Paths are relative to `src/laser_show/` unless they say otherwise, and citations refer to the code at the time of writing.

## Design brief

Audit: "A field edit can undo another edit made since the last render" (row also covers manual projector submission) in section "Where the UI or stored state can be wrong". Shell/settings auditor finding 6 (a7e414e):
"Add Projector embeds the rendered host, name, port or driver inputs in the button (views/dialogs/add_projector_manual.clj:146–159); the handler uses that payload even if the form is already closed (events/handlers/projector.clj:312–350). A handler probe with a newer host in form state still added the old host; repeating the submit after closure was accepted. Send a submit-manual-form intent that reads and validates the open form on the event thread. The OSC Start button has the same issue (views/components/osc_settings.clj:119–142): the port field commits only on Enter, and an explicit stale :port wins over state (events/handlers/input.clj:149–156). Use commit-field for Enter/blur, and omit :port from Start so the handler uses its current configuration."

### Part 09-ADD (projectors lane)
- NEW intent (e.g. `:projectors/submit-manual-form`) dispatched by the Add button with no form values; its pure handler reads the CURRENT open form from app state (`[:ui :dialogs :add-projector-manual]` — verify where the form's host/name/port/driver/driver-config inputs live), validates with the existing rules (IDN host/port; non-IDN `driver/coerce-config` against the descriptor), adds via the existing `added`/`handle-projectors-add-manual` logic, closes and clears the dialog. If the dialog is no longer open (double click, queued second submit), it does nothing.
- Keep `:projectors/add-manual` with an explicit payload for programmatic callers/tests, but the UI no longer uses it.
- Show Lock: `:projectors/add-manual` is in `events/show_lock.clj` `blocked-events`; the new submit intent must be refused while locked too (add it; `laser-show.events.show-lock-test/blocked-events-name-real-events-test` checks blocked events are routed).
- Tests: handler tests (newer host in form state wins; second submit after close adds nothing; invalid form adds nothing and keeps the dialog), a UI test in the existing setup/add-projector dialog UI namespace (find it: test/laser_show/ui_test/setup_dialogs_test.clj or projector_drivers_test.clj) that types a host, clicks Add twice quickly (or fires the button action twice before render) and asserts exactly one projector with the typed host. Keep existing add-projector tests passing.

### Part 09-OSC (input lane)
- The OSC port field commits on Enter AND blur (use views/components/commit_field.clj like other commit fields) and shows the applied port after an invalid entry.
- Start Server no longer sends a rendered `:port`; the start operation uses the configured port from state at handling time (25's device worker, landed earlier in that worktree, owns the actual start).
- Tests: typed port then blur then Start → server requested on the typed port; invalid port text restores the applied port; existing OSC/settings tests pass (stub the server start, no real sockets).

## As implemented

**Changes**
- `events/handlers/projector.clj`: NEW `manual-port`, `manual-form-add`, `handle-projectors-submit-manual-form` (after `handle-projectors-set-manual-field`). Reads `[:ui :dialogs :add-projector-manual]`; nothing unless open; builds the same payload the button used to; adds via existing `handle-projectors-add-manual` (closes + clears the dialog). One new case in `handle`. `handle-projectors-add-manual` only a docstring update (keeps explicit payload).
- `views/dialogs/add_projector_manual.clj`: Add's `:on-action` → `{:event/type :projectors/submit-manual-form}`.
- `events/show_lock.clj`: new event in `blocked-events`.
- `events/handlers/history.clj`: label "Add Projector".
- Validation: IDN needs non-blank host; other drivers via `driver/coerce-config`; failure adds nothing, dialog stays.

**Decision to confirm (user)**: an IDN port that isn't 1–65535 (e.g. "70000", "0") now becomes 7255 (before: "abc" → 7255 but out-of-range numbers were stored as broken ports). Alternative: refuse invalid ports (also needs a rule in `subs/add-manual-form` so Add disables).

**Notes**: 08's scoped-question branch and the Add Projector close branch untouched; no 09-OSC files touched. Pre-existing: projector IDs from `System/currentTimeMillis` → two explicit `:projectors/add-manual` in the same ms collide (submit path can't hit it).

## As implemented

**Changes**: `views/components/osc_settings.clj` Port field uses `commit-field` with private `parse-port` (integer 1–65535, trimmed); invalid text ("abc", "0", "70000", "") restores the applied port and dispatches nothing; field still disabled while listening. Start/Stop event no longer carries `:port` (the start reads `[:backend :input :osc :port]` at handling time; a real click on Start takes focus first, so the field's text is applied before the start). `events/handlers/input.clj` `:input/osc-set-port` validates the port in the handler (invalid → `{}`); Stop Server still dispatches 26's `:trigger/retire`. `commit_field.clj` unchanged.

**Tests**: NEW `laser-show.views.components.osc-settings-test`; NEW `settings-test/typed-port-then-start-listens-on-it-test`, `port-field-takes-only-a-port-test`; NEW `input-test` `osc-set-port-takes-only-a-port`.

**Deviation**: the handler doesn't refuse a port change while the server runs (would break 25's restart-on-another-port path); a programmatic `:input/osc-set-port` while listening changes `:port`, so "Listening on port N" shows the new port before any restart.
