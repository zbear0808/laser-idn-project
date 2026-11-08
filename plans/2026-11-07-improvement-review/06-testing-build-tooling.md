# Improvement Plan — Testing, Build & Tooling

Area scope: `test/**`, `deps.edn`, `build.clj`, `.github/**`, `dev/**`, `src/laser_show/dev/**`,
`src/laser_show/dev_config.clj`, `src/laser_show/experiments/**`, `legacy/`, `.clj-kondo/`,
`linux-install.sh`, `QUICKSTART.md` (plus the README/docs sections that describe them).

Review date: 2026-11-07. Read-only review; nothing in the repo was changed except this file.

---

## 1. Summary

The project has a reasonable unit suite: 59 test namespaces, about 13k lines. Pure logic is covered
well: IDN packets and fragmentation, modulation, keyframes, list helpers, and most event handlers.
There is also a recent TestFX smoke suite (`test/laser_show/ui_test/e2e_test.clj`) that runs in CI
under Xvfb. The test *infrastructure* is weaker than the tests themselves. The `:test` and
`:test-all` aliases pick up the TestFX namespaces without having TestFX on the classpath. CI runs
the same suites two or three times. The local commands in the README and in the targeted-ui-testing
convention run tests against a different JavaFX version (22-ea) than the app ships (28-ea). Some
dev-only tooling (`laser-show.dev.reload`, `laser-show.css.reload`) can be reached from the
production Help menu but needs a dependency that is only on the `:dev` classpath. There is also a
lot of tooling debris: an older LLM-vision UI harness that duplicates the TestFX DSL, a
`laser-show.experiments.*` namespace that runs code when it loads, a vendored Clojure installer,
and invalid aliases in `deps.edn`.

---

## 2. Findings

Severity: **High** = broken today or ships a user-visible failure. **Med** = correctness or
flakiness risk, or a convention violation. **Low** = hygiene or developer experience.

### F1. `:test` / `:test-all` load TestFX namespaces without TestFX on the classpath — **High**
- `deps.edn:122-132`: `:test` runs `-d test/laser_show`, which recursively includes
  `test/laser_show/ui_test/e2e_test.clj` and `ui_test/cases/example_test.clj`. Both namespace names
  match cognitect test-runner's default regex `.*-test$`. The runner `require`s every matching
  namespace before running anything (`cognitect/test_runner.clj` → `(dorun (map require nses))`).
- `e2e_test.clj` → `ui_test/testfx.clj:17-18` imports `org.testfx.api.*`. `example_test.clj` →
  `llm_agent.clj:5` requires `clojure.data.json`. Neither library is in `:test` or `:test-all`. I
  checked with `clojure -Spath -A:test-all`: no testfx and no data.json.
- `:test-all` (`deps.edn:144-152`) has no `-d`, so it scans all of `test/` and has the same problem.
- `-d "test/idn_hello"` (`deps.edn:130`) points at a directory that does not exist (stale).
- Impact: `clj -M:<platform>:test` should fail with `ClassNotFoundException org.testfx...` before
  any unit test runs. That breaks CI steps `build-and-test.yml:44` and `:50`, and the final
  regression step of the targeted-ui-testing convention. **Verify first** with one
  `clj -M:win:test` run, since I did not run the suite. If it really does pass, find out why (for
  example, a transitive dependency I missed).

### F2. Help-menu "Reload Styles/App Code" fails in production builds — **High**
- `src/laser_show/css/reload.clj:15` and `src/laser_show/dev/reload.clj:12-14` require
  `clojure.tools.namespace.*`. That library is only declared in `:dev` (`deps.edn:74`). It is not
  on the default classpath (checked with `clojure -Spath`), so it is not in the uberjar.
- Both namespaces also scan `(io/file "src")` (`css/reload.clj:30`, `dev/reload.clj:79`). That
  directory does not exist next to an installed jar.
- `views/components/title_bar.clj:214-219` shows both menu items unconditionally.
  `events/handlers/menu.clj:257-277` does `require` + reload *inside an event handler*, with no
  `try`. Exceptions go to the agent error handler, and the user gets no feedback.
- Impact: clicking these items in a jpackage build throws. This also breaks the
  "pure handlers" rule in `docs/CLJFX_STATE_MANAGEMENT.md`. Coordinate with the events/state and
  views/UI areas.

