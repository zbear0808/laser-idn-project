# TestFX UI Integration Tests: Cross-Platform + Headless

**Status:** In progress (Phase 1 done)   **Last verified against:** 36e4dec + working tree
**Supersedes / Superseded by:** none   **Related findings:** 01 F19, 01 M3, 06 F7, 06 F8, 06 M3

## 0. Summary of the revision

The first draft of this plan treated macOS window focus as the main problem and
put headless testing out of scope, because Monocle 21.0.2 does not work with
JavaFX 28. That was based on a wrong assumption: **JavaFX 26+ ships its own
headless glass platform** (`-Dglass.platform=headless`, from Gluon, JDK-8324941).
It needs no Monocle, no Xvfb and no display, and it works the same way on
macOS, Windows and Linux.

Measured on macOS arm64 (JDK 25.0.5, JavaFX 28-ea+11, TestFX 4.0.18):

| Mode | Command | Result |
|---|---|---|
| Visible (native glass) | `clj -M:test-ui:mac-arm` | 2 pass, 7 fail, 13 errors (first draft, §1) |
| Headless, before fixes | `clj -M:test-ui:mac-arm:test-ui-headless` | 16 pass, 3 errors |
| Headless, after Phase 1 | same | **19/19 pass, 55 assertions** |

Most of the visible-mode failures are caused by the OS window manager (focus,
app activation, a real mouse pointer). The headless platform removes those
causes, because it models focus and input inside the JVM. Headless is now the
**default way to run the suite** on every platform. Visible mode is still useful
for watching a test run and for occasional smoke checks of the real platform.

## 1. Current behaviour (file:line)

### Visible mode (native glass, macOS)
Unchanged from the first draft: 19 tests, 7 failures, 13 errors. See §2 for
root causes R1–R9.

### Headless mode before Phase 1
Only the three cue-chain multi-selection tests failed
(`cue_chain_list_test.clj:53,69,86`), all timing out waiting for 2 selected
items, including the **Shift**-click test. A probe confirmed that modifier
flags are always `false` on headless mouse events.

## 2. Root causes

### Headless-specific
- **H1. HeadlessRobot drops modifiers from mouse events.** In
  `com.sun.glass.ui.headless.HeadlessRobot`, `keyPress` records Shift/Ctrl/Cmd/Alt
  in `specialKeys`, but `mousePress`/`mouseRelease` build modifiers only from
  `getModifiers(buttons)`. So Shift/Ctrl/Cmd + click never reaches the scene.
  This is a JavaFX bug worth reporting upstream. Key events do carry
  modifiers, so keyboard shortcuts work.
- **H2. `-Dtestfx.headless=true` breaks the run.** In TestFX 4.0.18 that flag
  makes TestFX load Monocle (`MonoclePlatformFactory`), which fails on
  JavaFX 28. The headless alias must leave it out and set
  `glass.platform=headless` directly.

### Cross-platform (affect visible mode, some affect both)
- **R1. `fill!` uses `WriteRobot`** (`testfx.clj` `fill!`), which needs a window
  with `Window::isFocused`. On macOS a JVM started from a terminal often does
  not get OS activation → `NoSuchElementException`. *Visible-mode only.*
- **R2. `focus!` waits on `Node.isFocused()`**, which also needs window focus.
  *Visible-mode only.*
- **R3. `click!` passes a selector string to `clickOn`.** TestFX resolves it
  against its own target window, not the topmost stage that `query` searches,
  so clicks in secondary stages (timeline editor, trigger editor) can miss.
  *Both modes; works headless today only because of how windows happen to be ordered.*
- **R4. Duplicate `#play-btn` id** (`views/toolbar.clj:19`): Retrigger gets
  `play-btn` too. *Both modes; a latent bug.*
- **R5. Unawaited async assertions** (`main_window_test.clj:29-39`). *Both
  modes; can be flaky on slow machines.*
- **R6. Ctrl vs Cmd.** `list.clj:325,723` uses only `.isShortcutDown`, which is
  Cmd on macOS and Ctrl elsewhere. Tests hard-coded `KeyCode/CONTROL`.
  *Both modes, macOS only.*
- **R7. `#transport-menu` id on the menu's graphic label**, not on the `Menu`
  (`title_bar.clj:256-264`). *Visible-mode failure.*
- **R8. `#preview-zone-label-N` on the wrapper, not the clickable label**
  (`preview.clj:284-295`). *Visible-mode failure.*
- **R9. Tap-tempo clicks with no wait between them** (`toolbar_test.clj:34-39`)
  race the event agent. *Both modes; can be flaky.*

## 3. Phases

### Phase 1: Headless runner (DONE)
- `deps.edn` `:test-ui-headless` now sets
  `-Dglass.platform=headless -Dprism.order=sw -Dtestfx.robot=glass -Djava.awt.headless=true`
  and no longer sets `testfx.headless` or `prism.text=t2k` (fixes H2).
- `testfx.clj`:
  - `headless?`: true when `glass.platform` is `headless`.
  - `shortcut-key-code`: `COMMAND` on macOS, `CONTROL` elsewhere.
  - `modifier-click-node!` / `modifier-click!` accept `KeyCode/SHORTCUT` and
    resolve it per platform (R6, test side). When headless, they fire a
    synthetic PRESSED/RELEASED/CLICKED `MouseEvent` with the modifier flags set
    on the node (works around H1). Visible mode still uses the real robot.
