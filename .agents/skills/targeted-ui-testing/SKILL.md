---
name: targeted-ui-testing
description: Use targeted TestFX UI tests first, then run the complete UI and unit suites only as the final regression check.
---

# Targeted UI testing workflow

Use this workflow whenever a change affects JavaFX views, UI event handlers, subscriptions, UI state, CSS, application lifecycle, or an interactive user flow.

## Required order

1. Identify the smallest existing UI test namespace that covers the changed behavior.
2. If the behavior has no regression test, add one before relying on the broader suite.
3. During implementation, run only the affected test namespace:

   ```powershell
   clj -M:test-ui:win -n laser-show.ui-test.<namespace>
   ```

4. While iterating on one test, run only that test var:

   ```powershell
   clj -M:test-ui:win -v laser-show.ui-test.<namespace/<test-var>
   ```

5. Run the related non-UI unit tests for changed handlers, state, subscriptions, or pure view logic.
6. Only after the focused checks pass, run the complete UI suite and the complete unit suite as the final regression check:

   ```powershell
   clj -M:test-ui:win:test-ui-headless
   clj -M:test
   ```

On Linux/CI, run the UI command through the repository's Xvfb wrapper and include the `:linux` alias.

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
- Keep hardware, network discovery, MIDI, OSC, Link, and gamepad services disabled or mocked in UI tests.

## Completion evidence

When reporting validation, state which focused namespace or test var was run during development and whether the final full UI and unit suites passed. If the full suites could not run, say so explicitly instead of presenting focused results as complete validation.