### F3. App-code reload also reloads experiments, dev tooling, and the app entry point — **Med**
- `dev/reload.clj:70-83` reloads every `laser-show.*` namespace under `src` except
  `laser-show.state.*`. That includes:
  - `laser-show.experiments.check-max-chroma`, which has a top-level side effect
    `(check-max-chroma 0.70)` at `experiments/check_max_chroma.clj:26` that prints 360-hue
    analysis on every load.
  - `laser-show.experiments.dnd-repro`, which builds a cljfx renderer at load time
    (`dnd_repro.clj:156-159`).
  - `laser-show.dev.reload` itself (reloaded while it is running) and `laser-show.app`.
- `build.clj:79` copies all of `src/`, including experiments, into the uberjar.
- Impact: noisy or surprising reloads, and dead code shipped to users.

### F4. CI runs suites redundantly and has no guard rails — **Med**
- `build-and-test.yml:43-50` runs `:test`, then `:test-integration`, then `:test-all`.
  `:test-all` is the union of the first two plus the UI tests (without TestFX deps, see F1), so the
  unit tests run twice.
- No `timeout-minutes`. A hung JavaFX robot or `Platform/runLater` would hang the job until
  GitHub's 6-hour limit. `testfx.clj:53` times out after 10 s but leaves the FX job queued.
- No `permissions:` block on any workflow. `ai-code-review.yml` gives the default `GITHUB_TOKEN` to
  a third-party action pinned by tag (`@v1.0`), not by SHA.
- No clj-kondo step, no JUnit/XML test report, no upload of `test-results/` or screenshots when a
  UI test fails, and no `concurrency` group to cancel superseded runs.
- The uberjar build step (`build-and-test.yml:60-61`) runs without a display, but `release.yml:74-78`
  starts Xvfb before the same `uber` command. One of the two is wrong.

### F5. JavaFX version skew between local tests, CI, and the shipped app — **Med**
- cljfx 1.10.6 pulls in `org.openjfx/javafx-* 22-ea+16` transitively. Without a platform alias the
  test classpath uses JavaFX **22-ea** (checked with `clojure -Spath -A:test`). With `:win`, the
  versions are mixed: 28-ea+11 jars plus a leftover `javafx-web-22-ea+16.jar`.
- `README.md:59`, `.github/copilot-instructions.md:82,111`, and the targeted-ui-testing skill
  (`clj -M:test`) all tell contributors to run unit tests without a platform alias. They are
  testing against a different JavaFX from the one CI (`:linux`) and the app (`28-ea+11`) use.
- All platform aliases (`deps.edn:88-120`) pin **early-access** `28-ea+11`, and release builds ship
  it. That is an intentional choice (`QUICKSTART.md:7`), but nothing in the build documents or
  checks it.

### F6. Several e2e tests don't test what their names claim — **Med**
- `e2e_test.clj:250-289` `grouped-list-drag-and-drop-reorders-items`: dispatches the drop events,
  then reorders the state *by hand* with `state/assoc-in-state!` (`:283-287`), and only asserts
  `(= 3 (count items))`. The test passes even if `:list/perform-drop` does nothing.
- `:239-248` `grouped-list-can-group-collapse-and-delete-selection`: clicks "group" and only checks
  that delete is enabled. It does not assert grouping, collapse, or deletion.
- `:30-35` `user-can-type-and-switch-tabs`: no assertion after clicking `#settings-tab`.
  `:150-155` menu test: pressing ESC is never verified. `:202-203`: clicking the canvas, then
  asserting the canvas exists.
- `:8-28` the ns `:require` block indentation is broken (cosmetic).

### F7. TestFX DSL inconsistencies cause flakiness and confusing failures — **Med**
`test/laser_show/ui_test/testfx.clj`:
- `query` (`:59-68`) searches only the *topmost showing window*, but `query-all` (`:70-82`) searches
  all windows. `click!`, `fill!`, and `select!` wait on `query`, so a main-window selector cannot
  be clicked while a dialog is open, even though `visible?` (which uses `query-all`) says it is
  there.
- `assert-no-node!` (`:343-344`) doesn't wait, unlike every other `assert-*`, so it races cljfx
  re-renders.
- `check!`, `uncheck!`, `slide!`, `submit!`, and `hover!` (`:255-304`) don't `wait-for!` the node.
  A missing node becomes an NPE inside `.interact` instead of a readable "node not found" error.