- `cue_chain_list_test.clj`: `KeyCode/CONTROL` → `KeyCode/SHORTCUT`.

### Phase 2: Harden the DSL for both modes
Changes to `testfx.clj`:
- `click!`: resolve with `query` and click the `Node` (like `click-node!`) (R3).
- `fill!`: set text on the FX thread: `requestFocus`, `setText`,
  `positionCaret` (R1). Keep an opt-in `type!` that uses `WriteRobot` for the
  rare test that needs real key events per character.
- `focus!`: wait for `scene.getFocusOwner == node` instead of
  `Node.isFocused` (R2).
- `hover!`: resolve the node first (the same issue as R3).
- `run-on-fx-thread!` timeout: on timeout, include the selector and a list of
  window titles in the `ex-info` data so CI failures can be diagnosed.
- On a test failure, `with-app` should take a screenshot to
  `test-results/<ns>/<test>.png` (`screenshot!` already exists; headless
  snapshots work with `prism.order=sw`). CI already uploads `test-results/`.

### Phase 3: App-side fixes (small, real bugs)
- `toolbar.clj` `transport-button`: ids `play-btn` / `stop-btn` /
  `retrigger-btn` (R4).
- `title_bar.clj`: put `:id "transport-menu"` (and the other menu ids) on the
  `:menu` node (R7).
- `preview.clj`: move `preview-zone-label-N` onto the clickable
  `zone-selector-label` (R8).
- `list.clj:325,723`: `ctrl? (or (.isShortcutDown e) (.isControlDown e))`,
  which matches `cue_chain_editor.clj:643` and the other dialogs (R6, app
  side; Ctrl-click then also works on macOS).

### Phase 4: Test fixes
- `main_window_test`: wrap tab assertions in `ui/wait-for!` (R5).
- `toolbar_test/tap-tempo-button-records-taps`: wait for count `i+1` after
  each click (R9).
- Add `retrigger-button-dispatches-retrigger` to cover the R4 fix.

### Phase 5: CI and docs
- `.github/workflows/build-and-test.yml`: turn the UI step back on, **without
  Xvfb**:
  ```yaml
  - name: Run headless UI tests
    timeout-minutes: 10
    run: clojure -M:linux:test-ui:test-ui-headless
  ```
  Optionally add a `macos-latest` / `windows-latest` matrix job for the UI
  step only (`:mac-arm` / `:win`), since headless needs no display there
  either.
- Update `README.md`, `.github/copilot-instructions.md` and
  `.agents/skills/targeted-ui-testing/SKILL.md`: headless is the default; the
  Xvfb/Monocle notes are no longer needed; use visible mode for watching tests.
- `docs/MACOS_SETUP.md`: note that the keg-only `openjdk@25` must be on
  `PATH` / `JAVA_HOME` before `clj` can run tests.

## 4. Running

```bash
# Default: headless, in the background, any OS (pick the platform alias)
clj -M:test-ui:mac-arm:test-ui-headless
clj -M:test-ui:win:test-ui-headless
clj -M:test-ui:linux:test-ui-headless

# Focused
clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.toolbar-test
clj -M:test-ui:mac-arm:test-ui-headless tap-tempo

# Visible (watch it run; real OS focus/mouse)
clj -M:test-ui:mac-arm tap-tempo
```

## 5. Platform matrix / invariants

| Concern | Headless (all OS) | Visible macOS | Visible Windows | Visible Linux |
|---|---|---|---|---|
| Display needed | No | Yes | Yes | Yes (or Xvfb) |
| Mouse pointer moves | No | Yes | Yes | Yes |
| Window focus | JVM-modelled, reliable | Needs OS activation (R1/R2) | Usually OK | WM-dependent |
| Modifier+click | Synthetic event (H1) | Robot | Robot | Robot |
| Shortcut key | `SHORTCUT` → Cmd/Ctrl by `os.name` | Cmd | Ctrl | Ctrl |
| Rendering | `prism.order=sw` | GPU | GPU | GPU/sw |

Rules for test authors:
- Never hard-code `KeyCode/CONTROL` for a shortcut; use `KeyCode/SHORTCUT`.
- Never pass a selector string straight to TestFX `clickOn`/`moveTo`; resolve the node with `query`.
- Every state assertion after an interaction goes through `wait-for!`.
- Don't rely on pixel colours or font metrics (software rendering differs).

## 6. Tests (all with `with-toolkit` once + `with-app` each)

The 19 existing vars (unchanged list) plus `toolbar-test/retrigger-button-dispatches-retrigger`.
Done means: 20/20 pass headless on macOS and in Linux CI, and visible macOS
passes after Phases 2–4.

## 7. Out of scope / alternatives

- **Monocle**: not needed now that JavaFX has a built-in headless platform.
- **Xvfb**: no longer needed for tests. CI still uses it for the AOT `uber` build step.
- **Forcing macOS app activation (JNA `activateIgnoringOtherApps`)**: rejected as fragile. Headless avoids the problem, and Phase 2 makes visible mode independent of OS focus.
- **Upstream fix for H1**: file an OpenJFX bug ("HeadlessRobot mousePress/Release ignore held modifier keys"). When it is fixed, remove `fire-modified-click!`.
