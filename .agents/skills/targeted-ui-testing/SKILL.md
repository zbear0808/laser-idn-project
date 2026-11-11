---
name: targeted-ui-testing
description: Use targeted TestFX UI tests first, then run the complete UI and unit suites only as the final regression check.
---

# Targeted UI testing workflow

Use this workflow whenever a change affects JavaFX views, UI event handlers, subscriptions, UI state, CSS, application lifecycle, or an interactive user flow.

## Required order

1. Identify the smallest existing UI test namespace that covers the changed behavior.
2. If the behavior has no regression test, add one before relying on the broader suite.
3. During implementation, run only the affected feature namespace (`clj -M:test-ui:win:test-ui-headless --list` shows every UI test grouped by namespace):

   ```powershell
   clj -M:test-ui:win:test-ui-headless -n laser-show.ui-test.<feature>-test
   ```

4. While iterating on one test, run it by part of its name (any substring of `namespace/test-name`, ignoring case; several patterns are OR'ed):

   ```powershell
   clj -M:test-ui:win:test-ui-headless <part-of-test-name>
   ```

   A pattern that matches nothing exits non-zero, so a typo can't pass as "Ran 0 tests".

5. Run the related non-UI unit tests for changed handlers, state, subscriptions, or pure view logic:

   ```powershell
   clj -M:win:test <part-of-test-name>
   ```

6. Only after the focused checks pass, run the complete UI suite and the complete unit suite as the final regression check:

   ```powershell
   clj -M:test-ui:win:test-ui-headless
   clj -M:win:test
   ```

Always include a platform alias (`:win`, `:mac`, `:mac-arm` or `:linux`); without one the tests run against a different JavaFX version than the app ships. Keep `:test-ui-headless` on for UI runs: it uses JavaFX's built-in headless platform, so tests run in the background with no display, no Xvfb and no mouse movement on Windows, macOS and Linux. Drop it only to watch a test in real windows (on macOS that needs Accessibility permission for the terminal/IDE).

## Selection guidance

- Prefer a single test var over a namespace when debugging one workflow.
- Prefer a namespace when several tests cover the same feature area.
- Do not run the complete TestFX suite after every edit unless the change is cross-cutting or the focused test cannot isolate the affected behavior.
- Do not treat a focused pass as sufficient completion; the full UI and unit suites remain required before declaring the change ready.

## Test design guidance

- Keep TestFX coverage for real scene-graph behavior: user input, focus, windows, dialogs, visibility, and JavaFX interaction.
- Put pure event-handler, state-transition, selector, and view-model behavior in ordinary unit tests so it can run without JavaFX.
- Give new interactive controls stable JavaFX `:id` values.
- Use the shared helpers in `laser-show.ui-test.testfx`; do not cache JavaFX nodes across renders.
- Put new UI tests in the feature namespace they belong to (or a new `laser-show.ui-test.<feature>-test`), with `(use-fixtures :once fixtures/with-toolkit)` and `(use-fixtures :each fixtures/with-app)` from `laser-show.ui-test.fixtures`, so each test mounts a fresh app and can run on its own.
- Use `KeyCode/SHORTCUT` (Cmd on macOS, Ctrl elsewhere) for shortcut-modifier clicks; never hard-code `KeyCode/CONTROL`.
- Wrap every state assertion that follows an interaction in `ui/wait-for!`; events are processed asynchronously.
- Failure screenshots are written to `test-results/<ns>/<test>.png`.
- Keep hardware, network discovery, MIDI, OSC, Link, and gamepad services disabled or mocked in UI tests.

## Completion evidence

When reporting validation, state which focused namespace or test var was run during development and whether the final full UI and unit suites passed. If the full suites could not run, say so explicitly instead of presenting focused results as complete validation.