- `visible?` (`:94-98`) checks only the node's own `visible`/`managed` flags, not its ancestors or
  its window.
- Every `query` and `query-all` call runs `sync-fx!`, and `wait-for!` runs it on every poll
  (50 ms). That works, but it is slow as the suite grows.
- `wait-for!` throws `ex-info` with only `{:timeout-ms}`. Failures don't say *which* condition or
  selector timed out, and no screenshot is captured, although `screenshot!` exists (`:346-358`).

### F8. Legacy LLM-vision UI harness duplicates TestFX and breaks UI-test rules — **Med**
- `ui_test/driver.clj` re-implements `run-on-fx-thread!`, node lookup, clicking (synthetic
  `MouseEvent`s), and screenshots, all of which `testfx.clj` already provides. It uses fixed
  `Thread/sleep 1000` waits (`driver.clj:128-132`).
- `ui_test/runner.clj:15` boots with `app/start!`. That starts Carabiner/Link, an IDN network scan,
  gamepad polling, and the frame service (`app.clj:111-164`). `app/stop!` (`app.clj:166-178`) stops
  none of them except the input mapper. This breaks the rule "keep hardware, network discovery,
  MIDI, OSC, Link, and gamepad services disabled" (copilot-instructions.md:117-118, skill).
- `ui_test/cases/example_test.clj` is a single `(comment ...)` block. It refers to
  `#effect-editor-btn` and `#bpm-slider`, which no longer exist anywhere in `src/` (grep finds
  nothing).
- `ui_test/llm_agent.clj`: the Gemini API key is sent in the URL query string (`:106-107`) and can
  leak into logs or proxies. The HTTP status is never checked (`:95-96`). `io/input-stream` is
  never closed (`:59`). Its only dependency need is `data.json` in `:test-ui`.

### F9. Wall-clock timing tests will flake on shared CI runners — **Med**
- `test/laser_show/common/timing_test.clj:34-52` and `:121-146` assert accuracy under 1 ms for
  `precise-sleep-until`/`-nanos`, and `max-deviation < 1ms` over 10 frames. GC pauses or a noisy
  neighbour on `ubuntu-latest` can break these.
- `:152` `^:performance sleep-comparison-test` contains only `println`. No alias excludes
  `:performance`, even though the runner supports `-e :performance`.

### F10. Coverage gaps by area — **Med**
Namespaces in `src/` with no test that `require`s them (computed from the test `:require` forms;
line counts in parentheses):

| Area | Untested namespaces |
|---|---|
| events/state | `subs` (640), `events.handlers.effect-params` (151), `.chain.params` (140), `.chain.core` (73), `.chain.helpers` (38), `.connection` (91), `.input-mapper` (71), `.files` (32), `events.helpers` (89), `state.extractors` (158), `state.templates` (57) |
| animation/effects | `animation.presets` (133), `modulators` (224), `modulator-registry` (191), `modulator-defs`, `time` (201), `effects.shape` (235), `effects.curves` (158), `effects.calibration` (88), `modulation-diagnostics` |
| input/triggers | `input.midi` (263), `input.osc` (314), `input.gamepad`. The `triggers/*_test.clj` files are each one 7–14 line smoke test. |
| output pipeline | `backend/streaming_engine_test.clj` (33 lines) only tests construction. There is no loopback test of the send loop, frame pacing, or stop/start. |
| views/UI | 50+ view namespaces. Only `status-bar`, `preview`, and `track-logic` have pure tests, and only the e2e smoke suite reaches the rest. |
| tooling | `css.reload`, `dev.reload`, `profiling.*` |

`subs.clj` is the most important gap. It is the memoised subscription layer that every view reads,
and `docs/STALE_CLOSURES.md` describes bugs that come from exactly this layer.

### F11. Test hygiene issues — **Low**
- Debug `println` noise in `animation/effects_test.clj:127-130, 177-313` and
  `services/reproduction_test.clj:73-111`. `reproduction_test.clj:95` has its key assertion
  commented out, and `:82` hard-codes a star-start index of 195.
- `backend/streaming_engine_test.clj:7` says "Behavioral Tests (not testing internal structure)"
  but asserts on `(:target-host engine)` (`:24, :31`).
- `triggers/shuffle_bag_test.clj:6-9` rebinds `b` (shadowing) and refers `testing` without using it.
- `test/integration/keyframe_integration_test.clj` is a pure unit test (no I/O), yet it lives in
  `integration/`. `backend/idn_stream_test.clj` tests `laser-show.idn.stream` but sits under
  `backend/`, not `idn/`.
- `integration/idn_hello_test.clj` sends real UDP packets to fixed ports 7255/7256 and mutates the
  global DAC registry, with no fixture to restore it.

### F12. `build.clj` inconsistencies — **Low**
- `version "0.1.0"` (`:6`) versus jpackage `--app-version "1.0.0"` (`release.yml:184`), with no
  shared source of truth. There is no git-sha or build-date stamp either.
- The `uber` docstring says "JavaFX 26" (`:61`). The JFR javac call is duplicated in `compile-java`
  (`:38-41`) and `uber` (`:74-77`). It uses `--release 17` although JDK 25 is required.
- `copy-dir ["src" "resources"]` (`:79`) ships experiments and dev namespaces (see F3).

### F13. Release packaging problems — **Med**
- `release.yml:179` `jpackage --input target` packages the *entire* `target/` directory. That
  includes `target/classes/`: thousands of AOT `.class` files and copied sources that sit next to
  the uberjar inside every installer.
- The "Upload Release Assets" job is commented out (`release.yml:220-292`), so a GitHub Release
  ends with no binaries attached. Artifacts expire after 7 or 30 days. The native-image job is a
  stub comment (`:91-96`).
- No smoke test of the built jar, such as `java -cp jar clojure.main -e "(require 'laser-show.app)"`.
  That check would catch F2-type classpath gaps.

### F14. `deps.edn` hygiene — **Low**
- `:java-src-paths` (`:4`) is not a tools.deps key, so it is silently ignored and misleading.
- `:repl-mcp` uses `:git/sha "latest-sha"` (`:203`). That is not a SHA, so the alias cannot
  resolve and tools that resolve all aliases may error.
- `clj-async-profiler` sits in the main `:deps` (`:28`), so it ships in the uberjar with its native
  agents. Only `dev/user.clj` reaches `laser-show.profiling.async-profiler`, via `resolve`.
- `app.clj:17` requires `taoensso.timbre` directly, but timbre is only a transitive dependency of
  beat-carabiner. It should be declared.
- The JavaFX `-D`/`--enable-native-access` JVM flags are repeated in 6 aliases. The test-runner
  coordinate is repeated in 4.
- `"classes"` on `:paths` (`:1`) depends on a manual `clj -T:build compile-java`. `jfr_profiler.clj`
  uses `Class/forName` and tolerates the missing classes, but nothing tells the developer.

### F15. clj-kondo config is permissive and never runs in CI — **Low**
- `.clj-kondo/config.edn:2-5` turns off `unused-import`, `unused-binding`, and
  `unused-private-var`. `unused-namespace` is commented out with `#_#_`, so it stays at its
  default. `:clojure-lsp/unused-public-var` excludes all of `idn.hello` and `idn.stream`.
- The `defstate` hook (`.clj-kondo/hooks/laser_show/state/core.clj`) is correct and matches
  `state/core.clj:199`.
- No workflow runs `clj-kondo --lint src test dev`, so the kondo `:error` settings
  (`unresolved-var`, `unresolved-namespace`) are never enforced.

### F16. Documentation drift for build and test commands — **Low**
- `README.md:59` and `copilot-instructions.md:82` use `clj -M:test` without a platform alias (F5).
- `docs/LINUX_SETUP.md:36` says "JDK 25 for JavaFX 26-ea". `:96` uses `clojure -M:test:linux`
  (fine). `:99` uses `clojure -T:build:linux uber`, which is wrong because the `:platform` argument
  is required (`build.clj:63-64`).
- `QUICKSTART.md:27-33,103-105` leaves out `:mac-arm`, even though it exists in `deps.edn:106` and
  `release.yml:26`.
- `README.md:99` says "Test Suite is pretty bad" without saying what is missing.

### F17. Repository debris and vendored code — **Low**
- `linux-install.sh` (48 lines) is a verbatim copy of the Clojure CLI installer pinned to
  1.12.4.1582. `docs/LINUX_SETUP.md:57` already curls the upstream script.
- `dev/cljfx/dev/help.clj` (540 lines) is an `(in-ns 'cljfx.dev)` override of cljfx.dev internals,
  tied to `io.github.cljfx/dev 1.10.6.42` (`deps.edn:76`). It has no comment explaining why it
  exists, and it will drift on upgrade.
- `.gitignore` ignores `/plans`, so every plan, including this review, is local-only and
  unversioned.
- `/legacy/` (ignored) is a local dump of about 12k `.class` files (an AppCDS/AOT leftover). It is
  harmless for git, but it confuses anyone browsing the workspace and the dev reload scanners (it is
  not under `src`, so it is only confusing).

### F18. `dev/event_audit.clj` is useful but fragile and manual — **Low**
- `extract-case-keywords` (`:54-74`) uses `re-find`, so it only reads the *first*
  `(case type|event/type ...)` block per file, and it depends on the block ending with `{}` on its
  own line. `menu.clj` has 7 `case` forms and `grid.clj` has 3, so missing or unused handler
  reports can be wrong.
- It only runs by hand at the REPL. A cheap automated check would catch typos in dispatched event
  keywords, which are a common bug in keyword-dispatched cljfx apps.

---

## 3. Proposed improvements

### Quick wins (S effort each)

**Q1. Fix test aliases so unit, integration, and UI runs are separate** (F1)
- Change `:test` to `-d test/laser_show` plus `-e :ui`, *or* change the namespace regex to exclude
  `laser-show.ui-test.*`, e.g. `-r "^(?!laser-show\.ui-test\.).*-test$"`. The regex approach needs
  no metadata changes, so it is the better choice.
- Remove `-d test/idn_hello`.
- Change `:test-all` to `-d test/laser_show -d test/integration` with the same exclusion.
- Files: `deps.edn`. Risk: low. Verify with `clj -M:win:test` and `clj -M:win:test-all`.

**Q2. Remove CI duplication and add guard rails** (F4)
- Delete the `Run all tests` step, or keep only `:test-all` and drop the separate unit and
  integration steps.
- Add `timeout-minutes: 30` to the job and `timeout-minutes: 10` to the UI step.
- Add top-level `permissions: contents: read`. For `ai-code-review.yml`, add
  `pull-requests: write` and pin the action to a commit SHA.
- Add `concurrency: { group: ${{ github.ref }}, cancel-in-progress: true }`.
- Add `if: failure()` → `actions/upload-artifact` for `test-results/`.
- Files: `.github/workflows/*.yml`. Risk: low.

**Q3. Use the platform alias in every documented test command** (F5, F16)
- `clj -M:win:test` in README, copilot-instructions, the targeted-ui-testing skill text, and
  LINUX_SETUP. Fix the `:platform` argument in LINUX_SETUP and the "JavaFX 26" mentions. Add
  `:mac-arm` to QUICKSTART.
- Optionally exclude the transitive cljfx JavaFX in the base deps:
  `cljfx/cljfx {:mvn/version "1.10.6" :exclusions [org.openjfx/javafx-base ...]}`. Then running
  without a platform alias fails fast instead of silently using 22-ea.
- Files: `README.md`, `.github/copilot-instructions.md`, `.agents/skills/targeted-ui-testing/*`,
  `docs/LINUX_SETUP.md`, `QUICKSTART.md`, `build.clj:61`, optionally `deps.edn`.
  Risk: low (docs). The exclusions are medium risk; verify `clj -M:win:laser-show`.

**Q4. `deps.edn` hygiene** (F14)
- Delete `:java-src-paths`.
- Fix the `:repl-mcp` SHA or delete the alias.
- Move `clj-async-profiler` to `:dev`.
- Declare `com.taoensso/timbre` explicitly.
- Files: `deps.edn`. Risk: low. Check that the `uber` build still compiles; `async_profiler.clj`
  is not reached from `laser-show.app`.

**Q5. Quarantine the flaky timing tests** (F9)
- Tag `precise-sleep-until-accuracy-test`, `precise-sleep-nanos-test`, `precise-timing-loop-test`,
  and `sleep-comparison-test` as `^:timing`/`^:performance`. Exclude them from `:test` with
  `-e :performance -e :timing`. Loosen the remaining bounds to "≥ requested" checks only.
- Files: `test/laser_show/common/timing_test.clj`, `deps.edn`. Risk: low.

**Q6. Test hygiene sweep** (F11)
- Remove the `println` calls from `effects_test` and `reproduction_test`.
- Restore or delete the commented-out assertion at `reproduction_test.clj:95`. Derive index 195
  from the generator output instead of hard-coding it.
- Move `keyframe_integration_test` to `test/laser_show/animation/` and `idn_stream_test` to
  `test/laser_show/idn/`.
- Add a fixture that clears the DAC registry in `integration/idn_hello_test.clj`, and bind to
  port 0 (ephemeral) receivers.
- Files: the listed tests. Risk: low.

**Q7. Make the e2e tests assert real behaviour** (F6)
- Drag-drop test: delete the manual `assoc-in-state!` (`e2e_test.clj:283-287`) and assert
  `(= [second-id last-id first-id] (mapv :id items))` after `:list/perform-drop`. If that fails, it
  has found a real bug; hand it to the events/state area.
- Group test: assert that a `:group` item now exists, then collapse and delete and assert each
  step.
- Add assertions to the tab, menu-ESC, and canvas tests, or rename them to "smoke".
- Follow the targeted-ui-testing convention while doing this: run
  `clj -M:test-ui:win -v laser-show.ui-test.e2e-test/<var>` for each test var, and the full UI
  suite last.
- Files: `test/laser_show/ui_test/e2e_test.clj`. Risk: low. This may surface real bugs, which is
  the point.

**Q8. Remove repository debris** (F17)
- Delete `linux-install.sh` and point to the upstream installer as LINUX_SETUP already does.
- Add a header comment to `dev/cljfx/dev/help.clj` explaining why it overrides upstream, or delete
  it if upstream now has the same `defmethod`s.
- Decide whether `/plans` should be versioned. Remove it from `.gitignore` if these plans are meant
  to be shared.
- Files: `linux-install.sh`, `dev/cljfx/dev/help.clj`, `.gitignore`. Risk: low.

### Medium refactors (M effort)

**M1. Gate or fix the dev reload tooling** (F2, F3)
1. Move `laser-show.dev.reload` and `laser-show.css.reload` out of `src/` into `dev/`, *or* keep
   them in `src` but add `org.clojure/tools.namespace` to the main deps. Moving them to `dev/` is
   preferred because it keeps tools.namespace out of production.
2. In `title_bar.clj:214-219`, render the "Reload Styles" and "Reload App Code" items only when
   `(dev-config/dev-mode?)`. The value should come through a subscription or a state flag, not a
   direct call in the view, to stay pure (see the views/UI area).
3. In `menu.clj:257-277`, return an effect such as `{:dev/reload :styles}` instead of doing
   `require` + reload inside the handler. Register the effect in the app's effects map, and catch
   and report failures to the status bar.
4. In `dev/reload.clj:22-25`, add `laser-show.experiments`, `laser-show.dev`, and `laser-show.app`
   to `excluded-prefixes`.
- Files: `src/laser_show/dev/reload.clj`, `src/laser_show/css/reload.clj`, `events/handlers/menu.clj`,
  `views/components/title_bar.clj`, `deps.edn`, `dev/user.clj` (already uses `resolve`).
- Effort M, risk medium (touches events and views; coordinate). Add a unit test asserting that
  `:help/reload-app` returns an effect, not a side effect.

**M2. Retire or relocate experiments** (F3)
- Move `src/laser_show/experiments/*` to `dev/experiments/`, or delete them. Wrap
  `check-max-chroma`'s top-level call in `(comment ...)`. If `dnd_repro` documents a JavaFX DnD bug
  that still matters, convert it into a TestFX regression test (see `e2e_test.clj:262-263`, which
  avoids real DnD for this reason).
- Files: `src/laser_show/experiments/**`. Effort S–M, risk low.

**M3. Consolidate the UI-test harness on TestFX** (F7, F8)
1. In `testfx.clj`:
   - Make `query` search all showing windows: the focused window first, then the others, like
     `driver/find-node*` (`driver.clj:54-67`).
   - Make `assert-no-node!` wait.
   - Make every action helper `wait-for!` its node first.
   - Have `visible?` check `.isVisible` on ancestors and `.getScene`/`.getWindow`.
   - Accept a `:desc` in `wait-for!` and include the selector in the ex-data.
2. Add an `:each` fixture wrapper, `ui/with-failure-screenshot`, that calls `ui/screenshot!` into
   `test-results/<test-var>.png` when a test fails. CI uploads it (Q2).
3. Delete `driver.clj`, `runner.clj`, and `cases/example_test.clj`. If LLM visual checks are still
   wanted, keep `llm_agent.clj` behind its own `:test-ui-llm` alias that uses
   `testfx/screenshot!` + `app/start-test!`. Move the API key to the `x-goog-api-key` header, check
   the status code, and use `with-open` for the input stream.
4. Update `deps.edn :test-ui` to drop `data.json` if the LLM agent is removed.
- Files: `test/laser_show/ui_test/**`, `deps.edn`. Effort M, risk low–medium (existing e2e tests
  must still pass; run them one var at a time per the convention).

**M4. Subscription and handler unit coverage** (F10)
- Add `test/laser_show/subs_test.clj`. Build a context with
  `(fx/create-context state cache/lru-cache-factory)` from `domains/build-initial-state` plus
  fixtures, and assert the derived values of the heavier subs (the grid cell view-models, the
  active-cue and preview subs, and the stylesheet-urls sub that `dev/user.clj:148-150` mentions).
- Add handler tests for `effect_params`, `chain/params`, `chain/core`, `connection` (the pure part),
  and `input_mapper` (mapping table logic). Follow the pattern of the existing
  `events/*_handlers_test.clj` files: pure `{:state ...}` in, `{:state ...}` out.
- Add `state/extractors_test.clj` and `state/templates_test.clj`. The templates feed
  `start-test!`, so a broken template breaks every UI test.
- Files: new tests only. Effort M, risk none. Coordinate with the events/state area on which subs
  are most at risk.

**M5. Output-pipeline loopback test** (F10)
- Start a `DatagramSocket` on `127.0.0.1:0` and point `streaming-engine/create-engine` at it with a
  fixed frame provider. `start!`, receive N packets, assert the IDN headers and sequence numbers
  increase, then `stop!` and assert that the thread terminates and the socket closes. Tag it
  `^:integration` and put it in `test/integration/`.
- Files: `test/integration/streaming_loopback_test.clj`. Effort M, risk low (ephemeral port, local
  only). Coordinate with the output pipeline area.

**M6. Turn the event audit into a CI test** (F18)
- Replace the regex scan with a check against the real dispatch table. If `events/handlers.clj`
  dispatches by `case`, expose a set of handled `:event/type` values, or move it to a
  `defmulti`/map so `keys` gives the set. Assert that every `:event/type` literal found in `src/`
  views (via kondo analysis or the existing regex as a fallback) is in that set.
- Files: `dev/event_audit.clj` → `test/laser_show/events/dispatch_coverage_test.clj`, maybe a small
  change in `events/handlers.clj`. Effort M, risk low–medium (depends on the events/state area).

**M7. Lint in CI** (F15)
- Add a `lint` job: `clojure -M:kondo --lint src test dev --fail-level error` with a new
  `:kondo {:extra-deps {clj-kondo/clj-kondo {...}} :main-opts ["-m" "clj-kondo.main"]}` alias.
- Re-enable `unused-binding` and `unused-private-var` at `:warning` once the first pass is clean.
  Replace the namespace-wide `unused-public-var` excludes with `^:export`/`#_:clj-kondo/ignore` on
  the specific protocol constants.
- Files: `deps.edn`, `.clj-kondo/config.edn`, `.github/workflows/build-and-test.yml`. Effort S–M,
  risk low (start at `--fail-level error`).

### Larger efforts (L effort)

**L1. Release pipeline that produces usable, verified artifacts** (F12, F13)
1. Keep one version source: read `version` from a `VERSION` file or `git describe` in `build.clj`.
   Pass it to jpackage via `--app-version` (jpackage needs numeric `x.y.z`) and write it into the
   jar manifest.
2. Stage only the uberjar: `mkdir pkg && cp target/*-standalone.jar pkg/` and
   `jpackage --input pkg`.
3. Add a jar smoke step on each OS:
   `java -Djava.awt.headless=true -cp target/*.jar clojure.main -e "(require 'laser-show.app 'laser-show.events.handlers.menu) (println :ok)"`.
   Also run the same check under Xvfb, using `(laser-show.app/start-test!)` then exit. This catches
   missing deps like F2 and missing natives.
4. Resolve the Xvfb question for `uber` (F4): keep it in both workflows or remove it from both.
5. Re-enable `upload-release-assets` using `softprops/action-gh-release` pinned by SHA, with
   `permissions: contents: write` on that job only.
6. Deduplicate the javac call in `build.clj` (call `compile-java` with a target dir) and set
   `--release 25` to match the required JDK.
- Files: `build.clj`, `.github/workflows/release.yml`, `.github/workflows/build-and-test.yml`.
  Effort L, risk medium (changes release behaviour; validate with `workflow_dispatch`).

**L2. `deps.edn` alias restructuring** (F14)
- Introduce `:jfx-opts` (shared JavaFX JVM flags) and `:test-base` (test path, test-runner, shared
  opts). Compose them as `:test`, `:test-integration`, and `:test-ui` (only `:main-opts` and extra
  deps differ). For example, `clj -M:win:test-base:test` or keep the single-alias UX by merging
  through `:aliases` composition.
- Document the alias matrix in one place (README "Testing" table) and delete the duplicated
  instructions in copilot-instructions and the skill, linking to it instead.
- Files: `deps.edn`, `README.md`, `.github/copilot-instructions.md`, skill file. Effort M–L, risk
  medium (every documented command changes; update CI in the same PR).

**L3. Coverage for input and animation registries** (F10)
- Test `input.midi` and `input.osc` message → event translation by extracting the pure parse
  functions if needed (input/triggers area).
- Test `animation.presets` and `modulator-registry`. Use a property-style test: every registered
  preset generates a valid frame (finite x/y, colours in [0,1]) for default params, and every
  modulator def evaluates without throwing across its param ranges. This is a cheap, high-value
  guard against regressions when new presets or modulators are added.
- Add `clojure.test.check` to `:test` for the property tests.
- Effort L (spread over areas), risk low.

---

## 4. Suggested order of work and dependencies

1. **Q1 → Q3 → Q2** (same PR or back-to-back). Make `clj -M:<platform>:test` provably green and
   stop the duplicated CI runs. Every other area's plan relies on "run the unit suite" working, so
   this goes first. Verify F1 by running `clj -M:win:test` once before changing anything.
2. **Q5, Q6, Q4, Q8.** Low-risk cleanup that reduces noise and flakiness before more tests are
   added.
3. **M3 then Q7.** Fix the TestFX DSL first (query semantics, waits, failure screenshots), then
   tighten the e2e assertions. Q7 may expose real list or drag-drop bugs: **depends on and feeds
   the views/UI and events/state areas**.
4. **M1 + M2.** Gate the dev reload and remove experiments. **Coordinate with events/state** (the
   handler-to-effect change in `menu.clj`) **and views/UI** (conditional menu items in
   `title_bar.clj`).
5. **M7** (lint in CI) once Q1 is green. Start at error level so other areas' PRs are not blocked.
6. **M4, M5, M6.** New coverage:
   - M4 (subs and handlers) **pairs with events/state**.
   - M5 (loopback) **pairs with output pipeline (IDN/backend/routing)**. Agree on the
     `start!`/`stop!` contract of `streaming_engine` first.
   - M6 needs a queryable handler set from **events/state**.
7. **L1** (release pipeline) after Q4/M1, so the jar smoke test passes without the reload
   workaround.
8. **L2 and L3** last. L2 changes every documented command, so do it once the other areas have
   finished their test additions. L3 is spread across **animation/effects** and
   **input/triggers**.

Cross-area notes:
- **views/UI**: needs stable `:id`s for any new TestFX tests (M3/Q7). The dev-mode menu gating
  (M1) needs a subscription rather than a direct `dev-config` call in the view.
- **events/state**: `menu.clj` side effects (M1); a handler dispatch set for M6; `subs` test
  fixtures (M4); and whatever bug Q7's honest drag-drop assertion uncovers.
- **output pipeline**: the M5 loopback test, and `app/stop!` not stopping the frame service, Link,
  or gamepad (`app.clj:166-178`). The latter matters for any test or REPL flow that uses
  `app/start!`.
- **animation/effects** and **input/triggers**: the L3 registry and parser property tests. Their
  plans should list which pure functions they extract so the tests can target them.


---

## Carried over from old plans (2026-11-08 audit, see 07-old-plans-audit.md)
- **Help > Check for Updates is a no-op** (`events/handlers/menu.clj:229-234`). Implement it against GitHub Releases once releases are published, or remove the menu item.
